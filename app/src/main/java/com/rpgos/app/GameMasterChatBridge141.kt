package com.rpgos.app

import android.app.Application

/**
 * Thin UI integration boundary for GM141.
 *
 * RpgOsViewModel should not know about proposal transport, repository sessions,
 * resolver internals or persistence. This bridge returns only information the
 * chat/read-model layer needs after a fully accepted turn.
 */
data class GameMasterChatOutcome141(
    val narrative: String,
    val contextSummary: String,
    val warnings: List<String>,
    val mutationCount: Int,
    val eventCount: Int,
    val truthCount: Int,
    val memoryCount: Int,
    val chronicleCount: Int
)

class GameMasterChatBridge141(
    private val app: Application,
    private val store: LocalGameStore
) {
    suspend fun play(
        playerAction: String,
        chapter: Int,
        backendUrl: String
    ): GameMasterChatOutcome141 {
        require(playerAction.isNotBlank()) { "Akcja gracza nie może być pusta." }
        require(chapter >= 0) { "Rozdział nie może być ujemny." }

        val result = GameMasterRuntime141(
            context = app,
            store = store,
            backendUrl = backendUrl
        ).play(
            playerAction = playerAction,
            currentChapter = chapter.toLong()
        )

        val summary = buildString {
            append("GM141: mutacje=")
            append(result.stateMutations.size)
            append(", eventy=")
            append(result.worldEvents.size)
            append(", prawdy=")
            append(result.truthWrites.size)
            append(", pamięć=")
            append(result.memoryWrites.size)
            append(", kronika=")
            append(result.chronicleEntries.size)
            if (result.diagnostics.warnings.isNotEmpty()) {
                append(", ostrzeżenia=")
                append(result.diagnostics.warnings.size)
            }
        }

        return GameMasterChatOutcome141(
            narrative = result.narrative,
            contextSummary = summary,
            warnings = result.diagnostics.warnings,
            mutationCount = result.stateMutations.size,
            eventCount = result.worldEvents.size,
            truthCount = result.truthWrites.size,
            memoryCount = result.memoryWrites.size,
            chronicleCount = result.chronicleEntries.size
        )
    }
}
