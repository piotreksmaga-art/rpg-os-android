package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE13_CONTRACT_GUARD_MIGRATION_ID = "RPGOS-13.2-FINANCIAL-CONTRACT-GUARDS"
const val FINANCIAL_ACCOUNT_TYPE_STANDARD = "RPGOS-FIN-ACCOUNT:STANDARD"

fun MigrationManager.ensureV13ContractGuards(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV13BalanceGuards(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS financial_account_type_definitions(
                account_type_uid TEXT PRIMARY KEY,
                type_status TEXT NOT NULL CHECK(type_status IN ('ACTIVE','RETIRED')),
                provenance TEXT NOT NULL CHECK(length(trim(provenance))>0))
        """.trimIndent())
        saveDb.execSQL("INSERT OR IGNORE INTO financial_account_type_definitions(account_type_uid,type_status,provenance) VALUES('$FINANCIAL_ACCOUNT_TYPE_STANDARD','ACTIVE','RPGOS-13 standard generic account')")

        listOf(
            "trg_fin_currency_definition_update_guard","trg_fin_currency_definition_delete_guard",
            "trg_fin_tx_type_update_guard","trg_fin_tx_type_delete_guard",
            "trg_fin_account_type_update_guard","trg_fin_account_type_delete_guard"
        ).forEach{saveDb.execSQL("DROP TRIGGER IF EXISTS $it")}

        saveDb.execSQL("DROP TRIGGER IF EXISTS trg_fin_account_holder_guard")
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_account_holder_guard
            BEFORE INSERT ON financial_accounts
            WHEN NOT EXISTS(
                SELECT 1 FROM ownership_party_registry p
                JOIN ownership_owner_kinds k ON k.owner_kind_uid=p.owner_kind_uid AND k.kind_status='ACTIVE'
                WHERE p.campaign_id=NEW.campaign_id AND p.owner_kind_uid=NEW.holder_kind_uid
                  AND p.owner_uid=NEW.holder_uid AND p.reference_status='ACTIVE')
              OR NOT EXISTS(SELECT 1 FROM currency_definitions c WHERE c.currency_uid=NEW.currency_uid AND c.definition_status='ACTIVE')
              OR NOT EXISTS(SELECT 1 FROM financial_account_type_definitions a WHERE a.account_type_uid=NEW.account_type_uid AND a.type_status='ACTIVE')
            BEGIN SELECT RAISE(ABORT,'financial account holder, currency, or account type is unresolved/inactive'); END
        """.trimIndent())

        // Refresh transaction authority with lifecycle + deterministic non-backdating checks.
        saveDb.execSQL("DROP TRIGGER IF EXISTS trg_fin_transaction_reference_guard")
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_transaction_reference_guard
            BEFORE INSERT ON financial_ledger_transactions
            WHEN NOT EXISTS(SELECT 1 FROM financial_transaction_type_definitions t WHERE t.transaction_type_uid=NEW.transaction_type_uid AND t.type_status='ACTIVE' AND t.flow_kind=NEW.flow_kind)
              OR NOT EXISTS(SELECT 1 FROM currency_definitions c WHERE c.currency_uid=NEW.currency_uid AND c.definition_status='ACTIVE')
              OR (NEW.from_account_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM financial_accounts a WHERE a.campaign_id=NEW.campaign_id AND a.account_uid=NEW.from_account_uid AND a.currency_uid=NEW.currency_uid AND a.closed_order IS NULL AND a.opened_order<=NEW.effective_order))
              OR (NEW.to_account_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM financial_accounts a WHERE a.campaign_id=NEW.campaign_id AND a.account_uid=NEW.to_account_uid AND a.currency_uid=NEW.currency_uid AND a.closed_order IS NULL AND a.opened_order<=NEW.effective_order))
              OR (NEW.from_account_uid IS NOT NULL AND EXISTS(SELECT 1 FROM financial_ledger_transactions x WHERE x.campaign_id=NEW.campaign_id AND (x.from_account_uid=NEW.from_account_uid OR x.to_account_uid=NEW.from_account_uid) AND x.effective_order>NEW.effective_order))
              OR (NEW.to_account_uid IS NOT NULL AND EXISTS(SELECT 1 FROM financial_ledger_transactions x WHERE x.campaign_id=NEW.campaign_id AND (x.from_account_uid=NEW.to_account_uid OR x.to_account_uid=NEW.to_account_uid) AND x.effective_order>NEW.effective_order))
            BEGIN SELECT RAISE(ABORT,'financial transaction reference/type/currency/lifecycle/order is invalid'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_currency_definition_update_guard
            BEFORE UPDATE ON currency_definitions
            WHEN NEW.currency_uid<>OLD.currency_uid OR NEW.currency_key<>OLD.currency_key
              OR NEW.display_name<>OLD.display_name OR NEW.minor_unit_scale<>OLD.minor_unit_scale
              OR NEW.provenance<>OLD.provenance
              OR NOT(OLD.definition_status='ACTIVE' AND NEW.definition_status='RETIRED')
            BEGIN SELECT RAISE(ABORT,'currency identity/precision is immutable; only ACTIVE to RETIRED is legal'); END
        """.trimIndent())
        saveDb.execSQL("CREATE TRIGGER trg_fin_currency_definition_delete_guard BEFORE DELETE ON currency_definitions BEGIN SELECT RAISE(ABORT,'currency definitions are append-preserved'); END")
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_tx_type_update_guard
            BEFORE UPDATE ON financial_transaction_type_definitions
            WHEN NEW.transaction_type_uid<>OLD.transaction_type_uid OR NEW.flow_kind<>OLD.flow_kind
              OR NEW.provenance<>OLD.provenance OR NOT(OLD.type_status='ACTIVE' AND NEW.type_status='RETIRED')
            BEGIN SELECT RAISE(ABORT,'financial transaction type meaning is immutable'); END
        """.trimIndent())
        saveDb.execSQL("CREATE TRIGGER trg_fin_tx_type_delete_guard BEFORE DELETE ON financial_transaction_type_definitions BEGIN SELECT RAISE(ABORT,'financial transaction types are append-preserved'); END")
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_account_type_update_guard
            BEFORE UPDATE ON financial_account_type_definitions
            WHEN NEW.account_type_uid<>OLD.account_type_uid OR NEW.provenance<>OLD.provenance
              OR NOT(OLD.type_status='ACTIVE' AND NEW.type_status='RETIRED')
            BEGIN SELECT RAISE(ABORT,'financial account type meaning is immutable'); END
        """.trimIndent())
        saveDb.execSQL("CREATE TRIGGER trg_fin_account_type_delete_guard BEFORE DELETE ON financial_account_type_definitions BEGIN SELECT RAISE(ABORT,'financial account types are append-preserved'); END")

        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE13_CONTRACT_GUARD_MIGRATION_ID',strftime('%s','now'),'Registers account-type namespace; freezes currency precision/type meaning; validates endpoint lifecycle and rejects backdating behind committed account history')")
        saveDb.setTransactionSuccessful()
    } finally { saveDb.endTransaction() }
}
