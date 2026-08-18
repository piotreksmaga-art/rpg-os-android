package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Work026ProgressionCommitIntegrationTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val binding = WorldPackRuleBinding("WORLD-P20", "1")

    @Test fun progression_e2e_commits_once_retries_without_duplicate_and_rolls_back_atomically() {
        resetProbes()
        SQLiteDatabase.create(null).use { db ->
            setupStat(db)
            val command = command("CMD-PROG-COMMIT")
            val admitted = admit(command)
            assertEquals(1, admitted.playerChangeSet.changes.size)
            assertTrue(admitted.playerChangeSet.eventIntents.isEmpty())
            val generatedStatChange = admitted.playerChangeSet.changes.single().payload as StatChange
            assertTrue(generatedStatChange.delta.units > 0L)
            assertEquals(PlayerLedgerIntentKinds.PROGRESSION, admitted.playerChangeSet.ledgerIntents.single().ledgerKindUid)
            assertTrue(admitted.playerChangeSet.ledgerIntents.single().payload is ProgressionLedgerIntentPayload)
            assertEquals(listOf(admitted.playerChangeSet.changes.single().changeUid),admitted.playerChangeSet.ledgerIntents.single().causalChangeUids)
            assertEquals(1, precheckCalls);assertEquals(1, effectCheckCalls);assertEquals(1, invariantSnapshotCalls)

            val expectedCommittedValue = 10.0 + generatedStatChange.delta.units.toDouble()
            val identity = TurnTransactionIdentity("C1", "TURN-PROG-COMMIT", command.commandUid, "TX-PROG-COMMIT")
            val committed = TurnTransactionBoundary.create(db, identity, admitted).commit()
            assertTrue(committed is TurnExecutionResult.Committed)
            val receipt=(committed as TurnExecutionResult.Committed).receipt
            assertEquals(expectedCommittedValue, StatResourceStore(db,"C1").playerStats("P1").single{it.statUid=="STR"}.baseValue,0.0)
            assertEquals(1L,receiptCount(db));assertEquals(1L,eventCount(db,identity.transactionUid))
            assertEquals(1,receipt.requiredEventCount);assertNotNull(receipt.requiredEventManifestFingerprint);assertNotNull(receipt.commitOrder)
            val eventOrder=db.rawQuery("SELECT committed_order,event_ordinal FROM canonical_gameplay_events WHERE campaign_uid='C1' AND transaction_uid=?",arrayOf(identity.transactionUid)).use{c->c.moveToFirst();c.getLong(0) to c.getInt(1)}
            assertEquals(receipt.commitOrder,eventOrder.first);assertEquals(0,eventOrder.second)

            val retry=TurnTransactionBoundary.create(db,identity,admitted).commit();assertTrue(retry is TurnExecutionResult.AlreadyCommitted)
            assertEquals(expectedCommittedValue,StatResourceStore(db,"C1").playerStats("P1").single{it.statUid=="STR"}.baseValue,0.0)
            assertEquals(1L,receiptCount(db));assertEquals(1L,eventCount(db,identity.transactionUid))
            assertFalse(tableExists(db,"progression_ledger"));assertFalse(tableExists(db,"progression_ledger_entries"))
        }

        resetProbes()
        SQLiteDatabase.create(null).use { db ->
            setupStat(db)
            val command=command("CMD-PROG-ROLLBACK");val admitted=admit(command);val identity=TurnTransactionIdentity("C1","TURN-PROG-ROLLBACK",command.commandUid,"TX-PROG-ROLLBACK")
            val failure=runCatching{TurnTransactionBoundary.create(db,identity,admitted,TurnFailureInjector{point->if(point==TurnFailurePoint.AFTER_FIRST_WRITE)error("forced rollback")}).commit()}.exceptionOrNull()
            assertNotNull(failure);assertEquals(10.0,StatResourceStore(db,"C1").playerStats("P1").single{it.statUid=="STR"}.baseValue,0.0);assertEquals(0L,receiptCount(db));assertEquals(0L,eventCount(db,identity.transactionUid))
            val laterRetry=TurnTransactionBoundary.create(db,identity,admitted).commit();assertTrue(laterRetry is TurnExecutionResult.Committed);val generatedStatChange=admitted.playerChangeSet.changes.single().payload as StatChange;assertEquals(10.0+generatedStatChange.delta.units.toDouble(),StatResourceStore(db,"C1").playerStats("P1").single{it.statUid=="STR"}.baseValue,0.0);assertEquals(1L,receiptCount(db));assertEquals(1L,eventCount(db,identity.transactionUid))
        }
    }

    private fun setupStat(db:SQLiteDatabase){CurrentSchema.ensure(db,"C1");val store=StatResourceStore(db,"C1");store.registerStatDefinitions("WP",listOf(StatDefinition("STR","str","CORE",minValue=0.0,maxValue=200.0,worldPackUid="WP")));store.savePlayerStat(PlayerStat("C1","P1","STR",10.0));GameplayRuntimeBootstrap.initialize(db,"C1")}
    private fun command(uid:String)=PlayerCommand(commandUid=uid,campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.TRAIN,payload=TrainCommandPayload(DomainRef("STAT","STR"),10L,"METHOD"),provenance=CommandProvenance("WORK-026"))
    private fun admit(command:PlayerCommand<TrainCommandPayload>):CanonicalCampaignMutationProposal{val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(ProgressionComponent())),worldRuleRegistry=WorldRuleProviderRegistry.of(listOf(AllowingProbeProvider())),worldPackAuthority=WorldPackAuthoritySnapshot.single("C1",binding),invariantSnapshotResolver=PlayerInvariantSnapshotResolver{campaignUid,_->invariantSnapshotCalls++;PlayerInvariantSnapshot.create(campaignUid)});val context=PlayerResolutionContext.create(campaignUid="C1",actor=actor,knownReferences=setOf(CampaignScopedDomainRef("C1",DomainRef("PLAYER","P1")),CampaignScopedDomainRef("C1",DomainRef("STAT","STR"))),worldRuleMode=WorldRuleMode.Bound(binding));return when(val admission=CampaignMutationBoundary.resolveAndAdmit("C1",engine,command,context)){is CampaignMutationAdmission.Accepted->admission.proposal;is CampaignMutationAdmission.Rejected->error("progression admission rejected: ${admission.reasonUid}")}}
    private class ProgressionComponent:PlayerResolutionComponent<TrainCommandPayload>(PlayerCommandKinds.TRAIN,TrainCommandPayload::class,"RPGOS-COMPONENT:WORK-026-PROGRESSION","1") {
        override fun resolve(command:PlayerCommand<TrainCommandPayload>,context:PlayerResolutionContext) =
            PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(
                    progressionStimuli=listOf(
                        ProgressionStimulus.create(
                            stimulusUid="RPGOS-STIMULUS:${command.commandUid}:TRAIN",
                            sourceTypeUid="RPGOS-SOURCE:TRAIN_COMMAND",
                            sourceChannelUid=ProgressionSourceChannels.TRAINING,
                            subject=DomainRef("PLAYER",command.actor.actorUid),
                            targetKindUid=ProgressionTargetKinds.STAT,
                            targetUid="STR",
                            targetValueEvidence=ProgressionTargetValueEvidence("RPGOS-CURRENT:${command.actor.actorUid}:STR","10","RPGOS-VALUE:EXACT","1"),
                            progressSemanticsUid="RPGOS-PROGRESS:EXACT_UNITS",
                            progressSemanticsVersion="1",
                            effortUnits=command.payload.effortUnits,
                            methodUid=command.payload.methodUid,
                            progressionPolicyUid="RPGOS-PROGRESSION-POLICY:WORK-026",
                            progressionPolicyVersion="1"
                        )
                    )
                )
            )
    }
    private class AllowingProbeProvider:WorldRuleProvider("WORK-026-ALLOW","1","WORLD-P20","1"){override fun evaluate(request:WorldRuleRequest):WorldRuleDecision{when(request.stage){WorldRuleEvaluationStage.COMMAND_PRECHECK->precheckCalls++;WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK->{effectCheckCalls++;assertNotNull(request.effects);assertTrue(request.effects!!.ledgerIntents.any{it.ledgerKindUid==PlayerLedgerIntentKinds.PROGRESSION});assertTrue(request.effects!!.changes.any{it.payload is StatChange})}};return WorldRuleDecision.Allowed.create("WORK-026-ALLOW-RULE")}}
    private fun receiptCount(db:SQLiteDatabase)=db.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts WHERE campaign_uid='C1'",null).use{it.moveToFirst();it.getLong(0)}
    private fun eventCount(db:SQLiteDatabase,tx:String)=db.rawQuery("SELECT COUNT(*) FROM canonical_gameplay_events WHERE campaign_uid='C1' AND transaction_uid=?",arrayOf(tx)).use{it.moveToFirst();it.getLong(0)}
    private fun tableExists(db:SQLiteDatabase,name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(name)).use{it.moveToFirst()}
    private fun resetProbes(){precheckCalls=0;effectCheckCalls=0;invariantSnapshotCalls=0}
    companion object{@JvmField var precheckCalls=0;@JvmField var effectCheckCalls=0;@JvmField var invariantSnapshotCalls=0}
}
