package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
@Config(sdk = [34])
class Phase32RestoreAuthorityTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var campaignDir: File
    private lateinit var dbFile: File
    private lateinit var backupFile: File
    private val campaignUid = ActiveCampaignRef.DEFAULT_CAMPAIGN_ID

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.filesDir, "rpgos").also { it.deleteRecursively() }
        campaignDir = File(root, "saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}").apply { mkdirs() }
        File(campaignDir, "campaign.json").writeText("{\"id\":\"$campaignUid\"}")
        dbFile = File(campaignDir, "campaign.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GroupATransactionTestFixtures.setupFinance(db, campaignUid)
            GameplayRuntimeBootstrap.ensureReady(db, campaignUid)
            GameplayRuntimeBootstrap.requireReady(db, campaignUid)
        }
        backupFile = File(campaignDir, "backups/g32-before.db").apply {
            parentFile?.mkdirs()
            dbFile.copyTo(this, overwrite = true)
        }
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    @Test
    fun restoreManagerRejectsBeforeFileMutationInsideCanonicalTurnAndWorksOutsideGameplay() {
        val safetyBefore = backupFile.parentFile!!.listFiles { f -> f.name.startsWith("pre_restore_") }?.size ?: 0
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val proposal = GroupATransactionTestFixtures.admittedFinancialProposal(
                campaignUid = campaignUid,
                commandUid = "CMD-G32-RESTORE-IN-TURN",
                amountMinor = 5L
            )
            val identity = TurnTransactionIdentity(
                campaignUid,
                "TURN-G32-RESTORE-IN-TURN",
                "CMD-G32-RESTORE-IN-TURN",
                "TX-G32-RESTORE-IN-TURN"
            )
            val failure = runCatching {
                TurnTransactionBoundary.create(
                    db,
                    identity,
                    proposal,
                    failureInjector = TurnFailureInjector { point ->
                        if (point == TurnFailurePoint.BEFORE_DOMAIN_APPLY) {
                            RestoreManager(context).restoreBackup(
                                ActiveCampaignRef.DEFAULT_DIRECTORY,
                                backupFile.absolutePath
                            )
                        }
                    }
                ).commit()
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("RPGOS-G32:GAMEPLAY_CANNOT_INVOKE_ADMIN_AUTHORITY"))
            assertEquals(100L, FinancialStore(db, campaignUid).balance("A"))
            assertEquals(0L, count(db, "turn_transaction_receipts"))
        }
        val safetyAfterRejected = backupFile.parentFile!!.listFiles { f -> f.name.startsWith("pre_restore_") }?.size ?: 0
        assertEquals(safetyBefore, safetyAfterRejected)

        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val proposal = GroupATransactionTestFixtures.admittedFinancialProposal(
                campaignUid = campaignUid,
                commandUid = "CMD-G32-RESTORE-LEGAL",
                amountMinor = 5L
            )
            val identity = TurnTransactionIdentity(
                campaignUid,
                "TURN-G32-RESTORE-LEGAL",
                "CMD-G32-RESTORE-LEGAL",
                "TX-G32-RESTORE-LEGAL"
            )
            assertTrue(TurnTransactionBoundary.create(db, identity, proposal).commit() is TurnExecutionResult.Committed)
            assertEquals(95L, FinancialStore(db, campaignUid).balance("A"))
        }

        val safety = RestoreManager(context).restoreBackup(
            ActiveCampaignRef.DEFAULT_DIRECTORY,
            backupFile.absolutePath
        )
        assertTrue(safety.isFile)

        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { restored ->
            GameplayRuntimeBootstrap.ensureReady(restored, campaignUid)
            GameplayRuntimeBootstrap.requireReady(restored, campaignUid)
            assertEquals(100L, FinancialStore(restored, campaignUid).balance("A"))
            assertEquals(0L, count(restored, "turn_transaction_receipts"))
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }
}
