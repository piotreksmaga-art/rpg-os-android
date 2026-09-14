package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34],shadows=[Phase60AndroidAtomicRenameShadow::class])
class Phase60CheckpointStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val scope = TemporalScope("C1", "GEN1", 3, "digest")
    private fun initial() = Phase60TimeProcessor(emptyList()).begin(scope, "CMD", WorldTimeTick(10),
        listOf(TimedActionNode("walk", "owner", AcceptedActionTiming(ActionDuration(100), "rule", 1))),
        listOf(WorldProcessDeadline("bell", "owner", WorldTimeTick(200))))

    @Test fun reopeningStorePreservesTypedCandidatesAndInitialIdentity() {
        val dir = folder.newFolder("checkpoints")
        val initial = initial()
        val work = initial.copy(candidateChanges = listOf(ResourceChange(DomainRef("PLAYER", "P1"), "ENERGY", ExactLongDelta.of(-1))))
        FileTemporalCheckpointStore(dir).save(work)
        assertEquals(work, FileTemporalCheckpointStore(dir).load("C1", "CMD"))
        assertNull(FileTemporalCheckpointStore(dir).load("C2", "CMD"))
        FileTemporalCheckpointStore(dir).remove("C1", "CMD")
        assertNull(FileTemporalCheckpointStore(dir).load("C1", "CMD"))
    }
    @Test fun replacingCheckpointPublishesLatestWholeVersion() {
        val store=FileTemporalCheckpointStore(folder.newFolder("replace"))
        val first=initial();store.save(first)
        val next=first.copy(reached=WorldTimeTick(40),evaluatedBoundaries=2)
        store.save(next)
        assertEquals(next,store.load("C1","CMD"))
    }
    @Test fun corruptCacheIsDiscardedWithoutRequiringCampaignRepair() {
        val dir = folder.newFolder("corrupt")
        val store = FileTemporalCheckpointStore(dir)
        store.save(initial())
        dir.listFiles()!!.single().writeText("broken")
        assertNull(store.load("C1", "CMD"))
        assertEquals("P60:CHECKPOINT_INVALID", store.lastFailureUid)
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun uidCannotEscapePrivateCheckpointDirectory() {
        val dir = folder.newFolder("private")
        val work = initial().copy(scope = scope.copy(campaignUid = "../../campaign"), commandUid = "../../command")
        FileTemporalCheckpointStore(dir).save(work)
        assertEquals(1, dir.listFiles()!!.size)
        assertEquals(work, FileTemporalCheckpointStore(dir).load("../../campaign", "../../command"))
    }
    @Test fun undoCleanupRemovesOnlyTargetCampaignIncludingAllItsCommands() {
        val dir=folder.newFolder("cleanup")
        val store=FileTemporalCheckpointStore(dir)
        store.save(initial());store.save(initial().copy(commandUid="OTHER"))
        val retained=initial().copy(scope=scope.copy(campaignUid="C2"))
        store.save(retained)
        store.clearCampaign("C1")
        assertNull(store.load("C1","CMD"));assertNull(store.load("C1","OTHER"))
        assertEquals(retained,store.load("C2","CMD"))
        assertEquals(1,dir.listFiles()!!.size)
    }
    @Test fun duringStartsWithItsAnchorAndIsNotSerialisedAfterIt() {
        val walk = TimedActionNode("walk", "owner", AcceptedActionTiming(ActionDuration(100), "rule", 1))
        val talk = TimedActionNode("talk", "owner", AcceptedActionTiming(ActionDuration(40), "rule", 1), during = "walk")
        val work = Phase60TimeProcessor(emptyList()).begin(scope, "CMD", WorldTimeTick(10), listOf(talk, walk))
        assertEquals(10L, work.schedule.single { it.action.uid == "talk" }.start.milliseconds)
        assertEquals(110L, work.schedule.maxOf { it.end.milliseconds })
        assertEquals(work, Phase60CheckpointCodec.decode(Phase60CheckpointCodec.encode(work)))
        assertTrue(runCatching { Phase60ActionPlanner.schedule(WorldTimeTick(0), listOf(walk, talk.copy(timing = AcceptedActionTiming(ActionDuration(101), "rule", 1)))) }.isFailure)
    }
}
