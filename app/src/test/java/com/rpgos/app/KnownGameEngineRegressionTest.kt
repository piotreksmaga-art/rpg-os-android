package com.rpgos.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class KnownGameEngineRegressionTest {
    private val campaign="C"
    private val actor=CommandActorRef("PLAYER","P1")
    private val audience=VisibilityAudienceFactory.player(campaign)
    private val purpose=PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)

    @Test
    fun naturalPolishMovementIsKeptAsAnUnresolvedDescriptorByTheEmergencyFallback(){
        val parsed=IntentParser().parse(campaign,actor,"Idę na poranne zajęcia w akademii.") as IntentParseResult.Parsed

        assertEquals("MOVE",parsed.intent.actionUid)
        assertEquals(IntentParser.UNRESOLVED_TEXT_KIND,parsed.intent.targetRefs.single().kindUid)
        assertEquals("akademii",parsed.intent.targetRefs.single().uid)
        val document=LegacyIntentDocumentAdapter.toDocument(parsed.intent)
        assertEquals(IntentReferenceKind.DESCRIPTIVE,document.references.single().kind)
        assertEquals(IntentReferenceState.UNRESOLVED,document.references.single().state)
        assertEquals("akademii",document.references.single().rawPhrase)
    }

    @Test
    fun localBielikIsThePrimarySemanticIntentInterpreterAndCoreRestoresTrustedIdentity(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę na poranne zajęcia w akademii.","pl-PL")
        val codec=LocalCompactAiJsonCodec()
        val encoded=codec.encodeIntent(request)
        val decoded=codec.decodeIntent(
            """{"s":"U","a":"TRAVEL","p":"idę","t":["akademii"],"m":"ATTEMPT_NOW","pol":"AFFIRMATIVE","q":[]}""",
            request
        )

        assertTrue(encoded.contains("RPGOS_INTENT_LOCAL_9"))
        assertEquals(campaign,decoded.campaignUid)
        assertEquals(actor,decoded.actor)
        assertEquals(request.rawInput,decoded.rawInput)
        assertEquals(IntentInterpretationSource.AI_PROVIDER,decoded.provenance.source)
        assertEquals("TRAVEL",decoded.nodes.single().semanticAction.semanticFamilyUid)
        assertEquals("akademii",decoded.references.single().rawPhrase)
        assertTrue(Phase43IntentValidator().validate(decoded) is IntentValidationResult.Accepted)
    }

    @Test
    fun execuTorchIntentPromptNeverAsksForCharacterCreationQrStatus(){
        val payload=LocalCompactAiJsonCodec().encodeIntent(AiIntentRequest("REQ",campaign,actor,"Idę do Akademii.","pl-PL"))
        val prompt=ExecuTorchInferenceService.bielikChatPrompt(payload)

        assertTrue(prompt.contains("Jesteś parserem"))
        assertTrue(prompt.contains("Każda niezależna czynność z wiadomości gracza to osobny element steps"))
        assertTrue(prompt.contains("what to „miecz”, a where to „stojaka”"))
        assertTrue(prompt.endsWith("{\"steps\":[{\"locality\":\""))
        assertFalse(prompt.contains("destination/where/who/what"))
        assertTrue(prompt.indexOf("Każda niezależna czynność")<prompt.lastIndexOf("Idę do Akademii."))
        assertFalse(prompt.contains("przewoźnika"))
        assertFalse(prompt.contains("statusu Q albo R"))
    }

    @Test
    fun compactV9PreservesEveryCompoundActionAndAlignsModelTyposBackToPlayerText(){
        val input="Siadam na skraju poligonu i spokojnie ćwiczę kontrolę chakry."
        val request=AiIntentRequest("REQ",campaign,actor,input,"pl-PL")
        val codec=LocalCompactAiJsonCodec()

        val encoded=JSONObject(codec.encodeIntent(request))
        val decoded=codec.decodeIntent(
            """{"steps":[{"locality":"L","kind":"ACTION","action":"siadam","where":"polimongu"},{"locality":"L","kind":"TRAIN","action":"ćwiczę","what":"kontrolę chakry"}]}""",
            request
        )

        assertEquals(listOf("Siadam na skraju poligonu","spokojnie ćwiczę kontrolę chakry"),encoded.getJSONArray("segments").let{array->
            (0 until array.length()).map(array::getString)
        })
        assertEquals(listOf("OPEN_WORLD_ACTION","TRAIN"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals("poligonu",decoded.references[0].rawPhrase)
        assertEquals(1,decoded.references.size)
        assertEquals(decoded.nodes[0].participants,decoded.nodes[1].participants)
        assertEquals(IntentDependencyKind.AFTER_SUCCESS,decoded.nodes[1].dependencies.single().kind)
        assertTrue(Phase43IntentValidator().validate(decoded) is IntentValidationResult.Accepted)
    }

    @Test
    fun compactV9RepairsObservedBielikFieldMixupWithoutInventingAWorldObject(){
        val input="Siadam na skraju poligonu i spokojnie ćwiczę kontrolę chakry."
        val request=AiIntentRequest("REQ",campaign,actor,input,"pl-PL")

        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"steps":[{"locality":"poligon","kind":"MOVE","action":"siadam"},{"locality":"poligon","kind":"MOVE","action":"spokojniećwiczę"}]}""",
            request
        )

        assertEquals(listOf("OPEN_WORLD_ACTION","TRAIN"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals("SIADAM",decoded.nodes.first().semanticAction.attributes[UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE])
        assertTrue(decoded.nodes.last().semanticAction.attributes[UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE].orEmpty().contains("WICZ"))
        assertEquals("poligonu",decoded.references.single().rawPhrase)
        assertTrue(decoded.nodes.first().participants.isNotEmpty())
        assertEquals(decoded.nodes.first().participants,decoded.nodes.last().participants)
        assertEquals("SIADAM",UniversalIntentFamilies.trustProviderAction(decoded.nodes.first().semanticAction).canonicalActionUid)
        assertEquals("CWICZE",UniversalIntentFamilies.trustProviderAction(decoded.nodes.last().semanticAction).canonicalActionUid)
        assertTrue(Phase43IntentValidator().validate(decoded) is IntentValidationResult.Accepted)
    }

    @Test
    fun compactV9NeverTurnsTheLastNounOfACopiedClauseIntoTheAction(){
        val request=AiIntentRequest("REQ",campaign,actor,"Rozglądam się po poligonie.","pl-PL")

        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"steps":[{"locality":"L","kind":"MOVE","action":"Rozglądam się po poligonie."}]}""",
            request
        )

        val action=decoded.nodes.single().semanticAction
        assertEquals("ROZGLADAM",action.attributes[UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE])
        assertEquals("OPEN_WORLD_ACTION",action.semanticFamilyUid)
        assertFalse(action.attributes.values.any{it=="POLIGONIE"})
    }

    @Test
    fun localIntentDecoderNeverTreatsChoiceListsAsTrustedWorldHints(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę do warsztatu.","pl-PL")

        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"s":"U","n":[{"id":"N0","a":"TRAVEL","r":"MOVEMENT","p":"idę","t":[{"x":"warsztat","shape":"NAMED_INSTANCE/CATEGORY/ROLE/AFFORDANCE/UNKNOWN","kind":"PLACE/ACTOR/OBJECT/GROUP/ORGANIZATION/EVENT/PROCESS/CONCEPT","category":"WORKSHOP","aff":["CRAFTING"],"topo":"SETTLEMENT_FACILITY"}],"after":[]}],"q":[]}""",
            request
        )

        assertEquals("CATEGORY",decoded.references.single().descriptorHints["shape"])
        assertEquals(null,decoded.references.single().descriptorHints["world_base_kind"])
        assertEquals("WORKSHOP",decoded.references.single().descriptorHints["category"])
    }

    @Test
    fun movementWithAnUnknownTargetRequiresClarificationInsteadOfExecutingASelfMove(){
        val reference=IntentReference("R1",IntentReferenceKind.DESCRIPTIVE,"akademii","TARGET",state=IntentReferenceState.UNRESOLVED)
        val document=document(reference)
        val capability=CapabilityDescriptor(
            "MOVEMENT",1,semanticFamilyUids=setOf("TRAVEL"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,
            mechanicsOwnerUid="UNIVERSAL_MOVEMENT"
        )

        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan

        assertEquals(CapabilityMatchState.REQUIRES_ADJUDICATION,plan.steps.single().matchState)
        assertEquals(listOf("REQUIRED_REFERENCE_UNRESOLVED"),plan.steps.single().reasonUids)
    }

    @Test
    fun movementWithAResolvedCanonicalLocationCanBePlanned(){
        val reference=IntentReference(
            "R1",IntentReferenceKind.DESCRIPTIVE,"Akademia","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,
            resolvedProjectedRef=DomainRef("LOCATION","LOC-ACADEMY"),resolutionEvidenceUid="PHASE38:TEST"
        )
        val document=document(reference,IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION)
        val capability=CapabilityDescriptor(
            "MOVEMENT",1,semanticFamilyUids=setOf("TRAVEL"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,
            mechanicsOwnerUid="UNIVERSAL_MOVEMENT"
        )

        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan

        assertEquals(CapabilityMatchState.COMPOSED,plan.steps.single().matchState)
    }

    @Test
    fun clarificationCannotCarryAWorldMutation(){
        val resolved=IntentReference(
            "R1",IntentReferenceKind.DESCRIPTIVE,"Akademia","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,
            resolvedProjectedRef=DomainRef("LOCATION","LOC-ACADEMY"),resolutionEvidenceUid="PHASE38:TEST"
        )
        val document=document(resolved,IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION)
        val capability=CapabilityDescriptor(
            "MOVEMENT",1,semanticFamilyUids=setOf("TRAVEL"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,
            mechanicsOwnerUid="UNIVERSAL_MOVEMENT"
        )
        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val candidate=GmProposalCandidate(
            proposalUid="P",campaignUid=campaign,planUid=plan.planUid,
            nodeProposals=listOf(GmNodeProposal("N1","O1","Którą Akademię masz na myśli?",actor,"TRAVEL",listOf(DomainRef("LOCATION","LOC-ACADEMY")),IntentModality.ATTEMPT_NOW,GmNodeOutcomeState.NEEDS_CLARIFICATION)),
            mechanicsEffects=listOf(MechanicsEffectRequest("E1","N1","UNIVERSAL_MOVEMENT","MOVEMENT",DomainRef("LOCATION","LOC-ACADEMY"))),
            narrativeBlueprint=NarrativeBlueprint(emptyList(),stopPointUid="PLAYER_CLARIFICATION"),providerUid="P",modelUid="M",
            intentFingerprint=plan.intent.canonicalFingerprint()
        )

        val rejected=StructuredGmProposalValidator().validate(candidate,plan) as GmProposalValidationResult.Rejected
        assertTrue("CLARIFICATION_CARRIES_MUTATION" in rejected.reasonUids)
        assertTrue("PROVIDER_REOPENED_RESOLVED_ADJUDICATION:N1" in rejected.reasonUids)
    }

    @Test
    fun providerCannotReopenCoreAdjudicationForAnExecutableResolvedNode(){
        val resolved=IntentReference("R1",IntentReferenceKind.DESCRIPTIVE,"Akademia","TARGET",state=IntentReferenceState.RESOLVED_LATENT,
            resolvedProjectedRef=DomainRef("PLACE","DYN-ACADEMY"),resolutionEvidenceUid="RPGOS-CORE:LATENT-WORLD:TEST")
        val document=document(resolved,IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION)
        val capability=CapabilityDescriptor("QUERY",1,semanticFamilyUids=setOf("TRAVEL"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),latentParticipantRoles=setOf("TARGET"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="UNIVERSAL_ACTION")
        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val candidate=GmProposalCandidate(proposalUid="P",campaignUid=campaign,planUid=plan.planUid,nodeProposals=listOf(GmNodeProposal(
            "N1","O1","Core ma rozstrzygnąć trasę",actor,"TRAVEL",listOf(DomainRef("PLACE","DYN-ACADEMY")),IntentModality.ATTEMPT_NOW,GmNodeOutcomeState.REQUIRES_ADJUDICATION,
            uncertaintyUids=listOf("ROUTE_UNKNOWN")
        )),narrativeBlueprint=NarrativeBlueprint(emptyList(),stopPointUid="STOP"),providerUid="P",modelUid="M",intentFingerprint=plan.intent.canonicalFingerprint())

        val rejected=StructuredGmProposalValidator().validate(candidate,plan) as GmProposalValidationResult.Rejected

        assertEquals(listOf("PROVIDER_REOPENED_RESOLVED_ADJUDICATION:N1"),rejected.reasonUids)
    }

    @Test
    fun executableMechanicsAdjudicationWithAnExactEffectMeansSubmitTheAttemptToCore(){
        val resolved=IntentReference(
            "R1",IntentReferenceKind.DESCRIPTIVE,"koleżanka","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,
            resolvedProjectedRef=DomainRef("ACTOR","CLASSMATE"),resolutionEvidenceUid="PHASE38:TEST"
        )
        val node=IntentNode(
            "N1",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="ATTACK",rawPhrase="atakuję"),
            participants=listOf(IntentParticipant("TARGET",referenceUid="R1"))
        )
        val document=IntentDocument(
            campaignUid=campaign,actor=actor,rawInput="Próbuję dotknąć koleżankę.",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(node),references=listOf(resolved),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"TEST","1","HASH")
        )
        val capability=CapabilityDescriptor(
            "COMBAT",1,semanticFamilyUids=setOf("ATTACK"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="UNIVERSAL_COMBAT"
        )
        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val target=DomainRef("ACTOR","CLASSMATE")
        val candidate=GmProposalCandidate(
            proposalUid="P",campaignUid=campaign,planUid=plan.planUid,
            nodeProposals=listOf(GmNodeProposal(
                "N1","O1","Mechanika rozstrzyga próbę",actor,"ATTACK",listOf(target),IntentModality.ATTEMPT_NOW,GmNodeOutcomeState.REQUIRES_ADJUDICATION
            )),
            mechanicsEffects=listOf(MechanicsEffectRequest("E1","N1","UNIVERSAL_COMBAT","COMBAT_RESOLUTION",target)),
            narrativeBlueprint=NarrativeBlueprint(emptyList(),stopPointUid="STOP"),providerUid="P",modelUid="M",
            intentFingerprint=plan.intent.canonicalFingerprint()
        )

        val normalized=normalizeExecutableMechanicsAdjudication(candidate,plan)
        assertEquals(GmNodeOutcomeState.PROPOSED_SUCCESS,normalized.nodeProposals.single().outcomeState)
        assertEquals(candidate.mechanicsEffects,normalized.mechanicsEffects)
    }

    @Test
    fun localBielikProposalUsesACompactDecisionWhileCoreRestoresAllCanonicalFields(){
        val resolved=IntentReference(
            "R1",IntentReferenceKind.DESCRIPTIVE,"Akademia","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,
            resolvedProjectedRef=DomainRef("LOCATION","LOC-ACADEMY"),resolutionEvidenceUid="PHASE38:TEST"
        )
        val document=document(resolved,IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION)
        val capability=CapabilityDescriptor(
            "MOVEMENT",1,semanticFamilyUids=setOf("TRAVEL"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,
            mechanicsOwnerUid="UNIVERSAL_MOVEMENT"
        )
        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val context=CanonicalIterativeRetrievalPipeline(
            StructuredSqlRetriever(emptyList()),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()}
        ).execute(plan,ContextRuntimeProfile("TEST",8_000,100,100,500,100)).budgeted
        val request=AiGmProposalRequest("REQ-P",plan,context)
        val codec=LocalCompactAiJsonCodec()

        val encoded=codec.encodeProposal(request)
        val decoded=codec.decodeProposal("""{"n":[{"id":"N1","s":"OK","x":"Smagi rusza w stronę Akademii.","q":[]}]}""",request)
        val prompt=ExecuTorchInferenceService.bielikChatPrompt(encoded)

        assertTrue(encoded.contains("RPGOS_GM_LOCAL_1"))
        assertTrue(prompt.contains("wybierasz tylko OK, F albo Q"))
        assertFalse(prompt.contains("statusu Q albo R"))
        assertEquals(plan.planUid,decoded.planUid)
        assertEquals(actor,decoded.nodeProposals.single().actor)
        assertEquals("TRAVEL",decoded.nodeProposals.single().actionSemanticUid)
        assertEquals(listOf(DomainRef("LOCATION","LOC-ACADEMY")),decoded.nodeProposals.single().targetProjectedRefs)
        assertEquals("LOCATION_TRANSITION",decoded.mechanicsEffects.single().effectKindUid)
        assertTrue(StructuredGmProposalValidator().validate(decoded,plan) is GmProposalValidationResult.Accepted)
    }

    @Test
    fun localBielikProposalAcceptsExactPlanNodeAsObjectKey(){
        val resolved=IntentReference(
            "R1",IntentReferenceKind.DESCRIPTIVE,"Akademia","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,
            resolvedProjectedRef=DomainRef("LOCATION","LOC-ACADEMY"),resolutionEvidenceUid="PHASE38:TEST"
        )
        val document=document(resolved,IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION)
        val capability=CapabilityDescriptor(
            "MOVEMENT",1,semanticFamilyUids=setOf("TRAVEL"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,
            mechanicsOwnerUid="UNIVERSAL_MOVEMENT"
        )
        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val context=CanonicalIterativeRetrievalPipeline(
            StructuredSqlRetriever(emptyList()),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()}
        ).execute(plan,ContextRuntimeProfile("TEST",8_000,100,100,500,100)).budgeted

        val decoded=LocalCompactAiJsonCodec().decodeProposal(
            """{"N1":{"s":"OK","x":"Można wykonać na podstawie kontekstu","q":[]}}""",
            AiGmProposalRequest("REQ-P",plan,context)
        )

        assertEquals("N1",decoded.nodeProposals.single().nodeUid)
        assertTrue(StructuredGmProposalValidator().validate(decoded,plan) is GmProposalValidationResult.Accepted)
    }

    @Test
    fun localBielikProposalRestoresTruncatedDeterministicSecondNodeWithoutInventingClaims(){
        val target=IntentReference(
            "R1",IntentReferenceKind.DESCRIPTIVE,"poligonu","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,
            resolvedProjectedRef=DomainRef("PLACE","DYN-POLIGON"),resolutionEvidenceUid="PHASE38:TEST"
        )
        val document=IntentDocument(
            campaignUid=campaign,actor=actor,rawInput="Siadam i ćwiczę.",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(
                IntentNode("N1",IntentForm.SEQUENCE_MEMBER,SemanticAction(canonicalActionUid="SIADAM",semanticFamilyUid="OPEN_WORLD_ACTION",rawPhrase="siadam"),participants=listOf(IntentParticipant("TARGET",referenceUid="R1"))),
                IntentNode("N2",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="TRAIN",rawPhrase="ćwiczę"),participants=listOf(IntentParticipant("TARGET",referenceUid="R1")),dependencies=listOf(IntentDependency("N1",IntentDependencyKind.AFTER_SUCCESS)))
            ),references=listOf(target),provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"TEST","1","HASH")
        )
        val plan=(GraphTurnPlanner(productionUniversalCapabilities(emptyList())).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val context=CanonicalIterativeRetrievalPipeline(
            StructuredSqlRetriever(emptyList()),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()}
        ).execute(plan,ContextRuntimeProfile("TEST",8_000,100,100,500,100)).budgeted

        val decoded=LocalCompactAiJsonCodec().decodeProposal(
            """{"id":"N1","s":"OK","x":"Można wykonać na podstawie kontekstu","q":[]}""",
            AiGmProposalRequest("REQ-P",plan,context)
        )

        assertEquals(listOf("N1","N2"),decoded.nodeProposals.map{it.nodeUid})
        assertEquals(listOf("INTERACTION","TRAINING"),decoded.mechanicsEffects.map{it.effectKindUid})
        assertTrue(decoded.proposedClaims.isEmpty())
        assertTrue(StructuredGmProposalValidator().validate(decoded,plan) is GmProposalValidationResult.Accepted)
    }

    @Test
    fun gameTurnContextIsFittedToTheActuallySelectedTwoThousandUnitMobileModel(){
        val configured=ContextRuntimeProfile("ANDROID",8_192,256,768,1_024,256)

        val fitted=requireNotNull(configured.fitToProvider(2_048))

        assertEquals(2_048,fitted.effectiveContextUnits)
        assertTrue(fitted.payloadUnits>0)
        assertTrue(fitted.protocolReserveUnits+fitted.systemReserveUnits+fitted.outputReserveUnits+fitted.safetyMarginUnits<2_048)
        assertEquals(configured,configured.fitToProvider(16_384))
    }

    @Test
    fun localBielikNarrativeUsesCompactTextWhileCoreRestoresCommitEvidence(){
        val context=CommittedNarrationContext(
            campaign,"T","CMD","TX",7,"P38",emptyMap(),
            listOf(CommittedNarrativeFact("F1",CommittedNarrativeFactKind.MECHANICAL_RESULT,"P1","POSITION_DELTA","1000",7)),
            listOf("Smagi zbliżył się do celu."),emptySet(),emptySet(),"PLAYER_DECISION_POINT","CTX-FP"
        )
        val request=AiNarrativeRequest(
            "NREQ",context,"pl-PL",
            playerInput="Rozglądam się po poligonie.",
            authorizedContext=listOf(NarrativeAuthorizedContext(
                "FACT:POLIGON","LOCATION","FACT","Poligon jest dostępnym miejscem treningowym w Konohagakure."
            ))
        )
        val codec=LocalCompactAiJsonCodec()

        val encoded=codec.encodeNarrative(request)
        val decoded=codec.decodeNarrative("""{"t":"Rozglądasz się uważnie po poligonie.","vol":false}""",request)
        val prompt=ExecuTorchInferenceService.bielikChatPrompt(encoded)

        assertTrue(encoded.contains("RPGOS_NARRATIVE_LOCAL_1"))
        assertTrue(prompt.contains("polskim Mistrzem Gry"))
        assertTrue(prompt.contains("Rozglądam się po poligonie."))
        assertTrue(prompt.contains("Poligon jest dostępnym miejscem treningowym"))
        assertTrue(prompt.contains("WIDOCZNE FAKTY SCENY"))
        assertTrue(prompt.contains("ZATWIERDZONE SKUTKI"))
        assertFalse(prompt.contains("PLAYER_DECISION_POINT"))
        assertTrue(prompt.endsWith("{\"t\":\""))
        assertEquals("Rozglądasz się uważnie po poligonie.",decoded.text)
        assertEquals(7,decoded.committedOrder)
        assertEquals("PLAYER_DECISION_POINT",decoded.stopReasonUid)
        assertEquals("F1",decoded.claims.single().supportFactUid)
        assertTrue(NarrativeValidator().validate(decoded,context,request.playerInput).accepted)
        val thirdPersonQuestion=decoded.copy(text="Pytasz koleżankę, czy chce poćwiczyć po zajęciach.")
        assertTrue(NarrativeValidator().validate(thirdPersonQuestion,context,"Pytam koleżankę, czy chce poćwiczyć po zajęciach.").accepted)
        val inventedDesire=decoded.copy(text="Chcę teraz zaatakować.")
        assertTrue(NarrativeValidator().validate(inventedDesire,context,request.playerInput).reasonUids.contains("NARRATIVE_INVENTED_PLAYER_VOLITION"))
        val firstPerson=codec.decodeNarrative(
            """{"t":"Czekam spokojnie przez chwilę, a potem zacznę poszukiwania śladów wokół mnie.","vol":false}""",
            request.copy(playerInput="Czekam spokojnie przez chwilę.")
        )
        assertTrue("Provider cannot clear a surface agency violation with vol=false",
            NarrativeValidator().validate(firstPerson,context,"Czekam spokojnie przez chwilę.").reasonUids.contains("NARRATIVE_INVENTED_PLAYER_VOLITION"))
        val addedAction=codec.decodeNarrative(
            """{"t":"Rozglądasz się uważnie po poligonie, szukając dogodnego miejsca do ćwiczeń.","vol":false}""",request
        )
        assertTrue(NarrativeValidator().validate(addedAction,context,request.playerInput).reasonUids.contains("NARRATIVE_INVENTED_PLAYER_VOLITION"))
        assertFalse(encoded.contains("\"t\":\"narracja\""))
        val internalOnlyContext=CommittedNarrationContext(
            context.campaignUid,context.turnUid,context.commandUid,context.transactionUid,context.committedOrder,
            context.playerVisibleProjectionUid,context.playerSnapshot,context.legalFacts,
            listOf("Czynność „rozgladam” została wykonana."),context.forbiddenDisclosureTokens,
            context.campaignDivergenceUids,context.stopPointUid,context.contextFingerprint
        )
        val internalOnlyRequest=request.copy(context=internalOnlyContext)
        assertFalse(codec.encodeNarrative(internalOnlyRequest).contains("została wykonana"))
        assertEquals(
            "Rozglądasz się uważnie po poligonie.",
            codec.decodeNarrative(
                """{"t":"Rozglądasz się uważnie po poligonie.\nCzynność „rozgladam” została wykonana.","vol":false}""",
                internalOnlyRequest
            ).text
        )
        assertThrows(IllegalArgumentException::class.java){codec.decodeNarrative("""{"t":"narracja","vol":false}""",request)}
        assertThrows(IllegalArgumentException::class.java){codec.decodeNarrative("""{"w":["Pierwsze","zdanie.","Drugie","zdanie."],"vol":false}""",request)}
    }

    @Test
    fun compactV9RejectsAnInstructionVerbThatWasNotPresentInThePlayersMessage(){
        val request=AiIntentRequest("REQ",campaign,actor,"Rozglądam się po poligonie i szukam miejsca.","pl-PL")

        assertThrows(IllegalArgumentException::class.java){
            LocalCompactAiJsonCodec().decodeIntent("""{"steps":[{"action":"kopiuj"}]}""",request)
        }

        val legitimateCopy=AiIntentRequest("REQ2",campaign,actor,"Kopiuję mapę do notatnika.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent("""{"steps":[{"action":"kopiuj","what":"mapę","locality":"U"}]}""",legitimateCopy)
        assertEquals("OPEN_WORLD_ACTION",decoded.nodes.single().semanticAction.semanticFamilyUid)
    }

    @Test
    fun localIntentContractPreservesCompoundActionsAndGenericWorldShape(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę do miejsca pracy i naprawiam urządzenie.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"s":"U","n":[{"id":"go","a":"TRAVEL","p":"idę","t":[{"x":"miejsce pracy","shape":"CATEGORY","kind":"PLACE","category":"WORKPLACE","aff":["WORK"],"topo":"SETTLEMENT_FACILITY"}],"after":[]},{"id":"repair","a":"REPAIR","p":"naprawiam","t":[{"x":"urządzenie","shape":"CATEGORY","kind":"OBJECT","category":"DEVICE","aff":["REPAIRABLE"]}],"after":["go"]}],"q":[]}""",
            request
        )

        assertEquals(listOf("TRAVEL","REPAIR"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals(IntentDependencyKind.AFTER_SUCCESS,decoded.nodes[1].dependencies.single().kind)
        assertEquals("WORKPLACE",decoded.references[0].descriptorHints["category"])
        assertEquals("OBJECT",decoded.references[1].descriptorHints["world_base_kind"])
        assertTrue(Phase43IntentValidator().validate(decoded) is IntentValidationResult.Accepted)
    }

    @Test
    fun compactV3IntentCodesPreserveArbitraryCompoundActionAndWorldKinds(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę do warsztatu naprawić zegarek.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"s":"U","n":[{"id":"0","a":"TRAVEL","r":"M","t":[{"x":"warsztat","k":"P","c":"WORKSHOP","f":["ENTER"],"o":"SETTLEMENT_FACILITY"}],"d":[]},{"id":"1","a":"REPAIR","r":"A","t":[{"x":"zegarek","k":"O","c":"CLOCK","f":["REPAIR"],"o":"LOCAL_SITE"}],"d":["0"]}],"q":[]}""",
            request
        )

        assertEquals(listOf("TRAVEL","REPAIR"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals(IntentDependencyKind.AFTER_SUCCESS,decoded.nodes[1].dependencies.single().kind)
        assertEquals("PLACE",decoded.references[0].descriptorHints["world_base_kind"])
        assertEquals("OBJECT",decoded.references[1].descriptorHints["world_base_kind"])
        assertEquals("CATEGORY",decoded.references[1].descriptorHints["shape"])
    }

    @Test
    fun compactV4FlatRowsRestoreTypedCompoundIntent(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę do warsztatu naprawić zegarek.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"s":"U","n":[["TRAVEL","M","warsztat","P","WORKSHOP","ENTER","SETTLEMENT_FACILITY",""],["REPAIR","A","zegarek","O","CLOCK","REPAIR","LOCAL_SITE","0"]],"q":[]}""",
            request
        )

        assertEquals(listOf("TRAVEL","REPAIR"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals("PLACE",decoded.references[0].descriptorHints["world_base_kind"])
        assertEquals("OBJECT",decoded.references[1].descriptorHints["world_base_kind"])
        assertEquals(IntentDependencyKind.AFTER_SUCCESS,decoded.nodes[1].dependencies.single().kind)
    }

    @Test
    fun compactV6LineRowsRestoreAnyTypedCompoundIntentWithoutJson(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę do warsztatu naprawić zegarek.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """ACTIONS
TRAVEL|M|warsztatu|P|L
REPAIR|A|zegarek|O|L
END""",
            request
        )

        assertEquals(listOf("TRAVEL","REPAIR"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals("PLACE",decoded.references[0].descriptorHints["world_base_kind"])
        assertEquals("OBJECT",decoded.references[1].descriptorHints["world_base_kind"])
        assertEquals(IntentDependencyKind.AFTER_SUCCESS,decoded.nodes[1].dependencies.single().kind)
    }

    @Test
    fun compactV6RejectsEveryTargetNotGroundedInThePlayersMessage(){
        val request=AiIntentRequest("REQ",campaign,actor,"Kupuję chleb.","pl-PL")

        assertThrows(IllegalArgumentException::class.java){
            LocalCompactAiJsonCodec().decodeIntent("BUY|A|lokalny biskup chleba|A|L\nEND",request)
        }
    }

    @Test
    fun compactV6UsesTheSameContractForArbitraryWorldActionsAndTargetKinds(){
        val request=AiIntentRequest("REQ",campaign,actor,"Płynę do odległej wyspy, negocjuję z gildą i naprawiam kompas.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """SWIM|M|odległej wyspy|P|R
NEGOTIATE|A|gildą|N|L
REPAIR|A|kompas|O|L
END""",request
        )

        assertEquals(listOf("TRAVEL","OPEN_WORLD_ACTION","REPAIR"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals(listOf("PLACE","ORGANIZATION","OBJECT"),decoded.references.map{it.descriptorHints["world_base_kind"]})
        assertEquals("REMOTE",decoded.references[0].descriptorHints["spatial_scope"])
        assertEquals("LOCAL",decoded.references[1].descriptorHints["spatial_scope"])
        assertEquals("GENERIC_OBJECT",decoded.references[2].descriptorHints["category"])
    }

    @Test
    fun compactV7SeededJsonRestoresArbitraryActionsWithoutDomainSpecificCases(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę do piekarni kupić chleb.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"a":[["M","TRAVEL","piekarni","P","L"],["A","BUY","chleb","O","L"]]}""",request
        )

        assertEquals(listOf("TRAVEL","BUY"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals(listOf("PLACE","OBJECT"),decoded.references.map{it.descriptorHints["world_base_kind"]})
        assertEquals(IntentDependencyKind.AFTER_SUCCESS,decoded.nodes[1].dependencies.single().kind)
    }

    @Test
    fun compactV8NamedFieldsAcceptSemanticWordsInsteadOfPromptCodeLists(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę do piekarni kupić chleb.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"actions":[{"route":"movement","verb":"travel","target":"piekarni","kind":"place","locality":"local"},{"route":"action","verb":"buy","target":"chleb","kind":"object","locality":"local"}]}""",request
        )

        assertEquals(listOf("TRAVEL","BUY"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals(listOf("PLACE","OBJECT"),decoded.references.map{it.descriptorHints["world_base_kind"]})
        assertEquals(IntentDependencyKind.AFTER_SUCCESS,decoded.nodes[1].dependencies.single().kind)
    }

    @Test
    fun compactV9SemanticRolesCreateMultipleUniversalStepsWithoutDomainVocabulary(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę do piekarni kupić chleb.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"steps":[{"action":"go","destination":"piekarni","locality":"local"},{"action":"buy","thing":"chleb","locality":"local"}]}""",request
        )

        assertEquals(listOf("TRAVEL","BUY"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals(listOf("PLACE","OBJECT"),decoded.references.map{it.descriptorHints["world_base_kind"]})
        assertEquals(IntentDependencyKind.AFTER_SUCCESS,decoded.nodes[1].dependencies.single().kind)
    }

    @Test
    fun compactV9PrioritizesTheAffectedObjectOverItsSourceForUniversalActions(){
        val request=AiIntentRequest("REQ",campaign,actor,"Biorę ze stojaka drugi treningowy kunai.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"steps":[{"action":"take","kind":"ACTION","locality":"local","where":"stojaka","what":"drugi treningowy kunai"}]}""",request
        )

        assertEquals("TAKE",decoded.nodes.single().semanticAction.semanticFamilyUid)
        assertEquals(listOf("drugi treningowy kunai","stojaka"),decoded.references.map{it.rawPhrase})
        assertEquals(listOf("OBJECT","PLACE"),decoded.references.map{it.descriptorHints["world_base_kind"]})
        assertEquals(decoded.references.first().referenceUid,decoded.nodes.single().participants.first().referenceUid)
    }

    @Test
    fun compactV9FourQuestionVocabularyRoutesMovementBeforeUntrustedRoleNoise(){
        val request=AiIntentRequest("REQ",campaign,actor,"Idę do biblioteki i pytam archiwistkę o zwój.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"steps":[{"locality":"local","action":"Idę","destination":"biblioteki","what":"zwój"},{"locality":"local","action":"pytam","who":"archiwistkę","what":"zwój"}]}""",request
        )

        assertEquals(listOf("TRAVEL","OPEN_WORLD_ACTION"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals(listOf("biblioteki","archiwistkę","zwój"),decoded.references.map{it.rawPhrase})
        assertEquals(listOf("PLACE","ACTOR","OBJECT"),decoded.references.map{it.descriptorHints["world_base_kind"]})
    }

    @Test
    fun compactV9NormalizesGroundedProximityPhraseEmittedInLocality(){
        val request=AiIntentRequest("REQ",campaign,actor,"Ide na pobliski teren treningowy potrenowac.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"steps":[{"locality":"pobliskiteren","action":"trening","where":"treningowy","who":"ja","what":"potrenuj"}]}""",request
        )

        assertTrue(decoded.references.isNotEmpty())
        assertTrue(decoded.references.all{it.descriptorHints["spatial_scope"]=="LOCAL"})
        assertTrue(decoded.references.all{it.descriptorHints["shape"]=="CATEGORY"})
    }

    @Test
    fun compactV9CollapsesModelLoopBeyondGroundedActionOccurrences(){
        val request=AiIntentRequest("REQ",campaign,actor,"Ide na pobliski teren treningowy potrenowac.","pl-PL")
        val repeated=(1..5).joinToString(","){
            """{"locality":"Ide","action":"potrenowac","destination":"poblicznyterentreningowy"}"""
        }
        val decoded=LocalCompactAiJsonCodec().decodeIntent("""{"steps":[$repeated]}""",request)

        assertEquals(1,decoded.nodes.size)
    }

    @Test
    fun truncatedLocalIntentRecoversOnlyCompleteGroundedStepsAndRepairsRoleConflicts(){
        val partial="""{"steps":[{"action":"Ide","destination":"piekarni","thing":"chleb"},{"action":"kupic","destination":"chleb","person":"Ide"},{"action":"copy","thing":""""
        val recovered=ExecuTorchInferenceService.recoverNamedIntentSteps(partial)
        val request=AiIntentRequest("REQ",campaign,actor,"Ide do piekarni kupic chleb.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(requireNotNull(recovered),request)

        assertEquals(listOf("TRAVEL","OPEN_WORLD_ACTION"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals(listOf("piekarni","chleb"),decoded.references.map{it.rawPhrase})
        assertEquals(listOf("PLACE","OBJECT"),decoded.references.map{it.descriptorHints["world_base_kind"]})
    }

    @Test
    fun compactV9SupportsArbitraryWorldKindsAndRejectsUngroundedModelInventions(){
        val request=AiIntentRequest("REQ",campaign,actor,"Badam klątwę z drużyną i rozmawiam z kapłanką.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"steps":[{"action":"inspect","topic":"klątwę","group":"drużyną","locality":"local"},{"action":"talk","person":"kapłanką","thing":"nieistniejący miecz","locality":"local"}]}""",request
        )

        assertEquals(setOf("CONCEPT","GROUP","ACTOR"),decoded.references.mapNotNull{it.descriptorHints["world_base_kind"]}.toSet())
        assertFalse(decoded.references.any{it.rawPhrase=="nieistniejący miecz"})
        assertEquals(listOf("OPEN_WORLD_ACTION","TALK"),decoded.nodes.map{it.semanticAction.semanticFamilyUid})
    }

    @Test
    fun localIntentContractRoutesEveryUnknownVerbWithoutDiscardingItsMeaning(){
        val request=AiIntentRequest("REQ",campaign,actor,"Świętuję przy lokalnym sanktuarium.","pl-PL")

        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"s":"U","n":[{"id":"celebrate","a":"CELEBRATE","p":"świętuję","t":[{"x":"lokalne sanktuarium","shape":"CATEGORY","kind":"PLACE","category":"SHRINE","aff":["CELEBRATION"],"topo":"SETTLEMENT_FACILITY"}],"after":[]}],"q":[]}""",
            request
        )

        val action=decoded.nodes.single().semanticAction
        assertEquals("OPEN_WORLD_ACTION",action.semanticFamilyUid)
        assertEquals(null,action.canonicalActionUid)
        assertEquals("CELEBRATE",action.attributes[UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE])
        assertEquals("SHRINE",decoded.references.single().descriptorHints["category"])
        assertTrue(Phase43IntentValidator().validate(decoded) is IntentValidationResult.Accepted)
        assertEquals("CELEBRATE",UniversalIntentFamilies.trustProviderAction(action).canonicalActionUid)
    }

    @Test
    fun completelyNewMovementVerbStillUsesMovementMechanics(){
        val request=AiIntentRequest("REQ",campaign,actor,"Czołgam się do schronienia.","pl-PL")
        val decoded=LocalCompactAiJsonCodec().decodeIntent(
            """{"s":"U","n":[{"id":"crawl","a":"CRAWL","r":"MOVEMENT","p":"czołgam się","t":[{"x":"schronienie","shape":"CATEGORY","kind":"PLACE","category":"SHELTER","aff":["PROTECTION"],"topo":"SETTLEMENT_FACILITY"}],"after":[]}],"q":[]}""",
            request
        )
        val providerAction=decoded.nodes.single().semanticAction
        assertEquals("TRAVEL",providerAction.semanticFamilyUid)
        assertEquals("CRAWL",providerAction.attributes[UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE])
        assertTrue(Phase43IntentValidator().validate(decoded) is IntentValidationResult.Accepted)
        assertEquals("CRAWL",UniversalIntentFamilies.trustProviderAction(providerAction).canonicalActionUid)
    }

    @Test
    fun polishOpenInventoryVerbsAreNormalizedBeforePlanning(){
        val drop=UniversalIntentFamilies.trustProviderAction(SemanticAction(
            semanticFamilyUid="OPEN_WORLD_ACTION",rawPhrase="odkładam",
            attributes=mapOf(UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE to "ODLOZYC")
        ))
        val take=UniversalIntentFamilies.trustProviderAction(SemanticAction(
            semanticFamilyUid="OPEN_WORLD_ACTION",rawPhrase="biorę",
            attributes=mapOf(UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE to "BIORE")
        ))
        val unknown=UniversalIntentFamilies.trustProviderAction(SemanticAction(
            semanticFamilyUid="OPEN_WORLD_ACTION",rawPhrase="świętuję",
            attributes=mapOf(UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE to "CELEBRATE")
        ))

        assertEquals("DROP",drop.canonicalActionUid)
        assertEquals("DROP",drop.semanticFamilyUid)
        assertEquals("TAKE",take.canonicalActionUid)
        assertEquals("TAKE",take.semanticFamilyUid)
        assertEquals("CELEBRATE",unknown.canonicalActionUid)
        assertEquals("OPEN_WORLD_ACTION",unknown.semanticFamilyUid)
    }

    @Test
    fun canonicalProposalRequestExposesTheEffectiveActionRequiredByCore(){
        val action=UniversalIntentFamilies.trustProviderAction(SemanticAction(
            semanticFamilyUid="OPEN_WORLD_ACTION",rawPhrase="odkładam",
            attributes=mapOf(UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE to "ODLOZYC")
        ))
        val document=IntentDocument(campaignUid=campaign,actor=actor,rawInput="Odkładam stojak.",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(IntentNode("N1",IntentForm.DIRECT_ACTION,action)),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","H"))
        val capability=CapabilityDescriptor("ACTION",1,semanticFamilyUids=setOf("DROP"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,
            sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="UNIVERSAL_ACTION")
        val plan=(GraphTurnPlanner(listOf(capability)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val context=CanonicalIterativeRetrievalPipeline(
            StructuredSqlRetriever(emptyList()),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()}
        ).execute(plan,ContextRuntimeProfile("TEST",8_000,100,100,500,100)).budgeted
        val encoded=JSONObject(CanonicalAiJsonCodec().encodeProposal(AiGmProposalRequest("REQ",plan,context)))
        val encodedNode=encoded.getJSONObject("intent").getJSONArray("nodes").getJSONObject(0)

        assertEquals("DROP",encodedNode.getString("semantic_family_uid"))
        assertEquals("DROP",encodedNode.getString("canonical_action_uid"))
        assertEquals("DROP",encodedNode.getString("action_semantic_uid"))
    }

    @Test
    fun openActionPlannerSeparatesSelfActionsFromTargetedAndUnresolvedActions(){
        fun plan(node:IntentNode,references:List<IntentReference> = emptyList())=(GraphTurnPlanner(productionUniversalCapabilities(emptyList())).plan(
            IntentDocument(campaignUid=campaign,actor=actor,rawInput="Działam.",meaningState=MeaningState.UNDERSTOOD,
                nodes=listOf(node),references=references,
                provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","HASH")),
            audience,purpose
        ) as CanonicalPlanningResult.Planned).plan.steps.single()
        val self=plan(IntentNode("SELF",IntentForm.DIRECT_ACTION,
            SemanticAction(canonicalActionUid="DANCE",semanticFamilyUid="OPEN_WORLD_ACTION",rawPhrase="tańczę")))
        val unresolvedReference=IntentReference("R-U",IntentReferenceKind.DESCRIPTIVE,"instrument","TARGET",state=IntentReferenceState.UNRESOLVED)
        val unresolved=plan(IntentNode("UNRESOLVED",IntentForm.DIRECT_ACTION,
            SemanticAction(canonicalActionUid="PLAY_MUSIC",semanticFamilyUid="OPEN_WORLD_ACTION",rawPhrase="gram"),
            participants=listOf(IntentParticipant("TARGET",referenceUid="R-U"))),listOf(unresolvedReference))
        val latentReference=IntentReference("R-L",IntentReferenceKind.SET,"lokalny instrument","TARGET",
            state=IntentReferenceState.RESOLVED_LATENT,resolvedProjectedRef=DomainRef("OBJECT","DYN-INSTRUMENT"),
            resolutionEvidenceUid="RPGOS-CORE:LATENT-WORLD:TEST")
        val targeted=plan(IntentNode("TARGETED",IntentForm.DIRECT_ACTION,
            SemanticAction(canonicalActionUid="PLAY_MUSIC",semanticFamilyUid="OPEN_WORLD_ACTION",rawPhrase="gram"),
            participants=listOf(IntentParticipant("TARGET",referenceUid="R-L"))),listOf(latentReference))

        assertEquals("RPGOS-CAPABILITY:UNIVERSAL-ACTION-SELF",self.capabilityUid)
        assertEquals(CapabilityMatchState.REQUIRES_ADJUDICATION,unresolved.matchState)
        assertEquals(listOf("REQUIRED_REFERENCE_UNRESOLVED"),unresolved.reasonUids)
        assertEquals("RPGOS-CAPABILITY:UNIVERSAL-ACTION-TARGET",targeted.capabilityUid)
    }

    @Test
    fun purposeGoalCannotBecomeASecondMovementCommit(){
        val destination=IntentReference("R",IntentReferenceKind.DESCRIPTIVE,"poranne zajęcia","TARGET",state=IntentReferenceState.RESOLVED_LATENT,
            resolvedProjectedRef=DomainRef("EVENT","DYN-CLASS"),resolutionEvidenceUid="RPGOS-CORE:LATENT-WORLD:TEST")
        val goal=IntentNode("GOAL",IntentForm.GOAL,SemanticAction(semanticFamilyUid="REACH",rawPhrase="zdążyć na zajęcia"),
            participants=listOf(IntentParticipant("TARGET",referenceUid="R")),modality=IntentModality.INTEND)
        val document=IntentDocument(campaignUid=campaign,actor=actor,rawInput="Idę do szkoły, żeby zdążyć na zajęcia.",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(goal),references=listOf(destination),provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","H"))

        val step=(GraphTurnPlanner(productionUniversalCapabilities(emptyList())).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan.steps.single()

        assertEquals("RPGOS-CAPABILITY:DECLARED-GOAL",step.capabilityUid)
        assertEquals(CapabilityExecutionKind.READ_CONTEXT,step.executionKind)
        assertEquals(CapabilitySideEffectClass.NONE,step.sideEffectClass)
        assertEquals(null,step.mechanicsOwnerUid)
    }

    @Test
    fun universalWorldResolverMaterializesAnyFeasibleCategoryAndNotOnlyTrainingGrounds(){
        val reference=IntentReference(
            "R",IntentReferenceKind.SET,"warsztat","TARGET",descriptorHints=mapOf(
                "shape" to "CATEGORY","world_base_kind" to "PLACE","category" to "CRAFTING_VENUE",
                "affordances" to "CRAFTING,REPAIR","topology" to "SETTLEMENT_FACILITY"
            )
        )
        val consumer=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="CRAFT",rawPhrase="tworzę"),participants=listOf(IntentParticipant("TARGET",referenceUid="R")))
        val resolver=UniversalWorldMaterializationResolver()

        val first=resolver.resolve(campaign,reference,listOf(consumer),"VILLAGE",emptyList(),null) as UniversalWorldReferenceResolution.Latent
        val replay=resolver.resolve(campaign,reference,listOf(consumer),"VILLAGE",emptyList(),null) as UniversalWorldReferenceResolution.Latent

        assertEquals(first.draft.element,replay.draft.element)
        assertEquals("warsztat",first.draft.displayName)
        assertEquals(WorldElementBaseKind.PLACE,first.draft.baseKind)
        assertEquals("CRAFTING_VENUE",first.draft.categoryUid)
        assertTrue(first.draft.affordanceUids.containsAll(setOf("CRAFTING","REPAIR")))
    }

    @Test
    fun multipleCategoryCandidatesDoNotTurnOneClearCompoundPracticeIntoClarification(){
        fun reference(uid:String,phrase:String,category:String)=IntentReference(
            uid,IntentReferenceKind.DESCRIPTIVE,phrase,"TARGET",semanticTypeHints=setOf("PROCESS"),descriptorHints=mapOf(
                "shape" to "CATEGORY","world_base_kind" to "PROCESS","category" to category,
                "affordances" to "PRACTICE,TRAIN","topology" to "LOCAL_SITE"
            )
        )
        val footwork=reference("R1","pracę nóg","FOOTWORK")
        val posture=reference("R2","utrzymanie prawidłowej postawy","POSTURE_MAINTENANCE")
        val unresolvedNode=IntentNode(
            "N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="PRACTICE",rawPhrase="Ćwiczę"),
            participants=listOf(IntentParticipant("TARGET",referenceUid="R1"),IntentParticipant("TARGET",referenceUid="R2"))
        )
        val resolved=listOf(footwork,posture).mapIndexed{index,value->
            requireNotNull(resolveExistingDescriptorCandidates(
                value,listOf(unresolvedNode),listOf(DomainRef("PROCESS","OLD-${index+1}-B"),DomainRef("PROCESS","OLD-${index+1}-A"))
            ))
        }
        val document=IntentDocument(
            campaignUid=campaign,actor=actor,rawInput="Ćwiczę pracę nóg i utrzymanie prawidłowej postawy.",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(unresolvedNode),references=resolved,
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","HASH")
        )

        assertTrue(resolved.all{it.state==IntentReferenceState.RESOLVED_PROJECTED})
        assertEquals(listOf("OLD-1-A","OLD-2-A"),resolved.map{it.resolvedProjectedRef?.uid})
        val step=(GraphTurnPlanner(productionUniversalCapabilities(emptyList())).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan.steps.single()
        assertEquals(CapabilityMatchState.COMPOSED,step.matchState)

        val named=footwork.copy(descriptorHints=footwork.descriptorHints+("shape" to "NAMED_INSTANCE"))
        assertEquals(IntentReferenceState.AMBIGUOUS,resolveExistingDescriptorCandidates(
            named,listOf(unresolvedNode),listOf(DomainRef("PROCESS","A"),DomainRef("PROCESS","B"))
        )?.state)
    }

    @Test
    fun universalWorldResolverAlsoMaterializesNonLocationKindsFromGenericHints(){
        val reference=IntentReference("R",IntentReferenceKind.SET,"lokalny przewodnik","TARGET",descriptorHints=mapOf(
            "shape" to "ROLE","world_base_kind" to "ACTOR","category" to "GUIDE",
            "affordances" to "GUIDANCE,CONVERSATION","topology" to "LOCAL_SITE"
        ))
        val consumer=IntentNode("N",IntentForm.DIRECT_ACTION,
            SemanticAction(canonicalActionUid="ASK_DIRECTIONS",semanticFamilyUid="OPEN_WORLD_ACTION",rawPhrase="pytam"),
            participants=listOf(IntentParticipant("TARGET",referenceUid="R")))

        val result=UniversalWorldMaterializationResolver().resolve(campaign,reference,listOf(consumer),"MARKET",emptyList(),null)
            as UniversalWorldReferenceResolution.Latent

        assertEquals(WorldElementBaseKind.ACTOR,result.draft.baseKind)
        assertEquals("GUIDE",result.draft.categoryUid)
        assertEquals("MARKET",result.draft.parentAnchorUid)
        assertEquals(WorldEvidenceClassification.GENERATED_PLAUSIBLE,result.draft.sourceClassification)
    }

    @Test
    fun ordinalCategoryObjectIsNotMistakenForAProperNamedArtefact(){
        fun reference(phrase:String)=IntentReference("R",IntentReferenceKind.DESCRIPTIVE,phrase,"TARGET",semanticTypeHints=setOf("OBJECT"),descriptorHints=mapOf(
            "shape" to "NAMED_INSTANCE","world_base_kind" to "OBJECT","category" to "KUNAI",
            "affordances" to "TAKE,HOLD,USE,PRACTICE","topology" to "LOCAL_SITE","ordinal" to "SECOND"
        ))
        val node=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="TAKE",rawPhrase="biorę"),participants=listOf(IntentParticipant("TARGET",referenceUid="R")))
        val first=CampaignWorldElement(
            DomainRef("OBJECT","KUNAI-1"),"treningowy kunai","KUNAI","ACADEMY-YARD",
            setOf("TAKE","HOLD","USE","PRACTICE"),"LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE
        )
        val resolver=UniversalWorldMaterializationResolver()

        val second=resolver.resolve(campaign,reference("drugi treningowy kunai"),listOf(node),"ACADEMY-YARD",listOf(first),null)
            as UniversalWorldReferenceResolution.Latent
        val sameOrdinalRephrased=resolver.resolve(campaign,reference("drugi kunai treningowy"),listOf(node),"ACADEMY-YARD",listOf(first),null)
            as UniversalWorldReferenceResolution.Latent

        assertEquals(WorldElementBaseKind.OBJECT,second.draft.baseKind)
        assertEquals("KUNAI",second.draft.categoryUid)
        assertEquals(second.draft.element,sameOrdinalRephrased.draft.element)
        assertTrue(second.feasibility.state==WorldFeasibilityState.FEASIBLE_NEARBY)
    }

    @Test
    fun ordinaryProviderSemanticHintsMaterializeGloballyWithoutTechnicalDescriptorTokens(){
        val route=IntentReference("R-ROUTE",IntentReferenceKind.DESCRIPTIVE,"którędy dojść","TARGET",
            semanticTypeHints=setOf("ROUTE"),descriptorHints=mapOf("kind" to "droga prowadząca do celu","spatial_scope" to "Konohagakure"))
        val destination=IntentReference("R-DEST",IntentReferenceKind.DESCRIPTIVE,"poranne zajęcia w Akademii","DESTINATION",
            semanticTypeHints=setOf("ACTIVITY_LOCATION"),descriptorHints=mapOf("kind" to "zajęcia w Akademii","category" to "poranne zajęcia","spatial_scope" to "Akademia"))
        val node=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="SEARCH",rawPhrase="sprawdzam którędy dojść"),
            participants=listOf(IntentParticipant("TARGET",referenceUid=route.referenceUid),IntentParticipant("DESTINATION",referenceUid=destination.referenceUid)))
        val resolver=UniversalWorldMaterializationResolver()

        val routeResult=resolver.resolve(campaign,route,listOf(node),"VIL-KONOHA",emptyList(),null) as UniversalWorldReferenceResolution.Latent
        val destinationResult=resolver.resolve(campaign,destination,listOf(node),"VIL-KONOHA",emptyList(),null) as UniversalWorldReferenceResolution.Latent

        assertEquals(WorldElementBaseKind.PLACE,routeResult.draft.baseKind)
        assertEquals("ROUTE",routeResult.draft.categoryUid)
        assertEquals("LOCAL_SITE",routeResult.draft.topologyClassUid)
        assertTrue("SEARCH" in routeResult.draft.affordanceUids)
        assertEquals("ACTIVITY_LOCATION",destinationResult.draft.categoryUid)
        assertEquals("SETTLEMENT_FACILITY",destinationResult.draft.topologyClassUid)
        assertEquals("VIL-KONOHA",destinationResult.draft.parentAnchorUid)
    }

    @Test
    fun providerNamedInstanceMistakeDoesNotBlockBareLocalFacilityButProperNamesStayStrict(){
        val academy=IntentReference("R-A",IntentReferenceKind.DESCRIPTIVE,"Akademii","TARGET",semanticTypeHints=setOf("PLACE"),descriptorHints=mapOf(
            "shape" to "NAMED_INSTANCE","world_base_kind" to "PLACE","kind" to "academy",
            "category" to "educational facility","topology" to "SETTLEMENT_FACILITY","affordances" to "education,training"
        ))
        val queen=IntentReference("R-Q",IntentReferenceKind.DESCRIPTIVE,"Królowa Północy","TARGET",semanticTypeHints=setOf("ACTOR"),descriptorHints=mapOf(
            "shape" to "NAMED_INSTANCE","world_base_kind" to "ACTOR","category" to "RULER","topology" to "LOCAL_SITE","affordances" to "conversation"
        ))
        val visit=IntentNode("N-A",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="TRAVEL",rawPhrase="idę"),participants=listOf(IntentParticipant("TARGET",referenceUid="R-A")))
        val talk=IntentNode("N-Q",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="TALK",rawPhrase="rozmawiam"),participants=listOf(IntentParticipant("TARGET",referenceUid="R-Q")))
        val resolver=UniversalWorldMaterializationResolver()

        val local=resolver.resolve(campaign,academy,listOf(visit),"VIL-KONOHA",emptyList(),null)
        val named=resolver.resolve(campaign,queen,listOf(talk),"VIL-KONOHA",emptyList(),null)

        assertTrue(local is UniversalWorldReferenceResolution.Latent)
        assertEquals("EDUCATIONAL_FACILITY",(local as UniversalWorldReferenceResolution.Latent).draft.categoryUid)
        assertTrue(named is UniversalWorldReferenceResolution.Unresolved)
        assertEquals("NAMED_INSTANCE_EVIDENCE_REQUIRED",(named as UniversalWorldReferenceResolution.Unresolved).reasonUid)
    }

    @Test
    fun namedCharactersAreNeverPlacedLocallyFromUnanchoredInternetEvidence(){
        val reference=IntentReference("R",IntentReferenceKind.DESCRIPTIVE,"Królowa Północy","TARGET",descriptorHints=mapOf(
            "shape" to "NAMED_INSTANCE","world_base_kind" to "ACTOR","category" to "RULER",
            "affordances" to "CONVERSATION","topology" to "LOCAL_SITE"
        ))
        val consumer=IntentNode("N",IntentForm.DIRECT_ACTION,
            SemanticAction(semanticFamilyUid="TALK",rawPhrase="rozmawiam"),participants=listOf(IntentParticipant("TARGET",referenceUid="R")))
        val internet=WorldEvidenceProviderPort{request->listOf(WorldEvidenceCandidate(
            "WEB:1",request.phrase,WorldEvidenceClassification.SOURCE_CANON,0.99,"https://example.test/wiki","1","HASH",
            WorldElementBaseKind.ACTOR,"RULER",null,setOf("CONVERSATION"),"LOCAL_SITE"
        ))}

        val result=UniversalWorldMaterializationResolver(internet).resolve(campaign,reference,listOf(consumer),"MOUNTAIN_VILLAGE",emptyList(),null)

        assertTrue(result is UniversalWorldReferenceResolution.Unresolved)
        assertEquals("NAMED_INSTANCE_EVIDENCE_REQUIRED",(result as UniversalWorldReferenceResolution.Unresolved).reasonUid)
    }

    @Test
    fun coreDoesNotInventRemoteNaturalTopologyAtTheCurrentLocation(){
        val reference=IntentReference("R",IntentReferenceKind.DESCRIPTIVE,"morze","TARGET",descriptorHints=mapOf(
            "shape" to "CATEGORY","world_base_kind" to "PLACE","category" to "SEA","affordances" to "SWIMMING","topology" to "SEA"
        ))
        val consumer=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="TRAVEL",rawPhrase="idę"),participants=listOf(IntentParticipant("TARGET",referenceUid="R")))

        val result=UniversalWorldMaterializationResolver().resolve(campaign,reference,listOf(consumer),"MOUNTAIN_VILLAGE",emptyList(),null)

        assertTrue(result is UniversalWorldReferenceResolution.Unresolved)
        assertEquals("NATURAL_OR_REMOTE_TOPOLOGY_UNRESOLVED",(result as UniversalWorldReferenceResolution.Unresolved).reasonUid)
    }

    @Test
    fun plannerAcceptsFingerprintedLatentPlacesOnlyForOptedInCapabilities(){
        val reference=IntentReference("R",IntentReferenceKind.SET,"lokalne miejsce","TARGET",descriptorHints=mapOf(
            "shape" to "CATEGORY","world_base_kind" to "PLACE","category" to "SERVICE_VENUE","affordances" to "SERVICE","topology" to "SETTLEMENT_FACILITY"
        ))
        val node=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="TRAVEL",rawPhrase="idę"),participants=listOf(IntentParticipant("TARGET",referenceUid="R")))
        val draft=(UniversalWorldMaterializationResolver().resolve(campaign,reference,listOf(node),"HOME",emptyList(),null) as UniversalWorldReferenceResolution.Latent)
        val latent=LatentWorldReferenceCodec.attach(reference,draft.draft,draft.feasibility)
        val document=IntentDocument(campaignUid=campaign,actor=actor,rawInput="Idę do lokalnego miejsca.",meaningState=MeaningState.UNDERSTOOD,nodes=listOf(node),references=listOf(latent),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","HASH"))
        val allowed=CapabilityDescriptor("MOVE",1,semanticFamilyUids=setOf("TRAVEL"),requiredParticipantRoles=setOf("TARGET"),resolvedParticipantRoles=setOf("TARGET"),latentParticipantRoles=setOf("TARGET"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="UNIVERSAL_MOVEMENT")
        val denied=allowed.copy(capabilityUid="MOVE-NO-MATERIALIZATION",latentParticipantRoles=emptySet())

        val allowedPlan=(GraphTurnPlanner(listOf(allowed)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan
        val deniedPlan=(GraphTurnPlanner(listOf(denied)).plan(document,audience,purpose) as CanonicalPlanningResult.Planned).plan

        assertEquals(CapabilityMatchState.COMPOSED,allowedPlan.steps.single().matchState)
        assertEquals(CapabilityMatchState.REQUIRES_ADJUDICATION,deniedPlan.steps.single().matchState)
        assertEquals("REQUIRED_REFERENCE_LATENT_NOT_MATERIALIZABLE",deniedPlan.steps.single().reasonUids.single())
        assertEquals(draft.draft,LatentWorldReferenceCodec.decode(campaign,latent))
    }

    @Test
    fun emergencyFallbackDoesNotContainSpecialCasesForParticularPlayerActivities(){
        val document=requireNotNull(LegacyRuleIntentFallback().interpret(AiIntentRequest("R",campaign,actor,"Idę na poligon potrenować.","pl-PL")))

        assertEquals(listOf("MOVE"),document.nodes.map{it.semanticAction.semanticFamilyUid})
        assertEquals("poligon potrenować",document.references.single().rawPhrase)
        assertEquals(null,document.references.single().descriptorHints["category"])
    }

    @Test
    fun worldDraftMaterializesAsTypedCampaignFactsAndNeverAsModelOwnedState(){
        val target=DomainRef("OBJECT","DYN-OBJECT-1")
        val effect=VerifiedMechanicsCommandEffect(
            "E","N","RPGOS-CORE:WORLD-MATERIALIZER","WORLD_ELEMENT_MATERIALIZE",target,1,
            mapOf(
                "world_base_kind" to "OBJECT","display_name" to "nieznane urządzenie","category_uid" to "DEVICE",
                "affordance_uids" to "REPAIRABLE,PORTABLE","topology_class_uid" to "LOCAL_SITE",
                "source_classification" to "GENERATED_PLAUSIBLE","materialization_level_uid" to "PARTIAL"
            ),"PROOF","INPUT","OUTPUT"
        )

        val materialized=MechanicalEffectMaterializer.materialize(effect) as MechanicalEffectMaterializationResult.Materialized
        val truths=materialized.changes.map{it.payload as CampaignTruthChange}

        assertTrue(truths.all{it.kind==TruthKind.FACT&&it.subjectUid==target.uid&&it.predicate in CampaignWorldFacts.ALL})
        assertTrue(truths.any{it.predicate==CampaignWorldFacts.CATEGORY&&it.objectValue=="DEVICE"})
        assertEquals(setOf("PORTABLE","REPAIRABLE"),truths.filter{it.predicate==CampaignWorldFacts.AFFORDANCE}.mapNotNull{it.objectValue}.toSet())
        assertEquals(materialized.changes.map{it.changeUid},materialized.eventIntents.single().causalChangeUids)
    }

    @Test
    fun takingAWorldObjectMaterializesARealInventoryChange(){
        val player=DomainRef("PLAYER","P1")
        val item=universalInventoryItemMaterialization()
        val effect=VerifiedMechanicsCommandEffect(
            "E-TAKE","N","UNIVERSAL_ACTION","INVENTORY_ADD",player,1,
            mapOf(
                "item_instance_uid" to "DYN-OBJECT-KUNAI","item_definition_uid" to item.itemDefinitionUid,
                "item_world_pack_uid" to item.worldPackUid,"item_key" to item.itemKey,
                "item_display_name" to item.displayName,"item_category_uid" to requireNotNull(item.categoryUid)
            ),"PROOF","INPUT","OUTPUT"
        )

        val materialized=MechanicalEffectMaterializer.materialize(effect) as MechanicalEffectMaterializationResult.Materialized
        val inventory=materialized.changes.single().payload as InventoryChange

        assertEquals(player,inventory.subject)
        assertEquals("DYN-OBJECT-KUNAI",inventory.itemInstanceUid)
        assertEquals(1L,inventory.quantityDelta.units)
        assertEquals(item,inventory.itemMaterialization)
        assertTrue(DomainRef("OBJECT","DYN-OBJECT-KUNAI") in draftReferences(
            PlayerResolutionDraft.create(changes=listOf(materialized.changes.single()))
        ))
        val command=PlayerCommand(
            commandUid="CMD-TAKE",campaignUid=campaign,actor=actor,
            commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN-TAKE",listOf(effect)),
            provenance=CommandProvenance("TEST")
        )
        val binding=WorldPackRuleBinding("WORLD-TAKE","1")
        val decision=UniversalMechanicsWorldRuleProvider(binding).evaluate(
            WorldRuleRequest.draftEffectCheck(
                binding,campaign,actor,command,"COMMAND-FP","CONTEXT-FP",
                WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(changes=materialized.changes))
            )
        )
        assertTrue(decision is WorldRuleDecision.Allowed)
    }

    @Test
    fun inventoryRemovalAdmitsOnlyAnInstanceActuallyHeldByTheCommandActor(){
        val player=DomainRef("PLAYER","P1")
        fun removal(itemUid:String)=VerifiedMechanicsCommandEffect(
            "E-DROP-$itemUid","N","UNIVERSAL_ACTION","INVENTORY_REMOVE",player,-1,
            mapOf("item_instance_uid" to itemUid,"target_kind_uid" to player.kindUid,"target_uid" to player.uid,"magnitude" to "-1"),
            "PROOF","INPUT","OUTPUT"
        )
        val command=PlayerCommand(
            commandUid="CMD-DROP",campaignUid=campaign,actor=actor,
            commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN-DROP",listOf(removal("ITEM-HELD"),removal("ITEM-GHOST"))),
            provenance=CommandProvenance("TEST")
        )

        assertEquals(
            setOf(DomainRef("ITEM_INSTANCE","ITEM-HELD")),
            canonicalHeldInventoryReferences(command,setOf("ITEM-HELD"))
        )
    }

    @Test
    fun hardNoDamageDirectiveIsAValidatedCoreCombatPolicy(){
        val node=IntentNode(
            "N-SPAR",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="ATTACK",rawPhrase="dotykam kunai"),
            intendedResult=IntendedResult("RESULT-SPAR","TOUCH_ONLY","Tylko kontakt w sparingu"),
            constraints=listOf(IntentDirective("NO-INJURY",IntentConstraintKind.QUALITATIVE,DirectiveStrength.HARD,"NO_DAMAGE","N-SPAR"))
        )
        val intent=IntentDocument(
            campaignUid=campaign,actor=actor,rawInput="Dotykam bez obrażeń.",meaningState=MeaningState.UNDERSTOOD,nodes=listOf(node),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,"TEST","1","HASH")
        )

        assertTrue(nonDamagingCombatRequested(intent,node))
        assertFalse(nonDamagingCombatRequested(intent,node.copy(
            intendedResult=null,constraints=node.constraints.map{it.copy(strength=DirectiveStrength.SOFT)}
        )))
    }

    @Test
    fun mixedExactAndZoneCombatPositionsUseACommonCoreConfirmedLocation(){
        val player=DomainRef("PLAYER","P1")
        val npc=DomainRef("ACTOR","N1")
        val normalized=normalizeCombatPositionsForSharedLocation(
            mapOf(player to CombatPosition.Exact(1_000,2_000),npc to CombatPosition.Zone("ACADEMY-YARD")),
            mapOf(player to "ACADEMY-YARD",npc to "ACADEMY-YARD")
        )
        assertEquals(CombatPosition.Zone("ACADEMY-YARD"),normalized[player])
        assertEquals(CombatPosition.Zone("ACADEMY-YARD"),normalized[npc])

        val separate=normalizeCombatPositionsForSharedLocation(
            mapOf(player to CombatPosition.Exact(1_000,2_000),npc to CombatPosition.Zone("FOREST")),
            mapOf(player to "ACADEMY-YARD",npc to "FOREST")
        )
        assertEquals(CombatPosition.Zone("ACADEMY-YARD"),separate[player])
        assertEquals(CombatPosition.Zone("FOREST"),separate[npc])
    }

    @Test
    fun nestedCampaignElementsResolveToTheirCanonicalSceneWithoutWorldSpecificRules(){
        val parents=mapOf(
            "SPARRING" to CampaignSceneParent("PROCESS","ACADEMY-YARD"),
            "ACADEMY-YARD" to CampaignSceneParent("PLACE",null)
        )

        assertEquals("ACADEMY-YARD",canonicalCampaignSceneAnchor("SPARRING",parents::get))
        assertEquals(listOf("SPARRING","ACADEMY-YARD"),canonicalCampaignScenePath("SPARRING",parents::get))
        assertEquals("WORLD-PACK-LOCATION",canonicalCampaignSceneAnchor("WORLD-PACK-LOCATION",parents::get))
        assertEquals(null,canonicalCampaignSceneAnchor("LOOP"){CampaignSceneParent("PROCESS","LOOP")})
        assertTrue(canonicalCampaignScenePath("LOOP"){CampaignSceneParent("PROCESS","LOOP")}.isEmpty())

        val player=DomainRef("PLAYER","P1");val npc=DomainRef("ACTOR","N1")
        assertEquals("COURTYARD",nearestCombatSceneAnchor(mapOf(
            player to listOf("TRAINING-AREA","COURTYARD","ACADEMY"),
            npc to listOf("COURTYARD","ACADEMY")
        )))
        assertEquals(null,nearestCombatSceneAnchor(mapOf(
            player to listOf("ROOM-A","ACADEMY"),npc to listOf("ROOM-B","ACADEMY")
        )))
    }

    @Test
    fun aCombatInstrumentIsNotMisclassifiedAsASecondDefender(){
        val target=IntentReference(
            "R-TARGET",IntentReferenceKind.DESCRIPTIVE,"koleżankę","TARGET",
            state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=DomainRef("ACTOR","CLASSMATE")
        )
        val instrument=IntentReference(
            "R-TOOL",IntentReferenceKind.DESCRIPTIVE,"kunai","INSTRUMENT",
            state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=DomainRef("OBJECT","KUNAI")
        )
        val node=IntentNode(
            "N",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="ATTACK",rawPhrase="atakuję"),
            participants=listOf(IntentParticipant("INSTRUMENT",referenceUid=instrument.referenceUid),IntentParticipant("TARGET",referenceUid=target.referenceUid))
        )
        val intent=IntentDocument(
            campaignUid=campaign,actor=actor,rawInput="Dotykam koleżankę kunai.",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(node),references=listOf(instrument,target),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,"TEST","1","HASH")
        )

        assertEquals(listOf(DomainRef("ACTOR","CLASSMATE")),projectedTargetRefs(intent,node))
    }

    @Test
    fun npcAnswerIsCommittedAsNarrativeEvidenceAndCannotBecomeFact(){
        val teacher=DomainRef("ACTOR","TEACHER-1")
        val text="Dziś ćwiczymy podstawy techniki klonowania. Pokaż mi pierwszą próbę."
        val effect=VerifiedMechanicsCommandEffect(
            "E-NPC-SPEECH","N1","RPGOS-CORE:NARRATIVE-MATERIALIZER","NARRATIVE_EVENT",teacher,1,
            mapOf("predicate_uid" to GmNarrativePredicates.NPC_UTTERANCE,"narrative_text" to text),
            "PROOF","INPUT","OUTPUT"
        )

        val materialized=MechanicalEffectMaterializer.materialize(effect) as MechanicalEffectMaterializationResult.Materialized
        val truth=materialized.changes.single().payload as CampaignTruthChange
        assertEquals(TruthKind.NARRATIVE,truth.kind)
        assertEquals(teacher.uid,truth.subjectUid)
        assertEquals(GmNarrativePredicates.NPC_UTTERANCE,truth.predicate)
        assertEquals(text,truth.narrativeText)
        assertEquals(null,truth.objectValue)

        val readback=PostCommitPlayerVisibleReadback(
            campaign,"TURN","CMD","TX",1,"P38",emptyMap(),
            listOf(CommittedNarrativeFact("F-SPEECH",CommittedNarrativeFactKind.NARRATIVE_COLOR,teacher.uid,GmNarrativePredicates.NPC_UTTERANCE,text,1)),
            listOf("Rozmówca odpowiada: „$text”"),emptySet(),emptySet(),"PLAYER_DECISION_POINT"
        )
        val context=CommittedNarrationContextBuilder(CommittedNarrationReadPort{_,_,_,_->readback}).build(
            AuthoritativeCommitEvidence(
                TurnCommitReceipt(campaign,"TURN","CMD","TX","SEMANTIC","RESULT",1,0,"MANIFEST",3),
                TurnTransactionIdentity(campaign,"TURN","CMD","TX")
            ),audience,purpose
        )
        val supported=RenderedNarrative(
            "Nauczyciel odpowiada: „$text”","PLAYER_DECISION_POINT",1,
            listOf(NarrativeSemanticClaim("C",NarrativeClaimKind.NARRATIVE_COLOR,"F-SPEECH",GmNarrativePredicates.NPC_UTTERANCE,text))
        )
        assertTrue(NarrativeValidator().validate(supported,context).accepted)
        val promoted=supported.copy(claims=listOf(NarrativeSemanticClaim("C",NarrativeClaimKind.FACT,"F-SPEECH",GmNarrativePredicates.NPC_UTTERANCE,text)))
        assertTrue(NarrativeValidator().validate(promoted,context).reasonUids.any{it.startsWith("NARRATIVE_FACT_WITHOUT_FACT_SUPPORT")})
        val drift=supported.copy(claims=listOf(NarrativeSemanticClaim("C",NarrativeClaimKind.NARRATIVE_COLOR,"F-SPEECH",GmNarrativePredicates.NPC_UTTERANCE,"Inne ćwiczenie.")))
        assertTrue(NarrativeValidator().validate(drift,context).reasonUids.any{it.startsWith("NARRATIVE_FACT_DRIFT")})
    }

    @Test
    fun repeatedQuestionsToOneActorBecomeOneCanonicalInteractionDelta(){
        val target=DomainRef("PLAYER","P1")
        fun interaction(uid:String,node:String)=VerifiedMechanicsCommandEffect(
            uid,node,"UNIVERSAL_ACTION","INTERACTION",target,1,
            mapOf("track_uid" to "ACTION:QUERY","magnitude" to "1","target_kind_uid" to target.kindUid,"target_uid" to target.uid),
            "PROOF:$uid","INPUT:$uid","OUTPUT:$uid"
        )

        val result=coalesceInteractionEffects(listOf(interaction("E1","N1"),interaction("E2","N2")))

        assertEquals(1,result.size)
        assertEquals(2L,result.single().magnitude)
        assertEquals("2",result.single().canonicalPayload["magnitude"])
        assertEquals("N1,N2",result.single().canonicalPayload["coalesced_node_uids"])
        assertEquals("N2",result.single().nodeUid)
    }

    @Test
    fun onlyTargetedConversationCanProposeNpcUtteranceNarrative(){
        val teacher=DomainRef("ACTOR","TEACHER-1")
        val reference=IntentReference("R1",IntentReferenceKind.DESCRIPTIVE,"nauczyciel","TARGET",state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=teacher)
        val node=IntentNode("N1",IntentForm.QUERY,SemanticAction(semanticFamilyUid="QUERY",rawPhrase="pytam"),participants=listOf(IntentParticipant("TARGET",referenceUid="R1")))
        val intent=IntentDocument(campaignUid=campaign,actor=actor,rawInput="Pytam nauczyciela o ćwiczenie.",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(node),references=listOf(reference),provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","HASH"))
        val plan=CanonicalTurnPlan(planUid="PLAN",campaignUid=campaign,intent=intent,audience=audience,purpose=purpose,steps=listOf(
            CanonicalTurnPlanStep("STEP","N1","QUERY",CapabilityMatchState.COMPOSED,emptyList(),emptyList(),CapabilityExecutionKind.MECHANICS_PROPOSAL,CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,"UNIVERSAL_ACTION")
        ))
        val base=GmProposalCandidate(
            proposalUid="P",campaignUid=campaign,planUid=plan.planUid,
            nodeProposals=listOf(GmNodeProposal("N1","O","Pytasz nauczyciela.",actor,"QUERY",listOf(teacher),IntentModality.ATTEMPT_NOW,GmNodeOutcomeState.PROPOSED_SUCCESS)),
            proposedClaims=listOf(ProposedWorldClaim("C","N1",ProposedClaimKind.NARRATIVE_COLOR,teacher.uid,GmNarrativePredicates.NPC_UTTERANCE,"Dziś ćwiczymy klony.")),
            narrativeBlueprint=NarrativeBlueprint(emptyList(),stopPointUid="PLAYER_DECISION_POINT"),providerUid="P",modelUid="M",intentFingerprint=intent.canonicalFingerprint()
        )
        assertTrue(StructuredGmProposalValidator().validate(base,plan) is GmProposalValidationResult.Accepted)
        val describedPracticeQuestionIntent=intent.copy(nodes=listOf(node.copy(
            form=IntentForm.QUERY,
            semanticAction=SemanticAction(semanticFamilyUid="PRACTICE",rawPhrase="pytam, czy mogę poćwiczyć")
        )))
        val describedPracticeQuestionPlan=plan.copy(
            intent=describedPracticeQuestionIntent
        )
        val describedPracticeQuestion=base.copy(
            intentFingerprint=describedPracticeQuestionIntent.canonicalFingerprint(),
            nodeProposals=base.nodeProposals.map{it.copy(actionSemanticUid="PRACTICE")}
        )
        assertTrue(StructuredGmProposalValidator().validate(describedPracticeQuestion,describedPracticeQuestionPlan) is GmProposalValidationResult.Accepted)
        val wrongSubject=base.copy(proposedClaims=listOf(base.proposedClaims.single().copy(subjectProjectedUid="STRANGER")))
        val rejected=StructuredGmProposalValidator().validate(wrongSubject,plan) as GmProposalValidationResult.Rejected
        assertTrue(rejected.reasonUids.any{it.startsWith("NPC_UTTERANCE_SUBJECT_NOT_TARGET_ACTOR")})
        val duplicate=base.copy(proposedClaims=listOf(base.proposedClaims.single(),base.proposedClaims.single().copy(claimUid="C2",valueCanonical="Drugie zdanie.")))
        val duplicateRejected=StructuredGmProposalValidator().validate(duplicate,plan) as GmProposalValidationResult.Rejected
        assertTrue(duplicateRejected.reasonUids.any{it.startsWith("MULTIPLE_NPC_UTTERANCES_PER_ACTOR")})
    }

    @Test
    fun askingPermissionToPracticeDoesNotExecuteTrainingBeforePlayerActs(){
        val practice=CapabilityDescriptor(
            "PRACTICE",1,semanticFamilyUids=setOf("PRACTICE"),
            executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,
            sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,
            mechanicsOwnerUid="UNIVERSAL_ACTION"
        )
        val question=IntentDocument(
            campaignUid=campaign,actor=actor,rawInput="Czy mogę poćwiczyć technikę klonowania?",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(IntentNode(
                "N1",IntentForm.QUERY,SemanticAction(semanticFamilyUid="PRACTICE",rawPhrase="czy mogę poćwiczyć"),
                modality=IntentModality.ASK_IF_POSSIBLE
            )),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","HASH")
        )

        val plan=(GraphTurnPlanner(listOf(practice)).plan(question,audience,purpose) as CanonicalPlanningResult.Planned).plan

        assertEquals(CapabilitySideEffectClass.NONE,plan.steps.single().sideEffectClass)
        assertEquals(null,plan.steps.single().mechanicsOwnerUid)
        assertEquals(CapabilityExecutionKind.MECHANICS_PROPOSAL,plan.steps.single().executionKind)
    }

    @Test
    fun futurePlansAndIntentionsNeverExecuteMechanicsBeforeThePlayerActs(){
        val target=IntentReference(
            referenceUid="R1",kind=IntentReferenceKind.EXISTING_ENTITY,rawPhrase="poligon",roleUid="TARGET",
            state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=DomainRef("PLACE","P1")
        )
        fun document(modality:IntentModality)=IntentDocument(
            campaignUid=campaign,actor=actor,rawInput="Pójdę na poligon.",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(IntentNode(
                "N1",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="TRAVEL",rawPhrase="pójdę"),
                participants=listOf(IntentParticipant("TARGET",referenceUid="R1")),modality=modality
            )),references=listOf(target),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"CORE","1","HASH")
        )
        val planner=GraphTurnPlanner(productionUniversalCapabilities(emptyList()))

        val future=(planner.plan(document(IntentModality.PLAN_FUTURE),audience,purpose) as CanonicalPlanningResult.Planned).plan.steps.single()
        val now=(planner.plan(document(IntentModality.ATTEMPT_NOW),audience,purpose) as CanonicalPlanningResult.Planned).plan.steps.single()

        assertEquals(CapabilitySideEffectClass.NONE,future.sideEffectClass)
        assertEquals(null,future.mechanicsOwnerUid)
        assertEquals(CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,now.sideEffectClass)
        assertEquals("UNIVERSAL_MOVEMENT",now.mechanicsOwnerUid)
    }

    @Test
    fun retrospectiveResultReferenceBindsOnlyToAnAuthoritativePreviousCommit(){
        val receipt=TurnCommitReceipt("C","TURN-4","CMD-4","TX-4","SEM","RESULT",4,1,"MANIFEST")
        val result=IntentReference(
            "R1",IntentReferenceKind.DESCRIPTIVE,"rezultat klona","TARGET",
            semanticTypeHints=setOf("OBJECT","RESULT"),descriptorHints=mapOf("category" to "CLONE_RESULT")
        )
        val future=result.copy(rawPhrase="następna próba",semanticTypeHints=setOf("EVENT"),descriptorHints=mapOf("ordinal" to "NEXT","category" to "ATTEMPT"))
        val advice=result.copy(
            rawPhrase="wskazówkę nauczyciela",
            semanticTypeHints=setOf("CONCEPT","INSTRUCTIONAL_HINT"),
            descriptorHints=mapOf("category" to "INSTRUCTIONAL_HINT")
        )

        assertEquals(DomainRef("TURN_RESULT","TX-4"),resolveCommittedTurnResultReference(result,receipt))
        assertEquals(DomainRef("TURN_RESULT","TX-4"),resolveCommittedTurnResultReference(advice,receipt))
        assertEquals(null,resolveCommittedTurnResultReference(result,null))
        assertEquals(null,resolveCommittedTurnResultReference(future,receipt))
    }

    @Test
    fun narrationReadbackKeepsTrainingCountersOutOfPlayerFacingProse(){
        val gain=MechanicalTrackChange(DomainRef("PLAYER","P1"),"TRAINING:GENERAL",ExactLongDelta.of(1))
        val contact=MechanicalTrackChange(DomainRef("ACTOR","N1"),"CONTEST:CONTACT_SUCCESS",ExactLongDelta.of(7))
        val action=MechanicalTrackChange(DomainRef("PLAYER","P1"),"ACTION:TOUCH",ExactLongDelta.of(1))

        assertEquals("PROGRESS_GAINED",playerVisibleTrackValue(gain))
        assertEquals("Ćwiczenie przyniosło zauważalny postęp.",playerVisibleTrackConsequence(gain))
        assertFalse(playerVisibleTrackConsequence(gain).contains("1"))
        assertEquals("CONTACT_SUCCESS_WITHOUT_DAMAGE",playerVisibleTrackValue(contact))
        assertEquals("ACTION_COMPLETED",playerVisibleTrackValue(action))
        assertEquals("Udaje ci się wykonać zamierzone działanie.",playerVisibleTrackConsequence(action))
        assertFalse(narrativeMemoryRecordKindAllowed("RPGOS-CHANGE:MECHANICAL_TRACK"))
        assertTrue(narrativeMemoryRecordKindAllowed("EVENT"))
    }

    @Test
    fun narrationRejectsInternalMechanicalTrackProseEvenWhenTheClaimIsSupported(){
        val context=CommittedNarrationContext(
            campaign,"T","C","TX",12,"P38",emptyMap(),
            listOf(CommittedNarrativeFact("F1",CommittedNarrativeFactKind.MECHANICAL_RESULT,"P1","RPGOS-CHANGE:MECHANICAL_TRACK","ACTION_COMPLETED",12)),
            listOf("Udaje ci się wykonać zamierzone działanie."),emptySet(),emptySet(),"PLAYER_DECISION_POINT","FP"
        )
        val narrative=RenderedNarrative(
            "Na twoim torze mechanicznym odnotowano 1.","PLAYER_DECISION_POINT",12,
            listOf(NarrativeSemanticClaim("C1",NarrativeClaimKind.MECHANICAL_RESULT,"F1","RPGOS-CHANGE:MECHANICAL_TRACK","ACTION_COMPLETED")),false
        )

        assertTrue(NarrativeValidator().validate(narrative,context).reasonUids.contains("INTERNAL_MECHANICS_SURFACE_DISCLOSURE"))
    }

    @Test
    fun spatialTransitionCarriesDestinationWithoutFakeCoordinateDelta(){
        val transition=SpatialChange(actor.let{DomainRef(it.actorKindUid,it.actorUid)},0,0,DomainRef("PLACE","DYN-PLACE-1"))

        assertEquals("DYN-PLACE-1",transition.destinationLocation?.uid)
        assertEquals(0L,transition.deltaXMillimetres)
    }

    private fun document(reference:IntentReference,source:IntentInterpretationSource=IntentInterpretationSource.AI_PROVIDER)=IntentDocument(
        campaignUid=campaign,actor=actor,rawInput="Idę do Akademii.",meaningState=MeaningState.UNDERSTOOD,
        nodes=listOf(IntentNode("N1",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="TRAVEL",rawPhrase="idę"),
            participants=listOf(IntentParticipant("TARGET",referenceUid=reference.referenceUid)))),references=listOf(reference),
        provenance=IntentInterpretationProvenance(source,"TEST","1","HASH")
    )
}
