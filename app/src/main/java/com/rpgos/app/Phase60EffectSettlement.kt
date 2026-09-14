package com.rpgos.app

/**
 * The time processor has already asked the domain owners about the actually elapsed interval.
 * Admission may reject their candidates, but must never silently omit them and still commit time.
 * On interruption, only those evaluated interval effects may be written: a precomputed final
 * action result is not evidence that the unfinished action happened.
 */
internal object Phase60EffectSettlement {
    fun validate(result: TemporalExecutionResult, admitted: List<PlayerDomainChangePayload>): String? {
        if (!result.readyForAdmission) return "P60:RESULT_NOT_ADMISSIBLE"
        val expected = result.checkpoint.candidateChanges
        if (expected.any { it is TemporalStateChange }) return "P60:DOMAIN_OWNER_CANNOT_WRITE_CLOCK"
        val actual = admitted.filterNot { it is TemporalStateChange }.groupingBy { it }.eachCount().toMutableMap()
        // Keep multiplicity: two identical deltas are two effects, not one set member.
        for (candidate in expected) {
            val count = actual[candidate] ?: return "P60:INTERVAL_EFFECT_DROPPED"
            if (count == 1) actual.remove(candidate) else actual[candidate] = count - 1
        }
        if (result.reason == TemporalStopReason.PLAYER_DECISION && actual.isNotEmpty())
            return "P60:UNEVALUATED_INTERRUPTION_EFFECT"
        return null
    }
}
