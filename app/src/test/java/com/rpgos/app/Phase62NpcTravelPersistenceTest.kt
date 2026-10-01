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

/** Persists effects produced by the real travel resolver, not handwritten movement deltas.
 * This complements the pending-action codec tests; it is not an Android process-death test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase62NpcTravelPersistenceTest {
    @get:Rule val folder=TemporaryFolder()
    private val npc=DomainRef("NPC","N")
    private fun setup(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
        db.execSQL("INSERT INTO entity_positions VALUES('N','A',12,34,0,0)")
        GroupATransactionTestFixtures.setupFinance(db)
        withAdministrativeMutationAuthority(db,"C1") {
            MechanicalActorStateStore(db,"C1").materializeIfMissing(MechanicalActorSeed(npc,MechanicalActorKind.NPC,"T","S","TEST",
                mapOf("POWER" to 10),listOf(MechanicalResource("STAMINA",98,100),MechanicalResource("SUPPLIES",10,10),MechanicalResource("HEALTH",80,100)),setOf("WORLD:WALK")))
        }
    }
    private fun proposal(uid:String):CanonicalCampaignMutationProposal {
        val actor=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(commandUid="CMD:$uid",campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN:$uid",Phase62NpcTravelLifecycleTest.resolvedTravelEffects(uid)),
            provenance=CommandProvenance("TEST"),requestedEffectiveOrder=1)
        val refs=setOf(DomainRef("PLAYER","P1"),npc,DomainRef("RESOURCE","STAMINA"),DomainRef("RESOURCE","SUPPLIES"),
            DomainRef("PLACE","A"),DomainRef("PLACE","B")).map{CampaignScopedDomainRef("C1",it)}.toSet()
        val result=CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",actor,refs))
        assertTrue(result.toString(),result is CampaignMutationAdmission.Accepted)
        return (result as CampaignMutationAdmission.Accepted).proposal
    }
    private fun commit(db:SQLiteDatabase,uid:String,injector:TurnFailureInjector=TurnFailureInjector.NONE)=
        TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN:$uid","CMD:$uid","TX:$uid"),proposal(uid),injector).commit()
    private fun location(db:SQLiteDatabase)=db.rawQuery("SELECT location_uid FROM entity_positions WHERE entity_uid='N'",null).use{assertTrue(it.moveToFirst());it.getString(0)}
    private fun resource(db:SQLiteDatabase,uid:String)=MechanicalActorStateStore(db,"C1").actor(npc)!!.resources.single{it.resourceUid==uid}.current
    private fun assertOrigin(db:SQLiteDatabase) {
        assertEquals("A",location(db));assertEquals(DomainRef("LOCATION","A"),MechanicalActorStateStore(db,"C1").actor(npc)!!.locationRef)
        assertEquals(98L,resource(db,"STAMINA"));assertEquals(10L,resource(db,"SUPPLIES"))
    }
    private fun assertArrival(db:SQLiteDatabase) {
        assertEquals("B",location(db));assertEquals(DomainRef("LOCATION","B"),MechanicalActorStateStore(db,"C1").actor(npc)!!.locationRef)
        assertEquals(95L,resource(db,"STAMINA"));assertEquals(8L,resource(db,"SUPPLIES"))
        assertEquals(80L,resource(db,"HEALTH"))
    }

    private fun route()=NpcTravelRouteContract("C1","ROAD",1,DomainRef("PLACE","A"),DomainRef("PLACE","B"),
        ActionDuration(120000),"WORLD:ROAD",capabilityUid="WORLD:WALK",resourceCosts=mapOf("STAMINA" to 3L,"SUPPLIES" to 2L))

    private fun committedArrivalEvidence(db:SQLiteDatabase,receipt:TurnCommitReceipt):NpcActivityResolutionEvidence? {
        val route=route();val owner=NpcTravelAffordances.ownerContract(route)
        val attempt=NpcActivityAttemptIdentity(
            campaignUid="C1",historyGenerationUid="H",actor=npc,planUid="PLAN:TRAVEL",optionUid="OPTION:TRAVEL",
            capabilityUid=route.capabilityUid,authorizedAt=WorldTimeTick(0),dueAt=WorldTimeTick(120000),
            authorizationFingerprint=phase60Hash("TRAVEL-AUTH"),ownerContractFingerprint=owner.fingerprint
        )
        val replay=CommittedReplayPayloadStore(db).after("C1",0).singleOrNull{it.identity.transactionUid==receipt.transactionUid}?:return null
        return NpcTravelArrivalEvidence.fromCommitted(attempt,route,receipt,replay)
    }

    @Test fun costsAndArrivalCommitTogetherAndRetryDoesNotChargeAgain()=SQLiteDatabase.create(null).use{db->
        setup(db);assertOrigin(db)
        val committed=commit(db,"TRAVEL") as TurnExecutionResult.Committed
        assertArrival(db)
        val evidence=requireNotNull(committedArrivalEvidence(db,committed.receipt))
        assertEquals(NpcActivityResolutionKind.SUCCEEDED,evidence.resolutionKind)
        assertEquals(listOf(PlayerChangeKinds.SPATIAL),evidence.canonicalEvidence.map{it.changeKindUid})
        val digest=AuthoritativeStateDigest.compute(db)
        assertTrue(commit(db,"TRAVEL") is TurnExecutionResult.AlreadyCommitted)
        assertEquals(digest,AuthoritativeStateDigest.compute(db));assertArrival(db)
    }

    @Test fun failureBetweenWritesRollsBackBothCostsAndArrival()=SQLiteDatabase.create(null).use{db->
        setup(db);val before=AuthoritativeStateDigest.compute(db)
        assertTrue(runCatching{commit(db,"FAIL",TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("injected")})}.isFailure)
        assertEquals(before,AuthoritativeStateDigest.compute(db));assertOrigin(db)
        assertTrue(commit(db,"FAIL") is TurnExecutionResult.Committed);assertArrival(db)
    }

    @Test fun fileReopenAndUndoRestoreOriginAndCostsThenAllowDifferentTransaction() {
        val file=File(folder.root,"campaign.db");val snapshots=File(folder.root,"snapshots")
        var db=SQLiteDatabase.openOrCreateDatabase(file,null)
        try {
            setup(db);CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
            assertTrue(commit(db,"FIRST") is TurnExecutionResult.Committed);assertArrival(db)
            db.close();db=SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
            GameplayRuntimeBootstrap.requireReady(db,"C1");assertArrival(db)
            val undo=DestructiveTurnUndoCoordinator(db,"C1",snapshots,file)
            val preview=undo.previewLastTurn();assertTrue(preview.toString(),preview.canConfirm)
            val result=undo.confirm(preview);assertTrue(result.toString(),result is DestructiveUndoResult.Completed)
            db=SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
            GameplayRuntimeBootstrap.requireReady(db,"C1");assertOrigin(db)
            assertTrue(commit(db,"ALTERNATIVE") is TurnExecutionResult.Committed);assertArrival(db)
        } finally { if(db.isOpen)db.close() }
    }
}
