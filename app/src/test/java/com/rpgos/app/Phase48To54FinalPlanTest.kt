package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class Phase48To54FinalPlanTest{
    private val campaign="C-FINAL"
    private val actor=CommandActorRef("PLAYER","P1")

    @Test fun localAdmission_enforcesCapabilitiesMemoryThermalAndRecommendedDefaults(){
        val profile=BielikLocalModelProfiles.BIELIK_4_5B_V3
        val recommended=LocalRecommendedSettings.forProfile(profile)
        assertEquals(profile.recommendedContextUnits,recommended.contextUnits)
        assertEquals(LocalRuntimeBackend.AUTO,recommended.backend)
        val runtime=LocalRuntimeCapabilities("JNI",setOf(LocalArtifactFormat.GGUF),setOf(LocalRuntimeBackend.AUTO,LocalRuntimeBackend.CPU,LocalRuntimeBackend.GPU),true,true,true,true,true,true)
        val safeDevice=LocalDeviceCapabilities(14L shl 30,16L shl 30,LocalThermalState.NOMINAL,setOf(LocalRuntimeBackend.CPU,LocalRuntimeBackend.GPU),1L shl 30)
        val admitted=LocalModelAdmissionController().evaluate(profile,recommended,runtime,safeDevice) as LocalAdmissionResult.Admitted
        assertEquals(LocalRuntimeBackend.GPU,admitted.selectedBackend)
        val unsafe=LocalModelAdmissionController().evaluate(profile,recommended.copy(contextUnits=32_768,recommended=false),runtime,safeDevice.copy(availableMemoryBytes=4L shl 30)) as LocalAdmissionResult.Rejected
        assertTrue("UNSAFE_MEMORY_PROFILE" in unsafe.reasonUids)
        val npu=LocalModelAdmissionController().evaluate(profile,recommended.copy(backend=LocalRuntimeBackend.NPU,recommended=false),runtime,safeDevice) as LocalAdmissionResult.Rejected
        assertTrue("BACKEND_UNAVAILABLE" in npu.reasonUids)
        val thermal=LocalModelAdmissionController().evaluate(profile,recommended,runtime,safeDevice.copy(thermalState=LocalThermalState.CRITICAL)) as LocalAdmissionResult.Rejected
        assertTrue("THERMAL_CRITICAL" in thermal.reasonUids)
    }

    @Test fun localPort_runsBielikProfileThroughUniversalDriverAndCanCancelWithoutCoreLeakage(){
        val profile=BielikLocalModelProfiles.BIELIK_4_5B_V3
        val settings=LocalRecommendedSettings.forProfile(profile)
        val generated=AtomicInteger();val cancelled=mutableListOf<String>()
        val driver=object:LocalInferenceDriver{
            override fun open(profile:LocalModelProfile,settings:LocalModelSettings,artifact:LocalModelArtifact,backend:LocalRuntimeBackend):Any="HANDLE"
            override fun infer(handle:Any,requestUid:String,prompt:String,maximumOutputUnits:Int,cancellation:AiCancellationSignal,onChunk:(LocalGenerationChunk)->Unit):LocalGenerationOutput{
                generated.incrementAndGet();return LocalGenerationOutput("WIRE","TRACE:$requestUid",12,4)
            }
            override fun cancel(requestUid:String){cancelled+=requestUid}
            override fun close(handle:Any)=Unit
        }
        val runtime=DriverBackedLocalInferenceRuntime(LocalRuntimeCapabilities("CONTROLLED-JNI",setOf(LocalArtifactFormat.GGUF),setOf(LocalRuntimeBackend.CPU),true,true,true,true,true,true),driver)
        val artifact=LocalModelArtifact(profile.modelUid,settings.variantUid,"/controlled/bielik.gguf",profile.variants.first().expectedBytes,"a".repeat(64))
        val port=LocalAiPort(profile,settings,runtime,LocalModelArtifactStore{_,_->artifact},{LocalDeviceCapabilities(16L shl 30,20L shl 30,LocalThermalState.NOMINAL,setOf(LocalRuntimeBackend.CPU),1L shl 30)},wireCodec())
        val result=port.interpret(AiIntentRequest("Q",campaign,actor,"Jadę do Krakowa na turniej rycerski.","pl-PL"))
        assertTrue(result is AiProviderResult.Success);assertEquals(1,generated.get())
        assertEquals(AiProviderKind.LOCAL,port.capabilities.providerKind)
        port.cancel("Q");assertEquals(listOf("Q"),cancelled)
        assertFalse(LocalGenerationRequest::class.java.declaredFields.any{it.name.contains("campaignRepository",true)||it.name.contains("database",true)})
    }

    @Test fun openRouterPkce_usesS256AndKeepsCredentialOutsideCampaign(){
        val secrets=memorySecrets();var verifierSeen=""
        val auth=OpenRouterPkceAuthPort(secrets,OpenRouterCallbackEndpointFactory{nonce->OpenRouterCallbackEndpoint("http://127.0.0.1:7777/callback/$nonce","H:$nonce")},OpenRouterCodeExchange{code,verifier->
            assertEquals("AUTH-CODE",code);verifierSeen=verifier;"sk-or-test".toCharArray() to "USER"
        })
        val start=auth.beginConnect()
        assertTrue(start.authorizationUrl.startsWith("https://openrouter.ai/auth?"));assertTrue("code_challenge_method=S256" in start.authorizationUrl)
        assertFalse(start.authorizationUrl.contains("sk-or-test"));assertTrue(verifierSeen.isEmpty())
        val completed=auth.complete(CloudAuthCallback(start.callbackUrl,"AUTH-CODE"))
        assertEquals(CloudAuthState.CONNECTED,completed.state);assertTrue(verifierSeen.length>=43)
        val key=auth.accessCredential()!!;assertEquals("sk-or-test",key.concatToString());key.fill('\u0000')
        auth.disconnect();assertEquals(CloudAuthState.DISCONNECTED,auth.status().state)
        assertFalse(AiSystemConfiguration::class.java.declaredFields.any{it.name.contains("credential",true)||it.name.contains("apiKey",true)})
    }

    @Test fun cloudPort_mapsConnectionAndRateLimitThroughUniversalContract(){
        val auth=fixedAuth("OPENROUTER","key")
        val model=CloudModelProfile("OPENROUTER","provider/model","Cloud Model",32_000,AiWorkload.entries.toSet(),true,true)
        var called=0
        val client=object:CloudInferenceClient{
            override fun discoverModels(credential:CharArray)=listOf(model)
            override fun execute(model:CloudModelProfile,credential:CharArray,request:AiTransportRequest,cancellation:AiCancellationSignal):CloudInferenceResponse{
                called++;return CloudInferenceResponse("WIRE","TRACE",CloudUsage(10,3),99)
            }
            override fun cancel(requestUid:String)=Unit
        }
        val port=CloudAiPort(model,auth,client,wireCodec())
        assertTrue(port.interpret(AiIntentRequest("Q",campaign,actor,"dowolny polski input","pl-PL")) is AiProviderResult.Success)
        assertEquals(1,called);assertEquals(AiProviderKind.CLOUD,port.capabilities.providerKind)
        val limited=CloudAiPort(model,auth,object:CloudInferenceClient{
            override fun discoverModels(credential:CharArray)=emptyList<CloudModelProfile>()
            override fun execute(model:CloudModelProfile,credential:CharArray,request:AiTransportRequest,cancellation:AiCancellationSignal):CloudInferenceResponse=throw CloudRateLimitException()
            override fun cancel(requestUid:String)=Unit
        },wireCodec())
        assertEquals("CLOUD_RATE_LIMIT",(limited.interpret(AiIntentRequest("L",campaign,actor,"input","pl-PL")) as AiProviderResult.Failure).reasonUid)
    }

    @Test fun roleRouter_supportsAllFiveMatricesPinsPrivacyAndUnavailableModel(){
        val local=provider("LOCAL","L",AiProviderKind.LOCAL)
        val cloud=provider("OPENROUTER","C",AiProviderKind.CLOUD)
        val registry=AiProviderRegistry.fromCompositionRoot(listOf(local,cloud))
        val ready=AiAvailabilityPort{p->AiProviderAvailability(AiModelSelection(p.capabilities.providerUid,p.capabilities.modelUid),AiAvailabilityState.READY,"READY")}
        fun router(gm:AiRoleAssignment,director:AiRoleAssignment,privacy:AiPrivacyPolicy=AiPrivacyPolicy())=RoleAwareModelRouter(registry,listOf(gm,director),privacy,ready)
        val autoGm=AiRoleAssignment(AiRole.GAME_MASTER);val autoDirector=AiRoleAssignment(AiRole.DIRECTOR_SCENARIST)
        val localGm=pin(AiRole.GAME_MASTER,local);val localDirector=pin(AiRole.DIRECTOR_SCENARIST,local)
        val cloudGm=pin(AiRole.GAME_MASTER,cloud);val cloudDirector=pin(AiRole.DIRECTOR_SCENARIST,cloud)
        val matrices=listOf(
            router(autoGm,autoDirector) to (local to local),router(localGm,localDirector) to (local to local),
            router(cloudGm,cloudDirector) to (cloud to cloud),router(localGm,cloudDirector) to (local to cloud),
            router(cloudGm,localDirector) to (cloud to local)
        )
        matrices.forEach{(router,expected)->
            assertSame(expected.first,(router.route(AiRole.GAME_MASTER,AiWorkload.GM_PROPOSAL) as AiRouteResult.Selected).provider)
            assertSame(expected.second,(router.route(AiRole.DIRECTOR_SCENARIST,AiWorkload.DIRECTOR_STRATEGY) as AiRouteResult.Selected).provider)
        }
        assertTrue(router(cloudGm,cloudDirector,AiPrivacyPolicy(cloudAllowed=false)).route(AiRole.GAME_MASTER,AiWorkload.GM_PROPOSAL) is AiRouteResult.Unavailable)
        val missing=AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,AiModelSelection("LOCAL","MISSING"))
        assertTrue(router(missing,autoDirector).route(AiRole.GAME_MASTER,AiWorkload.GM_PROPOSAL) is AiRouteResult.Unavailable)
    }

    @Test fun phase43And48_controlledPolishSemanticMatrixPreservesGraphMeaningWithoutWorldAuthority(){
        fun reference(uid:String,kind:IntentReferenceKind,raw:String,state:IntentReferenceState=IntentReferenceState.UNRESOLVED)=
            IntentReference(uid,kind,raw,"TARGET",descriptorHints=mapOf("surface" to raw),state=state)
        fun document(raw:String,nodes:List<IntentNode>,references:List<IntentReference> = emptyList(),meaning:MeaningState=MeaningState.UNDERSTOOD,uncertainties:List<String> = emptyList())=
            IntentDocument(campaignUid=campaign,actor=actor,rawInput=raw,meaningState=meaning,nodes=nodes,references=references,uncertainties=uncertainties,
                provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,"CONTROLLED-POLISH-SEMANTIC","1","INPUT-HASH"))
        fun action(uid:String,family:String,raw:String,form:IntentForm=IntentForm.DIRECT_ACTION,participants:List<IntentParticipant> = emptyList(),
                   dependencies:List<IntentDependency> = emptyList(),polarity:IntentPolarity=IntentPolarity.AFFIRMATIVE,
                   modality:IntentModality=IntentModality.ATTEMPT_NOW,conditions:List<IntentCondition> = emptyList(),result:IntendedResult?=null,
                   commitment:IntentCommitmentState=IntentCommitmentState.ACTIVE)=IntentNode(uid,form,SemanticAction(semanticFamilyUid=family,rawPhrase=raw),participants,
            conditions,dependencies,result,polarity,modality,commitment)

        val travelRef=reference("R-KRAKOW",IntentReferenceKind.DESCRIPTIVE,"Kraków")
        val travel=document("Jadę do Krakowa na turniej rycerski.",listOf(action("N","TRAVEL","jadę",participants=listOf(IntentParticipant("TARGET",referenceUid=travelRef.referenceUid)),modality=IntentModality.INTEND)),listOf(travelRef))
        val typo=document("jade do krakowa na turniej rycerski",travel.nodes,travel.references)
        val abbreviation=document("jadę do krk",travel.nodes,listOf(reference("R-KRK",IntentReferenceKind.DESCRIPTIVE,"krk"))).copy(
            nodes=listOf(action("N","TRAVEL","jadę",participants=listOf(IntentParticipant("TARGET",referenceUid="R-KRK")))))
        val catchNode=action("N1","FISH","łowię rybę",form=IntentForm.SEQUENCE_MEMBER,result=IntendedResult("CAUGHT-FISH","ITEM","złowiona ryba"))
        val sellNode=action("N2","SELL","sprzedaję ją",form=IntentForm.SEQUENCE_MEMBER,participants=listOf(IntentParticipant("TARGET",futureResult=FutureResultReference("CAUGHT-FISH","TARGET",true))),dependencies=listOf(IntentDependency("N1",IntentDependencyKind.REQUIRES_RESULT)))
        val fishing=document("Łowię rybę, a jeśli się uda, sprzedaję ją.",listOf(catchNode,sellNode))
        val negation=document("Nie atakuję strażnika.",listOf(action("N","ATTACK","atakuję",polarity=IntentPolarity.NEGATED)))
        val correction=document("Idę do Gdańska — nie, do Krakowa.",listOf(
            action("OLD","TRAVEL","idę do Gdańska",commitment=IntentCommitmentState.REPLACED),
            action("NEW","TRAVEL","do Krakowa",form=IntentForm.CORRECTION,dependencies=listOf(IntentDependency("OLD",IntentDependencyKind.REPLACES)))
        ))
        val condition=document("Jeśli pada, czekam w gospodzie.",listOf(action("N","WAIT","czekam",form=IntentForm.CONDITIONAL_ACTION,
            modality=IntentModality.CONDITIONAL_FUTURE,conditions=listOf(IntentCondition("C","WEATHER_IS_RAIN")))))
        val ambiguousRef=reference("R-AMB",IntentReferenceKind.DESCRIPTIVE,"zamek",IntentReferenceState.AMBIGUOUS)
        val ambiguity=document("Idę do zamku.",listOf(action("N","TRAVEL","idę",participants=listOf(IntentParticipant("TARGET",referenceUid="R-AMB")))),listOf(ambiguousRef),MeaningState.PARTIAL,listOf("TARGET_AMBIGUOUS"))
        val unknown=document("Przestrajam rezonator eteru.",listOf(action("N","OPEN_WORLD_UNKNOWN","przestrajam rezonator eteru")))
        val deicticRef=reference("R-THERE",IntentReferenceKind.DEICTIC,"tam")
        val deictic=document("Idę tam.",listOf(action("N","TRAVEL","idę",participants=listOf(IntentParticipant("TARGET",referenceUid="R-THERE")))),listOf(deicticRef),MeaningState.PARTIAL,listOf("DEICTIC_REFERENCE_UNRESOLVED"))

        val documents=listOf(travel,typo,abbreviation,fishing,negation,correction,condition,ambiguity,unknown,deictic)
        documents.forEach{assertTrue("Rejected: ${it.rawInput}",Phase43IntentValidator().validate(it) is IntentValidationResult.Accepted)}
        assertEquals(IntentPolarity.NEGATED,negation.nodes.single().polarity)
        assertEquals(IntentDependencyKind.REQUIRES_RESULT,fishing.nodes.single{it.nodeUid=="N2"}.dependencies.single().kind)
        assertEquals(IntentForm.CORRECTION,correction.nodes.single{it.nodeUid=="NEW"}.form)
        assertEquals(IntentModality.CONDITIONAL_FUTURE,condition.nodes.single().modality)
        assertEquals(IntentReferenceState.AMBIGUOUS,ambiguity.references.single().state)
        assertEquals(IntentReferenceKind.DEICTIC,deictic.references.single().kind)
        assertTrue(documents.all{doc->doc.references.none{it.resolvedProjectedRef!=null||it.resolutionEvidenceUid!=null}})
        val onlyTravel=CapabilityDescriptor("TRAVEL",1,semanticFamilyUids=setOf("TRAVEL"),executionKind=CapabilityExecutionKind.READ_CONTEXT,sideEffectClass=CapabilitySideEffectClass.NONE)
        val unknownPlan=(GraphTurnPlanner(listOf(onlyTravel)).plan(unknown,VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)) as CanonicalPlanningResult.Planned).plan
        assertEquals(CapabilityMatchState.REQUIRES_ADJUDICATION,unknownPlan.steps.single().matchState)
    }

    @Test fun phase49_preservesActorActionTargetModalityAndPlayerAgency(){
        val ref=IntentReference("R",IntentReferenceKind.EXISTING_ENTITY,"cel","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=DomainRef("ENTITY","E1"),resolutionEvidenceUid="P38")
        val node=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="MOVE",rawPhrase="idę"),participants=listOf(IntentParticipant("TARGET",referenceUid="R")),modality=IntentModality.INTEND)
        val document=IntentDocument(campaignUid=campaign,actor=actor,rawInput="Idę tam",meaningState=MeaningState.UNDERSTOOD,nodes=listOf(node),references=listOf(ref),provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","H"))
        val capability=CapabilityDescriptor("CAP",1,semanticFamilyUids=setOf("MOVE"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="MOVEMENT")
        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)) as CanonicalPlanningResult.Planned).plan
        val bad=proposalFor(plan,"P").copy(nodeProposals=listOf(GmNodeProposal("N","OK","ruszasz",actor,"ATTACK",listOf(DomainRef("ENTITY","E2")),IntentModality.ATTEMPT_NOW,GmNodeOutcomeState.PROPOSED_SUCCESS)),requestedPlayerVolitionalActionUids=listOf("AI_SPEAKS_FOR_PLAYER"))
        val rejected=StructuredGmProposalValidator().validate(bad,plan) as GmProposalValidationResult.Rejected
        assertTrue(rejected.reasonUids.any{it.startsWith("ACTION_PRESERVATION")});assertTrue(rejected.reasonUids.any{it.startsWith("TARGET_PRESERVATION")})
        assertTrue("AI_REQUESTED_PLAYER_VOLITION" in rejected.reasonUids)
    }

    @Test fun phase50_worldGenerationDoesNotScaleFromPlayerAndCombatIsReplaySafe(){
        assertFalse(WorldActorGenerationRequest::class.java.declaredFields.any{it.name.contains("playerPower",true)||it.name.contains("playerLevel",true)})
        val template=WorldActorMechanicalTemplate("T",MechanicalActorKind.NPC,mapOf("POWER" to 50,"DEFENCE" to 45,"SKILL" to 30,"AGILITY" to 20),setOf("STRIKE"),emptyList(),0,1000)
        val generated=WorldActorMechanicalGenerator().generate(WorldActorGenerationRequest(campaign,DomainRef("NPC","N1"),"T","WORLD:REGION:17",setOf("FOREST"),emptySet(),MechanicalStateMaterialization.FULL),template)
        val defender=generated.copy(actor=DomainRef("NPC","N2"),attributes=mapOf("POWER" to 20,"DEFENCE" to 40,"SKILL" to 20,"AGILITY" to 25))
        val snapshot=ImmutableCombatSnapshot("S",campaign,10,listOf(generated,defender),emptyList(),emptyMap(),emptyMap(),"SNAPSHOT-HASH")
        val intent=CombatIntent("I",campaign,generated.actor,defender.actor,"STRIKE",VolitionalActionSource.NPC_DECISION_ENGINE,"DEFEAT",10)
        val first=UniversalCombatResolver().resolve(intent,snapshot) as CombatResolution.Resolved
        val replay=UniversalCombatResolver().resolve(intent,snapshot) as CombatResolution.Resolved
        assertEquals(first,replay);assertEquals(first.evidence.inputFingerprint,replay.evidence.inputFingerprint)
        val hiddenReaction=UniversalCombatResolver().resolve(intent,snapshot,CombatReactionRequest(defender.actor,"STRIKE",null)) as CombatResolution.Rejected
        assertEquals("ATTACK_NOT_PERCEIVED",hiddenReaction.reasonUid)
    }

    @Test fun phase51And52_rejectConservationConflictBeliefFutureAndNarrativePromotion(){
        val state=CrossDomainCandidateState(campaign,1,finances=listOf(CandidateFinancialBalance("A","C",50),CandidateFinancialBalance("B","C",40)),expectedConservedCurrencyTotals=mapOf("C" to 100))
        assertTrue(CrossDomainCandidateStateValidator().validate(state).any{it.detailUid=="CURRENCY_CONSERVATION:C"})
        val plan=readPlan();val context=safeContext(plan)
        val claims=listOf(
            ProposedWorldClaim("BELIEF","N",ProposedClaimKind.PROJECTED_FACT_CONCLUSION,null,"IS","x",epistemicBasis=ClaimEpistemicBasis.HOLDER_BELIEF),
            ProposedWorldClaim("FUTURE","N",ProposedClaimKind.PROJECTED_FACT_CONCLUSION,null,"WILL","x",supportingRecordUids=listOf("R"),temporalPosition=ClaimTemporalPosition.PREDICTED_OUTCOME),
            ProposedWorldClaim("COLOR","N",ProposedClaimKind.PROJECTED_FACT_CONCLUSION,null,"MOOD","x",supportingRecordUids=listOf("R"),epistemicBasis=ClaimEpistemicBasis.NARRATIVE_ONLY)
        )
        val candidate=proposalFor(plan,"P").copy(proposedClaims=claims)
        val resolved=ResolvedGmProposal(candidate,emptyList())
        val reasons=CounterfactualGuard().rejectionReasons(resolved,context)
        assertTrue(reasons.any{it.startsWith("BELIEF_PROMOTED")});assertTrue(reasons.any{it.startsWith("FUTURE_OR_COUNTERFACTUAL")});assertTrue(reasons.any{it.startsWith("NARRATIVE_PROMOTED")})
    }

    @Test fun phase53_repairCannotRerollOrExpandMechanicsEntitlement(){
        val plan=mechanicsPlan();val context=safeContext(plan);val calls=AtomicInteger()
        val engine=MechanicsResolutionEngine(MechanicsResolverRegistry.fromCompositionRoot(mapOf("FINANCE" to MechanicsRuleResolver{effect,_->
            val n=calls.incrementAndGet();MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(effect.effectUid,effect.nodeUid,effect.mechanicsOwnerUid,effect.effectKindUid,effect.parameters,"PROOF:$n","INPUT","OUTPUT"))
        })))
        val evaluator=GmProposalEvaluator(StructuredGmProposalValidator(),engine)
        val request=AiGmProposalRequest("REQ",plan,context)
        val initial=proposalFor(plan,"P").copy(
            mechanicsEffects=listOf(MechanicsEffectRequest("E","N","FINANCE","TRANSFER",parameters=mapOf("amount" to "5"))),
            proposedClaims=listOf(ProposedWorldClaim("BAD","N",ProposedClaimKind.PROJECTED_FACT_CONCLUSION,null,"X","Y"))
        )
        val repaired=initial.copy(proposedClaims=emptyList(),mechanicsEffects=listOf(MechanicsEffectRequest("E","N","FINANCE","TRANSFER",parameters=mapOf("amount" to "500"))))
        val provider=DeterministicAiProvider(capabilities("P","M",AiProviderKind.CONTROLLED_TEST),{intentDoc(it.rawInput)},{initial},repairFunction={repaired},narrativeFunction={RenderedNarrative("done",it.context.stopPointUid,it.context.committedOrder)})
        val result=BoundedProposalRepair(evaluator,ProposalRepairPolicy(1)).evaluateAndRepair(provider,request,initial,AiCancellationSignal.NONE)
        assertTrue(result.evaluation is GmProposalEvaluation.Rejected);assertEquals(1,calls.get())
        assertTrue((result.evaluation as GmProposalEvaluation.Rejected).reasonUids.contains("REPAIR_REROLL_OR_ENTITLEMENT_CHANGE"))
    }

    @Test fun phase54_validatorUsesCommittedFactsAndFallbackNeverRerunsCommit(){
        val context=committedContext()
        val valid=RenderedNarrative("Drzwi ustępują z cichym trzaskiem.","PLAYER_AGENCY",7,listOf(NarrativeSemanticClaim("C",NarrativeClaimKind.FACT,"F","DOOR_STATE","OPEN")))
        assertTrue(NarrativeValidator().validate(valid,context).accepted)
        val invented=valid.copy(text="PROOF:SECRET Gracz postanawia uciec.",claims=listOf(NarrativeSemanticClaim("X",NarrativeClaimKind.FACT,"UNKNOWN","DOOR_STATE","BROKEN")),assertsPlayerVolition=true)
        val rejection=NarrativeValidator().validate(invented,context)
        assertFalse(rejection.accepted);assertTrue(rejection.reasonUids.any{it.startsWith("NARRATIVE_UNSUPPORTED_FACT")});assertTrue("NARRATIVE_INVENTED_PLAYER_VOLITION" in rejection.reasonUids)
        val provider=provider("P","M",AiProviderKind.CONTROLLED_TEST,narrative={invented})
        val outcome=CommittedNarrativeRenderer(policy=NarrativeRepairPolicy(0)).render(provider,AiNarrativeRequest("N",context,"pl-PL"),AiCancellationSignal.NONE)
        assertTrue(outcome.usedFallback);assertEquals(7,outcome.narrative.committedOrder);assertFalse(outcome.narrative.text.contains("PROOF"))
        val store=InMemoryNarrativeDeliveryStore();val identity=NarrativeDeliveryIdentity("TX",7,"pl-PL")
        val receipt=NarrativeDeliveryReceipt("D",identity,context.contextFingerprint,outcome.narrative,"P","M",narrativeFingerprint(outcome.narrative))
        assertSame(receipt,store.record(receipt));assertEquals(receipt,store.record(receipt));assertEquals(receipt,store.find(identity))
    }

    @Test fun phase65_directorIsPeriodicIdempotentStaleSafeAndOffTurnPath(){
        val jobs=InMemoryDirectorJobStore();val bundles=InMemoryDirectorCandidateStore();val queued=mutableListOf<()->Unit>()
        val context=DirectorContextEnvelope(campaign,"CTX-1",10,setOf("R1"),listOf("W świecie narasta napięcie."),setOf("HIDDEN"),"P38")
        val provider=DeterministicAiProvider(
            capabilities("OPENROUTER","DIRECTOR",AiProviderKind.CLOUD),{intentDoc(it.rawInput)},{throw IllegalArgumentException()},
            narrativeFunction={RenderedNarrative("unused",it.context.stopPointUid,it.context.committedOrder)},
            directorFunction={request->DirectorBundle(bundleUid="B",jobUid=request.jobUid,campaignUid=campaign,triggerUid=request.trigger.triggerUid,contextVersion="CTX-1",asOfCommittedOrder=10,providerUid="OPENROUTER",modelUid="DIRECTOR",candidates=listOf(
                DirectorCandidate("D1",DirectorCandidateKind.FORESHADOWING,"Cienie na trakcie","Kandydat przyszłego wątku",listOf("R1"),"LONG",setOf("SLOW"),"PHASE65_DIRECTOR")
            ),createdAgainstFingerprint="F")}
        )
        val engine=DirectorEngine(FixedAiModelRoute(provider),jobs,bundles,DirectorJobDispatcher{_,work->queued+=work},DirectorContextVersionPort{"CTX-1"})
        val trigger=DirectorTrigger("T",campaign,DirectorTriggerKind.SEMANTIC_EVENT,10,listOf("E"))
        val scheduled=engine.schedule(trigger,context) as DirectorDispatchResult.Scheduled
        assertNull(bundles.latest(campaign));assertEquals(1,queued.size)
        assertTrue(engine.schedule(trigger,context) is DirectorDispatchResult.Skipped)
        queued.single().invoke();assertEquals(DirectorJobState.ACCEPTED,jobs.find(scheduled.jobUid)!!.state);assertNotNull(bundles.latest(campaign))
        val malicious=bundles.latest(campaign)!!.copy(contextVersion="OLD",candidates=bundles.latest(campaign)!!.candidates.map{it.copy(directMutationPayload="WRITE DB")})
        val checked=DirectorBundleValidator().validate(malicious,AiDirectorRequest("R","J",trigger,context),"CTX-1")
        assertFalse(checked.accepted);assertTrue("DIRECTOR_STALE_CONTEXT" in checked.reasonUids);assertTrue("DIRECTOR_DIRECT_MUTATION_ATTEMPT" in checked.reasonUids)
    }

    private fun pin(role:AiRole,p:AiProvider)=AiRoleAssignment(role,AiAssignmentKind.PINNED,AiModelSelection(p.capabilities.providerUid,p.capabilities.modelUid))
    private fun capabilities(uid:String,model:String,kind:AiProviderKind)=AiCapabilityContract("C:$uid:$model",uid,model,AiWorkload.entries.toSet(),maximumContextUnits=64_000,providerKind=kind)
    private fun provider(uid:String,model:String,kind:AiProviderKind,narrative:(AiNarrativeRequest)->RenderedNarrative={RenderedNarrative("ok",it.context.stopPointUid,it.context.committedOrder)}):AiProvider=
        DeterministicAiProvider(capabilities(uid,model,kind),{intentDoc(it.rawInput)},{request->proposalFor(request.plan,uid).copy(modelUid=model)},narrativeFunction=narrative)
    private fun intentDoc(raw:String)=IntentDocument(campaignUid=campaign,actor=actor,rawInput=raw,meaningState=MeaningState.UNDERSTOOD,nodes=listOf(IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="READ",rawPhrase=raw))),provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,"AI","1","H"))
    private fun readPlan():CanonicalTurnPlan{
        val doc=intentDoc("read").copy(provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","H"))
        val cap=CapabilityDescriptor("READ",1,semanticFamilyUids=setOf("READ"),executionKind=CapabilityExecutionKind.READ_CONTEXT,sideEffectClass=CapabilitySideEffectClass.NONE)
        return (GraphTurnPlanner(listOf(cap)).plan(doc,VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)) as CanonicalPlanningResult.Planned).plan
    }
    private fun mechanicsPlan():CanonicalTurnPlan{
        val doc=intentDoc("transfer").copy(nodes=listOf(IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="TRANSFER",rawPhrase="transfer"))),provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","H"))
        val cap=CapabilityDescriptor("TRANSFER",1,semanticFamilyUids=setOf("TRANSFER"),executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="FINANCE")
        return (GraphTurnPlanner(listOf(cap)).plan(doc,VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)) as CanonicalPlanningResult.Planned).plan
    }
    private fun proposalFor(plan:CanonicalTurnPlan,uid:String):GmProposalCandidate=GmProposalCandidate(
        proposalUid="P",campaignUid=plan.campaignUid,planUid=plan.planUid,nodeProposals=plan.steps.map{step->
            val node=plan.intent.nodes.single{it.nodeUid==step.nodeUid};GmNodeProposal(step.nodeUid,"OK","rezultat",plan.intent.actor,node.semanticAction.canonicalActionUid?:node.semanticAction.semanticFamilyUid!!,emptyList(),node.modality,GmNodeOutcomeState.PROPOSED_SUCCESS)
        },narrativeBlueprint=NarrativeBlueprint(listOf("SHOW"),stopPointUid="PLAYER_AGENCY"),providerUid=uid,modelUid="M",intentFingerprint=plan.intent.canonicalFingerprint()
    )
    private fun safeContext(plan:CanonicalTurnPlan)=CanonicalIterativeRetrievalPipeline(StructuredSqlRetriever(emptyList()),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()})
        .execute(plan,ContextRuntimeProfile("T",16_000,100,100,1000,100)).budgeted
    private fun committedContext():CommittedNarrationContext{
        val receipt=TurnCommitReceipt(campaign,"TURN","CMD","TX","SEMANTIC","RESULT",7,1,"MANIFEST",TURN_TRANSACTION_RECEIPT_VERSION)
        val identity=TurnTransactionIdentity(campaign,"TURN","CMD","TX")
        val evidence=AuthoritativeCommitEvidence(receipt,identity)
        return CommittedNarrationContextBuilder(CommittedNarrationReadPort{_,_,_,_->PostCommitPlayerVisibleReadback(
            campaign,"TURN","CMD","TX",7,"P38",mapOf("location" to "hall"),listOf(CommittedNarrativeFact("F",CommittedNarrativeFactKind.FACT,"DOOR","DOOR_STATE","OPEN",7)),listOf("Drzwi są otwarte."),setOf("SECRET"),emptySet(),"PLAYER_AGENCY"
        )}).build(evidence,VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION))
    }
    private fun memorySecrets()=object:SecretStore{
        private val values=mutableMapOf<String,CharArray>()
        override fun put(secretUid:String,value:CharArray){values[secretUid]=value.copyOf()}
        override fun get(secretUid:String)=values[secretUid]?.copyOf()
        override fun remove(secretUid:String){values.remove(secretUid)?.fill('\u0000')}
    }
    private fun fixedAuth(uid:String,key:String)=object:CloudAuthPort{
        override val providerUid=uid;override fun status()=CloudConnectionStatus(uid,CloudAuthState.CONNECTED)
        override fun beginConnect()=error("unused");override fun complete(callback:CloudAuthCallback)=status()
        override fun accessCredential()=key.toCharArray();override fun disconnect()=Unit
    }
    private fun wireCodec()=object:AiStructuredCodec{
        override fun encodeIntent(request:AiIntentRequest)="INPUT"
        override fun decodeIntent(payload:String)=intentDoc("decoded")
        override fun encodeProposal(request:AiGmProposalRequest)="PROPOSAL"
        override fun decodeProposal(payload:String)=error("unused")
        override fun encodeRepair(request:AiRepairRequest)="REPAIR"
        override fun encodeNarrative(request:AiNarrativeRequest)="NARRATIVE"
        override fun encodeNarrativeRepair(request:AiNarrativeRepairRequest)="NARRATIVE_REPAIR"
        override fun decodeNarrative(payload:String)=error("unused")
        override fun encodeDirector(request:AiDirectorRequest)="DIRECTOR"
        override fun decodeDirector(payload:String)=error("unused")
    }
}
