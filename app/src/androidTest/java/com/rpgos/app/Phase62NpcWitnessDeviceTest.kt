package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4


@RunWith(AndroidJUnit4::class)

class Phase62NpcWitnessDeviceTest {
    @Test fun sharedLocationAloneNeverGrantsWitnessKnowledgeAndLegalDisclosureContainsNoHiddenFields()=SQLiteDatabase.create(null).use { db->
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        GameplayRuntimeBootstrap.initialize(db,"C1")
        val observer=DomainRef("NPC","O");val target=DomainRef("NPC","T")
        val authority=UniversalAccessAuthority(AccessAuthorityStore(db,"C1"))
        val trusted=TrustedPrincipalContext("C1",VisibilityPrincipalRef("NPC","O"),AudienceKinds.WORLD_ACTOR,roleUids=emptySet(),organizationUids=emptySet(),clearanceUids=emptySet())
        val scope=TemporalScope("C1","H",0,"STATE")
        val body=MechanicalActorView("C1",observer,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,emptyMap(),
            listOf(MechanicalResource("HEALTH",10,10)),setOf(NpcLegalEffectObservation.CAPABILITY),generationProvenanceUid="TEST")
        val effect=VerifiedMechanicsCommandEffect("E","NODE","UNIVERSAL_COMBAT","WOUND",target,4,
            mapOf("hidden_poison" to "secret","attacker" to "SECRET_ACTOR"),"PROOF:E","INPUT","OUTPUT")
        val recognized=listOf(NpcKnownRecord("K",KnowledgeEpistemicState.KNOWN,"Recognized","A",1,setOf(target)))
        val input=NpcWitnessPerceptionInput(body,"L","L",CombatPosition.Exact(0,0),CombatPosition.Exact(1000,0),recognized)
        fun project(i:NpcWitnessPerceptionInput=input)=NpcLegalEffectObservation.project(authority,trusted,scope,target,effect,i)
        assertNull(project()) // co-location + visual ability without a disclosure grant
        withAdministrativeMutationAuthority(db,"C1") {
            AccessAuthorityStore(db,"C1").apply(TurnTransactionIdentity("C1","T","C","TX"),"G",
                AccessAuthorityChange(AccessOperation.GRANT,"G","NPC","O",AccessGrantKind.WORLD_RULE.name,NpcLegalEffectObservation.POLICY,
                    "NPC","T",validFromOrder=0),0)
        }
        assertNotNull(project())
        assertNull(project(input.copy(recognized=emptyList())))
        assertNull(project(input.copy(subjectPosition=CombatPosition.Exact(5001,0))))
        assertNull(project(input.copy(body=body.copy(executableAbilityUids=emptySet()))))
        assertNull(project(input.copy(observerLocation=null,subjectLocation=null)))
        assertNull(project(input.copy(body=body.copy(conditions=listOf(MechanicalCondition("BLIND",1))))))
        val annotated=NpcWitnessObservation.annotate(scope,listOf(effect)){listOf(project()!!)}
        val acquired=NpcWitnessObservation.materialize("C1","CMD",1,annotated).changes.single().payload as KnowledgeAcquisitionChange
        assertEquals("Zaobserwowałem widoczny uraz.",acquired.claim.valueCanonical)
        assertEquals(KnowledgeEpistemicState.KNOWN,acquired.acquisition.epistemicState)
        assertFalse(acquired.toString().contains("SECRET_ACTOR"));assertFalse(acquired.toString().contains("secret"))
        assertTrue(runCatching{NpcWitnessObservation.materialize("OTHER","CMD",1,annotated)}.isFailure)
        assertTrue(runCatching{NpcWitnessObservation.materialize("C1","CMD",2,annotated)}.isFailure)
    }
}
