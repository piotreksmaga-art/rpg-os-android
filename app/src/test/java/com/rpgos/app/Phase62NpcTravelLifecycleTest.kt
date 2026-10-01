package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

/** Actual decision, mechanics, Phase50 materializer and persisted Phase60 pending-action codec.
 * Only world reads and the AI decision are controlled; no movement outcome is fabricated. */
class Phase62NpcTravelLifecycleTest {
    companion object {
        internal fun resolvedTravelEffects(label:String)=Phase62NpcTravelLifecycleTest().let { fixture ->
            fixture.scope=fixture.scope.copy(authoritativeFingerprint=phase60Hash(label))
            fixture.resolve().effects
        }
    }
    private val npc=DomainRef("NPC","N")
    private val origin=DomainRef("PLACE","A")
    private val destination=DomainRef("PLACE","B")
    private var scope=TemporalScope("C1","H",1,"STATE")
    private var route=NpcTravelRouteContract("C1","ROAD",1,origin,destination,ActionDuration(120000),"WORLD:ROAD",
        capabilityUid="WORLD:WALK",resourceCosts=mapOf("STAMINA" to 3L,"SUPPLIES" to 2L))
    private var roadOpen=true
    private var knowsDestination=true
    private var body=MechanicalActorView("C1",npc,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,
        emptyMap(),listOf(MechanicalResource("STAMINA",98,100),MechanicalResource("SUPPLIES",10,10),MechanicalResource("HEALTH",80,100)),
        setOf("WORLD:WALK"),locationRef=origin,generationProvenanceUid="GEN")
    private var brain=NpcBrainOwner.initialize("C1",npc,"seed").let{it.copy(goals=listOf(NpcGoal("G",it.motivations.first().uid,
        "Dotrzeć do celu",NpcWeight(5000),NpcGoalLifecycle.ACTIVE,NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"ACQ"))))}
    private val record=NpcKnownRecord("R",KnowledgeEpistemicState.KNOWN,"Znam miejsce docelowe.","ACQ",1,setOf(destination))
    private val routes=NpcTravelRoutePort { campaign,actor,at ->
        if(roadOpen && actor==npc && route.campaignUid==campaign && route.origin==at)listOf(route) else emptyList()
    }
    private var modelCalls=0
    private val provider=DeterministicAiProvider(AiCapabilityContract("TEST","TEST","TEST",setOf(AiWorkload.NPC_DECISION),maximumContextUnits=8192),
        intentFunction={error("unused")},proposalFunction={error("unused")},narrativeFunction={error("unused")},
        npcDecisionFunction={r->modelCalls++;NpcDecisionProposal(r.requestUid,r.context.contextFingerprint,listOf(NpcDecisionCandidate(r.context.options.single().uid)))})
    private val generic=MechanicsRuleResolver { _,_->error("Travel must never reach generic movement fallback") }
    private fun mechanics()=NpcMechanicalActionApplication(generic,{scope},routes,NpcTravelActorReadPort { requested,actor ->
        body.takeIf{requested==scope && actor==npc}
    })
    private fun input(at:Long,changes:List<PlayerDomainChangePayload> = emptyList(),effects:List<VerifiedMechanicsCommandEffect> = emptyList())=
        TemporalOwnerInput(scope,WorldTimeTick(0),WorldTimeTick(at),emptyList(),emptyList(),null,stagedChanges=changes,stagedEffects=effects)
    private fun project(input:TemporalOwnerInput=input(0),pending:NpcPendingAction?=null):NpcContextResult.Ready {
        val state=applyNpcBrainOverlay(brain,scope,input.stagedChanges.filterIsInstance<NpcBrainChange>())
        val reads=object:NpcProjectionReadPort {
            override fun brain(a:AudienceContext,p:PurposeContext,actor:DomainRef,h:KnowledgeHolderRef)=ProtectedReadResult.Allow(state,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(a:AudienceContext,p:PurposeContext,h:KnowledgeHolderRef,order:Long,limit:Int)=
                ProtectedReadResult.Allow(if(knowsDestination)listOf(record) else emptyList(),DisclosureLevel.DISCLOSE_FULL,"ACQUIRED")
        }
        val cause=pending?.let{p->state.plans.single{it.uid==p.planUid}.cause}?:NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"ACQ")
        val trigger=NpcTrigger("T",if(pending==null)NpcTriggerKind.KNOWLEDGE_CHANGED else NpcTriggerKind.PLAN_BOUNDARY,input.through,cause)
        return NpcDecisionContextProjector(reads).project(NpcDecisionScope(scope,npc,state.revision,input.through,0,"P"),trigger,state.knowledgeHolder,
            ContextRuntimeProfile("TEST",8192,64,64,512)){b,records->NpcTravelAffordances.options(b,records,body,routes)} as NpcContextResult.Ready
    }
    private fun choose(p:NpcContextResult.Ready)=NpcDecisionEngine().select(p.context,NpcDecisionProposal("D",p.context.contextFingerprint,
        listOf(NpcDecisionCandidate(p.context.options.single().uid))),p.context.scope) as NpcDecisionResult.Selected
    private fun resolve():NpcMechanicalResult.Resolved { val p=project();return mechanics().resolve(p,choose(p)) as NpcMechanicalResult.Resolved }
    private fun app()=NpcTimedActionApplication("CMD",NpcPhysicalContextPort{_,i,p->project(i,p)},
        AiModelRoutePort{_,_,_->AiRouteResult.Selected(provider,true,"TEST")},{scope},mechanics())
    private fun begin()=app().prepare(npc,input(0)){false} as NpcActionPreparation.Started
    private fun changes(effects:List<VerifiedMechanicsCommandEffect>)=effects.flatMap {
        (MechanicalEffectMaterializer.materialize(it) as MechanicalEffectMaterializationResult.Materialized).changes.map{c->c.payload}
    }

    @Test fun domainPreflightComputesTwoIndependentCostsAndOneArrivalWithoutChangingState() {
        val result=resolve()
        assertEquals(setOf(ResourceChange(npc,"STAMINA",ExactLongDelta.of(-3)),ResourceChange(npc,"SUPPLIES",ExactLongDelta.of(-2))),
            result.changes.filterIsInstance<ResourceChange>().toSet())
        assertEquals(listOf(SpatialChange(npc,0,0,destination)),result.changes.filterIsInstance<SpatialChange>())
        assertEquals(3,result.effects.size)
        assertEquals(3,result.effects.map{it.effectUid}.distinct().size)
        assertEquals(NpcTravelMechanics.TIMING_RULE,result.timing.ruleUid)
        assertTrue(result.effects.all{Phase60DomainTiming.effectOffset(it,result.timing.duration)==120000L})
        assertEquals(origin,body.locationRef);assertEquals(98L,body.resources.first().current)
    }

    @Test fun pendingActionRoundTripContainsNoFutureEffectsAndEarlyCompletionIsRejected() {
        val started=begin()
        val encoded=NpcActionProcess.encode(listOf(started.pending))
        val pending=NpcActionProcess.decode(TemporalOwnerState(NpcActionProcess.OWNER,1,encoded)).single()
        assertEquals(started.pending,pending)
        assertFalse(encoded.contains("RESOURCE_DELTA"));assertFalse(encoded.contains("LOCATION_TRANSITION"))
        assertEquals(origin,body.locationRef);assertEquals(98L,body.resources.first().current)
        val early=app().complete(pending,input(119999,started.changes)){false}
        assertEquals("P62:ACTION_NOT_DUE",(early as NpcActionCompletion.Unavailable).reasonUid)
        assertEquals(1,modelCalls)
    }

    @Test fun serializedPlanResumesWithFreshMechanicsAndNoSecondModelCall() {
        val started=begin()
        brain=NpcBrainCodec.decode(NpcBrainCodec.encode(applyNpcBrainOverlay(brain,scope,started.changes)))
        val pending=NpcActionProcess.decode(TemporalOwnerState(NpcActionProcess.OWNER,1,NpcActionProcess.encode(listOf(started.pending)))).single()
        body=body.copy(stateVersion=2,resources=body.resources.map{if(it.resourceUid=="STAMINA")it.copy(current=50) else it})
        assertEquals(origin,body.locationRef)
        val done=app().complete(pending,input(120000)){false} as NpcActionCompletion.Finished
        assertFalse(done.interrupted)
        assertTrue(LocationReachedCriterion(npc,destination).provenBy(changes(done.effects)))
        assertEquals(NpcPlanLifecycle.COMPLETED,NpcBrainCodec.decode(done.brainChange.stateCanonical).plans.single().lifecycle)
        assertEquals(NpcGoalLifecycle.ACTIVE,NpcBrainCodec.decode(done.brainChange.stateCanonical).goals.single().lifecycle)
        assertEquals(1,modelCalls)
        // Even a finished candidate does not mutate persistence; only the common commit can do so.
        assertEquals(origin,body.locationRef);assertEquals(50L,body.resources.first().current)
    }

    @Test fun roadWithdrawalInterruptsWithoutSpatialChangeOrCharge() {
        val started=begin();roadOpen=false
        val done=app().complete(started.pending,input(120000,started.changes)){false} as NpcActionCompletion.Finished
        assertTrue(done.interrupted);assertTrue(done.effects.isEmpty());assertEquals(origin,body.locationRef)
        assertEquals(NpcPlanLifecycle.INTERRUPTED,NpcBrainCodec.decode(done.brainChange.stateCanonical).plans.single().lifecycle)
        assertEquals(1,modelCalls)
    }

    @Test fun routeChangeWithoutVersionBumpStillInvalidatesSavedIntention() {
        val started=begin();route=route.copy(resourceCosts=mapOf("STAMINA" to 4L,"SUPPLIES" to 2L))
        val done=app().complete(started.pending,input(120000,started.changes)){false} as NpcActionCompletion.Finished
        assertTrue(done.interrupted);assertTrue(done.effects.isEmpty());assertEquals(1,modelCalls)
    }

    @Test fun incapacityOrUnknownDestinationCannotCompleteTravel() {
        val started=begin();knowsDestination=false
        val hidden=app().complete(started.pending,input(120000,started.changes)){false} as NpcActionCompletion.Finished
        assertTrue(hidden.interrupted);assertTrue(hidden.effects.isEmpty())
        knowsDestination=true;body=body.copy(conditions=listOf(MechanicalCondition("UNCONSCIOUS",1)))
        val incapable=app().complete(started.pending,input(120000,started.changes)){false} as NpcActionCompletion.Finished
        assertTrue(incapable.interrupted);assertTrue(incapable.effects.isEmpty())
    }

    @Test fun changedOriginAndExhaustedResourcesInvalidateCompletion() {
        val started=begin();body=body.copy(locationRef=DomainRef("PLACE","OTHER"))
        val moved=app().complete(started.pending,input(120000,started.changes)){false} as NpcActionCompletion.Finished
        assertTrue(moved.interrupted);assertTrue(moved.effects.isEmpty())
        body=body.copy(locationRef=origin,resources=body.resources.map{if(it.resourceUid=="STAMINA")it.copy(current=0) else it})
        val depleted=app().complete(started.pending,input(120000,started.changes)){false} as NpcActionCompletion.Finished
        assertTrue(depleted.interrupted);assertTrue(depleted.effects.isEmpty())
    }

    @Test fun staleRouteProjectionAndMissingAuthorityCannotUseGenericFallback() {
        val p=project();val selected=choose(p)
        roadOpen=false
        assertTrue(mechanics().resolve(p,selected) is NpcMechanicalResult.Unavailable)
        roadOpen=true;route=route.copy(duration=ActionDuration(240000))
        assertTrue(mechanics().resolve(p,selected) is NpcMechanicalResult.Unavailable)
        assertEquals("P62:TRAVEL_ACTOR_UNAVAILABLE",(NpcMechanicalActionApplication(generic,{scope}).resolve(p,selected) as NpcMechanicalResult.Unavailable).reasonUid)
    }

    @Test fun stagedResourceSpendingAndMovementAreRecheckedAtResolution() {
        val p=project();val selected=choose(p)
        val spent=VerifiedMechanicsEffect("SPENT","NODE","OWNER","RESOURCE_DELTA",
            mapOf("target_kind_uid" to npc.kindUid,"target_uid" to npc.uid,"resource_uid" to "STAMINA","magnitude" to "-97"),"PROOF","INPUT","OUTPUT")
        assertTrue(mechanics().resolve(p,selected,listOf(spent)) is NpcMechanicalResult.Unavailable)
        val moved=spent.copy(effectUid="MOVED",effectKindUid="LOCATION_TRANSITION",canonicalPayload=mapOf(
            "target_kind_uid" to npc.kindUid,"target_uid" to npc.uid,"magnitude" to "1","destination_kind_uid" to "PLACE","destination_uid" to "OTHER"))
        assertTrue(mechanics().resolve(p,selected,listOf(moved)) is NpcMechanicalResult.Unavailable)
    }

    @Test fun cancellationAndAbandonedHistoryCannotProduceTravelEffects() {
        val started=begin();val oldInput=input(120000,started.changes)
        assertEquals("P62:CANCELLED",(app().complete(started.pending,oldInput){true} as NpcActionCompletion.Unavailable).reasonUid)
        scope=scope.copy(historyGenerationUid="OTHER-HISTORY")
        assertEquals("P62:STALE_SCOPE",(app().complete(started.pending,oldInput){false} as NpcActionCompletion.Unavailable).reasonUid)
        assertEquals(origin,body.locationRef);assertEquals(1,modelCalls)
    }
}
