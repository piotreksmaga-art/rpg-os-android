package com.rpgos.app

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.util.Log
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
    fun hundredTurnSaveReopenUndoAndAlternateFuture() = movementScenario(100, 50)

    /** Quick local check of the same legal-route fixture, not a substitute for the 100-turn gate. */
    @Test
    fun knownRouteRoundTripReopenUndoAndDifferentDestination() = movementScenario(2, 1)

    private fun movementScenario(turnCount: Int, reopenAfter: Int) = runBlocking {
        milestone("scenario-start turns=$turnCount reopen_after=$reopenAfter")
        Os.chmod(context.applicationInfo.dataDir, 0x1C0)

        val repository = UnifiedGameRepository(context).also { it.bootstrap() }
        val previousCampaign = repository.activeCampaignDirName()
        val created = repository.createCampaign("P55-59-100-${System.nanoTime()}")
        var reopenedRepository: UnifiedGameRepository? = null

        suspend fun runTurn(
            app: CanonicalChatApplication,
            repository: UnifiedGameRepository,
            index: Int,
            target: WorldLocationItem,
            durationMillis: Long = 300_000
        ): Long {
            val started = SystemClock.elapsedRealtime()
            milestone("turn-start=$index")
            val before = observeTurn(repository)
            assertNotEquals("Turn #$index must really change location", target.uid,
                before.locationUid)
            val input = "Tura $index: przez ${durationMillis / 60_000} minut idę do ${target.name}."
            val outcome = app.play(input, AiCancellationSignal.NONE)
            assertTrue(
                "Turn #$index expected narrated outcome, got $outcome",
                outcome is ChatApplicationOutcome.Narrated
            )
            val turn = outcome as ChatApplicationOutcome.Narrated
            val order = requireNotNull(turn.result.receipt.commitOrder) {
                "Turn #$index expected commit order, but commit receipt was missing (result=$turn)"
            }
            val after = observeTurn(repository)
            assertEquals("Turn #$index must preserve the active player", before.playerUid, after.playerUid)
            assertEquals(
                "Turn #$index should be visible as repository last committed order",
                order,
                after.commitOrder
            )
            assertEquals("Turn #$index must reach the requested place", target.uid,
                after.locationUid)
            assertEquals("Travel must use the registered route duration", before.milliseconds + durationMillis,
                after.milliseconds)
            milestone("turn=$index order=$order elapsed_ms=${SystemClock.elapsedRealtime() - started}")
            return order
        }

        try {
            val activePlayer = createPlayer(repository)
            val campaignUid = activePlayer.campaignId
            val (setupOrder, locations) = prepareKnownRoutes(repository, activePlayer)
            milestone("fixture-ready")

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
            repeat(reopenAfter) { index ->
                val order = runTurn(
                    app = rootApplication,
                    repository = repository,
                    index = index + 1,
                    target = locations[index % 2]
                )
                firstBatchOrders += order
            }

            assertStrictlyIncreasing(firstBatchOrders)
            val reopenExpected = firstBatchOrders.takeLast(1).first()

            val reopenRepository = UnifiedGameRepository(context)
            milestone("reopen-start")
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

            val replayAfterReopen = reopenedCommitOrders(reopenRepository, setupOrder)
            assertEquals(
                "Unexpected commit count after first stage and reopen",
                reopenAfter,
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
            milestone("reopen-verified")

            val secondBatchOrders = mutableListOf<Long>()
            repeat(turnCount - reopenAfter) { index ->
                val stepIndex = index + reopenAfter + 1
                val order = runTurn(
                    app = reopenedApplication,
                    repository = reopenRepository,
                    index = stepIndex,
                    target = locations[(stepIndex - 1) % 2]
                )
                secondBatchOrders += order
            }

            val replayAfterSecondBatch = reopenedCommitOrders(reopenRepository, setupOrder)
            assertEquals(
                "Expected full 100-turn history after second batch",
                turnCount,
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
            milestone("undo-preview-start")
            val preview = reopenRepository.previewUndoLastTurn()
            assertTrue("Undo preview must be confirmable at 100th turn, got $preview", preview.canConfirm)
            assertEquals(replayAfterSecondBatch.last(), preview.currentCommitOrder)
            assertEquals(replayAfterSecondBatch[replayAfterSecondBatch.size - 2], preview.targetCommitOrder)

            milestone("undo-confirm-start")
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
            val afterUndoOrders = reopenedCommitOrders(reopenRepository, setupOrder)
            assertEquals(turnCount - 1, afterUndoOrders.size)
            assertEquals(preview.targetCommitOrder, afterUndoOrders.last())
            assertTrue(
                "Undo should remove the last commit from committed replay",
                afterUndoOrders.last() < preview.currentCommitOrder
            )
            milestone("undo-verified")

            assertEquals("Undo must restore the previous actual location", locations[0].uid,
                reopenRepository.infrastructureEntityLocationUid(activePlayer.playerUid))
            // A genuinely different destination via two registered edges, not a hash-selected target.
            val alternateOrder = runTurn(reopenedApplication, reopenRepository, turnCount + 1,
                locations[2], durationMillis = 600_000)
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
            val finalReplay = reopenedCommitOrders(reopenRepository, setupOrder)
            assertEquals("The alternative must preserve the gameplay turn count", turnCount, finalReplay.size)
            assertEquals("After alternative branch, history should end exactly at latest alternate turn", alternateOrder, finalReplay.last())
            milestone("alternative-verified")
        } finally {
            milestone("cleanup-start")
            repository.closeBackgroundWorkForTest()
            milestone("first-worker-close-returned")
            reopenedRepository?.closeBackgroundWorkForTest()
            milestone("reopened-worker-close-returned")
            val restore = runCatching { LocalGameStore(context).setActiveCampaign(previousCampaign) }
            milestone("selection-restore-returned success=${restore.isSuccess}")
            val trash = runCatching { LocalGameStore(context).moveCampaignToTrash(created.name) }
            milestone("cleanup-finished trash_success=${trash.isSuccess}")
        }
    }

    private data class TurnObservation(
        val playerUid: String,
        val locationUid: String?,
        val milliseconds: Long,
        val commitOrder: Long
    )

    /** Fresh, coherent observations from the same canonical owners used by the repository.
     * Readiness is still verified; no handle or lock survives into app.play/Undo. The time
     * assertion needs the owner clock, not an additional full-database scope digest. */
    private fun observeTurn(repository: UnifiedGameRepository): TurnObservation {
        val campaignUid = repository.activeCampaignRef().campaignId
        return CampaignRuntimeLifecycleLock.withTurn(campaignUid) {
            LocalGameStore(context).openGameplaySaveDb().use { db ->
                check(repository.activeCampaignRef().campaignId == campaignUid)
                val playerUid = requireNotNull(ActivePlayerStore(db, campaignUid).active()).playerUid
                val locationUid = db.rawQuery(
                    "SELECT location_uid FROM entity_positions WHERE entity_uid=? LIMIT 1",
                    arrayOf(playerUid)
                ).use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0))
                        cursor.getString(0)?.takeIf(String::isNotBlank) else null
                }
                TurnObservation(playerUid, locationUid,
                    Phase60TemporalStateStore(db, campaignUid).read().time.milliseconds,
                    TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder ?: 0L)
            }
        }
    }

    /** Only counters/stage names, never inputs, narration or private campaign records. */
    private fun milestone(stage: String) {
        Log.i("RPGOS100Acceptance", "elapsed_ms=${SystemClock.elapsedRealtime()} $stage")
    }

    private fun reopenedCommitOrders(repository: UnifiedGameRepository, setupOrder: Long): List<Long> =
        repository.infrastructureReplayPayloadsAfter(setupOrder)
            .filter { it.commitOrder > setupOrder }
            .map { it.commitOrder }

    /** Materialize two visible local sites through the real owners. Phase63 derives the
     * registered edges and Phase37 records route observations in the same normal transaction.
     * No SQL grants, arbitrary map teleportation or replacement of the replay baseline. */
    private fun prepareKnownRoutes(repository: UnifiedGameRepository, player: ActivePlayerRef): Pair<Long, List<WorldLocationItem>> {
        val campaign = player.campaignId
        val anchorUid = requireNotNull(repository.infrastructureEntityLocationUid(player.playerUid))
        val anchor = repository.worldLocations().single { it.uid == anchorUid }
        val skeleton = requireNotNull(repository.infrastructureWorldSkeletonCandidate())
        val drafts = listOf("Plac próby pamięci", "Plac innej decyzji").mapIndexed { ordinal, name ->
            val slot = LatentWorldSlot(anchorUid, "MEMORY_ACCEPTANCE", WorldElementBaseKind.PLACE, ordinal.toLong())
            WorldElementDraft(campaign, slot.ref(skeleton), name, WorldElementBaseKind.PLACE,
                slot.categoryUid, anchorUid, setOf("MOVE", "LOOK"), "LOCAL_SITE",
                WorldEvidenceClassification.GENERATED_PLAUSIBLE, emptyList(), null, null, null,
                slotOrdinal = slot.ordinal)
        }
        val effects = drafts.map { draft ->
            VerifiedMechanicsCommandEffect("SETUP:${draft.element.uid}", "SETUP", "RPGOS-CORE:WORLD-MATERIALIZER",
                "WORLD_ELEMENT_MATERIALIZE", draft.element, 1, draft.materializationPayload(),
                "RPGOS-CORE:WORLD-MATERIALIZATION:${draft.fingerprint()}",
                phase63Hash("INPUT:${draft.fingerprint()}"), phase63Hash("OUTPUT:${draft.fingerprint()}"))
        }
        val identity = TurnTransactionIdentity(campaign, "KNOWN-ROUTES", "KNOWN-ROUTES-CMD", "KNOWN-ROUTES-TX")
        val order = repository.infrastructureLastCommitOrder() + 1
        val actor = CommandActorRef("PLAYER", player.playerUid)
        val request = ChatTurnRequest(requestUid = "KNOWN-ROUTES-REQUEST", campaignUid = campaign,
            turnUid = identity.turnUid, commandUid = identity.commandUid, transactionUid = identity.transactionUid,
            actor = actor, input = "Oglądam dwa dostępne place", localeUid = "pl-PL",
            audience = VisibilityAudienceFactory.player(campaign),
            purpose = PurposeContext(campaign, VisibilityPurposeKinds.GAMEPLAY_NARRATION), atOrder = order)
        val worldChanges = repository.infrastructureWorldExpansion(request, effects)
        val edges = worldChanges.flatMap { it.edges }
        assertEquals("Both registered local connections must be bidirectional", 4, edges.size)
        assertTrue(edges.all { it.duration.milliseconds == 300_000L && it.resourceCosts.isEmpty() })
        val authority = repository.infrastructureWorldPackAuthority()
        val engine = productionMechanicsPlayerDomainEngine(
            WorldRuleProviderRegistry.of(listOf(UniversalMechanicsWorldRuleProvider(authority.binding))),
            WorldPackAuthoritySnapshot.single(campaign, authority.binding))
        val command = PlayerCommand(commandUid = identity.commandUid, campaignUid = campaign, actor = actor,
            commandKindUid = PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload = ApplyVerifiedMechanicsCommandPayload("KNOWN-ROUTES-PLAN", effects, worldChanges = worldChanges),
            provenance = CommandProvenance("P55-59:LEGAL_ROUTE_FIXTURE"), requestedEffectiveOrder = order)
        val refs = (listOf(DomainRef("PLAYER", player.playerUid), DomainRef("CHARACTER", player.playerUid),
            DomainRef("CAMPAIGN", campaign), DomainRef("PLACE", anchorUid), DomainRef("LOCATION", anchorUid)) +
            drafts.flatMap { listOf(it.element, DomainRef("LOCATION", it.element.uid)) } +
            edges.map { DomainRef("WORLD_ROUTE", it.uid) }).map { CampaignScopedDomainRef(campaign, it) }.toSet()
        val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, command,
            PlayerResolutionContext.create(campaign, actor, refs, worldRuleMode = WorldRuleMode.Bound(authority.binding)))
        assertTrue(admission.toString(), admission is CampaignMutationAdmission.Accepted)
        val result = repository.commitTurn(identity, (admission as CampaignMutationAdmission.Accepted).proposal,
            TurnFailureInjector.NONE)
        assertTrue(result.toString(), result is TurnExecutionResult.Committed)
        val setupOrder = repository.infrastructureLastCommitOrder()
        // Dynamic sites live in canonical campaign facts, not the immutable Pack map catalog.
        val places = LocalGameStore(context).openGameplaySaveDb().use { db ->
            val projection = CampaignWorldProjectionStore(db, campaign)
            drafts.associate { draft ->
                val place = requireNotNull(projection.canonicalElement(draft.element.uid))
                assertEquals(CampaignWorldAudience.PLAYER_VISIBLE, place.audienceScopeUid)
                place.element.uid to WorldLocationItem(place.element.uid, place.displayName, place.categoryUid,
                    place.parentAnchorUid.orEmpty(), "")
            }
        }
        return setupOrder to listOf(requireNotNull(places[drafts[0].element.uid]), anchor,
            requireNotNull(places[drafts[1].element.uid]))
    }

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
            val selected = locations.single { request.rawInput.endsWith("do ${it.name}.") }
            val duration = if (request.rawInput.contains("przez 10 minut")) "600000" else "300000"
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
                        SemanticAction(semanticFamilyUid = "MOVE", rawPhrase = request.rawInput,
                            attributes = mapOf("time_min_ms" to duration, "time_max_ms" to duration)),
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
                        "LOCATION_TRANSITION",
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
