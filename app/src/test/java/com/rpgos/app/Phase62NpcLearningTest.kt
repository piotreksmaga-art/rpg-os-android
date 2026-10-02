package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase62NpcLearningTest {
    private val actor=DomainRef("NPC","N")
    private val rule=NpcLearningRule(ProgressionTargetKinds.SKILL,"POTTERY","XP",3,10.0)
    private val contract=NpcActivityContract("PRACTICE","WORLD:POTTERY",2,ActionDuration(30000),"POTTERY_EFFORT",learning=rule)
    private val state=NpcLearningState(10.0,2.0,"XP",1,1)
    private fun effect(c:NpcActivityContract=contract,s:NpcLearningState=state)=VerifiedMechanicsCommandEffect("E","NODE",NpcActivityMechanics.OWNER,
        "INTERACTION",actor,1,mapOf("track_uid" to c.effortTrackUid,"magnitude" to "1","p60_core_duration_ms" to "30000",
            "npc_activity_contract" to c.fingerprint)+(if(c.learning==null)emptyMap() else NpcLearningApplication.fields("C",actor,c,s)),"PROOF","INPUT","OUTPUT")

    @Test fun registeredLearningUsesExistingProgressionOwnerAndNeverAcquiresATechnique() {
        val command=PlayerCommand(commandUid="CMD",campaignUid="C",actor=CommandActorRef("PLAYER","P"),commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN",listOf(effect())),provenance=CommandProvenance("TEST"))
        val refs=setOf(actor,DomainRef("PLAYER","P"),DomainRef("SKILL","POTTERY")).map{CampaignScopedDomainRef("C",it)}.toSet()
        val result=productionMechanicsPlayerDomainEngine().resolve(command,PlayerResolutionContext.createUnboundGeneric("C",command.actor,refs))
        assertTrue(result.toString(),result is PlayerResolutionOutcome.Resolved)
        val changes=(result as PlayerResolutionOutcome.Resolved).proposal.changes
        assertEquals(listOf(SkillChange(actor,"POTTERY",ExactLongDelta.of(3))),changes.map{it.payload}.filterIsInstance<SkillChange>())
        assertTrue(changes.none{it.payload is TechniqueChange})
        assertEquals(1,result.proposal.ledgerIntents.size)
        val owner=NpcActivityMechanics.ownerContract(contract)
        assertEquals(NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE,owner.resultPolicy)
        assertEquals(NpcActionProcess.OWNER,owner.lifecycleOwnerUid)
        assertTrue(PlayerChangeKinds.SKILL in owner.allowedCanonicalChangeKindUids)
        assertFalse(PlayerChangeKinds.TECHNIQUE in owner.allowedCanonicalChangeKindUids)
        assertNotEquals(owner.fingerprint,NpcActivityMechanics.ownerContract(contract.copy(version=3)).fingerprint)
    }
    @Test fun missingEntryChangedSemanticsOrInsufficientMasteryCannotReceiveProgress() {
        assertFalse(NpcLearningApplication.admitted(rule,null))
        assertFalse(NpcLearningApplication.admitted(rule,state.copy(progressSemanticsUid="OTHER")))
        assertFalse(NpcLearningApplication.admitted(rule,state.copy(mastery=9.0)))
        assertTrue(NpcLearningApplication.admitted(rule,state))
        assertNotEquals(contract.fingerprint,contract.copy(learning=rule.copy(effortUnits=100)).fingerprint)
    }
    @Test fun codecRetainsTypedRulesAndCostsWithoutTreatingEffortAsMastery() {
        val complex=contract.copy(requirements=NpcActivityRequirements(mapOf("STAMINA" to 2),setOf("BOOK"),setOf("CLAIM"),DomainRef("NPC","TEACHER")))
        assertEquals(complex,NpcActivityContractCodec.decode(NpcActivityContractCodec.encode(complex)))
        assertEquals(complex.fingerprint,NpcActivityContractCodec.decode(NpcActivityContractCodec.encode(complex)).fingerprint)
    }
    @Test fun authorizedReadingAcquiresABeliefAndDoesNotCreateWorldTruth() {
        val reading=contract.copy(learning=null,reading=NpcReadingRule(DomainRef("BOOK","B"),"READ_POLICY",
            KnowledgeClaim("CLAIM","LOCATION","A","HAS_DRAGON","yes",domainUid=KnowledgeDomains.WORLD_SPECIFIC)))
        val e=effect(reading).copy(canonicalPayload=mapOf("npc_activity_contract" to reading.fingerprint)+NpcReadingApplication.fields("C",actor,reading))
        val draft=NpcReadingApplication.materialize("C","CMD",1,listOf(e))
        val knowledge=draft.changes.single().payload as KnowledgeAcquisitionChange
        assertEquals(KnowledgeEpistemicState.BELIEVED,knowledge.acquisition.epistemicState)
        assertEquals(KnowledgeAcquisitionMethods.DOCUMENT,knowledge.acquisition.methodUid)
        assertTrue(draft.changes.none{it.payload is CampaignTruthChange})
        assertEquals(reading,NpcActivityContractCodec.decode(NpcActivityContractCodec.encode(reading)))
        assertThrows(IllegalArgumentException::class.java){NpcReadingApplication.materialize("OTHER","CMD",1,listOf(e))}
    }
    @Test fun multiResourceEffectsRetainTheExactResourceForEachMember() {
        val verified=VerifiedMechanicsEffect("E","NODE",NpcActivityMechanics.OWNER,"INTERACTION",mapOf(
            "target_kind_uid" to "NPC","target_uid" to "N","magnitude" to "1","area_target_count" to "2",
            "area_target_0_kind_uid" to "NPC","area_target_0_uid" to "N","area_target_0_effect_kind_uid" to "RESOURCE_DELTA",
            "area_target_0_magnitude" to "-2","area_target_0_resource_uid" to "STAMINA",
            "area_target_1_kind_uid" to "NPC","area_target_1_uid" to "N","area_target_1_effect_kind_uid" to "RESOURCE_DELTA",
            "area_target_1_magnitude" to "-1","area_target_1_resource_uid" to "FOCUS"),"PROOF")
        val material=canonicalMechanicsCommandEffects(verified,actor)!!.map{MechanicalEffectMaterializer.materialize(it) as MechanicalEffectMaterializationResult.Materialized}
        assertEquals(listOf("STAMINA","FOCUS"),material.map{(it.changes.single().payload as ResourceChange).resourceUid})
    }
}
