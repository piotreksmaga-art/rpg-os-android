package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64CombatReceiptAdmissionTest {
    private val actor = DomainRef("ACTOR", "COMBATANT1")
    private val target = DomainRef("UNIT", "FORMATION1")
    private val identity = TurnTransactionIdentity("C1", "TURN1", "COMPLETE-COMMAND", "TX1")
    private val rule = BackgroundProcessDefinition(Phase64CombatReceiptFactory.RULE_UID, Phase64CombatReceiptFactory.RULE_VERSION,
        "CONFLICT", "COMBAT", 60_000, parameters = mapOf(Phase64CombatReceiptFactory.OWNER_PARAMETER to NpcActionProcess.OWNER))

    private data class Fixture(val brain: NpcBrainState, val finished: NpcBrainChange, val receipt: BackgroundProcessChange,
        val clock: TemporalStateChange, val mechanics: List<PlayerDomainChange>, val events: List<PlayerEventIntent>)

    private fun fixture(): Fixture {
        val initial = NpcBrainOwner.initialize("C1", actor, "seed")
        val cause = NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION, "START-COMMAND")
        val goal = NpcGoal("COMBAT-GOAL", initial.motivations.first().uid, "Defend the settlement", NpcWeight(5000), NpcGoalLifecycle.ACTIVE, cause)
        val contract = phase60Hash("REGISTERED-COMBAT-CONTRACT")
        val option = "P62:OPTION:${phase60Hash("$actor|${goal.uid}|ATTACK|$target|$contract").take(32)}"
        val plan = NpcPlan("P62:DECISION:DURABLE-PLAN", goal.uid, option, NpcPlanLifecycle.RUNNING, WorldTimeTick(0), WorldTimeTick(1000), cause)
        val brain = initial.copy(revision = 3, goals = listOf(goal), plans = listOf(plan))
        val completedCause = NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION, identity.commandUid)
        val terminal = brain.copy(revision = 4, plans = listOf(plan.copy(lifecycle = NpcPlanLifecycle.COMPLETED,
            nextEvaluationAt = null, cause = completedCause)))
        val finished = NpcBrainChange("C1", actor, "H1", 3, NpcBrainCodec.fingerprint(brain), NpcBrainCodec.encode(terminal),
            NpcBrainRules.PLANNING.uid, 1, listOf(completedCause))
        val markers = mapOf("npc_plan_uid" to plan.uid, "npc_option_uid" to option, "npc_started_at_ms" to "0", "npc_due_at_ms" to "1000",
            "npc_ability_uid" to "ATTACK", "npc_target_kind_uid" to target.kindUid, "npc_target_uid" to target.uid,
            "npc_ability_contract" to contract, "source_actor_kind_uid" to actor.kindUid, "source_actor_uid" to actor.uid)
        val proof = "P60:PROCESS:${phase60Hash("COMPLETION")}:RPGOS-P50-PROOF:${phase60Hash("COMBAT")}"
        val effects = listOf(
            VerifiedMechanicsCommandEffect("IMPACT", "NODE", "UNIVERSAL_COMBAT", "WOUND", target, 2,
                markers + ("combat_proof_uid" to "PROOF:${phase60Hash("ACTUAL-COMBAT")}"), proof, "INPUT", "OUTPUT"),
            VerifiedMechanicsCommandEffect("COST", "NODE", "UNIVERSAL_COMBAT", "RESOURCE_DELTA", actor, -2,
                markers + ("resource_uid" to "STAMINA"), "$proof:COST", "INPUT", "COST-OUTPUT"))
        val material = effects.map { MechanicalEffectMaterializer.materialize(it) as MechanicalEffectMaterializationResult.Materialized }
        val mechanics = material.flatMap { it.changes }
        val clock = TemporalStateChange("C1", 1, WorldTimeTick(0), WorldTimeTick(1000), Phase60ProcessStateCodec.encode(listOf(
            TemporalOwnerState(NpcActionProcess.OWNER, 1, NpcActionProcess.encode(emptyList())))))
        val scope = TemporalScope("C1", "H1", 7, "digest")
        val input = TemporalOwnerInput(scope, clock.expectedTime, clock.proposedTime, emptyList(), emptyList(), null,
            listOf(finished) + mechanics.map { it.payload }, effects,
            mapOf(NpcActionProcess.OWNER to Phase60ProcessStateCodec.decode(clock.processStatesCanonical).single()))
        val receipt = (Phase64CombatReceiptFactory.prepare(BackgroundProcessEvaluationScope(scope, "seed", "rules"), identity.commandUid,
            "ACTIVE-PC", rule, brain, input, effects) as Phase64CombatReceiptPreparation.Ready).change
        return Fixture(brain, finished, receipt, clock, mechanics, material.flatMap { it.eventIntents })
    }

    private fun changeSet(f: Fixture, receipt: BackgroundProcessChange = f.receipt, clock: TemporalStateChange = f.clock,
        brains: List<NpcBrainChange> = listOf(f.finished), mechanics: List<PlayerDomainChange> = f.mechanics,
        events: List<PlayerEventIntent> = f.events) = PlayerChangeSet.create(changeSetUid = "SET1", campaignUid = "C1",
        sourceCommandUid = identity.commandUid, actor = CommandActorRef("PLAYER", "ACTIVE-PC"),
        changes = mechanics + brains.mapIndexed { index, brain -> PlayerDomainChange.create("BRAIN-$index", NPC_BRAIN_CHANGE_KIND, brain, brain.ruleUid) } +
            listOf(PlayerDomainChange.create("CLOCK", PHASE60_TIME_CHANGE_KIND, clock, "RPGOS-P60:ACCEPTED_ACTION_TIME"),
                PlayerDomainChange.create("RECEIPT", PHASE64_CHANGE_KIND, receipt, rule.uid)),
        eventIntents = events, provenance = ChangeSetProvenance(identity.commandUid, "TEST", "1"))

    private fun validate(f: Fixture, receipt: BackgroundProcessChange = f.receipt, clock: TemporalStateChange = f.clock,
        brain: NpcBrainState = f.brain, set: PlayerChangeSet = changeSet(f, receipt, clock),
        definition: BackgroundProcessDefinition = rule, fingerprint: String = "rules", activePlayer: String = "ACTIVE-PC",
        transaction: TurnTransactionIdentity = identity) = Phase64CombatReceiptAdmission.validate(transaction, receipt, definition,
        fingerprint, brain, activePlayer, clock, set)

    private fun rejected(code: Phase64CombatReceiptAdmissionCode, block: () -> Unit) {
        try { block(); fail("Expected ${code.uid}") }
        catch (failure: Phase64CombatReceiptAdmissionException) {
            assertEquals(code, failure.code)
            assertEquals(code.uid, failure.message)
        }
    }

    @Test fun acceptsActualFactoryReceiptWithoutAnotherExecutionOrSixtySecondWait() {
        val f = fixture()
        validate(f)
        validate(f)
        assertEquals(60_000L, rule.durationMillis)
        assertEquals(WorldTimeTick(1000), f.receipt.process.due)
        assertEquals(NpcPlanLifecycle.RUNNING, f.brain.plans.single().lifecycle)
        assertEquals(3, f.receipt.consequenceFingerprints.size)
    }

    @Test fun rejectsInventedProofAndActualSourceRuleRebindingEvenWithUnchangedPayloadHashes() {
        val f = fixture()
        val invented = "P60:PROCESS:${phase60Hash("INVENTED")}:RPGOS-P50-PROOF:${phase60Hash("INVENTED-COMBAT")}"
        val extra = f.receipt.copy(evidence = f.receipt.evidence.copy(sourceUids = f.receipt.evidence.sourceUids + invented))
        rejected(Phase64CombatReceiptAdmissionCode.PROOF_BINDING) { validate(f, receipt = extra) }
        val rebound = f.mechanics.map { wrapper -> PlayerDomainChange.create(wrapper.changeUid, wrapper.changeKindUid, wrapper.payload, invented) }
        rejected(Phase64CombatReceiptAdmissionCode.PROOF_BINDING) { validate(f, set = changeSet(f, mechanics = rebound)) }
    }

    @Test fun consequenceMultisetCannotOmitCostOrReplaceItWithAnUnrelatedOrDuplicateHash() {
        val f = fixture()
        val omitted = f.receipt.copy(consequenceFingerprints = f.receipt.consequenceFingerprints.dropLast(1))
        rejected(Phase64CombatReceiptAdmissionCode.CONSEQUENCES) { validate(f, receipt = omitted) }
        val invented = Phase64BackgroundCodec.fingerprint(ResourceChange(target, "MANA", ExactLongDelta.of(-1)))
        val unrelated = f.receipt.copy(consequenceFingerprints = f.receipt.consequenceFingerprints + invented)
        rejected(Phase64CombatReceiptAdmissionCode.CONSEQUENCES) { validate(f, receipt = unrelated) }
        val duplicate = f.receipt.copy(consequenceFingerprints = f.receipt.consequenceFingerprints + f.receipt.consequenceFingerprints.last())
        rejected(Phase64CombatReceiptAdmissionCode.CONSEQUENCES) { validate(f, receipt = duplicate) }
        val impact = f.mechanics.first()
        val repeated = PlayerDomainChange.create("REPEATED-IMPACT", impact.changeKindUid, impact.payload, impact.sourceRuleUid)
        val repeatedEvent = PlayerEventIntent.create("REPEATED-EVENT", PlayerEventIntentKinds.DOMAIN_EFFECT, actor, listOf(target),
            listOf(repeated.changeUid), DomainEffectEventIntentPayload(target, "WOUND"))
        val repeatedSet = changeSet(f, mechanics = f.mechanics + repeated, events = f.events + repeatedEvent)
        rejected(Phase64CombatReceiptAdmissionCode.CONSEQUENCES) { validate(f, set = repeatedSet) }
        val exactRepeated = f.receipt.copy(consequenceFingerprints = f.receipt.consequenceFingerprints + Phase64BackgroundCodec.fingerprint(impact.payload))
        validate(f, receipt = exactRepeated, set = changeSet(f, exactRepeated, mechanics = f.mechanics + repeated, events = f.events + repeatedEvent))
    }

    @Test fun requiresCausalMaterializerActorEvidenceAndImmutableOptionIdentity() {
        val f = fixture()
        rejected(Phase64CombatReceiptAdmissionCode.MECHANICS_BINDING) { validate(f, set = changeSet(f, events = emptyList())) }
        val stolenEvents = f.events.map { event -> PlayerEventIntent.create(event.eventIntentUid, event.eventKindUid,
            DomainRef("ACTOR", "OTHER-NPC"), event.targetRefs, event.causalChangeUids, event.payload) }
        rejected(Phase64CombatReceiptAdmissionCode.MECHANICS_BINDING) { validate(f, set = changeSet(f, events = stolenEvents)) }
        val altered = f.receipt.copy(process = f.receipt.process.copy(parameters = f.receipt.process.parameters + ("ability_uid" to "OTHER-ABILITY")))
        rejected(Phase64CombatReceiptAdmissionCode.OPTION) { validate(f, receipt = altered) }
    }

    @Test fun ruleParametersCampaignCommandAndActivePlayerCannotBeRebound() {
        val f = fixture()
        rejected(Phase64CombatReceiptAdmissionCode.RULE) { validate(f, definition = rule.copy(version = 1)) }
        rejected(Phase64CombatReceiptAdmissionCode.PARAMETERS) { validate(f, fingerprint = "OTHER-RULE-SOURCE") }
        val extra = f.receipt.copy(process = f.receipt.process.copy(parameters = f.receipt.process.parameters + ("untrusted_extra" to "1")))
        rejected(Phase64CombatReceiptAdmissionCode.PARAMETERS) { validate(f, receipt = extra) }
        rejected(Phase64CombatReceiptAdmissionCode.IDENTITY) { validate(f, transaction = identity.copy(commandUid = "OTHER-COMMAND")) }
        rejected(Phase64CombatReceiptAdmissionCode.IDENTITY) { validate(f, transaction = identity.copy(campaignUid = "OTHER-CAMPAIGN")) }
        rejected(Phase64CombatReceiptAdmissionCode.IDENTITY) { validate(f, activePlayer = actor.uid) }
    }

    @Test fun rejectsHistoricalClockStaleBrainAndUnconsumedOriginalPlan() {
        val f = fixture()
        val historical = f.clock.copy(expectedTime = WorldTimeTick(1000), proposedTime = WorldTimeTick(2000))
        rejected(Phase64CombatReceiptAdmissionCode.CLOCK) { validate(f, clock = historical) }
        val wrongClock = f.clock.copy(proposedTime = WorldTimeTick(999))
        rejected(Phase64CombatReceiptAdmissionCode.CLOCK) { validate(f, clock = wrongClock) }
        rejected(Phase64CombatReceiptAdmissionCode.BRAIN_CHAIN) { validate(f, brain = f.brain.copy(revision = 2)) }
        val prior = f.brain.plans.single()
        val pending = NpcPendingAction(actor, prior.uid, prior.actionUid, requireNotNull(prior.startedAt), requireNotNull(prior.nextEvaluationAt), Phase60CombatTime.RULE, 1)
        val pendingClock = f.clock.copy(processStatesCanonical = Phase60ProcessStateCodec.encode(listOf(
            TemporalOwnerState(NpcActionProcess.OWNER, 1, NpcActionProcess.encode(listOf(pending))))))
        rejected(Phase64CombatReceiptAdmissionCode.PENDING) { validate(f, clock = pendingClock) }
        rejected(Phase64CombatReceiptAdmissionCode.PENDING) { validate(f, clock = f.clock.copy(processStatesCanonical = "[]")) }
        val history = f.finished.copy(historyGenerationUid = "H2")
        rejected(Phase64CombatReceiptAdmissionCode.BRAIN_CHAIN) { validate(f, set = changeSet(f, brains = listOf(history))) }
    }

    @Test fun validatesSameTurnGenesisOnceThenRequiresEntirePlanningAndCompletionChain() {
        val f = fixture()
        val initial = NpcBrainOwner.initialize("C1", actor, "seed")
        val started = initial.copy(revision = 2, goals = f.brain.goals, plans = f.brain.plans)
        val genesisCause = NpcCauseRef(NpcCauseKind.GENESIS, "P61:GENESIS:${initial.seedFingerprint}")
        val genesis = NpcBrainChange("C1", actor, "H1", 0, null, NpcBrainCodec.encode(initial), NpcBrainRules.GENESIS.uid, 1, listOf(genesisCause))
        val planning = NpcBrainChange("C1", actor, "H1", 1, NpcBrainCodec.fingerprint(initial), NpcBrainCodec.encode(started),
            NpcBrainRules.PLANNING.uid, 1, listOf(started.plans.single().cause))
        val after = NpcBrainCodec.decode(f.finished.stateCanonical).copy(revision = 3)
        val finished = f.finished.copy(expectedVersion = 2, beforeFingerprint = NpcBrainCodec.fingerprint(started), stateCanonical = NpcBrainCodec.encode(after))
        val receipt = f.receipt.copy(consequenceFingerprints = listOf(Phase64BackgroundCodec.fingerprint(finished)) + f.receipt.consequenceFingerprints.drop(1))
        val set = changeSet(f, receipt, brains = listOf(genesis, planning, finished))
        validate(f, receipt = receipt, brain = initial, set = set)
        rejected(Phase64CombatReceiptAdmissionCode.BRAIN_CHAIN) { validate(f, receipt = receipt, brain = f.brain, set = set) }
        rejected(Phase64CombatReceiptAdmissionCode.BRAIN_CHAIN) { validate(f, receipt = receipt, brain = initial,
            set = changeSet(f, receipt, brains = listOf(finished))) }
    }
}
