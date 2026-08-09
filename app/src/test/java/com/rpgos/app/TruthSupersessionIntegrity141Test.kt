package com.rpgos.app

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
class TruthSupersessionIntegrity141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    private val subject = EntityUid("SUBJECT-integrity-chain")
    private val a = EntityUid("FACT-integrity-A")
    private val b = EntityUid("FACT-integrity-B")
    private val c = EntityUid("FACT-integrity-C")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/GM141_Truth_Supersession_Integrity.campaign")
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
    fun validSupersessionChainPassesOfflineAudit() = runBlocking {
        createValidChain()

        LocalGameStore(context).openSaveDb().use { db ->
            val report = TruthSupersessionIntegrity141(db).check()
            assertTrue(report.issues.joinToString { it.code }, report.ok)
            assertTrue(report.issues.isEmpty())
        }
    }

    @Test
    fun tamperedEffectiveTurnIsDetectedAsBrokenTimeline() = runBlocking {
        val campaignUid = createValidChain()

        LocalGameStore(context).openSaveDb().use { db ->
            db.execSQL(
                """
                UPDATE gm_truth_supersessions
                SET effective_turn=6
                WHERE campaign_id=? AND previous_truth_id=?
                """.trimIndent(),
                arrayOf(campaignUid.value, a.value)
            )

            val report = TruthSupersessionIntegrity141(db).check()
            assertFalse(report.ok)
            assertHasCode(report, "SUPERSESSION_PREVIOUS_WINDOW_MISMATCH")
            assertHasCode(report, "SUPERSESSION_REPLACEMENT_WINDOW_MISMATCH")
        }
    }

    @Test
    fun missingReplacementIsDetectedEvenWhenForeignKeysWereBypassed() = runBlocking {
        val campaignUid = createValidChain()

        LocalGameStore(context).openSaveDb().use { db ->
            db.setForeignKeyConstraintsEnabled(false)
            db.delete(
                "gm_facts",
                "campaign_id=? AND fact_id=?",
                arrayOf(campaignUid.value, c.value)
            )

            val report = TruthSupersessionIntegrity141(db).check()
            assertFalse(report.ok)
            assertHasCode(report, "SUPERSESSION_MISSING_REPLACEMENT")
        }
    }

    @Test
    fun semanticTamperingIsDetectedWithoutRelyingOnValueHash() = runBlocking {
        val campaignUid = createValidChain()

        LocalGameStore(context).openSaveDb().use { db ->
            db.execSQL(
                "UPDATE gm_facts SET predicate='world.capital' WHERE campaign_id=? AND fact_id=?",
                arrayOf(campaignUid.value, b.value)
            )

            val report = TruthSupersessionIntegrity141(db).check()
            assertFalse(report.ok)
            assertHasCode(report, "SUPERSESSION_SEMANTIC_MISMATCH")
        }
    }

    @Test
    fun manualLedgerCycleIsDetected() = runBlocking {
        val campaignUid = createValidChain()

        LocalGameStore(context).openSaveDb().use { db ->
            db.execSQL(
                """
                UPDATE gm_truth_supersessions
                SET replacement_truth_id=?
                WHERE campaign_id=? AND previous_truth_id=?
                """.trimIndent(),
                arrayOf(a.value, campaignUid.value, b.value)
            )

            val report = TruthSupersessionIntegrity141(db).check()
            assertFalse(report.ok)
            assertHasCode(report, "SUPERSESSION_CYCLE")
        }
    }

    @Test
    fun offlineDiagnosticsSurfaceHealthyAndCorruptedSupersessionState() = runBlocking {
        val campaignUid = createValidChain()
        val diagnostics = GameMasterDiagnosticsService141(context, LocalGameStore(context))

        val healthy = diagnostics.report()
        assertTrue(healthy, healthy.contains("truthSupersessionIntegrity=OK"))

        LocalGameStore(context).openSaveDb().use { db ->
            db.execSQL(
                """
                UPDATE gm_truth_supersessions
                SET effective_turn=6
                WHERE campaign_id=? AND previous_truth_id=?
                """.trimIndent(),
                arrayOf(campaignUid.value, a.value)
            )
        }

        val corrupted = diagnostics.report()
        assertTrue(corrupted, corrupted.contains("truthSupersessionIntegrity=ERROR"))
        assertTrue(corrupted, corrupted.contains("SUPERSESSION_PREVIOUS_WINDOW_MISMATCH"))
        assertTrue(corrupted, corrupted.contains("SUPERSESSION_REPLACEMENT_WINDOW_MISMATCH"))
    }

    private suspend fun createValidChain(): EntityUid =
        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            active.repository.writeTruth(fact(a, "OWNER-A", validFrom = 0L, sourceTurn = 0L))
            val supersession = requireNotNull(active.truthSupersessionStore)
            supersession.supersedeFact(
                a,
                fact(b, "OWNER-B", validFrom = null, sourceTurn = 5L),
                effectiveTurn = 5L
            )
            supersession.supersedeFact(
                b,
                fact(c, "OWNER-C", validFrom = null, sourceTurn = 9L),
                effectiveTurn = 9L
            )
            active.campaignUid
        }

    private fun fact(
        uid: EntityUid,
        value: String,
        validFrom: Long?,
        sourceTurn: Long
    ) = CampaignTruth(
        uid = uid,
        kind = TruthKind.FACT,
        subjectUid = subject,
        predicate = "world.owner",
        value = value,
        validFromTurn = validFrom,
        provenance = ProvenanceRecord(
            type = ProvenanceType.CAMPAIGN_EVENT,
            sourceUid = EntityUid("EVENT-${uid.value}"),
            turnId = sourceTurn,
            confidence = 1.0,
            verified = true
        )
    )

    private fun assertHasCode(report: TruthSupersessionIntegrityReport141, code: String) {
        assertTrue(
            report.issues.joinToString(prefix = "[", postfix = "]") { it.code },
            report.issues.any { it.code == code }
        )
    }
}
