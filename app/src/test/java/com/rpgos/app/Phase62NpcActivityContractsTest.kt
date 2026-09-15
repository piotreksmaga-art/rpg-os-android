package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcActivityContractsTest {
    private val actor=DomainRef("NPC","N1")
    private val base=NpcBrainOwner.initialize("C",actor,"seed")
    private val brain=base.copy(goals=listOf(NpcGoal("G",base.motivations.first().uid,"Ćwiczyć rzemiosło",NpcWeight(100),NpcGoalLifecycle.ACTIVE,
        NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A"))))
    private val record=NpcKnownRecord("R",KnowledgeEpistemicState.BELIEVED,"Mam możliwość ćwiczenia.","A",1,setOf(actor))
    private val mechanical=MechanicalActorView("C",actor,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,emptyMap(),emptyList(),
        setOf("CUSTOM_POTTERY"),generationProvenanceUid="GEN")
    private val contract=NpcActivityContract("CUSTOM_POTTERY","DOMAIN:POTTERY_PRACTICE",2,ActionDuration(30000),"TRAINING:POTTERY")
    private val scope=NpcDecisionScope(TemporalScope("C","H",1,"STATE"),actor,1,WorldTimeTick(0),0,"P")
    private fun options(state:MechanicalActorView=mechanical,port:NpcActivityContractPort=NpcActivityContractPort.registered(listOf(contract)))=
        NpcMechanicalAffordances(CombatAbilityContractPort.UNIVERSAL_FALLBACK,port).options(brain,listOf(record),state)

    @Test fun customRegisteredCapabilityDoesNotNeedAWorldNameOrHardcodedVerb() {
        val option=options().single()
        assertEquals("CUSTOM_POTTERY",option.capabilityUid)
        assertEquals(actor,option.target)
        assertEquals(NpcActivityMechanics.OWNER,option.mechanicsOwnerUid)
        assertTrue(options(port=NpcActivityContractPort.NONE).isEmpty())
        assertTrue(options(mechanical.copy(executableAbilityUids=emptySet())).isEmpty())
        assertTrue(options(mechanical.copy(kind=MechanicalActorKind.ACTIVE_PLAYER)).isEmpty())
        assertTrue(options(mechanical.copy(conditions=listOf(MechanicalCondition("UNCONSCIOUS",1)))).isEmpty())
    }
    @Test fun sharedMechanicsRecordsOnlyRegisteredEffortWithExactDomainTime() {
        val reads=object:NpcProjectionReadPort {
            override fun brain(audience:AudienceContext,purpose:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef)=ProtectedReadResult.Allow(brain,DisclosureLevel.DISCLOSE_FULL,"SELF")
            override fun knowledge(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int)=ProtectedReadResult.Allow(listOf(record),DisclosureLevel.DISCLOSE_FULL,"KNOWLEDGE")
        }
        val projected=NpcDecisionContextProjector(reads).project(scope,NpcTrigger("T",NpcTriggerKind.KNOWLEDGE_CHANGED,WorldTimeTick(0),
            NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A")),brain.knowledgeHolder,ContextRuntimeProfile("TEST",8192,64,64,512)) {_,_->options()} as NpcContextResult.Ready
        val chosen=NpcDecisionEngine().select(projected.context,NpcDecisionProposal("REQ",projected.context.contextFingerprint,
            listOf(NpcDecisionCandidate(options().single().uid))),scope) as NpcDecisionResult.Selected
        fun resolve(rule:NpcActivityContract)=NpcMechanicalActionApplication(MechanicsRuleResolver{r,c->NpcActivityMechanics.resolve(r,c,mechanical,rule)},
            {scope.temporal}).resolve(projected,chosen)
        val result=resolve(contract) as NpcMechanicalResult.Resolved
        assertEquals(listOf(MechanicalTrackChange(actor,"TRAINING:POTTERY",ExactLongDelta.of(1))),result.changes)
        assertEquals(30000,result.timing.duration.milliseconds)
        assertEquals(2,result.timing.ruleVersion)
        assertEquals(30000,Phase60DomainTiming.effectOffset(result.effects.single(),result.timing.duration))
        assertEquals(NpcGoalLifecycle.ACTIVE,brain.goals.single().lifecycle)
        assertTrue(resolve(contract.copy(effortUnits=999)) is NpcMechanicalResult.Unavailable)
    }
    @Test fun productionActivitiesCarryCoreDefinedPersonalityAndMotivationPreferences() {
        val training=requireNotNull(NpcActivityContractPort.STANDARD.contract("C","TRAIN"))
        val option=NpcActivityMechanics.option(brain,brain.goals.single(),record,training)
        assertEquals(setOf("PERSISTENCE","CURIOSITY"),option.traitPreferences.map{it.traitUid}.toSet())
        assertEquals(NpcAffect(6000),option.motivationAlignment["P61:EXPLORATION"])
        assertEquals(NpcAffect(5000),option.valueAlignment["DISCIPLINE"])
        val changed=training.copy(traitPreferences=emptyList())
        assertNotEquals(training.fingerprint,changed.fingerprint)
        assertNotEquals(option.uid,NpcActivityMechanics.option(brain,brain.goals.single(),record,changed).uid)
        // Custom domains supply their own semantics; an unknown trait is not invented on the NPC.
        val custom=contract.copy(traitPreferences=listOf(NpcTraitPreference("ABSENT",NpcWeight(5000),NpcWeight(1000)) ))
        assertTrue(NpcActivityMechanics.option(brain,brain.goals.single(),record,custom).traitPreferences.isEmpty())
    }
    @Test fun effortDoesNotInterruptThePlayerButPhysicalConsequencesStillDo() {
        val effect=VerifiedMechanicsCommandEffect("E","N",NpcActivityMechanics.OWNER,"INTERACTION",actor,1,
            mapOf("track_uid" to "TRAINING:GENERAL"),"PROOF","INPUT","OUTPUT")
        assertFalse(npcRequiresForegroundDecision(listOf(effect),setOf(actor)))
        assertTrue(npcRequiresForegroundDecision(listOf(effect.copy(mechanicsOwnerUid="UNIVERSAL_COMBAT",effectKindUid="WOUND")),setOf(actor)))
        assertFalse(npcRequiresForegroundDecision(listOf(effect.copy(mechanicsOwnerUid="UNIVERSAL_COMBAT",effectKindUid="WOUND")),setOf(DomainRef("PLAYER","P"))))
    }

    @Test fun legacyCombatantGetsBasicNonViolentChoicesWithoutLearningNewAbilities() {
        val state=mechanical.copy(executableAbilityUids=setOf("ATTACK","STRIKE","DEFEND"),resources=listOf(MechanicalResource("STAMINA",90,100)))
        val offered=NpcMechanicalAffordances(CombatAbilityContractPort.UNIVERSAL_FALLBACK).options(brain,listOf(record),state)
        assertEquals(setOf("WAIT","REST"),offered.map{it.capabilityUid}.toSet())
        assertTrue(offered.all{it.target==actor && it.resourceCosts.isEmpty() && it.mechanicsOwnerUid==NpcActivityMechanics.OWNER})
        assertEquals(setOf("ATTACK","STRIKE","DEFEND"),state.executableAbilityUids)
        val wait=requireNotNull(NpcActivityContractPort.STANDARD.contract("C","WAIT"))
        assertTrue(NpcActivityMechanics.available(state,wait))
        assertFalse(NpcActivityMechanics.available(state,wait.copy(eligibility=NpcActivityEligibility.MATERIALIZED_CAPABILITY)))
        assertNotEquals(wait.fingerprint,wait.copy(eligibility=NpcActivityEligibility.MATERIALIZED_CAPABILITY).fingerprint)
    }

    @Test fun basicActivityDoesNotBypassConsciousnessMaterializationOrActorControl() {
        val wait=requireNotNull(NpcActivityContractPort.STANDARD.contract("C","WAIT"))
        assertFalse(NpcActivityMechanics.available(mechanical.copy(kind=MechanicalActorKind.ACTIVE_PLAYER),wait))
        assertFalse(NpcActivityMechanics.available(mechanical.copy(materialization=MechanicalStateMaterialization.PARTIAL),wait))
        assertFalse(NpcActivityMechanics.available(mechanical.copy(conditions=listOf(MechanicalCondition("DEAD",1))),wait))
        assertFalse(NpcActivityMechanics.available(mechanical.copy(resources=listOf(MechanicalResource("HEALTH",0,100))),wait))
        assertFalse(NpcActivityMechanics.available(mechanical,requireNotNull(NpcActivityContractPort.STANDARD.contract("C","TRAIN"))))
    }

    @Test fun registeredActivitySnapshotDoesNotChangeWhenCallerMutatesItsCollections() {
        val values=mutableMapOf("DISCIPLINE" to NpcAffect(100))
        val source=contract.copy(valuePreferences=values)
        val port=NpcActivityContractPort.registered(listOf(source))
        val old=port.contract("C",source.capabilityUid)!!.fingerprint
        values["DISCIPLINE"]=NpcAffect(9999)
        assertEquals(old,port.contract("C",source.capabilityUid)!!.fingerprint)
        assertNotEquals(source.fingerprint,old)
    }
}
