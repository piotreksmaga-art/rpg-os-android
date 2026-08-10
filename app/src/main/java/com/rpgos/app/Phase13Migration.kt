package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE13_MIGRATION_ID = "RPGOS-13.0-FINANCIAL-LEDGER"

/** Additive Phase 13 financial ledger. No legacy balance/history synthesis is performed. */
fun MigrationManager.ensureV13(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV12(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS currency_definitions(
                currency_uid TEXT PRIMARY KEY,
                currency_key TEXT NOT NULL,
                display_name TEXT NOT NULL,
                minor_unit_scale INTEGER NOT NULL CHECK(typeof(minor_unit_scale)='integer' AND minor_unit_scale > 0),
                definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','RETIRED')),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
                UNIQUE(currency_key))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS financial_transaction_type_definitions(
                transaction_type_uid TEXT PRIMARY KEY,
                flow_kind TEXT NOT NULL CHECK(flow_kind IN ('INTERNAL','SOURCE','SINK','REVERSAL')),
                type_status TEXT NOT NULL CHECK(type_status IN ('ACTIVE','RETIRED')),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0))
        """.trimIndent())
        saveDb.execSQL("INSERT OR IGNORE INTO financial_transaction_type_definitions(transaction_type_uid,flow_kind,type_status,provenance) VALUES('RPGOS-FIN-TYPE:TRANSFER','INTERNAL','ACTIVE','RPGOS-13 core transfer')")
        saveDb.execSQL("INSERT OR IGNORE INTO financial_transaction_type_definitions(transaction_type_uid,flow_kind,type_status,provenance) VALUES('RPGOS-FIN-TYPE:MIGRATION_OPENING_BALANCE','SOURCE','ACTIVE','RPGOS-13 explicit migration opening balance')")
        saveDb.execSQL("INSERT OR IGNORE INTO financial_transaction_type_definitions(transaction_type_uid,flow_kind,type_status,provenance) VALUES('RPGOS-FIN-TYPE:EXTERNAL_CREDIT','SOURCE','ACTIVE','RPGOS-13 explicit external source')")
        saveDb.execSQL("INSERT OR IGNORE INTO financial_transaction_type_definitions(transaction_type_uid,flow_kind,type_status,provenance) VALUES('RPGOS-FIN-TYPE:EXTERNAL_DEBIT','SINK','ACTIVE','RPGOS-13 explicit external sink')")
        saveDb.execSQL("INSERT OR IGNORE INTO financial_transaction_type_definitions(transaction_type_uid,flow_kind,type_status,provenance) VALUES('RPGOS-FIN-TYPE:REVERSAL','REVERSAL','ACTIVE','RPGOS-13 correction/reversal')")

        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS financial_accounts(
                campaign_id TEXT NOT NULL,
                account_uid TEXT NOT NULL,
                holder_kind_uid TEXT NOT NULL,
                holder_uid TEXT NOT NULL,
                account_type_uid TEXT NOT NULL CHECK(length(trim(account_type_uid)) > 0),
                currency_uid TEXT NOT NULL,
                opened_order INTEGER NOT NULL CHECK(typeof(opened_order)='integer'),
                closed_order INTEGER CHECK(closed_order IS NULL OR typeof(closed_order)='integer'),
                account_version INTEGER NOT NULL CHECK(typeof(account_version)='integer' AND account_version >= 1),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
                PRIMARY KEY(campaign_id,account_uid),
                FOREIGN KEY(campaign_id,holder_kind_uid,holder_uid) REFERENCES ownership_party_registry(campaign_id,owner_kind_uid,owner_uid),
                FOREIGN KEY(currency_uid) REFERENCES currency_definitions(currency_uid),
                CHECK(closed_order IS NULL OR closed_order > opened_order))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS financial_ledger_transactions(
                campaign_id TEXT NOT NULL,
                financial_transaction_uid TEXT NOT NULL,
                from_account_uid TEXT,
                to_account_uid TEXT,
                currency_uid TEXT NOT NULL,
                amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor > 0),
                transaction_type_uid TEXT NOT NULL,
                flow_kind TEXT NOT NULL CHECK(flow_kind IN ('INTERNAL','SOURCE','SINK','REVERSAL')),
                reason TEXT NOT NULL CHECK(length(trim(reason)) > 0),
                effective_order INTEGER NOT NULL CHECK(typeof(effective_order)='integer'),
                source_event_uid TEXT,
                command_uid TEXT,
                reversal_of_uid TEXT,
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
                transaction_status TEXT NOT NULL DEFAULT 'COMMITTED' CHECK(transaction_status='COMMITTED'),
                PRIMARY KEY(campaign_id,financial_transaction_uid),
                FOREIGN KEY(campaign_id,from_account_uid) REFERENCES financial_accounts(campaign_id,account_uid),
                FOREIGN KEY(campaign_id,to_account_uid) REFERENCES financial_accounts(campaign_id,account_uid),
                FOREIGN KEY(currency_uid) REFERENCES currency_definitions(currency_uid),
                FOREIGN KEY(transaction_type_uid) REFERENCES financial_transaction_type_definitions(transaction_type_uid),
                FOREIGN KEY(campaign_id,reversal_of_uid) REFERENCES financial_ledger_transactions(campaign_id,financial_transaction_uid),
                CHECK(source_event_uid IS NULL OR length(trim(source_event_uid)) > 0),
                CHECK(command_uid IS NULL OR length(trim(command_uid)) > 0),
                CHECK(reversal_of_uid IS NULL OR length(trim(reversal_of_uid)) > 0),
                CHECK((flow_kind='INTERNAL' AND from_account_uid IS NOT NULL AND to_account_uid IS NOT NULL AND from_account_uid<>to_account_uid)
                   OR (flow_kind='SOURCE' AND from_account_uid IS NULL AND to_account_uid IS NOT NULL)
                   OR (flow_kind='SINK' AND from_account_uid IS NOT NULL AND to_account_uid IS NULL)
                   OR (flow_kind='REVERSAL' AND reversal_of_uid IS NOT NULL AND NOT(from_account_uid IS to_account_uid))))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS financial_account_balances(
                campaign_id TEXT NOT NULL,
                account_uid TEXT NOT NULL,
                balance_minor INTEGER NOT NULL DEFAULT 0 CHECK(typeof(balance_minor)='integer'),
                balance_version INTEGER NOT NULL DEFAULT 1 CHECK(typeof(balance_version)='integer' AND balance_version >= 1),
                last_effective_order INTEGER,
                PRIMARY KEY(campaign_id,account_uid),
                FOREIGN KEY(campaign_id,account_uid) REFERENCES financial_accounts(campaign_id,account_uid) ON DELETE CASCADE)
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS legacy_financial_evidence(
                campaign_id TEXT NOT NULL,
                legacy_evidence_uid TEXT NOT NULL,
                evidence_kind TEXT NOT NULL,
                mapped_account_uid TEXT,
                mapped_transaction_uid TEXT,
                mapping_version INTEGER NOT NULL CHECK(mapping_version >= 1),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
                PRIMARY KEY(campaign_id,legacy_evidence_uid),
                FOREIGN KEY(campaign_id,mapped_account_uid) REFERENCES financial_accounts(campaign_id,account_uid),
                FOREIGN KEY(campaign_id,mapped_transaction_uid) REFERENCES financial_ledger_transactions(campaign_id,financial_transaction_uid))
        """.trimIndent())

        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_fin_accounts_holder ON financial_accounts(campaign_id,holder_kind_uid,holder_uid,currency_uid,account_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_fin_ledger_from_time ON financial_ledger_transactions(campaign_id,from_account_uid,currency_uid,effective_order,financial_transaction_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_fin_ledger_to_time ON financial_ledger_transactions(campaign_id,to_account_uid,currency_uid,effective_order,financial_transaction_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_fin_ledger_event ON financial_ledger_transactions(campaign_id,source_event_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_fin_ledger_reversal ON financial_ledger_transactions(campaign_id,reversal_of_uid)")
        saveDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_fin_ledger_command ON financial_ledger_transactions(campaign_id,command_uid) WHERE command_uid IS NOT NULL")
        saveDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_fin_single_reversal ON financial_ledger_transactions(campaign_id,reversal_of_uid) WHERE reversal_of_uid IS NOT NULL")

        listOf(
            "trg_fin_account_holder_guard",
            "trg_fin_account_close_guard",
            "trg_fin_account_update_guard",
            "trg_fin_account_delete_guard",
            "trg_fin_holder_retire_guard",
            "trg_fin_currency_retire_guard",
            "trg_fin_transaction_reference_guard",
            "trg_fin_transaction_immutable_guard",
            "trg_fin_transaction_delete_guard",
            "trg_fin_balance_delete_guard"
        ).forEach { saveDb.execSQL("DROP TRIGGER IF EXISTS $it") }

        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_account_holder_guard
            BEFORE INSERT ON financial_accounts
            WHEN NOT EXISTS(
                SELECT 1 FROM ownership_party_registry p
                JOIN ownership_owner_kinds k ON k.owner_kind_uid=p.owner_kind_uid AND k.kind_status='ACTIVE'
                WHERE p.campaign_id=NEW.campaign_id AND p.owner_kind_uid=NEW.holder_kind_uid
                  AND p.owner_uid=NEW.holder_uid AND p.reference_status='ACTIVE')
              OR NOT EXISTS(SELECT 1 FROM currency_definitions c WHERE c.currency_uid=NEW.currency_uid AND c.definition_status='ACTIVE')
            BEGIN SELECT RAISE(ABORT,'financial account holder or currency is unresolved/inactive'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_account_close_guard
            BEFORE UPDATE OF closed_order ON financial_accounts
            WHEN OLD.closed_order IS NULL AND NEW.closed_order IS NOT NULL
             AND COALESCE((SELECT balance_minor FROM financial_account_balances b WHERE b.campaign_id=OLD.campaign_id AND b.account_uid=OLD.account_uid),0)<>0
            BEGIN SELECT RAISE(ABORT,'financial account with nonzero balance cannot be closed'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_account_update_guard
            BEFORE UPDATE ON financial_accounts
            WHEN NEW.campaign_id<>OLD.campaign_id OR NEW.account_uid<>OLD.account_uid
              OR NEW.holder_kind_uid<>OLD.holder_kind_uid OR NEW.holder_uid<>OLD.holder_uid
              OR NEW.account_type_uid<>OLD.account_type_uid OR NEW.currency_uid<>OLD.currency_uid
              OR NEW.opened_order<>OLD.opened_order OR NEW.provenance<>OLD.provenance
              OR OLD.closed_order IS NOT NULL OR NEW.closed_order IS NULL OR NEW.closed_order<=OLD.opened_order
              OR NEW.account_version<>OLD.account_version+1
            BEGIN SELECT RAISE(ABORT,'financial account mutation is not a legal close transition'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_account_delete_guard
            BEFORE DELETE ON financial_accounts
            BEGIN SELECT RAISE(ABORT,'financial account identity is append-preserved'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_holder_retire_guard
            BEFORE UPDATE OF reference_status ON ownership_party_registry
            WHEN OLD.reference_status='ACTIVE' AND NEW.reference_status='RETIRED'
             AND EXISTS(SELECT 1 FROM financial_accounts a WHERE a.campaign_id=OLD.campaign_id AND a.holder_kind_uid=OLD.owner_kind_uid AND a.holder_uid=OLD.owner_uid AND a.closed_order IS NULL)
            BEGIN SELECT RAISE(ABORT,'cannot retire finance holder while open financial account exists'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_currency_retire_guard
            BEFORE UPDATE OF definition_status ON currency_definitions
            WHEN OLD.definition_status='ACTIVE' AND NEW.definition_status='RETIRED'
             AND EXISTS(SELECT 1 FROM financial_accounts a WHERE a.currency_uid=OLD.currency_uid AND a.closed_order IS NULL)
            BEGIN SELECT RAISE(ABORT,'cannot retire currency while open financial account exists'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_transaction_reference_guard
            BEFORE INSERT ON financial_ledger_transactions
            WHEN NOT EXISTS(SELECT 1 FROM financial_transaction_type_definitions t WHERE t.transaction_type_uid=NEW.transaction_type_uid AND t.type_status='ACTIVE' AND t.flow_kind=NEW.flow_kind)
              OR NOT EXISTS(SELECT 1 FROM currency_definitions c WHERE c.currency_uid=NEW.currency_uid AND c.definition_status='ACTIVE')
              OR (NEW.from_account_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM financial_accounts a WHERE a.campaign_id=NEW.campaign_id AND a.account_uid=NEW.from_account_uid AND a.currency_uid=NEW.currency_uid AND a.closed_order IS NULL))
              OR (NEW.to_account_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM financial_accounts a WHERE a.campaign_id=NEW.campaign_id AND a.account_uid=NEW.to_account_uid AND a.currency_uid=NEW.currency_uid AND a.closed_order IS NULL))
            BEGIN SELECT RAISE(ABORT,'financial transaction reference/type/currency is unresolved or inactive'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_transaction_immutable_guard
            BEFORE UPDATE ON financial_ledger_transactions
            BEGIN SELECT RAISE(ABORT,'committed financial history is immutable'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_fin_transaction_delete_guard
            BEFORE DELETE ON financial_ledger_transactions
            BEGIN SELECT RAISE(ABORT,'committed financial history is append-only'); END
        """.trimIndent())

        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE13_MIGRATION_ID',strftime('%s','now'),'Append-only exact-integer FinancialTransaction ledger; campaign-scoped accounts reuse Phase-12 party identity; rebuildable balance projection; generic StatePatch bypass closed separately; zero legacy financial history synthesis')")
        saveDb.setTransactionSuccessful()
    } finally { saveDb.endTransaction() }
}
