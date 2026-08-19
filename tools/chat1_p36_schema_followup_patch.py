from pathlib import Path

def rep(path,old,new):
 p=Path(path);s=p.read_text();n=s.count(old)
 if n!=1: raise SystemExit(f'{path}: expected 1 match got {n}')
 p.write_text(s.replace(old,new))

p='app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt'
rep(p,'''        CampaignRuntimeLifecycleLock.withRecovery(campaignUid){ensureReadyLocked(db,campaignUid,safetySnapshotUid,injector)}
    }

    private fun ensureReadyLocked(db:SQLiteDatabase,campaignUid:String,safetySnapshotUid:String?,injector:Phase36MigrationFailureInjector){''','''        CampaignRuntimeLifecycleLock.withRecovery(campaignUid){ensureReadyLocked(db,campaignUid,safetySnapshotUid,injector,false)}
    }

    internal fun ensureReadyForRecoveryStaging(db:SQLiteDatabase,campaignUid:String){
        require(!db.inTransaction()){"RPGOS-SCHEMA:TOP_LEVEL_MIGRATION_REQUIRED"}
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid){ensureReadyLocked(db,campaignUid,null,Phase36MigrationFailureInjector.NONE,true)}
    }

    private fun ensureReadyLocked(db:SQLiteDatabase,campaignUid:String,safetySnapshotUid:String?,injector:Phase36MigrationFailureInjector,recoveryStaging:Boolean){''')
rep(p,'''        val firstSafety=MigrationSafetyPolicy.requireProtectedSnapshot(db,campaignUid,plan.orderedEdges,safetySnapshotUid)
''','''        val firstSafety=if(recoveryStaging)null else MigrationSafetyPolicy.requireProtectedSnapshot(db,campaignUid,plan.orderedEdges,safetySnapshotUid)
''')
rep(p,'''        val secondSafety=MigrationSafetyPolicy.requireProtectedSnapshot(db,campaignUid,plan.orderedEdges,safetySnapshotUid)
        require(firstSafety==secondSafety){"RPGOS-SCHEMA:SAFETY_SNAPSHOT_CHANGED"}
''','''        val secondSafety=if(recoveryStaging)null else MigrationSafetyPolicy.requireProtectedSnapshot(db,campaignUid,plan.orderedEdges,safetySnapshotUid)
        if(!recoveryStaging)require(firstSafety==secondSafety){"RPGOS-SCHEMA:SAFETY_SNAPSHOT_CHANGED"}
''')
print('Phase36 recovery staging patched')
