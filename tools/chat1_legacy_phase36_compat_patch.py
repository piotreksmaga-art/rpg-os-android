from pathlib import Path


def replace_once(path, old, new):
    p=Path(path);s=p.read_text(encoding='utf-8')
    if new in s:return
    n=s.count(old)
    if n!=1:raise SystemExit(f'{path}: expected 1 match, got {n}')
    p.write_text(s.replace(old,new,1),encoding='utf-8')

# Physical schema is the actual source route. If no migration attempt is active, a lower recognized
# physical version may repair stale same-generation metadata before Phase36 plans a new attempt.
replace_once('app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt',
'''        adoptMissingFamilyVersions(db,campaignUid)
        recoverInterrupted(db,campaignUid)
        val source=currentVector(db)''',
'''        adoptMissingFamilyVersions(db,campaignUid)
        if(!hasActiveAttempt(db,campaignUid)) reconcilePhysicalSourceVersions(db,campaignUid)
        recoverInterrupted(db,campaignUid)
        val source=currentVector(db)''')

marker='''    private fun validatePhysicalMetadataConsistency(db:SQLiteDatabase){'''
method='''    private fun hasActiveAttempt(db:SQLiteDatabase,campaignUid:String):Boolean = table(db,ATTEMPTS) && db.rawQuery(
        "SELECT 1 FROM $ATTEMPTS WHERE campaign_uid=? AND state IN (?,?) LIMIT 1",
        arrayOf(campaignUid,MigrationAttemptState.PREPARED.name,MigrationAttemptState.RUNNING.name)
    ).use{it.moveToFirst()}

    private fun reconcilePhysicalSourceVersions(db:SQLiteDatabase,campaignUid:String){
        val physical=mapOf(
            SchemaFamilyUid.EVENT to CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db),
            SchemaFamilyUid.RECEIPT to TurnTransactionReceiptSchema.physicalSchemaVersion(db)
        )
        val corrections=physical.mapNotNull{(family,found)->
            val metadata=currentMetadata(db,family)
            if(found!=null&&metadata!=null&&found<metadata){
                val contract=contracts.single{it.family==family}
                require(found>=contract.minimumSupportedVersion){"RPGOS-SCHEMA:UNSUPPORTED_OLD:$family:$found:${contract.minimumSupportedVersion}"}
                family to found
            }else null
        }
        if(corrections.isEmpty())return
        administrativeWrite(db,campaignUid){corrections.forEach{(family,found)->
            db.execSQL("UPDATE $VERSIONS SET schema_version=?,migration_owner=?,updated_at_epoch_ms=? WHERE schema_family_uid=?",
                arrayOf(found,"RPGOS-P36-PHYSICAL-SOURCE-RECONCILIATION",System.currentTimeMillis(),family.name))
        }}
    }

'''
p=Path('app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt');s=p.read_text(encoding='utf-8')
if method not in s:
    if marker not in s:raise SystemExit('Phase36 physical reconcile marker missing')
    p.write_text(s.replace(marker,method+marker,1),encoding='utf-8')

# Production bootstrap automatically creates a Phase33 PRE_RESTORE safety snapshot for recognized
# material physical legacy edges when the caller did not already supply one.
replace_once('app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt',
'''    fun requireNoUnsupportedFuture(db:SQLiteDatabase)=inspectCompatibilityBeforeMutation(db)
''',
'''    fun requiresMaterialPhysicalMigration(db:SQLiteDatabase):Boolean =
        (TurnTransactionReceiptSchema.physicalSchemaVersion(db)?.let{it<TURN_TRANSACTION_RECEIPT_VERSION}==true) ||
        (CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db)?.let{it<PHASE30_EVENT_SCHEMA_VERSION}==true)

    fun requireNoUnsupportedFuture(db:SQLiteDatabase)=inspectCompatibilityBeforeMutation(db)
''')

replace_once('app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt',
'''package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
''',
'''package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
''')
replace_once('app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt',
'''            check(!db.inTransaction()) { "RPGOS-SCHEMA:PHASE36_REQUIRES_TOP_LEVEL_MIGRATION_BOUNDARY" }
            Phase36SchemaVersioning.ensureReady(db, campaignUid, safetySnapshotUid)
''',
'''            check(!db.inTransaction()) { "RPGOS-SCHEMA:PHASE36_REQUIRES_TOP_LEVEL_MIGRATION_BOUNDARY" }
            val effectiveSafetyUid = safetySnapshotUid ?: if(Phase36SchemaVersioning.requiresMaterialPhysicalMigration(db)) {
                val dbFile=File(db.path)
                val parent=dbFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: ".")
                CampaignSnapshotManager(db,campaignUid,File(parent,"snapshots")).create(SnapshotKind.PRE_RESTORE).snapshotUid
            } else null
            Phase36SchemaVersioning.ensureReady(db, campaignUid, effectiveSafetyUid)
''')

print('legacy Phase36 compatibility repair applied')
