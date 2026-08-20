package com.rpgos.app

/** Phase 38 Slice A: world-agnostic protected-read authority. Derived/on-demand; no persistent grants. */
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
    const val LOCATION_VISUALIZATION = "LOCATION_VISUALIZATION"
    const val IMAGE_EDIT_VISUALIZATION = "IMAGE_EDIT_VISUALIZATION"
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
    const val RELATIONSHIP_DATA = "RELATIONSHIP_DATA"
    const val ECONOMY_DATA = "ECONOMY_DATA"
    const val POLITICS_DATA = "POLITICS_DATA"
    const val ORGANIZATION_DATA = "ORGANIZATION_DATA"
    const val CAMPAIGN_TRUTH = "CAMPAIGN_TRUTH"
    const val CANON_DIVERGENCE = "CANON_DIVERGENCE"
    const val HIDDEN_PRESSURE = "HIDDEN_PRESSURE"
    const val PHASE37_HOLDER_KNOWLEDGE = "PHASE37_HOLDER_KNOWLEDGE"
    const val PLAYER_STATE = "PLAYER_STATE"
    const val WORLD_PRESENTATION = "WORLD_PRESENTATION"
}

data class VisibilityPrincipalRef(val kindUid: String, val uid: String) {
    init { require(kindUid.isNotBlank() && uid.isNotBlank()) }
}

// AudienceContext is defined in Phase38TrustedAuthority.kt; it is an untrusted audience descriptor.

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
        holder?.let { require(it.campaignUid == campaignUid) { "RPGOS-VISIBILITY:CAMPAIGN_QUALIFIED_SUBJECT_HOLDER_REQUIRED" } }
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
    DENY(0), DISCLOSE_EXISTENCE(10), CATEGORY_ONLY(20), QUALITATIVE(30), APPROXIMATE(40), RANGE(50),
    SUMMARY(60), DISCLOSE_REDACTED(70), DISCLOSE_PARTIAL(80), DETAILED(90), DISCLOSE_FULL(100);
    fun canReduceTo(other: DisclosureLevel): Boolean = other.rank <= rank
}

enum class ProjectionDataState { DISCLOSED, NO_DATA, DENIED, NOT_DISCLOSED, UNKNOWN, CORRUPTION }

data class VisibilityDecision(
    val level: DisclosureLevel,
    val reasonCode: String,
    val disclosedProperties: Set<String> = emptySet(),
    val redactedProperties: Set<String> = emptySet()
) {
    init { require(reasonCode.isNotBlank()) }
}

sealed class VisibilityAuthorityFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class Malformed(detail: String) : VisibilityAuthorityFailure("RPGOS-VISIBILITY:MALFORMED:$detail")
    class CrossCampaign : VisibilityAuthorityFailure("RPGOS-VISIBILITY:CROSS_CAMPAIGN")
    class CorruptAuthority(detail: String, cause: Throwable? = null) : VisibilityAuthorityFailure("RPGOS-VISIBILITY:CORRUPT_AUTHORITY:$detail", cause)
    class CorruptRead(detail: String, cause: Throwable) : VisibilityAuthorityFailure("RPGOS-VISIBILITY:CORRUPT_PROTECTED_READ:$detail", cause)
    class Escalation : VisibilityAuthorityFailure("RPGOS-VISIBILITY:DOWNSTREAM_ESCALATION_REJECTED")
}

data class VisibilityProjectionEnvelope(
    val campaignUid: String,
    val audience: AudienceContext,
    val purpose: PurposeContext,
    val maximumDisclosure: DisclosureLevel,
    val authorityUid: String = VisibilityAuthorityService.AUTHORITY_UID,
    val projectionVersionUid: String = VisibilityAuthorityService.PROJECTION_VERSION_UID
) {
    init {
        require(campaignUid == audience.campaignUid && campaignUid == purpose.campaignUid)
        require(authorityUid == VisibilityAuthorityService.AUTHORITY_UID) { "RPGOS-VISIBILITY:UNKNOWN_AUTHORITY" }
        require(projectionVersionUid == VisibilityAuthorityService.PROJECTION_VERSION_UID) { "RPGOS-VISIBILITY:UNKNOWN_PROJECTION_VERSION" }
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
    val value: T?,
    val dataState: ProjectionDataState
) {
    init {
        if (decision.level == DisclosureLevel.DENY) {
            require(value == null)
            require(dataState in setOf(ProjectionDataState.DENIED, ProjectionDataState.NOT_DISCLOSED, ProjectionDataState.UNKNOWN))
        }
    }

    fun reduceTo(level: DisclosureLevel, transform: (T?) -> T?): VisibilityProjection<T> {
        if (!decision.level.canReduceTo(level)) throw VisibilityAuthorityFailure.Escalation()
        if (level == DisclosureLevel.DENY) {
            return copy(decision = decision.copy(level = level, reasonCode = "DOWNSTREAM_REDUCTION"), value = null, dataState = ProjectionDataState.NOT_DISCLOSED)
        }
        return copy(decision = decision.copy(level = level, reasonCode = "DOWNSTREAM_REDUCTION"), value = transform(value))
    }
}

class VisibilityAuthorityService {
    companion object {
        const val AUTHORITY_UID = "RPGOS-P38-VISIBILITY-AUTHORITY-1"
        const val PROJECTION_VERSION_UID = "RPGOS-P38-VISIBILITY-PROJECTION-1"
    }

    private val knownAudiences = setOf(
        AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER, AudienceKinds.WORLD_ACTOR,
        AudienceKinds.GM_RUNTIME, AudienceKinds.INTERNAL_SYSTEM, AudienceKinds.DEVELOPER_DIAGNOSTIC
    )
    private val knownPurposes = setOf(
        VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
        VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.SCENE_VISUALIZATION,
        VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION,
        VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION, VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION
    )
    private val publicKinds = setOf(
        VisibilitySubjectKinds.PUBLIC_WORLD_EVENT, VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,
        VisibilitySubjectKinds.PUBLIC_WAR_SUMMARY, VisibilitySubjectKinds.WORLD_PRESENTATION
    )
    private val protectedKinds = setOf(
        VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_MEMORY, VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_BELIEF,
        VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_SCHEDULE, VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_DECISION,
        VisibilitySubjectKinds.RELATIONSHIP_DATA, VisibilitySubjectKinds.ECONOMY_DATA,
        VisibilitySubjectKinds.POLITICS_DATA, VisibilitySubjectKinds.ORGANIZATION_DATA,
        VisibilitySubjectKinds.CAMPAIGN_TRUTH, VisibilitySubjectKinds.CANON_DIVERGENCE,
        VisibilitySubjectKinds.HIDDEN_PRESSURE, VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL
    )

    fun decide(request: VisibilityRequest): VisibilityDecision = decide(request, null)

    fun decide(request: VisibilityRequest, trusted: TrustedPrincipalContext?): VisibilityDecision {
        validate(request)
        if (trusted != null && trusted.campaignUid != request.audience.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        if (trusted != null && trusted.principal != request.audience.principal) return deny("TRUSTED_PRINCIPAL_MISMATCH")
        val a = request.audience.audienceKindUid
        val p = request.purpose.purposeUid
        val s = request.subject.subjectKindUid
        if (a !in knownAudiences) return deny("UNKNOWN_AUDIENCE")
        if (p !in knownPurposes) return deny("UNKNOWN_PURPOSE")

        if (a == AudienceKinds.DEVELOPER_DIAGNOSTIC && p == VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
            return if (trusted?.isPrivileged(Phase38RuntimeAuthority.PRIV_DIAGNOSTIC) == true) full("TRUSTED_DIAGNOSTIC") else deny("PRIVILEGED_CAPABILITY_REQUIRED")
        if (a == AudienceKinds.INTERNAL_SYSTEM && p == VisibilityPurposeKinds.INTERNAL_SIMULATION)
            return if (trusted?.isPrivileged(Phase38RuntimeAuthority.PRIV_INTERNAL) == true) full("TRUSTED_INTERNAL_SIMULATION") else deny("PRIVILEGED_CAPABILITY_REQUIRED")
        if (a == AudienceKinds.GM_RUNTIME && p in setOf(VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.INTERNAL_SIMULATION))
            return if (trusted?.isPrivileged(Phase38RuntimeAuthority.PRIV_GM) == true) full("TRUSTED_GM_RUNTIME") else deny("PRIVILEGED_CAPABILITY_REQUIRED")

        if (s in publicKinds && p in setOf(
                VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION,
                VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION,
                VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING
            )) return full("PUBLIC_PROJECTION")

        if (s == VisibilitySubjectKinds.PLAYER_STATE && a in setOf(AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER)) {
            if (trusted == null || !trusted.controls(request.subject.subjectUid)) return deny("PLAYER_STATE_SUBJECT_NOT_CONTROLLED")
            return if (p in setOf(VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION)) full("CONTROLLED_PLAYER_STATE") else deny("PURPOSE_NOT_NECESSARY")
        }

        if (s == VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE) {
            val holder = request.subject.holder ?: return deny("HOLDER_REQUIRED")
            val explicitlyMapped = trusted?.cognitionHolders?.any {
                it.campaignUid == request.audience.campaignUid &&
                    it.holderKindUid == holder.holderKindUid && it.holderUid == holder.holderUid
            } == true
            return if (explicitlyMapped && p == VisibilityPurposeKinds.WORLD_ACTOR_REASONING) full("EXPLICIT_COGNITION_MAPPING") else deny("KNOWLEDGE_NOT_MAPPED_TO_AUDIENCE")
        }

        if (s in protectedKinds) return deny("PROTECTED_SUBJECT")
        return deny("UNKNOWN_PROTECTED_SUBJECT")
    }

    fun envelope(audience: AudienceContext, purpose: PurposeContext): VisibilityProjectionEnvelope {
        if (audience.campaignUid != purpose.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        if (audience.audienceKindUid !in knownAudiences || purpose.purposeUid !in knownPurposes) {
            return VisibilityProjectionEnvelope(audience.campaignUid, audience, purpose, DisclosureLevel.DENY)
        }
        return VisibilityProjectionEnvelope(audience.campaignUid, audience, purpose, DisclosureLevel.DISCLOSE_FULL)
    }

    fun <T> project(request: VisibilityRequest, read: () -> T): VisibilityProjection<T> = project(request, null, read)

    fun <T> project(request: VisibilityRequest, trusted: TrustedPrincipalContext?, read: () -> T): VisibilityProjection<T> {
        val decision = decide(request, trusted)
        if (decision.level == DisclosureLevel.DENY) {
            val state = when (decision.reasonCode) {
                "UNKNOWN_AUDIENCE", "UNKNOWN_PURPOSE", "UNKNOWN_PROTECTED_SUBJECT" -> ProjectionDataState.UNKNOWN
                "PURPOSE_NOT_NECESSARY", "KNOWLEDGE_NOT_MAPPED_TO_AUDIENCE" -> ProjectionDataState.NOT_DISCLOSED
                else -> ProjectionDataState.DENIED
            }
            return VisibilityProjection(request, decision, null, state)
        }
        return try {
            val value = read()
            VisibilityProjection(request, decision, value, if (value == null) ProjectionDataState.NO_DATA else ProjectionDataState.DISCLOSED)
        } catch (failure: VisibilityAuthorityFailure) {
            throw failure
        } catch (failure: Exception) {
            throw VisibilityAuthorityFailure.CorruptRead("${request.subject.subjectKindUid}:${request.subject.subjectUid}", failure)
        }
    }

    fun <T> projectList(request: VisibilityRequest, read: () -> List<T>): VisibilityProjection<List<T>> = projectList(request, null, read)

    fun <T> projectList(request: VisibilityRequest, trusted: TrustedPrincipalContext?, read: () -> List<T>): VisibilityProjection<List<T>> {
        val projection = project(request, trusted, read)
        return if (projection.value != null && projection.value.isEmpty()) projection.copy(dataState = ProjectionDataState.NO_DATA) else projection
    }

    private fun validate(request: VisibilityRequest) {
        if (request.audience.campaignUid != request.purpose.campaignUid || request.audience.campaignUid != request.subject.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()
        if (request.audience.audienceKindUid.isBlank() || request.purpose.purposeUid.isBlank() || request.subject.subjectKindUid.isBlank()) throw VisibilityAuthorityFailure.Malformed("BLANK_IDENTITY")
    }

    private fun deny(reason: String) = VisibilityDecision(DisclosureLevel.DENY, reason)
    private fun full(reason: String) = VisibilityDecision(DisclosureLevel.DISCLOSE_FULL, reason)
}

object VisibilityAudienceFactory {
    fun player(campaignUid: String) = AudienceContext(campaignUid, AudienceKinds.PLAYER, VisibilityPrincipalRef(AudienceKinds.PLAYER, "HUMAN_PLAYER"))
    fun diagnostic(campaignUid: String) = AudienceContext(campaignUid, AudienceKinds.DEVELOPER_DIAGNOSTIC, VisibilityPrincipalRef(AudienceKinds.DEVELOPER_DIAGNOSTIC, "LOCAL_DIAGNOSTIC"))
}
