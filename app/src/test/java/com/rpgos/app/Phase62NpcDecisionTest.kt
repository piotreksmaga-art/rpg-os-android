package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcDecisionTest {
    private val actor=DomainRef("NPC","N1")
    private val brain=NpcBrainOwner.initialize("C1",actor,"seed")
    private val scope=NpcDecisionScope(TemporalScope("C1","G1",7,"digest"),actor,1,WorldTimeTick(100),0,"P1")
    private val record=NpcKnownRecord("KNOWN1",KnowledgeEpistemicState.BELIEVED,"Usłyszano, że droga jest bezpieczna.","ACQ1",1)
    private val trigger=NpcTrigger("TRIGGER",NpcTriggerKind.KNOWLEDGE_CHANGED,WorldTimeTick(100),NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"ACQ1"))
    private fun option(uid:String,risk:Int=0,routine:Boolean=false)=NpcActionOption(uid,"WALK",null,
        AcceptedActionTiming(ActionDuration(1000),"rule",1),null,emptyList(),setOf(record.uid),perceivedRisk=NpcWeight(risk),routine=routine)
    private fun context(options:List<NpcActionOption> = listOf(option("A"),option("B",10_000)))=
        NpcDecisionContextEnvelope(scope,trigger,brain,listOf(record),options,4096)
    private fun proposal(c:NpcDecisionContextEnvelope,ids:List<String> = listOf("A","B"))=NpcDecisionProposal("REQ",c.contextFingerprint,ids.map(::NpcDecisionCandidate))
    private fun rejection(result:NpcDecisionResult)= (result as NpcDecisionResult.Unavailable).reasonUid

    @Test fun coreScoresCandidatesAndDoesNotTrustModelOrdering() {
        val c=context()
        val engine=NpcDecisionEngine()
        val selected=engine.select(NpcDecisionRequest("REQ",c),proposal(c,listOf("B","A")),scope) as NpcDecisionResult.Selected
        assertEquals("A",selected.option.uid)
        assertTrue(selected.authorization.matches(scope,c.contextFingerprint,selected.option))
        assertFalse(selected.authorization.matches(scope,c.contextFingerprint,selected.option.copy(capabilityUid="ATTACK")))
        assertEquals(selected.authorization.decisionUid,(engine.select(c,proposal(c),scope) as NpcDecisionResult.Selected).authorization.decisionUid)
    }
    @Test fun persistentFearIsASeparateExplainedFactorNotKnowledgeOrAnAbsoluteBan() {
        val target=DomainRef("NPC","KNOWN")
        val state=brain.copy(motivations=brain.motivations+NpcMotivation("FEAR",NpcMotivationKind.FEAR,"SECURITY",NpcWeight(8000),target))
        val options=listOf(option("APPROACH").copy(target=target),option("WAIT"))
        val c=NpcDecisionContextEnvelope(scope,trigger,state,listOf(record.copy(subjectRefs=setOf(target))),options,4096)
        val selected=NpcDecisionEngine().select(c,proposal(c,listOf("APPROACH","WAIT")),scope) as NpcDecisionResult.Selected
        assertEquals("WAIT",selected.option.uid)
        assertEquals(-4000L,selected.evaluations.single{it.optionUid=="APPROACH"}.factors.single{it.uid=="FEAR:FEAR"}.contribution.units)
        assertTrue(NpcDecisionEngine().select(c,proposal(c,listOf("APPROACH")),scope) is NpcDecisionResult.Selected)
        assertEquals(KnowledgeEpistemicState.BELIEVED,c.records.single().epistemicState)
    }
    @Test fun unknownActionsStaleHistoryAndWrongRequestsAreRejected() {
        val c=context();val engine=NpcDecisionEngine()
        assertEquals("P62:UNAUTHORIZED_OPTION",rejection(engine.select(c,proposal(c,listOf("HIDDEN")),scope)))
        assertEquals("P62:STALE_SCOPE",rejection(engine.select(c,proposal(c),scope.copy(temporal=scope.temporal.copy(historyGenerationUid="G2")))))
        assertEquals("P62:RESPONSE_CORRELATION",rejection(engine.select(NpcDecisionRequest("OTHER",c),proposal(c),scope)))
        assertEquals("P62:CANCELLED",rejection(engine.select(c,proposal(c),scope,AiCancellationSignal{true})))
    }
    @Test fun routineIsExplicitAndNeverFakesAnAiDecision() {
        assertEquals("P62:DECISION_PROVIDER_REQUIRED",rejection(NpcDecisionEngine().select(context(),null,scope)))
        val c=context(listOf(option("ROUTINE",routine=true)))
        assertEquals("ROUTINE",(NpcDecisionEngine().select(c,null,scope) as NpcDecisionResult.Selected).option.uid)
    }
    @Test fun differentValuesAndEmotionsChangeChoiceWithoutRandomReroll() {
        val cause=NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"ACQ1")
        val individual=brain.copy(values=mapOf("COMPASSION" to NpcWeight(10_000)),
            emotions=listOf(NpcEmotion("ANGER",NpcAffect(2000),WorldTimeTick(100),cause)))
        val help=option("HELP").copy(valueAlignment=mapOf("COMPASSION" to NpcAffect(10_000)),resourcePressure=NpcWeight(1000))
        val confront=option("CONFRONT").copy(emotionalAffinity=mapOf("ANGER" to NpcAffect(10_000)))
        fun selected(state:NpcBrainState):NpcDecisionResult.Selected {
            val c=NpcDecisionContextEnvelope(scope,trigger,state,listOf(record),listOf(help,confront),4096)
            return NpcDecisionEngine().select(c,proposal(c,listOf("CONFRONT","HELP")),scope) as NpcDecisionResult.Selected
        }
        assertEquals("HELP",selected(individual).option.uid)
        assertEquals("CONFRONT",selected(individual.copy(values=mapOf("COMPASSION" to NpcWeight(1000)))).option.uid)
        assertTrue(selected(individual).evaluations.flatMap{it.factors}.any{it.uid=="VALUE:COMPASSION"})
    }
    @Test fun activePlayerAndUnseenEvidenceCannotEnterDecision() {
        assertTrue(runCatching{scope.copy(activePlayerUid=actor.uid)}.isFailure)
        assertTrue(runCatching{context(listOf(option("A").copy(supportingRecordUids=setOf("SECRET"))))}.isFailure)
    }
    @Test fun worldTimeDecaysAffectInBothDecisionAndDialogueWithoutMutatingCanonicalBrain() {
        val emotion=NpcEmotion("ANGER",NpcAffect(8000),WorldTimeTick(100),trigger.cause)
        val state=brain.copy(emotions=listOf(emotion))
        val before=NpcBrainCodec.encode(state)
        val choice=option("A").copy(emotionalAffinity=mapOf("ANGER" to NpcAffect(10000)))
        listOf(0L to 8000,1_800_000L to 4000,3_600_000L to 0,Long.MAX_VALUE-100L to 0).forEach{(elapsed,expected)->
            val now=scope.copy(atTime=WorldTimeTick(100+elapsed))
            val c=NpcDecisionContextEnvelope(now,trigger,state,listOf(record),listOf(choice),4096)
            val result=NpcDecisionEngine().select(c,proposal(c,listOf("A")),now) as NpcDecisionResult.Selected
            assertEquals(expected.toLong(),result.evaluations.single().factors.single{it.uid=="EMOTION:ANGER"}.contribution.units)
            val decision=NpcDecisionCodec.encodeRequest("REQ",c)
            val dialogue=NpcDialogueCodec.encode(NpcDialogueRequest("REQ",c,"Jak się czujesz?"))
            if(expected==0){assertTrue(decision.contains("\"emotions\":[]"));assertTrue(dialogue.contains("\"emotions\":[]"))}
            else {assertTrue(decision.contains("\"intensity\":$expected"));assertTrue(dialogue.contains("\"intensity\":$expected"))}
        }
        assertEquals(before,NpcBrainCodec.encode(state))
        assertTrue(runCatching{emotion.intensityAt(WorldTimeTick(99))}.isFailure)
        assertEquals(-4000,emotion.copy(intensity=NpcAffect(-8000)).intensityAt(WorldTimeTick(1_800_100)).basisPoints)
    }
    @Test fun aliasedMutationInvalidatesContextAndAuthorization() {
        val options=mutableListOf(option("A"));val c=context(options)
        options+=option("B")
        assertEquals("P62:CONTEXT_MUTATED",rejection(NpcDecisionEngine().select(c,null,scope)))
        assertTrue(runCatching{NpcDecisionCodec.encodeRequest("REQ",c)}.isFailure)
    }
    @Test fun structuredResponseHasNoStateWriteOrUncorrelatedFields() {
        val c=context()
        val valid="""{"request_uid":"REQ","context_fingerprint":"${c.contextFingerprint}","candidates":[{"option_uid":"A"}]}"""
        assertEquals(listOf(NpcDecisionCandidate("A")),NpcDecisionCodec.decodeProposal(valid,"REQ",c.contextFingerprint).candidates)
        assertTrue(runCatching{NpcDecisionCodec.decodeProposal(valid,"OTHER",c.contextFingerprint)}.isFailure)
        assertTrue(runCatching{NpcDecisionCodec.decodeProposal(valid.replace("\"option_uid\":\"A\"","\"option_uid\":\"A\",\"health\":100"),"REQ",c.contextFingerprint)}.isFailure)
    }
    @Test fun wireBudgetAppliesToTheActualPayload() {
        val c=NpcDecisionContextEnvelope(scope,trigger,brain,listOf(record),listOf(option("A")),1)
        assertTrue(runCatching{NpcDecisionCodec.encodeRequest("REQ",c)}.isFailure)
    }
    @Test fun continuationIsBoundedCorrelatedAndCannotReferenceAnotherGoalOrHiddenCapability() {
        val goal=NpcGoal("G",brain.motivations.first().uid,"Ćwiczyć",NpcWeight(5000),NpcGoalLifecycle.ACTIVE,trigger.cause)
        val state=brain.copy(goals=listOf(goal,goal.copy(uid="OTHER")))
        val options=listOf(option("A").copy(goalUid="G"),option("B").copy(goalUid="G"),option("C").copy(goalUid="OTHER"))
        val c=NpcDecisionContextEnvelope(scope,trigger,state,listOf(record),options,4096)
        fun choose(next:List<String>)=NpcDecisionEngine().select(c,NpcDecisionProposal("REQ",c.contextFingerprint,listOf(NpcDecisionCandidate("A",next))),scope)
        assertEquals("P62:UNAUTHORIZED_CONTINUATION",rejection(choose(listOf("SECRET"))))
        assertEquals("P62:UNAUTHORIZED_CONTINUATION",rejection(choose(listOf("C"))))
        val selected=choose(listOf("B","B")) as NpcDecisionResult.Selected
        assertEquals(listOf("B","B"),selected.authorization.continuationOptionUids)
        assertNotEquals((choose(emptyList()) as NpcDecisionResult.Selected).authorization.decisionUid,selected.authorization.decisionUid)
        assertTrue(runCatching{NpcDecisionCandidate("A",List(4){"B"})}.isFailure)
        val payload="""{"request_uid":"REQ","context_fingerprint":"${c.contextFingerprint}","candidates":[{"option_uid":"A","continuation_option_uids":["B","B"]}]}"""
        assertEquals(listOf("B","B"),NpcDecisionCodec.decodeProposal(payload,"REQ",c.contextFingerprint).candidates.single().continuationOptionUids)
    }

    @Test fun completedPlanHistoryIsNotResentToMobileModel() {
        val goal=NpcGoal("G",brain.motivations.first().uid,"Cel",NpcWeight(100),NpcGoalLifecycle.ACTIVE,trigger.cause)
        val withHistory=brain.copy(goals=listOf(goal),plans=(0 until 32).map{index->NpcPlan("PLAN:$index","G","ACTION:$index",NpcPlanLifecycle.COMPLETED,
            WorldTimeTick(index.toLong()),null,NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION,"COMMAND:$index"))})
        val c=NpcDecisionContextEnvelope(scope,trigger,withHistory,listOf(record),emptyList(),2048)
        val payload=NpcDecisionCodec.encodeRequest("REQ",c)
        assertFalse(payload.contains("COMMAND:31"))
        assertFalse(payload.contains("PLAN:31"))
        assertEquals(32,withHistory.plans.size)
    }

    @Test fun alternativeMustBeExplicitKnownAndSameGoalWithNoSelfLoop() {
        val goal=NpcGoal("G",brain.motivations.first().uid,"Ćwiczyć",NpcWeight(5000),NpcGoalLifecycle.ACTIVE,trigger.cause)
        val state=brain.copy(goals=listOf(goal,goal.copy(uid="OTHER")))
        val c=NpcDecisionContextEnvelope(scope,trigger,state,listOf(record),listOf(option("A").copy(goalUid="G"),option("B").copy(goalUid="G"),option("C").copy(goalUid="OTHER")),4096)
        fun choose(alternative:String?)=NpcDecisionEngine().select(c,NpcDecisionProposal("REQ",c.contextFingerprint,listOf(NpcDecisionCandidate("A",onUnavailableOptionUid=alternative))),scope)
        assertEquals("P62:UNAUTHORIZED_ALTERNATIVE",rejection(choose("SECRET")))
        assertEquals("P62:UNAUTHORIZED_ALTERNATIVE",rejection(choose("C")))
        val selected=choose("B") as NpcDecisionResult.Selected
        assertEquals("B",selected.authorization.onUnavailableOptionUid)
        assertNotEquals((choose(null) as NpcDecisionResult.Selected).authorization.decisionUid,selected.authorization.decisionUid)
        assertTrue(runCatching{NpcDecisionCandidate("A",onUnavailableOptionUid="A")}.isFailure)
        val payload="""{"request_uid":"REQ","context_fingerprint":"${c.contextFingerprint}","candidates":[{"option_uid":"A","on_unavailable_option_uid":"B"}]}"""
        assertEquals("B",NpcDecisionCodec.decodeProposal(payload,"REQ",c.contextFingerprint).candidates.single().onUnavailableOptionUid)
        assertNull(NpcDecisionCodec.decodeProposal(payload.replace("\"on_unavailable_option_uid\":\"B\"","\"on_unavailable_option_uid\":null"),"REQ",c.contextFingerprint).candidates.single().onUnavailableOptionUid)
        assertTrue(runCatching{NpcDecisionCodec.decodeProposal(payload.replace("\"B\"","true"),"REQ",c.contextFingerprint)}.isFailure)
    }

    @Test fun expiredRoleSnapshotCannotAuthorizeCurrentDecision() {
        val old=brain.copy(roleUids=setOf("OLD_GUARD"))
        val current=NpcDecisionContextEnvelope(scope,trigger,old,listOf(record),emptyList(),4096,currentRoleUids=setOf("MEDIC"))
        val wire=NpcDecisionCodec.encodeRequest("REQ",current)
        assertFalse(wire.contains("OLD_GUARD"))
        assertTrue(wire.contains("MEDIC"))
        assertTrue(runCatching{NpcDecisionContextEnvelope(scope,trigger,old,listOf(record),
            listOf(option("A").copy(roleAlignment=mapOf("OLD_GUARD" to NpcAffect(100)))),4096,currentRoleUids=setOf("MEDIC"))}.isFailure)
    }

    @Test fun choiceSetIsBoundedBeforeProjectionAndKeepsTheExactPendingAction() {
        val goal=NpcGoal("G",brain.motivations.first().uid,"Ćwiczyć",NpcWeight(5000),NpcGoalLifecycle.ACTIVE,trigger.cause)
        val pending=brain.copy(goals=listOf(goal),plans=listOf(NpcPlan("PLAN","G","O7",NpcPlanLifecycle.RUNNING,
            WorldTimeTick(0),WorldTimeTick(1000),NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION,"CMD"))))
        val offered=(0..7).map{i->option("O$i").copy(capabilityUid="CAP$i",goalUid="G",parameters=mapOf("detail" to "x".repeat(480)))}
        val reads=object:NpcProjectionReadPort {
            override fun brain(audience:AudienceContext,purpose:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef)=ProtectedReadResult.Allow(pending,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(listOf(record),DisclosureLevel.DISCLOSE_FULL,"HOLDER")
        }
        val result=NpcDecisionContextProjector(reads).project(scope,trigger,pending.knowledgeHolder,ContextRuntimeProfile("mobile",2048,64,64,256,64)){_,_->offered}
        assertTrue(result.toString(),result is NpcContextResult.Ready)
        val ready=result as NpcContextResult.Ready
        assertTrue(ready.context.options.size in 1..7)
        assertTrue(ready.context.options.any{it.uid=="O7"})
        assertEquals(8,offered.size)
        NpcDecisionCodec.encodeRequest("REQ",ready.context)
    }
    @Test fun projectionPassesRealEnvelopesAndKeepsBeliefAsBelief() {
        val reads=object:NpcProjectionReadPort {
            override fun brain(audience:AudienceContext,purpose:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef)=ProtectedReadResult.Allow(brain,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(listOf(record),DisclosureLevel.DISCLOSE_FULL,"HOLDER")
        }
        val projector=NpcDecisionContextProjector(reads)
        val result=projector.project(scope,trigger,brain.knowledgeHolder,ContextRuntimeProfile("mobile",2048,64,64,256,64)){_,_->listOf(option("A"))}
        assertTrue(result.toString(),result is NpcContextResult.Ready)
        val ready=result as NpcContextResult.Ready
        assertTrue(ready.budget.safeForAi)
        assertEquals("N1",ready.workingMemory.scope.principalUid)
        assertEquals(HistoryGenerationUid("G1"),ready.workingMemory.scope.historyGenerationUid)
        assertTrue(ready.workingMemory.records.single{it.canonicalRecordUid==record.uid}.pinned)
        assertEquals(ContextEpistemicState.HOLDER_BELIEF,ready.budget.includedSegments.flatMap{it.records}.single{it.record.recordUid==record.uid}.epistemicState)
        assertTrue(NpcDecisionCodec.encodeRequest("REQ",ready.context).contains("BELIEVED"))
        assertEquals("P62:TRIGGER_NOT_PERCEIVED",(projector.project(scope,trigger.copy(cause=NpcCauseRef(NpcCauseKind.COMMITTED_EVENT,"SECRET")),
            brain.knowledgeHolder,ContextRuntimeProfile("mobile",2048,64,64,256)){_,_->emptyList()} as NpcContextResult.Unavailable).reasonUid)
    }
    @Test fun noTrustedHolderMeansNoPrivateBrainRead() {
        val audience=AudienceContext("C1",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("NPC","N1"))
        val request=VisibilityRequest(audience,PurposeContext("C1",VisibilityPurposeKinds.WORLD_ACTOR_REASONING),
            VisibilitySubjectRef("C1",VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_BRAIN,"N1",holder=brain.knowledgeHolder))
        var called=false
        val deny=ProtectedReadGateway(VisibilityAuthorityService(),TrustedPrincipalResolver{null}).read(request){called=true;brain}
        assertTrue(deny is ProtectedReadResult.NotDisclosed);assertFalse(called)
        val trusted=TrustedPrincipalContext("C1",audience.principal!!,AudienceKinds.WORLD_ACTOR,cognitionHolders=setOf(brain.knowledgeHolder))
        assertTrue(ProtectedReadGateway(VisibilityAuthorityService(),TrustedPrincipalResolver{trusted}).read(request){brain} is ProtectedReadResult.Allow)
        val foreign=request.copy(subject=request.subject.copy(subjectUid="N2",holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,"N2","C1")))
        assertTrue(ProtectedReadGateway(VisibilityAuthorityService(),TrustedPrincipalResolver{trusted}).read(foreign){error("hidden read")} is ProtectedReadResult.Deny)
    }
    @Test fun providerUsesGmPrivacyAndRejectsLateRepliesAfterUndo() {
        var calls=0;var current=scope
        val c=context();val request=NpcDecisionRequest("REQ",c)
        val provider=DeterministicAiProvider(AiCapabilityContract("contract","CLOUD","MODEL",setOf(AiWorkload.NPC_DECISION),
            maximumContextUnits=4096,providerKind=AiProviderKind.CLOUD),intentFunction={error("unused")},proposalFunction={error("unused")},
            narrativeFunction={error("unused")},npcDecisionFunction={calls++;current=scope.copy(temporal=scope.temporal.copy(historyGenerationUid="AFTER_UNDO"));proposal(c)})
        fun router(privacy:AiPrivacyPolicy):AiModelRoutePort {
            val modelRouter=RoleAwareModelRouter(AiProviderRegistry.fromCompositionRoot(listOf(provider)),
                listOf(AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,AiModelSelection("CLOUD","MODEL")),AiRoleAssignment(AiRole.DIRECTOR_SCENARIST)),
                privacy,AiAvailabilityPort{AiProviderAvailability(AiModelSelection("CLOUD","MODEL"),AiAvailabilityState.READY,"READY")})
            return AiModelRoutePort{role,workload,units->modelRouter.route(role,workload,units)}
        }
        val denied=NpcDecisionApplication(router(AiPrivacyPolicy(cloudAllowedForPlayerText=false)),{current}).decide(request)
        assertTrue(rejection(denied).startsWith("P62:PROVIDER_UNAVAILABLE"));assertEquals(0,calls)
        assertEquals("P62:STALE_SCOPE",rejection(NpcDecisionApplication(router(AiPrivacyPolicy()),{current}).decide(request)))
        assertEquals(1,calls)
    }
}
