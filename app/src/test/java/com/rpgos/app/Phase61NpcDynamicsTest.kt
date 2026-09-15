package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase61NpcDynamicsTest {
    private val actor=DomainRef("NPC","N1")
    private val base=NpcBrainOwner.initialize("C1",actor,"seed")
    private val subject=DomainRef("NPC","N2")
    private val record=NpcKnownRecord("R1",KnowledgeEpistemicState.BELIEVED,"N2 zaoferował pomoc.","A1",1,setOf(subject),2)
    private fun context(brain:NpcBrainState=base,at:Long=1000,records:List<NpcKnownRecord> = listOf(record),options:List<NpcActionOption> = emptyList()):NpcDecisionContextEnvelope {
        val scope=NpcDecisionScope(TemporalScope("C1","G1",10,"digest"),actor,brain.revision,WorldTimeTick(at),0,"P1")
        return NpcDecisionContextEnvelope(scope,NpcTrigger("T",NpcTriggerKind.KNOWLEDGE_CHANGED,WorldTimeTick(at),
            NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,records.first().acquisitionUid)),brain,records,options,8192)
    }
    @Test fun appraisalIsBoundedPersonalAndDoesNotPromoteBeliefToFact() {
        val change=NpcBrainDynamics.appraise(context(),listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.GOODWILL,"R1",subject)))!!
        val after=NpcBrainCodec.decode(change.stateCanonical)
        assertTrue(after.dispositions.single().trust.basisPoints in 1..1000)
        assertEquals(base.personality,after.personality)
        assertEquals(base.motivations,after.motivations)
        assertEquals(base.goals,after.goals)
        assertEquals(KnowledgeEpistemicState.BELIEVED,record.epistemicState)
        assertEquals(2,after.lastAppraisedAcquisitionOrder)
    }
    @Test fun executionGoalUsesOnlyAuthorizedOptionAndCoreDescriptionNotTheModelsWorldSuccessClaim() {
        val intention=NpcGoal("ORIGINAL",base.motivations.first().uid,"Ćwiczyć",NpcWeight(4000),NpcGoalLifecycle.ACTIVE,NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A1"))
        val brain=base.copy(goals=listOf(intention))
        val option=NpcActionOption("TRAIN_ONCE","TRAIN",actor,AcceptedActionTiming(ActionDuration(1000),NpcActivityMechanics.TIMING_RULE,1),
            intention.uid,emptyList(),setOf(record.uid),mechanicsOwnerUid=NpcActivityMechanics.OWNER,mechanicalEffectKindUid="INTERACTION")
        val c=context(brain,options=listOf(option))
        val proposal=NpcGoalCandidate("FINITE",intention.motivationUid,"Wygrać wszystkie wojny",setOf("R1"),executionOptionUid=option.uid)
        val change=NpcBrainDynamics.considerGoals(c,listOf(proposal))!!
        val after=NpcBrainCodec.decode(change.stateCanonical);val goal=after.goals.single{it.uid=="FINITE"}
        assertNotEquals(proposal.objective,goal.objective)
        assertEquals("TRAIN",goal.executionObjective!!.capabilityUid)
        assertEquals(NpcExecutionGoals.description(goal.executionObjective),goal.objective)
        assertEquals(NpcGoalLifecycle.ACTIVE,goal.lifecycle)
        assertEquals(after,NpcBrainCodec.decode(NpcBrainCodec.encode(after)))
        assertTrue(runCatching{NpcBrainDynamics.considerGoals(c,listOf(proposal.copy(executionOptionUid="HIDDEN")))}.isFailure)
        assertTrue(runCatching{goal.copy(objective="Wygrano wojnę")}.isFailure)
        assertTrue(runCatching{proposal.copy(operation=NpcGoalOperation.ABANDON)}.isFailure)
        val payload="""{"request_uid":"REQ","context_fingerprint":"${c.contextFingerprint}","candidates":[],"goals":[{"uid":"FINITE","motivation_uid":"${intention.motivationUid}","objective":"Wygrać wszystkie wojny","supporting_record_uids":["R1"],"execution_option_uid":"TRAIN_ONCE"}]}"""
        assertEquals(proposal,NpcDecisionCodec.decodeProposal(payload,"REQ",c.contextFingerprint).goals.single())
        assertFalse(NpcBrainCodec.encode(base).contains("execution_objective"))
    }
    @Test fun ownNewPerceivedThreatCanCreatePersistentFallibleFearWithoutWorldFacts() {
        val threat=NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,record.uid,subject)
        val c=context()
        val changes=NpcBrainDynamics.proposedChanges(c,listOf(threat),emptyList())
        assertEquals(2,changes.size)
        val after=applyNpcBrainOverlay(base,c.scope.temporal,changes)
        val fear=after.motivations.single{it.kind==NpcMotivationKind.FEAR}
        assertEquals(subject,fear.subject);assertEquals(NpcWeight(375),fear.strength)
        assertEquals(base.personality,after.personality)
        assertEquals(KnowledgeEpistemicState.BELIEVED,record.epistemicState)
        assertTrue(NpcBrainDynamics.proposedChanges(context(after),listOf(threat),emptyList()).isEmpty())
        val newer=record.copy(uid="SAFE",acquisitionUid="A2",sourceCommittedOrder=3)
        val safetyContext=context(after,at=2000,records=listOf(newer))
        val safe=applyNpcBrainOverlay(after,safetyContext.scope.temporal,NpcBrainDynamics.proposedChanges(safetyContext,
            listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.SAFETY,"SAFE",subject)),emptyList()))
        assertEquals(NpcWeight(0),safe.motivations.single{it.uid==fear.uid}.strength)
        assertEquals(fear.uid,NpcBrainCodec.decode(NpcBrainCodec.encode(safe)).motivations.single{it.kind==NpcMotivationKind.FEAR}.uid)
    }
    @Test fun historicalOrAmbivalentEvidenceDoesNotInventFearOrReassurance() {
        val threat=NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,"R1",subject)
        val safety=NpcAppraisalCandidate(NpcAppraisalMeaning.SAFETY,"R1",subject)
        assertTrue(NpcAffectiveMotivations.derive(context(),listOf(threat,safety),-1).causes.isEmpty())
        assertTrue(NpcAffectiveMotivations.derive(context(),listOf(safety),-1).causes.isEmpty())
        assertTrue(NpcAffectiveMotivations.derive(context(records=listOf(record.copy(memoryKind=NpcMemoryRecordKind.HISTORICAL_ACQUISITION))),listOf(threat),-1).causes.isEmpty())
        assertTrue(NpcAffectiveMotivations.derive(context(),listOf(threat.copy(subject=DomainRef("NPC","SECRET"))),-1).causes.isEmpty())
    }
    @Test fun oldAcquisitionCannotPumpEmotionAfterNewerEvidenceOrRestart() {
        fun apply(brain:NpcBrainState,r:NpcKnownRecord)=NpcBrainCodec.decode(NpcBrainDynamics.appraise(context(brain,records=listOf(r)),
            listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,r.uid)))!!.stateCanonical)
        val first=apply(base,record)
        assertNull(NpcBrainDynamics.appraise(context(first),listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,"R1"))))
        val next=apply(first,record.copy(uid="R2",acquisitionUid="A2",sourceCommittedOrder=3))
        val reopened=NpcBrainCodec.decode(NpcBrainCodec.encode(next))
        assertNull(NpcBrainDynamics.appraise(context(reopened),listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,"R1"))))
    }
    @Test fun hiddenEvidenceAndUnperceivedDispositionSubjectsAreRejected() {
        assertTrue(runCatching{NpcBrainDynamics.appraise(context(),listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,"HIDDEN")))}.isFailure)
        assertTrue(runCatching{NpcBrainDynamics.appraise(context(),listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.GOODWILL,"R1",DomainRef("NPC","SECRET"))))}.isFailure)
    }
    @Test fun goalsUseExistingMotivationsAndOwnerPriorityAndCannotBeRewritten() {
        val motivation=base.motivations.first()
        val candidate=NpcGoalCandidate("G",motivation.uid,"Sprawdzić propozycję pomocy",setOf(record.uid))
        val next=NpcBrainCodec.decode(NpcBrainDynamics.considerGoals(context(),listOf(candidate))!!.stateCanonical)
        assertEquals(motivation.strength,next.goals.single().priority)
        assertEquals(NpcGoalLifecycle.ACTIVE,next.goals.single().lifecycle)
        assertNull(NpcBrainDynamics.considerGoals(context(next),listOf(candidate)))
        assertTrue(runCatching{NpcBrainDynamics.considerGoals(context(next),listOf(candidate.copy(objective="Inny cel")))}.isFailure)
        assertTrue(runCatching{NpcBrainDynamics.considerGoals(context(),listOf(candidate.copy(motivationUid="INVENTED")))}.isFailure)
    }
    @Test fun sequentialRevisionsRequireMatchingFingerprintAndCannotCompeteForSameRevision() {
        val changes=NpcBrainDynamics.proposedChanges(context(),listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,record.uid)),
            listOf(NpcGoalCandidate("G",base.motivations.first().uid,"Sprawdzić sytuację",setOf(record.uid))))
        assertTrue(validNpcBrainChains(changes))
        assertTrue(npcBrainChangeCodec().conflictKeys(changes.first()).intersect(npcBrainChangeCodec().conflictKeys(changes.last())).isEmpty())
        assertFalse(validNpcBrainChains(changes.reversed()))
        assertFalse(validNpcBrainChains(listOf(changes.first(),changes.last().copy(beforeFingerprint="0".repeat(64)))))
        assertFalse(validNpcBrainChains(listOf(changes.first(),changes.first())))
    }
    @Test fun plansNeedCoreAuthorizationAndElapsedTimeButCompletionDoesNotProveGoalSuccess() {
        val goal=NpcGoalCandidate("G",base.motivations.first().uid,"Wykonać zadanie",setOf(record.uid))
        val brain=NpcBrainCodec.decode(NpcBrainDynamics.considerGoals(context(),listOf(goal))!!.stateCanonical)
        val option=NpcActionOption("READ","READ",null,AcceptedActionTiming(ActionDuration(1000),"RULE",1),"G",emptyList(),setOf(record.uid),routine=true)
        val c=context(brain,1000,options=listOf(option))
        val chosen=NpcDecisionEngine().select(c,null,c.scope) as NpcDecisionResult.Selected
        val running=NpcBrainCodec.decode(NpcBrainDynamics.beginPlan(c,chosen,"CMD").stateCanonical)
        val plan=running.plans.single()
        assertEquals(WorldTimeTick(2000),plan.nextEvaluationAt)
        assertTrue(runCatching{NpcBrainDynamics.finishPlan(context(running,1999),plan.uid,"CMD2",true)}.isFailure)
        val finished=NpcBrainCodec.decode(NpcBrainDynamics.finishPlan(context(running,2000),plan.uid,"CMD2",true).stateCanonical)
        assertEquals(NpcPlanLifecycle.COMPLETED,finished.plans.single().lifecycle)
        assertEquals(NpcGoalLifecycle.ACTIVE,finished.goals.single().lifecycle)
        assertTrue(runCatching{NpcBrainDynamics.beginPlan(c,chosen.copy(option=option.copy(capabilityUid="ATTACK")),"CMD")}.isFailure)
    }

    @Test fun goalsCanBeSuspendedResumedAndAbandonedOnlyAfterNewEvidence() {
        val candidate=NpcGoalCandidate("G",base.motivations.first().uid,"Sprawdzić sytuację",setOf("R1"))
        var brain=NpcBrainCodec.decode(NpcBrainDynamics.considerGoals(context(),listOf(candidate))!!.stateCanonical)
        val newer=record.copy(uid="R2",acquisitionUid="A2",sourceCommittedOrder=3)
        fun update(operation:NpcGoalOperation,r:NpcKnownRecord,expected:NpcGoalLifecycle) {
            val change=NpcBrainDynamics.considerGoals(context(brain,records=listOf(record,newer,r).distinctBy{it.uid}),
                listOf(candidate.copy(operation=operation,supportingRecordUids=setOf(r.uid))))!!
            brain=NpcBrainCodec.decode(change.stateCanonical)
            assertEquals(expected,brain.goals.single().lifecycle)
        }
        assertTrue(runCatching{NpcBrainDynamics.considerGoals(context(brain),listOf(candidate.copy(operation=NpcGoalOperation.SUSPEND)))}.isFailure)
        update(NpcGoalOperation.SUSPEND,newer,NpcGoalLifecycle.SUSPENDED)
        assertTrue(runCatching{NpcBrainDynamics.considerGoals(context(brain,records=listOf(record,newer)),
            listOf(candidate.copy(operation=NpcGoalOperation.RESUME,supportingRecordUids=setOf(newer.uid))))}.isFailure)
        update(NpcGoalOperation.RESUME,record.copy(uid="R3",acquisitionUid="A3",sourceCommittedOrder=4),NpcGoalLifecycle.ACTIVE)
        update(NpcGoalOperation.ABANDON,record.copy(uid="R4",acquisitionUid="A4",sourceCommittedOrder=5),NpcGoalLifecycle.ABANDONED)
        assertTrue(runCatching{NpcBrainDynamics.considerGoals(context(brain,records=listOf(record.copy(uid="R5",acquisitionUid="A5",sourceCommittedOrder=6))),
            listOf(candidate.copy(operation=NpcGoalOperation.RESUME,supportingRecordUids=setOf("R5"))))}.isFailure)
    }

    @Test fun personalityAdaptsSlowlyAtNewEvidenceThresholdAndIsNotRerolled() {
        val old=NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"OLD")
        val brain=base.copy(lastAppraisedAcquisitionOrder=1,emotions=listOf(NpcEmotion("THREAT",NpcAffect(7400),WorldTimeTick(1000),old)))
        val changes=NpcBrainDynamics.proposedChanges(context(brain),listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,"R1")),emptyList())
        assertEquals(2,changes.size)
        val adapted=NpcBrainCodec.decode(changes.last().stateCanonical)
        assertEquals(NpcBrainRules.ADAPTATION.uid,changes.last().ruleUid)
        assertEquals(brain.personality.getValue("CAUTION").basisPoints+25,adapted.personality.getValue("CAUTION").basisPoints)
        assertEquals(brain.seedFingerprint,adapted.seedFingerprint)
        assertTrue(NpcBrainDynamics.proposedChanges(context(adapted),listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.THREAT,"R1")),emptyList()).isEmpty())
    }

    @Test fun intrinsicIntentDoesNotRequireOrCreateFictionalExternalKnowledge() {
        val ordinary=context()
        val candidate=NpcGoalCandidate("INTRINSIC",base.motivations.first().uid,"Chcę zaspokoić własną potrzebę",emptySet())
        assertTrue(runCatching{NpcBrainDynamics.considerGoals(ordinary,listOf(candidate))}.isFailure)
        val c=NpcDecisionContextEnvelope(ordinary.scope,NpcTrigger("SELF",NpcTriggerKind.SELF_REFLECTION,ordinary.scope.atTime,
            NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,candidate.motivationUid)),base,emptyList(),emptyList(),8192)
        val changed=NpcBrainDynamics.considerGoals(c,listOf(candidate))!!
        val state=NpcBrainCodec.decode(changed.stateCanonical)
        assertEquals(NpcCauseKind.INTRINSIC_MOTIVATION,state.goals.single().cause.kind)
        assertTrue(state.emotions.isEmpty());assertTrue(state.dispositions.isEmpty())
        assertTrue(c.records.isEmpty())
        assertTrue(runCatching{NpcBrainDynamics.appraise(c,listOf(NpcAppraisalCandidate(NpcAppraisalMeaning.GOODWILL,"INVENTED")))}.isFailure)
        val actorState=MechanicalActorView("C1",actor,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,emptyMap(),emptyList(),
            setOf("WAIT","ATTACK"),generationProvenanceUid="GEN")
        val options=NpcMechanicalAffordances(CombatAbilityContractPort.UNIVERSAL_FALLBACK).options(state,emptyList(),actorState)
        assertEquals(setOf("WAIT","REST"),options.map{it.capabilityUid}.toSet())
        assertTrue(options.all{it.target==actor})
    }
}
