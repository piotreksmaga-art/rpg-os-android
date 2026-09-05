package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID

enum class UndoAvailabilityReason{
    READY,
    NO_COMMITTED_TURN,
    NO_VERIFIED_BASELINE,
    REPLAY_COVERAGE_INCOMPLETE,
    REPLAY_V2_REQUIRED,
    STALE_PREVIEW,
    PREVIEW_EXPIRED,
    VERIFICATION_FAILED
}

enum class UndoFailurePoint{
    BEFORE_STAGING,
    AFTER_STAGING_VERIFIED,
    BEFORE_ACTIVE_RENAME,
    AFTER_ACTIVE_RENAME,
    AFTER_STAGING_ACTIVATION
}
fun interface UndoFailureInjector{
    fun failIfRequested(point:UndoFailurePoint)
    companion object{val NONE=UndoFailureInjector{}}
}

data class UndoPreview(
    val previewToken:String,
    val campaignUid:String,
    val currentCommitOrder:Long,
    val targetCommitOrder:Long,
    val removedTransactionUid:String,
    val removedTurnUid:String,
    val historyGenerationUid:String,
    val createdAtEpochMs:Long,
    val expiresAtEpochMs:Long,
    val availability:UndoAvailabilityReason,
    val reasonUid:String?=null
){
    val canConfirm:Boolean get()=availability==UndoAvailabilityReason.READY
}

sealed interface DestructiveUndoResult{
    data class Completed(val campaignUid:String,val removedCommitOrder:Long,val activeCommitOrder:Long,val historyGenerationUid:String):DestructiveUndoResult
    data class Rejected(val reason:UndoAvailabilityReason,val reasonUid:String):DestructiveUndoResult
}

/**
 * Administrative, fail-closed removal of the last active turn. It never edits the live database
 * in place: a verified prefix is reconstructed first and only then replaces the active file.
 */
class DestructiveTurnUndoCoordinator(
    private val db:SQLiteDatabase,
    private val campaignUid:String,
    private val snapshotDir:File,
    private val activeDbFile:File,
    private val clock:()->Long={System.currentTimeMillis()},
    private val failureInjector:UndoFailureInjector=UndoFailureInjector.NONE
){
    companion object{const val PREVIEW_TTL_MS=5*60*1000L}
    init{require(campaignUid.isNotBlank())}

    fun previewLastTurn():UndoPreview{
        requireAdministrativeRecoveryEntryPoint()
        CampaignReplayAuthorityMatrix.validateComplete()
        val now=clock()
        val last=TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)
            ?:return unavailable(now,UndoAvailabilityReason.NO_COMMITTED_TURN,"RPGOS-UNDO:NO_COMMITTED_TURN")
        val currentOrder=last.commitOrder?:return unavailable(now,UndoAvailabilityReason.NO_COMMITTED_TURN,"RPGOS-UNDO:MISSING_COMMIT_ORDER")
        val target=currentOrder-1L
        if(target<0L)return unavailable(now,UndoAvailabilityReason.NO_COMMITTED_TURN,"RPGOS-UNDO:NO_PREVIOUS_PREFIX")
        val generation=HistoryGenerationStore(db,campaignUid).current().value
        val failure=runCatching{
            val baseline=RecoverableSnapshotPolicy.latestRecoverableAtOrBefore(db,campaignUid,target)
                ?:error("RPGOS-UNDO:NO_VERIFIED_BASELINE")
            require(baseline.anchorAuthoritativeDigest!=null){"RPGOS-UNDO:BASELINE_DIGEST_MISSING"}
            val payloads=CommittedReplayPayloadStore(db).between(campaignUid,baseline.anchorCommitOrder,target)
            val expected=if(target==baseline.anchorCommitOrder)emptyList() else (baseline.anchorCommitOrder+1..target).toList()
            require(payloads.map{it.commitOrder}==expected){"RPGOS-UNDO:REPLAY_COVERAGE_INCOMPLETE"}
            require(payloads.all{it.replaySchemaVersion==2&&it.postAuthoritativeDigest!=null}){"RPGOS-UNDO:REPLAY_V2_REQUIRED"}
        }.exceptionOrNull()
        if(failure!=null){
            val reason=when(failure.message){
                "RPGOS-UNDO:NO_VERIFIED_BASELINE","RPGOS-UNDO:BASELINE_DIGEST_MISSING"->UndoAvailabilityReason.NO_VERIFIED_BASELINE
                "RPGOS-UNDO:REPLAY_COVERAGE_INCOMPLETE"->UndoAvailabilityReason.REPLAY_COVERAGE_INCOMPLETE
                "RPGOS-UNDO:REPLAY_V2_REQUIRED"->UndoAvailabilityReason.REPLAY_V2_REQUIRED
                else->UndoAvailabilityReason.VERIFICATION_FAILED
            }
            return UndoPreview(UUID.randomUUID().toString(),campaignUid,currentOrder,target,last.transactionUid,last.turnUid,generation,now,now+PREVIEW_TTL_MS,reason,failure.message)
        }
        return UndoPreview(UUID.randomUUID().toString(),campaignUid,currentOrder,target,last.transactionUid,last.turnUid,generation,now,now+PREVIEW_TTL_MS,UndoAvailabilityReason.READY)
    }

    fun confirm(preview:UndoPreview):DestructiveUndoResult{
        requireAdministrativeRecoveryEntryPoint()
        CampaignReplayAuthorityMatrix.validateComplete()
        if(!preview.canConfirm)return DestructiveUndoResult.Rejected(preview.availability,preview.reasonUid?:"RPGOS-UNDO:PREVIEW_NOT_CONFIRMABLE")
        if(clock()>preview.expiresAtEpochMs)return DestructiveUndoResult.Rejected(UndoAvailabilityReason.PREVIEW_EXPIRED,"RPGOS-UNDO:PREVIEW_EXPIRED")
        if(preview.campaignUid!=campaignUid)return DestructiveUndoResult.Rejected(UndoAvailabilityReason.STALE_PREVIEW,"RPGOS-UNDO:CAMPAIGN_CHANGED")
        val last=TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)
        val generation=HistoryGenerationStore(db,campaignUid).current().value
        if(last?.commitOrder!=preview.currentCommitOrder||last.transactionUid!=preview.removedTransactionUid||generation!=preview.historyGenerationUid){
            return DestructiveUndoResult.Rejected(UndoAvailabilityReason.STALE_PREVIEW,"RPGOS-UNDO:STALE_PREVIEW")
        }
        var verifiedStaging:VerifiedUndoStaging?=null
        return try{
            val manager=CampaignSnapshotManager(db,campaignUid,snapshotDir)
            failureInjector.failIfRequested(UndoFailurePoint.BEFORE_STAGING)
            val staged=manager.reconstructToVerifiedStagingAt(preview.targetCommitOrder).also{verifiedStaging=it}
            failureInjector.failIfRequested(UndoFailurePoint.AFTER_STAGING_VERIFIED)
            manager.activateVerifiedUndoStaging(activeDbFile,staged,failureInjector)
            DestructiveUndoResult.Completed(
                campaignUid,preview.currentCommitOrder,preview.targetCommitOrder,staged.targetHistoryGenerationUid
            )
        }catch(failure:Throwable){
            verifiedStaging?.file?.takeIf{it.exists()}?.delete()
            DestructiveUndoResult.Rejected(UndoAvailabilityReason.VERIFICATION_FAILED,failure.message?:"RPGOS-UNDO:VERIFICATION_FAILED")
        }
    }

    private fun unavailable(now:Long,reason:UndoAvailabilityReason,reasonUid:String)=UndoPreview(
        UUID.randomUUID().toString(),campaignUid,0L,0L,"","",HistoryGenerationStore(db,campaignUid).current().value,
        now,now+PREVIEW_TTL_MS,reason,reasonUid
    )
}
