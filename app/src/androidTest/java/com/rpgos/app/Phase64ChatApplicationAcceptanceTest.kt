package com.rpgos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The same composition/intent/proposal/mechanics/commit route as UI and LAB. The only
 * deterministic replacement is the AI boundary; it cannot write a process or inventory.
 * A starting item/grant is part of explicit pre-character bootstrap, before the baseline.
 * This is functional acceptance, not a model-quality test or a simulated UI button click. */
@RunWith(AndroidJUnit4::class)
class Phase64ChatApplicationAcceptanceTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val playerUid="P64:CHAT:PLAYER"
    private val itemUid="P64:CHAT:MATERIAL"
    private val itemName="Materiał do zużycia"
    private val player=DomainRef("PLAYER",playerUid)

    @Test fun publicStartAndDisplayedCancellationUseRealPortsAndUndoRestoresOnlyTheRemovedAction()=runBlocking {
        val repository=UnifiedGameRepository(context)
        repository.bootstrap()
        val previous=repository.activeCampaignDirName()
        val created=repository.createCampaign("P64-chat-${System.nanoTime()}")
        try {
            val catalog=repository.characterCreationCatalog()
            val campaign=catalog.campaignUid
            val binding=CampaignSelectionManager(context).currentWorldPackAuthority().binding
            LocalGameStore(context).openGameplaySaveDb().use { db->
                withAdministrativeMutationAuthority(db,campaign) {
                    val inventory=InventoryStore(db,campaign)
                    inventory.registerDefinitions(binding.worldPackUid,listOf(ItemDefinition("P64:CHAT:MATERIAL_DEF",binding.worldPackUid,
                        "p64-chat-material",itemName,storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="DEVICE_BOOTSTRAP")))
                    inventory.createInstance(ItemInstance(campaign,itemUid,"P64:CHAT:MATERIAL_DEF",provenance="DEVICE_BOOTSTRAP"))
                    inventory.addUnique(playerUid,itemUid,"DEVICE_BOOTSTRAP")
                    val identity=TurnTransactionIdentity(campaign,"BOOT:T","BOOT:C","BOOT:X")
                    AccessAuthorityStore(db,campaign).apply(identity,"P64:CHAT:GRANT",AccessAuthorityChange(AccessOperation.GRANT,
                        "P64:CHAT:GRANT",player.kindUid,player.uid,AccessGrantKind.WORLD_RULE.name,
                        Phase64EconomyOperations.CONSUME,"ITEM_INSTANCE",itemUid,validFromOrder=0),0)
                }
            }
            fun choices(kind:CharacterCreationDefinitionKind,all:Boolean=true)=catalog.options.filter { it.kind==kind }
                .let { if(all)it else it.take(1) }.map { option->
                    val desired=when(kind){
                        CharacterCreationDefinitionKind.STAT,CharacterCreationDefinitionKind.RESOURCE->5.0
                        CharacterCreationDefinitionKind.POTENTIAL->100.0
                        else->1.0
                    }
                    CharacterCreationValueChoice(option.definitionUid,desired.coerceAtLeast(option.minimumValue?:desired)
                        .coerceAtMost(option.maximumValue?:desired),option.dimensionUid)
                }
            val draft=PlayerCharacterCreationDraft("P64:CHAT:CREATION",campaign,playerUid,"Mika","PLAYER_SELECTED",
                stats=choices(CharacterCreationDefinitionKind.STAT),resources=choices(CharacterCreationDefinitionKind.RESOURCE),
                talents=choices(CharacterCreationDefinitionKind.TALENT),potentials=choices(CharacterCreationDefinitionKind.POTENTIAL),
                skills=choices(CharacterCreationDefinitionKind.SKILL),techniques=choices(CharacterCreationDefinitionKind.TECHNIQUE,false),
                startingLocationUid=catalog.options.first { it.kind==CharacterCreationDefinitionKind.STARTING_LOCATION }.definitionUid)
            repository.createPlayerCharacter(draft,PlayerCharacterCreationConfirmation(PlayerCharacterBootstrapService.fingerprint(draft),"P64:CHAT:CONFIRM"))
            val selection=AiModelSelection("P64-DEVICE-CONTROLLED","ONE")
            val provider=provider(campaign,selection)
            val configuration=AiSystemConfiguration(gameMaster=AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,selection))
            val application=ProductionGameEngineCompositionRoot(context,repository,AndroidAiProviderCenterApplication(context),
                {configuration},{listOf(provider)}).chatApplication()
            val audience=VisibilityAudienceFactory.player(campaign)
            val purpose=PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION)
            fun own()=requireNotNull(repository.infrastructurePlayerProcessProjection(audience,purpose))
            fun hasItem()=itemUid in repository.infrastructureHeldItemInstanceUids(playerUid)
            fun narrated(outcome:ChatApplicationOutcome){
                assertTrue(outcome.toString(),outcome is ChatApplicationOutcome.Narrated)
            }

            narrated(application.play("Rozpoczynam zużywanie $itemName.",AiCancellationSignal.NONE))
            val pending=own().rows.single()
            assertTrue(hasItem()) // Future consumption is not awarded at initiation.
            assertTrue(pending.canCancelAt(repository.infrastructureTemporalRead().state.time))
            // Use exactly the displayed selector and message sent by the ordinary UI button.
            narrated(application.play("Przerywam ${pending.displayLabel}. Pozostałe zadania pozostawiam bez zmian.",AiCancellationSignal.NONE))
            assertTrue(own().rows.isEmpty())
            assertTrue(hasItem())
            LocalGameStore(context).openGameplaySaveDb().use { db->
                assertEquals(BackgroundProcessStatus.INTERRUPTED,Phase64BackgroundStore(db,campaign).process(pending.processRef.uid)?.status)
                assertTrue(Phase60TemporalStateStore(db,campaign).read().deadlines.none { it.uid.contains(pending.processRef.uid) })
            }
            val undo=repository.previewUndoLastTurn()
            assertTrue(undo.toString(),undo.canConfirm)
            assertTrue(repository.confirmUndoLastTurn(undo.previewToken) is DestructiveUndoResult.Completed)
            assertEquals(pending.processRef,own().rows.single().processRef)
            assertTrue(hasItem())
            // Different choice after Undo: let the existing process actually finish.
            narrated(application.play("Czekam w miejscu przez siedemdziesiąt sekund.",AiCancellationSignal.NONE))
            assertTrue(own().rows.isEmpty())
            assertFalse(hasItem())
            LocalGameStore(context).openGameplaySaveDb().use { db->
                assertEquals(BackgroundProcessStatus.COMPLETED,Phase64BackgroundStore(db,campaign).process(pending.processRef.uid)?.status)
            }
        } finally {
            repository.closeBackgroundWorkForTest()
            runCatching { LocalGameStore(context).setActiveCampaign(previous) }
            runCatching { LocalGameStore(context).moveCampaignToTrash(created.name) }
        }
    }

    private fun provider(campaign:String,selection:AiModelSelection)=DeterministicAiProvider(
        AiCapabilityContract("P64:CHAT:AI",selection.providerUid,selection.modelUid,AiWorkload.entries.toSet(),maximumContextUnits=16_000),
        intentFunction={request->
            val cancel=request.rawInput.startsWith("Przerywam ")
            val wait=request.rawInput.startsWith("Czekam ")
            val phrase=if(cancel)request.rawInput.removePrefix("Przerywam ").substringBefore('.') else if(wait)"ja" else itemName
            val action=if(cancel)Phase64ProcessActivation.CANCEL_ACTION else if(wait)"WAIT" else "CONSUME_OWN_ITEM"
            IntentDocument(campaignUid=campaign,actor=request.actor,rawInput=request.rawInput,meaningState=MeaningState.UNDERSTOOD,
                nodes=listOf(IntentNode("ACTION",IntentForm.DIRECT_ACTION,SemanticAction(canonicalActionUid=action,semanticFamilyUid=if(wait)"WAIT" else "OPEN_WORLD_ACTION",rawPhrase=request.rawInput,
                    attributes=if(wait)mapOf("time_min_ms" to "70000","time_max_ms" to "70000") else emptyMap()),
                    participants=listOf(IntentParticipant("TARGET",referenceUid="TARGET")))),
                references=listOf(IntentReference("TARGET",IntentReferenceKind.DESCRIPTIVE,phrase,"TARGET")),
                provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,selection.providerUid,"1","DEVICE"))
        },
        proposalFunction={request->
            val node=request.plan.intent.nodes.single()
            val target=requireNotNull(request.plan.intent.references.single().resolvedProjectedRef)
            GmProposalCandidate(1,"PROPOSAL:${request.requestUid}",campaign,request.plan.planUid,
                listOf(GmNodeProposal(node.nodeUid,"OK","Wykonujesz zadeklarowaną czynność.",request.plan.intent.actor,
                    node.semanticAction.canonicalActionUid!!,listOf(target),node.modality,GmNodeOutcomeState.PROPOSED_SUCCESS)),
                mechanicsEffects=listOf(MechanicsEffectRequest("EFFECT",node.nodeUid,"UNIVERSAL_ACTION","INTERACTION",target)),
                narrativeBlueprint=NarrativeBlueprint(listOf("COMMITTED_ACTION"),stopPointUid="PLAYER_DECISION_POINT"),
                providerUid=selection.providerUid,modelUid=selection.modelUid,intentFingerprint=request.plan.intent.canonicalFingerprint())
        },
        narrativeFunction={request->RenderedNarrative("Wykonujesz zadeklarowaną czynność.",request.context.stopPointUid,request.context.committedOrder)}
    )
}
