package com.rpgos.app

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RpgOsViewModelCampaignGate141Test {
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
        GameMasterRepositoryFactory(app, store).openRuntimeSession().use { }
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun blockedCampaignDoesNotEnterSendOrAppendPlayerTurn() = runBlocking {
        val subject = EntityUid("SUBJECT-vm-blocked")
        val oldUid = EntityUid("FACT-vm-old")
        val newUid = EntityUid("FACT-vm-new")

        GameMasterRepositoryFactory(app, store).openActiveSession().use { active ->
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

        val viewModel = RpgOsViewModel(app)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val before = viewModel.messages.value.size
        val playerText = "TA WIADOMOSC NIE MOZE WEJSC DO TURY"
        viewModel.send(playerText)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val access = requireNotNull(viewModel.campaignAccess.value)
        assertFalse(access.canEnterGameMaster)
        assertEquals(CampaignHealthState141.BLOCKED, access.health.state)
        assertFalse(viewModel.messages.value.any { it.role == "player" && it.content == playerText })
        assertTrue(viewModel.messages.value.size >= before + 1)
        assertTrue(viewModel.messages.value.last().content.contains("Kampania zablokowana"))
    }
}
