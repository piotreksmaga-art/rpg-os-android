package com.rpgos.app

enum class ProtectedConsumerCapability {
    PROJECTION_AUTHORITY,
    PROJECTED_CONSUMER,
    DIAGNOSTIC_PROJECTED_CONSUMER,
    AUTHORITY_INTERNAL,
    PRESENTATION_AFTER_PROJECTION
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

/**
 * Phase 38 Slice B fail-closed inventory. The repository test scans production Kotlin sources for
 * protected-data markers and requires every matching source path to be classified here.
 */
object VisibilityConsumerInventory {
    private fun c(uid: String, path: String, capability: ProtectedConsumerCapability, vararg purposes: String) =
        ProtectedConsumerContract(uid, path, capability, purposes.toSet())

    val contracts: List<ProtectedConsumerContract> = listOf(
        c("visibility-authority", "app/src/main/java/com/rpgos/app/Phase38Visibility.kt", ProtectedConsumerCapability.PROJECTION_AUTHORITY,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("context-builder", "app/src/main/java/com/rpgos/app/ContextBuilder.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.SCENE_VISUALIZATION,
            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("local-game-store", "app/src/main/java/com/rpgos/app/LocalGameStore.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.SCENE_VISUALIZATION,
            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("world-reader", "app/src/main/java/com/rpgos/app/WorldReader.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("npc-world-dashboard", "app/src/main/java/com/rpgos/app/NpcWorldDashboardReader.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("rpgos-view-model", "app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.SCENE_VISUALIZATION,
            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("visual-prompt", "app/src/main/java/com/rpgos/app/VisualPromptBuilder.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION),
        c("backend-client", "app/src/main/java/com/rpgos/app/BackendClient.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING),
        c("json-context-codec", "app/src/main/java/com/rpgos/app/JsonCodec.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION),
        c("phase37-knowledge-authority", "app/src/main/java/com/rpgos/app/Phase37WorldActorKnowledge.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION),
        c("phase37-context-discovery", "app/src/main/java/com/rpgos/app/Phase37KnowledgeContextDiscovery.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION),
        c("campaign-truth-authority", "app/src/main/java/com/rpgos/app/CampaignTruthStore.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
    )

    private val byPath = contracts.associateBy { it.sourcePath }

    fun contractForSource(sourcePath: String): ProtectedConsumerContract? = byPath[sourcePath]

    fun requireClassified(sourcePath: String): ProtectedConsumerContract =
        requireNotNull(contractForSource(sourcePath)) { "RPGOS-VISIBILITY:UNCLASSIFIED_PROTECTED_CONSUMER:$sourcePath" }

    fun validateUnique() {
        require(contracts.map { it.consumerUid }.distinct().size == contracts.size) { "RPGOS-VISIBILITY:DUPLICATE_CONSUMER_UID" }
        require(contracts.map { it.sourcePath }.distinct().size == contracts.size) { "RPGOS-VISIBILITY:DUPLICATE_CONSUMER_PATH" }
    }
}
