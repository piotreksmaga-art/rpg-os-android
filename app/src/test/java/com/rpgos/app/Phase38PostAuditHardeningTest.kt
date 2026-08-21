package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class Phase38PostAuditHardeningTest {
    private fun apply(db:SQLiteDatabase,campaign:String,order:Long,p:AccessAuthorityChange){
        if(!Phase38AccessAuthoritySchema.isReady(db))Phase38AccessAuthoritySchema.ensureReady(db)
        db.beginTransaction();try{AccessAuthorityStore(db,campaign).apply(TurnTransactionIdentity(campaign,"T$order","CMD$order","TX$order"),"CH$order:${p.recordUid}",p,order);db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    private fun player(campaign:String="C")=VisibilityAudienceFactory.player(campaign)
    private fun purpose(campaign:String="C")=PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)

    @Test fun accessMutationValidatorRejectsOperationKindAndBadDelegation(){
        assertTrue(runCatching{AccessAuthorityChange(AccessOperation.GRANT,"X","ENTITY","A",AccessBindingKind.ROLE.name,"ROLE",validFromOrder=1)}.isFailure)
        assertTrue(runCatching{AccessAuthorityChange(AccessOperation.BIND_COGNITION,"X","ENTITY","A",AccessBindingKind.ROLE.name,"H",subjectKindUid="ORG",subjectUid="H",validFromOrder=1)}.isFailure)
        assertTrue(runCatching{AccessAuthorityChange(AccessOperation.GRANT,"X","ENTITY","A",AccessGrantKind.DELEGATED.name,"POLICY",validFromOrder=1)}.isFailure)
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val delegated=AccessAuthorityChange(AccessOperation.GRANT,"DG","ENTITY","A",AccessGrantKind.DELEGATED.name,"POLICY",validFromOrder=1,delegatedByPrincipalUid="B")
        db.beginTransaction();try{assertTrue(runCatching{AccessAuthorityStore(db,"C").apply(TurnTransactionIdentity("C","T","CMD","TX"),"CH",delegated,1)}.isFailure)}finally{db.endTransaction();db.close()}
    }

    @Test fun canonicalCognitionSupportsZeroOneManyAndNeverInfersUidAsCharacter(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val orgAudience=AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ORGANIZATION","X"))
        var reads=ProtectedCampaignReadRepository.borrowed(db,"C"){null}
        assertTrue(reads.trustedPrincipal(orgAudience)?.cognitionHolders.orEmpty().isEmpty())
        apply(db,"C",1,AccessAuthorityChange(AccessOperation.BIND_COGNITION,"C1","ORGANIZATION","X",AccessBindingKind.COGNITION.name,"MAP",subjectKindUid="ORGANIZATION",subjectUid="H1",validFromOrder=1))
        apply(db,"C",2,AccessAuthorityChange(AccessOperation.BIND_COGNITION,"C2","ORGANIZATION","X",AccessBindingKind.COGNITION.name,"MAP",subjectKindUid="SHARED_COLLECTIVE",subjectUid="H2",validFromOrder=1))
        reads=ProtectedCampaignReadRepository.borrowed(db,"C"){null}
        val holders=reads.trustedPrincipal(orgAudience)!!.cognitionHolders
        assertEquals(setOf(KnowledgeHolderRef("ORGANIZATION","H1","C"),KnowledgeHolderRef("SHARED_COLLECTIVE","H2","C")),holders)
        assertFalse(holders.contains(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,"X","C")))
        val injected=AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","U"),listOf(KnowledgeHolderRef("FAKE","F","C")))
        assertTrue(reads.trustedPrincipal(injected)?.cognitionHolders.orEmpty().isEmpty())
        db.close()
    }

    @Test fun persistedAuthorityChangesFlowThroughNormalDomainReaders(){
        val save=SQLiteDatabase.create(null)
        val world=SQLiteDatabase.create(null)
        Phase38AccessAuthoritySchema.ensureReady(save)
        save.execSQL("CREATE TABLE relationships_v2(entity_a_uid TEXT,entity_b_uid TEXT,other_entity_uid TEXT,relationship_type TEXT,relationship_score REAL)")
        save.execSQL("INSERT INTO relationships_v2 VALUES('A','B','B','ALLY',75.0)")
        save.execSQL("CREATE TABLE country_economies(country_uid TEXT,treasury TEXT,prosperity TEXT,stability TEXT)")
        save.execSQL("INSERT INTO country_economies VALUES('LAND','100','80','90')")
        save.execSQL("CREATE TABLE political_entities(political_uid TEXT,display_name TEXT,legitimacy TEXT,influence TEXT,stability TEXT)")
        save.execSQL("INSERT INTO political_entities VALUES('P','Council','70','60','50')")
        world.execSQL("CREATE TABLE organization_definitions_v3(organization_uid TEXT,name TEXT,organization_type TEXT,active_status TEXT)")
        world.execSQL("INSERT INTO organization_definitions_v3 VALUES('ORG','Order','FACTION','ACTIVE')")
        val audience=player();val purpose=purpose()
        val npc=NpcWorldDashboardReader(world,save);val social=SocialReader(world,save)

        assertTrue(npc.relationEdges(audience,purpose).isEmpty())
        apply(save,"C",1,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"R1",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.ROLE.name,ProtectedSubjectAccessRegistry.RELATIONSHIP_READ_ROLE_UID,validFromOrder=1))
        assertEquals(1,npc.relationEdges(audience,purpose).size)

        assertTrue(npc.economies(audience,purpose).isEmpty())
        apply(save,"C",2,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"CL1",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.CLEARANCE.name,ProtectedSubjectAccessRegistry.ECONOMY_READ_CLEARANCE_UID,validFromOrder=2))
        assertEquals(1,npc.economies(audience,purpose).size)

        apply(save,"C",3,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"ORG-MEMBER",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.ORGANIZATION.name,"ORG",validFromOrder=3))
        assertTrue("organization membership alone must not disclose an unrelated protected collection",social.organizations(audience,purpose).isEmpty())
        apply(save,"C",4,AccessAuthorityChange(AccessOperation.SET_CARRIER_ACCESS,"G1",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.EXPLICIT.name,ProtectedSubjectAccessRegistry.ORGANIZATION_READ_POLICY_UID,VisibilitySubjectKinds.ORGANIZATION_DATA,"ORGANIZATIONS",4))
        assertEquals(1,social.organizations(audience,purpose).size)
        apply(save,"C",5,AccessAuthorityChange(AccessOperation.REVOKE_GRANT,"G2",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.EXPLICIT.name,ProtectedSubjectAccessRegistry.ORGANIZATION_READ_POLICY_UID,VisibilitySubjectKinds.ORGANIZATION_DATA,"ORGANIZATIONS",5))
        assertTrue(social.organizations(audience,purpose).isEmpty())

        apply(save,"C2",6,AccessAuthorityChange(AccessOperation.SET_CARRIER_ACCESS,"CROSS",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.EXPLICIT.name,ProtectedSubjectAccessRegistry.ORGANIZATION_READ_POLICY_UID,VisibilitySubjectKinds.ORGANIZATION_DATA,"ORGANIZATIONS",0))
        assertTrue("cross-campaign grant must never authorize campaign C",social.organizations(audience,purpose).isEmpty())

        apply(save,"C",7,AccessAuthorityChange(AccessOperation.SET_CARRIER_ACCESS,"TMP",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.TEMPORARY.name,ProtectedSubjectAccessRegistry.POLITICS_READ_POLICY_UID,VisibilitySubjectKinds.POLITICS_DATA,"POLITICS",7,7))
        assertEquals(1,social.politics(audience,purpose).size)
        apply(save,"C",8,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"ADV",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.ORGANIZATION.name,"OTHER",validFromOrder=8))
        assertTrue("expired grant must not authorize",social.politics(audience,purpose).isEmpty())

        save.close();world.close()
    }

    @Test fun fullToDetailedReductionPhysicallyRemovesExactProtectedPayload(){
        val a=player();val env=VisibilityAuthorityService().envelope(a,purpose())
        val bundle=ContextBundle(emptyMap(),emptyMap(),emptyMap(),listOf(mapOf("thread_uid" to "T","title" to "safe","description" to "THREAD-SECRET")),emptyList(),listOf(mapOf("predicate" to "p","object" to "KNOWLEDGE-SECRET")),emptyList(),emptyList(),listOf(mapOf("secret" to "CONSTRAINT-SECRET")),listOf(mapOf("chapter" to 1,"title" to "safe","decisions_json" to "DECISION-SECRET")),listOf(mapOf("summary" to "MEMORY-SECRET")),campaignTruth=listOf(mapOf("exact" to "TRUTH-SECRET")),playerState=mapOf("runtime" to "STATE-SECRET"),visibilityEnvelope=env)
        val detailed=bundle.reduceDisclosureTo(DisclosureLevel.DETAILED)
        val dump=detailed.toString()
        listOf("THREAD-SECRET","KNOWLEDGE-SECRET","CONSTRAINT-SECRET","DECISION-SECRET","MEMORY-SECRET","TRUTH-SECRET","STATE-SECRET").forEach{assertFalse(dump.contains(it))}
        assertEquals(DisclosureLevel.DETAILED,detailed.visibilityEnvelope.maximumDisclosure)
    }

    @Test fun perceptionInputsRequireRuntimeIssuerAndGatewayRejectsUntrustedDescriptor(){
        assertTrue(java.lang.reflect.Modifier.isPrivate(PerceptionSignal::class.java.declaredConstructors.single().modifiers))
        assertTrue(java.lang.reflect.Modifier.isPrivate(PerceptionCapability::class.java.declaredConstructors.single().modifiers))
        val fixture=Phase38TrustedTestAuthority.playerCharacter("C","PC")
        val cap=Phase38PerceptionRuntimeAuthority.issueCapability(fixture.trusted,PerceptionCapabilityRef("C","CAP"),fixture.trusted.principal,setOf("CH"),0.1,DisclosureLevel.DISCLOSE_FULL)
        val sig=Phase38PerceptionRuntimeAuthority.issueSignal("C",PerceptionSignalRef("C","SIG"),"K",1.0,mapOf("presence" to true),PerceptionUncertainty(1.0,1.0,1.0))
        val rules=PerceptionWorldRules("R",mapOf("K" to setOf("CH")),emptyMap(),emptyMap())
        val gateway=PerceptionRuntimeGateway(TrustedPrincipalResolver{aud->if(aud==fixture.audience)fixture.trusted else null},TrustedPerceptionSignalSource{_,_->sig},TrustedPerceptionCapabilitySource{_,_->listOf(cap)})
        assertEquals(PerceptionResultState.DETECTED,gateway.evaluate(fixture.audience,sig.ref,rules).state)
        val forged=AudienceContext("C",AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","OTHER"))
        assertEquals(PerceptionResultState.DENIED,gateway.evaluate(forged,sig.ref,rules).state)
    }

    @Test fun productionIntegrationUsesTrustedContextGatewayPreparedEditAndNoDescriptorDiagnosticBypass(){
        val context=source("app/src/main/java/com/rpgos/app/ContextBuilder.kt")
        assertFalse(context.contains("val diagnostic = audience.audienceKindUid"))
        assertFalse(context.contains("KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, principal.uid"))
        assertTrue(context.contains("protectedReads.trustedPrincipal(audience)"))
        assertTrue(context.contains("protectedReads.diagnosticRows"))
        val panel=source("app/src/main/java/com/rpgos/app/CharacterPanel.kt")
        assertTrue(panel.contains("ProtectedReadResult<PlayerStateSnapshot>"));assertFalse(panel.contains("visibility.project(request)"))
        val store=source("app/src/main/java/com/rpgos/app/LocalGameStore.kt")
        assertTrue(store.contains("reads.playerState(audience,purpose,playerUid)"))
        val protected=source("app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt")
        assertTrue(protected.contains("ProtectedSubjectAccessRegistry.requirementFor(request.subject)"))
        assertFalse(protected.contains("read(request:VisibilityRequest,requirement:AccessRequirement"))
        val npc=source("app/src/main/java/com/rpgos/app/NpcWorldDashboardReader.kt")
        val social=source("app/src/main/java/com/rpgos/app/SocialReader.kt")
        val world=source("app/src/main/java/com/rpgos/app/WorldReader.kt")
        assertTrue(npc.contains(".policyRows("));assertTrue(npc.contains(".protectedRows("))
        assertTrue(social.contains(".policyRows("))
        assertFalse(npc.contains("val diagnostic = audience.audienceKindUid"))
        assertFalse(world.contains("val diagnostic = audience.audienceKindUid"))
        val view=source("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt")
        assertTrue(view.contains("prepareSource(source.visualUid, source.uri)"));assertTrue(view.contains("sourceImageSha256 = prepared.sha256"));assertTrue(view.contains("editPrepared"))
        val edit=source("app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt")
        assertTrue(edit.contains("digestBytes(prepared.bytes)"));assertTrue(edit.contains("prepared.bytes.toRequestBody"))
    }

    private fun repoRoot():File{var f=File(System.getProperty("user.dir")).canonicalFile;repeat(8){if(File(f,"app/src/main/java").isDirectory)return f;f=f.parentFile?:return@repeat};error("repo root not found")}
    private fun source(path:String)=File(repoRoot(),path).readText()
}