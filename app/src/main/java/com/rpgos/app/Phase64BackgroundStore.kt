package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

internal const val PHASE64_CHANGE_KIND="RPGOS-CHANGE:BACKGROUND_PROCESS"

data class BackgroundProcessChange internal constructor(val campaignUid:String,val historyGenerationUid:String,
    val expectedVersion:Long,val process:BackgroundProcessInstance,val evidence:WorldProcessEvidence,
    val consequenceFingerprints:List<String> = emptyList(),
    val deadlineAdds:List<WorldProcessDeadline> = emptyList(),val deadlineRemovals:List<String> = emptyList(),
    val ownerDelegations:List<TemporalOwnerDelegation> = emptyList()):PlayerDomainChangePayload {
    init {
        require(campaignUid.isNotBlank() && historyGenerationUid.isNotBlank() && expectedVersion>=0)
        require(process.version==Math.addExact(expectedVersion,1) && evidence.processUid==process.uid)
        require(evidence.ruleUid==process.definitionUid && evidence.ruleVersion==process.definitionVersion)
        require(consequenceFingerprints.size<=128)
        require(deadlineAdds.size<=32 && deadlineRemovals.size<=32 && deadlineAdds.map { it.uid }.distinct().size==deadlineAdds.size && deadlineRemovals.distinct().size==deadlineRemovals.size)
        require(deadlineAdds.all { it.ownerUid==NpcDutyDeadlineProcess.OWNER }) {"P64:UNREGISTERED_DEADLINE_DELEGATION"}
        require(ownerDelegations.size<=4 && ownerDelegations.all { it.sourceUid==process.uid && it.proposed.ownerUid==NpcActionProcess.OWNER })
    }
}

internal object Phase64BackgroundCodec {
    private fun strings(value:Map<String,String>)=buildJsonObject { value.toSortedMap().forEach { (k,v)->put(k,v) } }
    private fun readStrings(value:JsonElement)=value.jsonObject.mapValues { Phase63WorldCodec.string(it.value) }
    private fun ref(value:DomainRef)=buildJsonObject { put("kind",value.kindUid);put("uid",value.uid) }
    private fun readRef(value:JsonElement)=value.jsonObject.let { DomainRef(text(it,"kind"),text(it,"uid")) }
    private fun text(o:JsonObject,k:String)=Phase63WorldCodec.text(o,k)
    private fun number(o:JsonObject,k:String)=Phase63WorldCodec.number(o,k)
    fun definition(v:BackgroundProcessDefinition)=buildJsonObject {
        put("uid",v.uid);put("version",v.version);put("domain",v.domain);put("operation",v.operation)
        put("duration",v.durationMillis);put("priority",v.priority);put("parameters",strings(v.parameters))
    }
    fun readDefinition(o:JsonObject):BackgroundProcessDefinition {
        Phase63WorldCodec.keys(o,"uid","version","domain","operation","duration","priority","parameters")
        return BackgroundProcessDefinition(text(o,"uid"),Math.toIntExact(number(o,"version")),text(o,"domain"),text(o,"operation"),
            number(o,"duration"),Math.toIntExact(number(o,"priority")),readStrings(o.getValue("parameters")))
    }
    fun process(v:BackgroundProcessInstance)=buildJsonObject {
        put("uid",v.uid);put("definition",v.definitionUid);put("definition_version",v.definitionVersion);put("actor",ref(v.actor))
        put("version",v.version);put("started",v.startedAt.milliseconds);put("due",v.due.milliseconds);put("status",v.status.name)
        put("parameters",strings(v.parameters));put("dependencies",JsonArray(v.dependencyUids.map(::JsonPrimitive)))
        put("progress",v.progressUnits);put("reason",v.reasonUid?.let(::JsonPrimitive)?:JsonNull)
    }
    fun readProcess(o:JsonObject):BackgroundProcessInstance {
        Phase63WorldCodec.keys(o,"uid","definition","definition_version","actor","version","started","due","status","parameters","dependencies","progress","reason")
        return BackgroundProcessInstance(text(o,"uid"),text(o,"definition"),Math.toIntExact(number(o,"definition_version")),readRef(o.getValue("actor")),
            number(o,"version"),WorldTimeTick(number(o,"started")),WorldTimeTick(number(o,"due")),enumValueOf(text(o,"status")),
            readStrings(o.getValue("parameters")),o.getValue("dependencies").jsonArray.map(Phase63WorldCodec::string),number(o,"progress"),
            o.getValue("reason").takeUnless { it==JsonNull }?.let(Phase63WorldCodec::string))
    }
    fun evidence(v:WorldProcessEvidence)=buildJsonObject {
        put("uid",v.uid);put("process",v.processUid);put("rule",v.ruleUid);put("version",v.ruleVersion)
        put("sources",JsonArray(v.sourceUids.map(::JsonPrimitive)));put("at",v.at.milliseconds);put("reason",v.reasonUid?.let(::JsonPrimitive)?:JsonNull)
    }
    fun readEvidence(o:JsonObject):WorldProcessEvidence {
        Phase63WorldCodec.keys(o,"uid","process","rule","version","sources","at","reason")
        return WorldProcessEvidence(text(o,"uid"),text(o,"process"),text(o,"rule"),Math.toIntExact(number(o,"version")),
            o.getValue("sources").jsonArray.map(Phase63WorldCodec::string),WorldTimeTick(number(o,"at")),
            o.getValue("reason").takeUnless { it==JsonNull }?.let(Phase63WorldCodec::string))
    }
    fun fingerprint(payload:PlayerDomainChangePayload)=phase63Hash(TypedPlayerChangeRegistry.core().encodeWorkerPayload(payload).toString())
    /** The typed change retains its exact list order through admission/replay. The clock
     * store may canonicalize its own deadline set, but must not rewrite this owner receipt. */
    fun receiptDeadlines(values:List<WorldProcessDeadline>)=JsonArray(values.map { deadline->
        Json.parseToJsonElement(Phase60DeadlineCodec.encode(listOf(deadline))).jsonArray.single()
    })
    fun delegation(v:TemporalOwnerDelegation)=buildJsonObject {
        put("source",v.sourceUid);put("expected",v.expectedStateFingerprint)
        put("state",Json.parseToJsonElement(Phase60ProcessStateCodec.encode(listOf(v.proposed))))
        put("deadlines",receiptDeadlines(v.deadlines))
    }
    fun readDelegation(o:JsonObject):TemporalOwnerDelegation {
        Phase63WorldCodec.keys(o,"source","expected","state","deadlines")
        return TemporalOwnerDelegation(text(o,"source"),text(o,"expected"),
            Phase60ProcessStateCodec.decode(o.getValue("state").toString()).single(),Phase60DeadlineCodec.decode(o.getValue("deadlines").toString()))
    }
}

internal fun phase64ChangeCodec()=object:TypedPlayerChangeCodec<BackgroundProcessChange>(BackgroundProcessChange::class,
    ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,setOf("campaign","generation","expected_version","process","evidence","consequences","deadline_adds","deadline_removals","owner_delegations")) {
    override fun encode(p:BackgroundProcessChange)=buildJsonObject {
        put("campaign",p.campaignUid);put("generation",p.historyGenerationUid);put("expected_version",p.expectedVersion)
        put("process",Phase64BackgroundCodec.process(p.process));put("evidence",Phase64BackgroundCodec.evidence(p.evidence))
        put("consequences",JsonArray(p.consequenceFingerprints.map(::JsonPrimitive)))
        put("deadline_adds",Phase64BackgroundCodec.receiptDeadlines(p.deadlineAdds))
        put("deadline_removals",JsonArray(p.deadlineRemovals.map(::JsonPrimitive)))
        put("owner_delegations",JsonArray(p.ownerDelegations.map(Phase64BackgroundCodec::delegation)))
    }
    override fun decodeKnownFields(o:JsonObject)=BackgroundProcessChange(Phase63WorldCodec.text(o,"campaign"),Phase63WorldCodec.text(o,"generation"),
        Phase63WorldCodec.number(o,"expected_version"),Phase64BackgroundCodec.readProcess(o.getValue("process").jsonObject),
        Phase64BackgroundCodec.readEvidence(o.getValue("evidence").jsonObject),o.getValue("consequences").jsonArray.map(Phase63WorldCodec::string),
        o["deadline_adds"]?.let { Phase60DeadlineCodec.decode(it.toString()) }?:emptyList(),
        o["deadline_removals"]?.jsonArray?.map(Phase63WorldCodec::string)?:emptyList(),
        o["owner_delegations"]?.jsonArray?.map { Phase64BackgroundCodec.readDelegation(it.jsonObject) }?:emptyList())
    override fun conflictKeys(p:BackgroundProcessChange)=setOf("P64:${p.campaignUid}:${p.process.uid}:${p.expectedVersion}")
}

internal object Phase64BackgroundSchema {
    const val POLICY="phase64_campaign_policy"
    const val DEFINITIONS="phase64_process_definitions"
    const val PROCESSES="phase64_process_instances"
    const val DEPENDENCIES="phase64_process_dependencies"
    const val EVIDENCE="phase64_process_evidence"
    const val ACTIVATIONS="phase64_process_activations"
    val tables=setOf(POLICY,DEFINITIONS,PROCESSES,DEPENDENCIES,EVIDENCE,ACTIVATIONS)+Phase64DemographySchema.tables
    fun ensureReady(db:SQLiteDatabase) {
        Phase64DemographySchema.ensureReady(db)
        db.execSQL("CREATE TABLE IF NOT EXISTS $POLICY(campaign_uid TEXT PRIMARY KEY,policy_uid TEXT NOT NULL,rule_fingerprint TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $DEFINITIONS(campaign_uid TEXT NOT NULL,definition_uid TEXT NOT NULL,definition_version INTEGER NOT NULL,canonical TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(campaign_uid,definition_uid,definition_version))")
        db.execSQL("CREATE TABLE IF NOT EXISTS $PROCESSES(campaign_uid TEXT NOT NULL,process_uid TEXT NOT NULL,state_version INTEGER NOT NULL,status TEXT NOT NULL,due_ms INTEGER NOT NULL,canonical TEXT NOT NULL,fingerprint TEXT NOT NULL,updated_order INTEGER NOT NULL,PRIMARY KEY(campaign_uid,process_uid))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p64_due ON $PROCESSES(campaign_uid,status,due_ms,process_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p64_update ON $PROCESSES(campaign_uid,updated_order,process_uid)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $DEPENDENCIES(campaign_uid TEXT NOT NULL,process_uid TEXT NOT NULL,dependency_uid TEXT NOT NULL,PRIMARY KEY(campaign_uid,process_uid,dependency_uid))")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p64_dependency ON $DEPENDENCIES(campaign_uid,dependency_uid,process_uid)")
        db.execSQL("CREATE TABLE IF NOT EXISTS $EVIDENCE(campaign_uid TEXT NOT NULL,evidence_uid TEXT NOT NULL,process_uid TEXT NOT NULL,canonical TEXT NOT NULL,created_order INTEGER NOT NULL,PRIMARY KEY(campaign_uid,evidence_uid))")
        db.execSQL("CREATE TABLE IF NOT EXISTS $ACTIVATIONS(campaign_uid TEXT NOT NULL,action_uid TEXT NOT NULL,definition_uid TEXT NOT NULL,definition_version INTEGER NOT NULL,PRIMARY KEY(campaign_uid,action_uid))")
    }
    fun isReady(db:SQLiteDatabase)=tables.all { table->db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use { it.moveToFirst() } }
}

internal class Phase64BackgroundStore(private val db:SQLiteDatabase,private val campaign:String) {
    fun policy():String? {
        if(!Phase64BackgroundSchema.isReady(db))return null
        return db.rawQuery("SELECT rule_fingerprint FROM ${Phase64BackgroundSchema.POLICY} WHERE campaign_uid=? AND policy_uid='PHASE64_V1'",arrayOf(campaign)).use { if(it.moveToFirst())it.getString(0) else null }
    }
    fun definition(uid:String,version:Int):BackgroundProcessDefinition?=db.rawQuery("SELECT canonical,fingerprint FROM ${Phase64BackgroundSchema.DEFINITIONS} WHERE campaign_uid=? AND definition_uid=? AND definition_version=?",arrayOf(campaign,uid,version.toString())).use { c->
        if(!c.moveToFirst())null else { val wire=c.getString(0);require(phase63Hash(wire)==c.getString(1));Phase64BackgroundCodec.readDefinition(Json.parseToJsonElement(wire).jsonObject) }
    }
    fun process(uid:String):BackgroundProcessInstance?=db.rawQuery("SELECT canonical,fingerprint FROM ${Phase64BackgroundSchema.PROCESSES} WHERE campaign_uid=? AND process_uid=?",arrayOf(campaign,uid)).use { c->
        if(!c.moveToFirst())null else { val wire=c.getString(0);require(phase63Hash(wire)==c.getString(1));Phase64BackgroundCodec.readProcess(Json.parseToJsonElement(wire).jsonObject) }
    }
    fun activation(action:String):BackgroundProcessDefinition? {
        if(policy()==null)return null
        return db.rawQuery("SELECT definition_uid,definition_version FROM ${Phase64BackgroundSchema.ACTIVATIONS} WHERE campaign_uid=? AND action_uid=?",arrayOf(campaign,action)).use { c->
            if(c.moveToFirst())definition(c.getString(0),c.getInt(1)) else null
        }
    }
    fun publicActivations(limit:Int=16):List<BackgroundProcessDefinition> {
        require(limit in 1..32)
        if(policy()==null)return emptyList()
        return db.rawQuery("SELECT definition_uid,definition_version FROM ${Phase64BackgroundSchema.ACTIVATIONS} WHERE campaign_uid=? ORDER BY action_uid LIMIT ?",arrayOf(campaign,limit.toString())).use { c->buildList {
            while(c.moveToNext())definition(c.getString(0),c.getInt(1))?.takeIf { it.parameters[Phase64ProcessActivation.PUBLIC_KEY]=="true" }?.let(::add)
        } }
    }
    /** A bounded own-state read, not a dump of the world's process parameters. The exact
     * canonical actor fragment is only an SQL prefilter; the decoded owner is rechecked. */
    fun playerProcessProjection(scope:TemporalScope,player:DomainRef):Phase64PlayerProcessSnapshot {
        require(scope.campaignUid==campaign && player.kindUid=="PLAYER")
        if(policy()==null)return Phase64PlayerProcessSnapshot(scope,player,emptyList(),true)
        val actorFragment="\"actor\":"+buildJsonObject { put("kind",player.kindUid);put("uid",player.uid) }
        val rows=db.rawQuery("SELECT canonical,fingerprint FROM ${Phase64BackgroundSchema.PROCESSES} WHERE campaign_uid=? AND status IN ('ACTIVE','BLOCKED') AND instr(canonical,?)>0 ORDER BY due_ms,process_uid LIMIT 33",
            arrayOf(campaign,actorFragment)).use { c->buildList {
            while(c.moveToNext()) {
                val wire=c.getString(0);require(phase63Hash(wire)==c.getString(1)){"P64:PROCESS_INTEGRITY"}
                val process=Phase64BackgroundCodec.readProcess(Json.parseToJsonElement(wire).jsonObject)
                require(process.actor==player){"P64:PLAYER_PROCESS_SCOPE_MISMATCH"}
                add(process)
            }
        } }
        val definitions=rows.map { it.definitionUid to it.definitionVersion }.distinct().associateWith { (uid,version)->
            requireNotNull(definition(uid,version)){"P64:PLAYER_PROJECTION_RULE_REQUIRED"}
        }
        return Phase64PlayerProcessProjection.capture(scope,player,rows,definitions)
    }
    /** Exact current receipt boundary, bounded to the turn's process budget. Principal
     * filtering happens before presentation; no parameters or private actors leave Core. */
    fun playerNotices(player:DomainRef,committedOrder:Long):List<String> {
        require(player.kindUid=="PLAYER" && committedOrder>0)
        if(policy()==null)return emptyList()
        return db.rawQuery("SELECT canonical,fingerprint FROM ${Phase64BackgroundSchema.PROCESSES} WHERE campaign_uid=? AND updated_order=? ORDER BY process_uid LIMIT 1025",
            arrayOf(campaign,committedOrder.toString())).use { c->
            val notices=mutableListOf<String>();var reads=0
            while(c.moveToNext()) {
                require(++reads<=1024){"P64:NOTICE_READ_BUDGET"}
                val wire=c.getString(0);require(phase63Hash(wire)==c.getString(1)){"P64:PROCESS_INTEGRITY"}
                val process=Phase64BackgroundCodec.readProcess(Json.parseToJsonElement(wire).jsonObject)
                if(process.actor!=player)continue
                val rule=definition(process.definitionUid,process.definitionVersion)?:error("P64:NOTICE_RULE_REQUIRED")
                if(notices.size<4)notices+=Phase64WorldMessages.notice(rule,process)
            }
            notices.distinct()
        }
    }
    fun due(through:WorldTimeTick,limit:Int=32):List<BackgroundProcessInstance> {
        require(limit in 1..256)
        if(policy()==null)return emptyList()
        return db.rawQuery("SELECT canonical FROM ${Phase64BackgroundSchema.PROCESSES} WHERE campaign_uid=? AND status IN ('ACTIVE','BLOCKED') AND due_ms<=? ORDER BY due_ms,process_uid LIMIT ?",
            arrayOf(campaign,through.milliseconds.toString(),(limit+1).toString())).use { c->buildList {
                while(c.moveToNext()){require(size<limit){"P64:PROCESS_BATCH_REQUIRED"};add(Phase64BackgroundCodec.readProcess(Json.parseToJsonElement(c.getString(0)).jsonObject))}
            } }
    }
    fun duePage(through:WorldTimeTick,after:BackgroundDueCursor?):BackgroundDuePage {
        if(policy()==null)return BackgroundDuePage(emptyList(),null)
        val continuation=if(after==null)"" else " AND (due_ms>? OR (due_ms=? AND process_uid>?))"
        val args=mutableListOf(campaign,through.milliseconds.toString())
        after?.let { args+=listOf(it.due.milliseconds.toString(),it.due.milliseconds.toString(),it.uid) }
        return db.rawQuery("SELECT canonical,fingerprint FROM ${Phase64BackgroundSchema.PROCESSES} WHERE campaign_uid=? AND status IN ('ACTIVE','BLOCKED') AND due_ms<=?$continuation ORDER BY due_ms,process_uid LIMIT 33",args.toTypedArray()).use { c->
            val rows=buildList { while(c.moveToNext()) {
                val wire=c.getString(0);require(phase63Hash(wire)==c.getString(1)){"P64:PROCESS_INTEGRITY"}
                add(Phase64BackgroundCodec.readProcess(Json.parseToJsonElement(wire).jsonObject))
            } }
            val page=rows.take(32)
            BackgroundDuePage(page,if(rows.size>32)page.last().let { BackgroundDueCursor(it.due,it.uid) } else null)
        }
    }
    /** Validate receipts before any of this turn's owner changes have been applied. */
    fun validateCompletionReceipts(identity:TurnTransactionIdentity,set:PlayerChangeSet) {
        val receipts=set.changes.mapNotNull { it.payload as? BackgroundProcessChange }
            .filter { it.expectedVersion==0L && it.process.status==BackgroundProcessStatus.COMPLETED }
        if(receipts.isEmpty())return
        val fingerprint=requireNotNull(policy()){ "P64:CAMPAIGN_NOT_ENABLED" }
        val clock=requireNotNull(set.changes.mapNotNull { it.payload as? TemporalStateChange }.singleOrNull()){ "P64:CANONICAL_TIME_REQUIRED" }
        val before=Phase60TemporalStateStore(db,campaign).read()
        require(clock.expectedVersion==before.version && clock.expectedTime==before.time){ "P64:STALE_COMPLETION_CLOCK" }
        val active=requireNotNull(ActivePlayerStore(db,campaign).active()){ "P64:ACTIVE_PLAYER_OWNER_REQUIRED" }
        require(receipts.map { it.process.parameters["decision_uid"] }.distinct().size==receipts.size){ "P64:DUPLICATE_COMPLETION_RECEIPT" }
        receipts.forEach { receipt->
            require(process(receipt.process.uid)==null){ "P64:COMBAT_RECEIPT_ALREADY_EXISTS" }
            val rule=requireNotNull(definition(receipt.process.definitionUid,receipt.process.definitionVersion)){ "P64:RULE_REQUIRED" }
            val brain=NpcBrainStore(db,campaign).read(receipt.process.actor) ?: set.changes.mapNotNull { it.payload as? NpcBrainChange }
                .firstOrNull { it.actor==receipt.process.actor && it.expectedVersion==0L }?.let { NpcBrainCodec.decode(it.stateCanonical) }
                ?:error("P64:COMBAT_BRAIN_REQUIRED")
            Phase64CombatReceiptAdmission.validate(identity,receipt,rule,fingerprint,brain,active.playerUid,clock,set)
        }
    }
    fun apply(identity:TurnTransactionIdentity,p:BackgroundProcessChange,order:Long,set:PlayerChangeSet) {
        require(db.inTransaction() && identity.campaignUid==campaign && p.campaignUid==campaign);requireCanonicalGameplayMutation(db,campaign)
        requireNotNull(policy()){ "P64:CAMPAIGN_NOT_ENABLED" }
        val before=process(p.process.uid)
        require((before?.version?:0)==p.expectedVersion){"P64:STALE_PROCESS_VERSION"}
        val rule=requireNotNull(definition(p.process.definitionUid,p.process.definitionVersion)){"P64:RULE_REQUIRED"}
        val completionReceipt=before==null && p.process.status==BackgroundProcessStatus.COMPLETED
        if(completionReceipt) {
            require(rule.uid==Phase64CombatReceiptFactory.RULE_UID && rule.version==Phase64CombatReceiptFactory.RULE_VERSION &&
                rule.parameters==mapOf(Phase64CombatReceiptFactory.OWNER_PARAMETER to NpcActionProcess.OWNER) &&
                p.expectedVersion==0L && p.process.version==1L && p.process.progressUnits==1L && p.process.reasonUid==null &&
                p.process.dependencyUids.isEmpty() && p.deadlineAdds.isEmpty() && p.deadlineRemovals.isEmpty() && p.ownerDelegations.isEmpty()) {"P64:COMBAT_RECEIPT_REQUIRED"}
        } else if(before==null) {
            require(p.process.status==BackgroundProcessStatus.ACTIVE && p.process.progressUnits==0L && p.consequenceFingerprints.isEmpty()) {"P64:START_REQUIRES_ACTIVE_INTENT"}
            require(p.process.due.milliseconds==Math.addExact(p.process.startedAt.milliseconds,rule.durationMillis)) {"P64:START_DURATION_MISMATCH"}
            val proof=p.process.parameters["p64_start_proof_uid"]
            val initiator=DomainRef(requireNotNull(p.process.parameters["p64_initiator_kind_uid"]),requireNotNull(p.process.parameters["p64_initiator_uid"]))
            require(initiator==p.process.actor && p.process.parameters["p64_start_command_uid"]==identity.commandUid &&
                proof!=null && proof in p.evidence.sourceUids && proof.startsWith("${Phase64ProcessActivation.START_PROOF}${phase63Hash(Phase64BackgroundCodec.definition(rule).toString())}:")) {"P64:START_AUTHORIZATION_REQUIRED"}
            require(set.changes.any { c->c.sourceRuleUid==proof && (c.payload as? MechanicalTrackChange)?.let { track->
                track.subject==initiator && track.trackUid=="ACTION:P64_START:${p.process.uid}" && track.delta.units==1L }==true }) {"P64:START_ACTION_EVIDENCE_REQUIRED"}
            val target=DomainRef(requireNotNull(p.process.parameters["p64_start_target_kind"]),requireNotNull(p.process.parameters["p64_start_target_uid"]))
            val ownSource=if(Phase64ProcessActivation.OWN_ACQUISITION in rule.parameters.values)
                requireNotNull(p.process.parameters["p64_start_source_acquisition_uid"]) else null
            val bound=Phase64ProcessActivation.bind(rule,initiator,target,p.process.parameters["message_text"],p.process.uid,ownSource)
            if(Phase64NeutralCommunicationOwner.matchesDefinition(rule) || ownSource!=null)require(proof.endsWith(":${Phase64ProcessActivation.parameterFingerprint(bound)}")){"P64:BOUND_SOURCE_PROOF_MISMATCH"}
            ownSource?.let { source->
                require(source in p.evidence.sourceUids && initiator.kindUid!="PLAYER" &&
                    db.rawQuery("SELECT 1 FROM ${Phase37KnowledgeSchema.ACQUISITIONS} WHERE campaign_uid=? AND acquisition_uid=? AND holder_kind_uid=? AND holder_uid=? AND created_order<=? LIMIT 1",
                        arrayOf(campaign,source,initiator.kindUid,initiator.uid,(order-1).toString())).use { it.moveToFirst() }) {"P64:OWN_SOURCE_REQUIRED"}
            }
            val expectedParameters=bound+p.process.parameters.filterKeys { it.startsWith("p64_") }
            require(p.process.parameters==expectedParameters && p.process.parameters.keys.count { it.startsWith("p64_") }==(if(ownSource==null)6 else 7)) {"P64:START_RULE_PARAMETERS_CHANGED"}
        } else {
            require(before.status in setOf(BackgroundProcessStatus.ACTIVE,BackgroundProcessStatus.BLOCKED)) {"P64:TERMINAL_PROCESS_REEXECUTION"}
            require(before.copy(version=p.process.version,due=p.process.due,status=p.process.status,progressUnits=p.process.progressUnits,reasonUid=p.process.reasonUid)==p.process) {"P64:PROCESS_IDENTITY_CHANGED"}
            if(p.process.status==BackgroundProcessStatus.INTERRUPTED && p.evidence.at<before.due) {
                val proof=p.evidence.sourceUids.singleOrNull { it.startsWith(Phase64ProcessActivation.CANCEL_PROOF) }
                require(p.process.actor.kindUid=="PLAYER" && p.consequenceFingerprints.isEmpty() && p.process.progressUnits==before.progressUnits &&
                    proof!=null && set.changes.any { c->c.sourceRuleUid==proof && (c.payload as? MechanicalTrackChange)?.let { track->
                        track.subject==before.actor && track.trackUid=="ACTION:P64_CANCEL:${before.uid}" && track.delta.units==1L }==true }) {"P64:CANCELLATION_ACTION_EVIDENCE_REQUIRED"}
            } else require(p.process.progressUnits>=before.progressUnits && p.evidence.at>=before.due) {"P64:PREMATURE_SETTLEMENT"}
        }
        val clock=set.changes.mapNotNull { it.payload as? TemporalStateChange }.singleOrNull()
        require(clock!=null && p.evidence.at in clock.expectedTime..clock.proposedTime) {"P64:CANONICAL_TIME_REQUIRED"}
        if(before==null && !completionReceipt || p.process.status==BackgroundProcessStatus.INTERRUPTED)require(p.evidence.at==clock.proposedTime) {"P64:ACTION_TIME_EVIDENCE_REQUIRED"}
        val deadlines=Phase60DeadlineCodec.decode(clock.deadlinesCanonical)
        if(p.expectedVersion==0L && !completionReceipt)require(deadlines.any { it.uid==Phase64BackgroundProcessOwner.deadline(p.process) && it.due==p.process.due && it.ownerUid==Phase64BackgroundProcessOwner.OWNER }) {"P64:START_DEADLINE_REQUIRED"}
        if(completionReceipt)require(deadlines.none { it.uid==Phase64BackgroundProcessOwner.deadline(p.process) }) {"P64:COMPLETED_RECEIPT_DEADLINE_FORBIDDEN"}
        if(before!=null && p.process.status==BackgroundProcessStatus.INTERRUPTED)require(deadlines.none { it.uid==Phase64BackgroundProcessOwner.deadline(before) }) {"P64:CANCELLATION_DEADLINE_RETAINED"}
        require(p.deadlineAdds.all { it.due>clock.proposedTime && it in deadlines } && p.deadlineRemovals.none { uid->deadlines.any { it.uid==uid } }) {"P64:DEADLINE_CONSEQUENCE_DROPPED"}
        p.ownerDelegations.forEach { delegation->
            require(p.process.status==BackgroundProcessStatus.COMPLETED && rule.domain=="ORGANIZATION" && rule.operation=="DECISION") {"P64:DELEGATION_RULE_REQUIRED"}
            val added=NpcActionProcess.decode(delegation.proposed)
            val clockPending=NpcActionProcess.decode(Phase60ProcessStateCodec.decode(clock.processStatesCanonical).singleOrNull { it.ownerUid==NpcActionProcess.OWNER })
            delegation.deadlines.forEach { deadline->
                val action=added.single { it.deadlineUid==deadline.uid }
                require(action.actor==p.process.actor && action.startedAt==p.evidence.at)
                val brain=set.changes.mapNotNull { it.payload as? NpcBrainChange }.lastOrNull { it.actor==action.actor }
                    ?.let { NpcBrainCodec.decode(it.stateCanonical) } ?: error("P64:DELEGATED_PLAN_REQUIRED")
                val plan=brain.plans.single { it.uid==action.planUid }
                require(plan.actionUid==action.optionUid && plan.startedAt==action.startedAt && plan.nextEvaluationAt==action.due ||
                    plan.actionUid==action.optionUid && plan.startedAt==action.startedAt && plan.lifecycle in setOf(NpcPlanLifecycle.COMPLETED,NpcPlanLifecycle.INTERRUPTED))
                if(deadline.due>clock.proposedTime)require(deadline in deadlines && action in clockPending && plan.lifecycle==NpcPlanLifecycle.RUNNING)
                else require(deadline !in deadlines && action !in clockPending && plan.lifecycle in setOf(NpcPlanLifecycle.COMPLETED,NpcPlanLifecycle.INTERRUPTED))
            }
        }
        val actual=set.changes.filterNot { it.payload is BackgroundProcessChange }.map { Phase64BackgroundCodec.fingerprint(it.payload) }.groupingBy { it }.eachCount().toMutableMap()
        p.consequenceFingerprints.forEach { hash->val count=actual[hash]?:error("P64:CONSEQUENCE_DROPPED");if(count==1)actual.remove(hash) else actual[hash]=count-1 }
        val wire=Phase64BackgroundCodec.process(p.process).toString()
        db.execSQL("INSERT OR REPLACE INTO ${Phase64BackgroundSchema.PROCESSES} VALUES(?,?,?,?,?,?,?,?)",arrayOf(campaign,p.process.uid,p.process.version,p.process.status.name,p.process.due.milliseconds,wire,phase63Hash(wire),order))
        db.execSQL("DELETE FROM ${Phase64BackgroundSchema.DEPENDENCIES} WHERE campaign_uid=? AND process_uid=?",arrayOf(campaign,p.process.uid))
        p.process.dependencyUids.forEach { db.execSQL("INSERT INTO ${Phase64BackgroundSchema.DEPENDENCIES} VALUES(?,?,?)",arrayOf(campaign,p.process.uid,it)) }
        db.execSQL("INSERT INTO ${Phase64BackgroundSchema.EVIDENCE} VALUES(?,?,?,?,?)",arrayOf(campaign,p.evidence.uid,p.process.uid,Phase64BackgroundCodec.evidence(p.evidence).toString(),order))
    }
    /** New-campaign staging only. Ordinary migrations and readers NEVER activate policy. */
    fun initializeNew(binding:WorldPackRuleBinding,definitions:List<BackgroundProcessDefinition>) {
        require(db.inTransaction() && policy()==null){"P64:NEW_CAMPAIGN_BOOTSTRAP_REQUIRED"}
        val fingerprint=phase63Hash("PHASE64_V1|${binding.ruleSource}|"+definitions.sortedBy { it.uid }.joinToString { Phase64BackgroundCodec.definition(it).toString() })
        db.execSQL("INSERT INTO ${Phase64BackgroundSchema.POLICY} VALUES(?,?,?)",arrayOf(campaign,"PHASE64_V1",fingerprint))
        definitions.forEach { definition->val wire=Phase64BackgroundCodec.definition(definition).toString()
            db.execSQL("INSERT INTO ${Phase64BackgroundSchema.DEFINITIONS} VALUES(?,?,?,?,?)",arrayOf(campaign,definition.uid,definition.version,wire,phase63Hash(wire)))
            definition.parameters[Phase64ProcessActivation.ACTION_KEY]?.let { action->
                require(action.isNotBlank() && action.length<=160) {"P64:INVALID_ACTIVATION_ACTION"}
                db.execSQL("INSERT INTO ${Phase64BackgroundSchema.ACTIVATIONS} VALUES(?,?,?,?)",arrayOf(campaign,action,definition.uid,definition.version))
            }
        }
    }
}
