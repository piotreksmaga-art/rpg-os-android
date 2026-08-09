package com.rpgos.app

/**
 * Admission boundary for memories proposed by the language model.
 *
 * Working memory is transient context and is never persisted here. The model may
 * only propose current-turn EPISODIC memories that are grounded in at least one
 * accepted event from the same resolved turn. SEMANTIC memory is intentionally
 * reserved for the deterministic Memory Consolidator; model prose can never
 * promote itself into durable campaign semantics.
 */
class MemoryAdmissionTurnValidator141(
    private val delegate: GameMasterTurnValidator
) : GameMasterTurnValidator {

    override suspend fun validate(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        result: GameMasterTurnResult
    ): GameMasterValidationReport {
        val issues = delegate.validate(request, context, result).issues.toMutableList()
        val eventKeys = result.worldEvents.map { it.eventKey }.toSet()

        result.memoryWrites.forEachIndexed { index, memory ->
            if (memory.memoryType in MODEL_FORBIDDEN_SEMANTIC_TYPES) {
                issues += error(
                    "MODEL_SEMANTIC_MEMORY_FORBIDDEN",
                    "Pamięć #${index + 1} typu ${memory.memoryType} nie może być zapisana bezpośrednio przez model; SEMANTIC memory tworzy Memory Consolidator."
                )
                return@forEachIndexed
            }

            val sourceEventKeys = memory.tags
                .asSequence()
                .filter { it.startsWith(EVENT_TAG_PREFIX) }
                .map { it.removePrefix(EVENT_TAG_PREFIX) }
                .filter { it.isNotBlank() }
                .toSet()

            if (sourceEventKeys.isEmpty()) {
                issues += error(
                    "UNGROUNDED_EPISODIC_MEMORY",
                    "Pamięć #${index + 1} typu ${memory.memoryType} nie wskazuje żadnego event:<eventKey> z zaakceptowanej tury."
                )
            } else {
                sourceEventKeys
                    .filterNot { it in eventKeys }
                    .forEach { missing ->
                        issues += error(
                            "UNKNOWN_MEMORY_EVENT",
                            "Pamięć #${index + 1} wskazuje nieznany eventKey=$missing."
                        )
                    }
            }

            if (memory.chapter != request.currentChapter) {
                issues += error(
                    "EPISODIC_MEMORY_CHAPTER_MISMATCH",
                    "Pamięć #${index + 1} pochodzi z bieżącej tury, więc chapter=${memory.chapter} musi odpowiadać ${request.currentChapter}."
                )
            }
        }

        return GameMasterValidationReport(issues.distinctBy { Triple(it.code, it.message, it.severity) })
    }

    private fun error(code: String, message: String) =
        GameMasterValidationIssue(code, message, ValidationSeverity.ERROR)

    companion object {
        private const val EVENT_TAG_PREFIX = "event:"

        /** These map to DurableMemoryKind.SEMANTIC in persistence. */
        private val MODEL_FORBIDDEN_SEMANTIC_TYPES = setOf(
            MemoryType.FACT,
            MemoryType.PLAYER_PREFERENCE,
            MemoryType.LONG_TERM_THREAD
        )
    }
}
