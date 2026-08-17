package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

const val PHASE31_CAUSAL_SCHEMA_VERSION = 1

enum class CausalRelationClass { CAUSAL, PROVENANCE, EVIDENCE, TEMPORAL, NARRATIVE, DERIVED, RETRIEVAL }

object CausalRelationKinds {
    const val CAUSES = "RPGOS-REL:CAUSES"
    const val ENABLES = "RPGOS-REL:ENABLES"
    const val PREVENTS = "RPGOS-REL:PREVENTS"
    const val TRIGGERED_BY = "RPGOS-REL:TRIGGERED_BY"
    const val PROVENANCE_OF = "RPGOS-REL:PROVENANCE_OF"
    const val EVIDENCE_FOR = "RPGOS-REL:EVIDENCE_FOR"
    const val BEFORE = "RPGOS-REL:BEFORE"
    const val NARRATIVE_ASSOCIATION = "RPGOS-REL:NARRATIVE_ASSOCIATION"
    const val DERIVED_FROM = "RPGOS-REL:DERIVED_FROM"
    const val RETRIEVED_WITH = "RPGOS-REL:RETRIEVED_WITH"

    fun expectedClass(kindUid: String): CausalRelationClass = when (kindUid) {
        CAUSES, ENABLES, PREVENTS, TRIGGERED_BY -> CausalRelationClass.CAUSAL
        PROVENANCE_OF -> CausalRelationClass.PROVENANCE
        EVIDENCE_FOR -> CausalRelationClass.EVIDENCE
        BEFORE -> CausalRelationClass.TEMPORAL
        NARRATIVE_ASSOCIATION -> CausalRelationClass.NARRATIVE
        DERIVED_FROM -> CausalRelationClass.DERIVED
        RETRIEVED_WITH -> CausalRelationClass.RETRIEVAL
        else -> throw CausalGraphIntegrityException("UNKNOWN_RELATION_KIND")
    }
}

class CausalGraphIntegrityException(code: String) : IllegalStateException("RPGOS-CAUSAL-GRAPH:$code")
class CausalGraphIdentityConflictException : IllegalStateException("RPGOS-CAUSAL-GRAPH:IDENTITY_CONFLICT")

data class CanonicalCausalRelationIntent(
    val relationIntentUid: String,
    val relationClass: CausalRelationClass,
    val relationKindUid: String,
    val sourceEventUid: String,
    val targetEventUid: String,
    val evidenceEventUids: List<String> = emptyList(),
    val provenanceEventUids: List<String> = emptyList(),
    val supersedesRelationUid: String? = null
) {
    init {
        require(relationIntentUid.isNotBlank())
        require(sourceEventUid.isNotBlank())
        require(targetEventUid.isNotBlank())
        require(relationKindUid.isNotBlank())
        require(evidenceEventUids.none { it.isBlank() })
        require(provenanceEventUids.none { it.isBlank() })
        require(supersedesRelationUid?.isNotBlank() != false)
    }
}

data class CanonicalCausalRelationRecord(
    val campaignUid: String,
    val relationUid: String,
    val transactionUid: String,
    val relationIntentUid: String,
    val relationClass: CausalRelationClass,
    val relationKindUid: String,
    val sourceEventUid: String,
    val targetEventUid: String,
    val supersedesRelationUid: String?,
    val semanticFingerprint: String
)

internal object CampaignCausalGraphSchema {
    const val TABLE = "canonical_causal_relations"

    fun ensureReady(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE(
            campaign_uid TEXT NOT NULL,
            relation_uid TEXT NOT NULL,
            transaction_uid TEXT NOT NULL,
            turn_uid TEXT NOT NULL,
            command_uid TEXT NOT NULL,
            relation_intent_uid TEXT NOT NULL,
            relation_class_uid TEXT NOT NULL,
            relation_kind_uid TEXT NOT NULL,
            source_event_uid TEXT NOT NULL,
            target_event_uid TEXT NOT NULL,
            evidence_event_uids_canonical TEXT NOT NULL,
            provenance_event_uids_canonical TEXT NOT NULL,
            supersedes_relation_uid TEXT,
            committed_order INTEGER NOT NULL,
            semantic_fingerprint TEXT NOT NULL,
            schema_version INTEGER NOT NULL,
            PRIMARY KEY(campaign_uid,relation_uid),
            UNIQUE(campaign_uid,transaction_uid,relation_intent_uid),
            UNIQUE(campaign_uid,committed_order)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_causal_graph_tx ON $TABLE(campaign_uid,transaction_uid,committed_order)")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rpgos_causal_graph_no_update
            BEFORE UPDATE ON $TABLE BEGIN SELECT RAISE(ABORT,'RPGOS-CAUSAL-GRAPH:APPEND_ONLY'); END""".trimIndent())
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rpgos_causal_graph_no_delete
            BEFORE DELETE ON $TABLE BEGIN SELECT RAISE(ABORT,'RPGOS-CAUSAL-GRAPH:APPEND_ONLY'); END""".trimIndent())
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rpgos_causal_graph_turn_insert
            BEFORE INSERT ON $TABLE
            WHEN NOT EXISTS(SELECT 1 FROM ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}
                WHERE campaign_uid=NEW.campaign_uid AND capability_kind='TURN')
              OR NOT EXISTS(SELECT 1 FROM ${CampaignIntelligencePhase30Schema.WRITER_CONTEXT_TABLE}
                WHERE campaign_uid=NEW.campaign_uid AND writer_contract_version >= $PHASE30_WRITER_CONTRACT_VERSION)
            BEGIN SELECT RAISE(ABORT,'RPGOS-CAUSAL-GRAPH:CANONICAL_TURN_REQUIRED'); END""".trimIndent())
    }
}

internal class CampaignCausalGraph(private val db: SQLiteDatabase, private val campaignUid: String) {
    init { require(campaignUid.isNotBlank()) }

    fun validate(intents: List<CanonicalCausalRelationIntent>) {
        val ids = intents.map { it.relationIntentUid }
        if (ids.distinct().size != ids.size) throw CausalGraphIntegrityException("DUPLICATE_RELATION_INTENT")
        intents.forEach { intent ->
            if (CausalRelationKinds.expectedClass(intent.relationKindUid) != intent.relationClass) {
                throw CausalGraphIntegrityException("RELATION_CLASS_KIND_MISMATCH")
            }
            if (intent.relationClass == CausalRelationClass.CAUSAL &&
                intent.evidenceEventUids.isEmpty() && intent.provenanceEventUids.isEmpty()) {
                throw CausalGraphIntegrityException("CAUSAL_RELATION_REQUIRES_EXPLICIT_EVIDENCE_OR_PROVENANCE")
            }
        }
    }

    fun appendRequired(identity: TurnTransactionIdentity, intents: List<CanonicalCausalRelationIntent>) {
        check(db.inTransaction()) { "RPGOS-CAUSAL-GRAPH:OUTSIDE_TURN_TRANSACTION" }
        require(identity.campaignUid == campaignUid) { "RPGOS-CAUSAL-GRAPH:CROSS_CAMPAIGN" }
        validate(intents)
        intents.sortedBy { it.relationIntentUid }.forEach { intent ->
            validateEventEndpoint(intent.sourceEventUid)
            validateEventEndpoint(intent.targetEventUid)
            intent.evidenceEventUids.forEach(::validateEventEndpoint)
            intent.provenanceEventUids.forEach(::validateEventEndpoint)
            intent.supersedesRelationUid?.let { previous ->
                if (!relationExists(previous)) throw CausalGraphIntegrityException("DANGLING_SUPERSESSION")
            }
            val planned = planned(identity, intent)
            find(identity.transactionUid, intent.relationIntentUid)?.let { existing ->
                if (existing.relationUid != planned.first || existing.semanticFingerprint != planned.second) {
                    throw CausalGraphIdentityConflictException()
                }
                return@forEach
            }
            val values = ContentValues().apply {
                put("campaign_uid", campaignUid); put("relation_uid", planned.first)
                put("transaction_uid", identity.transactionUid); put("turn_uid", identity.turnUid); put("command_uid", identity.commandUid)
                put("relation_intent_uid", intent.relationIntentUid); put("relation_class_uid", intent.relationClass.name); put("relation_kind_uid", intent.relationKindUid)
                put("source_event_uid", intent.sourceEventUid); put("target_event_uid", intent.targetEventUid)
                put("evidence_event_uids_canonical", encodeStrings(intent.evidenceEventUids)); put("provenance_event_uids_canonical", encodeStrings(intent.provenanceEventUids))
                if (intent.supersedesRelationUid == null) putNull("supersedes_relation_uid") else put("supersedes_relation_uid", intent.supersedesRelationUid)
                put("committed_order", nextOrder()); put("semantic_fingerprint", planned.second); put("schema_version", PHASE31_CAUSAL_SCHEMA_VERSION)
            }
            if (db.insert(CampaignCausalGraphSchema.TABLE, null, values) == -1L) throw CausalGraphIntegrityException("APPEND_FAILED")
        }
    }

    fun assertCommittedSetMatches(identity: TurnTransactionIdentity, intents: List<CanonicalCausalRelationIntent>) {
        validate(intents)
        val rows = relationsForTransaction(identity.transactionUid)
        val expected = intents.sortedBy { it.relationIntentUid }.map { planned(identity, it) }
        if (rows.size != expected.size) throw CausalGraphIntegrityException("COMMITTED_SET_MISSING_OR_EXTRA")
        expected.forEachIndexed { i, p -> if (rows[i].relationUid != p.first || rows[i].semanticFingerprint != p.second) throw CausalGraphIdentityConflictException() }
    }

    fun relationsForTransaction(transactionUid: String): List<CanonicalCausalRelationRecord> =
        if (!tableExists()) emptyList() else db.rawQuery("""SELECT relation_uid,transaction_uid,relation_intent_uid,relation_class_uid,relation_kind_uid,
            source_event_uid,target_event_uid,supersedes_relation_uid,semantic_fingerprint FROM ${CampaignCausalGraphSchema.TABLE}
            WHERE campaign_uid=? AND transaction_uid=? ORDER BY relation_intent_uid""", arrayOf(campaignUid, transactionUid)).use { c ->
            buildList { while (c.moveToNext()) add(CanonicalCausalRelationRecord(campaignUid,c.getString(0),c.getString(1),c.getString(2),CausalRelationClass.valueOf(c.getString(3)),c.getString(4),c.getString(5),c.getString(6),if(c.isNull(7))null else c.getString(7),c.getString(8))) }
        }

    fun relationUid(identity: TurnTransactionIdentity, relationIntentUid: String): String =
        "RPGOS-RELATION:" + sha256("$campaignUid|${identity.transactionUid}|${identity.commandUid}|$relationIntentUid")

    fun planFingerprint(identity: TurnTransactionIdentity, intents: List<CanonicalCausalRelationIntent>): String {
        if (intents.isEmpty()) return ""
        validate(intents)
        return sha256(intents.sortedBy { it.relationIntentUid }.joinToString("\u001f") { planned(identity, it).second })
    }

    private fun planned(identity: TurnTransactionIdentity, intent: CanonicalCausalRelationIntent): Pair<String,String> {
        val semantic = listOf("v=$PHASE31_CAUSAL_SCHEMA_VERSION","campaign=$campaignUid","tx=${identity.transactionUid}","turn=${identity.turnUid}","command=${identity.commandUid}",
            "intent=${intent.relationIntentUid}","class=${intent.relationClass.name}","kind=${intent.relationKindUid}","source=${intent.sourceEventUid}","target=${intent.targetEventUid}",
            "evidence=${encodeStrings(intent.evidenceEventUids)}","provenance=${encodeStrings(intent.provenanceEventUids)}","supersedes=${intent.supersedesRelationUid ?: "UNKNOWN"}").joinToString("|")
        return relationUid(identity,intent.relationIntentUid) to sha256(semantic)
    }

    private fun validateEventEndpoint(eventUid: String) {
        val campaign = db.rawQuery("SELECT campaign_uid FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} WHERE event_uid=? LIMIT 1", arrayOf(eventUid)).use { c -> if(c.moveToFirst()) c.getString(0) else null }
        if (campaign == null) throw CausalGraphIntegrityException("DANGLING_EVENT_ENDPOINT")
        if (campaign != campaignUid) throw CausalGraphIntegrityException("CROSS_CAMPAIGN_EVENT_ENDPOINT")
    }
    private fun relationExists(uid: String): Boolean = db.rawQuery("SELECT 1 FROM ${CampaignCausalGraphSchema.TABLE} WHERE campaign_uid=? AND relation_uid=? LIMIT 1", arrayOf(campaignUid,uid)).use { it.moveToFirst() }
    private fun find(tx: String, intent: String): CanonicalCausalRelationRecord? = relationsForTransaction(tx).singleOrNull { it.relationIntentUid == intent }
    private fun nextOrder(): Long = db.rawQuery("SELECT COALESCE(MAX(committed_order),0)+1 FROM ${CampaignCausalGraphSchema.TABLE} WHERE campaign_uid=?", arrayOf(campaignUid)).use { it.moveToFirst(); it.getLong(0) }
    private fun encodeStrings(values: List<String>) = values.sorted().joinToString(";") { "${it.length}:$it" }
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun tableExists()=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(CampaignCausalGraphSchema.TABLE)).use{it.moveToFirst()}
}
