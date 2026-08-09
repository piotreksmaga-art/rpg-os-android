package com.rpgos.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoryConsolidator141Test {
    private lateinit var app: Application
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(app.filesDir, "rpgos/saves/Naruto_Default.campaign")
        campaignDir.deleteRecursively()
        campaignDir.mkdirs()
        store = LocalGameStore(app)
        store.setActiveCampaign(campaignDir.name)
        store.bootstrap()
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun significantCommittedEventBecomesOneIdempotentEpisodicMemory() = runBlocking {
        GameMasterRepositoryFactory(app, store).openActiveSession().use { session ->
            val repository = session.repository
            val nextTurn = repository.currentTurnId(session.campaignUid) + 1L
            val significant = EntityUid("EVENT-memory-location")
            val noise = EntityUid("EVENT-memory-custom")

            repository.inTransaction {
                writeTurn(
                    DurableTurnRecord(
                        turnUid = EntityUid("TURN-memory-$nextTurn"),
                        campaignUid = session.campaignUid,
                        turnId = nextTurn,
                        chapter = 1L,
                        playerInput = "Badam ruiny.",
                        narrative = "Odkrywasz starożytne ruiny.",
                        startedAtEpochMs = 1L,
                        committedAtEpochMs = 2L,
                        status = TurnTransactionStatus.COMMITTED
                    )
                )
                appendEvent(
                    DurableCampaignEvent(
                        eventUid = significant,
                        campaignUid = session.campaignUid,
                        turnId = nextTurn,
                        sequence = 1L,
                        type = CampaignEventType.LOCATION_DISCOVERED,
                        actorUid = EntityUid("PLAYER"),
                        targetUid = EntityUid("LOC-RUINS"),
                        description = "Gracz odkrył starożytne ruiny.",
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.SYSTEM_SIMULATION,
                            sourceUid = null,
                            turnId = nextTurn,
                            confidence = 1.0,
                            verified = true
                        )
                    )
                )
                appendEvent(
                    DurableCampaignEvent(
                        eventUid = noise,
                        campaignUid = session.campaignUid,
                        turnId = nextTurn,
                        sequence = 2L,
                        type = CampaignEventType.CUSTOM,
                        actorUid = EntityUid("PLAYER"),
                        description = "Niskowagowy techniczny event.",
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.SYSTEM_SIMULATION,
                            sourceUid = null,
                            turnId = nextTurn,
                            confidence = 1.0,
                            verified = true
                        )
                    )
                )
            }

            val consolidator = MemoryConsolidator141(repository, session.campaignUid)
            val first = consolidator.consolidateEpisodic(throughTurnId = nextTurn)
            assertEquals(1, first.createdMemories)
            assertEquals(1, first.eligibleEvents)

            val memories = repository.memories(
                campaignUid = session.campaignUid,
                kinds = setOf(DurableMemoryKind.EPISODIC),
                limit = 100
            ).filter { "auto:episodic:v1" in it.tags }

            assertEquals(1, memories.size)
            val memory = memories.single()
            assertEquals(setOf(significant), memory.sourceEventUids)
            assertEquals(EntityUid("LOC-RUINS"), memory.subjectUid)
            assertEquals("Gracz odkrył starożytne ruiny.", memory.text)
            assertTrue("event_type:location_discovered" in memory.tags)

            val second = consolidator.consolidateEpisodic(throughTurnId = nextTurn)
            assertEquals(0, second.createdMemories)
            assertEquals(1, second.skippedExisting)

            val afterRetry = repository.memories(
                campaignUid = session.campaignUid,
                kinds = setOf(DurableMemoryKind.EPISODIC),
                limit = 100
            ).filter { "auto:episodic:v1" in it.tags }
            assertEquals(1, afterRetry.size)
            assertEquals(memory.memoryUid, afterRetry.single().memoryUid)
        }
    }
}
