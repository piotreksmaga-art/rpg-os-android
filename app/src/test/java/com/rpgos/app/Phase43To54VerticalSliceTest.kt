package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase43To54VerticalSliceTest{
    private val campaign="C1"
    private val actor=CommandActorRef("PLAYER","P1")
    private val audience=VisibilityAudienceFactory.player(campaign)
    private val purpose=PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)

    @Test fun phase43_acceptsDeterministicGraphButRejectsAiWorldAuthorityAndBrokenFutureResult(){
        val first=IntentNode(
            "N1",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="SEARCH",rawPhrase="search"),
            intendedResult=IntendedResult("FOUND","ENTITY","something found")
        )
        val second=IntentNode(
            "N2",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="USE",rawPhrase="use"),
            participants=listOf(IntentParticipant("TARGET",futureResult=FutureResultReference("FOUND","TARGET"))),
            dependencies=listOf(IntentDependency("N1",IntentDependencyKind.REQUIRES_RESULT))
        )
        val accepted=Phase43IntentValidator().validate(aiDocument("search then use",listOf(second,first))) as IntentValidationResult.Accepted
        assertEquals(listOf("N1","N2"),accepted.document.nodes.map{it.nodeUid})
        assertEquals(accepted.canonicalHash,Phase43IntentValidator().validate(aiDocument("search then use",listOf(first,second))).let{(it as IntentValidationResult.Accepted).canonicalHash})

        val forged=aiDocument("look",listOf(IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction("LOOK","LOOK","look"))))
        val rejection=Phase43IntentValidator().validate(forged) as IntentValidationResult.Rejected
        assertTrue(rejection.reasonUids.contains("AI_CANNOT_ASSERT_CANONICAL_ACTION_UID"))

        val broken=aiDocument("use it",listOf(second.copy(dependencies=emptyList())))
        assertTrue((Phase43IntentValidator().validate(broken) as IntentValidationResult.Rejected).reasonUids.any{it.startsWith("DANGLING_FUTURE_RESULT")})
    }

    @Test fun phase44_plansEveryResolvedTargetAndEnvelopeRejectsIdentityWidening(){
        val refs=listOf(
            IntentReference("R1",IntentReferenceKind.EXISTING_ENTITY,"first","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=DomainRef("ENTITY","E1"),resolutionEvidenceUid="PROJECTED:R1"),
            IntentReference("R2",IntentReferenceKind.EXISTING_ENTITY,"second","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=DomainRef("ENTITY","E2"),resolutionEvidenceUid="PROJECTED:R2")
        )
        val node=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="SEARCH",rawPhrase="search"),participants=refs.map{IntentParticipant("TARGET",referenceUid=it.referenceUid)})
        val document=trustedDocument("search both",listOf(node),refs)
        val capability=CapabilityDescriptor(
            "CAP:SEARCH",1,semanticFamilyUids=setOf("SEARCH"),requiredParticipantRoles=setOf("TARGET"),executionKind=CapabilityExecutionKind.READ_CONTEXT,
            sideEffectClass=CapabilitySideEffectClass.NONE,requirements=listOf(CapabilityRequirementTemplate(
                "TARGET_STATE","STATE","READ",RequirementImportance.REQUIRED,setOf("subject_kind_uid","subject_uid"),20,"TARGET","subject_kind_uid","subject_uid"
            ))
        )
        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val requirements=plan.steps.single().requirements
        assertEquals(listOf("E1","E2"),requirements.map{it.request.filters.getValue("subject_uid")})
        val widened=requirements.first().request.copy(filters=requirements.first().request.filters+mapOf("subject_uid" to "OTHER"))
        assertTrue(requirements.first().envelope.validate(widened) is EnvelopeValidationResult.Rejected)
    }

    @Test fun phases45To47_neverDropMandatoryContextOrExpandAPlanEnvelope(){
        val plan=singleRequirementPlan()
        val huge="x".repeat(12_000)
        val valueRetriever=StructuredSqlRetriever(listOf(StructuredProviderBinding("STATE",setOf("READ"),StructuredQueryProvider{
            StructuredRetrievalResult.Value(listOf(RetrievalRecord("R",mapOf("payload" to huge),"PROJECTED")),true)
        })))
        val candidate=ContextIntegrityBuilder(valueRetriever).build(plan)
        val tooSmall=SemanticContextBudgetManager().apply(candidate,ContextRuntimeProfile("SMALL",600,20,20,100,10))
        assertFalse(tooSmall.safeForAi)
        assertTrue(tooSmall.includedSegments.any{it.requirement.importance==RequirementImportance.REQUIRED})
        assertTrue(tooSmall.reasonUids.contains("MANDATORY_CONTEXT_EXCEEDS_BUDGET")||tooSmall.reasonUids.contains("FINAL_SERIALIZED_PAYLOAD_EXCEEDS_BUDGET"))

        val unknownRetriever=StructuredSqlRetriever(listOf(StructuredProviderBinding("STATE",setOf("READ"),StructuredQueryProvider{
            StructuredRetrievalResult.Unknown("TEMPORARILY_UNKNOWN")
        })))
        val pipeline=CanonicalIterativeRetrievalPipeline(
            unknownRetriever,SemanticContextBudgetManager(),TypedContextCompletionStrategy{p,_,_->
                val original=p.steps.single().requirements.single().request
                listOf(original.copy(filters=original.filters+mapOf("forbidden_uid" to "SECRET")))
            }
        )
        val result=pipeline.execute(plan,ContextRuntimeProfile("NORMAL",8_000,100,100,500,100))
        assertFalse(result.budgeted.safeForAi)
        assertEquals("NO_LEGAL_PROGRESS",result.terminationUid)
        assertEquals("ENVELOPE_REJECTED",result.attempts.single().reasonUid)
    }

    @Test fun mobileBudgetCountsSharedMandatoryRecordOnceAcrossMultipleNodes(){
        val nodes=listOf(
            IntentNode("N1",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="SEARCH",rawPhrase="search")),
            IntentNode("N2",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="SEARCH",rawPhrase="search"),
                dependencies=listOf(IntentDependency("N1",IntentDependencyKind.AFTER_SUCCESS)))
        )
        val document=trustedDocument("search then search",nodes,emptyList())
        val capability=CapabilityDescriptor(
            "CAP:SEARCH",1,semanticFamilyUids=setOf("SEARCH"),executionKind=CapabilityExecutionKind.READ_CONTEXT,
            sideEffectClass=CapabilitySideEffectClass.NONE,requirements=listOf(
                CapabilityRequirementTemplate("PLAYER_STATE","STATE","READ",RequirementImportance.SAFETY,maximumLimit=1)
            )
        )
        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val retriever=StructuredSqlRetriever(listOf(StructuredProviderBinding("STATE",setOf("READ"),StructuredQueryProvider{
            StructuredRetrievalResult.Value(listOf(RetrievalRecord("SHARED-PLAYER-STATE",mapOf(
                "active_player" to mapOf("campaign_id" to campaign,"player_uid" to "P1"),
                "stats" to listOf(mapOf("uid" to "STAT:AGILITY","value" to 10L))
            ),"PHASE38:TEST")),true)
        })))
        val budgeted=SemanticContextBudgetManager().apply(
            ContextIntegrityBuilder(retriever).build(plan),ContextRuntimeProfile("MOBILE-2K",2_048,64,128,256,64)
        )

        assertTrue(budgeted.safeForAi)
        assertEquals(1,Regex("SHARED-PLAYER-STATE").findAll(budgeted.canonicalPayload()).count())
        assertTrue(budgeted.finalSerializedUnits<=budgeted.payloadCapacityUnits)
    }

    @Test fun phases48To53_providerBoundaryIsSwappableAndUnsupportedFactCannotReachAssembly(){
        val a=provider("A");val b=provider("B")
        val registry=AiProviderRegistry.fromCompositionRoot(listOf(a,b))
        assertSame(a,registry.require("A",AiWorkload.GM_PROPOSAL))
        assertSame(b,registry.require("B",AiWorkload.GM_PROPOSAL))

        val plan=readOnlyPlan()
        val context=safeEmptyContext(plan)
        val candidate=GmProposalCandidate(
            proposalUid="BAD",campaignUid=campaign,planUid=plan.planUid,nodeProposals=listOf(GmNodeProposal("N","OK","result",actor,"READ",emptyList(),IntentModality.ATTEMPT_NOW,GmNodeOutcomeState.PROPOSED_SUCCESS)),
            proposedClaims=listOf(ProposedWorldClaim("CLAIM","N",ProposedClaimKind.PROJECTED_FACT_CONCLUSION,null,"IS_TRUE","yes")),
            narrativeBlueprint=NarrativeBlueprint(listOf("DESCRIBE"),stopPointUid="PLAYER_AGENCY"),providerUid="A",modelUid="MODEL-A",intentFingerprint=plan.intent.canonicalFingerprint()
        )
        val evaluator=evaluator()
        val rejected=evaluator.evaluate(candidate,AiGmProposalRequest("Q",plan,context)) as GmProposalEvaluation.Rejected
        assertTrue(rejected.reasonUids.contains("UNSUPPORTED_FACT_CLAIM:CLAIM"))
    }

    @Test fun phase48_transportFailureAndInvalidOutputStayTyped(){
        val codec=object:AiStructuredCodec{
            override fun encodeIntent(request:AiIntentRequest)="intent"
            override fun decodeIntent(payload:String):IntentDocument=throw NoSuchElementException("invalid enum")
            override fun encodeProposal(request:AiGmProposalRequest)="proposal"
            override fun decodeProposal(payload:String):GmProposalCandidate=throw IllegalArgumentException("invalid")
            override fun encodeRepair(request:AiRepairRequest)="repair"
            override fun encodeNarrative(request:AiNarrativeRequest)="narrative"
            override fun encodeNarrativeRepair(request:AiNarrativeRepairRequest)="narrative-repair"
            override fun decodeNarrative(payload:String):RenderedNarrative=throw IllegalArgumentException("invalid")
            override fun encodeDirector(request:AiDirectorRequest)="director"
            override fun decodeDirector(payload:String):DirectorBundle=throw IllegalArgumentException("invalid")
        }
        val capabilities=capabilities("REMOTE")
        val unavailable=TransportAiProviderAdapter(capabilities,AiStructuredTransport{_,_->AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,"OFFLINE")},codec)
        val request=AiIntentRequest("Q",campaign,actor,"do something","pl-PL")
        assertEquals(AiProviderFailureKind.UNAVAILABLE,(unavailable.interpret(request) as AiProviderResult.Failure).kind)
        val invalid=TransportAiProviderAdapter(capabilities,AiStructuredTransport{transport,_->AiProviderResult.Success(AiTransportResponse(transport.requestUid,"{}","TRACE"),"WIRE","WIRE","TRACE")},codec)
        assertEquals(AiProviderFailureKind.INVALID_STRUCTURED_OUTPUT,(invalid.interpret(request) as AiProviderResult.Failure).kind)
    }

    @Test fun phase54_realCommitPrecedesNarrativeAndCancellationBeforeCommitMutatesNothing(){
        SQLiteDatabase.create(null).use{db->
            GroupATransactionTestFixtures.setupFinance(db,campaign)
            var assembled=0;var committed=0;var narrativeSawReceipt=false
            val provider=provider("P") { request->
                narrativeSawReceipt=TurnTransactionReceiptStore(db).committedTransaction(request.context.transactionUid)?.commitOrder==request.context.committedOrder
                RenderedNarrative("The transfer is complete.","PLAYER_AGENCY",request.context.committedOrder)
            }
            val facade=facade(
                db,provider,
                CanonicalMutationAssembler{request,_,_->assembled++;GroupATransactionTestFixtures.admittedFinancialProposal(campaign,request.commandUid,5)},
                AuthoritativeTurnCommitPort{identity,proposal->committed++;TurnTransactionBoundary.create(db,identity,proposal).commit()}
            )
            val request=chatRequest("PLAY","CMD-A","TX-A")
            val cancelled=facade.play(request.copy(requestUid="CANCEL",commandUid="CMD-C",transactionUid="TX-C"),AiCancellationSignal{true})
            assertTrue(cancelled is ChatTurnResult.Cancelled);assertEquals(0,assembled);assertEquals(0,committed);assertEquals(100L,FinancialStore(db,campaign).balance("A"))

            val result=facade.play(request)
            assertTrue(result is ChatTurnResult.Narrated)
            assertEquals(1,assembled);assertEquals(1,committed);assertTrue(narrativeSawReceipt)
            assertEquals(95L,FinancialStore(db,campaign).balance("A"))
            assertNotNull(TurnTransactionReceiptStore(db).committedTransaction("TX-A")?.commitOrder)
        }
    }

    @Test fun phase54_invalidProposalFailsBeforeAssemblyAndCommit(){
        SQLiteDatabase.create(null).use{db->
            GroupATransactionTestFixtures.setupFinance(db,campaign)
            var assembled=0;var committed=0
            val invalidProvider=DeterministicAiProvider(
                capabilities("BAD"),
                intentFunction={intentFor(it.rawInput)},
                proposalFunction={request->validProposal(request,"BAD").copy(planUid="FOREIGN")},
                narrativeFunction={RenderedNarrative("never",it.context.stopPointUid,it.context.committedOrder)}
            )
            val facade=facade(db,invalidProvider,CanonicalMutationAssembler{request,_,_->assembled++;GroupATransactionTestFixtures.admittedFinancialProposal(campaign,request.commandUid)},AuthoritativeTurnCommitPort{identity,proposal->committed++;TurnTransactionBoundary.create(db,identity,proposal).commit()})
            val result=facade.play(chatRequest("BAD","CMD-BAD","TX-BAD"))
            assertTrue(result is ChatTurnResult.Rejected)
            assertEquals(0,assembled);assertEquals(0,committed);assertEquals(100L,FinancialStore(db,campaign).balance("A"))
        }
    }

    private fun aiDocument(raw:String,nodes:List<IntentNode>)=IntentDocument(
        campaignUid=campaign,actor=actor,rawInput=raw,meaningState=MeaningState.UNDERSTOOD,nodes=nodes,
        provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,"AI","1","HASH")
    )
    private fun trustedDocument(raw:String,nodes:List<IntentNode>,refs:List<IntentReference>)=IntentDocument(
        campaignUid=campaign,actor=actor,rawInput=raw,meaningState=MeaningState.UNDERSTOOD,nodes=nodes,references=refs,
        provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","HASH")
    )
    private fun intentFor(raw:String)=aiDocument(raw,listOf(IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="TRANSFER_FUNDS",rawPhrase=raw))))

    private fun capability(requirement:Boolean)=CapabilityDescriptor(
        "CAP:TRANSFER",1,semanticFamilyUids=setOf("TRANSFER_FUNDS"),executionKind=if(requirement)CapabilityExecutionKind.READ_CONTEXT else CapabilityExecutionKind.MECHANICS_PROPOSAL,
        sideEffectClass=if(requirement)CapabilitySideEffectClass.NONE else CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,
        mechanicsOwnerUid=if(requirement)null else "FINANCE",
        requirements=if(!requirement)emptyList() else listOf(CapabilityRequirementTemplate("STATE","STATE","READ",RequirementImportance.REQUIRED,emptySet(),20))
    )

    private fun singleRequirementPlan():CanonicalTurnPlan{
        val document=trustedDocument("check",listOf(IntentNode("N",IntentForm.QUERY,SemanticAction(semanticFamilyUid="TRANSFER_FUNDS",rawPhrase="check"))),emptyList())
        return (GraphTurnPlanner(listOf(capability(true))).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
    }
    private fun readOnlyPlan():CanonicalTurnPlan{
        val cap=CapabilityDescriptor("CAP:READ",1,semanticFamilyUids=setOf("READ"),executionKind=CapabilityExecutionKind.READ_CONTEXT,sideEffectClass=CapabilitySideEffectClass.NONE)
        val doc=trustedDocument("read",listOf(IntentNode("N",IntentForm.QUERY,SemanticAction(semanticFamilyUid="READ",rawPhrase="read"))),emptyList())
        return (GraphTurnPlanner(listOf(cap)).plan(doc,audience,purpose) as CanonicalPlanningResult.Planned).plan
    }
    private fun safeEmptyContext(plan:CanonicalTurnPlan):BudgetedCanonicalContext{
        val retriever=StructuredSqlRetriever(emptyList())
        return CanonicalIterativeRetrievalPipeline(retriever,SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()})
            .execute(plan,ContextRuntimeProfile("TEST",8_000,100,100,500,100)).budgeted
    }
    private fun capabilities(uid:String)=AiCapabilityContract(
        "CONTRACT:$uid",uid,"MODEL-$uid",AiWorkload.entries.toSet(),maximumContextUnits=16_000
    )
    private fun provider(uid:String,narrative:(AiNarrativeRequest)->RenderedNarrative={RenderedNarrative("done",it.context.stopPointUid,it.context.committedOrder)}):AiProvider=
        DeterministicAiProvider(capabilities(uid),intentFunction={intentFor(it.rawInput)},proposalFunction={validProposal(it,uid)},narrativeFunction=narrative)
    private fun validProposal(request:AiGmProposalRequest,providerUid:String)=GmProposalCandidate(
        proposalUid="PROPOSAL:${request.requestUid}",campaignUid=campaign,planUid=request.plan.planUid,
        nodeProposals=request.plan.steps.map{step->
            val node=request.plan.intent.nodes.single{it.nodeUid==step.nodeUid}
            val targets=node.participants.mapNotNull{part->part.referenceUid?.let{uid->request.plan.intent.references.singleOrNull{it.referenceUid==uid}?.resolvedProjectedRef}}
            GmNodeProposal(step.nodeUid,"SUCCESS","resolved",request.plan.intent.actor,node.semanticAction.canonicalActionUid?:node.semanticAction.semanticFamilyUid!!,targets,node.modality,GmNodeOutcomeState.PROPOSED_SUCCESS)
        },
        mechanicsEffects=request.plan.steps.filter{it.sideEffectClass==CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT}.map{
            MechanicsEffectRequest("EFFECT:${it.nodeUid}",it.nodeUid,it.mechanicsOwnerUid!!,"TRANSFER",parameters=mapOf("amount" to "5"))
        },narrativeBlueprint=NarrativeBlueprint(listOf("SHOW_OUTCOME"),stopPointUid="PLAYER_AGENCY"),providerUid=providerUid,modelUid="MODEL-$providerUid",intentFingerprint=request.plan.intent.canonicalFingerprint()
    )
    private fun evaluator()=GmProposalEvaluator(
        StructuredGmProposalValidator(),MechanicsResolutionEngine(MechanicsResolverRegistry.fromCompositionRoot(mapOf("FINANCE" to MechanicsRuleResolver{effect,_->
            MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(effect.effectUid,effect.nodeUid,effect.mechanicsOwnerUid,effect.effectKindUid,effect.parameters,"FINANCE_RULE_V1"))
        })))
    )
    private fun facade(db:SQLiteDatabase,provider:AiProvider,assembler:CanonicalMutationAssembler,commit:AuthoritativeTurnCommitPort)=AiChatEngineFacade(
        FixedAiModelRoute(provider),Phase43IntentValidator(),TrustedIntentResolutionPort.NONE,IntentInterpretationFallback.NONE,
        GraphTurnPlanner(listOf(capability(false))),
        CanonicalIterativeRetrievalPipeline(StructuredSqlRetriever(emptyList()),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()}),
        ContextRuntimeProfile("E2E",16_000,200,200,1_000,200),BoundedProposalRepair(evaluator()),assembler,commit,
        PersistedCommitReceiptAuthority(CommittedReceiptLookup{TurnTransactionReceiptStore(db).committedTransaction(it)}),
        CommittedNarrationContextBuilder(CommittedNarrationReadPort{identity,receipt,_,_->PostCommitPlayerVisibleReadback(
            identity.campaignUid,identity.turnUid,identity.commandUid,identity.transactionUid,receipt.commitOrder!!,"P38:TEST",
            mapOf("balance" to FinancialStore(db,campaign).balance("A").toString()),
            listOf(CommittedNarrativeFact("FACT:BALANCE",CommittedNarrativeFactKind.MECHANICAL_RESULT,"A","BALANCE",FinancialStore(db,campaign).balance("A").toString(),receipt.commitOrder!!)),
            listOf("The transfer is complete."),emptySet(),emptySet(),"PLAYER_AGENCY"
        )})
    )
    private fun chatRequest(uid:String,command:String,transaction:String)=ChatTurnRequest(
        uid,campaign,"TURN:$uid",command,transaction,actor,"transfer funds","en-US",audience,purpose
    )
}
