package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

/**
 * Offline integrity audit for durable NPC-knowledge lifecycle ledgers.
 *
 * Unlike same-turn semantic validation, this checker follows durable UIDs already stored in
 * campaign.db. It never repairs or rewrites history; it only reports broken references,
 * wrong truth kinds/holders and records that claim to originate from a future turn.
 */
class NpcKnowledgeIntegrity141(private val db: SQLiteDatabase) {

    private data class TruthMeta(val kind: String, val holderId: String?)

    fun check(): GameMasterIntegrityReport141 {
        if (!tableExists("gm_campaign_meta")) {
            return GameMasterIntegrityReport141(ok = true, issues = emptyList())
        }
        val campaignUid = scalarString("SELECT campaign_id FROM gm_campaign_meta LIMIT 1")
            ?: return GameMasterIntegrityReport141(
                ok = false,
                issues = listOf(error("NPC_KNOWLEDGE_MISSING_CAMPAIGN", "Nie można ustalić campaign_id dla audytu wiedzy NPC."))
            )
        val currentTurn = scalarLong(
            "SELECT current_turn FROM gm_campaign_meta WHERE campaign_id=? LIMIT 1",
            arrayOf(campaignUid)
        ) ?: 0L

        val requiredTables = listOf(
            "gm_npc_belief_retractions",
            "gm_npc_inferences",
            "gm_organization_knowledge_transmissions",
            "gm_npc_knowledge_resolutions"
        )
        val missing = requiredTables.filterNot(::tableExists)
        if (missing.isNotEmpty()) {
            return GameMasterIntegrityReport141(
                ok = true,
                issues = listOf(
                    GameMasterIntegrityIssue141(
                        code = "NPC_KNOWLEDGE_LEDGER_NOT_INITIALIZED",
                        severity = ValidationSeverity.WARNING,
                        message = "Brakuje tabel lifecycle wiedzy NPC: ${missing.joinToString()}.",
                        count = missing.size
                    )
                )
            )
        }

        val truths = truthIndex(campaignUid)
        val issues = mutableListOf<GameMasterIntegrityIssue141>()
        auditRetractions(campaignUid, currentTurn, truths, issues)
        auditInferences(campaignUid, currentTurn, truths, issues)
        auditOrganizations(campaignUid, currentTurn, truths, issues)
        auditResolutions(campaignUid, currentTurn, truths, issues)

        return GameMasterIntegrityReport141(
            ok = issues.none { it.severity == ValidationSeverity.ERROR },
            issues = issues
        )
    }

    private fun auditRetractions(
        campaignUid: String,
        currentTurn: Long,
        truths: Map<String, TruthMeta>,
        issues: MutableList<GameMasterIntegrityIssue141>
    ) {
        db.rawQuery(
            """
            SELECT holder_id,retracted_belief_id,replacement_truth_id,turn_number
            FROM gm_npc_belief_retractions WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val holder = c.getString(0)
                val retractedUid = c.getString(1)
                val replacementUid = c.getString(2)
                val turn = c.getLong(3)
                val retracted = truths[retractedUid]
                when {
                    retracted == null -> add(issues, "NPC_RETRACTION_UNKNOWN_BELIEF", "Retrakcja wskazuje nieistniejący BELIEF $retractedUid.")
                    retracted.kind != TruthKind.BELIEF.name -> add(issues, "NPC_RETRACTION_TARGET_NOT_BELIEF", "Retrakcja wskazuje $retractedUid typu ${retracted.kind}, a nie BELIEF.")
                    retracted.holderId != holder -> add(issues, "NPC_RETRACTION_HOLDER_MISMATCH", "Retrakcja holder=$holder wskazuje BELIEF $retractedUid należący do ${retracted.holderId}.")
                }
                if (truths[replacementUid] == null) {
                    add(issues, "NPC_RETRACTION_UNKNOWN_REPLACEMENT", "Retrakcja wskazuje nieistniejący replacement truth $replacementUid.")
                }
                if (turn > currentTurn) add(issues, "NPC_RETRACTION_FROM_FUTURE", "Retrakcja z tury $turn wyprzedza current_turn=$currentTurn.")
            }
        }
    }

    private fun auditInferences(
        campaignUid: String,
        currentTurn: Long,
        truths: Map<String, TruthMeta>,
        issues: MutableList<GameMasterIntegrityIssue141>
    ) {
        db.rawQuery(
            """
            SELECT holder_id,resulting_belief_id,premise_truth_ids_json,turn_number
            FROM gm_npc_inferences WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val holder = c.getString(0)
                val resultUid = c.getString(1)
                val result = truths[resultUid]
                when {
                    result == null -> add(issues, "NPC_INFERENCE_UNKNOWN_RESULT", "Inference wskazuje nieistniejący wynik $resultUid.")
                    result.kind != TruthKind.BELIEF.name -> add(issues, "NPC_INFERENCE_RESULT_NOT_BELIEF", "Inference wynik $resultUid ma typ ${result.kind}, a nie BELIEF.")
                    result.holderId != holder -> add(issues, "NPC_INFERENCE_HOLDER_MISMATCH", "Inference holder=$holder wskazuje BELIEF $resultUid należący do ${result.holderId}.")
                }
                jsonIds(c.getString(2)).forEach { premiseUid ->
                    if (truths[premiseUid] == null) {
                        add(issues, "NPC_INFERENCE_UNKNOWN_PREMISE", "Inference wskazuje nieistniejącą przesłankę $premiseUid.")
                    }
                }
                val turn = c.getLong(3)
                if (turn > currentTurn) add(issues, "NPC_INFERENCE_FROM_FUTURE", "Inference z tury $turn wyprzedza current_turn=$currentTurn.")
            }
        }
    }

    private fun auditOrganizations(
        campaignUid: String,
        currentTurn: Long,
        truths: Map<String, TruthMeta>,
        issues: MutableList<GameMasterIntegrityIssue141>
    ) {
        db.rawQuery(
            """
            SELECT source_truth_id,receiver_id,resulting_belief_id,turn_number
            FROM gm_organization_knowledge_transmissions WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val sourceUid = c.getString(0)
                val receiver = c.getString(1)
                val resultUid = c.getString(2)
                if (truths[sourceUid] == null) {
                    add(issues, "ORG_KNOWLEDGE_UNKNOWN_SOURCE", "Organization knowledge wskazuje nieistniejące źródło $sourceUid.")
                }
                val result = truths[resultUid]
                when {
                    result == null -> add(issues, "ORG_KNOWLEDGE_UNKNOWN_RESULT", "Organization knowledge wskazuje nieistniejący wynik $resultUid.")
                    result.kind != TruthKind.BELIEF.name -> add(issues, "ORG_KNOWLEDGE_RESULT_NOT_BELIEF", "Organization knowledge wynik $resultUid ma typ ${result.kind}, a nie BELIEF.")
                    result.holderId != receiver -> add(issues, "ORG_KNOWLEDGE_RECEIVER_MISMATCH", "Receiver=$receiver nie jest holderem BELIEF $resultUid (${result.holderId}).")
                }
                val turn = c.getLong(3)
                if (turn > currentTurn) add(issues, "ORG_KNOWLEDGE_FROM_FUTURE", "Organization knowledge z tury $turn wyprzedza current_turn=$currentTurn.")
            }
        }
    }

    private fun auditResolutions(
        campaignUid: String,
        currentTurn: Long,
        truths: Map<String, TruthMeta>,
        issues: MutableList<GameMasterIntegrityIssue141>
    ) {
        db.rawQuery(
            """
            SELECT holder_id,competing_belief_ids_json,winner_belief_id,superseded_belief_ids_json,turn_number
            FROM gm_npc_knowledge_resolutions WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val holder = c.getString(0)
                val competing = jsonIds(c.getString(1))
                val winner = if (c.isNull(2)) null else c.getString(2)
                val superseded = jsonIds(c.getString(3))
                (competing + listOfNotNull(winner) + superseded).distinct().forEach { beliefUid ->
                    val truth = truths[beliefUid]
                    when {
                        truth == null -> add(issues, "NPC_RESOLUTION_UNKNOWN_BELIEF", "Resolution wskazuje nieistniejący BELIEF $beliefUid.")
                        truth.kind != TruthKind.BELIEF.name -> add(issues, "NPC_RESOLUTION_REF_NOT_BELIEF", "Resolution wskazuje $beliefUid typu ${truth.kind}, a nie BELIEF.")
                        truth.holderId != holder -> add(issues, "NPC_RESOLUTION_HOLDER_MISMATCH", "Resolution holder=$holder wskazuje BELIEF $beliefUid należący do ${truth.holderId}.")
                    }
                }
                if (competing.size < 2) {
                    add(issues, "NPC_RESOLUTION_TOO_FEW_BELIEFS", "Resolution ma mniej niż dwa competing BELIEF-y.")
                }
                val turn = c.getLong(4)
                if (turn > currentTurn) add(issues, "NPC_RESOLUTION_FROM_FUTURE", "Resolution z tury $turn wyprzedza current_turn=$currentTurn.")
            }
        }
    }

    private fun truthIndex(campaignUid: String): Map<String, TruthMeta> {
        val out = LinkedHashMap<String, TruthMeta>()
        db.rawQuery(
            "SELECT fact_id,truth_kind,holder_id FROM gm_facts WHERE campaign_id=?",
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = TruthMeta(c.getString(1), if (c.isNull(2)) null else c.getString(2))
            }
        }
        return out
    }

    private fun jsonIds(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) add(array.getString(i))
        }
    }.getOrElse { emptyList() }

    private fun add(issues: MutableList<GameMasterIntegrityIssue141>, code: String, message: String) {
        val existing = issues.indexOfFirst { it.code == code && it.message == message }
        if (existing >= 0) issues[existing] = issues[existing].copy(count = issues[existing].count + 1)
        else issues += error(code, message)
    }

    private fun error(code: String, message: String) =
        GameMasterIntegrityIssue141(code, ValidationSeverity.ERROR, message)

    private fun scalarLong(sql: String, args: Array<String>? = null): Long? = runCatching {
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    private fun scalarString(sql: String, args: Array<String>? = null): String? = runCatching {
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }.getOrNull()

    private fun tableExists(name: String): Boolean = runCatching {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(name)
        ).use { it.moveToFirst() }
    }.getOrDefault(false)
}
