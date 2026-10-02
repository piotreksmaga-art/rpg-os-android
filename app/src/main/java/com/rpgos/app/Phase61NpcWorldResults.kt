package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

enum class NpcWorldResultKind {
    LOCATION_REACHED, SKILL_PROGRESS, TECHNIQUE_PROGRESS, SKILL_MASTERY, TECHNIQUE_ACQUIRED,
    KNOWLEDGE_ACQUIRED, RESOURCE_AT_LEAST, WOUND_RESOLVED, CONDITION_REMOVED, PATIENT_STABILIZED,
    ITEM_OWNED, COMBAT_DEFEAT, DUTY_COMPLETED
}
/** A Core-projected predicate, not a string objective interpreted by a model. All predicates
 * must be true, backed by the completed attempt's active receipt and their current domain owner. */
data class NpcWorldResultCriterion(val kind:NpcWorldResultKind,val target:DomainRef,val valueUid:String,
    val minimum:Double=0.0) {
    init { npcUid(target.kindUid);npcUid(target.uid);npcUid(valueUid);require(minimum.isFinite() && minimum>=0) }
}
data class NpcWorldResultContract(val ownerUid:String,val contractFingerprint:String,val criteria:List<NpcWorldResultCriterion>) {
    // Eight explicit predicates plus bounded resources, conditions, wound and stabilization.
    // A legal multi-effect treatment must not crash option projection while deriving its goals.
    init {npcUid(ownerUid);require(contractFingerprint.matches(Regex("[0-9a-f]{64}")) && criteria.size in 1..32 && criteria.distinct().size==criteria.size)}
}
internal object NpcWorldResultCodec {
    fun encode(c:NpcWorldResultContract)=buildJsonObject {
        put("owner",c.ownerUid);put("contract",c.contractFingerprint)
        put("criteria",JsonArray(c.criteria.map { r->buildJsonObject {
            put("kind",r.kind.name);put("target",NpcBrainCodec.ref(r.target));put("value",r.valueUid);put("minimum",r.minimum)
        }}))
    }
    fun decode(value:JsonElement):NpcWorldResultContract {
        val o=value.jsonObject;NpcBrainCodec.keys(o,"owner","contract","criteria")
        return NpcWorldResultContract(NpcBrainCodec.text(o,"owner"),NpcBrainCodec.text(o,"contract"),o.getValue("criteria").jsonArray.also{require(it.size in 1..32)}.map{
            val r=it.jsonObject;NpcBrainCodec.keys(r,"kind","target","value","minimum")
            val minimum=r.getValue("minimum").jsonPrimitive.also{require(!it.isString)}.double
            NpcWorldResultCriterion(NpcWorldResultKind.valueOf(NpcBrainCodec.text(r,"kind")),NpcBrainCodec.readRef(r.getValue("target")),NpcBrainCodec.text(r,"value"),minimum)
        })
    }
}

/** Infrastructure reader only. It returns a digest of active canonical leaves, never hidden patient state.
 * Reads in another actor's domain additionally require the same Phase38 explicit authority. */
internal class NpcCanonicalResultOwner(private val db:SQLiteDatabase,private val campaign:String) {
    fun proof(brain:NpcBrainState,goal:NpcGoal,throughOrder:Long):String? {
        if(brain.campaignUid!=campaign || goal.lifecycle!=NpcGoalLifecycle.ACTIVE)return null
        if((TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder?:0L)!=throughOrder)return null
        val contract=goal.executionObjective?.worldResult?:return null
        val plans=brain.plans.filter{it.goalUid==goal.uid && it.lifecycle==NpcPlanLifecycle.COMPLETED && it.cause.kind==NpcCauseKind.ACCEPTED_ACTION}
            .sortedWith(compareByDescending<NpcPlan>{it.startedAt}.thenBy{it.uid})
        val eligible=plans.mapNotNull { plan ->
            val receipt=TurnTransactionReceiptStore(db).committedCommand(campaign,plan.cause.uid)?:return@mapNotNull null
            val order=receipt.commitOrder?:return@mapNotNull null
            if(order>throughOrder || receipt.receiptVersion<TURN_TRANSACTION_RECEIPT_VERSION)return@mapNotNull null
            val replay=CommittedReplayPayloadStore(db).between(campaign,order-1,order).singleOrNull{it.identity.transactionUid==receipt.transactionUid}?:return@mapNotNull null
            if(replay.semanticFingerprint!=receipt.semanticFingerprint || replay.changeSet.sourceCommandUid!=plan.cause.uid)return@mapNotNull null
            // A plan row alone is not domain evidence. Its exact completed revision must also be
            // in this receipt, followed by an owner-matched change for every result predicate.
            if(replay.changeSet.changes.mapNotNull{it.payload as? NpcBrainChange}.none{change->
                change.actor==brain.actor && NpcBrainCodec.decode(change.stateCanonical).plans.any{it==plan}})return@mapNotNull null
            receipt to replay
        }
        // A multi-step treatment can stabilize first and heal later. Each predicate needs its
        // own active leaf; do not require all effects to have happened in one artificial action.
        val evidence=contract.criteria.map { criterion ->
            if(!authorized(brain.actor,criterion,throughOrder) || !current(criterion))return null
            val supporting=eligible.firstOrNull{(_,replay)->changedByAttempt(brain.actor,contract,criterion,replay.changeSet)}?:return null
            "${criterion}|${supporting.first.transactionUid}|${supporting.second.payloadSha256}|${ownerFingerprint(criterion)}"
        }
        return "P61:RESULT:${phase60Hash("$campaign|${brain.actor}|${goal.uid}|${NpcWorldResultCodec.encode(contract)}|${evidence.joinToString(";")}")}"
    }
    private fun authorized(actor:DomainRef,c:NpcWorldResultCriterion,order:Long):Boolean {
        if(c.target==actor)return true
        // Location is not hidden actor state; route knowledge was admitted by the movement owner.
        if(c.kind==NpcWorldResultKind.LOCATION_REACHED)return c.target==actor
        val a=UniversalAccessAuthority(AccessAuthorityStore(db,campaign))
        val trusted=a.trustedContext(AudienceContext(campaign,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef(actor.kindUid,actor.uid)),order)?:return false
        val policy=if(c.kind==NpcWorldResultKind.COMBAT_DEFEAT)"P62:COMBAT_OUTCOME" else "P62:TREATMENT_ACCESS"
        return a.authorize(trusted,AccessRequirement(policy,explicitGrantRequired=true,
            carrier=InformationCarrierRef(campaign,c.target.kindUid,c.target.uid)),order).authorized
    }
    private fun changedByAttempt(actor:DomainRef,contract:NpcWorldResultContract,c:NpcWorldResultCriterion,set:PlayerChangeSet):Boolean {
        val changes=set.changes
        val sourcePrefix=if(contract.ownerUid=="UNIVERSAL_MOVEMENT")"P62:TRAVEL:${contract.contractFingerprint}:" else "P62:ACTIVITY:${contract.contractFingerprint}:"
        val owned=changes.filter { it.sourceRuleUid.orEmpty().let{proof->proof.startsWith(sourcePrefix) ||
            (proof.startsWith("P60:PROCESS:") || proof.startsWith("P60:SETTLEMENT:")) && proof.contains(":$sourcePrefix")} }
        return when(c.kind) {
            NpcWorldResultKind.LOCATION_REACHED->owned.any{(it.payload as? SpatialChange)?.let{p->p.subject==actor && p.destinationLocation?.uid==c.valueUid}==true}
            NpcWorldResultKind.SKILL_PROGRESS,NpcWorldResultKind.TECHNIQUE_PROGRESS->set.ledgerIntents.any { ledger->
                val p=ledger.payload as? ProgressionLedgerIntentPayload?:return@any false
                val kind=if(c.kind==NpcWorldResultKind.SKILL_PROGRESS)ProgressionTargetKinds.SKILL else ProgressionTargetKinds.TECHNIQUE
                val effectUid=p.stimulusUid.removePrefix("P62:LEARNING:")
                p.sourceTypeUid=="NPC_REGISTERED_LEARNING" && p.characterUid==actor.uid && p.targetKindUid==kind && p.targetUid==c.valueUid &&
                    p.stimulusUid.startsWith("P62:LEARNING:") && p.sourceCommandUid==set.sourceCommandUid &&
                    owned.any{it.changeUid=="RPGOS-MECHANICS-CHANGE:${phase60Hash(effectUid).take(32)}" && (it.payload as? MechanicalTrackChange)?.subject==actor} &&
                    changes.any{change->change.changeUid in ledger.causalChangeUids && when(val v=change.payload) {
                        is SkillChange->v.subject==actor && v.skillUid==c.valueUid && v.progressDelta.units==p.finalGrantUnits
                        is TechniqueChange->v.subject==actor && v.techniqueUid==c.valueUid && v.progressDelta.units==p.finalGrantUnits
                        else->false
                    }}
            }
            NpcWorldResultKind.KNOWLEDGE_ACQUIRED->owned.any{(it.payload as? KnowledgeAcquisitionChange)?.let{p->p.claim.claimUid==c.valueUid && p.acquisition.holder.holderUid==actor.uid}==true}
            NpcWorldResultKind.RESOURCE_AT_LEAST->owned.any{(it.payload as? ResourceChange)?.let{p->p.subject==c.target && p.resourceUid==c.valueUid && p.delta.units>0}==true}
            NpcWorldResultKind.WOUND_RESOLVED->owned.any{(it.payload as? WoundChange)?.let{p->p.subject==c.target && p.severityDelta.units<0}==true}
            NpcWorldResultKind.CONDITION_REMOVED,NpcWorldResultKind.PATIENT_STABILIZED->owned.any{(it.payload as? ConditionChange)?.let{p->p.subject==c.target && p.conditionUid==c.valueUid && p.operation==ConditionOperation.REMOVE}==true}
            NpcWorldResultKind.DUTY_COMPLETED->owned.any{(it.payload as? MechanicalTrackChange)?.let{p->p.subject==actor && p.trackUid==c.valueUid && p.delta.units>0}==true}
            // These predicates read the existing typed owner, not progress converted into
            // mastery/acquisition. An already acquired technique remains acquired; this
            // assessment cannot insert one or promote progress into learning.
            NpcWorldResultKind.SKILL_MASTERY,NpcWorldResultKind.TECHNIQUE_ACQUIRED->owned.any{(it.payload as? MechanicalTrackChange)?.subject==actor}
            NpcWorldResultKind.ITEM_OWNED->changes.any{(it.payload as? InventoryChange)?.let{p->p.subject==actor && p.itemInstanceUid==c.valueUid && p.quantityDelta.units>0}==true}
            NpcWorldResultKind.COMBAT_DEFEAT->c.valueUid in setOf("INCAPACITATED","DEAD","CAPTURED","SURRENDERED") && changes.any{change->
                val p=change.payload as? ConditionChange
                p?.subject==c.target && p.conditionUid==c.valueUid && p.operation==ConditionOperation.ADD &&
                    change.sourceRuleUid.orEmpty().let{it.startsWith("PROOF:") || it.startsWith("P60:PROCESS:") && it.contains(":PROOF:")} &&
                    set.eventIntents.any{event->event.actorRef==actor && change.changeUid in event.causalChangeUids}
            }
        }
    }
    private fun current(c:NpcWorldResultCriterion):Boolean=when(c.kind) {
        NpcWorldResultKind.LOCATION_REACHED->MechanicalActorStateStore(db,campaign).actor(c.target)?.locationRef?.uid==c.valueUid
        NpcWorldResultKind.SKILL_PROGRESS->SkillStore(db,campaign).playerSkills(c.target.uid).singleOrNull{it.skillUid==c.valueUid}?.progressValue?.let{it>=c.minimum}==true
        NpcWorldResultKind.TECHNIQUE_PROGRESS->TechniqueStore(db,campaign).playerTechniques(c.target.uid).singleOrNull{it.techniqueUid==c.valueUid}?.progressValue?.let{it>=c.minimum}==true
        NpcWorldResultKind.KNOWLEDGE_ACQUIRED->db.rawQuery("SELECT 1 FROM world_actor_knowledge_states WHERE campaign_uid=? AND holder_kind_uid=? AND holder_uid=? AND claim_uid=? AND epistemic_state_uid IN ('KNOWN','BELIEVED','PARTIALLY_KNOWN') LIMIT 1",
            arrayOf(campaign,KnowledgeHolderKinds.CHARACTER,c.target.uid,c.valueUid)).use{it.moveToFirst()}
        NpcWorldResultKind.RESOURCE_AT_LEAST->MechanicalActorStateStore(db,campaign).actor(c.target)?.resources?.singleOrNull{it.resourceUid==c.valueUid}?.current?.let{it.toDouble()>=c.minimum}==true
        NpcWorldResultKind.WOUND_RESOLVED->MechanicalActorStateStore(db,campaign).actor(c.target)?.conditions?.none{it.conditionUid=="WOUND" && it.intensity>0}==true
        NpcWorldResultKind.CONDITION_REMOVED,NpcWorldResultKind.PATIENT_STABILIZED->MechanicalActorStateStore(db,campaign).actor(c.target)?.conditions?.none{it.conditionUid==c.valueUid && it.intensity>0}==true
        NpcWorldResultKind.DUTY_COMPLETED->db.rawQuery("SELECT current_value FROM mechanical_actor_tracks WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND track_uid=?",
            arrayOf(campaign,c.target.kindUid,c.target.uid,c.valueUid)).use{it.moveToFirst() && it.getLong(0)>0}
        NpcWorldResultKind.SKILL_MASTERY->SkillStore(db,campaign).playerSkills(c.target.uid).singleOrNull{it.skillUid==c.valueUid}?.baseMastery?.let{it>=c.minimum}==true &&
            SkillStore(db,campaign).definitions().any{it.skillUid==c.valueUid && it.status==SkillDefinitionStatus.ACTIVE}
        NpcWorldResultKind.TECHNIQUE_ACQUIRED->TechniqueStore(db,campaign).playerTechniques(c.target.uid).any{it.techniqueUid==c.valueUid && it.baseMastery>=c.minimum} &&
            TechniqueStore(db,campaign).definitions().any{it.techniqueUid==c.valueUid && it.status==TechniqueDefinitionStatus.ACTIVE}
        NpcWorldResultKind.ITEM_OWNED->InventoryStore(db,campaign).typedUnique(c.target.uid).any{it.first.itemInstanceUid==c.valueUid}
        NpcWorldResultKind.COMBAT_DEFEAT->c.valueUid in setOf("INCAPACITATED","DEAD","CAPTURED","SURRENDERED") &&
            MechanicalActorStateStore(db,campaign).actor(c.target)?.conditions?.any{it.conditionUid==c.valueUid && it.intensity>0}==true
    }
    private fun ownerFingerprint(c:NpcWorldResultCriterion):String=phase60Hash(when(c.kind) {
        NpcWorldResultKind.SKILL_PROGRESS,NpcWorldResultKind.SKILL_MASTERY->SkillStore(db,campaign).playerSkills(c.target.uid).single{it.skillUid==c.valueUid}.toString()
        NpcWorldResultKind.TECHNIQUE_PROGRESS,NpcWorldResultKind.TECHNIQUE_ACQUIRED->TechniqueStore(db,campaign).playerTechniques(c.target.uid).single{it.techniqueUid==c.valueUid}.toString()
        NpcWorldResultKind.ITEM_OWNED->InventoryStore(db,campaign).typedUnique(c.target.uid).single{it.first.itemInstanceUid==c.valueUid}.toString()
        NpcWorldResultKind.KNOWLEDGE_ACQUIRED->db.rawQuery("SELECT state_version,latest_acquisition_uid,epistemic_state_uid FROM world_actor_knowledge_states WHERE campaign_uid=? AND holder_kind_uid=? AND holder_uid=? AND claim_uid=?",
            arrayOf(campaign,KnowledgeHolderKinds.CHARACTER,c.target.uid,c.valueUid)).use{it.moveToFirst();"${it.getLong(0)}|${it.getString(1)}|${it.getString(2)}"}
        else->requireNotNull(MechanicalActorStateStore(db,campaign).actor(c.target)).toString()
    })
    fun reconcile(brain:NpcBrainState,scope:TemporalScope):NpcBrainChange? {
        require(brain.campaignUid==scope.campaignUid)
        val proofs=brain.goals.mapNotNull { g->proof(brain,g,scope.baseCommitOrder)?.let{g.uid to it} }.toMap()
        if(proofs.isEmpty())return null
        val causes=proofs.values.distinct().map{NpcCauseRef(NpcCauseKind.COMMITTED_EVENT,it)}
        val after=brain.copy(revision=brain.revision+1,goals=brain.goals.map{g->proofs[g.uid]?.let{g.copy(lifecycle=NpcGoalLifecycle.ACHIEVED,cause=NpcCauseRef(NpcCauseKind.COMMITTED_EVENT,it))}?:g})
        return NpcBrainChange(campaign,brain.actor,scope.historyGenerationUid,brain.revision,NpcBrainCodec.fingerprint(brain),NpcBrainCodec.encode(after),
            NpcBrainRules.DOMAIN_RESULT.uid,NpcBrainRules.DOMAIN_RESULT.version,causes)
    }
}

/** Runs only for already participating NPCs and only at the initial boundary. Result changes join
 * the next normal TurnTransaction; it performs no SQL writes and no extra AI invocation. */
internal class NpcResultReconciliationProcess(private val expected:TemporalScope,private val at:WorldTimeTick,
    private val reconcile:(TemporalOwnerInput)->List<NpcBrainChange>) {
    fun extension()=TemporalProcessExtension(listOf(RegisteredTemporalOwner("1",object:WorldProcessOwnerPort {
        override val ownerUid="P62:NPC_DOMAIN_RESULTS"
        override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult {
            if(input.scope!=expected || input.actions.isNotEmpty() || input.deadlines.isNotEmpty())return TemporalOwnerResult.Unsupported("P61:RESULT_SCOPE")
            val changes=if(input.from==at && input.through==at)reconcile(input) else emptyList()
            return TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid,1,"{}"),changes)
        }
    })),null)
}
