package com.rpgos.app

data class ContextBudget(
    val maxCharacters: Int = 90_000,
    val recentNarrativeCharacters: Int = 24_000,
    val memoryCharacters: Int = 24_000,
    val worldKnowledgeCharacters: Int = 18_000,
    val stateCharacters: Int = 14_000,
    val rulesCharacters: Int = 10_000
)

data class ContextSource(
    val sourceType: String,
    val sourceId: String,
    val description: String? = null
)

data class ContextSection(
    val title: String,
    val content: String,
    val priority: Int,
    val estimatedCharacters: Int = content.length
)

data class GameMasterContext(
    val campaignId: String,
    val chapter: Long,
    val scene: ContextSection,
    val playerState: ContextSection,
    val activeWorldState: ContextSection,
    val activeThreads: ContextSection,
    val relevantMemories: ContextSection,
    val canonKnowledge: ContextSection,
    val rules: ContextSection,
    val recentNarrative: ContextSection,
    val provenance: List<ContextSource> = emptyList()
) {
    fun totalCharacters(): Int = listOf(
        scene,
        playerState,
        activeWorldState,
        activeThreads,
        relevantMemories,
        canonKnowledge,
        rules,
        recentNarrative
    ).sumOf { it.estimatedCharacters }
}

data class GameMasterTurnRequest(
    val campaignId: String,
    val worldPackId: String,
    val playerAction: String,
    val currentChapter: Long,
    val locale: String = "pl-PL",
    val contextBudget: ContextBudget = ContextBudget()
)

/** Untrusted semantic proposal returned by the model. It is never persisted directly. */
data class GameMasterProposal(
    val narrativeDraft: String,
    val proposedActions: List<ProposedWorldAction> = emptyList(),
    val proposedMemories: List<MemoryWrite> = emptyList(),
    val proposedChronicleEntries: List<ChronicleWrite> = emptyList(),
    val diagnostics: GameMasterDiagnostics = GameMasterDiagnostics()
)

data class ProposedWorldAction(
    val actionType: String,
    val actorId: String? = null,
    val targetId: String? = null,
    val parametersJson: String = "{}",
    val reason: String = ""
)

/** Result after deterministic/rule-based resolution and validation. */
data class GameMasterTurnResult(
    val narrative: String,
    val stateMutations: List<GameStateMutation> = emptyList(),
    val truthWrites: List<TruthWrite> = emptyList(),
    val divergenceWrites: List<DivergenceWrite> = emptyList(),
    val memoryWrites: List<MemoryWrite> = emptyList(),
    val chronicleEntries: List<ChronicleWrite> = emptyList(),
    val worldEvents: List<WorldEventWrite> = emptyList(),
    val diagnostics: GameMasterDiagnostics = GameMasterDiagnostics()
)

data class GameStateMutation(
    val entityType: String,
    val entityId: String,
    val field: String,
    val operation: MutationOperation,
    val oldValue: String? = null,
    val newValue: String? = null,
    val reason: String,
    val causedByEventKey: String? = null
)

enum class MutationOperation { SET, ADD, REMOVE, INCREMENT, DECREMENT }

data class TruthWrite(
    val kind: TruthKind,
    val subjectId: String?,
    val predicate: String,
    val value: String,
    val holderId: String? = null,
    val confidence: Double = 1.0,
    val sourceType: ProvenanceType,
    val sourceId: String? = null,
    val validFromTurn: Long? = null,
    val validUntilTurn: Long? = null,
    val knowledgeChannel: KnowledgeChannel141? = null,
    val sourceNpcId: String? = null
) {
    init {
        require(kind != TruthKind.BELIEF || !holderId.isNullOrBlank()) {
            "BELIEF wymaga holderId."
        }
        require(confidence in 0.0..1.0) { "confidence musi mieścić się w zakresie 0..1." }

        val expectedKnowledgeProvenance = when (knowledgeChannel) {
            KnowledgeChannel141.OBSERVATION -> ProvenanceType.NPC_OBSERVATION
            KnowledgeChannel141.REPORT -> ProvenanceType.NPC_REPORT
            KnowledgeChannel141.RESEARCH -> ProvenanceType.NPC_RESEARCH
            KnowledgeChannel141.INFERENCE -> ProvenanceType.NPC_INFERENCE
            null -> null
        }
        val isNpcKnowledgeProvenance = sourceType in setOf(
            ProvenanceType.NPC_OBSERVATION,
            ProvenanceType.NPC_REPORT,
            ProvenanceType.NPC_RESEARCH,
            ProvenanceType.NPC_INFERENCE
        )
        require(!isNpcKnowledgeProvenance || kind == TruthKind.BELIEF) {
            "Provenance wiedzy NPC może być użyte wyłącznie dla BELIEF."
        }
        require(!isNpcKnowledgeProvenance || knowledgeChannel != null) {
            "BELIEF z provenance wiedzy NPC wymaga knowledgeChannel."
        }
        require(expectedKnowledgeProvenance == null || sourceType == expectedKnowledgeProvenance) {
            "knowledgeChannel $knowledgeChannel nie odpowiada sourceType $sourceType."
        }
        require(knowledgeChannel == null || !sourceId.isNullOrBlank()) {
            "BELIEF z knowledgeChannel wymaga trwałego sourceId."
        }
        require(knowledgeChannel != KnowledgeChannel141.REPORT || !sourceNpcId.isNullOrBlank()) {
            "BELIEF z kanału REPORT wymaga sourceNpcId."
        }
    }
}

data class DivergenceWrite(
    val canonSubjectId: String,
    val canonEventId: String? = null,
    val divergenceType: String,
    val description: String,
    val causedByEventKey: String? = null
)

data class MemoryWrite(
    val memoryType: MemoryType,
    val subjectId: String?,
    val text: String,
    val importance: Double,
    val chapter: Long,
    val tags: Set<String> = emptySet()
)

enum class MemoryType {
    FACT,
    RELATIONSHIP,
    DISCOVERY,
    PROMISE,
    THREAT,
    MYSTERY,
    CHARACTER_ARC,
    WORLD_CHANGE,
    PERSONAL
}

data class ChronicleWrite(
    val title: String,
    val summary: String,
    val chapter: Long,
    val importance: Double = 0.5,
    val relatedEventKeys: Set<String> = emptySet()
)

data class WorldEventWrite(
    val eventType: String,
    val eventKey: String,
    val actorId: String? = null,
    val targetId: String? = null,
    val description: String,
    val payloadJson: String = "{}",
    val causeEventKey: String? = null,
    val effectiveChapter: Long
)

data class GameMasterDiagnostics(
    val retrievedMemories: Int = 0,
    val retrievedCanonRecords: Int = 0,
    val retrievedNpcRecords: Int = 0,
    val contextCharacters: Int = 0,
    val modelLatencyMs: Long = 0,
    val warnings: List<String> = emptyList()
)
