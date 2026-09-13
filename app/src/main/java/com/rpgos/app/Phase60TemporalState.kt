package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

internal const val PHASE60_TIME_CHANGE_KIND = "RPGOS-CHANGE:TEMPORAL_STATE"

/** A complete temporal-state replacement, admitted alongside the domain changes of one turn. */
data class TemporalStateChange(
    val campaignUid: String,
    val expectedVersion: Long,
    val expectedTime: WorldTimeTick,
    val proposedTime: WorldTimeTick,
    val processStatesCanonical: String,
    val deadlinesCanonical: String = "[]",
    val stopReason: String? = null,
    val actionExecutionsCanonical:String = "[]"
) : PlayerDomainChangePayload {
    init {
        require(campaignUid.isNotBlank())
        require(stopReason == null || stopReason in setOf("COMPLETED","PLAYER_DECISION"))
        require(expectedVersion >= 0 && expectedVersion < Long.MAX_VALUE)
        require(proposedTime >= expectedTime) { "P60:BACKWARD_GAMEPLAY_TIME" }
        val executions=Phase60ExecutionReport.decode(actionExecutionsCanonical,expectedTime,proposedTime)
        if(stopReason=="COMPLETED")require(executions.all{it.completion==TemporalActionCompletion.COMPLETED})
        require(processStatesCanonical.length <= 1_048_576) { "P60:PROCESS_STATE_TOO_LARGE" }
        val states = Phase60ProcessStateCodec.decode(processStatesCanonical)
        require(Phase60ProcessStateCodec.encode(states) == processStatesCanonical) { "P60:NONCANONICAL_PROCESS_STATE" }
        val deadlines = Phase60DeadlineCodec.decode(deadlinesCanonical)
        require(deadlines.all { it.due > proposedTime }) { "P60:UNPROCESSED_DEADLINE" }
        require(Phase60DeadlineCodec.encode(deadlines) == deadlinesCanonical) { "P60:NONCANONICAL_DEADLINES" }
    }
}

internal object Phase60DeadlineCodec {
    fun encode(deadlines: List<WorldProcessDeadline>): String {
        require(deadlines.size <= 100_000 && deadlines.map { it.uid }.distinct().size == deadlines.size)
        return JsonArray(deadlines.sortedWith(compareBy<WorldProcessDeadline> { it.due }.thenBy { it.uid }).map { buildJsonObject {
            put("uid", it.uid); put("owner", it.ownerUid); put("due", it.due.milliseconds)
        } }).toString()
    }
    fun decode(value: String): List<WorldProcessDeadline> {
        require(value.length <= 4_194_304)
        val rows = Json.parseToJsonElement(value).jsonArray
        require(rows.size <= 100_000)
        return rows.map { row ->
            val obj = row.jsonObject
            require(obj.keys == setOf("uid", "owner", "due"))
            require(obj.getValue("uid").jsonPrimitive.isString && obj.getValue("owner").jsonPrimitive.isString && !obj.getValue("due").jsonPrimitive.isString)
            WorldProcessDeadline(obj.getValue("uid").jsonPrimitive.content, obj.getValue("owner").jsonPrimitive.content, WorldTimeTick(obj.getValue("due").jsonPrimitive.long))
        }.also { require(it.map { deadline -> deadline.uid }.distinct().size == it.size) }
    }
}

/** Stable owner ordering makes process state part of replay fingerprints, not wall-clock state. */
internal object Phase60ProcessStateCodec {
    fun encode(states: List<TemporalOwnerState>): String {
        require(states.map { it.ownerUid }.distinct().size == states.size)
        require(states.size <= 1024)
        return JsonArray(states.sortedBy { it.ownerUid }.map { state -> buildJsonObject {
            put("owner", state.ownerUid); put("version", state.version); put("value", state.canonicalValue)
        } }).toString()
    }
    fun decode(value: String): List<TemporalOwnerState> {
        require(value.length <= 1_048_576)
        val array = Json.parseToJsonElement(value).jsonArray
        require(array.size <= 1024)
        return array.map {
            val obj = it.jsonObject
            require(obj.keys == setOf("owner", "version", "value"))
            require(obj.getValue("owner").jsonPrimitive.isString && obj.getValue("value").jsonPrimitive.isString)
            require(!obj.getValue("version").jsonPrimitive.isString)
            TemporalOwnerState(obj.getValue("owner").jsonPrimitive.content, obj.getValue("version").jsonPrimitive.int, obj.getValue("value").jsonPrimitive.content)
        }.also { require(it.map { state -> state.ownerUid }.distinct().size == it.size) }
    }
}

internal fun phase60TimeChangeCodec() = object : TypedPlayerChangeCodec<TemporalStateChange>(
    TemporalStateChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
    setOf("campaignUid", "expectedVersion", "expectedTime", "proposedTime", "processStates", "deadlines", "stopReason", "actionExecutions")
) {
    override fun encode(payload: TemporalStateChange) = buildJsonObject {
        put("campaignUid", payload.campaignUid)
        put("expectedVersion", payload.expectedVersion); put("expectedTime", payload.expectedTime.milliseconds)
        put("proposedTime", payload.proposedTime.milliseconds); put("processStates", payload.processStatesCanonical)
        put("deadlines", payload.deadlinesCanonical)
        payload.stopReason?.let { put("stopReason",it) }
        if(payload.actionExecutionsCanonical!="[]")put("actionExecutions",payload.actionExecutionsCanonical)
    }
    override fun decodeKnownFields(obj: JsonObject): TemporalStateChange {
        fun number(key: String): Long = obj.getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
        val states = obj.getValue("processStates").jsonPrimitive
        require(states.isString)
        val campaign = obj.getValue("campaignUid").jsonPrimitive
        val deadlines = obj.getValue("deadlines").jsonPrimitive
        require(campaign.isString && deadlines.isString)
        val stop=obj["stopReason"]?.jsonPrimitive?.let { require(it.isString); it.content }
        val executions=obj["actionExecutions"]?.jsonPrimitive?.let{require(it.isString);it.content}?:"[]"
        return TemporalStateChange(campaign.content, number("expectedVersion"), WorldTimeTick(number("expectedTime")), WorldTimeTick(number("proposedTime")), states.content, deadlines.content,stop,executions)
    }
    override fun conflictKeys(payload: TemporalStateChange) = setOf("P60:CAMPAIGN_TEMPORAL_STATE")
}

internal object Phase60TemporalSchema {
    const val TABLE = "phase60_temporal_state"
    fun ensureReady(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $TABLE(
            campaign_uid TEXT PRIMARY KEY NOT NULL,
            state_version INTEGER NOT NULL CHECK(state_version>0),
            world_time_ms INTEGER NOT NULL,
            process_states_canonical TEXT NOT NULL,
            deadlines_canonical TEXT NOT NULL,
            transaction_uid TEXT NOT NULL
        )""")
    }
    fun isReady(db: SQLiteDatabase): Boolean = db.rawQuery("PRAGMA table_info($TABLE)", null).use { cursor ->
        val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        names == setOf("campaign_uid", "state_version", "world_time_ms", "process_states_canonical", "deadlines_canonical", "transaction_uid")
    }
}

internal data class CanonicalTemporalState(val version: Long, val time: WorldTimeTick, val processStates: List<TemporalOwnerState>, val deadlines: List<WorldProcessDeadline> = emptyList())

internal class Phase60TemporalStateStore(private val db: SQLiteDatabase, private val campaignUid: String) {
    init { require(campaignUid.isNotBlank()) }
    fun read(): CanonicalTemporalState {
        check(Phase60TemporalSchema.isReady(db)) { "P60:SCHEMA_NOT_READY" }
        db.rawQuery("SELECT state_version,world_time_ms,process_states_canonical,deadlines_canonical FROM ${Phase60TemporalSchema.TABLE} WHERE campaign_uid=?", arrayOf(campaignUid)).use {
            if (it.moveToFirst()) return CanonicalTemporalState(it.getLong(0), WorldTimeTick(it.getLong(1)), Phase60ProcessStateCodec.decode(it.getString(2)), Phase60DeadlineCodec.decode(it.getString(3)))
        }
        // Old campaigns retain their exact calendar anchor until their first temporal commit.
        // Missing calendar is an explicit error, never a guessed midnight or epoch.
        val time = db.rawQuery("SELECT absolute_day,hour,minute FROM campaign_calendar WHERE id=1", null).use {
            check(it.moveToFirst()) { "P60:CALENDAR_ANCHOR_MISSING" }
            check(!it.isNull(0) && !it.isNull(1) && !it.isNull(2)) { "P60:CALENDAR_ANCHOR_INVALID" }
            WorldCalendarReading(it.getLong(0), it.getInt(1), it.getInt(2)).toTick()
        }
        return CanonicalTemporalState(0, time, emptyList())
    }
    fun apply(identity: TurnTransactionIdentity, change: TemporalStateChange) {
        require(db.inTransaction() && identity.campaignUid == campaignUid && change.campaignUid == campaignUid) { "P60:TURN_TRANSACTION_REQUIRED" }
        val before = read()
        require(before.version == change.expectedVersion && before.time == change.expectedTime) { "P60:STALE_TEMPORAL_STATE" }
        if (before.version == 0L) {
            db.execSQL("INSERT INTO ${Phase60TemporalSchema.TABLE} VALUES(?,?,?,?,?,?)", arrayOf<Any>(campaignUid, 1L, change.proposedTime.milliseconds, change.processStatesCanonical, change.deadlinesCanonical, identity.transactionUid))
        } else {
            db.execSQL("UPDATE ${Phase60TemporalSchema.TABLE} SET state_version=?,world_time_ms=?,process_states_canonical=?,deadlines_canonical=?,transaction_uid=? WHERE campaign_uid=?",
                arrayOf<Any>(before.version + 1, change.proposedTime.milliseconds, change.processStatesCanonical, change.deadlinesCanonical, identity.transactionUid, campaignUid))
        }
    }
}
