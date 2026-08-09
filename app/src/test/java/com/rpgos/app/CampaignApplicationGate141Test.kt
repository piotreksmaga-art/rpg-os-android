package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class CampaignApplicationGate141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

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
        GameMasterRepositoryFactory(context, store).openRuntimeSession().use { }
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun healthyCampaignAllowsUiReadsAndGameMaster() {
        val access = CampaignApplicationGate141(store).inspect()

        assertTrue(access.canReadCampaignData)
        assertTrue(access.canEnterGameMaster)
        assertTrue(access.statusMessage.contains("gotowa"))
    }

    @Test
    fun blockedCampaignDeniesUiReadsAndGameMasterWithIntegrityReason() = runBlocking {
        val subject = EntityUid("SUBJECT-app-gate")
        val oldUid = EntityUid("FACT-app-gate-old")
        val newUid = EntityUid("FACT-app-gate-new")

        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            active.repository.writeTruth(
                CampaignTruth(
                    uid = oldUid,
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
                oldUid,
                CampaignTruth(
                    uid = newUid,
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

        SQLiteDatabase.openDatabase(
            File(campaignDir, "campaign.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.execSQL(
                "UPDATE gm_truth_supersessions SET effective_turn=2 WHERE previous_truth_id=?",
                arrayOf(oldUid.value)
            )
        }

        val access = CampaignApplicationGate141(store).inspect()

        assertFalse(access.canReadCampaignData)
        assertFalse(access.canEnterGameMaster)
        assertTrue(access.statusMessage.contains("CAMPAIGN_OPEN"))
        assertTrue(access.statusMessage.contains("SUPERSESSION_PREVIOUS_WINDOW_MISMATCH"))
    }
}
