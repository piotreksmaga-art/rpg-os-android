package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64WorldMessagesTest {
    private val definition=BackgroundProcessDefinition("RULE",1,"PROJECT","RESEARCH",1000)
    private fun process(status:BackgroundProcessStatus,reason:String?=null)=BackgroundProcessInstance(
        "PROCESS","RULE",1,DomainRef("PLAYER","P"),1,WorldTimeTick(0),WorldTimeTick(1000),status,
        parameters=mapOf("hidden_text" to "SECRET-NPC-MEMORY"),reasonUid=reason)

    @Test fun ordinaryMessagesContainNoPrivateParametersOrDiagnosticCodes() {
        BackgroundProcessStatus.entries.forEach { status->
            val notice=Phase64WorldMessages.notice(definition,process(status,"P64:RESOURCE_REQUIREMENT_UNSATISFIED"))
            assertFalse(notice.contains("SECRET"));assertFalse(notice.contains("P64:"));assertFalse(notice.contains("PROCESS"))
        }
    }
    @Test fun blockedAndInterruptedNeverClaimAnAwardOrRollback() {
        assertTrue(Phase64WorldMessages.notice(definition,process(BackgroundProcessStatus.BLOCKED)).contains("wstrzymany"))
        assertTrue(Phase64WorldMessages.notice(definition,process(BackgroundProcessStatus.INTERRUPTED)).contains("przyszłe skutki nie"))
        assertFalse(Phase64WorldMessages.explanation(listOf("P64:DELIVERY_ROUTE_CHANGED"))!!.contains("nie upłynął"))
    }
    @Test fun unrelatedReasonsStayWithExistingMessageOwners() {
        assertNull(Phase64WorldMessages.explanation(listOf("EXECUTORCH_SERVICE_DIED","P63:ROUTE_UNKNOWN")))
    }
    @Test fun mixedReasonListsAndHistoryFailuresStayActionable() {
        val text=Phase64WorldMessages.explanation(listOf("OTHER|P64:STALE_COMPLETION_CLOCK"))!!
        assertTrue(text.contains("ponownie"));assertFalse(text.contains("OTHER"))
        assertTrue(Phase64WorldMessages.explanation(listOf("P64:CAMPAIGN_NOT_ENABLED"))!!.contains("wcześniejszych"))
    }
    @Test fun noticeRejectsAnUnrelatedDefinition() {
        assertTrue(runCatching{Phase64WorldMessages.notice(definition.copy(version=2),process(BackgroundProcessStatus.ACTIVE))}.isFailure)
    }
}
