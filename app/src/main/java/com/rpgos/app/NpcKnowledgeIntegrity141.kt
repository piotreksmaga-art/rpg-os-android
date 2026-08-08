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

    private data class TruthMeta(
        val kind: String,
        val holderId: String?,
        val subjectId: String?,
        val predicate: String,
        val validFromTurn: Long,
        val validUntilTurn: Long?
    )

    private data class MembershipMeta(
        val npcId: String,
        val organizationId: String,
        val clearance: Int,
        val validFromTurn: Long,
        val validUntilTurn: Long?
    ) {
        fun activeAt(turn: Long): Boolean =
            turn >= validFromTurn && (validUntilTurn == null || turn <= validUntilTurn)
    }

    private data class PublicationMeta(
        val organizationId: String,
        val truthId: String,
        val subjectId: String,
        val predicate: String,
        val minimumClearance: Int,
        val validFromTurn: Long,
        val validUntilTurn: Long?
    ) {
        fun activeAt(turn: Long): Boolean =
            turn >= validFromTurn && (validUntilTurn == null || turn <= validUntilTurn)
    }

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
            "gm_npc_knowledge_resolutions",
            "gm_organization_memberships",
            "gm_organization_fact_publications"
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
        auditOrganizationPublications(campaignUid, truths, issues)
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

    private fun auditOrganizationPublications(
        campaignUid: String,
        truths: Map<String, TruthMeta>,
        issues: MutableList<GameMasterIntegrityIssue141>
    ) {
        db.rawQuery(
            """
            SELECT publication_id,truth_id,subject_id,predicate,valid_from_turn,valid_until_turn,source_value_hash
            FROM gm_organization_fact_publications
            WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val publicationUid = c.getString(0)
                val truthUid = c.getString(1)
                val subjectId = c.getString(2)
                val predicate = c.getString(3)
                val validFrom = c.getLong(4)
                val validUntil = if (c.isNull(5)) null else c.getLong(5)
                val expectedValueHash = if (c.isNull(6)) null else c.getString(6)
                val source = truths[truthUid]

                when {
                    source == null -> add(
                        issues,
                        "ORG_PUBLICATION_UNKNOWN_SOURCE",
                        "Publication $publicationUid wskazuje nieistniejący FACT $truthUid."
                    )
                    source.kind != TruthKind.FACT.name -> add(
                        issues,
                        "ORG_PUBLICATION_SOURCE_NOT_FACT",
                        "Publication $publicationUid wskazuje $truthUid typu ${source.kind}, a nie FACT."
                    )
                    source.subjectId != subjectId || source.predicate != predicate -> add(
                        issues,
                        "ORG_PUBLICATION_FACT_MISMATCH",
                        "Publication $publicationUid nie odpowiada subject/predicate FACT $truthUid."
                    )
                }

                if (source != null) {
                    if (validFrom < source.validFromTurn) {
                        add(
                            issues,
                            "ORG_PUBLICATION_BEFORE_FACT",
                            "Publication $publicationUid zaczyna się w turze $validFrom przed FACT $truthUid (${source.validFromTurn})."
                        )
                    }
                    if (source.validUntilTurn != null && (validUntil == null || validUntil > source.validUntilTurn)) {
                        add(
                            issues,
                            "ORG_PUBLICATION_OUTLIVES_FACT",
                            "Publication $publicationUid wykracza poza ważność FACT $truthUid kończącą się w turze ${source.validUntilTurn}."
                        )
                    }
                    val currentValueHash = sourceValueHash(campaignUid, truthUid)
                    if (expectedValueHash.isNullOrBlank() || currentValueHash != expectedValueHash) {
                        add(
                            issues,
                            "ORG_PUBLICATION_SOURCE_VALUE_CHANGED",
                            "Publication $publicationUid nie odpowiada już wartości FACT $truthUid zapisanej przy publikacji."
                        )
                    }
                }
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
            SELECT organization_id,membership_id,publication_id,source_truth_id,
                   receiver_id,resulting_belief_id,turn_number
            FROM gm_organization_knowledge_transmissions WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val organization = c.getString(0)
                val membershipUid = c.getString(1)
                val publicationUid = c.getString(2)
                val sourceUid = c.getString(3)
                val receiver = c.getString(4)
                val resultUid = c.getString(5)
                val turn = c.getLong(6)

                val source = truths[sourceUid]
                when {
                    source == null -> add(issues, "ORG_KNOWLEDGE_UNKNOWN_SOURCE", "Organization knowledge wskazuje nieistniejące źródło $sourceUid.")
                    source.kind != TruthKind.FACT.name -> add(issues, "ORG_KNOWLEDGE_SOURCE_NOT_FACT", "Organization knowledge źródło $sourceUid ma typ ${source.kind}, a nie FACT.")
                }

                val result = truths[resultUid]
                when {
                    result == null -> add(issues, "ORG_KNOWLEDGE_UNKNOWN_RESULT", "Organization knowledge wskazuje nieistniejący wynik $resultUid.")
                    result.kind != TruthKind.BELIEF.name -> add(issues, "ORG_KNOWLEDGE_RESULT_NOT_BELIEF", "Organization knowledge wynik $resultUid ma typ ${result.kind}, a nie BELIEF.")
                    result.holderId != receiver -> add(issues, "ORG_KNOWLEDGE_RECEIVER_MISMATCH", "Receiver=$receiver nie jest holderem BELIEF $resultUid (${result.holderId}).")
                }

                val membership = membership(campaignUid, membershipUid)
                when {
                    membership == null -> add(issues, "ORG_KNOWLEDGE_UNKNOWN_MEMBERSHIP", "Organization knowledge wskazuje nieistniejące membership $membershipUid.")
                    membership.npcId != receiver -> add(issues, "ORG_KNOWLEDGE_MEMBERSHIP_RECEIVER_MISMATCH", "Membership $membershipUid należy do ${membership.npcId}, a transmission receiver=$receiver.")
                    membership.organizationId != organization -> add(issues, "ORG_KNOWLEDGE_MEMBERSHIP_ORG_MISMATCH", "Membership $membershipUid należy do ${membership.organizationId}, a transmission organization=$organization.")
                    !membership.activeAt(turn) -> add(issues, "ORG_KNOWLEDGE_MEMBERSHIP_NOT_ACTIVE", "Membership $membershipUid nie było aktywne w turze $turn.")
                }

                val publication = publication(campaignUid, publicationUid)
                when {
                    publication == null -> add(issues, "ORG_KNOWLEDGE_UNKNOWN_PUBLICATION", "Organization knowledge wskazuje nieistniejącą publication $publicationUid.")
                    publication.organizationId != organization -> add(issues, "ORG_KNOWLEDGE_PUBLICATION_ORG_MISMATCH", "Publication $publicationUid należy do ${publication.organizationId}, a transmission organization=$organization.")
                    publication.truthId != sourceUid -> add(issues, "ORG_KNOWLEDGE_PUBLICATION_SOURCE_MISMATCH", "Publication $publicationUid wskazuje ${publication.truthId}, a transmission source=$sourceUid.")
                    !publication.activeAt(turn) -> add(issues, "ORG_KNOWLEDGE_PUBLICATION_NOT_ACTIVE", "Publication $publicationUid nie była aktywna w turze $turn.")
                    source != null && (publication.subjectId != source.subjectId || publication.predicate != source.predicate) ->
                        add(issues, "ORG_KNOWLEDGE_PUBLICATION_FACT_MISMATCH", "Publication $publicationUid nie odpowiada subject/predicate źródła $sourceUid.")
                }

                if (membership != null && publication != null && membership.clearance < publication.minimumClearance) {
                    add(
                        issues,
                        "ORG_KNOWLEDGE_INSUFFICIENT_CLEARANCE",
                        "Membership $membershipUid clearance=${membership.clearance}, ale publication $publicationUid wymaga ${publication.minimumClearance}."
                    )
                }
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
            """
            SELECT fact_id,truth_kind,holder_id,subject_id,predicate,valid_from_turn,valid_until_turn
            FROM gm_facts WHERE campaign_id=?
            """.trimIndent(),
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = TruthMeta(
                    kind = c.getString(1),
                    holderId = if (c.isNull(2)) null else c.getString(2),
                    subjectId = if (c.isNull(3)) null else c.getString(3),
                    predicate = c.getString(4),
                    validFromTurn = c.getLong(5),
                    validUntilTurn = if (c.isNull(6)) null else c.getLong(6)
                )
            }
        }
        return out
    }

    private fun sourceValueHash(campaignUid: String, truthUid: String): String? {
        val value = db.rawQuery(
            "SELECT object_json FROM gm_facts WHERE campaign_id=? AND fact_id=? LIMIT 1",
            arrayOf(campaignUid, truthUid)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
        return OrganizationPublicationSourceHash141.hash(value)
    }

    private fun membership(campaignUid: String, membershipUid: String): MembershipMeta? {
        db.rawQuery(
            """
            SELECT npc_id,organization_id,clearance,valid_from_turn,valid_until_turn
            FROM gm_organization_memberships
            WHERE campaign_id=? AND membership_id=? LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid, membershipUid)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return MembershipMeta(
                npcId = c.getString(0),
                organizationId = c.getString(1),
                clearance = c.getInt(2),
                validFromTurn = c.getLong(3),
                validUntilTurn = if (c.isNull(4)) null else c.getLong(4)
            )
        }
    }

    private fun publication(campaignUid: String, publicationUid: String): PublicationMeta? {
        db.rawQuery(
            """
            SELECT organization_id,truth_id,subject_id,predicate,minimum_clearance,valid_from_turn,valid_until_turn
            FROM gm_organization_fact_publications
            WHERE campaign_id=? AND publication_id=? LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid, publicationUid)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return PublicationMeta(
                organizationId = c.getString(0),
                truthId = c.getString(1),
                subjectId = c.getString(2),
                predicate = c.getString(3),
                minimumClearance = c.getInt(4),
                validFromTurn = c.getLong(5),
                validUntilTurn = if (c.isNull(6)) null else c.getLong(6)
            )
        }
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
