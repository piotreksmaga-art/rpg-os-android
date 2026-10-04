package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64FrontierCheckpointTest {
    private val scope=BackgroundProcessEvaluationScope(TemporalScope("C","G",0,"S"),"SEED","RULES")
    private val rule=BackgroundProcessDefinition("R",1,"ECONOMY","TEST",10)
    private val rows=(0 until 289).map { BackgroundProcessInstance("P:${it.toString().padStart(4,'0')}","R",1,DomainRef("NPC","N"),1,WorldTimeTick(0),WorldTimeTick(10)) }
    private val pool=DomainRef("RESOURCE","EXISTING_POOL")
    private val reads=object:BackgroundWorldReadPort {
        override fun available(resource:DomainRef,staged:List<PlayerDomainChangePayload>)=100L
        override fun exists(ref:DomainRef)=true
        override fun route(actor:DomainRef,destination:DomainRef,at:WorldTimeTick):String?=null
        override fun authorize(actor:DomainRef,purpose:String,refs:List<DomainRef>)=true
        override fun prepareOwnedEffect(operation:String,actor:DomainRef,parameters:Map<String,String>,scope:BackgroundProcessEvaluationScope,staged:List<PlayerDomainChangePayload>)=error("not used")
    }
    private val adapter=object:BackgroundDomainAdapter {
        override val domains=setOf("ECONOMY")
        override fun evaluate(definition:BackgroundProcessDefinition,process:BackgroundProcessInstance,scope:BackgroundProcessEvaluationScope,at:WorldTimeTick,reads:BackgroundWorldReadPort,staged:List<PlayerDomainChangePayload>)=
            WorldConsequencePlan(claims=listOf(WorldResourceClaim(pool,1)),sourceUids=listOf("R"))
    }
    private fun run(frontier:BackgroundProcessFrontierPort?):Pair<TemporalOwnerResult.Evaluated,Int> {
        val owner=Phase64BackgroundProcessOwner(scope,if(frontier==null)rows else emptyList(),mapOf(("R" to 1) to rule),{null},reads,listOf(adapter),frontier=frontier)
        val port=owner.extension().evaluation!!
        val input=TemporalOwnerInput(scope.temporal,WorldTimeTick(0),WorldTimeTick(10),emptyList(),emptyList(),null)
        val request=TemporalEvaluationRequest(owner.ownerUid,input,"P64:PROCESS_BATCH_REQUIRED")
        var yields=0
        repeat(40) {
            when(val response=port.evaluate(request) {false}) {
                is TemporalEvaluationResponse.Yielded->{assertEquals(request.fingerprint,response.requestFingerprint);yields++}
                is TemporalEvaluationResponse.Accepted->return response.result to yields
                is TemporalEvaluationResponse.Unavailable->error(response.reasonUid)
            }
        }
        error("cursor failed to terminate")
    }
    @Test fun indexedPagesAndEvaluationSlicesPreserveResourceConflictOutcomeBeyondOld256Limit() {
        var pages=0
        val frontier=object:BackgroundProcessFrontierPort {
            override fun page(through:WorldTimeTick,after:BackgroundDueCursor?):BackgroundDuePage {
                pages++
                val rest=rows.filter { after==null || it.uid>after.uid }
                val page=rest.take(32)
                return BackgroundDuePage(page,if(rest.size>32)page.last().let { BackgroundDueCursor(it.due,it.uid) } else null)
            }
            override fun definition(uid:String,version:Int)=rule
        }
        val paged=run(frontier);val direct=run(null)
        assertEquals(10,pages);assertTrue(paged.second>=17);assertTrue(direct.second>=8)
        assertEquals(direct.first,paged.first)
        val outcomes=paged.first.changes.filterIsInstance<BackgroundProcessChange>()
        assertEquals(100,outcomes.count { it.process.status==BackgroundProcessStatus.COMPLETED })
        assertEquals(189,outcomes.count { it.process.reasonUid=="P64:RESOURCE_CONFLICT" })
        assertEquals(rows.map { it.uid },outcomes.map { it.process.uid })
    }
    @Test fun cancellationAndChangedHistoryDiscardTheCalculationCursorWithoutAResult() {
        var generation=scope.temporal
        val owner=Phase64BackgroundProcessOwner(scope,rows,mapOf(("R" to 1) to rule),{null},reads,listOf(adapter),currentScope={generation})
        val port=owner.extension().evaluation!!
        val request=TemporalEvaluationRequest(owner.ownerUid,TemporalOwnerInput(scope.temporal,WorldTimeTick(0),WorldTimeTick(10),emptyList(),emptyList(),null),"P64:PROCESS_BATCH_REQUIRED")
        assertTrue(port.evaluate(request){false} is TemporalEvaluationResponse.Yielded)
        assertEquals(TemporalEvaluationResponse.Unavailable("P60:CANCELLED"),port.evaluate(request){true})
        generation=scope.temporal.copy(historyGenerationUid="OTHER")
        assertEquals(TemporalEvaluationResponse.Unavailable("P64:STALE_HISTORY"),port.evaluate(request){false})
    }
}
