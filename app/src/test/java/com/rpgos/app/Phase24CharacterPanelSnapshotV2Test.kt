package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase24CharacterPanelSnapshotV2Test {
    @Test fun P24_01_repeatedBuildAndDeleteRebuildAreIdentical() {
        val source = FakeSource()
        val a = CharacterPanelSnapshotV2Builder.build(source, "C1", "P1")
        val b = CharacterPanelSnapshotV2Builder.build(source, "C1", "P1")
        assertEquals(a, b)
        assertEquals(a.fingerprint, b.fingerprint)
        var transient: CharacterPanelSnapshotV2? = a
        transient = null
        val rebuilt = CharacterPanelSnapshotV2Builder.build(source, "C1", "P1")
        assertEquals(b, rebuilt)
        assertEquals(CharacterPanelSnapshotClassification.DERIVED_PRESENTATION, rebuilt.classification)
        assertEquals(null, transient)
    }

    @Test fun P24_02_rebuildNeverMutatesAuthorityAndStaleSnapshotCannotOverrideSource() {
        val source = FakeSource()
        val stale = CharacterPanelSnapshotV2Builder.build(source, "C1", "P1")
        assertEquals(0, source.writeCount)
        source.strength = 25L
        val fresh = CharacterPanelSnapshotV2Builder.build(source, "C1", "P1")
        assertEquals(0, source.writeCount)
        assertEquals(10L, stale.stats.single().exactValue)
        assertEquals(25L, fresh.stats.single().exactValue)
        assertNotEquals(stale.fingerprint, fresh.fingerprint)
    }

    @Test fun P24_03_exactNumericValuesAndAllExpectedSectionsSurviveProjection() {
        val s = CharacterPanelSnapshotV2Builder.build(FakeSource(), "C1", "P1")
        assertEquals(10L, s.stats.single().exactValue)
        assertEquals(7L, s.resources.single().exactValue)
        assertEquals(33L, s.skills.single().exactProgress)
        assertEquals(44L, s.techniques.single().exactProgress)
        assertEquals("1.25", s.talent.single().canonicalValue)
        assertEquals("2.50", s.potential.single().canonicalValue)
        assertEquals("AWAKENED", s.innateAndEvolution.single().stateUid)
        assertEquals(2L, s.inventory.single().quantity)
        assertEquals("ITEM-1", s.equipment.single().itemInstanceUid)
        assertEquals("P1", s.ownershipAndAssets.single().ownerUid)
        assertEquals(1234L, s.economy.single().exactBalance)
        assertEquals(55L, s.progression.single().exactValue)
        assertEquals(66L, s.projects.single().exactProgress)
        assertEquals(77L, s.relationships.single().exactScore)
        assertEquals(5L, s.goals.single().priority)
    }

    @Test fun P24_04_sourceOrderDoesNotAffectSnapshotIdentity() {
        val source = FakeSource()
        source.reverse = false
        val a = CharacterPanelSnapshotV2Builder.build(source, "C1", "P1")
        source.reverse = true
        val b = CharacterPanelSnapshotV2Builder.build(source, "C1", "P1")
        assertEquals(a, b)
    }

    @Test fun P24_05_snapshotAndBuilderHaveNoDatabaseWriterCapability() {
        val forbidden = listOf("SQLite", "Database", "Store", "Repository", "Dao", "Transaction", "Writer")
        listOf(CharacterPanelSnapshotV2::class.java, CharacterPanelSnapshotV2Builder::class.java).forEach { type ->
            type.declaredFields.forEach { field ->
                forbidden.forEach { token -> assertFalse(field.type.name.contains(token, ignoreCase = true)) }
            }
        }
        assertTrue(CharacterPanelV2ReadSource::class.java.isInterface)
    }

    private class FakeSource : CharacterPanelV2ReadSource {
        var strength = 10L
        var writeCount = 0
        var reverse = false
        private fun <T> ordered(a: T, b: T): List<T> = if (reverse) listOf(b, a) else listOf(a, b)

        override fun identity(campaignUid: String, characterUid: String) =
            ordered(CharacterPanelIdentityV2("NAME", "Smagi"), CharacterPanelIdentityV2("RANK", "GENIN"))
        override fun stats(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelExactValueV2("STR", strength, "EXACT_LONG"))
        override fun resources(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelExactValueV2("CHAKRA", 7L, "EXACT_LONG"))
        override fun skills(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelMasteryV2("SKILL-A", 33L, "Skill A"))
        override fun techniques(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelMasteryV2("TECH-A", 44L, "Technique A"))
        override fun talent(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelProfileValueV2("DOMAIN-A", null, "1.25", "T-E"))
        override fun potential(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelProfileValueV2("DOMAIN-A", "DIM-A", "2.50", "P-E"))
        override fun innateAndEvolution(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelInnateV2("INNATE-A", "AWAKENED", "1"))
        override fun inventory(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelInventoryV2("ITEM-1", "DEF-1", 2L))
        override fun equipment(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelEquipmentV2("HAND", "ITEM-1"))
        override fun ownershipAndAssets(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelOwnershipV2("PROPERTY", "ASSET-1", "P1"))
        override fun economy(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelEconomyV2("RYO", 1234L, "FIN-TX-10"))
        override fun progression(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelProgressionV2("STAT", "STR", 55L, "RECORDED"))
        override fun projects(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelProjectV2("PROJECT-A", "ACTIVE", 66L))
        override fun relationships(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelRelationshipV2("NPC-A", "ALLY", 77L))
        override fun goals(campaignUid: String, characterUid: String) =
            listOf(CharacterPanelGoalV2("GOAL-A", "Train", 5L))
    }
}
