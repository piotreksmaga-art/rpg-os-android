package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class SnapshotKind { AUTOMATIC, MANUAL_BACKUP, MANUAL_EXPORT, PRE_RESTORE, USER_PINNED, LEGACY_BACKUP }
enum class SnapshotPublicationState { STAGED, VALID, INVALID }
enum class ReplayAuthorityCoverage { REPLAYABLE, NON_REPLAYABLE_FAIL_CLOSED }

/** Closed Phase33 inventory of authority reachable through the current typed TurnTransaction applier. */
object CampaignReplayAuthorityMatrix {
    val replayableFamilyUids:Set<String> = setOf(
        "CAMPAIGN_TRUTH","CANON_DIVERGENCE","BASE_STATS_RESOURCES","SKILLS_TECHNIQUES","INVENTORY","EQUIPMENT_LOADOUT",
        "OWNERSHIP_HISTORY","FINANCE_AUTHORITY","DEVELOPMENT_PROJECTS","NPC_KNOWLEDGE_STATE"
    )
    val nonReplayableFamilyUids:Set<String> = RuntimeTruthLayerRegistry.families.filter{it.isAuthoritative}.map{it.uid}.toSet()-replayableFamilyUids
    fun coverage(familyUid:String):ReplayAuthorityCoverage { val family=RuntimeTruthLayerRegistry.requireFamily(familyUid);require(family.isAuthoritative);return if(familyUid in replayableFamilyUids)ReplayAuthorityCoverage.REPLAYABLE else ReplayAuthorityCoverage.NON_REPLAYABLE_FAIL_CLOSED }
    fun validateComplete(){val authority=RuntimeTruthLayerRegistry.families.filter{it.isAuthoritative}.map{it.uid}.toSet();require((replayableFamilyUids+nonReplayableFamilyUids)==authority);require(replayableFamilyUids.intersect(nonReplayableFamilyUids).isEmpty())}
}

data class CampaignSnapshotDescriptor(
    val snapshotUid: String,
    val campaignUid: String,
    val kind: SnapshotKind,
    val schemaVersion: Int,
    val createdOrder: Long,
    val createdAtEpochMs: Long,
    val anchorCommitOrder: Long,
    val anchorTransactionUid: String?,
    val anchorTurnUid: String?,
    val anchorEventUid: String?,
    val payloadPath: String,
    val payloadSha256: String?,
    val state: SnapshotPublicationState,
    val pinned: Boolean
)

internal object CampaignSnapshotSchema {
    const val VERSION = 1
    const val CATALOG = "campaign_snapshots"
    const val REPLAY = "canonical_turn_replay_payloads"

    fun ensureReady(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $CATALOG(
            snapshot_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,snapshot_kind TEXT NOT NULL,
            snapshot_schema_version INTEGER NOT NULL,created_order INTEGER NOT NULL,created_at_epoch_ms INTEGER NOT NULL,
            anchor_commit_order INTEGER NOT NULL,anchor_transaction_uid TEXT,anchor_turn_uid TEXT,anchor_event_uid TEXT,
            payload_path TEXT NOT NULL,payload_sha256 TEXT,publication_state TEXT NOT NULL,pinned INTEGER NOT NULL CHECK(pinned IN (0,1)),
            UNIQUE(campaign_uid,created_order))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_campaign_snapshots_selection ON $CATALOG(campaign_uid,publication_state,snapshot_kind,pinned,created_order DESC,snapshot_uid DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $REPLAY(
            transaction_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,turn_uid TEXT NOT NULL,command_uid TEXT NOT NULL,
            commit_order INTEGER NOT NULL,semantic_fingerprint TEXT NOT NULL,required_event_count INTEGER NOT NULL,
            required_event_manifest_fingerprint TEXT NOT NULL,event_boundary_uid TEXT,replay_schema_version INTEGER NOT NULL,
            player_change_set_json TEXT NOT NULL,causal_plan_json TEXT NOT NULL,payload_sha256 TEXT NOT NULL,
            UNIQUE(campaign_uid,commit_order))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_turn_replay_order ON $REPLAY(campaign_uid,commit_order)")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rpgos_replay_no_update BEFORE UPDATE ON $REPLAY
            BEGIN SELECT RAISE(ABORT,'RPGOS-SNAPSHOT:REPLAY_APPEND_ONLY'); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rpgos_replay_no_delete BEFORE DELETE ON $REPLAY
            BEGIN SELECT RAISE(ABORT,'RPGOS-SNAPSHOT:REPLAY_APPEND_ONLY'); END""")
    }

    fun isReady(db: SQLiteDatabase): Boolean = listOf(CATALOG, REPLAY).all { table ->
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
    }
}

internal data class CommittedReplayPayload(
    val identity: TurnTransactionIdentity,
    val commitOrder: Long,
    val semanticFingerprint: String,
    val eventManifest: RequiredEventManifestSummary,
    val eventBoundaryUid: String?,
    val replaySchemaVersion: Int,
    val changeSet: PlayerChangeSet,
    val causalPlan: List<CanonicalCausalRelationIntent>,
    val payloadSha256: String
)

internal class CommittedReplayPayloadStore(private val db: SQLiteDatabase) {
    fun append(
        identity: TurnTransactionIdentity,
        commitOrder: Long,
        semanticFingerprint: String,
        manifest: RequiredEventManifestSummary,
        changeSet: PlayerChangeSet,
        causalPlan: List<CanonicalCausalRelationIntent>,
        eventBoundaryUid: String?
    ) {
        check(db.inTransaction()) { "RPGOS-SNAPSHOT:REPLAY_OUTSIDE_TURN" }
        check(CampaignSnapshotSchema.isReady(db)) { "RPGOS-SNAPSHOT:SCHEMA_NOT_READY" }
        val changeJson = PlayerChangeSetCodec.encode(changeSet)
        val causalJson = encodeCausalPlan(causalPlan)
        val digest = sha256(replayCanonical(identity, commitOrder, semanticFingerprint, manifest, eventBoundaryUid, changeJson, causalJson))
        existingDigest(identity.transactionUid)?.let {
            require(it == digest) { "RPGOS-SNAPSHOT:REPLAY_IDENTITY_CONFLICT" }
            return
        }
        db.execSQL("""INSERT INTO ${CampaignSnapshotSchema.REPLAY}(
            transaction_uid,campaign_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
            required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,payload_sha256)
            VALUES(?,?,?,?,?,?,?,?,?,1,?,?,?)""", arrayOf(identity.transactionUid,identity.campaignUid,identity.turnUid,identity.commandUid,
            commitOrder,semanticFingerprint,manifest.requiredEventCount,manifest.orderedManifestFingerprint,eventBoundaryUid,changeJson,causalJson,digest))
    }

    fun after(campaignUid: String, commitOrder: Long): List<CommittedReplayPayload> = db.rawQuery(
        """SELECT transaction_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
            required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,payload_sha256
            FROM ${CampaignSnapshotSchema.REPLAY} WHERE campaign_uid=? AND commit_order>? ORDER BY commit_order""",
        arrayOf(campaignUid,commitOrder.toString())
    ).use { c -> buildList { while(c.moveToNext()) { val payload=decode(campaignUid,c);require(valid(payload)){"RPGOS-SNAPSHOT:REPLAY_PAYLOAD_DIGEST_MISMATCH"};add(payload) } } }

    fun existingDigest(transactionUid: String): String? = db.rawQuery(
        "SELECT payload_sha256 FROM ${CampaignSnapshotSchema.REPLAY} WHERE transaction_uid=?", arrayOf(transactionUid)
    ).use { c -> if(!c.moveToFirst()) null else c.getString(0) }

    private fun decode(campaignUid:String,c:android.database.Cursor):CommittedReplayPayload {
        val version=c.getInt(8)
        require(version==1){"RPGOS-SNAPSHOT:UNSUPPORTED_REPLAY_SCHEMA:$version"}
        return CommittedReplayPayload(
            TurnTransactionIdentity(campaignUid,c.getString(1),c.getString(2),c.getString(0)),c.getLong(3),c.getString(4),
            RequiredEventManifestSummary(c.getInt(5),c.getString(6)),if(c.isNull(7))null else c.getString(7),
            version,PlayerChangeSetCodec.decode(c.getString(9)),decodeCausalPlan(c.getString(10)),c.getString(11)
        )
    }
    private fun valid(p:CommittedReplayPayload):Boolean { val change=PlayerChangeSetCodec.encode(p.changeSet);val causal=encodeCausalPlan(p.causalPlan);return p.payloadSha256==sha256(replayCanonical(p.identity,p.commitOrder,p.semanticFingerprint,p.eventManifest,p.eventBoundaryUid,change,causal)) }
    companion object {
        fun encodeCausalPlan(plan:List<CanonicalCausalRelationIntent>):String = JSONArray().apply { plan.forEach { p -> put(JSONObject().apply {
            put("relationIntentUid",p.relationIntentUid);put("relationClass",p.relationClass.name);put("relationKindUid",p.relationKindUid)
            put("sourceEventUid",p.sourceEventUid);put("targetEventUid",p.targetEventUid);put("evidenceEventUids",JSONArray(p.evidenceEventUids))
            put("provenanceEventUids",JSONArray(p.provenanceEventUids));if(p.supersedesRelationUid!=null)put("supersedesRelationUid",p.supersedesRelationUid)
        }) } }.toString()
        fun decodeCausalPlan(json:String):List<CanonicalCausalRelationIntent>{ val a=JSONArray(json);return (0 until a.length()).map { i -> val o=a.getJSONObject(i);CanonicalCausalRelationIntent(
            o.getString("relationIntentUid"),CausalRelationClass.valueOf(o.getString("relationClass")),o.getString("relationKindUid"),
            o.getString("sourceEventUid"),o.getString("targetEventUid"),o.getJSONArray("evidenceEventUids").strings(),
            o.getJSONArray("provenanceEventUids").strings(),if(o.has("supersedesRelationUid"))o.getString("supersedesRelationUid") else null) } }
        private fun JSONArray.strings()=(0 until length()).map{getString(it)}
        private fun replayCanonical(i:TurnTransactionIdentity,o:Long,f:String,m:RequiredEventManifestSummary,e:String?,c:String,p:String)=
            listOf("v=1",i.campaignUid,i.transactionUid,i.commandUid,i.turnUid,o.toString(),f,m.requiredEventCount.toString(),m.orderedManifestFingerprint,e?:"",c,p).joinToString("\u001f")
    }
}

class CampaignSnapshotManager(private val db:SQLiteDatabase,private val campaignUid:String,private val snapshotDir:File) {
    companion object { const val AUTOMATIC_RETENTION=6 }
    init { require(campaignUid.isNotBlank()) }

    fun create(kind:SnapshotKind=SnapshotKind.AUTOMATIC,pinned:Boolean=kind==SnapshotKind.USER_PINNED):CampaignSnapshotDescriptor {
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { createLocked(kind,pinned) }
    }

    private fun createLocked(kind:SnapshotKind,pinned:Boolean):CampaignSnapshotDescriptor {
        check(CampaignSnapshotSchema.isReady(db)) { "RPGOS-SNAPSHOT:SCHEMA_NOT_READY" }
        snapshotDir.mkdirs(); reconcileOrphansLocked();val effectivePinned=pinned||kind==SnapshotKind.USER_PINNED
        val order=db.rawQuery("SELECT COALESCE(MAX(created_order),0)+1 FROM ${CampaignSnapshotSchema.CATALOG} WHERE campaign_uid=?",arrayOf(campaignUid)).use{it.moveToFirst();it.getLong(0)}
        val uid="SNAP-$campaignUid-$order-${UUID.randomUUID()}";val staged=File(snapshotDir,".$uid.staged.db");val published=File(snapshotDir,"$uid.db")
        db.execSQL("""INSERT INTO ${CampaignSnapshotSchema.CATALOG}(snapshot_uid,campaign_uid,snapshot_kind,snapshot_schema_version,created_order,
            created_at_epoch_ms,anchor_commit_order,anchor_transaction_uid,anchor_turn_uid,anchor_event_uid,payload_path,payload_sha256,publication_state,pinned)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(uid,campaignUid,kind.name,CampaignSnapshotSchema.VERSION,order,System.currentTimeMillis(),
            0L,null,null,null,published.absolutePath,null,SnapshotPublicationState.STAGED.name,if(effectivePinned)1 else 0))
        try {
            if(staged.exists())staged.delete(); db.execSQL("VACUUM INTO ?",arrayOf(staged.absolutePath));check(staged.isFile)
            val capturedAnchor=SQLiteDatabase.openDatabase(staged.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{capturedDb->
                check(capturedDb.isDatabaseIntegrityOk)
                val receipt=TurnTransactionReceiptStore(capturedDb).lastValidCommit(campaignUid)
                CapturedSnapshotAnchor(
                    commitOrder=receipt?.commitOrder?:0L,
                    transactionUid=receipt?.transactionUid,
                    turnUid=receipt?.turnUid,
                    eventUid=receipt?.transactionUid?.let{tx->CampaignEventStore(capturedDb,campaignUid).eventsForTransaction(tx).lastOrNull()?.eventUid}
                )
            }
            val digest=fileSha256(staged);check(staged.renameTo(published)){"RPGOS-SNAPSHOT:PUBLISH_RENAME_FAILED"}
            db.execSQL("""UPDATE ${CampaignSnapshotSchema.CATALOG}
                SET anchor_commit_order=?,anchor_transaction_uid=?,anchor_turn_uid=?,anchor_event_uid=?,payload_sha256=?,publication_state=?
                WHERE snapshot_uid=?""",arrayOf(capturedAnchor.commitOrder,capturedAnchor.transactionUid,capturedAnchor.turnUid,
                capturedAnchor.eventUid,digest,SnapshotPublicationState.VALID.name,uid))
            if(kind==SnapshotKind.AUTOMATIC&&!effectivePinned)pruneAutomaticLocked()
            return requireNotNull(find(uid))
        } catch(t:Throwable) {
            staged.delete();published.delete();db.execSQL("UPDATE ${CampaignSnapshotSchema.CATALOG} SET publication_state=? WHERE snapshot_uid=?",arrayOf(SnapshotPublicationState.INVALID.name,uid));throw t
        }
    }

    fun list():List<CampaignSnapshotDescriptor> { check(CampaignSnapshotSchema.isReady(db));return db.rawQuery("""SELECT snapshot_uid,snapshot_kind,snapshot_schema_version,created_order,created_at_epoch_ms,
        anchor_commit_order,anchor_transaction_uid,anchor_turn_uid,anchor_event_uid,payload_path,payload_sha256,publication_state,pinned
        FROM ${CampaignSnapshotSchema.CATALOG} WHERE campaign_uid=? ORDER BY created_order DESC,snapshot_uid DESC""",arrayOf(campaignUid)).use{c->buildList{while(c.moveToNext())add(row(c))}} }

    fun latestValidCompatible():CampaignSnapshotDescriptor? = RecoverableSnapshotPolicy.latestRecoverable(db,campaignUid)

    fun delete(uid:String):Boolean {
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { deleteLocked(uid) }
    }

    private fun deleteLocked(uid:String):Boolean {
        if(uid in Phase36SchemaVersioning.activeSafetySnapshotUids(db,campaignUid)) return false
        val s=find(uid)?:return false
        val file=File(s.payloadPath)
        if(file.exists()&&!file.delete())return false
        db.delete(CampaignSnapshotSchema.CATALOG,"snapshot_uid=? AND campaign_uid=?",arrayOf(uid,campaignUid))
        return true
    }

    fun pruneAutomatic(){
        requireAdministrativeRecoveryEntryPoint()
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { pruneAutomaticLocked() }
    }

    private fun pruneAutomaticLocked(){
        val protected=Phase36SchemaVersioning.activeSafetySnapshotUids(db,campaignUid)
        val eligible=list().filter{it.kind==SnapshotKind.AUTOMATIC&&!it.pinned&&it.state==SnapshotPublicationState.VALID&&it.snapshotUid !in protected}
        eligible.drop(AUTOMATIC_RETENTION).forEach{deleteLocked(it.snapshotUid)}
    }

    fun reconcileOrphans(){
        requireAdministrativeRecoveryEntryPoint()
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { reconcileOrphansLocked() }
    }

    private fun reconcileOrphansLocked(){
        if(!CampaignSnapshotSchema.isReady(db))return
        val protected=Phase36SchemaVersioning.activeSafetySnapshotUids(db,campaignUid)
        val catalog=list()
        catalog.filter{it.snapshotUid !in protected && (it.state==SnapshotPublicationState.STAGED||it.state==SnapshotPublicationState.VALID&&(!File(it.payloadPath).isFile||fileSha256(File(it.payloadPath))!=it.payloadSha256))}.forEach{
            db.execSQL("UPDATE ${CampaignSnapshotSchema.CATALOG} SET publication_state=? WHERE snapshot_uid=? AND campaign_uid=?",arrayOf(SnapshotPublicationState.INVALID.name,it.snapshotUid,campaignUid))
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

    private fun find(uid:String)=list().firstOrNull{it.snapshotUid==uid}
    private fun row(c:android.database.Cursor)=CampaignSnapshotDescriptor(c.getString(0),campaignUid,SnapshotKind.valueOf(c.getString(1)),c.getInt(2),c.getLong(3),c.getLong(4),c.getLong(5),if(c.isNull(6))null else c.getString(6),if(c.isNull(7))null else c.getString(7),if(c.isNull(8))null else c.getString(8),c.getString(9),if(c.isNull(10))null else c.getString(10),SnapshotPublicationState.valueOf(c.getString(11)),c.getInt(12)!=0)
    private fun copyCurrentCatalogTo(target:SQLiteDatabase){
        target.beginTransaction();try{target.delete(CampaignSnapshotSchema.CATALOG,"campaign_uid=?",arrayOf(campaignUid));list().forEach{s->target.insertOrThrow(CampaignSnapshotSchema.CATALOG,null,ContentValues().apply{
            put("snapshot_uid",s.snapshotUid);put("campaign_uid",s.campaignUid);put("snapshot_kind",s.kind.name);put("snapshot_schema_version",s.schemaVersion)
            put("created_order",s.createdOrder);put("created_at_epoch_ms",s.createdAtEpochMs);put("anchor_commit_order",s.anchorCommitOrder)
            if(s.anchorTransactionUid==null)putNull("anchor_transaction_uid")else put("anchor_transaction_uid",s.anchorTransactionUid)
            if(s.anchorTurnUid==null)putNull("anchor_turn_uid")else put("anchor_turn_uid",s.anchorTurnUid)
            if(s.anchorEventUid==null)putNull("anchor_event_uid")else put("anchor_event_uid",s.anchorEventUid)
            put("payload_path",s.payloadPath);if(s.payloadSha256==null)putNull("payload_sha256")else put("payload_sha256",s.payloadSha256)
            put("publication_state",s.state.name);put("pinned",if(s.pinned)1 else 0)
        })};target.setTransactionSuccessful()}finally{target.endTransaction()}
    }

    fun reconstructToVerifiedStaging(snapshotUid:String?=null):File {
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { reconstructLocked(snapshotUid) }
    }

    private fun reconstructLocked(snapshotUid:String?):File {
        reconcileOrphansLocked()
        val snapshot = if(snapshotUid==null) {
            RecoverableSnapshotPolicy.latestRecoverable(db,campaignUid)
                ?: error("RPGOS-SNAPSHOT:NO_VALID_COMPATIBLE_SNAPSHOT")
        } else {
            RecoverableSnapshotPolicy.requireRecoverable(db,campaignUid,snapshotUid)
        }
        val staging=File(snapshotDir,".${snapshot.snapshotUid}.reconstructing.db")
        if(staging.exists())staging.delete();File(snapshot.payloadPath).copyTo(staging)
        val payloads=CommittedReplayPayloadStore(db).after(campaignUid,snapshot.anchorCommitOrder)
        val last=TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder?:0L
        val expected=(snapshot.anchorCommitOrder+1..last).toList()
        require(payloads.map{it.commitOrder}==expected){"RPGOS-SNAPSHOT:NON_REPLAYABLE_INTERVAL"}
        try {
            SQLiteDatabase.openDatabase(staging.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { target ->
                check(target.isDatabaseIntegrityOk){"RPGOS-SNAPSHOT:STAGING_INTEGRITY_FAILED"}
                Phase36SchemaVersioning.ensureReady(target,campaignUid)
                GameplayRuntimeBootstrap.initialize(target,campaignUid)
                payloads.forEach { replayCommittedTransaction(target,it) }
                GameplayRuntimeBootstrap.requireReady(target,campaignUid)
                require(AuthoritativeStateDigest.compute(target)==AuthoritativeStateDigest.compute(db)){"RPGOS-SNAPSHOT:AUTHORITATIVE_DIGEST_MISMATCH"}
                listOf("turn_transaction_receipts","canonical_gameplay_events","canonical_causal_relations","canonical_turn_replay_payloads").forEach { table ->
                    require(TableDigest.compute(target,table)==TableDigest.compute(db,table)){"RPGOS-SNAPSHOT:EVIDENCE_DIGEST_MISMATCH:$table"}
                }
                copyCurrentCatalogTo(target)
            }
            return staging
        }catch(t:Throwable){staging.delete();throw t}
    }

    fun activateVerifiedStaging(activeDbFile:File,verifiedStaging:File):File {
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid) {
            require(verifiedStaging.isFile&&verifiedStaging.parentFile?.canonicalFile==snapshotDir.canonicalFile)
            check(db.isOpen){"RPGOS-SNAPSHOT:ACTIVE_DB_NOT_OPEN_FOR_FRESHNESS_CHECK"}
            SQLiteDatabase.openDatabase(verifiedStaging.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{stagedDb->
                check(stagedDb.isDatabaseIntegrityOk)
                require(AuthoritativeStateDigest.compute(stagedDb)==AuthoritativeStateDigest.compute(db)){"RPGOS-SNAPSHOT:STALE_VERIFIED_STAGING:AUTHORITY"}
                require(Phase36SchemaCompatibilityFingerprint.compute(stagedDb)==Phase36SchemaCompatibilityFingerprint.compute(db)){
                    "RPGOS-SNAPSHOT:STALE_VERIFIED_STAGING:SCHEMA_VECTOR"
                }
                listOf(
                    "turn_transaction_receipts","canonical_gameplay_events","canonical_causal_relations","canonical_turn_replay_payloads",
                    CampaignSnapshotSchema.CATALOG
                ).forEach { table ->
                    require(TableDigest.compute(stagedDb,table)==TableDigest.compute(db,table)){"RPGOS-SNAPSHOT:STALE_VERIFIED_STAGING:$table"}
                }
            }
            val rollback=File(activeDbFile.parentFile,".${activeDbFile.name}.before_snapshot_activation")
            if(rollback.exists())rollback.delete();db.close()
            check(activeDbFile.renameTo(rollback)){"RPGOS-SNAPSHOT:ACTIVE_STAGE_RENAME_FAILED"}
            try {
                check(verifiedStaging.renameTo(activeDbFile)){"RPGOS-SNAPSHOT:STAGING_ACTIVATION_FAILED"}
                rollback.delete();activeDbFile
            } catch(t:Throwable) {
                activeDbFile.delete();rollback.renameTo(activeDbFile);throw t
            }
        }
    }
}

private data class CapturedSnapshotAnchor(
    val commitOrder:Long,
    val transactionUid:String?,
    val turnUid:String?,
    val eventUid:String?
)

internal object AuthoritativeStateDigest {
    fun compute(db:SQLiteDatabase):String { val md=MessageDigest.getInstance("SHA-256");RuntimeTruthLayerRegistry.authoritativePersistentTables().filter{tableExists(db,it)}.sorted().forEach{t->
        val columns=db.rawQuery("PRAGMA table_info(`$t`)",null).use{c->buildList{while(c.moveToNext())add(c.getString(1))}}
        md.update("T:$t:${columns.joinToString(",")}\n".toByteArray());val order=columns.joinToString(","){"`$it`"}
        db.rawQuery("SELECT $order FROM `$t` ORDER BY $order",null).use{c->while(c.moveToNext()){for(i in columns.indices){md.update(when(c.getType(i)){android.database.Cursor.FIELD_TYPE_NULL->"N";android.database.Cursor.FIELD_TYPE_BLOB->"B"+c.getBlob(i).joinToString(""){"%02x".format(it)};else->"V"+c.getString(i)}.toByteArray());md.update(0)}}}
    };return md.digest().joinToString(""){"%02x".format(it)}}
    private fun tableExists(db:SQLiteDatabase,t:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(t)).use{it.moveToFirst()}
}

internal object TableDigest {
    fun compute(db:SQLiteDatabase,table:String):String {
        val md=MessageDigest.getInstance("SHA-256")
        val columns=db.rawQuery("PRAGMA table_info(`$table`)",null).use{c->buildList{while(c.moveToNext())add(c.getString(1))}}
        if(columns.isEmpty())return "MISSING"
        val order=columns.joinToString(","){"`$it`"};db.rawQuery("SELECT $order FROM `$table` ORDER BY $order",null).use{c->while(c.moveToNext()){for(i in columns.indices){md.update(if(c.isNull(i)) byteArrayOf(0) else c.getString(i).toByteArray());md.update(31)}}}
        return md.digest().joinToString(""){"%02x".format(it)}
    }
}

private fun sha256(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}
private fun fileSha256(f:File):String{val md=MessageDigest.getInstance("SHA-256");f.inputStream().use{s->val b=ByteArray(64*1024);while(true){val n=s.read(b);if(n<0)break;md.update(b,0,n)}};return md.digest().joinToString(""){"%02x".format(it)}}
