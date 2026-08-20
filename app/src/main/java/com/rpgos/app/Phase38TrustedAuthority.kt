package com.rpgos.app

/** Runtime-owned identity/privilege state. Normal callers can describe an audience, never manufacture this authority. */
class PrivilegedAudienceCapability private constructor(
    internal val campaignUid: String,
    internal val privilegeUid: String,
    private val seal: Any
) {
    init { require(campaignUid.isNotBlank() && privilegeUid.isNotBlank() && seal === PRIVILEGE_SEAL) }
    companion object {
        internal fun issue(campaignUid: String, privilegeUid: String): PrivilegedAudienceCapability =
            PrivilegedAudienceCapability(campaignUid, privilegeUid, PRIVILEGE_SEAL)
    }
}
private val PRIVILEGE_SEAL = Any()

data class TrustedPrincipalContext internal constructor(
    val campaignUid: String,
    val principal: VisibilityPrincipalRef,
    val audienceKindUid: String,
    val controlledSubjectUids: Set<String> = emptySet(),
    val roleUids: Set<String> = emptySet(),
    val organizationUids: Set<String> = emptySet(),
    val clearanceUids: Set<String> = emptySet(),
    val cognitionHolders: Set<KnowledgeHolderRef> = emptySet(),
    internal val privilegedCapability: PrivilegedAudienceCapability? = null,
    internal val resolverVersionUid: String = "RPGOS-P38-TRUSTED-PRINCIPAL-1"
) {
    init {
        require(campaignUid.isNotBlank() && principal.kindUid.isNotBlank() && principal.uid.isNotBlank())
        cognitionHolders.forEach { require(it.campaignUid == campaignUid) }
        privilegedCapability?.let { require(it.campaignUid == campaignUid) }
    }
    fun controls(subjectUid: String) = subjectUid in controlledSubjectUids
    fun isPrivileged(privilegeUid: String) = privilegedCapability?.privilegeUid == privilegeUid
}

fun interface TrustedCognitionResolver {
    fun holdersFor(campaignUid: String, principal: VisibilityPrincipalRef): Set<KnowledgeHolderRef>
}

fun interface TrustedPrincipalResolver {
    fun resolve(audience: AudienceContext): TrustedPrincipalContext?
}

internal object Phase38RuntimeAuthority {
    const val PRIV_GM = "GM_RUNTIME"
    const val PRIV_INTERNAL = "INTERNAL_SYSTEM"
    const val PRIV_DIAGNOSTIC = "DEVELOPER_DIAGNOSTIC"

    fun application(
        audience: AudienceContext,
        controlledSubjectUids: Set<String> = emptySet(),
        cognitionResolver: TrustedCognitionResolver = TrustedCognitionResolver { _, _ -> emptySet() },
        roleUids: Set<String> = emptySet(),
        organizationUids: Set<String> = emptySet(),
        clearanceUids: Set<String> = emptySet()
    ): TrustedPrincipalContext? {
        val principal = audience.principal ?: return null
        if (audience.audienceKindUid in setOf(AudienceKinds.GM_RUNTIME, AudienceKinds.INTERNAL_SYSTEM, AudienceKinds.DEVELOPER_DIAGNOSTIC)) return null
        return TrustedPrincipalContext(
            audience.campaignUid, principal, audience.audienceKindUid,
            controlledSubjectUids, roleUids, organizationUids, clearanceUids,
            cognitionResolver.holdersFor(audience.campaignUid, principal)
        )
    }

    internal fun privileged(audience: AudienceContext, privilegeUid: String): TrustedPrincipalContext {
        val principal = requireNotNull(audience.principal)
        require(audience.audienceKindUid == privilegeUid) { "RPGOS-P38:PRIVILEGE_AUDIENCE_MISMATCH" }
        return TrustedPrincipalContext(
            audience.campaignUid, principal, audience.audienceKindUid,
            privilegedCapability = PrivilegedAudienceCapability.issue(audience.campaignUid, privilegeUid)
        )
    }
}

/** Compatibility descriptor: caller-supplied holders are deliberately ignored by trusted authorization. */
data class AudienceContext(
    val campaignUid: String,
    val audienceKindUid: String,
    val principal: VisibilityPrincipalRef? = null,
    @Deprecated("Caller-supplied cognition mappings are not authority in Phase38")
    val knowledgeHolders: List<KnowledgeHolderRef> = emptyList()
) {
    init {
        require(campaignUid.isNotBlank() && audienceKindUid.isNotBlank())
        knowledgeHolders.forEach { require(it.campaignUid == campaignUid) { "RPGOS-VISIBILITY:CAMPAIGN_QUALIFIED_HOLDER_REQUIRED" } }
        if (audienceKindUid == AudienceKinds.WORLD_ACTOR || audienceKindUid == AudienceKinds.PLAYER_CHARACTER) {
            require(principal != null) { "RPGOS-VISIBILITY:ACTOR_AUDIENCE_REQUIRES_PRINCIPAL" }
        }
    }
}
