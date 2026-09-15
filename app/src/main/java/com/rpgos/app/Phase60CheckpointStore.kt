package com.rpgos.app

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Versioned local CACHE/REBUILDABLE codec. Restored candidates still require Core admission. */
internal object Phase60CheckpointCodec {
    private val registry get() = TypedPlayerChangeRegistry.core()
    fun encode(work: TemporalExecutionCheckpoint): String = buildJsonObject {
        put("version", 3)
        put("executionFingerprint", work.executionFingerprint?.let(::JsonPrimitive) ?: JsonNull)
        put("campaign", work.scope.campaignUid); put("generation", work.scope.historyGenerationUid)
        put("commit", work.scope.baseCommitOrder); put("digest", work.scope.authoritativeFingerprint)
        put("command", work.commandUid); put("start", work.startedAt.milliseconds); put("reached", work.reached.milliseconds)
        put("boundaries", work.evaluatedBoundaries)
        put("terminal", work.terminalReason?.let { JsonPrimitive(it.name) } ?: JsonNull)
        put("schedule", JsonArray(work.schedule.map { interval -> buildJsonObject {
            val node = interval.action
            put("uid", node.uid); put("owner", node.ownerUid); put("duration", node.timing.duration.milliseconds)
            put("rule", node.timing.ruleUid); put("ruleVersion", node.timing.ruleVersion); put("instant", node.timing.instantaneous)
            put("after", strings(node.after)); put("slots", strings(node.exclusiveSlots))
            put("during", node.during?.let(::JsonPrimitive) ?: JsonNull)
            put("start", interval.start.milliseconds); put("end", interval.end.milliseconds)
        } }))
        put("deadlines", Json.parseToJsonElement(Phase60DeadlineCodec.encode(work.deadlines)))
        put("initialDeadlines", Json.parseToJsonElement(Phase60DeadlineCodec.encode(work.initialDeadlines)))
        put("owners", Json.parseToJsonElement(Phase60ProcessStateCodec.encode(work.ownerStates.values.toList())))
        put("initialOwners", Json.parseToJsonElement(Phase60ProcessStateCodec.encode(work.initialOwnerStates.values.toList())))
        put("evaluated", strings(work.evaluatedDeadlineUids))
        put("changes", JsonArray(work.candidateChanges.map(registry::encodeWorkerPayload)))
        put("effects",TemporalMechanicsCodec.encode(work.candidateEffects))
    }.toString()

    fun decode(text: String): TemporalExecutionCheckpoint {
        val obj = Json.parseToJsonElement(text).jsonObject
        val version = number(obj,"version")
        require(version in 1L..3L)
        val extra= (if(version>=2L)setOf("executionFingerprint") else emptySet()) + (if(version>=3L)setOf("effects") else emptySet())
        require(obj.keys == setOf("version", "campaign", "generation", "commit", "digest", "command", "start", "reached", "boundaries", "terminal", "schedule", "deadlines", "initialDeadlines", "owners", "initialOwners", "evaluated", "changes") + extra)
        val scope = TemporalScope(string(obj, "campaign"), string(obj, "generation"), number(obj, "commit"), string(obj, "digest"))
        val rows = obj.getValue("schedule").jsonArray
        require(rows.size in 1..1024)
        val schedule = rows.map { value ->
            val row = value.jsonObject
            require(row.keys == setOf("uid", "owner", "duration", "rule", "ruleVersion", "instant", "after", "slots", "during", "start", "end"))
            val instant = row.getValue("instant").jsonPrimitive
            require(!instant.isString)
            val ruleVersion = number(row, "ruleVersion"); require(ruleVersion in 1..Int.MAX_VALUE)
            val action = TimedActionNode(string(row, "uid"), string(row, "owner"),
                AcceptedActionTiming(ActionDuration(number(row, "duration")), string(row, "rule"), ruleVersion.toInt(), instant.boolean),
                stringSet(row.getValue("after")), stringSet(row.getValue("slots")),
                row.getValue("during").let { if (it == JsonNull) null else string(row, "during") })
            ScheduledActionInterval(action, WorldTimeTick(number(row, "start")), WorldTimeTick(number(row, "end")))
        }
        val start = WorldTimeTick(number(obj, "start"))
        require(Phase60ActionPlanner.schedule(start, schedule.map { it.action }) == schedule) { "P60:CHECKPOINT_SCHEDULE_MISMATCH" }
        val reached = WorldTimeTick(number(obj, "reached")); require(reached >= start && reached <= schedule.maxOf { it.end })
        val boundaries = number(obj, "boundaries"); require(boundaries in 0..100_000)
        val terminal = obj.getValue("terminal").let { if (it == JsonNull) null else TemporalStopReason.valueOf(string(obj, "terminal")) }
        require(terminal == null || terminal in setOf(TemporalStopReason.COMPLETED, TemporalStopReason.PLAYER_DECISION))
        if (terminal == TemporalStopReason.COMPLETED) require(reached == schedule.maxOf { it.end })
        val deadlines = Phase60DeadlineCodec.decode(obj.getValue("deadlines").toString())
        require(deadlines.all { it.due >= reached })
        if (terminal != null) require(deadlines.all { it.due > reached })
        val evaluated = stringSet(obj.getValue("evaluated")); require(deadlines.none { it.uid in evaluated })
        val changes = obj.getValue("changes").jsonArray; require(changes.size <= 100_000)
        val initialDeadlines = Phase60DeadlineCodec.decode(obj.getValue("initialDeadlines").toString())
        require(initialDeadlines.all { it.due >= start })
        val command = string(obj, "command"); require(command.isNotBlank())
        return TemporalExecutionCheckpoint(scope, command, start, reached, schedule, deadlines,
            Phase60ProcessStateCodec.decode(obj.getValue("owners").toString()).associateBy { it.ownerUid },
            changes.map { registry.decodeWorkerPayload(it.jsonObject) }, boundaries, evaluated, terminal, initialDeadlines,
            Phase60ProcessStateCodec.decode(obj.getValue("initialOwners").toString()).associateBy { it.ownerUid },
            obj["executionFingerprint"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.let { require(it.isString && it.content.length==64); it.content },
            obj["effects"]?.let(TemporalMechanicsCodec::decode)?:emptyList())
    }
    private fun strings(values: Set<String>) = JsonArray(values.sorted().map(::JsonPrimitive))
    private fun stringSet(value: JsonElement): Set<String> {
        val rows = value.jsonArray; require(rows.size <= 100_000)
        val strings = rows.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content.also { s -> require(s.isNotBlank()) } }
        require(strings.distinct().size == strings.size)
        return strings.toSet()
    }
    private fun string(obj: JsonObject, key: String) = obj.getValue(key).jsonPrimitive.let { require(it.isString); it.content }
    private fun number(obj: JsonObject, key: String) = obj.getValue(key).jsonPrimitive.let { require(!it.isString); it.long }
}

/**
 * Directory must be under private app cache/noBackup storage, outside campaign snapshot/export
 * directories. UID-derived filenames are hashed, so campaign or command text cannot escape it.
 * AtomicFile restores the previous complete checkpoint after interrupted publication.
 */
internal class FileTemporalCheckpointStore(private val directory: File) : TemporalCheckpointPort {
    @Volatile var lastFailureUid: String? = null
        private set
    override fun save(checkpoint: TemporalExecutionCheckpoint): Unit = CampaignRuntimeLifecycleLock.withTurn(checkpoint.scope.campaignUid) {
        val payload = Phase60CheckpointCodec.encode(checkpoint)
        // Validate the exact serialized state before replacing any previously recoverable work.
        Phase60CheckpointCodec.decode(payload)
        val encoded = buildJsonObject { put("sha256", hash(payload)); put("payload", payload) }.toString().toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_BYTES) { "P60:CHECKPOINT_TOO_LARGE" }
        check(directory.isDirectory || directory.mkdirs()) { "P60:CHECKPOINT_DIRECTORY_UNAVAILABLE" }
        val file = file(checkpoint.scope.campaignUid, checkpoint.commandUid)
        val stream = file.startWrite()
        try { stream.write(encoded); file.finishWrite(stream); lastFailureUid = null }
        catch (failure: Exception) { file.failWrite(stream); lastFailureUid = "P60:CHECKPOINT_WRITE_FAILED"; throw failure }
    }
    override fun load(campaignUid: String, commandUid: String): TemporalExecutionCheckpoint? = CampaignRuntimeLifecycleLock.withTurn(campaignUid) {
        val file = file(campaignUid, commandUid)
        try {
            val text = file.openRead().use { input ->
                val bytes = input.readBytesBounded(MAX_BYTES)
                bytes.toString(Charsets.UTF_8)
            }
            val envelope = Json.parseToJsonElement(text).jsonObject
            require(envelope.keys == setOf("sha256", "payload"))
            require(envelope.getValue("payload").jsonPrimitive.isString && envelope.getValue("sha256").jsonPrimitive.isString)
            val payload = envelope.getValue("payload").jsonPrimitive.content
            require(hash(payload) == envelope.getValue("sha256").jsonPrimitive.content)
            Phase60CheckpointCodec.decode(payload).also {
                require(it.scope.campaignUid == campaignUid && it.commandUid == commandUid)
                lastFailureUid = null
            }
        } catch (_: java.io.FileNotFoundException) { lastFailureUid = null; null }
        catch (_: Exception) { lastFailureUid = "P60:CHECKPOINT_INVALID"; file.delete(); null }
    }
    override fun remove(campaignUid: String, commandUid: String): Unit = CampaignRuntimeLifecycleLock.withTurn(campaignUid) { file(campaignUid, commandUid).delete() }
    fun clearCampaign(campaignUid:String):Unit = CampaignRuntimeLifecycleLock.withTurn(campaignUid) {
        require(campaignUid.isNotBlank())
        val prefix=hash(campaignUid)+"."
        directory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith(prefix) }.forEach { candidate ->
            val base=candidate.name.removeSuffix(".bak").removeSuffix(".new")
            if(base.matches(Regex("[0-9a-f]{64}\\.[0-9a-f]{64}\\.checkpoint"))) AtomicFile(File(directory,base)).delete()
        }
    }
    private fun file(campaignUid: String, commandUid: String): AtomicFile {
        require(campaignUid.isNotBlank() && commandUid.isNotBlank())
        return AtomicFile(File(directory, hash(campaignUid)+"."+hash(commandUid)+".checkpoint"))
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer); if (count < 0) break
            require(output.size() <= limit - count) { "P60:CHECKPOINT_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    companion object { private const val MAX_BYTES = 16 * 1024 * 1024 }
}
