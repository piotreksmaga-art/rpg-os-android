package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

const val PHASE30_EVENT_SCHEMA_VERSION = 1
const val PHASE30_WRITER_CONTRACT_VERSION = 30

class EventStoreIntegrityException(code: String) : IllegalStateException("RPGOS-EVENT-STORE:$code")
class EventStoreIdentityConflictException : IllegalStateException("RPGOS-EVENT-STORE:IDENTITY_CONFLICT")

/**
 * Prospective Phase-30 activation. Pre-activation history is intentionally not reconstructed.
 * The event store is authoritative only for the fact that these semantic event records committed.
 */
internal object CampaignIntelligencePhase30Schema {
    const val ACTIVATION_TABLE = "campaign_intelligence_activation"
    const val WRITER_CONTEXT_TABLE = "rpgos_writer_contract_context"
    const val EVENT_TABLE = "canonical_gameplay_events"

    fun ensureActivated(db: SQLiteDatabase, campaignUid: String) {
        require(campaignUid.isNotBlank())
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $ACTIVATION_TABLE(
                campaign_uid TEXT PRIMARY KEY,
                event_schema_version INTEGER NOT NULL,
                min_writer_contract_version INTEGER NOT NULL,
                legacy_event_history_status TEXT NOT NULL CHECK(legacy_event_history_status IN ('UNKNOWN_NOT_RECORDED','LEGACY','PARTIALLY_RECORDED'))
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $WRITER_CONTEXT_TABLE(
                campaign_uid TEXT PRIMARY KEY,
                writer_contract_version INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS $EVENT_TABLE(
                campaign_uid TEXT NOT NULL,
                event_uid TEXT NOT NULL,
                transaction_uid TEXT NOT NULL,
                turn_uid TEXT NOT NULL,
                command_uid TEXT NOT NULL,
                event_intent_uid TEXT NOT NULL,
                event_kind_uid TEXT NOT NULL,
                committed_order INTEGER NOT NULL,
                source_actor_kind_uid TEXT NOT NULL,
                source_actor_uid TEXT NOT NULL,
                actor_ref_kind_uid TEXT,
                actor_ref_uid TEXT,
                subject_ref_kind_uid TEXT NOT NULL,
                subject_ref_uid TEXT NOT NULL,
                target_refs_canonical TEXT NOT NULL,
                causal_change_uids_canonical TEXT NOT NULL,
                effect_kind_uid TEXT NOT NULL,
                source_event_uid TEXT,
                resolver_kind_uid TEXT NOT NULL,
                resolver_version TEXT NOT NULL,
                semantic_fingerprint TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                PRIMARY KEY(campaign_uid,event_uid),
                UNIQUE(campaign_uid,transaction_uid,event_intent_uid),
                UNIQUE(campaign_uid,committed_order)
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_canonical_gameplay_events_tx ON $EVENT_TABLE(campaign_uid,transaction_uid,committed_order)")
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS rpgos_event_store_no_update
               BEFORE UPDATE ON $EVENT_TABLE BEGIN
                 SELECT RAISE(ABORT,'RPGOS-EVENT-STORE:APPEND_ONLY');
               END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS rpgos_event_store_no_delete
               BEFORE DELETE ON $EVENT_TABLE BEGIN
                 SELECT RAISE(ABORT,'RPGOS-EVENT-STORE:APPEND_ONLY');
               END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER IF NOT EXISTS rpgos_event_store_turn_insert
               BEFORE INSERT ON $EVENT_TABLE
               WHEN NOT EXISTS(
                 SELECT 1 FROM ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}
                 WHERE campaign_uid=NEW.campaign_uid AND capability_kind='TURN'
               ) OR NOT EXISTS(
                 SELECT 1 FROM $WRITER_CONTEXT_TABLE
                 WHERE campaign_uid=NEW.campaign_uid AND writer_contract_version >= $PHASE30_WRITER_CONTRACT_VERSION
               )
               BEGIN
                 SELECT RAISE(ABORT,'RPGOS-EVENT-STORE:CANONICAL_TURN_REQUIRED');
               END""".trimIndent()
        )
        val activation = ContentValues().apply {
            put("campaign_uid", campaignUid)
            put("event_schema_version", PHASE30_EVENT_SCHEMA_VERSION)
            put("min_writer_contract_version", PHASE30_WRITER_CONTRACT_VERSION)
            put("legacy_event_history_status", "UNKNOWN_NOT_RECORDED")
        }
        db.insertWithOnConflict(ACTIVATION_TABLE, null, activation, SQLiteDatabase.CONFLICT_IGNORE)
        installOldWriterGuards(db)
    }

    fun enterWriter(db: SQLiteDatabase, campaignUid: String) {
        if (!isActivated(db, campaignUid)) return
        val values = ContentValues().apply {
            put("campaign_uid", campaignUid)
            put("writer_contract_version", PHASE30_WRITER_CONTRACT_VERSION)
        }
        val result = db.insertWithOnConflict(WRITER_CONTEXT_TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        check(result != -1L) { "RPGOS-PHASE30:WRITER_CONTRACT_ENTER_FAILED" }
    }

    fun leaveWriter(db: SQLiteDatabase, campaignUid: String) {
        if (tableExists(db, WRITER_CONTEXT_TABLE)) db.delete(WRITER_CONTEXT_TABLE, "campaign_uid=?", arrayOf(campaignUid))
    }

    fun isActivated(db: SQLiteDatabase, campaignUid: String): Boolean {
        if (!tableExists(db, ACTIVATION_TABLE)) return false
        return db.rawQuery("SELECT 1 FROM $ACTIVATION_TABLE WHERE campaign_uid=? LIMIT 1", arrayOf(campaignUid)).use { it.moveToFirst() }
    }

    private fun installOldWriterGuards(db: SQLiteDatabase) {
        GameplayMutationDatabaseGuards.authoritativeTablesForCompatibility().filter { tableExists(db, it) }.forEach { table ->
            val campaignColumn = GameplayMutationDatabaseGuards.campaignColumnForCompatibility(db, table)
            listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD").forEach { (op, row) ->
                val trigger = "rpgos_phase30_${table}_${op.lowercase()}"
                db.execSQL(
                    """CREATE TRIGGER IF NOT EXISTS $trigger BEFORE $op ON $table
                       WHEN EXISTS(
                         SELECT 1 FROM $ACTIVATION_TABLE WHERE campaign_uid=$row.$campaignColumn
                       ) AND NOT EXISTS(
                         SELECT 1 FROM $WRITER_CONTEXT_TABLE
                         WHERE campaign_uid=$row.$campaignColumn AND writer_contract_version >= $PHASE30_WRITER_CONTRACT_VERSION
                       )
                       BEGIN
                         SELECT RAISE(ABORT,'RPGOS-PHASE30:INCOMPATIBLE_WRITER');
                       END""".trimIndent()
                )
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }
}

data class CanonicalGameplayEventRecord(
    val campaignUid: String,
    val eventUid: String,
    val transactionUid: String,
    val turnUid: String,
    val commandUid: String,
    val eventIntentUid: String,
    val eventKindUid: String,
    val committedOrder: Long,
    val semanticFingerprint: String,
    val schemaVersion: Int
)

internal class CampaignEventStore(private val db: SQLiteDatabase, private val campaignUid: String) {
    init { require(campaignUid.isNotBlank()) }

    fun validateRequiredEventIntents(changeSet: PlayerChangeSet) {
        require(changeSet.campaignUid == campaignUid) { "RPGOS-EVENT-STORE:CROSS_CAMPAIGN_CHANGESET" }
        val changeUids = changeSet.changes.map { it.changeUid }.toSet()
        changeSet.eventIntents.forEach { intent ->
            if (intent.causalChangeUids.isEmpty()) throw UnsupportedCanonicalIntentException("EVENT_INTENT_WITHOUT_CHANGE_PROVENANCE")
            if (intent.causalChangeUids.any { it !in changeUids }) throw UnsupportedCanonicalIntentException("EVENT_INTENT_UNKNOWN_CHANGE")
            val payload = intent.payload as? DomainEffectEventIntentPayload
                ?: throw UnsupportedCanonicalIntentException("EVENT_INTENT_PAYLOAD")
            if (payload.subject !in intent.targetRefs) throw UnsupportedCanonicalIntentException("EVENT_INTENT_SUBJECT_NOT_TARGET")
            if (intent.eventKindUid.isBlank() || payload.effectKindUid.isBlank()) throw UnsupportedCanonicalIntentException("EVENT_INTENT_KIND")
        }
    }

    fun appendRequired(identity: TurnTransactionIdentity, changeSet: PlayerChangeSet) {
        check(db.inTransaction()) { "RPGOS-EVENT-STORE:OUTSIDE_TURN_TRANSACTION" }
        require(identity.campaignUid == campaignUid && changeSet.campaignUid == campaignUid) { "RPGOS-EVENT-STORE:CROSS_CAMPAIGN" }
        validateRequiredEventIntents(changeSet)
        changeSet.eventIntents.sortedBy { it.eventIntentUid }.forEach { intent ->
            val planned = planned(identity, changeSet, intent)
            val existing = find(identity.transactionUid, intent.eventIntentUid)
            if (existing != null) {
                if (existing.eventUid != planned.eventUid || existing.semanticFingerprint != planned.semanticFingerprint) {
                    throw EventStoreIdentityConflictException()
                }
                return@forEach
            }
            val payload = intent.payload as DomainEffectEventIntentPayload
            val order = nextCommittedOrder()
            val values = ContentValues().apply {
                put("campaign_uid", campaignUid); put("event_uid", planned.eventUid)
                put("transaction_uid", identity.transactionUid); put("turn_uid", identity.turnUid); put("command_uid", identity.commandUid)
                put("event_intent_uid", intent.eventIntentUid); put("event_kind_uid", intent.eventKindUid); put("committed_order", order)
                put("source_actor_kind_uid", changeSet.actor.actorKindUid); put("source_actor_uid", changeSet.actor.actorUid)
                if (intent.actorRef == null) { putNull("actor_ref_kind_uid"); putNull("actor_ref_uid") }
                else { put("actor_ref_kind_uid", intent.actorRef.kindUid); put("actor_ref_uid", intent.actorRef.uid) }
                put("subject_ref_kind_uid", payload.subject.kindUid); put("subject_ref_uid", payload.subject.uid)
                put("target_refs_canonical", encodeRefs(intent.targetRefs)); put("causal_change_uids_canonical", encodeStrings(intent.causalChangeUids))
                put("effect_kind_uid", payload.effectKindUid)
                if (changeSet.provenance.sourceEventUid == null) putNull("source_event_uid") else put("source_event_uid", changeSet.provenance.sourceEventUid)
                put("resolver_kind_uid", changeSet.provenance.resolverKindUid); put("resolver_version", changeSet.provenance.resolverVersion)
                put("semantic_fingerprint", planned.semanticFingerprint); put("schema_version", PHASE30_EVENT_SCHEMA_VERSION)
            }
            if (db.insert(CampaignIntelligencePhase30Schema.EVENT_TABLE, null, values) == -1L) throw EventStoreIntegrityException("APPEND_FAILED")
        }
    }

    fun assertCommittedSetMatches(identity: TurnTransactionIdentity, changeSet: PlayerChangeSet) {
        validateRequiredEventIntents(changeSet)
        val rows = eventsForTransaction(identity.transactionUid)
        val expected = changeSet.eventIntents.sortedBy { it.eventIntentUid }.map { planned(identity, changeSet, it) }
        if (rows.size != expected.size) throw EventStoreIntegrityException("COMMITTED_SET_MISSING_OR_EXTRA")
        expected.forEachIndexed { index, event ->
            val row = rows[index]
            if (row.eventUid != event.eventUid || row.semanticFingerprint != event.semanticFingerprint) {
                throw EventStoreIdentityConflictException()
            }
        }
    }

    fun eventsForTransaction(transactionUid: String): List<CanonicalGameplayEventRecord> {
        if (!CampaignIntelligencePhase30Schema.isActivated(db, campaignUid)) return emptyList()
        return db.rawQuery(
            "SELECT event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,committed_order,semantic_fingerprint,schema_version FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} WHERE campaign_uid=? AND transaction_uid=? ORDER BY event_intent_uid",
            arrayOf(campaignUid, transactionUid)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(CanonicalGameplayEventRecord(campaignUid,c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getString(7),c.getInt(8)))
            }
        }
    }

    private data class Planned(val eventUid: String, val semanticFingerprint: String)
    private fun planned(identity: TurnTransactionIdentity, changeSet: PlayerChangeSet, intent: PlayerEventIntent): Planned {
        val payload = intent.payload as DomainEffectEventIntentPayload
        val semantic = listOf(
            "v=$PHASE30_EVENT_SCHEMA_VERSION", "campaign=$campaignUid", "tx=${identity.transactionUid}", "turn=${identity.turnUid}", "command=${identity.commandUid}",
            "intent=${intent.eventIntentUid}", "kind=${intent.eventKindUid}", "sourceActor=${changeSet.actor.actorKindUid}:${changeSet.actor.actorUid}",
            "actor=${intent.actorRef?.let { "${it.kindUid}:${it.uid}" } ?: "UNKNOWN"}", "subject=${payload.subject.kindUid}:${payload.subject.uid}",
            "targets=${encodeRefs(intent.targetRefs)}", "changes=${encodeStrings(intent.causalChangeUids)}", "effect=${payload.effectKindUid}",
            "sourceEvent=${changeSet.provenance.sourceEventUid ?: "UNKNOWN"}", "resolver=${changeSet.provenance.resolverKindUid}:${changeSet.provenance.resolverVersion}"
        ).joinToString("|")
        val eventUid = "RPGOS-EVENT:" + sha256("$campaignUid|${identity.transactionUid}|${identity.commandUid}|${intent.eventIntentUid}")
        return Planned(eventUid, sha256(semantic))
    }

    private fun find(transactionUid: String, eventIntentUid: String): CanonicalGameplayEventRecord? =
        db.rawQuery(
            "SELECT event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,committed_order,semantic_fingerprint,schema_version FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} WHERE campaign_uid=? AND transaction_uid=? AND event_intent_uid=? LIMIT 1",
            arrayOf(campaignUid, transactionUid, eventIntentUid)
        ).use { c -> if (!c.moveToFirst()) null else CanonicalGameplayEventRecord(campaignUid,c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getString(7),c.getInt(8)) }

    private fun nextCommittedOrder(): Long = db.rawQuery(
        "SELECT COALESCE(MAX(committed_order),0)+1 FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} WHERE campaign_uid=?",
        arrayOf(campaignUid)
    ).use { c -> c.moveToFirst(); c.getLong(0) }

    private fun encodeRefs(refs: List<DomainRef>): String = refs.sortedWith(compareBy<DomainRef> { it.kindUid }.thenBy { it.uid }).joinToString(";") { encode(it.kindUid) + encode(it.uid) }
    private fun encodeStrings(values: List<String>): String = values.sorted().joinToString(";") { encode(it) }
    private fun encode(value: String): String = "${value.length}:$value"
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
