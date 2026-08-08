package com.rpgos.app

/** Retrieval layer backed by campaign storage and the active worldpack. */
interface GameMasterContextRepository {
    suspend fun buildContext(request: GameMasterTurnRequest): GameMasterContext
}

/** Provider-neutral AI gateway. The model proposes; it never commits mechanics. */
interface GameMasterModelGateway {
    suspend fun generateProposal(
        request: GameMasterTurnRequest,
        context: GameMasterContext
    ): GameMasterProposal
}

/** Deterministic/rule-aware resolution between AI proposal and durable state. */
interface GameMasterRuleResolver {
    suspend fun resolve(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        proposal: GameMasterProposal
    ): GameMasterTurnResult
}

/** Consistency/canon/knowledge validation after mechanics resolution. */
interface GameMasterTurnValidator {
    suspend fun validate(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        result: GameMasterTurnResult
    ): GameMasterValidationReport
}

/** Transactional persistence of all consequences of an accepted turn. */
interface GameMasterStateRepository {
    suspend fun commitTurn(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        result: GameMasterTurnResult
    )
}

/**
 * Orchestrates one RPG turn.
 *
 * Invariants:
 * - model never receives the full campaign transcript;
 * - model proposal cannot directly mutate canonical state;
 * - mechanics/rules resolve consequences before validation;
 * - no narrative becomes canonical until the atomic commit succeeds.
 */
class GameMasterEngine(
    private val contextRepository: GameMasterContextRepository,
    private val modelGateway: GameMasterModelGateway,
    private val ruleResolver: GameMasterRuleResolver,
    private val validator: GameMasterTurnValidator,
    private val stateRepository: GameMasterStateRepository
) {
    suspend fun play(request: GameMasterTurnRequest): GameMasterTurnResult {
        require(request.playerAction.isNotBlank()) { "Akcja gracza nie może być pusta." }
        require(request.campaignId.isNotBlank()) { "Brak identyfikatora kampanii." }
        require(request.worldPackId.isNotBlank()) { "Brak worldpacka." }

        val context = contextRepository.buildContext(request)
        validateContext(context, request.contextBudget)

        val proposal = modelGateway.generateProposal(request, context)
        require(proposal.narrativeDraft.isNotBlank()) { "MG zwrócił pustą propozycję narracji." }

        val resolved = ruleResolver.resolve(request, context, proposal)
        validateResolvedShape(resolved)

        val report = validator.validate(request, context, resolved)
        require(report.accepted) {
            report.issues
                .filter { it.severity == ValidationSeverity.ERROR }
                .joinToString(prefix = "Tura odrzucona: ", separator = "; ") { "${it.code}: ${it.message}" }
        }

        stateRepository.commitTurn(request, context, resolved)
        return resolved
    }

    private fun validateContext(context: GameMasterContext, budget: ContextBudget) {
        val total = listOf(
            context.scene,
            context.playerState,
            context.activeWorldState,
            context.activeThreads,
            context.relevantMemories,
            context.canonKnowledge,
            context.rules,
            context.recentNarrative
        ).sumOf { it.content.length }

        require(total <= budget.maxCharacters) {
            "ContextBundle przekracza limit: $total / ${budget.maxCharacters}."
        }
    }

    private fun validateResolvedShape(result: GameMasterTurnResult) {
        require(result.narrative.isNotBlank()) { "Rule Resolver zwrócił pustą narrację." }
        result.memoryWrites.forEach {
            require(it.importance in 0.0..1.0) { "Niepoprawna ważność pamięci." }
        }
        result.truthWrites.forEach {
            require(it.confidence in 0.0..1.0) { "Niepoprawna pewność faktu/belief." }
        }
    }
}
