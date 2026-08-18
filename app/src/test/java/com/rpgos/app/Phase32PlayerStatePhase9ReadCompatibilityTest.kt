package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32PlayerStatePhase9ReadCompatibilityTest {
    @Test
    fun mutationFreePlayerStatePhase9ProjectionMatchesCanonicalPhase9SnapshotShape() {
        SQLiteDatabase.create(null).use { db ->
            CurrentSchema.ensure(db, "C1")
            val store = Phase9Store(db, "C1")
            store.registerOrigins(
                "G32-WP",
                listOf(OriginDefinition("ORIGIN-G32", "G32-WP", "origin", "Origin", "generic", provenance = "G32"))
            )
            store.registerInnateFeatures(
                "G32-WP",
                listOf(InnateFeatureDefinition("FEATURE-G32", "G32-WP", "feature", "Feature", "generic", provenance = "G32"))
            )
            store.registerEvolutionPaths(
                "G32-WP",
                listOf(EvolutionPathDefinition("PATH-G32", "G32-WP", "path", "Path", provenance = "G32"))
            )
            store.registerEvolutionStages(
                "G32-WP",
                listOf(EvolutionStageDefinition("STAGE-G32", "PATH-G32", "G32-WP", "stage", "Stage", provenance = "G32"))
            )
            store.registerEvolutionTransitions(
                "G32-WP",
                listOf(EvolutionTransitionDefinition("ENTRY-G32", "G32-WP", null, "STAGE-G32", provenance = "G32"))
            )
            store.registerForms(
                "G32-WP",
                listOf(FormDefinition("FORM-G32", "G32-WP", "form", "Form", sourceStageUid = "STAGE-G32", provenance = "G32"))
            )
            store.saveOrigin(PlayerOrigin("C1", "P1", "ORIGIN-G32", "PRIMARY", provenance = "G32"))
            store.grantInnateFeature(PlayerInnateFeature("C1", "P1", "FEATURE-G32", acquiredChapter = 7L, provenance = "G32"))
            store.transitionEvolution("P1", "ENTRY-G32", "G32", attainedChapter = 8L)
            store.unlockForm(PlayerFormUnlock("C1", "P1", "FORM-G32", provenance = "G32"))
            store.activateForm(PlayerActiveForm("C1", "P1", "FORM-G32", activatedAt = 9L, provenance = "G32"))
            db.execSQL(
                "INSERT OR REPLACE INTO active_player_ref(campaign_id,player_uid,updated_at) VALUES('C1','P1',1)"
            )

            val expected = store.snapshot("P1").toContextMap()
            GameplayRuntimeBootstrap.ensureReady(db, "C1")
            GameplayRuntimeBootstrap.requireReady(db, "C1")

            val state = PlayerStateStore(db, "C1").load()
            assertNotNull(state)
            assertEquals(expected, state!!.persistent["phase9"])
        }
    }
}
