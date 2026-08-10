package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE13_BALANCE_GUARD_MIGRATION_ID = "RPGOS-13.1-FINANCIAL-BALANCE-GUARDS"

/**
 * DB-authoritative balance guards. A valid ledger INSERT itself is the atomic mutation boundary:
 * reference validation, insufficient-funds/overflow validation, immutable ledger append and
 * rebuildable projection update succeed together or the statement aborts.
 */
fun MigrationManager.ensureV13BalanceGuards(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV13(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        listOf("trg_fin_transaction_balance_guard", "trg_fin_transaction_apply_balance").forEach {
            saveDb.execSQL("DROP TRIGGER IF EXISTS $it")
        }
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_transaction_balance_guard
            BEFORE INSERT ON financial_ledger_transactions
            WHEN (NEW.from_account_uid IS NOT NULL AND NOT EXISTS(
                    SELECT 1 FROM financial_account_balances b
                    WHERE b.campaign_id=NEW.campaign_id AND b.account_uid=NEW.from_account_uid
                      AND b.balance_minor>=NEW.amount_minor AND b.balance_version<9223372036854775807))
              OR (NEW.to_account_uid IS NOT NULL AND NOT EXISTS(
                    SELECT 1 FROM financial_account_balances b
                    WHERE b.campaign_id=NEW.campaign_id AND b.account_uid=NEW.to_account_uid
                      AND b.balance_minor<=9223372036854775807-NEW.amount_minor
                      AND b.balance_version<9223372036854775807))
            BEGIN SELECT RAISE(ABORT,'financial insufficient funds, balance overflow, or missing projection'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_transaction_apply_balance
            AFTER INSERT ON financial_ledger_transactions
            BEGIN
                UPDATE financial_account_balances
                   SET balance_minor=balance_minor-NEW.amount_minor,
                       balance_version=balance_version+1,
                       last_effective_order=NEW.effective_order
                 WHERE NEW.from_account_uid IS NOT NULL
                   AND campaign_id=NEW.campaign_id AND account_uid=NEW.from_account_uid;
                UPDATE financial_account_balances
                   SET balance_minor=balance_minor+NEW.amount_minor,
                       balance_version=balance_version+1,
                       last_effective_order=NEW.effective_order
                 WHERE NEW.to_account_uid IS NOT NULL
                   AND campaign_id=NEW.campaign_id AND account_uid=NEW.to_account_uid;
            END
        """.trimIndent())
        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE13_BALANCE_GUARD_MIGRATION_ID',strftime('%s','now'),'Moves insufficient-funds, overflow and balance projection application to the authoritative SQLite ledger INSERT boundary')")
        saveDb.setTransactionSuccessful()
    } finally { saveDb.endTransaction() }
}
