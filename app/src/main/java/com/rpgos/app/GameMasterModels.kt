package com.rpgos.app

/**
 * Neutral contracts for the RPG OS Game Master engine.
 *
 * The AI model is intentionally separated from storage and mechanics. A
 * campaign may grow to millions of words while a single turn receives only the
 * bounded working set selected by retrieval.
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

/**
 * Untrusted AI proposal. Nothing in this object changes canonical state.
 * Mechanical consequences are requests that must be resolved by Rule Engine.
 */
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
    val diagnostics: GameMasterDiagnostics = GameMasterDiagnostics(),
    val npcKnowledgeWrites: NpcKnowledgeWrites141 = NpcKnowledgeWrites141()
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
    val sourceNpcId: String? = null,
    /** Stable only inside this resolved turn; persistence maps it to the generated durable UID. */
    val truthKey: String? = null
) {
    init {
        require(kind != TruthKind.BELIEF || !holderId.isNullOrBlank()) {
            "BELIEF wymaga holderId."
        }
        require(confidence in 0.0..1.0) { "confidence musi mieścić się w zakresie 0..1." }
        require(knowledgeChannel != KnowledgeChannel141.REPORT || !sourceNpcId.isNullOrBlank()) {
            "BELIEF z kanału REPORT wymaga sourceNpcId."
        }
        require(truthKey == null || truthKey.isNotBlank()) { "truthKey nie może być pusty." }
    }
}

/** Reference either to a durable truth from an older turn or to a truth written in this turn. */
data class TruthRef141(
    val durableUid: String? = null,
    val truthKey: String? = null
) {
    init {
        require(!durableUid.isNullOrBlank() xor !truthKey.isNullOrBlank()) {
            "TruthRef141 wymaga dokładnie jednego z durableUid lub truthKey."
        }
    }
}

data class NpcKnowledgeWrites141(
    val retractions: List<NpcBeliefRetractionWrite141> = emptyList(),
    val inferences: List<NpcInferenceWrite141> = emptyList(),
    val organizationTransmissions: List<OrganizationKnowledgeWrite141> = emptyList(),
    val resolutions: List<NpcKnowledgeResolutionWrite141> = emptyList()
)

data class NpcBeliefRetractionWrite141(
    val holderId: String,
    val retractedBelief: TruthRef141,
    val replacementTruth: TruthRef141,
    val reason: String
)

data class NpcInferenceWrite141(
    val holderId: String,
    val resultingBelief: TruthRef141,
    val premiseTruths: List<TruthRef141>,
    val confidence: Double
) {
    init {
        require(confidence in 0.0..1.0) { "confidence inference musi mieścić się w zakresie 0..1." }
        require(premiseTruths.isNotEmpty()) { "Inference wymaga co najmniej jednej przesłanki." }
    }
}

data class OrganizationKnowledgeWrite141(
    val organizationId: String,
    val membershipId: String,
    val publicationId: String,
    val sourceTruth: TruthRef141,
    val receiverId: String,
    val resultingBelief: TruthRef141,
    val confidence: Double
) {
    init {
        require(confidence in 0.0..1.0) { "confidence organization knowledge musi mieścić się w zakresie 0..1." }
    }
}

data class NpcKnowledgeResolutionWrite141(
    val holderId: String,
    val subjectId: String? = null,
    val predicate: String,
    val competingBeliefs: List<TruthRef141>,
    val winner: TruthRef141? = null,
    val supersededBeliefs: List<TruthRef141> = emptyList(),
    val reason: NpcKnowledgeLifecycle141.ResolutionReason
) {
    init {
        require(predicate.isNotBlank()) { "Resolution predicate nie może być pusty." }
        require(competingBeliefs.size >= 2) { "Resolution wymaga co najmniej dwóch konkurencyjnych BELIEF-ów." }
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
    val actorId: String? = null,
    val targetId: String? = null,
    val causeEventKey: String? = null,
    val payloadJson: String = "{}",
    val visibility: EventVisibility = EventVisibility.WORLD_INTERNAL
)

enum class EventVisibility { PLAYER_KNOWN, NPC_LOCAL, WORLD_INTERNAL, GM_ONLY }

data class GameMasterDiagnostics(
    val contextCharacters: Int = 0,
    val retrievedMemoryCount: Int = 0,
    val retrievedCanonCount: Int = 0,
    val retrievedNpcCount: Int = 0,
    val retrievedThreadCount: Int = 0,
    val warnings: List<String> = emptyList()
)

data class GameMasterValidationIssue(
    val code: String,
    val message: String,
    val severity: ValidationSeverity
)

enum class ValidationSeverity { WARNING, ERROR }

data class GameMasterValidationReport(
    val issues: List<GameMasterValidationIssue> = emptyList()
) {
    val accepted: Boolean get() = issues.none { it.severity == ValidationSeverity.ERROR }
}
