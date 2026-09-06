package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

enum class SnapshotKind { AUTOMATIC, MANUAL_BACKUP, MANUAL_EXPORT, PRE_RESTORE, USER_PINNED, LEGACY_BACKUP, UNDO_BASELINE }
enum class SnapshotPublicationState { STAGED, VALID, INVALID }
enum class ReplayAuthorityCoverage { REPLAYABLE, BASELINE_DIGEST_GUARDED, NON_REPLAYABLE_FAIL_CLOSED }

/** Closed Phase33 inventory of authority reachable through the current typed TurnTransaction applier. */
object CampaignReplayAuthorityMatrix {
    /** Authority families directly mutated by the currently accepted CanonicalPlayerChangeApplier payloads. */
    val replayableFamilyUids:Set<String> = setOf(
        "CAMPAIGN_TRUTH","CANON_DIVERGENCE","BASE_STATS_RESOURCES","SKILLS_TECHNIQUES","INVENTORY","EQUIPMENT_LOADOUT",
        "OWNERSHIP_REFERENCE_STATE","OWNERSHIP_HISTORY","FINANCE_AUTHORITY","ASSET_LIABILITY_AUTHORITY",
        "MECHANICAL_ACTOR_AND_AGGREGATE_STATE","DEVELOPMENT_PROJECTS","NPC_KNOWLEDGE_STATE","ACCESS_AUTHORITY"
    )
    /**
     * These families are carried by the verified baseline and included in the post-commit whole-
     * authority digest. No accepted turn payload mutates them. If that changes, the family must be
     * moved to [replayableFamilyUids] in the same change that adds the new canonical applier.
     */
    val baselineDigestGuardedFamilyUids:Set<String> = setOf(
        "ACTIVE_PLAYER_IDENTITY",
        "PROGRESSION_PROFILES",
        "INNATE_EVOLUTION",
        "MODIFIER_INPUTS",
        "CURRENT_WORLD_AUTHORITY",
        "NARRATIVE_PLANNING_STATE",
        "TEMPORAL_SCHEDULE_STATE"
    )
    val nonReplayableFamilyUids:Set<String> = emptySet()
    fun coverage(familyUid:String):ReplayAuthorityCoverage {
        val family=RuntimeTruthLayerRegistry.requireFamily(familyUid);require(family.isAuthoritative)
        return when(familyUid){
            in replayableFamilyUids->ReplayAuthorityCoverage.REPLAYABLE
            in baselineDigestGuardedFamilyUids->ReplayAuthorityCoverage.BASELINE_DIGEST_GUARDED
            else->ReplayAuthorityCoverage.NON_REPLAYABLE_FAIL_CLOSED
        }
    }
    fun validateComplete(){
        val authority=RuntimeTruthLayerRegistry.families.filter{it.isAuthoritative}.map{it.uid}.toSet()
        val classified=replayableFamilyUids+baselineDigestGuardedFamilyUids+nonReplayableFamilyUids
        require(classified==authority){"RPGOS-UNDO:REPLAY_AUTHORITY_INVENTORY_INCOMPLETE"}
        require(replayableFamilyUids.intersect(baselineDigestGuardedFamilyUids).isEmpty())
        require(nonReplayableFamilyUids.isEmpty()){"RPGOS-UNDO:NON_REPLAYABLE_AUTHORITY:${nonReplayableFamilyUids.sorted().joinToString(",")}"}
    }
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
    val anchorAuthoritativeDigest: String?,
    val payloadPath: String,
    val payloadSha256: String?,
    val state: SnapshotPublicationState,
    val pinned: Boolean
)

internal object CampaignSnapshotSchema {
    // The catalog/payload contract remains v1. Replay envelopes are independently versioned per
    // row, so adding the optional v2 digest columns must not make existing manual backups look as
    // though their payload format was rewritten.
    const val VERSION = 1
    const val CATALOG = "campaign_snapshots"
    const val REPLAY = "canonical_turn_replay_payloads"

    fun ensureReady(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $CATALOG(
            snapshot_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,snapshot_kind TEXT NOT NULL,
            snapshot_schema_version INTEGER NOT NULL,created_order INTEGER NOT NULL,created_at_epoch_ms INTEGER NOT NULL,
            anchor_commit_order INTEGER NOT NULL,anchor_transaction_uid TEXT,anchor_turn_uid TEXT,anchor_event_uid TEXT,
            anchor_authoritative_digest TEXT,
            payload_path TEXT NOT NULL,payload_sha256 TEXT,publication_state TEXT NOT NULL,pinned INTEGER NOT NULL CHECK(pinned IN (0,1)),
            UNIQUE(campaign_uid,created_order))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_campaign_snapshots_selection ON $CATALOG(campaign_uid,publication_state,snapshot_kind,pinned,created_order DESC,snapshot_uid DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $REPLAY(
            transaction_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,turn_uid TEXT NOT NULL,command_uid TEXT NOT NULL,
            commit_order INTEGER NOT NULL,semantic_fingerprint TEXT NOT NULL,required_event_count INTEGER NOT NULL,
            required_event_manifest_fingerprint TEXT NOT NULL,event_boundary_uid TEXT,replay_schema_version INTEGER NOT NULL,
            player_change_set_json TEXT NOT NULL,causal_plan_json TEXT NOT NULL,post_authoritative_digest TEXT,payload_sha256 TEXT NOT NULL,
            UNIQUE(campaign_uid,commit_order))""")
        addColumnIfMissing(db,CATALOG,"anchor_authoritative_digest","TEXT")
        addColumnIfMissing(db,REPLAY,"post_authoritative_digest","TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_turn_replay_order ON $REPLAY(campaign_uid,commit_order)")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rpgos_replay_no_update BEFORE UPDATE ON $REPLAY
            BEGIN SELECT RAISE(ABORT,'RPGOS-SNAPSHOT:REPLAY_APPEND_ONLY'); END""")
        db.execSQL("""CREATE TRIGGER IF NOT EXISTS rpgos_replay_no_delete BEFORE DELETE ON $REPLAY
            BEGIN SELECT RAISE(ABORT,'RPGOS-SNAPSHOT:REPLAY_APPEND_ONLY'); END""")
    }

    fun isReady(db: SQLiteDatabase): Boolean = listOf(CATALOG, REPLAY).all { table ->
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
    }

    private fun addColumnIfMissing(db:SQLiteDatabase,table:String,column:String,declaration:String){
        val exists=db.rawQuery("PRAGMA table_info(`$table`)",null).use{c->
            var found=false
            while(c.moveToNext())if(c.getString(1)==column){found=true;break}
            found
        }
        if(!exists)db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $declaration")
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
    val postAuthoritativeDigest: String?,
    val payloadSha256: String
)

@ConsistentCopyVisibility
data class VerifiedUndoStaging internal constructor(
    val file:File,
    val campaignUid:String,
    val sourceHistoryGenerationUid:String,
    val targetHistoryGenerationUid:String,
    val sourceCommitOrder:Long,
    val targetCommitOrder:Long,
    val expectedAuthoritativeDigest:String
)

/** Test-only process boundary: activation deliberately leaves its durable journal untouched. */
internal class AbruptUndoActivationInterruption:Error("RPGOS-UNDO:SIMULATED_PROCESS_TERMINATION")

private data class UndoActivationJournal(
    val campaignUid:String,
    val phase:String,
    val activePath:String,
    val stagingPath:String,
    val rollbackPath:String,
    val sourceHistoryGenerationUid:String,
    val targetHistoryGenerationUid:String,
    val sourceCommitOrder:Long,
    val targetCommitOrder:Long,
    val sourceAuthoritativeDigest:String,
    val targetAuthoritativeDigest:String
)

/**
 * Durable single-file activation protocol. The live database is replaced with one atomic
 * REPLACE_EXISTING move, so a process or power loss can expose either the complete old file or the
 * complete verified target, never a missing/partially copied authority file. The journal lets the
 * next open deterministically finish cleanup for either outcome.
 */
private object CrashSafeUndoActivation {
    private const val VERSION=1
    private const val PHASE_PREPARING="PREPARING"
    private const val PHASE_PREPARED="PREPARED"
    private const val PHASE_ACTIVATED="ACTIVATED"

    fun journalFile(active:File)=File(requireNotNull(active.parentFile),".${active.name}.undo-activation-v1.json")

    fun create(
        active:File,staging:File,rollback:File,campaignUid:String,
        sourceGeneration:String,targetGeneration:String,sourceOrder:Long,targetOrder:Long,
        sourceDigest:String,targetDigest:String
    ):UndoActivationJournal=UndoActivationJournal(
        campaignUid,PHASE_PREPARING,active.canonicalPath,staging.canonicalPath,rollback.canonicalPath,
        sourceGeneration,targetGeneration,sourceOrder,targetOrder,sourceDigest,targetDigest
    ).also{write(active,it)}

    fun prepared(active:File,journal:UndoActivationJournal)=journal.copy(phase=PHASE_PREPARED).also{write(active,it)}
    fun activated(active:File,journal:UndoActivationJournal)=journal.copy(phase=PHASE_ACTIVATED).also{write(active,it)}

    fun atomicReplace(source:File,target:File){
        require(source.isFile){"RPGOS-UNDO:ATOMIC_SOURCE_MISSING"}
        try{
            Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }catch(unsupported:AtomicMoveNotSupportedException){
            throw IllegalStateException("RPGOS-UNDO:ATOMIC_REPLACE_UNSUPPORTED",unsupported)
        }
        require(target.isFile){"RPGOS-UNDO:ATOMIC_TARGET_MISSING"}
    }

    fun syncCopy(source:File,target:File){
        source.copyTo(target,overwrite=false)
        RandomAccessFile(target,"rw").use{it.fd.sync()}
    }

    fun recoverOpened(db:SQLiteDatabase,campaignUid:String,active:File,snapshotDir:File){
        val marker=journalFile(active)
        if(!marker.isFile)return
        val journal=read(marker)
        require(journal.campaignUid==campaignUid){"RPGOS-UNDO:JOURNAL_CAMPAIGN_MISMATCH"}
        require(File(journal.activePath).canonicalFile==active.canonicalFile){"RPGOS-UNDO:JOURNAL_ACTIVE_PATH_MISMATCH"}
        val staging=File(journal.stagingPath);val rollback=File(journal.rollbackPath)
        require(staging.parentFile?.canonicalFile==snapshotDir.canonicalFile){"RPGOS-UNDO:JOURNAL_STAGING_PATH_MISMATCH"}
        require(rollback.parentFile?.canonicalFile==active.parentFile?.canonicalFile){"RPGOS-UNDO:JOURNAL_ROLLBACK_PATH_MISMATCH"}
        check(db.isDatabaseIntegrityOk){"RPGOS-UNDO:RECOVERY_ACTIVE_INTEGRITY_FAILED"}
        val currentOrder=TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder?:0L
        val currentGeneration=HistoryGenerationStore(db,campaignUid).current().value
        val currentDigest=AuthoritativeStateDigest.compute(db)
        val isSource=currentOrder==journal.sourceCommitOrder&&currentGeneration==journal.sourceHistoryGenerationUid&&currentDigest==journal.sourceAuthoritativeDigest
        val isTarget=currentOrder==journal.targetCommitOrder&&currentGeneration==journal.targetHistoryGenerationUid&&currentDigest==journal.targetAuthoritativeDigest
        require(isSource||isTarget){"RPGOS-UNDO:RECOVERY_ACTIVE_STATE_UNRECOGNIZED:${journal.phase}"}
        cleanup(active,journal)
    }

    fun matches(file:File,campaignUid:String,order:Long,generation:String,digest:String):Boolean = runCatching{
        if(!file.isFile)return@runCatching false
        SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{candidate->
            candidate.isDatabaseIntegrityOk&&
                (TurnTransactionReceiptStore(candidate).lastValidCommit(campaignUid)?.commitOrder?:0L)==order&&
                HistoryGenerationStore(candidate,campaignUid).current().value==generation&&
                AuthoritativeStateDigest.compute(candidate)==digest
        }
    }.getOrDefault(false)

    fun rollbackToSource(active:File,journal:UndoActivationJournal){
        val rollback=File(journal.rollbackPath)
        val sourceAlreadyActive=matches(
            active,journal.campaignUid,journal.sourceCommitOrder,journal.sourceHistoryGenerationUid,journal.sourceAuthoritativeDigest
        )
        if(!sourceAlreadyActive){
            require(rollback.isFile){"RPGOS-UNDO:ROLLBACK_SOURCE_MISSING"}
            atomicReplace(rollback,active)
            require(matches(active,journal.campaignUid,journal.sourceCommitOrder,journal.sourceHistoryGenerationUid,journal.sourceAuthoritativeDigest)){
                "RPGOS-UNDO:ROLLBACK_RESTORE_VERIFICATION_FAILED"
            }
        }
        cleanup(active,journal)
    }

    fun cleanup(active:File,journal:UndoActivationJournal){
        listOf(File(journal.stagingPath),File(journal.rollbackPath)).forEach{file->
            if(file.exists())check(file.delete()){"RPGOS-UNDO:RECOVERY_ARTIFACT_DELETE_FAILED:${file.name}"}
        }
        val marker=journalFile(active)
        if(marker.exists())check(marker.delete()){"RPGOS-UNDO:JOURNAL_DELETE_FAILED"}
        File(marker.parentFile,".${marker.name}.tmp").takeIf{it.exists()}?.let{check(it.delete()){"RPGOS-UNDO:JOURNAL_TEMP_DELETE_FAILED"}}
    }

    private fun write(active:File,journal:UndoActivationJournal){
        val marker=journalFile(active);val temp=File(marker.parentFile,".${marker.name}.tmp")
        if(temp.exists())check(temp.delete()){"RPGOS-UNDO:JOURNAL_TEMP_STALE"}
        val payload=JSONObject().apply{
            put("version",VERSION);put("campaign_uid",journal.campaignUid);put("phase",journal.phase)
            put("active_path",journal.activePath);put("staging_path",journal.stagingPath);put("rollback_path",journal.rollbackPath)
            put("source_generation",journal.sourceHistoryGenerationUid);put("target_generation",journal.targetHistoryGenerationUid)
            put("source_order",journal.sourceCommitOrder);put("target_order",journal.targetCommitOrder)
            put("source_digest",journal.sourceAuthoritativeDigest);put("target_digest",journal.targetAuthoritativeDigest)
        }.toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(temp).use{stream->stream.write(payload);stream.flush();stream.fd.sync()}
        atomicReplace(temp,marker)
    }

    private fun read(marker:File):UndoActivationJournal{
        val root=runCatching{JSONObject(marker.readText(Charsets.UTF_8))}.getOrElse{throw IllegalStateException("RPGOS-UNDO:JOURNAL_CORRUPT",it)}
        require(root.getInt("version")==VERSION){"RPGOS-UNDO:JOURNAL_VERSION_UNSUPPORTED"}
        return UndoActivationJournal(
            root.getString("campaign_uid"),root.getString("phase"),root.getString("active_path"),root.getString("staging_path"),root.getString("rollback_path"),
            root.getString("source_generation"),root.getString("target_generation"),root.getLong("source_order"),root.getLong("target_order"),
            root.getString("source_digest"),root.getString("target_digest")
        ).also{require(it.phase in setOf(PHASE_PREPARING,PHASE_PREPARED,PHASE_ACTIVATED)){"RPGOS-UNDO:JOURNAL_PHASE_INVALID"}}
    }
}

internal class CommittedReplayPayloadStore(private val db: SQLiteDatabase) {
    fun append(
        identity: TurnTransactionIdentity,
        commitOrder: Long,
        semanticFingerprint: String,
        manifest: RequiredEventManifestSummary,
        changeSet: PlayerChangeSet,
        causalPlan: List<CanonicalCausalRelationIntent>,
        eventBoundaryUid: String?,
        postAuthoritativeDigest:String?=null
    ) {
        check(db.inTransaction()) { "RPGOS-SNAPSHOT:REPLAY_OUTSIDE_TURN" }
        check(CampaignSnapshotSchema.isReady(db)) { "RPGOS-SNAPSHOT:SCHEMA_NOT_READY" }
        val changeJson = PlayerChangeSetCodec.encode(changeSet)
        val causalJson = encodeCausalPlan(causalPlan)
        val version=if(postAuthoritativeDigest==null)1 else 2
        val digest = sha256(replayCanonical(version,identity, commitOrder, semanticFingerprint, manifest, eventBoundaryUid, changeJson, causalJson,postAuthoritativeDigest))
        existingDigest(identity.transactionUid)?.let {
            require(it == digest) { "RPGOS-SNAPSHOT:REPLAY_IDENTITY_CONFLICT" }
            return
        }
        db.execSQL("""INSERT INTO ${CampaignSnapshotSchema.REPLAY}(
            transaction_uid,campaign_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
            required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,post_authoritative_digest,payload_sha256)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(identity.transactionUid,identity.campaignUid,identity.turnUid,identity.commandUid,
            commitOrder,semanticFingerprint,manifest.requiredEventCount,manifest.orderedManifestFingerprint,eventBoundaryUid,version,changeJson,causalJson,postAuthoritativeDigest,digest))
    }

    fun after(campaignUid: String, commitOrder: Long): List<CommittedReplayPayload> = db.rawQuery(
        """SELECT transaction_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
            required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,post_authoritative_digest,payload_sha256
            FROM ${CampaignSnapshotSchema.REPLAY} WHERE campaign_uid=? AND commit_order>? ORDER BY commit_order""",
        arrayOf(campaignUid,commitOrder.toString())
    ).use { c -> buildList { while(c.moveToNext()) { val payload=decode(campaignUid,c);require(valid(payload)){"RPGOS-SNAPSHOT:REPLAY_PAYLOAD_DIGEST_MISMATCH"};add(payload) } } }

    fun afterLimited(campaignUid:String,commitOrder:Long,maximumTransactions:Int):List<CommittedReplayPayload>{
        require(maximumTransactions>0)
        return db.rawQuery(
            """SELECT transaction_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
                required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,post_authoritative_digest,payload_sha256
                FROM ${CampaignSnapshotSchema.REPLAY} WHERE campaign_uid=? AND commit_order>? ORDER BY commit_order LIMIT ?""",
            arrayOf(campaignUid,commitOrder.toString(),maximumTransactions.toString())
        ).use{c->buildList{while(c.moveToNext()){val payload=decode(campaignUid,c);require(valid(payload)){"RPGOS-SNAPSHOT:REPLAY_PAYLOAD_DIGEST_MISMATCH"};add(payload)}}}
    }

    fun tailAfterLimited(campaignUid:String,commitOrder:Long,maximumTransactions:Int):List<CommittedReplayPayload>{
        require(maximumTransactions>0)
        return db.rawQuery(
            """SELECT transaction_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
                required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,post_authoritative_digest,payload_sha256
                FROM (SELECT transaction_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
                    required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,post_authoritative_digest,payload_sha256
                    FROM ${CampaignSnapshotSchema.REPLAY} WHERE campaign_uid=? AND commit_order>? ORDER BY commit_order DESC LIMIT ?)
                ORDER BY commit_order""",
            arrayOf(campaignUid,commitOrder.toString(),maximumTransactions.toString())
        ).use{c->buildList{while(c.moveToNext()){val payload=decode(campaignUid,c);require(valid(payload)){"RPGOS-SNAPSHOT:REPLAY_PAYLOAD_DIGEST_MISMATCH"};add(payload)}}}
    }

    fun between(campaignUid:String,afterExclusive:Long,throughInclusive:Long):List<CommittedReplayPayload> = db.rawQuery(
        """SELECT transaction_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
            required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,post_authoritative_digest,payload_sha256
            FROM ${CampaignSnapshotSchema.REPLAY} WHERE campaign_uid=? AND commit_order>? AND commit_order<=? ORDER BY commit_order""",
        arrayOf(campaignUid,afterExclusive.toString(),throughInclusive.toString())
    ).use{c->buildList{while(c.moveToNext()){val payload=decode(campaignUid,c);require(valid(payload)){"RPGOS-SNAPSHOT:REPLAY_PAYLOAD_DIGEST_MISMATCH"};add(payload)}}}

    fun atOrders(campaignUid:String,commitOrders:Set<Long>):List<CommittedReplayPayload>{
        require(commitOrders.size<=200){"RPGOS-SNAPSHOT:REPLAY_ORDER_LOOKUP_TOO_LARGE"}
        require(commitOrders.all{it>=0L})
        if(commitOrders.isEmpty())return emptyList()
        val ordered=commitOrders.sorted()
        val placeholders=List(ordered.size){"?"}.joinToString(",")
        return db.rawQuery(
            """SELECT transaction_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
                required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,post_authoritative_digest,payload_sha256
                FROM ${CampaignSnapshotSchema.REPLAY} WHERE campaign_uid=? AND commit_order IN ($placeholders) ORDER BY commit_order""",
            (listOf(campaignUid)+ordered.map(Long::toString)).toTypedArray()
        ).use{c->buildList{while(c.moveToNext()){val payload=decode(campaignUid,c);require(valid(payload)){"RPGOS-SNAPSHOT:REPLAY_PAYLOAD_DIGEST_MISMATCH"};add(payload)}}}
    }

    fun existingDigest(transactionUid: String): String? = db.rawQuery(
        "SELECT payload_sha256 FROM ${CampaignSnapshotSchema.REPLAY} WHERE transaction_uid=?", arrayOf(transactionUid)
    ).use { c -> if(!c.moveToFirst()) null else c.getString(0) }

    private fun decode(campaignUid:String,c:android.database.Cursor):CommittedReplayPayload {
        val version=c.getInt(8)
        require(version in 1..2){"RPGOS-SNAPSHOT:UNSUPPORTED_REPLAY_SCHEMA:$version"}
        return CommittedReplayPayload(
            TurnTransactionIdentity(campaignUid,c.getString(1),c.getString(2),c.getString(0)),c.getLong(3),c.getString(4),
            RequiredEventManifestSummary(c.getInt(5),c.getString(6)),if(c.isNull(7))null else c.getString(7),
            version,PlayerChangeSetCodec.decode(c.getString(9)),decodeCausalPlan(c.getString(10)),
            if(c.isNull(11))null else c.getString(11),c.getString(12)
        )
    }
    private fun valid(p:CommittedReplayPayload):Boolean { val change=PlayerChangeSetCodec.encode(p.changeSet);val causal=encodeCausalPlan(p.causalPlan);return p.payloadSha256==sha256(replayCanonical(p.replaySchemaVersion,p.identity,p.commitOrder,p.semanticFingerprint,p.eventManifest,p.eventBoundaryUid,change,causal,p.postAuthoritativeDigest)) }
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
        private fun replayCanonical(v:Int,i:TurnTransactionIdentity,o:Long,f:String,m:RequiredEventManifestSummary,e:String?,c:String,p:String,d:String?)=
            listOf("v=$v",i.campaignUid,i.transactionUid,i.commandUid,i.turnUid,o.toString(),f,m.requiredEventCount.toString(),m.orderedManifestFingerprint,e?:"",c,p).let{
                if(v>=2)it+(d?:error("RPGOS-SNAPSHOT:V2_POST_DIGEST_REQUIRED")) else it
            }.joinToString("\u001f")
    }
}

class CampaignSnapshotManager(private val db:SQLiteDatabase,private val campaignUid:String,private val snapshotDir:File) {
    companion object { const val AUTOMATIC_RETENTION=6 }
    init {
        require(campaignUid.isNotBlank())
        File(db.path).takeIf{it.isAbsolute&&it.isFile}?.let{active->
            CrashSafeUndoActivation.recoverOpened(db,campaignUid,active,snapshotDir)
        }
    }

    fun create(kind:SnapshotKind=SnapshotKind.AUTOMATIC,pinned:Boolean=kind==SnapshotKind.USER_PINNED):CampaignSnapshotDescriptor {
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { createLocked(kind,pinned) }
    }

    private fun createLocked(kind:SnapshotKind,pinned:Boolean):CampaignSnapshotDescriptor {
        check(CampaignSnapshotSchema.isReady(db)) { "RPGOS-SNAPSHOT:SCHEMA_NOT_READY" }
        snapshotDir.mkdirs(); reconcileOrphansLocked();val effectivePinned=pinned||kind==SnapshotKind.USER_PINNED
        val order=db.rawQuery("SELECT COALESCE(MAX(created_order),0)+1 FROM ${CampaignSnapshotSchema.CATALOG} WHERE campaign_uid=?",arrayOf(campaignUid)).use{it.moveToFirst();it.getLong(0)}
        val uid="SNAP-$campaignUid-$order-${UUID.randomUUID()}"
        // Keep the payload name short for Windows while retaining campaign isolation when tests or
        // recovery tooling intentionally share one snapshot directory between several campaigns.
        val fileToken="${compactCampaignToken()}-$order"
        val staged=File(snapshotStagingRoot(),".$fileToken.tmp");val published=File(snapshotDir,"$fileToken.db")
        db.execSQL("""INSERT INTO ${CampaignSnapshotSchema.CATALOG}(snapshot_uid,campaign_uid,snapshot_kind,snapshot_schema_version,created_order,
            created_at_epoch_ms,anchor_commit_order,anchor_transaction_uid,anchor_turn_uid,anchor_event_uid,anchor_authoritative_digest,
            payload_path,payload_sha256,publication_state,pinned)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf<Any?>(uid,campaignUid,kind.name,CampaignSnapshotSchema.VERSION,order,System.currentTimeMillis(),
            0L,null,null,null,null,published.absolutePath,null,SnapshotPublicationState.STAGED.name,if(effectivePinned)1 else 0))
        try {
            if(staged.exists())staged.delete()
            check(!db.inTransaction()){"RPGOS-SNAPSHOT:CAPTURE_INSIDE_TRANSACTION"}
            // VACUUM INTO requires a newer SQLite than Android 9 (the app's minSdk). The recovery
            // lock prevents concurrent turns; checkpoint first, then copy the canonical DB file.
            requireFullWalCheckpoint(db)
            File(db.path).copyTo(staged,overwrite=false);check(staged.isFile)
            val capturedAnchor=SQLiteDatabase.openDatabase(staged.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{capturedDb->
                check(capturedDb.isDatabaseIntegrityOk)
                val receipt=TurnTransactionReceiptStore(capturedDb).lastValidCommit(campaignUid)
                CapturedSnapshotAnchor(
                    commitOrder=receipt?.commitOrder?:0L,
                    transactionUid=receipt?.transactionUid,
                    turnUid=receipt?.turnUid,
                    eventUid=receipt?.transactionUid?.let{tx->CampaignEventStore(capturedDb,campaignUid).eventsForTransaction(tx).lastOrNull()?.eventUid},
                    authoritativeDigest=AuthoritativeStateDigest.compute(capturedDb)
                )
            }
            val digest=fileSha256(staged);check(staged.renameTo(published)){"RPGOS-SNAPSHOT:PUBLISH_RENAME_FAILED"}
            db.execSQL("""UPDATE ${CampaignSnapshotSchema.CATALOG}
                SET anchor_commit_order=?,anchor_transaction_uid=?,anchor_turn_uid=?,anchor_event_uid=?,anchor_authoritative_digest=?,payload_sha256=?,publication_state=?
                WHERE snapshot_uid=?""",arrayOf<Any?>(capturedAnchor.commitOrder,capturedAnchor.transactionUid,capturedAnchor.turnUid,
                capturedAnchor.eventUid,capturedAnchor.authoritativeDigest,digest,SnapshotPublicationState.VALID.name,uid))
            if(kind==SnapshotKind.AUTOMATIC&&!effectivePinned)pruneAutomaticLocked()
            return requireNotNull(find(uid))
        } catch(t:Throwable) {
            staged.delete();published.delete();db.execSQL("UPDATE ${CampaignSnapshotSchema.CATALOG} SET publication_state=? WHERE snapshot_uid=?",arrayOf(SnapshotPublicationState.INVALID.name,uid));throw t
        }
    }

    fun list():List<CampaignSnapshotDescriptor> { check(CampaignSnapshotSchema.isReady(db));return db.rawQuery("""SELECT snapshot_uid,snapshot_kind,snapshot_schema_version,created_order,created_at_epoch_ms,
        anchor_commit_order,anchor_transaction_uid,anchor_turn_uid,anchor_event_uid,anchor_authoritative_digest,payload_path,payload_sha256,publication_state,pinned
        FROM ${CampaignSnapshotSchema.CATALOG} WHERE campaign_uid=? ORDER BY created_order DESC,snapshot_uid DESC""",arrayOf(campaignUid)).use{c->buildList{while(c.moveToNext())add(row(c))}} }

    fun pruneUndoBaselines(keep:Int=2){
        require(keep>=2){"RPGOS-UNDO:AT_LEAST_TWO_BASELINES_REQUIRED"}
        list().filter{it.kind==SnapshotKind.UNDO_BASELINE&&it.state==SnapshotPublicationState.VALID}
            .sortedByDescending{it.anchorCommitOrder}.drop(keep).forEach{snapshot->
                val payload=File(snapshot.payloadPath)
                if(payload.exists()&&!payload.delete())return@forEach
                db.delete(CampaignSnapshotSchema.CATALOG,"campaign_uid=? AND snapshot_uid=?",arrayOf(campaignUid,snapshot.snapshotUid))
            }
    }

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
        val legacyCampaignFilePrefix="SNAP-$campaignUid-"
        val compactCampaignFilePrefix="${compactCampaignToken()}-"
        snapshotDir.listFiles{f->
            f.name.startsWith(".$legacyCampaignFilePrefix")&&(f.name.endsWith(".staged.db")||f.name.endsWith(".reconstructing.db"))
        }?.forEach{it.delete()}
        snapshotStagingRoot().listFiles{f->f.isFile&&f.name.startsWith(".$compactCampaignFilePrefix")&&f.name.endsWith(".tmp")}
            ?.forEach{it.delete()}
        val known=db.rawQuery("SELECT payload_path FROM ${CampaignSnapshotSchema.CATALOG}",null).use{c->buildSet{
            while(c.moveToNext())add(File(c.getString(0)).canonicalFile)
        }}
        snapshotDir.listFiles{f->
            f.isFile&&(f.name.startsWith(legacyCampaignFilePrefix)||f.name.startsWith(compactCampaignFilePrefix))&&
                f.extension=="db"&&f.canonicalFile !in known
        }?.forEach{it.delete()}
        snapshotDir.listFiles{f->f.isFile&&f.name.startsWith(".r-${shortCampaignToken()}-")&&f.name.endsWith(".db")}?.forEach{it.delete()}
        snapshotDir.listFiles{f->f.isFile&&f.name.startsWith(".u-${shortCampaignToken()}-")&&f.name.endsWith(".db")}?.forEach{it.delete()}
    }

    private fun compactCampaignToken()="c-${sha256(campaignUid).take(12)}"
    private fun shortCampaignToken()=sha256(campaignUid).take(4)
    private fun snapshotStagingRoot()=snapshotDir.parentFile?.parentFile?:requireNotNull(snapshotDir.parentFile)

    private fun find(uid:String)=list().firstOrNull{it.snapshotUid==uid}
    private fun row(c:android.database.Cursor)=CampaignSnapshotDescriptor(c.getString(0),campaignUid,SnapshotKind.valueOf(c.getString(1)),c.getInt(2),c.getLong(3),c.getLong(4),c.getLong(5),if(c.isNull(6))null else c.getString(6),if(c.isNull(7))null else c.getString(7),if(c.isNull(8))null else c.getString(8),if(c.isNull(9))null else c.getString(9),c.getString(10),if(c.isNull(11))null else c.getString(11),SnapshotPublicationState.valueOf(c.getString(12)),c.getInt(13)!=0)
    private fun copyCurrentCatalogTo(target:SQLiteDatabase,targetCommitOrder:Long?=null){
        target.beginTransaction();try{target.delete(CampaignSnapshotSchema.CATALOG,"campaign_uid=?",arrayOf(campaignUid));list().forEach{s->target.insertOrThrow(CampaignSnapshotSchema.CATALOG,null,ContentValues().apply{
            val manual=s.kind in setOf(SnapshotKind.MANUAL_BACKUP,SnapshotKind.MANUAL_EXPORT,SnapshotKind.USER_PINNED,SnapshotKind.LEGACY_BACKUP)
            if(targetCommitOrder!=null&&!manual&&s.anchorCommitOrder>targetCommitOrder)return@forEach
            put("snapshot_uid",s.snapshotUid);put("campaign_uid",s.campaignUid);put("snapshot_kind",s.kind.name);put("snapshot_schema_version",s.schemaVersion)
            put("created_order",s.createdOrder);put("created_at_epoch_ms",s.createdAtEpochMs);put("anchor_commit_order",s.anchorCommitOrder)
            if(s.anchorTransactionUid==null)putNull("anchor_transaction_uid")else put("anchor_transaction_uid",s.anchorTransactionUid)
            if(s.anchorTurnUid==null)putNull("anchor_turn_uid")else put("anchor_turn_uid",s.anchorTurnUid)
            if(s.anchorEventUid==null)putNull("anchor_event_uid")else put("anchor_event_uid",s.anchorEventUid)
            if(s.anchorAuthoritativeDigest==null)putNull("anchor_authoritative_digest")else put("anchor_authoritative_digest",s.anchorAuthoritativeDigest)
            put("payload_path",s.payloadPath);if(s.payloadSha256==null)putNull("payload_sha256")else put("payload_sha256",s.payloadSha256)
            put("publication_state",s.state.name);put("pinned",if(s.pinned)1 else 0)
        })};target.setTransactionSuccessful()}finally{target.endTransaction()}
    }

    fun reconstructToVerifiedStaging(snapshotUid:String?=null):File {
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { reconstructLocked(snapshotUid) }
    }

    fun reconstructToVerifiedStagingAt(targetCommitOrder:Long):VerifiedUndoStaging{
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid){reconstructPrefixLocked(targetCommitOrder)}
    }

    private fun reconstructPrefixLocked(targetCommitOrder:Long):VerifiedUndoStaging{
        CampaignReplayAuthorityMatrix.validateComplete()
        require(targetCommitOrder>=0L){"RPGOS-UNDO:NEGATIVE_TARGET"}
        reconcileOrphansLocked()
        val currentOrder=TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder?:0L
        require(targetCommitOrder<currentOrder){"RPGOS-UNDO:TARGET_NOT_BEFORE_CURRENT"}
        val sourceGeneration=HistoryGenerationStore(db,campaignUid).current()
        val snapshot=RecoverableSnapshotPolicy.latestRecoverableAtOrBefore(db,campaignUid,targetCommitOrder)
            ?:error("RPGOS-UNDO:NO_VERIFIED_BASELINE")
        require(snapshot.anchorAuthoritativeDigest!=null){"RPGOS-UNDO:BASELINE_DIGEST_MISSING"}
        val payloads=CommittedReplayPayloadStore(db).between(campaignUid,snapshot.anchorCommitOrder,targetCommitOrder)
        val expectedOrders=if(targetCommitOrder==snapshot.anchorCommitOrder)emptyList() else (snapshot.anchorCommitOrder+1..targetCommitOrder).toList()
        require(payloads.map{it.commitOrder}==expectedOrders){"RPGOS-UNDO:REPLAY_COVERAGE_INCOMPLETE"}
        require(payloads.all{it.replaySchemaVersion==2&&it.postAuthoritativeDigest!=null}){"RPGOS-UNDO:REPLAY_V2_REQUIRED"}
        val expectedDigest=payloads.lastOrNull()?.postAuthoritativeDigest?:snapshot.anchorAuthoritativeDigest
        val staging=File(snapshotDir,".u-${shortCampaignToken()}-$targetCommitOrder-${UUID.randomUUID().toString().take(8)}.db")
        if(staging.exists())staging.delete()
        File(snapshot.payloadPath).copyTo(staging)
        try{
            SQLiteDatabase.openDatabase(staging.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{target->
                check(target.isDatabaseIntegrityOk){"RPGOS-UNDO:STAGING_INTEGRITY_FAILED"}
                Phase36SchemaVersioning.ensureReady(target,campaignUid)
                GameplayRuntimeBootstrap.initialize(target,campaignUid)
                payloads.forEach{replayCommittedTransaction(target,it)}
                require(AuthoritativeStateDigest.compute(target)==expectedDigest){"RPGOS-UNDO:TARGET_DIGEST_MISMATCH"}
                val last=TurnTransactionReceiptStore(target).lastValidCommit(campaignUid)?.commitOrder?:0L
                require(last==targetCommitOrder){"RPGOS-UNDO:TARGET_ORDER_MISMATCH"}
                withAdministrativeMutationAuthority(target,campaignUid){
                    replaceDerivedMemoryHistory(target,campaignUid,"DESTRUCTIVE_UNDO")
                }
                copyCurrentCatalogTo(target,targetCommitOrder)
                GameplayRuntimeBootstrap.requireReady(target,campaignUid)
            }
            val targetGeneration=SQLiteDatabase.openDatabase(staging.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{target->
                HistoryGenerationStore(target,campaignUid).current().value
            }
            return VerifiedUndoStaging(
                staging,campaignUid,sourceGeneration.value,targetGeneration,currentOrder,targetCommitOrder,expectedDigest
            )
        }catch(t:Throwable){staging.delete();throw t}
    }

    fun activateVerifiedUndoStaging(
        activeDbFile:File,
        verified:VerifiedUndoStaging,
        failureInjector:UndoFailureInjector=UndoFailureInjector.NONE
    ):File{
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid){
            require(verified.campaignUid==campaignUid){"RPGOS-UNDO:CAMPAIGN_MISMATCH"}
            require(verified.file.isFile&&verified.file.parentFile?.canonicalFile==snapshotDir.canonicalFile){"RPGOS-UNDO:INVALID_STAGING_LOCATION"}
            check(db.isOpen){"RPGOS-UNDO:ACTIVE_DB_NOT_OPEN"}
            require(HistoryGenerationStore(db,campaignUid).current().value==verified.sourceHistoryGenerationUid){"RPGOS-UNDO:STALE_HISTORY_GENERATION"}
            require((TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder?:0L)==verified.sourceCommitOrder){"RPGOS-UNDO:STALE_PREVIEW"}
            val obsoleteInternalSnapshots=list().filter{snapshot->
                snapshot.anchorCommitOrder>verified.targetCommitOrder&&snapshot.kind !in setOf(
                    SnapshotKind.MANUAL_BACKUP,SnapshotKind.MANUAL_EXPORT,SnapshotKind.USER_PINNED,SnapshotKind.LEGACY_BACKUP
                )
            }.map{File(it.payloadPath)}
            SQLiteDatabase.openDatabase(verified.file.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{staged->
                check(staged.isDatabaseIntegrityOk){"RPGOS-UNDO:STAGING_INTEGRITY_FAILED"}
                require(AuthoritativeStateDigest.compute(staged)==verified.expectedAuthoritativeDigest){"RPGOS-UNDO:STAGING_DIGEST_CHANGED"}
                require((TurnTransactionReceiptStore(staged).lastValidCommit(campaignUid)?.commitOrder?:0L)==verified.targetCommitOrder){"RPGOS-UNDO:STAGING_ORDER_CHANGED"}
                require(Phase36SchemaCompatibilityFingerprint.compute(staged)==Phase36SchemaCompatibilityFingerprint.compute(db)){"RPGOS-UNDO:SCHEMA_VECTOR_MISMATCH"}
            }
            requireFullWalCheckpoint(db)
            val rollback=File(activeDbFile.parentFile,".${activeDbFile.name}.before_undo_activation-${UUID.randomUUID()}")
            val wal=File(activeDbFile.absolutePath+"-wal")
            val shm=File(activeDbFile.absolutePath+"-shm")
            val sourceDigest=AuthoritativeStateDigest.compute(db)
            failureInjector.failIfRequested(UndoFailurePoint.BEFORE_ACTIVE_RENAME)
            db.close()
            check(!wal.exists()||wal.delete()){ "RPGOS-UNDO:STALE_WAL_DELETE_FAILED" }
            check(!shm.exists()||shm.delete()){ "RPGOS-UNDO:STALE_SHM_DELETE_FAILED" }
            var journal=CrashSafeUndoActivation.create(
                activeDbFile,verified.file,rollback,campaignUid,
                verified.sourceHistoryGenerationUid,verified.targetHistoryGenerationUid,
                verified.sourceCommitOrder,verified.targetCommitOrder,sourceDigest,verified.expectedAuthoritativeDigest
            )
            try{
                CrashSafeUndoActivation.syncCopy(activeDbFile,rollback)
                journal=CrashSafeUndoActivation.prepared(activeDbFile,journal)
                failureInjector.failIfRequested(UndoFailurePoint.AFTER_ACTIVE_RENAME)
                CrashSafeUndoActivation.atomicReplace(verified.file,activeDbFile)
                journal=CrashSafeUndoActivation.activated(activeDbFile,journal)
                failureInjector.failIfRequested(UndoFailurePoint.AFTER_STAGING_ACTIVATION)
                SQLiteDatabase.openDatabase(activeDbFile.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{activated->
                    check(activated.isDatabaseIntegrityOk){"RPGOS-UNDO:ACTIVATED_INTEGRITY_FAILED"}
                    require(AuthoritativeStateDigest.compute(activated)==verified.expectedAuthoritativeDigest){
                        "RPGOS-UNDO:ACTIVATED_DIGEST_CHANGED"
                    }
                    require((TurnTransactionReceiptStore(activated).lastValidCommit(campaignUid)?.commitOrder?:0L)==verified.targetCommitOrder){
                        "RPGOS-UNDO:ACTIVATED_ORDER_CHANGED"
                    }
                    require(HistoryGenerationStore(activated,campaignUid).current().value==verified.targetHistoryGenerationUid){
                        "RPGOS-UNDO:ACTIVATED_GENERATION_CHANGED"
                    }
                }
                CrashSafeUndoActivation.cleanup(activeDbFile,journal)
                obsoleteInternalSnapshots.forEach{it.delete()}
                activeDbFile
            }catch(t:Throwable){
                if(t is AbruptUndoActivationInterruption)throw t
                try{CrashSafeUndoActivation.rollbackToSource(activeDbFile,journal)}
                catch(rollbackFailure:Throwable){throw IllegalStateException("RPGOS-UNDO:ROLLBACK_RESTORE_FAILED:${rollback.absolutePath}",rollbackFailure)}
                throw t
            }
        }
    }

    private fun reconstructLocked(snapshotUid:String?):File {
        reconcileOrphansLocked()
        val snapshot = if(snapshotUid==null) {
            RecoverableSnapshotPolicy.latestRecoverable(db,campaignUid)
                ?: error("RPGOS-SNAPSHOT:NO_VALID_COMPATIBLE_SNAPSHOT")
        } else {
            RecoverableSnapshotPolicy.requireRecoverable(db,campaignUid,snapshotUid)
        }
        val staging=File(snapshotDir,".r-${shortCampaignToken()}-${snapshot.createdOrder}.db")
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
            val rollback=File(activeDbFile.parentFile,".${activeDbFile.name}.before_snapshot_activation-${UUID.randomUUID()}")
            db.close()
            check(activeDbFile.renameTo(rollback)){"RPGOS-SNAPSHOT:ACTIVE_STAGE_RENAME_FAILED"}
            try {
                check(verifiedStaging.renameTo(activeDbFile)){"RPGOS-SNAPSHOT:STAGING_ACTIVATION_FAILED"}
                rollback.delete();activeDbFile
            } catch(t:Throwable) {
                if(activeDbFile.exists())activeDbFile.delete()
                val restored=rollback.renameTo(activeDbFile)||runCatching{
                    rollback.copyTo(activeDbFile,overwrite=false);activeDbFile.isFile
                }.getOrDefault(false)
                if(!restored)throw IllegalStateException("RPGOS-SNAPSHOT:ROLLBACK_RESTORE_FAILED:${rollback.absolutePath}",t)
                throw t
            }
        }
    }
}

private data class CapturedSnapshotAnchor(
    val commitOrder:Long,
    val transactionUid:String?,
    val turnUid:String?,
    val eventUid:String?,
    val authoritativeDigest:String
)

internal fun replaceDerivedMemoryHistory(db:SQLiteDatabase,campaignUid:String,reasonUid:String):HistoryGenerationUid{
    require(reasonUid.isNotBlank())
    listOf(
        Phase55To58MemorySchema.ARTIFACTS,
        Phase55To58MemorySchema.LEAVES,
        Phase55To58MemorySchema.DEPENDENCIES,
        Phase55To58MemorySchema.EPISODE_MEMBERSHIP,
        Phase55To58MemorySchema.RECEIPTS
    ).forEach{table->db.delete(table,"campaign_uid=?",arrayOf(campaignUid))}
    return HistoryGenerationStore(db,campaignUid).advance(reasonUid)
}

internal object AuthoritativeStateDigest {
    fun compute(db:SQLiteDatabase):String { val md=MessageDigest.getInstance("SHA-256");RuntimeTruthLayerRegistry.authoritativePersistentTables().filter{tableExists(db,it)}.sorted().forEach{t->
        val columns=db.rawQuery("PRAGMA table_info(`$t`)",null).use{c->buildList{while(c.moveToNext())add(c.getString(1))}}
        md.update("T:$t:${columns.joinToString(",")}\n".toByteArray(Charsets.UTF_8));val order=columns.joinToString(","){"`$it`"}
        db.rawQuery("SELECT $order FROM `$t` ORDER BY $order",null).use{c->while(c.moveToNext()){for(i in columns.indices){md.update(when(c.getType(i)){android.database.Cursor.FIELD_TYPE_NULL->"N";android.database.Cursor.FIELD_TYPE_BLOB->"B"+c.getBlob(i).joinToString(""){"%02x".format(it)};else->"V"+c.getString(i)}.toByteArray(Charsets.UTF_8));md.update(0)}}}
    };return md.digest().joinToString(""){"%02x".format(it)}}
    private fun tableExists(db:SQLiteDatabase,t:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(t)).use{it.moveToFirst()}
}

internal object TableDigest {
    fun compute(db:SQLiteDatabase,table:String):String {
        val md=MessageDigest.getInstance("SHA-256")
        val columns=db.rawQuery("PRAGMA table_info(`$table`)",null).use{c->buildList{while(c.moveToNext())add(c.getString(1))}}
        if(columns.isEmpty())return "MISSING"
        val order=columns.joinToString(","){"`$it`"};db.rawQuery("SELECT $order FROM `$table` ORDER BY $order",null).use{c->while(c.moveToNext()){for(i in columns.indices){
            val encoded=when(c.getType(i)){
                android.database.Cursor.FIELD_TYPE_NULL->byteArrayOf(0)
                android.database.Cursor.FIELD_TYPE_BLOB->byteArrayOf(1)+c.getBlob(i)
                else->byteArrayOf(2)+c.getString(i).toByteArray(Charsets.UTF_8)
            }
            md.update(encoded);md.update(31)
        }}}
        return md.digest().joinToString(""){"%02x".format(it)}
    }
}

private fun requireFullWalCheckpoint(db:SQLiteDatabase){
    db.rawQuery("PRAGMA wal_checkpoint(FULL)",null).use{cursor->
        require(cursor.moveToFirst()){"RPGOS-SNAPSHOT:WAL_CHECKPOINT_NO_RESULT"}
        val busy=cursor.getInt(0);val logPages=cursor.getInt(1);val checkpointedPages=cursor.getInt(2)
        require(busy==0&&checkpointedPages>=logPages){"RPGOS-SNAPSHOT:WAL_CHECKPOINT_INCOMPLETE:$busy:$checkpointedPages:$logPages"}
    }
}
private fun sha256(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
private fun fileSha256(f:File):String{val md=MessageDigest.getInstance("SHA-256");f.inputStream().use{s->val b=ByteArray(64*1024);while(true){val n=s.read(b);if(n<0)break;md.update(b,0,n)}};return md.digest().joinToString(""){"%02x".format(it)}}
