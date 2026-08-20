package com.rpgos.app

/** Phase 38 Slice A: world-agnostic protected-read authority. Derived/on-demand; no persistent grants yet. */
object AudienceKinds {
    const val PLAYER = "PLAYER"
    const val PLAYER_CHARACTER = "PLAYER_CHARACTER"
    const val WORLD_ACTOR = "WORLD_ACTOR"
    const val GM_RUNTIME = "GM_RUNTIME"
    const val INTERNAL_SYSTEM = "INTERNAL_SYSTEM"
    const val DEVELOPER_DIAGNOSTIC = "DEVELOPER_DIAGNOSTIC"
}

object VisibilityPurposeKinds {
    const val GAMEPLAY_NARRATION = "GAMEPLAY_NARRATION"
    const val WORLD_ACTOR_REASONING = "WORLD_ACTOR_REASONING"
    const val PLAYER_UI = "PLAYER_UI"
    const val SCENE_VISUALIZATION = "SCENE_VISUALIZATION"
    const val CHARACTER_VISUALIZATION = "CHARACTER_VISUALIZATION"
    const val INTERNAL_SIMULATION = "INTERNAL_SIMULATION"
    const val DIAGNOSTIC_INSPECTION = "DIAGNOSTIC_INSPECTION"
}

object VisibilitySubjectKinds {
    const val PUBLIC_WORLD_EVENT = "PUBLIC_WORLD_EVENT"
    const val WORLD_EVENT_GM_DETAIL = "WORLD_EVENT_GM_DETAIL"
    const val PUBLIC_WORLD_ACTOR_PROFILE = "PUBLIC_WORLD_ACTOR_PROFILE"
    const val WORLD_ACTOR_PRIVATE_MEMORY = "WORLD_ACTOR_PRIVATE_MEMORY"
    const val WORLD_ACTOR_PRIVATE_BELIEF = "WORLD_ACTOR_PRIVATE_BELIEF"
    const val WORLD_ACTOR_PRIVATE_SCHEDULE = "WORLD_ACTOR_PRIVATE_SCHEDULE"
    const val WORLD_ACTOR_PRIVATE_DECISION = "WORLD_ACTOR_PRIVATE_DECISION"
    const val PUBLIC_WAR_SUMMARY = "PUBLIC_WAR_SUMMARY"
    const val CAMPAIGN_TRUTH = "CAMPAIGN_TRUTH"
    const val PHASE37_HOLDER_KNOWLEDGE = "PHASE37_HOLDER_KNOWLEDGE"
    const val PLAYER_STATE = "PLAYER_STATE"
    const val WORLD_PRESENTATION = "WORLD_PRESENTATION"
}

data class VisibilityPrincipalRef(val kindUid: String, val uid: String) {
    init { require(kindUid.isNotBlank() && uid.isNotBlank()) }
}

data class AudienceContext(
    val campaignUid: String,
    val audienceKindUid: String,
    val principal: VisibilityPrincipalRef? = null,
    /** Explicit cognition mapping. WORLD_ACTOR identity is never inferred to equal a Phase37 holder. */
    val knowledgeHolders: List<KnowledgeHolderRef> = emptyList()
) {
    init {
        require(campaignUid.isNotBlank() && audienceKindUid.isNotBlank())
        knowledgeHolders.forEach { holder ->
            require(holder.campaignUid == null || holder.campaignUid == campaignUid) { "RPGOS-VISIBILITY:CROSS_CAMPAIGN_HOLDER_BINDING" }
        }
        if (audienceKindUid == AudienceKinds.WORLD_ACTOR || audienceKindUid == AudienceKinds.PLAYER_CHARACTER) {
            require(principal != null) { "RPGOS-VISIBILITY:ACTOR_AUDIENCE_REQUIRES_PRINCIPAL" }
        }
    }
}

data class PurposeContext(val campaignUid: String, val purposeUid: String) {
    init { require(campaignUid.isNotBlank() && purposeUid.isNotBlank()) }
}

data class VisibilitySubjectRef(
    val campaignUid: String,
    val subjectKindUid: String,
    val subjectUid: String,
    val propertyUid: String? = null,
    val holder: KnowledgeHolderRef? = null
) {
    init {
        require(campaignUid.isNotBlank() && subjectKindUid.isNotBlank() && subjectUid.isNotBlank())
        require(propertyUid?.isBlank() != true)
        holder?.let { require(it.campaignUid == null || it.campaignUid == campaignUid) { "RPGOS-VISIBILITY:CROSS_CAMPAIGN_SUBJECT_HOLDER" } }
    }
}

data class VisibilityRequest(
    val audience: AudienceContext,
    val purpose: PurposeContext,
    val subject: VisibilitySubjectRef
) {
    init {
        require(audience.campaignUid == purpose.campaignUid && audience.campaignUid == subject.campaignUid) {
            "RPGOS-VISIBILITY:CROSS_CAMPAIGN_REQUEST"
        }
    }
}

enum class DisclosureLevel(val rank: Int) {
    DENY(0), DISCLOSE_EXISTENCE(1), DISCLOSE_REDACTED(2), DISCLOSE_PARTIAL(3), DISCLOSE_FULL(4);
    fun canReduceTo(other: DisclosureLevel): Boolean = other.rank <= rank
}

data class VisibilityDecision(
    val level: DisclosureLevel,
    val reasonCode: String,
    val disclosedProperties: Set<String> = emptySet(),
    val redactedProperties: Set<String> = emptySet()
) {
    init { require(reasonCode.isNotBlank()) }
}

sealed class VisibilityAuthorityFailure(message: String) : IllegalStateException(message) {
    class Malformed(detail: String) : VisibilityAuthorityFailure("RPGOS-VISIBILITY:MALFORMED:$detail")
    class CrossCampaign : VisibilityAuthorityFailure("RPGOS-VISIBILITY:CROSS_CAMPAIGN")
    class CorruptAuthority(detail: String) : VisibilityAuthorityFailure("RPGOS-VISIBILITY:CORRUPT_AUTHORITY:$detail")
    class Escalation : VisibilityAuthorityFailure("RPGOS-VISIBILITY:DOWNSTREAM_ESCALATION_REJECTED")
}

data class VisibilityProjectionEnvelope(
    val campaignUid: String,
    val audience: AudienceContext,
    val purpose: PurposeContext,
    val maximumDisclosure: DisclosureLevel,
    val authorityUid: String = VisibilityAuthorityService.AUTHORITY_UID
) {
    init {
        require(campaignUid == audience.campaignUid && campaignUid == purpose.campaignUid)
        require(authorityUid == VisibilityAuthorityService.AUTHORITY_UID) { "RPGOS-VISIBILITY:UNKNOWN_AUTHORITY" }
    }

    fun reduceTo(level: DisclosureLevel): VisibilityProjectionEnvelope {
        if (!maximumDisclosure.canReduceTo(level)) throw VisibilityAuthorityFailure.Escalation()
        return copy(maximumDisclosure = level)
    }

    fun requirePurpose(vararg allowed: String) {
        require(purpose.purposeUid in allowed) { "RPGOS-VISIBILITY:PURPOSE_ENVELOPE_MISMATCH" }
    }
}

data class VisibilityProjection<T>(
    val request: VisibilityRequest,
    val decision: VisibilityDecision,
    val value: T?
) {
    init { if (decision.level == DisclosureLevel.DENY) require(value == null) }
    fun reduceTo(level: DisclosureLevel, transform: (T?) -> T?): VisibilityProjection<T> {
        if (!decision.level.canReduceTo(level)) throw VisibilityAuthorityFailure.Escalation()
        return copy(decision = decision.copy(level = level, reasonCode = "DOWNSTREAM_REDUCTION"), value = if (level == DisclosureLevel.DENY) null else transform(value))
    }
}

class VisibilityAuthorityService {
    companion object { const val AUTHORITY_UID = "RPGOS-P38-VISIBILITY-AUTHORITY-1" }

    private val knownAudiences = setOf(
        AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER, AudienceKinds.WORLD_ACTOR,
        AudienceKinds.GM_RUNTIME, AudienceKinds.INTERNAL_SYSTEM, AudienceKinds.DEVELOPER_DIAGNOSTIC
    )
    private val knownPurposes = setOf(
        VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
        VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.SCENE_VISUALIZATION,
        VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.INTERNAL_SIMULATION,
        VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION
    )
    private val publicKinds = setOf(
        VisibilitySubjectKinds.PUBLIC_WORLD_EVENT, VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,
        VisibilitySubjectKinds.PUBLIC_WAR_SUMMARY, VisibilitySubjectKinds.WORLD_PRESENTATION
    )
    private val privateActorKinds = setOf(
        VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_MEMORY, VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_BELIEF,
        VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_SCHEDULE, VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_DECISION
    )

    fun decide(request: VisibilityRequest): VisibilityDecision {
        validate(request)
        val a = request.audience.audienceKindUid
        val p = request.purpose.purposeUid
        val s = request.subject.subjectKindUid
        if (a !in knownAudiences) return deny("UNKNOWN_AUDIENCE")
        if (p !in knownPurposes) return deny("UNKNOWN_PURPOSE")

        if (a == AudienceKinds.DEVELOPER_DIAGNOSTIC && p == VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION) {
            return full("EXPLICIT_DIAGNOSTIC")
        }
        if (a == AudienceKinds.INTERNAL_SYSTEM && p == VisibilityPurposeKinds.INTERNAL_SIMULATION) {
            return full("EXPLICIT_INTERNAL_SIMULATION")
        }
        if (a == AudienceKinds.GM_RUNTIME && p in setOf(VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.INTERNAL_SIMULATION)) {
            return full("GM_RUNTIME_INTERNAL")
        }
        if (s in publicKinds && p in setOf(
                VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION,
                VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION,
                VisibilityPurposeKinds.WORLD_ACTOR_REASONING
            )) return full("PUBLIC_PROJECTION")

        if (s == VisibilitySubjectKinds.PLAYER_STATE && a in setOf(AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER)) {
            return if (p in setOf(VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION)) full("PLAYER_STATE") else deny("PURPOSE_NOT_NECESSARY")
        }

        if (s == VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE) {
            val holder = request.subject.holder ?: return deny("HOLDER_REQUIRED")
            val explicitlyMapped = request.audience.knowledgeHolders.any {
                it.holderKindUid == holder.holderKindUid && it.holderUid == holder.holderUid && (it.campaignUid == null || it.campaignUid == request.audience.campaignUid)
            }
            return if (explicitlyMapped && p == VisibilityPurposeKinds.WORLD_ACTOR_REASONING) full("EXPLICIT_COGNITION_MAPPING") else deny("KNOWLEDGE_NOT_MAPPED_TO_AUDIENCE")
        }

        if (s in privateActorKinds || s == VisibilitySubjectKinds.CAMPAIGN_TRUTH || s == VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL) {
            return deny("PROTECTED_SUBJECT")
        }
        return deny("UNKNOWN_PROTECTED_SUBJECT")
    }

    fun envelope(audience: AudienceContext, purpose: PurposeContext): VisibilityProjectionEnvelope {
        if (audience.campaignUid != purpose.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        if (audience.audienceKindUid !in knownAudiences || purpose.purposeUid !in knownPurposes) {
            return VisibilityProjectionEnvelope(audience.campaignUid, audience, purpose, DisclosureLevel.DENY)
        }
        return VisibilityProjectionEnvelope(audience.campaignUid, audience, purpose, DisclosureLevel.DISCLOSE_FULL)
    }

    fun <T> project(request: VisibilityRequest, read: () -> T): VisibilityProjection<T> {
        val decision = decide(request)
        if (decision.level == DisclosureLevel.DENY) return VisibilityProjection(request, decision, null)
        return VisibilityProjection(request, decision, read())
    }

    private fun validate(request: VisibilityRequest) {
        if (request.audience.campaignUid != request.purpose.campaignUid || request.audience.campaignUid != request.subject.campaignUid) {
            throw VisibilityAuthorityFailure.CrossCampaign()
        }
        if (request.audience.audienceKindUid.isBlank() || request.purpose.purposeUid.isBlank() || request.subject.subjectKindUid.isBlank()) {
            throw VisibilityAuthorityFailure.Malformed("BLANK_IDENTITY")
        }
    }

    private fun deny(reason: String) = VisibilityDecision(DisclosureLevel.DENY, reason)
    private fun full(reason: String) = VisibilityDecision(DisclosureLevel.DISCLOSE_FULL, reason)
}

object VisibilityAudienceFactory {
    fun player(campaignUid: String) = AudienceContext(campaignUid, AudienceKinds.PLAYER, VisibilityPrincipalRef(AudienceKinds.PLAYER, "HUMAN_PLAYER"))
    fun diagnostic(campaignUid: String) = AudienceContext(campaignUid, AudienceKinds.DEVELOPER_DIAGNOSTIC, VisibilityPrincipalRef(AudienceKinds.DEVELOPER_DIAGNOSTIC, "LOCAL_DIAGNOSTIC"))
}
