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
class SemanticMemoryConsolidator141Test {
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
    fun consolidationIsIncrementalIdempotentAndKeepsExactTruthProvenance() = runBlocking {
        GameMasterRepositoryFactory(app, store).openActiveSession().use { active ->
            val facts = (1..3).map { index ->
                CampaignTruth(
                    uid = EntityUid("FACT-semantic-batch-$index"),
                    kind = TruthKind.FACT,
                    subjectUid = EntityUid("SUBJECT-semantic-batch"),
                    predicate = "batch.value.$index",
                    value = "VALUE-$index",
                    validFromTurn = 0L,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.WORLD_CANON,
                        sourceUid = EntityUid("CANON-batch-$index"),
                        turnId = 0L,
                        confidence = 1.0,
                        verified = true
                    )
                )
            }
            facts.forEach { active.repository.writeTruth(it) }

            // Unverified rows must never become semantic memory candidates.
            active.repository.writeTruth(
                CampaignTruth(
                    uid = EntityUid("FACT-semantic-unverified"),
                    kind = TruthKind.FACT,
                    subjectUid = EntityUid("SUBJECT-semantic-batch"),
                    predicate = "batch.unverified",
                    value = "NO",
                    validFromTurn = 0L,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.IMPORTED_CONTENT,
                        sourceUid = null,
                        turnId = 0L,
                        confidence = 0.5,
                        verified = false
                    )
                )
            )

            val consolidator = requireNotNull(active.semanticMemoryConsolidator)
            assertEquals(1, consolidator.consolidate(throughTurnId = 0L, factLimit = 1).createdMemories)
            assertEquals(1, consolidator.consolidate(throughTurnId = 0L, factLimit = 1).createdMemories)
            assertEquals(1, consolidator.consolidate(throughTurnId = 0L, factLimit = 1).createdMemories)

            val exhausted = consolidator.consolidate(throughTurnId = 0L, factLimit = 1)
            assertEquals(0, exhausted.scannedCandidates)
            assertEquals(0, exhausted.createdMemories)

            val semantic = active.repository.memories(
                campaignUid = active.campaignUid,
                kinds = setOf(DurableMemoryKind.SEMANTIC),
                limit = 20
            ).filter { "auto:semantic:v1" in it.tags }
            assertEquals(3, semantic.size)

            val semanticStore = requireNotNull(active.semanticMemoryStore)
            facts.forEach { fact ->
                val expectedUid = consolidator.memoryUidFor(fact.uid)
                val memory = semantic.single { it.memoryUid == expectedUid }
                assertTrue(memory.text.contains(fact.predicate))
                assertTrue(memory.text.contains(fact.value))
                assertEquals(setOf(fact.uid), semanticStore.sourceTruthUids(memory.memoryUid))
            }
            assertTrue(semantic.none { it.text.contains("batch.unverified") })
        }
    }
}
