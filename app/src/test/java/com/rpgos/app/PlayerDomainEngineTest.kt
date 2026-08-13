package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerDomainEngineTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val commandRegistry = PlayerCommandKindRegistry.core()

    @Test fun p18Hotfix01_noFullCommandToChangeSetComponentBypass() {
        val method = PlayerResolutionComponent::class.java.declaredMethods.single { it.name.startsWith("resolve") }
        assertNotEquals(PlayerChangeSet::class.java, method.returnType)
        assertTrue(PlayerResolutionComponentOutcome::class.java.isAssignableFrom(method.returnType))
        try {
            Class.forName("com.rpgos.app.PlayerCommandResolver")
            fail("legacy public PlayerCommandResolver must not exist")
        } catch (_: ClassNotFoundException) {
        }
    }

    @Test fun p18Hotfix02_canonicalPublicEntryIsPlayerDomainEngine() {
        val publicResolve = PlayerDomainEngine::class.java.methods.filter { it.name == "resolve" }
        assertEquals(1, publicResolve.size)
        assertEquals(PlayerResolutionOutcome::class.java, publicResolve.single().returnType)
    }

    @Test fun p18Hotfix03_componentReturnsTypedDraftNotFinalProposal() {
        val outcome = StatComponent(3).resolve(train(), context())
        assertTrue(outcome is PlayerResolutionComponentOutcome.Resolved)
        val draft = (outcome as PlayerResolutionComponentOutcome.Resolved).draft
        assertEquals(1, draft.changes.size)
        assertFalse(draft is Any && draft.javaClass == PlayerChangeSet::class.java)
    }

    @Test fun p18Hotfix04_domainRejectionIsTypedAndDistinctFromStructuralFault() {
        val result = engine(RejectingTrainComponent()).resolve(train(), context())
        assertTrue(result is PlayerResolutionOutcome.Rejected)
        val rejection = (result as PlayerResolutionOutcome.Rejected).rejection
        assertEquals(PlayerResolutionRejectionReason.DOMAIN_REJECTED, rejection.reason)
        assertEquals("RPGOS-RESOLUTION-REJECTION:DOMAIN_REJECTED", rejection.reason.reasonUid)

        failsEngine("DUPLICATE_COMMAND_RESOLUTION_COMPONENT") {
            PlayerResolutionComponentRegistry.of(listOf(StatComponent(1), StatComponent(2)))
        }
    }

    @Test fun p18Hotfix05_hiddenWriterCannotBeRegisteredAsSupportedComponentState() {
        val db = authorityDb()
        try {
            failsEngine("UNSAFE_RESOLUTION_COMPONENT_STATE") {
                PlayerResolutionComponentRegistry.of(listOf(DbCapturingTrainComponent(db)))
            }
            assertEquals(7L, authorityValue(db))
        } finally {
            db.close()
        }
    }

    @Test fun p18Hotfix06_resolutionContextIsImmutableDataOnly() {
        val ctx = context()
        val forbiddenTypeTokens = listOf("SQLite", "Room", "Dao", "Store", "Repository", "Transaction", "StatePatch", "Random", "Clock")
        PlayerResolutionContext::class.java.declaredFields.forEach { field ->
            forbiddenTypeTokens.forEach { token ->
                assertFalse("context field ${field.name} leaked $token", field.type.name.contains(token, ignoreCase = true))
            }
        }
        try {
            @Suppress("UNCHECKED_CAST")
            (ctx.knownReferences as MutableSet<CampaignScopedDomainRef>).clear()
            fail("knownReferences must be immutable")
        } catch (_: UnsupportedOperationException) {
        }
        try {
            @Suppress("UNCHECKED_CAST")
            (ctx.dependencyVersions as MutableMap<String, String>).clear()
            fail("dependencyVersions must be immutable")
        } catch (_: UnsupportedOperationException) {
        }
    }

    @Test fun p18Hotfix07_sideEffectBeforeFailureAttackIsRejectedAtRegistration() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            failsEngine("UNSAFE_RESOLUTION_COMPONENT_STATE") {
                engine(DbCapturingTrainComponent(db))
            }
            assertEquals(before, authorityValue(db))
        } finally {
            db.close()
        }
    }

    @Test fun p18Hotfix08_mutableComponentAliasIsRejectedAtRegistration() {
        val component = MutableTrainComponent(1)
        failsEngine("MUTABLE_RESOLUTION_COMPONENT_STATE") {
            PlayerResolutionComponentRegistry.of(listOf(component))
        }
        component.delta = 9
        assertEquals(9L, component.delta)
    }

    @Test fun p18Hotfix09_sameCommandAndContextAreDeterministic() {
        val engine = engine(StatComponent(3))
        val a = resolved(engine.resolve(train(), context()))
        val b = resolved(engine.resolve(train(), context()))
        assertEquals(a, b)
        assertEquals(PlayerChangeSetCodec.fingerprint(a), PlayerChangeSetCodec.fingerprint(b))
    }

    @Test fun p18Hotfix10_sameExplicitEntropyProducesSameResultAndEvidence() {
        val ctxA = context(entropy = ResolutionEntropyEvidence("TEST-SEED", 7))
        val ctxB = context(entropy = ResolutionEntropyEvidence("TEST-SEED", 7))
        val engine = engine(EntropyTrainComponent())
        val a = engine.resolve(train(), ctxA) as PlayerResolutionOutcome.Resolved
        val b = engine.resolve(train(), ctxB) as PlayerResolutionOutcome.Resolved
        assertEquals(a, b)
        assertEquals(7L, a.evidence.entropy.exactValue)
    }

    @Test fun p18Hotfix11_differentExplicitEntropyCanChangeProposalAndEvidence() {
        val engine = engine(EntropyTrainComponent())
        val a = engine.resolve(train(), context(entropy = ResolutionEntropyEvidence("TEST-SEED", 7))) as PlayerResolutionOutcome.Resolved
        val b = engine.resolve(train(), context(entropy = ResolutionEntropyEvidence("TEST-SEED", 8))) as PlayerResolutionOutcome.Resolved
        assertNotEquals(a.proposal, b.proposal)
        assertNotEquals(a.evidence, b.evidence)
        assertEquals(7L, (a.proposal.changes.single().payload as StatChange).delta.units)
        assertEquals(8L, (b.proposal.changes.single().payload as StatChange).delta.units)
    }

    @Test fun p18Hotfix12_noHiddenEntropyCapabilityOnEngineOrContext() {
        val types = listOf(PlayerDomainEngine::class.java, PlayerResolutionContext::class.java, ResolutionEntropyEvidence::class.java)
        val forbidden = listOf("Random", "Clock", "UUID", "Instant")
        types.flatMap { it.declaredFields.toList() }.forEach { field ->
            forbidden.forEach { token -> assertFalse(field.type.name.contains(token, ignoreCase = true)) }
        }
        assertTrue(ResolutionEntropyEvidence::class.java.declaredFields.any { it.name == "exactValue" })
    }

    @Test fun p18Hotfix13_unknownOrWrongCampaignReferenceIsTypedRejection() {
        val ghostCommand = train().copy(payload = TrainCommandPayload(DomainRef("PLAYER", "GHOST-NOT-IN-CAMPAIGN"), 10, "METHOD"))
        val unknown = engine(StatComponent(1)).resolve(ghostCommand, context()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, unknown.rejection.reason)

        val wrongCtx = context(extraRefs = setOf(scoped("OTHER", "PLAYER", "GHOST-NOT-IN-CAMPAIGN")))
        val wrong = engine(StatComponent(1)).resolve(ghostCommand, wrongCtx) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, wrong.rejection.reason)
    }

    @Test fun p18Hotfix14_validSameCampaignReferenceIsAccepted() {
        val result = engine(StatComponent(1)).resolve(train(), context())
        assertTrue(result is PlayerResolutionOutcome.Resolved)
    }

    @Test fun p18Hotfix15_referenceValidationWritesNothing() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            val ghostCommand = train().copy(payload = TrainCommandPayload(DomainRef("PLAYER", "GHOST-NOT-IN-CAMPAIGN"), 10, "METHOD"))
            val result = engine(StatComponent(1)).resolve(ghostCommand, context())
            assertTrue(result is PlayerResolutionOutcome.Rejected)
            assertEquals(before, authorityValue(db))
        } finally {
            db.close()
        }
    }

    @Test fun p18Hotfix16_componentRejectionCausesZeroAuthoritativeMutation() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            val result = engine(RejectingTrainComponent()).resolve(train(), context())
            assertTrue(result is PlayerResolutionOutcome.Rejected)
            assertEquals(before, authorityValue(db))
        } finally {
            db.close()
        }
    }

    @Test fun p18Hotfix17_componentThrowCausesZeroAuthoritativeMutation() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            failsEngine("RESOLUTION_COMPONENT_FAILURE") {
                engine(ThrowingTrainComponent()).resolve(train(), context())
            }
            assertEquals(before, authorityValue(db))
        } finally {
            db.close()
        }
    }

    @Test fun p18Hotfix18_changeSetValidationFailureCausesZeroAuthoritativeMutation() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            failsChangeSet("DUPLICATE_CHANGE_UID") {
                engine(DuplicateDraftTrainComponent()).resolve(train(), context())
            }
            assertEquals(before, authorityValue(db))
        } finally {
            db.close()
        }
    }

    @Test fun p18Hotfix19_singleHandlerRoutingRegression() {
        val engine = engine(StatComponent(5), FinanceComponent())
        val trainProposal = resolved(engine.resolve(train(), context()))
        assertTrue(trainProposal.changes.single().payload is StatChange)
        assertEquals(5L, (trainProposal.changes.single().payload as StatChange).delta.units)
    }

    @Test fun p18Hotfix20_duplicateKindRegistrationFailsClosed() {
        failsEngine("DUPLICATE_COMMAND_RESOLUTION_COMPONENT") {
            PlayerResolutionComponentRegistry.of(listOf(StatComponent(1), StatComponent(2)))
        }
    }

    @Test fun p18Hotfix21_unsupportedCommandFailsClosed() {
        failsEngine("UNKNOWN_COMMAND_RESOLUTION_COMPONENT") {
            PlayerDomainEngine(PlayerResolutionComponentRegistry.empty()).resolve(train(), context())
        }
    }

    @Test fun p18Hotfix22_projectFailureWithZeroProgressIsPreserved() {
        val proposal = resolved(engine(ProjectComponent("FAILURE")).resolve(projectWork(), context()))
        val project = proposal.changes.single().payload as DevelopmentProjectChange
        assertEquals(0L, project.progressDelta.units)
        assertEquals("FAILURE", project.workResultKindUid)
    }

    @Test fun p18Hotfix23_exactLongDeltaZeroStillRejected() {
        failsChangeSet("ZERO_DELTA") { ExactLongDelta.of(0) }
        assertEquals(Long.MIN_VALUE, ExactLongDelta.of(Long.MIN_VALUE).units)
        assertEquals(Long.MAX_VALUE, ExactLongDelta.of(Long.MAX_VALUE).units)
    }

    @Test fun p18Hotfix24_compositeConflictIdentityIsPreserved() {
        val proposal = resolved(engine(CompositeStatComponent()).resolve(train(), compositeContext()))
        assertEquals(2, proposal.changes.size)
        PlayerChangeSetValidator.validate(proposal)
    }

    @Test fun p18Hotfix25_assetIdentityIsPreserved() {
        val proposal = resolved(engine(AssetComponent()).resolve(assetCommand(), context()))
        val asset = (proposal.changes.single().payload as AssetChange).asset
        assertEquals("RPGOS-ASSET-KIND:PROPERTY:BUSINESS", asset.assetKindUid)
        assertEquals("BUSINESS:A-1", asset.assetUid)
    }

    @Test fun p18Hotfix26_financialLedgerTermsArePreserved() {
        val proposal = resolved(engine(FinanceComponent()).resolve(financeCommand(), context()))
        val change = proposal.changes.single().payload as FinancialChange
        val ledger = proposal.ledgerIntents.single().payload as FinancialTransferLedgerIntentPayload
        assertEquals(change.fromAccountUid, ledger.fromAccountUid)
        assertEquals(change.toAccountUid, ledger.toAccountUid)
        assertEquals(change.amountMinor, ledger.amountMinor)
        assertEquals(change.currencyUid, ledger.currencyUid)
        assertEquals(change.transactionTypeUid, ledger.transactionTypeUid)
    }

    @Test fun p18Hotfix27_canonicalSerializationClosure() {
        val proposal = resolved(engine(ProjectComponent("NO_PROGRESS")).resolve(projectWork(), context()))
        val encoded = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(proposal, decoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
    }

    @Test fun p18Hotfix28_fingerprintDeterminism() {
        val engine = engine(StatComponent(3))
        val a = resolved(engine.resolve(train(), context()))
        val b = resolved(engine.resolve(train(), context()))
        assertEquals(PlayerChangeSetCodec.fingerprint(a), PlayerChangeSetCodec.fingerprint(b))
    }

    @Test fun p18Hotfix29_phase17RepresentativeRegressionLock() {
        assertEquals(commandRegistry.encode(train()), commandRegistry.encode(commandRegistry.decode(commandRegistry.encode(train()))))
        assertEquals(OWNERSHIP_SHARE_SCALE, OwnershipShare.full().units)
        assertEquals(0L, ProjectProgressDelta.of(0).units)
        try {
            ProjectProgressDelta.of(-1)
            fail("negative project progress must fail")
        } catch (_: PlayerChangeSetStructuralException) {
        }
        try {
            OwnershipShare.full().copy(units = 0)
            fail("ownership invariant must fail")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun p18Hotfix30_realIndependentAuthorityFixtureNeverChangesAcrossResolution() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            resolved(engine(StatComponent(2)).resolve(train(), context()))
            resolved(engine(ProjectComponent("FAILURE")).resolve(projectWork(), context()))
            val rejected = engine(RejectingTrainComponent()).resolve(train(), context())
            assertTrue(rejected is PlayerResolutionOutcome.Rejected)
            assertEquals(before, authorityValue(db))
        } finally {
            db.close()
        }
    }

    @Test fun componentPayloadTypeMismatchFailsBeforeExecution() {
        failsEngine("COMMAND_RESOLUTION_COMPONENT_PAYLOAD_TYPE_MISMATCH") {
            engine(WrongPayloadComponent()).resolve(train(), context())
        }
    }

    @Test fun registryInputListAndKindSetAreDefensivelyImmutable() {
        val components = mutableListOf<PlayerResolutionComponent<out PlayerCommandPayload>>(StatComponent(1))
        val registry = PlayerResolutionComponentRegistry.of(components)
        components.clear()
        assertTrue(PlayerCommandKinds.TRAIN in registry.commandKindUids)
        try {
            @Suppress("UNCHECKED_CAST")
            (registry.commandKindUids as MutableSet<String>).clear()
            fail("kind set must be immutable")
        } catch (_: UnsupportedOperationException) {
        }
    }

    @Test fun exactCommandPayloadDrivesDraftWithoutLossyConversion() {
        val proposal = resolved(engine(EffortTrainComponent()).resolve(train(), context()))
        assertEquals(10L, (proposal.changes.single().payload as StatChange).delta.units)
    }

    @Test fun semanticallyRelevantInputCanProduceDifferentProposal() {
        val engine = engine(EffortTrainComponent())
        val a = resolved(engine.resolve(train(), context()))
        val b = resolved(engine.resolve(train().copy(payload = TrainCommandPayload(DomainRef("STAT", "STR"), 11, "METHOD")), context()))
        assertNotEquals(a, b)
        assertEquals(10L, (a.changes.single().payload as StatChange).delta.units)
        assertEquals(11L, (b.changes.single().payload as StatChange).delta.units)
    }

    @Test fun contextCampaignAndActorMismatchAreTypedRejections() {
        val wrongCampaign = context(campaignUid = "OTHER")
        val a = engine(StatComponent(1)).resolve(train(), wrongCampaign) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.CONTEXT_CAMPAIGN_MISMATCH, a.rejection.reason)

        val wrongActor = PlayerResolutionContext.create(
            campaignUid = "C1",
            actor = CommandActorRef("PLAYER", "P2"),
            knownReferences = baseRefs()
        )
        val b = engine(StatComponent(1)).resolve(train(), wrongActor) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.CONTEXT_ACTOR_MISMATCH, b.rejection.reason)
    }

    @Test fun unknownDraftTargetIsRejectedBeforePlayerChangeSetLeavesEngine() {
        val result = engine(GhostOutputTrainComponent()).resolve(train(), context())
        assertTrue(result is PlayerResolutionOutcome.Rejected)
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, (result as PlayerResolutionOutcome.Rejected).rejection.reason)
    }

    @Test fun engineOwnsCommandProposalLinkageAndProvenance() {
        val proposal = resolved(engine(StatComponent(1)).resolve(train(), context()))
        assertEquals("C1", proposal.campaignUid)
        assertEquals("CMD-TRAIN", proposal.sourceCommandUid)
        assertEquals(actor, proposal.actor)
        assertEquals("CAUSE", proposal.causationUid)
        assertEquals("CORR", proposal.correlationUid)
        assertEquals(17L, proposal.requestedEffectiveOrder)
        assertEquals("RPGOS-COMPONENT:STAT", proposal.provenance.resolverKindUid)
        assertEquals("1", proposal.provenance.resolverVersion)
        assertTrue(proposal.preconditions.contains(ChangeSetExpectedRecordVersion(DomainRef("PLAYER", "P1"), 3)))
    }

    @Test fun projectEvidenceListsRemainImmutableAcrossDraftAndProposal() {
        val external = mutableListOf(DomainRef("EVIDENCE", "E1"))
        val command = projectWork(external)
        val proposal = resolved(engine(ProjectComponent("FAILURE")).resolve(command, context()))
        external.clear()
        val project = proposal.changes.single().payload as DevelopmentProjectChange
        assertEquals(listOf(DomainRef("EVIDENCE", "E1")), project.evidenceRefs)
    }

    private fun train() = PlayerCommand(
        commandUid = "CMD-TRAIN",
        campaignUid = "C1",
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10, "METHOD"),
        provenance = CommandProvenance("TEST"),
        causationUid = "CAUSE",
        correlationUid = "CORR",
        requestedEffectiveOrder = 17,
        preconditions = listOf(ExpectedRecordVersion(DomainRef("PLAYER", "P1"), 3))
    )

    private fun financeCommand() = PlayerCommand(
        commandUid = "CMD-FIN",
        campaignUid = "C1",
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
        payload = TransferFundsCommandPayload("ACCOUNT:A", "ACCOUNT:B", 125, "CUR:PLN"),
        provenance = CommandProvenance("TEST"),
        causationUid = "CAUSE-F",
        correlationUid = "CORR-F",
        requestedEffectiveOrder = 18
    )

    private fun assetCommand() = PlayerCommand(
        commandUid = "CMD-ASSET",
        campaignUid = "C1",
        actor = actor,
        commandKindUid = PlayerCommandKinds.ACQUIRE_ASSET,
        payload = AcquireAssetCommandPayload("RPGOS-ASSET-KIND:PROPERTY:BUSINESS"),
        provenance = CommandProvenance("TEST")
    )

    private fun projectWork(evidence: List<DomainRef> = listOf(DomainRef("EVIDENCE", "E1"))) = PlayerCommand(
        commandUid = "CMD-PROJECT",
        campaignUid = "C1",
        actor = actor,
        commandKindUid = PlayerCommandKinds.RECORD_PROJECT_WORK,
        payload = RecordProjectWorkCommandPayload("PROJECT:P1", "EXPERIMENT", 10, "METHOD", evidence),
        provenance = CommandProvenance("TEST"),
        causationUid = "CAUSE-P",
        correlationUid = "CORR-P",
        requestedEffectiveOrder = 19
    )

    private fun context(
        campaignUid: String = "C1",
        extraRefs: Set<CampaignScopedDomainRef> = emptySet(),
        entropy: ResolutionEntropyEvidence = ResolutionEntropyEvidence.none()
    ): PlayerResolutionContext = PlayerResolutionContext.create(
        campaignUid = campaignUid,
        actor = actor,
        knownReferences = baseRefs() + extraRefs,
        dependencyVersions = mapOf("RPGOS-DEPENDENCY:REFERENCE-SNAPSHOT" to "1"),
        entropy = entropy
    )

    private fun compositeContext(): PlayerResolutionContext = context(extraRefs = setOf(
        scoped("C1", "PLAYER", "X:Y"),
        scoped("C1", "STAT", "Z"),
        scoped("C1", "PLAYER", "X"),
        scoped("C1", "STAT", "Y:Z")
    ))

    private fun baseRefs(): Set<CampaignScopedDomainRef> = setOf(
        scoped("C1", "PLAYER", "P1"),
        scoped("C1", "STAT", "STR"),
        scoped("C1", "STAT", "STAT:STR"),
        scoped("C1", "PROJECT", "PROJECT:P1"),
        scoped("C1", "EVIDENCE", "E1")
    )

    private fun scoped(campaign: String, kind: String, uid: String) =
        CampaignScopedDomainRef(campaign, DomainRef(kind, uid))

    private fun engine(vararg components: PlayerResolutionComponent<out PlayerCommandPayload>): PlayerDomainEngine =
        PlayerDomainEngine(PlayerResolutionComponentRegistry.of(components.toList()))

    private fun resolved(outcome: PlayerResolutionOutcome): PlayerChangeSet = when (outcome) {
        is PlayerResolutionOutcome.Resolved -> outcome.proposal
        is PlayerResolutionOutcome.Rejected -> fail("unexpected rejection ${outcome.rejection.reason}")
    }

    private fun authorityDb(): SQLiteDatabase = SQLiteDatabase.create(null).also { db ->
        db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
    }

    private fun authorityValue(db: SQLiteDatabase): Long = db.rawQuery(
        "SELECT value FROM authority_fixture WHERE uid='A'",
        null
    ).use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }

    private fun failsEngine(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected PlayerDomainEngineStructuralException($code)")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertEquals(code, e.code)
        }
    }

    private fun failsChangeSet(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected PlayerChangeSetStructuralException($code)")
        } catch (e: PlayerChangeSetStructuralException) {
            assertEquals(code, e.code)
        }
    }

    private class StatComponent(private val delta: Long) : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:STAT",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            resolvedDraft(statChange("CH-STAT", "P1", "STAT:STR", delta))
    }

    private class EffortTrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:EFFORT",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            resolvedDraft(statChange("CH-EFFORT", "P1", "STAT:STR", command.payload.effortUnits))
    }

    private class EntropyTrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:ENTROPY",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            resolvedDraft(statChange("CH-ENTROPY", "P1", "STAT:STR", context.entropy.exactValue))
    }

    private class RejectingTrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:REJECT",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            PlayerResolutionComponentOutcome.Rejected(
                PlayerResolutionRejection.create(
                    PlayerResolutionRejectionReason.DOMAIN_REJECTED,
                    detailUid = "RPGOS-TEST:EXPECTED_REJECTION"
                )
            )
    }

    private class ThrowingTrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:THROW",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            throw IllegalStateException("test fault")
    }

    private class MutableTrainComponent(var delta: Long) : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:MUTABLE",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            resolvedDraft(statChange("CH-MUT", "P1", "STAT:STR", delta))
    }

    private class DbCapturingTrainComponent(private val authority: SQLiteDatabase) : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:DB",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            authority.execSQL("UPDATE authority_fixture SET value=99 WHERE uid='A'")
            throw IllegalStateException("must never execute")
        }
    }

    private class DuplicateDraftTrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:DUP",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val change = statChange("CH-DUP", "P1", "STAT:STR", 1)
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(change, change)))
        }
    }

    private class GhostOutputTrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:GHOST",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            resolvedDraft(statChange("CH-GHOST", "GHOST-NOT-IN-CAMPAIGN", "STAT:STR", 1))
    }

    private class CompositeStatComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "RPGOS-COMPONENT:COMPOSITE",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(
                statChange("CH-A", "X:Y", "Z", 1),
                statChange("CH-B", "X", "Y:Z", 1)
            )))
    }

    private class ProjectComponent(private val resultKindUid: String) : PlayerResolutionComponent<RecordProjectWorkCommandPayload>(
        PlayerCommandKinds.RECORD_PROJECT_WORK,
        RecordProjectWorkCommandPayload::class,
        "RPGOS-COMPONENT:PROJECT",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<RecordProjectWorkCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val payload = DevelopmentProjectChange.create(
                command.payload.projectUid,
                resultKindUid,
                ProjectProgressDelta.of(0),
                command.payload.evidenceRefs
            )
            val change = PlayerDomainChange.create("CH-PROJECT", PlayerChangeKinds.DEVELOPMENT_PROJECT, payload)
            return resolvedDraft(change)
        }
    }

    private class FinanceComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "RPGOS-COMPONENT:FINANCE",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val p = command.payload
            val change = PlayerDomainChange.create(
                "CH-FIN",
                PlayerChangeKinds.FINANCIAL,
                FinancialChange(p.fromAccountUid, p.toAccountUid, p.amountMinor, p.currencyUid, "TRANSFER")
            )
            val ledger = PlayerLedgerIntent.create(
                "LED-FIN",
                PlayerLedgerIntentKinds.FINANCIAL_TRANSFER,
                listOf(change.changeUid),
                FinancialTransferLedgerIntentPayload(p.fromAccountUid, p.toAccountUid, p.amountMinor, p.currencyUid, "TRANSFER")
            )
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(changes = listOf(change), ledgerIntents = listOf(ledger))
            )
        }
    }

    private class AssetComponent : PlayerResolutionComponent<AcquireAssetCommandPayload>(
        PlayerCommandKinds.ACQUIRE_ASSET,
        AcquireAssetCommandPayload::class,
        "RPGOS-COMPONENT:ASSET",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<AcquireAssetCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val change = PlayerDomainChange.create(
                "CH-ASSET",
                PlayerChangeKinds.ASSET,
                AssetChange(OwnedAssetRef(command.payload.assetKindUid, "BUSINESS:A-1"), "PROPOSED")
            )
            return resolvedDraft(change)
        }
    }

    private class WrongPayloadComponent : PlayerResolutionComponent<LearnSkillCommandPayload>(
        PlayerCommandKinds.TRAIN,
        LearnSkillCommandPayload::class,
        "RPGOS-COMPONENT:WRONG",
        "1"
    ) {
        override fun resolve(command: PlayerCommand<LearnSkillCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            throw AssertionError("payload mismatch component must not execute")
    }

    companion object {
        private fun statChange(uid: String, subjectUid: String, statUid: String, delta: Long): PlayerDomainChange =
            PlayerDomainChange.create(
                uid,
                PlayerChangeKinds.STAT,
                StatChange(DomainRef("PLAYER", subjectUid), statUid, ExactLongDelta.of(delta))
            )

        private fun resolvedDraft(change: PlayerDomainChange): PlayerResolutionComponentOutcome =
            PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(change)))
    }
}
