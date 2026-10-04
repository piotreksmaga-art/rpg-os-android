package com.rpgos.app

import java.util.Locale

/** A bounded presentation of already captured, current-player-owned process rows. The
 * caller establishes the protected-read authority and captures every row in [scope]. */
internal data class Phase64PlayerProcessSnapshot(
    val scope: TemporalScope,
    val player: DomainRef,
    val rows: List<Phase64PlayerProcessView>,
    val complete: Boolean
)

/** Parameters, dependencies, private reasons and owner records never leave this projection. */
internal data class Phase64PlayerProcessView(
    val processRef: DomainRef,
    val version: Long,
    val processFingerprint: String,
    val handle: String,
    val displayLabel: String,
    val categoryLabel: String,
    val status: BackgroundProcessStatus,
    val due: WorldTimeTick
) {
    val statusText: String get() = when (status) {
        BackgroundProcessStatus.ACTIVE -> "Trwa"
        BackgroundProcessStatus.BLOCKED -> "Wstrzymany"
        else -> error("P64:PLAYER_PROJECTION_REQUIRES_PENDING_PROCESS")
    }

    /** The existing cancellation action takes one second and cannot undo a due result. */
    fun canCancelAt(at: WorldTimeTick): Boolean = runCatching {
        Math.addExact(at.milliseconds, 1000L) < due.milliseconds
    }.getOrDefault(false)

    /** These are legal own-process metadata, not the process's canonical JSON. */
    fun contextValues(): Map<String, String> = mapOf(
        "process_kind_uid" to processRef.kindUid,
        "process_uid" to processRef.uid,
        "process_handle" to handle,
        "display_label" to displayLabel,
        "status" to status.name,
        "due_ms" to due.milliseconds.toString(),
        "epistemic_state_uid" to "PROJECTED_FACT"
    )
}

internal object Phase64PlayerProcessProjection {
    const val VERSION_HINT = "p64_process_version"
    const val FINGERPRINT_HINT = "p64_process_fingerprint"
    const val SCOPE_HINT = "p64_process_scope"
    private val pending = setOf(BackgroundProcessStatus.ACTIVE, BackgroundProcessStatus.BLOCKED)
    private val allCategories = setOf(
        "proces", "process", "zadanie", "task", "moj proces", "mój proces",
        "moje zadanie", "my process", "my task"
    )

    fun capture(
        scope: TemporalScope,
        player: DomainRef,
        processes: List<BackgroundProcessInstance>,
        definitions: Map<Pair<String, Int>, BackgroundProcessDefinition>,
        limit: Int = 32
    ): Phase64PlayerProcessSnapshot {
        require(limit in 1..256) { "P64:PLAYER_PROJECTION_BUDGET" }
        if (player.kindUid != "PLAYER") return Phase64PlayerProcessSnapshot(scope, player, emptyList(), true)
        val own = processes.filter { it.actor == player && it.status in pending }
            .sortedWith(compareBy<BackgroundProcessInstance> { it.due }.thenBy { it.uid })
        require(own.map { it.uid }.distinct().size == own.size) { "P64:DUPLICATE_PROJECTED_PROCESS" }
        val rows = own.take(limit).map { process ->
            val definition = requireNotNull(definitions[process.definitionUid to process.definitionVersion]) {
                "P64:PLAYER_PROJECTION_RULE_REQUIRED"
            }
            require(definition.uid == process.definitionUid && definition.version == process.definitionVersion) {
                "P64:PLAYER_PROJECTION_RULE_MISMATCH"
            }
            val category = when (definition.domain) {
                "PROJECT" -> "Projekt"
                "INFORMATION" -> "Przekazanie informacji"
                "ORGANIZATION" -> "Zadanie organizacji"
                "POPULATION" -> "Proces populacji"
                "CONFLICT" -> "Działanie formacji"
                "EPIDEMIC" -> "Działanie zdrowotne"
                else -> if (definition.operation == Phase64EconomyOperations.DELIVER) "Dostawa" else "Zadanie"
            }
            val handle = "Z${phase63Hash(process.uid).take(16).uppercase(Locale.ROOT)}"
            Phase64PlayerProcessView(DomainRef("WORLD_PROCESS", process.uid), process.version,
                phase63Hash(Phase64BackgroundCodec.process(process).toString()), handle,
                "$category $handle", category, process.status, process.due)
        }
        require(rows.map { it.handle }.distinct().size == rows.size) { "P64:PROCESS_HANDLE_COLLISION" }
        return Phase64PlayerProcessSnapshot(scope, player, rows, own.size <= limit)
    }

    /** Exact selectors or explicit category names only. Never choose the first category
     * member and never delegate an unknown process to latent-world materialization. */
    fun resolve(
        snapshot: Phase64PlayerProcessSnapshot,
        reference: IntentReference,
        currentScope: TemporalScope = snapshot.scope
    ): IntentReference {
        val clean = reference.copy(state = IntentReferenceState.UNRESOLVED, resolvedProjectedRef = null,
            candidateProjectedRefs = emptyList(), resolutionEvidenceUid = null,
            descriptorHints = reference.descriptorHints - VERSION_HINT - FINGERPRINT_HINT - SCOPE_HINT - "world_resolution_reason")
        fun unresolved(reason: String) = clean.copy(descriptorHints = clean.descriptorHints + ("world_resolution_reason" to reason))
        if (snapshot.scope != currentScope) return unresolved("P64:CANCELLATION_STALE_PROJECTION")
        if (snapshot.player.kindUid != "PLAYER" || reference.roleUid != "TARGET" ||
            reference.kind in setOf(IntentReferenceKind.FUTURE_RESULT, IntentReferenceKind.RESOURCE_FROM_RESULT, IntentReferenceKind.SET))
            return unresolved("P64:CANCELLATION_PROCESS_UNRESOLVED")
        val phrase = selector(reference.rawPhrase ?: reference.descriptorHints["surface"].orEmpty())
        val exact = snapshot.rows.filter { row -> phrase in setOf(selector(row.handle), selector(row.processRef.uid), selector(row.displayLabel)) }
        val category = phrase in allCategories || snapshot.rows.any { selector(it.categoryLabel) == phrase }
        val matches = if (exact.isNotEmpty()) exact else if (category) snapshot.rows.filter {
            phrase in allCategories || selector(it.categoryLabel) == phrase
        } else emptyList()
        if (matches.size > 1) return clean.copy(state = IntentReferenceState.AMBIGUOUS,
            candidateProjectedRefs = matches.map { it.processRef },
            descriptorHints = clean.descriptorHints + ("world_resolution_reason" to "P64:CANCELLATION_PROCESS_AMBIGUOUS"))
        if (matches.isEmpty()) return unresolved("P64:CANCELLATION_PROCESS_UNRESOLVED")
        if (exact.isEmpty() && !snapshot.complete) return unresolved("P64:CANCELLATION_SELECTION_INCOMPLETE")
        val row = matches.single()
        val scopeFingerprint = scopeFingerprint(snapshot.scope)
        val evidence = "P64:PLAYER-PROCESS:${phase63Hash("$scopeFingerprint|${row.processRef.uid}|${row.version}|${row.processFingerprint}")}"
        return clean.copy(state = IntentReferenceState.RESOLVED_PROJECTED, resolvedProjectedRef = row.processRef,
            resolutionEvidenceUid = evidence,
            descriptorHints = clean.descriptorHints + mapOf(VERSION_HINT to row.version.toString(),
                FINGERPRINT_HINT to row.processFingerprint, SCOPE_HINT to scopeFingerprint))
    }

    /** Mechanics can reject a previously projected reference after version/history changes. */
    fun isCurrentBinding(snapshot: Phase64PlayerProcessSnapshot, reference: IntentReference): Boolean {
        val row = snapshot.rows.singleOrNull { it.processRef == reference.resolvedProjectedRef } ?: return false
        return reference.state == IntentReferenceState.RESOLVED_PROJECTED &&
            reference.descriptorHints[VERSION_HINT] == row.version.toString() &&
            reference.descriptorHints[FINGERPRINT_HINT] == row.processFingerprint &&
            reference.descriptorHints[SCOPE_HINT] == scopeFingerprint(snapshot.scope)
    }

    private fun scopeFingerprint(scope: TemporalScope) = phase63Hash(listOf(scope.campaignUid,
        scope.historyGenerationUid, scope.baseCommitOrder.toString(), scope.authoritativeFingerprint)
        .joinToString("") { "${it.length}:$it" })

    private fun selector(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
