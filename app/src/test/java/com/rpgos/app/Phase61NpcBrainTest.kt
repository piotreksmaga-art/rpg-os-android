package com.rpgos.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Phase61NpcBrainTest {
    private val actor=DomainRef("NPC","N1")
    private val base get()=NpcBrainOwner.initialize("C1",actor,"WORLD")
    private val acquired=NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"K1")

    @Test fun firstEncounterBrainRequiresTheExactAdmittedActorMaterialization() {
        val draft=WorldElementDraft("C1",DomainRef("ACTOR","NEW"),"Przewodnik",WorldElementBaseKind.ACTOR,"GUIDE","MARKET",
            setOf("TALK"),"LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE,emptyList(),null,null,null)
        val effect=VerifiedMechanicsCommandEffect("E","N","RPGOS-CORE:WORLD-MATERIALIZER","WORLD_ELEMENT_MATERIALIZE",draft.element,1,
            draft.materializationPayload(),"RPGOS-CORE:WORLD-MATERIALIZATION:${draft.fingerprint()}","INPUT","OUTPUT")
        assertEquals(setOf(draft.element),NpcBrainOwner.admittedMaterializations("C1",listOf(effect),listOf(draft)))
        assertTrue(NpcBrainOwner.admittedMaterializations("C1",emptyList(),listOf(draft)).isEmpty())
        assertTrue(NpcBrainOwner.admittedMaterializations("C1",listOf(effect),emptyList()).isEmpty())
        assertTrue(NpcBrainOwner.admittedMaterializations("OTHER",listOf(effect),listOf(draft)).isEmpty())
        for(bad in listOf(effect.copy(proofUid="AI"),effect.copy(mechanicsOwnerUid="AI"),effect.copy(magnitude=0),
            effect.copy(canonicalPayload=effect.canonicalPayload+("display_name" to "Someone else"))))
            assertTrue(NpcBrainOwner.admittedMaterializations("C1",listOf(bad),listOf(draft)).isEmpty())
        assertTrue(NpcBrainOwner.admittedMaterializations("C1",listOf(effect),listOf(draft.copy(
            element=DomainRef("OBJECT","NEW"),baseKind=WorldElementBaseKind.OBJECT))).isEmpty())
    }

    @Test fun identityAndTraitsAreStableAndIndependentOfMaterializationOrder() {
        val first=base
        NpcBrainOwner.initialize("C1",DomainRef("NPC","N2"),"WORLD")
        assertEquals(first,base)
        assertNotEquals(first.seedFingerprint,NpcBrainOwner.initialize("C2",actor,"WORLD").seedFingerprint)
        assertNotEquals(first.seedFingerprint,NpcBrainOwner.initialize("C1",DomainRef("NPC","N2"),"WORLD").seedFingerprint)
    }
    @Test fun knownTraitsOverrideDefaultsWithoutWorldPackOrGeneratedBackstory() {
        val state=NpcBrainOwner.initialize("C1",actor,"WORLD",mapOf("CAUTION" to NpcWeight(9000)))
        assertEquals(NpcWeight(9000),state.personality["CAUTION"])
        assertEquals(NpcWeight(9000),state.motivations.single{it.uid=="P61:SELF_PRESERVATION"}.strength)
        assertTrue(state.goals.isEmpty() && state.emotions.isEmpty() && state.dispositions.isEmpty())
    }
    @Test fun valuesAreStableIndependentAndExplicitValuesOverrideOnlyTheirOwnField() {
        val first=base
        val changed=NpcBrainOwner.initialize("C1",actor,"WORLD",knownValues=mapOf("COMPASSION" to NpcWeight(9999)))
        assertEquals(setOf("HONESTY","COMPASSION","AUTONOMY","LOYALTY","DISCIPLINE"),first.values.keys)
        assertEquals(first.personality,changed.personality)
        assertEquals(first.values.filterKeys{it!="COMPASSION"},changed.values.filterKeys{it!="COMPASSION"})
        assertEquals(NpcWeight(9999),changed.values["COMPASSION"])
        assertTrue(first.goals.isEmpty() && first.dispositions.isEmpty())
        // Older stored brains with no generated values remain valid and round-trip unchanged.
        val old=first.copy(values=emptyMap())
        assertEquals(old,NpcBrainCodec.decode(NpcBrainCodec.encode(old)))
        NpcBrainOwner.validateTransition(null,old,NpcBrainRules.GENESIS,listOf(NpcCauseRef(NpcCauseKind.GENESIS,"P61:GENESIS:${old.seedFingerprint}")))
    }
    @Test fun codecRetainsAllLayersAndCanonicalOrdering() {
        val m=NpcMotivation("M",NpcMotivationKind.OBLIGATION,"TEACH",NpcWeight(7000))
        val g=NpcGoal("G","M","Przygotować uczniów",NpcWeight(8000),NpcGoalLifecycle.ACTIVE,acquired,WorldTimeTick(90000))
        val state=base.copy(values=mapOf("LOYALTY" to NpcWeight(6000)),motivations=listOf(m),goals=listOf(g),
            emotions=listOf(NpcEmotion("CONCERN",NpcAffect(-1000),WorldTimeTick(100),acquired)),
            dispositions=listOf(NpcDisposition(DomainRef("PLAYER","P1"),NpcAffect(2000),NpcAffect(100),NpcWeight(0),acquired)),
            roleUids=setOf("TEACHER"),plans=listOf(NpcPlan("PLAN","G","TEACH",NpcPlanLifecycle.READY,null,WorldTimeTick(1000),acquired)))
        val encoded=NpcBrainCodec.encode(state)
        assertEquals(state,NpcBrainCodec.decode(encoded))
        assertEquals(encoded,NpcBrainCodec.encode(state.copy(personality=state.personality.entries.reversed().associate{it.toPair()})))
        assertEquals(NpcBrainCodec.fingerprint(state),NpcBrainCodec.fingerprint(NpcBrainCodec.decode(encoded)))
    }
    @Test fun codecRejectsUnknownFieldsVersionsAndStringNumbers() {
        val original=Json.parseToJsonElement(NpcBrainCodec.encode(base)).jsonObject
        listOf(original+("hiddenTruth" to JsonPrimitive("secret")),original+("schema" to JsonPrimitive(2)),
            original+("revision" to JsonPrimitive("1"))).forEach { bad ->
            assertTrue(runCatching{NpcBrainCodec.decode(JsonObject(bad).toString())}.isFailure)
        }
    }
    @Test fun scalesAndDependenciesAreTypedAndBounded() {
        assertTrue(runCatching{NpcWeight(-1)}.isFailure)
        assertTrue(runCatching{NpcAffect(10001)}.isFailure)
        assertTrue(runCatching{base.copy(goals=listOf(NpcGoal("G","MISSING","cel",NpcWeight(1),NpcGoalLifecycle.ACTIVE,acquired)))}.isFailure)
        assertTrue(runCatching{base.copy(roleUids=(0..32).map{"R$it"}.toSet())}.isFailure)
    }
    @Test fun appraisalCannotRewritePersonalityOrSocialRole() {
        val before=base
        val changed=before.copy(revision=2,personality=before.personality+("CAUTION" to NpcWeight(10000)))
        assertTrue(runCatching{NpcBrainOwner.validateTransition(before,changed,NpcBrainRules.APPRAISAL,listOf(acquired))}.isFailure)
        assertTrue(runCatching{NpcBrainOwner.validateTransition(before,before.copy(revision=2,roleUids=setOf("KING")),NpcBrainRules.APPRAISAL,listOf(acquired))}.isFailure)
    }
    @Test fun legalEmotionChangeNeedsItsOwnEvidenceAndMonotonicWorldTime() {
        val first=base.copy(emotions=listOf(NpcEmotion("FEAR",NpcAffect(100),WorldTimeTick(100),acquired)))
        val second=first.copy(revision=2,emotions=listOf(NpcEmotion("FEAR",NpcAffect(500),WorldTimeTick(200),acquired)))
        NpcBrainOwner.validateTransition(first,second,NpcBrainRules.APPRAISAL,listOf(acquired))
        assertTrue(runCatching{NpcBrainOwner.validateTransition(first,second,NpcBrainRules.APPRAISAL,listOf(acquired.copy(uid="K2")))}.isFailure)
        assertTrue(runCatching{NpcBrainOwner.validateTransition(first,second.copy(emotions=listOf(second.emotions.single().copy(updatedAt=WorldTimeTick(99)))),NpcBrainRules.APPRAISAL,listOf(acquired))}.isFailure)
    }
    @Test fun personalityAdaptationIsBoundedAndRequiresEvidence() {
        val first=base
        val after=first.copy(revision=2,personality=first.personality+("CAUTION" to NpcWeight(first.personality.getValue("CAUTION").basisPoints+200)))
        NpcBrainOwner.validateTransition(first,after,NpcBrainRules.ADAPTATION,listOf(acquired))
        val tooMuch=after.copy(personality=after.personality+("CAUTION" to NpcWeight(first.personality.getValue("CAUTION").basisPoints+251)))
        assertTrue(runCatching{NpcBrainOwner.validateTransition(first,tooMuch,NpcBrainRules.ADAPTATION,listOf(acquired))}.isFailure)
        assertTrue(runCatching{NpcBrainOwner.validateTransition(first,after,NpcBrainRules.ADAPTATION,listOf(NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION,"CMD")))}.isFailure)
    }
    @Test fun genesisAndPlanningDoNotSilentlyResetAnExistingActor() {
        assertTrue(runCatching{NpcBrainOwner.validateTransition(base,base.copy(revision=2),NpcBrainRules.GENESIS,listOf(NpcCauseRef(NpcCauseKind.GENESIS,"seed")))}.isFailure)
        assertTrue(runCatching{NpcBrainOwner.validateTransition(base,base.copy(revision=2,emotions=listOf(NpcEmotion("ANGER",NpcAffect(400),WorldTimeTick(1),acquired))),NpcBrainRules.PLANNING,listOf(acquired))}.isFailure)
    }
}
