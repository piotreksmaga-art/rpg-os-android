package com.rpgos.app

import org.json.JSONObject
import java.util.UUID

/**
 * First production persistence bridge for GM Engine 141.
 *
 * The resolved turn is still treated as uncommitted until every durable write
 * succeeds inside one SQLite transaction. Turn numbers are allocated from the
 * database while the transaction is active, never trusted from model output.
 */
class GameMasterStateRepository141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid,
    private val knowledgeStore: KnowledgeTransmissionStore141? = null,
    private val npcKnowledgeStores: SQLiteNpcKnowledgeStores141? = null
) : GameMasterStateRepository {

    override suspend fun commitTurn(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        result: GameMasterTurnResult
    ) {
        require(request.campaignId == campaignUid.value) {
            "Żądanie dotyczy kampanii ${request.campaignId}, a repozytorium ${campaignUid.value}."
        }
        require(context.campaignId == campaignUid.value) {
            "ContextBundle należy do innej kampanii: ${context.campaignId}."
        }

        repository.inTransaction {
            val previousTurn = currentTurnId(campaignUid)
            val turnId = previousTurn + 1L
            val now = System.currentTimeMillis()

            val eventUids = LinkedHashMap<String, EntityUid>()
            result.worldEvents.forEach { event ->
                require(event.eventKey !in eventUids) { "Duplikat eventKey: ${event.eventKey}" }
                eventUids[event.eventKey] = uid("EVT")
            }

            // Allocate all keyed truth UIDs before any write. Knowledge lifecycle records may
            // safely refer to any truth created by this accepted turn, regardless of ordering.
            val truthUidsByKey = LinkedHashMap<String, EntityUid>()
            result.truthWrites.forEach { truth ->
                truth.truthKey?.let { key ->
                    require(key !in truthUidsByKey) { "Duplikat truthKey: $key" }
                    truthUidsByKey[key] = uid(if (truth.kind == TruthKind.BELIEF) "BELIEF" else "FACT")
                }
            }

            writeTurn(
                DurableTurnRecord(
                    turnUid = uid("TURN"),
                    campaignUid = campaignUid,
                    turnId = turnId,
                    chapter = request.currentChapter,
                    playerInput = request.playerAction,
                    narrative = result.narrative,
                    startedAtEpochMs = now,
                    committedAtEpochMs = now,
                    status = TurnTransactionStatus.COMMITTED
                )
            )

            result.worldEvents.forEachIndexed { index, event ->
                appendEvent(
                    DurableCampaignEvent(
                        eventUid = requireNotNull(eventUids[event.eventKey]),
                        campaignUid = campaignUid,
                        turnId = turnId,
                        sequence = index.toLong() + 1L,
                        type = eventType(event.eventType),
                        actorUid = event.actorId.asUidOrNull(),
                        targetUid = event.targetId.asUidOrNull(),
                        causeEventUid = event.causeEventKey?.let { key ->
                            requireNotNull(eventUids[key]) { "Nieznany causeEventKey: $key" }
                        },
                        description = event.description,
                        payloadJson = durableEventPayload(event),
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.SYSTEM_SIMULATION,
                            sourceUid = null,
                            turnId = turnId,
                            confidence = 1.0,
                            verified = true
                        )
                    )
                )
            }

            result.stateMutations.forEach { mutation ->
                applyMutation(
                    DurableStateMutation(
                        mutationUid = uid("MUT"),
                        campaignUid = campaignUid,
                        turnId = turnId,
                        entityUid = EntityUid(mutation.entityId),
                        entityType = mutation.entityType,
                        field = mutation.field,
                        operation = mutation.operation,
                        oldValue = mutation.oldValue,
                        newValue = mutation.newValue,
                        reason = mutation.reason,
                        causedByEventUid = mutation.causedByEventKey?.let { key ->
                            requireNotNull(eventUids[key]) { "Nieznany causedByEventKey: $key" }
                        }
                    )
                )
            }

            val durableTruthsByUid = LinkedHashMap<EntityUid, CampaignTruth>()
            result.truthWrites.forEach { truth ->
                val sourceUid = when {
                    truth.sourceId.isNullOrBlank() -> null
                    truth.sourceType == ProvenanceType.CAMPAIGN_EVENT ->
                        eventUids[truth.sourceId] ?: EntityUid(truth.sourceId)
                    else -> EntityUid(truth.sourceId)
                }
                val durableTruth = CampaignTruth(
                    uid = truth.truthKey?.let { requireNotNull(truthUidsByKey[it]) }
                        ?: uid(if (truth.kind == TruthKind.BELIEF) "BELIEF" else "FACT"),
                    kind = truth.kind,
                    subjectUid = truth.subjectId.asUidOrNull(),
                    predicate = truth.predicate,
                    value = truth.value,
                    holderUid = truth.holderId.asUidOrNull(),
                    validFromTurn = truth.validFromTurn ?: turnId,
                    validUntilTurn = truth.validUntilTurn,
                    provenance = ProvenanceRecord(
                        type = truth.sourceType,
                        sourceUid = sourceUid,
                        turnId = turnId,
                        confidence = truth.confidence,
                        verified = truth.sourceType in VERIFIED_PROVENANCE
                    )
                )
                writeTruth(durableTruth)
                durableTruthsByUid[durableTruth.uid] = durableTruth

                if (truth.kind == TruthKind.BELIEF) {
                    val channel = requireNotNull(truth.knowledgeChannel) {
                        "BELIEF ${truth.predicate} nie ma knowledgeChannel."
                    }
                    val sourceTruthUid = requireNotNull(sourceUid) {
                        "BELIEF ${truth.predicate} nie ma trwałego sourceId."
                    }
                    val receiverUid = requireNotNull(durableTruth.holderUid) {
                        "BELIEF ${truth.predicate} nie ma holderUid."
                    }
                    val ledger = requireNotNull(knowledgeStore) {
                        "Brak KnowledgeTransmissionStore141 dla zapisu BELIEF."
                    }
                    ledger.appendKnowledgeTransmission(
                        KnowledgeTransmission141(
                            transmissionUid = uid("KNOW"),
                            campaignUid = campaignUid,
                            sourceTruthUid = sourceTruthUid,
                            sourceNpcUid = truth.sourceNpcId.asUidOrNull(),
                            receiverUid = receiverUid,
                            resultingBeliefUid = durableTruth.uid,
                            channel = channel,
                            turnId = turnId,
                            confidence = durableTruth.provenance.confidence
                        )
                    )
                }
            }

            val lifecycle = result.npcKnowledgeWrites
            if (
                lifecycle.retractions.isNotEmpty() || lifecycle.inferences.isNotEmpty() ||
                lifecycle.organizationTransmissions.isNotEmpty() || lifecycle.resolutions.isNotEmpty()
            ) {
                val stores = requireNotNull(npcKnowledgeStores) {
                    "Brak SQLiteNpcKnowledgeStores141 dla lifecycle wiedzy NPC."
                }

                lifecycle.retractions.forEach { write ->
                    stores.retractions.appendRetraction(
                        NpcBeliefRetraction141(
                            retractionUid = uid("RETRACTION"),
                            campaignUid = campaignUid,
                            holderUid = EntityUid(write.holderId),
                            retractedBeliefUid = resolveTruthRef(write.retractedBelief, truthUidsByKey),
                            replacementTruthUid = resolveTruthRef(write.replacementTruth, truthUidsByKey),
                            turnId = turnId,
                            reason = write.reason
                        )
                    )
                }

                lifecycle.inferences.forEach { write ->
                    stores.inferences.appendInference(
                        NpcInferenceLedgerEntry141(
                            inferenceUid = uid("INFERENCE"),
                            campaignUid = campaignUid,
                            holderUid = EntityUid(write.holderId),
                            resultingBeliefUid = resolveTruthRef(write.resultingBelief, truthUidsByKey),
                            premiseTruthUids = write.premiseTruths.map { resolveTruthRef(it, truthUidsByKey) },
                            turnId = turnId,
                            confidence = write.confidence
                        )
                    )
                }

                lifecycle.organizationTransmissions.forEach { write ->
                    stores.organizations.appendOrganizationKnowledge(
                        OrganizationKnowledgeTransmission141(
                            transmissionUid = uid("ORGKNOW"),
                            campaignUid = campaignUid,
                            organizationUid = EntityUid(write.organizationId),
                            membershipUid = EntityUid(write.membershipId),
                            publicationUid = EntityUid(write.publicationId),
                            sourceTruthUid = resolveTruthRef(write.sourceTruth, truthUidsByKey),
                            receiverUid = EntityUid(write.receiverId),
                            resultingBeliefUid = resolveTruthRef(write.resultingBelief, truthUidsByKey),
                            turnId = turnId,
                            confidence = write.confidence
                        )
                    )
                }

                lifecycle.resolutions.forEach { write ->
                    fun auditTruth(ref: TruthRef141): CampaignTruth {
                        val resolvedUid = resolveTruthRef(ref, truthUidsByKey)
                        return durableTruthsByUid[resolvedUid] ?: CampaignTruth(
                            uid = resolvedUid,
                            kind = TruthKind.BELIEF,
                            subjectUid = write.subjectId.asUidOrNull(),
                            predicate = write.predicate,
                            value = "<historical>",
                            holderUid = EntityUid(write.holderId),
                            validFromTurn = null,
                            provenance = ProvenanceRecord(
                                type = ProvenanceType.SYSTEM_SIMULATION,
                                sourceUid = null,
                                turnId = turnId,
                                confidence = 1.0,
                                verified = true
                            )
                        )
                    }
                    val competing = write.competingBeliefs.map(::auditTruth)
                    stores.resolutions.appendResolution(
                        NpcKnowledgeLifecycle141.Resolution(
                            resolutionUid = uid("RESOLUTION"),
                            conflict = NpcKnowledgeLifecycle141.Conflict(
                                holderUid = EntityUid(write.holderId),
                                subjectUid = write.subjectId.asUidOrNull(),
                                predicate = write.predicate,
                                competingBeliefs = competing
                            ),
                            winner = write.winner?.let(::auditTruth),
                            supersededBeliefUids = write.supersededBeliefs.map { resolveTruthRef(it, truthUidsByKey) },
                            reason = write.reason,
                            turnId = turnId
                        )
                    )
                }
            }

            result.divergenceWrites.forEach { divergence ->
                writeDivergence(
                    CanonDivergence(
                        uid = uid("DIV"),
                        canonSubjectUid = EntityUid(divergence.canonSubjectId),
                        canonEventUid = divergence.canonEventId.asUidOrNull(),
                        divergenceType = divergence.divergenceType,
                        description = divergence.description,
                        causedByEventUid = divergence.causedByEventKey?.let { key ->
                            requireNotNull(eventUids[key]) { "Nieznany divergence causedByEventKey: $key" }
                        },
                        createdTurn = turnId,
                        active = true
                    )
                )
            }

            result.memoryWrites.forEach { memory ->
                writeMemory(
                    DurableMemoryRecord(
                        memoryUid = uid("MEM"),
                        campaignUid = campaignUid,
                        kind = memoryKind(memory.memoryType),
                        subjectUid = memory.subjectId.asUidOrNull(),
                        text = memory.text,
                        importance = memory.importance,
                        createdTurn = turnId,
                        sourceEventUids = memory.tags.mapNotNull { tag ->
                            if (!tag.startsWith(EVENT_TAG_PREFIX)) null
                            else eventUids[tag.removePrefix(EVENT_TAG_PREFIX)]
                        }.toSet(),
                        tags = memory.tags
                    )
                )
            }

            val allTurnEvents = eventUids.values.toSet()
            result.chronicleEntries.forEach { chronicle ->
                writeChronicle(
                    DurableChronicleRecord(
                        entryUid = uid("CHR"),
                        campaignUid = campaignUid,
                        turnId = turnId,
                        chapter = chronicle.chapter,
                        title = chronicle.title,
                        summary = chronicle.summary,
                        eventUids = allTurnEvents
                    )
                )
            }
        }
    }

    private fun resolveTruthRef(ref: TruthRef141, truthUidsByKey: Map<String, EntityUid>): EntityUid =
        ref.truthKey?.let { key -> requireNotNull(truthUidsByKey[key]) { "Nieznany truthKey: $key" } }
            ?: EntityUid(requireNotNull(ref.durableUid))

    private fun durableEventPayload(event: WorldEventWrite): String = JSONObject().apply {
        put("event_key", event.eventKey)
        put("effective_chapter", event.effectiveChapter)
        put("visibility", event.visibility.name)
        put("payload", JSONObject(event.payloadJson))
    }.toString()

    private fun eventType(raw: String): CampaignEventType {
        val normalized = raw.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
        return runCatching { CampaignEventType.valueOf(normalized) }
            .getOrDefault(CampaignEventType.CUSTOM)
    }

    private fun memoryKind(type: MemoryType): DurableMemoryKind = when (type) {
        MemoryType.FACT,
        MemoryType.PLAYER_PREFERENCE,
        MemoryType.LONG_TERM_THREAD -> DurableMemoryKind.SEMANTIC
        else -> DurableMemoryKind.EPISODIC
    }

    private fun String?.asUidOrNull(): EntityUid? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.let(::EntityUid)

    private fun uid(prefix: String): EntityUid = EntityUid("$prefix-${UUID.randomUUID()}")

    companion object {
        private const val EVENT_TAG_PREFIX = "event:"
        private val VERIFIED_PROVENANCE = setOf(
            ProvenanceType.WORLD_CANON,
            ProvenanceType.CAMPAIGN_EVENT,
            ProvenanceType.PLAYER_STATE,
            ProvenanceType.SYSTEM_SIMULATION
        )
    }
}

class GameMasterTurnValidator141(
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid
) : GameMasterTurnValidator {

    override suspend fun validate(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        result: GameMasterTurnResult
    ): GameMasterValidationReport {
        val issues = mutableListOf<GameMasterValidationIssue>()

        errorIf(request.campaignId != campaignUid.value, issues, "CAMPAIGN_MISMATCH", "Żądanie nie należy do aktywnej kampanii.")
        errorIf(context.campaignId != campaignUid.value, issues, "CONTEXT_CAMPAIGN_MISMATCH", "Kontekst nie należy do aktywnej kampanii.")
        errorIf(context.chapter != request.currentChapter, issues, "CHAPTER_MISMATCH", "Rozdział kontekstu i żądania jest różny.")
        errorIf(result.narrative.isBlank(), issues, "EMPTY_NARRATIVE", "Narracja jest pusta.")

        val eventKeys = result.worldEvents.map { it.eventKey }
        errorIf(eventKeys.any { it.isBlank() }, issues, "EMPTY_EVENT_KEY", "Event ma pusty eventKey.")
        errorIf(eventKeys.size != eventKeys.toSet().size, issues, "DUPLICATE_EVENT_KEY", "W jednej turze występują zduplikowane eventKey.")
        val eventKeySet = eventKeys.toSet()

        result.worldEvents.forEach { event ->
            errorIf(event.description.isBlank(), issues, "EMPTY_EVENT_DESCRIPTION", "Event ${event.eventKey} nie ma opisu.")
            errorIf(event.effectiveChapter < 0L, issues, "INVALID_EVENT_CHAPTER", "Event ${event.eventKey} ma ujemny effectiveChapter.")
            errorIf(event.causeEventKey != null && event.causeEventKey !in eventKeySet, issues, "UNKNOWN_EVENT_CAUSE", "Event ${event.eventKey} wskazuje nieznany causeEventKey=${event.causeEventKey}.")
            if (runCatching { JSONObject(event.payloadJson) }.isFailure) {
                issues += error("INVALID_EVENT_JSON", "Event ${event.eventKey} zawiera niepoprawny payload JSON.")
            }
        }

        val mutationKeys = result.stateMutations.map { Triple(it.entityType, it.entityId, it.field) }
        errorIf(mutationKeys.size != mutationKeys.toSet().size, issues, "DUPLICATE_FIELD_MUTATION", "Jedna tura nie może modyfikować tego samego pola stanu więcej niż raz.")

        result.stateMutations.forEach { mutation ->
            errorIf(mutation.entityType.isBlank(), issues, "EMPTY_ENTITY_TYPE", "Mutacja ma pusty entityType.")
            errorIf(mutation.entityId.isBlank(), issues, "EMPTY_ENTITY_ID", "Mutacja ma pusty entityId.")
            errorIf(mutation.field.isBlank(), issues, "EMPTY_FIELD", "Mutacja ma puste pole.")
            errorIf(mutation.reason.isBlank(), issues, "EMPTY_MUTATION_REASON", "Mutacja ${mutation.entityId}.${mutation.field} nie ma uzasadnienia.")
            errorIf(mutation.causedByEventKey != null && mutation.causedByEventKey !in eventKeySet, issues, "UNKNOWN_MUTATION_CAUSE", "Mutacja ${mutation.entityId}.${mutation.field} wskazuje nieznany event ${mutation.causedByEventKey}.")
            val requiresValue = mutation.operation != MutationOperation.REMOVE
            errorIf(requiresValue && mutation.newValue == null, issues, "MISSING_MUTATION_VALUE", "Mutacja ${mutation.operation} ${mutation.entityId}.${mutation.field} nie ma newValue.")

            if (mutation.entityId.isNotBlank() && mutation.entityType.isNotBlank() && mutation.field.isNotBlank()) {
                val current = repository.getEntityState(campaignUid, EntityUid(mutation.entityId), mutation.entityType)
                    .firstOrNull { it.field == mutation.field }?.value
                if (mutation.oldValue != null && current != mutation.oldValue) {
                    issues += error("STALE_OLD_VALUE", "${mutation.entityId}.${mutation.field}: oczekiwano '${mutation.oldValue}', Source of Truth ma '$current'.")
                }
            }
        }

        val truthKeys = result.truthWrites.mapNotNull { it.truthKey }
        errorIf(truthKeys.size != truthKeys.toSet().size, issues, "DUPLICATE_TRUTH_KEY", "Jedna tura nie może zawierać zduplikowanego truthKey.")
        val truthKeySet = truthKeys.toSet()

        result.truthWrites.forEach { truth ->
            errorIf(truth.predicate.isBlank(), issues, "EMPTY_PREDICATE", "Fact/belief ma pusty predicate.")
            errorIf(truth.confidence !in 0.0..1.0, issues, "INVALID_TRUTH_CONFIDENCE", "Fact/belief ${truth.predicate} ma confidence poza zakresem 0..1.")
            errorIf(truth.kind == TruthKind.BELIEF && truth.holderId.isNullOrBlank(), issues, "BELIEF_WITHOUT_HOLDER", "BELIEF ${truth.predicate} nie ma holderId.")
            errorIf(truth.kind == TruthKind.BELIEF && truth.knowledgeChannel == null, issues, "BELIEF_WITHOUT_KNOWLEDGE_CHANNEL", "BELIEF ${truth.predicate} nie ma jawnego kanału wiedzy.")
            errorIf(truth.kind == TruthKind.BELIEF && truth.sourceId.isNullOrBlank(), issues, "BELIEF_WITHOUT_SOURCE", "BELIEF ${truth.predicate} nie wskazuje trwałego źródła.")
            errorIf(truth.kind == TruthKind.BELIEF && truth.knowledgeChannel == KnowledgeChannel141.REPORT && truth.sourceNpcId.isNullOrBlank(), issues, "REPORT_WITHOUT_SOURCE_NPC", "BELIEF ${truth.predicate} z kanału REPORT nie wskazuje nadawcy.")
            errorIf(truth.kind == TruthKind.BELIEF && truth.knowledgeChannel == KnowledgeChannel141.ORGANIZATION && !truth.sourceNpcId.isNullOrBlank(), issues, "ORGANIZATION_WITH_SOURCE_NPC", "BELIEF ${truth.predicate} z kanału ORGANIZATION nie może wskazywać nadawcy NPC.")
            if (truth.kind == TruthKind.BELIEF && truth.knowledgeChannel != null) {
                val expected = when (truth.knowledgeChannel) {
                    KnowledgeChannel141.OBSERVATION -> ProvenanceType.NPC_OBSERVATION
                    KnowledgeChannel141.REPORT -> ProvenanceType.NPC_REPORT
                    KnowledgeChannel141.RESEARCH -> ProvenanceType.NPC_RESEARCH
                    KnowledgeChannel141.INFERENCE -> ProvenanceType.NPC_INFERENCE
                    KnowledgeChannel141.ORGANIZATION -> ProvenanceType.ORGANIZATION_REPORT
                }
                errorIf(truth.sourceType != expected, issues, "KNOWLEDGE_PROVENANCE_MISMATCH", "BELIEF ${truth.predicate}: kanał ${truth.knowledgeChannel} nie pasuje do ${truth.sourceType}.")
            }
            errorIf(truth.kind != TruthKind.BELIEF && truth.knowledgeChannel != null, issues, "KNOWLEDGE_CHANNEL_ON_NON_BELIEF", "Tylko BELIEF może posiadać knowledgeChannel.")
            errorIf(truth.validFromTurn != null && truth.validUntilTurn != null && truth.validUntilTurn < truth.validFromTurn, issues, "INVALID_TRUTH_INTERVAL", "Fact/belief ${truth.predicate} ma odwrócony przedział czasu.")
            if (truth.sourceType == ProvenanceType.CAMPAIGN_EVENT && !truth.sourceId.isNullOrBlank() && truth.sourceId !in eventKeySet) {
                issues += warning("EXTERNAL_EVENT_PROVENANCE", "${truth.predicate} wskazuje CAMPAIGN_EVENT spoza bieżącej tury; sourceId zostanie potraktowane jako trwały UID.")
            }
        }

        fun validateRef(ref: TruthRef141, label: String) {
            if (ref.truthKey != null) {
                errorIf(ref.truthKey !in truthKeySet, issues, "UNKNOWN_TRUTH_KEY", "$label wskazuje nieznany truthKey=${ref.truthKey}.")
            }
        }
        result.npcKnowledgeWrites.retractions.forEach { write ->
            errorIf(write.holderId.isBlank(), issues, "EMPTY_RETRACTION_HOLDER", "Retrakcja nie ma holderId.")
            errorIf(write.reason.isBlank(), issues, "EMPTY_RETRACTION_REASON", "Retrakcja nie ma powodu.")
            validateRef(write.retractedBelief, "Retrakcja")
            validateRef(write.replacementTruth, "Retrakcja")
        }
        result.npcKnowledgeWrites.inferences.forEach { write ->
            errorIf(write.holderId.isBlank(), issues, "EMPTY_INFERENCE_HOLDER", "Inference nie ma holderId.")
            validateRef(write.resultingBelief, "Inference")
            write.premiseTruths.forEach { validateRef(it, "Inference premise") }
        }
        result.npcKnowledgeWrites.organizationTransmissions.forEach { write ->
            errorIf(write.organizationId.isBlank() || write.membershipId.isBlank() || write.publicationId.isBlank(), issues, "INVALID_ORGANIZATION_KNOWLEDGE", "Organization knowledge wymaga organization/membership/publication ID.")
            errorIf(write.receiverId.isBlank(), issues, "EMPTY_ORGANIZATION_RECEIVER", "Organization knowledge nie ma receiverId.")
            validateRef(write.sourceTruth, "Organization source")
            validateRef(write.resultingBelief, "Organization result")
        }
        result.npcKnowledgeWrites.resolutions.forEach { write ->
            errorIf(write.holderId.isBlank(), issues, "EMPTY_RESOLUTION_HOLDER", "Resolution nie ma holderId.")
            write.competingBeliefs.forEach { validateRef(it, "Resolution competing belief") }
            write.winner?.let { validateRef(it, "Resolution winner") }
            write.supersededBeliefs.forEach { validateRef(it, "Resolution superseded belief") }
        }

        result.divergenceWrites.forEach { divergence ->
            errorIf(divergence.canonSubjectId.isBlank(), issues, "EMPTY_CANON_SUBJECT", "Divergence nie ma canonSubjectId.")
            errorIf(divergence.divergenceType.isBlank(), issues, "EMPTY_DIVERGENCE_TYPE", "Divergence nie ma typu.")
            errorIf(divergence.description.isBlank(), issues, "EMPTY_DIVERGENCE_DESCRIPTION", "Divergence nie ma opisu.")
            errorIf(divergence.causedByEventKey != null && divergence.causedByEventKey !in eventKeySet, issues, "UNKNOWN_DIVERGENCE_CAUSE", "Divergence wskazuje nieznany event ${divergence.causedByEventKey}.")
        }

        result.memoryWrites.forEach { memory ->
            errorIf(memory.text.isBlank(), issues, "EMPTY_MEMORY", "Pamięć ma pustą treść.")
            errorIf(memory.importance !in 0.0..1.0, issues, "INVALID_MEMORY_IMPORTANCE", "Pamięć ma importance poza zakresem 0..1.")
            errorIf(memory.chapter > request.currentChapter, issues, "FUTURE_MEMORY", "Pamięć nie może powstać w przyszłym rozdziale.")
            memory.tags.filter { it.startsWith(EVENT_TAG_PREFIX) }.forEach { tag ->
                val key = tag.removePrefix(EVENT_TAG_PREFIX)
                errorIf(key !in eventKeySet, issues, "UNKNOWN_MEMORY_EVENT", "Pamięć wskazuje nieznany eventKey=$key.")
            }
        }

        result.chronicleEntries.forEach { chronicle ->
            errorIf(chronicle.title.isBlank(), issues, "EMPTY_CHRONICLE_TITLE", "Wpis kroniki nie ma tytułu.")
            errorIf(chronicle.summary.isBlank(), issues, "EMPTY_CHRONICLE_SUMMARY", "Wpis kroniki nie ma podsumowania.")
            errorIf(chronicle.chapter != request.currentChapter, issues, "CHRONICLE_CHAPTER_MISMATCH", "Wpis kroniki ma rozdział ${chronicle.chapter}, oczekiwano ${request.currentChapter}.")
        }

        return GameMasterValidationReport(issues)
    }

    private fun errorIf(condition: Boolean, issues: MutableList<GameMasterValidationIssue>, code: String, message: String) {
        if (condition) issues += error(code, message)
    }

    private fun error(code: String, message: String) = GameMasterValidationIssue(code, message, ValidationSeverity.ERROR)
    private fun warning(code: String, message: String) = GameMasterValidationIssue(code, message, ValidationSeverity.WARNING)

    companion object {
        private const val EVENT_TAG_PREFIX = "event:"
    }
}