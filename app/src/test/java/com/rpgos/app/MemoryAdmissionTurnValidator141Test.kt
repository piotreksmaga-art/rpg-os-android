package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAdmissionTurnValidator141Test {
    private val campaignUid = "CAMPAIGN-memory-admission"
    private val request = GameMasterTurnRequest(
        campaignId = campaignUid,
        worldPackId = "WORLDPACK-test",
        playerAction = "Działam.",
        currentChapter = 7L
    )
    private val context = GameMasterContext(
        campaignId = campaignUid,
        chapter = 7L,
        scene = section("scene"),
        playerState = section("player"),
        activeWorldState = section("world"),
        activeThreads = section("threads"),
        relevantMemories = section("memory"),
        canonKnowledge = section("canon"),
        rules = section("rules"),
        recentNarrative = section("recent")
    )

    @Test
    fun rejectsSemanticMemoryProposedDirectlyByModel() = runBlocking {
        val report = validator().validate(
            request,
            context,
            GameMasterTurnResult(
                narrative = "Narracja.",
                memoryWrites = listOf(
                    MemoryWrite(
                        memoryType = MemoryType.FACT,
                        subjectId = "NPC-AIKO",
                        text = "Aiko zawsze zdradza sojuszników.",
                        importance = 0.9,
                        chapter = 7L
                    )
                )
            )
        )

        assertFalse(report.accepted)
        assertTrue(report.issues.any { it.code == "MODEL_SEMANTIC_MEMORY_FORBIDDEN" })
    }

    @Test
    fun rejectsEpisodicMemoryWithoutAcceptedEventSource() = runBlocking {
        val report = validator().validate(
            request,
            context,
            GameMasterTurnResult(
                narrative = "Narracja.",
                memoryWrites = listOf(
                    MemoryWrite(
                        memoryType = MemoryType.DISCOVERY,
                        subjectId = "LOC-RUINS",
                        text = "Odkryto ruiny.",
                        importance = 0.7,
                        chapter = 7L
                    )
                )
            )
        )

        assertFalse(report.accepted)
        assertTrue(report.issues.any { it.code == "UNGROUNDED_EPISODIC_MEMORY" })
    }

    @Test
    fun acceptsCurrentTurnEpisodicMemoryGroundedInAcceptedEvent() = runBlocking {
        val report = validator().validate(
            request,
            context,
            GameMasterTurnResult(
                narrative = "Narracja.",
                worldEvents = listOf(
                    WorldEventWrite(
                        eventType = "LOCATION_DISCOVERED",
                        eventKey = "ruins-found",
                        description = "Gracz odkrył ruiny.",
                        effectiveChapter = 7L
                    )
                ),
                memoryWrites = listOf(
                    MemoryWrite(
                        memoryType = MemoryType.DISCOVERY,
                        subjectId = "LOC-RUINS",
                        text = "Odkryto ruiny.",
                        importance = 0.7,
                        chapter = 7L,
                        tags = setOf("event:ruins-found", "ruins")
                    )
                )
            )
        )

        assertTrue(report.issues.joinToString { "${it.code}:${it.message}" }, report.accepted)
    }

    private fun validator(): MemoryAdmissionTurnValidator141 =
        MemoryAdmissionTurnValidator141(
            object : GameMasterTurnValidator {
                override suspend fun validate(
                    request: GameMasterTurnRequest,
                    context: GameMasterContext,
                    result: GameMasterTurnResult
                ): GameMasterValidationReport = GameMasterValidationReport()
            }
        )

    private fun section(name: String) = ContextSection(name, "{}", 1)
}
