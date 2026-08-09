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
class MemoryEmbeddingIndex141Test {
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
    fun exactCosineRanksOnlyMatchingEmbeddingSpace() = runBlocking {
        val (campaignUid, memories) = createMemories(
            memory("MEM-A", "miecz wykuty w kuźni", 2L),
            memory("MEM-B", "sekretna biblioteka", 3L)
        )

        store.openSaveDb().use { db ->
            val index = SQLiteMemoryEmbeddingIndex141(db, campaignUid)
            index.upsert(memories[0], EmbeddingVector141("test", "model-v1", listOf(1.0, 0.0)))
            index.upsert(memories[1], EmbeddingVector141("test", "model-v1", listOf(0.0, 1.0)))

            val provider = SQLiteExactCosineMemoryCandidateProvider141(
                db = db,
                campaignUid = campaignUid,
                queryEmbeddingProvider = QueryEmbeddingProvider141 {
                    EmbeddingVector141("test", "model-v1", listOf(0.95, 0.05))
                }
            )
            val result = provider.candidates(campaignUid, "kuznia", 10L, 10)
            assertEquals(listOf("MEM-A", "MEM-B"), result.map { it.memory.memoryUid.value })

            val wrongModel = SQLiteExactCosineMemoryCandidateProvider141(
                db = db,
                campaignUid = campaignUid,
                queryEmbeddingProvider = QueryEmbeddingProvider141 {
                    EmbeddingVector141("test", "model-v2", listOf(0.95, 0.05))
                }
            )
            assertTrue(wrongModel.candidates(campaignUid, "kuznia", 10L, 10).isEmpty())
        }
    }

    @Test
    fun futureMemoryIsExcludedBeforeCosineRanking() = runBlocking {
        val (campaignUid, memories) = createMemories(memory("MEM-FUTURE", "przyszłość", 20L))

        store.openSaveDb().use { db ->
            SQLiteMemoryEmbeddingIndex141(db, campaignUid).upsert(
                memories.single(),
                EmbeddingVector141("test", "model-v1", listOf(1.0, 0.0))
            )
            val provider = SQLiteExactCosineMemoryCandidateProvider141(
                db,
                campaignUid,
                QueryEmbeddingProvider141 { EmbeddingVector141("test", "model-v1", listOf(1.0, 0.0)) }
            )
            assertTrue(provider.candidates(campaignUid, "future", 10L, 10).isEmpty())
        }
    }

    @Test
    fun staleContentHashCannotReturnChangedMemory() = runBlocking {
        val (campaignUid, memories) = createMemories(memory("MEM-STALE", "stara treść", 2L))

        store.openSaveDb().use { db ->
            SQLiteMemoryEmbeddingIndex141(db, campaignUid).upsert(
                memories.single(),
                EmbeddingVector141("test", "model-v1", listOf(1.0, 0.0))
            )
            db.update(
                "gm_memories",
                ContentValues().apply { put("text", "zmieniona treść") },
                "campaign_id=? AND memory_id=?",
                arrayOf(campaignUid.value, "MEM-STALE")
            )

            val provider = SQLiteExactCosineMemoryCandidateProvider141(
                db,
                campaignUid,
                QueryEmbeddingProvider141 { EmbeddingVector141("test", "model-v1", listOf(1.0, 0.0)) }
            )
            assertTrue(provider.candidates(campaignUid, "stara", 10L, 10).isEmpty())
        }
    }

    private suspend fun createMemories(vararg memories: DurableMemoryRecord): Pair<EntityUid, List<DurableMemoryRecord>> {
        lateinit var campaignUid: EntityUid
        GameMasterRepositoryFactory(app, store).openActiveSession().use { active ->
            campaignUid = active.campaignUid
            memories.forEach { active.repository.writeMemory(it.copy(campaignUid = campaignUid)) }
        }
        return campaignUid to memories.map { it.copy(campaignUid = campaignUid) }
    }

    private fun memory(uid: String, text: String, turn: Long) = DurableMemoryRecord(
        memoryUid = EntityUid(uid),
        campaignUid = EntityUid("CAMPAIGN-placeholder"),
        kind = DurableMemoryKind.EPISODIC,
        subjectUid = null,
        text = text,
        importance = 0.8,
        createdTurn = turn,
        sourceEventUids = emptySet(),
        tags = setOf("test")
    )
}
