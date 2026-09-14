package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase60TemporalCommitTest {
    private val start = WorldCalendarReading(-10, 8, 0).toTick()
    private val change get() = TemporalStateChange("C1", 0, start, start + ActionDuration(1500), "[]")
    private fun database(): SQLiteDatabase = SQLiteDatabase.create(null).also {
        it.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        it.execSQL("INSERT INTO campaign_calendar VALUES(1,-10,8,0)")
        GroupATransactionTestFixtures.setupFinance(it)
    }
    private fun proposal(): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(commandUid = "TIME-CMD", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload = ApplyVerifiedMechanicsCommandPayload("TIME-PLAN", emptyList(), change), provenance = CommandProvenance("P60-TEST"), requestedEffectiveOrder = 1)
        val refs = listOf(DomainRef("PLAYER", "P1"), DomainRef("CAMPAIGN", "C1"),
            DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A"), DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B"),
            DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR")).map { CampaignScopedDomainRef("C1", it) }.toSet()
        val admitted = CampaignMutationBoundary.resolveAndAdmit("C1", productionMechanicsPlayerDomainEngine(), command,
            PlayerResolutionContext.createUnboundGeneric("C1", actor, refs))
        assertTrue(admitted.toString(), admitted is CampaignMutationAdmission.Accepted)
        return (admitted as CampaignMutationAdmission.Accepted).proposal
    }
    private val identity = TurnTransactionIdentity("C1", "TIME-TURN", "TIME-CMD", "TIME-TX")

    @Test fun productionComponentRejectsAnotherCampaignClock() {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(commandUid = "TIME-CMD", campaignUid = "C1", actor = actor,
            commandKindUid = PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload = ApplyVerifiedMechanicsCommandPayload("TIME-PLAN", emptyList(), change.copy(campaignUid = "C2")),
            provenance = CommandProvenance("P60-TEST"), requestedEffectiveOrder = 1)
        val context = PlayerResolutionContext.createUnboundGeneric("C1", actor, emptySet())
        assertTrue(ProductionVerifiedMechanicsComponent().resolve(command, context) is PlayerResolutionComponentOutcome.Rejected)
    }

    @Test fun mechanicsCommandCodecRetainsTypedTime() {
        @Suppress("UNCHECKED_CAST")
        val codec = coreCommandCodecs().getValue(PlayerCommandKinds.APPLY_VERIFIED_MECHANICS) as TypedCommandCodec<ApplyVerifiedMechanicsCommandPayload>
        val payload = ApplyVerifiedMechanicsCommandPayload("TIME-PLAN", emptyList(), change)
        assertEquals(payload, codec.decode(codec.encode(payload)))
        val effect = VerifiedMechanicsCommandEffect("E1", "N1", "CORE", "RESOURCE_DELTA", DomainRef("PLAYER", "P1"),
            -1, mapOf("resource_uid" to "HEALTH"), "PROOF", "INPUT", "OUTPUT")
        val legacy = ApplyVerifiedMechanicsCommandPayload("TIME-PLAN", listOf(effect))
        assertFalse(codec.encode(legacy).containsKey("temporalState"))
        assertEquals(legacy, codec.decode(codec.encode(legacy)))
        val combined = legacy.copy(temporalState = change)
        assertEquals(combined, codec.decode(codec.encode(combined)))
        assertTrue(runCatching { ApplyVerifiedMechanicsCommandPayload("TIME-PLAN", emptyList()) }.isFailure)
    }

    @Test fun commitAndRetryChargeTimeExactlyOnce() = database().use { db ->
        val proposal = proposal()
        assertTrue(TurnTransactionBoundary.create(db, identity, proposal).commit() is TurnExecutionResult.Committed)
        val first = Phase60TemporalStateStore(db, "C1").read()
        assertEquals(change.proposedTime, first.time)
        assertEquals(1L, first.version)
        assertTrue(TurnTransactionBoundary.create(db, identity, proposal).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(first, Phase60TemporalStateStore(db, "C1").read())
        assertEquals(ReplayAuthorityCoverage.REPLAYABLE, CampaignReplayAuthorityMatrix.coverage("ACTION_TIME_AUTHORITY"))
    }
    @Test fun failureAfterTimeWriteRollsBackClockAndReceipt() = database().use { db ->
        val failure = TurnFailureInjector { if (it == TurnFailurePoint.AFTER_FIRST_WRITE) error("injected") }
        val accepted = proposal()
        val thrown = runCatching { TurnTransactionBoundary.create(db, identity, accepted, failure).commit() }.exceptionOrNull()
        assertEquals("injected", thrown?.message)
        assertEquals(CanonicalTemporalState(0, start, emptyList()), Phase60TemporalStateStore(db, "C1").read())
        assertNull(TurnTransactionReceiptStore(db).committedCommand("C1", "TIME-CMD"))
    }
    @Test fun payloadCodecPreservesClockAndRejectsStringNumbers() {
        val codec = phase60TimeChangeCodec()
        assertEquals(change, codec.decode(codec.encode(change)))
        val interrupted=change.copy(stopReason="PLAYER_DECISION")
        assertEquals(interrupted,codec.decode(codec.encode(interrupted)))
        val altered = kotlinx.serialization.json.JsonObject(codec.encode(change) + ("expectedVersion" to kotlinx.serialization.json.JsonPrimitive("0")))
        assertTrue(runCatching { codec.decode(altered) }.isFailure)
    }
    @Test fun addingEmptyTemporalSchemaPreservesPrePhase60Digests() = SQLiteDatabase.create(null).use { db ->
        val old = AuthoritativeStateDigest.compute(db)
        Phase60TemporalSchema.ensureReady(db)
        assertEquals(old, AuthoritativeStateDigest.compute(db))
    }

    @Test fun clockProjectionUsesCommittedOwnerWithoutExposingProcessState() = database().use { db ->
        val legacy = mapOf<String,Any?>("year_label" to "Rok 1", "era_name" to "Era", "season" to "spring", "absolute_day" to -10L, "hour" to 8, "minute" to 0)
        assertEquals(legacy, Phase60ClockProjection.project(db,"C1",legacy))
        TurnTransactionBoundary.create(db, identity, proposal()).commit()
        val before = AuthoritativeStateDigest.compute(db)
        val projected = Phase60ClockProjection.project(db,"C1",legacy)
        assertEquals(1500, projected["millisecond_of_minute"])
        assertEquals("08:00", Phase60ClockProjection.snapshot(projected).hour)
        assertFalse(projected.containsKey("process_states_canonical"))
        assertEquals(legacy, Phase60ClockProjection.project(db,"OTHER",legacy))
        assertEquals(before, AuthoritativeStateDigest.compute(db))
    }

    @Test fun smallModelCodecPreservesTimeAndFutureModality() {
        val request = AiIntentRequest("R", "C1", CommandActorRef("PLAYER", "P1"), "ćwiczę przez 20 minut", "pl")
        val intent = LocalCompactAiJsonCodec().decodeIntent("""{"steps":[{"action":"ćwiczę","kind":"TRAIN","time_scope":"WORLD","time_min_ms":1200000,"time_max_ms":1200000,"modality":"PLAN_FUTURE"}]}""", request)
        assertEquals("1200000", intent.nodes.single().semanticAction.attributes["time_min_ms"])
        assertEquals(IntentModality.PLAN_FUTURE, intent.nodes.single().modality)
    }

    @Test fun realCommitAdapterRejectsAdmissionWhichDropsIntervalEffects() = database().use { db ->
        val adapter = Phase60CanonicalCommitAdapter(db,"C1",TemporalProposalAdmissionPort { _, _ -> proposal() }, { identity })
        val scope = adapter.currentScope("C1")
        val action = TimedActionNode("A","OWNER",AcceptedActionTiming(ActionDuration(1500),"R",1))
        val work = Phase60TimeProcessor(emptyList()).begin(scope,"TIME-CMD",start,listOf(action)).copy(
            reached=change.proposedTime,terminalReason=TemporalStopReason.PLAYER_DECISION,
            candidateChanges=listOf(ResourceChange(DomainRef("PLAYER","P1"),"HEALTH",ExactLongDelta.of(-1))))
        assertEquals(TemporalCommitDecision.Rejected("P60:INTERVAL_EFFECT_DROPPED"),
            adapter.admitAndCommit(TemporalExecutionResult(work,TemporalStopReason.PLAYER_DECISION)))
        assertEquals(0L,Phase60TemporalStateStore(db,"C1").read().version)
        assertNull(TurnTransactionReceiptStore(db).committedCommand("C1","TIME-CMD"))
    }
}
