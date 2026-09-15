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
class Phase61NpcBrainUndoTest {
    @get:Rule val folder=TemporaryFolder()
    private val actor=DomainRef("NPC","N1")
    private fun commit(db:SQLiteDatabase,uid:String) {
        val before=NpcBrainStore(db,"C1").read(actor)
        val cause=if(before==null)NpcCauseRef(NpcCauseKind.GENESIS,"P61:GENESIS:${NpcBrainOwner.initialize("C1",actor,"SEED").seedFingerprint}")
            else NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION,uid)
        val after=if(before==null)NpcBrainOwner.initialize("C1",actor,"SEED") else before.copy(revision=before.revision+1,
            motivations=listOf(NpcMotivation("M",NpcMotivationKind.DESIRE,"LEARN",NpcWeight(5000))),
            goals=listOf(NpcGoal("G:$uid","M","Cel $uid",NpcWeight(5000),NpcGoalLifecycle.ACTIVE,cause)))
        val rule=if(before==null)NpcBrainRules.GENESIS else NpcBrainRules.PLANNING
        val change=NpcBrainChange("C1",actor,HistoryGenerationStore(db,"C1").current().value,before?.revision?:0,
            before?.let(NpcBrainCodec::fingerprint),NpcBrainCodec.encode(after),rule.uid,1,listOf(cause))
        val player=CommandActorRef("PLAYER","P1")
        val order=(TurnTransactionReceiptStore(db).lastValidCommit("C1")?.commitOrder?:0)+1
        val command=PlayerCommand(commandUid=uid,campaignUid="C1",actor=player,commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN:$uid",emptyList(),npcBrains=listOf(change)),
            provenance=CommandProvenance("TEST"),requestedEffectiveOrder=order)
        val refs=setOf(DomainRef("PLAYER","P1"),actor,DomainRef("CAMPAIGN","C1")).map{CampaignScopedDomainRef("C1",it)}.toSet()
        val admitted=CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",player,refs)) as CampaignMutationAdmission.Accepted
        assertTrue(TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN:$uid",uid,"TX:$uid"),admitted.proposal).commit() is TurnExecutionResult.Committed)
    }
    @Test fun undoAndAlternativeHistoryReplayBrainWithoutRetainingRemovedGoals() {
        val file=File(folder.root,"campaign.db");val snapshots=File(folder.root,"snapshots")
        var db=SQLiteDatabase.openOrCreateDatabase(file,null)
        try {
            db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
            db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
            GroupATransactionTestFixtures.setupFinance(db)
            CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
            commit(db,"ONE");commit(db,"TWO");commit(db,"REMOVED")
            val backup=CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.MANUAL_BACKUP,true)
            fun undo():SQLiteDatabase {
                val coordinator=DestructiveTurnUndoCoordinator(db,"C1",snapshots,file)
                val preview=coordinator.previewLastTurn();assertTrue(preview.toString(),preview.canConfirm)
                val result=coordinator.confirm(preview);assertTrue(result.toString(),result is DestructiveUndoResult.Completed)
                return SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).also{GameplayRuntimeBootstrap.requireReady(it,"C1")}
            }
            db=undo()
            assertEquals("Cel TWO",NpcBrainStore(db,"C1").read(actor)!!.goals.single().objective)
            commit(db,"ALTERNATIVE");val alternative=NpcBrainStore(db,"C1").read(actor)
            commit(db,"REMOVED_AGAIN")
            db=undo()
            assertEquals(alternative,NpcBrainStore(db,"C1").read(actor))
            assertNull(TurnTransactionReceiptStore(db).committedCommand("C1","REMOVED"))
            assertNull(TurnTransactionReceiptStore(db).committedCommand("C1","REMOVED_AGAIN"))
            db.rawQuery("SELECT change_canonical FROM ${Phase61NpcSchema.HISTORY}",null).use{c->
                while(c.moveToNext())assertFalse(c.getString(0).contains("REMOVED"))
            }
            assertTrue(File(backup.payloadPath).isFile)
            commit(db,"CONTINUED")
        } finally { if(db.isOpen)db.close() }
    }
}
