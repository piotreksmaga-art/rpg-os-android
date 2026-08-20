from pathlib import Path

p = Path("app/src/test/java/com/rpgos/app/Phase37WorldActorKnowledgeTest.kt")
text = p.read_text()
marker = '''    private fun withDb(block: (SQLiteDatabase) -> Unit) {
'''
if text.count(marker) != 1:
    raise SystemExit(f"expected one helper marker, found {text.count(marker)}")
addition = r'''    @Test fun canonicalProjectionCorruptionFailsClosedInsteadOfEmptyKnowledge() = withDb { db ->
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

'''
p.write_text(text.replace(marker, addition + marker, 1))
print("appended Phase37 finalization adversarial regressions")
