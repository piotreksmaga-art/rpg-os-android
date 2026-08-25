package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase39To47BlockTest {
    private val audience=AudienceContext("C",AudienceKinds.INTERNAL_SYSTEM)
    private val purpose=PurposeContext("C",VisibilityPurposeKinds.INTERNAL_SIMULATION)

    @Test fun gateA_temporalIsAsOfAndSchedulerStoresEvaluationNotOutcome(){
        RuntimeTruthLayerRegistry.validateCanonicalInventory()
        assertEquals("TEMPORAL_SCHEDULE_STATE",RuntimeTruthLayerRegistry.requireClassifiedTable(Phase40SchedulerSchema.DEFINITIONS).uid)
        assertEquals("TEMPORAL_SCHEDULE_STATE",RuntimeTruthLayerRegistry.requireClassifiedTable(Phase40SchedulerSchema.TRANSITIONS).uid)
        val engine=TemporalEngine(listOf(TemporalSourceBinding("HISTORY",TemporalSource{q->listOf(
            TemporalRecord("OLD",0,10,mapOf("value" to "old")),TemporalRecord("NOW",10,null,mapOf("value" to "now"))).filter{it.validAt(q.atOrder)}.let{TemporalResult.Value(it)}})))
        val result=engine.query(TemporalQuery("C","HISTORY","THING","X",5,audience,purpose)) as TemporalResult.Value
        assertEquals(listOf("OLD"),result.records.map{it.recordUid})

        SQLiteDatabase.create(null).use{db->
            val store=ScheduledEvaluationStore(db,"C");val id=TurnTransactionIdentity("C","TURN","CMD","TX")
            assertTrue(runCatching{store.schedule(id,ScheduledEvaluation("C","E0","CHECK","THING","X",20,5,"EVENT:0"))}.isFailure)
            db.beginTransaction();try{store.schedule(id,ScheduledEvaluation("C","E1","EVALUATE_MISSION","MISSION","M1",20,5,"EVENT:1","condition=at-runtime"));db.setTransactionSuccessful()}finally{db.endTransaction()}
            assertTrue(store.due(19).isEmpty());assertEquals("E1",store.due(20).single().evaluation.evaluationUid)
            db.beginTransaction();try{store.transition(id,"T1","E1",ScheduledEvaluationState.PROCESSED,30,"EVENT:2");db.setTransactionSuccessful()}finally{db.endTransaction()}
            assertEquals("E1",store.due(20).single().evaluation.evaluationUid);assertTrue(store.due(30).isEmpty())
        }
    }

    @Test fun gateB_retrievalIsAllowlistedBoundedAndGraphTraversalPreservesKinds(){
        val provider=StructuredQueryProvider{StructuredRetrievalResult.Value((1..10).map{RetrievalRecord("R$it",mapOf("n" to it))},false)}
        val retriever=StructuredSqlRetriever(listOf(StructuredProviderBinding("P",setOf("READ"),provider)))
        val request=StructuredRetrievalRequest("Q","C","P","READ",emptyMap(),3,audience,purpose)
        assertEquals(3,(retriever.retrieve(request) as StructuredRetrievalResult.Value).records.size)
        assertTrue(retriever.retrieve(request.copy(operationUid="RAW_SQL")) is StructuredRetrievalResult.Unsupported)

        SQLiteDatabase.create(null).use{db->
            db.execSQL("CREATE TABLE canonical_causal_relations(campaign_uid TEXT,relation_uid TEXT,relation_class_uid TEXT,relation_kind_uid TEXT,source_event_uid TEXT,target_event_uid TEXT,committed_order INTEGER,relation_ordinal INTEGER,semantic_fingerprint TEXT)")
            db.execSQL("INSERT INTO canonical_causal_relations VALUES('C','R1','CAUSAL','CAUSES','A','B',1,0,'F1')")
            db.execSQL("INSERT INTO canonical_causal_relations VALUES('C','R2','NARRATIVE','NARRATIVE_ASSOCIATION','B','C',2,0,'F2')")
            val rows=Phase42CausalGraphRetriever(db,"C").traverse(CausalTraversalSpec("A","OUTGOING",maxDepth=2,maxEdges=1))
            assertEquals(1,rows.size);assertEquals("CAUSES",rows.single().values["relation_kind_uid"])
        }
    }

    @Test fun gateC_parserPreservesChoiceAndPlannerOnlyBuildsPlan(){
        val actor=CommandActorRef("PLAYER","P1");val parser=IntentParser()
        assertTrue(parser.parse("C",actor,"atakuj") is IntentParseResult.Ambiguous)
        val parsed=parser.parse("C",actor,"atakuj @character:npc1 method:sword") as IntentParseResult.Parsed
        assertEquals("ATTACK",parsed.intent.actionUid);assertEquals("npc1",parsed.intent.targetRefs.single().uid);assertEquals("sword",parsed.intent.methodUid)
        val planner=TurnPlanner(listOf(PlannerCapabilityRule("ATTACK","ACTORS","READ_ACTOR",PlanStepPriority.REQUIRED,true)))
        val plan=planner.plan(parsed.intent,audience,purpose,12)
        assertEquals(1,plan.steps.size);assertEquals("npc1",plan.steps.single().request.filters["subject_uid"])
    }

    @Test fun gateD_contextBudgetAndIterationAreBoundedAndDoNotExpandEntitlement(){
        var calls=0
        val provider=StructuredQueryProvider{request->calls++;if(request.filters["followup"]=="true")StructuredRetrievalResult.Value(listOf(RetrievalRecord("FOUND",mapOf("v" to "x"))),true)else StructuredRetrievalResult.NoData}
        val retriever=StructuredSqlRetriever(listOf(StructuredProviderBinding("P",setOf("READ"),provider)))
        val intent=NormalizedIntent("C",CommandActorRef("PLAYER","P1"),"LOOK",emptyList(),null,null,"look","RULE_EXACT")
        val request=StructuredRetrievalRequest("REQ","C","P","READ",emptyMap(),10,audience,purpose)
        val plan=TurnPlan("PLAN","C",intent,audience,purpose,listOf(TurnPlanStep("S",request,PlanStepPriority.REQUIRED)))
        val pipeline=IterativeRetrievalPipeline(retriever,ContextBudgetManager(ContextUnitEstimator{100}),MissingContextStrategy{_,_,missing->listOf(request.copy(filters=mapOf("followup" to "true"),requestUid=missing.single().requestUid))},IterativeRetrievalPolicy(2,2))
        val result=pipeline.execute(plan,ContextModelProfile("SMALL",256,16))
        assertEquals("CONTEXT_COMPLETE",result.terminationUid);assertEquals(1,result.iterations);assertEquals(2,calls);assertEquals("FOUND",result.budgeted.includedSegments.single().records.single().recordUid);assertTrue(result.budgeted.usedUnits<=result.budgeted.availableUnits)

        val attack=IterativeRetrievalPipeline(retriever,ContextBudgetManager(),MissingContextStrategy{_,_,_->listOf(request.copy(audience=AudienceContext("C",AudienceKinds.GM_RUNTIME),filters=mapOf("followup" to "true")))})
        assertTrue(runCatching{attack.execute(plan,ContextModelProfile("P",100,10))}.isFailure)
    }

    @Test fun integration_inputToSafeContextWorksWithoutAiProvider(){
        val parsed=IntentParser().parse("C",CommandActorRef("PLAYER","P1"),"sprawdź") as IntentParseResult.Parsed
        val planner=TurnPlanner(listOf(PlannerCapabilityRule("LOOK","WORLD","VISIBLE",PlanStepPriority.REQUIRED)))
        val plan=planner.plan(parsed.intent,audience,purpose,7)
        val retriever=StructuredSqlRetriever(listOf(StructuredProviderBinding("WORLD",setOf("VISIBLE"),StructuredQueryProvider{StructuredRetrievalResult.Value(listOf(RetrievalRecord("VISIBLE:1",mapOf("summary" to "safe"),"PROJECTED")),true)})))
        val result=IterativeRetrievalPipeline(retriever,ContextBudgetManager(),MissingContextStrategy{_,_,_->emptyList()}).execute(plan,ContextModelProfile("LOCAL",2048,256))
        assertTrue(result.missing.isEmpty());assertEquals("safe",result.budgeted.includedSegments.single().records.single().values["summary"])
    }
}
