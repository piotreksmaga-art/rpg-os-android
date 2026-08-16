package com.rpgos.app

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.MessageDigest
import java.util.Collections
import java.util.TreeMap

class ProgressionStructuralException(val code: String, cause: Throwable? = null) : IllegalArgumentException(code, cause)

object ProgressionTargetKinds {
    const val STAT = "STAT"
    const val SKILL = "SKILL"
    const val TECHNIQUE = "TECHNIQUE"
    val supported: Set<String> = Collections.unmodifiableSet(linkedSetOf(STAT, SKILL, TECHNIQUE))
}

object ProgressionSourceChannels {
    const val TRAINING = "RPGOS-PROGRESSION-CHANNEL:TRAINING"
    const val PRACTICE = "RPGOS-PROGRESSION-CHANNEL:PRACTICE"
    const val PROJECT = "RPGOS-PROGRESSION-CHANNEL:PROJECT"
    const val COMBAT = "RPGOS-PROGRESSION-CHANNEL:COMBAT"
}

object ProgressionFactorKinds {
    const val TALENT = "RPGOS-PROGRESSION-FACTOR:TALENT"
    const val POTENTIAL = "RPGOS-PROGRESSION-FACTOR:POTENTIAL"
    const val DIFFICULTY = "RPGOS-PROGRESSION-FACTOR:DIFFICULTY"
    const val QUALITY = "RPGOS-PROGRESSION-FACTOR:QUALITY"
    const val OUTCOME = "RPGOS-PROGRESSION-FACTOR:OUTCOME"
}

data class ProgressionScaledValue private constructor(val scaledUnits: Long) {
    init { if (scaledUnits < 0L) fail("NEGATIVE_PROGRESSION_NUMERIC_VALUE") }

    companion object {
        fun ofScaled(scaledUnits: Long): ProgressionScaledValue = ProgressionScaledValue(scaledUnits)
        fun one(): ProgressionScaledValue = ProgressionScaledValue(ProgressionNumericPolicy.SCALE)
        fun zero(): ProgressionScaledValue = ProgressionScaledValue(0L)
        fun fromDouble(value: Double): ProgressionScaledValue = ProgressionNumericPolicy.fromDouble(value)
        private fun fail(code: String): Nothing = throw ProgressionStructuralException(code)
    }
}

object ProgressionNumericPolicy {
    const val POLICY_UID = "RPGOS-PROGRESSION-NUMERIC:FIXED_1E6_HALF_UP"
    const val POLICY_VERSION = "1"
    const val SCALE = 1_000_000L
    const val ROUNDING_UID = "RPGOS-ROUNDING:HALF_UP"

    fun fromDouble(value: Double): ProgressionScaledValue {
        if (!value.isFinite()) fail("NON_FINITE_PROGRESSION_NUMERIC_VALUE")
        if (value < 0.0) fail("NEGATIVE_PROGRESSION_NUMERIC_VALUE")
        val scaled = try {
            BigDecimal.valueOf(value)
                .multiply(BigDecimal.valueOf(SCALE))
                .setScale(0, RoundingMode.HALF_UP)
                .toBigIntegerExact()
        } catch (e: ArithmeticException) {
            throw ProgressionStructuralException("PROGRESSION_NUMERIC_CONVERSION_FAILURE", e)
        }
        if (value > 0.0 && scaled == BigInteger.ZERO) fail("PROGRESSION_NUMERIC_UNDERFLOW")
        if (scaled > LONG_MAX) fail("PROGRESSION_NUMERIC_OVERFLOW")
        return ProgressionScaledValue.ofScaled(scaled.longValueExact())
    }

    internal fun applyFactors(baseGrantUnits: Long, factors: List<ProgressionCalculationFactor>): Long {
        if (baseGrantUnits < 0L) fail("NEGATIVE_BASE_PROGRESSION_GRANT")
        if (baseGrantUnits == 0L) return 0L
        var numerator = BigInteger.valueOf(baseGrantUnits)
        var denominator = BigInteger.ONE
        factors.forEach { factor ->
            numerator = numerator.multiply(BigInteger.valueOf(factor.appliedFactor.scaledUnits))
            denominator = denominator.multiply(BIG_SCALE)
        }
        if (numerator == BigInteger.ZERO) return 0L
        val rounded = numerator.add(denominator.divide(TWO)).divide(denominator)
        if (rounded > LONG_MAX) fail("PROGRESSION_GRANT_OVERFLOW")
        return rounded.longValueExact()
    }

    private val BIG_SCALE = BigInteger.valueOf(SCALE)
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private val TWO = BigInteger.valueOf(2L)
    private fun fail(code: String): Nothing = throw ProgressionStructuralException(code)
}

data class ProgressionTargetValueEvidence(
    val evidenceUid: String,
    val canonicalValue: String,
    val semanticsUid: String,
    val semanticsVersion: String
) {
    init {
        require(evidenceUid.isNotBlank())
        require(canonicalValue.isNotBlank())
        require(semanticsUid.isNotBlank())
        require(semanticsVersion.isNotBlank())
    }
}

data class ProgressionCalculationFactor(
    val factorKindUid: String,
    val evidenceUid: String,
    val sourceValue: ProgressionScaledValue,
    val appliedFactor: ProgressionScaledValue
) {
    init {
        require(factorKindUid.isNotBlank())
        require(evidenceUid.isNotBlank())
    }

    internal fun fingerprint(): String = progressionFingerprint("PROGRESSION_CALCULATION_FACTOR",
        factorKindUid, evidenceUid, sourceValue.scaledUnits.toString(), appliedFactor.scaledUnits.toString(),
        ProgressionNumericPolicy.POLICY_UID, ProgressionNumericPolicy.POLICY_VERSION
    )
}

class ProgressionProfileModifierEvidence private constructor(
    val axis: ProgressionProfileAxis,
    val evidenceUid: String,
    val campaignUid: String,
    val characterUid: String,
    val domainUid: String,
    val dimensionUid: String?,
    val sourceValue: ProgressionScaledValue,
    val appliedFactor: ProgressionScaledValue,
    val entryVersion: Long,
    val provenance: String
) {
    init {
        require(evidenceUid.isNotBlank() && campaignUid.isNotBlank() && characterUid.isNotBlank() && domainUid.isNotBlank())
        require(dimensionUid?.isBlank() != true)
        require(entryVersion >= 1L)
        require(provenance.isNotBlank())
    }

    internal fun asCalculationFactor(): ProgressionCalculationFactor = ProgressionCalculationFactor(
        if (axis == ProgressionProfileAxis.TALENT) ProgressionFactorKinds.TALENT else ProgressionFactorKinds.POTENTIAL,
        evidenceUid,
        sourceValue,
        appliedFactor
    )

    companion object {
        fun fromTalent(entry: TalentEntry, appliedFactor: ProgressionScaledValue): ProgressionProfileModifierEvidence {
            ProgressionProfilePolicy.validate(entry)
            return ProgressionProfileModifierEvidence(
                ProgressionProfileAxis.TALENT,
                "RPGOS-TALENT-EVIDENCE:" + progressionFingerprint("TALENT_ENTRY", entry.campaignId, entry.characterUid, entry.domainUid, entry.baseValue.toString(), entry.entryVersion.toString(), entry.provenance),
                entry.campaignId, entry.characterUid, entry.domainUid, null,
                ProgressionScaledValue.fromDouble(entry.baseValue), appliedFactor, entry.entryVersion, entry.provenance
            )
        }

        fun fromPotential(entry: PotentialEntry, appliedFactor: ProgressionScaledValue): ProgressionProfileModifierEvidence {
            ProgressionProfilePolicy.validate(entry)
            return ProgressionProfileModifierEvidence(
                ProgressionProfileAxis.POTENTIAL,
                "RPGOS-POTENTIAL-EVIDENCE:" + progressionFingerprint("POTENTIAL_ENTRY", entry.campaignId, entry.characterUid, entry.domainUid, entry.dimensionUid, entry.baseValue.toString(), entry.entryVersion.toString(), entry.provenance),
                entry.campaignId, entry.characterUid, entry.domainUid, entry.dimensionUid,
                ProgressionScaledValue.fromDouble(entry.baseValue), appliedFactor, entry.entryVersion, entry.provenance
            )
        }
    }
}

class ProgressionStimulus private constructor(
    val stimulusUid: String,
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
    val effortUnits: Long?,
    val durationUnits: Long?,
    val intensity: ProgressionScaledValue?,
    val methodUid: String?,
    calculationFactors: List<ProgressionCalculationFactor>,
    val talentEvidence: ProgressionProfileModifierEvidence?,
    val potentialEvidence: ProgressionProfileModifierEvidence?,
    evidenceRefs: List<DomainRef>,
    val progressionPolicyUid: String,
    val progressionPolicyVersion: String,
    val expectedWorldPackUid: String?,
    val expectedWorldPackVersion: String?,
    dependencyVersions: Map<String, String>
) {
    val calculationFactors: List<ProgressionCalculationFactor> = immutableProgressionList(calculationFactors.sortedWith(compareBy({ it.factorKindUid }, { it.evidenceUid })))
    val evidenceRefs: List<DomainRef> = immutableProgressionList(evidenceRefs)
    val dependencyVersions: Map<String, String> = Collections.unmodifiableMap(TreeMap(dependencyVersions))

    init {
        require(stimulusUid.isNotBlank() && sourceTypeUid.isNotBlank() && sourceChannelUid.isNotBlank())
        require(subject.kindUid.isNotBlank() && subject.uid.isNotBlank())
        require(targetKindUid.isNotBlank() && targetUid.isNotBlank())
        require(progressionDomainUid?.isBlank() != true && progressionDomainWorldPackUid?.isBlank() != true)
        require(progressSemanticsUid.isNotBlank() && progressSemanticsVersion.isNotBlank())
        require(effortUnits == null || effortUnits >= 0L)
        require(durationUnits == null || durationUnits >= 0L)
        require(methodUid?.isBlank() != true)
        require(this.evidenceRefs.all { it.kindUid.isNotBlank() && it.uid.isNotBlank() })
        require(progressionPolicyUid.isNotBlank() && progressionPolicyVersion.isNotBlank())
        require(expectedWorldPackUid?.isBlank() != true && expectedWorldPackVersion?.isBlank() != true)
        require((expectedWorldPackUid == null) == (expectedWorldPackVersion == null))
        require(this.dependencyVersions.all { it.key.isNotBlank() && it.value.isNotBlank() })
    }

    companion object {
        fun create(
            stimulusUid: String,
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
            effortUnits: Long? = null,
            durationUnits: Long? = null,
            intensity: ProgressionScaledValue? = null,
            methodUid: String? = null,
            calculationFactors: List<ProgressionCalculationFactor> = emptyList(),
            talentEvidence: ProgressionProfileModifierEvidence? = null,
            potentialEvidence: ProgressionProfileModifierEvidence? = null,
            evidenceRefs: List<DomainRef> = emptyList(),
            progressionPolicyUid: String,
            progressionPolicyVersion: String,
            expectedWorldPackUid: String? = null,
            expectedWorldPackVersion: String? = null,
            dependencyVersions: Map<String, String> = emptyMap()
        ): ProgressionStimulus = ProgressionStimulus(
            stimulusUid, sourceTypeUid, sourceChannelUid, subject, targetKindUid, targetUid,
            progressionDomainUid, progressionDomainWorldPackUid, targetValueEvidence,
            progressSemanticsUid, progressSemanticsVersion, effortUnits, durationUnits, intensity, methodUid,
            calculationFactors, talentEvidence, potentialEvidence, evidenceRefs,
            progressionPolicyUid, progressionPolicyVersion, expectedWorldPackUid, expectedWorldPackVersion,
            dependencyVersions
        )
    }
}

class ProgressionEvaluationInput private constructor(
    val campaignUid: String,
    val characterUid: String,
    val sourceTypeUid: String,
    val sourceChannelUid: String,
    val stimulusUid: String,
    val sourceCommandUid: String,
    val commandKindUid: String,
    val commandFingerprint: String,
    val targetKindUid: String,
    val targetUid: String,
    val progressionDomainUid: String?,
    val targetValueEvidence: ProgressionTargetValueEvidence,
    val progressSemanticsUid: String,
    val progressSemanticsVersion: String,
    val effortUnits: Long?,
    val durationUnits: Long?,
    val intensity: ProgressionScaledValue?,
    val methodUid: String?,
    calculationFactors: List<ProgressionCalculationFactor>,
    val talentEvidence: ProgressionProfileModifierEvidence?,
    val potentialEvidence: ProgressionProfileModifierEvidence?,
    val worldPackUid: String?,
    val worldPackVersion: String?,
    val worldPackBindingIdentity: String,
    val progressionPolicyUid: String,
    val progressionPolicyVersion: String,
    val progressionEngineUid: String,
    val progressionEngineVersion: String,
    dependencyVersions: Map<String, String>
) {
    val calculationFactors: List<ProgressionCalculationFactor> = immutableProgressionList(calculationFactors.sortedWith(compareBy({ it.factorKindUid }, { it.evidenceUid })))
    val dependencyVersions: Map<String, String> = Collections.unmodifiableMap(TreeMap(dependencyVersions))

    val inputFingerprint: String = progressionFingerprint(
        "PROGRESSION_EVALUATION_INPUT", campaignUid, characterUid, sourceTypeUid, sourceChannelUid,
        stimulusUid, sourceCommandUid, commandKindUid, commandFingerprint, targetKindUid, targetUid,
        progressionDomainUid ?: "<NULL>", targetValueEvidence.evidenceUid, targetValueEvidence.canonicalValue,
        targetValueEvidence.semanticsUid, targetValueEvidence.semanticsVersion,
        progressSemanticsUid, progressSemanticsVersion,
        effortUnits?.toString() ?: "<NULL>", durationUnits?.toString() ?: "<NULL>",
        intensity?.scaledUnits?.toString() ?: "<NULL>", methodUid ?: "<NULL>",
        this.calculationFactors.joinToString(",") { it.fingerprint() },
        talentEvidence?.evidenceUid ?: "<NULL>", talentEvidence?.appliedFactor?.scaledUnits?.toString() ?: "<NULL>",
        potentialEvidence?.evidenceUid ?: "<NULL>", potentialEvidence?.appliedFactor?.scaledUnits?.toString() ?: "<NULL>",
        worldPackUid ?: "<NULL>", worldPackVersion ?: "<NULL>", worldPackBindingIdentity,
        progressionPolicyUid, progressionPolicyVersion, progressionEngineUid, progressionEngineVersion,
        this.dependencyVersions.entries.joinToString(",") { "${it.key}=${it.value}" },
        ProgressionNumericPolicy.POLICY_UID, ProgressionNumericPolicy.POLICY_VERSION, ProgressionNumericPolicy.ROUNDING_UID
    )

    init {
        require(campaignUid.isNotBlank() && characterUid.isNotBlank())
        require(sourceTypeUid.isNotBlank() && sourceChannelUid.isNotBlank() && stimulusUid.isNotBlank())
        require(sourceCommandUid.isNotBlank() && commandKindUid.isNotBlank() && commandFingerprint.isNotBlank())
        require(targetKindUid.isNotBlank() && targetUid.isNotBlank())
        require(progressionDomainUid?.isBlank() != true)
        require(progressSemanticsUid.isNotBlank() && progressSemanticsVersion.isNotBlank())
        require(effortUnits == null || effortUnits >= 0L)
        require(durationUnits == null || durationUnits >= 0L)
        require(methodUid?.isBlank() != true)
        require((worldPackUid == null) == (worldPackVersion == null))
        require(worldPackBindingIdentity.isNotBlank())
        require(progressionPolicyUid.isNotBlank() && progressionPolicyVersion.isNotBlank())
        require(progressionEngineUid.isNotBlank() && progressionEngineVersion.isNotBlank())
        require(this.dependencyVersions.all { it.key.isNotBlank() && it.value.isNotBlank() })
    }

    companion object {
        fun create(
            campaignUid: String,
            characterUid: String,
            sourceTypeUid: String,
            sourceChannelUid: String,
            stimulusUid: String,
            sourceCommandUid: String,
            commandKindUid: String,
            commandFingerprint: String,
            targetKindUid: String,
            targetUid: String,
            progressionDomainUid: String? = null,
            targetValueEvidence: ProgressionTargetValueEvidence,
            progressSemanticsUid: String,
            progressSemanticsVersion: String,
            effortUnits: Long? = null,
            durationUnits: Long? = null,
            intensity: ProgressionScaledValue? = null,
            methodUid: String? = null,
            calculationFactors: List<ProgressionCalculationFactor> = emptyList(),
            talentEvidence: ProgressionProfileModifierEvidence? = null,
            potentialEvidence: ProgressionProfileModifierEvidence? = null,
            worldPackUid: String? = null,
            worldPackVersion: String? = null,
            worldPackBindingIdentity: String,
            progressionPolicyUid: String,
            progressionPolicyVersion: String,
            progressionEngineUid: String,
            progressionEngineVersion: String,
            dependencyVersions: Map<String, String> = emptyMap()
        ): ProgressionEvaluationInput = ProgressionEvaluationInput(
            campaignUid, characterUid, sourceTypeUid, sourceChannelUid, stimulusUid,
            sourceCommandUid, commandKindUid, commandFingerprint, targetKindUid, targetUid,
            progressionDomainUid, targetValueEvidence, progressSemanticsUid, progressSemanticsVersion,
            effortUnits, durationUnits, intensity, methodUid, calculationFactors, talentEvidence, potentialEvidence,
            worldPackUid, worldPackVersion, worldPackBindingIdentity, progressionPolicyUid,
            progressionPolicyVersion, progressionEngineUid, progressionEngineVersion, dependencyVersions
        )
    }
}

data class ProgressionGrant(
    val grantUid: String,
    val causalChangeUid: String,
    val progressionUid: String,
    val campaignUid: String,
    val characterUid: String,
    val targetKindUid: String,
    val targetUid: String,
    val grantUnits: Long,
    val progressSemanticsUid: String,
    val progressSemanticsVersion: String,
    val progressionDomainUid: String?,
    val sourceChannelUid: String,
    val stimulusUid: String,
    val policyUid: String,
    val computationFingerprint: String
) {
    init {
        require(grantUid.isNotBlank() && causalChangeUid.isNotBlank() && progressionUid.isNotBlank())
        require(campaignUid.isNotBlank() && characterUid.isNotBlank())
        require(targetKindUid.isNotBlank() && targetUid.isNotBlank())
        require(grantUnits > 0L)
        require(progressSemanticsUid.isNotBlank() && progressSemanticsVersion.isNotBlank())
        require(progressionDomainUid?.isBlank() != true)
        require(sourceChannelUid.isNotBlank() && stimulusUid.isNotBlank() && policyUid.isNotBlank())
        require(computationFingerprint.isNotBlank())
    }
}

data class ProgressionComputationRecord(
    val computationUid: String,
    val inputFingerprint: String,
    val baseGrantUnits: Long,
    val finalGrantUnits: Long,
    val numericPolicyUid: String,
    val numericPolicyVersion: String,
    val roundingUid: String,
    val computationFingerprint: String
)

class ProgressionResult private constructor(
    val progressionUid: String,
    grants: List<ProgressionGrant>,
    ledgerIntents: List<PlayerLedgerIntent>,
    computationRecords: List<ProgressionComputationRecord>,
    val inputFingerprint: String,
    val resultFingerprint: String
) {
    val grants: List<ProgressionGrant> = immutableProgressionList(grants)
    val ledgerIntents: List<PlayerLedgerIntent> = immutableProgressionList(ledgerIntents)
    val computationRecords: List<ProgressionComputationRecord> = immutableProgressionList(computationRecords)

    override fun equals(other: Any?): Boolean = other is ProgressionResult &&
        progressionUid == other.progressionUid && grants == other.grants && ledgerIntents == other.ledgerIntents &&
        computationRecords == other.computationRecords && inputFingerprint == other.inputFingerprint &&
        resultFingerprint == other.resultFingerprint

    override fun hashCode(): Int = arrayOf(progressionUid, grants, ledgerIntents, computationRecords, inputFingerprint, resultFingerprint).contentHashCode()

    internal companion object {
        fun create(
            progressionUid: String,
            grants: List<ProgressionGrant>,
            ledgerIntents: List<PlayerLedgerIntent>,
            computationRecords: List<ProgressionComputationRecord>,
            inputFingerprint: String,
            resultFingerprint: String
        ) = ProgressionResult(progressionUid, grants, ledgerIntents, computationRecords, inputFingerprint, resultFingerprint)
    }
}

class ProgressionEngine(
    val engineUid: String = ENGINE_UID,
    val engineVersion: String = ENGINE_VERSION
) {
    init {
        require(engineUid.isNotBlank() && engineVersion.isNotBlank())
    }

    fun evaluate(input: ProgressionEvaluationInput): ProgressionResult {
        validateInput(input)
        val base = input.effortUnits ?: fail("MISSING_CAUSAL_PROGRESSION_EFFORT")
        val factors = buildList {
            addAll(input.calculationFactors)
            input.talentEvidence?.let { add(it.asCalculationFactor()) }
            input.potentialEvidence?.let { add(it.asCalculationFactor()) }
        }.sortedWith(compareBy({ it.factorKindUid }, { it.evidenceUid }))
        val finalGrant = ProgressionNumericPolicy.applyFactors(base, factors)
        val progressionUid = "RPGOS-PROGRESSION:" + progressionFingerprint("PROGRESSION_UID", input.inputFingerprint)
        val computationFingerprint = progressionFingerprint(
            "PROGRESSION_COMPUTATION", input.inputFingerprint, base.toString(), finalGrant.toString(),
            factors.joinToString(",") { it.fingerprint() }, ProgressionNumericPolicy.POLICY_UID,
            ProgressionNumericPolicy.POLICY_VERSION, ProgressionNumericPolicy.ROUNDING_UID,
            engineUid, engineVersion, input.progressionPolicyUid, input.progressionPolicyVersion
        )
        val computation = ProgressionComputationRecord(
            "RPGOS-PROGRESSION-COMPUTATION:" + computationFingerprint,
            input.inputFingerprint, base, finalGrant, ProgressionNumericPolicy.POLICY_UID,
            ProgressionNumericPolicy.POLICY_VERSION, ProgressionNumericPolicy.ROUNDING_UID,
            computationFingerprint
        )
        if (finalGrant == 0L) {
            val resultFingerprint = progressionFingerprint("PROGRESSION_RESULT", progressionUid, input.inputFingerprint, computationFingerprint, "ZERO")
            return ProgressionResult.create(progressionUid, emptyList(), emptyList(), listOf(computation), input.inputFingerprint, resultFingerprint)
        }

        val grantFingerprint = progressionFingerprint(
            "PROGRESSION_GRANT", progressionUid, input.campaignUid, input.characterUid, input.targetKindUid,
            input.targetUid, finalGrant.toString(), input.progressSemanticsUid, input.progressSemanticsVersion,
            input.progressionDomainUid ?: "<NULL>", input.sourceChannelUid, input.stimulusUid,
            input.progressionPolicyUid, computationFingerprint
        )
        val grantUid = "RPGOS-PROGRESSION-GRANT:$grantFingerprint"
        val changeUid = "RPGOS-PROGRESSION-CHANGE:" + progressionFingerprint("PROGRESSION_CHANGE", grantUid)
        val grant = ProgressionGrant(
            grantUid, changeUid, progressionUid, input.campaignUid, input.characterUid,
            input.targetKindUid, input.targetUid, finalGrant, input.progressSemanticsUid,
            input.progressSemanticsVersion, input.progressionDomainUid, input.sourceChannelUid,
            input.stimulusUid, input.progressionPolicyUid, computationFingerprint
        )
        val ledgerFactors = factors.map {
            ProgressionLedgerFactorEvidence(
                it.factorKindUid, it.evidenceUid, it.sourceValue.scaledUnits,
                it.appliedFactor.scaledUnits, ProgressionNumericPolicy.SCALE
            )
        }
        val ledgerFingerprint = progressionFingerprint("PROGRESSION_LEDGER_INTENT", grantUid, computationFingerprint, changeUid)
        val ledger = PlayerLedgerIntent.create(
            ledgerIntentUid = "RPGOS-PROGRESSION-LEDGER:$ledgerFingerprint",
            ledgerKindUid = PlayerLedgerIntentKinds.PROGRESSION,
            causalChangeUids = listOf(changeUid),
            payload = ProgressionLedgerIntentPayload.create(
                progressionUid = progressionUid,
                campaignUid = input.campaignUid,
                characterUid = input.characterUid,
                targetKindUid = input.targetKindUid,
                targetUid = input.targetUid,
                sourceTypeUid = input.sourceTypeUid,
                sourceChannelUid = input.sourceChannelUid,
                sourceCommandUid = input.sourceCommandUid,
                stimulusUid = input.stimulusUid,
                progressionDomainUid = input.progressionDomainUid,
                methodUid = input.methodUid,
                currentValueEvidenceUid = input.targetValueEvidence.evidenceUid,
                currentValueCanonical = input.targetValueEvidence.canonicalValue,
                currentValueSemanticsUid = input.targetValueEvidence.semanticsUid,
                currentValueSemanticsVersion = input.targetValueEvidence.semanticsVersion,
                calculationFactors = ledgerFactors,
                talentEvidenceUid = input.talentEvidence?.evidenceUid,
                talentFactorScaled = input.talentEvidence?.appliedFactor?.scaledUnits,
                potentialEvidenceUid = input.potentialEvidence?.evidenceUid,
                potentialFactorScaled = input.potentialEvidence?.appliedFactor?.scaledUnits,
                baseGrantUnits = base,
                finalGrantUnits = finalGrant,
                progressSemanticsUid = input.progressSemanticsUid,
                progressSemanticsVersion = input.progressSemanticsVersion,
                engineUid = engineUid,
                engineVersion = engineVersion,
                numericPolicyUid = ProgressionNumericPolicy.POLICY_UID,
                numericPolicyVersion = ProgressionNumericPolicy.POLICY_VERSION,
                progressionPolicyUid = input.progressionPolicyUid,
                progressionPolicyVersion = input.progressionPolicyVersion,
                worldPackUid = input.worldPackUid,
                worldPackVersion = input.worldPackVersion,
                worldPackBindingIdentity = input.worldPackBindingIdentity,
                inputFingerprint = input.inputFingerprint,
                computationFingerprint = computationFingerprint,
                grantUid = grantUid
            )
        )
        val resultFingerprint = progressionFingerprint(
            "PROGRESSION_RESULT", progressionUid, input.inputFingerprint, grantUid,
            ledger.ledgerIntentUid, computationFingerprint
        )
        return ProgressionResult.create(progressionUid, listOf(grant), listOf(ledger), listOf(computation), input.inputFingerprint, resultFingerprint)
    }

    private fun validateInput(input: ProgressionEvaluationInput) {
        if (input.progressionEngineUid != engineUid || input.progressionEngineVersion != engineVersion) fail("PROGRESSION_ENGINE_IDENTITY_MISMATCH")
        if (input.targetKindUid !in ProgressionTargetKinds.supported) fail("UNSUPPORTED_PROGRESSION_TARGET")
        if (input.effortUnits == null) fail("MISSING_CAUSAL_PROGRESSION_EFFORT")
        if (input.effortUnits < 0L) fail("NEGATIVE_BASE_PROGRESSION_GRANT")
        validateModifierScope(input, input.talentEvidence)
        validateModifierScope(input, input.potentialEvidence)
    }

    private fun validateModifierScope(input: ProgressionEvaluationInput, evidence: ProgressionProfileModifierEvidence?) {
        if (evidence == null) return
        if (evidence.campaignUid != input.campaignUid) fail("PROGRESSION_MODIFIER_CAMPAIGN_MISMATCH")
        if (evidence.characterUid != input.characterUid) fail("PROGRESSION_MODIFIER_CHARACTER_MISMATCH")
        if (input.progressionDomainUid != null && evidence.domainUid != input.progressionDomainUid) fail("PROGRESSION_MODIFIER_DOMAIN_MISMATCH")
    }

    private fun fail(code: String): Nothing = throw ProgressionStructuralException(code)

    companion object {
        const val ENGINE_UID = "RPGOS-CORE:PROGRESSION_ENGINE"
        const val ENGINE_VERSION = "20.1"
    }
}

internal fun progressionWorldPackBindingIdentity(binding: WorldPackRuleBinding?): String =
    if (binding == null) "RPGOS-WORLD-PACK-BINDING:UNBOUND_GENERIC"
    else "RPGOS-WORLD-PACK-BINDING:" + progressionFingerprint("WORLD_PACK_BINDING", binding.worldPackUid, binding.worldPackVersion)

internal fun progressionFingerprint(domain: String, vararg values: String): String {
    val canonical = buildString {
        append(domain.length).append(':').append(domain).append('|').append(values.size).append('|')
        values.forEach { append(it.length).append(':').append(it).append('|') }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun <T> immutableProgressionList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
