package com.rpgos.app

/** A domain-approved full effect plus its explicit timing rule, not an AI proposal. */
internal data class TemporalMechanicalAccrualBinding(
    val effectUid:String,
    val actionUid:String,
    val fullEffect:PlayerDomainChangePayload,
    val accrual:TemporalAccrualContract
) {
    init {
        require(effectUid.isNotBlank() && actionUid.isNotBlank())
        val units = when(fullEffect) {
            is ResourceChange -> fullEffect.delta.units
            is MechanicalTrackChange -> fullEffect.delta.units
            else -> error("P60:MECHANIC_HAS_NO_QUANTIFIED_ACCRUAL")
        }
        require(units == accrual.totalUnits) { "P60:ACCRUAL_MAGNITUDE_MISMATCH" }
    }
}

/**
 * Adapter for already quantified resource/track mechanics. It never decides success, invents
 * a rate, creates a skill, or writes storage. Deadlines belong to their registered process owner.
 * Bindings must be supplied again by the domain owner when rebuilding an interrupted run.
 */
internal class Phase60MechanicalAccrualOwner(
    override val ownerUid:String,
    private val campaignUid:String,
    bindings:List<TemporalMechanicalAccrualBinding>
) : WorldProcessOwnerPort {
    private val bindings = bindings.sortedBy { it.effectUid }.toList()
    init {
        require(ownerUid.isNotBlank() && campaignUid.isNotBlank())
        require(bindings.size <= 100_000 && bindings.map { it.effectUid }.distinct().size == bindings.size)
    }
    override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult {
        if(input.scope.campaignUid != campaignUid) return TemporalOwnerResult.Unsupported("P60:CROSS_CAMPAIGN_ACCRUAL")
        if(input.deadlines.isNotEmpty()) return TemporalOwnerResult.Unsupported("P60:ACCRUAL_IS_NOT_DEADLINE_OWNER")
        if(input.actions.any { it.action.ownerUid != ownerUid }) return TemporalOwnerResult.Unsupported("P60:FOREIGN_ACCRUAL_ACTION")
        if(input.previous != null && (input.previous.ownerUid != ownerUid || input.previous.version != 1))
            return TemporalOwnerResult.Unsupported("P60:ACCRUAL_STATE_VERSION")
        val active = input.actions.associateBy { it.action.uid }
        val changes = bindings.mapNotNull { binding ->
            val action = active[binding.actionUid] ?: return@mapNotNull null
            val delta = Phase60Accrual.intervalUnits(binding.accrual,input,action)
            if(delta == 0L) return@mapNotNull null
            when(val full = binding.fullEffect) {
                is ResourceChange -> full.copy(delta=ExactLongDelta.of(delta))
                is MechanicalTrackChange -> full.copy(delta=ExactLongDelta.of(delta))
                else -> error("P60:MECHANIC_HAS_NO_QUANTIFIED_ACCRUAL")
            }
        }
        return TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid,1,input.through.milliseconds.toString()),changes)
    }
}
