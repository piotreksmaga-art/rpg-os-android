package com.rpgos.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
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
class SemanticMemoryRetrieval141Test {
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
    fun supersededFactKeepsHistoricalSemanticMemoryButRemovesItFromLaterContext() = runBlocking {
        GameMasterRepositoryFactory(app, store).openActiveSession().use { active ->
            val subject = EntityUid("SUBJECT-semantic-owner")
            val oldFactUid = EntityUid("FACT-owner-old")
            val newFactUid = EntityUid("FACT-owner-new")
            active.repository.writeTruth(
                CampaignTruth(
                    uid = oldFactUid,
                    kind = TruthKind.FACT,
                    subjectUid = subject,
                    predicate = "location.owner",
                    value = "OLD",
                    validFromTurn = 0L,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.WORLD_CANON,
                        sourceUid = EntityUid("CANON-owner"),
                        turnId = 0L,
                        confidence = 1.0,
                        verified = true
                    )
                )
            )

            val semanticStore = requireNotNull(active.semanticMemoryStore)
            val oldMemory = DurableMemoryRecord(
                memoryUid = EntityUid("MEM-owner-old"),
                campaignUid = active.campaignUid,
                kind = DurableMemoryKind.SEMANTIC,
                subjectUid = subject,
                text = "Właścicielem jest OLD.",
                importance = 0.9,
                createdTurn = 0L,
                tags = setOf("owner", "OLD")
            )
            semanticStore.writeFromVerifiedFact(oldMemory, oldFactUid)

            requireNotNull(active.truthSupersessionStore).supersedeFact(
                previousTruthUid = oldFactUid,
                replacement = CampaignTruth(
                    uid = newFactUid,
                    kind = TruthKind.FACT,
                    subjectUid = subject,
                    predicate = "location.owner",
                    value = "NEW",
                    validFromTurn = null,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.SYSTEM_SIMULATION,
                        sourceUid = null,
                        turnId = 1L,
                        confidence = 1.0,
                        verified = true
                    )
                ),
                effectiveTurn = 1L
            )

            val newMemory = DurableMemoryRecord(
                memoryUid = EntityUid("MEM-owner-new"),
                campaignUid = active.campaignUid,
                kind = DurableMemoryKind.SEMANTIC,
                subjectUid = subject,
                text = "Właścicielem jest NEW.",
                importance = 0.9,
                createdTurn = 1L,
                tags = setOf("owner", "NEW")
            )
            semanticStore.writeFromVerifiedFact(newMemory, newFactUid)

            val retriever = GameMasterRetriever141(
                repository = active.repository,
                campaignUid = active.campaignUid,
                semanticEligibility = requireNotNull(active.semanticMemoryEligibility)
            )

            val atTurn0 = retriever.retrieve(
                playerAction = "owner OLD NEW",
                atTurnId = 0L,
                relevantNpcUids = emptyList(),
                memoryLimit = 10
            ).memories
            assertTrue(atTurn0.any { it.memoryUid == oldMemory.memoryUid })
            assertFalse(atTurn0.any { it.memoryUid == newMemory.memoryUid })

            val atTurn1 = retriever.retrieve(
                playerAction = "owner OLD NEW",
                atTurnId = 1L,
                relevantNpcUids = emptyList(),
                memoryLimit = 10
            ).memories
            assertFalse(atTurn1.any { it.memoryUid == oldMemory.memoryUid })
            assertTrue(atTurn1.any { it.memoryUid == newMemory.memoryUid })

            val durableHistory = active.repository.memories(
                campaignUid = active.campaignUid,
                kinds = setOf(DurableMemoryKind.SEMANTIC),
                limit = 10
            )
            assertTrue(durableHistory.any { it.memoryUid == oldMemory.memoryUid })
            assertTrue(durableHistory.any { it.memoryUid == newMemory.memoryUid })
        }
    }

    @Test
    fun semanticMemoryFailsClosedWhenRetrieverHasNoEligibilityProvider() = runBlocking {
        GameMasterRepositoryFactory(app, store).openActiveSession().use { active ->
            val factUid = EntityUid("FACT-fail-closed")
            active.repository.writeTruth(
                CampaignTruth(
                    uid = factUid,
                    kind = TruthKind.FACT,
                    subjectUid = EntityUid("SUBJECT-fail-closed"),
                    predicate = "fact.value",
                    value = "SAFE",
                    validFromTurn = 0L,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.WORLD_CANON,
                        sourceUid = null,
                        turnId = 0L,
                        confidence = 1.0,
                        verified = true
                    )
                )
            )
            val memory = DurableMemoryRecord(
                memoryUid = EntityUid("MEM-fail-closed"),
                campaignUid = active.campaignUid,
                kind = DurableMemoryKind.SEMANTIC,
                text = "SAFE",
                importance = 1.0,
                createdTurn = 0L
            )
            requireNotNull(active.semanticMemoryStore).writeFromVerifiedFact(memory, factUid)

            val result = GameMasterRetriever141(active.repository, active.campaignUid).retrieve(
                playerAction = "SAFE",
                atTurnId = 0L,
                relevantNpcUids = emptyList(),
                memoryLimit = 10
            )
            assertFalse(result.memories.any { it.memoryUid == memory.memoryUid })
        }
    }
}
