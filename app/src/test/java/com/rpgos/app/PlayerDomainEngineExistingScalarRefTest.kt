package com.rpgos.app
import org.junit.Assert.assertEquals
import org.junit.Test
class PlayerDomainEngineExistingScalarRefTest {
 private val actor=CommandActorRef("PLAYER","P1")
 private fun refs(kind:String,p:PlayerCommandPayload)=commandReferences(PlayerCommand(commandUid="M",campaignUid="C1",actor=actor,commandKindUid=kind,payload=p,provenance=CommandProvenance("TEST")))
 @Test fun existingScalarTargetsArePhase18Refs(){
  assertEquals(listOf(DomainRef("SKILL","S1")),refs(PlayerCommandKinds.PRACTICE_SKILL,PracticeSkillCommandPayload("S1",1)))
  assertEquals(listOf(DomainRef("OBLIGATION","O1")),refs(PlayerCommandKinds.SETTLE_OBLIGATION,SettleObligationCommandPayload("O1")))
 }
}