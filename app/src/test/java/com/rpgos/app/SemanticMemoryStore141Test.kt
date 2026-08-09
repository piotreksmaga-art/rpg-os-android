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
class SemanticMemoryStore141Test {
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
    fun verifiedFactCreatesSemanticMemoryWithExactTruthProvenance() = runBlocking {
        GameMasterRepositoryFactory(app, store).openActiveSession().use { active ->
            val factUid = EntityUid("FACT-semantic-source")
            active.repository.writeTruth(
                CampaignTruth(
                    uid = factUid,
                    kind = TruthKind.FACT,
                    subjectUid = EntityUid("SUBJECT-village"),
                    predicate = "location.owner",
                    value = "Konoha",
                    validFromTurn = 0L,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.WORLD_CANON,
                        sourceUid = EntityUid("CANON-location-owner"),
                        turnId = 0L,
                        confidence = 1.0,
                        verified = true
                    )
                )
            )

            val memory = DurableMemoryRecord(
                memoryUid = EntityUid("MEM-semantic-owner"),
                campaignUid = active.campaignUid,
                kind = DurableMemoryKind.SEMANTIC,
                subjectUid = EntityUid("SUBJECT-village"),
                text = "Właścicielem lokacji jest Konoha.",
                importance = 0.9,
                createdTurn = 0L,
                tags = setOf("semantic", "location.owner")
            )
            val semantic = requireNotNull(active.semanticMemoryStore)
            semantic.writeFromVerifiedFact(memory, factUid)
            semantic.writeFromVerifiedFact(memory, factUid)

            val stored = active.repository.memories(
                campaignUid = active.campaignUid,
                kinds = setOf(DurableMemoryKind.SEMANTIC),
                limit = 10
            ).single { it.memoryUid == memory.memoryUid }
            assertEquals(memory.text, stored.text)
            assertEquals(setOf(factUid), semantic.sourceTruthUids(memory.memoryUid))
        }
    }

    @Test
    fun unverifiedFactCannotBecomeSemanticMemory() = runBlocking {
        GameMasterRepositoryFactory(app, store).openActiveSession().use { active ->
            val factUid = EntityUid("FACT-unverified")
            active.repository.writeTruth(
                CampaignTruth(
                    uid = factUid,
                    kind = TruthKind.FACT,
                    subjectUid = EntityUid("SUBJECT-rumor"),
                    predicate = "rumor.value",
                    value = "niepotwierdzone",
                    validFromTurn = 0L,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.IMPORTED_CONTENT,
                        sourceUid = null,
                        turnId = 0L,
                        confidence = 0.4,
                        verified = false
                    )
                )
            )
            val result = runCatching {
                requireNotNull(active.semanticMemoryStore).writeFromVerifiedFact(
                    DurableMemoryRecord(
                        memoryUid = EntityUid("MEM-should-fail"),
                        campaignUid = active.campaignUid,
                        kind = DurableMemoryKind.SEMANTIC,
                        text = "Nie może stać się wiedzą semantyczną.",
                        importance = 0.5,
                        createdTurn = 0L
                    ),
                    factUid
                )
            }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("verified FACT"))
        }
    }

    @Test
    fun semanticMemoryWithoutTruthLinkBlocksRuntimeOpen() = runBlocking {
        GameMasterRepositoryFactory(app, store).openActiveSession().use { active ->
            active.repository.writeMemory(
                DurableMemoryRecord(
                    memoryUid = EntityUid("MEM-corrupt-semantic"),
                    campaignUid = active.campaignUid,
                    kind = DurableMemoryKind.SEMANTIC,
                    text = "Semantyka bez źródła.",
                    importance = 0.8,
                    createdTurn = 0L
                )
            )
        }

        val result = runCatching {
            GameMasterRepositoryFactory(app, store).openRuntimeSession().use { }
        }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SEMANTIC_MEMORY_WITHOUT_TRUTH"))
    }
}
