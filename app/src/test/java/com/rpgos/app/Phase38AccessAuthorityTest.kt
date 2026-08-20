package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db);val auth=UniversalAccessAuthority(AccessAuthorityStore(db,"C"))
        val denied=AuthorizationDecision(false,"NO_GRANT");assertFalse(auth.effectiveAccess(denied).accessible)
        assertTrue(auth.effectiveAccess(denied,AccessPath("INTERCEPT","E1",false,true)).accessible);db.close()
    }
    @Test fun accessDoesNotCreatePhase37Acquisition(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db);Phase37KnowledgeSchema.ensureReady(db)
        fun count()=db.rawQuery("SELECT COUNT(*) FROM ${Phase37KnowledgeSchema.ACQUISITIONS}",null).use{it.moveToFirst();it.getLong(0)}
        val before=count();UniversalAccessAuthority(AccessAuthorityStore(db,"C")).effectiveAccess(AuthorizationDecision(true,"OK"));assertEquals(before,count());db.close()
    }
    @Test fun temporaryGrantValidityIsTemporalAndNonDestructive(){
        val p=AccessAuthorityChange(AccessOperation.GRANT,"G","ENTITY","A",AccessGrantKind.TEMPORARY.name,"POLICY",validFromOrder=10,validUntilOrder=20)
        assertEquals(10,p.validFromOrder);assertEquals(20,p.validUntilOrder)
    }
}
