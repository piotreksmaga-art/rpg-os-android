package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class PlayerSnapshotBuilderTest {
    private class Source : PlayerSnapshotReadSource {
        var reads = 0
        private fun <T> read(v: List<T>): List<T> { reads++; return v }
        override fun identity(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelIdentityV2("name", "Smagi")))
        override fun stats(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelExactValueV2("stat.speed", 77, "EXACT")))
        override fun resources(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelExactValueV2("chakra", 50, "EXACT")))
        override fun skills(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelMasteryV2("skill.sword", 60, "Sword")))
        override fun techniques(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelMasteryV2("tech.flash", 40, "Flash")))
        override fun talent(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelProfileValueV2("combat", null, "1.2", "ev.t")))
        override fun potential(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelProfileValueV2("combat", null, "1.5", "ev.p")))
        override fun innateAndEvolution(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelInnateV2("innate.eye", "AWAKENED", null)))
        override fun inventory(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelInventoryV2("item.1", "sword", 1)))
        override fun equipment(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelEquipmentV2("hand", "item.1")))
        override fun ownershipAndAssets(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelOwnershipV2("HOUSE", "house.1", characterUid)))
        override fun economy(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelEconomyV2("ryo", 1234, "finance.entry.9")))
        override fun progression(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelProgressionV2("STAT", "stat.speed", 77, "KNOWN")))
        override fun projects(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelProjectV2("project.1", "ACTIVE", 22)))
        override fun relationships(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelRelationshipV2("npc.1", "ALLY", 80)))
        override fun goals(campaignUid: String, characterUid: String) = read(listOf(CharacterPanelGoalV2("goal.1", "Win", 1)))
        override fun truthViews(campaignUid: String, characterUid: String) = read(listOf(
            PlayerTruthView("truth.fact", PlayerTruthClass.FACT, "npc.1", "alive", "ev.fact"),
            PlayerTruthView("truth.belief", PlayerTruthClass.BELIEF, "npc.1", "trusted", "ev.belief"),
            PlayerTruthView("truth.narrative", PlayerTruthClass.NARRATIVE, "npc.1", "hero", null)
        ))
    }

    @Test fun `all six profiles are deterministic derived projections`() {
        val source = Source()
        PlayerSnapshotProfile.values().forEach { profile ->
            val a = PlayerSnapshotBuilder.build(source, "camp", "pc", profile)
            val b = PlayerSnapshotBuilder.build(source, "camp", "pc", profile)
            assertEquals(PlayerSnapshotClassification.DERIVED_PROJECTION, a.classification)
            assertEquals(a.fingerprint, b.fingerprint)
            assertEquals(a.panel, b.panel)
        }
    }

    @Test fun `profile omission means projection omission only`() {
        val source = Source()
        val full = PlayerSnapshotBuilder.build(source, "camp", "pc", PlayerSnapshotProfile.FULL)
        val combat = PlayerSnapshotBuilder.build(source, "camp", "pc", PlayerSnapshotProfile.COMBAT)
        assertEquals(1234, full.panel.economy.single().exactBalance)
        assertTrue(combat.panel.economy.isEmpty())
        assertEquals(77, combat.panel.stats.single().exactValue)
        assertEquals("item.1", combat.panel.equipment.single().itemInstanceUid)
        assertEquals(1234, PlayerSnapshotBuilder.build(source, "camp", "pc", PlayerSnapshotProfile.ECONOMY).panel.economy.single().exactBalance)
    }

    @Test fun `progression economy and social project their own read concerns`() {
        val source = Source()
        val progression = PlayerSnapshotBuilder.build(source, "camp", "pc", PlayerSnapshotProfile.PROGRESSION).panel
        assertEquals("stat.speed", progression.progression.single().targetUid)
        assertEquals("project.1", progression.projects.single().projectUid)
        assertTrue(progression.economy.isEmpty())
        val social = PlayerSnapshotBuilder.build(source, "camp", "pc", PlayerSnapshotProfile.SOCIAL).panel
        assertEquals("npc.1", social.relationships.single().otherEntityUid)
        assertTrue(social.stats.isEmpty())
    }

    @Test fun `gm context preserves fact belief narrative separation without npc knowledge store`() {
        val gm = PlayerSnapshotBuilder.build(Source(), "camp", "pc", PlayerSnapshotProfile.GM_CONTEXT)
        assertEquals(setOf(PlayerTruthClass.FACT, PlayerTruthClass.BELIEF, PlayerTruthClass.NARRATIVE), gm.truthViews.map { it.truthClass }.toSet())
        assertEquals(3, gm.truthViews.size)
        assertEquals(1234, gm.panel.economy.single().exactBalance)
    }

    @Test fun `building and rebuilding has no writer capability or authoritative mutation`() {
        val source = Source()
        val before = PlayerSnapshotBuilder.build(source, "camp", "pc", PlayerSnapshotProfile.FULL)
        val readsAfterFirst = source.reads
        val rebuilt = PlayerSnapshotBuilder.build(source, "camp", "pc", PlayerSnapshotProfile.FULL)
        assertTrue(source.reads > readsAfterFirst)
        assertEquals(before.fingerprint, rebuilt.fingerprint)
        assertEquals("finance.entry.9", rebuilt.panel.economy.single().authorityRecordUid)
    }
}
