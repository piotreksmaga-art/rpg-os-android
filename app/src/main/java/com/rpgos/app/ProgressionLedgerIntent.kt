package com.rpgos.app

import java.util.Collections

data class ProgressionLedgerFactorEvidence(
    val factorKindUid: String,
    val evidenceUid: String,
    val sourceValueScaled: Long,
    val appliedFactorScaled: Long,
    val scale: Long
) {
    init {
        require(factorKindUid.isNotBlank())
        require(evidenceUid.isNotBlank())
        require(sourceValueScaled >= 0L)
        require(appliedFactorScaled >= 0L)
        require(scale > 0L)
    }
}

class ProgressionLedgerIntentPayload private constructor(
    val progressionUid: String,
    val campaignUid: String,
    val characterUid: String,
    val targetKindUid: String,
    val targetUid: String,
    val sourceTypeUid: String,
    val sourceChannelUid: String,
    val sourceCommandUid: String,
    val stimulusUid: String,
    val progressionDomainUid: String?,
    val methodUid: String?,
    val currentValueEvidenceUid: String,
    val currentValueCanonical: String,
    val currentValueSemanticsUid: String,
    val currentValueSemanticsVersion: String,
    calculationFactors: List<ProgressionLedgerFactorEvidence>,
    val talentEvidenceUid: String?,
    val talentFactorScaled: Long?,
    val potentialEvidenceUid: String?,
    val potentialFactorScaled: Long?,
    val baseGrantUnits: Long,
    val finalGrantUnits: Long,
    val progressSemanticsUid: String,
    val progressSemanticsVersion: String,
    val engineUid: String,
    val engineVersion: String,
    val numericPolicyUid: String,
    val numericPolicyVersion: String,
    val progressionPolicyUid: String,
    val progressionPolicyVersion: String,
    val worldPackUid: String?,
    val worldPackVersion: String?,
    val worldPackBindingIdentity: String,
    val inputFingerprint: String,
    val computationFingerprint: String,
    val grantUid: String
) : PlayerLedgerIntentPayload {
    val calculationFactors: List<ProgressionLedgerFactorEvidence> =
        Collections.unmodifiableList(ArrayList(calculationFactors))

    init {
        require(progressionUid.isNotBlank() && campaignUid.isNotBlank() && characterUid.isNotBlank())
        require(targetKindUid.isNotBlank() && targetUid.isNotBlank())
        require(sourceTypeUid.isNotBlank() && sourceChannelUid.isNotBlank())
        require(sourceCommandUid.isNotBlank() && stimulusUid.isNotBlank())
        require(progressionDomainUid?.isBlank() != true && methodUid?.isBlank() != true)
        require(currentValueEvidenceUid.isNotBlank() && currentValueCanonical.isNotBlank())
        require(currentValueSemanticsUid.isNotBlank() && currentValueSemanticsVersion.isNotBlank())
        require((talentEvidenceUid == null) == (talentFactorScaled == null))
        require((potentialEvidenceUid == null) == (potentialFactorScaled == null))
        require(talentEvidenceUid?.isBlank() != true && potentialEvidenceUid?.isBlank() != true)
        require(talentFactorScaled == null || talentFactorScaled >= 0L)
        require(potentialFactorScaled == null || potentialFactorScaled >= 0L)
        require(baseGrantUnits >= 0L && finalGrantUnits > 0L)
        require(progressSemanticsUid.isNotBlank() && progressSemanticsVersion.isNotBlank())
        require(engineUid.isNotBlank() && engineVersion.isNotBlank())
        require(numericPolicyUid.isNotBlank() && numericPolicyVersion.isNotBlank())
        require(progressionPolicyUid.isNotBlank() && progressionPolicyVersion.isNotBlank())
        require((worldPackUid == null) == (worldPackVersion == null))
        require(worldPackUid?.isBlank() != true && worldPackVersion?.isBlank() != true)
        require(worldPackBindingIdentity.isNotBlank())
        require(inputFingerprint.isNotBlank() && computationFingerprint.isNotBlank() && grantUid.isNotBlank())
    }

    override fun equals(other: Any?): Boolean = other is ProgressionLedgerIntentPayload &&
        progressionUid == other.progressionUid && campaignUid == other.campaignUid && characterUid == other.characterUid &&
        targetKindUid == other.targetKindUid && targetUid == other.targetUid && sourceTypeUid == other.sourceTypeUid &&
        sourceChannelUid == other.sourceChannelUid && sourceCommandUid == other.sourceCommandUid && stimulusUid == other.stimulusUid &&
        progressionDomainUid == other.progressionDomainUid && methodUid == other.methodUid &&
        currentValueEvidenceUid == other.currentValueEvidenceUid && currentValueCanonical == other.currentValueCanonical &&
        currentValueSemanticsUid == other.currentValueSemanticsUid && currentValueSemanticsVersion == other.currentValueSemanticsVersion &&
        calculationFactors == other.calculationFactors && talentEvidenceUid == other.talentEvidenceUid &&
        talentFactorScaled == other.talentFactorScaled && potentialEvidenceUid == other.potentialEvidenceUid &&
        potentialFactorScaled == other.potentialFactorScaled && baseGrantUnits == other.baseGrantUnits &&
        finalGrantUnits == other.finalGrantUnits && progressSemanticsUid == other.progressSemanticsUid &&
        progressSemanticsVersion == other.progressSemanticsVersion && engineUid == other.engineUid &&
        engineVersion == other.engineVersion && numericPolicyUid == other.numericPolicyUid &&
        numericPolicyVersion == other.numericPolicyVersion && progressionPolicyUid == other.progressionPolicyUid &&
        progressionPolicyVersion == other.progressionPolicyVersion && worldPackUid == other.worldPackUid &&
        worldPackVersion == other.worldPackVersion && worldPackBindingIdentity == other.worldPackBindingIdentity &&
        inputFingerprint == other.inputFingerprint && computationFingerprint == other.computationFingerprint && grantUid == other.grantUid

    override fun hashCode(): Int = arrayOf(
        progressionUid, campaignUid, characterUid, targetKindUid, targetUid, sourceTypeUid, sourceChannelUid,
        sourceCommandUid, stimulusUid, progressionDomainUid, methodUid, currentValueEvidenceUid, currentValueCanonical,
        currentValueSemanticsUid, currentValueSemanticsVersion, calculationFactors, talentEvidenceUid, talentFactorScaled,
        potentialEvidenceUid, potentialFactorScaled, baseGrantUnits, finalGrantUnits, progressSemanticsUid,
        progressSemanticsVersion, engineUid, engineVersion, numericPolicyUid, numericPolicyVersion,
        progressionPolicyUid, progressionPolicyVersion, worldPackUid, worldPackVersion, worldPackBindingIdentity,
        inputFingerprint, computationFingerprint, grantUid
    ).contentHashCode()

    companion object {
        fun create(
            progressionUid: String,
            campaignUid: String,
            characterUid: String,
            targetKindUid: String,
            targetUid: String,
            sourceTypeUid: String,
            sourceChannelUid: String,
            sourceCommandUid: String,
            stimulusUid: String,
            progressionDomainUid: String? = null,
            methodUid: String? = null,
            currentValueEvidenceUid: String,
            currentValueCanonical: String,
            currentValueSemanticsUid: String,
            currentValueSemanticsVersion: String,
            calculationFactors: List<ProgressionLedgerFactorEvidence> = emptyList(),
            talentEvidenceUid: String? = null,
            talentFactorScaled: Long? = null,
            potentialEvidenceUid: String? = null,
            potentialFactorScaled: Long? = null,
            baseGrantUnits: Long,
            finalGrantUnits: Long,
            progressSemanticsUid: String,
            progressSemanticsVersion: String,
            engineUid: String,
            engineVersion: String,
            numericPolicyUid: String,
            numericPolicyVersion: String,
            progressionPolicyUid: String,
            progressionPolicyVersion: String,
            worldPackUid: String? = null,
            worldPackVersion: String? = null,
            worldPackBindingIdentity: String,
            inputFingerprint: String,
            computationFingerprint: String,
            grantUid: String
        ): ProgressionLedgerIntentPayload = ProgressionLedgerIntentPayload(
            progressionUid, campaignUid, characterUid, targetKindUid, targetUid, sourceTypeUid, sourceChannelUid,
            sourceCommandUid, stimulusUid, progressionDomainUid, methodUid, currentValueEvidenceUid, currentValueCanonical,
            currentValueSemanticsUid, currentValueSemanticsVersion, calculationFactors, talentEvidenceUid, talentFactorScaled,
            potentialEvidenceUid, potentialFactorScaled, baseGrantUnits, finalGrantUnits, progressSemanticsUid,
            progressSemanticsVersion, engineUid, engineVersion, numericPolicyUid, numericPolicyVersion,
            progressionPolicyUid, progressionPolicyVersion, worldPackUid, worldPackVersion, worldPackBindingIdentity,
            inputFingerprint, computationFingerprint, grantUid
        )
    }
}

object ProgressionLedgerIntentKinds {
    const val PROGRESSION = "RPGOS-LEDGER-INTENT:PROGRESSION"
}
