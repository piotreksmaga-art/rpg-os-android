package com.rpgos.app

/**
 * Neutral contracts for the RPG OS Game Master engine.
 *
 * The AI model is intentionally separated from storage. A campaign may grow to
 * millions of words while a single turn receives only the bounded working set
 * selected by retrieval.
 */
data class GameMasterTurnRequest(
    val campaignId: String,
    val worldPackId: String,
    val playerAction: String,
    val currentChapter: Long,
    val locale: String = "pl-PL",
    val contextBudget: ContextBudget = ContextBudget()
)

data class ContextBudget(
    val maxCharacters: Int = 120_000,
    val recentNarrativeCharacters: Int = 24_000,
    val memoryCharacters: Int = 28_000,
    val worldKnowledgeCharacters: Int = 28_000,
    val stateCharacters: Int = 20_000,
    val rulesCharacters: Int = 20_000
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
)

data class ContextSection(
    val title: String,
    val content: String,
    val priority: Int,
    val estimatedCharacters: Int = content.length
)

data class ContextSource(
    val sourceType: String,
    val sourceId: String,
    val reason: String,
    val confidence: Double = 1.0
)

data class GameMasterTurnResult(
    val narrative: String,
    val stateMutations: List<GameStateMutation>,
    val memoryWrites: List<MemoryWrite>,
    val chronicleEntries: List<ChronicleWrite>,
    val worldEvents: List<WorldEventWrite>,
    val diagnostics: GameMasterDiagnostics
)

data class GameStateMutation(
    val entityType: String,
    val entityId: String,
    val field: String,
    val operation: MutationOperation,
    val value: String,
    val reason: String
)

enum class MutationOperation { SET, ADD, REMOVE, INCREMENT, DECREMENT }

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
    PROMISE,
    SECRET,
    DISCOVERY,
    CHARACTER_DEVELOPMENT,
    WORLD_CHANGE,
    PLAYER_PREFERENCE,
    LONG_TERM_THREAD
}

data class ChronicleWrite(
    val chapter: Long,
    val title: String,
    val summary: String,
    val participants: Set<String> = emptySet(),
    val locationIds: Set<String> = emptySet()
)

data class WorldEventWrite(
    val eventType: String,
    val eventKey: String,
    val description: String,
    val effectiveChapter: Long,
    val visibility: EventVisibility = EventVisibility.WORLD_INTERNAL
)

enum class EventVisibility { PLAYER_KNOWN, NPC_LOCAL, WORLD_INTERNAL, GM_ONLY }

data class GameMasterDiagnostics(
    val contextCharacters: Int,
    val retrievedMemoryCount: Int,
    val retrievedCanonCount: Int,
    val retrievedNpcCount: Int,
    val retrievedThreadCount: Int,
    val warnings: List<String> = emptyList()
)
