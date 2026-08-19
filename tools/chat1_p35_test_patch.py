from pathlib import Path

def rep(path,old,new):
 p=Path(path);s=p.read_text();n=s.count(old)
 if n!=1: raise SystemExit(f'{path}: expected 1 match got {n}')
 p.write_text(s.replace(old,new))

p='app/src/test/java/com/rpgos/app/Phase30To36PostAuditHardeningTest.kt'
rep(p,'''        val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(DivergenceTruthComponent())))
        val context=PlayerResolutionContext.createUnboundGeneric("C1",actor,refs)''','''        val binding=WorldPackRuleBinding("WORLD-A","1")
        val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(DivergenceTruthComponent())),
            worldRuleRegistry=WorldRuleProviderRegistry.of(listOf(PostAuditCanonProvider())),
            worldPackAuthority=WorldPackAuthoritySnapshot.single("C1",binding))
        val context=PlayerResolutionContext.create("C1",actor,refs,worldRuleMode=WorldRuleMode.Bound(binding))''')
marker='''    private class DivergenceTruthComponent :
        PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"POST-AUDIT-DIVERGENCE","1") {'''
provider='''    private class PostAuditCanonProvider : WorldRuleProvider("POST-AUDIT-CANON-PROVIDER","1","WORLD-A","1") {
        override fun canonicalExpectation(reference:CanonReference):CanonicalWorldExpectation? =
            if(reference.expectationUid=="CANON-WP-A") CanonicalWorldExpectation(reference,CanonDivergenceKind.OUTCOME,"CANON") else null
        override fun evaluate(request:WorldRuleRequest):WorldRuleDecision=WorldRuleDecision.Allowed.create("POST-AUDIT-CANON-RULE")
    }

'''
rep(p,marker,provider+marker)

# Append adversarial tests into existing Phase35 test before helper methods.
p='app/src/test/java/com/rpgos/app/Phase35CanonDivergenceTest.kt'
marker='''    private fun spec(uid: String, expected: String, actual: String) = CanonDivergenceSpec('''
tests=r'''    @Test fun forgedTurnAndAdminSqlContextsCannotCreateRecordedWithoutCanonicalEvidence() {
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            listOf("TURN","ADMIN").forEach { kind ->
                db.execSQL("DELETE FROM ${GameplayMutationDatabaseGuards.CONTEXT_TABLE}")
                db.execSQL("INSERT INTO ${GameplayMutationDatabaseGuards.CONTEXT_TABLE}(campaign_uid,capability_kind,depth) VALUES('C1',?,1)",arrayOf(kind))
                assertTrue(runCatching { rawRecordedInsert(db,"RAW-$kind") }.isFailure)
            }
            db.execSQL("DELETE FROM ${GameplayMutationDatabaseGuards.CONTEXT_TABLE}")
            assertTrue(CanonDivergenceStore(db,"C1").list().isEmpty())
        }
    }

    @Test fun administrativeAuthorityForeignCampaignAndMissingProvenanceCannotCallRecordCommitted() {
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            val s=spec("DIV-AUTH","CANON","CAMPAIGN")
            db.beginTransaction(); try {
                assertTrue(runCatching { withAdministrativeMutationAuthority(db,"C1") { CanonDivergenceStore(db,"C1").recordCommitted(s,TurnTransactionIdentity("C1","T","C","TX"),"E") } }.isFailure)
                assertTrue(runCatching { CanonDivergenceStore(db,"C1").recordCommitted(s,TurnTransactionIdentity("C2","T","C","TX"),"E") }.isFailure)
                assertTrue(runCatching { CanonDivergenceStore(db,"C1").recordCommitted(s,TurnTransactionIdentity("C1","T","C","TX"),"E") }.isFailure)
            } finally { db.endTransaction() }
        }
    }

    @Test fun unboundWorldPackAndCallerSuppliedAuthenticityMismatchesFailClosed() {
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            assertTrue(runCatching { proposalBound("UNBOUND",spec("DIV-U","CANON","CAMPAIGN"),false) }.isFailure)
            assertTrue(runCatching { proposalBound("UID",spec("DIV-UID","CANON","CAMPAIGN").copy(worldPackUid="WORLD-X"),true) }.isFailure)
            assertTrue(runCatching { proposalBound("VER",spec("DIV-VER","CANON","CAMPAIGN").copy(worldPackVersion="2"),true) }.isFailure)
            assertTrue(runCatching { proposalBound("EXPECT",spec("DIV-EXPECT","WRONG","CAMPAIGN"),true) }.isFailure)
            divergenceActualOverrides["ACTUAL"]="REAL"
            assertTrue(runCatching { proposalBound("ACTUAL",spec("DIV-ACTUAL","CANON","CLAIMED"),true) }.isFailure)
        }
    }

    @Test fun lifecycleLinksRequireExistingSameCampaignNonSelfLegalStatus() {
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1"); GameplayRuntimeBootstrap.initialize(db,"C2")
            commitBound(db,"BASE",spec("DIV-BASE","CANON","CAMPAIGN"),"C1")
            commitBound(db,"C2BASE",spec("DIV-C2","CANON","CAMPAIGN"),"C2")
            assertTrue(runCatching{commitBound(db,"SELF",spec("DIV-SELF","CANON","CAMPAIGN").copy(supersedesDivergenceUid="DIV-SELF"),"C1")}.isFailure)
            assertTrue(runCatching{commitBound(db,"MISS",spec("DIV-MISS","CANON","CAMPAIGN").copy(supersedesDivergenceUid="NONE"),"C1")}.isFailure)
            assertTrue(runCatching{commitBound(db,"CROSS",spec("DIV-CROSS","CANON","CAMPAIGN").copy(supersedesDivergenceUid="DIV-C2"),"C1")}.isFailure)
            assertTrue(runCatching{commitBound(db,"BADSTATUS",spec("DIV-BAD","CANON","CAMPAIGN").copy(status=CanonDivergenceStatus.RESOLVED),"C1")}.isFailure)
            commitBound(db,"SUPER",spec("DIV-SUPER","CANON","CAMPAIGN").copy(supersedesDivergenceUid="DIV-BASE"),"C1")
            commitBound(db,"RESOLVE",spec("DIV-RESOLVE","CANON","CAMPAIGN").copy(status=CanonDivergenceStatus.RESOLVED,resolvesDivergenceUid="DIV-SUPER"),"C1")
            assertEquals(setOf("DIV-BASE","DIV-SUPER","DIV-RESOLVE"),CanonDivergenceStore(db,"C1").list().map{it.spec.divergenceUid}.toSet())
        }
    }

    private fun rawRecordedInsert(db:SQLiteDatabase,uid:String){
        db.execSQL("""INSERT INTO ${Phase35CanonDivergenceSchema.TABLE}(divergence_uid,campaign_uid,canonical_subject_kind_uid,canonical_subject_uid,canonical_expectation_uid,world_pack_uid,world_pack_version,divergence_kind,expected_canonical_value,actual_campaign_value,lifecycle_status,created_transaction_uid,created_turn_uid,created_event_uid,provenance_status,divergence_schema_version,created_at_epoch_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(uid,"C1","CHARACTER","P1","CANON-EXPECTATION-1","WORLD-A","1","OUTCOME","CANON","CAMPAIGN","ACTIVE","TX-FAKE","TURN-FAKE","EVENT-FAKE","RECORDED",1,1L))
    }

    private fun commitBound(db:SQLiteDatabase,command:String,s:CanonDivergenceSpec,campaign:String)=
        TurnTransactionBoundary.create(db,TurnTransactionIdentity(campaign,"TURN-$command",command,"TX-$command"),proposalBound(command,s,true,campaign)).commit()

    private fun proposalBound(command:String,s:CanonDivergenceSpec,bound:Boolean,campaign:String="C1"):CanonicalCampaignMutationProposal {
        divergenceByCommand[command]=s
        val actor=CommandActorRef("PLAYER","P1")
        val cmd=PlayerCommand(commandUid=command,campaignUid=campaign,actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,payload=TransferFundsCommandPayload("A","B",1,"CUR"),provenance=CommandProvenance("P35-BOUND"),requestedEffectiveOrder=1)
        val refs=setOf(CampaignScopedDomainRef(campaign,DomainRef("PLAYER","P1")),CampaignScopedDomainRef(campaign,DomainRef("CHARACTER","P1")),CampaignScopedDomainRef(campaign,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A")),CampaignScopedDomainRef(campaign,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B")),CampaignScopedDomainRef(campaign,DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR")))
        val registry=PlayerResolutionComponentRegistry.of(listOf(TruthComponent()))
        val engine:PlayerDomainEngine; val context:PlayerResolutionContext
        if(bound){val binding=WorldPackRuleBinding("WORLD-A","1");engine=PlayerDomainEngine(registry,worldRuleRegistry=WorldRuleProviderRegistry.of(listOf(TestCanonProvider())),worldPackAuthority=WorldPackAuthoritySnapshot.single(campaign,binding));context=PlayerResolutionContext.create(campaign,actor,refs,worldRuleMode=WorldRuleMode.Bound(binding))}
        else{engine=PlayerDomainEngine(registry);context=PlayerResolutionContext.createUnboundGeneric(campaign,actor,refs)}
        return when(val a=CampaignMutationBoundary.resolveAndAdmit(campaign,engine,cmd,context)){is CampaignMutationAdmission.Accepted->a.proposal;is CampaignMutationAdmission.Rejected->error(a.reasonUid)}
    }

    private class TestCanonProvider:WorldRuleProvider("P35-TEST-PROVIDER","1","WORLD-A","1"){
        override fun canonicalExpectation(reference:CanonReference)=if(reference.expectationUid=="CANON-EXPECTATION-1")CanonicalWorldExpectation(reference,CanonDivergenceKind.OUTCOME,"CANON")else null
        override fun evaluate(request:WorldRuleRequest)=WorldRuleDecision.Allowed.create("P35-TEST-RULE")
    }

'''
rep(p,marker,tests+marker)
# Make the existing helper's formerly-unbound canonical divergence tests use a bound path while leaving SAME/no-divergence generic.
rep(p,'''        divergenceByCommand[command] = divergence
        val engine = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TruthComponent())))
        val context = PlayerResolutionContext.createUnboundGeneric(campaign, actor, setOf(''','''        divergenceByCommand[command] = divergence
        val refs = setOf(''')
rep(p,'''))
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, cmd, context)) {''',''' )
        val engine: PlayerDomainEngine
        val context: PlayerResolutionContext
        if(divergence!=null){
            val binding=WorldPackRuleBinding("WORLD-A","1")
            engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TruthComponent())),worldRuleRegistry=WorldRuleProviderRegistry.of(listOf(TestCanonProvider())),worldPackAuthority=WorldPackAuthoritySnapshot.single(campaign,binding))
            context=PlayerResolutionContext.create(campaign,actor,refs,worldRuleMode=WorldRuleMode.Bound(binding))
        } else {
            engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TruthComponent())))
            context=PlayerResolutionContext.createUnboundGeneric(campaign,actor,refs)
        }
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, cmd, context)) {''')
# Bind actual mismatch adversarial fixture to truth payload without changing spec.
rep(p,'''divergence?.actualCampaignValue ?: "CANON", null, null, null, divergence)''','''divergenceActualOverrides[command.commandUid] ?: divergence?.actualCampaignValue ?: "CANON", null, null, null, divergence)''')
rep(p,'''companion object { private val divergenceByCommand = mutableMapOf<String, CanonDivergenceSpec?>() }''','''companion object { private val divergenceByCommand = mutableMapOf<String, CanonDivergenceSpec?>(); private val divergenceActualOverrides=mutableMapOf<String,String>() }''')
print('Phase35 tests patched')
