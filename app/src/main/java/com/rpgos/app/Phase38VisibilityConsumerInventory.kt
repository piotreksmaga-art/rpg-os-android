package com.rpgos.app

enum class ProtectedConsumerCapability {
    TRUSTED_GATEWAY,
    PROJECTION_AUTHORITY,
    PROJECTION_DATA_SOURCE,
    PROJECTED_CONSUMER,
    DIAGNOSTIC_PROJECTED_CONSUMER,
    AUTHORITY_INTERNAL,
    PRESENTATION_AFTER_PROJECTION,
    ADMINISTRATIVE_WRITE_ONLY,
    AUTHORITY_METADATA
}

enum class ProtectedEntryPointClassification {
    TRUSTED_GATEWAY, PROJECTED_CONSUMER, FORBIDDEN_DIRECT_CONSUMER
}

data class ProtectedConsumerContract(
    val consumerUid: String,
    val sourcePath: String,
    val capability: ProtectedConsumerCapability,
    val allowedPurposeUids: Set<String>
) {
    val allowedAudienceKindUids: Set<String> = buildSet {
        if (VisibilityPurposeKinds.PLAYER_UI in allowedPurposeUids) addAll(setOf(AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER))
        if (VisibilityPurposeKinds.GAMEPLAY_NARRATION in allowedPurposeUids) addAll(setOf(AudienceKinds.PLAYER, AudienceKinds.GM_RUNTIME))
        if (VisibilityPurposeKinds.WORLD_ACTOR_REASONING in allowedPurposeUids) add(AudienceKinds.WORLD_ACTOR)
        if (allowedPurposeUids.any { it in setOf(VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION) })
            addAll(setOf(AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER))
        if (VisibilityPurposeKinds.INTERNAL_SIMULATION in allowedPurposeUids) addAll(setOf(AudienceKinds.INTERNAL_SYSTEM, AudienceKinds.GM_RUNTIME))
        if (VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION in allowedPurposeUids) add(AudienceKinds.DEVELOPER_DIAGNOSTIC)
    }
    val projectionSourceUid: String = when(capability) {
        ProtectedConsumerCapability.TRUSTED_GATEWAY -> "PROTECTED_READ_GATEWAY"
        ProtectedConsumerCapability.PROJECTION_AUTHORITY -> "VISIBILITY_AUTHORITY"
        ProtectedConsumerCapability.PROJECTION_DATA_SOURCE -> "RAW_ADAPTER_REQUIRES_PROJECTION"
        ProtectedConsumerCapability.PROJECTED_CONSUMER, ProtectedConsumerCapability.DIAGNOSTIC_PROJECTED_CONSUMER -> "PROTECTED_READ_PROJECTION"
        ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION -> "VISIBILITY_PROJECTION_ENVELOPE"
        ProtectedConsumerCapability.AUTHORITY_INTERNAL -> "CANONICAL_AUTHORITY_INTERNAL"
        ProtectedConsumerCapability.ADMINISTRATIVE_WRITE_ONLY, ProtectedConsumerCapability.AUTHORITY_METADATA -> "NO_PROTECTED_READ"
    }
    init {
        require(consumerUid.isNotBlank() && sourcePath.isNotBlank())
        require(allowedPurposeUids.isNotEmpty())
        require(allowedAudienceKindUids.isNotEmpty())
        require(projectionSourceUid.isNotBlank())
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
        c("context-bundle-disclosure-projector", "app/src/main/java/com/rpgos/app/ContextModels.kt", ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("local-game-store", "app/src/main/java/com/rpgos/app/LocalGameStore.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.SCENE_VISUALIZATION,
            VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("world-reader", "app/src/main/java/com/rpgos/app/WorldReader.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("npc-world-dashboard", "app/src/main/java/com/rpgos/app/NpcWorldDashboardReader.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("canon-character-projection-source", "app/src/main/java/com/rpgos/app/CanonCharacterProjectionReader.kt", ProtectedConsumerCapability.PROJECTION_DATA_SOURCE,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
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
        c("campaign-causal-graph", "app/src/main/java/com/rpgos/app/CampaignCausalGraph.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("phase38-access-authority", "app/src/main/java/com/rpgos/app/Phase38AccessAuthority.kt", ProtectedConsumerCapability.AUTHORITY_INTERNAL,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("phase39-temporal-consumer", "app/src/main/java/com/rpgos/app/Phase39TemporalAndPhase40Scheduler.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("phase41-42-retrieval-consumer", "app/src/main/java/com/rpgos/app/Phase41StructuredAndPhase42GraphRetrieval.kt", ProtectedConsumerCapability.PROJECTED_CONSUMER,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
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
        c("protected-read-gateway", "app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt", ProtectedConsumerCapability.TRUSTED_GATEWAY,
            VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
            VisibilityPurposeKinds.SCENE_VISUALIZATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION, VisibilityPurposeKinds.LOCATION_VISUALIZATION,
            VisibilityPurposeKinds.INTERNAL_SIMULATION, VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),
        c("visibility-consumer-inventory", "app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt", ProtectedConsumerCapability.AUTHORITY_METADATA,
            VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
    )

    val protectedMarkers: Set<String> = setOf(
        "gm_summary","npc_memories_v2","npc_beliefs","npc_schedules","npc_decisions",
        "CampaignTruthStore(","KnowledgeContextProjection(","campaign_truth","canon_diverg","canon_characters_v2",
        "hidden_pressure","world_pressures","country_economies","relationships_v2","InformationCarrierRef",
        "AccessAuthorityStore(","CampaignCausalGraph(","CampaignCausalGraphSchema.TABLE",
        "Phase38AccessRuntimeAuthority.issuePath(","AuthorizationDecision.allow(","EffectiveAccessDecision.granted(",
        "visibility_envelope","Phase38VisualAuthorization","/v1/images/generate","/v1/images/edit"
    )
    private val forbiddenDirectSymbols = listOf(
        "CampaignTruthStore(", "PlayerStateStore(", "KnowledgeStore(", "AccessAuthorityStore(", "CampaignCausalGraph(", "CampaignCausalGraphSchema.TABLE",
        ".openWorldDb()", ".openCoreDb()", "Phase38AccessRuntimeAuthority.issuePath(", "AuthorizationDecision.allow(", "EffectiveAccessDecision.granted("
    )
    fun hasForbiddenDirectProtectedEntryPoint(source: String): Boolean = forbiddenDirectSymbols.any(source::contains)

    private fun trustedGatewayBody(source: String): IntRange? {
        val annotation = source.indexOf("@TrustedProtectedReadGateway")
        if (annotation < 0) return null
        val classPos = source.indexOf("class ProtectedCampaignReadRepository", annotation)
        if (classPos < 0) return null
        val open = source.indexOf('{', classPos)
        if (open < 0) return null
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return open..i
                }
            }
        }
        return null
    }

    fun entryPointClassification(sourcePath: String, sourceText: String): ProtectedEntryPointClassification {
        val directPositions = forbiddenDirectSymbols.flatMap { symbol ->
            buildList {
                var start = 0
                while (true) {
                    val at = sourceText.indexOf(symbol, start)
                    if (at < 0) break
                    add(at)
                    start = at + symbol.length
                }
            }
        }
        if (directPositions.isEmpty()) return ProtectedEntryPointClassification.PROJECTED_CONSUMER
        val contract = contractForSource(sourcePath) ?: return ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER
        return when (contract.capability) {
            ProtectedConsumerCapability.TRUSTED_GATEWAY -> {
                val scope = trustedGatewayBody(sourceText) ?: return ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER
                if (directPositions.all { it in scope }) ProtectedEntryPointClassification.TRUSTED_GATEWAY
                else ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER
            }
            ProtectedConsumerCapability.PROJECTED_CONSUMER,
            ProtectedConsumerCapability.DIAGNOSTIC_PROJECTED_CONSUMER,
            ProtectedConsumerCapability.PRESENTATION_AFTER_PROJECTION -> ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER
            else -> ProtectedEntryPointClassification.PROJECTED_CONSUMER
        }
    }

    fun looksProtected(source: String): Boolean = protectedMarkers.any(source::contains)
    fun requireClassifiedIfProtected(sourcePath:String,sourceText:String):ProtectedConsumerContract? {
        if (!looksProtected(sourceText)) return null
        val contract = requireClassified(sourcePath)
        require(entryPointClassification(sourcePath, sourceText) != ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER) {
            "RPGOS-VISIBILITY:FORBIDDEN_DIRECT_PROTECTED_ENTRY_POINT:$sourcePath"
        }
        return contract
    }

    private val byPath = contracts.associateBy { it.sourcePath }
    fun contractForSource(sourcePath: String): ProtectedConsumerContract? = byPath[sourcePath]
    fun requireClassified(sourcePath: String): ProtectedConsumerContract =
        requireNotNull(contractForSource(sourcePath)) { "RPGOS-VISIBILITY:UNCLASSIFIED_PROTECTED_CONSUMER:$sourcePath" }
    fun validateUnique() {
        require(contracts.map { it.consumerUid }.distinct().size == contracts.size) { "RPGOS-VISIBILITY:DUPLICATE_CONSUMER_UID" }
        require(contracts.map { it.sourcePath }.distinct().size == contracts.size) { "RPGOS-VISIBILITY:DUPLICATE_CONSUMER_PATH" }
        require(contracts.all { it.allowedAudienceKindUids.isNotEmpty() && it.allowedPurposeUids.isNotEmpty() && it.projectionSourceUid.isNotBlank() }) {
            "RPGOS-VISIBILITY:INCOMPLETE_CONSUMER_CONTRACT"
        }
    }
}
