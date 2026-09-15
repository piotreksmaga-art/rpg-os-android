package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcCognitionProcessTest {
    private val scope=TemporalScope("C1","G1",7,"digest")
    private val actor=DomainRef("NPC","N1")
    private val stimulus=NpcCognitionStimulus(actor,1,"ACQ",5)
    private val brain=NpcBrainOwner.initialize("C1",actor,"seed")
    private val foreground=object:WorldProcessOwnerPort {
        override val ownerUid=PHASE60_FOREGROUND_OWNER
        override fun evaluate(input:TemporalOwnerInput)=TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid,1,"READY"))
    }
    private fun context():NpcDecisionContextEnvelope {
        val c=NpcDecisionScope(scope,actor,1,WorldTimeTick(0),0,"P1")
        return NpcDecisionContextEnvelope(c,NpcTrigger("T",NpcTriggerKind.KNOWLEDGE_CHANGED,WorldTimeTick(0),NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"ACQ")),
            brain,listOf(NpcKnownRecord("R",KnowledgeEpistemicState.BELIEVED,"Droga może być niebezpieczna.","ACQ",1,sourceCommittedOrder=5)),emptyList(),8192)
    }
    private fun run(extension:TemporalProcessExtension,previous:TemporalOwnerState?=null):TemporalExecutionResult {
        val processor=Phase60TimeProcessor(listOf(foreground)+extension.owners.map{it.owner})
        val start=processor.begin(scope,"CMD",WorldTimeTick(0),listOf(TimedActionNode("READ",PHASE60_FOREGROUND_OWNER,
            AcceptedActionTiming(ActionDuration(1000),"RULE",1))),ownerStates=listOfNotNull(previous))
        val initial=processor.advance(start,scope)
        if(initial.reason!=TemporalStopReason.OWNER_EVALUATION_REQUIRED)return initial
        val pending=initial.pendingEvaluation!!
        val answer=extension.evaluation!!.evaluate(pending){false} as TemporalEvaluationResponse.Accepted
        return processor.advance(initial.checkpoint,scope,evaluations=mapOf(pending.fingerprint to answer.result))
    }
    @Test fun providerCognitionProducesVersionedBrainCandidatesButNoPhysicalEffects() {
        var calls=0
        val c=context()
        val provider=DeterministicAiProvider(AiCapabilityContract("TEST","TEST","TEST",setOf(AiWorkload.NPC_DECISION),maximumContextUnits=8192),
            intentFunction={error("unused")},proposalFunction={error("unused")},narrativeFunction={error("unused")},
            npcDecisionFunction={request->calls++;NpcDecisionProposal(request.requestUid,request.context.contextFingerprint,emptyList(),
                listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,"R")),
                listOf(NpcGoalCandidate("G",brain.motivations.first().uid,"Sprawdzić bezpieczeństwo drogi",setOf("R"))))})
        val application=NpcDecisionApplication(AiModelRoutePort { _,_,_->AiRouteResult.Selected(provider,true,"TEST") },{c.scope})
        val extension=NpcCognitionProcess(scope,WorldTimeTick(0),listOf(stimulus)){_,_->application.decide(NpcDecisionRequest("REQ",c))}.extension()
        val result=run(extension)
        assertEquals(TemporalStopReason.COMPLETED,result.reason)
        assertEquals(1,calls)
        val changes=result.checkpoint.candidateChanges.filterIsInstance<NpcBrainChange>()
        assertEquals(2,changes.size);assertEquals(listOf(1L,2L),changes.map{it.expectedVersion})
        assertEquals(NpcGoalLifecycle.ACTIVE,NpcBrainCodec.decode(changes.last().stateCanonical).goals.single().lifecycle)
        assertTrue(result.checkpoint.candidateChanges.all{it is NpcBrainChange})
        val previous=result.checkpoint.ownerStates.getValue(NpcCognitionProcess.OWNER)
        assertEquals(listOf(NpcCognitionCursor(actor,5)),NpcCognitionProcess.decode(previous))
        assertEquals(TemporalStopReason.COMPLETED,run(extension,previous).reason)
        assertEquals(1,calls)
    }
    @Test fun unavailableCognitionDoesNotFabricateActionOrMarkKnowledgeConsumed() {
        val extension=NpcCognitionProcess(scope,WorldTimeTick(0),listOf(stimulus)){_,_->NpcDecisionResult.Unavailable("P62:PROVIDER_UNAVAILABLE")}.extension()
        val result=run(extension)
        assertEquals(TemporalStopReason.COMPLETED,result.reason)
        assertTrue(result.checkpoint.candidateChanges.isEmpty())
        val state=result.checkpoint.ownerStates.getValue(NpcCognitionProcess.OWNER)
        assertTrue(NpcCognitionProcess.decode(state).isEmpty())
        assertTrue(state.canonicalValue.contains("P62:PROVIDER_UNAVAILABLE"))
    }
    @Test fun batchIsBoundedAndSuccessfulCursorSurvivesSerialization() {
        var calls=0
        val stimuli=(1..8).map{stimulus.copy(actor=DomainRef("NPC","N$it"))}
        val extension=NpcCognitionProcess(scope,WorldTimeTick(0),stimuli){_,_->calls++;NpcDecisionResult.Reflected(emptyList())}.extension()
        val result=run(extension)
        assertEquals(4,calls)
        val state=result.checkpoint.ownerStates.getValue(NpcCognitionProcess.OWNER)
        assertEquals(state.canonicalValue,NpcCognitionProcess.encode(NpcCognitionProcess.decode(state),
            (1..4).associate{"N$it" to "P62:COGNITION_EVALUATED"}))
        assertEquals(4,NpcCognitionProcess.decode(state).size)
    }
    @Test fun malformedFutureAndCrossCampaignCognitionIsRejected() {
        assertTrue(runCatching{NpcCognitionProcess(scope,WorldTimeTick(0),listOf(stimulus.copy(order=8))){_,_->NpcDecisionResult.Reflected(emptyList())}}.isFailure)
        val extension=NpcCognitionProcess(scope,WorldTimeTick(0),listOf(stimulus)){_,_->NpcDecisionResult.Reflected(emptyList())}.extension()
        val owner=extension.owners.single().owner
        assertTrue(owner.evaluate(TemporalOwnerInput(scope.copy(campaignUid="OTHER"),WorldTimeTick(0),WorldTimeTick(0),emptyList(),emptyList(),null)) is TemporalOwnerResult.Unsupported)
    }
    @Test fun boundedSchedulingHistoryDoesNotPermanentlyExcludeThe129thNpc() {
        val previous=TemporalOwnerState(NpcCognitionProcess.OWNER,1,NpcCognitionProcess.encode((0 until 128).map{
            NpcCognitionCursor(DomainRef("NPC","OLD:${it.toString().padStart(3,'0')}"),0)}))
        var calls=0
        val fresh=stimulus.copy(actor=DomainRef("NPC","NEW"))
        val extension=NpcCognitionProcess(scope,WorldTimeTick(0),listOf(fresh)){_,_->calls++;NpcDecisionResult.Reflected(emptyList())}.extension()
        val result=run(extension,previous)
        assertEquals(1,calls)
        val rows=NpcCognitionProcess.decode(result.checkpoint.ownerStates.getValue(NpcCognitionProcess.OWNER))
        assertEquals(128,rows.size)
        assertTrue(rows.any{it.actor==fresh.actor})
        assertFalse(rows.any{it.actor.uid=="OLD:000"})
        assertTrue(result.checkpoint.candidateChanges.isEmpty()) // No lost or invented brain/knowledge.
    }
}
