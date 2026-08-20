package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Phase38SliceDPerceptionDisclosureTest {
    private val campaign = "C1"
    private val resolver = PerceptionResolver()
    private val disclosure = DisclosureResolver()

    private fun pc(uid: String = "PC-A") = Phase38TrustedTestAuthority.playerCharacter(campaign, uid)

    private fun rules(
        kind: String = "SIGNAL_KIND_A",
        channels: Set<String> = setOf("CHANNEL_A"),
        recognition: RecognitionRule? = RecognitionRule(
            kind, "category", "recognition", "identity",
            classificationThreshold = 0.30, recognitionThreshold = 0.60, identificationThreshold = 0.80
        ),
        interpretation: InterpretationRule? = InterpretationRule(
            kind, "pattern", "interpretation", mapOf("pattern-a" to "supported-conclusion-a", "decoy" to "supported-false-conclusion"),
            minimumQuality = 0.70, expertiseDomainUid = "DOMAIN_A", expertiseWeight = 0.50
        )
    ): PerceptionWorldRules = PerceptionWorldRules(
        "RULES-A", mapOf(kind to channels),
        recognition?.let { mapOf(kind to it) } ?: emptyMap(),
        interpretation?.let { mapOf(kind to it) } ?: emptyMap()
    )

    private fun capability(
        fixture: TrustedAudienceFixture,
        channel: String = "CHANNEL_A",
        minimum: Double = 0.25,
        ceiling: DisclosureLevel = DisclosureLevel.DISCLOSE_FULL,
        owner: VisibilityPrincipalRef = fixture.trusted.principal,
        campaignUid: String = campaign
    ) = PerceptionCapability(
        campaignUid, PerceptionCapabilityRef(campaignUid, "CAP-${owner.uid}-$channel"), owner,
        setOf(channel), minimum, ceiling
    )

    private fun context(
        fixture: TrustedAudienceFixture = pc(),
        capabilities: List<PerceptionCapability> = listOf(capability(fixture)),
        worldRules: PerceptionWorldRules = rules(),
        interference: List<PerceptionInterference> = emptyList(),
        expertise: List<PerceptionExpertise> = emptyList()
    ) = PerceptionContext(campaign, fixture.trusted, capabilities, worldRules, interference, expertise)

    private fun signal(
        quality: Double = 0.90,
        kind: String = "SIGNAL_KIND_A",
        evidence: Map<String, Any?> = mapOf(
            "presence" to true,
            "category" to "presented-category",
            "recognition" to "presented-recognition",
            "identity" to "PRESENTED-B",
            "pattern" to "pattern-a",
            "exact" to 17,
            "private_thought" to "PRIVATE-THOUGHT",
            "hidden_stat" to 999,
            "gm_internal" to "GM-SECRET"
        ),
        campaignUid: String = campaign,
        uncertainty: PerceptionUncertainty = PerceptionUncertainty(0.85, 0.70, 0.60, 10.0, 20.0, setOf("candidate-a", "candidate-b"), 50L),
        presentedUid: String = "PRESENTED-B"
    ) = PerceptionSignal(
        campaignUid, PerceptionSignalRef(campaignUid, "SIG-1"), kind, quality, evidence, uncertainty,
        VisibilitySubjectRef(campaignUid, "OBSERVED_SUBJECT", presentedUid), mapOf("source" to "fixture")
    )

    private fun policy(
        maximum: DisclosureLevel = DisclosureLevel.DISCLOSE_FULL,
        campaignUid: String = campaign
    ) = DisclosurePolicy(
        campaignUid, "POLICY-A", maximum,
        mapOf(
            "presence" to PropertyDisclosureRule("presence", mapOf(DisclosureLevel.DISCLOSE_EXISTENCE to DisclosureValueProjection.Keep)),
            "category" to PropertyDisclosureRule("category", mapOf(DisclosureLevel.CATEGORY_ONLY to DisclosureValueProjection.Keep)),
            "recognition" to PropertyDisclosureRule("recognition", mapOf(DisclosureLevel.SUMMARY to DisclosureValueProjection.Keep)),
            "identity" to PropertyDisclosureRule("identity", mapOf(DisclosureLevel.DETAILED to DisclosureValueProjection.Keep)),
            "interpretation" to PropertyDisclosureRule("interpretation", mapOf(DisclosureLevel.SUMMARY to DisclosureValueProjection.Keep)),
            "exact" to PropertyDisclosureRule("exact", mapOf(
                DisclosureLevel.QUALITATIVE to DisclosureValueProjection.Replace("qualitative-low"),
                DisclosureLevel.APPROXIMATE to DisclosureValueProjection.Approximate("about-fifteen"),
                DisclosureLevel.RANGE to DisclosureValueProjection.Range(10.0, 20.0),
                DisclosureLevel.DISCLOSE_FULL to DisclosureValueProjection.Keep
            ))
        )
    )

    private fun pipeline(
        c: PerceptionContext = context(),
        s: PerceptionSignal = signal(),
        p: DisclosurePolicy = policy(),
        level: DisclosureLevel = DisclosureLevel.DISCLOSE_FULL
    ): Triple<PerceptionDecision, RecognitionDecision, DisclosureProjection> {
        val perception = resolver.evaluate(PerceptionRequest(c, s))
        val recognition = resolver.recognize(c, perception)
        val interpretation = resolver.interpret(c, perception)
        return Triple(perception, recognition, disclosure.resolve(perception, recognition, interpretation, p, level))
    }

    @Test fun noCompatibleCapabilityMeansNoDetection() {
        val f = pc()
        val c = context(f, listOf(capability(f, channel = "OTHER_CHANNEL")))
        val d = resolver.evaluate(PerceptionRequest(c, signal()))
        assertEquals(PerceptionResultState.NO_COMPATIBLE_CAPABILITY, d.state)
        assertFalse(d.detected)
    }

    @Test fun compatibleCapabilityWithInsufficientSignalMeansNoDetection() {
        val f = pc()
        val c = context(f, listOf(capability(f, minimum = 0.80)))
        val d = resolver.evaluate(PerceptionRequest(c, signal(quality = 0.40)))
        assertEquals(PerceptionResultState.INSUFFICIENT_SIGNAL, d.state)
        assertFalse(d.detected)
    }

    @Test fun sufficientJustifiedSignalIsDetected() {
        val d = resolver.evaluate(PerceptionRequest(context(), signal(quality = 0.80)))
        assertEquals(PerceptionResultState.DETECTED, d.state)
        assertTrue(d.detected)
        assertEquals("PRESENTED-B", d.presentedSubject?.subjectUid)
    }

    @Test fun detectionDoesNotEqualRecognition() {
        val c = context(worldRules = rules(recognition = RecognitionRule("SIGNAL_KIND_A", "category", "recognition", "identity", 0.95, 0.97, 0.99)))
        val d = resolver.evaluate(PerceptionRequest(c, signal(quality = 0.80)))
        val r = resolver.recognize(c, d)
        assertTrue(d.detected)
        assertEquals(RecognitionState.NOT_RECOGNIZED, r.state)
    }

    @Test fun recognitionDoesNotDiscloseHiddenState() {
        val (d, r, projection) = pipeline(level = DisclosureLevel.SUMMARY)
        assertTrue(d.detected)
        assertTrue(r.state in setOf(RecognitionState.RECOGNIZED, RecognitionState.IDENTIFIED))
        assertFalse(r.attributes.containsKey("private_thought"))
        assertFalse(r.attributes.containsKey("hidden_stat"))
        assertFalse(projection.payload.containsKey("private_thought"))
        assertFalse(projection.payload.containsKey("hidden_stat"))
    }

    @Test fun identificationDoesNotExposePrivateThoughtsOrStats() {
        val c = context()
        val d = resolver.evaluate(PerceptionRequest(c, signal(quality = 0.95)))
        val r = resolver.recognize(c, d)
        assertEquals(RecognitionState.IDENTIFIED, r.state)
        assertEquals("PRESENTED-B", r.attributes["identity"])
        assertFalse(r.attributes.containsKey("private_thought"))
        assertFalse(r.attributes.containsKey("hidden_stat"))
    }

    @Test fun maskingAndInterferenceReduceQualityAndDisclosure() {
        val c = context(interference = listOf(PerceptionInterference("I", 0.20, 0.20, 0.10, DisclosureLevel.CATEGORY_ONLY)))
        val d = resolver.evaluate(PerceptionRequest(c, signal(quality = 0.90)))
        val r = resolver.recognize(c, d)
        val i = resolver.interpret(c, d)
        val p = disclosure.resolve(d, r, i, policy(), DisclosureLevel.DISCLOSE_FULL)
        assertEquals(0.70, d.effectiveQuality, 0.0001)
        assertEquals(DisclosureLevel.CATEGORY_ONLY, p.decision.level)
        assertTrue(p.payload.containsKey("category"))
        assertFalse(p.payload.containsKey("identity"))
        assertTrue((d.uncertainty?.confidence ?: 1.0) < signal().uncertainty.confidence)
    }

    @Test fun falseOrDecoySignalCanProduceFalseInterpretationWithoutFactCorrection() {
        val c = context()
        val decoy = signal(evidence = signal().evidence + ("pattern" to "decoy") + ("identity" to "DECOY-B"), presentedUid = "DECOY-B")
        val d = resolver.evaluate(PerceptionRequest(c, decoy))
        val r = resolver.recognize(c, d)
        val i = resolver.interpret(c, d)
        assertEquals("DECOY-B", r.attributes["identity"])
        assertEquals("supported-false-conclusion", i.payload["interpretation"])
        assertFalse(i.payload.values.contains("OBJECTIVE-A"))
    }

    @Test fun hiddenObjectiveIdentityNeverSilentlyCorrectsPresentedIdentity() {
        val c = context()
        val d = resolver.evaluate(PerceptionRequest(c, signal(presentedUid = "APPEARS-B")))
        val r = resolver.recognize(c, d)
        assertEquals("PRESENTED-B", r.attributes["identity"])
        assertEquals("APPEARS-B", r.presentedSubject?.subjectUid)
        assertFalse(r.attributes.values.contains("OBJECTIVE-A"))
    }

    @Test fun expertiseImprovesInterpretationButNotCapabilityOrHiddenTruth() {
        val f = pc()
        val weakSignal = signal(quality = 0.50)
        val noExpert = context(f, listOf(capability(f)), rules())
        val d1 = resolver.evaluate(PerceptionRequest(noExpert, weakSignal))
        assertEquals(InterpretationState.NOT_AVAILABLE, resolver.interpret(noExpert, d1).state)

        val holder = f.trusted.cognitionHolders.single()
        val profile = ExpertiseProfile(campaign, holder, "DOMAIN_A", 100L, 0.60, 1L)
        val withExpert = context(f, listOf(capability(f)), rules(), expertise = listOf(Phase38ExpertiseBridge.from(profile)))
        val d2 = resolver.evaluate(PerceptionRequest(withExpert, weakSignal))
        val interpreted = resolver.interpret(withExpert, d2)
        assertEquals(InterpretationState.INTERPRETED, interpreted.state)
        assertFalse(interpreted.payload.values.contains("OBJECTIVE-A"))

        val noCapability = context(f, emptyList(), rules(), expertise = listOf(Phase38ExpertiseBridge.from(profile)))
        assertEquals(PerceptionResultState.NO_COMPATIBLE_CAPABILITY, resolver.evaluate(PerceptionRequest(noCapability, weakSignal)).state)
    }

    @Test fun partialDisclosurePhysicallyRemovesExactHiddenValues() {
        val (_, _, projection) = pipeline(level = DisclosureLevel.QUALITATIVE)
        assertEquals("qualitative-low", projection.payload["exact"])
        assertFalse(projection.payload.values.contains(17))
        assertFalse(projection.payload.containsKey("identity"))
        assertFalse(projection.payload.containsKey("gm_internal"))
    }

    @Test fun rangeAndApproximateDisclosurePreserveUncertainty() {
        val (_, _, range) = pipeline(level = DisclosureLevel.RANGE)
        assertEquals(DisclosedRange(10.0, 20.0), range.payload["exact"])
        assertEquals(10.0, range.uncertainty?.minimum ?: -1.0, 0.0)
        assertEquals(20.0, range.uncertainty?.maximum ?: -1.0, 0.0)
        val (_, _, approximate) = pipeline(level = DisclosureLevel.APPROXIMATE)
        assertEquals(ApproximateDisclosure("about-fifteen"), approximate.payload["exact"])
        assertNotEquals(17, approximate.payload["exact"])
    }

    @Test fun unknownSignalKindFailsClosedAsCorruption() {
        val d = resolver.evaluate(PerceptionRequest(context(), signal(kind = "UNKNOWN_KIND")))
        assertEquals(PerceptionResultState.CORRUPTION, d.state)
        assertEquals(DisclosureLevel.DENY, d.maximumDisclosure)
    }

    @Test fun malformedCapabilityFailsClosedAsCorruption() {
        val f = pc()
        val malformed = capability(f, minimum = -1.0)
        val d = resolver.evaluate(PerceptionRequest(context(f, listOf(malformed)), signal()))
        assertEquals(PerceptionResultState.CORRUPTION, d.state)
        assertEquals("MALFORMED_CAPABILITY", d.reasonCode)
    }

    @Test fun campaignIsolationRejectsCrossCampaignSignal() {
        val other = signal(campaignUid = "C2", presentedUid = "SAME-UID")
        assertTrue(runCatching { resolver.evaluate(PerceptionRequest(context(), other)) }.exceptionOrNull() is VisibilityAuthorityFailure.CrossCampaign)
    }

    @Test fun observerCannotInheritAnotherObserversCapability() {
        val a = pc("PC-A")
        val b = pc("PC-B")
        val onlyB = capability(b, owner = b.trusted.principal)
        val d = resolver.evaluate(PerceptionRequest(context(a, listOf(onlyB)), signal()))
        assertEquals(PerceptionResultState.NO_COMPATIBLE_CAPABILITY, d.state)
    }

    @Test fun pcAPerceptionDoesNotBecomePcBPerception() {
        val a = pc("PC-A")
        val b = pc("PC-B")
        val capA = capability(a)
        val seenA = resolver.evaluate(PerceptionRequest(context(a, listOf(capA)), signal()))
        val seenB = resolver.evaluate(PerceptionRequest(context(b, listOf(capA)), signal()))
        assertTrue(seenA.detected)
        assertFalse(seenB.detected)
    }

    @Test fun playerVisibleNarrativeDisclosureDoesNotCreatePcKnowledge() {
        val player = Phase38TrustedTestAuthority.player(campaign)
        val c = context(player, listOf(capability(player)))
        val d = resolver.evaluate(PerceptionRequest(c, signal()))
        val r = resolver.recognize(c, d)
        val i = resolver.interpret(c, d)
        val p = disclosure.resolve(d, r, i, policy(), DisclosureLevel.SUMMARY)
        assertEquals(ProjectionDataState.DISCLOSED, p.decision.dataState)
        assertTrue(player.trusted.cognitionHolders.isEmpty())
        assertTrue(pc("PC-A").trusted.cognitionHolders.isNotEmpty())
        assertTrue(Phase38PerceptionAcquisitionBridge.opportunity(c, d, CommittedObservationRef(campaign, "E", "EV")).eligibleHolders.isEmpty())
    }

    @Test fun perceptionAloneDoesNotCallOrCreatePhase37Acquisition() {
        val c = context()
        val d = resolver.evaluate(PerceptionRequest(c, signal()))
        assertTrue(d.detected)
        val source = source("app/src/main/java/com/rpgos/app/Phase38PerceptionDisclosure.kt")
        assertFalse(source.contains("KnowledgeStore("))
        assertFalse(source.contains("KnowledgeAcquisitionChange("))
        assertFalse(source.contains("stageRecorded("))
    }

    @Test fun committedObservationCanFeedExistingPhase37AcquisitionAuthority() {
        val c = context()
        val d = resolver.evaluate(PerceptionRequest(c, signal()))
        val opportunity = Phase38PerceptionAcquisitionBridge.opportunity(c, d, CommittedObservationRef(campaign, "EVENT-1", "EVIDENCE-1"))
        val holder = opportunity.eligibleHolders.single()
        val change = KnowledgeAcquisitionChange(
            KnowledgeClaim("CLAIM-1", "SUBJECT", "S", "PREDICATE", "observed-value", domainUid = "DOMAIN_A"),
            KnowledgeAcquisitionSpec(
                "ACQ-1", holder, KnowledgeAcquisitionMethods.DIRECT_OBSERVATION, KnowledgeScope.PERSONAL,
                KnowledgeEpistemicState.BELIEVED, KnowledgeQuality(0.8, 0.6, 0.5, 0.8, 1, 50L)
            )
        )
        assertEquals(holder, change.acquisition.holder)
        assertEquals("EVENT-1", opportunity.committedObservation.eventUid)
    }

    @Test fun organizationSharedObserverUsesTrustedCognitionMapping() {
        val principal = VisibilityPrincipalRef("SENSOR_NETWORK", "ORG-NET")
        val audience = AudienceContext(campaign, AudienceKinds.WORLD_ACTOR, principal)
        val organizationHolder = KnowledgeHolderRef(KnowledgeHolderKinds.ORGANIZATION, "ORG-1", campaign)
        val trusted = requireNotNull(Phase38RuntimeAuthority.application(audience, cognitionResolver = TrustedCognitionResolver { requested, p ->
            if (requested == campaign && p == principal) setOf(organizationHolder) else emptySet()
        }))
        val fixture = TrustedAudienceFixture(audience, trusted)
        val c = context(fixture, listOf(capability(fixture)))
        val d = resolver.evaluate(PerceptionRequest(c, signal()))
        val opportunity = Phase38PerceptionAcquisitionBridge.opportunity(c, d, CommittedObservationRef(campaign, "E", "V"))
        assertEquals(setOf(organizationHolder), opportunity.eligibleHolders)
    }

    @Test fun arbitraryWorldPackSignalAndChannelWorkWithoutCoreChange() {
        val f = pc()
        val kind = "PACK_DEFINED_SIGNAL_73"
        val channel = "PACK_DEFINED_CHANNEL_91"
        val world = rules(kind, setOf(channel), recognition = RecognitionRule(kind, "category", null, null, 0.2, 0.9, 1.0), interpretation = null)
        val cap = capability(f, channel = channel)
        val s = signal(kind = kind)
        val c = context(f, listOf(cap), world)
        assertEquals(PerceptionResultState.DETECTED, resolver.evaluate(PerceptionRequest(c, s)).state)
    }

    @Test fun ordinaryObserverPlaceholderUsesSameGenericPipeline() {
        assertGenericObserver("ORDINARY_OBSERVER", "ORDINARY_SIGNAL", "ORDINARY_CHANNEL")
    }

    @Test fun technologicalObserverPlaceholderUsesSameGenericPipeline() {
        assertGenericObserver("DEVICE_OBSERVER", "DEVICE_SIGNAL", "DEVICE_CHANNEL")
    }

    @Test fun supernaturalObserverPlaceholderUsesSameGenericPipeline() {
        assertGenericObserver("EXTRA_OBSERVER", "EXTRA_SIGNAL", "EXTRA_CHANNEL")
    }

    @Test fun collectiveObserverWorksThroughTrustedPrincipalAndSameResolver() {
        assertGenericObserver("COLLECTIVE_OBSERVER", "COLLECTIVE_SIGNAL", "COLLECTIVE_CHANNEL")
    }

    @Test fun coreHasNoWorldSpecificPerceptionBranches() {
        val code = source("app/src/main/java/com/rpgos/app/Phase38PerceptionDisclosure.kt").lowercase()
        val banned = listOf("naruto", "shinobi", "ninja", "chakra", "bleach", "reiatsu", "witcher", "wizard", "dragon", "jedi", "radar", "telepathy", "magic sight")
        banned.forEach { assertFalse("Slice D core contains world-specific authority token $it", code.contains(it)) }
    }

    @Test fun downstreamDisclosureReductionRemovesDataAndCannotReconstructIt() {
        val (_, _, full) = pipeline(level = DisclosureLevel.DISCLOSE_FULL)
        assertEquals(17, full.payload["exact"])
        val range = disclosure.reduce(full, policy(), DisclosureLevel.RANGE)
        assertEquals(DisclosedRange(10.0, 20.0), range.payload["exact"])
        assertFalse(range.payload.values.contains(17))
        val qualitative = disclosure.reduce(range, policy(), DisclosureLevel.QUALITATIVE)
        assertEquals("qualitative-low", qualitative.payload["exact"])
        assertFalse(qualitative.payload.values.contains(17))
    }

    @Test fun downstreamEscalationIsRejected() {
        val (_, _, range) = pipeline(level = DisclosureLevel.RANGE)
        assertTrue(runCatching { disclosure.reduce(range, policy(), DisclosureLevel.DISCLOSE_FULL) }.exceptionOrNull() is VisibilityAuthorityFailure.Escalation)
    }

    @Test fun hiddenGmInternalFactIsAbsentFromPlayerPerceptionPayload() {
        val (_, _, projection) = pipeline(level = DisclosureLevel.DISCLOSE_FULL)
        assertFalse(projection.payload.containsKey("gm_internal"))
        assertFalse(projection.presentationText().contains("GM-SECRET"))
    }

    @Test fun visualGenerationReceivesOnlyDisclosedProjectionFields() {
        val player = Phase38TrustedTestAuthority.player(campaign)
        val c = context(player, listOf(capability(player)))
        val d = resolver.evaluate(PerceptionRequest(c, signal()))
        val r = resolver.recognize(c, d)
        val i = resolver.interpret(c, d)
        val projection = disclosure.resolve(d, r, i, policy(), DisclosureLevel.SUMMARY)
        val prompt = projection.presentationText()
        val env = VisibilityAuthorityService().envelope(player.audience, PurposeContext(campaign, VisibilityPurposeKinds.SCENE_VISUALIZATION)).reduceTo(projection.decision.level)
        val auth = Phase38VisualAuthorization.authorize(env, VisibilityPurposeKinds.SCENE_VISUALIZATION, projection.subject.subjectKindUid, projection.subject.subjectUid, prompt, requestUid = "GEN-D-1", payloadDisclosure = projection.decision.level)
        val request = ImageGenerationRequest("scene", "disclosed", prompt, authorization = auth)
        assertFalse(request.prompt.contains("GM-SECRET"))
        assertFalse(request.prompt.contains("PRIVATE-THOUGHT"))
        auth.requireRequest(campaign, VisibilityPurposeKinds.SCENE_VISUALIZATION, request.prompt)
    }

    @Test fun imageEditSourceAndRequestBindingStillPassesAndRejectsSubstitution() {
        val player = Phase38TrustedTestAuthority.player(campaign)
        val payload = "only disclosed edit instruction"
        val sourceDigest = Phase38VisualAuthorization.digestBytes("source-image".toByteArray())
        val env = VisibilityAuthorityService().envelope(player.audience, PurposeContext(campaign, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION))
        val auth = Phase38VisualAuthorization.authorize(
            env, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION, "VISUAL", "V1", payload,
            VisualInputOrigins.USER_STANDALONE, requestUid = "EDIT-D-1", sourceVisualUid = "V1", sourceImageSha256 = sourceDigest
        )
        val semantic = VisualSemanticRequest(
            campaign, AudienceKinds.PLAYER, "HUMAN_PLAYER", VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
            "VISUAL", "V1", "EDIT-D-1", VisualRequestKinds.EDIT, payload,
            sourceVisualUid = "V1", sourceImageSha256 = sourceDigest
        )
        auth.requireRequest(semantic)
        assertTrue(runCatching { auth.requireRequest(semantic.copy(sourceVisualUid = "V2")) }.isFailure)
    }

    @Test fun fullSemanticRequestBindingRemainsIntact() {
        val player = Phase38TrustedTestAuthority.player(campaign)
        val payload = "disclosed payload"
        val env = VisibilityAuthorityService().envelope(player.audience, PurposeContext(campaign, VisibilityPurposeKinds.CHARACTER_VISUALIZATION))
        val auth = Phase38VisualAuthorization.authorize(
            env, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, "OBSERVED_SUBJECT", "PRESENTED-B", payload,
            requestUid = "REQ-D-SEMANTIC", relatedEntityUid = "PRESENTED-B"
        )
        val request = VisualSemanticRequest(
            campaign, AudienceKinds.PLAYER, "HUMAN_PLAYER", VisibilityPurposeKinds.CHARACTER_VISUALIZATION,
            "OBSERVED_SUBJECT", "PRESENTED-B", "REQ-D-SEMANTIC", VisualRequestKinds.GENERATE, payload,
            relatedEntityUid = "PRESENTED-B"
        )
        auth.requireRequest(request)
        assertTrue(runCatching { auth.requireRequest(request.copy(subjectUid = "OTHER")) }.isFailure)
        assertTrue(runCatching { auth.requireRequest(request.copy(promptOrInstruction = "substitution")) }.isFailure)
    }

    @Test fun formalAccessAndPerceptionRemainSeparate() {
        val authorizedArchive = AuthorizationDecision(true, "AUTHORIZED")
        val unread = resolver.evaluate(PerceptionRequest(context(), null))
        assertTrue(authorizedArchive.authorized)
        assertEquals(PerceptionResultState.NO_DATA, unread.state)

        val deniedFormalAccess = AuthorizationDecision(false, "DENIED")
        val openSignal = resolver.evaluate(PerceptionRequest(context(), signal()))
        assertFalse(deniedFormalAccess.authorized)
        assertTrue(openSignal.detected)
    }

    @Test fun typedProtectedAndPerceptionStatesRemainDistinct() {
        assertNotEquals(ProjectionDataState.DENIED, ProjectionDataState.NO_DATA)
        assertNotEquals(ProjectionDataState.NO_DATA, ProjectionDataState.NOT_DISCLOSED)
        assertNotEquals(ProjectionDataState.NOT_DISCLOSED, ProjectionDataState.UNKNOWN)
        assertNotEquals(ProjectionDataState.UNKNOWN, ProjectionDataState.CORRUPTION)
        assertNotEquals(PerceptionResultState.NO_COMPATIBLE_CAPABILITY, PerceptionResultState.INSUFFICIENT_SIGNAL)
        assertNotEquals(RecognitionState.NOT_RECOGNIZED, RecognitionState.IDENTIFIED)
    }

    @Test fun malformedDisclosurePolicyFailsClosedAsCorruption() {
        val c = context()
        val d = resolver.evaluate(PerceptionRequest(c, signal()))
        val r = resolver.recognize(c, d)
        val i = resolver.interpret(c, d)
        val malformed = DisclosurePolicy(campaign, "BAD", DisclosureLevel.DENY, emptyMap())
        val p = disclosure.resolve(d, r, i, malformed, DisclosureLevel.DISCLOSE_FULL)
        assertEquals(ProjectionDataState.CORRUPTION, p.decision.dataState)
        assertTrue(p.payload.isEmpty())
    }

    @Test fun batchEvaluationIsOnDemandAndObserverScoped() {
        val c = context()
        val out = resolver.evaluateMany(c, listOf(signal(), signal(quality = 0.10).copy(ref = PerceptionSignalRef(campaign, "SIG-2"))))
        assertEquals(2, out.size)
        assertEquals(PerceptionResultState.DETECTED, out[0].state)
        assertEquals(PerceptionResultState.INSUFFICIENT_SIGNAL, out[1].state)
        assertTrue(out.all { it.observer == c.trustedObserver.principal })
    }

    @Test fun decisionBoundaryConsumesDisclosureRatherThanObjectiveFact() {
        val (_, _, projection) = pipeline(level = DisclosureLevel.CATEGORY_ONLY)
        val actorPayload = PerceptionUseBoundary.actorDecisionPayload(projection)
        assertEquals(projection.presentationPayload(), actorPayload)
        assertFalse(actorPayload.containsKey("gm_internal"))
        assertEquals("OBJECTIVE_RESOLUTION_MAY_USE_FACT", PerceptionUseBoundary.OBJECTIVE_RESOLUTION_MAY_USE_FACT)
        assertEquals("ACTOR_VOLITION_REQUIRES_DISCLOSED_OBSERVATION", PerceptionUseBoundary.ACTOR_VOLITION_REQUIRES_DISCLOSED_OBSERVATION)
    }

    @Test fun sameTextualUidAcrossCampaignsDoesNotAlias() {
        val c2Signal = signal(campaignUid = "C2", presentedUid = "PRESENTED-B")
        assertEquals("PRESENTED-B", c2Signal.presentedSubject?.subjectUid)
        assertTrue(runCatching { resolver.evaluate(PerceptionRequest(context(), c2Signal)) }.isFailure)
    }

    @Test fun propertyLevelDisclosureCanRevealLocationClassWithoutIdentity() {
        val c = context()
        val s = signal(evidence = signal().evidence + ("location" to "ZONE-1"))
        val d = resolver.evaluate(PerceptionRequest(c, s))
        val r = resolver.recognize(c, d)
        val i = resolver.interpret(c, d)
        val propertyPolicy = DisclosurePolicy(campaign, "PROPERTY", DisclosureLevel.DISCLOSE_FULL, mapOf(
            "category" to PropertyDisclosureRule("category", mapOf(DisclosureLevel.CATEGORY_ONLY to DisclosureValueProjection.Keep)),
            "location" to PropertyDisclosureRule("location", mapOf(DisclosureLevel.CATEGORY_ONLY to DisclosureValueProjection.Keep))
        ))
        val p = disclosure.resolve(d, r, i, propertyPolicy, DisclosureLevel.CATEGORY_ONLY)
        assertEquals("ZONE-1", p.payload["location"])
        assertEquals("presented-category", p.payload["category"])
        assertFalse(p.payload.containsKey("identity"))
    }

    private fun assertGenericObserver(principalKind: String, signalKind: String, channel: String) {
        val principal = VisibilityPrincipalRef(principalKind, "OBSERVER-1")
        val audience = AudienceContext(campaign, AudienceKinds.WORLD_ACTOR, principal)
        val trusted = requireNotNull(Phase38RuntimeAuthority.application(audience))
        val fixture = TrustedAudienceFixture(audience, trusted)
        val world = rules(signalKind, setOf(channel), recognition = null, interpretation = null)
        val cap = capability(fixture, channel = channel)
        val c = context(fixture, listOf(cap), world)
        val d = resolver.evaluate(PerceptionRequest(c, signal(kind = signalKind)))
        assertEquals(PerceptionResultState.DETECTED, d.state)
    }

    private fun repoRoot(): File {
        var f = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            if (File(f, "app/src/main/java").isDirectory) return f
            f = f.parentFile ?: return@repeat
        }
        error("repo root not found")
    }

    private fun source(path: String) = File(repoRoot(), path).readText()
}
