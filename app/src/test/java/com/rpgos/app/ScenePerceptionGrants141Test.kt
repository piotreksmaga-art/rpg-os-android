package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenePerceptionGrants141Test {
    private val npc = EntityUid("NPC-SCOUT")
    private val room = EntityUid("LOC-ROOM-A")
    private val otherRoom = EntityUid("LOC-ROOM-B")
    private val subject = EntityUid("CHAR-TARGET")
    private val resolver = ScenePerceptionGrantResolver141()

    @Test
    fun visibleFactCreatesOneTurnGrant() {
        val result = resolver.resolve(
            observer = observer(),
            turnId = 42,
            candidates = listOf(candidate("FACT-VISIBLE", ScenePerceptionGrantResolver141.Modality.VISION, 8.0))
        )

        assertEquals(1, result.grants.size)
        assertEquals(42L, result.grants.single().validFromTurn)
        assertEquals(42L, result.grants.single().validUntilTurn)
        assertTrue(result.denied.isEmpty())
    }

    @Test
    fun visionCannotSeeThroughBlockedLineOfSight() {
        val blocked = candidate(
            "FACT-BEHIND-WALL",
            ScenePerceptionGrantResolver141.Modality.VISION,
            5.0,
            lineOfSight = false
        )

        val result = resolver.resolve(observer(), 42, listOf(blocked))

        assertTrue(result.grants.isEmpty())
        assertEquals(ScenePerceptionGrantResolver141.DenialReason.NO_LINE_OF_SIGHT, result.denied.single().reason)
    }

    @Test
    fun hearingCannotCrossClosedSoundPath() {
        val blocked = candidate(
            "FACT-SILENT-ROOM",
            ScenePerceptionGrantResolver141.Modality.HEARING,
            4.0,
            soundPathOpen = false
        )

        val result = resolver.resolve(observer(), 42, listOf(blocked))

        assertTrue(result.grants.isEmpty())
        assertEquals(ScenePerceptionGrantResolver141.DenialReason.NO_SOUND_PATH, result.denied.single().reason)
    }

    @Test
    fun factFromDifferentLocationIsDeniedEvenWhenDistanceIsSmall() {
        val remote = candidate(
            "FACT-REMOTE",
            ScenePerceptionGrantResolver141.Modality.VISION,
            1.0,
            location = otherRoom
        )

        val result = resolver.resolve(observer(), 42, listOf(remote))

        assertTrue(result.grants.isEmpty())
        assertEquals(ScenePerceptionGrantResolver141.DenialReason.DIFFERENT_LOCATION, result.denied.single().reason)
    }

    @Test
    fun detectionRequiresMatchingCapabilityTag() {
        val chakra = candidate(
            "FACT-CHAKRA",
            ScenePerceptionGrantResolver141.Modality.DETECTION,
            12.0,
            requiredDetectionTag = "CHAKRA"
        )

        val withoutSense = resolver.resolve(observer(detectionTags = emptySet()), 42, listOf(chakra))
        assertTrue(withoutSense.grants.isEmpty())
        assertEquals(
            ScenePerceptionGrantResolver141.DenialReason.MISSING_DETECTION_CAPABILITY,
            withoutSense.denied.single().reason
        )

        val withSense = resolver.resolve(observer(detectionTags = setOf("CHAKRA")), 42, listOf(chakra))
        assertEquals(1, withSense.grants.size)
    }

    private fun observer(detectionTags: Set<String> = setOf("CHAKRA")) =
        ScenePerceptionGrantResolver141.Observer(
            npcUid = npc,
            locationUid = room,
            visionRangeMeters = 30.0,
            hearingRangeMeters = 20.0,
            detectionRangeMeters = 25.0,
            detectionTags = detectionTags
        )

    private fun candidate(
        uid: String,
        modality: ScenePerceptionGrantResolver141.Modality,
        distance: Double,
        location: EntityUid = room,
        lineOfSight: Boolean = true,
        soundPathOpen: Boolean = true,
        requiredDetectionTag: String? = null
    ) = ScenePerceptionGrantResolver141.CandidateFact(
        truthUid = EntityUid(uid),
        subjectUid = subject,
        predicate = "target.state",
        locationUid = location,
        modality = modality,
        distanceMeters = distance,
        lineOfSight = lineOfSight,
        soundPathOpen = soundPathOpen,
        requiredDetectionTag = requiredDetectionTag
    )
}
