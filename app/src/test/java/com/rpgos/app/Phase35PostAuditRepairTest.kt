package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase35PostAuditRepairTest {
    private lateinit var root: File
    private lateinit var dbFile: File

    @Before fun setUp() {
        specs.clear()
        actualOverrides.clear()
        root = kotlin.io.path.createTempDirectory("p35-post-audit-").toFile()
        dbFile = File(root, "campaign.db")
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun fakeTurnAndAdminSqlContextCannotCreateRecordedDivergence() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            fakeContext(db, "C1", "TURN") {
                assertTrue(runCatching { rawRecordedInsert(db, "DIV-FAKE-TURN", "C1", "TX-F", "TURN-F", "EVENT-F") }.isFailure)
            }
            fakeContext(db, "C1", "ADMIN") {
                assertTrue(runCatching { rawRecordedInsert(db, "DIV-FAKE-ADMIN", "C1", "TX-A", "TURN-A", "EVENT-A") }.isFailure)
            }
            assertTrue(CanonDivergenceStore(db, "C1").list().isEmpty())
        }
    }

    @Test fun administrativeAuthorityCannotInvokeRecordCommittedAndCampaignIdentityIsBound() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val store = CanonDivergenceStore(db, "C1")
            val validSpec = spec("DIV-ADMIN")
            assertTrue(runCatching {
                withAdministrativeMutationAuthority(db, "C1") {
                    store.recordCommitted(validSpec, TurnTransactionIdentity("C1", "T", "CMD", "TX"), "EVENT")
                }
            }.isFailure)
            assertTrue(runCatching {
                withAdministrativeMutationAuthority(db, "C1") {
                    store.recordCommitted(validSpec, TurnTransactionIdentity("C2", "T", "CMD", "TX"), "EVENT")
                }
            }.isFailure)
            assertTrue(store.list().isEmpty())
        }
    }

    @Test fun nonexistentTransactionTurnEventAndCrossCampaignEventProvenanceFailClosed() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            GameplayRuntimeBootstrap.initialize(db, "C2")

            val c2 = commit(db, "C2", "REAL-C2", spec("DIV-C2-REAL"))
            assertTrue(c2 is TurnExecutionResult.Committed)
            val c2Event = requireNotNull(CanonDivergenceStore(db, "C2").list().single().createdEventUid)

            fakeContext(db, "C1", "TURN") {
                assertTrue(runCatching { rawRecordedInsert(db, "DIV-NO-TX", "C1", "TX-NONE", "TURN-NONE", "EVENT-NONE") }.isFailure)
                assertTrue(runCatching { rawRecordedInsert(db, "DIV-NO-TURN", "C1", "TX-NONE-2", "TURN-NONE-2", "EVENT-NONE-2") }.isFailure)
                assertTrue(runCatching { rawRecordedInsert(db, "DIV-NO-EVENT", "C1", "TX-NONE-3", "TURN-NONE-3", "EVENT-NONE-3") }.isFailure)
                assertTrue(runCatching { rawRecordedInsert(db, "DIV-CROSS-EVENT", "C1", "TX-REAL-C2", "TURN-REAL-C2", c2Event) }.isFailure)
            }
            assertTrue(CanonDivergenceStore(db, "C1").list().isEmpty())
        }
    }

    @Test fun canonicalTurnPassesRetryIsSingleAndFailureRollsBackWithoutPhantom() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val accepted = acceptedProposal("C1", "GOOD", spec("DIV-GOOD"))
            val identity = TurnTransactionIdentity("C1", "TURN-GOOD", "GOOD", "TX-GOOD")
            assertTrue(TurnTransactionBoundary.create(db, identity, accepted).commit() is TurnExecutionResult.Committed)
            assertEquals(1, CanonDivergenceStore(db, "C1").list().size)
            assertTrue(TurnTransactionBoundary.create(db, identity, accepted).commit() is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1, CanonDivergenceStore(db, "C1").list().size)

            val rollbackProposal = acceptedProposal("C1", "ROLLBACK", spec("DIV-ROLLBACK"))
            assertTrue(runCatching {
                TurnTransactionBoundary.create(
                    db,
                    TurnTransactionIdentity("C1", "TURN-ROLLBACK", "ROLLBACK", "TX-ROLLBACK"),
                    rollbackProposal,
                    TurnFailureInjector { if (it == TurnFailurePoint.AFTER_EVENT_APPEND) error("injected") }
                ).commit()
            }.isFailure)
            assertEquals(listOf("DIV-GOOD"), CanonDivergenceStore(db, "C1").list().map { it.spec.divergenceUid })
        }
    }

    @Test fun recordedDivergenceRejectsUnboundAndWorldPackIdentityMismatch() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertRejectedOrFailed { admission("C1", "UNBOUND", spec("DIV-U"), bound = false) }
            assertRejectedOrFailed { admission("C1", "WRONG-PACK", spec("DIV-WP").copy(worldPackUid = "WORLD-B")) }
            assertRejectedOrFailed { admission("C1", "WRONG-VERSION", spec("DIV-V").copy(worldPackVersion = "2")) }
        }
    }

    @Test fun recordedDivergenceAuthenticatesExpectationExpectedAndActual() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertRejectedOrFailed {
                admission("C1", "NO-EXPECTATION", spec("DIV-NE").copy(
                    canonicalReference = CanonReference("CHARACTER", "P1", "NO-SUCH-EXPECTATION")
                ))
            }
            assertRejectedOrFailed {
                admission("C1", "WRONG-EXPECTED", spec("DIV-WE", expected = "WRONG-CANON"))
            }
            assertRejectedOrFailed {
                admission("C1", "WRONG-ACTUAL", spec("DIV-WA", actual = "DECLARED"), actualOverride = "COMMITTED")
            }
            val ok = admission("C1", "AUTHENTIC", spec("DIV-AUTH"))
            assertTrue(ok is CampaignMutationAdmission.Accepted)
        }
    }

    @Test fun lifecycleRejectsCrossCampaignMissingAndSelfReferences() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            GameplayRuntimeBootstrap.initialize(db, "C2")
            import(db, "C2", imported("DIV-C2"))

            assertTrue(runCatching {
                import(db, "C1", imported("DIV-C1-SUP-CROSS").copy(
                    status = CanonDivergenceStatus.SUPERSEDED,
                    supersedesDivergenceUid = "DIV-C2"
                ))
            }.isFailure)
            assertTrue(runCatching {
                import(db, "C1", imported("DIV-C1-RES-CROSS").copy(
                    status = CanonDivergenceStatus.RESOLVED,
                    resolvesDivergenceUid = "DIV-C2"
                ))
            }.isFailure)
            assertTrue(runCatching {
                import(db, "C1", imported("DIV-MISSING").copy(
                    status = CanonDivergenceStatus.SUPERSEDED,
                    supersedesDivergenceUid = "NO-SUCH-DIVERGENCE"
                ))
            }.isFailure)
            assertTrue(runCatching {
                import(db, "C1", imported("DIV-SELF").copy(
                    status = CanonDivergenceStatus.SUPERSEDED,
                    supersedesDivergenceUid = "DIV-SELF"
                ))
            }.isFailure)
        }
    }

    @Test fun lifecycleAllowsSameCampaignSupersedeAndResolve() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            import(db, "C1", imported("DIV-BASE-SUP"))
            val supersede = imported("DIV-SUP").copy(
                status = CanonDivergenceStatus.SUPERSEDED,
                supersedesDivergenceUid = "DIV-BASE-SUP"
            )
            assertEquals("DIV-SUP", import(db, "C1", supersede).spec.divergenceUid)

            import(db, "C1", imported("DIV-BASE-RES"))
            val resolve = imported("DIV-RES").copy(
                status = CanonDivergenceStatus.RESOLVED,
                resolvesDivergenceUid = "DIV-BASE-RES"
            )
            assertEquals("DIV-RES", import(db, "C1", resolve).spec.divergenceUid)
        }
    }

    private fun acceptedProposal(campaign: String, command: String, spec: CanonDivergenceSpec): CanonicalCampaignMutationProposal =
        when (val result = admission(campaign, command, spec)) {
            is CampaignMutationAdmission.Accepted -> result.proposal
            is CampaignMutationAdmission.Rejected -> error("unexpected rejection: ${result.reasonUid}")
        }

    private fun admission(
        campaign: String,
        commandUid: String,
        spec: CanonDivergenceSpec,
        bound: Boolean = true,
        actualOverride: String? = null
    ): CampaignMutationAdmission {
        specs[commandUid] = spec
        if (actualOverride != null) actualOverrides[commandUid] = actualOverride
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = commandUid,
            campaignUid = campaign,
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 1L, "CUR"),
            provenance = CommandProvenance("P35-POST-AUDIT"),
            requestedEffectiveOrder = 50L
        )
        val refs = setOf(
            CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef("CHARACTER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        )
        val binding = WorldPackRuleBinding("WORLD-A", "1")
        val authority: WorldPackAuthorityResolver = if (bound) WorldPackAuthoritySnapshot.single(campaign, binding) else WorldPackAuthoritySnapshot.empty()
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(PostAuditTruthComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(PostAuditCanonProvider())),
            worldPackAuthority = authority
        )
        val context = if (bound) {
            PlayerResolutionContext.create(campaign, actor, refs, worldRuleMode = WorldRuleMode.Bound(binding))
        } else {
            PlayerResolutionContext.createUnboundGeneric(campaign, actor, refs)
        }
        return CampaignMutationBoundary.resolveAndAdmit(campaign, engine, command, context)
    }

    private fun commit(
        db: SQLiteDatabase,
        campaign: String,
        command: String,
        spec: CanonDivergenceSpec
    ): TurnExecutionResult<TurnCommitAppliedResult> {
        val proposal = acceptedProposal(campaign, command, spec)
        return TurnTransactionBoundary.create(
            db,
            TurnTransactionIdentity(campaign, "TURN-$command", command, "TX-$command"),
            proposal
        ).commit()
    }

    private fun spec(uid: String, expected: String = "CANON", actual: String = "CAMPAIGN") = CanonDivergenceSpec(
        uid,
        CanonReference("CHARACTER", "P1", "CANON-EXPECTATION-1"),
        "WORLD-A",
        "1",
        CanonDivergenceKind.OUTCOME,
        expected,
        actual
    )

    private fun imported(uid: String) = spec(uid).copy(provenanceStatus = HistoricalProvenanceStatus.VERIFIED_IMPORT)

    private fun import(db: SQLiteDatabase, campaign: String, spec: CanonDivergenceSpec): CanonDivergenceRecord =
        withAdministrativeMutationAuthority(db, campaign) { CanonDivergenceStore(db, campaign).importVerified(spec) }

    private fun fakeContext(db: SQLiteDatabase, campaign: String, kind: String, block: () -> Unit) {
        db.execSQL(
            "INSERT INTO ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}(campaign_uid,capability_kind) VALUES(?,?)",
            arrayOf(campaign, kind)
        )
        try { block() } finally {
            db.delete(GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME, "campaign_uid=?", arrayOf(campaign))
        }
    }

    private fun rawRecordedInsert(
        db: SQLiteDatabase,
        uid: String,
        campaign: String,
        tx: String,
        turn: String,
        event: String
    ) {
        db.execSQL(
            """INSERT INTO ${Phase35CanonDivergenceSchema.TABLE}(
                divergence_uid,campaign_uid,canonical_subject_kind_uid,canonical_subject_uid,canonical_expectation_uid,
                world_pack_uid,world_pack_version,divergence_kind,expected_canonical_value,actual_campaign_value,
                lifecycle_status,created_transaction_uid,created_turn_uid,created_event_uid,provenance_status,
                effective_from,effective_until,supersedes_divergence_uid,resolves_divergence_uid,
                divergence_schema_version,created_at_epoch_ms)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            arrayOf(
                uid,campaign,"CHARACTER","P1","CANON-EXPECTATION-1","WORLD-A","1","OUTCOME","CANON","CAMPAIGN",
                "ACTIVE",tx,turn,event,"RECORDED",50L,null,null,null,CANON_DIVERGENCE_SCHEMA_VERSION,50L
            )
        )
    }

    private fun assertRejectedOrFailed(block: () -> CampaignMutationAdmission) {
        val result = runCatching(block)
        if (result.isSuccess) assertTrue(result.getOrThrow() is CampaignMutationAdmission.Rejected)
        else assertNotNull(result.exceptionOrNull())
    }

    private class PostAuditCanonProvider : WorldRuleProvider("P35-POST-AUDIT-CANON", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            val evidence = if (request.stage == WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK) {
                listOf(CanonExpectationEvidence.uid(
                    CanonReference("CHARACTER", "P1", "CANON-EXPECTATION-1"),
                    "CANON"
                ))
            } else emptyList()
            return WorldRuleDecision.Allowed.create("RPGOS-RULE:P35-POST-AUDIT-CANON", evidence)
        }
    }

    private class PostAuditTruthComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "P35-POST-AUDIT-TRUTH",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TransferFundsCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            val divergence = requireNotNull(specs[command.commandUid])
            val actual = actualOverrides[command.commandUid] ?: divergence.actualCampaignValue
            val truth = CampaignTruthChange(
                "TRUTH-${command.commandUid}",
                TruthKind.FACT,
                "P1",
                "canon.outcome",
                actual,
                null,
                null,
                null,
                divergence
            )
            val changeUid = "CHANGE-${command.commandUid}"
            val subject = DomainRef("PLAYER", "P1")
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(
                changes = listOf(PlayerDomainChange.create(changeUid, PlayerChangeKinds.CAMPAIGN_TRUTH, truth)),
                eventIntents = listOf(PlayerEventIntent.create(
                    "EVENT-INTENT-${command.commandUid}",
                    PlayerEventIntentKinds.DOMAIN_EFFECT,
                    subject,
                    listOf(subject),
                    listOf(changeUid),
                    DomainEffectEventIntentPayload(subject, "RPGOS-EFFECT:CANON-DIVERGENCE")
                ))
            ))
        }
    }

    companion object {
        private val specs = mutableMapOf<String, CanonDivergenceSpec>()
        private val actualOverrides = mutableMapOf<String, String>()
    }
}
