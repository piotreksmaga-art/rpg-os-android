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
    private val causalRelationIntents:List<CanonicalCausalRelationIntent>,
    private val seal:Any
){
    init{
        require(seal===TURN_TRANSACTION_SEAL){"RPGOS-TURN-TRANSACTION:FORGED_CAPABILITY"}
        require(proposal.isCanonical()){"RPGOS-TURN-TRANSACTION:FORGED_PROPOSAL"}
    }
    private val receiptStore=TurnTransactionReceiptStore(db)
    private val eventStore=CampaignEventStore(db,identity.campaignUid)
    private val causalGraph=CampaignCausalGraph(db,identity.campaignUid)
    private val semanticFingerprint=transactionFingerprint()
    var state:TurnTransactionState=TurnTransactionState.VALIDATED;private set

    fun commit():TurnExecutionResult<TurnCommitAppliedResult>{
        check(state==TurnTransactionState.VALIDATED){"turn transaction can execute exactly once"}
        check(!db.inTransaction()){"nested outer TurnTransaction is forbidden"}
        receiptStore.replay(identity,semanticFingerprint)?.let{
            eventStore.assertCommittedSetMatches(identity,proposal.playerChangeSet)
            causalGraph.assertCommittedSetMatches(identity,causalRelationIntents)
            state=TurnTransactionState.COMMITTED
            return TurnExecutionResult.AlreadyCommitted(it)
        }
        CanonicalPlayerChangeApplier.preflight(proposal.playerChangeSet)
        eventStore.validateRequiredEventIntents(proposal.playerChangeSet)
        causalGraph.validate(causalRelationIntents)
        failureInjector.failIfRequested(TurnFailurePoint.BEFORE_FIRST_WRITE)
        db.beginTransaction()
        state=TurnTransactionState.IN_PROGRESS
        return try{
            receiptStore.replay(identity,semanticFingerprint)?.let{existing->
                eventStore.assertCommittedSetMatches(identity,proposal.playerChangeSet)
                causalGraph.assertCommittedSetMatches(identity,causalRelationIntents)
                db.setTransactionSuccessful()
                db.endTransaction()
                state=TurnTransactionState.COMMITTED
                return TurnExecutionResult.AlreadyCommitted(existing)
            }
            val applied=withCanonicalGameplayMutationForTurn(db,identity.campaignUid,seal){
                val result=CanonicalPlayerChangeApplier.applyAll(db,identity,proposal.playerChangeSet,failureInjector)
                require(result.appliedChangeUids==proposal.playerChangeSet.changes.map{it.changeUid}){
                    "RPGOS-TURN-APPLIER:INCOMPLETE_CHANGESET_APPLICATION"
                }
                failureInjector.failIfRequested(TurnFailurePoint.BEFORE_EVENT_APPEND)
                eventStore.appendRequired(identity,proposal.playerChangeSet)
                failureInjector.failIfRequested(TurnFailurePoint.AFTER_EVENT_APPEND)
                failureInjector.failIfRequested(TurnFailurePoint.BEFORE_CAUSAL_APPEND)
                causalGraph.appendRequired(identity,causalRelationIntents)
                failureInjector.failIfRequested(TurnFailurePoint.AFTER_CAUSAL_APPEND)
                result
            }
            failureInjector.failIfRequested(TurnFailurePoint.BEFORE_COMMIT)
            val receipt=receiptStore.appendCommitted(identity,semanticFingerprint)
            failureInjector.failIfRequested(TurnFailurePoint.AFTER_RECEIPT_BEFORE_COMMIT)
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

    private fun transactionFingerprint():String{
        val proposalFingerprint=TurnSemanticFingerprint.forProposal(proposal)
        if(causalRelationIntents.isEmpty()) return proposalFingerprint
        val graphFingerprint=causalGraph.planFingerprint(identity,causalRelationIntents)
        return sha256("RPGOS-TURN-WITH-CAUSAL-V1\u001f$proposalFingerprint\u001f$graphFingerprint")
    }
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}

private object CanonicalPlayerChangeApplier{
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
                is DevelopmentProjectChange -> Unit
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
                is ResourceChange->applyResource(db,identity,change.changeUid,payload)
                is SkillChange->applySkill(db,identity,change.changeUid,payload)
                is TechniqueChange->applyTechnique(db,identity,change.changeUid,payload)
                is InventoryChange->applyInventory(db,identity,change.changeUid,payload)
                is EquipmentChange->applyEquipment(db,identity,change.changeUid,payload)
                is FinancialChange->applyFinancial(db,identity,changeSet,change.changeUid,payload)
                is OwnershipChange->applyOwnership(db,identity,changeSet,change.changeUid,payload)
                is CampaignTruthChange->applyTruth(db,identity,changeSet,payload)
                is DevelopmentProjectChange->applyProject(db,identity,changeSet,change.changeUid,payload)
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

    private fun applyStat(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeUid:String,p:StatChange){
        val store=StatResourceStore(db,identity.campaignUid)
        val current=store.playerStats(p.subject.uid).singleOrNull{it.statUid==p.statUid}
            ?:error("RPGOS-TURN-APPLIER:MISSING_STAT:${p.statUid}")
        val next=current.baseValue+p.delta.units.toDouble()
        require(next.isFinite()){"RPGOS-TURN-APPLIER:INVALID_STAT_RESULT"}
        store.savePlayerStat(current.copy(baseValue=next,version=Math.addExact(current.version,1L)))
    }

    private fun applyResource(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeUid:String,p:ResourceChange){
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

    private fun applyOwnership(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:OwnershipChange){
        val store=OwnershipStore(db,identity.campaignUid)
        val source=store.currentOwnership(p.asset).singleOrNull{it.ownershipRecordUid==p.ownershipRecordUid&&it.owner==p.fromOwner}
            ?:error("RPGOS-TURN-APPLIER:MISSING_OWNERSHIP_SOURCE:${p.ownershipRecordUid}")
        store.transferShare(
            "${identity.transactionUid}:$changeUid",p.fromOwner,p.toOwner,p.asset,source.ownershipTypeUid,p.share,
            effectiveOrder(changeSet),changeSet.provenance.sourceEventUid,provenance(identity,changeUid)
        )
    }

    private fun applyTruth(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,p:CampaignTruthChange){
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
            supersedesTruthUid=p.supersedesTruthUid
        )
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
        TurnTransactionReceiptSchema.ensureReady(db)
        GameplayMutationDatabaseGuards.ensureInstalled(db)
        CampaignIntelligencePhase30Schema.ensureActivated(db,identity.campaignUid)
        CampaignCausalGraphSchema.ensureReady(db)
        return TurnTransaction(db,identity,proposal,failureInjector,causalRelationIntents.toList(),TURN_TRANSACTION_SEAL)
    }
}