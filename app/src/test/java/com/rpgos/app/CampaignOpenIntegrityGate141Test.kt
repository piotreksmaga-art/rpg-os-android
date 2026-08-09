package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class CampaignOpenIntegrityGate141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    private val subject = EntityUid("SUBJECT-campaign-open-gate")
    private val previousUid = EntityUid("FACT-campaign-open-old")
    private val replacementUid = EntityUid("FACT-campaign-open-new")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/Naruto_Default.campaign")
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
    fun activeGmSessionFailsClosedWhenCampaignIntegrityIsBroken() = runBlocking {
        createHealthySupersession()
        corruptSupersessionDirectly()

        val failure = runCatching {
            GameMasterRepositoryFactory(context, store).openActiveSession().use { }
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is GameMasterIntegrityGateException141)
        val gate = failure as GameMasterIntegrityGateException141
        assertEquals("CAMPAIGN_OPEN", gate.boundary)
        assertTrue("SUPERSESSION_PREVIOUS_WINDOW_MISMATCH" in gate.errorCodes)
    }

    @Test
    fun legacyContextBuilderCannotBypassCampaignOpenGate() = runBlocking {
        createHealthySupersession()
        corruptSupersessionDirectly()

        val failure = runCatching {
            store.buildContext("Kontynuuj", 1)
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is GameMasterIntegrityGateException141)
        val gate = failure as GameMasterIntegrityGateException141
        assertEquals("CAMPAIGN_OPEN", gate.boundary)
        assertTrue("SUPERSESSION_PREVIOUS_WINDOW_MISMATCH" in gate.errorCodes)
    }

    private suspend fun createHealthySupersession() {
        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            active.repository.writeTruth(
                CampaignTruth(
                    uid = previousUid,
                    kind = TruthKind.FACT,
                    subjectUid = subject,
                    predicate = "world.owner",
                    value = "OLD",
                    validFromTurn = 0L,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.IMPORTED_CONTENT,
                        sourceUid = null,
                        turnId = 0L,
                        confidence = 1.0,
                        verified = true
                    )
                )
            )
            requireNotNull(active.truthSupersessionStore).supersedeFact(
                previousUid,
                CampaignTruth(
                    uid = replacementUid,
                    kind = TruthKind.FACT,
                    subjectUid = subject,
                    predicate = "world.owner",
                    value = "NEW",
                    validFromTurn = null,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.IMPORTED_CONTENT,
                        sourceUid = null,
                        turnId = 1L,
                        confidence = 1.0,
                        verified = true
                    )
                ),
                effectiveTurn = 1L
            )
        }
    }

    private fun corruptSupersessionDirectly() {
        val live = File(campaignDir, "campaign.db")
        SQLiteDatabase.openDatabase(
            live.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.execSQL(
                "UPDATE gm_truth_supersessions SET effective_turn=2 WHERE previous_truth_id=?",
                arrayOf(previousUid.value)
            )
        }
        val report = GameMasterIntegrityGate141.checkFile(live)
        assertTrue("Test musi faktycznie uszkodzić spójność kampanii.", !report.ok)
        assertTrue("SUPERSESSION_PREVIOUS_WINDOW_MISMATCH" in report.errorCodes)
    }
}
