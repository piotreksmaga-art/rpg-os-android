package com.rpgos.app

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Phase55To59HundredTurnAcceptanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun hundredTurnSaveReopenUndoAndAlternateFuture() = runBlocking {
        Os.chmod(context.applicationInfo.dataDir, 0x1C0)

        val repository = UnifiedGameRepository(context).also { it.bootstrap() }
        val previousCampaign = repository.activeCampaignDirName()
        val created = repository.createCampaign("P55-59-100-${System.nanoTime()}")
        var reopenedRepository: UnifiedGameRepository? = null

        suspend fun runTurn(
            app: CanonicalChatApplication,
            repository: UnifiedGameRepository,
            index: Int,
            input: String
        ): Long {
            val outcome = app.play(input, AiCancellationSignal.NONE)
            assertTrue(
                "Turn #$index expected narrated outcome, got $outcome",
                outcome is ChatApplicationOutcome.Narrated
            )
            val turn = outcome as ChatApplicationOutcome.Narrated
            val order = requireNotNull(turn.result.receipt.commitOrder) {
                "Turn #$index expected commit order, but commit receipt was missing (result=$turn)"
            }
            val lastKnownOrder = repository.infrastructureLastCommitOrder()
            assertEquals(
                "Turn #$index should be visible as repository last committed order",
                order,
                lastKnownOrder
            )
            return order
        }

        try {
            val activePlayer = createPlayer(repository)
            val campaignUid = activePlayer.campaignId
            val locations = repository.worldLocations()
            assertTrue("World location catalog must be available", locations.isNotEmpty())

            val selection = AiModelSelection("DEVICE-CONTROLLED-100", "MODEL-1")
            val provider = movementProvider(campaignUid, locations, selection)
            val configuration = AiSystemConfiguration(
                gameMaster = AiRoleAssignment(AiRole.GAME_MASTER, AiAssignmentKind.PINNED, selection)
            )
            val rootApplication = ProductionGameEngineCompositionRoot(
                context,
                repository,
                AndroidAiProviderCenterApplication(context),
                { configuration },
                { listOf(provider) }
            ).chatApplication()

            val firstBatchOrders = mutableListOf<Long>()
            repeat(50) { index ->
                val order = runTurn(
                    app = rootApplication,
                    repository = repository,
                    index = index + 1,
                    input = "Tura ${index + 1}: idę do ${locations[index % locations.size].name}"
                )
                firstBatchOrders += order
            }

            assertStrictlyIncreasing(firstBatchOrders)
            val reopenExpected = firstBatchOrders.takeLast(1).first()

            val reopenRepository = UnifiedGameRepository(context)
            reopenRepository.setActiveCampaign(created.name)
            reopenedRepository = reopenRepository
            val reopenedApplication = ProductionGameEngineCompositionRoot(
                context,
                reopenRepository,
                AndroidAiProviderCenterApplication(context),
                { configuration },
                { listOf(provider) }
            ).chatApplication()

            assertEquals(
                "Reopening campaign must keep active player",
                activePlayer.playerUid,
                reopenRepository.activePlayerRef()?.playerUid
            )
            assertEquals(
                "Reopen should keep the character creator identity",
                campaignUid,
                reopenRepository.activePlayerRef()?.campaignId
            )

            val replayAfterReopen = reopenedCommitOrders(reopenRepository)
            assertEquals(
                "Unexpected commit count after first stage and reopen",
                50,
                replayAfterReopen.size
            )
            assertEquals(
                "Reopened repository should see the same last committed order",
                reopenRepository.infrastructureLastCommitOrder(),
                replayAfterReopen.last()
            )
            assertEquals(
                "Unexpected last committed order at reopen boundary",
                reopenExpected,
                replayAfterReopen.last()
            )
            assertEquals(
                "Reopen should preserve committed history",
                firstBatchOrders,
                replayAfterReopen
            )

            val secondBatchOrders = mutableListOf<Long>()
            repeat(50) { index ->
                val stepIndex = index + 51
                val order = runTurn(
                    app = reopenedApplication,
                    repository = reopenRepository,
                    index = stepIndex,
                    input = "Tura $stepIndex: idę do ${locations[index % locations.size].name}"
                )
                secondBatchOrders += order
            }

            val replayAfterSecondBatch = reopenedCommitOrders(reopenRepository)
            assertEquals(
                "Expected full 100-turn history after second batch",
                100,
                replayAfterSecondBatch.size
            )
            assertEquals(
                "Expected full committed history to be monotonic",
                (firstBatchOrders + secondBatchOrders).sorted(),
                replayAfterSecondBatch.sorted()
            )
            assertEquals(
                "Expected replay last committed order to match repository state",
                reopenRepository.infrastructureLastCommitOrder(),
                replayAfterSecondBatch.last()
            )
            assertTrue(
                "Both batches should remain strictly increasing",
                isStrictlyIncreasing(replayAfterSecondBatch)
            )

            val beforeUndoGeneration = reopenRepository.infrastructureHistoryGenerationUid()
            val preview = reopenRepository.previewUndoLastTurn()
            assertTrue("Undo preview must be confirmable at 100th turn, got $preview", preview.canConfirm)
            assertEquals(replayAfterSecondBatch.last(), preview.currentCommitOrder)
            assertEquals(replayAfterSecondBatch[replayAfterSecondBatch.size - 2], preview.targetCommitOrder)

            val undoResult = reopenRepository.confirmUndoLastTurn(preview.previewToken)
            assertTrue("Undo must complete, got $undoResult", undoResult is DestructiveUndoResult.Completed)
            val undone = undoResult as DestructiveUndoResult.Completed
            assertEquals(
                "Wrong removed turn",
                preview.currentCommitOrder,
                undone.removedCommitOrder
            )
            assertEquals(
                "Wrong active order after undo",
                preview.targetCommitOrder,
                undone.activeCommitOrder
            )
            assertNotEquals(
                "History generation must change after successful undo",
                beforeUndoGeneration.value,
                reopenRepository.infrastructureHistoryGenerationUid().value
            )
            assertEquals(
                "After undo expected active last order",
                preview.targetCommitOrder,
                reopenRepository.infrastructureLastCommitOrder()
            )
            val afterUndoOrders = reopenedCommitOrders(reopenRepository)
            assertEquals(99, afterUndoOrders.size)
            assertEquals(preview.targetCommitOrder, afterUndoOrders.last())
            assertTrue(
                "Undo should remove the last commit from committed replay",
                afterUndoOrders.last() < preview.currentCommitOrder
            )

            val alternate = reopenedApplication.play(
                "Tura 101: jadę inną drogą i wykonuję inną decyzję.",
                AiCancellationSignal.NONE
            )
            assertTrue(
                "Alternate future after undo expected narrated outcome, got $alternate",
                alternate is ChatApplicationOutcome.Narrated
            )
            val alternateOrder = (alternate as ChatApplicationOutcome.Narrated).result.receipt.commitOrder
            val observedStride = replayAfterSecondBatch
                .sorted()
                .zipWithNext()
                .map { (previous, current) -> current - previous }
                .also { require(it.isNotEmpty()) }
                .let { strides ->
                    require(strides.all { it > 0L }) { "Observed commit order stride must be positive, strides=$strides" }
                    strides.distinct().singleOrNull() ?: strides.first()
                }
            assertEquals(
                "Alternate future should recreate exactly one final commit with observed commit stride",
                preview.targetCommitOrder + observedStride,
                alternateOrder
            )
            assertEquals(
                "Alternate future should keep repository state coherent",
                alternateOrder,
                reopenRepository.infrastructureLastCommitOrder()
            )
            val finalReplay = reopenedCommitOrders(reopenRepository)
            assertEquals("After alternative branch, 100 committed turns should exist", 100, finalReplay.size)
            assertEquals("After alternative branch, history should end exactly at latest alternate turn", alternateOrder, finalReplay.last())
        } finally {
            repository.closeBackgroundWorkForTest()
            reopenedRepository?.closeBackgroundWorkForTest()
            runCatching { LocalGameStore(context).setActiveCampaign(previousCampaign) }
            runCatching { LocalGameStore(context).moveCampaignToTrash(created.name) }
        }
    }

    private fun reopenedCommitOrders(repository: UnifiedGameRepository): List<Long> =
        repository.infrastructureReplayPayloadsAfter(0L)
            .filter { it.commitOrder > 0L }
            .map { it.commitOrder }

    private fun isStrictlyIncreasing(values: List<Long>): Boolean = values.zipWithNext().all { (previous, current) -> current > previous }

    private fun assertStrictlyIncreasing(values: List<Long>) {
        assertTrue("Commit order sequence must be strictly increasing. Orders: $values", isStrictlyIncreasing(values))
    }

    private fun createPlayer(repository: UnifiedGameRepository): ActivePlayerRef {
        val catalog = repository.characterCreationCatalog()

        fun choices(kind: CharacterCreationDefinitionKind, all: Boolean = true) =
            catalog.options.filter { it.kind == kind }
                .let { if (all) it else it.take(1) }
                .map { option ->
                    CharacterCreationValueChoice(
                        option.definitionUid,
                        option.maximumValue ?: option.minimumValue ?: if (kind == CharacterCreationDefinitionKind.POTENTIAL) 50.0 else 10.0,
                        option.dimensionUid
                    )
                }

        val draft = PlayerCharacterCreationDraft(
            "CREATE-100",
            catalog.campaignUid,
            "PLAYER-100",
            "Mika",
            "PLAYER_SELECTED",
            stats = choices(CharacterCreationDefinitionKind.STAT),
            resources = choices(CharacterCreationDefinitionKind.RESOURCE),
            talents = choices(CharacterCreationDefinitionKind.TALENT),
            potentials = choices(CharacterCreationDefinitionKind.POTENTIAL),
            skills = choices(CharacterCreationDefinitionKind.SKILL),
            techniques = choices(CharacterCreationDefinitionKind.TECHNIQUE, false),
            startingLocationUid = catalog.options.first { it.kind == CharacterCreationDefinitionKind.STARTING_LOCATION }.definitionUid
        )

        repository.createPlayerCharacter(
            draft,
            PlayerCharacterCreationConfirmation(
                PlayerCharacterBootstrapService.fingerprint(draft),
                "CREATE-100-CONFIRM"
            )
        )
        return requireNotNull(repository.activePlayerRef())
    }

    private fun movementProvider(
        campaignUid: String,
        locations: List<WorldLocationItem>,
        selection: AiModelSelection
    ) = DeterministicAiProvider(
        AiCapabilityContract(
            "CONTROLLED-100-TURNS",
            selection.providerUid,
            selection.modelUid,
            AiWorkload.entries.toSet(),
            maximumContextUnits = 16_000
        ),
        intentFunction = { request ->
            val selected = locations[(request.rawInput.hashCode() and 0x7fffffff) % locations.size]
            val reference = IntentReference(
                "TARGET",
                IntentReferenceKind.DESCRIPTIVE,
                selected.name,
                "TARGET",
                descriptorHints = mapOf("surface" to selected.name)
            )
            IntentDocument(
                campaignUid = request.campaignUid,
                actor = request.actor,
                rawInput = request.rawInput,
                meaningState = MeaningState.UNDERSTOOD,
                nodes = listOf(
                    IntentNode(
                        "MOVE",
                        IntentForm.DIRECT_ACTION,
                        SemanticAction(semanticFamilyUid = "MOVE", rawPhrase = request.rawInput),
                        participants = listOf(IntentParticipant("TARGET", referenceUid = "TARGET"))
                    )
                ),
                references = listOf(reference),
                provenance = IntentInterpretationProvenance(
                    IntentInterpretationSource.AI_PROVIDER,
                    selection.providerUid,
                    "1",
                    request.rawInput.hashCode().toString()
                )
            )
        },
        proposalFunction = { request ->
            val node = request.plan.intent.nodes.single()
            val target = requireNotNull(request.plan.intent.references.single().resolvedProjectedRef)
            GmProposalCandidate(
                1,
                "PROPOSAL:${request.requestUid}",
                campaignUid,
                request.plan.planUid,
                listOf(
                    GmNodeProposal(
                        node.nodeUid,
                        "MOVE-OK",
                        "Docierasz do wskazanego miejsca.",
                        request.plan.intent.actor,
                        node.semanticAction.canonicalActionUid ?: requireNotNull(node.semanticAction.semanticFamilyUid),
                        listOf(target),
                        node.modality,
                        GmNodeOutcomeState.PROPOSED_SUCCESS
                    )
                ),
                mechanicsEffects = listOf(
                    MechanicsEffectRequest(
                        "MOVE-EFFECT",
                        node.nodeUid,
                        "UNIVERSAL_MOVEMENT",
                        "MOVEMENT",
                        target
                    )
                ),
                narrativeBlueprint = NarrativeBlueprint(
                    listOf("COMMITTED_MOVE"),
                    stopPointUid = "PLAYER_DECISION_POINT"
                ),
                providerUid = selection.providerUid,
                modelUid = selection.modelUid,
                intentFingerprint = request.plan.intent.canonicalFingerprint()
            )
        },
        narrativeFunction = { request ->
            RenderedNarrative(
                "Przemieszczasz się zgodnie ze swoim wyborem.",
                request.context.stopPointUid,
                request.context.committedOrder
            )
        }
    )
}
