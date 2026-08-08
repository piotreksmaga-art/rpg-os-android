package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class TruthSupersession141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    private val subject = EntityUid("SUBJECT-supersession")
    private val previousUid = EntityUid("FACT-supersession-old")
    private val replacementUid = EntityUid("FACT-supersession-new")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/GM141_Truth_Supersession.campaign")
        campaignDir.deleteRecursively()
        campaignDir.mkdirs()
        store = LocalGameStore(context)
        store.setActiveCampaign(campaignDir.name)
        store.bootstrap()
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun supersessionCreatesNonOverlappingTimelineAndDurableLineage() = runBlocking {
        val campaignUid = GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            active.repository.writeTruth(previousFact())
            val supersession = requireNotNull(active.truthSupersessionStore)
            val replacement = replacementFact()

            assertEquals(
                replacement.copy(validFromTurn = 5L),
                supersession.supersedeFact(previousUid, replacement, effectiveTurn = 5L)
            )

            val before = active.repository.getTruth(
                active.campaignUid,
                subject,
                "world.owner",
                atTurnId = 4L
            )
            assertEquals(listOf(previousUid), before.map { it.uid })

            val after = active.repository.getTruth(
                active.campaignUid,
                subject,
                "world.owner",
                atTurnId = 5L
            )
            assertEquals(listOf(replacementUid), after.map { it.uid })
            active.campaignUid
        }

        LocalGameStore(context).openSaveDb().use { db ->
            db.rawQuery(
                "SELECT valid_from_turn,valid_until_turn FROM gm_facts WHERE campaign_id=? AND fact_id=?",
                arrayOf(campaignUid.value, previousUid.value)
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0L, c.getLong(0))
                assertEquals(4L, c.getLong(1))
            }
            db.rawQuery(
                "SELECT valid_from_turn,valid_until_turn FROM gm_facts WHERE campaign_id=? AND fact_id=?",
                arrayOf(campaignUid.value, replacementUid.value)
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(5L, c.getLong(0))
                assertTrue(c.isNull(1))
            }
            db.rawQuery(
                """
                SELECT previous_truth_id,replacement_truth_id,effective_turn
                FROM gm_truth_supersessions
                WHERE campaign_id=?
                """.trimIndent(),
                arrayOf(campaignUid.value)
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(previousUid.value, c.getString(0))
                assertEquals(replacementUid.value, c.getString(1))
                assertEquals(5L, c.getLong(2))
                assertFalse(c.moveToNext())
            }
        }
    }

    @Test
    fun identicalSupersessionRetryIsIdempotentAcrossRestart() = runBlocking {
        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            active.repository.writeTruth(previousFact())
            requireNotNull(active.truthSupersessionStore).supersedeFact(
                previousUid,
                replacementFact(),
                effectiveTurn = 5L
            )
        }

        GameMasterRepositoryFactory(context, LocalGameStore(context)).openActiveSession().use { reopened ->
            val result = requireNotNull(reopened.truthSupersessionStore).supersedeFact(
                previousUid,
                replacementFact(),
                effectiveTurn = 5L
            )
            assertEquals(replacementFact().copy(validFromTurn = 5L), result)
        }

        LocalGameStore(context).openSaveDb().use { db ->
            val lineageCount = db.rawQuery(
                "SELECT COUNT(*) FROM gm_truth_supersessions WHERE previous_truth_id=?",
                arrayOf(previousUid.value)
            ).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
            val replacementCount = db.rawQuery(
                "SELECT COUNT(*) FROM gm_facts WHERE fact_id=?",
                arrayOf(replacementUid.value)
            ).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
            assertEquals(1L, lineageCount)
            assertEquals(1L, replacementCount)
        }
    }

    @Test
    fun semanticMismatchRollsBackWithoutClosingPreviousFact() = runBlocking {
        val campaignUid = GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            active.repository.writeTruth(previousFact())
            val mismatched = replacementFact().copy(predicate = "world.capital")
            val failure = runCatching {
                requireNotNull(active.truthSupersessionStore).supersedeFact(
                    previousUid,
                    mismatched,
                    effectiveTurn = 5L
                )
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(
                failure?.message.orEmpty(),
                failure?.message.orEmpty().contains("TRUTH_SUPERSESSION_SEMANTIC_MISMATCH")
            )
            active.campaignUid
        }

        LocalGameStore(context).openSaveDb().use { db ->
            db.rawQuery(
                "SELECT valid_until_turn FROM gm_facts WHERE campaign_id=? AND fact_id=?",
                arrayOf(campaignUid.value, previousUid.value)
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
            }
            val replacementCount = db.rawQuery(
                "SELECT COUNT(*) FROM gm_facts WHERE campaign_id=? AND fact_id=?",
                arrayOf(campaignUid.value, replacementUid.value)
            ).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
            val lineageCount = db.rawQuery(
                "SELECT COUNT(*) FROM gm_truth_supersessions WHERE campaign_id=?",
                arrayOf(campaignUid.value)
            ).use { c ->
                c.moveToFirst()
                c.getLong(0)
            }
            assertEquals(0L, replacementCount)
            assertEquals(0L, lineageCount)
        }
    }

    private fun previousFact() = CampaignTruth(
        uid = previousUid,
        kind = TruthKind.FACT,
        subjectUid = subject,
        predicate = "world.owner",
        value = "OLD-OWNER",
        validFromTurn = 0L,
        provenance = ProvenanceRecord(
            type = ProvenanceType.CAMPAIGN_EVENT,
            sourceUid = EntityUid("EVENT-old-owner"),
            turnId = 0L,
            confidence = 1.0,
            verified = true
        )
    )

    private fun replacementFact() = CampaignTruth(
        uid = replacementUid,
        kind = TruthKind.FACT,
        subjectUid = subject,
        predicate = "world.owner",
        value = "NEW-OWNER",
        validFromTurn = null,
        provenance = ProvenanceRecord(
            type = ProvenanceType.CAMPAIGN_EVENT,
            sourceUid = EntityUid("EVENT-new-owner"),
            turnId = 5L,
            confidence = 1.0,
            verified = true
        )
    )
}
