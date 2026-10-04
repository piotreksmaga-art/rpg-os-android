package com.rpgos.app

internal sealed interface Phase64CombatReceiptPreparation {
    data class Ready(val change: BackgroundProcessChange) : Phase64CombatReceiptPreparation
    data class Unavailable(val reasonUid: String) : Phase64CombatReceiptPreparation
}

/** Records one real Phase62 completion in the background process history. This factory has
 * no clock, decision, mechanics or persistence authority; admission rechecks its references
 * against the ordinary turn's sealed brain, mechanical changes and final temporal state. */
internal object Phase64CombatReceiptFactory {
    const val RULE_UID = "P64:CORE:CONFLICT:COMBAT"
    const val RULE_VERSION = 2
    const val OWNER_PARAMETER = "completion_owner"
    const val SOURCE_FAMILY = "P64:NPC-COMBAT-COMPLETION:1"

    fun receiptUid(campaignUid: String, planUid: String, ruleUid: String = RULE_UID, ruleVersion: Int = RULE_VERSION): String =
        "P64:NPC-COMBAT:${identityHash(campaignUid, planUid, ruleUid, ruleVersion)}"

    fun completionSourceUid(campaignUid: String, planUid: String, ruleUid: String = RULE_UID, ruleVersion: Int = RULE_VERSION): String =
        "$SOURCE_FAMILY:${identityHash(campaignUid, planUid, ruleUid, ruleVersion)}"

    fun logicalEventUid(campaignUid: String, planUid: String, ruleUid: String = RULE_UID, ruleVersion: Int = RULE_VERSION): String =
        "P64:EVENT:NPC-COMBAT:${identityHash(campaignUid, planUid, ruleUid, ruleVersion)}"

    fun evidenceUid(campaignUid: String, planUid: String, ruleUid: String = RULE_UID, ruleVersion: Int = RULE_VERSION): String =
        "P64:NPC-COMBAT-EVIDENCE:${identityHash(campaignUid, planUid, ruleUid, ruleVersion)}"

    fun prepare(scope: BackgroundProcessEvaluationScope, commandUid: String, activePlayerUid: String,
        coreRule: BackgroundProcessDefinition, canonicalBrain: NpcBrainState, input: TemporalOwnerInput,
        originalEffects: List<VerifiedMechanicsCommandEffect>): Phase64CombatReceiptPreparation = try {
        prepareCaptured(scope, commandUid, activePlayerUid, coreRule, canonicalBrain, input, originalEffects)
    } catch (_: IllegalArgumentException) {
        Phase64CombatReceiptPreparation.Unavailable("P64:COMBAT_RECEIPT_BINDING")
    } catch (_: ArithmeticException) {
        Phase64CombatReceiptPreparation.Unavailable("P64:COMBAT_RECEIPT_OVERFLOW")
    }

    private fun prepareCaptured(scope: BackgroundProcessEvaluationScope, commandUid: String, activePlayerUid: String,
        coreRule: BackgroundProcessDefinition, canonicalBrain: NpcBrainState, input: TemporalOwnerInput,
        originalEffects: List<VerifiedMechanicsCommandEffect>): Phase64CombatReceiptPreparation {
        fun unavailable(reason: String) = Phase64CombatReceiptPreparation.Unavailable(reason)
        if (coreRule.uid != RULE_UID || coreRule.version != RULE_VERSION || coreRule.domain != "CONFLICT" || coreRule.operation != "COMBAT" ||
            coreRule.parameters != mapOf(OWNER_PARAMETER to NpcActionProcess.OWNER))
            return unavailable("P64:COMBAT_RECEIPT_RULE_REQUIRED")
        npcUid(commandUid)
        npcUid(activePlayerUid)
        if (scope.temporal != input.scope || canonicalBrain.campaignUid != scope.temporal.campaignUid || input.through < input.from)
            return unavailable("P64:COMBAT_RECEIPT_SCOPE")
        if (originalEffects.isEmpty() || originalEffects.size > 127 || originalEffects.map { it.effectUid }.distinct().size != originalEffects.size)
            return unavailable("P64:COMBAT_RECEIPT_EFFECTS_REQUIRED")
        val planUid = originalEffects.map { it.canonicalPayload["npc_plan_uid"] }.distinct().singleOrNull()?.takeIf(String::isNotBlank)
            ?: return unavailable("P64:COMBAT_RECEIPT_PLAN_REQUIRED")
        npcUid(planUid)
        val effects = originalEffects.sortedBy { it.effectUid }
        if (input.stagedEffects.filter { it.canonicalPayload["npc_plan_uid"] == planUid }.sortedBy { it.effectUid } != effects)
            return unavailable("P64:COMBAT_RECEIPT_EFFECT_PREFIX")
        val markers = effects.first().canonicalPayload
        val actor = canonicalBrain.actor
        val started = markers["npc_started_at_ms"]?.toLongOrNull()?.let(::WorldTimeTick)
            ?: return unavailable("P64:COMBAT_RECEIPT_TIME_REQUIRED")
        val due = markers["npc_due_at_ms"]?.toLongOrNull()?.let(::WorldTimeTick)
            ?: return unavailable("P64:COMBAT_RECEIPT_TIME_REQUIRED")
        if (due <= started || due > input.through) return unavailable("P64:COMBAT_RECEIPT_TIME_REQUIRED")
        val ability = markers["npc_ability_uid"]?.takeIf(String::isNotBlank) ?: return unavailable("P64:COMBAT_RECEIPT_ABILITY_REQUIRED")
        val targetKind = markers["npc_target_kind_uid"]?.takeIf(String::isNotBlank) ?: return unavailable("P64:COMBAT_RECEIPT_TARGET_REQUIRED")
        val targetUid = markers["npc_target_uid"]?.takeIf(String::isNotBlank) ?: return unavailable("P64:COMBAT_RECEIPT_TARGET_REQUIRED")
        val processUid = receiptUid(scope.temporal.campaignUid, planUid, coreRule.uid, coreRule.version)
        val eventUid = logicalEventUid(scope.temporal.campaignUid, planUid, coreRule.uid, coreRule.version)
        val sourceUid = completionSourceUid(scope.temporal.campaignUid, planUid, coreRule.uid, coreRule.version)
        val parameters = mapOf(
            "decision_uid" to planUid, "ability_uid" to ability, "target_kind_uid" to targetKind, "target_uid" to targetUid,
            "p64_ability_contract" to requireNotNull(markers["npc_ability_contract"]),
            "p64_completion_option_uid" to requireNotNull(markers["npc_option_uid"]),
            "p64_completion_owner" to NpcActionProcess.OWNER, "p64_completion_command_uid" to commandUid,
            "p64_completion_proof_uid" to sourceUid, "p64_logical_event_uid" to eventUid,
            "p64_started_at_ms" to started.milliseconds.toString(), "p64_event_at_ms" to due.milliseconds.toString(),
            "p64_process_uid" to processUid, "p64_rule_uid" to coreRule.uid, "p64_rule_version" to coreRule.version.toString(),
            "p64_rule_fingerprint" to scope.ruleFingerprint
        )
        val acknowledgment = Phase64CombatCompletionPreparation.prepare(actor, coreRule, parameters, scope,
            canonicalBrain, activePlayerUid, input, input.stagedChanges)
        if (acknowledgment.status != BackgroundProcessStatus.COMPLETED)
            return unavailable(acknowledgment.reasonUid ?: "P64:COMBAT_RECEIPT_COMPLETION_REQUIRED")
        if (acknowledgment.changes.isNotEmpty() || acknowledgment.effects.isNotEmpty() || acknowledgment.claims.isNotEmpty() ||
            acknowledgment.deadlineAdds.isNotEmpty() || acknowledgment.deadlineRemovals.isNotEmpty() || acknowledgment.ownerDelegations.isNotEmpty() ||
            acknowledgment.existingConsequenceFingerprints.isEmpty() || acknowledgment.existingConsequenceFingerprints.size > 128)
            return unavailable("P64:COMBAT_RECEIPT_OWNER_RESULT")
        val process = BackgroundProcessInstance(processUid, coreRule.uid, coreRule.version, actor, 1, started, due,
            BackgroundProcessStatus.COMPLETED, parameters, progressUnits = 1)
        val evidence = WorldProcessEvidence(evidenceUid(scope.temporal.campaignUid, planUid, coreRule.uid, coreRule.version),
            processUid, coreRule.uid, coreRule.version, (listOf(sourceUid, commandUid) + acknowledgment.sourceUids).distinct(), due)
        return Phase64CombatReceiptPreparation.Ready(BackgroundProcessChange(scope.temporal.campaignUid, scope.temporal.historyGenerationUid,
            0, process, evidence, acknowledgment.existingConsequenceFingerprints))
    }

    private fun identityHash(campaignUid: String, planUid: String, ruleUid: String, ruleVersion: Int): String {
        npcUid(campaignUid)
        npcUid(planUid)
        npcUid(ruleUid)
        require(ruleVersion > 0)
        return phase63Hash(listOf(campaignUid, planUid, ruleUid, ruleVersion.toString()).joinToString("") { "${it.length}:$it" })
    }
}
