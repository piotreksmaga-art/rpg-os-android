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
class Phase37WorldActorKnowledgeTest {
    private lateinit var root: File
    private lateinit var dbFile: File
    private lateinit var snapshots: File

    @Before fun setUp() {
        knowledgeByCommand.clear()
        root = kotlin.io.path.createTempDirectory("p37-knowledge-").toFile()
        dbFile = File(root, "campaign.db")
        snapshots = File(root, "snapshots")
    }

    @After fun tearDown() {
        knowledgeByCommand.clear()
        root.deleteRecursively()
    }

    @Test fun globalFactWithoutAcquisitionDoesNotBecomeHolderKnowledge() = withDb { db ->
        init(db)
        recordFact(db, "C1", "FACT-X", "TARGET", "X", "status", "TRUE", 10)
        assertEquals("TRUE", CampaignTruthStore(db, "C1").active().single().objectValue)
        assertTrue(KnowledgeStore(db, "C1").states(holder("A")).isEmpty())
    }

    @Test fun holderAAcquiresWhileHolderBRemainsUnaware() = withDb { db ->
        init(db)
        commit(db, "A-ONLY", change("A-ONLY", holder("A"), claim("CLAIM-X", "VALUE-X")))
        assertEquals(1, KnowledgeStore(db, "C1").states(holder("A")).size)
        assertTrue(KnowledgeStore(db, "C1").states(holder("B")).isEmpty())
    }

    @Test fun directObservationBindsExactCommittedEventProvenance() = withDb { db ->
        init(db)
        commit(db, "OBS", change("OBS", holder("SCOUT"), claim("CLAIM-OBS", "ENEMY_PRESENT"), method = KnowledgeAcquisitionMethods.DIRECT_OBSERVATION))
        val acquisition = KnowledgeStore(db, "C1").acquisitions(holder("SCOUT")).single()
        assertEquals("TX-OBS", acquisition.createdTransactionUid)
        assertEquals("TURN-OBS", acquisition.createdTurnUid)
        assertNotNull(acquisition.createdEventUid)
        assertEquals(KnowledgeAcquisitionMethods.DIRECT_OBSERVATION, acquisition.methodUid)
        val eventEvidence = KnowledgeStore(db, "C1").evidence(acquisition.acquisitionUid).single { it.evidenceKindUid == "COMMITTED_EVENT" }
        assertEquals(acquisition.createdEventUid, eventEvidence.sourceEventUid)
    }

    @Test fun sharingCreatesRecipientAcquisitionWithLineageInsteadOfCloningState() = withDb { db ->
        init(db)
        val c = claim("CLAIM-SHARED", "ENEMY-EAST")
        commit(db, "SCOUT-KNOWS", change("SCOUT", holder("SCOUT"), c, method = KnowledgeAcquisitionMethods.DIRECT_OBSERVATION))
        commit(db, "CAPTAIN-HEARS", change(
            "CAPTAIN", holder("CAPTAIN"), c,
            method = KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION,
            parent = "ACQ-SCOUT", sourceHolder = holder("SCOUT")
        ))
        val scout = KnowledgeStore(db, "C1").acquisitions(holder("SCOUT")).single()
        val captain = KnowledgeStore(db, "C1").acquisitions(holder("CAPTAIN")).single()
        assertNotEquals(scout.acquisitionUid, captain.acquisitionUid)
        assertEquals(scout.acquisitionUid, captain.parentAcquisitionUid)
        assertEquals(holder("SCOUT"), captain.sourceHolder)
    }

    @Test fun falseReportCreatesBeliefWithoutChangingFact() = withDb { db ->
        init(db)
        recordFact(db, "C1", "FACT-ARMY", "ARMY", "RED", "strength", "20000", 10)
        val falseClaim = KnowledgeClaim("CLAIM-ARMY-FALSE", "ARMY", "RED", "strength", "40000", domainUid = KnowledgeDomains.MILITARY_INTELLIGENCE)
        commit(db, "FALSE-REPORT", change("FALSE-REPORT", holder("GENERAL"), falseClaim, method = KnowledgeAcquisitionMethods.REPORT, state = KnowledgeEpistemicState.BELIEVED))
        assertEquals("20000", CampaignTruthStore(db, "C1").active(subjectUid = "RED").single().objectValue)
        val state = KnowledgeStore(db, "C1").states(holder("GENERAL")).single()
        assertEquals(KnowledgeEpistemicState.BELIEVED, state.epistemicState)
        assertEquals("40000", KnowledgeContextProjection(db, "C1").forHolders(listOf(holder("GENERAL")), false).single()["value_canonical"])
    }

    @Test fun contradictoryEvidenceIsPreservedHistorically() = withDb { db ->
        init(db)
        val c = claim("CLAIM-CONTRA", "BRIDGE-INTACT")
        commit(db, "FIRST", change("FIRST", holder("A"), c, state = KnowledgeEpistemicState.KNOWN))
        val contradict = KnowledgeEvidenceSpec("E-CONTRA", "WITNESS_REPORT", KnowledgeEvidencePolarity.CONTRADICTS, sourceAcquisitionUid = "ACQ-FIRST")
        commit(db, "SECOND", change("SECOND", holder("A"), c, method = KnowledgeAcquisitionMethods.REPORT,
            state = KnowledgeEpistemicState.CONTRADICTED, evidence = listOf(contradict)))
        assertTrue(KnowledgeStore(db, "C1").evidence("ACQ-FIRST").any { it.evidenceKindUid == "COMMITTED_EVENT" })
        assertTrue(KnowledgeStore(db, "C1").evidence("ACQ-SECOND").any { it.polarity == KnowledgeEvidencePolarity.CONTRADICTS })
        assertEquals(KnowledgeEpistemicState.CONTRADICTED, KnowledgeStore(db, "C1").states(holder("A")).single().epistemicState)
    }

    @Test fun highConfidenceDoesNotMakeFalseClaimObjectivelyTrue() = withDb { db ->
        init(db)
        recordFact(db, "C1", "FACT-HIGH", "TARGET", "X", "alive", "YES", 10)
        val falseClaim = KnowledgeClaim("CLAIM-HIGH-FALSE", "TARGET", "X", "alive", "NO", domainUid = KnowledgeDomains.INVESTIGATION)
        commit(db, "HIGH-FALSE", change("HIGH-FALSE", holder("DETECTIVE"), falseClaim,
            state = KnowledgeEpistemicState.BELIEVED, quality = quality(confidence = .99, precision = 1.0, observed = 10)))
        assertEquals(.99, KnowledgeStore(db, "C1").states(holder("DETECTIVE")).single().quality.confidence, 0.0001)
        assertEquals("YES", CampaignTruthStore(db, "C1").active(subjectUid = "X").single().objectValue)
    }

    @Test fun factChangeDoesNotAutoRefreshKnowledgeAndFreshnessCanBecomeOutdated() = withDb { db ->
        init(db)
        recordFact(db, "C1", "FACT-V1", "MARKET", "SUNA", "price", "180", 10)
        val old = KnowledgeClaim("CLAIM-PRICE-OLD", "MARKET", "SUNA", "price", "180", domainUid = KnowledgeDomains.MARKET)
        commit(db, "PRICE-OLD", change("PRICE-OLD", holder("MERCHANT"), old, quality = quality(observed = 10)))
        recordFact(db, "C1", "FACT-V2", "MARKET", "SUNA", "price", "240", 20)
        val state = KnowledgeStore(db, "C1").states(holder("MERCHANT")).single()
        assertEquals("180", KnowledgeContextProjection(db, "C1").forHolders(listOf(holder("MERCHANT")), false).single()["value_canonical"])
        assertTrue(KnowledgeFreshness.isOutdated(state, 20))
        assertEquals(KnowledgeEpistemicState.KNOWN, state.epistemicState)
    }

    @Test fun merchantEstimateMayDifferFromActualCurrentPrice() = withDb { db ->
        init(db)
        recordFact(db, "C1", "FACT-MARKET", "ITEM", "IRON-SWORD", "market_price", "180", 10)
        val estimate = KnowledgeClaim("CLAIM-MARKET", "ITEM", "IRON-SWORD", "market_price_about", "165", domainUid = KnowledgeDomains.VALUATION)
        commit(db, "MERCHANT", change("MERCHANT", holder("MERCHANT"), estimate, state = KnowledgeEpistemicState.PARTIALLY_KNOWN,
            quality = quality(confidence = .8, precision = .55, observed = 8)))
        assertEquals("165", KnowledgeContextProjection(db, "C1").forHolders(listOf(holder("MERCHANT")), false).single()["value_canonical"])
        assertEquals("180", CampaignTruthStore(db, "C1").active(subjectUid = "IRON-SWORD").single().objectValue)
    }

    @Test fun generalReceivesEstimateNotExactHiddenArmyFact() = withDb { db ->
        init(db)
        recordFact(db, "C1", "FACT-HIDDEN-ARMY", "ARMY", "ENEMY", "strength", "20000", 10)
        val estimate = KnowledgeClaim("CLAIM-ESTIMATE", "ARMY", "ENEMY", "strength_about", "18000", domainUid = KnowledgeDomains.MILITARY_INTELLIGENCE)
        commit(db, "GENERAL-EST", change("GENERAL-EST", holder("GENERAL"), estimate, method = KnowledgeAcquisitionMethods.REPORT,
            state = KnowledgeEpistemicState.PARTIALLY_KNOWN, quality = quality(confidence = .72, precision = .35, observed = 9)))
        val row = KnowledgeContextProjection(db, "C1").forHolders(listOf(holder("GENERAL")), false).single()
        assertEquals("18000", row["value_canonical"])
        assertNotEquals("20000", row["value_canonical"])
    }

    @Test fun scientistMayHoldFalseOrDisputedHypothesis() = withDb { db ->
        init(db)
        val h = KnowledgeClaim("HYP-H", "HYPOTHESIS", "H", "explains", "Y", domainUid = KnowledgeDomains.SCIENCE)
        commit(db, "SCIENCE-H", change("SCIENCE-H", holder("SCIENTIST"), h, method = KnowledgeAcquisitionMethods.INFERENCE,
            state = KnowledgeEpistemicState.SUSPECTED, quality = quality(confidence = .58, precision = .4)))
        assertEquals(KnowledgeEpistemicState.SUSPECTED, KnowledgeStore(db, "C1").states(holder("SCIENTIST")).single().epistemicState)
        assertTrue(CampaignTruthStore(db, "C1").active(subjectUid = "H").isEmpty())
    }

    @Test fun doctorMayHoldUncertainDiagnosticBelief() = withDb { db ->
        init(db)
        val diagnosis = KnowledgeClaim("CLAIM-DIAG", "PATIENT", "P-9", "suspected_disease", "X", domainUid = KnowledgeDomains.MEDICINE)
        commit(db, "DOCTOR", change("DOCTOR", holder("DOCTOR"), diagnosis, method = KnowledgeAcquisitionMethods.INFERENCE,
            state = KnowledgeEpistemicState.SUSPECTED, quality = quality(confidence = .51, precision = .6)))
        val s = KnowledgeStore(db, "C1").states(holder("DOCTOR")).single()
        assertEquals(KnowledgeEpistemicState.SUSPECTED, s.epistemicState)
        assertTrue(s.quality.confidence < 1.0)
    }

    @Test fun techniqueKnowledgeDoesNotGrantExecutableTechnique() = withDb { db ->
        init(db)
        val before = TableDigest.compute(db, "player_techniques_v2")
        val techniqueClaim = KnowledgeClaim("CLAIM-RAIKIRI", "CHARACTER", "KAKASHI", "can_use", "RAIKIRI", domainUid = KnowledgeDomains.TECHNIQUE_KNOWLEDGE)
        commit(db, "TECH-KNOW", change("TECH-KNOW", holder("OBSERVER"), techniqueClaim, method = KnowledgeAcquisitionMethods.DIRECT_OBSERVATION))
        assertEquals(before, TableDigest.compute(db, "player_techniques_v2"))
        assertEquals(1, KnowledgeStore(db, "C1").states(holder("OBSERVER")).size)
    }

    @Test fun institutionalKnowledgeDoesNotAutoPropagateToMembers() = withDb { db ->
        init(db)
        val institutional = KnowledgeHolderRef(KnowledgeHolderKinds.INTELLIGENCE_SERVICE, "ANBU")
        commit(db, "ANBU", change("ANBU", institutional, claim("CLAIM-SECRET", "SECRET-X"), scope = KnowledgeScope.INSTITUTIONAL))
        assertEquals(1, KnowledgeStore(db, "C1").states(institutional).size)
        assertTrue(KnowledgeStore(db, "C1").states(holder("OFFICER-1")).isEmpty())
    }

    @Test fun roleAccessibleKnowledgeRemainsDistinctFromPersonalKnowledge() = withDb { db ->
        init(db)
        val minister = holder("MINISTER")
        val c = claim("CLAIM-OFFICE", "BUDGET-X")
        commit(db, "ROLE", change("ROLE", minister, c, scope = KnowledgeScope.ROLE_ACCESSIBLE, roleUid = "FINANCE_MINISTER"))
        commit(db, "PERSONAL", change("PERSONAL", minister, c, method = KnowledgeAcquisitionMethods.MEMORY_RECALL, scope = KnowledgeScope.PERSONAL))
        val states = KnowledgeStore(db, "C1").states(minister)
        assertEquals(2, states.size)
        assertEquals(setOf(KnowledgeScope.ROLE_ACCESSIBLE, KnowledgeScope.PERSONAL), states.map { it.scope }.toSet())
        assertEquals("FINANCE_MINISTER", states.single { it.scope == KnowledgeScope.ROLE_ACCESSIBLE }.roleUid)
    }

    @Test fun crossCampaignAcquisitionAndLineageFailClosed() = withDb { db ->
        init(db, "C1", "C2")
        val crossIdentity = TurnTransactionIdentity("C2", "T", "C", "TX")
        assertTrue(runCatching {
            KnowledgeStore(db, "C1").stageRecorded(change("X", holder("A"), claim("CLAIM-X", "X")), crossIdentity, "EVENT", 1)
        }.isFailure)

        val c = claim("CLAIM-CROSS", "X")
        commit(db, "C2-PARENT", change("C2-PARENT", holder("A"), c), campaign = "C2")
        val child = change("C1-CHILD", holder("B"), c, method = KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION,
            parent = "ACQ-C2-PARENT", sourceHolder = holder("A"))
        assertTrue(runCatching { commit(db, "C1-CHILD", child, campaign = "C1") }.isFailure)
        assertTrue(KnowledgeStore(db, "C1").acquisitions(holder("B")).isEmpty())
    }

    @Test fun crossHolderLineageProvenanceMismatchFails() = withDb { db ->
        init(db)
        val c = claim("CLAIM-HOLDER", "X")
        commit(db, "SOURCE-A", change("SOURCE-A", holder("A"), c))
        val invalid = change("TARGET-B", holder("B"), c, method = KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION,
            parent = "ACQ-SOURCE-A", sourceHolder = holder("C"))
        assertTrue(runCatching { commit(db, "TARGET-B", invalid) }.isFailure)
        assertTrue(KnowledgeStore(db, "C1").acquisitions(holder("B")).isEmpty())
    }

    @Test fun rawFakeTurnContextAndGenericStatePatchCannotForgeCanonicalKnowledge() = withDb { db ->
        init(db)
        db.execSQL("INSERT INTO ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}(campaign_uid,capability_kind) VALUES('C1','TURN')")
        val raw = runCatching {
            db.execSQL("""INSERT INTO ${Phase37KnowledgeSchema.CLAIMS}(
                campaign_uid,claim_uid,subject_kind_uid,subject_uid,predicate_uid,value_canonical,domain_uid,claim_schema_version)
                VALUES('C1','RAW','TARGET','X','p','v','D',?)""", arrayOf(PHASE37_KNOWLEDGE_SCHEMA_VERSION))
        }
        assertTrue(raw.isFailure)
        db.delete(GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME, "campaign_uid=?", arrayOf("C1"))
        assertTrue(runCatching {
            GenericStatePatchGateway.apply(db, "C1", Phase37KnowledgeSchema.CLAIMS, emptyMap())
        }.isFailure)
    }

    @Test fun administrativeAuthorityCannotFabricateRecordedAcquisition() = withDb { db ->
        init(db)
        val failure = runCatching {
            withAdministrativeMutationAuthority(db, "C1") {
                db.execSQL("""INSERT INTO ${Phase37KnowledgeSchema.ACQUISITIONS}(
                    campaign_uid,acquisition_uid,claim_uid,holder_kind_uid,holder_uid,method_uid,scope_uid,
                    created_transaction_uid,created_turn_uid,created_event_uid,provenance_status,created_order,acquisition_schema_version)
                    VALUES('C1','ADMIN-ACQ','CLAIM','CHARACTER','A','REPORT','PERSONAL','TX','TURN','EVENT','RECORDED',1,?)""",
                    arrayOf(PHASE37_KNOWLEDGE_SCHEMA_VERSION))
            }
        }
        assertTrue(failure.isFailure)
        assertTrue(KnowledgeStore(db, "C1").acquisitions().isEmpty())
    }

    @Test fun legacyInformationWithoutVerifiableSourceRemainsUnknownNotRecorded() {
        val legacyFile = File(root, "legacy.db")
        SQLiteDatabase.openOrCreateDatabase(legacyFile, null).use { db ->
            db.execSQL("CREATE TABLE information_facts(info_uid TEXT PRIMARY KEY,title TEXT,content_summary TEXT,secrecy_level TEXT)")
            db.execSQL("CREATE TABLE information_knowledge(holder_uid TEXT,info_uid TEXT,confidence REAL,accuracy REAL,acquisition_method TEXT,learned_chapter INTEGER)")
            db.execSQL("INSERT INTO information_facts VALUES('I1','Legacy report','opaque historical text','secret')")
            db.execSQL("INSERT INTO information_knowledge VALUES('A','I1',0.9,0.7,NULL,12)")
            val row = LegacyKnowledgeCompatibilityAdapter(db, "C1").forHolder(holder("A")).single()
            assertEquals(KnowledgeProvenanceStatus.UNKNOWN_NOT_RECORDED.name, row["provenance_status"])
            assertEquals("LEGACY_OPAQUE_TEXT", row["predicate_uid"])
            assertEquals(false, row["canonical"])
        }
    }

    @Test fun retrySameLogicalAcquisitionCreatesExactlyOneSemanticAcquisition() = withDb { db ->
        init(db)
        val p = proposal("RETRY", change("RETRY", holder("A"), claim("CLAIM-RETRY", "X")))
        val first = TurnTransactionBoundary.create(db, TurnTransactionIdentity("C1", "TURN-R1", "RETRY", "TX-R1"), p).commit()
        val retry = TurnTransactionBoundary.create(db, TurnTransactionIdentity("C1", "TURN-R2", "RETRY", "TX-R2"), p).commit()
        assertTrue(first is TurnExecutionResult.Committed)
        assertTrue(retry is TurnExecutionResult.AlreadyCommitted)
        assertEquals(1, KnowledgeStore(db, "C1").acquisitions(holder("A")).size)
    }

    @Test fun rollbackLeavesNoPhantomAcquisitionOrState() = withDb { db ->
        init(db)
        val p = proposal("ROLLBACK", change("ROLLBACK", holder("A"), claim("CLAIM-ROLLBACK", "X")))
        val result = runCatching {
            TurnTransactionBoundary.create(
                db, TurnTransactionIdentity("C1", "TURN-ROLLBACK", "ROLLBACK", "TX-ROLLBACK"), p,
                TurnFailureInjector { if (it == TurnFailurePoint.BEFORE_COMMIT) error("injected") }
            ).commit()
        }
        assertTrue(result.isFailure)
        assertTrue(KnowledgeStore(db, "C1").acquisitions().isEmpty())
        assertTrue(KnowledgeStore(db, "C1").states(holder("A")).isEmpty())
    }

    @Test fun snapshotReplayReconstructsExactEpistemicState() = withDb { db ->
        init(db)
        CampaignSnapshotManager(db, "C1", snapshots).create()
        commit(db, "AFTER-SNAP", change("AFTER-SNAP", holder("A"), claim("CLAIM-SNAP", "X")))
        val beforeStates = KnowledgeStore(db, "C1").states(holder("A"))
        val beforeAcq = KnowledgeStore(db, "C1").acquisitions(holder("A"))
        val staged = CampaignSnapshotManager(db, "C1", snapshots).reconstructToVerifiedStaging()
        SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { restored ->
            assertEquals(beforeStates, KnowledgeStore(restored, "C1").states(holder("A")))
            assertEquals(beforeAcq, KnowledgeStore(restored, "C1").acquisitions(holder("A")))
            assertEquals(AuthoritativeStateDigest.compute(db), AuthoritativeStateDigest.compute(restored))
        }
    }

    @Test fun contextBuilderKnowledgeProjectionForHolderAExcludesBOnlyKnowledge() = withDb { db ->
        init(db)
        commit(db, "CTX-A", change("CTX-A", holder("A"), claim("CLAIM-A", "A-VALUE")))
        commit(db, "CTX-B", change("CTX-B", holder("B"), claim("CLAIM-B", "B-VALUE")))
        val rows = KnowledgeContextProjection(db, "C1").forHolders(listOf(holder("A")), includeLegacy = false)
        assertEquals(1, rows.size)
        assertEquals("A", rows.single()["holder_uid"])
        assertEquals("CLAIM-A", rows.single()["claim_uid"])
        assertFalse(rows.any { it["holder_uid"] == "B" })
    }

    @Test fun evidenceLineageSurvivesSnapshotReplay() = withDb { db ->
        init(db)
        CampaignSnapshotManager(db, "C1", snapshots).create()
        val c = claim("CLAIM-LINEAGE", "ENEMY-NORTH")
        commit(db, "LINEAGE-A", change("LINEAGE-A", holder("SCOUT"), c))
        commit(db, "LINEAGE-B", change("LINEAGE-B", holder("CAPTAIN"), c, method = KnowledgeAcquisitionMethods.REPORT,
            parent = "ACQ-LINEAGE-A", sourceHolder = holder("SCOUT"), evidence = listOf(
                KnowledgeEvidenceSpec("E-LINEAGE", "SCOUT_REPORT", KnowledgeEvidencePolarity.SUPPORTS, sourceAcquisitionUid = "ACQ-LINEAGE-A")
            )))
        val staged = CampaignSnapshotManager(db, "C1", snapshots).reconstructToVerifiedStaging()
        SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { restored ->
            val child = KnowledgeStore(restored, "C1").acquisitions(holder("CAPTAIN")).single()
            assertEquals("ACQ-LINEAGE-A", child.parentAcquisitionUid)
            assertEquals(holder("SCOUT"), child.sourceHolder)
            assertTrue(KnowledgeStore(restored, "C1").evidence("ACQ-LINEAGE-B").any { it.sourceAcquisitionUid == "ACQ-LINEAGE-A" })
        }
    }

    @Test fun expertiseIsInterpretiveDomainNotExecutableSkillAuthority() {
        val profile = ExpertiseProfile("C1", holder("ANALYST"), KnowledgeDomains.MILITARY_INTELLIGENCE, 80, .9, 1)
        assertEquals(KnowledgeDomains.MILITARY_INTELLIGENCE, profile.domainUid)
        assertEquals(80, profile.levelUnits)
        assertNotEquals(PlayerChangeKinds.SKILL, PHASE37_KNOWLEDGE_CHANGE_KIND)
        assertNotEquals(PlayerChangeKinds.TECHNIQUE, PHASE37_KNOWLEDGE_CHANGE_KIND)
    }

    @Test fun carrierIsProvenanceHookAndDoesNotTransferPersonalState() = withDb { db ->
        init(db)
        val carrier = KnowledgeCarrierRef(KnowledgeCarrierKinds.REPORT, "REPORT-77")
        commit(db, "CARRIER", change("CARRIER", holder("A"), claim("CLAIM-CARRIER", "X"), method = KnowledgeAcquisitionMethods.DOCUMENT, carrier = carrier))
        val a = KnowledgeStore(db, "C1").acquisitions(holder("A")).single()
        assertEquals(carrier, a.carrier)
        assertTrue(KnowledgeStore(db, "C1").states(holder("B")).isEmpty())
    }

    @Test fun g32WriterInventoryClassifiesAllNewTablesUnderStableFamily() = withDb { db ->
        init(db)
        assertTrue(RuntimePersistentWriterRegistry.canonicalTurnTargetFamilies.contains("NPC_KNOWLEDGE_STATE"))
        listOf(
            Phase37KnowledgeSchema.CLAIMS, Phase37KnowledgeSchema.ACQUISITIONS, Phase37KnowledgeSchema.EVIDENCE,
            Phase37KnowledgeSchema.STATES, Phase37KnowledgeSchema.EXPERTISE
        ).forEach { table -> assertEquals("NPC_KNOWLEDGE_STATE", RuntimeTruthLayerRegistry.requireClassifiedTable(table).uid) }
        assertEquals(ReplayAuthorityCoverage.REPLAYABLE, CampaignReplayAuthorityMatrix.coverage("NPC_KNOWLEDGE_STATE"))
    }

    @Test fun phase36KnowledgeSchemaRegistrationIsAdditiveAndCurrent() = withDb { db ->
        init(db)
        val version = db.rawQuery(
            "SELECT schema_version FROM ${Phase36SchemaVersioning.VERSIONS} WHERE schema_family_uid=?",
            arrayOf(SchemaFamilyUid.KNOWLEDGE.name)
        ).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) }
        assertEquals(PHASE37_KNOWLEDGE_SCHEMA_VERSION, version)
        assertTrue(Phase37KnowledgeSchema.isReady(db))
    }

    @Test fun canonicalProjectionCorruptionFailsClosedInsteadOfEmptyKnowledge() = withDb { db ->
        init(db)
        db.execSQL("DROP TABLE ${Phase37KnowledgeSchema.STATES}")
        val failure = runCatching {
            KnowledgeContextProjection(db, "C1").forHolders(listOf(holder("A")), includeLegacy = true)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("CANONICAL_KNOWLEDGE_SCHEMA_CORRUPT"))
    }

    @Test fun currentKnowledgeSchemaWithMissingPhysicalTableFailsClosedOnBootstrap() = withDb { db ->
        init(db)
        db.execSQL("DROP TABLE ${Phase37KnowledgeSchema.EVIDENCE}")
        val failure = runCatching { GameplayRuntimeBootstrap.initialize(db, "C1") }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure!!.message.orEmpty().contains("KNOWLEDGE_PHYSICAL_SCHEMA_NOT_CURRENT"))
        assertFalse(Phase37KnowledgeSchema.isReady(db))
    }

    @Test fun forgedTurnWriterEventAndAdminStillCannotManufactureRecordedKnowledge() = withDb { db ->
        init(db)
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}(campaign_uid,capability_kind) VALUES('C1','TURN')"
            )
            db.execSQL(
                "INSERT OR REPLACE INTO ${CampaignIntelligencePhase30Schema.WRITER_CONTEXT_TABLE}(campaign_uid,writer_contract_version) VALUES('C1',?)",
                arrayOf(PHASE30_WRITER_CONTRACT_VERSION)
            )
            db.execSQL(
                """INSERT INTO ${CampaignIntelligencePhase30Schema.EVENT_TABLE}(
                    campaign_uid,event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,
                    committed_order,event_ordinal,source_actor_kind_uid,source_actor_uid,actor_ref_kind_uid,actor_ref_uid,
                    subject_ref_kind_uid,subject_ref_uid,target_refs_canonical,causal_change_uids_canonical,effect_kind_uid,
                    source_event_uid,resolver_kind_uid,resolver_version,semantic_fingerprint,schema_version)
                    VALUES('C1','FORGED-EVENT','FORGED-TX','FORGED-TURN','FORGED-CMD','FORGED-INTENT','DOMAIN_EFFECT',
                    999,0,'PLAYER','P1',NULL,NULL,'CHARACTER','A','CHARACTER:A','FORGED-CHANGE','KNOWLEDGE',
                    NULL,'FORGED','1','FORGED-FINGERPRINT',?)""",
                arrayOf(PHASE30_EVENT_SCHEMA_VERSION)
            )
            val forged = runCatching {
                db.execSQL(
                    """INSERT INTO ${Phase37KnowledgeSchema.CLAIMS}(
                        campaign_uid,claim_uid,subject_kind_uid,subject_uid,predicate_uid,value_canonical,domain_uid,claim_schema_version)
                        VALUES('C1','FORGED-CLAIM','TARGET','X','about','FORGED','INVESTIGATION',?)""",
                    arrayOf(PHASE37_KNOWLEDGE_SCHEMA_VERSION)
                )
            }
            assertTrue(forged.isFailure)
        } finally {
            db.endTransaction()
        }
        assertTrue(KnowledgeStore(db, "C1").acquisitions().isEmpty())

        val admin = runCatching {
            withAdministrativeMutationAuthority(db, "C1") {
                db.execSQL(
                    """INSERT INTO ${Phase37KnowledgeSchema.CLAIMS}(
                        campaign_uid,claim_uid,subject_kind_uid,subject_uid,predicate_uid,value_canonical,domain_uid,claim_schema_version)
                        VALUES('C1','ADMIN-FORGE','TARGET','X','about','FORGED','INVESTIGATION',?)""",
                    arrayOf(PHASE37_KNOWLEDGE_SCHEMA_VERSION)
                )
            }
        }
        assertTrue(admin.isFailure)
        assertTrue(KnowledgeStore(db, "C1").acquisitions().isEmpty())
    }

    @Test fun snapshotReplayPreservesFullContradictoryKnowledgeHistoryAndProjection() = withDb { db ->
        init(db)
        CampaignSnapshotManager(db, "C1", snapshots).create()
        val c = claim("CLAIM-REPLAY-EXACT", "ENEMY-EAST")
        commit(
            db, "REPLAY-A",
            change(
                "REPLAY-A", holder("SCOUT"), c,
                method = KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,
                quality = quality(confidence = .91, precision = .77, reliability = .88, corroboration = 2, observed = 11)
            )
        )
        commit(
            db, "REPLAY-B",
            change(
                "REPLAY-B", holder("CAPTAIN"), c,
                method = KnowledgeAcquisitionMethods.REPORT,
                state = KnowledgeEpistemicState.CONTRADICTED,
                quality = quality(confidence = .54, precision = .42, reliability = .61, corroboration = 1, observed = 11),
                parent = "ACQ-REPLAY-A",
                sourceHolder = holder("SCOUT"),
                evidence = listOf(
                    KnowledgeEvidenceSpec(
                        "E-REPLAY-CONTRA", "CONTRADICTORY_REPORT", KnowledgeEvidencePolarity.CONTRADICTS,
                        sourceAcquisitionUid = "ACQ-REPLAY-A"
                    )
                )
            )
        )
        val tables = listOf(
            Phase37KnowledgeSchema.CLAIMS,
            Phase37KnowledgeSchema.ACQUISITIONS,
            Phase37KnowledgeSchema.EVIDENCE,
            Phase37KnowledgeSchema.STATES
        )
        val expectedDigests = tables.associateWith { TableDigest.compute(db, it) }
        val expectedScout = KnowledgeStore(db, "C1").acquisitions(holder("SCOUT"))
        val expectedCaptain = KnowledgeStore(db, "C1").acquisitions(holder("CAPTAIN"))
        val expectedEvidence = KnowledgeStore(db, "C1").evidence("ACQ-REPLAY-B")
        val expectedState = KnowledgeStore(db, "C1").states(holder("CAPTAIN"))

        val staged = CampaignSnapshotManager(db, "C1", snapshots).reconstructToVerifiedStaging()
        SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { restored ->
            tables.forEach { table -> assertEquals(expectedDigests[table], TableDigest.compute(restored, table)) }
            assertEquals(expectedScout, KnowledgeStore(restored, "C1").acquisitions(holder("SCOUT")))
            assertEquals(expectedCaptain, KnowledgeStore(restored, "C1").acquisitions(holder("CAPTAIN")))
            assertEquals(expectedEvidence, KnowledgeStore(restored, "C1").evidence("ACQ-REPLAY-B"))
            assertEquals(expectedState, KnowledgeStore(restored, "C1").states(holder("CAPTAIN")))
        }
    }

    @Test fun contextBuilderSourceUsesTypedPhase37ProjectionNotLegacyKnowledgeSql() {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val source = generateSequence(start) { it.parentFile }
            .map { File(it, "app/src/main/java/com/rpgos/app/ContextBuilder.kt") }
            .firstOrNull { it.isFile }
            ?: error("ContextBuilder source not found")
        val code = source.readText()
        assertTrue(code.contains("KnowledgeContextProjection"))
        assertTrue(code.contains("KnowledgeContextHolderDiscovery"))
        assertFalse(code.contains("FROM information_knowledge"))
        assertFalse(code.contains("JOIN information_facts"))
    }

    private fun withDb(block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use(block)
    }

    private fun init(db: SQLiteDatabase, vararg campaigns: String) {
        val targets = if (campaigns.isEmpty()) listOf("C1") else campaigns.toList()
        targets.forEach { GameplayRuntimeBootstrap.initialize(db, it) }
    }

    private fun holder(uid: String) = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, uid)

    private fun quality(
        confidence: Double = .9,
        precision: Double = .8,
        completeness: Double = .8,
        reliability: Double = .8,
        corroboration: Int = 1,
        observed: Long? = 1
    ) = KnowledgeQuality(confidence, precision, completeness, reliability, corroboration, observed)

    private fun claim(uid: String, value: String, domain: String = KnowledgeDomains.INVESTIGATION) =
        KnowledgeClaim(uid, "TARGET", "X", "about", value, domainUid = domain)

    private fun change(
        suffix: String,
        holder: KnowledgeHolderRef,
        claim: KnowledgeClaim,
        method: String = KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,
        state: KnowledgeEpistemicState = KnowledgeEpistemicState.KNOWN,
        quality: KnowledgeQuality = quality(),
        scope: KnowledgeScope = KnowledgeScope.PERSONAL,
        roleUid: String? = null,
        parent: String? = null,
        sourceHolder: KnowledgeHolderRef? = null,
        carrier: KnowledgeCarrierRef? = null,
        evidence: List<KnowledgeEvidenceSpec> = emptyList()
    ) = KnowledgeAcquisitionChange(
        claim,
        KnowledgeAcquisitionSpec(
            acquisitionUid = "ACQ-$suffix", holder = holder, methodUid = method, scope = scope,
            epistemicState = state, quality = quality, parentAcquisitionUid = parent, sourceHolder = sourceHolder,
            roleUid = roleUid, carrier = carrier
        ),
        evidence
    )

    private fun commit(
        db: SQLiteDatabase,
        command: String,
        change: KnowledgeAcquisitionChange,
        campaign: String = "C1"
    ): TurnExecutionResult<TurnCommitAppliedResult> {
        val p = proposal(command, change, campaign)
        return TurnTransactionBoundary.create(
            db, TurnTransactionIdentity(campaign, "TURN-$command", command, "TX-$command"), p
        ).commit()
    }

    private fun proposal(
        command: String,
        change: KnowledgeAcquisitionChange,
        campaign: String = "C1"
    ): CanonicalCampaignMutationProposal {
        knowledgeByCommand[command] = change
        val actor = CommandActorRef("PLAYER", "P1")
        val cmd = PlayerCommand(
            commandUid = command,
            campaignUid = campaign,
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 1, "CUR"),
            provenance = CommandProvenance("P37-TEST"),
            requestedEffectiveOrder = command.hashCode().toLong().let { if (it == Long.MIN_VALUE) 1L else kotlin.math.abs(it) + 1L }
        )
        val refs = LinkedHashSet<CampaignScopedDomainRef>()
        refs += CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1"))
        refs += CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A"))
        refs += CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B"))
        refs += CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        refs += CampaignScopedDomainRef(campaign, DomainRef(change.acquisition.holder.holderKindUid, change.acquisition.holder.holderUid))
        change.acquisition.sourceHolder?.let { refs += CampaignScopedDomainRef(campaign, DomainRef(it.holderKindUid, it.holderUid)) }
        change.evidence.forEach { e ->
            if (e.sourceRefKindUid != null && e.sourceRefUid != null) refs += CampaignScopedDomainRef(campaign, DomainRef(e.sourceRefKindUid, e.sourceRefUid))
        }
        val context = PlayerResolutionContext.createUnboundGeneric(campaign, actor, refs)
        val engine = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(KnowledgeComponent())))
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, cmd, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("admission rejected: ${admission.reasonUid}")
        }
    }

    private fun recordFact(
        db: SQLiteDatabase,
        campaign: String,
        truthUid: String,
        subjectKind: String,
        subjectUid: String,
        predicate: String,
        value: String,
        order: Long
    ) {
        withAdministrativeMutationAuthority(db, campaign) {
            CampaignTruthStore(db, campaign).record(
                kind = TruthKind.FACT,
                predicate = "$subjectKind:$predicate",
                provenance = Provenance(ProvenanceSourceType.WORLD_CANON, sourceId = truthUid, createdTurn = order, verified = true),
                subjectUid = subjectUid,
                objectValue = value,
                truthUid = truthUid,
                createdAt = order
            )
        }
    }

    private class KnowledgeComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "P37-KNOWLEDGE-COMPONENT",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TransferFundsCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            val knowledge = requireNotNull(knowledgeByCommand[command.commandUid])
            val changeUid = "CHANGE-${command.commandUid}"
            val holderRef = DomainRef(knowledge.acquisition.holder.holderKindUid, knowledge.acquisition.holder.holderUid)
            val actorRef = DomainRef(command.actor.actorKindUid, command.actor.actorUid)
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(
                    changes = listOf(PlayerDomainChange.create(changeUid, PHASE37_KNOWLEDGE_CHANGE_KIND, knowledge)),
                    eventIntents = listOf(
                        PlayerEventIntent.create(
                            eventIntentUid = "EVENT-${command.commandUid}",
                            eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
                            actorRef = actorRef,
                            targetRefs = listOf(holderRef),
                            causalChangeUids = listOf(changeUid),
                            payload = DomainEffectEventIntentPayload(holderRef, "RPGOS-EFFECT:KNOWLEDGE_ACQUISITION")
                        )
                    )
                )
            )
        }
    }

    companion object {
        private val knowledgeByCommand = mutableMapOf<String, KnowledgeAcquisitionChange>()
    }
}
