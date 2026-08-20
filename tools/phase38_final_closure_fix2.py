from pathlib import Path

def replace_once(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s: raise SystemExit(f'missing fix2 anchor {path}: {old[:120]!r}')
    if s.count(old)!=1: raise SystemExit(f'nonunique fix2 anchor {path}: {s.count(old)}')
    p.write_text(s.replace(old,new,1))

# Kotlin reflection exposes multiple synthetic constructors; assert structural requirement without assuming one ctor.
p='app/src/test/java/com/rpgos/app/Phase38FinalClosureTest.kt'
replace_once(p,
'''        val generateCtor=ImageGenerationRequest::class.java.declaredConstructors.single { it.parameterTypes.any { t -> t == Phase38VisualAuthorization::class.java } }
        assertNotNull(generateCtor)
        val editCtor=ImageEditRequest::class.java.declaredConstructors.single { it.parameterTypes.any { t -> t == Phase38VisualAuthorization::class.java } }
        assertNotNull(editCtor)
''',
'''        assertTrue(ImageGenerationRequest::class.java.declaredConstructors.any { ctor -> ctor.parameterTypes.any { t -> t == Phase38VisualAuthorization::class.java } })
        assertTrue(ImageEditRequest::class.java.declaredConstructors.any { ctor -> ctor.parameterTypes.any { t -> t == Phase38VisualAuthorization::class.java } })
''')

p='app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt'
replace_once(p,
'''enum class ProtectedConsumerCapability {
    PROJECTION_AUTHORITY,
    PROJECTED_CONSUMER,
    DIAGNOSTIC_PROJECTED_CONSUMER,
    AUTHORITY_INTERNAL,
    PRESENTATION_AFTER_PROJECTION
}''',
'''enum class ProtectedConsumerCapability {
    PROJECTION_AUTHORITY,
    PROJECTED_CONSUMER,
    DIAGNOSTIC_PROJECTED_CONSUMER,
    AUTHORITY_INTERNAL,
    PRESENTATION_AFTER_PROJECTION,
    ADMINISTRATIVE_WRITE_ONLY,
    AUTHORITY_METADATA
}''')
replace_once(p,
'''        c("cloud-gm-backend", "backend/app.py", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING),''',
'''        c("cloud-gm-backend", "backend/app.py", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION,
            VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),''')
replace_once(p,
'''        c("unified-repository", "app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
    )''',
'''        c("unified-repository", "app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("image-generate-request-model", "app/src/main/java/com/rpgos/app/ImageModels.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION),
        c("image-edit-request-model", "app/src/main/java/com/rpgos/app/ImageEditModels.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),
        c("canon-divergence-authority", "app/src/main/java/com/rpgos/app/Phase35CanonDivergence.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("runtime-truth-layer-registry", "app/src/main/java/com/rpgos/app/RuntimeTruthLayerRegistry.kt", ProtectedConsumerCapability.AUTHORITY_METADATA,
            VisibilityPurposeKinds.INTERNAL_SIMULATION),
        c("gameplay-mutation-gate", "app/src/main/java/com/rpgos/app/GameplayMutationGate.kt", ProtectedConsumerCapability.ADMINISTRATIVE_WRITE_ONLY,
            VisibilityPurposeKinds.INTERNAL_SIMULATION),
        c("phase15-schema-migration", "app/src/main/java/com/rpgos/app/Phase15Migration.kt", ProtectedConsumerCapability.ADMINISTRATIVE_WRITE_ONLY,
            VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("schema-migration-manager", "app/src/main/java/com/rpgos/app/MigrationManager.kt", ProtectedConsumerCapability.ADMINISTRATIVE_WRITE_ONLY,
            VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("visibility-consumer-inventory", "app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt", ProtectedConsumerCapability.AUTHORITY_METADATA,
            VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
    )''')

# Preserve accepted Phase37 reconciliation semantics in normal active-player gameplay context:
# unresolved legacy evidence remains visible as non-canonical evidence and is never promoted to authority.
p='app/src/main/java/com/rpgos/app/ContextBuilder.kt'
replace_once(p,
'''        val techniques=if(playerUid!=null){val r=TechniqueStore(saveDb,campaignRef.campaignId).reconciled(playerUid);r.techniques.map{i->val t=i.playerTechnique;linkedMapOf<String,Any?>("entity_uid" to t.characterUid,"technique_uid" to t.techniqueUid,"mastery" to t.baseMastery,"progress_value" to t.progressValue,"canonical" to true)}}else emptyList()
''',
'''        val techniques=if(playerUid!=null){val r=TechniqueStore(saveDb,campaignRef.campaignId).reconciled(playerUid);r.techniques.map{i->val t=i.playerTechnique;linkedMapOf<String,Any?>("entity_uid" to t.characterUid,"technique_uid" to t.techniqueUid,"mastery" to t.baseMastery,"progress_value" to t.progressValue,"canonical" to true)}+r.unresolvedLegacy.map{l->linkedMapOf<String,Any?>("entity_uid" to l.characterUid,"technique_uid" to l.legacyTechniqueUid,"mastery_raw" to l.masteryRaw,"xp_raw" to l.xpRaw,"learned_chapter_raw" to l.learnedChapterRaw,"last_used_chapter_raw" to l.lastUsedChapterRaw,"usage_count_raw" to l.usageCountRaw,"success_count_raw" to l.successCountRaw,"failure_count_raw" to l.failureCountRaw,"is_equipped_raw" to l.isEquippedRaw,"notes_raw" to l.notesRaw,"display_name" to l.displayName,"category" to l.category,"legacy_chakra_cost_override_raw" to l.chakraCostOverrideRaw,"legacy_base_chakra_cost_raw" to l.baseChakraCostRaw,"authority_source" to "LEGACY_UNRESOLVED","canonical" to false)}}else emptyList()
''')
replace_once(p,
'''        val inventory=if(playerUid!=null){val r=InventoryStore(saveDb,campaignRef.campaignId).reconciled(playerUid);r.stacks.map{i->linkedMapOf<String,Any?>("entity_uid" to i.stack.characterUid,"item_definition_uid" to i.stack.itemDefinitionUid,"quantity" to i.stack.quantity,"canonical" to true)}+r.uniqueItems.map{i->linkedMapOf<String,Any?>("entity_uid" to i.entry.characterUid,"item_definition_uid" to i.instance.itemDefinitionUid,"item_instance_uid" to i.entry.itemInstanceUid,"canonical" to true)}}else emptyList()
''',
'''        val inventory=if(playerUid!=null){val r=InventoryStore(saveDb,campaignRef.campaignId).reconciled(playerUid);r.stacks.map{i->linkedMapOf<String,Any?>("entity_uid" to i.stack.characterUid,"item_definition_uid" to i.stack.itemDefinitionUid,"quantity" to i.stack.quantity,"canonical" to true)}+r.uniqueItems.map{i->linkedMapOf<String,Any?>("entity_uid" to i.entry.characterUid,"item_definition_uid" to i.instance.itemDefinitionUid,"item_instance_uid" to i.entry.itemInstanceUid,"canonical" to true)}+r.unresolvedLegacy.map{e->linkedMapOf<String,Any?>("entity_uid" to e.characterUid,"legacy_evidence_uid" to e.evidenceUid,"item_name" to e.itemName,"row_count" to e.rowCount,"raw_fields" to e.rawFields,"authority_source" to "LEGACY_UNRESOLVED","canonical" to false)}}else emptyList()
''')

print('Phase38 final closure fix2 applied')
