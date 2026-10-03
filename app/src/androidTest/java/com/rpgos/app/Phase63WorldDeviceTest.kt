package com.rpgos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android SQLite + production bootstrap/materialization/transaction/replay ports.
 * No direct population SQL, synthetic World Pack, model host or mutation through a read. */
@RunWith(AndroidJUnit4::class)
class Phase63WorldDeviceTest {
    @Test fun nativeWorldPopulationRollbackReopenUndoAndAlternateSlot() {
        val context:Context=ApplicationProvider.getApplicationContext()
        val local=LocalGameStore(context)
        local.bootstrap()
        val previous=local.activeCampaignDirName()
        val created=local.createNativeCampaign(NativeWorldCreationSpec("P63-device-${System.nanoTime()}",
            "Górska wioska bez nadnaturalnych mocy.","Era przedindustrialna","Kamienna",startingCategory="MOUNTAIN_SETTLEMENT"))
        val repository=UnifiedGameRepository(context)
        try {
            val catalog=local.characterCreationCatalog()
            fun choices(kind:CharacterCreationDefinitionKind)=catalog.options.filter { it.kind==kind }.map {
                CharacterCreationValueChoice(it.definitionUid,50.0.coerceAtLeast(it.minimumValue?:0.0).coerceAtMost(it.maximumValue?:100.0),it.dimensionUid)
            }
            val player=PlayerCharacterCreationDraft("P63-CREATE",catalog.campaignUid,"P63-PC","Smagi","UNSPECIFIED",
                stats=choices(CharacterCreationDefinitionKind.STAT),resources=choices(CharacterCreationDefinitionKind.RESOURCE),
                talents=choices(CharacterCreationDefinitionKind.TALENT),potentials=choices(CharacterCreationDefinitionKind.POTENTIAL),
                skills=choices(CharacterCreationDefinitionKind.SKILL),techniques=choices(CharacterCreationDefinitionKind.TECHNIQUE),
                startingLocationUid=local.worldLocations().single().uid)
            local.createPlayerCharacter(player,PlayerCharacterCreationConfirmation(PlayerCharacterBootstrapService.fingerprint(player),"EXPLICIT-CONFIRM"))
            assertEquals(listOf("Basic action"),local.techniqueBrowser().map { it.name })
            val campaign=catalog.campaignUid
            val skeleton=requireNotNull(repository.infrastructureWorldSkeletonCandidate())
            assertEquals(CampaignRuleSourceKind.CAMPAIGN_NATIVE,skeleton.ruleSource.kind)
            val slot=LatentWorldSlot(skeleton.initialAnchor.uid,"RESIDENTS",WorldElementBaseKind.GROUP)
            val group=WorldElementDraft(campaign,slot.ref(skeleton),"Mieszkańcy",WorldElementBaseKind.GROUP,"RESIDENTS",slot.regionUid,
                setOf("LOOK"),"LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE,emptyList(),null,null,null)
            fun prepare(draft:WorldElementDraft,uid:String,order:Long):Pair<TurnTransactionIdentity,CanonicalCampaignMutationProposal> {
                val identity=TurnTransactionIdentity(campaign,"T:$uid","CMD:$uid","TX:$uid")
                val effect=VerifiedMechanicsCommandEffect("E:$uid","N","RPGOS-CORE:WORLD-MATERIALIZER","WORLD_ELEMENT_MATERIALIZE",draft.element,1,
                    draft.materializationPayload()+repository.infrastructureWorldActorSeed(draft)?.let { mapOf("p63_actor_seed" to it) }.orEmpty()+
                        repository.infrastructureWorldPopulationPayload(draft),
                    "RPGOS-CORE:WORLD-MATERIALIZATION:${draft.fingerprint()}",phase63Hash("I:$uid"),phase63Hash("O:$uid"))
                val request=ChatTurnRequest(requestUid="REQUEST:$uid",campaignUid=campaign,turnUid=identity.turnUid,commandUid=identity.commandUid,
                    transactionUid=identity.transactionUid,actor=CommandActorRef("PLAYER",player.playerUid),input="Oglądam mieszkańców",localeUid="pl-PL",
                    audience=VisibilityAudienceFactory.player(campaign),purpose=PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),atOrder=order)
                val brains=repository.prepareNpcBrainInitializations(repository.infrastructureTemporalRead().scope,listOf(effect),listOf(draft.element),listOf(draft))
                val authority=repository.infrastructureWorldPackAuthority()
                val engine=productionMechanicsPlayerDomainEngine(WorldRuleProviderRegistry.of(listOf(UniversalMechanicsWorldRuleProvider(authority.binding))),
                    WorldPackAuthoritySnapshot.single(campaign,authority.binding))
                val command=PlayerCommand(commandUid=identity.commandUid,campaignUid=campaign,actor=request.actor,
                    commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,payload=ApplyVerifiedMechanicsCommandPayload("PLAN:$uid",listOf(effect),
                        npcBrains=brains,worldChanges=repository.infrastructureWorldExpansion(request,listOf(effect))),
                    provenance=CommandProvenance("P63:DEVICE"),requestedEffectiveOrder=order)
                val holders=brains.map { NpcBrainCodec.decode(it.stateCanonical).knowledgeHolder }.map { DomainRef(it.holderKindUid,it.holderUid) }
                val refs=(listOf(DomainRef("PLAYER",player.playerUid),DomainRef("CAMPAIGN",campaign),group.element,draft.element)+holders)
                    .map { CampaignScopedDomainRef(campaign,it) }.toSet()
                val admission=CampaignMutationBoundary.resolveAndAdmit(campaign,engine,command,
                    PlayerResolutionContext.create(campaign,request.actor,refs,worldRuleMode=WorldRuleMode.Bound(authority.binding)))
                assertTrue(admission.toString(),admission is CampaignMutationAdmission.Accepted)
                return identity to (admission as CampaignMutationAdmission.Accepted).proposal
            }
            fun digest()=local.openGameplaySaveDb().use { AuthoritativeStateDigest.compute(it) }
            val prepared=prepare(group,"GROUP",1)
            val before=digest()
            assertTrue(runCatching { repository.commitTurn(prepared.first,prepared.second,TurnFailureInjector {
                if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("P63:INJECTED")
            }) }.isFailure)
            assertEquals(before,digest())
            assertTrue(repository.commitTurn(prepared.first,prepared.second,TurnFailureInjector.NONE) is TurnExecutionResult.Committed)
            val committed=digest()
            assertTrue(repository.commitTurn(prepared.first,prepared.second,TurnFailureInjector.NONE) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(committed,digest())
            val manifest=local.openGameplaySaveDb().use { requireNotNull(WorldPopulationStore(it,campaign).forAggregate(group.element)) }
            fun member(ordinal:Long)=WorldElementDraft(campaign,manifest.member(ordinal),"Mieszkaniec ${ordinal+1}",WorldElementBaseKind.ACTOR,"RESIDENT",slot.regionUid,
                setOf("LOOK"),"LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE,
                listOf(manifest.uid,Phase63PopulationCodec.aggregateEvidence(group.element)),null,null,null,slotOrdinal=ordinal)
            val first=member(0)
            val extraction=prepare(first,"FIRST",2)
            assertTrue(repository.commitTurn(extraction.first,extraction.second,TurnFailureInjector.NONE) is TurnExecutionResult.Committed)
            val reopened=UnifiedGameRepository(context)
            assertEquals(manifest.originalCount-1,reopened.infrastructureMechanicalActor(group.element)!!.aggregatePopulation!!.totalCount)
            assertNotNull(reopened.infrastructureNpcBrainDiagnostics(first.element))
            val generation=reopened.infrastructureHistoryGenerationUid()
            val preview=reopened.previewUndoLastTurn()
            assertTrue(preview.toString(),preview.canConfirm)
            assertTrue(reopened.confirmUndoLastTurn(preview.previewToken) is DestructiveUndoResult.Completed)
            assertNotEquals(generation,reopened.infrastructureHistoryGenerationUid())
            assertEquals(manifest.originalCount,reopened.infrastructureMechanicalActor(group.element)!!.aggregatePopulation!!.totalCount)
            assertNull(reopened.infrastructureMechanicalActor(first.element))
            assertNull(reopened.infrastructureNpcBrainDiagnostics(first.element))
            assertEquals(skeleton,requireNotNull(reopened.infrastructureWorldSkeletonCandidate()))
            val second=member(1)
            val alternate=prepare(second,"SECOND",2)
            assertTrue(repository.commitTurn(alternate.first,alternate.second,TurnFailureInjector.NONE) is TurnExecutionResult.Committed)
            assertNotEquals(first.element,second.element)
            assertEquals(setOf(second.element),local.openGameplaySaveDb().use { WorldPopulationStore(it,campaign).namedMembers(group.element).toSet() })
            val coarse=repository.infrastructureWorldWorkPlan(repository.infrastructureTemporalRead(),emptyList())
            assertFalse(second.element in coarse.individualDecisionActors)
            assertNotNull(repository.infrastructureNpcBrainDiagnostics(second.element))
            assertEquals(NpcCauseKind.INTRINSIC_MOTIVATION,repository.npcCognitionStimulus(repository.infrastructureTemporalRead().scope,second.element)!!.causeKind)
            reopened.closeBackgroundWorkForTest()
        } finally {
            repository.closeBackgroundWorkForTest()
            local.setActiveCampaign(previous)
            local.moveCampaignToTrash(created.name)
        }
    }
}
