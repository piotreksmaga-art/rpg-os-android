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
            val knowledgeIntegrity = KnowledgeTransmissionIntegrity141(db).check()

            if (session == null) {
                return buildString {
                    appendLine("GM141: niezainicjalizowany")
                    appendLine("Integralność: ${if (integrity.ok) "OK" else "BŁĄD"}")
                    append("Ledger wiedzy: ${if (knowledgeIntegrity.ok) "OK" else "BŁĄD"}")
                }
            }

            val repositoryDiagnostics = runCatching {
                GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
                    RepositoryDiagnostics(
                        snapshot = active.repository.latestSnapshot(active.campaignUid),
                        durableNpcKnowledge = active.npcKnowledgeStores != null
                    )
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
                    repositoryDiagnostics?.snapshot?.let {
                        "snapshot=${it.snapshotUid.value} throughTurn=${it.throughTurnId} events=${it.throughEventSequence}"
                    } ?: "snapshot=brak"
                )
                appendLine("integrity=${if (integrity.ok) "OK" else "ERROR"}")
                appendLine("knowledgeIntegrity=${if (knowledgeIntegrity.ok) "OK" else "ERROR"}")
                appendLine(
                    "npcKnowledgePersistence=" +
                        if (repositoryDiagnostics?.durableNpcKnowledge == true) "READY" else "UNAVAILABLE"
                )

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

                if (knowledgeIntegrity.issues.isNotEmpty()) {
                    appendLine("knowledgeIntegrityIssues=${knowledgeIntegrity.issues.size}")
                    knowledgeIntegrity.issues.forEach {
                        appendLine("- ${it.severity} ${it.code} x${it.count}: ${it.message}")
                    }
                }
            }.trimEnd()
        }
    }

    /**
     * Deep, holder-scoped knowledge diagnostics reconstructed only from durable campaign.db data.
     * This never invokes the AI and never grants the NPC access to global campaign truth.
     */
    suspend fun npcKnowledgeReport(holderUid: EntityUid, atTurnId: Long? = null): String {
        return GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            val stores = requireNotNull(active.npcKnowledgeStores) {
                "Trwałe store'y wiedzy NPC nie są dostępne dla aktywnej kampanii."
            }
            val retractions = stores.retractions
            val policy = NpcKnowledgeAccessPolicy141(
                repository = active.repository,
                campaignUid = active.campaignUid,
                retractionStore = retractions
            )
            val timeline = NpcBeliefTimeline141(
                repository = active.repository,
                campaignUid = active.campaignUid,
                retractionStore = retractions
            )
            val explain = NpcKnowledgeExplain141(
                repository = active.repository,
                campaignUid = active.campaignUid,
                retractionStore = retractions,
                inferenceQueryStore = stores.inferences
            )
            val diagnostics = NpcKnowledgeDiagnostics141(policy, timeline, explain)
            val result = diagnostics.report(holderUid = holderUid, atTurnId = atTurnId)

            buildString {
                appendLine("GM141 NPC KNOWLEDGE DIAGNOSTICS")
                appendLine("campaign=${active.campaignUid.value}")
                appendLine("holder=${result.holderUid.value}")
                appendLine("turn=${result.atTurnId}")
                appendLine("status=${if (result.ok) "OK" else "ERROR"}")
                appendLine("activeBeliefs=${result.activeBeliefCount}")
                appendLine("unresolvedConflicts=${result.unresolvedConflictCount}")
                appendLine("recentChanges=${result.recentChanges.size}")
                appendLine("issues=${result.issues.size}")

                if (result.effectiveBeliefs.isNotEmpty()) {
                    appendLine("beliefs:")
                    result.effectiveBeliefs.forEach { belief ->
                        appendLine(
                            "- ${belief.truthUid.value} ${belief.predicate}=${belief.value} " +
                                "confidence=${belief.confidence} provenance=${belief.provenance} " +
                                "status=${belief.status} lineageDepth=${belief.lineageDepth}"
                        )
                    }
                }

                if (result.unresolvedConflicts.isNotEmpty()) {
                    appendLine("conflicts:")
                    result.unresolvedConflicts.forEach { conflict ->
                        appendLine(
                            "- ${conflict.predicate}: " +
                                conflict.competingBeliefs.joinToString(" | ") {
                                    "${it.uid.value}=${it.value}@${it.provenance.confidence}"
                                }
                        )
                    }
                }

                if (result.recentChanges.isNotEmpty()) {
                    appendLine("recentKnowledgeChanges:")
                    result.recentChanges.forEach { change ->
                        appendLine(
                            "- ${change.beliefUid.value} status=${change.status} " +
                                "learned=${change.learnedTurn ?: "—"} ended=${change.endedTurn ?: "—"} " +
                                "replacement=${change.replacementTruthUid?.value ?: "—"}"
                        )
                    }
                }

                if (result.issues.isNotEmpty()) {
                    appendLine("knowledgeIssues:")
                    result.issues.forEach { issue ->
                        appendLine(
                            "- ${issue.severity} ${issue.code}: ${issue.message}" +
                                (issue.truthUid?.let { " [${it.value}]" } ?: "")
                        )
                    }
                }
            }.trimEnd()
        }
    }

    private data class RepositoryDiagnostics(
        val snapshot: CampaignSnapshotRef?,
        val durableNpcKnowledge: Boolean
    )
}
