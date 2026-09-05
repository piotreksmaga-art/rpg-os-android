package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

const val MEMORY_ENRICHMENT_WORKLOAD_UID = "MEMORY_ENRICHMENT"

private const val PHASE58_RULE_MANIFEST_UID = "RPGOS-P58-EPISODE-MANIFEST"
private const val PHASE58_RULE_INTERPRETATION_UID = "RPGOS-P58-EPISODE-INTERPRETATION"
private const val PHASE58_DERIVATION_VERSION = 1
private const val PHASE58_RESUME_CURSOR_VERSION = 2
private const val PHASE58_MAX_INPUT_SLICE_LEAVES = 256
private const val PHASE58_NO_PROGRESS_REASON = "RPGOS-MEMORY:CONSOLIDATION_EXCEEDED_WORK_BUDGET"

class Phase58MemoryConsolidation(
    private val db: SQLiteDatabase,
    private val campaignUid: String,
    private val segmenter: DeterministicEpisodeSegmenter = DeterministicEpisodeSegmenter(),
    private val artifactStore: MemoryArtifactStore = MemoryArtifactStore(db),
    private val enrichmentPort: MemoryEnrichmentPort? = null
) {
    init { require(campaignUid.isNotBlank()) }

    fun postCommit(
        eventLeaves: List<MemoryEventLeaf>,
        localeUid: String,
        budget: ConsolidationWorkBudget = ConsolidationWorkBudget(),
        cancellation: AiCancellationSignal = AiCancellationSignal.NONE
    ): ConsolidationReceipt = runConsolidation(eventLeaves, localeUid, budget, cancellation)

    fun open(
        eventLeaves:List<MemoryEventLeaf> = emptyList(),
        localeUid: String,
        budget: ConsolidationWorkBudget = ConsolidationWorkBudget(),
        cancellation: AiCancellationSignal = AiCancellationSignal.NONE
    ): ConsolidationReceipt = runConsolidation(eventLeaves, localeUid, budget, cancellation)

    private data class ActiveConsolidationState(
        val historyGenerationUid: HistoryGenerationUid,
        val watermarkOrder: Long,
        val resumeCursor: String?,
        val lastInputFingerprint: String,
        val lastStatus: ConsolidationStatus
    )

    private data class ResumeCursor(
        val version: Int,
        val sliceThroughOrder: Long,
        val sliceLeafCount: Int,
        val sliceFingerprint: String,
        val nextLeafIndex: Int,
        val historyGenerationUid: HistoryGenerationUid
    )

    private fun runConsolidation(
        eventLeaves: List<MemoryEventLeaf>,
        localeUid: String,
        budget: ConsolidationWorkBudget,
        cancellation: AiCancellationSignal
    ): ConsolidationReceipt {
        require(localeUid.isNotBlank())
        require(!cancellation.isCancelled())
        require(budget.maxCandidateRelations >= 1)
        val maximumInputSlice = minOf(budget.maxLeafRecords, PHASE58_MAX_INPUT_SLICE_LEAVES)
        require(eventLeaves.size <= maximumInputSlice) {
            "RPGOS-MEMORY:CONSOLIDATION_INPUT_EXCEEDS_LEAF_BUDGET:${eventLeaves.size}:$maximumInputSlice"
        }

        Phase55To58MemorySchema.ensureReady(db, campaignUid)
        val generation = HistoryGenerationStore(db, campaignUid).current()
        val state = loadState(generation)

        val resume = parseResume(state.resumeCursor)
        if (state.resumeCursor != null && resume == null) {
            clearResumeState(generation)
            return runConsolidation(eventLeaves, localeUid, budget, cancellation)
        }
        if (resume != null && resume.historyGenerationUid != generation) {
            clearPendingState(generation)
            return runConsolidation(eventLeaves, localeUid, budget, cancellation)
        }

        val normalizedLeaves = normalizeIncomingLeaves(eventLeaves, state, resume)
        val candidates = normalizedLeaves.sortedWith(compareBy<MemoryEventLeaf> { it.committedOrder }.thenBy { it.eventOrdinal }.thenBy { it.eventUid })
        val inputLeafSetFingerprint = leafSetFingerprint(candidates)
        val fromOrder = candidates.minOfOrNull { it.committedOrder } ?: state.watermarkOrder

        if (resume != null && (candidates.size != resume.sliceLeafCount || inputLeafSetFingerprint != resume.sliceFingerprint)) {
            clearResumeState(generation)
            return failedWithoutProgress(
                generation = generation,
                state = state,
                fromOrder = fromOrder,
                inputFingerprint = inputLeafSetFingerprint,
                reasonUid = "RPGOS-MEMORY:CONSOLIDATION_RESUME_INPUT_MISMATCH"
            )
        }

        if (candidates.isEmpty()) {
            val receipt = ConsolidationReceipt(
                consolidationUid = UUID.randomUUID().toString(),
                campaignUid = campaignUid,
                historyGenerationUid = generation,
                fromOrder = fromOrder,
                throughOrder = state.watermarkOrder,
                inputLeafSetFingerprint = inputLeafSetFingerprint,
                derivationRuleUid = PHASE58_RULE_MANIFEST_UID,
                derivationVersion = PHASE58_DERIVATION_VERSION,
                producedArtifactRevisionUids = emptyList(),
                updatedArtifactRevisionUids = emptyList(),
                invalidatedArtifactRevisionUids = emptyList(),
                canonicalMutationReceiptUids = emptyList(),
                previousWatermark = state.watermarkOrder,
                resultingWatermark = state.watermarkOrder,
                resumeCursor = null,
                status = ConsolidationStatus.COMMITTED,
                reasonUid = if (state.resumeCursor != null) null else null
            )
            persistResult(generation, receipt, state.watermarkOrder, null)
            return receipt
        }

        val alreadyProcessedLeaves = resume?.nextLeafIndex ?: 0
        val candidatesForSegmentation = candidates.drop(alreadyProcessedLeaves)
        val candidateLeafByUid = candidatesForSegmentation.associateBy { it.eventUid }
        val episodes = segmenter.segment(campaignUid, generation, candidatesForSegmentation)

        val startNanos = System.nanoTime()
        val deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(budget.maxWallClockMillis)
        var workUnits = 0
        var serializedBytes = 0L
        var processedLeaves = 0
        var produced = mutableListOf<String>()
        var updated = mutableListOf<String>()
        var invalidated = mutableListOf<String>()
        var resultingWatermark = if (alreadyProcessedLeaves > 0) {
            maxOf(state.watermarkOrder, candidates[alreadyProcessedLeaves - 1].committedOrder)
        } else state.watermarkOrder
        var processedEpisodes = 0

        for (index in episodes.indices) {
            if(processedEpisodes>=budget.maxEpisodes)break
            if (workUnits >= budget.maxWorkUnits) break
            if (serializedBytes >= budget.maxSerializedBytes.toLong()) break
            if (System.nanoTime() >= deadlineNanos) break

            val episode = episodes[index]
            val remainingNanos = (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)
            val enrichmentDeadlineNanos = System.nanoTime() + remainingNanos / 2L
            val interpretation = buildInterpretation(episode, localeUid, generation, cancellation, enrichmentDeadlineNanos)
            val knowledgeProjector=Phase37EpisodeMemoryProjector(db,campaignUid)
            val knowledgeDerivation=knowledgeProjector.derive(episode)
            val episodeManifestPayload = episodeManifestJson(episode)
            val interpretationPayload = episodeInterpretationJson(interpretation)
            val episodeSerializedBytes = episodeManifestPayload.toByteArray(Charsets.UTF_8).size.toLong() +
                interpretationPayload.toByteArray(Charsets.UTF_8).size.toLong() +
                knowledgeProjector.serializedPayloadBytes(knowledgeDerivation)
            val episodeOutputs = 2+knowledgeDerivation.holderMemories.size+knowledgeDerivation.semanticAssertions.size
            val candidateRelations = 1+knowledgeDerivation.holderMemories.size+knowledgeDerivation.semanticAssertions.size

            if (System.nanoTime() >= deadlineNanos) break
            if (episode.eventUids.size > budget.maxLeafRecords) break
            if (workUnits + episodeOutputs > budget.maxWorkUnits) break
            if (serializedBytes + episodeSerializedBytes > budget.maxSerializedBytes.toLong()) break
            if (episode.eventUids.size + processedLeaves > budget.maxLeafRecords) break
            if (candidateRelations > budget.maxCandidateRelations) break
            if (produced.size + updated.size + episodeOutputs > budget.maxOutputArtifacts) break
            val eventLeavesInEpisode = episode.eventUids.map { eventUid ->
                candidateLeafByUid[eventUid]
                    ?: throw IllegalStateException("RPGOS-MEMORY:EVENT_MEMBERSHIP_MISSING:$eventUid")
            }

            val episodeResult = persistEpisodeArtifacts(
                generation = generation,
                episode = episode,
                episodeInterpretation = interpretation,
                interpretationPayload = interpretationPayload,
                episodeCandidateLeaves = eventLeavesInEpisode,
                knowledgeProjector = knowledgeProjector,
                knowledgeDerivation = knowledgeDerivation
            )
            produced += episodeResult.producedArtifactRevisionUids
            updated += episodeResult.updatedArtifactRevisionUids
            invalidated += episodeResult.invalidatedArtifactRevisionUids

            workUnits += episodeOutputs
            serializedBytes += episodeSerializedBytes

            processedLeaves += episode.eventUids.size
            resultingWatermark = maxOf(resultingWatermark, episode.endOrder)
            processedEpisodes++

            if (System.nanoTime() >= deadlineNanos) break
        }

        val nextLeafIndex = alreadyProcessedLeaves + processedLeaves
        val remainingLeaves = candidates.drop(nextLeafIndex)
        val hasMore = remainingLeaves.isNotEmpty()
        val status = if (!hasMore || (workUnits > 0)) ConsolidationStatus.COMMITTED else ConsolidationStatus.FAILED
        val resumeCursor = if (hasMore && workUnits > 0) encodeResume(
            ResumeCursor(
                version = PHASE58_RESUME_CURSOR_VERSION,
                sliceThroughOrder = resume?.sliceThroughOrder ?: candidates.maxOf { it.committedOrder },
                sliceLeafCount = candidates.size,
                sliceFingerprint = inputLeafSetFingerprint,
                nextLeafIndex = nextLeafIndex,
                historyGenerationUid = generation
            )
        ) else null
        val receipt = ConsolidationReceipt(
            consolidationUid = UUID.randomUUID().toString(),
            campaignUid = campaignUid,
            historyGenerationUid = generation,
            fromOrder = fromOrder,
            throughOrder = if (status == ConsolidationStatus.FAILED) maxOf(fromOrder, resultingWatermark) else resultingWatermark,
            inputLeafSetFingerprint = inputLeafSetFingerprint,
            derivationRuleUid = PHASE58_RULE_MANIFEST_UID,
            derivationVersion = PHASE58_DERIVATION_VERSION,
            producedArtifactRevisionUids = produced.distinct(),
            updatedArtifactRevisionUids = updated.distinct(),
            invalidatedArtifactRevisionUids = invalidated.distinct(),
            canonicalMutationReceiptUids = emptyList(),
            previousWatermark = state.watermarkOrder,
            resultingWatermark = resultingWatermark,
            resumeCursor = resumeCursor,
            status = status,
            reasonUid = if (status == ConsolidationStatus.FAILED) PHASE58_NO_PROGRESS_REASON else null
        )
        // Keep the durable watermark at the start of the immutable slice until that slice is
        // complete. This lets canonical replay re-supply the same <=256 leaves after restart,
        // including multiple leaves that share one committed order.
        val updatedWatermark = if (hasMore) state.watermarkOrder else resultingWatermark
        persistResult(generation, receipt, updatedWatermark, resumeCursor)
        return receipt
    }

    private fun failedWithoutProgress(
        generation: HistoryGenerationUid,
        state: ActiveConsolidationState,
        fromOrder: Long,
        inputFingerprint: String,
        reasonUid: String
    ): ConsolidationReceipt {
        val receipt = ConsolidationReceipt(
            consolidationUid = UUID.randomUUID().toString(),
            campaignUid = campaignUid,
            historyGenerationUid = generation,
            fromOrder = fromOrder,
            throughOrder = maxOf(fromOrder, state.watermarkOrder),
            inputLeafSetFingerprint = inputFingerprint,
            derivationRuleUid = PHASE58_RULE_MANIFEST_UID,
            derivationVersion = PHASE58_DERIVATION_VERSION,
            producedArtifactRevisionUids = emptyList(),
            updatedArtifactRevisionUids = emptyList(),
            invalidatedArtifactRevisionUids = emptyList(),
            canonicalMutationReceiptUids = emptyList(),
            previousWatermark = state.watermarkOrder,
            resultingWatermark = state.watermarkOrder,
            resumeCursor = null,
            status = ConsolidationStatus.FAILED,
            reasonUid = reasonUid
        )
        persistResult(generation, receipt, state.watermarkOrder, null)
        return receipt
    }

    private data class PersistEpisodeResult(
        val producedArtifactRevisionUids: List<String>,
        val updatedArtifactRevisionUids: List<String>,
        val invalidatedArtifactRevisionUids: List<String>
    )

    private fun persistEpisodeArtifacts(
        generation: HistoryGenerationUid,
        episode: EpisodeManifest,
        episodeInterpretation: EpisodeInterpretation,
        interpretationPayload: String,
        episodeCandidateLeaves: List<MemoryEventLeaf>,
        knowledgeProjector:Phase37EpisodeMemoryProjector,
        knowledgeDerivation:Phase37EpisodeMemoryDerivation
    ): PersistEpisodeResult {
        val needTransaction=!db.inTransaction()
        if(needTransaction)db.beginTransaction()
        try{
        val produced = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val invalidated = mutableListOf<String>()

        if (!hasArtifact(generation,episode.identity.artifactRevisionUid)) produced += episode.identity.artifactRevisionUid
        else updated += episode.identity.artifactRevisionUid
        invalidated += supersedePreviousArtifacts(
            logicalArtifactUid = episode.identity.logicalArtifactUid,
            artifactKind = episode.identity.artifactKind,
            keepRevisionUid = episode.identity.artifactRevisionUid,
            generation=generation
        )
        artifactStore.upsert(
            identity = episode.identity,
            status = MemoryArtifactStatus.CLEAN,
            payloadJson = episodeManifestJson(episode)
        )
        persistEpisodeMembership(episode.identity.artifactRevisionUid, episodeCandidateLeaves)

        if (!hasArtifact(generation,episodeInterpretation.identity.artifactRevisionUid)) produced += episodeInterpretation.identity.artifactRevisionUid
        else updated += episodeInterpretation.identity.artifactRevisionUid
        invalidated += supersedePreviousArtifacts(
            logicalArtifactUid = episodeInterpretation.identity.logicalArtifactUid,
            artifactKind = episodeInterpretation.identity.artifactKind,
            keepRevisionUid = episodeInterpretation.identity.artifactRevisionUid,
            generation=generation
        )
        artifactStore.upsert(
            identity = episodeInterpretation.identity,
            status = MemoryArtifactStatus.CLEAN,
            payloadJson = interpretationPayload,
            dependencies = listOf(
                episode.identity.artifactRevisionUid to MemoryDependencyKind.DERIVED_FROM
            )
        )

        val knowledgeResult=knowledgeProjector.persist(episode,knowledgeDerivation)
        produced+=knowledgeResult.producedRevisionUids
        updated+=knowledgeResult.updatedRevisionUids
        invalidated+=knowledgeResult.supersededRevisionUids

        val result=PersistEpisodeResult(
            producedArtifactRevisionUids = produced.distinct(),
            updatedArtifactRevisionUids = updated.distinct(),
            invalidatedArtifactRevisionUids = invalidated.distinct()
        )
        if(needTransaction)db.setTransactionSuccessful()
        return result
        }finally{if(needTransaction)db.endTransaction()}
    }

    private fun buildInterpretation(
        episode: EpisodeManifest,
        localeUid: String,
        generation: HistoryGenerationUid,
        cancellation: AiCancellationSignal,
        deadlineNanos: Long
    ): EpisodeInterpretation {
        val fallback = fallbackInterpretation(episode)
        val request = MemoryEnrichmentRequest(
            requestUid = "RPGOS-P58-EP:${episode.identity.artifactRevisionUid}:$localeUid",
            manifest = episode,
            localeUid = localeUid
        )
        val response = boundedEnrichment(request, cancellation, deadlineNanos)
        val title = (response as? MemoryEnrichmentResult.Success)?.title ?: fallback.first
        val summary = (response as? MemoryEnrichmentResult.Success)?.summary ?: fallback.second
        val tags = (response as? MemoryEnrichmentResult.Success)?.tags.orEmpty()
        val normalizedTags = if (tags.isEmpty()) setOf("episode") else tags.toSortedSet()
        val interpretationRevisionUid = "RPGOS-P58-INT:${memorySha256("${episode.identity.logicalArtifactUid}|${generation.value}|$localeUid|${episode.identity.sourceLeafSetFingerprint}")}"
        return EpisodeInterpretation(
            identity = MemoryArtifactIdentity(
                campaignUid = campaignUid,
                historyGenerationUid = generation,
                logicalArtifactUid = "RPGOS-P58-INT:${episode.identity.logicalArtifactUid}:$localeUid",
                artifactRevisionUid = interpretationRevisionUid,
                artifactKind = MemoryArtifactKind.EPISODE_INTERPRETATION,
                sourceLeafRefs = episode.identity.sourceLeafRefs,
                sourceLeafSetFingerprint = episode.identity.sourceLeafSetFingerprint,
                derivationRuleUid = PHASE58_RULE_INTERPRETATION_UID,
                derivationVersion = PHASE58_DERIVATION_VERSION,
                asOfCommittedOrder = episode.endOrder,
                createdFromOrder = episode.startOrder,
                createdThroughOrder = episode.endOrder
            ),
            episodeLogicalUid = episode.identity.logicalArtifactUid,
            localeUid = localeUid,
            title = title,
            summary = summary,
            presentationTags = normalizedTags
        )
    }

    private fun fallbackInterpretation(episode: EpisodeManifest): Pair<String, String> {
        return "Episode ${episode.identity.logicalArtifactUid}" to
            "Episode has ${episode.eventUids.size} events from ${episode.startOrder} to ${episode.endOrder}"
    }

    private fun normalizeIncomingLeaves(
        eventLeaves: List<MemoryEventLeaf>,
        state: ActiveConsolidationState,
        cursor: ResumeCursor?
    ): List<MemoryEventLeaf> {
        // A resumable slice is immutable. Appending new commits before it is fully consumed could
        // change the final episode boundary. The cursor retains only constant-size validation
        // metadata; canonical leaves are supplied again from the watermark by the caller.
        val baseline = eventLeaves.filter { leaf ->
            leaf.committedOrder > state.watermarkOrder && (cursor == null || leaf.committedOrder <= cursor.sliceThroughOrder)
        }
        return baseline
            .distinctBy { it.eventUid }
            .sortedWith(compareBy<MemoryEventLeaf> { it.committedOrder }.thenBy { it.eventOrdinal }.thenBy { it.eventUid })
    }

    private fun leafSetFingerprint(eventLeaves: List<MemoryEventLeaf>): String {
        if (eventLeaves.isEmpty()) return "EMPTY"
        return memoryLeafFingerprint(eventLeaves.map(::leafSourceRef))
    }

    private fun leafSourceRef(event: MemoryEventLeaf) = MemorySourceLeafRef(
        sourceKind = "EVENT",
        sourceUid = event.eventUid,
        sourceVersion = 1,
        committedOrder = event.committedOrder,
        fingerprint = event.fingerprint
    )

    private fun hasArtifact(generation:HistoryGenerationUid,revisionUid: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=? AND history_generation_uid=? AND artifact_revision_uid=?",
            arrayOf(campaignUid,generation.value, revisionUid)
        ).use { it.moveToFirst() }

    private fun supersedePreviousArtifacts(
        logicalArtifactUid: String,
        artifactKind: MemoryArtifactKind,
        keepRevisionUid: String,
        generation:HistoryGenerationUid
    ): List<String> {
        val superseded = db.rawQuery(
            """
                SELECT artifact_revision_uid FROM ${Phase55To58MemorySchema.ARTIFACTS}
                WHERE campaign_uid=? AND history_generation_uid=? AND logical_artifact_uid=? AND artifact_kind_uid=? AND artifact_revision_uid!=?
            """.trimIndent(),
            arrayOf(campaignUid,generation.value,logicalArtifactUid, artifactKind.name, keepRevisionUid)
        ).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }.toMutableList()
        superseded.forEach {
            db.execSQL(
                "UPDATE ${Phase55To58MemorySchema.ARTIFACTS} SET status_uid=? WHERE campaign_uid=? AND artifact_revision_uid=?",
                arrayOf<Any?>(MemoryArtifactStatus.SUPERSEDED.name, campaignUid, it)
            )
        }
        return superseded.distinct()
    }

    private fun persistEpisodeMembership(episodeRevisionUid: String, eventLeaves: List<MemoryEventLeaf>) {
        db.execSQL(
            "DELETE FROM ${Phase55To58MemorySchema.EPISODE_MEMBERSHIP} WHERE campaign_uid=? AND episode_revision_uid=?",
            arrayOf(campaignUid, episodeRevisionUid)
        )
        eventLeaves.forEach { event ->
            db.insertOrThrow(
                Phase55To58MemorySchema.EPISODE_MEMBERSHIP,
                null,
                ContentValues().apply {
                    put("campaign_uid", campaignUid)
                    put("episode_revision_uid", episodeRevisionUid)
                    put("event_uid", event.eventUid)
                    put("event_ordinal", event.eventOrdinal)
                    put("committed_order", event.committedOrder)
                }
            )
        }
    }

    private fun loadState(generation: HistoryGenerationUid): ActiveConsolidationState =
        db.rawQuery(
            "SELECT history_generation_uid, watermark_order, resume_cursor, last_input_fingerprint, last_status_uid FROM ${Phase55To58MemorySchema.STATE} WHERE campaign_uid=?",
            arrayOf(campaignUid)
        ).use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalStateException("RPGOS-MEMORY:CONSOLIDATION_STATE_MISSING")
            val persistedGeneration = HistoryGenerationUid(cursor.getString(0))
            val lastStatus = when (cursor.getString(4)) {
                ConsolidationStatus.FAILED.name -> ConsolidationStatus.FAILED
                else -> ConsolidationStatus.COMMITTED
            }
            ActiveConsolidationState(
                historyGenerationUid = persistedGeneration,
                watermarkOrder = cursor.getLong(1),
                resumeCursor = cursor.getString(2),
                lastInputFingerprint = cursor.getString(3),
                lastStatus = lastStatus
            ).also {
                if (it.historyGenerationUid != generation) {
                    clearPendingState(generation)
                }
            }
        }.let { state ->
            if (state.historyGenerationUid != generation) {
                return loadState(generation)
            }
            state
        }

    private fun clearResumeState(generation: HistoryGenerationUid) {
        db.execSQL(
            """
            UPDATE ${Phase55To58MemorySchema.STATE}
            SET history_generation_uid=?, resume_cursor=NULL, last_status_uid='DIRTY', updated_at_epoch_ms=?
            WHERE campaign_uid=?
            """.trimIndent(),
            arrayOf<Any?>(generation.value, System.currentTimeMillis(), campaignUid)
        )
    }

    private fun boundedEnrichment(
        request: MemoryEnrichmentRequest,
        cancellation: AiCancellationSignal,
        deadlineNanos: Long
    ): MemoryEnrichmentResult? {
        val port = enrichmentPort ?: return null
        if (cancellation.isCancelled() || System.nanoTime() >= deadlineNanos) return null
        val deadlineSignal = AiCancellationSignal {
            cancellation.isCancelled() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos
        }
        val task = FutureTask<MemoryEnrichmentResult?> {
            runCatching { port.enrich(request, deadlineSignal) }.getOrNull()
        }
        val worker = Thread(task, "phase58-memory-enrichment").apply { isDaemon = true }
        worker.start()
        return try {
            while (true) {
                if (cancellation.isCancelled()) {
                    task.cancel(true)
                    return null
                }
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0L) {
                    task.cancel(true)
                    return null
                }
                try {
                    return task.get(minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(10)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    // Poll the caller cancellation signal without waiting beyond the hard deadline.
                }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (_: Exception) {
            task.cancel(true)
            null
        }
    }

    private fun clearPendingState(generation: HistoryGenerationUid) {
        db.execSQL(
            """
            UPDATE ${Phase55To58MemorySchema.STATE}
            SET history_generation_uid=?, watermark_order=0, resume_cursor=NULL, last_input_fingerprint='EMPTY', last_status_uid='DIRTY', updated_at_epoch_ms=?
            WHERE campaign_uid=?
            """.trimIndent(),
            arrayOf<Any?>(generation.value, System.currentTimeMillis(), campaignUid)
        )
    }

    private fun persistResult(
        generation: HistoryGenerationUid,
        receipt: ConsolidationReceipt,
        resultingWatermark: Long,
        resumeCursor: String?
    ) {
        val needTransaction = !db.inTransaction()
        if (needTransaction) db.beginTransaction()
        try {
            db.execSQL(
                """
                INSERT OR REPLACE INTO ${Phase55To58MemorySchema.RECEIPTS}
                (
                    campaign_uid, consolidation_uid, history_generation_uid, from_order, through_order,
                    input_leaf_set_fingerprint, derivation_rule_uid, derivation_version,
                    previous_watermark, resulting_watermark, resume_cursor, status_uid, reason_uid, receipt_json
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                arrayOf<Any?>(
                    campaignUid,
                    receipt.consolidationUid,
                    generation.value,
                    receipt.fromOrder,
                    receipt.throughOrder,
                    receipt.inputLeafSetFingerprint,
                    receipt.derivationRuleUid,
                    receipt.derivationVersion,
                    receipt.previousWatermark,
                    resultingWatermark,
                    resumeCursor,
                    receipt.status.name,
                    receipt.reasonUid,
                    receiptToJson(receipt).toString()
                )
            )
            db.execSQL(
                """
                UPDATE ${Phase55To58MemorySchema.STATE}
                SET history_generation_uid=?, watermark_order=?, resume_cursor=?, last_input_fingerprint=?, last_status_uid=?, updated_at_epoch_ms=?
                WHERE campaign_uid=?
                """.trimIndent(),
                arrayOf<Any?>(generation.value, resultingWatermark, resumeCursor, receipt.inputLeafSetFingerprint, receipt.status.name, System.currentTimeMillis(), campaignUid)
            )
            if (needTransaction) db.setTransactionSuccessful()
        } finally {
            if (needTransaction) db.endTransaction()
        }
    }

    private fun receiptToJson(receipt: ConsolidationReceipt): JSONObject =
        JSONObject().apply {
            put("consolidation_uid", receipt.consolidationUid)
            put("campaign_uid", receipt.campaignUid)
            put("history_generation_uid", receipt.historyGenerationUid.value)
            put("from_order", receipt.fromOrder)
            put("through_order", receipt.throughOrder)
            put("input_leaf_set_fingerprint", receipt.inputLeafSetFingerprint)
            put("derivation_rule_uid", receipt.derivationRuleUid)
            put("derivation_version", receipt.derivationVersion)
            put("produced_artifact_revision_uids", JSONArray(receipt.producedArtifactRevisionUids))
            put("updated_artifact_revision_uids", JSONArray(receipt.updatedArtifactRevisionUids))
            put("invalidated_artifact_revision_uids", JSONArray(receipt.invalidatedArtifactRevisionUids))
            put("canonical_mutation_receipt_uids", JSONArray(receipt.canonicalMutationReceiptUids))
            put("previous_watermark", receipt.previousWatermark)
            put("resulting_watermark", receipt.resultingWatermark)
            put("resume_cursor", receipt.resumeCursor)
            put("status_uid", receipt.status.name)
            put("reason_uid", receipt.reasonUid)
        }

    private fun encodeResume(cursor: ResumeCursor): String =
        JSONObject().apply {
            put("version", cursor.version)
            put("history_generation_uid", cursor.historyGenerationUid.value)
            put("slice_through_order", cursor.sliceThroughOrder)
            put("slice_leaf_count", cursor.sliceLeafCount)
            put("slice_fingerprint", cursor.sliceFingerprint)
            put("next_leaf_index", cursor.nextLeafIndex)
        }.toString()

    private fun parseResume(raw: String?): ResumeCursor? {
        if (raw == null) return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.getInt("version") != PHASE58_RESUME_CURSOR_VERSION) return null
            ResumeCursor(
                version = root.getInt("version"),
                historyGenerationUid = HistoryGenerationUid(root.getString("history_generation_uid")),
                sliceThroughOrder = root.getLong("slice_through_order"),
                sliceLeafCount = root.getInt("slice_leaf_count"),
                sliceFingerprint = root.getString("slice_fingerprint"),
                nextLeafIndex = root.getInt("next_leaf_index")
            ).also { cursor ->
                require(cursor.sliceThroughOrder >= 0)
                require(cursor.sliceLeafCount in 1..PHASE58_MAX_INPUT_SLICE_LEAVES)
                require(cursor.nextLeafIndex in 1 until cursor.sliceLeafCount)
                require(cursor.sliceFingerprint.isNotBlank())
            }
        }.getOrNull()
    }

    private fun episodeInterpretationJson(interpretation: EpisodeInterpretation): String =
        JSONObject().apply {
            put("episode_logical_uid", interpretation.episodeLogicalUid)
            put("locale_uid", interpretation.localeUid)
            put("title", interpretation.title)
            put("summary", interpretation.summary)
            put("tags", JSONArray(interpretation.presentationTags.toSortedSet().map { it.lowercase(Locale.ROOT) }))
        }.toString()
}

internal object CanonicalMemoryLeafProjection{
    fun hasPendingResume(db:SQLiteDatabase,campaignUid:String):Boolean = db.rawQuery(
        "SELECT 1 FROM ${Phase55To58MemorySchema.STATE} WHERE campaign_uid=? AND resume_cursor IS NOT NULL LIMIT 1",
        arrayOf(campaignUid)
    ).use{it.moveToFirst()}

    fun pending(db:SQLiteDatabase,campaignUid:String,maximumLeaves:Int=256):List<MemoryEventLeaf>{
        require(maximumLeaves>0)
        Phase55To58MemorySchema.ensureReady(db,campaignUid)
        val watermark=db.rawQuery(
            "SELECT watermark_order FROM ${Phase55To58MemorySchema.STATE} WHERE campaign_uid=?",arrayOf(campaignUid)
        ).use{c->if(c.moveToFirst())c.getLong(0) else 0L}
        val selected=mutableListOf<MemoryEventLeaf>()
        for(payload in CommittedReplayPayloadStore(db).afterLimited(campaignUid,watermark,maximumLeaves)){
            val transactionLeaves=forPayload(db,payload)
            if(selected.isNotEmpty()&&selected.size+transactionLeaves.size>maximumLeaves)break
            selected+=transactionLeaves
            if(selected.size>=maximumLeaves)break
        }
        return selected
    }

    fun forTransaction(db:SQLiteDatabase,campaignUid:String,transactionUid:String):List<MemoryEventLeaf>{
        val payload=TurnTransactionReceiptStore(db).committedTransaction(transactionUid)?.commitOrder?.let{order->
            CommittedReplayPayloadStore(db).between(campaignUid,order-1L,order).singleOrNull{it.identity.transactionUid==transactionUid}
        }?:return emptyList()
        return forPayload(db,payload)
    }

    private fun forPayload(db:SQLiteDatabase,payload:CommittedReplayPayload):List<MemoryEventLeaf>{
        val intents=payload.changeSet.eventIntents.associateBy{it.eventIntentUid}
        return CampaignEventStore(db,payload.identity.campaignUid).eventsForTransaction(payload.identity.transactionUid).map{event->
            val intent=intents[event.eventIntentUid]
            val subject=(intent?.payload as? DomainEffectEventIntentPayload)?.subject
            val participants=buildList<DomainRef>{
                add(DomainRef(payload.changeSet.actor.actorKindUid,payload.changeSet.actor.actorUid))
                intent?.actorRef?.let(::add)
                addAll(intent?.targetRefs.orEmpty())
                subject?.let(::add)
            }.distinctBy{it.kindUid to it.uid}
            val locations=participants.filter{ref->
                val kind=ref.kindUid.uppercase(Locale.ROOT)
                "LOCATION" in kind||"PLACE" in kind||"REGION" in kind
            }
            MemoryEventLeaf(
                eventUid=event.eventUid,
                turnUid=event.turnUid,
                committedOrder=requireNotNull(event.committedOrder),
                eventOrdinal=requireNotNull(event.eventOrdinal),
                participantRefs=participants,
                locationRefs=locations,
                explicitBoundaryBefore=false,
                fingerprint=event.semanticFingerprint
            )
        }
    }
}
