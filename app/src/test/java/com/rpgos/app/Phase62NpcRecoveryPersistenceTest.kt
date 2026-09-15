package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase62NpcRecoveryPersistenceTest {
    @get:Rule val folder=TemporaryFolder()
    private val npc=DomainRef("NPC","N")
    private fun setup(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        GroupATransactionTestFixtures.setupFinance(db)
        withAdministrativeMutationAuthority(db,"C1") {
            MechanicalActorStateStore(db,"C1").materializeIfMissing(MechanicalActorSeed(npc,MechanicalActorKind.NPC,"T","S","TEST",
                mapOf("POWER" to 10),listOf(MechanicalResource("STAMINA",98,100),MechanicalResource("HEALTH",80,100)),setOf("ATTACK")))
        }
    }
    private fun proposal(uid:String,order:Long):CanonicalCampaignMutationProposal {
        val actor=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(commandUid="CMD:$uid",campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN:$uid",Phase62NpcRecoveryTest.resolvedDefaultRecoveryEffects(uid)),
            provenance=CommandProvenance("TEST"),requestedEffectiveOrder=order)
        val refs=setOf(DomainRef("PLAYER","P1"),npc,DomainRef("RESOURCE","STAMINA")).map{CampaignScopedDomainRef("C1",it)}.toSet()
        val result=CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",actor,refs))
        assertTrue(result.toString(),result is CampaignMutationAdmission.Accepted)
        return (result as CampaignMutationAdmission.Accepted).proposal
    }
    private fun commit(db:SQLiteDatabase,uid:String,order:Long=1,injector:TurnFailureInjector=TurnFailureInjector.NONE)=
        TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN:$uid","CMD:$uid","TX:$uid"),proposal(uid,order),injector).commit()
    private fun stamina(db:SQLiteDatabase)=MechanicalActorStateStore(db,"C1").actor(npc)!!.resources.single{it.resourceUid=="STAMINA"}.current
    private fun effort(db:SQLiteDatabase)=db.rawQuery("SELECT current_value FROM mechanical_actor_tracks WHERE entity_uid='N' AND track_uid='ACTION:REST'",null)
        .use{if(it.moveToFirst())it.getLong(0) else 0L}

    @Test fun effortAndRestorationCommitTogetherAndRetryCannotDuplicateEither()=SQLiteDatabase.create(null).use { db->
        setup(db)
        assertTrue(commit(db,"REST") is TurnExecutionResult.Committed)
        assertEquals(99L,stamina(db));assertEquals(1L,effort(db))
        val digest=AuthoritativeStateDigest.compute(db)
        assertTrue(commit(db,"REST") is TurnExecutionResult.AlreadyCommitted)
        assertEquals(digest,AuthoritativeStateDigest.compute(db))
        assertEquals(80L,MechanicalActorStateStore(db,"C1").actor(npc)!!.resources.single{it.resourceUid=="HEALTH"}.current)
    }

    @Test fun failureBetweenDomainWritesLeavesNoPartialRestoration()=SQLiteDatabase.create(null).use { db->
        setup(db);val before=AuthoritativeStateDigest.compute(db)
        assertTrue(runCatching{commit(db,"FAIL",injector=TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("injected")})}.isFailure)
        assertEquals(before,AuthoritativeStateDigest.compute(db));assertEquals(98L,stamina(db));assertEquals(0L,effort(db))
        assertTrue(commit(db,"FAIL") is TurnExecutionResult.Committed)
        assertEquals(99L,stamina(db));assertEquals(1L,effort(db))
    }

    @Test fun prefixReplayUndoAndReopenPreserveOnlyTheRetainedRestoration() {
        val file=File(folder.root,"campaign.db");val snapshots=File(folder.root,"snapshots")
        var db=SQLiteDatabase.openOrCreateDatabase(file,null)
        try {
            setup(db);CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
            assertTrue(commit(db,"FIRST",1) is TurnExecutionResult.Committed)
            assertTrue(commit(db,"SECOND",2) is TurnExecutionResult.Committed)
            assertEquals(100L,stamina(db));assertEquals(2L,effort(db))
            val undo=DestructiveTurnUndoCoordinator(db,"C1",snapshots,file)
            val preview=undo.previewLastTurn();assertTrue(preview.toString(),preview.canConfirm)
            val result=undo.confirm(preview);assertTrue(result.toString(),result is DestructiveUndoResult.Completed)
            db=SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
            GameplayRuntimeBootstrap.requireReady(db,"C1")
            assertEquals(99L,stamina(db));assertEquals(1L,effort(db))
            assertTrue(commit(db,"ALTERNATIVE",2) is TurnExecutionResult.Committed)
            assertEquals(100L,stamina(db))
        } finally {if(db.isOpen)db.close()}
    }
}
