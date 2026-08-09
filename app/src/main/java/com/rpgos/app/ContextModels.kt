package com.rpgos.app

data class ContextBundle(
    val playerStatus: Map<String, Any?>,
    val scene: Map<String, Any?>,
    val time: Map<String, Any?>,
    val activeThreads: List<Map<String, Any?>>,
    val relevantNpcs: List<Map<String, Any?>>,
    val npcKnowledge: List<Map<String, Any?>>,
    val missions: List<Map<String, Any?>>,
    val worldPressures: List<Map<String, Any?>>,
    val canonConstraints: List<Map<String, Any?>>,
    val recentChronicle: List<Map<String, Any?>>,
    val retrievedLongTermMemory: List<Map<String, Any?>>,
    val playerSkills: List<Map<String, Any?>> = emptyList(),
    val playerTechniques: List<Map<String, Any?>> = emptyList(),
    val playerOrganizations: List<Map<String, Any?>> = emptyList(),
    val activeWorldEvents: List<Map<String, Any?>> = emptyList(),
    val npcMemories: List<Map<String, Any?>> = emptyList(),
    val campaignTruth: List<Map<String, Any?>> = emptyList(),
    val playerState: Map<String, Any?> = emptyMap(),
    val contextMeta: Map<String, Any?> = emptyMap()
)

data class PatchOperation(
    val op: String,
    val table: String,
    val key: Map<String, Any?>,
    val values: Map<String, Any?>
)

data class StatePatch(
    val transactionId: String,
    val operations: List<PatchOperation>,
    val chapterManifest: Map<String, Any?> = emptyMap(),
    val requiresValidation: Boolean = true
)

data class PatchResult(
    val success: Boolean,
    val appliedOperations: Int,
    val message: String
)
