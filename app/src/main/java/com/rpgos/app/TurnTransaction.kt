package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

enum class TurnTransactionState { PROPOSED, VALIDATED, IN_PROGRESS, COMMITTED, ROLLED_BACK }
data class TurnTransactionIdentity(val campaignUid:String,val turnUid:String,val commandUid:String,val transactionUid:String){init{require(campaignUid.isNotBlank());require(turnUid.isNotBlank());require(commandUid.isNotBlank());require(transactionUid.isNotBlank())}}
enum class TurnFailurePoint{BEFORE_FIRST_WRITE,AFTER_FIRST_WRITE,AFTER_SECOND_DOMAIN_WRITE,BEFORE_EVENT_APPEND,AFTER_EVENT_APPEND,BEFORE_CAUSAL_APPEND,AFTER_CAUSAL_APPEND,BEFORE_COMMIT,AFTER_RECEIPT_BEFORE_COMMIT}
fun interface TurnFailureInjector{fun failIfRequested(point:TurnFailurePoint);companion object{val NONE=TurnFailureInjector{}}}
data class TurnCommitAppliedResult(val appliedChangeUids:List<String>)
class UnsupportedCanonicalChangeException(val changeKindUid:String):IllegalStateException("RPGOS-TURN-APPLIER:UNSUPPORTED_CHANGE:$changeKindUid")
class UnsupportedCanonicalIntentException(val intentKindUid:String):IllegalStateException("RPGOS-TURN-APPLIER:UNSUPPORTED_INTENT:$intentKindUid")
private val TURN_TRANSACTION_SEAL=Any()

class TurnTransaction internal constructor(
    private val db:SQLiteDatabase,
    val identity:TurnTransactionIdentity,
    private val proposal:CanonicalCampaignMutationProposal,
    private val failureInjector:TurnFailureInjector,
    private val seal:Any
){
    init{
        require(seal===TURN_TRANSACTION_SEAL){"RPGOS-TURN-TRANSACTION:FORGED_CAPABILITY"}
        require(proposal.isCanonical()){"RPGOS-TURN-TRANSACTION:FORGED_PROPOSAL"}
    }
    private val receiptStore=TurnTransactionReceiptStore(db)
    private val eventStore=CampaignEventStore(db,identity.campaignUid)
    private val causalGraph=CampaignCausalGraph(db,identity.campaignUid)
    private val causalRelationIntents get()=proposal.causalRelationIntents
    private val semanticFingerprint=transactionFingerprint()
    var state:TurnTransactionState=TurnTransactionState.VALIDATED;private set

    fun commit():TurnExecutionResult<TurnCommitAppliedResult> =
        CampaignRuntimeLifecycleLock.withTurn(identity.campaignUid) { commitLocked() }

    private fun commitLocked():TurnExecutionResult<TurnCommitAppliedResult>{
        check(state==TurnTransactionState.VALIDATED){"turn transaction can execute exactly once"}
        check(!db.inTransaction()){"nested outer TurnTransaction is forbidden"}

        replayReceiptOrNull()?.let{receipt->
            val committedIdentity=receipt.committedIdentity()
            eventStore.assertCommittedSetMatches(committedIdentity,proposal.playerChangeSet,receipt)
            causalGraph.assertCommittedSetMatches(committedIdentity,rebindCausalPlan(committedIdentity),receipt)
            state=TurnTransactionState.COMMITTED
            return TurnExecutionResult.AlreadyCommitted(receipt)
        }

        CanonicalPlayerChangeApplier.preflight(proposal.playerChangeSet)
        val requiredManifest=eventStore.resolveRequiredManifest(identity,proposal.playerChangeSet)
        causalGraph.validate(causalRelationIntents)
        failureInjector.failIfRequested(TurnFailurePoint.BEFORE_FIRST_WRITE)

        db.beginTransaction()
        state=TurnTransactionState.IN_PROGRESS
        return try{
            replayReceiptOrNull()?.let{existing->
                val committedIdentity=existing.committedIdentity()
                eventStore.assertCommittedSetMatches(committedIdentity,proposal.playerChangeSet,existing)
                causalGraph.assertCommittedSetMatches(committedIdentity,rebindCausalPlan(committedIdentity),existing)
                db.setTransactionSuccessful()
                db.endTransaction()
                state=TurnTransactionState.COMMITTED
                return TurnExecutionResult.AlreadyCommitted(existing)
            }

            val commitOrder=receiptStore.reserveNextCommitOrder(identity.campaignUid)
            val applied=withCanonicalGameplayMutationForTurn(db,identity.campaignUid,seal){
                val result=CanonicalPlayerChangeApplier.applyAll(db,identity,proposal.playerChangeSet,failureInjector)
                require(result.appliedChangeUids==proposal.playerChangeSet.changes.map{it.changeUid}){
                    "RPGOS-TURN-APPLIER:INCOMPLETE_CHANGESET_APPLICATION"
                }
                failureInjector.failIfRequested(TurnFailurePoint.BEFORE_EVENT_APPEND)
                eventStore.appendRequired(identity,proposal.playerChangeSet,commitOrder)
                failureInjector.failIfRequested(TurnFailurePoint.AFTER_EVENT_APPEND)
                failureInjector.failIfRequested(TurnFailurePoint.BEFORE_CAUSAL_APPEND)
                causalGraph.appendRequired(identity,causalRelationIntents,commitOrder)
                failureInjector.failIfRequested(TurnFailurePoint.AFTER_CAUSAL_APPEND)
                result
            }
            failureInjector.failIfRequested(TurnFailurePoint.BEFORE_COMMIT)
            val eventBoundaryUid=eventStore.eventsForTransaction(identity.transactionUid).lastOrNull()?.eventUid
            val receipt=withCanonicalCommitEvidenceForTurn(db,identity.campaignUid,seal){
                CommittedReplayPayloadStore(db).append(
                    identity,commitOrder,semanticFingerprint,requiredManifest.summary,proposal.playerChangeSet,
                    causalRelationIntents,eventBoundaryUid
                )
                val committed=receiptStore.appendCommitted(identity,semanticFingerprint,commitOrder,requiredManifest.summary)
                failureInjector.failIfRequested(TurnFailurePoint.AFTER_RECEIPT_BEFORE_COMMIT)
                committed
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            state=TurnTransactionState.COMMITTED
            TurnExecutionResult.Committed(applied,receipt)
        }catch(failure:Throwable){
            if(db.inTransaction())db.endTransaction()
            state=TurnTransactionState.ROLLED_BACK
            throw failure
        }
    }

    private fun replayReceiptOrNull():TurnCommitReceipt? {
        receiptStore.committedTransaction(identity.transactionUid)?.let { existing ->
            if(existing.campaignUid!=identity.campaignUid) throw TurnIdempotencyConflictException(TurnTransactionReceiptStore.CROSS_CAMPAIGN_TRANSACTION_UID)
            if(existing.commandUid!=identity.commandUid||existing.turnUid!=identity.turnUid) throw TurnIdempotencyConflictException(TurnTransactionReceiptStore.TRANSACTION_IDENTITY_MISMATCH)
            if(!fingerprintCompatible(existing)) throw TurnIdempotencyConflictException(TurnTransactionReceiptStore.SEMANTIC_FINGERPRINT_MISMATCH)
            return existing
        }
        receiptStore.committedCommand(identity.campaignUid,identity.commandUid)?.let { existing ->
            if(!fingerprintCompatible(existing)) throw TurnIdempotencyConflictException(TurnTransactionReceiptStore.COMMAND_SEMANTIC_FINGERPRINT_MISMATCH)
            return existing
        }
        return null
    }

    private fun fingerprintCompatible(receipt:TurnCommitReceipt):Boolean {
        if(receipt.semanticFingerprint==semanticFingerprint) return true
        if(causalRelationIntents.isEmpty()) return false
        val committedIdentity=receipt.committedIdentity()
        val rebound=rebindCausalPlan(committedIdentity)
        val proposalFingerprint=TurnSemanticFingerprint.forProposal(proposal)
        val legacyGraphFingerprint=causalGraph.planFingerprint(committedIdentity,rebound)
        val legacyV2=sha256("RPGOS-TURN-WITH-CAUSAL-V2\u001f$proposalFingerprint\u001f$legacyGraphFingerprint")
        return receipt.semanticFingerprint==legacyV2
    }

    private fun TurnCommitReceipt.committedIdentity() = TurnTransactionIdentity(campaignUid,turnUid,commandUid,transactionUid)

    /** Command semantic identity is independent of the retry attempt's transaction/turn UID. */
    private fun transactionFingerprint():String{
        val proposalFingerprint=TurnSemanticFingerprint.forProposal(proposal)
        if(causalRelationIntents.isEmpty()) return proposalFingerprint
        val aliases=currentEventAliases(identity)
        fun canonicalEvent(uid:String)=aliases[uid]?.let{"CURRENT_EVENT:$it"}?:"EXTERNAL_EVENT:$uid"
        val graphSemantic=causalRelationIntents.sortedBy{it.relationIntentUid}.mapIndexed{ordinal,intent->
            listOf(
                "v=$PHASE31_CAUSAL_SCHEMA_VERSION","campaign=${identity.campaignUid}","command=${identity.commandUid}",
                "intent=${intent.relationIntentUid}","ordinal=$ordinal","class=${intent.relationClass.name}","kind=${intent.relationKindUid}",
                "source=${canonicalEvent(intent.sourceEventUid)}","target=${canonicalEvent(intent.targetEventUid)}",
                "evidence=${encodeStrings(intent.evidenceEventUids.map(::canonicalEvent))}",
                "provenance=${encodeStrings(intent.provenanceEventUids.map(::canonicalEvent))}",
                "supersedes=${intent.supersedesRelationUid?:"UNKNOWN"}"
            ).joinToString("|")
        }.joinToString("\u001f")
        return sha256("RPGOS-TURN-WITH-CAUSAL-V3\u001f$proposalFingerprint\u001f${sha256(graphSemantic)}")
    }

    private fun currentEventAliases(forIdentity:TurnTransactionIdentity):Map<String,String> =
        eventStore.resolveRequiredManifest(forIdentity,proposal.playerChangeSet).intents.associate{intent->
            eventStore.eventUid(forIdentity,proposal.playerChangeSet,intent) to intent.eventIntentUid
        }

    private fun rebindCausalPlan(committedIdentity:TurnTransactionIdentity):List<CanonicalCausalRelationIntent>{
        if(causalRelationIntents.isEmpty()||committedIdentity.transactionUid==identity.transactionUid) return causalRelationIntents
        val attemptAliases=currentEventAliases(identity)
        val committedByIntent=currentEventAliases(committedIdentity).entries.associate{(eventUid,intentUid)->intentUid to eventUid}
        fun rebind(uid:String)=attemptAliases[uid]?.let{committedByIntent[it]}?:uid
        return causalRelationIntents.map{intent->intent.copy(
            sourceEventUid=rebind(intent.sourceEventUid),
            targetEventUid=rebind(intent.targetEventUid),
            evidenceEventUids=intent.evidenceEventUids.map(::rebind),
            provenanceEventUids=intent.provenanceEventUids.map(::rebind)
        )}
    }

    private fun encodeStrings(values:List<String>)=values.sorted().joinToString(";"){"${it.length}:$it"}
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}

internal object CanonicalPlayerChangeApplier{
    fun preflight(changeSet:PlayerChangeSet){
        changeSet.ledgerIntents.forEach{intent->
            when(intent.ledgerKindUid){
                PlayerLedgerIntentKinds.FINANCIAL_TRANSFER -> {
                    if(intent.causalChangeUids.isEmpty()) throw UnsupportedCanonicalIntentException(intent.ledgerKindUid)
                    if(intent.causalChangeUids.any{uid->changeSet.changes.none{it.changeUid==uid&&it.payload is FinancialChange}}){
                        throw UnsupportedCanonicalIntentException(intent.ledgerKindUid)
                    }
                }
                PlayerLedgerIntentKinds.PROGRESSION -> {
                    if(intent.payload !is ProgressionLedgerIntentPayload || intent.causalChangeUids.isEmpty()){
                        throw UnsupportedCanonicalIntentException(intent.ledgerKindUid)
                    }
                    if(intent.causalChangeUids.any{uid->
                        changeSet.changes.none{change->
                            change.changeUid==uid && (
                                change.payload is StatChange ||
                                change.payload is SkillChange ||
                                change.payload is TechniqueChange
                            )
                        }
                    }){
                        throw UnsupportedCanonicalIntentException(intent.ledgerKindUid)
                    }
                }
                else -> throw UnsupportedCanonicalIntentException(intent.ledgerKindUid)
            }
        }
        changeSet.changes.forEach{change->
            when(change.payload){
                is StatChange,is ResourceChange,is SkillChange,is TechniqueChange,is InventoryChange,
                is EquipmentChange,is FinancialChange,is OwnershipChange,is CampaignTruthChange,
                is AssetChange,is ConditionChange,is RuntimeChange,
                is WoundChange,is SpatialChange,is EquipmentIntegrityChange,is StructureIntegrityChange,
                is MechanicalTrackChange,is AggregatePopulationChange,
                is DevelopmentProjectChange,is KnowledgeAcquisitionChange -> Unit
                is AccessAuthorityChange -> AccessAuthorityChangeValidator.requireValid(change.payload)
                else -> throw UnsupportedCanonicalChangeException(change.changeKindUid)
            }
        }
    }

    fun applyAll(
        db:SQLiteDatabase,
        identity:TurnTransactionIdentity,
        changeSet:PlayerChangeSet,
        injector:TurnFailureInjector
    ):TurnCommitAppliedResult{
        preflight(changeSet)
        val applied=mutableListOf<String>()
        changeSet.changes.forEach{change->
            when(val payload=change.payload){
                is StatChange->applyStat(db,identity,change.changeUid,payload)
                is ResourceChange->applyResource(db,identity,changeSet,change.changeUid,payload)
                is SkillChange->applySkill(db,identity,change.changeUid,payload)
                is TechniqueChange->applyTechnique(db,identity,change.changeUid,payload)
                is InventoryChange->applyInventory(db,identity,change.changeUid,payload)
                is EquipmentChange->applyEquipment(db,identity,change.changeUid,payload)
                is FinancialChange->applyFinancial(db,identity,changeSet,change.changeUid,payload)
                is AssetChange->applyAsset(db,identity,changeSet,change.changeUid,payload)
                is OwnershipChange->applyOwnership(db,identity,changeSet,change.changeUid,payload)
                is CampaignTruthChange->applyTruth(db,identity,changeSet,change.changeUid,payload)
                is ConditionChange->applyCondition(db,identity,changeSet,change.changeUid,payload)
                is RuntimeChange->applyRuntime(db,identity,changeSet,change.changeUid,payload)
                is WoundChange->MechanicalActorStateStore(db,identity.campaignUid).applyWound(identity,change.changeUid,payload,effectiveOrder(changeSet))
                is SpatialChange->MechanicalActorStateStore(db,identity.campaignUid).applySpatial(identity,change.changeUid,payload,effectiveOrder(changeSet))
                is EquipmentIntegrityChange->MechanicalActorStateStore(db,identity.campaignUid).applyEquipmentIntegrity(identity,change.changeUid,payload,effectiveOrder(changeSet))
                is StructureIntegrityChange->MechanicalActorStateStore(db,identity.campaignUid).applyStructureIntegrity(identity,change.changeUid,payload,effectiveOrder(changeSet))
                is MechanicalTrackChange->MechanicalActorStateStore(db,identity.campaignUid).applyTrack(identity,change.changeUid,payload,effectiveOrder(changeSet))
                is AggregatePopulationChange->MechanicalActorStateStore(db,identity.campaignUid).applyAggregate(identity,change.changeUid,payload,effectiveOrder(changeSet))
                is KnowledgeAcquisitionChange->applyKnowledge(db,identity,changeSet,change.changeUid,payload)
                is DevelopmentProjectChange->applyProject(db,identity,changeSet,change.changeUid,payload)
                is AccessAuthorityChange->applyAccessAuthority(db,identity,changeSet,change.changeUid,payload)
                else->throw UnsupportedCanonicalChangeException(change.changeKindUid)
            }
            applied+=change.changeUid
            if(applied.size==1)injector.failIfRequested(TurnFailurePoint.AFTER_FIRST_WRITE)
            if(applied.size==2)injector.failIfRequested(TurnFailurePoint.AFTER_SECOND_DOMAIN_WRITE)
        }
        return TurnCommitAppliedResult(applied.toList())
    }

    private fun effectiveOrder(changeSet:PlayerChangeSet):Long=
        changeSet.requestedEffectiveOrder?:error("RPGOS-TURN-APPLIER:MISSING_EFFECTIVE_ORDER")

    private fun provenance(identity:TurnTransactionIdentity,changeUid:String)="TURN:${identity.transactionUid}:$changeUid"

    private fun applyAccessAuthority(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:AccessAuthorityChange){
        AccessAuthorityStore(db,identity.campaignUid).apply(identity,changeUid,p,effectiveOrder(changeSet))
    }

    private fun applyStat(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeUid:String,p:StatChange){
        val store=StatResourceStore(db,identity.campaignUid)
        val current=store.playerStats(p.subject.uid).singleOrNull{it.statUid==p.statUid}
            ?:error("RPGOS-TURN-APPLIER:MISSING_STAT:${p.statUid}")
        val next=current.baseValue+p.delta.units.toDouble()
        require(next.isFinite()){"RPGOS-TURN-APPLIER:INVALID_STAT_RESULT"}
        store.savePlayerStat(current.copy(baseValue=next,version=Math.addExact(current.version,1L)))
    }

    private fun applyResource(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:ResourceChange){
        if(p.subject.kindUid.uppercase()!="PLAYER"){
            MechanicalActorStateStore(db,identity.campaignUid).applyResource(identity,changeUid,p,effectiveOrder(changeSet))
            return
        }
        val store=StatResourceStore(db,identity.campaignUid)
        val current=store.playerResources(p.subject.uid).singleOrNull{it.resourceUid==p.resourceUid}
            ?:error("RPGOS-TURN-APPLIER:MISSING_RESOURCE:${p.resourceUid}")
        val next=current.currentValue+p.delta.units.toDouble()
        require(next.isFinite()){"RPGOS-TURN-APPLIER:INVALID_RESOURCE_RESULT"}
        store.savePlayerResource(current.copy(currentValue=next,version=Math.addExact(current.version,1L)))
    }

    private fun applySkill(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeUid:String,p:SkillChange){
        val store=SkillStore(db,identity.campaignUid)
        val current=store.playerSkills(p.subject.uid).singleOrNull{it.skillUid==p.skillUid}
            ?:error("RPGOS-TURN-APPLIER:MISSING_SKILL:${p.skillUid}")
        val next=(current.progressValue?:0.0)+p.progressDelta.units.toDouble()
        require(next.isFinite()){"RPGOS-TURN-APPLIER:INVALID_SKILL_RESULT"}
        store.savePlayerSkill(current.copy(progressValue=next,entryVersion=Math.addExact(current.entryVersion,1L),provenance=provenance(identity,changeUid)))
    }

    private fun applyTechnique(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeUid:String,p:TechniqueChange){
        val store=TechniqueStore(db,identity.campaignUid)
        val current=store.playerTechniques(p.subject.uid).singleOrNull{it.techniqueUid==p.techniqueUid}
            ?:error("RPGOS-TURN-APPLIER:MISSING_TECHNIQUE:${p.techniqueUid}")
        val next=(current.progressValue?:0.0)+p.progressDelta.units.toDouble()
        require(next.isFinite()){"RPGOS-TURN-APPLIER:INVALID_TECHNIQUE_RESULT"}
        store.savePlayerTechnique(current.copy(progressValue=next,entryVersion=Math.addExact(current.entryVersion,1L),provenance=provenance(identity,changeUid)))
    }

    private fun applyInventory(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeUid:String,p:InventoryChange){
        val store=InventoryStore(db,identity.campaignUid)
        val instance=store.typedUnique(p.subject.uid).singleOrNull{it.first.itemInstanceUid==p.itemInstanceUid}
        when(p.quantityDelta.units){
            1L->{
                require(instance==null){"RPGOS-TURN-APPLIER:INVENTORY_INSTANCE_ALREADY_HELD"}
                store.addUnique(p.subject.uid,p.itemInstanceUid,provenance(identity,changeUid))
            }
            -1L->{
                require(instance!=null){"RPGOS-TURN-APPLIER:INVENTORY_INSTANCE_NOT_HELD"}
                store.removeUnique(p.subject.uid,p.itemInstanceUid)
            }
            else->throw UnsupportedCanonicalChangeException(PlayerChangeKinds.INVENTORY)
        }
    }

    private fun applyEquipment(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeUid:String,p:EquipmentChange){
        val store=EquipmentStore(db,identity.campaignUid)
        when(p.operation){
            EquipmentOperation.EQUIP->{
                val itemUid=requireNotNull(p.itemInstanceUid)
                val instance=InventoryStore(db,identity.campaignUid).typedUnique(p.subject.uid)
                    .singleOrNull{it.first.itemInstanceUid==itemUid}?.second
                    ?:error("RPGOS-TURN-APPLIER:EQUIPMENT_ITEM_NOT_HELD")
                val rules=store.compatibilityRules().filter{it.itemDefinitionUid==instance.itemDefinitionUid&&it.requiredSlotUids.sorted()==listOf(p.slotUid)}
                require(rules.size==1){"RPGOS-TURN-APPLIER:EQUIPMENT_RULE_AMBIGUOUS_OR_MISSING"}
                store.equip(p.subject.uid,itemUid,rules.single().ruleUid,listOf(p.slotUid),"${identity.transactionUid}:$changeUid",provenance(identity,changeUid))
            }
            EquipmentOperation.UNEQUIP->{
                val record=store.equipment(p.subject.uid).singleOrNull{p.slotUid in it.occupiedSlotUids}
                    ?:error("RPGOS-TURN-APPLIER:EQUIPMENT_SLOT_NOT_OCCUPIED")
                store.unequip(p.subject.uid,record.equipment.equipmentEntryUid)
            }
        }
    }

    private fun applyFinancial(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:FinancialChange){
        require(p.amountMinor>0)
        FinancialStore(db,identity.campaignUid).commit(
            FinancialTransaction(
                identity.campaignUid,"${identity.transactionUid}:$changeUid",p.fromAccountUid,p.toAccountUid,p.currencyUid,
                p.amountMinor,p.transactionTypeUid,FinancialFlowKind.INTERNAL,"Canonical PlayerChangeSet ${changeSet.changeSetUid}",
                effectiveOrder(changeSet),provenance(identity,changeUid),changeSet.provenance.sourceEventUid,identity.commandUid
            )
        )
    }

    private fun applyAsset(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:AssetChange){
        val lifecycle=try{AssetLifecycle.valueOf(p.proposedLifecycleStateUid)}catch(_:Throwable){throw UnsupportedCanonicalChangeException(PlayerChangeKinds.ASSET)}
        require(lifecycle!=AssetLifecycle.ACTIVE){"RPGOS-TURN-APPLIER:ASSET_REACTIVATION_UNSUPPORTED"}
        AssetLiabilityStore(db,identity.campaignUid).retireAsset(p.asset,effectiveOrder(changeSet),lifecycle,provenance(identity,changeUid))
    }

    private fun applyCondition(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:ConditionChange){
        Phase50MechanicalStateStore(db,identity.campaignUid).applyCondition(identity,changeUid,p,effectiveOrder(changeSet))
    }

    private fun applyRuntime(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:RuntimeChange){
        Phase50MechanicalStateStore(db,identity.campaignUid).applyRuntime(identity,changeUid,p,effectiveOrder(changeSet))
    }

    private fun applyOwnership(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:OwnershipChange){
        val store=OwnershipStore(db,identity.campaignUid)
        val source=store.currentOwnership(p.asset).singleOrNull{it.ownershipRecordUid==p.ownershipRecordUid&&it.owner==p.fromOwner}
            ?:error("RPGOS-TURN-APPLIER:MISSING_OWNERSHIP_SOURCE:${p.ownershipRecordUid}")
        store.transferShare(
            "${identity.transactionUid}:$changeUid",p.fromOwner,p.toOwner,p.asset,source.ownershipTypeUid,p.share,
            effectiveOrder(changeSet),changeSet.provenance.sourceEventUid,provenance(identity,changeUid)
        )
    }

    private fun applyTruth(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:CampaignTruthChange){
        CampaignTruthStore(db,identity.campaignUid).record(
            kind=p.kind,
            predicate=p.predicate,
            provenance=Provenance(
                sourceType=ProvenanceSourceType.PLAYER_ACTION,
                sourceId=identity.commandUid,
                createdTurn=changeSet.requestedEffectiveOrder,
                createdEvent=changeSet.provenance.sourceEventUid,
                actorUid=changeSet.actor.actorUid,
                method=changeSet.provenance.resolverKindUid,
                engineVersion=changeSet.provenance.resolverVersion
            ),
            subjectUid=p.subjectUid,
            objectValue=p.objectValue,
            perspectiveUid=p.perspectiveUid,
            narrativeText=p.narrativeText,
            truthUid=p.truthUid,
            supersedesTruthUid=p.supersedesTruthUid,
            createdAt=changeSet.requestedEffectiveOrder ?: 0L
        )
        p.canonDivergence?.let { divergence ->
            val intent = changeSet.eventIntents.singleOrNull { changeUid in it.causalChangeUids }
                ?: error("RPGOS-CANON:DIVERGENCE_REQUIRES_EXACT_EVENT")
            val eventUid = CampaignEventStore(db, identity.campaignUid).eventUid(identity, changeSet, intent)
            CanonDivergenceStore(db, identity.campaignUid).recordCommitted(divergence, identity, eventUid)
        }
    }

    private fun applyKnowledge(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:KnowledgeAcquisitionChange){
        val intents=changeSet.eventIntents.filter{changeUid in it.causalChangeUids}
        require(intents.size==1){"RPGOS-KNOWLEDGE:ACQUISITION_REQUIRES_EXACT_EVENT"}
        val eventUid=CampaignEventStore(db,identity.campaignUid).eventUid(identity,changeSet,intents.single())
        KnowledgeStore(db,identity.campaignUid).stageRecorded(p,identity,eventUid,effectiveOrder(changeSet))
    }

    private fun applyProject(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:DevelopmentProjectChange){
        val result=try{ProjectWorkResult.valueOf(p.workResultKindUid)}catch(_:Throwable){throw UnsupportedCanonicalChangeException(PlayerChangeKinds.DEVELOPMENT_PROJECT)}
        val metadata=buildJsonObject{
            put("canonicalChangeUid",JsonPrimitive(changeUid))
            put("evidenceRefs",buildJsonArray{
                p.evidenceRefs.forEach{ref->add(buildJsonObject{put("kindUid",JsonPrimitive(ref.kindUid));put("uid",JsonPrimitive(ref.uid))})}
            })
        }.toString()
        DevelopmentProjectStore(db,identity.campaignUid).recordWork(
            ProjectWorkRecord(
                campaignId=identity.campaignUid,
                workRecordUid="${identity.transactionUid}:$changeUid",
                projectUid=p.projectUid,
                workKindUid="RPGOS-WORK:CANONICAL_PLAYER_CHANGE",
                actor=OwnershipOwnerRef(changeSet.actor.actorKindUid,changeSet.actor.actorUid),
                effectiveOrder=effectiveOrder(changeSet),
                result=result,
                progressDeltaUnits=p.progressDelta.units,
                commandUid=identity.commandUid,
                sourceEventUid=changeSet.provenance.sourceEventUid,
                provenance=provenance(identity,changeUid),
                metadataJson=metadata
            )
        )
    }
}

/** Recovery-only deterministic reapplication of material that was committed by TurnTransaction. */
internal fun replayCommittedTransaction(db:SQLiteDatabase,payload:CommittedReplayPayload){
    requireAdministrativeRecoveryEntryPoint()
    CampaignRuntimeLifecycleLock.withRecovery(payload.identity.campaignUid) {
        check(!db.inTransaction()){"RPGOS-SNAPSHOT:NESTED_REPLAY"}
        check(TurnTransactionReceiptStore(db).committedTransaction(payload.identity.transactionUid)==null){"RPGOS-SNAPSHOT:DUPLICATE_REPLAY"}
        val eventStore=CampaignEventStore(db,payload.identity.campaignUid)
        val causalGraph=CampaignCausalGraph(db,payload.identity.campaignUid)
        val resolved=eventStore.resolveRequiredManifest(payload.identity,payload.changeSet)
        require(resolved.summary==payload.eventManifest){"RPGOS-SNAPSHOT:REPLAY_EVENT_MANIFEST_MISMATCH"}
        causalGraph.validate(payload.causalPlan)
        db.beginTransaction()
        try{
            val applied=withCanonicalGameplayMutationForTurn(db,payload.identity.campaignUid,TURN_TRANSACTION_SEAL){
                val result=CanonicalPlayerChangeApplier.applyAll(db,payload.identity,payload.changeSet,TurnFailureInjector.NONE)
                eventStore.appendRequired(payload.identity,payload.changeSet,payload.commitOrder)
                causalGraph.appendRequired(payload.identity,payload.causalPlan,payload.commitOrder)
                result
            }
            require(applied.appliedChangeUids==payload.changeSet.changes.map{it.changeUid})
            val boundary=eventStore.eventsForTransaction(payload.identity.transactionUid).lastOrNull()?.eventUid
            require(boundary==payload.eventBoundaryUid){"RPGOS-SNAPSHOT:REPLAY_EVENT_BOUNDARY_MISMATCH"}
            withCanonicalCommitEvidenceForTurn(db,payload.identity.campaignUid,TURN_TRANSACTION_SEAL){
                CommittedReplayPayloadStore(db).append(payload.identity,payload.commitOrder,payload.semanticFingerprint,payload.eventManifest,payload.changeSet,payload.causalPlan,boundary)
                TurnTransactionReceiptStore(db).appendCommitted(payload.identity,payload.semanticFingerprint,payload.commitOrder,payload.eventManifest)
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }
}

object TurnTransactionBoundary{
    const val CAMPAIGN_MISMATCH="RPGOS-TURN-TRANSACTION:CAMPAIGN_MISMATCH"
    const val COMMAND_MISMATCH="RPGOS-TURN-TRANSACTION:COMMAND_MISMATCH"
    internal fun acceptsCanonicalSeal(value:Any)=value===TURN_TRANSACTION_SEAL

    fun create(
        db:SQLiteDatabase,
        identity:TurnTransactionIdentity,
        proposal:CanonicalCampaignMutationProposal,
        failureInjector:TurnFailureInjector=TurnFailureInjector.NONE,
        causalRelationIntents:List<CanonicalCausalRelationIntent> = emptyList()
    ):TurnTransaction{
        require(proposal.isCanonical()){"RPGOS-TURN-TRANSACTION:FORGED_PROPOSAL"}
        require(identity.campaignUid==proposal.campaignUid){CAMPAIGN_MISMATCH}
        require(identity.commandUid==proposal.playerChangeSet.sourceCommandUid){COMMAND_MISMATCH}
        GameplayRuntimeBootstrap.requireReady(db,identity.campaignUid)

        val normalizedProposal = when {
            causalRelationIntents.isEmpty() -> proposal
            proposal.causalRelationIntents.isEmpty() -> CampaignMutationBoundary.withValidatedCausalPlan(proposal, causalRelationIntents)
            proposal.causalRelationIntents == causalRelationIntents -> proposal
            else -> error("RPGOS-TURN-TRANSACTION:CAUSAL_PLAN_SIDE_CHANNEL_MISMATCH")
        }
        return TurnTransaction(db,identity,normalizedProposal,failureInjector,TURN_TRANSACTION_SEAL)
    }
}
