package com.rpgos.app

/** The technical lifecycle of an NPC attempt. It is deliberately separate from the result of
 * travel, progression, treatment, knowledge acquisition or any other domain-owned outcome. */
enum class NpcActivityLifecycleStage {
    AUTHORIZED,
    STARTED,
    IN_PROGRESS,
    INTERRUPTED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/** A completed attempt still needs typed evidence. DOMAIN_RESULT_EVIDENCE additionally requires
 * evidence issued by the canonical owner of the requested world result. */
enum class NpcActivityResultPolicy {
    ATTEMPT_EVIDENCE,
    DOMAIN_RESULT_EVIDENCE
}

enum class NpcActivityResolutionKind {
    SUCCEEDED,
    PARTIAL,
    FAILED,
    INTERRUPTED,
    CANCELLED
}

/**
 * Registration-time ownership contract. Phase62 may own the intention and plan lifecycle, but
 * [resultOwnerUid] remains the only owner allowed to prove the domain result.
 *
 * The contract is world-neutral: a World Pack can register its own UIDs without adding a branch to
 * Core. It contains no executable callback and therefore cannot bypass mechanics or TurnTransaction.
 */
data class NpcActivityOwnerContract(
    val contractUid: String,
    val version: Int,
    val lifecycleOwnerUid: String,
    val resultOwnerUid: String,
    val evidenceKindUid: String,
    val resultPolicy: NpcActivityResultPolicy,
    val allowedCanonicalChangeKindUids: Set<String> = emptySet()
) {
    init {
        listOf(contractUid, lifecycleOwnerUid, resultOwnerUid, evidenceKindUid).forEach(::npcUid)
        require(version > 0) { "P62:INVALID_ACTIVITY_OWNER_CONTRACT_VERSION" }
        require(allowedCanonicalChangeKindUids.size <= 32) { "P62:ACTIVITY_OWNER_CHANGE_KIND_BUDGET" }
        allowedCanonicalChangeKindUids.forEach(::npcUid)
        require(
            resultPolicy != NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE ||
                allowedCanonicalChangeKindUids.isNotEmpty()
        ) { "P62:DOMAIN_RESULT_CHANGE_KINDS_REQUIRED" }
    }

    val fingerprint: String
        get() = phase60Hash(
            "P62:ACTIVITY_OWNER_CONTRACT:1|" +
                listOf(
                    contractUid,
                    version.toString(),
                    lifecycleOwnerUid,
                    resultOwnerUid,
                    evidenceKindUid,
                    resultPolicy.name,
                    allowedCanonicalChangeKindUids.sorted().joinToString(",")
                ).joinToString("|")
        )
}

fun interface NpcActivityOwnerContractPort {
    fun contract(campaignUid: String, contractUid: String, version: Int): NpcActivityOwnerContract?

    companion object {
        val NONE = NpcActivityOwnerContractPort { _, _, _ -> null }

        fun registered(contracts: List<NpcActivityOwnerContract>): NpcActivityOwnerContractPort {
            require(contracts.size <= 256) { "P62:ACTIVITY_OWNER_CONTRACT_BUDGET" }
            require(contracts.map { it.contractUid to it.version }.distinct().size == contracts.size) {
                "P62:DUPLICATE_ACTIVITY_OWNER_CONTRACT"
            }
            val snapshot = contracts.map {
                it.copy(allowedCanonicalChangeKindUids = it.allowedCanonicalChangeKindUids.toSet())
            }.associateBy { it.contractUid to it.version }
            return NpcActivityOwnerContractPort { campaignUid, contractUid, version ->
                npcUid(campaignUid)
                npcUid(contractUid)
                require(version > 0) { "P62:INVALID_ACTIVITY_OWNER_CONTRACT_VERSION" }
                snapshot[contractUid to version]
            }
        }
    }
}

/**
 * Stable identity of one authorized attempt. It contains no model prose and no precomputed future
 * effect. The history generation makes an attempt from an abandoned branch unusable after Undo.
 */
data class NpcActivityAttemptIdentity(
    val campaignUid: String,
    val historyGenerationUid: String,
    val actor: DomainRef,
    val planUid: String,
    val optionUid: String,
    val capabilityUid: String,
    val authorizedAt: WorldTimeTick,
    val dueAt: WorldTimeTick,
    val authorizationFingerprint: String,
    val ownerContractFingerprint: String
) {
    init {
        listOf(
            campaignUid,
            historyGenerationUid,
            actor.kindUid,
            actor.uid,
            planUid,
            optionUid,
            capabilityUid
        ).forEach(::npcUid)
        require(dueAt > authorizedAt) { "P62:ACTIVITY_DUE_NOT_AFTER_AUTHORIZATION" }
        require(authorizationFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "P62:INVALID_ACTIVITY_AUTHORIZATION_FINGERPRINT"
        }
        require(ownerContractFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "P62:INVALID_ACTIVITY_OWNER_FINGERPRINT"
        }
    }

    val fingerprint: String
        get() = phase60Hash(
            "P62:ACTIVITY_ATTEMPT:1|" +
                listOf(
                    campaignUid,
                    historyGenerationUid,
                    actor.kindUid,
                    actor.uid,
                    planUid,
                    optionUid,
                    capabilityUid,
                    authorizedAt.milliseconds.toString(),
                    dueAt.milliseconds.toString(),
                    authorizationFingerprint,
                    ownerContractFingerprint
                ).joinToString("|")
        )
}

/** A reference to an effect/change that has already crossed the ordinary canonical boundary. */
data class NpcActivityCanonicalEvidence(
    val recordUid: String,
    val changeKindUid: String
) {
    init {
        npcUid(recordUid)
        npcUid(changeKindUid)
    }
}

/**
 * Typed result evidence. Similarity, model confidence, elapsed time and narration are intentionally
 * absent: none of them proves that a domain outcome occurred.
 */
data class NpcActivityResolutionEvidence(
    val attemptFingerprint: String,
    val ownerUid: String,
    val evidenceKindUid: String,
    val resolutionUid: String,
    val resolutionKind: NpcActivityResolutionKind,
    val resolvedAt: WorldTimeTick,
    val canonicalEvidence: List<NpcActivityCanonicalEvidence> = emptyList(),
    val verifiedEffectUids: List<String> = emptyList(),
    val sourceFingerprint: String
) {
    init {
        require(attemptFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "P62:INVALID_ACTIVITY_ATTEMPT_FINGERPRINT"
        }
        listOf(ownerUid, evidenceKindUid, resolutionUid).forEach(::npcUid)
        require(sourceFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "P62:INVALID_ACTIVITY_RESOLUTION_FINGERPRINT"
        }
        require(canonicalEvidence.size <= 64 && canonicalEvidence.distinct().size == canonicalEvidence.size) {
            "P62:ACTIVITY_CANONICAL_EVIDENCE_BUDGET"
        }
        require(verifiedEffectUids.size <= 64 && verifiedEffectUids.distinct().size == verifiedEffectUids.size) {
            "P62:ACTIVITY_EFFECT_EVIDENCE_BUDGET"
        }
        verifiedEffectUids.forEach(::npcUid)
        if (resolutionKind in setOf(
                NpcActivityResolutionKind.INTERRUPTED,
                NpcActivityResolutionKind.CANCELLED
            )
        ) {
            require(canonicalEvidence.isEmpty() && verifiedEffectUids.isEmpty()) {
                "P62:NON_COMPLETED_ACTIVITY_HAS_APPLIED_EFFECTS"
            }
        }
    }
}

data class NpcActivityLifecycleSnapshot(
    val identity: NpcActivityAttemptIdentity,
    val ownerContract: NpcActivityOwnerContract,
    val stage: NpcActivityLifecycleStage,
    val updatedAt: WorldTimeTick,
    val resolution: NpcActivityResolutionEvidence? = null
) {
    init {
        require(identity.ownerContractFingerprint == ownerContract.fingerprint) {
            "P62:ACTIVITY_OWNER_CONTRACT_CHANGED"
        }
        require(updatedAt >= identity.authorizedAt) { "P62:ACTIVITY_TIME_REGRESSION" }
        resolution?.let {
            require(it.attemptFingerprint == identity.fingerprint) { "P62:ACTIVITY_RESOLUTION_WRONG_ATTEMPT" }
            require(it.ownerUid == ownerContract.resultOwnerUid) { "P62:ACTIVITY_RESOLUTION_WRONG_OWNER" }
            require(it.evidenceKindUid == ownerContract.evidenceKindUid) { "P62:ACTIVITY_RESOLUTION_WRONG_KIND" }
            require(it.resolvedAt == updatedAt) { "P62:ACTIVITY_RESOLUTION_TIME_MISMATCH" }
            require(it.canonicalEvidence.all { evidence ->
                evidence.changeKindUid in ownerContract.allowedCanonicalChangeKindUids
            }) { "P62:ACTIVITY_RESOLUTION_UNAUTHORIZED_CHANGE_KIND" }
        }
        when (stage) {
            NpcActivityLifecycleStage.AUTHORIZED,
            NpcActivityLifecycleStage.STARTED,
            NpcActivityLifecycleStage.IN_PROGRESS -> require(resolution == null) {
                "P62:NON_TERMINAL_ACTIVITY_HAS_RESOLUTION"
            }

            NpcActivityLifecycleStage.COMPLETED -> {
                require(updatedAt >= identity.dueAt) { "P62:ACTIVITY_COMPLETED_BEFORE_DUE" }
                val evidence = requireNotNull(resolution) { "P62:ACTIVITY_COMPLETION_EVIDENCE_REQUIRED" }
                require(evidence.resolutionKind in setOf(
                    NpcActivityResolutionKind.SUCCEEDED,
                    NpcActivityResolutionKind.PARTIAL
                )) { "P62:ACTIVITY_COMPLETION_EVIDENCE_REQUIRED" }
                require(
                    evidence.canonicalEvidence.isNotEmpty() ||
                        evidence.verifiedEffectUids.isNotEmpty()
                ) { "P62:ACTIVITY_COMPLETION_LEAF_REQUIRED" }
                if (ownerContract.resultPolicy == NpcActivityResultPolicy.DOMAIN_RESULT_EVIDENCE) {
                    require(evidence.canonicalEvidence.isNotEmpty()) {
                        "P62:DOMAIN_RESULT_CANONICAL_EVIDENCE_REQUIRED"
                    }
                }
            }

            NpcActivityLifecycleStage.FAILED -> {
                require(updatedAt >= identity.dueAt) { "P62:ACTIVITY_FAILED_BEFORE_DUE" }
                require(resolution?.resolutionKind == NpcActivityResolutionKind.FAILED) {
                    "P62:ACTIVITY_FAILURE_EVIDENCE_REQUIRED"
                }
            }

            NpcActivityLifecycleStage.INTERRUPTED ->
                require(resolution?.resolutionKind == NpcActivityResolutionKind.INTERRUPTED) {
                    "P62:ACTIVITY_INTERRUPTION_EVIDENCE_REQUIRED"
                }

            NpcActivityLifecycleStage.CANCELLED ->
                require(resolution == null || resolution.resolutionKind == NpcActivityResolutionKind.CANCELLED) {
                    "P62:ACTIVITY_CANCELLATION_EVIDENCE_MISMATCH"
                }
        }
    }

    /** Only a full domain success may satisfy a success criterion. PARTIAL remains inspectable but
     * cannot be silently promoted to ACHIEVED. */
    fun provesFullDomainSuccess(): Boolean =
        stage == NpcActivityLifecycleStage.COMPLETED &&
            resolution?.resolutionKind == NpcActivityResolutionKind.SUCCEEDED
}

object NpcActivityLifecycleRules {
    private val allowed = mapOf(
        NpcActivityLifecycleStage.AUTHORIZED to setOf(
            NpcActivityLifecycleStage.STARTED,
            NpcActivityLifecycleStage.CANCELLED
        ),
        NpcActivityLifecycleStage.STARTED to setOf(
            NpcActivityLifecycleStage.IN_PROGRESS,
            NpcActivityLifecycleStage.INTERRUPTED,
            NpcActivityLifecycleStage.COMPLETED,
            NpcActivityLifecycleStage.FAILED,
            NpcActivityLifecycleStage.CANCELLED
        ),
        NpcActivityLifecycleStage.IN_PROGRESS to setOf(
            NpcActivityLifecycleStage.INTERRUPTED,
            NpcActivityLifecycleStage.COMPLETED,
            NpcActivityLifecycleStage.FAILED,
            NpcActivityLifecycleStage.CANCELLED
        )
    )

    fun authorize(
        identity: NpcActivityAttemptIdentity,
        ownerContract: NpcActivityOwnerContract
    ): NpcActivityLifecycleSnapshot =
        NpcActivityLifecycleSnapshot(
            identity,
            ownerContract,
            NpcActivityLifecycleStage.AUTHORIZED,
            identity.authorizedAt
        )

    fun transition(
        before: NpcActivityLifecycleSnapshot,
        next: NpcActivityLifecycleStage,
        at: WorldTimeTick,
        resolution: NpcActivityResolutionEvidence? = null
    ): NpcActivityLifecycleSnapshot {
        require(next in allowed[before.stage].orEmpty()) {
            "P62:INVALID_ACTIVITY_LIFECYCLE_TRANSITION:${before.stage}:$next"
        }
        require(at >= before.updatedAt) { "P62:ACTIVITY_TIME_REGRESSION" }
        return NpcActivityLifecycleSnapshot(
            before.identity,
            before.ownerContract,
            next,
            at,
            resolution
        )
    }
}
