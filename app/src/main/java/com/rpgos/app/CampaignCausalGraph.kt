package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

const val PHASE31_CAUSAL_SCHEMA_VERSION = 2

enum class CausalRelationClass { CAUSAL, PROVENANCE, EVIDENCE, TEMPORAL, NARRATIVE, DERIVED, RETRIEVAL }

object CausalRelationKinds {
    const val CAUSES = "RPGOS-REL:CAUSES"
    const val ENABLES = "RPGOS-REL:ENABLES"
    const val PREVENTS = "RPGOS-REL:PREVENTS"
    const val TRIGGERED_BY = "RPGOS-REL:TRIGGERED_BY"
    const val PROVENANCE_OF = "RPGOS-REL:PROVENANCE_OF"
    const val EVIDENCE_FOR = "RPGOS-REL:EVIDENCE_FOR"
    const val BEFORE = "RPGOS-REL:BEFORE"
    const val NARRATIVE_ASSOCIATION = "RPGOS-REL:NARRATIVE_ASSOCIATION"
    const val DERIVED_FROM = "RPGOS-REL:DERIVED_FROM"
    const val SUPERSEDES = "RPGOS-REL:SUPERSEDES"
    const val RETRIEVED_WITH = "RPGOS-REL:RETRIEVED_WITH"

    private val acyclicDependencyKinds = setOf(CAUSES, ENABLES, PREVENTS, TRIGGERED_BY, DERIVED_FROM, SUPERSEDES)

    fun expectedClass(kindUid: String): CausalRelationClass = when (kindUid) {
        CAUSES, ENABLES, PREVENTS, TRIGGERED_BY -> CausalRelationClass.CAUSAL
        PROVENANCE_OF -> CausalRelationClass.PROVENANCE
        EVIDENCE_FOR -> CausalRelationClass.EVIDENCE
        BEFORE -> CausalRelationClass.TEMPORAL
        NARRATIVE_ASSOCIATION -> CausalRelationClass.NARRATIVE
        DERIVED_FROM, SUPERSEDES -> CausalRelationClass.DERIVED
        RETRIEVED_WITH -> CausalRelationClass.RETRIEVAL
        else -> throw CausalGraphIntegrityException("UNKNOWN_RELATION_KIND")
    }

    fun isAcyclicDependency(kindUid: String): Boolean = kindUid in acyclicDependencyKinds
}

class CausalGraphIntegrityException(code: String) : IllegalStateException("RPGOS-CAUSAL-GRAPH:$code")
class CausalGraphIdentityConflictException : IllegalStateException("RPGOS-CAUSAL-GRAPH:IDENTITY_CONFLICT")

data class CanonicalCausalRelationIntent(
    val relationIntentUid: String,
    val relationClass: CausalRelationClass,
    val relationKindUid: String,
    val sourceEventUid: String,
    val targetEventUid: String,
    val evidenceEventUids: List<String> = emptyList(),
    val provenanceEventUids: List<String> = emptyList(),
    val supersedesRelationUid: String? = null
) {
    init {
        require(relationIntentUid.isNotBlank())
        require(sourceEventUid.isNotBlank())
        require(targetEventUid.isNotBlank())
        require(relationKindUid.isNotBlank())
        require(evidenceEventUids.none { it.isBlank() })
        require(provenanceEventUids.none { it.isBlank() })
        require(supersedesRelationUid?.isNotBlank() != false)
    }
}

data class CanonicalCausalRelationRecord(
    val campaignUid: String,
    val relationUid: String,
    val transactionUid: String,
    val relationIntentUid: String,
    val relationClass: CausalRelationClass,
    val relationKindUid: String,
    val sourceEventUid: String,
    val targetEventUid: String,
    val supersedesRelationUid: String?,
    val committedOrder: Long?,
    val relationOrdinal: Int?,
    val semanticFingerprint: String
)

data class CanonicalCausalEndpointRef(val eventUid:String,val subject:DomainRef){
    init{require(eventUid.isNotBlank()&&subject.kindUid.isNotBlank()&&subject.uid.isNotBlank())}
}

data class CanonicalCausalTraversalRecord(
    val relationUid:String,
    val relationClass:CausalRelationClass,
    val relationKindUid:String,
    val source:CanonicalCausalEndpointRef,
    val target:CanonicalCausalEndpointRef,
    val committedOrder:Long?,
    val semanticFingerprint:String
)

data class CanonicalCausalTraversalQuery(
    val startEventUid:String,
    val directionUid:String,
    val relationKinds:Set<String> = emptySet(),
    val maxDepth:Int=3,
    val maxEdges:Int=100
){
    init{
        require(startEventUid.isNotBlank())
        require(directionUid in setOf("OUTGOING","INCOMING","BOTH"))
        require(maxDepth in 1..8&&maxEdges in 1..200)
    }
}

internal object CampaignCausalGraphSchema {
    const val TABLE = "canonical_causal_relations"

    fun ensureReady(db: SQLiteDatabase) {
        migrateIfNeeded(db)
        installTriggers(db)
    }

    private fun migrateIfNeeded(db: SQLiteDatabase) {
        if (!tableExists(db, TABLE)) {
            createCurrentTable(db, TABLE)
            createIndexes(db)
            return
        }
        if (hasColumn(db, TABLE, "relation_ordinal") && !hasLegacyUniqueCommittedOrder(db)) {
            createIndexes(db)
            return
        }

        listOf("rpgos_causal_graph_no_update", "rpgos_causal_graph_no_delete", "rpgos_causal_graph_turn_insert").forEach {
            db.execSQL("DROP TRIGGER IF EXISTS $it")
        }
        db.execSQL("DROP TABLE IF EXISTS canonical_causal_relations_v2_new")
        createCurrentTable(db, "canonical_causal_relations_v2_new")
        db.execSQL(
            """INSERT INTO canonical_causal_relations_v2_new(
                campaign_uid,relation_uid,transaction_uid,turn_uid,command_uid,relation_intent_uid,relation_class_uid,relation_kind_uid,
                source_event_uid,target_event_uid,evidence_event_uids_canonical,provenance_event_uids_canonical,supersedes_relation_uid,
                committed_order,relation_ordinal,semantic_fingerprint,schema_version)
            SELECT c.campaign_uid,c.relation_uid,c.transaction_uid,c.turn_uid,c.command_uid,c.relation_intent_uid,c.relation_class_uid,c.relation_kind_uid,
                c.source_event_uid,c.target_event_uid,c.evidence_event_uids_canonical,c.provenance_event_uids_canonical,c.supersedes_relation_uid,
                CASE WHEN r.commit_order IS NOT NULL THEN r.commit_order ELSE NULL END,
                CASE WHEN r.commit_order IS NOT NULL THEN (
                    SELECT COUNT(*) FROM $TABLE c2
                    WHERE c2.campaign_uid=c.campaign_uid AND c2.transaction_uid=c.transaction_uid
                      AND c2.relation_intent_uid < c.relation_intent_uid
                ) ELSE NULL END,
                c.semantic_fingerprint,c.schema_version
            FROM $TABLE c
            LEFT JOIN turn_transaction_receipts r
              ON r.campaign_uid=c.campaign_uid AND r.transaction_uid=c.transaction_uid AND r.commit_state='COMMITTED'""".trimIndent()
        )
        db.execSQL("DROP TABLE $TABLE")
        db.execSQL("ALTER TABLE canonical_causal_relations_v2_new RENAME TO $TABLE")
        createIndexes(db)
    }

    private fun createCurrentTable(db: SQLiteDatabase, table: String) {
        db.execSQL("""CREATE TABLE $table(
            campaign_uid TEXT NOT NULL,
            relation_uid TEXT NOT NULL,
            transaction_uid TEXT NOT NULL,
            turn_uid TEXT NOT NULL,
            command_uid TEXT NOT NULL,
            relation_intent_uid TEXT NOT NULL,
            relation_class_uid TEXT NOT NULL,
            relation_kind_uid TEXT NOT NULL,
            source_event_uid TEXT NOT NULL,
            target_event_uid TEXT NOT NULL,
            evidence_event_uids_canonical TEXT NOT NULL,
            provenance_event_uids_canonical TEXT NOT NULL,
            supersedes_relation_uid TEXT,
            committed_order INTEGER NULL CHECK(committed_order IS NULL OR committed_order > 0),
            relation_ordinal INTEGER NULL CHECK(relation_ordinal IS NULL OR relation_ordinal >= 0),
            semantic_fingerprint TEXT NOT NULL,
            schema_version INTEGER NOT NULL,
            PRIMARY KEY(campaign_uid,relation_uid),
            UNIQUE(campaign_uid,transaction_uid,relation_intent_uid),
            UNIQUE(campaign_uid,transaction_uid,relation_ordinal),
            CHECK((committed_order IS NULL AND relation_ordinal IS NULL) OR (committed_order IS NOT NULL AND relation_ordinal IS NOT NULL))
        )""".trimIndent())
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_causal_graph_tx ON $TABLE(campaign_uid,transaction_uid,relation_ordinal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_causal_graph_order ON $TABLE(campaign_uid,committed_order,relation_ordinal) WHERE committed_order IS NOT NULL")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_causal_graph_dependency ON $TABLE(campaign_uid,relation_kind_uid,source_event_uid,target_event_uid)")
    }

    private fun installTriggers(db: SQLiteDatabase) {
        listOf("rpgos_causal_graph_no_update", "rpgos_causal_graph_no_delete", "rpgos_causal_graph_turn_insert").forEach {
            db.execSQL("DROP TRIGGER IF EXISTS $it")
        }
        db.execSQL("""CREATE TRIGGER rpgos_causal_graph_no_update
            BEFORE UPDATE ON $TABLE BEGIN SELECT RAISE(ABORT,'RPGOS-CAUSAL-GRAPH:APPEND_ONLY'); END""".trimIndent())
        db.execSQL("""CREATE TRIGGER rpgos_causal_graph_no_delete
            BEFORE DELETE ON $TABLE BEGIN SELECT RAISE(ABORT,'RPGOS-CAUSAL-GRAPH:APPEND_ONLY'); END""".trimIndent())
        db.execSQL("""CREATE TRIGGER rpgos_causal_graph_turn_insert
            BEFORE INSERT ON $TABLE
            WHEN NOT EXISTS(SELECT 1 FROM ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}
                WHERE campaign_uid=NEW.campaign_uid AND capability_kind='TURN')
              OR NOT EXISTS(SELECT 1 FROM ${CampaignIntelligencePhase30Schema.WRITER_CONTEXT_TABLE}
                WHERE campaign_uid=NEW.campaign_uid AND writer_contract_version >= $PHASE30_WRITER_CONTRACT_VERSION)
            BEGIN SELECT RAISE(ABORT,'RPGOS-CAUSAL-GRAPH:CANONICAL_TURN_REQUIRED'); END""".trimIndent())
    }

    fun isReady(db: SQLiteDatabase): Boolean = tableExists(db, TABLE) && hasColumn(db, TABLE, "relation_ordinal")

    private fun hasLegacyUniqueCommittedOrder(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(TABLE)
    ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) false
        else c.getString(0).replace(" ", "").lowercase().contains("unique(campaign_uid,committed_order)")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        val idx = c.getColumnIndex("name")
        while (c.moveToNext()) if (idx >= 0 && c.getString(idx) == column) return@use true
        false
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }
}

internal class CampaignCausalGraph(private val db: SQLiteDatabase, private val campaignUid: String) {
    init { require(campaignUid.isNotBlank()) }

    fun validate(intents: List<CanonicalCausalRelationIntent>) {
        val ids = intents.map { it.relationIntentUid }
        if (ids.distinct().size != ids.size) throw CausalGraphIntegrityException("DUPLICATE_RELATION_INTENT")
        intents.forEach { intent ->
            if (CausalRelationKinds.expectedClass(intent.relationKindUid) != intent.relationClass) {
                throw CausalGraphIntegrityException("RELATION_CLASS_KIND_MISMATCH")
            }
            if (CausalRelationKinds.isAcyclicDependency(intent.relationKindUid) && intent.sourceEventUid == intent.targetEventUid) {
                throw CausalGraphIntegrityException("SELF_EDGE_FORBIDDEN")
            }
            if (intent.relationClass == CausalRelationClass.CAUSAL) validateStrongCausalProofBinding(intent)
        }
    }

    private fun validateStrongCausalProofBinding(intent: CanonicalCausalRelationIntent) {
        val proof = (intent.evidenceEventUids + intent.provenanceEventUids).distinct()
        if (proof.isEmpty()) throw CausalGraphIntegrityException("CAUSAL_RELATION_REQUIRES_EXPLICIT_EVIDENCE_OR_PROVENANCE")
        val endpoints = setOf(intent.sourceEventUid, intent.targetEventUid)
        if (proof.any { it !in endpoints }) {
            throw CausalGraphIntegrityException("CAUSAL_PROOF_NOT_BOUND_TO_RELATION_ENDPOINT")
        }
    }

    fun appendRequired(identity: TurnTransactionIdentity, intents: List<CanonicalCausalRelationIntent>, commitOrder: Long) {
        check(db.inTransaction()) { "RPGOS-CAUSAL-GRAPH:OUTSIDE_TURN_TRANSACTION" }
        require(identity.campaignUid == campaignUid) { "RPGOS-CAUSAL-GRAPH:CROSS_CAMPAIGN" }
        require(commitOrder > 0L) { "RPGOS-CAUSAL-GRAPH:INVALID_COMMIT_ORDER" }
        validate(intents)
        validateEndpointsAndSupersession(intents)
        validateAcyclicAgainstCommittedGraph(intents)

        intents.sortedBy { it.relationIntentUid }.forEachIndexed { ordinal, intent ->
            val planned = planned(identity, intent, ordinal)
            find(identity.transactionUid, intent.relationIntentUid)?.let { existing ->
                if (existing.relationUid != planned.first || existing.semanticFingerprint != planned.second ||
                    existing.committedOrder != commitOrder || existing.relationOrdinal != ordinal) {
                    throw CausalGraphIdentityConflictException()
                }
                return@forEachIndexed
            }
            val values = ContentValues().apply {
                put("campaign_uid", campaignUid); put("relation_uid", planned.first)
                put("transaction_uid", identity.transactionUid); put("turn_uid", identity.turnUid); put("command_uid", identity.commandUid)
                put("relation_intent_uid", intent.relationIntentUid); put("relation_class_uid", intent.relationClass.name); put("relation_kind_uid", intent.relationKindUid)
                put("source_event_uid", intent.sourceEventUid); put("target_event_uid", intent.targetEventUid)
                put("evidence_event_uids_canonical", encodeStrings(intent.evidenceEventUids)); put("provenance_event_uids_canonical", encodeStrings(intent.provenanceEventUids))
                if (intent.supersedesRelationUid == null) putNull("supersedes_relation_uid") else put("supersedes_relation_uid", intent.supersedesRelationUid)
                put("committed_order", commitOrder); put("relation_ordinal", ordinal)
                put("semantic_fingerprint", planned.second); put("schema_version", PHASE31_CAUSAL_SCHEMA_VERSION)
            }
            if (db.insert(CampaignCausalGraphSchema.TABLE, null, values) == -1L) throw CausalGraphIntegrityException("APPEND_FAILED")
        }
    }

    @Deprecated("Phase32 post-audit: Causal append must receive the single Phase29 commitOrder reserved by TurnTransaction")
    fun appendRequired(identity: TurnTransactionIdentity, intents: List<CanonicalCausalRelationIntent>): Nothing =
        throw CausalGraphIntegrityException("COMMIT_ORDER_REQUIRED")

    fun assertCommittedSetMatches(
        identity: TurnTransactionIdentity,
        intents: List<CanonicalCausalRelationIntent>,
        receipt: TurnCommitReceipt? = null
    ) {
        validate(intents)
        val rows = relationsForTransaction(identity.transactionUid)
        val expected = intents.sortedBy { it.relationIntentUid }.mapIndexed { ordinal, intent -> planned(identity, intent, ordinal) }
        if (rows.size != expected.size) throw CausalGraphIntegrityException("COMMITTED_SET_MISSING_OR_EXTRA")
        expected.forEachIndexed { i, p ->
            val row = rows[i]
            if (row.relationUid != p.first || row.semanticFingerprint != p.second) throw CausalGraphIdentityConflictException()
            if (receipt != null && receipt.receiptVersion >= 3 && (row.committedOrder != receipt.commitOrder || row.relationOrdinal != i)) {
                throw CausalGraphIntegrityException("ORDER_BINDING_MISMATCH")
            }
        }
    }

    fun relationsForTransaction(transactionUid: String): List<CanonicalCausalRelationRecord> {
        if (!tableExists()) return emptyList()
        return db.rawQuery(
            """SELECT relation_uid,transaction_uid,relation_intent_uid,relation_class_uid,relation_kind_uid,
                source_event_uid,target_event_uid,supersedes_relation_uid,committed_order,relation_ordinal,semantic_fingerprint
                FROM ${CampaignCausalGraphSchema.TABLE}
                WHERE campaign_uid=? AND transaction_uid=? ORDER BY relation_ordinal,relation_intent_uid""".trimIndent(),
            arrayOf(campaignUid, transactionUid)
        ).use { c ->
            val rows = mutableListOf<CanonicalCausalRelationRecord>()
            while (c.moveToNext()) {
                rows += CanonicalCausalRelationRecord(
                    campaignUid,
                    c.getString(0),
                    c.getString(1),
                    c.getString(2),
                    CausalRelationClass.valueOf(c.getString(3)),
                    c.getString(4),
                    c.getString(5),
                    c.getString(6),
                    if (c.isNull(7)) null else c.getString(7),
                    if (c.isNull(8)) null else c.getLong(8),
                    if (c.isNull(9)) null else c.getInt(9),
                    c.getString(10)
                )
            }
            rows
        }
    }

    /** Canonical bounded read port for Phase42. Traversal semantics remain owned by Phase31. */
    fun traverseBounded(query:CanonicalCausalTraversalQuery):List<CanonicalCausalTraversalRecord>{
        if(!tableExists())return emptyList()
        val frontier=ArrayDeque<Pair<String,Int>>()
        frontier.add(query.startEventUid to 0)
        val visited=linkedSetOf(query.startEventUid)
        val seenRelations=linkedSetOf<String>()
        val out=mutableListOf<CanonicalCausalTraversalRecord>()
        while(frontier.isNotEmpty()&&out.size<query.maxEdges){
            val(node,depth)=frontier.removeFirst()
            if(depth>=query.maxDepth)continue
            val where=when(query.directionUid){
                "OUTGOING"->"source_event_uid=?"
                "INCOMING"->"target_event_uid=?"
                else->"(source_event_uid=? OR target_event_uid=?)"
            }
            val args=if(query.directionUid=="BOTH")arrayOf(campaignUid,node,node)else arrayOf(campaignUid,node)
            db.rawQuery(
                "SELECT relation_uid,relation_class_uid,relation_kind_uid,source_event_uid,target_event_uid,committed_order,semantic_fingerprint FROM ${CampaignCausalGraphSchema.TABLE} WHERE campaign_uid=? AND $where ORDER BY committed_order,relation_ordinal,relation_uid",
                args
            ).use{c->
                while(c.moveToNext()&&out.size<query.maxEdges){
                    val kind=c.getString(2)
                    if(query.relationKinds.isNotEmpty()&&kind !in query.relationKinds)continue
                    val relationUid=c.getString(0)
                    if(!seenRelations.add(relationUid))continue
                    val sourceUid=c.getString(3)
                    val targetUid=c.getString(4)
                    out+=CanonicalCausalTraversalRecord(
                        relationUid,
                        CausalRelationClass.valueOf(c.getString(1)),
                        kind,
                        eventEndpoint(sourceUid),
                        eventEndpoint(targetUid),
                        if(c.isNull(5))null else c.getLong(5),
                        c.getString(6)
                    )
                    val next=when(query.directionUid){
                        "OUTGOING"->targetUid
                        "INCOMING"->sourceUid
                        else->if(node==sourceUid)targetUid else sourceUid
                    }
                    if(visited.add(next))frontier.add(next to depth+1)
                }
            }
        }
        return out
    }

    fun relationUid(identity: TurnTransactionIdentity, relationIntentUid: String): String =
        "RPGOS-RELATION:" + sha256("$campaignUid|${identity.transactionUid}|${identity.commandUid}|$relationIntentUid")

    fun planFingerprint(identity: TurnTransactionIdentity, intents: List<CanonicalCausalRelationIntent>): String {
        if (intents.isEmpty()) return ""
        validate(intents)
        return sha256(intents.sortedBy { it.relationIntentUid }.mapIndexed { ordinal, intent -> planned(identity, intent, ordinal).second }.joinToString("\u001f"))
    }

    private fun planned(identity: TurnTransactionIdentity, intent: CanonicalCausalRelationIntent, ordinal: Int): Pair<String,String> {
        val semantic = listOf(
            "v=$PHASE31_CAUSAL_SCHEMA_VERSION","campaign=$campaignUid","tx=${identity.transactionUid}","turn=${identity.turnUid}","command=${identity.commandUid}",
            "intent=${intent.relationIntentUid}","ordinal=$ordinal","class=${intent.relationClass.name}","kind=${intent.relationKindUid}",
            "source=${intent.sourceEventUid}","target=${intent.targetEventUid}","evidence=${encodeStrings(intent.evidenceEventUids)}",
            "provenance=${encodeStrings(intent.provenanceEventUids)}","supersedes=${intent.supersedesRelationUid ?: "UNKNOWN"}"
        ).joinToString("|")
        return relationUid(identity,intent.relationIntentUid) to sha256(semantic)
    }

    private fun validateEndpointsAndSupersession(intents: List<CanonicalCausalRelationIntent>) {
        intents.forEach { intent ->
            validateEventEndpoint(intent.sourceEventUid)
            validateEventEndpoint(intent.targetEventUid)
            intent.evidenceEventUids.forEach(::validateEventEndpoint)
            intent.provenanceEventUids.forEach(::validateEventEndpoint)
            intent.supersedesRelationUid?.let { previous ->
                if (!relationExists(previous)) throw CausalGraphIntegrityException("DANGLING_SUPERSESSION")
            }
        }
    }

    /** Bounded dependency-DAG validation only; this is not retrieval/path-search functionality. */
    private fun validateAcyclicAgainstCommittedGraph(intents: List<CanonicalCausalRelationIntent>) {
        val adjacency = linkedMapOf<String, MutableSet<String>>()
        db.rawQuery(
            "SELECT relation_kind_uid,source_event_uid,target_event_uid FROM ${CampaignCausalGraphSchema.TABLE} WHERE campaign_uid=?",
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                val kind = c.getString(0)
                if (CausalRelationKinds.isAcyclicDependency(kind)) {
                    adjacency.getOrPut(c.getString(1)) { linkedSetOf() }.add(c.getString(2))
                }
            }
        }
        intents.filter { CausalRelationKinds.isAcyclicDependency(it.relationKindUid) }.forEach { intent ->
            adjacency.getOrPut(intent.sourceEventUid) { linkedSetOf() }.add(intent.targetEventUid)
        }

        val visiting = hashSetOf<String>()
        val visited = hashSetOf<String>()
        fun visit(node: String): Boolean {
            if (node in visiting) return true
            if (!visited.add(node)) return false
            visiting += node
            for (next in adjacency[node].orEmpty()) if (visit(next)) return true
            visiting -= node
            return false
        }
        val nodes = (adjacency.keys + adjacency.values.flatten()).toSortedSet()
        if (nodes.any(::visit)) throw CausalGraphIntegrityException("DIRECTED_DEPENDENCY_CYCLE")
    }

    private fun eventEndpoint(eventUid:String):CanonicalCausalEndpointRef{
        val subject=db.rawQuery(
            "SELECT subject_ref_kind_uid,subject_ref_uid FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} WHERE campaign_uid=? AND event_uid=? LIMIT 1",
            arrayOf(campaignUid,eventUid)
        ).use{c->if(!c.moveToFirst())null else DomainRef(c.getString(0),c.getString(1))}
            ?:throw CausalGraphIntegrityException("DANGLING_EVENT_ENDPOINT")
        return CanonicalCausalEndpointRef(eventUid,subject)
    }

    private fun validateEventEndpoint(eventUid: String) {
        val campaign = db.rawQuery(
            "SELECT campaign_uid FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} WHERE event_uid=? LIMIT 1",
            arrayOf(eventUid)
        ).use { c -> if(c.moveToFirst()) c.getString(0) else null }
        if (campaign == null) throw CausalGraphIntegrityException("DANGLING_EVENT_ENDPOINT")
        if (campaign != campaignUid) throw CausalGraphIntegrityException("CROSS_CAMPAIGN_EVENT_ENDPOINT")
    }

    private fun relationExists(uid: String): Boolean = db.rawQuery(
        "SELECT 1 FROM ${CampaignCausalGraphSchema.TABLE} WHERE campaign_uid=? AND relation_uid=? LIMIT 1",
        arrayOf(campaignUid,uid)
    ).use { it.moveToFirst() }

    private fun find(tx: String, intent: String): CanonicalCausalRelationRecord? = relationsForTransaction(tx).singleOrNull { it.relationIntentUid == intent }
    private fun encodeStrings(values: List<String>) = values.sorted().joinToString(";") { "${it.length}:$it" }
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun tableExists(): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
        arrayOf(CampaignCausalGraphSchema.TABLE)
    ).use { cursor -> cursor.moveToFirst() }
}
