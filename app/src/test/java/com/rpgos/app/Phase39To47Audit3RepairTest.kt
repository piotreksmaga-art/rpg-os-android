package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase39To47Audit3RepairTest {

    @Test fun causalFrontierStopsAtHiddenIntermediateNode(){
        graphDb().use{db->
            event(db,"A",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"VISIBLE-A")
            event(db,"B",VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"HIDDEN-B")
            event(db,"C",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"VISIBLE-C")
            relation(db,"R-AB","A","B",1)
            relation(db,"R-BC","B","C",2)
            val player=VisibilityAudienceFactory.player("C")
            grantRelation(db,"C",player.principal!!,"R-AB",3)
            grantRelation(db,"C",player.principal!!,"R-BC",4)
            val result=graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"A",depth=8,limit=10) as StructuredRetrievalResult.Value
            assertEquals(listOf("R-AB"),result.records.map{it.recordUid})
            val serialized=result.records.toString()
            assertFalse(serialized.contains("R-BC"))
            assertFalse(serialized.contains("VISIBLE-C"))
            assertFalse(serialized.contains("HIDDEN-B"))
            assertEquals("DENIED",result.records.single().values["target_disclosure_state"])
        }
    }

    @Test fun partialCausalProjectionDoesNotExposeCanonicalFingerprintButDiagnosticDoes(){
        graphDb().use{db->
            event(db,"A",VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"VISIBLE-A")
            event(db,"B",VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,"HIDDEN-B")
            relation(db,"R-AB","A","B",1,fingerprint="CANONICAL-FP-SECRET")
            val player=VisibilityAudienceFactory.player("C")
            grantRelation(db,"C",player.principal!!,"R-AB",2)
            val partial=(graph(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),"A") as StructuredRetrievalResult.Value).records.single()
            assertEquals("CAUSAL_RELATION_PROJECTED",partial.provenanceUid)
            assertFalse(partial.toString().contains("CANONICAL-FP-SECRET"))

            val fixture=Phase38TrustedTestAuthority.diagnostic("C")
            val full=(graph(db,"C",fixture.audience,PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),"A",trusted=fixture.trusted) as StructuredRetrievalResult.Value).records.single()
            assertEquals("CAUSAL_RELATION:CANONICAL-FP-SECRET",full.provenanceUid)
            assertEquals("HIDDEN-B",full.values["target_subject_uid"])
        }
    }

    @Test fun selfAccessHistoryRedactsThirdPartyIdentityAndCanonicalCorrelationKeys(){
        SQLiteDatabase.create(null).use{db->
            val player=VisibilityAudienceFactory.player("C")
            grantAuthority(db,"C",player.principal!!,"CANONICAL-SECRET-A","SECRET-SUBJECT-A",5)
            grantAuthority(db,"C",player.principal!!,"CANONICAL-SECRET-B","SECRET-SUBJECT-B",6)
            val result=temporal(db,"C",player,PurposeContext("C",VisibilityPurposeKinds.PLAYER_UI),player.principal!!,10) as TemporalResult.Value
            assertEquals(2,result.records.size)
            result.records.forEach{record->
                assertFalse(record.values.containsKey("subject_kind_uid"))
                assertFalse(record.values.containsKey("subject_uid"))
                assertEquals(true,record.values["subject_scoped"])
                assertTrue(record.recordUid.startsWith("ACCESS_HISTORY_PROJECTED:"))
                assertTrue(record.provenanceUid!!.startsWith("ACCESS_AUTHORITY_PROJECTED:"))
            }
            val serialized=result.records.toString()
            listOf("CANONICAL-SECRET-A","CANONICAL-SECRET-B","SECRET-SUBJECT-A","SECRET-SUBJECT-B").forEach{secret->assertFalse(serialized.contains(secret))}
        }
    }

    @Test fun trustedDiagnosticRetainsCanonicalAccessHistoryDetail(){
        SQLiteDatabase.create(null).use{db->
            val target=VisibilityPrincipalRef("ENTITY","TARGET")
            grantAuthority(db,"C",target,"CANONICAL-DIAG","SECRET-SUBJECT",5)
            val fixture=Phase38TrustedTestAuthority.diagnostic("C")
            val reads=ProtectedCampaignReadRepository.borrowedTrusted(db,"C",{null},fixture.trusted)
            val result=temporalWithReads(reads,fixture.audience,PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION),target,10) as TemporalResult.Value
            val record=result.records.single()
            assertEquals("CANONICAL-DIAG",record.recordUid)
            assertEquals("SECRET-SUBJECT",record.values["subject_uid"])
            assertTrue(record.provenanceUid!!.contains("CANONICAL-DIAG"))
        }
    }

    @Test fun crossCampaignTemporalMismatchIsExactlyDenied(){
        SQLiteDatabase.create(null).use{db->
            val target=VisibilityPrincipalRef(AudienceKinds.PLAYER,"HUMAN_PLAYER")
            putBinding(db,"C1",target,"SELF",5)
            val audience=VisibilityAudienceFactory.player("C2")
            val reads=ProtectedCampaignReadRepository.borrowed(db,"C1"){null}
            val result=temporalWithReads(reads,audience,PurposeContext("C2",VisibilityPurposeKinds.PLAYER_UI),target,10,"C2")
            assertTrue(result is TemporalResult.Denied)
            assertEquals("CROSS_CAMPAIGN",(result as TemporalResult.Denied).reasonUid)
        }
    }

    @Test fun phase47RejectsDifferentProvider(){
        val fixture=iterationFixture()
        val attack=fixture.request.copy(providerUid="Q")
        assertTrue(runCatching{fixture.pipeline(attack).execute(fixture.plan,profile())}.isFailure)
    }

    @Test fun phase47RejectsUnauthorizedOperation(){
        val fixture=iterationFixture()
        val attack=fixture.request.copy(operationUid="OTHER")
        assertTrue(runCatching{fixture.pipeline(attack).execute(fixture.plan,profile())}.isFailure)
    }

    @Test fun phase47RejectsUnrelatedTargetExpansion(){
        val fixture=iterationFixture()
        val attack=fixture.request.copy(filters=fixture.request.filters+mapOf("subject_uid" to "T2"))
        assertTrue(runCatching{fixture.pipeline(attack).execute(fixture.plan,profile())}.isFailure)
    }

    @Test fun phase47AllowsLegitimateRefinementWithinOriginalStep(){
        val fixture=iterationFixture()
        val legal=fixture.request.copy(filters=fixture.request.filters+mapOf("detail" to "more"))
        val result=fixture.pipeline(legal).execute(fixture.plan,profile())
        assertEquals("CONTEXT_COMPLETE",result.terminationUid)
        assertEquals("FOUND",result.budgeted.includedSegments.single().records.single().recordUid)
    }

    @Test fun phase47StillRejectsAudienceCampaignAndPurposeEscalation(){
        val fixture=iterationFixture()
        val audienceAttack=fixture.request.copy(audience=AudienceContext("C",AudienceKinds.GM_RUNTIME))
        assertTrue(runCatching{fixture.pipeline(audienceAttack).execute(fixture.plan,profile())}.isFailure)

        val campaignAttack=StructuredRetrievalRequest(
            fixture.request.requestUid,"C2",fixture.request.providerUid,fixture.request.operationUid,fixture.request.filters,fixture.request.limit,
            AudienceContext("C2",AudienceKinds.INTERNAL_SYSTEM),PurposeContext("C2",VisibilityPurposeKinds.INTERNAL_SIMULATION),fixture.request.atOrder
        )
        assertTrue(runCatching{fixture.pipeline(campaignAttack).execute(fixture.plan,profile())}.isFailure)

        val purposeAttack=fixture.request.copy(purpose=PurposeContext("C",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION))
        assertTrue(runCatching{fixture.pipeline(purposeAttack).execute(fixture.plan,profile())}.isFailure)
    }

    @Test fun schedulerClaimIsAtomicAndSecondClaimFails(){
        SQLiteDatabase.create(null).use{db->
            val store=scheduled(db,"E")
            transition(db,store,"TX1","CLAIM-1","E",ScheduledEvaluationState.CLAIMED,20)
            val second=runCatching{transition(db,store,"TX2","CLAIM-2","E",ScheduledEvaluationState.CLAIMED,20)}
            assertTrue(second.isFailure)
        }
    }

    @Test fun schedulerRejectsIllegalTransitionsAndTerminalReclaim(){
        SQLiteDatabase.create(null).use{db->
            val store=scheduled(db,"E")
            assertTrue(runCatching{transition(db,store,"TX0","BAD-PENDING","E",ScheduledEvaluationState.PENDING,20)}.isFailure)
            transition(db,store,"TX1","CLAIM","E",ScheduledEvaluationState.CLAIMED,20)
            transition(db,store,"TX2","DONE","E",ScheduledEvaluationState.PROCESSED,21)
            assertTrue(runCatching{transition(db,store,"TX3","RECLAIM","E",ScheduledEvaluationState.CLAIMED,22)}.isFailure)
        }
    }

    @Test fun schedulerCancelledEvaluationCannotBeClaimed(){
        SQLiteDatabase.create(null).use{db->
            val store=scheduled(db,"E")
            transition(db,store,"TX1","CANCEL","E",ScheduledEvaluationState.CANCELLED,20)
            assertTrue(runCatching{transition(db,store,"TX2","CLAIM","E",ScheduledEvaluationState.CLAIMED,21)}.isFailure)
        }
    }

    @Test fun schedulerRollbackDoesNotLeavePhantomClaim(){
        SQLiteDatabase.create(null).use{db->
            val store=scheduled(db,"E")
            db.beginTransaction();try{
                store.transition(TurnTransactionIdentity("C","TURN-R","CMD-R","TX-R"),"ROLLBACK-CLAIM","E",ScheduledEvaluationState.CLAIMED,20,"EVENT:R")
            }finally{db.endTransaction()}
            transition(db,store,"TX2","REAL-CLAIM","E",ScheduledEvaluationState.CLAIMED,20)
            assertTrue(store.due(20).isEmpty())
        }
    }

    @Test fun phase40RawSchedulerSurfaceIsInternalSimulationOnlyAndNotConsumedByRetrievalPipeline(){
        val p40=source("app/src/main/java/com/rpgos/app/Phase39TemporalAndPhase40Scheduler.kt")
        assertTrue(p40.contains("internal class Phase40Scheduler"))
        assertTrue(p40.contains("VisibilityPurposeKinds.INTERNAL_SIMULATION"))
        listOf(
            "app/src/main/java/com/rpgos/app/Phase41StructuredAndPhase42GraphRetrieval.kt",
            "app/src/main/java/com/rpgos/app/Phase43IntentAndPhase44TurnPlanner.kt",
            "app/src/main/java/com/rpgos/app/Phase45To47ContextPipeline.kt"
        ).forEach{path->
            val text=source(path)
            assertFalse(text.contains("ScheduledEvaluationStore("))
            assertFalse(text.contains("Phase40Scheduler("))
        }
    }

    private data class IterationFixture(
        val request:StructuredRetrievalRequest,
        val plan:TurnPlan,
        val retriever:StructuredSqlRetriever
    ){
        fun pipeline(followUp:StructuredRetrievalRequest)=IterativeRetrievalPipeline(
            retriever,ContextBudgetManager(),MissingContextStrategy{_,_,_->listOf(followUp)},IterativeRetrievalPolicy(1,1)
        )
    }

    private fun iterationFixture():IterationFixture{
        val audience=AudienceContext("C",AudienceKinds.INTERNAL_SYSTEM)
        val purpose=PurposeContext("C",VisibilityPurposeKinds.INTERNAL_SIMULATION)
        val provider=StructuredQueryProvider{request->
            if(request.filters["detail"]=="more")StructuredRetrievalResult.Value(listOf(RetrievalRecord("FOUND",mapOf("ok" to true))),true)
            else StructuredRetrievalResult.NoData
        }
        val retriever=StructuredSqlRetriever(listOf(
            StructuredProviderBinding("P",setOf("READ","OTHER"),provider),
            StructuredProviderBinding("Q",setOf("READ"),provider)
        ))
        val request=StructuredRetrievalRequest("REQ","C","P","READ",mapOf("subject_kind_uid" to "THING","subject_uid" to "T1"),10,audience,purpose,7)
        val intent=NormalizedIntent("C",CommandActorRef("PLAYER","P1"),"LOOK",emptyList(),null,null,"look","RULE_EXACT")
        val plan=TurnPlan("PLAN","C",intent,audience,purpose,listOf(TurnPlanStep("STEP",request,PlanStepPriority.REQUIRED)))
        return IterationFixture(request,plan,retriever)
    }

    private fun profile()=ContextModelProfile("P",1024,128)

    private fun scheduled(db:SQLiteDatabase,evaluationUid:String):ScheduledEvaluationStore{
        val store=ScheduledEvaluationStore(db,"C")
        db.beginTransaction();try{
            store.schedule(TurnTransactionIdentity("C","TURN-S","CMD-S","TX-S:$evaluationUid"),ScheduledEvaluation("C",evaluationUid,"CHECK","THING","T1",20,5,"EVENT:S"))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        return store
    }

    private fun transition(db:SQLiteDatabase,store:ScheduledEvaluationStore,tx:String,transitionUid:String,evaluationUid:String,state:ScheduledEvaluationState,order:Long){
        db.beginTransaction();try{
            store.transition(TurnTransactionIdentity("C","TURN:$tx","CMD:$tx",tx),transitionUid,evaluationUid,state,order,"EVENT:$transitionUid")
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }

    private fun temporal(
        db:SQLiteDatabase,campaign:String,audience:AudienceContext,purpose:PurposeContext,target:VisibilityPrincipalRef,atOrder:Long
    ):TemporalResult=temporalWithReads(ProtectedCampaignReadRepository.borrowed(db,campaign){null},audience,purpose,target,atOrder,campaign)

    private fun temporalWithReads(
        reads:ProtectedCampaignReadRepository,audience:AudienceContext,purpose:PurposeContext,target:VisibilityPrincipalRef,atOrder:Long,campaign:String=audience.campaignUid
    ):TemporalResult{
        val engine=TemporalEngine(listOf(TemporalSourceBinding("ACCESS",AccessAuthorityTemporalSource(reads))))
        return engine.query(TemporalQuery(campaign,"ACCESS","VISIBILITY_PRINCIPAL","${target.kindUid}:${target.uid}",atOrder,audience,purpose))
    }

    private fun putBinding(db:SQLiteDatabase,campaign:String,principal:VisibilityPrincipalRef,recordUid:String,order:Long){
        Phase38AccessAuthoritySchema.ensureReady(db)
        val change=AccessAuthorityChange(AccessOperation.UPSERT_BINDING,recordUid,principal.kindUid,principal.uid,AccessBindingKind.ROLE.name,"ROLE",validFromOrder=order)
        applyAccess(db,campaign,change,order)
    }

    private fun grantAuthority(db:SQLiteDatabase,campaign:String,principal:VisibilityPrincipalRef,recordUid:String,subjectUid:String,order:Long){
        Phase38AccessAuthoritySchema.ensureReady(db)
        val change=AccessAuthorityChange(
            AccessOperation.GRANT,recordUid,principal.kindUid,principal.uid,AccessGrantKind.EXPLICIT.name,
            ProtectedSubjectAccessRegistry.ORGANIZATION_READ_POLICY_UID,VisibilitySubjectKinds.ORGANIZATION_DATA,subjectUid,0
        )
        applyAccess(db,campaign,change,order)
    }

    private fun grantRelation(db:SQLiteDatabase,campaign:String,principal:VisibilityPrincipalRef,relationUid:String,order:Long){
        Phase38AccessAuthoritySchema.ensureReady(db)
        val change=AccessAuthorityChange(
            AccessOperation.GRANT,"GRANT:$relationUid:${principal.kindUid}:${principal.uid}",principal.kindUid,principal.uid,
            AccessGrantKind.EXPLICIT.name,ProtectedSubjectAccessRegistry.CAUSAL_RELATION_READ_POLICY_UID,
            VisibilitySubjectKinds.CAUSAL_RELATION,relationUid,0
        )
        applyAccess(db,campaign,change,order)
    }

    private fun applyAccess(db:SQLiteDatabase,campaign:String,change:AccessAuthorityChange,order:Long){
        val identity=TurnTransactionIdentity(campaign,"TURN:${change.recordUid}","CMD:${change.recordUid}","TX:${change.recordUid}")
        db.beginTransaction();try{
            AccessAuthorityStore(db,campaign).apply(identity,"CHANGE:${change.recordUid}",change,order)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }

    private fun graphDb():SQLiteDatabase=SQLiteDatabase.create(null).also{db->
        Phase38AccessAuthoritySchema.ensureReady(db)
        db.execSQL("CREATE TABLE canonical_gameplay_events(campaign_uid TEXT,event_uid TEXT,subject_ref_kind_uid TEXT,subject_ref_uid TEXT)")
        db.execSQL("CREATE TABLE canonical_causal_relations(campaign_uid TEXT,relation_uid TEXT,relation_class_uid TEXT,relation_kind_uid TEXT,source_event_uid TEXT,target_event_uid TEXT,committed_order INTEGER,relation_ordinal INTEGER,semantic_fingerprint TEXT)")
    }

    private fun event(db:SQLiteDatabase,eventUid:String,subjectKind:String,subjectUid:String,campaign:String="C"){
        db.execSQL("INSERT INTO canonical_gameplay_events VALUES(?,?,?,?)",arrayOf(campaign,eventUid,subjectKind,subjectUid))
    }

    private fun relation(
        db:SQLiteDatabase,relationUid:String,source:String,target:String,order:Long,
        relationClass:CausalRelationClass=CausalRelationClass.CAUSAL,kind:String=CausalRelationKinds.CAUSES,campaign:String="C",fingerprint:String="FP:$relationUid"
    ){
        db.execSQL("INSERT INTO canonical_causal_relations VALUES(?,?,?,?,?,?,?,?,?)",arrayOf(campaign,relationUid,relationClass.name,kind,source,target,order,0,fingerprint))
    }

    private fun graph(
        db:SQLiteDatabase,campaign:String,audience:AudienceContext,purpose:PurposeContext,start:String,
        direction:String="OUTGOING",depth:Int=3,limit:Int=100,trusted:TrustedPrincipalContext?=null,requestCampaign:String=campaign
    ):StructuredRetrievalResult{
        val reads=if(trusted==null)ProtectedCampaignReadRepository.borrowed(db,campaign){null}
            else ProtectedCampaignReadRepository.borrowedTrusted(db,campaign,{null},trusted)
        val provider=Phase42CausalQueryProvider(reads,campaign)
        val retriever=StructuredSqlRetriever(listOf(StructuredProviderBinding("GRAPH",setOf("TRAVERSE_CAUSAL"),provider)))
        return retriever.retrieve(StructuredRetrievalRequest(
            "R",requestCampaign,"GRAPH","TRAVERSE_CAUSAL",
            mapOf("start_event_uid" to start,"direction_uid" to direction,"max_depth" to depth.toString()),
            limit,audience,purpose
        ))
    }

    private fun repoRoot():File{
        var f=File(System.getProperty("user.dir")).canonicalFile
        repeat(8){if(File(f,"app/src/main/java").isDirectory)return f;f=f.parentFile?:return@repeat}
        error("repo root not found")
    }
    private fun source(path:String)=File(repoRoot(),path).readText()
}
