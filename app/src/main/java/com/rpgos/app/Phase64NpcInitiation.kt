package com.rpgos.app

/** Core projection of registered activities. Neither an organization nor a model can
 * grant a capability by naming a background rule. */
internal fun interface NpcBackgroundActivityPort {
    fun options(brain:NpcBrainState,records:List<NpcKnownRecord>,actor:MechanicalActorView,at:WorldTimeTick):List<NpcActionOption>
    companion object { val NONE=NpcBackgroundActivityPort { _,_,_,_->emptyList() } }
}

internal object Phase64NpcInitiation {
    /** Single-option facade for callers that do not enumerate the bounded choice set. */
    fun option(brain:NpcBrainState,records:List<NpcKnownRecord>,actor:MechanicalActorView,
        definition:BackgroundProcessDefinition):NpcActionOption? = options(brain,records,actor,definition).firstOrNull()

    /** The records are the actor's already protected projection. A known reference is not a
     * grant or proof that the target still exists; the ordinary owner preflight checks those.
     * No caller-supplied target or unprojected acquisition is accepted here. */
    fun options(brain:NpcBrainState,records:List<NpcKnownRecord>,actor:MechanicalActorView,
        definition:BackgroundProcessDefinition):List<NpcActionOption> {
        val action=definition.parameters[Phase64ProcessActivation.ACTION_KEY] ?: return emptyList()
        if(definition.parameters["activation_npc"]!="true" || action !in actor.executableAbilityUids ||
            actor.actor!=brain.actor || actor.campaignUid!=brain.campaignUid || actor.kind==MechanicalActorKind.ACTIVE_PLAYER)
            return emptyList()
        if(records.size>64 || records.map { it.uid }.distinct().size!=records.size)return emptyList()
        val projected=records.sortedWith(compareBy<NpcKnownRecord> { it.acquisitionUid }.thenBy { it.uid })
        val goal=brain.goals.filter { it.lifecycle==NpcGoalLifecycle.ACTIVE &&
            (it.cause.kind==NpcCauseKind.INTRINSIC_MOTIVATION && brain.motivations.any { motivation->motivation.uid==it.cause.uid } ||
                it.cause.kind==NpcCauseKind.KNOWLEDGE_ACQUISITION && projected.any { record->record.acquisitionUid==it.cause.uid }) }
            .sortedWith(compareByDescending<NpcGoal> { it.priority.basisPoints }.thenBy { it.uid }).firstOrNull() ?: return emptyList()
        val goalSource=projected.firstOrNull { it.acquisitionUid==goal.cause.uid }
        val recipe=definition.parameters.filterKeys { !it.startsWith("activation_") }.values
        val targetKind=definition.parameters["activation_target_kind"]
        val targetUid=definition.parameters["activation_target_uid"]
        val needsTarget=recipe.any { it=="@TARGET_UID" || it=="@TARGET_KIND" } || targetKind!=null || targetUid!=null
        val needsSource=recipe.any { it=="@OWN_ACQUISITION_UID" }
        if(needsSource && (definition.domain!="INFORMATION" || definition.operation!="REPORT" || actor.actor.kindUid=="PLAYER"))
            return emptyList()
        val candidates=when {
            needsTarget -> projected.flatMap { source->source.subjectRefs.map { target->Candidate(target,source) } }
                .sortedWith(compareBy<Candidate> { it.target.kindUid }.thenBy { it.target.uid }
                    .thenBy { it.source!!.acquisitionUid }.thenBy { it.source!!.uid })
            needsSource -> projected.map { Candidate(actor.actor,it) }
            else -> listOf(Candidate(actor.actor,goalSource))
        }
        // Without a source placeholder, one exact supporting record suffices for a target.
        // A report may instead offer distinct own acquisitions about that same known target.
        return candidates.filter { (targetKind==null || it.target.kindUid==targetKind) &&
            (targetUid==null || it.target.uid==targetUid) }.distinctBy { listOf(it.target.kindUid,it.target.uid,
            if(needsSource)it.source!!.acquisitionUid else "") }.take(4).map { candidate->
            val identity=listOf("P64:OPTION:2",definition.uid,definition.version.toString(),actor.actor.kindUid,
                actor.actor.uid,candidate.target.kindUid,candidate.target.uid,candidate.source?.acquisitionUid.orEmpty())
                .joinToString("") { "${it.length}:$it" }
            NpcActionOption("P64:OPTION:${phase63Hash(identity)}",action,candidate.target,
                AcceptedActionTiming(ActionDuration(1000),"P64:INITIATION_MS_V1",1),goal.uid,emptyList(),
                setOfNotNull(goalSource?.uid,candidate.source?.uid),
                routine=definition.parameters["activation_routine"]=="true",
                parameters=if(needsSource)mapOf("p64_source_acquisition_uid" to candidate.source!!.acquisitionUid) else emptyMap(),
                mechanicsOwnerUid="UNIVERSAL_ACTION",mechanicalEffectKindUid="INTERACTION")
        }
    }

    private data class Candidate(val target:DomainRef,val source:NpcKnownRecord?)
}
