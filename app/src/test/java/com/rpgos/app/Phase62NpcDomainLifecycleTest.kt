package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

/** The existing timed application is reused for all new domains. No future reward is stored in
 * pending actions, and completing an action never invents mastery, world FACT or healed state. */
class Phase62NpcDomainLifecycleTest {
    private class Fixture(val mode:String) {
        val actor=DomainRef("NPC","N")
        var scope=TemporalScope("C","H",1,"STATE")
        var available=true;var version=1;var calls=0
        val brain=NpcBrainOwner.initialize("C",actor,"SEED").let{it.copy(goals=listOf(NpcGoal("G",it.motivations.first().uid,
            "Own activity",NpcWeight(10000),NpcGoalLifecycle.ACTIVE,NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,it.motivations.first().uid))))}
        val body=MechanicalActorView("C",actor,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,
            mapOf("MEDICINE" to 10L),listOf(MechanicalResource("HEALTH",50,100),MechanicalResource("STAMINA",10,10)),setOf(mode),
            conditions=listOf(MechanicalCondition("WOUND",10)),generationProvenanceUid="TEST")
        fun contract()=NpcActivityContract(mode,"WORLD:$mode",version,ActionDuration(1000),"EFFORT:$mode",
            learning=if(mode=="TRAIN")NpcLearningRule("SKILL","SK","XP",2) else null,
            reading=if(mode=="READ")NpcReadingRule(DomainRef("BOOK","B"),"READ_POLICY",KnowledgeClaim("CL","LOCATION","A","P","V",domainUid=KnowledgeDomains.WORLD_SPECIFIC)) else null,
            treatment=if(mode=="TREAT")NpcTreatmentRule(mapOf("HEALTH" to 10),5) else null,
            duty=if(mode=="DUTY")NpcDutyRule("D",1,"ORG","ROLE","DEADLINE",WorldTimeTick(5000),"ASSIGNMENT") else null)
        val learning=NpcLearningStatePort{_,_,_->if(available)NpcLearningState(10.0,0.0,"XP",1,1) else null}
        val reading=NpcReadingAccessPort{_,_,_->available}
        val treatment=NpcTreatmentReadPort{_,_,_,_,_->if(available)body else null}
        val duties=NpcDutyAssignmentPort{_,_,_,_->available}
        fun context(input:TemporalOwnerInput,pending:NpcPendingAction?):NpcContextResult.Ready {
            val state=applyNpcBrainOverlay(brain,input.scope,input.stagedChanges.filterIsInstance<NpcBrainChange>())
            val options=NpcMechanicalAffordances(CombatAbilityContractPort.UNIVERSAL_FALLBACK,NpcActivityContractPort.registered(listOf(contract())),
                learningState=learning,readingAccess=reading,treatments=treatment,duties=duties,atTime=input.through,starting=pending==null)
                .options(state,emptyList(),body)
            val trigger=NpcTrigger("T",if(pending==null)NpcTriggerKind.SELF_REFLECTION else NpcTriggerKind.PLAN_BOUNDARY,input.through,
                pending?.let{state.plans.single{p->p.uid==it.planUid}.cause}?:state.goals.single().cause)
            val reads=object:NpcProjectionReadPort {
                override fun brain(a:AudienceContext,p:PurposeContext,r:DomainRef,h:KnowledgeHolderRef)=ProtectedReadResult.Allow(state,DisclosureLevel.DISCLOSE_FULL,"SELF")
                override fun knowledge(a:AudienceContext,p:PurposeContext,h:KnowledgeHolderRef,o:Long,l:Int)=ProtectedReadResult.Allow(emptyList<NpcKnownRecord>(),DisclosureLevel.DISCLOSE_FULL,"ACQUIRED")
                override fun currentRoles(a:AudienceContext,p:PurposeContext)=setOf("ROLE")
            }
            return NpcDecisionContextProjector(reads).project(NpcDecisionScope(input.scope,actor,state.revision,input.through,0,"P"),trigger,
                state.knowledgeHolder,ContextRuntimeProfile("TEST",8192,64,64,512)){_,_->options} as NpcContextResult.Ready
        }
        val provider=DeterministicAiProvider(AiCapabilityContract("TEST","TEST","TEST",setOf(AiWorkload.NPC_DECISION),maximumContextUnits=8192),
            intentFunction={error("unused")},proposalFunction={error("unused")},narrativeFunction={error("unused")},npcDecisionFunction={r->
                calls++;NpcDecisionProposal(r.requestUid,r.context.contextFingerprint,listOf(NpcDecisionCandidate(r.context.options.single().uid)))})
        fun app()=NpcTimedActionApplication("CMD",NpcPhysicalContextPort{_,input,pending->context(input,pending)},
            AiModelRoutePort{_,_,_->AiRouteResult.Selected(provider,true,"TEST")},{scope},
            NpcMechanicalActionApplication(MechanicsRuleResolver{r,c->NpcActivityMechanics.resolve(r,c,body,contract(),learning,reading,
                NpcActivityRequirementPort.NONE,treatment,duties)},{scope}))
        fun input(at:Long,changes:List<PlayerDomainChangePayload> = emptyList())=TemporalOwnerInput(scope,WorldTimeTick(0),WorldTimeTick(at),emptyList(),emptyList(),null,changes)
        fun begin()=app().prepare(actor,input(0)){false} as NpcActionPreparation.Started
    }
    @Test fun domainsFinishOnlyAtTheirBoundaryAndDoNotClaimWorldGoalSuccess() {
        for(mode in listOf("TRAIN","READ","TREAT","DUTY")) {
            val f=Fixture(mode);val started=f.begin()
            assertTrue(f.app().complete(started.pending,f.input(999,started.changes)){false} is NpcActionCompletion.Unavailable)
            val done=f.app().complete(started.pending,f.input(1000,started.changes)){false} as NpcActionCompletion.Finished
            assertFalse(mode,done.interrupted);assertTrue(done.effects.isNotEmpty());assertEquals(1,f.calls)
            assertEquals(NpcGoalLifecycle.ACTIVE,NpcBrainCodec.decode(done.brainChange.stateCanonical).goals.single().lifecycle)
        }
    }
    @Test fun changedPrerequisiteOrContractInterruptsWithoutAnyReward() {
        for(mode in listOf("TRAIN","READ","TREAT","DUTY"))for(change in listOf("ACCESS","VERSION")) {
            val f=Fixture(mode);val started=f.begin()
            if(change=="ACCESS")f.available=false else f.version=2
            val done=f.app().complete(started.pending,f.input(1000,started.changes)){false} as NpcActionCompletion.Finished
            assertTrue("$mode:$change",done.interrupted);assertTrue(done.effects.isEmpty());assertEquals(1,f.calls)
        }
    }
    @Test fun cancelledOrOldGenerationCompletionCannotProduceEffects() {
        for(mode in listOf("TRAIN","READ","TREAT","DUTY")) {
            val f=Fixture(mode);val started=f.begin()
            assertTrue(f.app().complete(started.pending,f.input(1000,started.changes)){true} is NpcActionCompletion.Unavailable)
            val old=f.input(1000,started.changes);f.scope=f.scope.copy(historyGenerationUid="NEW")
            assertTrue(f.app().complete(started.pending,old){false} is NpcActionCompletion.Unavailable)
        }
    }
}
