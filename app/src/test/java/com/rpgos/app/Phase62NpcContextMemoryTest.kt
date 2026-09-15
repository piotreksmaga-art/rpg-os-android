package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcContextMemoryTest {
    private fun record(uid:String,acquisition:String=uid,state:KnowledgeEpistemicState=KnowledgeEpistemicState.BELIEVED)=
        NpcKnownRecord(uid,state,"Podobno most jest otwarty.",acquisition,1)
    @Test fun currentStateWinsOverRebuildableRepetitionWithoutBeliefPromotion() {
        val current=record("STATE","ACQ",KnowledgeEpistemicState.CONTRADICTED)
        val old=record("MEMORY","ACQ",KnowledgeEpistemicState.OUTDATED).copy(memoryKind=NpcMemoryRecordKind.HISTORICAL_ACQUISITION)
        assertEquals(listOf(current),npcContextMemoryRecords(listOf(current),listOf(old),emptySet()))
    }
    @Test fun differentAcquisitionsStayDistinctEvenWithIdenticalText() {
        val a=record("CURRENT","A");val b=record("MEMORY","B").copy(memoryKind=NpcMemoryRecordKind.HISTORICAL_ACQUISITION)
        assertEquals(listOf(a,b),npcContextMemoryRecords(listOf(a),listOf(b),emptySet()))
    }
    @Test fun optionalHistoryNeverEvictsRequiredTriggerOrGoalEvidence() {
        val current=(0 until 64).map{record("C$it")};val history=(0 until 16).map{record("H$it")}
        assertEquals(current,npcContextMemoryRecords(current,history,current.map{it.acquisitionUid}.toSet()))
        val required=current.drop(1)
        val result=npcContextMemoryRecords(current,history,required.map{it.acquisitionUid}.toSet())
        assertEquals(64,result.size);assertTrue(result.containsAll(required));assertEquals(history.first(),result.last())
    }
    @Test fun historicalDuplicatesConsumeOneSlotAndResultStaysBounded() {
        val history=(0 until 32).flatMap{listOf(record("H$it","A$it"),record("SECOND:$it","A$it"))}
        val selected=npcContextMemoryRecords(emptyList(),history,emptySet())
        assertEquals(16,selected.size);assertEquals(16,selected.map{it.acquisitionUid}.distinct().size)
    }
}
