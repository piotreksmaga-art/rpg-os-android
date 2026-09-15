package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

internal const val NPC_BRAIN_CHANGE_KIND="RPGOS-CHANGE:NPC_BRAIN"

internal object NpcBrainRules {
    val GENESIS=NpcBrainRule("P61:GENESIS",1,NpcBrainTransitionKind.INITIALIZE)
    val APPRAISAL=NpcBrainRule("P61:APPRAISAL",1,NpcBrainTransitionKind.APPRAISAL)
    val PLANNING=NpcBrainRule("P61:PLANNING",1,NpcBrainTransitionKind.GOALS_AND_PLANS)
    val ADAPTATION=NpcBrainRule("P61:ADAPTATION",1,NpcBrainTransitionKind.PERSONALITY_ADAPTATION,250)
    val EXECUTION_COMPLETION=NpcBrainRule("P61:EXECUTION_COMPLETION",1,NpcBrainTransitionKind.GOALS_AND_PLANS)
    fun resolve(uid:String,version:Int)=listOf(GENESIS,APPRAISAL,PLANNING,ADAPTATION,EXECUTION_COMPLETION).singleOrNull{it.uid==uid && it.version==version}
        ?:error("P61:UNREGISTERED_BRAIN_RULE")
}

/** Not an AI response type. A Core owner prepares it before ordinary sealed admission. */
data class NpcBrainChange(
    val campaignUid:String,val actor:DomainRef,val historyGenerationUid:String,val expectedVersion:Long,
    val beforeFingerprint:String?,val stateCanonical:String,val ruleUid:String,val ruleVersion:Int,
    val causes:List<NpcCauseRef>
):PlayerDomainChangePayload {
    init {
        npcUid(campaignUid);npcUid(historyGenerationUid);npcUid(actor.kindUid);npcUid(actor.uid)
        require(expectedVersion in 0 until Long.MAX_VALUE && (beforeFingerprint==null)==(expectedVersion==0L))
        require(beforeFingerprint==null || beforeFingerprint.matches(Regex("[0-9a-f]{64}")))
        val state=NpcBrainCodec.decode(stateCanonical)
        require(NpcBrainCodec.encode(state)==stateCanonical && state.campaignUid==campaignUid && state.actor==actor && state.revision==expectedVersion+1)
        NpcBrainRules.resolve(ruleUid,ruleVersion)
        require(causes.isNotEmpty() && causes.size<=64 && causes.distinct().size==causes.size)
    }
}

internal fun npcBrainChangeCodec()=object:TypedPlayerChangeCodec<NpcBrainChange>(NpcBrainChange::class,
    ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,setOf("campaign","actor","history","expectedVersion","before","state","rule","ruleVersion","causes")) {
    override fun encode(payload:NpcBrainChange)=buildJsonObject {
        put("campaign",payload.campaignUid);put("actor",NpcBrainCodec.ref(payload.actor));put("history",payload.historyGenerationUid)
        put("expectedVersion",payload.expectedVersion);put("before",payload.beforeFingerprint?.let(::JsonPrimitive)?:JsonNull)
        put("state",payload.stateCanonical);put("rule",payload.ruleUid);put("ruleVersion",payload.ruleVersion)
        put("causes",JsonArray(payload.causes.sortedWith(compareBy<NpcCauseRef>{it.kind.name}.thenBy{it.uid}).map(NpcBrainCodec::cause)))
    }
    override fun decodeKnownFields(obj:JsonObject)=NpcBrainChange(
        NpcBrainCodec.text(obj,"campaign"),NpcBrainCodec.readRef(obj.getValue("actor")),NpcBrainCodec.text(obj,"history"),
        NpcBrainCodec.number(obj,"expectedVersion"),obj.getValue("before").takeUnless{it==JsonNull}?.let{NpcBrainCodec.text(obj,"before")},
        NpcBrainCodec.text(obj,"state"),NpcBrainCodec.text(obj,"rule"),NpcBrainCodec.integer(obj,"ruleVersion"),
        obj.getValue("causes").jsonArray.also{require(it.size<=64)}.map(NpcBrainCodec::readCause))
    override fun conflictKeys(payload:NpcBrainChange)=setOf("P61:${payload.campaignUid}:${payload.actor.kindUid}:${payload.actor.uid}:${payload.expectedVersion}")
}

/** Distinct revisions may coexist, but only as one ordered, fingerprint-linked chain per actor. */
internal fun validNpcBrainChains(changes:List<NpcBrainChange>):Boolean=changes.size<=128 &&
    changes.groupBy{it.campaignUid to it.actor}.values.all { chain -> chain.zipWithNext().all{(before,after)->
        after.expectedVersion==before.expectedVersion+1 && after.historyGenerationUid==before.historyGenerationUid &&
            after.beforeFingerprint==phase60Hash(before.stateCanonical)
    } }

internal object Phase61NpcSchema {
    const val VERSION=1
    const val STATES="phase61_npc_brains"
    const val HISTORY="phase61_npc_brain_history"
    val authoritativeTables=setOf(STATES,HISTORY)
    fun ensureReady(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $STATES(
            campaign_uid TEXT NOT NULL,actor_kind_uid TEXT NOT NULL,actor_uid TEXT NOT NULL,
            state_version INTEGER NOT NULL CHECK(state_version>0),state_canonical TEXT NOT NULL,
            fingerprint TEXT NOT NULL,transaction_uid TEXT NOT NULL,
            PRIMARY KEY(campaign_uid,actor_kind_uid,actor_uid))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $HISTORY(
            campaign_uid TEXT NOT NULL,actor_kind_uid TEXT NOT NULL,actor_uid TEXT NOT NULL,
            state_version INTEGER NOT NULL,change_uid TEXT NOT NULL,transaction_uid TEXT NOT NULL,
            before_fingerprint TEXT,after_fingerprint TEXT NOT NULL,change_canonical TEXT NOT NULL,
            PRIMARY KEY(campaign_uid,actor_kind_uid,actor_uid,state_version),UNIQUE(campaign_uid,change_uid))""")
    }
    fun isReady(db:SQLiteDatabase):Boolean {
        fun columns(table:String)=db.rawQuery("PRAGMA table_info($table)",null).use{c->buildSet{while(c.moveToNext())add(c.getString(1))}}
        return columns(STATES)==setOf("campaign_uid","actor_kind_uid","actor_uid","state_version","state_canonical","fingerprint","transaction_uid") &&
            columns(HISTORY)==setOf("campaign_uid","actor_kind_uid","actor_uid","state_version","change_uid","transaction_uid","before_fingerprint","after_fingerprint","change_canonical")
    }
}

/** Infrastructure-only read. Presentation/model callers must use an actor-scoped Phase38 projection. */
internal class NpcBrainStore(private val db:SQLiteDatabase,private val campaignUid:String) {
    init { npcUid(campaignUid) }
    fun read(actor:DomainRef):NpcBrainState?=db.rawQuery(
        "SELECT state_version,state_canonical,fingerprint FROM ${Phase61NpcSchema.STATES} WHERE campaign_uid=? AND actor_kind_uid=? AND actor_uid=?",
        arrayOf(campaignUid,actor.kindUid,actor.uid)).use{c->
        if(!c.moveToFirst())return@use null
        NpcBrainCodec.decode(c.getString(1)).also{state->
            check(state.campaignUid==campaignUid && state.actor==actor && state.revision==c.getLong(0) &&
                NpcBrainCodec.fingerprint(state)==c.getString(2)) { "P61:CORRUPT_BRAIN" }
        }
    }
    fun apply(identity:TurnTransactionIdentity,changeUid:String,change:NpcBrainChange) {
        require(db.inTransaction() && identity.campaignUid==campaignUid && change.campaignUid==campaignUid) { "P61:TURN_TRANSACTION_REQUIRED" }
        // History generation is a live admission guard in TurnTransaction, not replayed world
        // state. Revision, provenance and authoritative digest remain checked during replay.
        require(ActivePlayerStore(db,campaignUid).active()?.playerUid!=change.actor.uid) { "P61:ACTIVE_PLAYER_CONTROL_FORBIDDEN" }
        val before=read(change.actor)
        require((before?.revision?:0)==change.expectedVersion && before?.let(NpcBrainCodec::fingerprint)==change.beforeFingerprint) { "P61:STALE_BRAIN" }
        val after=NpcBrainCodec.decode(change.stateCanonical)
        val rule=NpcBrainRules.resolve(change.ruleUid,change.ruleVersion)
        NpcBrainOwner.validateTransition(before,after,rule,change.causes)
        var newestAcquisitionOrder=-1L
        change.causes.forEach { cause ->
            val present=when(cause.kind) {
                NpcCauseKind.GENESIS->before==null && cause.uid=="P61:GENESIS:${after.seedFingerprint}"
                NpcCauseKind.ACCEPTED_ACTION->cause.uid==identity.commandUid && rule.allowed==NpcBrainTransitionKind.GOALS_AND_PLANS
                NpcCauseKind.INTRINSIC_MOTIVATION->rule.allowed==NpcBrainTransitionKind.GOALS_AND_PLANS &&
                    before?.motivations?.any{it.uid==cause.uid}==true && after.goals.filter{it.cause==cause}.all{it.motivationUid==cause.uid}
                NpcCauseKind.KNOWLEDGE_ACQUISITION->db.rawQuery(
                    "SELECT created_order FROM world_actor_knowledge_acquisitions WHERE campaign_uid=? AND acquisition_uid=? AND holder_kind_uid=? AND holder_uid=? AND provenance_status='RECORDED'",
                    arrayOf(campaignUid,cause.uid,after.knowledgeHolder.holderKindUid,after.knowledgeHolder.holderUid)).use{
                        if(!it.moveToFirst())false else { newestAcquisitionOrder=maxOf(newestAcquisitionOrder,it.getLong(0));true }
                    }
                // Event existence alone is not evidence that the actor perceived it.
                NpcCauseKind.COMMITTED_EVENT->false
            }
            require(present) { "P61:CAUSE_NOT_AUTHORIZED" }
        }
        if(after.lastAppraisedAcquisitionOrder>(before?.lastAppraisedAcquisitionOrder?:-1))
            require(after.lastAppraisedAcquisitionOrder==newestAcquisitionOrder) { "P61:APPRAISAL_WATERMARK_UNPROVEN" }
        val fingerprint=NpcBrainCodec.fingerprint(after)
        if(before==null)db.execSQL("INSERT INTO ${Phase61NpcSchema.STATES} VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any>(campaignUid,after.actor.kindUid,after.actor.uid,after.revision,change.stateCanonical,fingerprint,identity.transactionUid))
        else db.execSQL("UPDATE ${Phase61NpcSchema.STATES} SET state_version=?,state_canonical=?,fingerprint=?,transaction_uid=? WHERE campaign_uid=? AND actor_kind_uid=? AND actor_uid=?",
            arrayOf<Any>(after.revision,change.stateCanonical,fingerprint,identity.transactionUid,campaignUid,after.actor.kindUid,after.actor.uid))
        db.execSQL("INSERT INTO ${Phase61NpcSchema.HISTORY} VALUES(?,?,?,?,?,?,?,?,?)",arrayOf<Any?>(campaignUid,after.actor.kindUid,after.actor.uid,
            after.revision,changeUid,identity.transactionUid,change.beforeFingerprint,fingerprint,npcBrainChangeCodec().encode(change).toString()))
    }
}
