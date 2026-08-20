package com.rpgos.app

enum class ProtectedConsumerCapability {
    PROJECTION_AUTHORITY,
    PROJECTED_CONSUMER,
    DIAGNOSTIC_PROJECTED_CONSUMER,
    AUTHORITY_INTERNAL,
    PRESENTATION_AFTER_PROJECTION,
    ADMINISTRATIVE_WRITE_ONLY,
    AUTHORITY_METADATA
}

data class ProtectedConsumerContract(
    val consumerUid: String,
    val sourcePath: String,
    val capability: ProtectedConsumerCapability,
    val allowedPurposeUids: Set<String>
) {
    init {
        require(consumerUid.isNotBlank() && sourcePath.isNotBlank())
        require(allowedPurposeUids.isNotEmpty())
    }
}

/** Phase 38 Slice B: fail-closed registry for every discovered protected-information consumer. */
object VisibilityConsumerInventory {
    private fun c(uid: String, path: String, capability: ProtectedConsumerCapability, vararg purposes: String) =
        ProtectedConsumerContract(uid, path, capability, purposes.toSet())

    val contracts: List<ProtectedConsumerContract> = listOf(
        c("visibility-authority", "app/src/main/java/com/rpgos/app/Phase38Visibility.kt", ProtectedConsumerCapability.PROJECTION_AUTHORITY,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("context-builder", "app/src/main/java/com/rpgos/app/ContextBuilder.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.SCENE_VISUALIZATION,
            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("local-game-store", "app/src/main/java/com/rpgos/app/LocalGameStore.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.SCENE_VISUALIZATION,
            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("world-reader", "app/src/main/java/com/rpgos/app/WorldReader.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("npc-world-dashboard", "app/src/main/java/com/rpgos/app/NpcWorldDashboardReader.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("social-reader", "app/src/main/java/com/rpgos/app/SocialReader.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("character-panel-reader", "app/src/main/java/com/rpgos/app/CharacterPanel.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("rpgos-view-model", "app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.SCENE_VISUALIZATION,
            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("visual-prompt", "app/src/main/java/com/rpgos/app/VisualPromptBuilder.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION),
        c("visual-authorization", "app/src/main/java/com/rpgos/app/Phase38VisualAuthorization.kt", ProtectedConsumerCapability.PROJECTION_AUTHORITY,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),
        c("image-generate-client", "app/src/main/java/com/rpgos/app/ImageBackendClient.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION),
        c("image-edit-client", "app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),
        c("backend-client", "app/src/main/java/com/rpgos/app/BackendClient.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING),
        c("cloud-gm-backend", "backend/app.py", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION,
            VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION),
        c("json-context-codec", "app/src/main/java/com/rpgos/app/JsonCodec.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION),
        c("phase37-knowledge-authority", "app/src/main/java/com/rpgos/app/Phase37WorldActorKnowledge.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION),
        c("phase37-context-discovery", "app/src/main/java/com/rpgos/app/Phase37KnowledgeContextDiscovery.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION),
        c("campaign-truth-authority", "app/src/main/java/com/rpgos/app/CampaignTruthStore.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("source-of-truth-registry", "app/src/main/java/com/rpgos/app/SourceOfTruthRegistry.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("persistent-family-registry", "app/src/main/java/com/rpgos/app/BundledCampaignPersistentFamilies.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION),
        c("turn-transaction", "app/src/main/java/com/rpgos/app/TurnTransaction.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION),
        c("unified-repository", "app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
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
    )

    val protectedMarkers: Set<String> = setOf(
        "gm_summary","npc_memories_v2","npc_beliefs","npc_schedules","npc_decisions",
        "CampaignTruthStore(","KnowledgeContextProjection(","campaign_truth","canon_diverg",
        "hidden_pressure","world_pressures","country_economies","relationships_v2",
        "visibility_envelope","Phase38VisualAuthorization","/v1/images/generate","/v1/images/edit"
    )
    fun looksProtected(sourceText:String):Boolean = protectedMarkers.any(sourceText::contains)
    fun requireClassifiedIfProtected(sourcePath:String,sourceText:String):ProtectedConsumerContract? =
        if(looksProtected(sourceText)) requireClassified(sourcePath) else null

    private val byPath = contracts.associateBy { it.sourcePath }
    fun contractForSource(sourcePath: String): ProtectedConsumerContract? = byPath[sourcePath]
    fun requireClassified(sourcePath: String): ProtectedConsumerContract =
        requireNotNull(contractForSource(sourcePath)) { "RPGOS-VISIBILITY:UNCLASSIFIED_PROTECTED_CONSUMER:$sourcePath" }
    fun validateUnique() {
        require(contracts.map { it.consumerUid }.distinct().size == contracts.size) { "RPGOS-VISIBILITY:DUPLICATE_CONSUMER_UID" }
        require(contracts.map { it.sourcePath }.distinct().size == contracts.size) { "RPGOS-VISIBILITY:DUPLICATE_CONSUMER_PATH" }
    }
}
