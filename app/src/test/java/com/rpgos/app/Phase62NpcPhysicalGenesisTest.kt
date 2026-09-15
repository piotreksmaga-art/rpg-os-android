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
class Phase62NpcPhysicalGenesisTest {
    @get:Rule val folder=TemporaryFolder()
    private fun setup(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
        GroupATransactionTestFixtures.setupFinance(db)
    }
    private fun effect(uid:String="N",legacy:Boolean=false):VerifiedMechanicsCommandEffect {
        val fp=phase60Hash("new:$uid")
        val payload=mapOf("world_base_kind" to "ACTOR","display_name" to "Podróżnik $uid","category_uid" to "PERSON",
            "parent_anchor_uid" to "PLACE:VILLAGE","topology_class_uid" to "LOCAL","source_classification" to "GENERATED_PLAUSIBLE")+
            if(legacy)emptyMap() else mapOf("draft_fingerprint" to fp)
        return VerifiedMechanicsCommandEffect("WORLD:$uid","NODE","RPGOS-CORE:WORLD-MATERIALIZER","WORLD_ELEMENT_MATERIALIZE",
            DomainRef("ACTOR",uid),1,payload,"RPGOS-CORE:WORLD-MATERIALIZATION:$fp",fp,fp)
    }
    private fun proposal(uid:String="N",legacy:Boolean=false,order:Long=1):CanonicalCampaignMutationProposal {
        val actor=CommandActorRef("PLAYER","P")
        val command=PlayerCommand(commandUid="CMD:$uid",campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN:$uid",listOf(effect(uid,legacy))),provenance=CommandProvenance("TEST"),requestedEffectiveOrder=order)
        val refs=setOf(DomainRef("PLAYER","P"),DomainRef("ACTOR",uid),DomainRef("LOCATION","PLACE:VILLAGE"))
            .map{CampaignScopedDomainRef("C1",it)}.toSet()
        val result=CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",actor,refs))
        assertTrue(result.toString(),result is CampaignMutationAdmission.Accepted)
        return (result as CampaignMutationAdmission.Accepted).proposal
    }
    private fun commit(db:SQLiteDatabase,uid:String="N",legacy:Boolean=false,injector:TurnFailureInjector=TurnFailureInjector.NONE,order:Long=1)=
        TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN:$uid","CMD:$uid","TX:$uid"),proposal(uid,legacy,order),injector).commit()

    @Test fun bodyAndWorldIdentityCommitTogetherAndReadDoesNotGenerateAnything()=SQLiteDatabase.create(null).use { db->
        setup(db)
        val store=MechanicalActorStateStore(db,"C1")
        assertNull(store.actor(DomainRef("ACTOR","N")))
        val before=AuthoritativeStateDigest.compute(db)
        assertTrue(commit(db) is TurnExecutionResult.Committed)
        val body=requireNotNull(store.actor(DomainRef("ACTOR","N")))
        assertEquals(MechanicalStateMaterialization.FULL,body.materialization)
        assertEquals(MechanicalActorKind.NPC,body.kind)
        assertTrue(NpcActivityMechanics.available(body,requireNotNull(NpcActivityContractPort.STANDARD.contract("C1","REST"))))
        assertNotEquals(before,AuthoritativeStateDigest.compute(db))
        db.rawQuery("SELECT location_uid,x_coord,y_coord FROM entity_positions WHERE entity_uid='N'",null).use {
            assertTrue(it.moveToFirst());assertEquals("PLACE:VILLAGE",it.getString(0));assertTrue(it.isNull(1));assertTrue(it.isNull(2))
        }
        val after=AuthoritativeStateDigest.compute(db)
        assertTrue(commit(db) is TurnExecutionResult.AlreadyCommitted)
        assertEquals(after,AuthoritativeStateDigest.compute(db))
    }
    @Test fun failureRollsBackPhysicalStateWithWorldIdentity()=SQLiteDatabase.create(null).use { db->
        setup(db);val before=AuthoritativeStateDigest.compute(db)
        assertTrue(runCatching{commit(db,injector=TurnFailureInjector{if(it==TurnFailurePoint.AFTER_SECOND_DOMAIN_WRITE)error("injected")})}.isFailure)
        assertNull(MechanicalActorStateStore(db,"C1").actor(DomainRef("ACTOR","N")))
        assertEquals(before,AuthoritativeStateDigest.compute(db))
    }
    @Test fun genesisHasClosedCodecAndRequiresExactSameCommitWorldFacts() {
        val proposal=proposal();val set=proposal.playerChangeSet
        val body=set.changes.mapNotNull{it.payload as? MechanicalActorGenesisChange}.single()
        assertEquals(body,mechanicalActorGenesisCodec().decode(mechanicalActorGenesisCodec().encode(body)))
        MechanicalActorGenesis.validate(body,set)
        assertTrue(runCatching{MechanicalActorGenesis.validate(body.copy(displayName="Inny"),set)}.isFailure)
        assertTrue(runCatching{MechanicalActorGenesis.validate(body.copy(actor=DomainRef("ACTOR","P")),set)}.isFailure)
        assertTrue(runCatching{body.copy(profileVersion=2)}.isFailure)
        assertTrue(runCatching{MechanicalActorGenesis.validate(body.copy(worldProofUid="RPGOS-CORE:WORLD-MATERIALIZATION:${"f".repeat(64)}"),set)}.isFailure)
    }
    @Test fun legacyWorldPayloadDoesNotRetroactivelyCreateBody()=SQLiteDatabase.create(null).use { db->
        setup(db);assertTrue(commit(db,legacy=true) is TurnExecutionResult.Committed)
        assertNull(MechanicalActorStateStore(db,"C1").actor(DomainRef("ACTOR","N")))
    }
    @Test fun prefixReplayAndUndoRemoveOnlyTheDiscardedBody() {
        val file=File(folder.root,"campaign.db");val snapshots=File(folder.root,"snapshots")
        var db=SQLiteDatabase.openOrCreateDatabase(file,null)
        try {
            setup(db);CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
            assertTrue(commit(db,"KEPT") is TurnExecutionResult.Committed)
            val kept=MechanicalActorStateStore(db,"C1").actor(DomainRef("ACTOR","KEPT"))
            assertTrue(commit(db,"REMOVED",order=2) is TurnExecutionResult.Committed)
            val undo=DestructiveTurnUndoCoordinator(db,"C1",snapshots,file)
            val preview=undo.previewLastTurn();assertTrue(preview.toString(),preview.canConfirm)
            val result=undo.confirm(preview);assertTrue(result.toString(),result is DestructiveUndoResult.Completed)
            db=SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
            GameplayRuntimeBootstrap.requireReady(db,"C1")
            assertEquals(kept,MechanicalActorStateStore(db,"C1").actor(DomainRef("ACTOR","KEPT")))
            assertNull(MechanicalActorStateStore(db,"C1").actor(DomainRef("ACTOR","REMOVED")))
            assertTrue(commit(db,"ALTERNATIVE",order=2) is TurnExecutionResult.Committed)
        } finally {if(db.isOpen)db.close()}
    }
}
