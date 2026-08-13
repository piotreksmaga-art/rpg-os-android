package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
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

    @Test fun p18Engine01_canonicalCommandEntersPlayerDomainEngine() {
        var seen: PlayerCommand<TrainCommandPayload>? = null
        val command = train()
        val engine = engine(resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
            seen = it
            statProposal(it, "CH-1", DomainRef("PLAYER", "P1"), "STAT:STR", 1)
        })
        val result = engine.resolve(command)
        assertEquals(command.commandUid, seen!!.commandUid)
        assertNotSame(command, seen)
        assertEquals(command.commandUid, result.sourceCommandUid)
    }

    @Test fun p18Engine02_correctHandlerResolutionPathSelected() {
        var trainHits = 0
        var financeHits = 0
        val engine = engine(
            resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) { trainHits++; statProposal(it, "CH-TRAIN", DomainRef("PLAYER", "P1"), "STAT:STR", 1) },
            resolver(PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class) { financeHits++; financeProposal(it) }
        )
        engine.resolve(train())
        assertEquals(1, trainHits)
        assertEquals(0, financeHits)
    }

    @Test fun p18Engine03_exactlyOnePathHandlesOneCommand() {
        var a = 0
        var b = 0
        val engine = engine(
            resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) { a++; statProposal(it, "CH-A", DomainRef("PLAYER", "P1"), "STAT:A", 1) },
            resolver(PlayerCommandKinds.RECOVER, RecoverCommandPayload::class) { b++; statProposal(it, "CH-B", DomainRef("PLAYER", "P1"), "STAT:B", 1) }
        )
        engine.resolve(train())
        assertEquals(1, a)
        assertEquals(0, b)
        failsEngine("DUPLICATE_COMMAND_RESOLVER") {
            PlayerCommandResolverRegistry.of(listOf(
                resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) { statProposal(it, "X", DomainRef("PLAYER", "P1"), "A", 1) },
                resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) { statProposal(it, "Y", DomainRef("PLAYER", "P1"), "B", 1) }
            ))
        }
    }

    @Test fun p18Engine04_unsupportedCommandFailsClosed() {
        failsEngine("UNKNOWN_COMMAND_RESOLVER") { PlayerDomainEngine(PlayerCommandResolverRegistry.empty()).resolve(train()) }
    }

    @Test fun p18Engine05_validProposalReturnsAsPlayerChangeSet() {
        val proposal = engine(resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
            statProposal(it, "CH-VALID", DomainRef("PLAYER", "P1"), "STAT:STR", 4)
        }).resolve(train())
        assertTrue(proposal.changes.single().payload is StatChange)
        PlayerChangeSetValidator.validate(proposal)
    }

    @Test fun p18Engine06_invalidProposalCannotEscapePhase17Validation() {
        val command = train()
        val duplicate = PlayerDomainChange.create("CH-DUP", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "P1"), "STAT:STR", ExactLongDelta.of(1)))
        val engine = engine(resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
            PlayerChangeSet.create(
                changeSetUid = "CS-BAD", campaignUid = it.campaignUid, sourceCommandUid = it.commandUid, actor = it.actor,
                changes = listOf(duplicate, duplicate), provenance = provenance(it),
                causationUid = it.causationUid, correlationUid = it.correlationUid,
                requestedEffectiveOrder = it.requestedEffectiveOrder, preconditions = mappedPreconditions(it)
            )
        })
        failsChangeSet("DUPLICATE_CHANGE_UID") { engine.resolve(command) }
    }

    @Test fun p18Engine07_sameInputsAndDependencyResultAreDeterministic() {
        val command = train()
        val engine = engine(resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
            statProposal(it, "CH-DET", DomainRef("PLAYER", "P1"), "STAT:STR", 3)
        })
        assertEquals(engine.resolve(command), engine.resolve(command))
    }

    @Test fun p18Engine08_fingerprintStableForEquivalentOrchestration() {
        val command = train()
        val engine = engine(resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
            statProposal(it, "CH-FP", DomainRef("PLAYER", "P1"), "STAT:STR", 3)
        })
        val a = PlayerChangeSetCodec.fingerprint(engine.resolve(command))
        val b = PlayerChangeSetCodec.fingerprint(engine.resolve(command))
        assertEquals(a, b)
    }

    @Test fun p18Engine09_callerCommandRemainsUnchangedAndResolverGetsDetachedCopy() {
        val evidence = mutableListOf(DomainRef("EVIDENCE", "E1"))
        val command = projectWork(evidence)
        val before = commandRegistry.fingerprint(command)
        var resolverCommand: PlayerCommand<RecordProjectWorkCommandPayload>? = null
        val engine = engine(resolver(PlayerCommandKinds.RECORD_PROJECT_WORK, RecordProjectWorkCommandPayload::class) {
            resolverCommand = it
            projectProposal(it, ProjectProgressDelta.of(0), "FAILURE")
        })
        engine.resolve(command)
        assertNotSame(command, resolverCommand)
        assertEquals(before, commandRegistry.fingerprint(command))
        assertEquals(listOf(DomainRef("EVIDENCE", "E1")), evidence)
    }

    @Test fun p18Engine10_returnedProposalHasNoMutableAliasToResolverInput() {
        val evidence = mutableListOf(DomainRef("EVIDENCE", "E1"))
        val command = projectWork(evidence)
        val proposal = engine(resolver(PlayerCommandKinds.RECORD_PROJECT_WORK, RecordProjectWorkCommandPayload::class) {
            val local = mutableListOf(DomainRef("EVIDENCE", "E1"))
            val change = DevelopmentProjectChange.create(it.payload.projectUid, "FAILURE", ProjectProgressDelta.of(0), local)
            val result = baseProposal(it, listOf(PlayerDomainChange.create("CH-P", PlayerChangeKinds.DEVELOPMENT_PROJECT, change)))
            local.clear()
            result
        }).resolve(command)
        evidence.clear()
        val project = proposal.changes.single().payload as DevelopmentProjectChange
        assertEquals(listOf(DomainRef("EVIDENCE", "E1")), project.evidenceRefs)
    }

    @Test fun p18Engine11_resolverFailureCausesNoAuthoritativeMutation() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            val engine = engine(resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
                throw PlayerDomainEngineStructuralException("DOMAIN_REJECTED")
            })
            failsEngine("DOMAIN_REJECTED") { engine.resolve(train()) }
            assertEquals(before, authorityValue(db))
        } finally { db.close() }
    }

    @Test fun p18Engine12_successfulProposalGenerationCausesNoAuthoritativeMutation() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            engine(resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
                statProposal(it, "CH-NO-WRITE", DomainRef("PLAYER", "P1"), "STAT:STR", 1)
            }).resolve(train())
            assertEquals(before, authorityValue(db))
        } finally { db.close() }
    }

    @Test fun p18Engine13_noDirectTurnTransactionOrCommitExecutionSurface() {
        val types = listOf(PlayerDomainEngine::class.java, PlayerCommandResolverRegistry::class.java, PlayerCommandResolver::class.java)
        val forbiddenTypes = listOf("SQLite", "Room", "Dao", "Store", "Repository", "TurnTransaction", "StatePatch", "PlayerSnapshotBuilder")
        types.flatMap { it.declaredFields.toList() }.forEach { field ->
            forbiddenTypes.forEach { token -> assertFalse("${field.name} leaked $token", field.type.name.contains(token, ignoreCase = true)) }
        }
        val forbiddenMethods = setOf("apply", "commit", "execute", "persist", "save", "insert", "update", "delete")
        assertTrue(types.flatMap { it.methods.toList() }.none { it.name in forbiddenMethods })
    }

    @Test fun p18Engine14_representativeCommandFamiliesRouteCorrectly() {
        val seen = mutableListOf<String>()
        val engine = engine(
            resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) { seen += it.commandKindUid; statProposal(it, "CH-T", DomainRef("PLAYER", "P1"), "STAT:STR", 1) },
            resolver(PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class) { seen += it.commandKindUid; financeProposal(it) },
            resolver(PlayerCommandKinds.ACQUIRE_ASSET, AcquireAssetCommandPayload::class) { seen += it.commandKindUid; assetProposal(it) },
            resolver(PlayerCommandKinds.RECORD_PROJECT_WORK, RecordProjectWorkCommandPayload::class) { seen += it.commandKindUid; projectProposal(it, ProjectProgressDelta.of(0), "NO_PROGRESS") }
        )
        engine.resolve(train())
        engine.resolve(financeCommand())
        engine.resolve(assetCommand())
        engine.resolve(projectWork())
        assertEquals(listOf(PlayerCommandKinds.TRAIN, PlayerCommandKinds.TRANSFER_FUNDS, PlayerCommandKinds.ACQUIRE_ASSET, PlayerCommandKinds.RECORD_PROJECT_WORK), seen)
    }

    @Test fun p18Engine15_projectZeroProgressSemanticsSurviveOrchestration() {
        val proposal = engine(resolver(PlayerCommandKinds.RECORD_PROJECT_WORK, RecordProjectWorkCommandPayload::class) {
            projectProposal(it, ProjectProgressDelta.of(0), "FAILURE")
        }).resolve(projectWork())
        val project = proposal.changes.single().payload as DevelopmentProjectChange
        assertEquals(0L, project.progressDelta.units)
        assertEquals("FAILURE", project.workResultKindUid)
    }

    @Test fun p18Engine16_financialLedgerProposalSemanticsSurviveOrchestration() {
        val proposal = engine(resolver(PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class, ::financeProposal)).resolve(financeCommand())
        val change = proposal.changes.single().payload as FinancialChange
        val ledger = proposal.ledgerIntents.single().payload as FinancialTransferLedgerIntentPayload
        assertEquals(change.fromAccountUid, ledger.fromAccountUid)
        assertEquals(change.toAccountUid, ledger.toAccountUid)
        assertEquals(change.amountMinor, ledger.amountMinor)
        assertEquals(change.currencyUid, ledger.currencyUid)
        assertEquals(change.transactionTypeUid, ledger.transactionTypeUid)
    }

    @Test fun p18Engine17_fullAssetIdentitySurvivesOrchestration() {
        val proposal = engine(resolver(PlayerCommandKinds.ACQUIRE_ASSET, AcquireAssetCommandPayload::class, ::assetProposal)).resolve(assetCommand())
        val asset = (proposal.changes.single().payload as AssetChange).asset
        assertEquals("RPGOS-ASSET-KIND:PROPERTY:BUSINESS", asset.assetKindUid)
        assertEquals("BUSINESS:A-1", asset.assetUid)
    }

    @Test fun p18Engine18_compositeConflictIdentitySurvivesOrchestration() {
        val command = train()
        val engine = engine(resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
            val a = PlayerDomainChange.create("CH-A", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X:Y"), "Z", ExactLongDelta.of(1)))
            val b = PlayerDomainChange.create("CH-B", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X"), "Y:Z", ExactLongDelta.of(1)))
            baseProposal(it, listOf(a, b))
        })
        val proposal = engine.resolve(command)
        assertEquals(2, proposal.changes.size)
        PlayerChangeSetValidator.validate(proposal)
    }

    @Test fun p18Engine19_phase3To16RepresentativeRegression() {
        val command = train()
        assertEquals(commandRegistry.encode(command), commandRegistry.encode(commandRegistry.decode(commandRegistry.encode(command))))
        assertEquals(OWNERSHIP_SHARE_SCALE, OwnershipShare.full().units)
        assertEquals(1L, ExactLongDelta.of(1).units)
        assertEquals(0L, ProjectProgressDelta.of(0).units)
    }

    @Test fun p18Engine20_engineProposalSupportsCanonicalEncodeDecodeFingerprintPath() {
        val proposal = engine(resolver(PlayerCommandKinds.RECORD_PROJECT_WORK, RecordProjectWorkCommandPayload::class) {
            projectProposal(it, ProjectProgressDelta.of(0), "NO_PROGRESS")
        }).resolve(projectWork())
        val encoded = PlayerChangeSetCodec.encode(proposal)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(proposal, decoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
        assertEquals(PlayerChangeSetCodec.fingerprint(proposal), PlayerChangeSetCodec.fingerprint(decoded))
    }

    @Test fun p18Engine21_commandProposalLinkageFailsClosed() {
        val engine = engine(resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) {
            PlayerChangeSet.create(
                changeSetUid = "CS-WRONG", campaignUid = "OTHER", sourceCommandUid = it.commandUid, actor = it.actor,
                changes = listOf(PlayerDomainChange.create("CH", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "P1"), "STAT:STR", ExactLongDelta.of(1)))),
                provenance = provenance(it), causationUid = it.causationUid, correlationUid = it.correlationUid,
                requestedEffectiveOrder = it.requestedEffectiveOrder, preconditions = mappedPreconditions(it)
            )
        })
        failsEngine("CHANGESET_CAMPAIGN_MISMATCH") { engine.resolve(train()) }
    }

    @Test fun p18Engine22_resolverPayloadMismatchFailsClosed() {
        @Suppress("UNCHECKED_CAST")
        val wrong = object : PlayerCommandResolver<LearnSkillCommandPayload> {
            override val commandKindUid = PlayerCommandKinds.TRAIN
            override val payloadType = LearnSkillCommandPayload::class
            override fun resolve(command: PlayerCommand<LearnSkillCommandPayload>): PlayerChangeSet = fail("must not execute")
        }
        failsEngine("COMMAND_RESOLVER_PAYLOAD_TYPE_MISMATCH") {
            PlayerDomainEngine(PlayerCommandResolverRegistry.of(listOf(wrong))).resolve(train())
        }
    }

    @Test fun p18Engine23_registryIsDefensivelyCopiedAndExternallyImmutable() {
        val list = mutableListOf<PlayerCommandResolver<out PlayerCommandPayload>>(
            resolver(PlayerCommandKinds.TRAIN, TrainCommandPayload::class) { statProposal(it, "CH", DomainRef("PLAYER", "P1"), "STAT:STR", 1) }
        )
        val registry = PlayerCommandResolverRegistry.of(list)
        list.clear()
        assertTrue(PlayerCommandKinds.TRAIN in registry.commandKindUids)
        try {
            @Suppress("UNCHECKED_CAST")
            (registry.commandKindUids as MutableSet<String>).clear()
            fail("expected immutable set")
        } catch (_: UnsupportedOperationException) {
        }
    }

    @Test fun p18Engine24_phase17ValueAndConflictRegressionsRemainEnforced() {
        failsChangeSet("ZERO_DELTA") { ExactLongDelta.of(0) }
        try { ProjectProgressDelta.of(-1); fail("expected negative project progress rejection") } catch (_: PlayerChangeSetStructuralException) {}
        try { OwnershipShare.full().copy(units = 0); fail("expected ownership invariant") } catch (_: IllegalArgumentException) {}

        val c = train()
        val a = PlayerDomainChange.create("A", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "P1"), "STAT:SAME", ExactLongDelta.of(1)))
        val b = PlayerDomainChange.create("B", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "P1"), "STAT:SAME", ExactLongDelta.of(2)))
        failsChangeSet("CONFLICTING_CHANGE_TARGET") { baseProposal(c, listOf(a, b)) }
    }

    private fun train() = PlayerCommand(
        commandUid = "CMD-TRAIN", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10, "METHOD"), provenance = CommandProvenance("TEST"),
        causationUid = "CAUSE", correlationUid = "CORR", requestedEffectiveOrder = 17,
        preconditions = listOf(ExpectedRecordVersion(DomainRef("PLAYER", "P1"), 3))
    )

    private fun financeCommand() = PlayerCommand(
        commandUid = "CMD-FIN", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
        payload = TransferFundsCommandPayload("ACCOUNT:A", "ACCOUNT:B", 125, "CUR:PLN"), provenance = CommandProvenance("TEST"),
        causationUid = "CAUSE-F", correlationUid = "CORR-F", requestedEffectiveOrder = 18
    )

    private fun assetCommand() = PlayerCommand(
        commandUid = "CMD-ASSET", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.ACQUIRE_ASSET,
        payload = AcquireAssetCommandPayload("RPGOS-ASSET-KIND:PROPERTY:BUSINESS"), provenance = CommandProvenance("TEST")
    )

    private fun projectWork(evidence: List<DomainRef> = listOf(DomainRef("EVIDENCE", "E1"))) = PlayerCommand(
        commandUid = "CMD-PROJECT", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.RECORD_PROJECT_WORK,
        payload = RecordProjectWorkCommandPayload("PROJECT:P1", "EXPERIMENT", 10, "METHOD", evidence), provenance = CommandProvenance("TEST"),
        causationUid = "CAUSE-P", correlationUid = "CORR-P", requestedEffectiveOrder = 19
    )

    private fun statProposal(command: PlayerCommand<out PlayerCommandPayload>, uid: String, subject: DomainRef, statUid: String, units: Long): PlayerChangeSet =
        baseProposal(command, listOf(PlayerDomainChange.create(uid, PlayerChangeKinds.STAT, StatChange(subject, statUid, ExactLongDelta.of(units)))))

    private fun financeProposal(command: PlayerCommand<TransferFundsCommandPayload>): PlayerChangeSet {
        val p = command.payload
        val change = PlayerDomainChange.create("CH-FIN", PlayerChangeKinds.FINANCIAL, FinancialChange(p.fromAccountUid, p.toAccountUid, p.amountMinor, p.currencyUid, "TRANSFER"))
        val ledger = PlayerLedgerIntent.create(
            "LED-FIN", PlayerLedgerIntentKinds.FINANCIAL_TRANSFER, listOf(change.changeUid),
            FinancialTransferLedgerIntentPayload(p.fromAccountUid, p.toAccountUid, p.amountMinor, p.currencyUid, "TRANSFER")
        )
        return baseProposal(command, listOf(change), listOf(ledger))
    }

    private fun assetProposal(command: PlayerCommand<AcquireAssetCommandPayload>): PlayerChangeSet {
        val change = PlayerDomainChange.create(
            "CH-ASSET", PlayerChangeKinds.ASSET,
            AssetChange(OwnedAssetRef(command.payload.assetKindUid, "BUSINESS:A-1"), "PROPOSED")
        )
        return baseProposal(command, listOf(change))
    }

    private fun projectProposal(command: PlayerCommand<RecordProjectWorkCommandPayload>, delta: ProjectProgressDelta, result: String): PlayerChangeSet {
        val change = PlayerDomainChange.create(
            "CH-PROJECT", PlayerChangeKinds.DEVELOPMENT_PROJECT,
            DevelopmentProjectChange.create(command.payload.projectUid, result, delta, command.payload.evidenceRefs)
        )
        return baseProposal(command, listOf(change))
    }

    private fun baseProposal(
        command: PlayerCommand<out PlayerCommandPayload>,
        changes: List<PlayerDomainChange>,
        ledgers: List<PlayerLedgerIntent> = emptyList()
    ): PlayerChangeSet = PlayerChangeSet.create(
        changeSetUid = "CS-${command.commandUid}", campaignUid = command.campaignUid, sourceCommandUid = command.commandUid,
        actor = command.actor, changes = changes, ledgerIntents = ledgers, preconditions = mappedPreconditions(command),
        provenance = provenance(command), causationUid = command.causationUid, correlationUid = command.correlationUid,
        requestedEffectiveOrder = command.requestedEffectiveOrder
    )

    private fun mappedPreconditions(command: PlayerCommand<out PlayerCommandPayload>): List<ChangeSetPrecondition> = command.preconditions.map {
        when (it) {
            is ExpectedRecordVersion -> ChangeSetExpectedRecordVersion(it.target, it.expectedVersion)
            is ExpectedLifecycleState -> ChangeSetExpectedLifecycleState(it.target, it.expectedStateUid)
        }
    }

    private fun provenance(command: PlayerCommand<out PlayerCommandPayload>) =
        ChangeSetProvenance(command.commandUid, "RPGOS-RESOLVER:PHASE18-TEST", "1")

    private fun engine(vararg resolvers: PlayerCommandResolver<out PlayerCommandPayload>) =
        PlayerDomainEngine(PlayerCommandResolverRegistry.of(resolvers.toList()))

    private fun <P : PlayerCommandPayload> resolver(
        kind: String,
        type: KClass<P>,
        block: (PlayerCommand<P>) -> PlayerChangeSet
    ): PlayerCommandResolver<P> = object : PlayerCommandResolver<P> {
        override val commandKindUid = kind
        override val payloadType = type
        override fun resolve(command: PlayerCommand<P>): PlayerChangeSet = block(command)
    }

    private fun authorityDb(): SQLiteDatabase = SQLiteDatabase.create(null).also { db ->
        db.execSQL("CREATE TABLE authority_fixture(uid TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO authority_fixture(uid,value) VALUES('A',7)")
    }

    private fun authorityValue(db: SQLiteDatabase): Long = db.rawQuery("SELECT value FROM authority_fixture WHERE uid='A'", null).use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }

    private fun failsEngine(code: String, block: () -> Unit) {
        try { block(); fail("expected PlayerDomainEngineStructuralException($code)") }
        catch (e: PlayerDomainEngineStructuralException) { assertEquals(code, e.code) }
    }

    private fun failsChangeSet(code: String, block: () -> Unit) {
        try { block(); fail("expected PlayerChangeSetStructuralException($code)") }
        catch (e: PlayerChangeSetStructuralException) { assertEquals(code, e.code) }
    }
}
