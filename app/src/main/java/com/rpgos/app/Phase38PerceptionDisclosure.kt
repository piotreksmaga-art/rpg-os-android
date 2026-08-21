package com.rpgos.app

/**
 * Phase 38 Slice D: derived, on-demand perception and disclosure.
 *
 * The resolver consumes observer-facing evidence only. It intentionally has no objective-identity
 * input and performs no persistence. Durable knowledge remains a Phase 37 acquisition concern.
 */
data class PerceptionSignalRef(val campaignUid: String, val signalUid: String) {
    init { require(campaignUid.isNotBlank() && signalUid.isNotBlank()) }
}

data class PerceptionCapabilityRef(val campaignUid: String, val capabilityUid: String) {
    init { require(campaignUid.isNotBlank() && capabilityUid.isNotBlank()) }
}

data class PerceptionUncertainty(
    val confidence: Double,
    val precision: Double,
    val completeness: Double,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val candidateUids: Set<String> = emptySet(),
    val observedOrder: Long? = null
) {
    init {
        listOf(confidence, precision, completeness).forEach { require(it.isFinite() && it in 0.0..1.0) }
        require((minimum == null) == (maximum == null))
        if (minimum != null && maximum != null) require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum)
        require(candidateUids.none { it.isBlank() })
        require(observedOrder == null || observedOrder >= 0L)
    }

    fun attenuate(confidencePenalty: Double, precisionPenalty: Double = 0.0): PerceptionUncertainty = copy(
        confidence = (confidence - confidencePenalty).coerceIn(0.0, 1.0),
        precision = (precision - precisionPenalty).coerceIn(0.0, 1.0)
    )
}

/** presentedSubject is evidence-facing identity/classification, never a hidden canonical origin. */
class PerceptionSignal private constructor(
    val campaignUid: String,
    val ref: PerceptionSignalRef,
    val signalKindUid: String,
    val quality: Double,
    val evidence: Map<String, Any?>,
    val uncertainty: PerceptionUncertainty,
    val presentedSubject: VisibilitySubjectRef? = null,
    val observationMetadata: Map<String, String> = emptyMap()
) {
    init {
        require(campaignUid.isNotBlank() && ref.campaignUid == campaignUid)
        require(signalKindUid.isNotBlank())
        require(quality.isFinite() && quality in 0.0..1.0)
        require(evidence.keys.none { it.isBlank() } && observationMetadata.keys.none { it.isBlank() })
        presentedSubject?.let { require(it.campaignUid == campaignUid) }
    }
    companion object {
        internal fun issue(
            campaignUid:String, ref:PerceptionSignalRef, signalKindUid:String, quality:Double,
            evidence:Map<String,Any?>, uncertainty:PerceptionUncertainty,
            presentedSubject:VisibilitySubjectRef?, observationMetadata:Map<String,String>
        ) = PerceptionSignal(campaignUid,ref,signalKindUid,quality,evidence,uncertainty,presentedSubject,observationMetadata)
    }
}

/** Thresholds are data supplied by the world rules. Core never assigns meaning to channel UIDs. */
class PerceptionCapability private constructor(
    val campaignUid: String,
    val ref: PerceptionCapabilityRef,
    val observer: VisibilityPrincipalRef,
    val channelUids: Set<String>,
    val minimumDetectionQuality: Double,
    val maximumDisclosure: DisclosureLevel = DisclosureLevel.DISCLOSE_FULL
) {
    init {
        require(campaignUid.isNotBlank() && ref.campaignUid == campaignUid)
        require(observer.kindUid.isNotBlank() && observer.uid.isNotBlank())
    }

    internal fun isWellFormed(): Boolean =
        channelUids.isNotEmpty() && channelUids.none { it.isBlank() } &&
            minimumDetectionQuality.isFinite() && minimumDetectionQuality in 0.0..1.0 &&
            maximumDisclosure != DisclosureLevel.DENY
    companion object {
        internal fun issue(
            campaignUid:String, ref:PerceptionCapabilityRef, observer:VisibilityPrincipalRef,
            channelUids:Set<String>, minimumDetectionQuality:Double, maximumDisclosure:DisclosureLevel
        ) = PerceptionCapability(campaignUid,ref,observer,channelUids,minimumDetectionQuality,maximumDisclosure)
    }
}

/** Runtime-owned issuer. Callers can request observation but cannot manufacture signal/capability authority. */
internal object Phase38PerceptionRuntimeAuthority {
    fun issueSignal(
        campaignUid:String, ref:PerceptionSignalRef, signalKindUid:String, quality:Double,
        evidence:Map<String,Any?>, uncertainty:PerceptionUncertainty,
        presentedSubject:VisibilitySubjectRef?=null, observationMetadata:Map<String,String> = emptyMap()
    ):PerceptionSignal = PerceptionSignal.issue(
        campaignUid,ref,signalKindUid,quality,evidence.toMap(),uncertainty,presentedSubject,observationMetadata.toMap()
    )
    fun issueCapability(
        trusted:TrustedPrincipalContext, ref:PerceptionCapabilityRef, observer:VisibilityPrincipalRef,
        channelUids:Set<String>, minimumDetectionQuality:Double, maximumDisclosure:DisclosureLevel
    ):PerceptionCapability {
        require(ref.campaignUid==trusted.campaignUid&&observer==trusted.principal){"RPGOS-P38-PERCEPTION:CAPABILITY_AUTHORITY_MISMATCH"}
        return PerceptionCapability.issue(trusted.campaignUid,ref,observer,channelUids.toSet(),minimumDetectionQuality,maximumDisclosure)
    }
}

data class PerceptionInterference(
    val interferenceUid: String,
    val qualityPenalty: Double = 0.0,
    val confidencePenalty: Double = 0.0,
    val precisionPenalty: Double = 0.0,
    val disclosureCeiling: DisclosureLevel? = null
) {
    init {
        require(interferenceUid.isNotBlank())
        require(qualityPenalty.isFinite() && qualityPenalty >= 0.0)
        require(confidencePenalty.isFinite() && confidencePenalty >= 0.0)
        require(precisionPenalty.isFinite() && precisionPenalty >= 0.0)
    }
}

data class PerceptionExpertise internal constructor(
    val campaignUid: String,
    val holder: KnowledgeHolderRef,
    val domainUid: String,
    val interpretationReliability: Double
) {
    init {
        require(campaignUid.isNotBlank() && holder.campaignUid == campaignUid && domainUid.isNotBlank())
        require(interpretationReliability.isFinite() && interpretationReliability in 0.0..1.0)
    }
}

object Phase38ExpertiseBridge {
    fun from(profile: ExpertiseProfile): PerceptionExpertise {
        require(profile.holder.campaignUid == profile.campaignUid) { "RPGOS-P38-PERCEPTION:EXPERTISE_CAMPAIGN_REQUIRED" }
        return PerceptionExpertise(profile.campaignUid, profile.holder, profile.domainUid, profile.interpretationReliability)
    }
}

data class RecognitionRule(
    val signalKindUid: String,
    val classificationPropertyUid: String? = null,
    val recognitionPropertyUid: String? = null,
    val identityPropertyUid: String? = null,
    val classificationThreshold: Double = 0.0,
    val recognitionThreshold: Double = 1.0,
    val identificationThreshold: Double = 1.0
) {
    internal fun isWellFormed(): Boolean {
        val keys = listOfNotNull(classificationPropertyUid, recognitionPropertyUid, identityPropertyUid)
        return signalKindUid.isNotBlank() && keys.none { it.isBlank() } &&
            listOf(classificationThreshold, recognitionThreshold, identificationThreshold).all { it.isFinite() && it in 0.0..1.0 } &&
            classificationThreshold <= recognitionThreshold && recognitionThreshold <= identificationThreshold
    }
}

data class InterpretationRule(
    val signalKindUid: String,
    val inputPropertyUid: String,
    val outputPropertyUid: String,
    val outcomes: Map<String, Any?>,
    val minimumQuality: Double,
    val expertiseDomainUid: String? = null,
    val expertiseWeight: Double = 0.0
) {
    internal fun isWellFormed(): Boolean =
        signalKindUid.isNotBlank() && inputPropertyUid.isNotBlank() && outputPropertyUid.isNotBlank() &&
            outcomes.keys.none { it.isBlank() } && minimumQuality.isFinite() && minimumQuality in 0.0..1.0 &&
            expertiseDomainUid?.isBlank() != true && expertiseWeight.isFinite() && expertiseWeight in 0.0..1.0
}

data class PerceptionWorldRules(
    val rulesUid: String,
    val compatibleChannelsBySignalKind: Map<String, Set<String>>,
    val recognitionRules: Map<String, RecognitionRule> = emptyMap(),
    val interpretationRules: Map<String, InterpretationRule> = emptyMap()
) {
    init { require(rulesUid.isNotBlank()) }

    internal fun isKnownSignalKind(signalKindUid: String): Boolean = signalKindUid in compatibleChannelsBySignalKind
    internal fun isWellFormed(): Boolean =
        compatibleChannelsBySignalKind.isNotEmpty() &&
            compatibleChannelsBySignalKind.all { (kind, channels) -> kind.isNotBlank() && channels.isNotEmpty() && channels.none { it.isBlank() } } &&
            recognitionRules.all { (kind, rule) -> kind == rule.signalKindUid && rule.isWellFormed() } &&
            interpretationRules.all { (kind, rule) -> kind == rule.signalKindUid && rule.isWellFormed() }
}

data class PerceptionContext internal constructor(
    val campaignUid: String,
    val trustedObserver: TrustedPrincipalContext,
    val capabilities: List<PerceptionCapability>,
    val rules: PerceptionWorldRules,
    val interference: List<PerceptionInterference> = emptyList(),
    val expertise: List<PerceptionExpertise> = emptyList()
) {
    init { require(campaignUid.isNotBlank() && trustedObserver.campaignUid == campaignUid) }
}

data class PerceptionRequest(val context: PerceptionContext, val signal: PerceptionSignal?)

enum class PerceptionResultState {
    DETECTED, NO_COMPATIBLE_CAPABILITY, INSUFFICIENT_SIGNAL, NO_DATA, DENIED, UNKNOWN, CORRUPTION
}

enum class RecognitionState { NOT_RECOGNIZED, CLASSIFIED, RECOGNIZED, IDENTIFIED, UNKNOWN, CORRUPTION }
enum class InterpretationState { NOT_AVAILABLE, INTERPRETED, UNKNOWN, CORRUPTION }

data class PerceptionDecision(
    val campaignUid: String,
    val observer: VisibilityPrincipalRef,
    val signalRef: PerceptionSignalRef?,
    val signalKindUid: String?,
    val state: PerceptionResultState,
    val reasonCode: String,
    val effectiveQuality: Double = 0.0,
    val evidence: Map<String, Any?> = emptyMap(),
    val uncertainty: PerceptionUncertainty? = null,
    val presentedSubject: VisibilitySubjectRef? = null,
    val capabilityRef: PerceptionCapabilityRef? = null,
    val maximumDisclosure: DisclosureLevel = DisclosureLevel.DENY
) {
    val detected: Boolean get() = state == PerceptionResultState.DETECTED
}

data class RecognitionDecision(
    val campaignUid: String,
    val observer: VisibilityPrincipalRef,
    val signalRef: PerceptionSignalRef,
    val state: RecognitionState,
    val reasonCode: String,
    val attributes: Map<String, Any?> = emptyMap(),
    val presentedSubject: VisibilitySubjectRef? = null,
    val confidence: Double = 0.0
)

data class InterpretationResult(
    val campaignUid: String,
    val observer: VisibilityPrincipalRef,
    val signalRef: PerceptionSignalRef,
    val state: InterpretationState,
    val reasonCode: String,
    val payload: Map<String, Any?> = emptyMap(),
    val confidence: Double = 0.0
)

fun interface TrustedPerceptionSignalSource { fun signal(campaignUid:String,signalRef:PerceptionSignalRef):PerceptionSignal? }
fun interface TrustedPerceptionCapabilitySource { fun capabilities(campaignUid:String,principal:VisibilityPrincipalRef):List<PerceptionCapability> }

class PerceptionRuntimeGateway internal constructor(
    private val principalResolver:TrustedPrincipalResolver,
    private val signalSource:TrustedPerceptionSignalSource,
    private val capabilitySource:TrustedPerceptionCapabilitySource
){
    fun evaluate(audience:AudienceContext,signalRef:PerceptionSignalRef,rules:PerceptionWorldRules,interference:List<PerceptionInterference> = emptyList(),expertise:List<PerceptionExpertise> = emptyList()):PerceptionDecision{
        if(audience.campaignUid!=signalRef.campaignUid)throw VisibilityAuthorityFailure.CrossCampaign()
        val principal=requireNotNull(audience.principal){"RPGOS-P38-PERCEPTION:PRINCIPAL_REQUIRED"}
        val trusted=principalResolver.resolve(audience)?:return PerceptionDecision(audience.campaignUid,principal,signalRef,null,PerceptionResultState.DENIED,"TRUSTED_OBSERVER_REQUIRED")
        val signal=signalSource.signal(audience.campaignUid,signalRef)?:return PerceptionDecision(audience.campaignUid,principal,signalRef,null,PerceptionResultState.NO_DATA,"NO_SIGNAL")
        val capabilities=capabilitySource.capabilities(audience.campaignUid,principal)
        val context=PerceptionContext(audience.campaignUid,trusted,capabilities,rules,interference,expertise)
        return PerceptionResolver().evaluate(PerceptionRequest(context,signal))
    }
}

class PerceptionResolver internal constructor() {
    fun evaluate(request: PerceptionRequest): PerceptionDecision {
        val c = request.context
        val signal = request.signal ?: return PerceptionDecision(
            c.campaignUid, c.trustedObserver.principal, null, null, PerceptionResultState.NO_DATA, "NO_SIGNAL"
        )
        if (signal.campaignUid != c.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        if (signal.ref.campaignUid != c.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        if (c.capabilities.any { it.campaignUid != c.campaignUid || it.ref.campaignUid != c.campaignUid })
            throw VisibilityAuthorityFailure.CrossCampaign()
        if (c.expertise.any { it.campaignUid != c.campaignUid || it.holder.campaignUid != c.campaignUid })
            throw VisibilityAuthorityFailure.CrossCampaign()
        if (!c.rules.isWellFormed()) return corrupt(c, signal, "MALFORMED_WORLD_RULES")
        if (!c.rules.isKnownSignalKind(signal.signalKindUid)) return corrupt(c, signal, "UNKNOWN_SIGNAL_KIND")

        val owned = c.capabilities.filter { it.observer == c.trustedObserver.principal }
        if (owned.any { !it.isWellFormed() }) return corrupt(c, signal, "MALFORMED_CAPABILITY")
        val compatibleChannels = c.rules.compatibleChannelsBySignalKind.getValue(signal.signalKindUid)
        val compatible = owned.filter { capability -> capability.channelUids.any { it in compatibleChannels } }
        if (compatible.isEmpty()) return PerceptionDecision(
            c.campaignUid, c.trustedObserver.principal, signal.ref, signal.signalKindUid,
            PerceptionResultState.NO_COMPATIBLE_CAPABILITY, "NO_COMPATIBLE_CAPABILITY",
            presentedSubject = signal.presentedSubject
        )

        val qualityPenalty = c.interference.sumOf { it.qualityPenalty }
        val effectiveQuality = (signal.quality - qualityPenalty).coerceIn(0.0, 1.0)
        val usable = compatible.filter { effectiveQuality >= it.minimumDetectionQuality }
        if (usable.isEmpty()) return PerceptionDecision(
            c.campaignUid, c.trustedObserver.principal, signal.ref, signal.signalKindUid,
            PerceptionResultState.INSUFFICIENT_SIGNAL, "INSUFFICIENT_SIGNAL", effectiveQuality,
            presentedSubject = signal.presentedSubject
        )
        val capability = usable.maxWithOrNull(compareBy<PerceptionCapability> { it.maximumDisclosure.rank }.thenBy { -it.minimumDetectionQuality })!!
        val interferenceCeiling = c.interference.mapNotNull { it.disclosureCeiling }.minByOrNull { it.rank }
        val ceiling = listOfNotNull(capability.maximumDisclosure, interferenceCeiling).minByOrNull { it.rank }!!
        val uncertainty = c.interference.fold(signal.uncertainty) { value, item ->
            value.attenuate(item.confidencePenalty, item.precisionPenalty)
        }
        return PerceptionDecision(
            c.campaignUid, c.trustedObserver.principal, signal.ref, signal.signalKindUid,
            PerceptionResultState.DETECTED, "DETECTED", effectiveQuality, signal.evidence.toMap(), uncertainty,
            signal.presentedSubject, capability.ref, ceiling
        )
    }

    fun evaluateMany(context: PerceptionContext, signals: List<PerceptionSignal>): List<PerceptionDecision> =
        signals.map { evaluate(PerceptionRequest(context, it)) }

    fun recognize(context: PerceptionContext, perception: PerceptionDecision): RecognitionDecision {
        requireSameObserver(context, perception)
        val signalRef = perception.signalRef ?: return RecognitionDecision(
            context.campaignUid, context.trustedObserver.principal,
            PerceptionSignalRef(context.campaignUid, "NO_SIGNAL"), RecognitionState.UNKNOWN, "NO_SIGNAL"
        )
        if (!perception.detected) return RecognitionDecision(
            context.campaignUid, context.trustedObserver.principal, signalRef,
            RecognitionState.NOT_RECOGNIZED, "NOT_DETECTED", presentedSubject = perception.presentedSubject
        )
        val rule = context.rules.recognitionRules[perception.signalKindUid]
            ?: return RecognitionDecision(context.campaignUid, context.trustedObserver.principal, signalRef, RecognitionState.NOT_RECOGNIZED, "NO_RECOGNITION_RULE", presentedSubject = perception.presentedSubject)
        if (!rule.isWellFormed()) return RecognitionDecision(context.campaignUid, context.trustedObserver.principal, signalRef, RecognitionState.CORRUPTION, "MALFORMED_RECOGNITION_RULE", presentedSubject = perception.presentedSubject)

        val q = perception.effectiveQuality
        val out = linkedMapOf<String, Any?>()
        var state = RecognitionState.NOT_RECOGNIZED
        var reason = "BELOW_CLASSIFICATION_THRESHOLD"
        if (q >= rule.classificationThreshold && rule.classificationPropertyUid != null) {
            perception.evidence[rule.classificationPropertyUid]?.let { out[rule.classificationPropertyUid] = it; state = RecognitionState.CLASSIFIED; reason = "CLASSIFIED" }
        }
        if (q >= rule.recognitionThreshold && rule.recognitionPropertyUid != null) {
            perception.evidence[rule.recognitionPropertyUid]?.let { out[rule.recognitionPropertyUid] = it; state = RecognitionState.RECOGNIZED; reason = "RECOGNIZED" }
        }
        if (q >= rule.identificationThreshold && rule.identityPropertyUid != null) {
            perception.evidence[rule.identityPropertyUid]?.let { out[rule.identityPropertyUid] = it; state = RecognitionState.IDENTIFIED; reason = "IDENTIFIED_FROM_PRESENTED_EVIDENCE" }
        }
        return RecognitionDecision(
            context.campaignUid, context.trustedObserver.principal, signalRef, state, reason, out,
            perception.presentedSubject, perception.uncertainty?.confidence ?: 0.0
        )
    }

    fun interpret(context: PerceptionContext, perception: PerceptionDecision): InterpretationResult {
        requireSameObserver(context, perception)
        val signalRef = perception.signalRef ?: return InterpretationResult(
            context.campaignUid, context.trustedObserver.principal,
            PerceptionSignalRef(context.campaignUid, "NO_SIGNAL"), InterpretationState.UNKNOWN, "NO_SIGNAL"
        )
        if (!perception.detected) return InterpretationResult(
            context.campaignUid, context.trustedObserver.principal, signalRef,
            InterpretationState.NOT_AVAILABLE, "NOT_DETECTED"
        )
        val rule = context.rules.interpretationRules[perception.signalKindUid]
            ?: return InterpretationResult(context.campaignUid, context.trustedObserver.principal, signalRef, InterpretationState.NOT_AVAILABLE, "NO_INTERPRETATION_RULE")
        if (!rule.isWellFormed()) return InterpretationResult(context.campaignUid, context.trustedObserver.principal, signalRef, InterpretationState.CORRUPTION, "MALFORMED_INTERPRETATION_RULE")
        val expertiseBoost = if (rule.expertiseDomainUid == null) 0.0 else context.expertise
            .filter { it.domainUid == rule.expertiseDomainUid && it.holder in context.trustedObserver.cognitionHolders }
            .maxOfOrNull { it.interpretationReliability * rule.expertiseWeight } ?: 0.0
        val effective = (perception.effectiveQuality + expertiseBoost).coerceIn(0.0, 1.0)
        if (effective < rule.minimumQuality) return InterpretationResult(
            context.campaignUid, context.trustedObserver.principal, signalRef,
            InterpretationState.NOT_AVAILABLE, "INSUFFICIENT_INTERPRETATION_QUALITY", confidence = effective
        )
        val input = perception.evidence[rule.inputPropertyUid]?.toString()
            ?: return InterpretationResult(context.campaignUid, context.trustedObserver.principal, signalRef, InterpretationState.NOT_AVAILABLE, "INTERPRETATION_INPUT_ABSENT", confidence = effective)
        val output = rule.outcomes[input]
            ?: return InterpretationResult(context.campaignUid, context.trustedObserver.principal, signalRef, InterpretationState.UNKNOWN, "NO_EVIDENCE_SUPPORTED_INTERPRETATION", confidence = effective)
        return InterpretationResult(
            context.campaignUid, context.trustedObserver.principal, signalRef,
            InterpretationState.INTERPRETED, "EVIDENCE_SUPPORTED_INTERPRETATION",
            mapOf(rule.outputPropertyUid to output), effective
        )
    }

    private fun requireSameObserver(context: PerceptionContext, perception: PerceptionDecision) {
        if (context.campaignUid != perception.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        require(context.trustedObserver.principal == perception.observer) { "RPGOS-P38-PERCEPTION:OBSERVER_MISMATCH" }
    }

    private fun corrupt(context: PerceptionContext, signal: PerceptionSignal, reason: String) = PerceptionDecision(
        context.campaignUid, context.trustedObserver.principal, signal.ref, signal.signalKindUid,
        PerceptionResultState.CORRUPTION, reason, presentedSubject = signal.presentedSubject
    )
}

sealed interface DisclosureValueProjection {
    data object Remove : DisclosureValueProjection
    data object Keep : DisclosureValueProjection
    data object Redact : DisclosureValueProjection
    data class Replace(val value: Any?) : DisclosureValueProjection
    data class Range(val minimum: Double, val maximum: Double) : DisclosureValueProjection {
        init { require(minimum.isFinite() && maximum.isFinite() && minimum <= maximum) }
    }
    data class Approximate(val value: Any?) : DisclosureValueProjection
    data class Summary(val value: Any?) : DisclosureValueProjection
}

data class DisclosedRange(val minimum: Double, val maximum: Double)
data class ApproximateDisclosure(val value: Any?)
data class SummaryDisclosure(val value: Any?)

data class PropertyDisclosureRule(
    val propertyUid: String,
    val projections: Map<DisclosureLevel, DisclosureValueProjection>
) {
    init { require(propertyUid.isNotBlank()) }
    internal fun projectionFor(level: DisclosureLevel): DisclosureValueProjection? =
        projections.filterKeys { it.rank <= level.rank }.maxByOrNull { it.key.rank }?.value
}

data class DisclosurePolicy(
    val campaignUid: String,
    val policyUid: String,
    val maximumLevel: DisclosureLevel,
    val properties: Map<String, PropertyDisclosureRule>
) {
    init { require(campaignUid.isNotBlank() && policyUid.isNotBlank()) }
    internal fun isWellFormed(): Boolean =
        maximumLevel != DisclosureLevel.DENY && properties.isNotEmpty() &&
            properties.all { (key, rule) -> key == rule.propertyUid && rule.projections.isNotEmpty() }
}

data class DisclosureDecision(
    val level: DisclosureLevel,
    val dataState: ProjectionDataState,
    val reasonCode: String,
    val disclosedProperties: Set<String> = emptySet(),
    val redactedProperties: Set<String> = emptySet()
)

data class DisclosureProjection(
    val campaignUid: String,
    val observer: VisibilityPrincipalRef,
    val subject: VisibilitySubjectRef,
    val decision: DisclosureDecision,
    val payload: Map<String, Any?>,
    val uncertainty: PerceptionUncertainty?
) {
    init {
        require(campaignUid == subject.campaignUid)
        if (decision.level == DisclosureLevel.DENY) require(payload.isEmpty())
    }

    fun presentationPayload(): Map<String, Any?> = payload.toMap()
    fun presentationText(): String = payload.toSortedMap().entries.joinToString("; ") { "${it.key}=${it.value}" }
}

class DisclosureResolver {
    fun resolve(
        perception: PerceptionDecision,
        recognition: RecognitionDecision,
        interpretation: InterpretationResult,
        policy: DisclosurePolicy,
        requestedLevel: DisclosureLevel
    ): DisclosureProjection {
        if (perception.campaignUid != policy.campaignUid || recognition.campaignUid != policy.campaignUid || interpretation.campaignUid != policy.campaignUid)
            throw VisibilityAuthorityFailure.CrossCampaign()
        require(perception.observer == recognition.observer && perception.observer == interpretation.observer) { "RPGOS-P38-DISCLOSURE:OBSERVER_MISMATCH" }
        val subject = perception.presentedSubject ?: VisibilitySubjectRef(policy.campaignUid, "PERCEPTION_SIGNAL", perception.signalRef?.signalUid ?: "NO_SIGNAL")
        if (!policy.isWellFormed()) return closed(perception, subject, ProjectionDataState.CORRUPTION, "MALFORMED_DISCLOSURE_POLICY")
        if (!perception.detected) {
            val state = when (perception.state) {
                PerceptionResultState.NO_DATA -> ProjectionDataState.NO_DATA
                PerceptionResultState.CORRUPTION -> ProjectionDataState.CORRUPTION
                PerceptionResultState.UNKNOWN -> ProjectionDataState.UNKNOWN
                PerceptionResultState.DENIED -> ProjectionDataState.DENIED
                else -> ProjectionDataState.NOT_DISCLOSED
            }
            return closed(perception, subject, state, perception.reasonCode)
        }
        if (requestedLevel == DisclosureLevel.DENY) return closed(perception, subject, ProjectionDataState.NOT_DISCLOSED, "DISCLOSURE_DENIED")
        val level = listOf(requestedLevel, policy.maximumLevel, perception.maximumDisclosure).minByOrNull { it.rank }!!
        if (level == DisclosureLevel.DENY) return closed(perception, subject, ProjectionDataState.NOT_DISCLOSED, "DISCLOSURE_CEILING_DENIED")
        val source = linkedMapOf<String, Any?>().apply {
            putAll(perception.evidence)
            putAll(recognition.attributes)
            putAll(interpretation.payload)
        }
        val projected = projectPayload(source, policy, level)
        return DisclosureProjection(
            policy.campaignUid, perception.observer, subject,
            DisclosureDecision(level, if (projected.first.isEmpty()) ProjectionDataState.NOT_DISCLOSED else ProjectionDataState.DISCLOSED,
                "DISCLOSED_FROM_OBSERVABLE_EVIDENCE", projected.first.keys, projected.second),
            projected.first, perception.uncertainty
        )
    }

    fun reduce(projection: DisclosureProjection, policy: DisclosurePolicy, targetLevel: DisclosureLevel): DisclosureProjection {
        if (projection.campaignUid != policy.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        if (!projection.decision.level.canReduceTo(targetLevel)) throw VisibilityAuthorityFailure.Escalation()
        if (targetLevel == DisclosureLevel.DENY) return projection.copy(
            decision = DisclosureDecision(DisclosureLevel.DENY, ProjectionDataState.NOT_DISCLOSED, "DOWNSTREAM_REDUCTION"),
            payload = emptyMap()
        )
        if (!policy.isWellFormed()) return projection.copy(
            decision = DisclosureDecision(DisclosureLevel.DENY, ProjectionDataState.CORRUPTION, "MALFORMED_DISCLOSURE_POLICY"), payload = emptyMap()
        )
        val projected = projectPayload(projection.payload, policy, targetLevel)
        return projection.copy(
            decision = DisclosureDecision(targetLevel, if (projected.first.isEmpty()) ProjectionDataState.NOT_DISCLOSED else ProjectionDataState.DISCLOSED,
                "DOWNSTREAM_REDUCTION", projected.first.keys, projected.second),
            payload = projected.first
        )
    }

    private fun projectPayload(source: Map<String, Any?>, policy: DisclosurePolicy, level: DisclosureLevel): Pair<Map<String, Any?>, Set<String>> {
        val output = linkedMapOf<String, Any?>()
        val redacted = linkedSetOf<String>()
        policy.properties.forEach { (key, rule) ->
            if (!source.containsKey(key)) return@forEach
            when (val transform = rule.projectionFor(level)) {
                null, DisclosureValueProjection.Remove -> Unit
                DisclosureValueProjection.Keep -> output[key] = source[key]
                DisclosureValueProjection.Redact -> { output[key] = "REDACTED"; redacted += key }
                is DisclosureValueProjection.Replace -> output[key] = transform.value
                is DisclosureValueProjection.Range -> output[key] = DisclosedRange(transform.minimum, transform.maximum)
                is DisclosureValueProjection.Approximate -> output[key] = ApproximateDisclosure(transform.value)
                is DisclosureValueProjection.Summary -> output[key] = SummaryDisclosure(transform.value)
            }
        }
        return output to redacted
    }

    private fun closed(perception: PerceptionDecision, subject: VisibilitySubjectRef, state: ProjectionDataState, reason: String) = DisclosureProjection(
        perception.campaignUid, perception.observer, subject,
        DisclosureDecision(DisclosureLevel.DENY, state, reason), emptyMap(), perception.uncertainty
    )
}

data class CommittedObservationRef(val campaignUid: String, val eventUid: String, val evidenceUid: String) {
    init { require(campaignUid.isNotBlank() && eventUid.isNotBlank() && evidenceUid.isNotBlank()) }
}

data class PerceptionAcquisitionOpportunity(
    val campaignUid: String,
    val committedObservation: CommittedObservationRef,
    val eligibleHolders: Set<KnowledgeHolderRef>
)

/** Produces only an opportunity for the existing Phase 37 authority; it never writes knowledge. */
object Phase38PerceptionAcquisitionBridge {
    fun opportunity(
        context: PerceptionContext,
        perception: PerceptionDecision,
        committedObservation: CommittedObservationRef
    ): PerceptionAcquisitionOpportunity {
        if (context.campaignUid != perception.campaignUid || context.campaignUid != committedObservation.campaignUid)
            throw VisibilityAuthorityFailure.CrossCampaign()
        require(perception.observer == context.trustedObserver.principal) { "RPGOS-P38-PERCEPTION:OBSERVER_MISMATCH" }
        val holders = if (perception.detected) context.trustedObserver.cognitionHolders else emptySet()
        return PerceptionAcquisitionOpportunity(context.campaignUid, committedObservation, holders)
    }
}

/** Future decision systems consume this disclosed view; objective resolution remains a separate authority. */
object PerceptionUseBoundary {
    const val OBJECTIVE_RESOLUTION_MAY_USE_FACT = "OBJECTIVE_RESOLUTION_MAY_USE_FACT"
    const val ACTOR_VOLITION_REQUIRES_DISCLOSED_OBSERVATION = "ACTOR_VOLITION_REQUIRES_DISCLOSED_OBSERVATION"
    fun actorDecisionPayload(projection: DisclosureProjection): Map<String, Any?> = projection.presentationPayload()
}
