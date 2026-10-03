package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase63WorldAuthorityTest {
    private val source=CampaignRuleSource(CampaignRuleSourceKind.WORLD_PACK,"NARUTO","1")
    private val anchor=DomainRef("PLACE","KONOHA")
    private val skeleton get()=CampaignWorldSkeleton.legacy("C1",source,"ERA",anchor)
    private val identity=TurnTransactionIdentity("C1","WORLD-TURN","WORLD-CMD","WORLD-TX")
    private fun database()=SQLiteDatabase.create(null).also(GroupATransactionTestFixtures::setupFinance)
    private fun change(db:SQLiteDatabase)=WorldSimulationChange("C1",HistoryGenerationStore(db,"C1").current(),0,skeleton)
    private fun proposal(change:WorldSimulationChange):CanonicalCampaignMutationProposal {
        val actor=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(commandUid=identity.commandUid,campaignUid="C1",actor=actor,
            commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("WORLD-PLAN",emptyList(),worldChanges=listOf(change)),
            provenance=CommandProvenance("P63-TEST"),requestedEffectiveOrder=1)
        val refs=listOf(DomainRef("PLAYER","P1"),DomainRef("CAMPAIGN","C1"),
            DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A"),
            DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B"),DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR"))
            .plus(change.actorExpansions.map { it.actor }).map { CampaignScopedDomainRef("C1",it) }.toSet()
        val admission=CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",actor,refs))
        assertTrue(admission.toString(),admission is CampaignMutationAdmission.Accepted)
        return (admission as CampaignMutationAdmission.Accepted).proposal
    }
    @Test fun emptyAdditiveSchemaAndMissingRootDoNotMutateLegacyDigest()=SQLiteDatabase.create(null).use { db->
        val digest=AuthoritativeStateDigest.compute(db)
        Phase63WorldSchema.ensureReady(db)
        assertNull(Phase63WorldStore(db,"C1").root())
        assertEquals(digest,AuthoritativeStateDigest.compute(db))
    }
    @Test fun commitRetryAndCrossCampaignIsolation()=database().use { db->
        val before=AuthoritativeStateDigest.compute(db)
        val proposal=proposal(change(db))
        assertTrue(TurnTransactionBoundary.create(db,identity,proposal).commit() is TurnExecutionResult.Committed)
        assertEquals(CanonicalWorldRoot(1,skeleton),Phase63WorldStore(db,"C1").root())
        assertNull(Phase63WorldStore(db,"C2").root())
        assertNotEquals(before,AuthoritativeStateDigest.compute(db))
        val digest=AuthoritativeStateDigest.compute(db)
        assertTrue(TurnTransactionBoundary.create(db,identity,proposal).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(digest,AuthoritativeStateDigest.compute(db))
        assertEquals(ReplayAuthorityCoverage.REPLAYABLE,CampaignReplayAuthorityMatrix.coverage("WORLD_SIMULATION_AUTHORITY"))
    }
    @Test fun failureAfterWorldWriteRollsBackRootAndReceipt()=database().use { db->
        val before=AuthoritativeStateDigest.compute(db)
        val failure=TurnFailureInjector { if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("injected") }
        assertEquals("injected",runCatching { TurnTransactionBoundary.create(db,identity,proposal(change(db)),failure).commit() }.exceptionOrNull()?.message)
        assertNull(Phase63WorldStore(db,"C1").root())
        assertNull(TurnTransactionReceiptStore(db).committedCommand("C1",identity.commandUid))
        assertEquals(before,AuthoritativeStateDigest.compute(db))
    }
    @Test fun staleGenerationCannotCreateWorld()=database().use { db->
        val stale=change(db).copy(historyGenerationUid=HistoryGenerationUid("STALE"))
        assertEquals("P63:STALE_HISTORY",runCatching { TurnTransactionBoundary.create(db,identity,proposal(stale)).commit() }.exceptionOrNull()?.message)
        assertNull(Phase63WorldStore(db,"C1").root())
    }
    @Test fun codecsRejectOverflowStringNumbersAndPreserveOldPayloadShape()=database().use { db->
        val change=change(db);val codec=phase63WorldChangeCodec()
        assertEquals(change,codec.decode(codec.encode(change)))
        assertTrue(runCatching { codec.decode(JsonObject(codec.encode(change)+("expected_version" to JsonPrimitive("0")))) }.isFailure)
        val encoded=Phase63WorldCodec.skeleton(skeleton)
        assertTrue(runCatching { Phase63WorldCodec.readSkeleton(JsonObject(encoded+("generator" to JsonPrimitive(4294967297L)))) }.isFailure)
        @Suppress("UNCHECKED_CAST") val commandCodec=coreCommandCodecs().getValue(PlayerCommandKinds.APPLY_VERIFIED_MECHANICS) as TypedCommandCodec<ApplyVerifiedMechanicsCommandPayload>
        val payload=ApplyVerifiedMechanicsCommandPayload("P",emptyList(),worldChanges=listOf(change))
        assertEquals(payload,commandCodec.decode(commandCodec.encode(payload)))
    }
    @Test fun identitiesAreIndependentOfGenerationQueryOrderAndUnrelatedSlots() {
        val a=LatentWorldSlot("KONOHA","TRAINING_GROUND",WorldElementBaseKind.PLACE)
        val b=a.copy(ordinal=1)
        assertEquals(a.ref(skeleton),a.ref(skeleton))
        assertNotEquals(a.ref(skeleton),b.ref(skeleton))
        assertNotEquals(skeleton.domainSeed("MECHANICS",a.canonicalKey),skeleton.domainSeed("APPEARANCE",a.canonicalKey))
        val reverse=listOf(b,a).associateWith { it.ref(skeleton) }
        assertEquals(listOf(a,b).associateWith { it.ref(skeleton) },reverse)
    }
    @Test fun expansionPreservesExistingComponentsAndRollback()=database().use { db->
        val actor=DomainRef("ACTOR","A63")
        withAdministrativeMutationAuthority(db,"C1") {
            MechanicalActorStateStore(db,"C1").materializeIfMissing(Phase63ActorGeneration.seed(actor,phase63Hash("slot"),MechanicalStateMaterialization.PARTIAL))
            db.execSQL("INSERT INTO mechanical_actor_attributes VALUES('C1','ACTOR','A63','DEFENCE',17,1)")
            db.execSQL("INSERT INTO mechanical_actor_resources VALUES('C1','ACTOR','A63','HEALTH',23,31,1)")
            db.execSQL("INSERT INTO mechanical_actor_tracks VALUES('C1','ACTOR','A63','WOUND',9,1)")
        }
        val before=AuthoritativeStateDigest.compute(db)
        val change=change(db).copy(actorExpansions=listOf(MechanicalActorExpansion(actor,1,MechanicalStateMaterialization.FULL)))
        val candidate=proposal(change)
        val preview=requireNotNull(Phase50ActorExpansion.preview(db,"C1",requireNotNull(MechanicalActorStateStore(db,"C1").actor(actor))))
        assertEquals(17L,preview.unwoundedDefence)
        assertEquals(8L,preview.attributes["DEFENCE"])
        assertEquals(MechanicalResource("HEALTH",23,31),preview.resources.single { it.resourceUid=="HEALTH" })
        val failure=TurnFailureInjector { if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("injected") }
        assertTrue(runCatching { TurnTransactionBoundary.create(db,identity,candidate,failure).commit() }.isFailure)
        assertEquals(before,AuthoritativeStateDigest.compute(db))
        assertEquals(MechanicalStateMaterialization.PARTIAL,MechanicalActorStateStore(db,"C1").actor(actor)?.materialization)
        assertTrue(TurnTransactionBoundary.create(db,identity,candidate).commit() is TurnExecutionResult.Committed)
        val full=requireNotNull(MechanicalActorStateStore(db,"C1").actor(actor))
        assertEquals(MechanicalStateMaterialization.FULL,full.materialization)
        assertEquals(17L,full.unwoundedDefence)
        assertEquals(MechanicalResource("HEALTH",23,31),full.resources.single { it.resourceUid=="HEALTH" })
        val digest=AuthoritativeStateDigest.compute(db)
        assertTrue(TurnTransactionBoundary.create(db,identity,candidate).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(digest,AuthoritativeStateDigest.compute(db))
    }
}
