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
class Phase9DerivedIntegrationTest {
    private lateinit var dbFile: File

    @Before fun setUp() {
        dbFile = File.createTempFile("rpgos-phase9-derived-", ".db")
        dbFile.delete()
    }

    @After fun tearDown() { dbFile.delete() }

    @Test
    fun activeFormEffectsFlowThroughGenericPhase5ResolverAndDeactivateCleanly() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            CurrentSchema.ensure(db, "C")
            val statDef = StatDefinition("STAT", "stat", "generic", worldPackUid = "W")
            val resourceDef = ResourceDefinition("RES", "res", "generic", minValue = 0.0, maxValue = 100.0, worldPackUid = "W")
            val stat = PlayerStat("C", "P", "STAT", 10.0)
            val resource = PlayerResource("C", "P", "RES", 40.0)
            val skillDef = SkillDefinition("SK", "W", "sk", "Skill", "generic", provenance = "pack")
            val skill = PlayerSkill("C", "P", "SK", 20.0, provenance = "base")
            val techniqueDef = TechniqueDefinition("TE", "W", "te", "Technique", "generic", provenance = "pack")
            val technique = PlayerTechnique("C", "P", "TE", 30.0, provenance = "base")

            val stats = StatResourceStore(db, "C")
            stats.registerStatDefinitions("W", listOf(statDef))
            stats.registerResourceDefinitions("W", listOf(resourceDef))
            stats.savePlayerStat(stat)
            stats.savePlayerResource(resource)
            SkillStore(db, "C").apply {
                registerDefinitions("W", listOf(skillDef))
                savePlayerSkill(skill)
            }
            TechniqueStore(db, "C").apply {
                registerDefinitions("W", listOf(techniqueDef))
                savePlayerTechnique(technique)
            }

            val phase9 = Phase9Store(db, "C")
            phase9.registerForms("W", listOf(FormDefinition("FORM", "W", "form", "Form", provenance = "pack")))
            phase9.registerFormModifierBindings("W", listOf(
                FormModifierBinding("STAT-B", "W", "FORM", "STAT", ModifierTargetKind.STAT_EFFECTIVE, ModifierOperation.ADD_FLAT, 5.0, provenance = "form"),
                FormModifierBinding("RES-B", "W", "FORM", "RES", ModifierTargetKind.RESOURCE_MAXIMUM, ModifierOperation.ADD_FLAT, 25.0, provenance = "form"),
                FormModifierBinding("SK-B", "W", "FORM", "SK", ModifierTargetKind.SKILL_EFFECTIVE, ModifierOperation.ADD_FLAT, 6.0, provenance = "form"),
                FormModifierBinding("TE-B", "W", "FORM", "TE", ModifierTargetKind.TECHNIQUE_EFFECTIVE, ModifierOperation.ADD_FLAT, 7.0, provenance = "form")
            ))
            phase9.unlockForm(PlayerFormUnlock("C", "P", "FORM", provenance = "unlock"))
            phase9.activateForm(PlayerActiveForm("C", "P", "FORM", provenance = "active"))

            val active = resolve(statDef, resourceDef, stat, resource, skillDef, skill, techniqueDef, technique, ModifierStore(db, "C").modifiers("P"))
            assertEquals(15.0, active.resolvedStats.single().effectiveValue, 0.0)
            assertEquals(125.0, active.resolvedResources.single().maximumValue!!, 0.0)
            assertEquals(40.0, active.resolvedResources.single().currentValueObserved, 0.0)
            assertEquals(26.0, active.resolvedSkills.single().effectiveMastery, 0.0)
            assertEquals(37.0, active.resolvedTechniques.single().effectiveMastery, 0.0)

            phase9.deactivateForm("P", "FORM")
            val inactive = resolve(statDef, resourceDef, stat, resource, skillDef, skill, techniqueDef, technique, ModifierStore(db, "C").modifiers("P"))
            assertEquals(10.0, inactive.resolvedStats.single().effectiveValue, 0.0)
            assertEquals(100.0, inactive.resolvedResources.single().maximumValue!!, 0.0)
            assertEquals(40.0, inactive.resolvedResources.single().currentValueObserved, 0.0)
            assertEquals(20.0, inactive.resolvedSkills.single().effectiveMastery, 0.0)
            assertEquals(30.0, inactive.resolvedTechniques.single().effectiveMastery, 0.0)
        }
    }

    private fun resolve(
        statDef: StatDefinition,
        resourceDef: ResourceDefinition,
        stat: PlayerStat,
        resource: PlayerResource,
        skillDef: SkillDefinition,
        skill: PlayerSkill,
        techniqueDef: TechniqueDefinition,
        technique: PlayerTechnique,
        modifiers: List<Modifier>
    ) = DerivedValueResolver().resolve(
        DerivedResolutionRequest(
            campaignId = "C",
            characterUid = "P",
            resolutionEpoch = 0L,
            statDefinitions = listOf(statDef),
            resourceDefinitions = listOf(resourceDef),
            playerStats = listOf(stat),
            playerResources = listOf(resource),
            modifiers = modifiers,
            skillDefinitions = listOf(skillDef),
            playerSkills = listOf(skill),
            techniqueDefinitions = listOf(techniqueDef),
            playerTechniques = listOf(technique)
        )
    )
}
