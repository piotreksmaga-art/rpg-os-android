from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"anchor mismatch {path}: expected 1 occurrence, got {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


phase37 = r'''package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import kotlin.math.roundToLong

const val NPC_KNOWLEDGE_SCHEMA_VERSION = 1
const val NPC_KNOWLEDGE_CONFIDENCE_SCALE = 1_000_000L

enum class NpcKnowledgeState {
    KNOWN,
    SUSPECTED,
    FALSE_BELIEF,
    RUMOUR,
    SECRET,
    OBSERVATION,
    INFERENCE,
    ORGANIZATION_KNOWLEDGE,
    LEGACY_UNKNOWN
}

enum class NpcKnowledgeAcquisitionKind {
    OBSERVATION,
    COMMUNICATION,
    RESEARCH,
    INFERENCE,
    ORGANIZATION,
    ESPIONAGE,
    WORLD_PACK_MECHANIC,
    VERIFIED_IMPORT,
    LEGACY_UNKNOWN
}

enum class NpcKnowledgeProvenanceStatus { COMMITTED, VERIFIED_IMPORT, LEGACY_UNKNOWN }

data class NpcKnowledgeSpec(
    val knowledgeUid: String,
    val holderUid: String,
    val claimUid: String,
    val subjectUid: String? = null,
    val state: NpcKnowledgeState,
    val contentSummary: String? = null,
    val confidenceScaled: Long,
    val accuracyScaled: Long,
    val acquisitionKind: NpcKnowledgeAcquisitionKind,
    val sourceActorUid: String? = null,
    val sourceEvidenceUid: String? = null,
    val sourceOrganizationUid: String? = null,
    val worldMechanicUid: String? = null,
    val canShare: Boolean = true,
    val supersedesKnowledgeUid: String? = null
) {
    fun validationErrorsForGameplay(): List<String> = buildList {
        if (knowledgeUid.isBlank() || holderUid.isBlank() || claimUid.isBlank()) add("INVALID_NPC_KNOWLEDGE_IDENTITY")
        if (subjectUid?.isBlank() == true || contentSummary?.isBlank() == true || sourceActorUid?.isBlank() == true ||
            sourceEvidenceUid?.isBlank() == true || sourceOrganizationUid?.isBlank() == true || worldMechanicUid?.isBlank() == true ||
            supersedesKnowledgeUid?.isBlank() == true) add("INVALID_NPC_KNOWLEDGE_OPTIONAL_UID")
        if (supersedesKnowledgeUid == knowledgeUid) add("INVALID_NPC_KNOWLEDGE_SUPERSESSION")
        if (confidenceScaled !in 0L..NPC_KNOWLEDGE_CONFIDENCE_SCALE || accuracyScaled !in 0L..NPC_KNOWLEDGE_CONFIDENCE_SCALE) {
            add("INVALID_NPC_KNOWLEDGE_CERTAINTY")
        }
        if (state == NpcKnowledgeState.LEGACY_UNKNOWN) add("INVALID_NPC_KNOWLEDGE_STATE")
        when (acquisitionKind) {
            NpcKnowledgeAcquisitionKind.OBSERVATION -> if (sourceEvidenceUid == null) add("NPC_KNOWLEDGE_OBSERVATION_EVIDENCE_REQUIRED")
            NpcKnowledgeAcquisitionKind.COMMUNICATION -> if (sourceActorUid == null || sourceEvidenceUid == null) add("NPC_KNOWLEDGE_COMMUNICATION_EVIDENCE_REQUIRED")
            NpcKnowledgeAcquisitionKind.RESEARCH -> if (sourceEvidenceUid == null) add("NPC_KNOWLEDGE_RESEARCH_EVIDENCE_REQUIRED")
            NpcKnowledgeAcquisitionKind.INFERENCE -> if (sourceEvidenceUid == null) add("NPC_KNOWLEDGE_INFERENCE_EVIDENCE_REQUIRED")
            NpcKnowledgeAcquisitionKind.ORGANIZATION -> if (sourceOrganizationUid == null || sourceEvidenceUid == null) add("NPC_KNOWLEDGE_ORGANIZATION_EVIDENCE_REQUIRED")
            NpcKnowledgeAcquisitionKind.ESPIONAGE -> if (sourceEvidenceUid == null) add("NPC_KNOWLEDGE_ESPIONAGE_EVIDENCE_REQUIRED")
            NpcKnowledgeAcquisitionKind.WORLD_PACK_MECHANIC -> if (worldMechanicUid == null || sourceEvidenceUid == null) add("NPC_KNOWLEDGE_WORLD_MECHANIC_EVIDENCE_REQUIRED")
            NpcKnowledgeAcquisitionKind.VERIFIED_IMPORT,
            NpcKnowledgeAcquisitionKind.LEGACY_UNKNOWN -> add("NPC_KNOWLEDGE_NON_GAMEPLAY_ACQUISITION_KIND")
        }
    }
}

data class NpcKnowledgeRecord(
    val campaignUid: String,
    val spec: NpcKnowledgeSpec,
    val provenanceStatus: NpcKnowledgeProvenanceStatus,
    val createdEffectiveOrder: Long?,
    val createdTransactionUid: String?,
    val createdTurnUid: String?,
    val createdEventUid: String?,
    val legacySourceUid: String?,
    val legacyAcquisitionMethod: String?,
    val legacyLearnedChapter: Int?,
    val schemaVersion: Int = NPC_KNOWLEDGE_SCHEMA_VERSION
)

internal object Phase37NpcKnowledgeSchema {
    const val TABLE = "npc_knowledge_records"
    private val requiredColumns = setOf(
        "campaign_uid", "knowledge_uid", "holder_uid", "claim_uid", "subject_uid", "knowledge_state",
        "content_summary", "confidence_scaled", "accuracy_scaled", "acquisition_kind", "source_actor_uid",
        "source_evidence_uid", "source_organization_uid", "world_mechanic_uid", "can_share",
        "supersedes_knowledge_uid", "provenance_status", "created_effective_order", "created_transaction_uid",
        "created_turn_uid", "created_event_uid", "legacy_source_uid", "legacy_acquisition_method",
        "legacy_learned_chapter", "schema_version"
    )

    fun ensureReady(db: SQLiteDatabase, campaignUid: String) {
        require(campaignUid.isNotBlank()) { "RPGOS-P37:BLANK_CAMPAIGN_UID" }
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE(
            campaign_uid TEXT NOT NULL,
            knowledge_uid TEXT NOT NULL,
            holder_uid TEXT NOT NULL,
            claim_uid TEXT NOT NULL,
            subject_uid TEXT,
            knowledge_state TEXT NOT NULL,
            content_summary TEXT,
            confidence_scaled INTEGER NOT NULL CHECK(confidence_scaled BETWEEN 0 AND $NPC_KNOWLEDGE_CONFIDENCE_SCALE),
            accuracy_scaled INTEGER NOT NULL CHECK(accuracy_scaled BETWEEN 0 AND $NPC_KNOWLEDGE_CONFIDENCE_SCALE),
            acquisition_kind TEXT NOT NULL,
            source_actor_uid TEXT,
            source_evidence_uid TEXT,
            source_organization_uid TEXT,
            world_mechanic_uid TEXT,
            can_share INTEGER NOT NULL CHECK(can_share IN (0,1)),
            supersedes_knowledge_uid TEXT,
            provenance_status TEXT NOT NULL,
            created_effective_order INTEGER,
            created_transaction_uid TEXT,
            created_turn_uid TEXT,
            created_event_uid TEXT,
            legacy_source_uid TEXT,
            legacy_acquisition_method TEXT,
            legacy_learned_chapter INTEGER,
            schema_version INTEGER NOT NULL,
            PRIMARY KEY(campaign_uid,knowledge_uid)
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_npc_knowledge_holder ON $TABLE(campaign_uid,holder_uid,created_effective_order,knowledge_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_npc_knowledge_claim ON $TABLE(campaign_uid,claim_uid,holder_uid)")
        importLegacyRows(db, campaignUid)
    }

    fun isReady(db: SQLiteDatabase): Boolean {
        if (!tableExists(db, TABLE)) return false
        val columns = tableColumns(db, TABLE)
        return requiredColumns.all { it in columns }
    }

    private fun importLegacyRows(db: SQLiteDatabase, campaignUid: String) {
        if (!tableExists(db, "information_knowledge")) return
        val knowledgeColumns = tableColumns(db, "information_knowledge")
        val required = setOf("holder_uid", "info_uid", "confidence", "accuracy", "learned_chapter", "acquisition_method", "source_uid", "can_share")
        if (!required.all { it in knowledgeColumns }) return
        val factsHaveContent = tableExists(db, "information_facts") && "content_summary" in tableColumns(db, "information_facts")
        val sql = if (factsHaveContent) {
            """SELECT k.holder_uid,k.info_uid,k.confidence,k.accuracy,k.learned_chapter,k.acquisition_method,k.source_uid,k.can_share,f.content_summary
               FROM information_knowledge k LEFT JOIN information_facts f ON f.info_uid=k.info_uid"""
        } else {
            """SELECT holder_uid,info_uid,confidence,accuracy,learned_chapter,acquisition_method,source_uid,can_share,NULL
               FROM information_knowledge"""
        }
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val holder = c.getString(0) ?: continue
                val claim = c.getString(1) ?: continue
                if (holder.isBlank() || claim.isBlank()) continue
                val knowledgeUid = legacyUid(campaignUid, holder, claim)
                val values = ContentValues().apply {
                    put("campaign_uid", campaignUid)
                    put("knowledge_uid", knowledgeUid)
                    put("holder_uid", holder)
                    put("claim_uid", claim)
                    putNull("subject_uid")
                    put("knowledge_state", NpcKnowledgeState.LEGACY_UNKNOWN.name)
                    if (c.isNull(8)) putNull("content_summary") else put("content_summary", c.getString(8))
                    put("confidence_scaled", legacyCertainty(c.getDouble(2)))
                    put("accuracy_scaled", legacyCertainty(c.getDouble(3)))
                    put("acquisition_kind", NpcKnowledgeAcquisitionKind.LEGACY_UNKNOWN.name)
                    putNull("source_actor_uid")
                    putNull("source_evidence_uid")
                    putNull("source_organization_uid")
                    putNull("world_mechanic_uid")
                    put("can_share", if (c.getInt(7) != 0) 1 else 0)
                    putNull("supersedes_knowledge_uid")
                    put("provenance_status", NpcKnowledgeProvenanceStatus.LEGACY_UNKNOWN.name)
                    putNull("created_effective_order")
                    putNull("created_transaction_uid")
                    putNull("created_turn_uid")
                    putNull("created_event_uid")
                    if (c.isNull(6)) putNull("legacy_source_uid") else put("legacy_source_uid", c.getString(6))
                    put("legacy_acquisition_method", c.getString(5))
                    put("legacy_learned_chapter", c.getInt(4))
                    put("schema_version", NPC_KNOWLEDGE_SCHEMA_VERSION)
                }
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    private fun legacyCertainty(value: Double): Long =
        (value.coerceIn(0.0, 1.0) * NPC_KNOWLEDGE_CONFIDENCE_SCALE.toDouble()).roundToLong()

    private fun legacyUid(campaignUid: String, holderUid: String, claimUid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$campaignUid\u001f$holderUid\u001f$claimUid".toByteArray())
        return "RPGOS-P37-LEGACY:" + digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> = buildSet {
        db.rawQuery("PRAGMA table_info($table)", null).use { c -> while (c.moveToNext()) add(c.getString(1)) }
    }
}

internal class NpcKnowledgeStore(private val db: SQLiteDatabase, private val campaignUid: String) {
    init {
        require(campaignUid.isNotBlank()) { "RPGOS-P37:BLANK_CAMPAIGN_UID" }
        check(Phase37NpcKnowledgeSchema.isReady(db)) { "RPGOS-P37:SCHEMA_NOT_READY" }
    }

    fun recordCommitted(
        spec: NpcKnowledgeSpec,
        identity: TurnTransactionIdentity,
        eventUid: String,
        effectiveOrder: Long
    ): NpcKnowledgeRecord {
        requireCanonicalGameplayMutation(db, campaignUid)
        require(db.inTransaction()) { "RPGOS-P37:OUTSIDE_TURN_TRANSACTION" }
        require(identity.campaignUid == campaignUid) { "RPGOS-P37:CROSS_CAMPAIGN" }
        require(eventUid.isNotBlank() && effectiveOrder > 0L) { "RPGOS-P37:INVALID_COMMIT_PROVENANCE" }
        spec.validationErrorsForGameplay().firstOrNull()?.let { throw PlayerChangeSetStructuralException(it) }
        find(spec.knowledgeUid)?.let { existing ->
            require(existing.spec == spec && existing.createdTransactionUid == identity.transactionUid &&
                existing.createdTurnUid == identity.turnUid && existing.createdEventUid == eventUid &&
                existing.createdEffectiveOrder == effectiveOrder && existing.provenanceStatus == NpcKnowledgeProvenanceStatus.COMMITTED) {
                "RPGOS-P37:KNOWLEDGE_IDENTITY_CONFLICT"
            }
            return existing
        }
        spec.supersedesKnowledgeUid?.let { previousUid ->
            val previous = requireNotNull(find(previousUid)) { "RPGOS-P37:SUPERSEDED_KNOWLEDGE_NOT_FOUND" }
            require(previous.spec.holderUid == spec.holderUid) { "RPGOS-P37:CROSS_HOLDER_SUPERSESSION" }
        }
        val values = valuesFor(spec).apply {
            put("campaign_uid", campaignUid)
            put("provenance_status", NpcKnowledgeProvenanceStatus.COMMITTED.name)
            put("created_effective_order", effectiveOrder)
            put("created_transaction_uid", identity.transactionUid)
            put("created_turn_uid", identity.turnUid)
            put("created_event_uid", eventUid)
            putNull("legacy_source_uid")
            putNull("legacy_acquisition_method")
            putNull("legacy_learned_chapter")
            put("schema_version", NPC_KNOWLEDGE_SCHEMA_VERSION)
        }
        check(db.insert(Phase37NpcKnowledgeSchema.TABLE, null, values) != -1L) { "RPGOS-P37:INSERT_FAILED" }
        return requireNotNull(find(spec.knowledgeUid))
    }

    fun knowledgeForActor(holderUid: String, limit: Int = 100): List<NpcKnowledgeRecord> {
        require(holderUid.isNotBlank() && limit in 1..1000)
        val sql = """SELECT ${selectColumns("k")} FROM ${Phase37NpcKnowledgeSchema.TABLE} k
            WHERE k.campaign_uid=? AND k.holder_uid=?
              AND NOT EXISTS(SELECT 1 FROM ${Phase37NpcKnowledgeSchema.TABLE} newer
                  WHERE newer.campaign_uid=k.campaign_uid AND newer.supersedes_knowledge_uid=k.knowledge_uid)
            ORDER BY COALESCE(k.created_effective_order,-1) DESC,k.knowledge_uid LIMIT ?"""
        return db.rawQuery(sql, arrayOf(campaignUid, holderUid, limit.toString())).use(::readAll)
    }

    fun all(limit: Int = 1000): List<NpcKnowledgeRecord> {
        require(limit in 1..5000)
        return db.rawQuery(
            "SELECT ${selectColumns()} FROM ${Phase37NpcKnowledgeSchema.TABLE} WHERE campaign_uid=? ORDER BY COALESCE(created_effective_order,-1),knowledge_uid LIMIT ?",
            arrayOf(campaignUid, limit.toString())
        ).use(::readAll)
    }

    fun holders(): Set<String> = db.rawQuery(
        "SELECT DISTINCT holder_uid FROM ${Phase37NpcKnowledgeSchema.TABLE} WHERE campaign_uid=? ORDER BY holder_uid",
        arrayOf(campaignUid)
    ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun find(knowledgeUid: String): NpcKnowledgeRecord? = db.rawQuery(
        "SELECT ${selectColumns()} FROM ${Phase37NpcKnowledgeSchema.TABLE} WHERE campaign_uid=? AND knowledge_uid=? LIMIT 1",
        arrayOf(campaignUid, knowledgeUid)
    ).use { c -> if (c.moveToFirst()) read(c) else null }

    private fun valuesFor(spec: NpcKnowledgeSpec) = ContentValues().apply {
        put("knowledge_uid", spec.knowledgeUid)
        put("holder_uid", spec.holderUid)
        put("claim_uid", spec.claimUid)
        if (spec.subjectUid == null) putNull("subject_uid") else put("subject_uid", spec.subjectUid)
        put("knowledge_state", spec.state.name)
        if (spec.contentSummary == null) putNull("content_summary") else put("content_summary", spec.contentSummary)
        put("confidence_scaled", spec.confidenceScaled)
        put("accuracy_scaled", spec.accuracyScaled)
        put("acquisition_kind", spec.acquisitionKind.name)
        if (spec.sourceActorUid == null) putNull("source_actor_uid") else put("source_actor_uid", spec.sourceActorUid)
        if (spec.sourceEvidenceUid == null) putNull("source_evidence_uid") else put("source_evidence_uid", spec.sourceEvidenceUid)
        if (spec.sourceOrganizationUid == null) putNull("source_organization_uid") else put("source_organization_uid", spec.sourceOrganizationUid)
        if (spec.worldMechanicUid == null) putNull("world_mechanic_uid") else put("world_mechanic_uid", spec.worldMechanicUid)
        put("can_share", if (spec.canShare) 1 else 0)
        if (spec.supersedesKnowledgeUid == null) putNull("supersedes_knowledge_uid") else put("supersedes_knowledge_uid", spec.supersedesKnowledgeUid)
    }

    private fun selectColumns(alias: String? = null): String {
        val p = alias?.let { "$it." }.orEmpty()
        return listOf(
            "knowledge_uid", "holder_uid", "claim_uid", "subject_uid", "knowledge_state", "content_summary",
            "confidence_scaled", "accuracy_scaled", "acquisition_kind", "source_actor_uid", "source_evidence_uid",
            "source_organization_uid", "world_mechanic_uid", "can_share", "supersedes_knowledge_uid", "provenance_status",
            "created_effective_order", "created_transaction_uid", "created_turn_uid", "created_event_uid", "legacy_source_uid",
            "legacy_acquisition_method", "legacy_learned_chapter", "schema_version"
        ).joinToString(",") { p + it }
    }

    private fun readAll(c: android.database.Cursor): List<NpcKnowledgeRecord> = buildList { while (c.moveToNext()) add(read(c)) }

    private fun read(c: android.database.Cursor): NpcKnowledgeRecord {
        fun optString(index: Int) = if (c.isNull(index)) null else c.getString(index)
        fun optLong(index: Int) = if (c.isNull(index)) null else c.getLong(index)
        fun optInt(index: Int) = if (c.isNull(index)) null else c.getInt(index)
        val spec = NpcKnowledgeSpec(
            knowledgeUid = c.getString(0), holderUid = c.getString(1), claimUid = c.getString(2), subjectUid = optString(3),
            state = NpcKnowledgeState.valueOf(c.getString(4)), contentSummary = optString(5), confidenceScaled = c.getLong(6),
            accuracyScaled = c.getLong(7), acquisitionKind = NpcKnowledgeAcquisitionKind.valueOf(c.getString(8)),
            sourceActorUid = optString(9), sourceEvidenceUid = optString(10), sourceOrganizationUid = optString(11),
            worldMechanicUid = optString(12), canShare = c.getInt(13) != 0, supersedesKnowledgeUid = optString(14)
        )
        return NpcKnowledgeRecord(
            campaignUid, spec, NpcKnowledgeProvenanceStatus.valueOf(c.getString(15)), optLong(16), optString(17), optString(18),
            optString(19), optString(20), optString(21), optInt(22), c.getInt(23)
        )
    }
}
'''
(ROOT / "app/src/main/java/com/rpgos/app/Phase37NpcKnowledge.kt").write_text(phase37)

replace_once(
    "app/src/main/java/com/rpgos/app/BundledCampaignPersistentFamilies.kt",
    '        "npc_beliefs",\n        "npc_memories_v2",',
    '        "npc_beliefs",\n        "npc_knowledge_records",\n        "npc_memories_v2",'
)

replace_once(
    "app/src/main/java/com/rpgos/app/RuntimePersistentInventory.kt",
    '        "CAMPAIGN_TRUTH",\n        "CANON_DIVERGENCE",\n        "DEVELOPMENT_PROJECTS",',
    '        "CAMPAIGN_TRUTH",\n        "CANON_DIVERGENCE",\n        "NPC_KNOWLEDGE_STATE",\n        "DEVELOPMENT_PROJECTS",'
)

replace_once(
    "app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt",
    '        "OWNERSHIP_HISTORY","FINANCE_AUTHORITY","DEVELOPMENT_PROJECTS"',
    '        "OWNERSHIP_HISTORY","FINANCE_AUTHORITY","NPC_KNOWLEDGE_STATE","DEVELOPMENT_PROJECTS"'
)

replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '    CANON_DIVERGENCE, FINANCE, INVENTORY, OWNERSHIP, DEVELOPMENT_PROJECT\n',
    '    CANON_DIVERGENCE, NPC_KNOWLEDGE, FINANCE, INVENTORY, OWNERSHIP, DEVELOPMENT_PROJECT\n'
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '    const val PLAN_IMPLEMENTATION_REVISION = "RPGOS-P36-MIGRATION-IMPL-1"',
    '    const val PLAN_IMPLEMENTATION_REVISION = "RPGOS-P36-MIGRATION-IMPL-2"'
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '        SchemaFamilyContract(SchemaFamilyUid.CANON_DIVERGENCE, CANON_DIVERGENCE_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.EVENT)),\n        SchemaFamilyContract(SchemaFamilyUid.FINANCE,',
    '        SchemaFamilyContract(SchemaFamilyUid.CANON_DIVERGENCE, CANON_DIVERGENCE_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.EVENT)),\n        SchemaFamilyContract(SchemaFamilyUid.NPC_KNOWLEDGE, NPC_KNOWLEDGE_SCHEMA_VERSION, 1, setOf(SchemaFamilyUid.CAMPAIGN, SchemaFamilyUid.EVENT)),\n        SchemaFamilyContract(SchemaFamilyUid.FINANCE,'
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '                Phase35CanonDivergenceSchema.ensureReady(db)\n                ordered.forEach { contract ->',
    '                Phase35CanonDivergenceSchema.ensureReady(db)\n                Phase37NpcKnowledgeSchema.ensureReady(db, campaignUid)\n                ordered.forEach { contract ->'
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '        check(table(db, VERSIONS) && table(db, ATTEMPTS) && Phase35CanonDivergenceSchema.isReady(db)) { "RPGOS-SCHEMA:NOT_READY" }',
    '        check(table(db, VERSIONS) && table(db, ATTEMPTS) && Phase35CanonDivergenceSchema.isReady(db) && Phase37NpcKnowledgeSchema.isReady(db)) { "RPGOS-SCHEMA:NOT_READY" }'
)

replace_once(
    "app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt",
    ') : PlayerDomainChangePayload\n\nenum class ConditionOperation { ADD, REMOVE }',
    ') : PlayerDomainChangePayload\n\n/** Typed actor-scoped knowledge acquisition; Phase37 store is the sole knowledge authority. */\ndata class NpcKnowledgeChange(val spec: NpcKnowledgeSpec) : PlayerDomainChangePayload\n\nenum class ConditionOperation { ADD, REMOVE }'
)
replace_once(
    "app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt",
    '    const val CAMPAIGN_TRUTH = "RPGOS-CHANGE:CAMPAIGN_TRUTH"\n    const val CONDITION =',
    '    const val CAMPAIGN_TRUTH = "RPGOS-CHANGE:CAMPAIGN_TRUTH"\n    const val NPC_KNOWLEDGE = "RPGOS-CHANGE:NPC_KNOWLEDGE_ACQUIRE"\n    const val CONDITION ='
)

npc_codec = r'''    PlayerChangeKinds.NPC_KNOWLEDGE to simpleCodec(
        NpcKnowledgeChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("knowledgeUid", "holderUid", "claimUid", "subjectUid", "state", "contentSummary", "confidenceScaled", "accuracyScaled",
            "acquisitionKind", "sourceActorUid", "sourceEvidenceUid", "sourceOrganizationUid", "worldMechanicUid", "canShare", "supersedesKnowledgeUid"),
        encode = {
            val s = it.spec
            pcsObj(
                "knowledgeUid" to pcsJ(s.knowledgeUid), "holderUid" to pcsJ(s.holderUid), "claimUid" to pcsJ(s.claimUid),
                "subjectUid" to pcsJn(s.subjectUid), "state" to pcsJ(s.state.name), "contentSummary" to pcsJn(s.contentSummary),
                "confidenceScaled" to pcsJ(s.confidenceScaled), "accuracyScaled" to pcsJ(s.accuracyScaled),
                "acquisitionKind" to pcsJ(s.acquisitionKind.name), "sourceActorUid" to pcsJn(s.sourceActorUid),
                "sourceEvidenceUid" to pcsJn(s.sourceEvidenceUid), "sourceOrganizationUid" to pcsJn(s.sourceOrganizationUid),
                "worldMechanicUid" to pcsJn(s.worldMechanicUid), "canShare" to pcsJ(if (s.canShare) 1L else 0L),
                "supersedesKnowledgeUid" to pcsJn(s.supersedesKnowledgeUid)
            )
        },
        decode = {
            val canShare = it.pcsReqLong("canShare")
            if (canShare !in 0L..1L) throw PlayerChangeSetStructuralException("INVALID_NPC_KNOWLEDGE_CAN_SHARE")
            NpcKnowledgeChange(NpcKnowledgeSpec(
                knowledgeUid = it.pcsReqString("knowledgeUid"), holderUid = it.pcsReqString("holderUid"), claimUid = it.pcsReqString("claimUid"),
                subjectUid = it.pcsOptString("subjectUid"), state = enumValue(it.pcsReqString("state"), "INVALID_NPC_KNOWLEDGE_STATE"),
                contentSummary = it.pcsOptString("contentSummary"), confidenceScaled = it.pcsReqLong("confidenceScaled"),
                accuracyScaled = it.pcsReqLong("accuracyScaled"), acquisitionKind = enumValue(it.pcsReqString("acquisitionKind"), "INVALID_NPC_KNOWLEDGE_ACQUISITION_KIND"),
                sourceActorUid = it.pcsOptString("sourceActorUid"), sourceEvidenceUid = it.pcsOptString("sourceEvidenceUid"),
                sourceOrganizationUid = it.pcsOptString("sourceOrganizationUid"), worldMechanicUid = it.pcsOptString("worldMechanicUid"),
                canShare = canShare == 1L, supersedesKnowledgeUid = it.pcsOptString("supersedesKnowledgeUid")
            ))
        },
        validate = { it.spec.validationErrorsForGameplay() },
        conflicts = { setOf("NPC_KNOWLEDGE:${it.spec.knowledgeUid}") }
    ),
'''
replace_once(
    "app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt",
    '    PlayerChangeKinds.CONDITION to simpleCodec(',
    npc_codec + '    PlayerChangeKinds.CONDITION to simpleCodec('
)

world_rule = r'''            is NpcKnowledgeChange -> {
                val k = payload.spec
                field("KNOWLEDGE_UID", k.knowledgeUid); field("HOLDER_UID", k.holderUid); field("CLAIM_UID", k.claimUid)
                nullableField("SUBJECT_UID", k.subjectUid); field("KNOWLEDGE_STATE", k.state.name); nullableField("CONTENT_SUMMARY", k.contentSummary)
                longField("CONFIDENCE_SCALED", k.confidenceScaled); longField("ACCURACY_SCALED", k.accuracyScaled)
                field("ACQUISITION_KIND", k.acquisitionKind.name); nullableField("SOURCE_ACTOR_UID", k.sourceActorUid)
                nullableField("SOURCE_EVIDENCE_UID", k.sourceEvidenceUid); nullableField("SOURCE_ORGANIZATION_UID", k.sourceOrganizationUid)
                nullableField("WORLD_MECHANIC_UID", k.worldMechanicUid); field("CAN_SHARE", k.canShare.toString())
                nullableField("SUPERSEDES_KNOWLEDGE_UID", k.supersedesKnowledgeUid)
            }
'''
replace_once(
    "app/src/main/java/com/rpgos/app/WorldRuleProvider.kt",
    '            is ConditionChange -> {\n',
    world_rule + '            is ConditionChange -> {\n'
)

replace_once(
    "app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt",
    '            is CampaignTruthChange -> Unit\n            is ConditionChange ->',
    '            is CampaignTruthChange -> Unit\n            is NpcKnowledgeChange -> Unit\n            is ConditionChange ->'
)

replace_once(
    "app/src/main/java/com/rpgos/app/CampaignEventStore.kt",
    '            is CampaignTruthChange -> PlayerChangeKinds.CAMPAIGN_TRUTH\n            is ConditionChange ->',
    '            is CampaignTruthChange -> PlayerChangeKinds.CAMPAIGN_TRUTH\n            is NpcKnowledgeChange -> PlayerChangeKinds.NPC_KNOWLEDGE\n            is ConditionChange ->'
)
replace_once(
    "app/src/main/java/com/rpgos/app/CampaignEventStore.kt",
    '        is CampaignTruthChange -> DomainRef("CAMPAIGN_TRUTH", payload.truthUid)\n        is ConditionChange ->',
    '        is CampaignTruthChange -> DomainRef("CAMPAIGN_TRUTH", payload.truthUid)\n        is NpcKnowledgeChange -> DomainRef("NPC_KNOWLEDGE", payload.spec.knowledgeUid)\n        is ConditionChange ->'
)

replace_once(
    "app/src/main/java/com/rpgos/app/TurnTransaction.kt",
    '                is EquipmentChange,is FinancialChange,is OwnershipChange,is CampaignTruthChange,\n                is DevelopmentProjectChange -> Unit',
    '                is EquipmentChange,is FinancialChange,is OwnershipChange,is CampaignTruthChange,\n                is NpcKnowledgeChange,is DevelopmentProjectChange -> Unit'
)
replace_once(
    "app/src/main/java/com/rpgos/app/TurnTransaction.kt",
    '                is CampaignTruthChange->applyTruth(db,identity,changeSet,change.changeUid,payload)\n                is DevelopmentProjectChange->',
    '                is CampaignTruthChange->applyTruth(db,identity,changeSet,change.changeUid,payload)\n                is NpcKnowledgeChange->applyNpcKnowledge(db,identity,changeSet,change.changeUid,payload)\n                is DevelopmentProjectChange->'
)
apply_knowledge = r'''    private fun applyNpcKnowledge(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:NpcKnowledgeChange){
        val eventStore=CampaignEventStore(db,identity.campaignUid)
        val intent=eventStore.resolveRequiredManifest(identity,changeSet).intents.singleOrNull{changeUid in it.causalChangeUids}
            ?:error("RPGOS-P37:KNOWLEDGE_REQUIRES_EXACT_EVENT")
        val eventUid=eventStore.eventUid(identity,changeSet,intent)
        NpcKnowledgeStore(db,identity.campaignUid).recordCommitted(p.spec,identity,eventUid,effectiveOrder(changeSet))
    }

'''
replace_once(
    "app/src/main/java/com/rpgos/app/TurnTransaction.kt",
    '    private fun applyProject(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:DevelopmentProjectChange){',
    apply_knowledge + '    private fun applyProject(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:DevelopmentProjectChange){'
)

test = r'''package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase37NpcKnowledgeTest {
    private lateinit var root: File
    private lateinit var dbFile: File

    @Before fun setUp() { root = kotlin.io.path.createTempDirectory("p37-").toFile(); dbFile = File(root, "campaign.db") }
    @After fun tearDown() { root.deleteRecursively(); specs.clear() }

    @Test fun acquisitionValidationAndPerspectiveIsolationAreEnforced() {
        assertEquals("NPC_KNOWLEDGE_OBSERVATION_EVIDENCE_REQUIRED", invalidObservationCode())
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertTrue(commit(db, "A", spec("K-A", "NPC-A", "CLAIM-A")) is TurnExecutionResult.Committed)
            assertTrue(commit(db, "B", spec("K-B", "NPC-B", "CLAIM-B")) is TurnExecutionResult.Committed)
            val store = NpcKnowledgeStore(db, "C1")
            assertEquals(listOf("K-A"), store.knowledgeForActor("NPC-A").map { it.spec.knowledgeUid })
            assertEquals(listOf("K-B"), store.knowledgeForActor("NPC-B").map { it.spec.knowledgeUid })
            val row = store.knowledgeForActor("NPC-A").single()
            assertEquals(NpcKnowledgeProvenanceStatus.COMMITTED, row.provenanceStatus)
            assertEquals("TX-A", row.createdTransactionUid)
            assertEquals("TURN-A", row.createdTurnUid)
            assertTrue(row.createdEventUid!!.startsWith("RPGOS-EVENT:"))
        }
    }

    @Test fun rollbackAndRetryAreAtomicAndIdempotent() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val proposal = proposal("ROLL", spec("K-ROLL", "NPC-A", "CLAIM-R"))
            assertTrue(runCatching {
                TurnTransactionBoundary.create(
                    db, TurnTransactionIdentity("C1", "TURN-ROLL", "ROLL", "TX-ROLL"), proposal,
                    TurnFailureInjector { if (it == TurnFailurePoint.AFTER_EVENT_APPEND) error("crash") }
                ).commit()
            }.isFailure)
            assertTrue(NpcKnowledgeStore(db, "C1").all().isEmpty())

            val first = commit(db, "IDEM", spec("K-IDEM", "NPC-A", "CLAIM-I"))
            val retryProposal = proposal("IDEM", spec("K-IDEM", "NPC-A", "CLAIM-I"))
            val second = TurnTransactionBoundary.create(
                db, TurnTransactionIdentity("C1", "TURN-IDEM", "IDEM", "TX-IDEM"), retryProposal
            ).commit()
            assertTrue(first is TurnExecutionResult.Committed)
            assertTrue(second is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1, NpcKnowledgeStore(db, "C1").all().size)
        }
    }

    @Test fun legacyRowsAreImportedWithoutInventedCommitProvenance() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE information_facts(info_uid TEXT PRIMARY KEY,title TEXT,content_summary TEXT,secrecy_level INTEGER)")
            db.execSQL("CREATE TABLE information_knowledge(holder_uid TEXT NOT NULL,info_uid TEXT NOT NULL,confidence REAL NOT NULL,accuracy REAL NOT NULL,learned_chapter INTEGER NOT NULL,acquisition_method TEXT NOT NULL,source_uid TEXT,can_share INTEGER NOT NULL,PRIMARY KEY(holder_uid,info_uid))")
            db.execSQL("INSERT INTO information_facts(info_uid,title,content_summary,secrecy_level) VALUES('I1','Legacy','Old memory',1)")
            db.execSQL("INSERT INTO information_knowledge(holder_uid,info_uid,confidence,accuracy,learned_chapter,acquisition_method,source_uid,can_share) VALUES('NPC-A','I1',0.7,0.8,12,'rumor','SRC-OLD',1)")
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val row = NpcKnowledgeStore(db, "C1").knowledgeForActor("NPC-A").single()
            assertEquals(NpcKnowledgeState.LEGACY_UNKNOWN, row.spec.state)
            assertEquals(NpcKnowledgeAcquisitionKind.LEGACY_UNKNOWN, row.spec.acquisitionKind)
            assertEquals(NpcKnowledgeProvenanceStatus.LEGACY_UNKNOWN, row.provenanceStatus)
            assertEquals("rumor", row.legacyAcquisitionMethod)
            assertEquals("SRC-OLD", row.legacySourceUid)
            assertEquals(12, row.legacyLearnedChapter)
            assertNull(row.createdTransactionUid); assertNull(row.createdTurnUid); assertNull(row.createdEventUid)
        }
    }

    @Test fun snapshotReplayReconstructsKnowledgeExactly() {
        val snapshots = File(root, "snapshots")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            CampaignSnapshotManager(db, "C1", snapshots).create()
            commit(db, "AFTER", spec("K-AFTER", "NPC-A", "CLAIM-AFTER"))
            val expected = NpcKnowledgeStore(db, "C1").knowledgeForActor("NPC-A")
            val staged = CampaignSnapshotManager(db, "C1", snapshots).reconstructToVerifiedStaging()
            SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { restored ->
                assertEquals(expected, NpcKnowledgeStore(restored, "C1").knowledgeForActor("NPC-A"))
            }
        }
    }

    @Test fun writerAndReplayContractsCoverNpcKnowledgeAuthority() {
        assertTrue("NPC_KNOWLEDGE_STATE" in RuntimePersistentWriterRegistry.canonicalTurnTargetFamilies)
        assertEquals(ReplayAuthorityCoverage.REPLAYABLE, CampaignReplayAuthorityMatrix.coverage("NPC_KNOWLEDGE_STATE"))
    }

    private fun invalidObservationCode(): String? = runCatching {
        PlayerDomainChange.create(
            "BAD", PlayerChangeKinds.NPC_KNOWLEDGE,
            NpcKnowledgeChange(NpcKnowledgeSpec(
                "K-BAD", "NPC-A", "CLAIM-BAD", state = NpcKnowledgeState.KNOWN,
                confidenceScaled = 500_000, accuracyScaled = 500_000,
                acquisitionKind = NpcKnowledgeAcquisitionKind.OBSERVATION
            ))
        )
    }.exceptionOrNull().let { (it as? PlayerChangeSetStructuralException)?.code }

    private fun spec(uid: String, holder: String, claim: String) = NpcKnowledgeSpec(
        knowledgeUid = uid, holderUid = holder, claimUid = claim, subjectUid = "SUBJECT-1",
        state = NpcKnowledgeState.KNOWN, contentSummary = "Observed fact $claim",
        confidenceScaled = 900_000, accuracyScaled = 950_000,
        acquisitionKind = NpcKnowledgeAcquisitionKind.OBSERVATION,
        sourceEvidenceUid = "OBS-EVIDENCE-$uid"
    )

    private fun commit(db: SQLiteDatabase, command: String, spec: NpcKnowledgeSpec, campaign: String = "C1") =
        TurnTransactionBoundary.create(
            db, TurnTransactionIdentity(campaign, "TURN-$command", command, "TX-$command"), proposal(command, spec, campaign)
        ).commit()

    private fun proposal(command: String, spec: NpcKnowledgeSpec, campaign: String = "C1"): CanonicalCampaignMutationProposal {
        specs[command] = spec
        val actor = CommandActorRef("PLAYER", "P1")
        val cmd = PlayerCommand(
            commandUid = command, campaignUid = campaign, actor = actor, commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 1, "CUR"), provenance = CommandProvenance("P37"), requestedEffectiveOrder = 1
        )
        val context = PlayerResolutionContext.createUnboundGeneric(campaign, actor, setOf(
            CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        ))
        val engine = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(KnowledgeComponent())))
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, cmd, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("admission rejected: ${admission.reasonUid}")
        }
    }

    private class KnowledgeComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class, "P37-KNOWLEDGE", "1"
    ) {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val spec = requireNotNull(specs[command.commandUid])
            val changeUid = "CHANGE-${command.commandUid}"
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(
                changes = listOf(PlayerDomainChange.create(changeUid, PlayerChangeKinds.NPC_KNOWLEDGE, NpcKnowledgeChange(spec)))
            ))
        }
    }

    companion object { private val specs = mutableMapOf<String, NpcKnowledgeSpec>() }
}
'''
(ROOT / "app/src/test/java/com/rpgos/app/Phase37NpcKnowledgeTest.kt").write_text(test)

print("Phase37 guarded patch applied")
