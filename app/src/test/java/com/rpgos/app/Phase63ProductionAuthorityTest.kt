package com.rpgos.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Phase63ProductionAuthorityTest {
    @Test fun bothSourceKindsRequireMatchingCampaign() {
        for(kind in CampaignRuleSourceKind.entries) {
            val authority=CurrentWorldPackAuthority("C",WorldPackRuleBinding("SOURCE","1",kind))
            assertSame(authority,productionWorldAuthority("C") { authority })
            assertThrows(ProductionWorldAuthorityUnavailable::class.java) { productionWorldAuthority("OTHER") { authority } }
        }
        val failure=assertThrows(ProductionWorldAuthorityUnavailable::class.java) { productionWorldAuthority("C") { error("private SQL/path") } }
        assertEquals(ProductionWorldAuthorityUnavailable.REASON,failure.message)
        assertThrows(java.util.concurrent.CancellationException::class.java) { productionWorldAuthority("C") { throw java.util.concurrent.CancellationException() } }
    }
    @Test fun missingSourceStopsBeforeProviderOrMutation() = runBlocking {
        var compositions=0
        val app=DynamicCanonicalChatApplication { compositions++;throw ProductionWorldAuthorityUnavailable() }
        assertEquals(ChatApplicationOutcome.Rejected(AiTurnStage.CONTEXT,listOf(ProductionWorldAuthorityUnavailable.REASON)),app.play("Idę na zajęcia"))
        assertEquals(1,compositions)
        assertNull(app.pendingRecovery());assertNull(app.pendingUncommittedInput())
        val request=ChatTurnRequest("R","C","T","CMD","TX",CommandActorRef("PLAYER","P"),"Akcja","pl-PL",
            VisibilityAudienceFactory.player("C"),PurposeContext("C",VisibilityPurposeKinds.GAMEPLAY_NARRATION))
        assertEquals(NarrativeRecoveryResult.Unavailable(ProductionWorldAuthorityUnavailable.REASON),app.recover(ChatNarrationRecoveryToken(request)))
        assertNotNull(Phase63WorldMessages.explanation(listOf(ProductionWorldAuthorityUnavailable.REASON)))
        Unit
    }
    @Test fun unrelatedCompositionFailuresAreNotDisguised() {
        val app=DynamicCanonicalChatApplication { error("programming failure") }
        assertThrows(IllegalStateException::class.java) { app.pendingRecovery() }
    }
}
