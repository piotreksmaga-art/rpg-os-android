package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class Phase38AccessAuthorityTest {
    @Test fun roleBindingIsPrincipalScopedAndCannotBeSelfAsserted(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val a=AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"))
        val noStore=AccessAuthorityStore(db,"C");val auth=UniversalAccessAuthority(noStore)
        val trustedFromCanonical=auth.trustedContext(a)!!
        assertFalse(auth.authorize(trustedFromCanonical,AccessRequirement("P",requiredRoleUids=setOf("ROLE"))).authorized)
        db.close()
    }
    @Test fun authorizationAndEffectiveAccessAreDistinct(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val auth=UniversalAccessAuthority(AccessAuthorityStore(db,"C"))
        val audience=AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"))
        val trusted=auth.trustedContext(audience)!!
        val carrier=InformationCarrierRef("C","WORLD_DEFINED_ENCRYPTED_REPORT","REPORT-1")
        val required=setOf(CarrierAccessStage.REACHABLE,CarrierAccessStage.AVAILABLE,CarrierAccessStage.OPENED,CarrierAccessStage.DECODED,CarrierAccessStage.COMPREHENDED)
        val requirement=AccessRequirement("P",explicitGrantRequired=true,carrier=carrier,requiredCarrierStages=required)
        val denied=auth.authorize(trusted,requirement)
        assertFalse(denied.authorized)
        assertFalse(auth.effectiveAccess(trusted,requirement,denied).accessible)

        val legalBypass=Phase38AccessRuntimeAuthority.issuePath(trusted,carrier,"WORLD_RULE_INTERCEPT","E1",true,required)
        val effective=auth.effectiveAccess(trusted,requirement,denied,legalBypass)
        assertTrue(effective.accessible)
        assertEquals("EFFECTIVE_WORLD_RULE_ACCESS:WORLD_RULE_INTERCEPT",effective.reasonCode)
        db.close()
    }
    @Test fun accessDoesNotCreatePhase37Acquisition(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db);Phase37KnowledgeSchema.ensureReady(db)
        fun count()=db.rawQuery("SELECT COUNT(*) FROM ${Phase37KnowledgeSchema.ACQUISITIONS}",null).use{it.moveToFirst();it.getLong(0)}
        val auth=UniversalAccessAuthority(AccessAuthorityStore(db,"C"))
        val trusted=auth.trustedContext(AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A")))!!
        val requirement=AccessRequirement("P")
        val before=count();auth.effectiveAccess(trusted,requirement,auth.authorize(trusted,requirement));assertEquals(before,count());db.close()
    }
    @Test fun temporaryGrantValidityIsTemporalAndNonDestructive(){
        val p=AccessAuthorityChange(AccessOperation.GRANT,"G","ENTITY","A",AccessGrantKind.TEMPORARY.name,"POLICY",validFromOrder=10,validUntilOrder=20)
        assertEquals(10L,p.validFromOrder);assertEquals(20L,p.validUntilOrder)
    }
    @Test fun carrierAvailabilityDecodeAndComprehensionAreRequiredForFullAccess(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val auth=UniversalAccessAuthority(AccessAuthorityStore(db,"C"))
        val trusted=auth.trustedContext(AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A")))!!
        val carrier=InformationCarrierRef("C","WORLD_DEFINED_CARRIER","R")
        val required=CarrierAccessStage.entries.toSet()
        val requirement=AccessRequirement("P",carrier=carrier,requiredCarrierStages=required)
        val authorization=auth.authorize(trusted,requirement)

        fun path(stages:Set<CarrierAccessStage>)=Phase38AccessRuntimeAuthority.issuePath(trusted,carrier,"READ","E",false,stages)
        val encrypted=auth.effectiveAccess(trusted,requirement,authorization,path(required-CarrierAccessStage.DECODED))
        assertFalse(encrypted.accessible);assertTrue(encrypted.reasonCode.contains("DECODED"))
        val unknownLanguage=auth.effectiveAccess(trusted,requirement,authorization,path(required-CarrierAccessStage.COMPREHENDED))
        assertFalse(unknownLanguage.accessible);assertTrue(unknownLanguage.reasonCode.contains("COMPREHENDED"))
        assertTrue(auth.effectiveAccess(trusted,requirement,authorization,path(required)).accessible)
        db.close()
    }
    @Test fun accessPathCannotBeCallerConstructedOrReusedForAnotherPrincipal(){
        listOf(AccessPath::class.java,AuthorizationDecision::class.java,EffectiveAccessDecision::class.java).forEach { type ->
            assertTrue(type.declaredConstructors.filterNot{it.isSynthetic}.all{Modifier.isPrivate(it.modifiers)})
        }
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db);val auth=UniversalAccessAuthority(AccessAuthorityStore(db,"C"))
        val a=auth.trustedContext(AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A")))!!
        val b=auth.trustedContext(AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","B")))!!
        val carrier=InformationCarrierRef("C","REPORT","R");val required=CarrierAccessStage.entries.toSet()
        val requirement=AccessRequirement("P",explicitGrantRequired=true,carrier=carrier,requiredCarrierStages=required)
        val pathA=Phase38AccessRuntimeAuthority.issuePath(a,carrier,"INTERCEPT","E",true,required)
        val result=auth.effectiveAccess(b,requirement,auth.authorize(b,requirement),pathA)
        assertFalse(result.accessible);assertEquals("CARRIER_PATH_BINDING_MISMATCH",result.reasonCode);db.close()
    }
}
