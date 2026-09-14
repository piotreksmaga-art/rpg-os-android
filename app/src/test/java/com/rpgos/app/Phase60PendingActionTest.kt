package com.rpgos.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,34],shadows=[Phase60AndroidAtomicRenameShadow::class])
class Phase60PendingActionTest {
    @get:Rule val folder=TemporaryFolder()
    private var scope=TemporalScope("C1","G1",0,"HASH")
    private var committed=false
    private val discarded=mutableListOf<String>()
    private val request=ChatTurnRequest("R","C1","T","CMD","TX",CommandActorRef("PLAYER","P1"),"Czytam przez 20 minut","pl",
        VisibilityAudienceFactory.player("C1"),PurposeContext("C1",VisibilityPurposeKinds.GAMEPLAY_NARRATION),1)
    private fun store()=FilePendingChatActionStore(folder.root,"C1",{scope},{committed},{discarded.add(it)})
    @Test fun reopeningOffersOnlyOriginalUncommittedInputAndNormalCompletionClearsIt() {
        store().begin(request)
        assertEquals(request.input,store().pendingInput())
        store().finished(request)
        assertNull(store().pendingInput())
        assertEquals(listOf("CMD"),discarded)
    }
    @Test fun committedTurnCannotBeResentEvenWhenHostDiedBeforeRemovingMarker() {
        store().begin(request);committed=true
        assertNull(store().pendingInput())
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
    @Test fun undoOrChangedCanonicalStateInvalidatesOffer() {
        store().begin(request);scope=scope.copy(historyGenerationUid="AFTER_UNDO")
        assertNull(store().pendingInput())
        scope=scope.copy(historyGenerationUid="G1")
        store().begin(request);scope=scope.copy(authoritativeFingerprint="NEW_STATE")
        assertNull(store().pendingInput())
    }
    @Test fun aNewAttemptDoesNotRestoreCachedMechanicalEffects() {
        store().begin(request)
        store().begin(request.copy(requestUid="R2",commandUid="CMD2",transactionUid="TX2"))
        assertEquals(listOf("CMD"),discarded)
        val files=folder.root.listFiles()!!.associate{it.name to it.readText()}
        assertEquals(files.toString(),1,files.size)
        assertTrue(files.values.single().contains("R2"))
        assertEquals("new marker before finishing old request",request.input,store().pendingInput())
        // Finishing an older concurrent request cannot erase the newer request's marker.
        store().finished(request)
        assertEquals(request.input,store().pendingInput())
    }
    @Test fun cleanupDoesNotTouchAnotherCampaignOrOtherFiles() {
        store().begin(request)
        val other=FilePendingChatActionStore(folder.root,"C2",{scope.copy(campaignUid="C2")},{false},{})
        other.begin(request.copy(campaignUid="C2",audience=VisibilityAudienceFactory.player("C2"),
            purpose=PurposeContext("C2",VisibilityPurposeKinds.GAMEPLAY_NARRATION)))
        FilePendingChatActionStore.clearCampaign(folder.root,"C1")
        assertNull(store().pendingInput())
        assertEquals(request.input,other.pendingInput())
    }
}
