package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class PlayerCommandContractTest {
    private val registry = PlayerCommandKindRegistry.core()
    private val actor = CommandActorRef("CHARACTER", "ACTOR-1")
    private val provenance = CommandProvenance("PLAYER_UI", "REQUEST-1", "phase16-test")

    private fun train(uid: String = "CMD-1", campaign: String = "C", effort: Long = 10) = PlayerCommand(
        commandUid = uid,
        campaignUid = campaign,
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STRENGTH"), effort, "METHOD"),
        provenance = provenance,
        causationUid = "CAUSE-1",
        correlationUid = "CORR-1",
        requestedEffectiveOrder = 42,
        preconditions = listOf(ExpectedRecordVersion(DomainRef("PLAYER", "ACTOR-1"), 3)),
        extensions = listOf(NamespacedTextCommandExtension("TEST:EXT", 1, "typed"))
    )

    @Test fun cmdSem01_sameUidExactCommandHasExactSemanticIdentity() {
        val a = train(); val b = train()
        assertEquals(registry.fingerprint(a), registry.fingerprint(b))
        assertEquals(CommandIdentityRelation.SAME_LOGICAL_COMMAND, PlayerCommandIdentity.compare(a, b, registry))
    }

    @Test fun cmdSem02_sameUidChangedPayloadIsIdentityConflict() {
        try { PlayerCommandIdentity.compare(train(), train(effort = 11), registry); fail("expected COMMAND_IDENTITY_CONFLICT") }
        catch (e: CommandIdentityConflictException) { assertEquals("COMMAND_IDENTITY_CONFLICT", e.message) }
    }

    @Test fun cmdSem03_campaignAndActorIdentityAreStructuralAndStable() {
        assertEquals(CommandIdentityRelation.DISTINCT_COMMAND, PlayerCommandIdentity.compare(train(campaign = "C"), train(campaign = "D"), registry))
        fails("INVALID_ACTOR_REF") { registry.validate(train().copy(actor = CommandActorRef("", "ACTOR-1"))) }
        fails("EMPTY_CAMPAIGN_UID") { registry.validate(train().copy(campaignUid = "")) }
        try { PlayerCommandIdentity.compare(train(), train().copy(actor = CommandActorRef("NPC", "ACTOR-1")), registry); fail("expected conflict") }
        catch (e: CommandIdentityConflictException) { assertEquals("COMMAND_IDENTITY_CONFLICT", e.message) }
    }

    @Test fun cmdSem04_typedPayloadMismatchRejected() {
        val bad = PlayerCommand(
            commandUid = "BAD-TYPE",
            campaignUid = "C",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = LearnSkillCommandPayload("SKILL-1"),
            provenance = provenance
        )
        fails("COMMAND_PAYLOAD_TYPE_MISMATCH") { registry.validate(bad) }
        fails("INVALID_EFFORT_UNITS") { registry.validate(train(effort = 0)) }
    }

    @Test fun cmdSem05_unknownCommandKindRejectedWithoutFallback() {
        fails("UNKNOWN_COMMAND_KIND") { registry.validate(train().copy(commandKindUid = "WORLD:UNKNOWN")) }
        fails("UNKNOWN_COMMAND_KIND") { registry.decode(registry.encode(train()).replace(PlayerCommandKinds.TRAIN, "WORLD:UNKNOWN")) }
    }

    @Test fun cmdSem06_invalidRefShapeRejectedButExistenceIsNotChecked() {
        fails("INVALID_FOCUS_REF") { registry.validate(train().copy(payload = TrainCommandPayload(DomainRef("STAT", ""), 1))) }
        val ghost = train().copy(payload = TrainCommandPayload(DomainRef("STAT", "GHOST-NOT-IN-DB"), 1))
        registry.validate(ghost)
        assertEquals("GHOST-NOT-IN-DB", ghost.payload.focus.uid)
    }

    @Test fun cmdSem07_rawStatePatchSqlAuthorityImpossibleByContract() {
        val payloadTypes = listOf(TrainCommandPayload::class.java, TransferFundsCommandPayload::class.java, StartProjectCommandPayload::class.java, RecordProjectWorkCommandPayload::class.java, ChangeProjectLifecycleCommandPayload::class.java)
        val forbidden = setOf("table", "column", "sql", "operation", "statePatch", "patchOperation", "arbitraryValue")
        payloadTypes.forEach { type ->
            val names = type.declaredFields.map { it.name }.toSet()
            assertTrue("raw mutation field leaked by ${type.simpleName}: $names", names.intersect(forbidden).isEmpty())
        }
        assertFalse(PlayerCommandKinds::class.java.declaredFields.any { it.name.contains("STATE_PATCH") })
    }

    @Test fun cmdSem08_serializationRoundTripAndFingerprintAreDeterministic() {
        val original = train(); val encoded1 = registry.encode(original); val encoded2 = registry.encode(original)
        assertEquals(encoded1, encoded2)
        val decoded = registry.decode(encoded1)
        assertEquals(encoded1, registry.encode(decoded))
        assertEquals(registry.fingerprint(original), registry.fingerprint(decoded))
        assertEquals(CommandIdentityRelation.SAME_LOGICAL_COMMAND, PlayerCommandIdentity.compare(original, decoded, registry))
        fails("UNSUPPORTED_COMMAND_SCHEMA_VERSION") { registry.decode(encoded1.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":99")) }
    }

    @Test fun cmdSem09_developmentProjectCommandsCarryIntentOnly() {
        val projectPayloads: List<PlayerCommandPayload> = listOf(
            StartProjectCommandPayload("TYPE", "Title", "Objective", targetDomainUid = "RESEARCH"),
            RecordProjectWorkCommandPayload("P", "EXPERIMENT", 5, "METHOD", listOf(DomainRef("EVIDENCE", "E1"))),
            SatisfyProjectRequirementCommandPayload("P", "REQ", listOf(DomainRef("EVIDENCE", "E2"))),
            AchieveProjectMilestoneCommandPayload("P", "M", listOf(DomainRef("EVIDENCE", "E3")), DomainRef("PROJECT_WORK", "W")),
            ChangeProjectLifecycleCommandPayload("P", "READY_TO_COMPLETE"),
            CompleteProjectCommandPayload("P", listOf(DomainRef("EVIDENCE", "E4"))),
            CancelProjectCommandPayload("P", reasonUid = "USER_CANCEL")
        )
        val forbidden = listOf("progressDelta", "resultKind", "milestoneAchieved", "satisfied", "finalBalance", "finalMastery", "finalOwnership", "outcomeUid", "committedOrder")
        projectPayloads.forEach { payload ->
            val fields = payload.javaClass.declaredFields.map { it.name }
            forbidden.forEach { name -> assertFalse("${payload.javaClass.simpleName} leaked $name", fields.any { it.contains(name, true) }) }
        }
    }

    @Test fun cmdSem10_constructValidateSerializeDoesNotMutateAuthoritativeState() {
        val db = SQLiteDatabase.create(null)
        try {
            CurrentSchema.ensure(db, "C")
            val before = counts(db)
            val cmd = PlayerCommand(
                commandUid = "NO-MUTATION", campaignUid = "C", actor = CommandActorRef("CHARACTER", "NONEXISTENT-ACTOR"),
                commandKindUid = PlayerCommandKinds.START_PROJECT,
                payload = StartProjectCommandPayload("UNKNOWN-TYPE", "Intent", "No domain mutation", targetDomainUid = "RESEARCH"),
                provenance = provenance
            )
            registry.validate(cmd); registry.decode(registry.encode(cmd)); registry.fingerprint(cmd)
            assertEquals(before, counts(db))
        } finally { db.close() }
    }

    @Test fun cmdSem11_commandUidIsSeparateFromDomainEventTransactionIdentities() {
        val finance = PlayerCommand(
            commandUid = "CMD-FIN-1", campaignUid = "C", actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("ACCOUNT-A", "ACCOUNT-B", 100, "CUR"),
            provenance = CommandProvenance("EVENT", "EVENT-77"), causationUid = "EVENT-77", correlationUid = "TURN-GROUP-9"
        )
        registry.validate(finance)
        assertNotEquals(finance.commandUid, finance.payload.fromAccountUid)
        assertNotEquals(finance.commandUid, finance.provenance.sourceUid)
        assertNotEquals(finance.commandUid, finance.causationUid)
        assertNotEquals(finance.commandUid, finance.correlationUid)
    }

    @Test fun cmdSem12_preconditionsAreTypedOptimisticExpectationsOnly() {
        val preconditions: List<CommandPrecondition> = listOf(ExpectedRecordVersion(DomainRef("PROJECT", "P"), 7), ExpectedLifecycleState(DomainRef("PROJECT", "P"), "ACTIVE_WORK"))
        val cmd = train().copy(preconditions = preconditions)
        registry.validate(cmd)
        assertEquals(preconditions, registry.decode(registry.encode(cmd)).preconditions)
        preconditions.forEach { p ->
            val names = p.javaClass.declaredFields.map { it.name }
            assertFalse(names.any { it.equals("table", true) || it.equals("column", true) || it.equals("value", true) })
        }
    }

    @Test fun deterministicFingerprintIsSafeForParallelTransientUse() {
        val command = train(); val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..64).map { pool.submit(Callable { registry.fingerprint(command) }) }
            assertEquals(1, futures.map { it.get() }.toSet().size)
        } finally { pool.shutdownNow() }
    }

    private fun counts(db: SQLiteDatabase): List<Long> = listOf(
        "campaign_truth_records", "player_stats", "player_skills_v2", "player_techniques_v2", "item_instances",
        "financial_ledger_transactions", "asset_records", "development_projects", "project_work_records"
    ).map { table -> db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) } }

    private fun fails(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") }
        catch (e: PlayerCommandStructuralException) { assertEquals(code, e.code) }
    }
}
