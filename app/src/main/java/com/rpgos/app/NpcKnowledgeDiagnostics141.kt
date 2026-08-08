package com.rpgos.app

/**
 * Read-only explainability snapshot for one NPC. It combines the effective knowledge view,
 * belief history and lineage diagnostics without mutating campaign state.
 */
class NpcKnowledgeDiagnostics141(
    private val policy: NpcKnowledgeAccessPolicy141,
    private val timeline: NpcBeliefTimeline141,
    private val explain: NpcKnowledgeExplain141
) {
    enum class Severity { INFO, WARNING, ERROR }

    data class Issue(
        val severity: Severity,
        val code: String,
        val message: String,
        val truthUid: EntityUid? = null
    )

    data class BeliefSummary(
        val truthUid: EntityUid,
        val subjectUid: EntityUid?,
        val predicate: String,
        val value: String,
        val confidence: Double,
        val provenance: ProvenanceType,
        val learnedTurn: Long?,
        val status: NpcBeliefTimeline141.Status,
        val lineageDepth: Int,
        val lineageCycle: Boolean,
        val lineageTruncated: Boolean
    )

    data class RecentChange(
        val beliefUid: EntityUid,
        val learnedTurn: Long?,
        val endedTurn: Long?,
        val status: NpcBeliefTimeline141.Status,
        val replacementTruthUid: EntityUid?
    )

    data class Report(
        val holderUid: EntityUid,
        val atTurnId: Long,
        val effectiveBeliefs: List<BeliefSummary>,
        val unresolvedConflicts: List<NpcKnowledgeLifecycle141.Conflict>,
        val recentChanges: List<RecentChange>,
        val issues: List<Issue>
    ) {
        val ok: Boolean = issues.none { it.severity == Severity.ERROR }
        val activeBeliefCount: Int = effectiveBeliefs.size
        val unresolvedConflictCount: Int = unresolvedConflicts.size
    }

    suspend fun report(
        holderUid: EntityUid,
        atTurnId: Long? = null,
        recentChangeLimit: Int = 20,
        beliefLimit: Int = 200
    ): Report {
        require(recentChangeLimit in 1..200) { "recentChangeLimit musi należeć do 1..200." }
        require(beliefLimit in 1..NpcBeliefTimeline141.MAX_BELIEF_QUERY_LIMIT) {
            "beliefLimit musi należeć do 1..${NpcBeliefTimeline141.MAX_BELIEF_QUERY_LIMIT}."
        }

        val view = policy.buildView(holderUid, atTurnId = atTurnId, beliefLimit = beliefLimit)
        val history = timeline.query(
            holderUid,
            atTurnId = view.atTurnId,
            limit = NpcBeliefTimeline141.MAX_BELIEF_QUERY_LIMIT
        )
        val issues = mutableListOf<Issue>()

        view.unresolvedBeliefConflicts.forEach { conflict ->
            issues += Issue(
                severity = Severity.WARNING,
                code = "NPC_KNOWLEDGE_UNRESOLVED_CONFLICT",
                message = "Nierozstrzygnięty konflikt ${conflict.predicate}: ${conflict.competingBeliefs.map { it.value }.distinct()}."
            )
        }

        val summaries = view.beliefs.map { belief ->
            val explanation = explain.explain(
                holderUid = holderUid,
                beliefUid = belief.uid,
                atTurnId = view.atTurnId
            )
            if (explanation.cycleDetected) {
                issues += Issue(
                    Severity.ERROR,
                    "NPC_KNOWLEDGE_LINEAGE_CYCLE",
                    "Wykryto cykl lineage dla ${belief.uid.value}.",
                    belief.uid
                )
            }
            if (explanation.lineageTruncated) {
                issues += Issue(
                    Severity.WARNING,
                    "NPC_KNOWLEDGE_LINEAGE_TRUNCATED",
                    "Lineage dla ${belief.uid.value} przekroczył limit głębokości.",
                    belief.uid
                )
            }
            if (explanation.terminalSourceUid != null &&
                explanation.provenanceChain.lastOrNull()?.provenance?.sourceUid != null &&
                !explanation.cycleDetected && !explanation.lineageTruncated
            ) {
                issues += Issue(
                    Severity.WARNING,
                    "NPC_KNOWLEDGE_LINEAGE_MISSING_SOURCE",
                    "Lineage dla ${belief.uid.value} kończy się na brakującym źródle ${explanation.terminalSourceUid.value}.",
                    belief.uid
                )
            }

            BeliefSummary(
                truthUid = belief.uid,
                subjectUid = belief.subjectUid,
                predicate = belief.predicate,
                value = belief.value,
                confidence = belief.provenance.confidence,
                provenance = belief.provenance.type,
                learnedTurn = belief.provenance.turnId ?: belief.validFromTurn,
                status = explanation.status,
                lineageDepth = explanation.provenanceChain.size,
                lineageCycle = explanation.cycleDetected,
                lineageTruncated = explanation.lineageTruncated
            )
        }

        val recent = history.entries
            .sortedByDescending { maxOf(it.learnedTurn ?: Long.MIN_VALUE, it.endedTurn ?: Long.MIN_VALUE) }
            .take(recentChangeLimit)
            .map {
                RecentChange(
                    beliefUid = it.belief.uid,
                    learnedTurn = it.learnedTurn,
                    endedTurn = it.endedTurn,
                    status = it.status,
                    replacementTruthUid = it.retraction?.replacementTruthUid
                )
            }

        return Report(
            holderUid = holderUid,
            atTurnId = view.atTurnId,
            effectiveBeliefs = summaries,
            unresolvedConflicts = view.unresolvedBeliefConflicts,
            recentChanges = recent,
            issues = issues.distinctBy { Triple(it.code, it.truthUid, it.message) }
        )
    }
}
