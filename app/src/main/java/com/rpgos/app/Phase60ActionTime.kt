package com.rpgos.app

/** World time is deliberately independent of receipt order and host wall time. */
@JvmInline
value class WorldTimeTick(val milliseconds: Long) : Comparable<WorldTimeTick> {
    override fun compareTo(other: WorldTimeTick) = milliseconds.compareTo(other.milliseconds)
    operator fun plus(duration: ActionDuration) = WorldTimeTick(Math.addExact(milliseconds, duration.milliseconds))
}

@JvmInline
value class ActionDuration(val milliseconds: Long) {
    init { require(milliseconds >= 0) { "P60:NEGATIVE_DURATION" } }
}

data class TemporalScope(
    val campaignUid: String,
    val historyGenerationUid: String,
    val baseCommitOrder: Long,
    val authoritativeFingerprint: String
) {
    init {
        require(listOf(campaignUid, historyGenerationUid, authoritativeFingerprint).none(String::isBlank))
        require(baseCommitOrder >= 0)
    }
}

/** Rules, not natural-language verbs, define the accepted estimate and its provenance. */
data class AcceptedActionTiming(
    val duration: ActionDuration,
    val ruleUid: String,
    val ruleVersion: Int,
    val instantaneous: Boolean = false
) {
    init {
        require(ruleUid.isNotBlank() && ruleVersion > 0)
        require(duration.milliseconds > 0 || instantaneous) { "P60:IMPLICIT_ZERO_TIME" }
    }
}

data class TimedActionNode(
    val uid: String,
    val ownerUid: String,
    val timing: AcceptedActionTiming,
    val after: Set<String> = emptySet(),
    /** Exclusive capacity slots, e.g. actor/movement or actor/hands. Empty permits overlap. */
    val exclusiveSlots: Set<String> = emptySet(),
    /** Starts with the anchor and must fit within its interval; not an AFTER dependency. */
    val during: String? = null
) {
    init {
        require(uid.isNotBlank() && ownerUid.isNotBlank())
        require(uid !in after && (after + exclusiveSlots).none(String::isBlank))
        require(during == null || (during.isNotBlank() && during != uid && during !in after))
    }
}

data class ScheduledActionInterval(val action: TimedActionNode, val start: WorldTimeTick, val end: WorldTimeTick)

/** Produces a DAG schedule; unrelated nodes overlap instead of charging elapsed time twice. */
object Phase60ActionPlanner {
    fun schedule(start: WorldTimeTick, nodes: List<TimedActionNode>): List<ScheduledActionInterval> {
        require(nodes.isNotEmpty() && nodes.size <= 1024) { "P60:INVALID_PLAN_SIZE" }
        val byUid = nodes.associateBy { it.uid }
        require(byUid.size == nodes.size) { "P60:DUPLICATE_ACTION" }
        require(nodes.all { byUid.keys.containsAll(it.after + listOfNotNull(it.during)) }) { "P60:UNKNOWN_DEPENDENCY" }
        val resolved = linkedMapOf<String, ScheduledActionInterval>()
        while (resolved.size < nodes.size) {
            val ready = nodes.filter { it.uid !in resolved && resolved.keys.containsAll(it.after + listOfNotNull(it.during)) }.sortedBy { it.uid }
            require(ready.isNotEmpty()) { "P60:CYCLIC_PLAN" }
            ready.forEach { node ->
                val after = node.after.map { resolved.getValue(it).end }.maxOrNull() ?: start
                val anchor = node.during?.let { resolved.getValue(it) }
                val from = anchor?.start ?: after
                require(from >= after) { "P60:DURING_DEPENDENCY_CONFLICT" }
                val end = from + node.timing.duration
                require(anchor == null || end <= anchor.end) { "P60:DURING_EXCEEDS_ANCHOR" }
                resolved[node.uid] = ScheduledActionInterval(node, from, end)
            }
        }
        val result = resolved.values.sortedWith(compareBy<ScheduledActionInterval> { it.start }.thenBy { it.action.uid })
        result.forEachIndexed { index, left ->
            result.drop(index + 1).forEach { right ->
                val overlap = left.start < right.end && right.start < left.end
                require(!overlap || left.action.exclusiveSlots.intersect(right.action.exclusiveSlots).isEmpty()) {
                    "P60:EXCLUSIVE_ACTIVITY_CONFLICT"
                }
            }
        }
        return result
    }
}

data class WorldProcessDeadline(val uid: String, val ownerUid: String, val due: WorldTimeTick) {
    init { require(uid.isNotBlank() && ownerUid.isNotBlank()) }
}

/** Owner state and candidates belong to speculative execution, never the live canonical DB. */
data class TemporalOwnerState(val ownerUid: String, val version: Int, val canonicalValue: String) {
    init { require(ownerUid.isNotBlank() && version > 0) }
}

data class TemporalOwnerInput(
    val scope: TemporalScope,
    val from: WorldTimeTick,
    val through: WorldTimeTick,
    val actions: List<ScheduledActionInterval>,
    val deadlines: List<WorldProcessDeadline>,
    val previous: TemporalOwnerState?
) {
    /** A newly starting concurrent action must not receive work for an earlier interval. */
    fun elapsedFor(action: ScheduledActionInterval): ActionDuration {
        require(action in actions)
        val start = maxOf(from.milliseconds, action.start.milliseconds)
        val end = minOf(through.milliseconds, action.end.milliseconds)
        return ActionDuration(if (end <= start) 0 else Math.subtractExact(end, start))
    }
}

sealed interface TemporalOwnerResult {
    data class Evaluated(
        val state: TemporalOwnerState,
        val changes: List<PlayerDomainChangePayload> = emptyList(),
        val nextDeadlines: List<WorldProcessDeadline> = emptyList(),
        val playerDecisionRequired: Boolean = false
    ) : TemporalOwnerResult
    data class Unsupported(val reasonUid: String) : TemporalOwnerResult
}

/** Implementations must be deterministic, pure and evaluate only the supplied time interval. */
interface WorldProcessOwnerPort {
    val ownerUid: String
    fun evaluate(input: TemporalOwnerInput): TemporalOwnerResult
}

data class TemporalExecutionCheckpoint(
    val scope: TemporalScope,
    val commandUid: String,
    val startedAt: WorldTimeTick,
    val reached: WorldTimeTick,
    val schedule: List<ScheduledActionInterval>,
    val deadlines: List<WorldProcessDeadline>,
    val ownerStates: Map<String, TemporalOwnerState> = emptyMap(),
    val candidateChanges: List<PlayerDomainChangePayload> = emptyList(),
    val evaluatedBoundaries: Long = 0,
    val evaluatedDeadlineUids: Set<String> = emptySet(),
    val terminalReason: TemporalStopReason? = null,
    val initialDeadlines: List<WorldProcessDeadline> = deadlines.toList(),
    val initialOwnerStates: Map<String, TemporalOwnerState> = ownerStates.toMap(),
    val executionFingerprint: String? = null
)

enum class TemporalStopReason { COMPLETED, PLAYER_DECISION, YIELDED, CANCELLED, STALE_HISTORY, UNSUPPORTED_OWNER, OWNER_FAILED, INVALID_OWNER_RESULT, BUDGET_EXCEEDED }

data class TemporalExecutionResult(
    val checkpoint: TemporalExecutionCheckpoint,
    val reason: TemporalStopReason,
    val diagnostic: String? = null
) {
    /** Only complete or deliberately interrupted in-world actions may reach canonical admission. */
    val readyForAdmission: Boolean get() = reason == TemporalStopReason.COMPLETED || reason == TemporalStopReason.PLAYER_DECISION
}

/**
 * Event-driven speculative time advancement. Yielding is not a turn or a commit. A caller must
 * admit the final changes, time and process states together through the existing Core boundary.
 * The monotonic host timer limits CPU work only; it never changes the world clock.
 */
class Phase60TimeProcessor(
    owners: List<WorldProcessOwnerPort>,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val ownersByUid = owners.associateBy { it.ownerUid }
    init { require(ownersByUid.size == owners.size && owners.none { it.ownerUid.isBlank() }) }

    fun begin(scope: TemporalScope, commandUid: String, start: WorldTimeTick, nodes: List<TimedActionNode>, deadlines: List<WorldProcessDeadline> = emptyList(), ownerStates: List<TemporalOwnerState> = emptyList()): TemporalExecutionCheckpoint {
        require(commandUid.isNotBlank())
        require(deadlines.all { it.due >= start } && deadlines.map { it.uid }.distinct().size == deadlines.size) { "P60:INVALID_DEADLINES" }
        require(ownerStates.map { it.ownerUid }.distinct().size == ownerStates.size)
        return TemporalExecutionCheckpoint(scope, commandUid, start, start, Phase60ActionPlanner.schedule(start, nodes),
            deadlines.sortedWith(compareBy<WorldProcessDeadline> { it.due }.thenBy { it.uid }), ownerStates.associateBy { it.ownerUid })
    }

    fun advance(
        checkpoint: TemporalExecutionCheckpoint,
        currentScope: TemporalScope,
        cancelled: () -> Boolean = { false },
        maxBoundaries: Int = 256,
        maxWallMillis: Long = 500
    ): TemporalExecutionResult {
        require(maxBoundaries in 1..256 && maxWallMillis in 1..500)
        if (checkpoint.scope != currentScope) return TemporalExecutionResult(checkpoint, TemporalStopReason.STALE_HISTORY)
        checkpoint.terminalReason?.let { return TemporalExecutionResult(checkpoint, it) }
        require(checkpoint.schedule.isNotEmpty())
        val target = checkpoint.schedule.maxOf { it.end }
        var work = checkpoint
        val startNanos = nanoTime()
        repeat(maxBoundaries) { iteration ->
            if (cancelled()) return TemporalExecutionResult(work, TemporalStopReason.CANCELLED)
            if (iteration > 0 && nanoTime() - startNanos >= maxWallMillis * 1_000_000) return TemporalExecutionResult(work, TemporalStopReason.YIELDED)
            if (work.evaluatedBoundaries >= 100_000 || work.candidateChanges.size >= 100_000) return TemporalExecutionResult(work, TemporalStopReason.BUDGET_EXCEEDED)
            val initial = work.evaluatedBoundaries == 0L
            val pendingNow = work.deadlines.any { it.due == work.reached }
            if (!initial && work.reached == target && !pendingNow) return TemporalExecutionResult(work.copy(terminalReason = TemporalStopReason.COMPLETED), TemporalStopReason.COMPLETED)
            val boundary = if (pendingNow || initial) work.reached else (
                work.schedule.flatMap { listOf(it.start, it.end) }.filter { it > work.reached } +
                    work.deadlines.map { it.due }.filter { it > work.reached && it <= target }
                ).minOrNull() ?: target
            val active = work.schedule.filter {
                (it.start <= boundary && it.end > work.reached) ||
                    (it.start == it.end && it.end == boundary && (initial || boundary > work.reached))
            }
            val due = work.deadlines.filter { it.due == boundary }.sortedBy { it.uid }
            // Every registered owner observes elapsed time, even without a foreground action.
            val requiredOwners = (ownersByUid.keys + work.ownerStates.keys + active.map { it.action.ownerUid } + due.map { it.ownerUid }).distinct().sorted()
            val states = work.ownerStates.toMutableMap()
            val changes = mutableListOf<PlayerDomainChangePayload>()
            val next = mutableListOf<WorldProcessDeadline>()
            var decision = false
            for (uid in requiredOwners) {
                val owner = ownersByUid[uid] ?: return TemporalExecutionResult(work, TemporalStopReason.UNSUPPORTED_OWNER, uid)
                val output = try {
                    owner.evaluate(TemporalOwnerInput(work.scope, work.reached, boundary, active.filter { it.action.ownerUid == uid }, due.filter { it.ownerUid == uid }, work.ownerStates[uid]))
                } catch (cancel: java.util.concurrent.CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    return TemporalExecutionResult(work, TemporalStopReason.OWNER_FAILED, uid)
                }
                when (output) {
                    is TemporalOwnerResult.Unsupported -> return TemporalExecutionResult(work, TemporalStopReason.UNSUPPORTED_OWNER, output.reasonUid)
                    is TemporalOwnerResult.Evaluated -> {
                        if (output.state.ownerUid != uid || output.nextDeadlines.any { it.ownerUid != uid || it.due <= boundary } ||
                            output.changes.any { it is TemporalStateChange })
                            return TemporalExecutionResult(work, TemporalStopReason.INVALID_OWNER_RESULT, uid)
                        states[uid] = output.state
                        changes += output.changes
                        next += output.nextDeadlines
                        decision = decision || output.playerDecisionRequired
                    }
                }
            }
            val evaluated = work.evaluatedDeadlineUids + due.map { it.uid }
            val remaining = (work.deadlines.filter { it.due != boundary } + next).sortedWith(compareBy<WorldProcessDeadline> { it.due }.thenBy { it.uid })
            if (remaining.size > 100_000 || changes.size > 100_000 - work.candidateChanges.size)
                return TemporalExecutionResult(work, TemporalStopReason.BUDGET_EXCEEDED)
            if (remaining.map { it.uid }.distinct().size != remaining.size || remaining.any { it.uid in evaluated })
                return TemporalExecutionResult(work, TemporalStopReason.INVALID_OWNER_RESULT, "P60:REUSED_DEADLINE")
            work = work.copy(reached = boundary, deadlines = remaining, ownerStates = states.toMap(), candidateChanges = work.candidateChanges + changes,
                evaluatedBoundaries = work.evaluatedBoundaries + 1, evaluatedDeadlineUids = evaluated)
            if (decision) return TemporalExecutionResult(work.copy(terminalReason = TemporalStopReason.PLAYER_DECISION), TemporalStopReason.PLAYER_DECISION)
        }
        val reason = if (work.reached == target && work.deadlines.none { it.due <= target }) TemporalStopReason.COMPLETED else TemporalStopReason.YIELDED
        return TemporalExecutionResult(if (reason == TemporalStopReason.COMPLETED) work.copy(terminalReason = reason) else work, reason)
    }
}
