package com.rpgos.app

/**
 * Enriches a resolved GM turn with deterministic NPC-knowledge lifecycle writes.
 *
 * The language model still never decides retractions or conflict winners. This decorator:
 *  - gives every same-turn BELIEF a stable truthKey,
 *  - emits inference ledger writes for NPC_INFERENCE beliefs,
 *  - compares newly touched belief groups with durable active beliefs,
 *  - emits deterministic conflict resolutions and append-only retractions.
 *
 * Only holder/subject/predicate groups touched by a new BELIEF are evaluated. Historical
 * conflicts elsewhere remain unchanged until new evidence reaches that NPC.
 */
class NpcKnowledgeLifecycleRuleResolver141(
    private val delegate: GameMasterRuleResolver,
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid,
    private val retractionStore: NpcBeliefRetractionStore141? = null,
    private val lifecycle: NpcKnowledgeLifecycle141 = NpcKnowledgeLifecycle141()
) : GameMasterRuleResolver {

    override suspend fun resolve(
        request: GameMasterTurnRequest,
        context: GameMasterContext,
        proposal: GameMasterProposal
    ): GameMasterTurnResult {
        val base = delegate.resolve(request, context, proposal)
        if (base.truthWrites.none { it.kind == TruthKind.BELIEF }) return base

        val currentTurn = repository.currentTurnId(campaignUid)
        val resolvedTurn = currentTurn + 1L
        val keyedTruths = assignBeliefKeys(base.truthWrites)
        val keyedBeliefs = keyedTruths.filter { it.kind == TruthKind.BELIEF }
        val truthByKey = keyedTruths.mapNotNull { truth -> truth.truthKey?.let { it to truth } }.toMap()

        val generatedInferences = keyedBeliefs.mapNotNull { belief ->
            if (belief.knowledgeChannel != KnowledgeChannel141.INFERENCE &&
                belief.sourceType != ProvenanceType.NPC_INFERENCE
            ) return@mapNotNull null

            val key = requireNotNull(belief.truthKey)
            val source = belief.sourceId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NpcInferenceWrite141(
                holderId = requireNotNull(belief.holderId),
                resultingBelief = TruthRef141(truthKey = key),
                premiseTruths = listOf(sourceRef(source, truthByKey)),
                confidence = belief.confidence
            )
        }

        val generatedResolutions = mutableListOf<NpcKnowledgeResolutionWrite141>()
        val generatedRetractions = mutableListOf<NpcBeliefRetractionWrite141>()

        val touchedGroups = keyedBeliefs.groupBy {
            BeliefGroupKey(requireNotNull(it.holderId), it.subjectId, it.predicate)
        }

        for ((groupKey, newWrites) in touchedGroups) {
            val holderUid = EntityUid(groupKey.holderId)
            val subjectUid = groupKey.subjectId?.let(::EntityUid)
            val alreadyRetracted = retractionStore
                ?.retractionsForHolder(campaignUid, holderUid, currentTurn)
                ?.mapTo(hashSetOf()) { it.retractedBeliefUid }
                .orEmpty()

            val durableBeliefs = repository.getBeliefs(
                campaignUid = campaignUid,
                holderUid = holderUid,
                subjectUid = subjectUid,
                atTurnId = currentTurn,
                limit = NpcBeliefTimeline141.MAX_BELIEF_QUERY_LIMIT
            ).filter {
                it.kind == TruthKind.BELIEF &&
                    it.holderUid == holderUid &&
                    it.subjectUid == subjectUid &&
                    it.predicate == groupKey.predicate &&
                    it.uid !in alreadyRetracted
            }

            val syntheticRefs = linkedMapOf<EntityUid, TruthRef141>()
            val syntheticBeliefs = newWrites.map { write ->
                val key = requireNotNull(write.truthKey)
                val syntheticUid = EntityUid("TURNKEY-$key")
                syntheticRefs[syntheticUid] = TruthRef141(truthKey = key)
                write.toSyntheticTruth(syntheticUid, resolvedTurn)
            }

            val lifecycleResult = lifecycle.resolve(
                holderUid = holderUid,
                turnId = resolvedTurn,
                beliefs = durableBeliefs + syntheticBeliefs
            )

            lifecycleResult.resolutions.forEach { resolution ->
                val refs = resolution.conflict.competingBeliefs.map { truth ->
                    syntheticRefs[truth.uid] ?: TruthRef141(durableUid = truth.uid.value)
                }
                val winnerRef = resolution.winner?.let { winner ->
                    syntheticRefs[winner.uid] ?: TruthRef141(durableUid = winner.uid.value)
                }
                val supersededRefs = resolution.supersededBeliefUids.map { uid ->
                    syntheticRefs[uid] ?: TruthRef141(durableUid = uid.value)
                }

                generatedResolutions += NpcKnowledgeResolutionWrite141(
                    holderId = groupKey.holderId,
                    subjectId = groupKey.subjectId,
                    predicate = groupKey.predicate,
                    competingBeliefs = refs,
                    winner = winnerRef,
                    supersededBeliefs = supersededRefs,
                    reason = resolution.reason
                )

                if (winnerRef != null) {
                    supersededRefs.forEach { loserRef ->
                        if (loserRef != winnerRef) {
                            generatedRetractions += NpcBeliefRetractionWrite141(
                                holderId = groupKey.holderId,
                                retractedBelief = loserRef,
                                replacementTruth = winnerRef,
                                reason = "Deterministic belief resolution: ${resolution.reason}"
                            )
                        }
                    }
                }
            }
        }

        val merged = NpcKnowledgeWrites141(
            retractions = (base.npcKnowledgeWrites.retractions + generatedRetractions)
                .distinctBy { Triple(it.holderId, it.retractedBelief, it.replacementTruth) },
            inferences = (base.npcKnowledgeWrites.inferences + generatedInferences)
                .distinctBy { Triple(it.holderId, it.resultingBelief, it.premiseTruths) },
            organizationTransmissions = base.npcKnowledgeWrites.organizationTransmissions,
            resolutions = (base.npcKnowledgeWrites.resolutions + generatedResolutions)
                .distinctBy { Triple(it.holderId, it.predicate, it.competingBeliefs.toSet()) }
        )

        return base.copy(truthWrites = keyedTruths, npcKnowledgeWrites = merged)
    }

    private fun assignBeliefKeys(truths: List<TruthWrite>): List<TruthWrite> {
        val used = truths.mapNotNullTo(linkedSetOf()) { it.truthKey }
        var sequence = 1
        return truths.map { truth ->
            if (truth.kind != TruthKind.BELIEF || truth.truthKey != null) return@map truth
            var candidate: String
            do {
                candidate = "npc-belief-${sequence++}"
            } while (!used.add(candidate))
            truth.copy(truthKey = candidate)
        }
    }

    private fun sourceRef(sourceId: String, keyedTruths: Map<String, TruthWrite>): TruthRef141 =
        if (sourceId in keyedTruths) TruthRef141(truthKey = sourceId)
        else TruthRef141(durableUid = sourceId)

    private fun TruthWrite.toSyntheticTruth(uid: EntityUid, resolvedTurn: Long): CampaignTruth =
        CampaignTruth(
            uid = uid,
            kind = kind,
            subjectUid = subjectId?.let(::EntityUid),
            predicate = predicate,
            value = value,
            holderUid = holderId?.let(::EntityUid),
            validFromTurn = validFromTurn ?: resolvedTurn,
            validUntilTurn = validUntilTurn,
            provenance = ProvenanceRecord(
                type = sourceType,
                sourceUid = sourceId?.let(::EntityUid),
                turnId = validFromTurn ?: resolvedTurn,
                confidence = confidence,
                verified = sourceType == ProvenanceType.NPC_OBSERVATION || sourceType == ProvenanceType.NPC_RESEARCH
            )
        )

    private data class BeliefGroupKey(
        val holderId: String,
        val subjectId: String?,
        val predicate: String
    )
}
