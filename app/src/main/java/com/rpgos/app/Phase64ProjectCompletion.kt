package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

const val PHASE64_PROJECT_COMPLETION_KIND = "RPGOS-CHANGE:PROJECT_COMPLETION_V1"
const val PHASE64_PROJECT_WORK_KIND = "RPGOS-CHANGE:BACKGROUND_PROJECT_WORK_V1"

/** Work uses the existing project ledger and retains the actual worker independently of the
 * active player's command actor. Material and financial costs remain separate owner payloads. */
data class BackgroundProjectWorkChange(
    val campaignUid: String,
    val projectUid: String,
    val worker: DomainRef,
    val operationUid: String,
    val expectedProjectVersion: Int,
    val expectedTypeDefinitionVersion: Int,
    val expectedProgressUnits: Long,
    val progressUnits: Long,
    val labourResourceUid: String,
    val labourUnits: Long,
    val ruleUid: String,
    val ruleVersion: Int,
    val evidenceRefs: List<DomainRef> = emptyList(),
    val readyToComplete: Boolean = false
) : PlayerDomainChangePayload {
    init {
        require(listOf(campaignUid, projectUid, worker.kindUid, worker.uid, operationUid, labourResourceUid, ruleUid)
            .all { it.isNotBlank() && it.length <= 320 })
        require(expectedProjectVersion > 0 && expectedTypeDefinitionVersion > 0 && expectedProgressUnits >= 0 &&
            progressUnits > 0 && labourUnits > 0 && ruleVersion > 0)
        require(operationUid in setOf(Phase64EconomyOperations.OWNED_BUILD_CHECK, Phase64EconomyOperations.OWNED_REPAIR_CHECK,
            Phase64EconomyOperations.OWNED_RESEARCH_CHECK))
        require(evidenceRefs.size <= 64 && evidenceRefs.distinct().size == evidenceRefs.size && evidenceRefs.all(::validRef))
    }
}

internal fun phase64ProjectWorkCodec() = object : TypedPlayerChangeCodec<BackgroundProjectWorkChange>(
    BackgroundProjectWorkChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
    setOf("campaignUid", "projectUid", "worker", "operationUid", "expectedProjectVersion", "expectedTypeDefinitionVersion",
        "expectedProgressUnits", "progressUnits", "labourResourceUid", "labourUnits", "ruleUid", "ruleVersion", "evidenceRefs", "readyToComplete")
) {
    override fun encode(payload: BackgroundProjectWorkChange) = buildJsonObject {
        put("campaignUid", payload.campaignUid); put("projectUid", payload.projectUid)
        put("worker", p64ProjectRef(payload.worker)); put("operationUid", payload.operationUid)
        put("expectedProjectVersion", payload.expectedProjectVersion); put("expectedTypeDefinitionVersion", payload.expectedTypeDefinitionVersion)
        put("expectedProgressUnits", payload.expectedProgressUnits); put("progressUnits", payload.progressUnits)
        put("labourResourceUid", payload.labourResourceUid); put("labourUnits", payload.labourUnits)
        put("ruleUid", payload.ruleUid); put("ruleVersion", payload.ruleVersion)
        put("evidenceRefs", JsonArray(payload.evidenceRefs.map(::p64ProjectRef)))
        put("readyToComplete", payload.readyToComplete)
    }
    override fun decodeKnownFields(obj: JsonObject): BackgroundProjectWorkChange {
        fun text(key: String) = obj.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        fun number(key: String) = obj.getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
        fun integer(key: String) = obj.getValue(key).jsonPrimitive.let { require(!it.isString); it.int }
        return BackgroundProjectWorkChange(text("campaignUid"), text("projectUid"), p64ReadProjectRef(obj.getValue("worker").jsonObject),
            text("operationUid"), integer("expectedProjectVersion"), integer("expectedTypeDefinitionVersion"), number("expectedProgressUnits"),
            number("progressUnits"), text("labourResourceUid"), number("labourUnits"), text("ruleUid"), integer("ruleVersion"),
            obj.getValue("evidenceRefs").jsonArray.map { p64ReadProjectRef(it.jsonObject) },
            obj.getValue("readyToComplete").jsonPrimitive.let { require(!it.isString); it.boolean })
    }
    override fun conflictKeys(payload: BackgroundProjectWorkChange) =
        setOf("P64:PROJECT_WORK:${payload.projectUid}:${payload.expectedProgressUnits}")
}

private fun p64ProjectRef(ref: DomainRef) = buildJsonObject { put("kindUid", ref.kindUid); put("uid", ref.uid) }
private fun p64ReadProjectRef(obj: JsonObject): DomainRef {
    require(obj.keys == setOf("kindUid", "uid"))
    val kind = obj.getValue("kindUid").jsonPrimitive; val uid = obj.getValue("uid").jsonPrimitive
    require(kind.isString && uid.isString)
    return DomainRef(kind.content, uid.content).also { require(validRef(it)) }
}

/** An outcome links an already proved canonical result; it does not grant skill/knowledge,
 * manufacture an asset, or mark an unvalidated project ready. */
data class DevelopmentProjectCompletionChange(
    val campaignUid: String,
    val projectUid: String,
    val expectedProjectVersion: Int,
    val expectedTypeDefinitionVersion: Int,
    val expectedProgressUnits: Long,
    val resultWorkRecordUid: String,
    val resultEventUid: String,
    val outputKindUid: String,
    val outputRef: DomainRef,
    val ruleUid: String,
    val ruleVersion: Int
) : PlayerDomainChangePayload {
    init {
        require(listOf(campaignUid, projectUid, resultWorkRecordUid, resultEventUid, outputKindUid, ruleUid,
            outputRef.kindUid, outputRef.uid).all { it.isNotBlank() && it.length <= 320 })
        require(expectedProjectVersion > 0 && expectedTypeDefinitionVersion > 0 && expectedProgressUnits > 0 && ruleVersion > 0)
        require(outputKindUid in setOf(PROJECT_OUTPUT_ITEM_INSTANCE, PROJECT_OUTPUT_ASSET, PROJECT_OUTPUT_TRUTH,
            PROJECT_OUTPUT_TECHNIQUE, PROJECT_OUTPUT_SKILL))
    }
}

internal fun phase64ProjectCompletionCodec() = object : TypedPlayerChangeCodec<DevelopmentProjectCompletionChange>(
    DevelopmentProjectCompletionChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
    setOf("campaignUid", "projectUid", "expectedProjectVersion", "expectedTypeDefinitionVersion", "expectedProgressUnits",
        "resultWorkRecordUid", "resultEventUid", "outputKindUid", "outputRef", "ruleUid", "ruleVersion")
) {
    override fun encode(payload: DevelopmentProjectCompletionChange) = buildJsonObject {
        put("campaignUid", payload.campaignUid); put("projectUid", payload.projectUid)
        put("expectedProjectVersion", payload.expectedProjectVersion); put("expectedTypeDefinitionVersion", payload.expectedTypeDefinitionVersion)
        put("expectedProgressUnits", payload.expectedProgressUnits); put("resultWorkRecordUid", payload.resultWorkRecordUid)
        put("resultEventUid", payload.resultEventUid); put("outputKindUid", payload.outputKindUid)
        put("outputRef", buildJsonObject { put("kindUid", payload.outputRef.kindUid); put("uid", payload.outputRef.uid) })
        put("ruleUid", payload.ruleUid); put("ruleVersion", payload.ruleVersion)
    }
    override fun decodeKnownFields(obj: JsonObject): DevelopmentProjectCompletionChange {
        fun text(key: String) = obj.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
        fun number(key: String) = obj.getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
        fun integer(key: String) = obj.getValue(key).jsonPrimitive.let { require(!it.isString); it.int }
        val ref = obj.getValue("outputRef").jsonObject
        require(ref.keys == setOf("kindUid", "uid"))
        val kind = ref.getValue("kindUid").jsonPrimitive; val uid = ref.getValue("uid").jsonPrimitive
        require(kind.isString && uid.isString)
        return DevelopmentProjectCompletionChange(text("campaignUid"), text("projectUid"), integer("expectedProjectVersion"),
            integer("expectedTypeDefinitionVersion"), number("expectedProgressUnits"), text("resultWorkRecordUid"), text("resultEventUid"),
            text("outputKindUid"), DomainRef(kind.content, uid.content), text("ruleUid"), integer("ruleVersion"))
    }
    override fun conflictKeys(payload: DevelopmentProjectCompletionChange) = setOf("PROJECT:${payload.projectUid}")
}

data class Phase64ProjectResultEvidence(val work: ProjectWorkRecord, val eventUid: String)

/** Called by captured Core reads after checking existence/access of the requested output. */
internal fun preparePhase64ProjectCompletion(
    parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
    snapshot: Phase64ProjectSnapshot?, result: Phase64ProjectResultEvidence?, outputExists: Boolean
): WorldConsequencePlan {
    val ready = Phase64EconomyOwnerRules.projectCompletionReadiness(parameters, scope, snapshot, result?.work)
    if (ready.status != BackgroundProcessStatus.COMPLETED) return ready
    if (snapshot == null || result == null || !outputExists) return backgroundBlocked("P64:PROJECT_OUTPUT_UNAVAILABLE")
    return try {
        val kind = backgroundRequired(parameters, "outputKindUid")
        val ref = DomainRef(backgroundRequired(parameters, "outputRefKindUid"), backgroundRequired(parameters, "outputUid"))
        if (snapshot.project.intendedOutputKindUid != kind || snapshot.project.targetUid != ref.uid ||
            snapshot.project.targetKindUid != ref.kindUid) return backgroundBlocked("P64:PROJECT_OUTPUT_CONTRACT_CHANGED")
        val change = DevelopmentProjectCompletionChange(scope.temporal.campaignUid, snapshot.project.projectUid,
            snapshot.project.projectVersion, snapshot.type.definitionVersion, snapshot.progress.progressUnits,
            result.work.workRecordUid, result.eventUid, kind, ref, backgroundRequired(parameters, "ruleUid"),
            backgroundRequired(parameters, "ruleVersion").toInt())
        WorldConsequencePlan(changes = listOf(change), sourceUids = (ready.sourceUids + result.eventUid + ref.uid).distinct())
    } catch (_: IllegalArgumentException) {
        backgroundBlocked("P64:PROJECT_OUTPUT_PARAMETERS_INVALID")
    }
}

/** Targeted existing-owner snapshot. No schema migration, bootstrap, global scan or writes. */
internal fun capturePhase64ProjectSnapshot(db: SQLiteDatabase, campaign: String, projectUid: String): Phase64ProjectSnapshot? {
    val store = DevelopmentProjectStore(db, campaign)
    val project = store.project(projectUid) ?: return null
    val type = db.rawQuery("SELECT generic_category_uid,lifecycle_policy_uid,world_pack_uid,definition_status,definition_version,provenance,metadata_json FROM project_type_definitions WHERE project_type_uid=?",
        arrayOf(project.projectTypeUid)).use { c ->
        if (!c.moveToFirst()) return null
        ProjectTypeDefinition(project.projectTypeUid, c.getString(0), c.getString(1), c.p64Optional(2), c.getString(3),
            c.getInt(4), c.getString(5), c.p64Optional(6))
    }
    val requirements = db.rawQuery("SELECT requirement_uid,requirement_type_uid,target_kind_uid,target_uid,comparator_uid,threshold_value,quantity_value,required,required_from_order,requirement_version,provenance,metadata_json FROM project_requirements WHERE campaign_id=? AND project_uid=? ORDER BY requirement_uid",
        arrayOf(campaign, projectUid)).use { c -> buildList {
        while (c.moveToNext()) add(ProjectRequirement(campaign, c.getString(0), projectUid, c.getString(1), c.p64Optional(2),
            c.p64Optional(3), c.p64Optional(4), c.p64LongOrNull(5), c.p64LongOrNull(6), c.getInt(7) == 1, c.getLong(8), c.getInt(9), c.getString(10), c.p64Optional(11)))
    } }
    val satisfactions = db.rawQuery("SELECT satisfaction_uid,requirement_uid,satisfied_order,evidence_kind_uid,evidence_uid,source_event_uid,provenance FROM project_requirement_satisfactions WHERE campaign_id=? AND project_uid=? ORDER BY satisfaction_uid",
        arrayOf(campaign, projectUid)).use { c -> buildList {
        while (c.moveToNext()) add(ProjectRequirementSatisfaction(campaign, c.getString(0), projectUid, c.getString(1),
            c.getLong(2), c.p64Optional(3), c.p64Optional(4), c.p64Optional(5), c.getString(6)))
    } }
    val dependencies = db.rawQuery("SELECT dependency_uid,depends_on_project_uid,dependency_type_uid,milestone_uid,valid_from_order,provenance FROM project_dependencies WHERE campaign_id=? AND project_uid=? ORDER BY dependency_uid",
        arrayOf(campaign, projectUid)).use { c -> buildList {
        while (c.moveToNext()) add(ProjectDependency(campaign, c.getString(0), projectUid, c.getString(1), c.getString(2),
            c.p64Optional(3), c.getLong(4), c.getString(5)))
    } }
    val completed = dependencies.map { it.dependsOnProjectUid }.distinct().filter { uid ->
        store.project(uid) != null && store.currentStatus(uid) == ProjectStatus.COMPLETED
    }.toSet()
    return Phase64ProjectSnapshot(project, type, store.progress(projectUid), requirements, satisfactions, dependencies, completed)
}

/** Bind the exact work record to its canonical committed event, including records whose old
 * applier omitted sourceEventUid. A matching command alone is not causal proof. */
internal fun capturePhase64ProjectResult(db: SQLiteDatabase, campaign: String, projectUid: String,
    workRecordUid: String, identity: TurnTransactionIdentity? = null,
    changeSet: PlayerChangeSet? = null): Phase64ProjectResultEvidence? {
    val work = db.rawQuery("SELECT work_kind_uid,actor_kind_uid,actor_uid,effective_order,result_kind,progress_delta_units,effort_units,financial_transaction_uid,command_uid,source_event_uid,provenance,metadata_json FROM project_work_records WHERE campaign_id=? AND project_uid=? AND work_record_uid=?",
        arrayOf(campaign, projectUid, workRecordUid)).use { c ->
        if (!c.moveToFirst()) return null
        ProjectWorkRecord(campaign, workRecordUid, projectUid, c.getString(0), OwnershipOwnerRef(c.getString(1), c.getString(2)),
            c.getLong(3), ProjectWorkResult.valueOf(c.getString(4)), c.getLong(5), c.p64LongOrNull(6), c.p64Optional(7),
            c.p64Optional(8), c.p64Optional(9), c.getString(10), c.p64Optional(11))
    }
    val command = work.commandUid ?: return null
    if (identity != null && changeSet != null && command == identity.commandUid && workRecordUid.startsWith(identity.transactionUid + ":")) {
        val exactChangeUid = workRecordUid.removePrefix(identity.transactionUid + ":")
        val source = changeSet.changes.singleOrNull { it.changeUid == exactChangeUid }?.payload
        val matches = when (source) {
            is BackgroundProjectWorkChange -> source.campaignUid == campaign && source.projectUid == projectUid &&
                source.progressUnits == work.progressDeltaUnits && source.worker == DomainRef(work.actor.ownerKindUid, work.actor.ownerUid)
            is DevelopmentProjectChange -> source.projectUid == projectUid && source.progressDelta.units == work.progressDeltaUnits
            else -> false
        }
        val intent = changeSet.eventIntents.singleOrNull { exactChangeUid in it.causalChangeUids }
        if (matches && intent != null) return Phase64ProjectResultEvidence(work,
            CampaignEventStore(db, campaign).eventUid(identity, changeSet, intent))
    }
    return db.rawQuery("SELECT event_uid,transaction_uid,causal_change_uids_canonical FROM canonical_gameplay_events WHERE campaign_uid=? AND command_uid=? AND subject_ref_kind_uid='PROJECT' AND subject_ref_uid=? AND committed_order IS NOT NULL ORDER BY event_uid",
        arrayOf(campaign, command, projectUid)).use { c ->
        while (c.moveToNext()) {
            val prefix = c.getString(1) + ":"
            if (!workRecordUid.startsWith(prefix)) continue
            val exactChangeUid = workRecordUid.removePrefix(prefix)
            val causal = Json.parseToJsonElement(c.getString(2)).jsonArray.map { it.jsonPrimitive.let { value -> require(value.isString); value.content } }
            if (exactChangeUid in causal) return@use Phase64ProjectResultEvidence(work, c.getString(0))
        }
        null
    }
}

internal fun applyPhase64ProjectWork(db: SQLiteDatabase, identity: TurnTransactionIdentity,
    changeSet: PlayerChangeSet, changeUid: String, change: BackgroundProjectWorkChange) {
    require(db.inTransaction() && identity.campaignUid == change.campaignUid) { "P64:PROJECT_WORK_TURN_REQUIRED" }
    val order = requireNotNull(changeSet.requestedEffectiveOrder) { "P64:PROJECT_WORK_ORDER_REQUIRED" }
    val snapshot = requireNotNull(capturePhase64ProjectSnapshot(db, identity.campaignUid, change.projectUid)) { "P64:PROJECT_WORK_STATE_MISSING" }
    require(snapshot.project.projectVersion == change.expectedProjectVersion && snapshot.type.definitionVersion == change.expectedTypeDefinitionVersion &&
        snapshot.progress.progressUnits == change.expectedProgressUnits) { "P64:PROJECT_WORK_STALE_STATE" }
    val scope = BackgroundProcessEvaluationScope(TemporalScope(identity.campaignUid, "P64:CANONICAL_APPLY", order, "P64:CANONICAL_APPLY"), "P64:UNUSED", "P64:UNUSED")
    val parameters = mapOf("projectUid" to change.projectUid, "projectVersion" to change.expectedProjectVersion.toString(),
        "progressUnits" to change.progressUnits.toString(), "workerKind" to change.worker.kindUid, "workerUid" to change.worker.uid,
        "labourResourceUid" to change.labourResourceUid, "labourUnits" to change.labourUnits.toString(),
        "ruleUid" to change.ruleUid, "ruleVersion" to change.ruleVersion.toString(),
        "inputItemUids" to change.evidenceRefs.filter { it.kindUid == "ITEM_INSTANCE" }.joinToString("|") { it.uid })
        .filterValues(String::isNotEmpty)
    val checked = Phase64EconomyOwnerRules.projectWork(change.operationUid, parameters, scope, snapshot, emptyList())
    require(checked.status == BackgroundProcessStatus.COMPLETED && checked.changes.singleOrNull() == change) { "P64:PROJECT_WORK_RECHECK_FAILED:${checked.reasonUid}" }
    phase64LabourDebit(change.worker,change.labourResourceUid,change.labourUnits)?.let { expected->
        val wrapper=changeSet.changes.single { it.changeUid==changeUid }
        require(changeSet.changes.filter { it.sourceRuleUid==wrapper.sourceRuleUid && it.payload==expected }.size==1) {
            "P64:PROJECT_LABOUR_DEBIT_REQUIRED"
        }
    }
    val intent = changeSet.eventIntents.singleOrNull { changeUid in it.causalChangeUids }
        ?: error("P64:PROJECT_WORK_EXACT_EVENT_REQUIRED")
    val sourceEvent = CampaignEventStore(db, identity.campaignUid).eventUid(identity, changeSet, intent)
    val owner = DevelopmentProjectStore(db, identity.campaignUid)
    owner.recordWork(ProjectWorkRecord(identity.campaignUid,
        "${identity.transactionUid}:$changeUid", change.projectUid, change.operationUid, OwnershipOwnerRef(change.worker.kindUid, change.worker.uid),
        order, ProjectWorkResult.SUCCESS, change.progressUnits, change.labourUnits, commandUid = identity.commandUid,
        sourceEventUid = sourceEvent, provenance = "TURN:${identity.transactionUid}:$changeUid"))
    if (change.readyToComplete) owner.changeStatus(ProjectStatusEvent(identity.campaignUid,
        "${identity.transactionUid}:$changeUid:READY", change.projectUid, ProjectStatus.READY_TO_COMPLETE,
        order, sourceEventUid = sourceEvent, provenance = "TURN:${identity.transactionUid}:$changeUid"))
}

internal fun phase64ProjectOutputExists(db: SQLiteDatabase, campaign: String, outputKind: String, output: DomainRef): Boolean {
    val query: Pair<String, Array<String>> = when (outputKind) {
        PROJECT_OUTPUT_ITEM_INSTANCE -> {
            if (output.kindUid != "ITEM_INSTANCE") return false
            "SELECT 1 FROM item_instances WHERE campaign_id=? AND item_instance_uid=?" to arrayOf(campaign, output.uid)
        }
        PROJECT_OUTPUT_ASSET -> "SELECT 1 FROM asset_records WHERE campaign_id=? AND asset_kind_uid=? AND asset_uid=? AND lifecycle_status='ACTIVE'" to arrayOf(campaign, output.kindUid, output.uid)
        PROJECT_OUTPUT_TRUTH -> {
            if (output.kindUid != "CAMPAIGN_TRUTH") return false
            "SELECT 1 FROM campaign_truth_records WHERE campaign_id=? AND truth_uid=? AND truth_kind='FACT' AND active=1" to arrayOf(campaign, output.uid)
        }
        PROJECT_OUTPUT_TECHNIQUE -> {
            if (output.kindUid != "TECHNIQUE") return false
            "SELECT 1 FROM technique_definitions_v2 WHERE technique_uid=? AND definition_status='ACTIVE'" to arrayOf(output.uid)
        }
        PROJECT_OUTPUT_SKILL -> {
            if (output.kindUid != "SKILL") return false
            "SELECT 1 FROM skill_definitions_v2 WHERE skill_uid=? AND definition_status='ACTIVE'" to arrayOf(output.uid)
        }
        else -> return false
    }
    return db.rawQuery(query.first, query.second).use { it.moveToFirst() }
}

/** The outer TurnTransaction owns atomicity. Both records are written through the existing
 * project owner; replay uses the same stable transaction/change identities and checks. */
internal fun applyPhase64ProjectCompletion(db: SQLiteDatabase, identity: TurnTransactionIdentity,
    changeSet: PlayerChangeSet, changeUid: String, change: DevelopmentProjectCompletionChange) {
    require(db.inTransaction() && identity.campaignUid == change.campaignUid) { "P64:PROJECT_COMPLETION_TURN_REQUIRED" }
    require(!change.resultWorkRecordUid.startsWith(identity.transactionUid + ":")) { "P64:PROJECT_COMPLETION_PRIOR_COMMIT_REQUIRED" }
    val order = requireNotNull(changeSet.requestedEffectiveOrder) { "P64:PROJECT_COMPLETION_ORDER_REQUIRED" }
    val snapshot = requireNotNull(capturePhase64ProjectSnapshot(db, identity.campaignUid, change.projectUid)) { "P64:PROJECT_COMPLETION_STATE_MISSING" }
    require(snapshot.project.projectVersion == change.expectedProjectVersion && snapshot.type.definitionVersion == change.expectedTypeDefinitionVersion &&
        snapshot.progress.progressUnits == change.expectedProgressUnits) { "P64:PROJECT_COMPLETION_STALE_STATE" }
    val result = requireNotNull(capturePhase64ProjectResult(db, identity.campaignUid, change.projectUid, change.resultWorkRecordUid, identity, changeSet)) { "P64:PROJECT_COMPLETION_RESULT_MISSING" }
    require(result.eventUid == change.resultEventUid) { "P64:PROJECT_COMPLETION_RESULT_EVENT_MISMATCH" }
    val scope = BackgroundProcessEvaluationScope(TemporalScope(identity.campaignUid, "P64:CANONICAL_APPLY", order, "P64:CANONICAL_APPLY"), "P64:UNUSED", "P64:UNUSED")
    val parameters = mapOf("projectUid" to change.projectUid, "projectVersion" to change.expectedProjectVersion.toString(),
        "resultEvidenceUid" to change.resultWorkRecordUid, "outputKindUid" to change.outputKindUid,
        "outputRefKindUid" to change.outputRef.kindUid, "outputUid" to change.outputRef.uid,
        "ruleUid" to change.ruleUid, "ruleVersion" to change.ruleVersion.toString())
    val checked = preparePhase64ProjectCompletion(parameters, scope, snapshot, result,
        phase64ProjectOutputExists(db, identity.campaignUid, change.outputKindUid, change.outputRef))
    require(checked.status == BackgroundProcessStatus.COMPLETED && checked.changes.singleOrNull() == change) { "P64:PROJECT_COMPLETION_RECHECK_FAILED:${checked.reasonUid}" }
    val intent = changeSet.eventIntents.singleOrNull { changeUid in it.causalChangeUids }
        ?: error("P64:PROJECT_COMPLETION_EXACT_EVENT_REQUIRED")
    val completionEventUid = CampaignEventStore(db, identity.campaignUid).eventUid(identity, changeSet, intent)
    val owner = DevelopmentProjectStore(db, identity.campaignUid)
    val provenance = "TURN:${identity.transactionUid}:$changeUid"
    owner.commitOutcome(ProjectOutcome(identity.campaignUid, "${identity.transactionUid}:$changeUid:OUTCOME", change.projectUid,
        change.outputKindUid, change.outputRef.kindUid, change.outputRef.uid, order, completionEventUid, identity.commandUid, provenance))
    owner.changeStatus(ProjectStatusEvent(identity.campaignUid, "${identity.transactionUid}:$changeUid:COMPLETED", change.projectUid,
        ProjectStatus.COMPLETED, order, sourceEventUid = completionEventUid, provenance = provenance))
}

private fun Cursor.p64Optional(index: Int): String? = if (isNull(index)) null else getString(index)
private fun Cursor.p64LongOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
