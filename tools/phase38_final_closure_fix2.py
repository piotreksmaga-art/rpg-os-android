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

print('Phase38 final closure fix2 applied')
