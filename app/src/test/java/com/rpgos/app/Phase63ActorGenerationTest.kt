package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63ActorGenerationTest {
    private val skeleton=CampaignWorldSkeleton.legacy("C",CampaignRuleSource(CampaignRuleSourceKind.CAMPAIGN_NATIVE,"C","1"),"Era",DomainRef("PLACE","A"))
        .copy(latentRules=CoreLatentWorldRules.initial())
    private val slot=LatentWorldSlot("A","LOCAL_PERSON",WorldElementBaseKind.ACTOR)
    private val draft=WorldElementDraft("C",slot.ref(skeleton),"Osoba",WorldElementBaseKind.ACTOR,"LOCAL_PERSON","A",setOf("TALK"),"LOCAL_SITE",
        WorldEvidenceClassification.GENERATED_PLAUSIBLE,emptyList(),null,null,null)
    @Test fun separatedDomainAndIncrementalLevelsNeverCreateHistoryOrExceptionalAbilities() {
        val seed=requireNotNull(Phase63ActorGeneration.forDraft(skeleton,draft,MechanicalStateMaterialization.SEED_ONLY))
        val partial=requireNotNull(Phase63ActorGeneration.forDraft(skeleton,draft,MechanicalStateMaterialization.PARTIAL))
        val full=requireNotNull(Phase63ActorGeneration.forDraft(skeleton,draft,MechanicalStateMaterialization.FULL))
        assertEquals(seed.seedUid,partial.seedUid);assertEquals(seed.seedUid,full.seedUid)
        assertTrue(seed.attributes.isEmpty());assertTrue(seed.resources.isEmpty());assertTrue(seed.abilities.isEmpty())
        assertEquals(full,Phase63ActorGeneration.forDraft(skeleton,draft.copy(displayName="Inna prezentacja"),MechanicalStateMaterialization.FULL))
        assertEquals(setOf("ATTACK","STRIKE","DEFEND","TRAVEL"),full.abilities)
        assertEquals(emptySet<String>(),full.traits)
        assertNotEquals(full.seedUid,skeleton.domainSeed("PERSONALITY",slot.canonicalKey))
        assertNull(Phase63ActorGeneration.forDraft(skeleton,draft.copy(element=DomainRef("ACTOR","AI-ID")),MechanicalStateMaterialization.FULL))
    }
    @Test fun oldGenesisBytesRemainReadableAndNewProfileIsExplicit() {
        val old=MechanicalActorGenesisChange(DomainRef("ACTOR","OLD"),"Stary",null,"RPGOS-CORE:WORLD-MATERIALIZATION:${"a".repeat(64)}")
        val codec=mechanicalActorGenesisCodec()
        assertFalse("domain_seed" in codec.encode(old));assertEquals(old,codec.decode(codec.encode(old)))
        val new=old.copy(actor=draft.element,parentAnchorUid="A",profileVersion=2,domainSeed=skeleton.domainSeed("MECHANICS",slot.canonicalKey))
        assertEquals(new,codec.decode(codec.encode(new)))
        assertThrows(IllegalArgumentException::class.java) { old.copy(domainSeed="a".repeat(64)) }
    }
}
