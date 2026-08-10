package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase9ReopenStateTest {
    private lateinit var dbFile: File

    @Before fun setUp() {
        dbFile = File.createTempFile("rpgos-phase9-reopen-", ".db")
        dbFile.delete()
    }

    @After fun tearDown() { dbFile.delete() }

    @Test
    fun reopenPreservesOriginFeatureEvolutionUnlockAndActiveState() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            CurrentSchema.ensure(db, "C")
            val store = Phase9Store(db, "C")
            store.registerOrigins("W", listOf(OriginDefinition("O", "W", "o", "Origin", "generic", provenance = "pack")))
            store.registerInnateFeatures("W", listOf(InnateFeatureDefinition("F", "W", "f", "Feature", "generic", provenance = "pack")))
            store.registerEvolutionPaths("W", listOf(EvolutionPathDefinition("P", "W", "p", "Path", provenance = "pack")))
            store.registerEvolutionStages("W", listOf(EvolutionStageDefinition("S", "P", "W", "s", "Stage", provenance = "pack")))
            store.registerEvolutionTransitions("W", listOf(EvolutionTransitionDefinition("ENTRY-S", "W", null, "S", provenance = "pack")))
            store.registerForms("W", listOf(FormDefinition("FORM", "W", "form", "Form", sourceStageUid = "S", provenance = "pack")))
            store.saveOrigin(PlayerOrigin("C", "PLAYER", "O", "PRIMARY", provenance = "typed"))
            store.grantInnateFeature(PlayerInnateFeature("C", "PLAYER", "F", provenance = "typed"))
            store.transitionEvolution("PLAYER", "ENTRY-S", "transition")
            store.unlockForm(PlayerFormUnlock("C", "PLAYER", "FORM", provenance = "unlock"))
            store.activateForm(PlayerActiveForm("C", "PLAYER", "FORM", provenance = "active"))
        }

        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            CurrentSchema.ensure(db, "C")
            val store = Phase9Store(db, "C")
            assertEquals("O", store.playerOrigins("PLAYER").single().originUid)
            assertEquals("F", store.playerInnateFeatures("PLAYER").single().featureUid)
            assertEquals("S", store.evolutionStates("PLAYER").single().currentStageUid)
            assertEquals("S", store.attainedStages("PLAYER").single().stageUid)
            assertEquals("FORM", store.formUnlocks("PLAYER").single().formUid)
            assertEquals("FORM", store.activeForms("PLAYER").single().formUid)

            store.deactivateForm("PLAYER", "FORM")
            assertEquals(1, store.formUnlocks("PLAYER").size)
            assertEquals(0, store.activeForms("PLAYER").size)
        }
    }
}
