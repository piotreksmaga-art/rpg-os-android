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

    @Test fun persistedRoleClearanceGrantRevocationExpiryAndCampaignIsolationFlowThroughGateway(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val a=player();val reads=ProtectedCampaignReadRepository.borrowed(db,"C"){null}
        val roleReq=AccessRequirement("ROLE-POLICY",requiredRoleUids=setOf("R"))
        fun read(req:AccessRequirement,uid:String="S")=reads.policyRows(a,purpose(),VisibilitySubjectKinds.RELATIONSHIP_DATA,uid,req){listOf("SECRET")}
        assertTrue(read(roleReq) is ProtectedReadResult.Deny)
        apply(db,"C",1,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"R1",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.ROLE.name,"R",validFromOrder=1))
        assertTrue(read(roleReq) is ProtectedReadResult.Allow)
        val clearanceReq=AccessRequirement("CLR-POLICY",requiredClearanceUids=setOf("C2"))
        assertTrue(read(clearanceReq) is ProtectedReadResult.Deny)
        apply(db,"C",2,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"CL1",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.CLEARANCE.name,"C2",validFromOrder=2))
        assertTrue(read(clearanceReq) is ProtectedReadResult.Allow)
        val grantReq=AccessRequirement("SECRET-POLICY",explicitGrantRequired=true,carrier=InformationCarrierRef("C",VisibilitySubjectKinds.RELATIONSHIP_DATA,"S"))
        apply(db,"C",3,AccessAuthorityChange(AccessOperation.SET_CARRIER_ACCESS,"G1",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.EXPLICIT.name,"SECRET-POLICY",VisibilitySubjectKinds.RELATIONSHIP_DATA,"S",3))
        assertTrue(read(grantReq) is ProtectedReadResult.Allow)
        apply(db,"C",4,AccessAuthorityChange(AccessOperation.REVOKE_GRANT,"G2",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.EXPLICIT.name,"SECRET-POLICY",VisibilitySubjectKinds.RELATIONSHIP_DATA,"S",4))
        assertTrue(read(grantReq) is ProtectedReadResult.Deny)
        apply(db,"C",5,AccessAuthorityChange(AccessOperation.GRANT,"TMP",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.TEMPORARY.name,"TEMP-P",validFromOrder=5,validUntilOrder=5))
        apply(db,"C",6,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"ADV",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.ORGANIZATION.name,"ORG",validFromOrder=6))
        assertTrue(read(AccessRequirement("TEMP-P",explicitGrantRequired=true)) is ProtectedReadResult.Deny)
        assertTrue(read(AccessRequirement("UNRELATED",explicitGrantRequired=true)) is ProtectedReadResult.Deny)
        val cross=AccessRequirement("X",explicitGrantRequired=true,carrier=InformationCarrierRef("C2",VisibilitySubjectKinds.RELATIONSHIP_DATA,"S"))
        assertTrue(read(cross) is ProtectedReadResult.Deny)
        db.close()
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
        val view=source("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt")
        assertTrue(view.contains("prepareSource(source.visualUid, source.uri)"));assertTrue(view.contains("sourceImageSha256 = prepared.sha256"));assertTrue(view.contains("editPrepared"))
        val edit=source("app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt")
        assertTrue(edit.contains("digestBytes(prepared.bytes)"));assertTrue(edit.contains("prepared.bytes.toRequestBody"))
    }

    private fun repoRoot():File{var f=File(System.getProperty("user.dir")).canonicalFile;repeat(8){if(File(f,"app/src/main/java").isDirectory)return f;f=f.parentFile?:return@repeat};error("repo root not found")}
    private fun source(path:String)=File(repoRoot(),path).readText()
}
