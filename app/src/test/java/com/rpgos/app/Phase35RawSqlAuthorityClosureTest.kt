package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Phase35RawSqlAuthorityClosureTest {
    private lateinit var root: File
    private lateinit var dbFile: File

    @Before fun setUp() {
        root = kotlin.io.path.createTempDirectory("p35-raw-authority-").toFile()
        dbFile = File(root, "campaign.db")
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun rawSqlCannotForgeTurnWriterEventAndRecordedDivergenceChain() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            forgeSqlTurnContext(db, "C1")
            forgeSqlWriterContext(db, "C1")

            // Mutable SQL contexts remain defense-in-depth. They are intentionally insufficient
            // even if they allow a caller to manufacture an Event-shaped row.
            rawEventInsert(db, "C1", "EVENT-FAKE", "TX-FAKE", "TURN-FAKE", "CMD-FAKE")
            assertEquals(1L, count(db, CampaignIntelligencePhase30Schema.EVENT_TABLE))

            val divergenceFailure = runCatching {
                rawRecordedDivergenceInsert(db, "DIV-FAKE", "C1", "TX-FAKE", "TURN-FAKE", "EVENT-FAKE")
            }.exceptionOrNull()
            assertNotNull("raw SQL must not manufacture the in-memory canonical turn authority required by RECORDED divergence", divergenceFailure)
            assertEquals(0L, count(db, Phase35CanonDivergenceSchema.TABLE))
        }
    }

    @Test fun adminOnlySqlCannotForgeCanonicalEventOrRecordedDivergence() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            forgeSqlContext(db, "C1", "ADMIN")
            forgeSqlWriterContext(db, "C1")

            // ADMIN is not a gameplay turn. Event Store fails closed before divergence storage.
            assertTrue(runCatching {
                rawEventInsert(db, "C1", "EVENT-ADMIN", "TX-ADMIN", "TURN-ADMIN", "CMD-ADMIN")
            }.isFailure)
            assertTrue(runCatching {
                rawRecordedDivergenceInsert(db, "DIV-ADMIN", "C1", "TX-ADMIN", "TURN-ADMIN", "EVENT-ADMIN")
            }.isFailure)
            assertEquals(0L, count(db, CampaignIntelligencePhase30Schema.EVENT_TABLE))
            assertEquals(0L, count(db, Phase35CanonDivergenceSchema.TABLE))
        }
    }

    @Test fun fakeEventWithRealLookingIdentityAndCrossCampaignProvenanceFailClosed() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            GameplayRuntimeBootstrap.initialize(db, "C2")
            forgeSqlTurnContext(db, "C1")
            forgeSqlWriterContext(db, "C1")

            rawEventInsert(db, "C1", "RPGOS-EVENT:LOOKS-REAL", "TX-42", "TURN-42", "CMD-42")
            assertTrue(runCatching {
                rawRecordedDivergenceInsert(db, "DIV-LOOKS-REAL", "C1", "TX-42", "TURN-42", "RPGOS-EVENT:LOOKS-REAL")
            }.isFailure)

            clearSqlContexts(db, "C1")
            val c2Proposal = acceptedProposal("C2", "C2-REAL", spec("DIV-C2"))
            assertTrue(
                TurnTransactionBoundary.create(
                    db,
                    TurnTransactionIdentity("C2", "TURN-C2-REAL", "C2-REAL", "TX-C2-REAL"),
                    c2Proposal
                ).commit() is TurnExecutionResult.Committed
            )
            val c2Event = requireNotNull(CanonDivergenceStore(db, "C2").list().single().createdEventUid)

            forgeSqlTurnContext(db, "C1")
            forgeSqlWriterContext(db, "C1")
            assertTrue(runCatching {
                rawRecordedDivergenceInsert(db, "DIV-CROSS", "C1", "TX-C2-REAL", "TURN-C2-REAL", c2Event)
            }.isFailure)
            assertTrue(CanonDivergenceStore(db, "C1").list().isEmpty())
        }
    }

    @Test fun helperApiWithoutCanonicalRuntimeCapabilityFailsAndLegalTurnPasses() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            db.beginTransaction()
            try {
                forgeSqlTurnContext(db, "C1")
                forgeSqlWriterContext(db, "C1")
                assertTrue(runCatching {
                    CanonDivergenceStore(db, "C1").recordCommitted(
                        spec("DIV-HELPER"),
                        TurnTransactionIdentity("C1", "TURN-H", "CMD-H", "TX-H"),
                        "EVENT-H"
                    )
                }.isFailure)
            } finally {
                db.endTransaction()
            }

            val proposal = acceptedProposal("C1", "LEGAL", spec("DIV-LEGAL"))
            val result = TurnTransactionBoundary.create(
                db,
                TurnTransactionIdentity("C1", "TURN-LEGAL", "LEGAL", "TX-LEGAL"),
                proposal
            ).commit()
            assertTrue(result is TurnExecutionResult.Committed)
            assertEquals(listOf("DIV-LEGAL"), CanonDivergenceStore(db, "C1").list().map { it.spec.divergenceUid })
        }
    }

    @Test
    @Config(sdk = [28])
    fun api28FallbackStillFailsClosedForRawSqlAndAllowsSealedTurn() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            forgeSqlTurnContext(db, "C1")
            forgeSqlWriterContext(db, "C1")
            rawEventInsert(db, "C1", "EVENT-28", "TX-28", "TURN-28", "CMD-28")
            assertTrue(runCatching {
                rawRecordedDivergenceInsert(db, "DIV-28", "C1", "TX-28", "TURN-28", "EVENT-28")
            }.isFailure)
            clearSqlContexts(db, "C1")

            val proposal = acceptedProposal("C1", "LEGAL28", spec("DIV-LEGAL28"))
            assertTrue(
                TurnTransactionBoundary.create(
                    db,
                    TurnTransactionIdentity("C1", "TURN-LEGAL28", "LEGAL28", "TX-LEGAL28"),
                    proposal
                ).commit() is TurnExecutionResult.Committed
            )
            assertEquals(1, CanonDivergenceStore(db, "C1").list().size)
        }
    }

    private fun forgeSqlTurnContext(db: SQLiteDatabase, campaign: String) = forgeSqlContext(db, campaign, "TURN")

    private fun forgeSqlContext(db: SQLiteDatabase, campaign: String, kind: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}(campaign_uid,capability_kind) VALUES(?,?)",
            arrayOf(campaign, kind)
        )
    }

    private fun forgeSqlWriterContext(db: SQLiteDatabase, campaign: String) {
        db.execSQL(
            "INSERT OR REPLACE INTO ${CampaignIntelligencePhase30Schema.WRITER_CONTEXT_TABLE}(campaign_uid,writer_contract_version) VALUES(?,?)",
            arrayOf(campaign, PHASE30_WRITER_CONTRACT_VERSION)
        )
    }

    private fun clearSqlContexts(db: SQLiteDatabase, campaign: String) {
        db.delete(GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME, "campaign_uid=?", arrayOf(campaign))
        db.delete(CampaignIntelligencePhase30Schema.WRITER_CONTEXT_TABLE, "campaign_uid=?", arrayOf(campaign))
    }

    private fun rawEventInsert(
        db: SQLiteDatabase,
        campaign: String,
        eventUid: String,
        transactionUid: String,
        turnUid: String,
        commandUid: String
    ) {
        db.execSQL(
            """INSERT INTO ${CampaignIntelligencePhase30Schema.EVENT_TABLE}(
                campaign_uid,event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,
                committed_order,event_ordinal,source_actor_kind_uid,source_actor_uid,actor_ref_kind_uid,actor_ref_uid,
                subject_ref_kind_uid,subject_ref_uid,target_refs_canonical,causal_change_uids_canonical,effect_kind_uid,
                source_event_uid,resolver_kind_uid,resolver_version,semantic_fingerprint,schema_version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            arrayOf(
                campaign,eventUid,transactionUid,turnUid,commandUid,"EVENT-INTENT-FAKE",PlayerEventIntentKinds.DOMAIN_EFFECT,
                42L,0,"PLAYER","P1","PLAYER","P1","PLAYER","P1","[]","[]","RPGOS-EFFECT:FAKE",
                null,"RAW-SQL","1","FAKE-FINGERPRINT",PHASE30_EVENT_SCHEMA_VERSION
            )
        )
    }

    private fun rawRecordedDivergenceInsert(
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
                "ACTIVE",tx,turn,event,"RECORDED",42L,null,null,null,CANON_DIVERGENCE_SCHEMA_VERSION,42L
            )
        )
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    private fun spec(uid: String) = CanonDivergenceSpec(
        uid,
        CanonReference("CHARACTER", "P1", "CANON-EXPECTATION-1"),
        "WORLD-A",
        "1",
        CanonDivergenceKind.OUTCOME,
        "CANON",
        "CAMPAIGN"
    )

    private fun acceptedProposal(campaign: String, commandUid: String, divergence: CanonDivergenceSpec): CanonicalCampaignMutationProposal {
        Fixture.divergence = divergence
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = commandUid,
            campaignUid = campaign,
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 1L, "CUR"),
            provenance = CommandProvenance("P35-RAW-AUTHORITY"),
            requestedEffectiveOrder = 42L
        )
        val refs = setOf(
            CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef("CHARACTER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        )
        val binding = WorldPackRuleBinding("WORLD-A", "1")
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(Fixture.Component())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(Fixture.Provider())),
            worldPackAuthority = WorldPackAuthoritySnapshot.single(campaign, binding)
        )
        val context = PlayerResolutionContext.create(campaign, actor, refs, worldRuleMode = WorldRuleMode.Bound(binding))
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, command, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("unexpected rejection: ${admission.reasonUid}")
        }
    }

    private object Fixture {
        lateinit var divergence: CanonDivergenceSpec

        class Provider : WorldRuleProvider("P35-RAW-PROVIDER", "1", "WORLD-A", "1") {
            override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
                val evidence = if (request.stage == WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK) {
                    listOf(CanonExpectationEvidence.uid(divergence.canonicalReference, divergence.expectedCanonicalValue))
                } else emptyList()
                return WorldRuleDecision.Allowed.create("RPGOS-RULE:P35-RAW", evidence)
            }
        }

        class Component : PlayerResolutionComponent<TransferFundsCommandPayload>(
            PlayerCommandKinds.TRANSFER_FUNDS,
            TransferFundsCommandPayload::class,
            "P35-RAW-COMPONENT",
            "1"
        ) {
            override fun resolve(
                command: PlayerCommand<TransferFundsCommandPayload>,
                context: PlayerResolutionContext
            ): PlayerResolutionComponentOutcome {
                val truth = CampaignTruthChange(
                    "TRUTH-${command.commandUid}",
                    TruthKind.FACT,
                    "P1",
                    "canon.outcome",
                    divergence.actualCampaignValue,
                    null,
                    null,
                    null,
                    divergence
                )
                val changeUid = "CHANGE-${command.commandUid}"
                val subject = DomainRef("PLAYER", "P1")
                return PlayerResolutionComponentOutcome.Resolved(
                    PlayerResolutionDraft.create(
                        changes = listOf(PlayerDomainChange.create(changeUid, PlayerChangeKinds.CAMPAIGN_TRUTH, truth)),
                        eventIntents = listOf(
                            PlayerEventIntent.create(
                                "EVENT-INTENT-${command.commandUid}",
                                PlayerEventIntentKinds.DOMAIN_EFFECT,
                                subject,
                                listOf(subject),
                                listOf(changeUid),
                                DomainEffectEventIntentPayload(subject, "RPGOS-EFFECT:CANON-DIVERGENCE")
                            )
                        )
                    )
                )
            }
        }
    }
}
