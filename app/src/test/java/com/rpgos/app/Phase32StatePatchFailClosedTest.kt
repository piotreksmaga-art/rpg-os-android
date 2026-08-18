package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32StatePatchFailClosedTest {
    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            c.moveToFirst()
            c.getLong(0)
        }

    @Test
    fun productionStatePatchEngineApplyRejectsRepresentativeAuthoritativeWritesWithoutMutation() {
        SQLiteDatabase.create(null).use { save ->
            Phase32ProductionReadyTestFixture.setup(save, "C1")
            SQLiteDatabase.create(null).use { core ->
                val before = mapOf(
                    "financial_accounts" to count(save, "financial_accounts"),
                    "financial_ledger_transactions" to count(save, "financial_ledger_transactions"),
                    "ownership_records" to count(save, "ownership_records"),
                    "player_stats" to count(save, "player_stats"),
                    "player_inventory_stacks" to count(save, "player_inventory_stacks"),
                    "development_projects" to count(save, "development_projects"),
                    "campaign_truth_records" to count(save, "campaign_truth_records")
                )

                val patch = StatePatch(
                    transactionId = "G32-STATEPATCH-AUTHORITY-ATTEMPT",
                    operations = listOf(
                        PatchOperation("update", "financial_accounts", mapOf("campaign_id" to "C1", "account_uid" to "A"), mapOf("provenance" to "ATTACK")),
                        PatchOperation("insert", "campaign_truth_records", mapOf("campaign_id" to "C1"), mapOf("truth_uid" to "ATTACK")),
                        PatchOperation("delete", "ownership_records", mapOf("campaign_id" to "C1"), emptyMap()),
                        PatchOperation("update", "player_stats", mapOf("campaign_id" to "C1"), mapOf("exact_value" to Long.MAX_VALUE)),
                        PatchOperation("insert", "player_inventory_stacks", mapOf("campaign_id" to "C1"), mapOf("quantity" to Long.MAX_VALUE)),
                        PatchOperation("replace", "development_projects", mapOf("campaign_id" to "C1"), mapOf("project_uid" to "ATTACK"))
                    )
                )

                val result = StatePatchEngine(save, SourceOfTruthRegistry(core)).apply(patch)

                assertFalse(result.success)
                assertEquals(0, result.appliedOperations)
                assertTrue(result.message.startsWith(StatePatchEngine.GAMEPLAY_PATCH_BYPASS_BLOCKED))
                assertEquals(before, before.keys.associateWith { count(save, it) })
            }
        }
    }

    @Test
    fun legacySourceOfTruthRegistryCannotOverrideAnyG32PersistentClassification() {
        SQLiteDatabase.create(null).use { core ->
            val registry = SourceOfTruthRegistry(core)
            RuntimeTruthLayerRegistry.validateCanonicalInventory()

            RuntimeTruthLayerRegistry.classifiedPersistentTables().forEach { table ->
                assertFalse(
                    "legacy StatePatch registry regained write authority over G32-owned table: $table",
                    registry.canWrite(table)
                )
            }

            // Bundled narrative planning is now explicitly G32-owned and cannot fall back to StatePatch.
            assertFalse(registry.canWrite("story_threads"))
        }
    }
}
