package com.rpgos.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Phase62NpcMobileBudgetTest {
    private val actor=DomainRef("ACTOR","DYN-ACTOR-510BC4CDF16F7958E02C6741")
    private val base=NpcBrainOwner.initialize("lab-npc-mobile",actor,"mobile-budget-regression")
    private val cause=NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,base.motivations.first().uid)
    private val goal=NpcGoal("P61:GOAL:independent-npc-short-rest",cause.uid,"Odpocząć przed kolejnym zajęciem",NpcWeight(5000),NpcGoalLifecycle.ACTIVE,cause)
    private val scope=NpcDecisionScope(TemporalScope(base.campaignUid,"HISTORY-MOBILE",5,"a".repeat(64)),actor,1,WorldTimeTick(1000),0,"PLAYER:SMAGI")
    private val trigger=NpcTrigger("P62:TRIGGER:SELF_REFLECTION",NpcTriggerKind.SELF_REFLECTION,scope.atTime,cause)
    private fun project(brain:NpcBrainState,offer:Boolean,records:List<NpcKnownRecord> = emptyList()):NpcContextResult {
        val reads=object:NpcProjectionReadPort {
            override fun brain(a:AudienceContext,p:PurposeContext,actor:DomainRef,h:KnowledgeHolderRef)=ProtectedReadResult.Allow(brain,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(a:AudienceContext,p:PurposeContext,h:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(records,DisclosureLevel.DISCLOSE_FULL,"HOLDER")
        }
        return NpcDecisionContextProjector(reads).project(scope,trigger,brain.knowledgeHolder,NpcContextProfiles.MOBILE){b,_->
            if(offer)listOf(NpcActivityMechanics.option(b,b.goals.single(),null,requireNotNull(NpcActivityContractPort.STANDARD.contract(b.campaignUid,"WAIT")))) else emptyList()
        }
    }
    @Test fun realProductionReservesFitAnAuthorizedActionAndLongCanonicalActorIdentity() {
        assertEquals(1152,NpcContextProfiles.MOBILE.payloadUnits)
        val state=base.copy(goals=listOf(goal))
        val result=project(state,true)
        assertTrue(result.toString(),result is NpcContextResult.Ready)
        val ready=result as NpcContextResult.Ready
        val wire=NpcDecisionCodec.encodeRequest("R".repeat(160),ready.context)
        assertTrue("actual units=${(wire.length+3)/4}",(wire.length+3)/4<=1152)
        assertEquals("WAIT",ready.context.options.single().capabilityUid)
        assertTrue(ready.budget.safeForAi)
        assertTrue(wire.contains(actor.uid))
        assertEquals(state,ready.context.brain)
    }
    @Test fun accumulatedFearsDoNotDisableCognitionOrDeleteCanonicalIndividuality() {
        val state=base.copy(goals=listOf(goal),motivations=base.motivations+(0 until 61).map{n->
            NpcMotivation("P61:FEAR:${n.toString().padStart(32,'0')}",NpcMotivationKind.FEAR,"SECURITY",NpcWeight(10000),DomainRef("ACTOR","OTHER:$n"))
        })
        val result=project(state,false)
        assertTrue(result.toString(),result is NpcContextResult.Ready)
        val ready=result as NpcContextResult.Ready
        val wire=Json.parseToJsonElement(NpcDecisionCodec.encodeRequest("R".repeat(160),ready.context)).jsonObject
        assertFalse(wire.containsKey("alternative"));assertFalse(wire.containsKey("execution_goal"))
        val view=wire.getValue("brain").jsonObject
        assertEquals(4,view.getValue("motivations").jsonArray.size)
        assertTrue(view.getValue("motivations").jsonArray.any{it.jsonObject.getValue("uid").jsonPrimitive.content==goal.motivationUid})
        assertEquals(60,view.getValue("omitted_motivation_count").jsonPrimitive.int)
        assertEquals(64,ready.context.brain.motivations.size)
        assertEquals(state,ready.context.brain)
    }
    @Test fun oversizedRequiredKnowledgeIsRejectedInsteadOfTruncatedOrPromoted() {
        val records=(0 until 8).map{NpcKnownRecord("REQUIRED:$it",KnowledgeEpistemicState.BELIEVED,"X".repeat(512),"ACQ:$it",1)}
        val state=base.copy(goals=listOf(goal.copy(cause=NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"ACQ:0"))))
        // A required evidence source cannot be silently dropped to make an option fit.
        val reads=object:NpcProjectionReadPort {
            override fun brain(a:AudienceContext,p:PurposeContext,actor:DomainRef,h:KnowledgeHolderRef)=ProtectedReadResult.Allow(state,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(a:AudienceContext,p:PurposeContext,h:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(records,DisclosureLevel.DISCLOSE_FULL,"HOLDER")
        }
        val result=NpcDecisionContextProjector(reads).project(scope,trigger,state.knowledgeHolder,NpcContextProfiles.MOBILE){b,_->
            listOf(NpcActivityMechanics.option(b,b.goals.single(),records.first(),requireNotNull(NpcActivityContractPort.STANDARD.contract(b.campaignUid,"WAIT")))
                .copy(supportingRecordUids=records.map{it.uid}.toSet()))
        }
        assertTrue(result.toString(),result is NpcContextResult.Unavailable)
        assertTrue(records.all{it.epistemicState==KnowledgeEpistemicState.BELIEVED})
        assertEquals(4096,records.sumOf{it.projectedText.length})
    }
}
