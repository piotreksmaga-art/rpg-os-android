from pathlib import Path

def rep(path,old,new):
 p=Path(path);s=p.read_text();n=s.count(old)
 if n!=1: raise SystemExit(f'{path}: expected 1 match got {n}')
 p.write_text(s.replace(old,new))

p='app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt'
marker='''internal data class CommittedReplayPayload(
'''
policy=r'''internal object CampaignSnapshotRecoveryPolicy {
    private val excludedKinds=setOf(SnapshotKind.MANUAL_EXPORT,SnapshotKind.LEGACY_BACKUP)

    fun isRecoverable(db:SQLiteDatabase,campaignUid:String,snapshot:CampaignSnapshotDescriptor):Boolean =
        runCatching{requireRecoverable(db,campaignUid,snapshot)}.isSuccess

    fun requireRecoverableSafetySnapshot(db:SQLiteDatabase,campaignUid:String,snapshotUid:String):SafetySnapshotIdentity {
        val snapshot=find(db,campaignUid,snapshotUid)?:error("RPGOS-SCHEMA:SAFETY_SNAPSHOT_NOT_FOUND")
        require(snapshot.kind!=SnapshotKind.AUTOMATIC||snapshot.pinned){"RPGOS-SCHEMA:SAFETY_SNAPSHOT_NOT_PROTECTED"}
        requireRecoverable(db,campaignUid,snapshot)
        return SafetySnapshotIdentity(snapshot.snapshotUid,snapshot.payloadPath,requireNotNull(snapshot.payloadSha256))
    }

    fun requireRecoverable(db:SQLiteDatabase,campaignUid:String,snapshot:CampaignSnapshotDescriptor):List<CommittedReplayPayload> {
        require(snapshot.campaignUid==campaignUid){"RPGOS-SNAPSHOT:CROSS_CAMPAIGN_SNAPSHOT"}
        require(snapshot.kind !in excludedKinds){"RPGOS-SNAPSHOT:KIND_NOT_RECOVERABLE"}
        require(snapshot.state==SnapshotPublicationState.VALID&&snapshot.schemaVersion==CampaignSnapshotSchema.VERSION){"RPGOS-SNAPSHOT:SNAPSHOT_NOT_VALID"}
        val file=File(snapshot.payloadPath);val expectedSha=snapshot.payloadSha256
        require(file.isFile&&expectedSha!=null&&fileSha256(file)==expectedSha){"RPGOS-SNAPSHOT:PAYLOAD_INTEGRITY_FAILED"}
        SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{captured->
            check(captured.isDatabaseIntegrityOk){"RPGOS-SNAPSHOT:PAYLOAD_DB_INTEGRITY_FAILED"}
            val anchor=TurnTransactionReceiptStore(captured).lastValidCommit(campaignUid)
            val order=anchor?.commitOrder?:0L
            require(order==snapshot.anchorCommitOrder){"RPGOS-SNAPSHOT:ANCHOR_ORDER_MISMATCH"}
            require(anchor?.transactionUid==snapshot.anchorTransactionUid&&anchor?.turnUid==snapshot.anchorTurnUid){"RPGOS-SNAPSHOT:ANCHOR_IDENTITY_MISMATCH"}
            val eventUid=anchor?.transactionUid?.let{tx->anchorEventUid(captured,campaignUid,tx)}
            require(eventUid==snapshot.anchorEventUid){"RPGOS-SNAPSHOT:ANCHOR_EVENT_MISMATCH"}
        }
        val last=TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder?:0L
        require(snapshot.anchorCommitOrder<=last){"RPGOS-SNAPSHOT:STALE_SNAPSHOT_ANCHOR"}
        val payloads=CommittedReplayPayloadStore(db).after(campaignUid,snapshot.anchorCommitOrder)
        val expected=if(snapshot.anchorCommitOrder==last)emptyList() else (snapshot.anchorCommitOrder+1..last).toList()
        require(payloads.map{it.commitOrder}==expected){"RPGOS-SNAPSHOT:NON_REPLAYABLE_INTERVAL"}
        return payloads
    }

    private fun find(db:SQLiteDatabase,campaignUid:String,uid:String):CampaignSnapshotDescriptor? {
        if(!CampaignSnapshotSchema.isReady(db))return null
        return db.rawQuery("""SELECT snapshot_uid,snapshot_kind,snapshot_schema_version,created_order,created_at_epoch_ms,anchor_commit_order,
            anchor_transaction_uid,anchor_turn_uid,anchor_event_uid,payload_path,payload_sha256,publication_state,pinned
            FROM ${CampaignSnapshotSchema.CATALOG} WHERE campaign_uid=? AND snapshot_uid=? LIMIT 1""",arrayOf(campaignUid,uid)).use{c->
            if(!c.moveToFirst())null else CampaignSnapshotDescriptor(c.getString(0),campaignUid,SnapshotKind.valueOf(c.getString(1)),c.getInt(2),c.getLong(3),c.getLong(4),c.getLong(5),if(c.isNull(6))null else c.getString(6),if(c.isNull(7))null else c.getString(7),if(c.isNull(8))null else c.getString(8),c.getString(9),if(c.isNull(10))null else c.getString(10),SnapshotPublicationState.valueOf(c.getString(11)),c.getInt(12)!=0)
        }
    }

    internal fun anchorEventUid(db:SQLiteDatabase,campaignUid:String,transactionUid:String):String? {
        val table=CampaignIntelligencePhase30Schema.EVENT_TABLE
        val exists=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()}
        if(!exists)return null
        val hasOrdinal=db.rawQuery("PRAGMA table_info($table)",null).use{c->var found=false;while(c.moveToNext())if(c.getString(1)=="event_ordinal")found=true;found}
        val order=if(hasOrdinal)"event_ordinal DESC,event_intent_uid DESC" else "event_intent_uid DESC"
        return db.rawQuery("SELECT event_uid FROM $table WHERE campaign_uid=? AND transaction_uid=? ORDER BY $order LIMIT 1",arrayOf(campaignUid,transactionUid)).use{c->if(c.moveToFirst())c.getString(0)else null}
    }
}

'''
rep(p,marker,policy+marker)
rep(p,'snapshotDir.mkdirs(); reconcileOrphans();val effectivePinned','snapshotDir.mkdirs(); reconcileOrphansLocked();val effectivePinned')
rep(p,'eventUid=receipt?.transactionUid?.let{tx->CampaignEventStore(capturedDb,campaignUid).eventsForTransaction(tx).lastOrNull()?.eventUid}','eventUid=receipt?.transactionUid?.let{tx->CampaignSnapshotRecoveryPolicy.anchorEventUid(capturedDb,campaignUid,tx)}')
rep(p,'if(kind==SnapshotKind.AUTOMATIC&&!effectivePinned)pruneAutomatic()','if(kind==SnapshotKind.AUTOMATIC&&!effectivePinned)pruneAutomaticLocked()')
rep(p,'''    fun latestValidCompatible():CampaignSnapshotDescriptor?=list().firstOrNull{it.kind!=SnapshotKind.LEGACY_BACKUP&&it.kind!=SnapshotKind.MANUAL_EXPORT&&it.state==SnapshotPublicationState.VALID&&it.schemaVersion==CampaignSnapshotSchema.VERSION&&File(it.payloadPath).isFile&&fileSha256(File(it.payloadPath))==it.payloadSha256}
    fun delete(uid:String):Boolean {
        if(uid in Phase36SchemaVersioning.activeSafetySnapshotUids(db,campaignUid)) return false
        val s=find(uid)?:return false;val file=File(s.payloadPath);if(file.exists()&&!file.delete())return false
        db.delete(CampaignSnapshotSchema.CATALOG,"snapshot_uid=? AND campaign_uid=?",arrayOf(uid,campaignUid));return true
    }
    fun pruneAutomatic(){
        val protected=Phase36SchemaVersioning.activeSafetySnapshotUids(db,campaignUid)
        val eligible=list().filter{it.kind==SnapshotKind.AUTOMATIC&&!it.pinned&&it.state==SnapshotPublicationState.VALID&&it.snapshotUid !in protected}
        eligible.drop(AUTOMATIC_RETENTION).forEach{delete(it.snapshotUid)}
    }
    fun reconcileOrphans(){
        if(!CampaignSnapshotSchema.isReady(db))return
        val catalog=list()
        catalog.filter{it.state==SnapshotPublicationState.STAGED||it.state==SnapshotPublicationState.VALID&&(!File(it.payloadPath).isFile||fileSha256(File(it.payloadPath))!=it.payloadSha256)}.forEach{
            db.execSQL("UPDATE ${CampaignSnapshotSchema.CATALOG} SET publication_state=? WHERE snapshot_uid=?",arrayOf(SnapshotPublicationState.INVALID.name,it.snapshotUid))
        }
        val campaignFilePrefix="SNAP-$campaignUid-"
        snapshotDir.listFiles{f->
            f.name.startsWith(".$campaignFilePrefix")&&(f.name.endsWith(".staged.db")||f.name.endsWith(".reconstructing.db"))
        }?.forEach{it.delete()}
        val known=db.rawQuery("SELECT payload_path FROM ${CampaignSnapshotSchema.CATALOG}",null).use{c->buildSet{
            while(c.moveToNext())add(File(c.getString(0)).canonicalFile)
        }}
        snapshotDir.listFiles{f->
            f.isFile&&f.name.startsWith(campaignFilePrefix)&&f.extension=="db"&&f.canonicalFile !in known
        }?.forEach{it.delete()}
    }
''',r'''    fun latestValidCompatible():CampaignSnapshotDescriptor?=list().firstOrNull{CampaignSnapshotRecoveryPolicy.isRecoverable(db,campaignUid,it)}
    fun delete(uid:String):Boolean=CampaignRuntimeLifecycleLock.withRecovery(campaignUid){deleteLocked(uid)}
    private fun deleteLocked(uid:String):Boolean {
        if(uid in Phase36SchemaVersioning.activeSafetySnapshotUids(db,campaignUid))return false
        val s=find(uid)?:return false;val file=File(s.payloadPath);if(file.exists()&&!file.delete())return false
        db.delete(CampaignSnapshotSchema.CATALOG,"snapshot_uid=? AND campaign_uid=?",arrayOf(uid,campaignUid));return true
    }
    fun pruneAutomatic()=CampaignRuntimeLifecycleLock.withRecovery(campaignUid){pruneAutomaticLocked()}
    private fun pruneAutomaticLocked(){
        val protected=Phase36SchemaVersioning.activeSafetySnapshotUids(db,campaignUid)
        val eligible=list().filter{it.kind==SnapshotKind.AUTOMATIC&&!it.pinned&&it.state==SnapshotPublicationState.VALID&&it.snapshotUid !in protected}
        eligible.drop(AUTOMATIC_RETENTION).forEach{deleteLocked(it.snapshotUid)}
    }
    fun reconcileOrphans()=CampaignRuntimeLifecycleLock.withRecovery(campaignUid){reconcileOrphansLocked()}
    private fun reconcileOrphansLocked(){
        if(!CampaignSnapshotSchema.isReady(db))return
        val protected=Phase36SchemaVersioning.activeSafetySnapshotUids(db,campaignUid)
        val catalog=list()
        catalog.filter{it.snapshotUid !in protected&&(it.state==SnapshotPublicationState.STAGED||it.state==SnapshotPublicationState.VALID&&(!File(it.payloadPath).isFile||fileSha256(File(it.payloadPath))!=it.payloadSha256))}.forEach{
            db.execSQL("UPDATE ${CampaignSnapshotSchema.CATALOG} SET publication_state=? WHERE snapshot_uid=?",arrayOf(SnapshotPublicationState.INVALID.name,it.snapshotUid))
        }
        val campaignFilePrefix="SNAP-$campaignUid-"
        snapshotDir.listFiles{f->f.name.startsWith(".$campaignFilePrefix")&&(f.name.endsWith(".staged.db")||f.name.endsWith(".reconstructing.db"))}?.forEach{it.delete()}
        val known=db.rawQuery("SELECT payload_path FROM ${CampaignSnapshotSchema.CATALOG}",null).use{c->buildSet{while(c.moveToNext())add(File(c.getString(0)).canonicalFile)}}
        snapshotDir.listFiles{f->f.isFile&&f.name.startsWith(campaignFilePrefix)&&f.extension=="db"&&f.canonicalFile !in known}?.forEach{it.delete()}
    }
''')
rep(p,'''        reconcileOrphans()
        val snapshot=list().firstOrNull { it.kind!=SnapshotKind.LEGACY_BACKUP&&it.kind!=SnapshotKind.MANUAL_EXPORT&&it.state==SnapshotPublicationState.VALID&&it.schemaVersion==CampaignSnapshotSchema.VERSION&&
            File(it.payloadPath).isFile&&fileSha256(File(it.payloadPath))==it.payloadSha256 }
            ?:error("RPGOS-SNAPSHOT:NO_VALID_COMPATIBLE_SNAPSHOT")
''','''        reconcileOrphansLocked()
        val snapshot=list().firstOrNull{CampaignSnapshotRecoveryPolicy.isRecoverable(db,campaignUid,it)}
            ?:error("RPGOS-SNAPSHOT:NO_VALID_COMPATIBLE_SNAPSHOT")
''')
rep(p,'''        val payloads=CommittedReplayPayloadStore(db).after(campaignUid,snapshot.anchorCommitOrder)
        val last=TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder?:0L
        val expected=(snapshot.anchorCommitOrder+1..last).toList()
        require(payloads.map{it.commitOrder}==expected){"RPGOS-SNAPSHOT:NON_REPLAYABLE_INTERVAL"}
''','''        val payloads=CampaignSnapshotRecoveryPolicy.requireRecoverable(db,campaignUid,snapshot)
''')
rep(p,'Phase36SchemaVersioning.ensureReady(target,campaignUid)','Phase36SchemaVersioning.ensureReadyForRecoveryStaging(target,campaignUid)')
print('Phase36 snapshot/recovery patched')
