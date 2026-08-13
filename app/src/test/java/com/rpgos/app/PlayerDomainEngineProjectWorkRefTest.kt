package com.rpgos.app
import org.junit.Assert.assertEquals
import org.junit.Test
class PlayerDomainEngineProjectWorkRefTest {
 private val actor=CommandActorRef("PLAYER","P1")
 private fun r(k:String,u:String)=DomainRef(k,u)
 private fun refs(kind:String,p:PlayerCommandPayload)=commandReferences(PlayerCommand(commandUid="M",campaignUid="C1",actor=actor,commandKindUid=kind,payload=p,provenance=CommandProvenance("TEST")))
 @Test fun projectWorkRequirementMilestoneRefsAreComplete(){
  assertEquals(listOf(r("PROJECT","P1"),r("EVIDENCE","E1"),r("RESOURCE","R1")),refs(PlayerCommandKinds.RECORD_PROJECT_WORK,RecordProjectWorkCommandPayload("P1","WORK",evidenceRefs=listOf(r("EVIDENCE","E1")),requestedResourceUse=listOf(r("RESOURCE","R1")))))
  assertEquals(listOf(r("PROJECT","P1"),r("PROJECT_REQUIREMENT","R1"),r("EVIDENCE","E1")),refs(PlayerCommandKinds.SATISFY_PROJECT_REQUIREMENT,SatisfyProjectRequirementCommandPayload("P1","R1",listOf(r("EVIDENCE","E1")))))
  assertEquals(listOf(r("PROJECT","P1"),r("PROJECT_MILESTONE","M1"),r("EVIDENCE","E1"),r("WORK","W1")),refs(PlayerCommandKinds.ACHIEVE_PROJECT_MILESTONE,AchieveProjectMilestoneCommandPayload("P1","M1",listOf(r("EVIDENCE","E1")),r("WORK","W1"))))
 }
}