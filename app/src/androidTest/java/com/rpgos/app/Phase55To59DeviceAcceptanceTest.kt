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

/** Real Android acceptance for memory schema, canonical isolation and destructive last-turn Undo. */
@RunWith(AndroidJUnit4::class)
class Phase55To59DeviceAcceptanceTest {
    private val context:Context=ApplicationProvider.getApplicationContext()

    @Test fun memoryRemainsDerivedAndUndoReplaysPrefixThenAllowsADifferentFuture()=runBlocking{
        // Lab data can be restored from a host archive before instrumentation. Reassert Android's
        // normal owner-only data-directory mode before the test uses run-as diagnostics.
        Os.chmod(context.applicationInfo.dataDir,0x1C0)
        val repository=UnifiedGameRepository(context)
        repository.bootstrap()
        val previousCampaign=repository.activeCampaignDirName()
        val created=repository.createCampaign("P55-59-device-${System.nanoTime()}")
        try{
            val active=createPlayer(repository)
            val campaign=active.campaignId
            assertDerivedMemoryDoesNotChangeCanonicalDigest(campaign)
            val location=repository.worldLocations().first()
            val selection=AiModelSelection("DEVICE-CONTROLLED","MODEL-1")
            val provider=movementProvider(campaign,location,selection)
            val configuration=AiSystemConfiguration(
                gameMaster=AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,selection)
            )
            val application=ProductionGameEngineCompositionRoot(
                context,repository,AndroidAiProviderCenterApplication(context),{configuration},{listOf(provider)}
            ).chatApplication()

            val first=application.play("Idę do ${location.name}.",AiCancellationSignal.NONE)
            assertTrue("First turn must be narrated, got $first",first is ChatApplicationOutcome.Narrated)
            val firstOrder=(first as ChatApplicationOutcome.Narrated).result.receipt.commitOrder!!
            val second=application.play("Obchodzę plac inną stroną.",AiCancellationSignal.NONE)
            assertTrue("Second turn must be narrated, got $second",second is ChatApplicationOutcome.Narrated)
            val secondOrder=(second as ChatApplicationOutcome.Narrated).result.receipt.commitOrder!!
            assertTrue(secondOrder>firstOrder)
            assertEquals(2_000L,exactX(repository,active.playerUid))

            val generationBefore=repository.infrastructureHistoryGenerationUid()
            val preview=repository.previewUndoLastTurn()
            assertTrue(preview.canConfirm)
            assertEquals(secondOrder,preview.currentCommitOrder)
            assertEquals(firstOrder,preview.targetCommitOrder)
            val undoResult=repository.confirmUndoLastTurn(preview.previewToken)
            assertTrue("Undo must complete, got $undoResult",undoResult is DestructiveUndoResult.Completed)
            val undone=undoResult as DestructiveUndoResult.Completed
            assertEquals(secondOrder,undone.removedCommitOrder)
            assertEquals(firstOrder,undone.activeCommitOrder)
            assertNotEquals(generationBefore,repository.infrastructureHistoryGenerationUid())
            assertEquals(1_000L,exactX(repository,active.playerUid))

            val alternate=application.play("Wracam na plac i wybieram przeciwny kierunek.",AiCancellationSignal.NONE)
            assertTrue(alternate is ChatApplicationOutcome.Narrated)
            assertEquals(2_000L,exactX(repository,active.playerUid))
        }finally{
            repository.closeBackgroundWorkForTest()
            runCatching{LocalGameStore(context).setActiveCampaign(previousCampaign)}
            runCatching{LocalGameStore(context).moveCampaignToTrash(created.name)}
        }
    }

    private fun exactX(repository:UnifiedGameRepository,playerUid:String):Long{
        val position=repository.infrastructureMechanicalPersistence(playerUid).position
        assertTrue("Expected an exact combat position, got $position",position is CombatPosition.Exact)
        return (position as CombatPosition.Exact).xMillimetres
    }

    private fun assertDerivedMemoryDoesNotChangeCanonicalDigest(campaignUid:String){
        LocalGameStore(context).openGameplaySaveDb().use{db->
            val before=AuthoritativeStateDigest.compute(db)
            Phase55To58MemorySchema.ensureReady(db,campaignUid)
            val generation=HistoryGenerationStore(db,campaignUid).current()
            val source=MemorySourceLeafRef("EVENT","DEVICE-DERIVED-SOURCE",1,0,"DEVICE-DERIVED-FP")
            MemoryArtifactStore(db).upsert(
                MemoryArtifactIdentity(
                    campaignUid,generation,"DEVICE-DERIVED-LOGICAL","DEVICE-DERIVED-REVISION",
                    MemoryArtifactKind.EPISODE_INTERPRETATION,listOf(source),memoryLeafFingerprint(listOf(source)),
                    "DEVICE-DERIVED-RULE",1,0,0,0
                ),
                MemoryArtifactStatus.CLEAN,
                "{\"title\":\"derived only\"}"
            )
            assertEquals(before,AuthoritativeStateDigest.compute(db))
        }
    }

    private fun createPlayer(repository:UnifiedGameRepository):ActivePlayerRef{
        val catalog=repository.characterCreationCatalog()
        fun choices(kind:CharacterCreationDefinitionKind,all:Boolean=true)=catalog.options.filter{it.kind==kind}
            .let{if(all)it else it.take(1)}
            .map{option->CharacterCreationValueChoice(
                option.definitionUid,option.maximumValue?:option.minimumValue?:if(kind==CharacterCreationDefinitionKind.POTENTIAL)50.0 else 10.0,
                option.dimensionUid
            )}
        val draft=PlayerCharacterCreationDraft(
            "DEVICE-CREATION",catalog.campaignUid,"PLAYER-DEVICE","Mika","PLAYER_SELECTED",
            stats=choices(CharacterCreationDefinitionKind.STAT),resources=choices(CharacterCreationDefinitionKind.RESOURCE),
            talents=choices(CharacterCreationDefinitionKind.TALENT),potentials=choices(CharacterCreationDefinitionKind.POTENTIAL),
            skills=choices(CharacterCreationDefinitionKind.SKILL),techniques=choices(CharacterCreationDefinitionKind.TECHNIQUE,false),
            startingLocationUid=catalog.options.first{it.kind==CharacterCreationDefinitionKind.STARTING_LOCATION}.definitionUid
        )
        repository.createPlayerCharacter(
            draft,PlayerCharacterCreationConfirmation(PlayerCharacterBootstrapService.fingerprint(draft),"DEVICE-CONFIRM")
        )
        return requireNotNull(repository.activePlayerRef())
    }

    private fun movementProvider(
        campaignUid:String,
        location:WorldLocationItem,
        selection:AiModelSelection
    )=DeterministicAiProvider(
        AiCapabilityContract(
            "DEVICE-CONTROLLED-CONTRACT",selection.providerUid,selection.modelUid,
            AiWorkload.entries.toSet(),maximumContextUnits=16_000
        ),
        intentFunction={request->
            val reference=IntentReference(
                "TARGET",IntentReferenceKind.DESCRIPTIVE,location.name,"TARGET",
                descriptorHints=mapOf("surface" to location.name)
            )
            IntentDocument(
                campaignUid=request.campaignUid,actor=request.actor,rawInput=request.rawInput,
                meaningState=MeaningState.UNDERSTOOD,
                nodes=listOf(IntentNode(
                    "MOVE",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="MOVE",rawPhrase=request.rawInput),
                    participants=listOf(IntentParticipant("TARGET",referenceUid="TARGET"))
                )),
                references=listOf(reference),
                provenance=IntentInterpretationProvenance(
                    IntentInterpretationSource.AI_PROVIDER,selection.providerUid,"1",request.rawInput.hashCode().toString()
                )
            )
        },
        proposalFunction={request->
            val node=request.plan.intent.nodes.single()
            val target=requireNotNull(request.plan.intent.references.single().resolvedProjectedRef)
            GmProposalCandidate(
                1,"PROPOSAL:${request.requestUid}",campaignUid,request.plan.planUid,
                listOf(GmNodeProposal(
                    node.nodeUid,"MOVE-OK","Docierasz na miejsce.",request.plan.intent.actor,"MOVE",listOf(target),
                    node.modality,GmNodeOutcomeState.PROPOSED_SUCCESS
                )),
                mechanicsEffects=listOf(MechanicsEffectRequest("MOVE-EFFECT",node.nodeUid,"UNIVERSAL_MOVEMENT","MOVEMENT",target)),
                narrativeBlueprint=NarrativeBlueprint(listOf("COMMITTED_MOVE"),stopPointUid="PLAYER_DECISION_POINT"),
                providerUid=selection.providerUid,modelUid=selection.modelUid,
                intentFingerprint=request.plan.intent.canonicalFingerprint()
            )
        },
        narrativeFunction={request->RenderedNarrative(
            "Przemieszczasz się zgodnie ze swoim wyborem.",request.context.stopPointUid,request.context.committedOrder
        )}
    )
}
