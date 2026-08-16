package com.rpgos.app

import java.math.BigInteger
import java.util.Collections
import java.util.TreeMap

/** Phase-21 factor vocabulary. Values are explicit evidence; no hidden repetition/adaptation state exists. */
object Phase21ProgressionFactorKinds {
    const val DIMINISHING_RETURNS = "RPGOS-PROGRESSION-FACTOR:DIMINISHING_RETURNS"
    const val NOVELTY = "RPGOS-PROGRESSION-FACTOR:NOVELTY"
    const val ADAPTATION = "RPGOS-PROGRESSION-FACTOR:ADAPTATION"
    const val REPETITION = "RPGOS-PROGRESSION-FACTOR:REPETITION"
    const val FATIGUE_IMPACT = "RPGOS-PROGRESSION-FACTOR:FATIGUE_IMPACT"
    const val INJURY_IMPACT = "RPGOS-PROGRESSION-FACTOR:INJURY_IMPACT"
    const val ENVIRONMENT = "RPGOS-PROGRESSION-FACTOR:ENVIRONMENT"

    val supported: Set<String> = Collections.unmodifiableSet(linkedSetOf(
        DIMINISHING_RETURNS, NOVELTY, ADAPTATION, REPETITION,
        FATIGUE_IMPACT, INJURY_IMPACT, ENVIRONMENT
    ))
}

/** Already-resolved external evidence mapped into the existing Phase-20 factor primitive. */
data class Phase21ProgressionFactorEvidence(
    val factorKindUid: String,
    val evidenceUid: String,
    val sourceValue: ProgressionScaledValue,
    val appliedFactor: ProgressionScaledValue,
    val policyUid: String,
    val policyVersion: String
) {
    init {
        require(factorKindUid in Phase21ProgressionFactorKinds.supported)
        require(evidenceUid.isNotBlank())
        require(policyUid.isNotBlank() && policyVersion.isNotBlank())
    }

    fun asCalculationFactor(): ProgressionCalculationFactor = ProgressionCalculationFactor(
        factorKindUid, evidenceUid, sourceValue, appliedFactor
    )
}

/**
 * Deterministic Core-owned diminishing-returns policy.
 * Explicit repetitionCount is evidence, never process-local memory.
 */
object Phase21DiminishingReturnsPolicy {
    const val POLICY_UID = "RPGOS-CORE:PHASE21_DIMINISHING_RETURNS"
    const val POLICY_VERSION = "1"

    fun factor(
        evidenceUid: String,
        repetitionCount: Long,
        resistanceUnits: Long,
        floorFactor: ProgressionScaledValue = ProgressionScaledValue.zero()
    ): Phase21ProgressionFactorEvidence {
        require(evidenceUid.isNotBlank())
        require(repetitionCount >= 0L)
        require(resistanceUnits > 0L)
        require(floorFactor.scaledUnits <= ProgressionNumericPolicy.SCALE)
        val denominator = BigInteger.valueOf(resistanceUnits).add(BigInteger.valueOf(repetitionCount))
        val scaled = BigInteger.valueOf(ProgressionNumericPolicy.SCALE)
            .multiply(BigInteger.valueOf(resistanceUnits))
            .add(denominator.divide(BigInteger.valueOf(2L)))
            .divide(denominator)
            .longValueExact()
        val applied = maxOf(scaled, floorFactor.scaledUnits)
        return Phase21ProgressionFactorEvidence(
            Phase21ProgressionFactorKinds.DIMINISHING_RETURNS,
            evidenceUid,
            ProgressionScaledValue.ofScaled(repetitionCount),
            ProgressionScaledValue.ofScaled(applied),
            POLICY_UID,
            POLICY_VERSION
        )
    }
}

/** Immutable external causal facts. This contract does not advance time or query a clock. */
class PassiveProgressionHookInput private constructor(
    val campaignUid: String,
    val characterUid: String,
    val sourceEventUid: String,
    val causeUid: String,
    val sourceTypeUid: String,
    val sourceChannelUid: String,
    val subject: DomainRef,
    val targetKindUid: String,
    val targetUid: String,
    val progressionDomainUid: String?,
    val progressionDomainWorldPackUid: String?,
    val targetValueEvidence: ProgressionTargetValueEvidence,
    val progressSemanticsUid: String,
    val progressSemanticsVersion: String,
    val effortUnits: Long,
    val durationUnits: Long?,
    val intensity: ProgressionScaledValue?,
    val methodUid: String?,
    factorEvidence: List<Phase21ProgressionFactorEvidence>,
    evidenceRefs: List<DomainRef>,
    val progressionPolicyUid: String,
    val progressionPolicyVersion: String,
    val expectedWorldPackUid: String?,
    val expectedWorldPackVersion: String?,
    dependencyVersions: Map<String, String>
) {
    val factorEvidence: List<Phase21ProgressionFactorEvidence> = Collections.unmodifiableList(
        ArrayList(factorEvidence.sortedWith(compareBy(
            { it.factorKindUid }, { it.evidenceUid },
            { it.sourceValue.scaledUnits }, { it.appliedFactor.scaledUnits },
            { it.policyUid }, { it.policyVersion }
        )))
    )
    val evidenceRefs: List<DomainRef> = Collections.unmodifiableList(
        ArrayList(evidenceRefs.distinct().sortedWith(compareBy({ it.kindUid }, { it.uid })))
    )
    val dependencyVersions: Map<String, String> = Collections.unmodifiableMap(TreeMap(dependencyVersions))

    val inputFingerprint: String = progressionFingerprint(
        "PHASE21_PASSIVE_HOOK_INPUT", campaignUid, characterUid, sourceEventUid, causeUid,
        sourceTypeUid, sourceChannelUid, subject.kindUid, subject.uid, targetKindUid, targetUid,
        progressionDomainUid ?: "<NULL>", progressionDomainWorldPackUid ?: "<NULL>",
        targetValueEvidence.evidenceUid, targetValueEvidence.canonicalValue,
        targetValueEvidence.semanticsUid, targetValueEvidence.semanticsVersion,
        progressSemanticsUid, progressSemanticsVersion, effortUnits.toString(),
        durationUnits?.toString() ?: "<NULL>", intensity?.scaledUnits?.toString() ?: "<NULL>",
        methodUid ?: "<NULL>",
        this.factorEvidence.joinToString(",") {
            progressionFingerprint("PHASE21_FACTOR_EVIDENCE", it.factorKindUid, it.evidenceUid,
                it.sourceValue.scaledUnits.toString(), it.appliedFactor.scaledUnits.toString(),
                it.policyUid, it.policyVersion)
        },
        this.evidenceRefs.joinToString(",") { "${it.kindUid}:${it.uid}" },
        progressionPolicyUid, progressionPolicyVersion,
        expectedWorldPackUid ?: "<NULL>", expectedWorldPackVersion ?: "<NULL>",
        this.dependencyVersions.entries.joinToString(",") { "${it.key}=${it.value}" }
    )

    init {
        require(campaignUid.isNotBlank() && characterUid.isNotBlank())
        require(sourceEventUid.isNotBlank() && causeUid.isNotBlank())
        require(sourceTypeUid.isNotBlank() && sourceChannelUid.isNotBlank())
        require(subject.kindUid == "PLAYER" && subject.uid == characterUid)
        require(targetKindUid in ProgressionTargetKinds.supported && targetUid.isNotBlank())
        require(progressionDomainUid?.isBlank() != true && progressionDomainWorldPackUid?.isBlank() != true)
        require((progressionDomainUid == null) == (progressionDomainWorldPackUid == null))
        require(progressSemanticsUid.isNotBlank() && progressSemanticsVersion.isNotBlank())
        require(effortUnits >= 0L)
        require(durationUnits == null || durationUnits >= 0L)
        require(methodUid?.isBlank() != true)
        require(progressionPolicyUid.isNotBlank() && progressionPolicyVersion.isNotBlank())
        require((expectedWorldPackUid == null) == (expectedWorldPackVersion == null))
        require(expectedWorldPackUid?.isBlank() != true && expectedWorldPackVersion?.isBlank() != true)
        require(this.evidenceRefs.all { it.kindUid.isNotBlank() && it.uid.isNotBlank() })
        require(this.dependencyVersions.all { it.key.isNotBlank() && it.value.isNotBlank() })
    }

    companion object {
        fun create(
            campaignUid: String,
            characterUid: String,
            sourceEventUid: String,
            causeUid: String,
            sourceTypeUid: String,
            sourceChannelUid: String,
            subject: DomainRef,
            targetKindUid: String,
            targetUid: String,
            progressionDomainUid: String? = null,
            progressionDomainWorldPackUid: String? = null,
            targetValueEvidence: ProgressionTargetValueEvidence,
            progressSemanticsUid: String,
            progressSemanticsVersion: String,
            effortUnits: Long,
            durationUnits: Long? = null,
            intensity: ProgressionScaledValue? = null,
            methodUid: String? = null,
            factorEvidence: List<Phase21ProgressionFactorEvidence> = emptyList(),
            evidenceRefs: List<DomainRef> = emptyList(),
            progressionPolicyUid: String,
            progressionPolicyVersion: String,
            expectedWorldPackUid: String? = null,
            expectedWorldPackVersion: String? = null,
            dependencyVersions: Map<String, String> = emptyMap()
        ) = PassiveProgressionHookInput(
            campaignUid, characterUid, sourceEventUid, causeUid, sourceTypeUid, sourceChannelUid,
            subject, targetKindUid, targetUid, progressionDomainUid, progressionDomainWorldPackUid,
            targetValueEvidence, progressSemanticsUid, progressSemanticsVersion, effortUnits,
            durationUnits, intensity, methodUid, factorEvidence, evidenceRefs, progressionPolicyUid,
            progressionPolicyVersion, expectedWorldPackUid, expectedWorldPackVersion, dependencyVersions
        )
    }
}

/** Pure adapter: resolved external causal facts -> canonical Phase-20 stimulus. */
object PassiveProgressionHook {
    const val HOOK_UID = "RPGOS-CORE:PASSIVE_PROGRESSION_HOOK"
    const val HOOK_VERSION = "1"

    fun evaluate(input: PassiveProgressionHookInput): ProgressionStimulus {
        val stimulusUid = "RPGOS-STIMULUS:PASSIVE:" + progressionFingerprint(
            "PHASE21_PASSIVE_STIMULUS", HOOK_UID, HOOK_VERSION, input.inputFingerprint
        )
        return ProgressionStimulus.create(
            stimulusUid = stimulusUid,
            sourceTypeUid = input.sourceTypeUid,
            sourceChannelUid = input.sourceChannelUid,
            subject = input.subject,
            targetKindUid = input.targetKindUid,
            targetUid = input.targetUid,
            progressionDomainUid = input.progressionDomainUid,
            progressionDomainWorldPackUid = input.progressionDomainWorldPackUid,
            targetValueEvidence = input.targetValueEvidence,
            progressSemanticsUid = input.progressSemanticsUid,
            progressSemanticsVersion = input.progressSemanticsVersion,
            effortUnits = input.effortUnits,
            durationUnits = input.durationUnits,
            intensity = input.intensity,
            methodUid = input.methodUid,
            calculationFactors = input.factorEvidence.map { it.asCalculationFactor() },
            evidenceRefs = input.evidenceRefs,
            progressionPolicyUid = input.progressionPolicyUid,
            progressionPolicyVersion = input.progressionPolicyVersion,
            expectedWorldPackUid = input.expectedWorldPackUid,
            expectedWorldPackVersion = input.expectedWorldPackVersion,
            dependencyVersions = input.dependencyVersions + mapOf(
                "RPGOS-DEPENDENCY:PASSIVE_HOOK" to HOOK_VERSION
            )
        )
    }
}
