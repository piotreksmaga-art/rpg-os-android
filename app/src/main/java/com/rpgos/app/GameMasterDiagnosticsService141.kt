package com.rpgos.app

import android.content.Context

/** Read-only diagnostics for GM141. Never invokes AI and never mutates a campaign. */
class GameMasterDiagnosticsService141(
    private val context: Context,
    private val store: LocalGameStore
) {
    suspend fun report(): String {
        store.openSaveDb().use { db ->
            val session = GameMasterSessionReader141(db).read()
            val integrity = GameMasterIntegrity141(db).check()

            if (session == null) {
                return buildString {
                    appendLine("GM141: niezainicjalizowany")
                    append("Integralność: ")
                    append(if (integrity.ok) "OK" else "BŁĄD")
                }
            }

            val snapshot = runCatching {
                GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
                    active.repository.latestSnapshot(active.campaignUid)
                }
            }.getOrNull()

            return buildString {
                appendLine("GM141 OFFLINE DIAGNOSTICS")
                appendLine("campaign=${session.campaignUid}")
                appendLine("worldpack=${session.worldPackUid ?: "—"}")
                appendLine("turn=${session.currentTurn} chapter=${session.currentChapter}")
                appendLine("player=${session.playerUid ?: "—"}")
                appendLine("location=${session.locationUid ?: "—"}")
                appendLine("time=${session.time?.label ?: "—"} | ${session.time?.era ?: "—"} | ${session.time?.season ?: "—"} | ${session.time?.hour ?: "—"}")
                appendLine("stateFields=${session.stateFieldCount} events=${session.eventCount} facts=${session.factCount} memories=${session.memoryCount} divergences=${session.activeDivergenceCount}")
                appendLine(
                    if (snapshot == null) "snapshot=brak"
                    else "snapshot=${snapshot.snapshotUid.value} throughTurn=${snapshot.throughTurnId} events=${snapshot.throughEventSequence}"
                )
                appendLine("integrity=${if (integrity.ok) "OK" else "ERROR"}")

                val warnings = session.consistencyWarnings
                if (warnings.isNotEmpty()) {
                    appendLine("sessionWarnings=${warnings.size}")
                    warnings.forEach { appendLine("- $it") }
                }

                if (integrity.issues.isNotEmpty()) {
                    appendLine("integrityIssues=${integrity.issues.size}")
                    integrity.issues.forEach {
                        appendLine("- ${it.severity} ${it.code} x${it.count}: ${it.message}")
                    }
                }
            }.trimEnd()
        }
    }
}
