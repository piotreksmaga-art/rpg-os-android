package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE14_MIGRATION_ID = "RPGOS-14.0-ASSETS-LIABILITIES"

/** Additive Phase 14 authority. No legacy aggregate is promoted without an explicit typed write. */
fun MigrationManager.ensureV14(db: SQLiteDatabase, campaignId: String) {
    ensureV13ContractGuards(db, campaignId)
    db.beginTransaction()
    try {
        db.execSQL("""CREATE TABLE IF NOT EXISTS asset_kind_definitions(
            asset_kind_uid TEXT PRIMARY KEY,
            asset_class TEXT NOT NULL CHECK(asset_class IN ('ASSET','SECURITY','PROPERTY','BUSINESS','OTHER')),
            display_name TEXT NOT NULL CHECK(length(trim(display_name))>0),
            world_pack_uid TEXT,
            definition_status TEXT NOT NULL CHECK(definition_status IN ('ACTIVE','DEPRECATED')),
            definition_version INTEGER NOT NULL CHECK(typeof(definition_version)='integer' AND definition_version>=1),
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
            FOREIGN KEY(asset_kind_uid) REFERENCES ownership_asset_kinds(asset_kind_uid),
            CHECK(world_pack_uid IS NULL OR length(trim(world_pack_uid))>0))""")
        CORE_ASSET_KINDS.forEach { (uid, klass, name) ->
            db.execSQL("INSERT OR IGNORE INTO ownership_asset_kinds(asset_kind_uid,kind_status,provenance) VALUES(?,'ACTIVE','RPGOS-14 generic asset namespace')", arrayOf(uid))
            db.execSQL("INSERT OR IGNORE INTO asset_kind_definitions(asset_kind_uid,asset_class,display_name,definition_status,definition_version,provenance) VALUES(?,?,?,'ACTIVE',1,'RPGOS-14 core asset kind')", arrayOf(uid,klass,name))
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS asset_records(
            campaign_id TEXT NOT NULL, asset_uid TEXT NOT NULL, asset_kind_uid TEXT NOT NULL,
            lifecycle_status TEXT NOT NULL CHECK(lifecycle_status IN ('ACTIVE','RETIRED','DESTROYED','LIQUIDATED')),
            created_order INTEGER NOT NULL CHECK(typeof(created_order)='integer'),
            retired_order INTEGER, source_event_uid TEXT,
            record_version INTEGER NOT NULL CHECK(typeof(record_version)='integer' AND record_version>=1),
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), metadata_json TEXT,
            PRIMARY KEY(campaign_id,asset_kind_uid,asset_uid),
            FOREIGN KEY(asset_kind_uid) REFERENCES asset_kind_definitions(asset_kind_uid),
            FOREIGN KEY(campaign_id,asset_kind_uid,asset_uid) REFERENCES ownership_asset_registry(campaign_id,asset_kind_uid,asset_uid),
            CHECK(asset_kind_uid<>'$OWNERSHIP_ASSET_KIND_ITEM_INSTANCE'),
            CHECK(source_event_uid IS NULL OR length(trim(source_event_uid))>0),
            CHECK((lifecycle_status='ACTIVE' AND retired_order IS NULL) OR (lifecycle_status<>'ACTIVE' AND retired_order IS NOT NULL AND retired_order>created_order)))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS asset_valuations(
            campaign_id TEXT NOT NULL, valuation_uid TEXT NOT NULL, asset_kind_uid TEXT NOT NULL, asset_uid TEXT NOT NULL,
            currency_uid TEXT NOT NULL, amount_minor INTEGER NOT NULL CHECK(typeof(amount_minor)='integer' AND amount_minor>=0),
            valuation_type TEXT NOT NULL CHECK(valuation_type IN ('MARKET','BOOK','APPRAISAL','FACE','CUSTOM')),
            effective_order INTEGER NOT NULL CHECK(typeof(effective_order)='integer'), valid_until_order INTEGER,
            source_event_uid TEXT, confidence_ppm INTEGER,
            valuation_version INTEGER NOT NULL CHECK(typeof(valuation_version)='integer' AND valuation_version>=1),
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
            PRIMARY KEY(campaign_id,valuation_uid),
            FOREIGN KEY(campaign_id,asset_kind_uid,asset_uid) REFERENCES asset_records(campaign_id,asset_kind_uid,asset_uid),
            FOREIGN KEY(currency_uid) REFERENCES currency_definitions(currency_uid),
            CHECK(valid_until_order IS NULL OR valid_until_order>effective_order),
            CHECK(source_event_uid IS NULL OR length(trim(source_event_uid))>0),
            CHECK(confidence_ppm IS NULL OR (typeof(confidence_ppm)='integer' AND confidence_ppm BETWEEN 0 AND 1000000)))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_asset_valuation_basis ON asset_valuations(campaign_id,asset_kind_uid,asset_uid,currency_uid,valuation_type,effective_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_asset_valuation_asof ON asset_valuations(campaign_id,asset_kind_uid,asset_uid,currency_uid,effective_order,valid_until_order)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS obligation_type_definitions(
            obligation_type_uid TEXT PRIMARY KEY, obligation_class TEXT NOT NULL CHECK(obligation_class IN ('DEBT','SERVICE','PAYMENT','DELIVERY','OTHER')),
            display_name TEXT NOT NULL CHECK(length(trim(display_name))>0), type_status TEXT NOT NULL CHECK(type_status IN ('ACTIVE','RETIRED')),
            definition_version INTEGER NOT NULL CHECK(definition_version>=1), provenance TEXT NOT NULL CHECK(length(trim(provenance))>0))""")
        listOf("DEBT","SERVICE","PAYMENT","DELIVERY","OTHER").forEach { k ->
            db.execSQL("INSERT OR IGNORE INTO obligation_type_definitions(obligation_type_uid,obligation_class,display_name,type_status,definition_version,provenance) VALUES(?,?,?,'ACTIVE',1,'RPGOS-14 core obligation type')", arrayOf("RPGOS-OBLIGATION-TYPE:$k",k,k))
        }
        db.execSQL("""CREATE TABLE IF NOT EXISTS obligation_records(
            campaign_id TEXT NOT NULL, obligation_uid TEXT NOT NULL, obligation_type_uid TEXT NOT NULL, obligation_class TEXT NOT NULL,
            obligor_kind_uid TEXT NOT NULL, obligor_uid TEXT NOT NULL, beneficiary_kind_uid TEXT NOT NULL, beneficiary_uid TEXT NOT NULL,
            currency_uid TEXT, principal_minor INTEGER, asset_kind_uid TEXT, asset_uid TEXT,
            created_order INTEGER NOT NULL CHECK(typeof(created_order)='integer'), due_order INTEGER, valid_until_order INTEGER,
            source_event_uid TEXT, source_contract_uid TEXT, record_version INTEGER NOT NULL CHECK(record_version>=1),
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), metadata_json TEXT,
            PRIMARY KEY(campaign_id,obligation_uid),
            FOREIGN KEY(obligation_type_uid) REFERENCES obligation_type_definitions(obligation_type_uid),
            FOREIGN KEY(campaign_id,obligor_kind_uid,obligor_uid) REFERENCES ownership_party_registry(campaign_id,owner_kind_uid,owner_uid),
            FOREIGN KEY(campaign_id,beneficiary_kind_uid,beneficiary_uid) REFERENCES ownership_party_registry(campaign_id,owner_kind_uid,owner_uid),
            FOREIGN KEY(currency_uid) REFERENCES currency_definitions(currency_uid),
            FOREIGN KEY(campaign_id,asset_kind_uid,asset_uid) REFERENCES asset_records(campaign_id,asset_kind_uid,asset_uid),
            CHECK(NOT(obligor_kind_uid=beneficiary_kind_uid AND obligor_uid=beneficiary_uid)),
            CHECK((currency_uid IS NULL AND principal_minor IS NULL) OR (currency_uid IS NOT NULL AND typeof(principal_minor)='integer' AND principal_minor>0)),
            CHECK((asset_kind_uid IS NULL AND asset_uid IS NULL) OR (asset_kind_uid IS NOT NULL AND asset_uid IS NOT NULL)),
            CHECK(due_order IS NULL OR due_order>=created_order), CHECK(valid_until_order IS NULL OR valid_until_order>created_order),
            CHECK(source_event_uid IS NULL OR length(trim(source_event_uid))>0), CHECK(source_contract_uid IS NULL OR length(trim(source_contract_uid))>0))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS obligation_status_history(
            campaign_id TEXT NOT NULL, status_event_uid TEXT NOT NULL, obligation_uid TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('ACTIVE','SETTLED','DEFAULTED','CANCELLED','EXPIRED')),
            effective_order INTEGER NOT NULL CHECK(typeof(effective_order)='integer'), source_event_uid TEXT,
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
            PRIMARY KEY(campaign_id,status_event_uid), FOREIGN KEY(campaign_id,obligation_uid) REFERENCES obligation_records(campaign_id,obligation_uid),
            CHECK(source_event_uid IS NULL OR length(trim(source_event_uid))>0))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_obligation_status_time ON obligation_status_history(campaign_id,obligation_uid,effective_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_obligation_party ON obligation_records(campaign_id,obligor_kind_uid,obligor_uid,beneficiary_kind_uid,beneficiary_uid)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS obligation_settlements(
            campaign_id TEXT NOT NULL, settlement_uid TEXT NOT NULL, obligation_uid TEXT NOT NULL,
            settlement_kind TEXT NOT NULL CHECK(settlement_kind IN ('PAYMENT','FORGIVENESS','WRITE_OFF','OTHER')),
            amount_minor INTEGER, financial_transaction_uid TEXT, ownership_operation_uid TEXT,
            effective_order INTEGER NOT NULL CHECK(typeof(effective_order)='integer'), source_event_uid TEXT,
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
            PRIMARY KEY(campaign_id,settlement_uid), FOREIGN KEY(campaign_id,obligation_uid) REFERENCES obligation_records(campaign_id,obligation_uid),
            FOREIGN KEY(campaign_id,financial_transaction_uid) REFERENCES financial_ledger_transactions(campaign_id,financial_transaction_uid),
            FOREIGN KEY(campaign_id,ownership_operation_uid) REFERENCES ownership_operations(campaign_id,operation_uid),
            CHECK(amount_minor IS NULL OR (typeof(amount_minor)='integer' AND amount_minor>0)),
            CHECK(source_event_uid IS NULL OR length(trim(source_event_uid))>0))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_obligation_settlement ON obligation_settlements(campaign_id,obligation_uid,effective_order,settlement_uid)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS asset_encumbrances(
            campaign_id TEXT NOT NULL, encumbrance_uid TEXT NOT NULL, asset_kind_uid TEXT NOT NULL, asset_uid TEXT NOT NULL, obligation_uid TEXT NOT NULL,
            encumbrance_type_uid TEXT NOT NULL CHECK(length(trim(encumbrance_type_uid))>0), priority INTEGER NOT NULL DEFAULT 0,
            valid_from_order INTEGER NOT NULL, released_order INTEGER, record_version INTEGER NOT NULL CHECK(record_version>=1),
            provenance TEXT NOT NULL CHECK(length(trim(provenance))>0), release_provenance TEXT,
            PRIMARY KEY(campaign_id,encumbrance_uid),
            FOREIGN KEY(campaign_id,asset_kind_uid,asset_uid) REFERENCES asset_records(campaign_id,asset_kind_uid,asset_uid),
            FOREIGN KEY(campaign_id,obligation_uid) REFERENCES obligation_records(campaign_id,obligation_uid),
            CHECK(released_order IS NULL OR released_order>valid_from_order),
            CHECK((released_order IS NULL AND release_provenance IS NULL) OR (released_order IS NOT NULL AND release_provenance IS NOT NULL AND length(trim(release_provenance))>0)))""")

        installV14Triggers(db)
        db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE14_MIGRATION_ID',strftime('%s','now'),'Generic canonical assets, append-only valuations, obligation/status/settlement history, encumbrances and derived net-worth inputs; zero legacy aggregate synthesis')")
        db.setTransactionSuccessful()
    } finally { db.endTransaction() }
}

fun MigrationManager.ensureV14ContractGuards(db: SQLiteDatabase, campaignId: String) {
    ensureV14(db, campaignId)
    db.beginTransaction(); try { installV14Triggers(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
}

private fun installV14Triggers(db: SQLiteDatabase) {
    listOf("trg_p14_asset_insert","trg_p14_asset_update","trg_p14_asset_delete","trg_p14_asset_registry_retire","trg_p14_valuation_insert","trg_p14_valuation_immutable","trg_p14_valuation_delete","trg_p14_obligation_insert","trg_p14_obligation_immutable","trg_p14_obligation_delete","trg_p14_obligation_party_retire","trg_p14_status_insert","trg_p14_status_immutable","trg_p14_status_delete","trg_p14_settlement_insert","trg_p14_settlement_immutable","trg_p14_settlement_delete","trg_p14_encumbrance_insert","trg_p14_encumbrance_update","trg_p14_encumbrance_delete").forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
    db.execSQL("""CREATE TRIGGER trg_p14_asset_insert BEFORE INSERT ON asset_records WHEN
      NOT EXISTS(SELECT 1 FROM asset_kind_definitions d JOIN ownership_asset_kinds k ON k.asset_kind_uid=d.asset_kind_uid WHERE d.asset_kind_uid=NEW.asset_kind_uid AND d.definition_status='ACTIVE' AND k.kind_status='ACTIVE')
      OR NOT EXISTS(SELECT 1 FROM ownership_asset_registry r WHERE r.campaign_id=NEW.campaign_id AND r.asset_kind_uid=NEW.asset_kind_uid AND r.asset_uid=NEW.asset_uid AND r.reference_status='ACTIVE')
      BEGIN SELECT RAISE(ABORT,'Phase14 asset kind/reference unresolved or inactive'); END""")
    db.execSQL("""CREATE TRIGGER trg_p14_asset_update BEFORE UPDATE ON asset_records WHEN
      NEW.campaign_id<>OLD.campaign_id OR NEW.asset_uid<>OLD.asset_uid OR NEW.asset_kind_uid<>OLD.asset_kind_uid OR NEW.created_order<>OLD.created_order OR NEW.source_event_uid IS NOT OLD.source_event_uid OR NEW.provenance<>OLD.provenance OR NEW.metadata_json IS NOT OLD.metadata_json
      OR OLD.lifecycle_status<>'ACTIVE' OR OLD.retired_order IS NOT NULL OR NEW.lifecycle_status='ACTIVE' OR NEW.retired_order IS NULL OR NEW.retired_order<=OLD.created_order OR NEW.record_version<>OLD.record_version+1
      OR EXISTS(SELECT 1 FROM ownership_records r WHERE r.campaign_id=OLD.campaign_id AND r.asset_kind_uid=OLD.asset_kind_uid AND r.asset_uid=OLD.asset_uid AND r.valid_from_order<NEW.retired_order AND (r.valid_until_order IS NULL OR NEW.retired_order<r.valid_until_order))
      BEGIN SELECT RAISE(ABORT,'illegal Phase14 asset lifecycle transition'); END""")
    db.execSQL("CREATE TRIGGER trg_p14_asset_delete BEFORE DELETE ON asset_records BEGIN SELECT RAISE(ABORT,'Phase14 asset identity/history is append-preserved'); END")
    db.execSQL("""CREATE TRIGGER trg_p14_asset_registry_retire BEFORE UPDATE OF reference_status ON ownership_asset_registry WHEN OLD.reference_status='ACTIVE' AND NEW.reference_status='RETIRED' AND EXISTS(SELECT 1 FROM asset_records a WHERE a.campaign_id=OLD.campaign_id AND a.asset_kind_uid=OLD.asset_kind_uid AND a.asset_uid=OLD.asset_uid AND a.lifecycle_status='ACTIVE') BEGIN SELECT RAISE(ABORT,'cannot retire registry target while Phase14 asset active'); END""")
    db.execSQL("""CREATE TRIGGER trg_p14_valuation_insert BEFORE INSERT ON asset_valuations WHEN
      NOT EXISTS(SELECT 1 FROM asset_records a WHERE a.campaign_id=NEW.campaign_id AND a.asset_kind_uid=NEW.asset_kind_uid AND a.asset_uid=NEW.asset_uid AND a.created_order<=NEW.effective_order AND (a.retired_order IS NULL OR NEW.effective_order<a.retired_order))
      OR NOT EXISTS(SELECT 1 FROM currency_definitions c WHERE c.currency_uid=NEW.currency_uid AND c.definition_status='ACTIVE')
      BEGIN SELECT RAISE(ABORT,'valuation asset/currency unresolved or inactive at effective order'); END""")
    db.execSQL("CREATE TRIGGER trg_p14_valuation_immutable BEFORE UPDATE ON asset_valuations BEGIN SELECT RAISE(ABORT,'asset valuation history is immutable'); END")
    db.execSQL("CREATE TRIGGER trg_p14_valuation_delete BEFORE DELETE ON asset_valuations BEGIN SELECT RAISE(ABORT,'asset valuation history is append-only'); END")
    db.execSQL("""CREATE TRIGGER trg_p14_obligation_insert BEFORE INSERT ON obligation_records WHEN
      NOT EXISTS(SELECT 1 FROM obligation_type_definitions t WHERE t.obligation_type_uid=NEW.obligation_type_uid AND t.obligation_class=NEW.obligation_class AND t.type_status='ACTIVE')
      OR NOT EXISTS(SELECT 1 FROM ownership_party_registry p WHERE p.campaign_id=NEW.campaign_id AND p.owner_kind_uid=NEW.obligor_kind_uid AND p.owner_uid=NEW.obligor_uid AND p.reference_status='ACTIVE')
      OR NOT EXISTS(SELECT 1 FROM ownership_party_registry p WHERE p.campaign_id=NEW.campaign_id AND p.owner_kind_uid=NEW.beneficiary_kind_uid AND p.owner_uid=NEW.beneficiary_uid AND p.reference_status='ACTIVE')
      OR (NEW.currency_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM currency_definitions c WHERE c.currency_uid=NEW.currency_uid AND c.definition_status='ACTIVE'))
      OR (NEW.asset_uid IS NOT NULL AND NOT EXISTS(SELECT 1 FROM asset_records a WHERE a.campaign_id=NEW.campaign_id AND a.asset_kind_uid=NEW.asset_kind_uid AND a.asset_uid=NEW.asset_uid AND a.created_order<=NEW.created_order AND (a.retired_order IS NULL OR NEW.created_order<a.retired_order)))
      BEGIN SELECT RAISE(ABORT,'obligation reference/type/currency unresolved or inactive'); END""")
    db.execSQL("CREATE TRIGGER trg_p14_obligation_immutable BEFORE UPDATE ON obligation_records BEGIN SELECT RAISE(ABORT,'obligation identity/contract is immutable'); END")
    db.execSQL("CREATE TRIGGER trg_p14_obligation_delete BEFORE DELETE ON obligation_records BEGIN SELECT RAISE(ABORT,'obligation history is append-only'); END")
    db.execSQL("""CREATE TRIGGER trg_p14_obligation_party_retire BEFORE UPDATE OF reference_status ON ownership_party_registry WHEN OLD.reference_status='ACTIVE' AND NEW.reference_status='RETIRED' AND EXISTS(SELECT 1 FROM obligation_records o WHERE o.campaign_id=OLD.campaign_id AND ((o.obligor_kind_uid=OLD.owner_kind_uid AND o.obligor_uid=OLD.owner_uid) OR (o.beneficiary_kind_uid=OLD.owner_kind_uid AND o.beneficiary_uid=OLD.owner_uid)) AND (SELECT s.status FROM obligation_status_history s WHERE s.campaign_id=o.campaign_id AND s.obligation_uid=o.obligation_uid ORDER BY s.effective_order DESC LIMIT 1) IN ('ACTIVE','DEFAULTED')) BEGIN SELECT RAISE(ABORT,'cannot retire party with live Phase14 obligation'); END""")
    db.execSQL("""CREATE TRIGGER trg_p14_status_insert BEFORE INSERT ON obligation_status_history WHEN
      NOT EXISTS(SELECT 1 FROM obligation_records o WHERE o.campaign_id=NEW.campaign_id AND o.obligation_uid=NEW.obligation_uid AND NEW.effective_order>=o.created_order)
      OR (EXISTS(SELECT 1 FROM obligation_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.obligation_uid=NEW.obligation_uid AND s.effective_order>=NEW.effective_order))
      OR (EXISTS(SELECT 1 FROM obligation_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.obligation_uid=NEW.obligation_uid) AND (SELECT status FROM obligation_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.obligation_uid=NEW.obligation_uid ORDER BY effective_order DESC LIMIT 1) NOT IN ('ACTIVE','DEFAULTED'))
      OR (NOT EXISTS(SELECT 1 FROM obligation_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.obligation_uid=NEW.obligation_uid) AND NEW.status<>'ACTIVE')
      OR (NEW.status='SETTLED' AND EXISTS(SELECT 1 FROM obligation_records o WHERE o.campaign_id=NEW.campaign_id AND o.obligation_uid=NEW.obligation_uid AND o.principal_minor IS NOT NULL AND COALESCE((SELECT SUM(x.amount_minor) FROM obligation_settlements x WHERE x.campaign_id=o.campaign_id AND x.obligation_uid=o.obligation_uid AND x.amount_minor IS NOT NULL),0)<>o.principal_minor))
      BEGIN SELECT RAISE(ABORT,'illegal obligation status transition/order'); END""")
    db.execSQL("CREATE TRIGGER trg_p14_status_immutable BEFORE UPDATE ON obligation_status_history BEGIN SELECT RAISE(ABORT,'obligation status history is immutable'); END")
    db.execSQL("CREATE TRIGGER trg_p14_status_delete BEFORE DELETE ON obligation_status_history BEGIN SELECT RAISE(ABORT,'obligation status history is append-only'); END")
    db.execSQL("""CREATE TRIGGER trg_p14_settlement_insert BEFORE INSERT ON obligation_settlements WHEN
      NOT EXISTS(SELECT 1 FROM obligation_records o WHERE o.campaign_id=NEW.campaign_id AND o.obligation_uid=NEW.obligation_uid AND NEW.effective_order>=o.created_order)
      OR (SELECT status FROM obligation_status_history s WHERE s.campaign_id=NEW.campaign_id AND s.obligation_uid=NEW.obligation_uid ORDER BY effective_order DESC LIMIT 1) NOT IN ('ACTIVE','DEFAULTED')
      OR EXISTS(SELECT 1 FROM obligation_records o WHERE o.campaign_id=NEW.campaign_id AND o.obligation_uid=NEW.obligation_uid AND ((o.principal_minor IS NULL AND NEW.amount_minor IS NOT NULL) OR (o.principal_minor IS NOT NULL AND (NEW.amount_minor IS NULL OR NEW.amount_minor>o.principal_minor-COALESCE((SELECT SUM(x.amount_minor) FROM obligation_settlements x WHERE x.campaign_id=o.campaign_id AND x.obligation_uid=o.obligation_uid AND x.amount_minor IS NOT NULL),0)))))
      OR (NEW.settlement_kind='PAYMENT' AND NOT EXISTS(SELECT 1 FROM obligation_records o JOIN financial_ledger_transactions f ON f.campaign_id=o.campaign_id AND f.financial_transaction_uid=NEW.financial_transaction_uid JOIN financial_accounts fa ON fa.campaign_id=f.campaign_id AND fa.account_uid=f.from_account_uid JOIN financial_accounts ta ON ta.campaign_id=f.campaign_id AND ta.account_uid=f.to_account_uid WHERE o.campaign_id=NEW.campaign_id AND o.obligation_uid=NEW.obligation_uid AND o.currency_uid=f.currency_uid AND o.principal_minor IS NOT NULL AND NEW.amount_minor=f.amount_minor AND f.flow_kind='INTERNAL' AND fa.holder_kind_uid=o.obligor_kind_uid AND fa.holder_uid=o.obligor_uid AND ta.holder_kind_uid=o.beneficiary_kind_uid AND ta.holder_uid=o.beneficiary_uid))
      OR (NEW.settlement_kind<>'PAYMENT' AND NEW.financial_transaction_uid IS NOT NULL)
      BEGIN SELECT RAISE(ABORT,'invalid obligation settlement/reference/outstanding amount'); END""")
    db.execSQL("CREATE TRIGGER trg_p14_settlement_immutable BEFORE UPDATE ON obligation_settlements BEGIN SELECT RAISE(ABORT,'obligation settlement history is immutable'); END")
    db.execSQL("CREATE TRIGGER trg_p14_settlement_delete BEFORE DELETE ON obligation_settlements BEGIN SELECT RAISE(ABORT,'obligation settlement history is append-only'); END")
    db.execSQL("""CREATE TRIGGER trg_p14_encumbrance_insert BEFORE INSERT ON asset_encumbrances WHEN NOT EXISTS(SELECT 1 FROM asset_records a WHERE a.campaign_id=NEW.campaign_id AND a.asset_kind_uid=NEW.asset_kind_uid AND a.asset_uid=NEW.asset_uid AND a.created_order<=NEW.valid_from_order AND (a.retired_order IS NULL OR NEW.valid_from_order<a.retired_order)) OR NOT EXISTS(SELECT 1 FROM obligation_records o WHERE o.campaign_id=NEW.campaign_id AND o.obligation_uid=NEW.obligation_uid AND o.created_order<=NEW.valid_from_order) BEGIN SELECT RAISE(ABORT,'encumbrance reference invalid at effective order'); END""")
    db.execSQL("""CREATE TRIGGER trg_p14_encumbrance_update BEFORE UPDATE ON asset_encumbrances WHEN NEW.campaign_id<>OLD.campaign_id OR NEW.encumbrance_uid<>OLD.encumbrance_uid OR NEW.asset_kind_uid<>OLD.asset_kind_uid OR NEW.asset_uid<>OLD.asset_uid OR NEW.obligation_uid<>OLD.obligation_uid OR NEW.encumbrance_type_uid<>OLD.encumbrance_type_uid OR NEW.priority<>OLD.priority OR NEW.valid_from_order<>OLD.valid_from_order OR NEW.provenance<>OLD.provenance OR OLD.released_order IS NOT NULL OR NEW.released_order IS NULL OR NEW.released_order<=OLD.valid_from_order OR NEW.record_version<>OLD.record_version+1 OR NEW.release_provenance IS NULL BEGIN SELECT RAISE(ABORT,'illegal encumbrance release transition'); END""")
    db.execSQL("CREATE TRIGGER trg_p14_encumbrance_delete BEFORE DELETE ON asset_encumbrances BEGIN SELECT RAISE(ABORT,'encumbrance history is append-preserved'); END")
}

private val CORE_ASSET_KINDS = listOf(
    Triple(ASSET_KIND_PROPERTY,"PROPERTY","Property"), Triple(ASSET_KIND_LAND,"PROPERTY","Land"),
    Triple(ASSET_KIND_BUSINESS,"BUSINESS","Business"), Triple(ASSET_KIND_COMPANY,"BUSINESS","Company"),
    Triple(ASSET_KIND_SHARES,"SECURITY","Shares"), Triple(ASSET_KIND_STAKE,"SECURITY","Stake"),
    Triple(ASSET_KIND_VEHICLE,"ASSET","Vehicle"), Triple(ASSET_KIND_RARE_ASSET,"OTHER","Rare asset")
)
