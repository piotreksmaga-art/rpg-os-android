package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64BackgroundProcessTest {
    private val actor=DomainRef("NPC","N")
    private val scope=BackgroundProcessEvaluationScope(TemporalScope("C","H",1,"STATE"),"SEED","RULES")
    private val rule=BackgroundProcessDefinition("RULE",1,"ECONOMY","PAY",10)
    private fun process(uid:String,dependencies:List<String> = emptyList())=BackgroundProcessInstance(uid,"RULE",1,actor,1,WorldTimeTick(0),WorldTimeTick(10),dependencyUids=dependencies)
    private class Reads:BackgroundWorldReadPort {
        var balance=10L
        override fun available(resource:DomainRef,staged:List<PlayerDomainChangePayload>):Long? =
            if(resource.kindUid=="FINANCIAL_ACCOUNT")staged.filterIsInstance<FinancialChange>().fold(balance) { n,p->
                n-(if(p.fromAccountUid==resource.uid)p.amountMinor else 0)+(if(p.toAccountUid==resource.uid)p.amountMinor else 0)
            } else 1
        override fun exists(ref:DomainRef)=true
        override fun route(actor:DomainRef,destination:DomainRef,at:WorldTimeTick):String?=null
        override fun authorize(actor:DomainRef,purpose:String,refs:List<DomainRef>)=true
        override fun prepareOwnedEffect(operation:String,actor:DomainRef,parameters:Map<String,String>,scope:BackgroundProcessEvaluationScope,staged:List<PlayerDomainChangePayload>)=backgroundBlocked("MISSING")
    }
    private fun owner(processes:List<BackgroundProcessInstance>,reads:Reads=Reads(),run:(BackgroundProcessInstance,List<PlayerDomainChangePayload>)->WorldConsequencePlan)=
        Phase64BackgroundProcessOwner(scope,processes,mapOf((rule.uid to rule.version) to rule),{null},reads,listOf(object:BackgroundDomainAdapter {
            override val domains=setOf("ECONOMY")
            override fun evaluate(definition:BackgroundProcessDefinition,process:BackgroundProcessInstance,scope:BackgroundProcessEvaluationScope,at:WorldTimeTick,reads:BackgroundWorldReadPort,staged:List<PlayerDomainChangePayload>)=run(process,staged)
        }))
    private fun input(at:Long=10,staged:List<PlayerDomainChangePayload> = emptyList(),previous:TemporalOwnerState?=null)=
        TemporalOwnerInput(scope.temporal,WorldTimeTick(0),WorldTimeTick(at),emptyList(),emptyList(),previous,staged)
    @Test fun twoConsumersSeeStagedDebitWithoutDoubleReservation() {
        val owner=owner(listOf(process("A"),process("B"))) { _,_->WorldConsequencePlan(
            changes=listOf(FinancialChange("BANK","SELLER",5,"COIN","TRANSFER")),claims=listOf(WorldResourceClaim(DomainRef("FINANCIAL_ACCOUNT","BANK"),5))) }
        val result=owner.evaluate(input()) as TemporalOwnerResult.Evaluated
        assertEquals(2,result.changes.filterIsInstance<FinancialChange>().size)
        assertTrue(result.changes.filterIsInstance<BackgroundProcessChange>().all { it.process.status==BackgroundProcessStatus.COMPLETED })
    }
    @Test fun competingConsumersCannotOverdraw() {
        val result=owner(listOf(process("A"),process("B"))) { _,_->WorldConsequencePlan(
            changes=listOf(FinancialChange("BANK","SELLER",6,"COIN","TRANSFER")),claims=listOf(WorldResourceClaim(DomainRef("FINANCIAL_ACCOUNT","BANK"),6))) }.evaluate(input()) as TemporalOwnerResult.Evaluated
        assertEquals(1,result.changes.filterIsInstance<FinancialChange>().size)
        assertEquals("P64:RESOURCE_CONFLICT",result.changes.filterIsInstance<BackgroundProcessChange>().last().process.reasonUid)
    }
    @Test fun dependenciesRunBeforeLowerUidConsumer() {
        val visited=mutableListOf<String>()
        val result=owner(listOf(process("A",listOf("Z")),process("Z"))) { p,_->visited+=p.uid;WorldConsequencePlan() }.evaluate(input()) as TemporalOwnerResult.Evaluated
        assertEquals(listOf("Z","A"),visited)
        assertTrue(result.changes.filterIsInstance<BackgroundProcessChange>().all { it.process.status==BackgroundProcessStatus.COMPLETED })
    }
    @Test fun cyclicDependenciesDoNotExecute() {
        val result=owner(listOf(process("A",listOf("B")),process("B",listOf("A")))) { _,_->fail("cycle executed");WorldConsequencePlan() }.evaluate(input()) as TemporalOwnerResult.Evaluated
        assertTrue(result.changes.filterIsInstance<BackgroundProcessChange>().all { it.process.status==BackgroundProcessStatus.BLOCKED })
    }
    @Test fun completedOverlayDoesNotPayTwice() {
        val o=owner(listOf(process("A"))) { _,_->WorldConsequencePlan(changes=listOf(FinancialChange("BANK","SELLER",5,"COIN","TRANSFER"))) }
        val first=o.evaluate(input()) as TemporalOwnerResult.Evaluated
        val again=o.evaluate(input(20,first.changes,first.state)) as TemporalOwnerResult.Evaluated
        assertTrue(again.changes.isEmpty())
    }
    @Test fun staleGenerationAndActivePlayerAreRejected() {
        val o=owner(listOf(process("A"))) { _,_->WorldConsequencePlan() }
        assertEquals("P64:STALE_HISTORY",(o.evaluate(input().copy(scope=scope.temporal.copy(historyGenerationUid="OTHER"))) as TemporalOwnerResult.Unsupported).reasonUid)
        val player=owner(listOf(process("A").copy(actor=DomainRef("PLAYER","P")))) { _,_->fail("player controlled");WorldConsequencePlan() }
        val result=player.evaluate(input()) as TemporalOwnerResult.Evaluated
        assertEquals("P64:ACTIVE_PLAYER_AGENCY",result.changes.filterIsInstance<BackgroundProcessChange>().single().process.reasonUid)
    }
    @Test fun processReceiptAndProjectEventKeepTypedIdentity() {
        val work=BackgroundProjectWorkChange("C","PROJECT",actor,Phase64EconomyOperations.OWNED_BUILD_CHECK,1,1,0,1,"LABOUR",1,"RULE",1)
        val receipt=BackgroundProcessChange("C","H",1,process("A").copy(version=2,status=BackgroundProcessStatus.COMPLETED,
            dependencyUids=listOf("Z:PREVIOUS","A:PREVIOUS")),
            WorldProcessEvidence("E","A","RULE",1,listOf("Z:SOURCE","A:SOURCE"),WorldTimeTick(10)),listOf(Phase64BackgroundCodec.fingerprint(work)),
            deadlineAdds=listOf(WorldProcessDeadline("Z:DUE",NpcDutyDeadlineProcess.OWNER,WorldTimeTick(30)),
                WorldProcessDeadline("A:DUE",NpcDutyDeadlineProcess.OWNER,WorldTimeTick(20))))
        val registry=TypedPlayerChangeRegistry.core()
        assertEquals(receipt,registry.decodeWorkerPayload(registry.encodeWorkerPayload(receipt)))
        val materialized=phase64MaterializeChanges("C","COMMAND",listOf(work,receipt))
        assertEquals(DomainRef("PROJECT","PROJECT"),(materialized.events.first().payload as DomainEffectEventIntentPayload).subject)
        assertEquals(listOf(materialized.changes.first().changeUid),materialized.events.first().causalChangeUids)
    }
    @Test fun ownerConsequencesCarryTheSameExactPoolAndConditionReferencesAsTheirDraft() {
        val payloads=listOf<PlayerDomainChangePayload>(
            ResourceChange(actor,"WORK",ExactLongDelta.of(-1)),
            ConditionChange(actor,"EXPOSED",ConditionOperation.ADD))
        payloads.forEachIndexed { index,payload ->
            val kind=TypedPlayerChangeRegistry.core().encodeWorkerPayload(payload).getValue("kind")
                .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            val draft=PlayerResolutionDraft.create(changes=listOf(PlayerDomainChange.create("CHANGE:$index",kind,payload,"RULE")))
            assertEquals(draftReferences(draft).toSet(),phase64References(payload).toSet())
        }
    }
    @Test fun newInventoryDutyAndMessageLineageKeepTheirCanonicalAdmissionReferences() {
        val knowledge=KnowledgeAcquisitionChange(
            KnowledgeClaim("CLAIM","NPC","N","REPORT","Claim, not world truth",domainUid=KnowledgeDomains.TACTICS),
            KnowledgeAcquisitionSpec("ACQ",KnowledgeHolderRef("CHARACTER","RECIPIENT","C"),
                KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,KnowledgeScope.PERSONAL,KnowledgeEpistemicState.BELIEVED,
                KnowledgeQuality(1.0,1.0,1.0,1.0,1,1),parentAcquisitionUid="SOURCE_ACQ",
                sourceHolder=KnowledgeHolderRef("CHARACTER","SOURCE","C")),
            listOf(KnowledgeEvidenceSpec("EVIDENCE","ACCESS_PATH",KnowledgeEvidencePolarity.SUPPORTS,
                sourceRef=KnowledgeSourceRef(KnowledgeReferenceScope.CAMPAIGN,"C","ACCESS_PATH_EVIDENCE","PATH"))))
        val payloads=listOf<PlayerDomainChangePayload>(
            InventoryChange(actor,"OUTPUT",ExactLongDelta.of(1),universalInventoryItemMaterialization()),
            AccessAuthorityChange(AccessOperation.GRANT,"GRANT",actor.kindUid,actor.uid,AccessGrantKind.EXPLICIT.name,
                "DUTY","NPC_DUTY","DUTY:1",validFromOrder=1),knowledge)
        payloads.forEachIndexed { index,payload->
            val kind=TypedPlayerChangeRegistry.core().encodeWorkerPayload(payload).getValue("kind")
                .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            val draft=PlayerResolutionDraft.create(changes=listOf(PlayerDomainChange.create("CHANGE:$index",kind,payload,"RULE")))
            assertTrue(phase64References(payload).toSet().containsAll(draftReferences(draft)))
        }
        assertTrue(DomainRef("OBJECT","OUTPUT") in phase64References(payloads[0]))
        assertFalse(DomainRef("ITEM_INSTANCE","OUTPUT") in phase64References(payloads[0]))
        assertTrue(DomainRef("CHARACTER","SOURCE") in phase64References(knowledge))
        assertTrue(DomainRef("ACCESS_PATH_EVIDENCE","PATH") in phase64References(knowledge))
    }
    @Test fun rulesDoNotCreateProcessesAndDoNotImportWorldPackMechanics() {
        val rules=Phase64NewCampaignBootstrap.coreDefinitions()
        assertEquals(BackgroundProcessDefinition.DOMAINS,rules.map { it.domain }.toSet())
        val publicRules=rules.filter { it.parameters[Phase64ProcessActivation.PUBLIC_KEY]=="true" }
        assertEquals(setOf("CONSUME_OWN_ITEM","WORK_ON_BUILD","WORK_ON_REPAIR","WORK_ON_RESEARCH",Phase64NeutralCommunicationOwner.ACTION),
            publicRules.map { it.parameters[Phase64ProcessActivation.ACTION_KEY] }.toSet())
        assertTrue(rules.none { it.parameters.containsKey("auto_start") })
        assertTrue(publicRules.all { it.parameters["activation_npc"]!="true" })
        assertEquals(mapOf(Phase64CombatReceiptFactory.OWNER_PARAMETER to NpcActionProcess.OWNER),
            rules.single { it.uid==Phase64CombatReceiptFactory.RULE_UID && it.version==2 }.parameters)
        assertTrue(rules.none { "CHAKRA" in it.uid || "NARUTO" in it.uid })
    }
}
