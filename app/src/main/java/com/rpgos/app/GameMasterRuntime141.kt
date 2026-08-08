package com.rpgos.app

import android.content.Context

/**
 * Single application-facing entry point for the GM 141 pipeline.
 *
 * UI code does not construct durable IDs or touch SQLite. Every invocation
 * resolves the active campaign identity from campaign.db and closes its session
 * after commit/rollback.
 */
class GameMasterRuntime141(
    private val context: Context,
    private val store: LocalGameStore,
    private val modelGateway: GameMasterModelGateway = GameMasterBackendGateway141()
) {
    private val repositoryFactory = GameMasterRepositoryFactory(context, store)
    private val contextRepository = GameMasterContextRepository141(context, store)

    suspend fun play(
        playerAction: String,
        currentChapter: Long,
        locale: String = "pl-PL",
        contextBudget: ContextBudget = ContextBudget()
    ): GameMasterTurnResult {
        require(playerAction.isNotBlank()) { "Akcja gracza nie może być pusta." }
        require(currentChapter >= 0L) { "Numer rozdziału nie może być ujemny." }

        repositoryFactory.openActiveSession().use { session ->
            val request = GameMasterTurnRequest(
                campaignId = session.campaignUid.value,
                worldPackId = session.worldPackUid.value,
                playerAction = playerAction,
                currentChapter = currentChapter,
                locale = locale,
                contextBudget = contextBudget
            )

            val engine = GameMasterEngine(
                contextRepository = contextRepository,
                modelGateway = modelGateway,
                ruleResolver = GameMasterRuleResolver141(session.repository, session.campaignUid),
                validator = GameMasterTurnValidator141(session.repository, session.campaignUid),
                stateRepository = GameMasterStateRepository141(session.repository, session.campaignUid)
            )
            return engine.play(request)
        }
    }
}
