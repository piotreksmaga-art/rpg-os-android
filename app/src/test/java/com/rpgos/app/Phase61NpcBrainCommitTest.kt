package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase61NpcBrainCommitTest {
    private val actor=DomainRef("NPC","N1")
    private fun database()=SQLiteDatabase.create(null).also { db ->
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        GroupATransactionTestFixtures.setupFinance(db)
    }
    private fun genesis(db:SQLiteDatabase):NpcBrainChange {
        val state=NpcBrainOwner.initialize("C1",actor,"WORLD")
        return NpcBrainChange("C1",actor,HistoryGenerationStore(db,"C1").current().value,0,null,NpcBrainCodec.encode(state),
            NpcBrainRules.GENESIS.uid,1,listOf(NpcCauseRef(NpcCauseKind.GENESIS,"P61:GENESIS:${state.seedFingerprint}")))
    }
    private fun proposal(change:NpcBrainChange,uid:String="CMD"):CanonicalCampaignMutationProposal {
        val player=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(commandUid=uid,campaignUid="C1",actor=player,commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN",emptyList(),npcBrains=listOf(change)),
            provenance=CommandProvenance("TEST"),requestedEffectiveOrder=change.expectedVersion+1)
        val refs=setOf(DomainRef("PLAYER","P1"),actor,DomainRef("CAMPAIGN","C1")).map{CampaignScopedDomainRef("C1",it)}.toSet()
        val result=CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",player,refs))
        assertTrue(result.toString(),result is CampaignMutationAdmission.Accepted)
        return (result as CampaignMutationAdmission.Accepted).proposal
    }
    private fun identity(uid:String="CMD")=TurnTransactionIdentity("C1","TURN:$uid",uid,"TX:$uid")

    @Test fun brainUsesOrdinaryAtomicCommitAndIdempotentReceipt()=database().use { db ->
        val before=AuthoritativeStateDigest.compute(db)
        val change=genesis(db)
        val admitted=proposal(change)
        assertTrue(TurnTransactionBoundary.create(db,identity(),admitted).commit() is TurnExecutionResult.Committed)
        assertEquals(NpcBrainCodec.decode(change.stateCanonical),NpcBrainStore(db,"C1").read(actor))
        assertNotEquals(before,AuthoritativeStateDigest.compute(db))
        val after=AuthoritativeStateDigest.compute(db)
        assertTrue(TurnTransactionBoundary.create(db,identity(),admitted).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(after,AuthoritativeStateDigest.compute(db))
        assertNull(NpcBrainStore(db,"OTHER").read(actor))
        assertEquals(ReplayAuthorityCoverage.REPLAYABLE,CampaignReplayAuthorityMatrix.coverage("NPC_BRAIN_AUTHORITY"))
    }
    @Test fun failureRollsBackStateHistoryAndReceipt()=database().use { db ->
        val before=AuthoritativeStateDigest.compute(db)
        val failure=TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("injected")}
        assertEquals("injected",runCatching{TurnTransactionBoundary.create(db,identity(),proposal(genesis(db)),failure).commit()}.exceptionOrNull()?.message)
        assertNull(NpcBrainStore(db,"C1").read(actor))
        assertEquals(before,AuthoritativeStateDigest.compute(db))
        db.rawQuery("SELECT COUNT(*) FROM ${Phase61NpcSchema.HISTORY}",null).use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }
    @Test fun canonicalCodecAndCommandCodecRoundTrip()=database().use { db ->
        val change=genesis(db)
        assertEquals(change,npcBrainChangeCodec().decode(npcBrainChangeCodec().encode(change)))
        val payload=ApplyVerifiedMechanicsCommandPayload("PLAN",emptyList(),npcBrains=listOf(change))
        val codec=coreCommandCodecs().getValue(PlayerCommandKinds.APPLY_VERIFIED_MECHANICS)
        assertEquals(payload,codec.decode(codec.encodeUntyped(payload)))
    }
    @Test fun missingOrWrongHistoryAndUnverifiedGenesisCannotCommit()=database().use { db ->
        val good=genesis(db)
        for(bad in listOf(good.copy(historyGenerationUid="STALE"),good.copy(causes=listOf(NpcCauseRef(NpcCauseKind.GENESIS,"invented"))))) {
            assertTrue(runCatching{TurnTransactionBoundary.create(db,identity(),proposal(bad)).commit()}.isFailure)
            assertNull(NpcBrainStore(db,"C1").read(actor))
        }
    }
    @Test fun directSqlAndAiStatePatchCannotWriteBrain()=database().use { db ->
        assertTrue(runCatching{db.execSQL("INSERT INTO ${Phase61NpcSchema.STATES} VALUES('C1','NPC','N1',1,'{}','x','t')")}.isFailure)
        assertFalse(SourceOfTruthRegistry(db).canWrite(Phase61NpcSchema.STATES))
        RuntimeTruthLayerRegistry.validateCanonicalInventory()
    }
    @Test fun additiveSchemaDoesNotChangeOldReceiptDigest()=SQLiteDatabase.create(null).use { db ->
        val before=AuthoritativeStateDigest.compute(db)
        Phase61NpcSchema.ensureReady(db)
        assertTrue(Phase61NpcSchema.isReady(db))
        assertEquals(before,AuthoritativeStateDigest.compute(db))
    }
}
