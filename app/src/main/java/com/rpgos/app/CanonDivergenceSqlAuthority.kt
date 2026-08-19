package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.util.function.UnaryOperator

/**
 * SQL-layer half of the RECORDED divergence capability.
 *
 * Durable receipt/event/replay evidence is necessary but deliberately not sufficient: the insert
 * trigger also requires a connection-local capability that exists only while TurnTransaction owns
 * the private canonical seal. Raw SQL can forge rows in writable context tables, but it cannot make
 * this ThreadLocal capability active.
 *
 * Android 28-29 do not expose a scalar SQL callback that can return a value/error to SQLite. On
 * those API levels RECORDED inserts therefore fail closed at the trigger; import provenance remains
 * available. API 30+ gets the full legal canonical path.
 */
internal object CanonDivergenceSqlAuthority {
    const val FUNCTION = "rpgos_canon_recorded_authorized"
    private const val SEP = "\u001f"

    private data class Active(val db: SQLiteDatabase, val keys: Set<String>)
    private val active = ThreadLocal<Active?>()

    fun install(db: SQLiteDatabase) {
        val authorityPredicate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            db.setCustomScalarFunction(FUNCTION, UnaryOperator { supplied ->
                val a = active.get()
                if (a != null && a.db === db && supplied in a.keys) "1" else "0"
            })
            "COALESCE($FUNCTION(${sqlAuthorizationKey()}),'0')<>'1'"
        } else {
            // Integrity beats compatibility: without an unforgeable scalar callback, RECORDED is disabled.
            "1=1"
        }

        db.execSQL("DROP TRIGGER IF EXISTS rpgos_canon_divergence_recorded_provenance_insert")
        db.execSQL(
            """CREATE TRIGGER rpgos_canon_divergence_recorded_provenance_insert
               BEFORE INSERT ON ${Phase35CanonDivergenceSchema.TABLE}
               WHEN NEW.provenance_status='RECORDED' AND (
                 $authorityPredicate OR
                 NEW.created_transaction_uid IS NULL OR NEW.created_turn_uid IS NULL OR NEW.created_event_uid IS NULL OR
                 NOT EXISTS(SELECT 1 FROM turn_transaction_receipts r
                   WHERE r.transaction_uid=NEW.created_transaction_uid AND r.campaign_uid=NEW.campaign_uid
                     AND r.turn_uid=NEW.created_turn_uid AND r.commit_state='COMMITTED') OR
                 NOT EXISTS(SELECT 1 FROM canonical_gameplay_events e
                   WHERE e.event_uid=NEW.created_event_uid AND e.campaign_uid=NEW.campaign_uid
                     AND e.transaction_uid=NEW.created_transaction_uid AND e.turn_uid=NEW.created_turn_uid) OR
                 NOT EXISTS(SELECT 1 FROM canonical_turn_replay_payloads p
                   WHERE p.transaction_uid=NEW.created_transaction_uid AND p.campaign_uid=NEW.campaign_uid
                     AND p.turn_uid=NEW.created_turn_uid)
               )
               BEGIN SELECT RAISE(ABORT,'RPGOS-CANON:CANONICAL_COMMIT_AUTHORITY_REQUIRED'); END""".trimIndent()
        )
    }

    fun <T> withAuthority(
        db: SQLiteDatabase,
        identity: TurnTransactionIdentity,
        authorizations: List<CanonicalDivergenceCommitAuthorization>,
        block: () -> T
    ): T {
        check(active.get() == null) { "RPGOS-CANON:NESTED_SQL_AUTHORITY" }
        val keys = authorizations.mapTo(linkedSetOf()) { authorizationKey(identity, it.spec.divergenceUid, it.eventUid) }
        active.set(Active(db, keys))
        return try { block() } finally { active.remove() }
    }

    private fun authorizationKey(identity: TurnTransactionIdentity, divergenceUid: String, eventUid: String): String =
        listOf(identity.campaignUid, divergenceUid, identity.transactionUid, identity.turnUid, eventUid).joinToString(SEP)

    private fun sqlAuthorizationKey(): String =
        "NEW.campaign_uid||char(31)||NEW.divergence_uid||char(31)||NEW.created_transaction_uid||char(31)||NEW.created_turn_uid||char(31)||NEW.created_event_uid"
}
