from pathlib import Path

schema = Path('app/src/main/java/com/rpgos/app/CampaignSourceOfTruthDb.kt')
s = schema.read_text()
anchor = '''        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_snapshots (
'''
table = '''        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS gm_knowledge_transmissions (
                transmission_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                source_truth_id TEXT NOT NULL,
                source_npc_id TEXT,
                receiver_id TEXT NOT NULL,
                resulting_belief_id TEXT NOT NULL,
                channel TEXT NOT NULL CHECK(channel IN ('OBSERVATION','REPORT','RESEARCH','INFERENCE')),
                turn_number INTEGER NOT NULL,
                confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0),
                created_at INTEGER NOT NULL,
                FOREIGN KEY(source_truth_id) REFERENCES gm_facts(fact_id),
                FOREIGN KEY(resulting_belief_id) REFERENCES gm_facts(fact_id)
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_gm_knowledge_receiver_turn ON gm_knowledge_transmissions(campaign_id,receiver_id,turn_number DESC)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_gm_knowledge_source ON gm_knowledge_transmissions(campaign_id,source_truth_id)"
        )

'''
if s.count(anchor) != 1:
    raise SystemExit(f'schema anchor mismatch: {s.count(anchor)}')
s = s.replace(anchor, table + anchor, 1)
schema.write_text(s)

repo = Path('app/src/main/java/com/rpgos/app/SQLiteUnifiedCampaignRepository.kt')
s = repo.read_text()
old = ') : UnifiedCampaignRepository, SnapshotRetention141, Closeable {'
new = ') : UnifiedCampaignRepository, SnapshotRetention141, KnowledgeTransmissionStore141, Closeable {'
if s.count(old) != 1:
    raise SystemExit(f'repo declaration mismatch: {s.count(old)}')
s = s.replace(old, new, 1)
marker = '''    override suspend fun <T> inTransaction(block: suspend UnifiedCampaignRepository.() -> T): T {
'''
methods = '''    override suspend fun appendKnowledgeTransmission(record: KnowledgeTransmission141) {
        requireCampaign(record.campaignUid)
        val values = ContentValues().apply {
            put("transmission_id", record.transmissionUid.value)
            put("campaign_id", record.campaignUid.value)
            put("source_truth_id", record.sourceTruthUid.value)
            record.sourceNpcUid?.let { put("source_npc_id", it.value) }
            put("receiver_id", record.receiverUid.value)
            put("resulting_belief_id", record.resultingBeliefUid.value)
            put("channel", record.channel.name)
            put("turn_number", record.turnId)
            put("confidence", record.confidence)
            put("created_at", System.currentTimeMillis())
        }
        require(db.insertOrThrow("gm_knowledge_transmissions", null, values) != -1L) {
            "Nie można zapisać transmisji wiedzy ${record.transmissionUid}."
        }
    }

    override suspend fun knowledgeTransmissionsForReceiver(
        campaignUid: EntityUid,
        receiverUid: EntityUid,
        beforeOrAtTurn: Long?,
        limit: Int
    ): List<KnowledgeTransmission141> {
        requireCampaign(campaignUid)
        require(limit >= 0) { "limit nie może być ujemny." }
        if (limit == 0) return emptyList()
        val whereTurn = if (beforeOrAtTurn == null) "" else " AND turn_number<=?"
        val args = mutableListOf(campaignUid.value, receiverUid.value)
        if (beforeOrAtTurn != null) args += beforeOrAtTurn.toString()
        val out = mutableListOf<KnowledgeTransmission141>()
        db.rawQuery(
            """
            SELECT transmission_id,source_truth_id,source_npc_id,resulting_belief_id,channel,turn_number,confidence
            FROM gm_knowledge_transmissions
            WHERE campaign_id=? AND receiver_id=?$whereTurn
            ORDER BY turn_number DESC, created_at DESC
            LIMIT $limit
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                out += KnowledgeTransmission141(
                    transmissionUid = EntityUid(c.getString(0)),
                    campaignUid = campaignUid,
                    sourceTruthUid = EntityUid(c.getString(1)),
                    sourceNpcUid = if (c.isNull(2)) null else EntityUid(c.getString(2)),
                    receiverUid = receiverUid,
                    resultingBeliefUid = EntityUid(c.getString(3)),
                    channel = KnowledgeChannel141.valueOf(c.getString(4)),
                    turnId = c.getLong(5),
                    confidence = c.getDouble(6)
                )
            }
        }
        return out
    }

'''
if s.count(marker) != 1:
    raise SystemExit(f'transaction marker mismatch: {s.count(marker)}')
s = s.replace(marker, methods + marker, 1)
repo.write_text(s)

persist = Path('app/src/main/java/com/rpgos/app/GameMasterPersistence141.kt')
s = persist.read_text()
old = '''            result.truthWrites.forEach { truth ->
                val sourceUid = when {
                    truth.sourceId.isNullOrBlank() -> null
                    truth.sourceType == ProvenanceType.CAMPAIGN_EVENT ->
                        eventUids[truth.sourceId] ?: EntityUid(truth.sourceId)
                    else -> EntityUid(truth.sourceId)
                }
                writeTruth(
                    CampaignTruth(
                        uid = uid("FACT"),
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
                )
            }
'''
new = '''            result.truthWrites.forEach { truth ->
                val sourceUid = when {
                    truth.sourceId.isNullOrBlank() -> null
                    truth.sourceType == ProvenanceType.CAMPAIGN_EVENT ->
                        eventUids[truth.sourceId] ?: EntityUid(truth.sourceId)
                    else -> EntityUid(truth.sourceId)
                }
                val truthUid = uid(if (truth.kind == TruthKind.BELIEF) "BELIEF" else "FACT")
                val durableTruth = CampaignTruth(
                    uid = truthUid,
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

                val channel = knowledgeChannelFor(truth.sourceType)
                if (truth.kind == TruthKind.BELIEF && channel != null && sourceUid != null && this is KnowledgeTransmissionStore141) {
                    val receiver = requireNotNull(durableTruth.holderUid) { "BELIEF transmisji nie ma holderUid." }
                    appendKnowledgeTransmission(
                        KnowledgeTransmission141(
                            transmissionUid = uid("KNOW"),
                            campaignUid = campaignUid,
                            sourceTruthUid = sourceUid,
                            sourceNpcUid = if (channel == KnowledgeChannel141.REPORT) truthSourceNpc(truth) else null,
                            receiverUid = receiver,
                            resultingBeliefUid = truthUid,
                            channel = channel,
                            turnId = turnId,
                            confidence = truth.confidence
                        )
                    )
                }
            }
'''
if s.count(old) != 1:
    raise SystemExit(f'truth persistence block mismatch: {s.count(old)}')
s = s.replace(old, new, 1)
marker = '''    private fun durableEventPayload(event: WorldEventWrite): String = JSONObject().apply {
'''
helpers = '''    private fun knowledgeChannelFor(type: ProvenanceType): KnowledgeChannel141? = when (type) {
        ProvenanceType.NPC_OBSERVATION -> KnowledgeChannel141.OBSERVATION
        ProvenanceType.NPC_REPORT -> KnowledgeChannel141.REPORT
        ProvenanceType.NPC_RESEARCH -> KnowledgeChannel141.RESEARCH
        ProvenanceType.NPC_INFERENCE -> KnowledgeChannel141.INFERENCE
        else -> null
    }

    private fun truthSourceNpc(truth: TruthWrite): EntityUid? =
        truth.sourceId?.takeIf { it.startsWith("NPC-") }?.let(::EntityUid)

'''
if s.count(marker) != 1:
    raise SystemExit(f'persistence helper marker mismatch: {s.count(marker)}')
s = s.replace(marker, helpers + marker, 1)
persist.write_text(s)
