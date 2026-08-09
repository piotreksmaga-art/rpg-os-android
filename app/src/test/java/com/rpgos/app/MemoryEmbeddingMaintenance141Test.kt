package com.rpgos.app

import android.app.Application
import android.content.ContentValues
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
class MemoryEmbeddingMaintenance141Test {
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
    fun batchLimitMakesForwardProgressWithoutStarvation() = runBlocking {
        val campaignUid = seedMemories(3)
        store.openSaveDb().use { db ->
            val space = EmbeddingSpace141("test", "v1", 2)
            val provider = BatchMemoryEmbeddingProvider141 { texts, requested ->
                texts.mapIndexed { index, _ ->
                    EmbeddingVector141(requested.provider, requested.model, listOf(1.0, index.toDouble()))
                }
            }
            val maintenance = MemoryEmbeddingMaintenance141(db, campaignUid, space, provider)

            assertEquals(1, maintenance.refresh(10L, 1).indexed)
            assertEquals(1, maintenance.refresh(10L, 1).indexed)
            assertEquals(1, maintenance.refresh(10L, 1).indexed)
            assertEquals(0, maintenance.refresh(10L, 1).indexed)

            val count = db.rawQuery(
                "SELECT COUNT(*) FROM gm_memory_embeddings WHERE campaign_id=? AND provider=? AND model=? AND dimensions=?",
                arrayOf(campaignUid.value, "test", "v1", "2")
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(3, count)
        }
    }

    @Test
    fun memoryContentUpdateInvalidatesAndReindexesExactlyThatRow() = runBlocking {
        val campaignUid = seedMemories(2)
        store.openSaveDb().use { db ->
            val space = EmbeddingSpace141("test", "v1", 2)
            val provider = BatchMemoryEmbeddingProvider141 { texts, requested ->
                texts.map { EmbeddingVector141(requested.provider, requested.model, listOf(1.0, 0.0)) }
            }
            val maintenance = MemoryEmbeddingMaintenance141(db, campaignUid, space, provider)
            assertEquals(2, maintenance.refresh(10L, 10).indexed)
            assertEquals(0, maintenance.refresh(10L, 10).indexed)

            db.update(
                "gm_memories",
                ContentValues().apply { put("text", "zmieniona pamięć") },
                "campaign_id=? AND memory_id=?",
                arrayOf(campaignUid.value, "MEM-1")
            )

            val afterUpdate = db.rawQuery(
                "SELECT COUNT(*) FROM gm_memory_embeddings WHERE campaign_id=?",
                arrayOf(campaignUid.value)
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(1, afterUpdate)
            assertEquals(1, maintenance.refresh(10L, 10).indexed)
        }
    }

    @Test
    fun providerSpaceMismatchIsRejectedBeforeAnyIndexWrite() = runBlocking {
        val campaignUid = seedMemories(1)
        store.openSaveDb().use { db ->
            val space = EmbeddingSpace141("test", "v1", 2)
            val maintenance = MemoryEmbeddingMaintenance141(
                db,
                campaignUid,
                space,
                BatchMemoryEmbeddingProvider141 { texts, _ ->
                    texts.map { EmbeddingVector141("other", "wrong", listOf(1.0, 0.0)) }
                }
            )

            val error = runCatching { maintenance.refresh(10L, 10) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            val count = db.rawQuery(
                "SELECT COUNT(*) FROM gm_memory_embeddings WHERE campaign_id=?",
                arrayOf(campaignUid.value)
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(0, count)
        }
    }

    private suspend fun seedMemories(count: Int): EntityUid =
        GameMasterRepositoryFactory(app, store).openActiveSession().use { active ->
            repeat(count) { index ->
                val number = index + 1
                active.repository.writeMemory(
                    DurableMemoryRecord(
                        memoryUid = EntityUid("MEM-$number"),
                        campaignUid = active.campaignUid,
                        kind = DurableMemoryKind.EPISODIC,
                        subjectUid = null,
                        text = "pamięć $number",
                        importance = 0.8,
                        createdTurn = number.toLong(),
                        sourceEventUids = emptySet(),
                        tags = setOf("test")
                    )
                )
            }
            active.campaignUid
        }
}
