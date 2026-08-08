package com.rpgos.app

/** Retrieval layer backed by campaign storage and the active worldpack. */
interface GameMasterContextRepository {
    suspend fun buildContext(request: GameMasterTurnRequest): GameMasterContext
}

/** Provider-neutral AI gateway. OpenAI or another backend can implement this. */
interface GameMasterModelGateway {
    suspend fun generateTurn(
        request: GameMasterTurnRequest,
        context: GameMasterContext
    ): GameMasterTurnResult
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
 * Important invariant: the model never receives the full campaign transcript.
 * Context is reconstructed from durable state and retrieval for every turn.
 */
class GameMasterEngine(
    private val contextRepository: GameMasterContextRepository,
    private val modelGateway: GameMasterModelGateway,
    private val stateRepository: GameMasterStateRepository
) {
    suspend fun play(request: GameMasterTurnRequest): GameMasterTurnResult {
        require(request.playerAction.isNotBlank()) { "Akcja gracza nie może być pusta." }
        require(request.campaignId.isNotBlank()) { "Brak identyfikatora kampanii." }
        require(request.worldPackId.isNotBlank()) { "Brak worldpacka." }

        val context = contextRepository.buildContext(request)
        validateContext(context, request.contextBudget)

        val result = modelGateway.generateTurn(request, context)
        validateResult(result)

        // The repository is responsible for an atomic transaction. If commit
        // fails, the narrative must not become canonical campaign state.
        stateRepository.commitTurn(request, context, result)
        return result
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

    private fun validateResult(result: GameMasterTurnResult) {
        require(result.narrative.isNotBlank()) { "MG zwrócił pustą narrację." }
        result.memoryWrites.forEach {
            require(it.importance in 0.0..1.0) { "Niepoprawna ważność pamięci." }
        }
    }
}
