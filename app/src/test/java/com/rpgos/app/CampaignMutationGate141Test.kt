package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class CampaignMutationGate141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/Naruto_Default.campaign")
        campaignDir.deleteRecursively()
        campaignDir.mkdirs()
        store = LocalGameStore(context)
        store.setActiveCampaign(campaignDir.name)
        store.bootstrap()
        GameMasterRepositoryFactory(context, store).openRuntimeSession().use { }

        val subject = EntityUid("SUBJECT-mutation-gate")
        val oldUid = EntityUid("FACT-mutation-old")
        val newUid = EntityUid("FACT-mutation-new")
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
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun blockedCampaignRejectsLegacyPatchBeforeMutation() {
        val failure = runCatching {
            store.applyPatch(
                StatePatch(
                    transactionId = "TX-blocked",
                    operations = emptyList()
                )
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message.orEmpty().contains("GM141_INTEGRITY_GATE_FAILED[CAMPAIGN_WRITE_PATCH]"))
    }

    @Test
    fun blockedCampaignRejectsChapterFinalizeBeforeBackup() {
        val before = store.backups().toSet()
        val failure = runCatching { store.finalizeChapter(999, "Blocked save") }.exceptionOrNull()
        val after = store.backups().toSet()

        assertTrue(failure != null)
        assertTrue(failure!!.message.orEmpty().contains("GM141_INTEGRITY_GATE_FAILED[CAMPAIGN_WRITE_SAVE]"))
        assertTrue(before == after)
    }

    @Test
    fun blockedCampaignRejectsVisualWrite() {
        val failure = runCatching {
            store.addVisual(
                title = "Blocked visual",
                kind = "scene",
                uri = "content://blocked",
                chapter = 999,
                relatedEntityUid = null,
                relatedLocationUid = null,
                prompt = "blocked",
                revisedPrompt = null
            )
        }.exceptionOrNull()

        assertTrue(failure != null)
        assertTrue(failure!!.message.orEmpty().contains("GM141_INTEGRITY_GATE_FAILED[CAMPAIGN_WRITE_VISUAL]"))
    }
}
