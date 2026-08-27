package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

/** One reusable semantic conformance probe run against controlled, local and cloud production ports. */
class AiProviderConformanceSuiteTest{
    private val campaign="CONFORMANCE-CAMPAIGN"
    private val actor=CommandActorRef("PLAYER","P1")
    private val target=DomainRef("LOCATION","L1")
    private val aiIntent=IntentDocument(
        campaignUid=campaign,actor=actor,rawInput="Idę do wieży.",meaningState=MeaningState.UNDERSTOOD,
        nodes=listOf(IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="MOVE",rawPhrase="idę"),participants=listOf(IntentParticipant("TARGET",referenceUid="R")))),
        references=listOf(IntentReference("R",IntentReferenceKind.DESCRIPTIVE,"wieża","TARGET",descriptorHints=mapOf("surface" to "wieża"))),
        provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,"CONFORMANCE","1","INPUT")
    )
    private val trustedIntent=aiIntent.copy(
        references=listOf(aiIntent.references.single().copy(state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=target,resolutionEvidenceUid="PHASE38:CONTROLLED")),
        provenance=aiIntent.provenance.copy(source=IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,sourceUid="PHASE38")
    )
    private val plan:CanonicalTurnPlan by lazy{
        val capability=CapabilityDescriptor("MOVE",1,semanticFamilyUids=setOf("MOVE"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),
            executionKind=CapabilityExecutionKind.READ_CONTEXT,sideEffectClass=CapabilitySideEffectClass.NONE)
        (GraphTurnPlanner(listOf(capability)).plan(trustedIntent,VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)) as CanonicalPlanningResult.Planned).plan
    }
    private val context:BudgetedCanonicalContext by lazy{
        CanonicalIterativeRetrievalPipeline(StructuredSqlRetriever(emptyList()),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()})
            .execute(plan,ContextRuntimeProfile("CONFORMANCE",8_000,100,100,500,100)).budgeted
    }
    private val validProposal:GmProposalCandidate by lazy{GmProposalCandidate(
        proposalUid="PROPOSAL",campaignUid=campaign,planUid=plan.planUid,
        nodeProposals=listOf(GmNodeProposal("N","OK","Docierasz do wieży.",actor,"MOVE",listOf(target),IntentModality.ATTEMPT_NOW,GmNodeOutcomeState.PROPOSED_SUCCESS)),
        narrativeBlueprint=NarrativeBlueprint(listOf("RESULT"),stopPointUid="PLAYER_AGENCY"),providerUid="CONFORMANCE",modelUid="MODEL",
        intentFingerprint=plan.intent.canonicalFingerprint()
    )}

    @Test fun controlledLocalAndCloudPortsPassTheSameSemanticConformanceSuite(){
        val providers=listOf(controlledProvider(),localProvider(),cloudProvider())
        providers.forEach(::assertConforming)
        val semanticOutputs=providers.map{(it.interpret(AiIntentRequest("SEMANTIC:${it.capabilities.providerUid}",campaign,actor,aiIntent.rawInput,"pl-PL")) as AiProviderResult.Success).value.copy(
            provenance=aiIntent.provenance
        ).canonicalFingerprint()}
        assertEquals(1,semanticOutputs.distinct().size)
    }

    private fun assertConforming(provider:AiProvider){
        val request=AiIntentRequest("INTENT:${provider.capabilities.providerUid}",campaign,actor,aiIntent.rawInput,"pl-PL")
        val interpreted=provider.interpret(request) as AiProviderResult.Success
        assertEquals(actor,interpreted.value.actor);assertEquals("MOVE",interpreted.value.nodes.single().semanticAction.semanticFamilyUid)
        assertEquals("wieża",interpreted.value.references.single().rawPhrase)
        assertTrue(Phase43IntentValidator().validate(interpreted.value) is IntentValidationResult.Accepted)

        val cancelled=MutableAiCancellationSignal().also{it.cancel()}
        assertEquals(AiProviderFailureKind.CANCELLED,(provider.interpret(request.copy(requestUid="CANCELLED"),cancelled) as AiProviderResult.Failure).kind)

        val proposalRequest=AiGmProposalRequest("PROPOSAL:${provider.capabilities.providerUid}",plan,context)
        val proposal=(provider.propose(proposalRequest) as AiProviderResult.Success).value
        assertTrue(StructuredGmProposalValidator().validate(proposal,plan) is GmProposalValidationResult.Accepted)
        assertEquals(actor,proposal.nodeProposals.single().actor);assertEquals(target,proposal.nodeProposals.single().targetProjectedRefs.single())

        val malicious=validProposal.copy(requestedPlayerVolitionalActionUids=listOf("AI_SPEAKS_FOR_PLAYER"),
            nodeProposals=validProposal.nodeProposals.map{it.copy(actionSemanticUid="INVENTED_ABILITY")})
        val rejection=StructuredGmProposalValidator().validate(malicious,plan) as GmProposalValidationResult.Rejected
        assertTrue(rejection.reasonUids.any{it.startsWith("ACTION_PRESERVATION")});assertTrue("AI_REQUESTED_PLAYER_VOLITION" in rejection.reasonUids)
        val repaired=(provider.repair(AiRepairRequest("REPAIR:${provider.capabilities.providerUid}",proposalRequest,malicious,rejection.reasonUids,1)) as AiProviderResult.Success).value
        assertTrue(StructuredGmProposalValidator().validate(repaired,plan) is GmProposalValidationResult.Accepted)
        assertFalse(context.canonicalPayload().contains("HIDDEN_SECRET"))
        assertFalse(provider.javaClass.declaredMethods.any{it.name.contains("commit",true)||it.name.contains("database",true)})
    }

    private fun controlledProvider()=DeterministicAiProvider(
        capabilities("CONTROLLED",AiProviderKind.CONTROLLED_TEST),{aiIntent},{validProposal},repairFunction={validProposal},
        narrativeFunction={RenderedNarrative("ok",it.context.stopPointUid,it.context.committedOrder)}
    )

    private fun localProvider():AiProvider{
        val profile=BielikLocalModelProfiles.BIELIK_4_5B_V3;val settings=LocalRecommendedSettings.forProfile(profile).copy(backend=LocalRuntimeBackend.CPU,recommended=false)
        val driver=object:LocalInferenceDriver{
            override fun open(profile:LocalModelProfile,settings:LocalModelSettings,artifact:LocalModelArtifact,backend:LocalRuntimeBackend):Any="HANDLE"
            override fun infer(handle:Any,requestUid:String,prompt:String,maximumOutputUnits:Int,cancellation:AiCancellationSignal,onChunk:(LocalGenerationChunk)->Unit)=LocalGenerationOutput(prompt,"TRACE:$requestUid",10,10)
            override fun cancel(requestUid:String)=Unit
            override fun close(handle:Any)=Unit
        }
        val runtime=DriverBackedLocalInferenceRuntime(LocalRuntimeCapabilities("CONFORMANCE",setOf(LocalArtifactFormat.GGUF),setOf(LocalRuntimeBackend.CPU),true,true,true,true,true,true),driver)
        val variant=profile.variants.single{it.variantUid==settings.variantUid}
        val artifact=LocalModelArtifact(profile.modelUid,variant.variantUid,"/controlled/model.gguf",variant.expectedBytes,"a".repeat(64))
        return LocalAiPort(profile,settings,runtime,LocalModelArtifactStore{_,_->artifact},{LocalDeviceCapabilities(32L shl 30,32L shl 30,LocalThermalState.NOMINAL,setOf(LocalRuntimeBackend.CPU),1L shl 30)},codec())
    }

    private fun cloudProvider():AiProvider{
        val model=CloudModelProfile("OPENROUTER","provider/model","Conformance",64_000,AiWorkload.entries.toSet(),true,true)
        val auth=object:CloudAuthPort{
            override val providerUid="OPENROUTER";override fun status()=CloudConnectionStatus(providerUid,CloudAuthState.CONNECTED)
            override fun beginConnect()=error("unused");override fun complete(callback:CloudAuthCallback)=status()
            override fun accessCredential()="key".toCharArray();override fun disconnect()=Unit
        }
        val client=object:CloudInferenceClient{
            override fun discoverModels(credential:CharArray)=listOf(model)
            override fun execute(model:CloudModelProfile,credential:CharArray,request:AiTransportRequest,cancellation:AiCancellationSignal)=CloudInferenceResponse(request.payload,"TRACE:${request.requestUid}",CloudUsage(10,10),1)
            override fun cancel(requestUid:String)=Unit
        }
        return CloudAiPort(model,auth,client,codec())
    }

    private fun capabilities(uid:String,kind:AiProviderKind)=AiCapabilityContract("C:$uid",uid,"MODEL",AiWorkload.entries.toSet(),maximumContextUnits=64_000,providerKind=kind)
    private fun codec()=object:AiStructuredCodec{
        override fun encodeIntent(request:AiIntentRequest)="INTENT"
        override fun decodeIntent(payload:String)=aiIntent
        override fun encodeProposal(request:AiGmProposalRequest)="PROPOSAL"
        override fun decodeProposal(payload:String)=validProposal
        override fun encodeRepair(request:AiRepairRequest)="REPAIR"
        override fun encodeNarrative(request:AiNarrativeRequest)="NARRATIVE"
        override fun encodeNarrativeRepair(request:AiNarrativeRepairRequest)="NARRATIVE_REPAIR"
        override fun decodeNarrative(payload:String)=error("unused")
        override fun encodeDirector(request:AiDirectorRequest)="DIRECTOR"
        override fun decodeDirector(payload:String)=error("unused")
    }
}
