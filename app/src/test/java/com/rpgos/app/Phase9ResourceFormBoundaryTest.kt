package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase9ResourceFormBoundaryTest {
    private lateinit var dbFile: File

    @Before fun setUp() {
        dbFile = File.createTempFile("rpgos-phase9-resource-", ".db")
        dbFile.delete()
    }

    @After fun tearDown() { dbFile.delete() }

    @Test
    fun activeFormUsesGenericResourceModifierWithoutRewritingCurrentAmount() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            CurrentSchema.ensure(db, "C")
            val resources = StatResourceStore(db, "C")
            resources.registerResourceDefinitions("W", listOf(
                ResourceDefinition("R", "resource", "generic", minValue = 0.0, maxValue = 100.0, worldPackUid = "W")
            ))
            resources.savePlayerResource(PlayerResource("C", "P", "R", 40.0))

            val phase9 = Phase9Store(db, "C")
            phase9.registerForms("W", listOf(FormDefinition("FORM", "W", "form", "Form", provenance = "pack")))
            phase9.registerFormModifierBindings("W", listOf(
                FormModifierBinding("B", "W", "FORM", "R", ModifierTargetKind.RESOURCE_MAXIMUM, ModifierOperation.ADD_FLAT, 25.0, provenance = "form")
            ))
            phase9.unlockForm(PlayerFormUnlock("C", "P", "FORM", provenance = "unlock"))
            phase9.activateForm(PlayerActiveForm("C", "P", "FORM", provenance = "active"))

            assertEquals(40.0, resources.playerResources("P").single().currentValue, 0.0)
            assertTrue(ModifierStore(db, "C").modifiers("P").single { it.sourceType == "PHASE9_FORM" }.sourceActive)

            phase9.deactivateForm("P", "FORM")
            assertEquals(40.0, resources.playerResources("P").single().currentValue, 0.0)
            assertTrue(!ModifierStore(db, "C").modifiers("P").single { it.sourceType == "PHASE9_FORM" }.sourceActive)
        }
    }
}
