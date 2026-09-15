package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcMechanicsTest {
    private val brain=NpcBrainOwner.initialize("C1",DomainRef("NPC","N1"),"SEED")
    private val scope=NpcDecisionScope(TemporalScope("C1","G1",1,"HASH"),brain.actor,1,WorldTimeTick(0),0,"P1")
    private val knowledge=NpcKnownRecord("K1",KnowledgeEpistemicState.KNOWN,"Postać zakończyła ćwiczenie.","A1",1,setOf(brain.actor))
    private val option=NpcActionOption("TRAIN","TRAIN",brain.actor,AcceptedActionTiming(ActionDuration(1000),"RULE",1),null,emptyList(),setOf("K1"),
        resourceCosts=mapOf("ENERGY" to 2),parameters=mapOf("resource_uid" to "ENERGY"),mechanicsOwnerUid="CORE",mechanicalEffectKindUid="RESOURCE_DELTA")
    private fun context():NpcContextResult.Ready {
        val reads=object:NpcProjectionReadPort {
            override fun brain(audience:AudienceContext,purpose:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef)=ProtectedReadResult.Allow(brain,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(listOf(knowledge),DisclosureLevel.DISCLOSE_FULL,"ACQUIRED")
        }
        return NpcDecisionContextProjector(reads).project(scope,NpcTrigger("T",NpcTriggerKind.KNOWLEDGE_CHANGED,WorldTimeTick(0),
            NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A1")),brain.knowledgeHolder,ContextRuntimeProfile("CPU",4096,64,64,512)) {_,_->listOf(option)} as NpcContextResult.Ready
    }
    private fun selected(context:NpcContextResult.Ready)=NpcDecisionEngine().select(context.context,NpcDecisionProposal("R",context.context.contextFingerprint,
        listOf(NpcDecisionCandidate(option.uid))),scope) as NpcDecisionResult.Selected

    @Test fun decisionDelegatesToCoreAndMaterializesWithoutCommittingOrInventingMagnitude() {
        val context=context();val selected=selected(context);var called=0
        val engine=NpcMechanicalActionApplication(MechanicsRuleResolver { request,mechanics ->
            called++
            assertEquals(brain.actor.uid,mechanics.plan.intent.actor.actorUid)
            assertEquals(selected.authorization,mechanics.npcAuthorization)
            assertTrue(selected.authorization.authorizesMechanics(scope.temporal,mechanics.plan,mechanics.plan.intent.nodes.single(),request))
            assertFalse(selected.authorization.authorizesMechanics(scope.temporal,mechanics.plan,mechanics.plan.intent.nodes.single(),request.copy(parameters=mapOf("resource_uid" to "HEALTH"))))
            MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid,request.nodeUid,"CORE","RESOURCE_DELTA",
                mapOf("resource_uid" to "ENERGY","magnitude" to "-3"),"PROOF","INPUT","OUTPUT"))
        },{scope.temporal})
        val result=engine.resolve(context,selected)
        assertTrue(result.toString(),result is NpcMechanicalResult.Resolved)
        assertEquals(listOf(ResourceChange(brain.actor,"ENERGY",ExactLongDelta.of(-3))),(result as NpcMechanicalResult.Resolved).changes)
        assertEquals(1,called);assertEquals(option.timing,result.timing)
    }
    @Test fun alteredOptionAndHistoryNeverReachMechanics() {
        val context=context();val selected=selected(context)
        val engine=NpcMechanicalActionApplication(MechanicsRuleResolver{_,_->error("must not run")},{scope.temporal})
        assertEquals(NpcMechanicalResult.Unavailable("P62:ACTION_AUTHORIZATION_MISMATCH"),engine.resolve(context,selected.copy(option=option.copy(target=DomainRef("NPC","SECRET")))))
        assertEquals(NpcMechanicalResult.Unavailable("P62:STALE_SCOPE"),NpcMechanicalActionApplication(MechanicsRuleResolver{_,_->error("must not run")},
            {scope.temporal.copy(historyGenerationUid="NEW")}).resolve(context,selected))
    }
    @Test fun failureIsNotAnExecutedAction() {
        val context=context()
        val result=NpcMechanicalActionApplication(MechanicsRuleResolver{_,_->MechanicsEffectResolution.Rejected("NO_ENERGY")},{scope.temporal}).resolve(context,selected(context))
        assertEquals(NpcMechanicalResult.Unavailable("P62:MECHANICS:NO_ENERGY"),result)
    }
}
