package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase32TruthLayerEnforcementTest {
    @Test fun requiredFamiliesAreExplicitlyClassified() {
        RuntimeTruthLayerRegistry.validateCanonicalInventory()
        val required = listOf("CAMPAIGN_TRUTH","ACTIVE_PLAYER_IDENTITY","BASE_STATS_RESOURCES","SKILLS_TECHNIQUES","INNATE_EVOLUTION","INVENTORY","EQUIPMENT_LOADOUT","OWNERSHIP_HISTORY","FINANCE_AUTHORITY","FINANCE_BALANCE_PROJECTION","DEVELOPMENT_PROJECTS","MODIFIER_INPUTS","RESOLVED_EFFECTIVE_VALUES","TURN_RECEIPTS","EVENT_STORE","CAUSAL_GRAPH","CHARACTER_PANEL_SNAPSHOT_V2","PLAYER_SNAPSHOT_PROFILES","CONTEXT_BUNDLE","CHAPTER_MANIFESTS_SUMMARIES","REBUILDABLE_INDEXES_MATERIALIZATIONS","UI_STATE","BACKUP_PACKAGES","SCHEMA_MIGRATION_REPAIR")
        required.forEach { assertNotNull(RuntimeTruthLayerRegistry.requireFamily(it)) }
    }

    @Test fun derivedCacheAndPresentationCannotMutateAuthority() {
        listOf(RuntimeMutationCapability.DERIVED_REBUILD,RuntimeMutationCapability.CACHE_REBUILD,RuntimeMutationCapability.PRESENTATION_ONLY).forEach { capability ->
            assertTrue(runCatching { RuntimeTruthLayerRegistry.requireAuthoritativeMutation("CAMPAIGN_TRUTH", capability) }.isFailure)
        }
    }

    @Test fun canonicalTurnAndAdministrativeCapabilitiesAreDistinct() {
        RuntimeTruthLayerRegistry.requireAuthoritativeMutation("CAMPAIGN_TRUTH", RuntimeMutationCapability.CANONICAL_TURN)
        RuntimeTruthLayerRegistry.requireAuthoritativeMutation("CAMPAIGN_TRUTH", RuntimeMutationCapability.ADMINISTRATIVE)
        RuntimeTruthLayerRegistry.requireGameplayCapability(RuntimeMutationCapability.CANONICAL_TURN)
        assertTrue(runCatching { RuntimeTruthLayerRegistry.requireGameplayCapability(RuntimeMutationCapability.ADMINISTRATIVE) }.isFailure)
    }

    @Test fun characterPanelRemainsDerivedPresentationOnly() {
        assertEquals(setOf(RuntimeTruthLayer.DERIVED_PRESENTATION), RuntimeTruthLayerRegistry.requireFamily("CHARACTER_PANEL_SNAPSHOT_V2").layers)
        assertEquals(CharacterPanelSnapshotClassification.DERIVED_PRESENTATION, CharacterPanelSnapshotV2Builder.build(object:CharacterPanelV2ReadSource{
            override fun identity(c:String,p:String)=emptyList<CharacterPanelIdentityV2>();override fun stats(c:String,p:String)=emptyList<CharacterPanelExactValueV2>();override fun resources(c:String,p:String)=emptyList<CharacterPanelExactValueV2>();override fun skills(c:String,p:String)=emptyList<CharacterPanelMasteryV2>();override fun techniques(c:String,p:String)=emptyList<CharacterPanelMasteryV2>();override fun talent(c:String,p:String)=emptyList<CharacterPanelProfileValueV2>();override fun potential(c:String,p:String)=emptyList<CharacterPanelProfileValueV2>();override fun innateAndEvolution(c:String,p:String)=emptyList<CharacterPanelInnateV2>();override fun inventory(c:String,p:String)=emptyList<CharacterPanelInventoryV2>();override fun equipment(c:String,p:String)=emptyList<CharacterPanelEquipmentV2>();override fun ownershipAndAssets(c:String,p:String)=emptyList<CharacterPanelOwnershipV2>();override fun economy(c:String,p:String)=emptyList<CharacterPanelEconomyV2>();override fun progression(c:String,p:String)=emptyList<CharacterPanelProgressionV2>();override fun projects(c:String,p:String)=emptyList<CharacterPanelProjectV2>();override fun relationships(c:String,p:String)=emptyList<CharacterPanelRelationshipV2>();override fun goals(c:String,p:String)=emptyList<CharacterPanelGoalV2>()},"C","P").classification)
    }

    @Test fun allPlayerSnapshotProfilesRemainDerivedProjection() {
        PlayerSnapshotProfile.values().forEach { assertEquals(RuntimeTruthLayer.DERIVED_PROJECTION, RuntimeTruthLayerRegistry.requireFamily("PLAYER_SNAPSHOT_PROFILES").layers.single()) }
    }

    @Test fun evidenceDoesNotBecomeDomainAuthority() {
        assertFalse(RuntimeTruthLayerRegistry.requireFamily("TURN_RECEIPTS").isAuthoritative)
        assertFalse(RuntimeTruthLayerRegistry.requireFamily("EVENT_STORE").isAuthoritative)
        assertFalse(RuntimeTruthLayerRegistry.requireFamily("CAUSAL_GRAPH").isAuthoritative)
        assertTrue(RuntimeTruthLayerRegistry.requireFamily("FINANCE_AUTHORITY").isAuthoritative)
        assertTrue(RuntimeTruthLayerRegistry.requireFamily("OWNERSHIP_HISTORY").isAuthoritative)
    }

    @Test fun unknownPersistentFamilyFailsClosed() {
        assertTrue(runCatching { RuntimeTruthLayerRegistry.requireClassifiedTable("future_unclassified_state") }.isFailure)
    }
}
