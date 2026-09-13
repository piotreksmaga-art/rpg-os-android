package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase60TemporalUndoTest {
    @get:Rule val folder = TemporaryFolder()

    private fun commit(db:SQLiteDatabase, uid:String, progress:String) {
        val before = Phase60TemporalStateStore(db,"C1").read()
        val change = TemporalStateChange("C1",before.version,before.time,before.time+ActionDuration(1000),
            Phase60ProcessStateCodec.encode(listOf(TemporalOwnerState("OWNER",1,progress))),
            Phase60DeadlineCodec.encode(listOf(WorldProcessDeadline("DUE","OWNER",WorldTimeTick(10000)))))
        val actor = CommandActorRef("PLAYER","P1")
        val command = PlayerCommand(commandUid=uid,campaignUid="C1",actor=actor,
            commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,payload=ApplyVerifiedMechanicsCommandPayload("PLAN:$uid",emptyList(),change),
            provenance=CommandProvenance("P60-TEST"),requestedEffectiveOrder=before.version+1)
        val refs = setOf(DomainRef("PLAYER","P1"),DomainRef("CAMPAIGN","C1")).map{CampaignScopedDomainRef("C1",it)}.toSet()
        val admission = CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",actor,refs))
        assertTrue(admission.toString(),admission is CampaignMutationAdmission.Accepted)
        assertTrue(TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN:$uid",uid,"TX:$uid"),
            (admission as CampaignMutationAdmission.Accepted).proposal).commit() is TurnExecutionResult.Committed)
    }

    @Test fun undoReplaysClockProcessesAndDeadlinesAndRetainsManualBackup() {
        val file=File(folder.root,"campaign.db")
        val snapshots=File(folder.root,"snapshots")
        val db=SQLiteDatabase.openOrCreateDatabase(file,null)
        try {
            db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
            db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
            GroupATransactionTestFixtures.setupFinance(db)
            CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
            commit(db,"FIRST","partial-one")
            val first=Phase60TemporalStateStore(db,"C1").read()
            commit(db,"SECOND","partial-two")
            val backup=CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.MANUAL_BACKUP,true)
            val generation=HistoryGenerationStore(db,"C1").current()
            val undo=DestructiveTurnUndoCoordinator(db,"C1",snapshots,file)
            val preview=undo.previewLastTurn()
            assertTrue(preview.toString(),preview.canConfirm)
            val result=undo.confirm(preview)
            assertTrue(result.toString(),result is DestructiveUndoResult.Completed)
            SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use { restored ->
                GameplayRuntimeBootstrap.requireReady(restored,"C1")
                assertEquals(first,Phase60TemporalStateStore(restored,"C1").read())
                assertNotEquals(generation,HistoryGenerationStore(restored,"C1").current())
                assertNull(TurnTransactionReceiptStore(restored).committedCommand("C1","SECOND"))
                assertTrue(File(backup.payloadPath).isFile)
                commit(restored,"ALTERNATIVE","different-decision")
                assertEquals(WorldTimeTick(2000),Phase60TemporalStateStore(restored,"C1").read().time)
            }
        } finally { if(db.isOpen)db.close() }
    }
}
