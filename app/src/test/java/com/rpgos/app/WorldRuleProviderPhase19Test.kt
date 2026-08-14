package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorldRuleProviderPhase19Test {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val binding = WorldPackRuleBinding("TEST-WORLD", "1")

    @Test fun p19_01_knownProviderLegalCommandContinues() {
        val r = engine(GenericProvider(Mode.ALLOW)).resolve(train(), context())
        assertTrue(r is PlayerResolutionOutcome.Resolved)
        val e = (r as PlayerResolutionOutcome.Resolved).evidence
        assertEquals(2, e.worldRuleDecisions.size)
        assertTrue(e.worldRuleDecisions.all { it.allowed })
    }

    @Test fun p19_02_typedRuleRejectionReturnsNoProposal() {
        val r = engine(GenericProvider(Mode.REJECT_COMMAND)).resolve(train(), context()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.WORLD_RULE_REJECTED, r.rejection.reason)
        assertEquals("RPGOS-TEST-REASON:COMMAND", r.rejection.detailUid)
        assertEquals("RPGOS-TEST-RULE:GENERIC", r.evidence.worldRuleDecisions.single().ruleUid)
    }

    @Test fun p19_03_ruleRejectionBeforeAuthoritativeMutation() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            assertTrue(engine(GenericProvider(Mode.REJECT_COMMAND)).resolve(train(), context()) is PlayerResolutionOutcome.Rejected)
            assertEquals(before, authorityValue(db))
        } finally { db.close() }
    }

    @Test fun p19_04_unknownReferenceRejectsBeforeProvider() {
        val ghost = train().copy(payload = TrainCommandPayload(DomainRef("STAT", "GHOST"), 10L, "METHOD"))
        val r = engine(ExplodeIfCalledProvider()).resolve(ghost, context()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, r.rejection.reason)
        assertTrue(r.evidence.worldRuleDecisions.isEmpty())
    }

    @Test fun p19_05_wrongCampaignReferenceRejectsBeforeProvider() {
        val ghost = train().copy(payload = TrainCommandPayload(DomainRef("STAT", "GHOST"), 10L, "METHOD"))
        val r = engine(ExplodeIfCalledProvider()).resolve(
            ghost,
            context(extra = setOf(scoped("OTHER", "STAT", "GHOST")))
        ) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, r.rejection.reason)
        assertTrue(r.evidence.worldRuleDecisions.isEmpty())
    }

    @Test fun p19_06_draftIllegalEffectRejectedAfterCommandAllow() {
        val r = engine(GenericProvider(Mode.REJECT_DRAFT)).resolve(train(), context()) as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.WORLD_RULE_REJECTED, r.rejection.reason)
        assertEquals(2, r.evidence.worldRuleDecisions.size)
        assertTrue(r.evidence.worldRuleDecisions.first().allowed)
        assertFalse(r.evidence.worldRuleDecisions.last().allowed)
        assertEquals(WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK, r.evidence.worldRuleDecisions.last().stage)
    }

    @Test fun p19_07_commandAndDraftAllowBuildNormalProposal() {
        val r = engine(GenericProvider(Mode.ALLOW)).resolve(train(), context()) as PlayerResolutionOutcome.Resolved
        assertEquals("CMD-P19", r.proposal.sourceCommandUid)
        assertEquals("RPGOS-TEST-WORLD-RULE-PROVIDER", r.proposal.provenance.worldRuleProviderUid)
        assertEquals(1, r.proposal.changes.size)
    }

    @Test fun p19_08_duplicateProviderRegistrationRejected() {
        fails("DUPLICATE_WORLD_RULE_PROVIDER") {
            WorldRuleProviderRegistry.of(listOf(GenericProvider(Mode.ALLOW), GenericProvider(Mode.ALLOW)))
        }
    }

    @Test fun p19_09_missingRequiredProviderFailsClosed() {
        fails("WORLD_RULE_PROVIDER_MISSING") {
            PlayerDomainEngine(
                PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
                worldPackAuthority = authority()
            ).resolve(train(), context())
        }
    }

    @Test fun p19_10_providerWorldPackVersionMismatchRejected() {
        val registry = WorldRuleProviderRegistry.of(listOf(GenericProvider(Mode.ALLOW, worldVersion = "2")))
        fails("WORLD_RULE_PROVIDER_VERSION_MISMATCH") {
            PlayerDomainEngine(
                PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
                worldRuleRegistry = registry,
                worldPackAuthority = authority()
            ).resolve(train(), context())
        }
    }

    @Test fun p19_11_sameInputSameDecision() {
        val e = engine(GenericProvider(Mode.ALLOW))
        val a = e.resolve(train(), context()) as PlayerResolutionOutcome.Resolved
        val b = e.resolve(train(), context()) as PlayerResolutionOutcome.Resolved
        assertEquals(a.evidence, b.evidence)
        assertEquals(a.proposal, b.proposal)
        assertEquals(a.evidence.worldRuleDecisions.map { it.decisionFingerprint }, b.evidence.worldRuleDecisions.map { it.decisionFingerprint })
    }

    @Test fun p19_12_providerVersionParticipatesInDecisionIdentity() {
        val a = engine(GenericProvider(Mode.ALLOW, providerVersion = "1")).resolve(train(), context()) as PlayerResolutionOutcome.Resolved
        val b = engine(GenericProvider(Mode.ALLOW, providerVersion = "2")).resolve(train(), context()) as PlayerResolutionOutcome.Resolved
        assertNotEquals(a.evidence.worldRuleDecisions.first().decisionFingerprint, b.evidence.worldRuleDecisions.first().decisionFingerprint)
        assertNotEquals(a.proposal.changeSetUid, b.proposal.changeSetUid)
    }

    @Test fun p19_13_normalRejectionDistinctFromProviderStructuralFault() {
        assertTrue(engine(GenericProvider(Mode.REJECT_COMMAND)).resolve(train(), context()) is PlayerResolutionOutcome.Rejected)
        fails("WORLD_RULE_PROVIDER_FAILURE") { engine(ThrowingProvider()).resolve(train(), context()) }
    }

    @Test fun p19_14_providerFaultProducesNoProposal() {
        fails("WORLD_RULE_PROVIDER_FAILURE") { engine(ThrowingProvider()).resolve(train(), context()) }
    }

    @Test fun p19_15_zeroAuthoritativeMutationOnProviderRejection() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            engine(GenericProvider(Mode.REJECT_DRAFT)).resolve(train(), context())
            assertEquals(before, authorityValue(db))
        } finally { db.close() }
    }

    @Test fun p19_16_zeroAuthoritativeMutationOnProviderFault() {
        val db = authorityDb()
        try {
            val before = authorityValue(db)
            fails("WORLD_RULE_PROVIDER_FAILURE") { engine(ThrowingProvider()).resolve(train(), context()) }
            assertEquals(before, authorityValue(db))
        } finally { db.close() }
    }

    @Test fun p19_17_equipmentSlotRemainsDefinitionIdentityB() {
        val cmd = PlayerCommand(
            commandUid = "CMD-EQ", campaignUid = "C1", actor = actor,
            commandKindUid = PlayerCommandKinds.EQUIP_ITEM,
            payload = EquipItemCommandPayload(DomainRef("ITEM_INSTANCE", "I1"), "SLOT:HAND"),
            provenance = CommandProvenance("TEST")
        )
        assertEquals(listOf(DomainRef("ITEM_INSTANCE", "I1")), commandReferences(cmd))
        val draft = PlayerResolutionDraft.create(changes = listOf(PlayerDomainChange.create(
            "CH-EQ", PlayerChangeKinds.EQUIPMENT,
            EquipmentChange(DomainRef("PLAYER", "P1"), "SLOT:HAND", EquipmentOperation.EQUIP, "I1")
        )))
        val refs = draftReferences(draft)
        assertTrue(DomainRef("ITEM_INSTANCE", "I1") in refs)
        assertFalse(DomainRef("EQUIPMENT_SLOT", "SLOT:HAND") in refs)
    }

    @Test fun p19_18_ownershipDaaaClassificationRemains() {
        val change = PlayerDomainChange.create(
            "CH-OWN", PlayerChangeKinds.OWNERSHIP,
            OwnershipChange(
                "OWNERSHIP:NEW",
                OwnedAssetRef("ASSET_KIND", "A1"),
                OwnershipOwnerRef("PLAYER", "P1"),
                OwnershipOwnerRef("PLAYER", "P2"),
                OwnershipShare.full()
            )
        )
        val refs = draftReferences(PlayerResolutionDraft.create(changes = listOf(change)))
        assertTrue(DomainRef("ASSET_KIND", "A1") in refs)
        assertTrue(DomainRef("PLAYER", "P1") in refs)
        assertTrue(DomainRef("PLAYER", "P2") in refs)
        assertFalse(refs.any { it.uid == "OWNERSHIP:NEW" })
    }

    @Test fun p19_19_financialReferenceCoverageUnchanged() {
        val cmd = financeCommand()
        assertEquals(
            listOf(
                DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "ACCOUNT:A"),
                DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "ACCOUNT:B"),
                DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR:PLN")
            ), commandReferences(cmd)
        )
    }

    @Test fun p19_20_projectProgressDeltaZeroRegression() {
        assertEquals(0L, ProjectProgressDelta.of(0L).units)
    }

    @Test fun p19_21_exactLongDeltaZeroRegression() {
        try { ExactLongDelta.of(0L); fail("zero ExactLongDelta must remain invalid") }
        catch (e: PlayerChangeSetStructuralException) { assertEquals("ZERO_DELTA", e.code) }
    }

    @Test fun p19_22_compositeConflictIdentityRegression() {
        val a = PlayerDomainChange.create("CH-A", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X:Y"), "Z", ExactLongDelta.of(1)))
        val b = PlayerDomainChange.create("CH-B", PlayerChangeKinds.STAT, StatChange(DomainRef("PLAYER", "X"), "Y:Z", ExactLongDelta.of(1)))
        val cs = PlayerChangeSet.create(
            changeSetUid = "CS-COMPOSITE", campaignUid = "C1", sourceCommandUid = "CMD",
            actor = actor, changes = listOf(a, b),
            provenance = ChangeSetProvenance("CMD", "TEST", "1")
        )
        PlayerChangeSetValidator.validate(cs)
        assertEquals(2, cs.changes.size)
    }

    @Test fun p19_23_ownedAssetRefIdentityRegression() {
        assertNotEquals(OwnedAssetRef("KIND:A", "SAME"), OwnedAssetRef("KIND:B", "SAME"))
    }

    @Test fun p19_24_financialLedgerExactSemanticsRegression() {
        val r = financeEngine().resolve(financeCommand(), financeContext()) as PlayerResolutionOutcome.Resolved
        val c = r.proposal.changes.single().payload as FinancialChange
        val l = r.proposal.ledgerIntents.single().payload as FinancialTransferLedgerIntentPayload
        assertEquals(c.fromAccountUid, l.fromAccountUid)
        assertEquals(c.toAccountUid, l.toAccountUid)
        assertEquals(c.amountMinor, l.amountMinor)
        assertEquals(c.currencyUid, l.currencyUid)
        assertEquals(c.transactionTypeUid, l.transactionTypeUid)
    }

    @Test fun p19_25_serializationAndFingerprintRegression() {
        val p = (engine(GenericProvider(Mode.ALLOW)).resolve(train(), context()) as PlayerResolutionOutcome.Resolved).proposal
        val encoded = PlayerChangeSetCodec.encode(p)
        val decoded = PlayerChangeSetCodec.decode(encoded)
        assertEquals(p, decoded)
        assertEquals(encoded, PlayerChangeSetCodec.encode(decoded))
        assertEquals(PlayerChangeSetCodec.fingerprint(p), PlayerChangeSetCodec.fingerprint(decoded))
    }

    @Test fun p19_26_coreContractContainsNoWorldSpecificTypeTokens() {
        val forbidden = listOf("Naruto", "Bleach", "Chakra", "Reiatsu", "Sharingan", "Kido", "Raiton", "Sonido", "Hollow", "Shinigami")
        val types = listOf(
            WorldPackRuleBinding::class.java,
            WorldRuleEvaluationStage::class.java,
            WorldRuleDecisionRecord::class.java,
            WorldRuleProvider::class.java,
            WorldRuleProviderRegistry::class.java,
            WorldRuleRequest::class.java
        )
        types.flatMap { listOf(it.name) + it.declaredFields.map { f -> f.name + ":" + f.type.name } }
            .forEach { text -> forbidden.forEach { token -> assertFalse("world-specific token $token leaked into $text", text.contains(token, true)) } }
    }

    @Test fun p19_27_providerHasNoSupportedWritableCapability() {
        val forbidden = listOf("SQLite", "Database", "Dao", "Store", "Repository", "Transaction", "StatePatch", "Commit", "LedgerWriter", "InventoryWriter", "ProjectWriter")
        listOf(WorldRuleProvider::class.java, WorldRuleRequest::class.java, WorldRuleProviderRegistry::class.java)
            .flatMap { it.declaredFields.toList() }
            .forEach { field -> forbidden.forEach { token -> assertFalse("writer capability leaked: ${field.name}", field.type.name.contains(token, true)) } }
    }

    @Test fun p19_28_phase3To18RepresentativeRegression() {
        val commandRegistry = PlayerCommandKindRegistry.core()
        assertEquals(commandRegistry.encode(train()), commandRegistry.encode(commandRegistry.decode(commandRegistry.encode(train()))))
        assertEquals(OWNERSHIP_SHARE_SCALE, OwnershipShare.full().units)
        assertEquals(0L, ProjectProgressDelta.of(0).units)
        val phase18Mode = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TrainComponent())))
        assertTrue(phase18Mode.resolve(train(), context(worldRules = false)) is PlayerResolutionOutcome.Resolved)
    }

    @Test fun p19_immutability_callerOwnedDecisionEvidenceCannotMutateDecision() {
        val evidence = mutableListOf("E2", "E1")
        val decision = WorldRuleDecision.Allowed.create("RULE", evidence)
        evidence.clear()
        assertEquals(listOf("E2", "E1"), decision.evidenceUids)
        try {
            @Suppress("UNCHECKED_CAST") (decision.evidenceUids as MutableList<String>).clear()
            fail("decision evidence must be immutable")
        } catch (_: UnsupportedOperationException) {}
    }

    private fun engine(provider: WorldRuleProvider): PlayerDomainEngine = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider)),
        worldPackAuthority = authority()
    )

    private fun financeEngine(): PlayerDomainEngine = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(FinanceComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(GenericProvider(Mode.ALLOW))),
        worldPackAuthority = authority()
    )

    private fun authority(b: WorldPackRuleBinding = binding): WorldPackAuthoritySnapshot =
        WorldPackAuthoritySnapshot.single("C1", b)

    private fun train() = PlayerCommand(
        commandUid = "CMD-P19", campaignUid = "C1", actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("TEST"), causationUid = "CAUSE", correlationUid = "CORR",
        requestedEffectiveOrder = 19L
    )

    private fun financeCommand() = PlayerCommand(
        commandUid = "CMD-FIN-P19", campaignUid = "C1", actor = actor,
        commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
        payload = TransferFundsCommandPayload("ACCOUNT:A", "ACCOUNT:B", 125L, "CUR:PLN"),
        provenance = CommandProvenance("TEST")
    )

    private fun context(
        extra: Set<CampaignScopedDomainRef> = emptySet(),
        worldRules: Boolean = true
    ) = PlayerResolutionContext.create(
        "C1", actor,
        setOf(scoped("C1", "PLAYER", "P1"), scoped("C1", "STAT", "STR")) + extra,
        mapOf("RPGOS-DEPENDENCY:REFERENCE-SNAPSHOT" to "1"),
        ResolutionEntropyEvidence.none(),
        if (worldRules) WorldRuleMode.Bound(binding) else UnboundGenericWorldRuleMode
    )

    private fun financeContext() = PlayerResolutionContext.create(
        "C1", actor,
        setOf(
            scoped("C1", PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "ACCOUNT:A"),
            scoped("C1", PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "ACCOUNT:B"),
            scoped("C1", PlayerResolutionReferenceKinds.CURRENCY, "CUR:PLN")
        ),
        worldRuleMode = WorldRuleMode.Bound(binding)
    )

    private fun scoped(campaign: String, kind: String, uid: String) = CampaignScopedDomainRef(campaign, DomainRef(kind, uid))

    private fun authorityDb(): SQLiteDatabase = SQLiteDatabase.create(null).also {
        it.execSQL("CREATE TABLE authority(value INTEGER NOT NULL)")
        it.execSQL("INSERT INTO authority(value) VALUES(7)")
    }

    private fun authorityValue(db: SQLiteDatabase): Long = db.rawQuery("SELECT value FROM authority", null).use {
        assertTrue(it.moveToFirst()); it.getLong(0)
    }

    private fun fails(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") }
        catch (e: PlayerDomainEngineStructuralException) { assertEquals(code, e.code) }
    }

    private enum class Mode { ALLOW, REJECT_COMMAND, REJECT_DRAFT }

    private class GenericProvider(
        private val mode: Mode,
        providerVersion: String = "1",
        worldVersion: String = "1"
    ) : WorldRuleProvider(
        "RPGOS-TEST-WORLD-RULE-PROVIDER", providerVersion, "TEST-WORLD", worldVersion
    ) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision = when {
            mode == Mode.REJECT_COMMAND && request.stage == WorldRuleEvaluationStage.COMMAND_PRECHECK ->
                WorldRuleDecision.Rejected.create("RPGOS-TEST-RULE:GENERIC", "RPGOS-TEST-REASON:COMMAND", listOf("EVIDENCE:COMMAND"))
            mode == Mode.REJECT_DRAFT && request.stage == WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK ->
                WorldRuleDecision.Rejected.create("RPGOS-TEST-RULE:GENERIC", "RPGOS-TEST-REASON:DRAFT", listOf("EVIDENCE:DRAFT"))
            else -> WorldRuleDecision.Allowed.create("RPGOS-TEST-RULE:GENERIC", listOf("EVIDENCE:ALLOW"))
        }
    }

    private class ExplodeIfCalledProvider : WorldRuleProvider(
        "RPGOS-TEST-WORLD-RULE-PROVIDER", "1", "TEST-WORLD", "1"
    ) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision =
            throw AssertionError("provider must not run before Phase-18 reference rejection")
    }

    private class ThrowingProvider : WorldRuleProvider(
        "RPGOS-TEST-WORLD-RULE-PROVIDER", "1", "TEST-WORLD", "1"
    ) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision = error("test provider fault")
    }

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN, TrainCommandPayload::class, "RPGOS-COMPONENT:P19-TRAIN", "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val change = PlayerDomainChange.create(
                "CH-P19", PlayerChangeKinds.STAT,
                StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
            )
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(change)))
        }
    }

    private class FinanceComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class, "RPGOS-COMPONENT:P19-FINANCE", "1"
    ) {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val p = command.payload
            val change = PlayerDomainChange.create(
                "CH-P19-FIN", PlayerChangeKinds.FINANCIAL,
                FinancialChange(p.fromAccountUid, p.toAccountUid, p.amountMinor, p.currencyUid, "TRANSFER")
            )
            val ledger = PlayerLedgerIntent.create(
                "LED-P19-FIN", PlayerLedgerIntentKinds.FINANCIAL_TRANSFER, listOf(change.changeUid),
                FinancialTransferLedgerIntentPayload(p.fromAccountUid, p.toAccountUid, p.amountMinor, p.currencyUid, "TRANSFER")
            )
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(changes = listOf(change), ledgerIntents = listOf(ledger))
            )
        }
    }
}
