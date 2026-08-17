package com.rpgos.app

import android.content.Context
import org.junit.After
import org.junit.Assert.assertFalse
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
class Work026ProductionInitializationEnforcementTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        File(context.filesDir, "rpgos").deleteRecursively()
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        File(context.filesDir, "rpgos").deleteRecursively()
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun production_campaign_open_is_fail_closed_before_first_turn_and_after_process_reopen() {
        val firstStore = LocalGameStore(context)
        firstStore.bootstrap()
        val campaignUid = CampaignSelectionManager(context).activeCampaignRef().campaignId

        firstStore.openGameplaySaveDb().use { db ->
            assertTrue(TurnTransactionReceiptSchema.isReady(db))
            assertTrue(GameplayMutationDatabaseGuards.isInstalled(db))
            assertDirectTruthBlocked(db, campaignUid, "WORK026-FIRST-OPEN")
        }

        // Simulates a later process/store instance. bootstrap + gameplay open must not create
        // a lifecycle window in which the first direct gameplay write can escape enforcement.
        val reopenedStore = LocalGameStore(context)
        reopenedStore.bootstrap()
        reopenedStore.openGameplaySaveDb().use { db ->
            assertTrue(TurnTransactionReceiptSchema.isReady(db))
            assertTrue(GameplayMutationDatabaseGuards.isInstalled(db))
            assertDirectTruthBlocked(db, campaignUid, "WORK026-REOPEN")

            val admin = runCatching {
                withAdministrativeMutationAuthority(db, campaignUid) {
                    CurrentSchema.ensure(db, campaignUid)
                    TurnTransactionReceiptSchema.ensureReady(db)
                    GameplayMutationDatabaseGuards.ensureInstalled(db)
                }
            }
            assertTrue("explicit migration/schema/admin authority must remain valid", admin.isSuccess)
        }
    }

    @Test fun gameplay_repository_surface_exposes_no_raw_writable_campaign_database_or_side_mutator() {
        val campaignMethods = CampaignRepository::class.java.methods.map { it.name }.toSet()
        assertFalse("gameplay repository must not export writable save DB", "openSaveDb" in campaignMethods)
        assertFalse("gameplay repository must not export direct truth writer", "recordTruth" in campaignMethods)
        assertFalse("gameplay repository must not export StatePatch bypass", "applyPatch" in campaignMethods)
        assertTrue("canonical turn commit must be the gameplay mutation facade", "commitTurn" in campaignMethods)

        val publicUnifiedMethods = UnifiedGameRepository::class.java.methods.map { it.name }.toSet()
        assertFalse("UnifiedGameRepository must not re-export writable save DB", "openSaveDb" in publicUnifiedMethods)
        assertFalse("UnifiedGameRepository must not re-export StatePatch bypass", "applyPatch" in publicUnifiedMethods)
    }

    private fun assertDirectTruthBlocked(db: android.database.sqlite.SQLiteDatabase, campaignUid: String, uid: String) {
        val failure = runCatching {
            CampaignTruthStore(db, campaignUid).record(
                kind = TruthKind.FACT,
                predicate = "work026_direct_gameplay_write",
                provenance = Provenance(ProvenanceSourceType.PLAYER_ACTION),
                truthUid = uid
            )
        }.exceptionOrNull()
        assertNotNull("direct normal gameplay truth writer unexpectedly succeeded", failure)
        assertTrue(
            "expected canonical transaction enforcement, got ${failure?.message}",
            failure?.message.orEmpty().contains("CANONICAL_TURN_TRANSACTION_REQUIRED")
        )
    }
}
