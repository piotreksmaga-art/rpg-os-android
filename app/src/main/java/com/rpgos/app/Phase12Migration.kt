package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

const val PHASE12_MIGRATION_ID = "RPGOS-12.0-OWNERSHIP"
const val PHASE12_OPTIONAL_SOURCE_EVENT_MIGRATION_ID = "RPGOS-12.1-OWNERSHIP-OPTIONAL-SOURCE-EVENT"

/** Additive Phase 12 legal OwnershipRecord history. Possession and Equipment remain independent authorities. */
fun MigrationManager.ensureV12(saveDb: SQLiteDatabase, campaignId: String) {
    ensureV11(saveDb, campaignId)
    saveDb.beginTransaction()
    try {
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS ownership_owner_kinds(
                owner_kind_uid TEXT PRIMARY KEY,
                kind_status TEXT NOT NULL CHECK(kind_status IN ('ACTIVE','RETIRED')),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0))
        """.trimIndent())
        listOf("CHARACTER","PLAYER","NPC","ORGANIZATION","STATE","BUSINESS","COMPANY").forEach { kind ->
            saveDb.execSQL("INSERT OR IGNORE INTO ownership_owner_kinds(owner_kind_uid,kind_status,provenance) VALUES(?,'ACTIVE','RPGOS-12 owner namespace')", arrayOf(kind))
        }
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS ownership_asset_kinds(
                asset_kind_uid TEXT PRIMARY KEY,
                kind_status TEXT NOT NULL CHECK(kind_status IN ('ACTIVE','RETIRED')),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0))
        """.trimIndent())
        saveDb.execSQL("INSERT OR IGNORE INTO ownership_asset_kinds(asset_kind_uid,kind_status,provenance) VALUES('$OWNERSHIP_ASSET_KIND_ITEM_INSTANCE','ACTIVE','RPGOS-12 ItemInstance namespace')")
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS ownership_party_registry(
                campaign_id TEXT NOT NULL,
                owner_kind_uid TEXT NOT NULL,
                owner_uid TEXT NOT NULL,
                reference_status TEXT NOT NULL CHECK(reference_status IN ('ACTIVE','RETIRED')),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
                retirement_provenance TEXT,
                PRIMARY KEY(campaign_id,owner_kind_uid,owner_uid),
                FOREIGN KEY(owner_kind_uid) REFERENCES ownership_owner_kinds(owner_kind_uid),
                CHECK((reference_status='ACTIVE' AND retirement_provenance IS NULL) OR
                      (reference_status='RETIRED' AND retirement_provenance IS NOT NULL AND length(trim(retirement_provenance)) > 0)))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS ownership_asset_registry(
                campaign_id TEXT NOT NULL,
                asset_kind_uid TEXT NOT NULL,
                asset_uid TEXT NOT NULL,
                reference_status TEXT NOT NULL CHECK(reference_status IN ('ACTIVE','RETIRED')),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
                retirement_provenance TEXT,
                PRIMARY KEY(campaign_id,asset_kind_uid,asset_uid),
                FOREIGN KEY(asset_kind_uid) REFERENCES ownership_asset_kinds(asset_kind_uid),
                CHECK(asset_kind_uid <> '$OWNERSHIP_ASSET_KIND_ITEM_INSTANCE'),
                CHECK((reference_status='ACTIVE' AND retirement_provenance IS NULL) OR
                      (reference_status='RETIRED' AND retirement_provenance IS NOT NULL AND length(trim(retirement_provenance)) > 0)))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS ownership_records(
                campaign_id TEXT NOT NULL,
                ownership_record_uid TEXT NOT NULL,
                owner_kind_uid TEXT NOT NULL CHECK(length(trim(owner_kind_uid)) > 0),
                owner_uid TEXT NOT NULL CHECK(length(trim(owner_uid)) > 0),
                asset_kind_uid TEXT NOT NULL CHECK(length(trim(asset_kind_uid)) > 0),
                asset_uid TEXT NOT NULL CHECK(length(trim(asset_uid)) > 0),
                ownership_type_uid TEXT NOT NULL CHECK(length(trim(ownership_type_uid)) > 0),
                share_units INTEGER NOT NULL CHECK(typeof(share_units)='integer' AND share_units > 0 AND share_units <= $OWNERSHIP_SHARE_SCALE),
                valid_from_order INTEGER NOT NULL CHECK(typeof(valid_from_order)='integer'),
                valid_until_order INTEGER CHECK(valid_until_order IS NULL OR typeof(valid_until_order)='integer'),
                source_event_uid TEXT,
                supersedes_record_uid TEXT,
                closed_by_event_uid TEXT,
                record_version INTEGER NOT NULL CHECK(typeof(record_version)='integer' AND record_version >= 1),
                record_status TEXT NOT NULL CHECK(record_status IN ('ACTIVE','CLOSED')),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
                closure_provenance TEXT,
                metadata_json TEXT,
                PRIMARY KEY(campaign_id,ownership_record_uid),
                FOREIGN KEY(campaign_id,supersedes_record_uid) REFERENCES ownership_records(campaign_id,ownership_record_uid),
                CHECK(source_event_uid IS NULL OR length(trim(source_event_uid)) > 0),
                CHECK(supersedes_record_uid IS NULL OR length(trim(supersedes_record_uid)) > 0),
                CHECK(closed_by_event_uid IS NULL OR length(trim(closed_by_event_uid)) > 0),
                CHECK(valid_until_order IS NULL OR valid_until_order > valid_from_order),
                CHECK((record_status='ACTIVE' AND valid_until_order IS NULL AND closed_by_event_uid IS NULL AND closure_provenance IS NULL)
                   OR (record_status='CLOSED' AND valid_until_order IS NOT NULL AND closure_provenance IS NOT NULL AND length(trim(closure_provenance)) > 0)))
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS ownership_operations(
                campaign_id TEXT NOT NULL,
                operation_uid TEXT NOT NULL,
                operation_kind TEXT NOT NULL CHECK(operation_kind IN ('TRANSFER','CLOSE')),
                asset_kind_uid TEXT NOT NULL,
                asset_uid TEXT NOT NULL,
                ownership_type_uid TEXT NOT NULL,
                source_record_uid TEXT NOT NULL,
                source_successor_uid TEXT,
                destination_successor_uid TEXT,
                effective_order INTEGER NOT NULL,
                source_event_uid TEXT CHECK(source_event_uid IS NULL OR length(trim(source_event_uid)) > 0),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
                PRIMARY KEY(campaign_id,operation_uid),
                FOREIGN KEY(campaign_id,source_record_uid) REFERENCES ownership_records(campaign_id,ownership_record_uid),
                FOREIGN KEY(campaign_id,source_successor_uid) REFERENCES ownership_records(campaign_id,ownership_record_uid),
                FOREIGN KEY(campaign_id,destination_successor_uid) REFERENCES ownership_records(campaign_id,ownership_record_uid))
        """.trimIndent())
        repairOwnershipOperationSourceEventNullability(saveDb)
        saveDb.execSQL("""
            CREATE TABLE IF NOT EXISTS legacy_ownership_mappings(
                campaign_id TEXT NOT NULL,
                legacy_evidence_uid TEXT NOT NULL,
                ownership_record_uid TEXT NOT NULL,
                mapping_version INTEGER NOT NULL CHECK(mapping_version >= 1),
                provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
                PRIMARY KEY(campaign_id,legacy_evidence_uid),
                FOREIGN KEY(campaign_id,ownership_record_uid) REFERENCES ownership_records(campaign_id,ownership_record_uid))
        """.trimIndent())

        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_ownership_asset_time ON ownership_records(campaign_id,asset_kind_uid,asset_uid,ownership_type_uid,valid_from_order,valid_until_order)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_ownership_owner_time ON ownership_records(campaign_id,owner_kind_uid,owner_uid,valid_from_order,valid_until_order)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_ownership_source_event ON ownership_records(campaign_id,source_event_uid)")
        saveDb.execSQL("CREATE INDEX IF NOT EXISTS idx_ownership_supersedes ON ownership_records(campaign_id,supersedes_record_uid)")
        saveDb.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS uq_ownership_current_owner_asset_type ON ownership_records(campaign_id,owner_kind_uid,owner_uid,asset_kind_uid,asset_uid,ownership_type_uid) WHERE record_status='ACTIVE' AND valid_until_order IS NULL")

        listOf(
            "trg_ownership_owner_reference_guard",
            "trg_ownership_item_instance_guard",
            "trg_ownership_generic_asset_guard",
            "trg_ownership_party_retire_guard",
            "trg_ownership_party_delete_guard",
            "trg_ownership_asset_retire_guard",
            "trg_ownership_asset_delete_guard",
            "trg_ownership_item_delete_guard",
            "trg_ownership_supersedes_guard",
            "trg_ownership_same_owner_overlap_guard",
            "trg_ownership_share_overlap_guard",
            "trg_ownership_immutable_update_guard",
            "trg_ownership_history_delete_guard"
        ).forEach { trigger -> saveDb.execSQL("DROP TRIGGER IF EXISTS $trigger") }

        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_owner_reference_guard
            BEFORE INSERT ON ownership_records
            WHEN NOT EXISTS(
                SELECT 1 FROM ownership_party_registry p
                JOIN ownership_owner_kinds k ON k.owner_kind_uid=p.owner_kind_uid AND k.kind_status='ACTIVE'
                WHERE p.campaign_id=NEW.campaign_id AND p.owner_kind_uid=NEW.owner_kind_uid
                  AND p.owner_uid=NEW.owner_uid AND p.reference_status='ACTIVE')
            BEGIN SELECT RAISE(ABORT,'ownership owner reference is unresolved or inactive'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_item_instance_guard
            BEFORE INSERT ON ownership_records
            WHEN NEW.asset_kind_uid='$OWNERSHIP_ASSET_KIND_ITEM_INSTANCE'
             AND NOT EXISTS(SELECT 1 FROM item_instances i WHERE i.campaign_id=NEW.campaign_id AND i.item_instance_uid=NEW.asset_uid)
            BEGIN SELECT RAISE(ABORT,'ownership ItemInstance asset target does not exist in campaign'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_generic_asset_guard
            BEFORE INSERT ON ownership_records
            WHEN NEW.asset_kind_uid<>'$OWNERSHIP_ASSET_KIND_ITEM_INSTANCE'
             AND NOT EXISTS(
                SELECT 1 FROM ownership_asset_registry a
                JOIN ownership_asset_kinds k ON k.asset_kind_uid=a.asset_kind_uid AND k.kind_status='ACTIVE'
                WHERE a.campaign_id=NEW.campaign_id AND a.asset_kind_uid=NEW.asset_kind_uid
                  AND a.asset_uid=NEW.asset_uid AND a.reference_status='ACTIVE')
            BEGIN SELECT RAISE(ABORT,'ownership generic asset reference is unresolved or inactive'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_party_retire_guard
            BEFORE UPDATE OF reference_status ON ownership_party_registry
            WHEN OLD.reference_status='ACTIVE' AND NEW.reference_status='RETIRED'
             AND EXISTS(SELECT 1 FROM ownership_records r WHERE r.campaign_id=OLD.campaign_id AND r.owner_kind_uid=OLD.owner_kind_uid AND r.owner_uid=OLD.owner_uid AND r.record_status='ACTIVE' AND r.valid_until_order IS NULL)
            BEGIN SELECT RAISE(ABORT,'cannot retire owner while active ownership exists'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_party_delete_guard
            BEFORE DELETE ON ownership_party_registry
            BEGIN SELECT RAISE(ABORT,'ownership party registry identity is append-preserved'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_asset_retire_guard
            BEFORE UPDATE OF reference_status ON ownership_asset_registry
            WHEN OLD.reference_status='ACTIVE' AND NEW.reference_status='RETIRED'
             AND EXISTS(SELECT 1 FROM ownership_records r WHERE r.campaign_id=OLD.campaign_id AND r.asset_kind_uid=OLD.asset_kind_uid AND r.asset_uid=OLD.asset_uid AND r.record_status='ACTIVE' AND r.valid_until_order IS NULL)
            BEGIN SELECT RAISE(ABORT,'cannot retire asset while active ownership exists'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_asset_delete_guard
            BEFORE DELETE ON ownership_asset_registry
            BEGIN SELECT RAISE(ABORT,'ownership asset registry identity is append-preserved'); END
        """.trimIndent())
        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_item_delete_guard
            BEFORE DELETE ON item_instances
            WHEN EXISTS(SELECT 1 FROM ownership_records r WHERE r.campaign_id=OLD.campaign_id AND r.asset_kind_uid='$OWNERSHIP_ASSET_KIND_ITEM_INSTANCE' AND r.asset_uid=OLD.item_instance_uid)
            BEGIN SELECT RAISE(ABORT,'cannot delete ItemInstance referenced by ownership history'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_supersedes_guard
            BEFORE INSERT ON ownership_records
            WHEN NEW.supersedes_record_uid IS NOT NULL
             AND NOT EXISTS(
                SELECT 1 FROM ownership_records p
                WHERE p.campaign_id=NEW.campaign_id AND p.ownership_record_uid=NEW.supersedes_record_uid
                  AND p.asset_kind_uid=NEW.asset_kind_uid AND p.asset_uid=NEW.asset_uid
                  AND p.ownership_type_uid=NEW.ownership_type_uid)
            BEGIN SELECT RAISE(ABORT,'ownership predecessor scope mismatch'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_same_owner_overlap_guard
            BEFORE INSERT ON ownership_records
            WHEN EXISTS(
                SELECT 1 FROM ownership_records r
                WHERE r.campaign_id=NEW.campaign_id
                  AND r.owner_kind_uid=NEW.owner_kind_uid AND r.owner_uid=NEW.owner_uid
                  AND r.asset_kind_uid=NEW.asset_kind_uid AND r.asset_uid=NEW.asset_uid
                  AND r.ownership_type_uid=NEW.ownership_type_uid
                  AND (NEW.valid_until_order IS NULL OR r.valid_from_order < NEW.valid_until_order)
                  AND (r.valid_until_order IS NULL OR NEW.valid_from_order < r.valid_until_order))
            BEGIN SELECT RAISE(ABORT,'overlapping ownership records for same owner/right'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_share_overlap_guard
            BEFORE INSERT ON ownership_records
            WHEN EXISTS(
                SELECT 1
                FROM (
                    SELECT NEW.valid_from_order AS point_order
                    UNION
                    SELECT r.valid_from_order
                    FROM ownership_records r
                    WHERE r.campaign_id=NEW.campaign_id
                      AND r.asset_kind_uid=NEW.asset_kind_uid AND r.asset_uid=NEW.asset_uid
                      AND r.ownership_type_uid=NEW.ownership_type_uid
                      AND r.valid_from_order >= NEW.valid_from_order
                      AND (NEW.valid_until_order IS NULL OR r.valid_from_order < NEW.valid_until_order)
                      AND (r.valid_until_order IS NULL OR NEW.valid_from_order < r.valid_until_order)
                ) points
                WHERE NEW.share_units > $OWNERSHIP_SHARE_SCALE - COALESCE((
                    SELECT SUM(x.share_units)
                    FROM ownership_records x
                    WHERE x.campaign_id=NEW.campaign_id
                      AND x.asset_kind_uid=NEW.asset_kind_uid AND x.asset_uid=NEW.asset_uid
                      AND x.ownership_type_uid=NEW.ownership_type_uid
                      AND x.valid_from_order <= points.point_order
                      AND (x.valid_until_order IS NULL OR points.point_order < x.valid_until_order)
                ),0))
            BEGIN SELECT RAISE(ABORT,'aggregate ownership share exceeds 100 percent'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_immutable_update_guard
            BEFORE UPDATE ON ownership_records
            WHEN NEW.campaign_id<>OLD.campaign_id
              OR NEW.ownership_record_uid<>OLD.ownership_record_uid
              OR NEW.owner_kind_uid<>OLD.owner_kind_uid OR NEW.owner_uid<>OLD.owner_uid
              OR NEW.asset_kind_uid<>OLD.asset_kind_uid OR NEW.asset_uid<>OLD.asset_uid
              OR NEW.ownership_type_uid<>OLD.ownership_type_uid OR NEW.share_units<>OLD.share_units
              OR NEW.valid_from_order<>OLD.valid_from_order
              OR NEW.source_event_uid IS NOT OLD.source_event_uid
              OR NEW.supersedes_record_uid IS NOT OLD.supersedes_record_uid
              OR NEW.provenance<>OLD.provenance OR NEW.metadata_json IS NOT OLD.metadata_json
              OR OLD.record_status<>'ACTIVE' OR OLD.valid_until_order IS NOT NULL
              OR NEW.record_status<>'CLOSED' OR NEW.valid_until_order IS NULL OR NEW.valid_until_order<=OLD.valid_from_order
              OR NEW.record_version<>OLD.record_version+1
              OR (NEW.closed_by_event_uid IS NOT NULL AND length(trim(NEW.closed_by_event_uid))=0)
              OR NEW.closure_provenance IS NULL OR length(trim(NEW.closure_provenance))=0
            BEGIN SELECT RAISE(ABORT,'ownership record mutation is not a legal close transition'); END
        """.trimIndent())

        saveDb.execSQL("""
            CREATE TRIGGER trg_ownership_history_delete_guard
            BEFORE DELETE ON ownership_records
            BEGIN SELECT RAISE(ABORT,'ownership history is append-preserved and cannot be deleted'); END
        """.trimIndent())

        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE12_MIGRATION_ID',strftime('%s','now'),'Generic temporal OwnershipRecord history with exact fixed-scale shares, campaign-scoped owner/asset reference registries, explicit ItemInstance target validation, DB temporal/share/reference guards, immutable-history close CAS, idempotent operation ledger and zero legacy possession/equipment synthesis')")
        saveDb.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE12_OPTIONAL_SOURCE_EVENT_MIGRATION_ID',strftime('%s','now'),'Ownership transfer/close preserves UNKNOWN_NOT_RECORDED provenance by allowing absent source/closure Event UID while retaining legal temporal close/open invariants')")
        saveDb.setTransactionSuccessful()
    } finally {
        saveDb.endTransaction()
    }
}

private fun repairOwnershipOperationSourceEventNullability(db:SQLiteDatabase){
    val sourceEventRequired=db.rawQuery("PRAGMA table_info(ownership_operations)",null).use{cursor->
        var required=false
        while(cursor.moveToNext()){
            if(cursor.getString(1)=="source_event_uid"){
                required=cursor.getInt(3)==1
                break
            }
        }
        required
    }
    if(!sourceEventRequired)return
    val gameplayGuardsWereInstalled=db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='rpgos_gameplay_mutation_context' LIMIT 1",null
    ).use{it.moveToFirst()}
    listOf("insert","update","delete").forEach{op->db.execSQL("DROP TRIGGER IF EXISTS rpgos_guard_ownership_operations_$op")}
    db.execSQL("ALTER TABLE ownership_operations RENAME TO ownership_operations_pre_work023")
    db.execSQL("""
        CREATE TABLE ownership_operations(
            campaign_id TEXT NOT NULL,
            operation_uid TEXT NOT NULL,
            operation_kind TEXT NOT NULL CHECK(operation_kind IN ('TRANSFER','CLOSE')),
            asset_kind_uid TEXT NOT NULL,
            asset_uid TEXT NOT NULL,
            ownership_type_uid TEXT NOT NULL,
            source_record_uid TEXT NOT NULL,
            source_successor_uid TEXT,
            destination_successor_uid TEXT,
            effective_order INTEGER NOT NULL,
            source_event_uid TEXT CHECK(source_event_uid IS NULL OR length(trim(source_event_uid)) > 0),
            provenance TEXT NOT NULL CHECK(length(trim(provenance)) > 0),
            PRIMARY KEY(campaign_id,operation_uid),
            FOREIGN KEY(campaign_id,source_record_uid) REFERENCES ownership_records(campaign_id,ownership_record_uid),
            FOREIGN KEY(campaign_id,source_successor_uid) REFERENCES ownership_records(campaign_id,ownership_record_uid),
            FOREIGN KEY(campaign_id,destination_successor_uid) REFERENCES ownership_records(campaign_id,ownership_record_uid))
    """.trimIndent())
    db.execSQL("""
        INSERT INTO ownership_operations(
            campaign_id,operation_uid,operation_kind,asset_kind_uid,asset_uid,ownership_type_uid,
            source_record_uid,source_successor_uid,destination_successor_uid,effective_order,source_event_uid,provenance)
        SELECT campaign_id,operation_uid,operation_kind,asset_kind_uid,asset_uid,ownership_type_uid,
            source_record_uid,source_successor_uid,destination_successor_uid,effective_order,source_event_uid,provenance
        FROM ownership_operations_pre_work023
    """.trimIndent())
    db.execSQL("DROP TABLE ownership_operations_pre_work023")
    if(gameplayGuardsWereInstalled)GameplayMutationDatabaseGuards.ensureInstalled(db)
}
