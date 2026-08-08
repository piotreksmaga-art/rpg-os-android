package com.rpgos.app

/**
 * Deterministic scene-perception gate used before an NPC decision is built.
 *
 * This layer does not discover campaign truth on its own. The scene/simulation layer supplies
 * candidate FACT references together with concrete perception conditions. Only candidates that
 * the NPC can actually perceive become one-turn OBSERVABLE_FACT grants.
 */
class ScenePerceptionGrantResolver141 {
    enum class Modality {
        VISION,
        HEARING,
        DETECTION
    }

    data class Observer(
        val npcUid: EntityUid,
        val locationUid: EntityUid,
        val visionRangeMeters: Double = 30.0,
        val hearingRangeMeters: Double = 20.0,
        val detectionRangeMeters: Double = 0.0,
        val canSee: Boolean = true,
        val canHear: Boolean = true,
        val detectionTags: Set<String> = emptySet()
    ) {
        init {
            require(visionRangeMeters >= 0.0) { "visionRangeMeters nie może być ujemny." }
            require(hearingRangeMeters >= 0.0) { "hearingRangeMeters nie może być ujemny." }
            require(detectionRangeMeters >= 0.0) { "detectionRangeMeters nie może być ujemny." }
        }
    }

    data class CandidateFact(
        val truthUid: EntityUid,
        val subjectUid: EntityUid,
        val predicate: String,
        val locationUid: EntityUid,
        val modality: Modality,
        val distanceMeters: Double,
        val lineOfSight: Boolean = true,
        val soundPathOpen: Boolean = true,
        val concealed: Boolean = false,
        val requiredDetectionTag: String? = null
    ) {
        init {
            require(predicate.isNotBlank()) { "Candidate predicate nie może być pusty." }
            require(distanceMeters >= 0.0) { "distanceMeters nie może być ujemny." }
            require(requiredDetectionTag == null || requiredDetectionTag.isNotBlank()) {
                "requiredDetectionTag nie może być pusty."
            }
        }
    }

    enum class DenialReason {
        DIFFERENT_LOCATION,
        OUT_OF_RANGE,
        NO_LINE_OF_SIGHT,
        NO_SOUND_PATH,
        SENSE_DISABLED,
        CONCEALED,
        MISSING_DETECTION_CAPABILITY
    }

    data class DeniedCandidate(
        val candidate: CandidateFact,
        val reason: DenialReason
    )

    data class Result(
        val grants: List<NpcKnowledgeAccessPolicy141.Grant>,
        val denied: List<DeniedCandidate>
    )

    fun resolve(
        observer: Observer,
        turnId: Long,
        candidates: List<CandidateFact>
    ): Result {
        require(turnId >= 0L) { "turnId nie może być ujemny." }
        val grants = mutableListOf<NpcKnowledgeAccessPolicy141.Grant>()
        val denied = mutableListOf<DeniedCandidate>()

        candidates.distinctBy { it.truthUid }.forEach { candidate ->
            val reason = denialReason(observer, candidate)
            if (reason != null) {
                denied += DeniedCandidate(candidate, reason)
            } else {
                grants += NpcKnowledgeAccessPolicy141.Grant(
                    holderUid = observer.npcUid,
                    truthUid = candidate.truthUid,
                    subjectUid = candidate.subjectUid,
                    predicate = candidate.predicate,
                    kind = NpcKnowledgeAccessPolicy141.GrantKind.OBSERVABLE_FACT,
                    grantedByUid = observer.locationUid,
                    validFromTurn = turnId,
                    validUntilTurn = turnId
                )
            }
        }

        return Result(grants = grants, denied = denied)
    }

    private fun denialReason(observer: Observer, candidate: CandidateFact): DenialReason? {
        if (candidate.locationUid != observer.locationUid) return DenialReason.DIFFERENT_LOCATION

        return when (candidate.modality) {
            Modality.VISION -> when {
                !observer.canSee -> DenialReason.SENSE_DISABLED
                candidate.distanceMeters > observer.visionRangeMeters -> DenialReason.OUT_OF_RANGE
                !candidate.lineOfSight -> DenialReason.NO_LINE_OF_SIGHT
                candidate.concealed -> DenialReason.CONCEALED
                else -> null
            }

            Modality.HEARING -> when {
                !observer.canHear -> DenialReason.SENSE_DISABLED
                candidate.distanceMeters > observer.hearingRangeMeters -> DenialReason.OUT_OF_RANGE
                !candidate.soundPathOpen -> DenialReason.NO_SOUND_PATH
                candidate.concealed -> DenialReason.CONCEALED
                else -> null
            }

            Modality.DETECTION -> when {
                candidate.distanceMeters > observer.detectionRangeMeters -> DenialReason.OUT_OF_RANGE
                candidate.requiredDetectionTag != null && candidate.requiredDetectionTag !in observer.detectionTags ->
                    DenialReason.MISSING_DETECTION_CAPABILITY
                candidate.concealed && candidate.requiredDetectionTag == null -> DenialReason.CONCEALED
                else -> null
            }
        }
    }
}
