package com.rpgos.app

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Actual campaign source/bootstrap, canonical commits and production snapshot reads. No AI,
 * direct SQL population seeding, alternative damage engine, or disposable projection authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase63ProductionCombatTest {
    @Test fun canonicalFormation() {
        val context:Context=RuntimeEnvironment.getApplication()
        val local=LocalGameStore(context)
        local.createNativeCampaign(NativeWorldCreationSpec("Combat-${System.nanoTime()}","Wioska bez nadnaturalnych mocy.","Era","Wioska"))
        val catalog=local.characterCreationCatalog()
        fun choices(kind:CharacterCreationDefinitionKind)=catalog.options.filter { it.kind==kind }.map {
            CharacterCreationValueChoice(it.definitionUid,50.0.coerceAtLeast(it.minimumValue?:0.0).coerceAtMost(it.maximumValue?:100.0),it.dimensionUid)
        }
        val player=PlayerCharacterCreationDraft("PC-CREATION",catalog.campaignUid,"PC63","Gracz","UNSPECIFIED",
            stats=choices(CharacterCreationDefinitionKind.STAT),resources=choices(CharacterCreationDefinitionKind.RESOURCE),
            talents=choices(CharacterCreationDefinitionKind.TALENT),potentials=choices(CharacterCreationDefinitionKind.POTENTIAL),
            skills=choices(CharacterCreationDefinitionKind.SKILL),techniques=choices(CharacterCreationDefinitionKind.TECHNIQUE),
            startingLocationUid=local.worldLocations().single().uid)
        local.createPlayerCharacter(player,PlayerCharacterCreationConfirmation(PlayerCharacterBootstrapService.fingerprint(player),"CONFIRM"))
        val repository=UnifiedGameRepository(context)
        val campaign=catalog.campaignUid
        val skeleton=requireNotNull(repository.infrastructureWorldSkeletonCandidate())
        val slot=LatentWorldSlot(skeleton.initialAnchor.uid,"RESIDENTS",WorldElementBaseKind.GROUP)
        val group=WorldElementDraft(campaign,slot.ref(skeleton),"Mieszkańcy",WorldElementBaseKind.GROUP,"RESIDENTS",slot.regionUid,
            setOf("LOOK"),"LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE,emptyList(),null,null,null)
        fun commit(draft:WorldElementDraft,order:Long) {
            val identity=TurnTransactionIdentity(campaign,"T$order","CMD$order","TX$order")
            val payload=draft.materializationPayload()+repository.infrastructureWorldActorSeed(draft)?.let { mapOf("p63_actor_seed" to it) }.orEmpty()+
                repository.infrastructureWorldPopulationPayload(draft)
            val effect=VerifiedMechanicsCommandEffect("E$order","N","RPGOS-CORE:WORLD-MATERIALIZER","WORLD_ELEMENT_MATERIALIZE",draft.element,1,
                payload,"RPGOS-CORE:WORLD-MATERIALIZATION:${draft.fingerprint()}",phase63Hash("I$order"),phase63Hash("O$order"))
            val request=ChatTurnRequest(requestUid="REQUEST$order",campaignUid=campaign,turnUid=identity.turnUid,commandUid=identity.commandUid,
                transactionUid=identity.transactionUid,actor=CommandActorRef("PLAYER",player.playerUid),input="Weryfikuję element",localeUid="pl-PL",
                audience=VisibilityAudienceFactory.player(campaign),purpose=PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),atOrder=order)
            val world=repository.infrastructureWorldExpansion(request,listOf(effect))
            val brains=repository.prepareNpcBrainInitializations(repository.infrastructureTemporalRead().scope,listOf(effect),listOf(draft.element),listOf(draft))
            val authority=repository.infrastructureWorldPackAuthority()
            val engine=productionMechanicsPlayerDomainEngine(WorldRuleProviderRegistry.of(listOf(UniversalMechanicsWorldRuleProvider(authority.binding))),
                WorldPackAuthoritySnapshot.single(campaign,authority.binding))
            val command=PlayerCommand(commandUid=identity.commandUid,campaignUid=campaign,actor=request.actor,
                commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,payload=ApplyVerifiedMechanicsCommandPayload("PLAN$order",listOf(effect),npcBrains=brains,worldChanges=world),
                provenance=CommandProvenance("P63:PRODUCTION-TEST"),requestedEffectiveOrder=order)
            val holders=brains.map { NpcBrainCodec.decode(it.stateCanonical).knowledgeHolder }.map { DomainRef(it.holderKindUid,it.holderUid) }
            val refs=(listOf(DomainRef("PLAYER",player.playerUid),DomainRef("CAMPAIGN",campaign),group.element,draft.element)+holders).map { CampaignScopedDomainRef(campaign,it) }.toSet()
            val admission=CampaignMutationBoundary.resolveAndAdmit(campaign,engine,command,
                PlayerResolutionContext.create(campaign,request.actor,refs,worldRuleMode=WorldRuleMode.Bound(authority.binding)))
            assertTrue(admission.toString(),admission is CampaignMutationAdmission.Accepted)
            assertTrue(repository.commitTurn(identity,(admission as CampaignMutationAdmission.Accepted).proposal,TurnFailureInjector.NONE) is TurnExecutionResult.Committed)
        }
        commit(group,1)
        val manifest=local.openGameplaySaveDb().use { WorldPopulationStore(it,campaign).forAggregate(group.element)!! }
        val member=WorldElementDraft(campaign,manifest.member(0),"Wyróżniony mieszkaniec",WorldElementBaseKind.ACTOR,"RESIDENT",slot.regionUid,
            setOf("LOOK"),"LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE,
            listOf(manifest.uid,Phase63PopulationCodec.aggregateEvidence(group.element)),null,null,null)
        commit(member,2)
        fun plan(family:String,order:Long=3):CanonicalTurnPlan {
            val node=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(canonicalActionUid=family,semanticFamilyUid=family,rawPhrase="Atakuję"),
                listOf(IntentParticipant("TARGET",referenceUid="R")))
            val reference=IntentReference("R",IntentReferenceKind.DESCRIPTIVE,"Mieszkańcy","TARGET",setOf("GROUP"),
                state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=group.element,resolutionEvidenceUid="P63:CANONICAL")
            val intent=IntentDocument(campaignUid=campaign,actor=CommandActorRef("PLAYER",player.playerUid),rawInput="Atakuję mieszkańców",
                meaningState=MeaningState.UNDERSTOOD,nodes=listOf(node),references=listOf(reference),
                provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","HASH"))
            return CanonicalTurnPlan(planUid="PLAN:$family",campaignUid=campaign,intent=intent,audience=VisibilityAudienceFactory.player(campaign),
                purpose=PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),steps=emptyList(),atOrder=order)
        }
        val areaPlan=plan("BLAST")
        assertEquals(setOf(group.element,member.element),repository.infrastructureWorldCombatMembers(areaPlan,listOf(group.element)).toSet())
        assertThrows(IllegalArgumentException::class.java) { repository.infrastructureWorldCombatMembers(plan("BLAST",0),listOf(group.element)) }
        fun mechanics(p:CanonicalTurnPlan):MechanicsResolutionContext {
            val core=SemanticCoreCapsule(campaign,p.planUid,p.intent.canonicalFingerprint(),p.intent.canonicalPayload(),"CORE",listOf("N"),emptyList(),emptyList(),emptyList())
            return MechanicsResolutionContext(campaign,p,BudgetedCanonicalContext(CanonicalContextCandidate(p,core,emptyList()),emptyList(),emptyList(),1,0,8192,1,true,emptyList()))
        }
        val authority=ProductionCombatSnapshotAuthority(repository)
        val effect=MechanicsEffectRequest("ATTACK","N","UNIVERSAL_COMBAT","WOUND",group.element)
        // No skill/technique + registered domain binding: a BLAST label is not a power.
        assertNull(authority.build(effect,mechanics(areaPlan),areaPlan.intent.nodes.single(),group.element))
        val singlePlan=plan("ATTACK")
        val snapshot=requireNotNull(authority.build(effect,mechanics(singlePlan),singlePlan.intent.nodes.single(),group.element))
        assertEquals(setOf(DomainRef("PLAYER",player.playerUid),group.element),snapshot.snapshot.actors.map { it.actor }.toSet())
        assertEquals(manifest.originalCount-1,snapshot.snapshot.actors.single { it.actor==group.element }.aggregatePopulation!!.totalCount)
        val captured=repository.infrastructureWorldResolutionScope()!!
        assertEquals(member.element,repository.infrastructureEstablishedWorldSlot(captured,member.element)!!.element)
        assertNull(repository.infrastructureEstablishedWorldSlot(captured.copy(historyGenerationUid=HistoryGenerationUid("OLD")),member.element))
        val stimulus=requireNotNull(repository.npcCognitionStimulus(repository.infrastructureTemporalRead().scope,member.element))
        assertEquals(NpcCauseKind.INTRINSIC_MOTIVATION,stimulus.causeKind)
        assertEquals(member.element,stimulus.actor)
    }
}
