package com.rpgos.app

import java.security.MessageDigest

internal const val PHASE60_FOREGROUND_OWNER="P60:ADMITTED_ACTION"

/** Only Core can select a proportional rule; unspecified effects remain atomic at completion. */
internal fun interface TemporalEffectPolicyPort {
    fun policy(effect:VerifiedMechanicsCommandEffect):TemporalAccrualPolicy
    companion object { val COMPLETION_ONLY=TemporalEffectPolicyPort { TemporalAccrualPolicy.AT_COMPLETION } }
}

internal object Phase60SegmentEffects {
    fun select(effects:List<VerifiedMechanicsCommandEffect>, work:TemporalExecutionCheckpoint,
               policies:Map<String,TemporalAccrualPolicy>):List<VerifiedMechanicsCommandEffect> {
        val schedule=work.schedule.associateBy { it.action.uid }
        return effects.mapNotNull { effect ->
            val interval=schedule[effect.nodeUid] ?: return@mapNotNull null
            if(work.reached < interval.start) return@mapNotNull null
            val offset=Phase60DomainTiming.effectOffset(effect,interval.action.timing.duration)
            if(work.reached >= interval.start+ActionDuration(offset)) return@mapNotNull effect
            if(policies.getValue(effect.effectUid)==TemporalAccrualPolicy.AT_COMPLETION) return@mapNotNull null
            val materialized=MechanicalEffectMaterializer.materialize(effect) as? MechanicalEffectMaterializationResult.Materialized
                ?: error("P60:UNMATERIALIZABLE_EFFECT")
            require(materialized.changes.size==1 && materialized.changes.single().payload.let { it is ResourceChange || it is MechanicalTrackChange }) {
                "P60:NONQUANTIFIED_PROPORTIONAL_EFFECT"
            }
            val input=TemporalOwnerInput(work.scope,work.startedAt,work.reached,listOf(interval),emptyList(),null)
            val units=Phase60Accrual.intervalUnits(TemporalAccrualContract(effect.proofUid,1,effect.magnitude,TemporalAccrualPolicy.PROPORTIONAL),input,interval)
            if(units==0L) return@mapNotNull null
            val fingerprint=phase60Hash("${work.executionFingerprint}|${effect.effectUid}|${work.reached.milliseconds}|$units")
            effect.copy(magnitude=units,canonicalPayload=effect.canonicalPayload+("magnitude" to units.toString()),
                proofUid="P60:INTERVAL:$fingerprint",deterministicInputFingerprint=fingerprint,deterministicOutputFingerprint=phase60Hash("$fingerprint|$units"))
        }
    }

    /** Process owners submit existing typed payloads, never arbitrary SQL or AI-supplied deltas. */
    fun background(changes:List<PlayerDomainChangePayload>, work:TemporalExecutionCheckpoint):List<VerifiedMechanicsCommandEffect> =
        changes.mapIndexed { index, change ->
            val target:DomainRef
            val magnitude:Long
            val kind:String
            val payload:Map<String,String>
            when(change) {
                is ResourceChange->{target=change.subject;magnitude=change.delta.units;kind="RESOURCE_DELTA";payload=mapOf("resource_uid" to change.resourceUid)}
                is MechanicalTrackChange->{target=change.subject;magnitude=change.delta.units;kind="PERSISTENT_EFFECT";payload=mapOf("effect_uid" to change.trackUid)}
                is ConditionChange->{target=change.subject;magnitude=1L;kind="CONDITION";payload=mapOf("condition_uid" to change.conditionUid,"operation" to change.operation.name)}
                else->error("P60:PROCESS_EFFECT_ADAPTER_REQUIRED")
            }
            val fingerprint=phase60Hash("${work.executionFingerprint}|$index|${TypedPlayerChangeRegistry.core().encodeWorkerPayload(change)}")
            VerifiedMechanicsCommandEffect("P60:PROCESS-EFFECT:$fingerprint","P60:BACKGROUND", "P60:PROCESS-OWNER",kind,target,magnitude,
                payload+("magnitude" to magnitude.toString()),"P60:PROCESS:$fingerprint",fingerprint,fingerprint)
        }
}

internal fun phase60Hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}

/** One scalar row per canonical key. Atomic effects retain their individual identities. */
internal fun phase60CoalesceEffects(effects:List<VerifiedMechanicsCommandEffect>):List<VerifiedMechanicsCommandEffect> {
    val grouped=linkedMapOf<Any,MutableList<VerifiedMechanicsCommandEffect>>()
    effects.forEachIndexed { index,effect ->
        val materialized=MechanicalEffectMaterializer.materialize(effect) as? MechanicalEffectMaterializationResult.Materialized
        val scalar=materialized?.changes?.singleOrNull()?.payload
        val key=when(scalar) {
            is ResourceChange->listOf("RESOURCE",scalar.subject.kindUid,scalar.subject.uid,scalar.resourceUid)
            is MechanicalTrackChange->listOf("TRACK",scalar.subject.kindUid,scalar.subject.uid,scalar.trackUid)
            else->index
        }
        grouped.getOrPut(key){mutableListOf()}.add(effect)
    }
    return grouped.values.mapNotNull { group ->
        if(group.size==1) return@mapNotNull group.single()
        val magnitude=group.fold(0L){sum,effect->Math.addExact(sum,effect.magnitude)}
        if(magnitude==0L)return@mapNotNull null
        val first=group.first()
        val fingerprint=phase60Hash(group.joinToString("|"){"${it.effectUid}:${it.proofUid}:${it.magnitude}"})
        val prefix=if(group.any{it.proofUid.startsWith("P60:PROCESS:")})"P60:PROCESS:" else "P60:SETTLEMENT:"
        first.copy(effectUid="P60:SUM:$fingerprint",magnitude=magnitude,
            canonicalPayload=first.canonicalPayload+("magnitude" to magnitude.toString())+mergedMechanicSourcePayload(group),
            proofUid=prefix+fingerprint,deterministicInputFingerprint=fingerprint,
            deterministicOutputFingerprint=phase60Hash("$fingerprint|$magnitude"))
    }
}
