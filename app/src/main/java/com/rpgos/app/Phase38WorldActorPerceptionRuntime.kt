package com.rpgos.app

/**
 * Production-owned, ephemeral Slice-D perception feed for WORLD_ACTOR reasoning.
 *
 * The runtime stores only authority-issued observer capabilities and evidence-facing signals.
 * Objective world rows merely nominate a signal ref; their hidden/raw fields are never copied into
 * the actor ContextBundle. Callers of CampaignRepository.buildContext cannot inject either source.
 */
internal class Phase38WorldActorPerceptionRuntime {
    companion object {
        const val WORLD_EVENT_SIGNAL_KIND = "WORLD_EVENT_OBSERVATION"
        const val WORLD_EVENT_CHANNEL = "WORLD_EVENT_CHANNEL"

        fun worldEventSignalRef(campaignUid: String, event: WorldEventItem): PerceptionSignalRef {
            fun part(value: String) = "${value.length}:$value"
            return PerceptionSignalRef(
                campaignUid,
                "WORLD_EVENT|${part(event.name)}|${part(event.status)}|${part(event.summary)}"
            )
        }
    }

    private val signals = linkedMapOf<String, MutableMap<String, PerceptionSignal>>()
    private val capabilities = linkedMapOf<String, MutableMap<VisibilityPrincipalRef, MutableList<PerceptionCapability>>>()

    private val rules = PerceptionWorldRules(
        rulesUid = "RPGOS-P38-WORLD-ACTOR-EVENT-RULES-1",
        compatibleChannelsBySignalKind = mapOf(WORLD_EVENT_SIGNAL_KIND to setOf(WORLD_EVENT_CHANNEL))
    )

    private fun policy(campaignUid: String) = DisclosurePolicy(
        campaignUid = campaignUid,
        policyUid = "RPGOS-P38-WORLD-ACTOR-EVENT-DISCLOSURE-1",
        maximumLevel = DisclosureLevel.DISCLOSE_FULL,
        properties = listOf("name", "status", "summary").associateWith { property ->
            PropertyDisclosureRule(
                property,
                mapOf(
                    DisclosureLevel.SUMMARY to DisclosureValueProjection.Keep,
                    DisclosureLevel.DISCLOSE_FULL to DisclosureValueProjection.Keep
                )
            )
        }
    )

    @Synchronized
    internal fun issueWorldEventSignal(
        campaignUid: String,
        event: WorldEventItem,
        evidence: Map<String, Any?>,
        quality: Double = 1.0,
        uncertainty: PerceptionUncertainty = PerceptionUncertainty(1.0, 1.0, 1.0),
        presentedSubject: VisibilitySubjectRef? = null
    ): PerceptionSignal {
        val ref = worldEventSignalRef(campaignUid, event)
        val signal = Phase38PerceptionRuntimeAuthority.issueSignal(
            campaignUid = campaignUid,
            ref = ref,
            signalKindUid = WORLD_EVENT_SIGNAL_KIND,
            quality = quality,
            evidence = evidence.toMap(),
            uncertainty = uncertainty,
            presentedSubject = presentedSubject,
            observationMetadata = mapOf("source" to "PRODUCTION_WORLD_EVENT_RUNTIME")
        )
        signals.getOrPut(campaignUid) { linkedMapOf() }[ref.signalUid] = signal
        return signal
    }

    @Synchronized
    internal fun issueWorldEventCapability(
        trusted: TrustedPrincipalContext,
        minimumDetectionQuality: Double = 0.0,
        maximumDisclosure: DisclosureLevel = DisclosureLevel.DISCLOSE_FULL,
        capabilityUid: String = "WORLD_EVENT:${trusted.principal.kindUid}:${trusted.principal.uid}"
    ): PerceptionCapability {
        val capability = Phase38PerceptionRuntimeAuthority.issueCapability(
            trusted = trusted,
            ref = PerceptionCapabilityRef(trusted.campaignUid, capabilityUid),
            observer = trusted.principal,
            channelUids = setOf(WORLD_EVENT_CHANNEL),
            minimumDetectionQuality = minimumDetectionQuality,
            maximumDisclosure = maximumDisclosure
        )
        val campaign = capabilities.getOrPut(trusted.campaignUid) { linkedMapOf() }
        campaign.getOrPut(trusted.principal) { mutableListOf() }.apply {
            removeAll { it.ref == capability.ref }
            add(capability)
        }
        return capability
    }

    @Synchronized
    internal fun clearCampaign(campaignUid: String) {
        signals.remove(campaignUid)
        capabilities.remove(campaignUid)
    }

    private fun gateway(trusted: TrustedPrincipalContext): PerceptionRuntimeGateway = PerceptionRuntimeGateway(
        principalResolver = TrustedPrincipalResolver { audience ->
            if (
                audience.campaignUid == trusted.campaignUid &&
                audience.audienceKindUid == trusted.audienceKindUid &&
                audience.principal == trusted.principal
            ) trusted else null
        },
        signalSource = TrustedPerceptionSignalSource { campaignUid, signalRef -> synchronized(this) {
            signals[campaignUid]?.get(signalRef.signalUid)
        } },
        capabilitySource = TrustedPerceptionCapabilitySource { campaignUid, principal -> synchronized(this) {
            capabilities[campaignUid]?.get(principal)?.toList().orEmpty()
        } }
    )

    internal fun projectWorldEvent(
        audience: AudienceContext,
        trusted: TrustedPrincipalContext,
        objectiveEvent: WorldEventItem
    ): DisclosureProjection {
        if (audience.campaignUid != trusted.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        require(audience.audienceKindUid == AudienceKinds.WORLD_ACTOR) { "RPGOS-P38-PERCEPTION:WORLD_ACTOR_REQUIRED" }
        val ref = worldEventSignalRef(audience.campaignUid, objectiveEvent)
        val perception = gateway(trusted).evaluate(audience, ref, rules)
        val effectiveRef = perception.signalRef ?: ref
        val recognition = RecognitionDecision(
            audience.campaignUid, trusted.principal, effectiveRef,
            RecognitionState.NOT_RECOGNIZED, "NO_RECOGNITION_RULE",
            presentedSubject = perception.presentedSubject
        )
        val interpretation = InterpretationResult(
            audience.campaignUid, trusted.principal, effectiveRef,
            InterpretationState.NOT_AVAILABLE, "NO_INTERPRETATION_RULE"
        )
        return DisclosureResolver().resolve(
            perception = perception,
            recognition = recognition,
            interpretation = interpretation,
            policy = policy(audience.campaignUid),
            requestedLevel = DisclosureLevel.DISCLOSE_FULL
        )
    }
}
