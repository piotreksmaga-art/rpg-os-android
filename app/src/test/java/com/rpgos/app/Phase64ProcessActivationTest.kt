package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64ProcessActivationTest {
    private val actor=DomainRef("PLAYER","P")
    private val target=DomainRef("OBJECT","BOOK")
    private val rule=BackgroundProcessDefinition("R",1,"PROJECT","PROJECT_RESEARCH_V1",60_000,parameters=mapOf(
        "activation_action_uid" to "START_RESEARCH","activation_public" to "true","worker_uid" to "@ACTOR_UID","target_uid" to "@TARGET_UID","cost" to "5"))
    private fun effect(rule:BackgroundProcessDefinition=this.rule):VerifiedMechanicsCommandEffect {
        val hash=phase63Hash(Phase64BackgroundCodec.definition(rule).toString())
        return VerifiedMechanicsCommandEffect("E","N","UNIVERSAL_ACTION","INTERACTION",actor,1,mapOf(
            "p64_start_rule" to rule.uid,"p64_start_version" to rule.version.toString(),"p64_start_fingerprint" to hash,
            "p64_start_process" to "PROCESS","track_uid" to "ACTION:P64_START:PROCESS","p64_start_target_kind" to target.kindUid,
            "p64_start_target_uid" to target.uid),"P64:START:$hash:PROOF","IN","OUT")
    }
    @Test fun bindingsResolveOnlyReferencesAndPreserveCosts() {
        val values=Phase64ProcessActivation.bind(rule,actor,target)
        assertEquals(mapOf("worker_uid" to "P","target_uid" to "BOOK","cost" to "5"),values)
        assertFalse(values.containsKey("activation_public"))
    }
    @Test fun initiationHasFutureDeadlineAndHistoricalProof() {
        val start=Phase64ProcessActivation.start(TemporalScope("C","GEN",2,"STATE"),"COMMAND",rule,effect(),WorldTimeTick(1000))
        assertEquals(0L,start.expectedVersion)
        assertEquals(WorldTimeTick(61_000),start.process.due)
        assertEquals(listOf(effect().proofUid),start.evidence.sourceUids)
        assertEquals("COMMAND",start.process.parameters["p64_start_command_uid"])
        val registry=TypedPlayerChangeRegistry.core()
        assertEquals(start,registry.decodeWorkerPayload(registry.encodeWorkerPayload(start)))
    }
    @Test fun historyGenerationDoesNotChangeProcessIdentityOrFixedBindings() {
        val a=Phase64ProcessActivation.start(TemporalScope("C","A",2,"S"),"CMD",rule,effect(),WorldTimeTick(1000))
        val b=Phase64ProcessActivation.start(TemporalScope("C","B",2,"S"),"CMD",rule,effect(),WorldTimeTick(1000))
        assertEquals(a.process,b.process)
        assertNotEquals(a.historyGenerationUid,b.historyGenerationUid)
    }
    @Test fun changedRuleAndReservedParametersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { Phase64ProcessActivation.start(TemporalScope("C","G",1,"S"),"CMD",rule.copy(version=2),effect(),WorldTimeTick(0)) }
        assertThrows(IllegalArgumentException::class.java) { Phase64ProcessActivation.bind(rule.copy(parameters=mapOf("p64_start_proof_uid" to "fake")),actor,target) }
    }
    @Test fun sameVersionRuleParameterTamperingAndWrongActionTrackCannotStartAProcess() {
        val scope=TemporalScope("C","G",1,"S")
        val changed=rule.copy(parameters=rule.parameters+("cost" to "0"))
        assertThrows(IllegalArgumentException::class.java) { Phase64ProcessActivation.start(scope,"CMD",changed,effect(),WorldTimeTick(0)) }
        val wrongTrack=effect().copy(canonicalPayload=effect().canonicalPayload+("track_uid" to "ACTION:P64_START:ANOTHER_PROCESS"))
        assertThrows(IllegalArgumentException::class.java) { Phase64ProcessActivation.start(scope,"CMD",rule,wrongTrack,WorldTimeTick(0)) }
        val npc=effect().copy(target=DomainRef("NPC",actor.uid))
        assertThrows(IllegalArgumentException::class.java) { Phase64ProcessActivation.start(scope,"CMD",rule,npc,WorldTimeTick(0)) }
    }
    @Test fun initiationRejectsDeadlineOverflowInsteadOfWrappingIntoThePast() {
        assertThrows(ArithmeticException::class.java) {
            Phase64ProcessActivation.start(TemporalScope("C","G",1,"S"),"CMD",rule,effect(),WorldTimeTick(Long.MAX_VALUE-1))
        }
    }
    @Test fun ownerInventoryIsComplete() { CampaignReplayAuthorityMatrix.validateComplete() }
    @Test fun interruptionKeepsProgressAndCannotPayFutureEffects() {
        val scope=TemporalScope("C","G",1,"S")
        val start=Phase64ProcessActivation.start(scope,"START",rule,effect(),WorldTimeTick(1000))
        val p=start.process
        val cancel=effect().copy(proofUid="P64:CANCEL:PROOF",canonicalPayload=mapOf("p64_cancel_process" to p.uid,"p64_cancel_version" to p.version.toString()))
        val receipt=Phase64ProcessActivation.cancel(scope,p,cancel,WorldTimeTick(2000))
        assertEquals(BackgroundProcessStatus.INTERRUPTED,receipt.process.status)
        assertEquals(p.progressUnits,receipt.process.progressUnits)
        assertTrue(receipt.consequenceFingerprints.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { Phase64ProcessActivation.cancel(scope,p,cancel,p.due) }
    }
    @Test fun interruptionRejectsStaleVersionOrAnotherActorBeforeProducingAnyConsequences() {
        val scope=TemporalScope("C","G",1,"S")
        val process=Phase64ProcessActivation.start(scope,"START",rule,effect(),WorldTimeTick(1000)).process
        val cancellation=effect().copy(proofUid="P64:CANCEL:PROOF",canonicalPayload=mapOf(
            "p64_cancel_process" to process.uid,"p64_cancel_version" to process.version.toString()))
        assertThrows(IllegalArgumentException::class.java) {
            Phase64ProcessActivation.cancel(scope,process.copy(version=2),cancellation,WorldTimeTick(2000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Phase64ProcessActivation.cancel(scope,process,cancellation.copy(target=DomainRef("PLAYER","ANOTHER")),WorldTimeTick(2000))
        }
    }
}
