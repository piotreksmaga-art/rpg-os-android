package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

const val PHASE30_EVENT_SCHEMA_VERSION = 2
const val PHASE30_WRITER_CONTRACT_VERSION = 32

class EventStoreIntegrityException(code: String) : IllegalStateException("RPGOS-EVENT-STORE:$code")
class EventStoreIdentityConflictException : IllegalStateException("RPGOS-EVENT-STORE:IDENTITY_CONFLICT")

/**
 * Prospective Phase-30 activation. Pre-activation history is intentionally not reconstructed.
 * Event Store is authoritative only for immutable committed event evidence, never current domain state.
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
        migrateEventTableIfNeeded(db)
        installEventTriggers(db)

        val existing = db.rawQuery(
            "SELECT legacy_event_history_status FROM $ACTIVATION_TABLE WHERE campaign_uid=?",
            arrayOf(campaignUid)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        if (existing == null) {
            val activation = ContentValues().apply {
                put("campaign_uid", campaignUid)
                put("event_schema_version", PHASE30_EVENT_SCHEMA_VERSION)
                put("min_writer_contract_version", PHASE30_WRITER_CONTRACT_VERSION)
                put("legacy_event_history_status", "UNKNOWN_NOT_RECORDED")
            }
            check(db.insert(ACTIVATION_TABLE, null, activation) != -1L) { "RPGOS-PHASE30:ACTIVATION_FAILED" }
        } else {
            db.execSQL(
                "UPDATE $ACTIVATION_TABLE SET event_schema_version=?,min_writer_contract_version=? WHERE campaign_uid=?",
                arrayOf(PHASE30_EVENT_SCHEMA_VERSION, PHASE30_WRITER_CONTRACT_VERSION, campaignUid)
            )
        }
        installOldWriterGuards(db)
    }

    private fun migrateEventTableIfNeeded(db: SQLiteDatabase) {
        if (!tableExists(db, EVENT_TABLE)) {
            createCurrentEventTable(db, EVENT_TABLE)
            createEventIndexes(db)
            return
        }
        if (hasColumn(db, EVENT_TABLE, "event_ordinal") && !eventTableHasLegacyUniqueCommittedOrder(db)) {
            createEventIndexes(db)
            return
        }

        listOf("rpgos_event_store_no_update", "rpgos_event_store_no_delete", "rpgos_event_store_turn_insert").forEach {
            db.execSQL("DROP TRIGGER IF EXISTS $it")
        }
        db.execSQL("DROP TABLE IF EXISTS canonical_gameplay_events_v2_new")
        createCurrentEventTable(db, "canonical_gameplay_events_v2_new")

        val legacyHasCommittedOrder = hasColumn(db, EVENT_TABLE, "committed_order")
        val legacyOrderExpr = if (legacyHasCommittedOrder) "e.committed_order" else "NULL"
        db.execSQL(
            """INSERT INTO canonical_gameplay_events_v2_new(
                campaign_uid,event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,
                committed_order,event_ordinal,source_actor_kind_uid,source_actor_uid,actor_ref_kind_uid,actor_ref_uid,
                subject_ref_kind_uid,subject_ref_uid,target_refs_canonical,causal_change_uids_canonical,effect_kind_uid,
                source_event_uid,resolver_kind_uid,resolver_version,semantic_fingerprint,schema_version)
            SELECT e.campaign_uid,e.event_uid,e.transaction_uid,e.turn_uid,e.command_uid,e.event_intent_uid,e.event_kind_uid,
                CASE WHEN r.commit_order IS NOT NULL THEN r.commit_order ELSE NULL END,
                CASE WHEN r.commit_order IS NOT NULL THEN (
                    SELECT COUNT(*) FROM $EVENT_TABLE e2
                    WHERE e2.campaign_uid=e.campaign_uid AND e2.transaction_uid=e.transaction_uid
                      AND e2.event_intent_uid < e.event_intent_uid
                ) ELSE NULL END,
                e.source_actor_kind_uid,e.source_actor_uid,e.actor_ref_kind_uid,e.actor_ref_uid,
                e.subject_ref_kind_uid,e.subject_ref_uid,e.target_refs_canonical,e.causal_change_uids_canonical,e.effect_kind_uid,
                e.source_event_uid,e.resolver_kind_uid,e.resolver_version,e.semantic_fingerprint,e.schema_version
            FROM $EVENT_TABLE e
            LEFT JOIN turn_transaction_receipts r
              ON r.campaign_uid=e.campaign_uid AND r.transaction_uid=e.transaction_uid AND r.commit_state='COMMITTED'""".trimIndent()
        )
        // legacyOrderExpr is deliberately not used as a fallback: an Event-local sequence is not proof of Phase29 order.
        @Suppress("UNUSED_VARIABLE") val ignoredLegacyOrder = legacyOrderExpr
        db.execSQL("DROP TABLE $EVENT_TABLE")
        db.execSQL("ALTER TABLE canonical_gameplay_events_v2_new RENAME TO $EVENT_TABLE")
        createEventIndexes(db)
    }

    private fun createCurrentEventTable(db: SQLiteDatabase, table: String) {
        db.execSQL(
            """CREATE TABLE $table(
                campaign_uid TEXT NOT NULL,
                event_uid TEXT NOT NULL,
                transaction_uid TEXT NOT NULL,
                turn_uid TEXT NOT NULL,
                command_uid TEXT NOT NULL,
                event_intent_uid TEXT NOT NULL,
                event_kind_uid TEXT NOT NULL,
                committed_order INTEGER NULL CHECK(committed_order IS NULL OR committed_order > 0),
                event_ordinal INTEGER NULL CHECK(event_ordinal IS NULL OR event_ordinal >= 0),
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
                UNIQUE(campaign_uid,transaction_uid,event_ordinal),
                CHECK((committed_order IS NULL AND event_ordinal IS NULL) OR (committed_order IS NOT NULL AND event_ordinal IS NOT NULL))
            )""".trimIndent()
        )
    }

    private fun createEventIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_canonical_gameplay_events_tx ON $EVENT_TABLE(campaign_uid,transaction_uid,event_ordinal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_canonical_gameplay_events_order ON $EVENT_TABLE(campaign_uid,committed_order,event_ordinal) WHERE committed_order IS NOT NULL")
    }

    private fun installEventTriggers(db: SQLiteDatabase) {
        listOf("rpgos_event_store_no_update", "rpgos_event_store_no_delete", "rpgos_event_store_turn_insert").forEach {
            db.execSQL("DROP TRIGGER IF EXISTS $it")
        }
        db.execSQL(
            """CREATE TRIGGER rpgos_event_store_no_update
               BEFORE UPDATE ON $EVENT_TABLE BEGIN
                 SELECT RAISE(ABORT,'RPGOS-EVENT-STORE:APPEND_ONLY');
               END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER rpgos_event_store_no_delete
               BEFORE DELETE ON $EVENT_TABLE BEGIN
                 SELECT RAISE(ABORT,'RPGOS-EVENT-STORE:APPEND_ONLY');
               END""".trimIndent()
        )
        db.execSQL(
            """CREATE TRIGGER rpgos_event_store_turn_insert
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
        return db.rawQuery(
            "SELECT 1 FROM $ACTIVATION_TABLE WHERE campaign_uid=? AND event_schema_version>=? AND min_writer_contract_version>=? LIMIT 1",
            arrayOf(campaignUid, PHASE30_EVENT_SCHEMA_VERSION.toString(), PHASE30_WRITER_CONTRACT_VERSION.toString())
        ).use { it.moveToFirst() }
    }

    private fun installOldWriterGuards(db: SQLiteDatabase) {
        GameplayMutationDatabaseGuards.authoritativeTablesForCompatibility().filter { tableExists(db, it) }.forEach { table ->
            val campaignColumn = GameplayMutationDatabaseGuards.campaignColumnForCompatibility(db, table)
            listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD").forEach { (op, row) ->
                val activationCampaign = campaignColumn?.let { "$row.$it" }
                val activationPredicate = activationCampaign?.let { "campaign_uid=$it" } ?: "1=1"
                val writerPredicate = activationCampaign?.let { "campaign_uid=$it AND " }.orEmpty()
                val trigger = "rpgos_phase30_${table}_${op.lowercase()}"
                db.execSQL("DROP TRIGGER IF EXISTS $trigger")
                db.execSQL(
                    """CREATE TRIGGER $trigger BEFORE $op ON $table
                       WHEN EXISTS(
                         SELECT 1 FROM $ACTIVATION_TABLE WHERE $activationPredicate
                       ) AND NOT EXISTS(
                         SELECT 1 FROM $WRITER_CONTEXT_TABLE
                         WHERE ${writerPredicate}writer_contract_version >= $PHASE30_WRITER_CONTRACT_VERSION
                       )
                       BEGIN
                         SELECT RAISE(ABORT,'RPGOS-PHASE30:INCOMPATIBLE_WRITER');
                       END""".trimIndent()
                )
            }
        }
    }

    private fun eventTableHasLegacyUniqueCommittedOrder(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(EVENT_TABLE)
    ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) false
        else c.getString(0).replace(" ", "").lowercase().contains("unique(campaign_uid,committed_order)")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val name = c.getColumnIndex("name")
            while (c.moveToNext()) if (name >= 0 && c.getString(name) == column) return@use true
            false
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
    val committedOrder: Long?,
    val eventOrdinal: Int?,
    val semanticFingerprint: String,
    val schemaVersion: Int
)

data class RequiredEventManifestSummary(
    val requiredEventCount: Int,
    val orderedManifestFingerprint: String
)

internal data class ResolvedRequiredEventManifest(
    val intents: List<PlayerEventIntent>,
    val summary: RequiredEventManifestSummary
)

internal class CampaignEventStore(private val db: SQLiteDatabase, private val campaignUid: String) {
    init { require(campaignUid.isNotBlank()) }

    fun resolveRequiredManifest(identity: TurnTransactionIdentity, changeSet: PlayerChangeSet): ResolvedRequiredEventManifest {
        require(identity.campaignUid == campaignUid && changeSet.campaignUid == campaignUid) { "RPGOS-EVENT-STORE:CROSS_CAMPAIGN" }
        val resolvedIntents = resolveRequiredEventIntents(changeSet)
        val planned = resolvedIntents.sortedBy { it.eventIntentUid }.mapIndexed { ordinal, intent -> planned(identity, changeSet, intent, ordinal) }
        val summary = RequiredEventManifestSummary(
            requiredEventCount = planned.size,
            orderedManifestFingerprint = sha256(
                planned.joinToString("\u001f") { "${it.eventOrdinal}:${it.eventUid}:${it.semanticFingerprint}" }
            )
        )
        return ResolvedRequiredEventManifest(resolvedIntents.sortedBy { it.eventIntentUid }, summary)
    }

    fun validateRequiredEventIntents(changeSet: PlayerChangeSet) {
        resolveRequiredEventIntents(changeSet)
    }

    private fun resolveRequiredEventIntents(changeSet: PlayerChangeSet): List<PlayerEventIntent> {
        require(changeSet.campaignUid == campaignUid) { "RPGOS-EVENT-STORE:CROSS_CAMPAIGN_CHANGESET" }
        val changeByUid = changeSet.changes.associateBy { it.changeUid }
        if (changeByUid.size != changeSet.changes.size) throw EventStoreIntegrityException("DUPLICATE_CHANGE_UID")

        changeSet.changes.forEach(::requireClassifiedEventBearingChange)
        val explicit = changeSet.eventIntents.toList()
        val intentIds = explicit.map { it.eventIntentUid }
        if (intentIds.distinct().size != intentIds.size) throw EventStoreIntegrityException("DUPLICATE_EVENT_INTENT_UID")
        explicit.forEach { intent -> validateExplicitIntent(intent, changeByUid) }
        val semanticKeys = explicit.map(::explicitSemanticKey)
        if (semanticKeys.distinct().size != semanticKeys.size) throw EventStoreIntegrityException("DUPLICATE_SEMANTIC_EVENT")

        val out = ArrayList<PlayerEventIntent>(explicit)
        changeSet.changes.sortedBy { it.changeUid }.forEach { change ->
            if (explicit.none { change.changeUid in it.causalChangeUids }) {
                val generated = requiredIntentFor(changeSet, change)
                if (out.any { it.eventIntentUid == generated.eventIntentUid }) {
                    throw EventStoreIntegrityException("REQUIRED_EVENT_ID_CONFLICT")
                }
                out += generated
            }
        }
        return out.sortedBy { it.eventIntentUid }
    }

    private fun validateExplicitIntent(intent: PlayerEventIntent, changes: Map<String, PlayerDomainChange>) {
        if (intent.causalChangeUids.isEmpty()) throw UnsupportedCanonicalIntentException("EVENT_INTENT_WITHOUT_CHANGE_PROVENANCE")
        if (intent.causalChangeUids.any { it !in changes }) throw UnsupportedCanonicalIntentException("EVENT_INTENT_UNKNOWN_CHANGE")
        val payload = intent.payload as? DomainEffectEventIntentPayload
            ?: throw UnsupportedCanonicalIntentException("EVENT_INTENT_PAYLOAD")
        if (payload.subject !in intent.targetRefs) throw UnsupportedCanonicalIntentException("EVENT_INTENT_SUBJECT_NOT_TARGET")
        if (intent.eventKindUid != PlayerEventIntentKinds.DOMAIN_EFFECT || payload.effectKindUid.isBlank()) {
            throw UnsupportedCanonicalIntentException("EVENT_INTENT_KIND")
        }
    }

    private fun requireClassifiedEventBearingChange(change: PlayerDomainChange) {
        val expectedKind = when (change.payload) {
            is StatChange -> PlayerChangeKinds.STAT
            is ResourceChange -> PlayerChangeKinds.RESOURCE
            is SkillChange -> PlayerChangeKinds.SKILL
            is TechniqueChange -> PlayerChangeKinds.TECHNIQUE
            is InnateChange -> PlayerChangeKinds.INNATE
            is InventoryChange -> PlayerChangeKinds.INVENTORY
            is EquipmentChange -> PlayerChangeKinds.EQUIPMENT
            is FinancialChange -> PlayerChangeKinds.FINANCIAL
            is AssetChange -> PlayerChangeKinds.ASSET
            is OwnershipChange -> PlayerChangeKinds.OWNERSHIP
            is CampaignTruthChange -> PlayerChangeKinds.CAMPAIGN_TRUTH
            is ConditionChange -> PlayerChangeKinds.CONDITION
            is RuntimeChange -> PlayerChangeKinds.RUNTIME
            is DevelopmentProjectChange -> PlayerChangeKinds.DEVELOPMENT_PROJECT
            else -> throw EventStoreIntegrityException("UNCLASSIFIED_CHANGE_KIND")
        }
        if (change.changeKindUid != expectedKind) throw EventStoreIntegrityException("CHANGE_KIND_PAYLOAD_MISMATCH")
        // All currently modeled PlayerDomainChange families are explicitly EVENT_BEARING.
    }

    private fun requiredIntentFor(changeSet: PlayerChangeSet, change: PlayerDomainChange): PlayerEventIntent {
        val subject = requiredSubject(change)
        return PlayerEventIntent.create(
            eventIntentUid = "RPGOS-REQUIRED-EVENT:${change.changeUid}",
            eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
            actorRef = DomainRef(changeSet.actor.actorKindUid, changeSet.actor.actorUid),
            targetRefs = listOf(subject),
            causalChangeUids = listOf(change.changeUid),
            payload = DomainEffectEventIntentPayload(subject, change.changeKindUid)
        )
    }

    private fun requiredSubject(change: PlayerDomainChange): DomainRef = when (val payload = change.payload) {
        is StatChange -> payload.subject
        is ResourceChange -> payload.subject
        is SkillChange -> payload.subject
        is TechniqueChange -> payload.subject
        is InnateChange -> payload.subject
        is InventoryChange -> payload.subject
        is EquipmentChange -> payload.subject
        is FinancialChange -> DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, payload.fromAccountUid)
        is AssetChange -> DomainRef("ASSET", payload.asset.assetUid)
        is OwnershipChange -> DomainRef("OWNERSHIP_RECORD", payload.ownershipRecordUid)
        is CampaignTruthChange -> DomainRef("CAMPAIGN_TRUTH", payload.truthUid)
        is ConditionChange -> payload.subject
        is RuntimeChange -> payload.subject
        is DevelopmentProjectChange -> DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid)
        else -> throw EventStoreIntegrityException("UNCLASSIFIED_CHANGE_KIND")
    }

    fun appendRequired(identity: TurnTransactionIdentity, changeSet: PlayerChangeSet, commitOrder: Long) {
        check(db.inTransaction()) { "RPGOS-EVENT-STORE:OUTSIDE_TURN_TRANSACTION" }
        require(commitOrder > 0L) { "RPGOS-EVENT-STORE:INVALID_COMMIT_ORDER" }
        val manifest = resolveRequiredManifest(identity, changeSet)
        manifest.intents.forEachIndexed { ordinal, intent ->
            val planned = planned(identity, changeSet, intent, ordinal)
            val existing = find(identity.transactionUid, intent.eventIntentUid)
            if (existing != null) {
                if (existing.eventUid != planned.eventUid || existing.semanticFingerprint != planned.semanticFingerprint ||
                    existing.committedOrder != commitOrder || existing.eventOrdinal != ordinal) {
                    throw EventStoreIdentityConflictException()
                }
                return@forEachIndexed
            }
            val payload = intent.payload as DomainEffectEventIntentPayload
            val values = ContentValues().apply {
                put("campaign_uid", campaignUid); put("event_uid", planned.eventUid)
                put("transaction_uid", identity.transactionUid); put("turn_uid", identity.turnUid); put("command_uid", identity.commandUid)
                put("event_intent_uid", intent.eventIntentUid); put("event_kind_uid", intent.eventKindUid)
                put("committed_order", commitOrder); put("event_ordinal", ordinal)
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

    @Deprecated("Phase32 post-audit: Event append must receive the single Phase29 commitOrder reserved by TurnTransaction")
    fun appendRequired(identity: TurnTransactionIdentity, changeSet: PlayerChangeSet): Nothing =
        throw EventStoreIntegrityException("COMMIT_ORDER_REQUIRED")

    fun assertCommittedSetMatches(identity: TurnTransactionIdentity, changeSet: PlayerChangeSet, receipt: TurnCommitReceipt? = null) {
        val expectedIntents = if (receipt != null && receipt.receiptVersion < 3) {
            changeSet.eventIntents.sortedBy { it.eventIntentUid }
        } else {
            resolveRequiredManifest(identity, changeSet).intents
        }
        val rows = eventsForTransaction(identity.transactionUid)
        val expected = expectedIntents.mapIndexed { ordinal, intent -> planned(identity, changeSet, intent, ordinal) }
        if (rows.size != expected.size) throw EventStoreIntegrityException("COMMITTED_SET_MISSING_OR_EXTRA")
        expected.forEachIndexed { index, event ->
            val row = rows[index]
            if (row.eventUid != event.eventUid || row.semanticFingerprint != event.semanticFingerprint) {
                throw EventStoreIdentityConflictException()
            }
            if (receipt != null && receipt.receiptVersion >= 3) {
                if (row.committedOrder != receipt.commitOrder || row.eventOrdinal != index) throw EventStoreIntegrityException("ORDER_BINDING_MISMATCH")
            }
        }
        if (receipt != null && receipt.receiptVersion >= 3) {
            val summary = resolveRequiredManifest(identity, changeSet).summary
            if (receipt.requiredEventCount != summary.requiredEventCount ||
                receipt.requiredEventManifestFingerprint != summary.orderedManifestFingerprint) {
                throw EventStoreIntegrityException("RECEIPT_MANIFEST_BINDING_MISMATCH")
            }
        }
    }

    fun eventsForTransaction(transactionUid: String): List<CanonicalGameplayEventRecord> {
        if (!CampaignIntelligencePhase30Schema.isActivated(db, campaignUid)) return emptyList()
        return db.rawQuery(
            """SELECT event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,
                committed_order,event_ordinal,semantic_fingerprint,schema_version
                FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE}
                WHERE campaign_uid=? AND transaction_uid=? ORDER BY event_ordinal,event_intent_uid""".trimIndent(),
            arrayOf(campaignUid, transactionUid)
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    CanonicalGameplayEventRecord(
                        campaignUid,c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),
                        if(c.isNull(6)) null else c.getLong(6), if(c.isNull(7)) null else c.getInt(7),c.getString(8),c.getInt(9)
                    )
                )
            }
        }
    }

    internal fun eventUid(identity: TurnTransactionIdentity, changeSet: PlayerChangeSet, intent: PlayerEventIntent): String =
        planned(identity, changeSet, intent, 0).eventUid

    private data class Planned(val eventUid: String, val semanticFingerprint: String, val eventOrdinal: Int)

    private fun planned(identity: TurnTransactionIdentity, changeSet: PlayerChangeSet, intent: PlayerEventIntent, ordinal: Int): Planned {
        val payload = intent.payload as DomainEffectEventIntentPayload
        val semantic = listOf(
            "v=$PHASE30_EVENT_SCHEMA_VERSION", "campaign=$campaignUid", "tx=${identity.transactionUid}", "turn=${identity.turnUid}", "command=${identity.commandUid}",
            "intent=${intent.eventIntentUid}", "ordinal=$ordinal", "kind=${intent.eventKindUid}", "sourceActor=${changeSet.actor.actorKindUid}:${changeSet.actor.actorUid}",
            "actor=${intent.actorRef?.let { "${it.kindUid}:${it.uid}" } ?: "UNKNOWN"}", "subject=${payload.subject.kindUid}:${payload.subject.uid}",
            "targets=${encodeRefs(intent.targetRefs)}", "changes=${encodeStrings(intent.causalChangeUids)}", "effect=${payload.effectKindUid}",
            "sourceEvent=${changeSet.provenance.sourceEventUid ?: "UNKNOWN"}", "resolver=${changeSet.provenance.resolverKindUid}:${changeSet.provenance.resolverVersion}"
        ).joinToString("|")
        val eventUid = "RPGOS-EVENT:" + sha256("$campaignUid|${identity.transactionUid}|${identity.commandUid}|${intent.eventIntentUid}")
        return Planned(eventUid, sha256(semantic), ordinal)
    }

    private fun explicitSemanticKey(intent: PlayerEventIntent): String {
        val payload = intent.payload as? DomainEffectEventIntentPayload ?: return "UNSUPPORTED:${intent.eventIntentUid}"
        return listOf(
            intent.eventKindUid,
            intent.actorRef?.let { "${it.kindUid}:${it.uid}" } ?: "UNKNOWN",
            "${payload.subject.kindUid}:${payload.subject.uid}",
            encodeRefs(intent.targetRefs),
            encodeStrings(intent.causalChangeUids),
            payload.effectKindUid,
            intent.proposedEffectiveOrder?.toString() ?: "UNKNOWN"
        ).joinToString("|")
    }

    private fun find(transactionUid: String, eventIntentUid: String): CanonicalGameplayEventRecord? =
        db.rawQuery(
            """SELECT event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,
                committed_order,event_ordinal,semantic_fingerprint,schema_version
                FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE}
                WHERE campaign_uid=? AND transaction_uid=? AND event_intent_uid=? LIMIT 1""".trimIndent(),
            arrayOf(campaignUid, transactionUid, eventIntentUid)
        ).use { c -> if (!c.moveToFirst()) null else CanonicalGameplayEventRecord(
            campaignUid,c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),
            if(c.isNull(6))null else c.getLong(6),if(c.isNull(7))null else c.getInt(7),c.getString(8),c.getInt(9)
        ) }

    private fun encodeRefs(refs: List<DomainRef>): String = refs.sortedWith(compareBy<DomainRef> { it.kindUid }.thenBy { it.uid }).joinToString(";") { encode(it.kindUid) + encode(it.uid) }
    private fun encodeStrings(values: List<String>): String = values.sorted().joinToString(";") { encode(it) }
    private fun encode(value: String): String = "${value.length}:$value"
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
