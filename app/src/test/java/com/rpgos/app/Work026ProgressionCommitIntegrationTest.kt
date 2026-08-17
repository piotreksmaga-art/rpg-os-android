package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
        SQLiteDatabase.create(null).use { db ->
            setupStat(db)
            val probe = AllowingProbeProvider()
            val command = command("CMD-PROG-COMMIT")
            val admitted = admit(command, probe)

            assertEquals(1, admitted.playerChangeSet.changes.size)
            assertTrue(admitted.playerChangeSet.changes.single().payload is StatChange)
            assertEquals(PlayerLedgerIntentKinds.PROGRESSION, admitted.playerChangeSet.ledgerIntents.single().ledgerKindUid)
            assertEquals(
                listOf(admitted.playerChangeSet.changes.single().changeUid),
                admitted.playerChangeSet.ledgerIntents.single().causalChangeUids
            )
            assertEquals(1, probe.precheckCalls)
            assertEquals(1, probe.effectCheckCalls)

            val identity = TurnTransactionIdentity("C1", "TURN-PROG-COMMIT", command.commandUid, "TX-PROG-COMMIT")
            val committed = TurnTransactionBoundary.create(db, identity, admitted).commit()
            assertTrue(committed is TurnExecutionResult.Committed)
            assertEquals(20.0, StatResourceStore(db, "C1").playerStats("P1").single { it.statUid == "STR" }.baseValue, 0.0)
            assertEquals(1L, receiptCount(db))

            val retry = TurnTransactionBoundary.create(db, identity, admitted).commit()
            assertTrue(retry is TurnExecutionResult.AlreadyCommitted)
            assertEquals(20.0, StatResourceStore(db, "C1").playerStats("P1").single { it.statUid == "STR" }.baseValue, 0.0)
            assertEquals(1L, receiptCount(db))

            assertFalse(tableExists(db, "progression_ledger"))
            assertFalse(tableExists(db, "progression_ledger_entries"))
            assertTrue(PlayerDomainEngine::class.java.declaredMethods.any { it.name == "validatePlayerInvariants" })
        }

        SQLiteDatabase.create(null).use { db ->
            setupStat(db)
            val command = command("CMD-PROG-ROLLBACK")
            val admitted = admit(command, AllowingProbeProvider())
            val identity = TurnTransactionIdentity("C1", "TURN-PROG-ROLLBACK", command.commandUid, "TX-PROG-ROLLBACK")
            val failure = runCatching {
                TurnTransactionBoundary.create(
                    db,
                    identity,
                    admitted,
                    TurnFailureInjector { point -> if (point == TurnFailurePoint.AFTER_FIRST_WRITE) error("forced rollback") }
                ).commit()
            }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(10.0, StatResourceStore(db, "C1").playerStats("P1").single { it.statUid == "STR" }.baseValue, 0.0)
            assertEquals(0L, receiptCount(db))
        }
    }

    private fun setupStat(db: SQLiteDatabase) {
        CurrentSchema.ensure(db, "C1")
        val store = StatResourceStore(db, "C1")
        store.registerStatDefinitions(
            "WP",
            listOf(StatDefinition("STR", "str", "CORE", minValue = 0.0, maxValue = 200.0, worldPackUid = "WP"))
        )
        store.savePlayerStat(PlayerStat("C1", "P1", "STR", 10.0))
    }

    private fun command(uid: String) = PlayerCommand(
        commandUid = uid,
        campaignUid = "C1",
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("WORK-026")
    )

    private fun admit(command: PlayerCommand<TrainCommandPayload>, provider: AllowingProbeProvider): CanonicalCampaignMutationProposal {
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(ProgressionComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider)),
            worldPackAuthority = WorldPackAuthoritySnapshot.single("C1", binding)
        )
        val context = PlayerResolutionContext.create(
            campaignUid = "C1",
            actor = actor,
            knownReferences = setOf(
                CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
                CampaignScopedDomainRef("C1", DomainRef("STAT", "STR"))
            ),
            worldRuleMode = WorldRuleMode.Bound(binding)
        )
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit("C1", engine, command, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("progression admission rejected: ${admission.reasonUid}")
        }
    }

    private class ProgressionComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:WORK-026-PROGRESSION",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                progressionStimuli = listOf(
                    ProgressionStimulus.create(
                        stimulusUid = "RPGOS-STIMULUS:${command.commandUid}:TRAIN",
                        sourceTypeUid = "RPGOS-SOURCE:TRAIN_COMMAND",
                        sourceChannelUid = ProgressionSourceChannels.TRAINING,
                        subject = DomainRef("PLAYER", command.actor.actorUid),
                        targetKindUid = ProgressionTargetKinds.STAT,
                        targetUid = "STR",
                        targetValueEvidence = ProgressionTargetValueEvidence(
                            "RPGOS-CURRENT:${command.actor.actorUid}:STR",
                            "10",
                            "RPGOS-VALUE:EXACT",
                            "1"
                        ),
                        progressSemanticsUid = "RPGOS-PROGRESS:EXACT_UNITS",
                        progressSemanticsVersion = "1",
                        effortUnits = command.payload.effortUnits,
                        methodUid = command.payload.methodUid,
                        progressionPolicyUid = "RPGOS-PROGRESSION-POLICY:WORK-026",
                        progressionPolicyVersion = "1"
                    )
                )
            )
        )
    }

    private class AllowingProbeProvider : WorldRuleProvider("WORK-026-ALLOW", "1", "WORLD-P20", "1") {
        var precheckCalls = 0
        var effectCheckCalls = 0

        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            when (request.stage) {
                WorldRuleEvaluationStage.COMMAND_PRECHECK -> precheckCalls++
                WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK -> {
                    effectCheckCalls++
                    assertNotNull(request.effects)
                    assertTrue(request.effects!!.ledgerIntents.any { it.ledgerKindUid == PlayerLedgerIntentKinds.PROGRESSION })
                }
            }
            return WorldRuleDecision.Allowed.create("WORK-026-ALLOW-RULE")
        }
    }

    private fun receiptCount(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts WHERE campaign_uid='C1'", null).use {
            it.moveToFirst(); it.getLong(0)
        }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name)).use { it.moveToFirst() }
}
