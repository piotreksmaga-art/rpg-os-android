package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class TemporalResultState { VALUE, NO_DATA, DENIED, NOT_DISCLOSED, UNKNOWN, UNSUPPORTED, CORRUPTION }

data class TemporalQuery(
    val campaignUid: String,
    val sourceUid: String,
    val subjectKindUid: String,
    val subjectUid: String,
    val atOrder: Long,
    val audience: AudienceContext,
    val purpose: PurposeContext,
    val limit: Int = 100
) {
    init {
        require(campaignUid.isNotBlank() && sourceUid.isNotBlank() && subjectKindUid.isNotBlank() && subjectUid.isNotBlank())
        require(atOrder >= 0L && limit in 1..500)
        require(audience.campaignUid == campaignUid && purpose.campaignUid == campaignUid) { "RPGOS-P39:CROSS_CAMPAIGN_QUERY" }
    }
}

data class TemporalRecord(
    val recordUid: String,
    val validFromOrder: Long,
    val validUntilOrder: Long? = null,
    val values: Map<String, Any?>,
    val provenanceUid: String? = null
) {
    init {
        require(recordUid.isNotBlank() && validFromOrder >= 0L)
        require(validUntilOrder == null || validUntilOrder >= validFromOrder)
    }
    fun validAt(order: Long) = order >= validFromOrder && (validUntilOrder == null || order < validUntilOrder)
}

sealed interface TemporalResult {
    val state: TemporalResultState
    data class Value(val records: List<TemporalRecord>, val complete: Boolean = true) : TemporalResult { override val state = TemporalResultState.VALUE }
    data object NoData : TemporalResult { override val state = TemporalResultState.NO_DATA }
    data class Denied(val reasonUid: String) : TemporalResult { override val state = TemporalResultState.DENIED }
    data class NotDisclosed(val reasonUid: String) : TemporalResult { override val state = TemporalResultState.NOT_DISCLOSED }
    data class Unknown(val reasonUid: String) : TemporalResult { override val state = TemporalResultState.UNKNOWN }
    data class Unsupported(val reasonUid: String) : TemporalResult { override val state = TemporalResultState.UNSUPPORTED }
    data class Corruption(val reasonUid: String) : TemporalResult { override val state = TemporalResultState.CORRUPTION }
}

fun interface TemporalSource { fun read(query: TemporalQuery): TemporalResult }
data class TemporalSourceBinding(val sourceUid: String, val source: TemporalSource) { init { require(sourceUid.isNotBlank()) } }

class TemporalEngine(bindings: List<TemporalSourceBinding>) {
    private val sources = bindings.associateBy { it.sourceUid }
    init { require(sources.size == bindings.size) { "RPGOS-P39:DUPLICATE_SOURCE" } }
    fun query(query: TemporalQuery): TemporalResult {
        val source = sources[query.sourceUid]?.source ?: return TemporalResult.Unsupported("SOURCE_NOT_REGISTERED")
        return try {
            when (val result = source.read(query)) {
                is TemporalResult.Value -> if (result.records.any { !it.validAt(query.atOrder) }) TemporalResult.Corruption("SOURCE_RETURNED_OUT_OF_RANGE_RECORD")
                else if (result.records.isEmpty()) TemporalResult.NoData
                else result.copy(records = result.records.sortedWith(compareBy<TemporalRecord> { it.validFromOrder }.thenBy { it.recordUid }).take(query.limit))
                else -> result
            }
        } catch (_: VisibilityAuthorityFailure.CrossCampaign) { throw VisibilityAuthorityFailure.CrossCampaign() }
        catch (_: Throwable) { TemporalResult.Corruption("TEMPORAL_SOURCE_FAILURE") }
    }
}

/** Phase-38-owned source port. It translates protected projection only; Phase 39 owns at-order semantics. */
class AccessAuthorityTemporalSource(private val protectedReads: ProtectedCampaignReadRepository) : TemporalSource {
    override fun read(query: TemporalQuery): TemporalResult {
        if (query.subjectKindUid != "VISIBILITY_PRINCIPAL") return TemporalResult.Unsupported("SUBJECT_KIND_UNSUPPORTED")
        val split = query.subjectUid.split(':', limit = 2)
        if (split.size != 2 || split.any { it.isBlank() }) return TemporalResult.Unknown("MALFORMED_PRINCIPAL")
        val target=VisibilityPrincipalRef(split[0],split[1])
        return when(val result=protectedReads.accessAuthorityHistory(query.audience,query.purpose,target,query.atOrder)){
            is ProtectedReadResult.Allow -> TemporalResult.Value(result.value.map { record ->
                val values=linkedMapOf<String,Any?>(
                    "operation" to record.operation.name,
                    "kind_uid" to record.kindUid,
                    "value_uid" to record.valueUid
                )
                if(result.reasonCode=="OWN_ACCESS_AUTHORITY_HISTORY"){
                    values["subject_scoped"]=record.subjectUid!=null
                }else{
                    values["subject_kind_uid"]=record.subjectKindUid
                    values["subject_uid"]=record.subjectUid
                }
                TemporalRecord(record.recordUid,record.validFromOrder,record.validUntilOrder,values,"ACCESS_AUTHORITY:${record.recordUid}:${record.createdOrder}")
            })
            ProtectedReadResult.NoData -> TemporalResult.NoData
            is ProtectedReadResult.Deny -> TemporalResult.Denied(result.reasonCode)
            is ProtectedReadResult.NotDisclosed -> TemporalResult.NotDisclosed(result.reasonCode)
            is ProtectedReadResult.Unknown -> TemporalResult.Unknown(result.reasonCode)
            is ProtectedReadResult.Corruption -> TemporalResult.Corruption(result.reasonCode)
        }
    }
}

enum class ScheduledEvaluationState { PENDING, CLAIMED, CANCELLED, PROCESSED }
data class ScheduledEvaluation(
    val campaignUid:String,val evaluationUid:String,val evaluatorKindUid:String,val subjectKindUid:String,val subjectUid:String,
    val dueOrder:Long,val createdOrder:Long,val provenanceUid:String,val payloadCanonical:String=""
){init{require(campaignUid.isNotBlank()&&evaluationUid.isNotBlank()&&evaluatorKindUid.isNotBlank());require(subjectKindUid.isNotBlank()&&subjectUid.isNotBlank()&&provenanceUid.isNotBlank());require(createdOrder>=0L&&dueOrder>=createdOrder)}}
data class DueEvaluation(val evaluation:ScheduledEvaluation,val observedAtOrder:Long){init{require(observedAtOrder>=evaluation.dueOrder)}}

internal object Phase40SchedulerSchema {
    const val DEFINITIONS="phase40_scheduled_evaluations";const val TRANSITIONS="phase40_scheduled_evaluation_transitions"
    fun ensureReady(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE IF NOT EXISTS $DEFINITIONS(campaign_uid TEXT NOT NULL,evaluation_uid TEXT NOT NULL,evaluator_kind_uid TEXT NOT NULL,subject_kind_uid TEXT NOT NULL,subject_uid TEXT NOT NULL,due_order INTEGER NOT NULL,created_order INTEGER NOT NULL,provenance_uid TEXT NOT NULL,payload_canonical TEXT NOT NULL,PRIMARY KEY(campaign_uid,evaluation_uid),CHECK(due_order>=created_order))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TRANSITIONS(campaign_uid TEXT NOT NULL,transition_uid TEXT NOT NULL,evaluation_uid TEXT NOT NULL,state_uid TEXT NOT NULL,effective_order INTEGER NOT NULL,transaction_uid TEXT NOT NULL,provenance_uid TEXT NOT NULL,PRIMARY KEY(campaign_uid,transition_uid))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p40_due ON $DEFINITIONS(campaign_uid,due_order,evaluation_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p40_transition ON $TRANSITIONS(campaign_uid,evaluation_uid,effective_order,transition_uid)")
    }
}

/** Internal canonical persistence. Mutations require an existing database transaction and Turn identity. */
internal class ScheduledEvaluationStore(private val db:SQLiteDatabase,private val campaignUid:String){
    init{require(campaignUid.isNotBlank());Phase40SchedulerSchema.ensureReady(db)}
    fun schedule(identity:TurnTransactionIdentity,evaluation:ScheduledEvaluation){
        require(db.inTransaction()){"RPGOS-P40:TURN_TRANSACTION_REQUIRED"};require(identity.campaignUid==campaignUid&&evaluation.campaignUid==campaignUid){"RPGOS-P40:CROSS_CAMPAIGN"}
        val existing=definition(evaluation.evaluationUid);if(existing!=null){require(existing==evaluation){"RPGOS-P40:EVALUATION_IDENTITY_CONFLICT"};return}
        db.execSQL("INSERT INTO ${Phase40SchedulerSchema.DEFINITIONS} VALUES(?,?,?,?,?,?,?,?,?)",arrayOf(campaignUid,evaluation.evaluationUid,evaluation.evaluatorKindUid,evaluation.subjectKindUid,evaluation.subjectUid,evaluation.dueOrder,evaluation.createdOrder,evaluation.provenanceUid,evaluation.payloadCanonical))
    }
    fun transition(identity:TurnTransactionIdentity,transitionUid:String,evaluationUid:String,state:ScheduledEvaluationState,effectiveOrder:Long,provenanceUid:String){
        require(db.inTransaction()){"RPGOS-P40:TURN_TRANSACTION_REQUIRED"};require(identity.campaignUid==campaignUid&&transitionUid.isNotBlank()&&provenanceUid.isNotBlank()){"RPGOS-P40:INVALID_TRANSITION"};val scheduled=requireNotNull(definition(evaluationUid)){"RPGOS-P40:UNKNOWN_EVALUATION"};require(effectiveOrder>=scheduled.createdOrder){"RPGOS-P40:TRANSITION_BEFORE_CREATION"}
        val existing=db.rawQuery("SELECT evaluation_uid,state_uid,effective_order,transaction_uid,provenance_uid FROM ${Phase40SchedulerSchema.TRANSITIONS} WHERE campaign_uid=? AND transition_uid=?",arrayOf(campaignUid,transitionUid)).use{c->if(!c.moveToFirst())null else listOf(c.getString(0),c.getString(1),c.getLong(2).toString(),c.getString(3),c.getString(4))}
        val expected=listOf(evaluationUid,state.name,effectiveOrder.toString(),identity.transactionUid,provenanceUid);if(existing!=null){require(existing==expected){"RPGOS-P40:TRANSITION_IDENTITY_CONFLICT"};return}
        db.execSQL("INSERT INTO ${Phase40SchedulerSchema.TRANSITIONS} VALUES(?,?,?,?,?,?,?)",arrayOf(campaignUid,transitionUid,evaluationUid,state.name,effectiveOrder,identity.transactionUid,provenanceUid))
    }
    fun due(atOrder:Long,limit:Int=100):List<DueEvaluation>{require(atOrder>=0L&&limit in 1..500);return db.rawQuery("""SELECT d.evaluation_uid,d.evaluator_kind_uid,d.subject_kind_uid,d.subject_uid,d.due_order,d.created_order,d.provenance_uid,d.payload_canonical,(SELECT t.state_uid FROM ${Phase40SchedulerSchema.TRANSITIONS} t WHERE t.campaign_uid=d.campaign_uid AND t.evaluation_uid=d.evaluation_uid AND t.effective_order<=? ORDER BY t.effective_order DESC,t.transition_uid DESC LIMIT 1) FROM ${Phase40SchedulerSchema.DEFINITIONS} d WHERE d.campaign_uid=? AND d.due_order<=? ORDER BY d.due_order,d.evaluation_uid LIMIT $limit""",arrayOf(atOrder.toString(),campaignUid,atOrder.toString())).use{c->buildList{while(c.moveToNext()){val state=if(c.isNull(8))ScheduledEvaluationState.PENDING else ScheduledEvaluationState.valueOf(c.getString(8));if(state==ScheduledEvaluationState.PENDING)add(DueEvaluation(ScheduledEvaluation(campaignUid,c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),c.getString(6),c.getString(7)),atOrder))}}}}
    private fun definition(uid:String):ScheduledEvaluation?=db.rawQuery("SELECT evaluator_kind_uid,subject_kind_uid,subject_uid,due_order,created_order,provenance_uid,payload_canonical FROM ${Phase40SchedulerSchema.DEFINITIONS} WHERE campaign_uid=? AND evaluation_uid=?",arrayOf(campaignUid,uid)).use{c->if(!c.moveToFirst())null else ScheduledEvaluation(campaignUid,uid,c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getString(5),c.getString(6))}
}

class Phase40Scheduler(private val campaignUid:String,private val dueSource:(Long,Int)->List<DueEvaluation>){
    fun due(atOrder:Long,limit:Int=100):List<DueEvaluation>{require(atOrder>=0L&&limit in 1..500);return dueSource(atOrder,limit).also{rows->require(rows.all{it.observedAtOrder==atOrder&&it.evaluation.campaignUid==campaignUid})}.sortedWith(compareBy<DueEvaluation>{it.evaluation.dueOrder}.thenBy{it.evaluation.evaluationUid}).take(limit)}
}
