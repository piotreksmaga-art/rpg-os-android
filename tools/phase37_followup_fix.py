from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]

def rep(rel, old, new):
    p=ROOT/rel; s=p.read_text()
    if s.count(old)!=1: raise SystemExit(f'anchor {rel} count={s.count(old)}: {old[:100]!r}')
    p.write_text(s.replace(old,new,1)); print('patched',rel)

PDE='app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt'
TEST='app/src/test/java/com/rpgos/app/Phase37WorldActorKnowledgeTest.kt'

rep(PDE, '''                if (payload.evidence.any { it.sourceRefKindUid != null && it.sourceRefUid != null }) {
                    payload.evidence.forEach { e ->
                        if (e.sourceRefKindUid != null && e.sourceRefUid != null) add(DomainRef(e.sourceRefKindUid, e.sourceRefUid))
                    }
                }''', '''                payload.evidence.forEach { e ->
                    e.sourceRef?.let { source ->
                        if (source.scope == KnowledgeReferenceScope.CAMPAIGN) {
                            add(DomainRef(source.kindUid, source.entityUid))
                        }
                    }
                }''')

rep(TEST, '''        change.evidence.forEach { e ->
            if (e.sourceRefKindUid != null && e.sourceRefUid != null) refs += CampaignScopedDomainRef(campaign, DomainRef(e.sourceRefKindUid, e.sourceRefUid))
        }''', '''        change.evidence.forEach { e ->
            e.sourceRef?.let { source ->
                if (source.scope == KnowledgeReferenceScope.CAMPAIGN) {
                    refs += CampaignScopedDomainRef(requireNotNull(source.campaignUid), DomainRef(source.kindUid, source.entityUid))
                }
            }
        }''')

anchor='''    private fun withDb(block: (SQLiteDatabase) -> Unit) {'''
extra=r'''    @Test fun projectionRejectsStateWrongClaim() = withDb { db ->
        init(db)
        commit(db, "CORRUPT-CLAIM", change("CORRUPT-CLAIM", holder("A"), claim("CLAIM-CORRUPT-CLAIM", "X")))
        corruptStateColumn(db, "claim_uid", "WRONG-CLAIM")
        assertPhase37Corruption(db)
    }

    @Test fun projectionRejectsStateWrongScope() = withDb { db ->
        init(db)
        commit(db, "CORRUPT-SCOPE", change("CORRUPT-SCOPE", holder("A"), claim("CLAIM-CORRUPT-SCOPE", "X"),
            scope = KnowledgeScope.ROLE_ACCESSIBLE, roleUid = "ROLE-A"))
        corruptStateColumn(db, "scope_uid", KnowledgeScope.PERSONAL.name)
        assertPhase37Corruption(db)
    }

    @Test fun projectionRejectsRoleAccessibleStateWrongRole() = withDb { db ->
        init(db)
        commit(db, "CORRUPT-ROLE", change("CORRUPT-ROLE", holder("A"), claim("CLAIM-CORRUPT-ROLE", "X"),
            scope = KnowledgeScope.ROLE_ACCESSIBLE, roleUid = "ROLE-A"))
        corruptStateColumn(db, "role_uid", "ROLE-B")
        assertPhase37Corruption(db)
    }

    @Test fun projectionRejectsStatePointingToForeignCampaignAcquisition() = withDb { db ->
        init(db, "C1", "C2")
        commit(db, "LOCAL-STATE", change("LOCAL-STATE", holder("A", "C1"), claim("CLAIM-LOCAL", "X")), campaign = "C1")
        commit(db, "FOREIGN-ACQ", change("FOREIGN-ACQ", holder("B", "C2"), claim("CLAIM-FOREIGN", "Y")), campaign = "C2")
        corruptStateColumn(db, "latest_acquisition_uid", "ACQ-FOREIGN-ACQ")
        assertPhase37Corruption(db)
    }

    @Test fun projectionRejectsEvidenceClaimOrAcquisitionMismatch() = withDb { db ->
        init(db)
        commit(db, "CORRUPT-EVIDENCE", change("CORRUPT-EVIDENCE", holder("A"), claim("CLAIM-CORRUPT-EVIDENCE", "X"), evidence = listOf(
            KnowledgeEvidenceSpec("E-CORRUPT", "REPORT", KnowledgeEvidencePolarity.SUPPORTS)
        )))
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_p37_evidence_no_update")
        withAdministrativeMutationAuthority(db, "C1") {
            db.execSQL("UPDATE ${Phase37KnowledgeSchema.EVIDENCE} SET claim_uid='WRONG-CLAIM' WHERE campaign_uid='C1' AND evidence_uid='E-CORRUPT'")
        }
        GameplayRuntimeBootstrap.initialize(db, "C1")
        assertPhase37Corruption(db)
    }

    @Test fun projectionRejectsInvalidParentSourceLineage() = withDb { db ->
        init(db)
        commit(db, "CORRUPT-PARENT", change("CORRUPT-PARENT", holder("A"), claim("CLAIM-CORRUPT-PARENT", "X")))
        db.execSQL("DROP TRIGGER IF EXISTS rpgos_p37_acquisition_no_update")
        withAdministrativeMutationAuthority(db, "C1") {
            db.execSQL("""UPDATE ${Phase37KnowledgeSchema.ACQUISITIONS}
                SET parent_acquisition_uid='MISSING-PARENT',source_holder_kind_uid='CHARACTER',source_holder_uid='SOURCE'
                WHERE campaign_uid='C1' AND acquisition_uid='ACQ-CORRUPT-PARENT'""")
        }
        GameplayRuntimeBootstrap.initialize(db, "C1")
        assertPhase37Corruption(db)
    }

    @Test fun fullyValidLineageProjectionPasses() = withDb { db ->
        init(db)
        val c = claim("CLAIM-VALID-LINEAGE", "X")
        commit(db, "VALID-SOURCE", change("VALID-SOURCE", holder("SOURCE"), c))
        commit(db, "VALID-TARGET", change("VALID-TARGET", holder("TARGET"), c,
            method = KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION,
            parent = "ACQ-VALID-SOURCE", sourceHolder = holder("SOURCE"),
            evidence = listOf(KnowledgeEvidenceSpec("E-VALID", "REPORT", KnowledgeEvidencePolarity.SUPPORTS,
                sourceAcquisitionUid = "ACQ-VALID-SOURCE",
                sourceRef = KnowledgeSourceRef.campaign("C1", "REPORT", "REPORT-VALID")))))
        Phase37KnowledgeLineageIntegrity.requireCampaign(db, "C1")
        val rows = KnowledgeContextProjection(db, "C1").forHolders(listOf(holder("TARGET")), false)
        assertEquals(1, rows.size)
        assertEquals("CLAIM-VALID-LINEAGE", rows.single()["claim_uid"])
    }

    @Test fun crossCampaignEvidenceCarrierFailsClosed() = withDb { db ->
        init(db, "C1", "C2")
        val foreignCarrier = KnowledgeCarrierRef(KnowledgeCarrierKinds.REPORT, "REPORT-X", "C2")
        val result = runCatching {
            commit(db, "X-EVID-CARRIER", change("X-EVID-CARRIER", holder("A"), claim("C-XEC", "X"), evidence = listOf(
                KnowledgeEvidenceSpec("E-XEC", "REPORT", KnowledgeEvidencePolarity.SUPPORTS, sourceCarrier = foreignCarrier)
            )))
        }
        assertTrue(result.isFailure)
        assertTrue(KnowledgeStore(db, "C1").acquisitions().isEmpty())
    }

    private fun corruptStateColumn(db: SQLiteDatabase, column: String, value: String) {
        val primary = Phase37GuardDefinitionIntegrity.primaryGuardName(Phase37KnowledgeSchema.STATES, "update")
        val seal = Phase37GuardDefinitionIntegrity.sealGuardName(Phase37KnowledgeSchema.STATES, "update")
        db.execSQL("DROP TRIGGER IF EXISTS $primary")
        db.execSQL("DROP TRIGGER IF EXISTS $seal")
        withAdministrativeMutationAuthority(db, "C1") {
            db.execSQL("UPDATE ${Phase37KnowledgeSchema.STATES} SET $column=? WHERE campaign_uid='C1'", arrayOf(value))
        }
        GameplayRuntimeBootstrap.initialize(db, "C1")
    }

    private fun assertPhase37Corruption(db: SQLiteDatabase) {
        val failure = runCatching { Phase37KnowledgeLineageIntegrity.requireCampaign(db, "C1") }.exceptionOrNull()
        assertTrue("expected typed Phase37 corruption but was $failure", failure is Phase37KnowledgeCorruptionException)
        val projectionFailure = runCatching { KnowledgeContextProjection(db, "C1").forHolders(listOf(holder("A")), false) }.exceptionOrNull()
        assertTrue("projection must fail closed but was $projectionFailure", projectionFailure is Phase37KnowledgeCorruptionException)
    }

'''
rep(TEST, anchor, extra+anchor)
print('followup fix complete')
