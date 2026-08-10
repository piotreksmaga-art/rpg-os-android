package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase9RequirementGatesTest {
    private lateinit var dbFile: File
    @Before fun setUp(){ dbFile=File.createTempFile("rpgos-p9-gates-",".db");dbFile.delete() }
    @After fun tearDown(){ dbFile.delete() }
    private fun open()=SQLiteDatabase.openOrCreateDatabase(dbFile,null)
    private fun fail(block:()->Unit){ var failed=false;try{block()}catch(_:Throwable){failed=true};assertTrue(failed) }

    private class Provider(
        val descriptors: MutableMap<String,RequirementRuleDescriptor> = linkedMapOf(),
        val results: MutableMap<String,Boolean?> = linkedMapOf()
    ): RequirementRuleProvider {
        override val providerUid="TEST-REQUIREMENTS"
        override fun descriptor(ruleUid:String)=descriptors[ruleUid]
        override fun evaluate(descriptor:RequirementRuleDescriptor,context:RequirementContext):Boolean?=results[descriptor.ruleUid]
        fun rule(uid:String,gate:RequirementGate,version:Long=1L,result:Boolean?=true,dependencies:List<String> = emptyList()){
            descriptors[uid]=RequirementRuleDescriptor(uid,version,setOf(gate),dependencies)
            results[uid]=result
        }
    }

    private fun path(uid:String)=EvolutionPathDefinition(uid,"W",uid.lowercase(),uid,provenance="pack")
    private fun stage(uid:String,path:String)=EvolutionStageDefinition(uid,path,"W",uid.lowercase(),uid,provenance="pack")
    private fun form(uid:String,unlock:String?=null,activation:String?=null)=FormDefinition(
        uid,"W",uid.lowercase(),uid,activationRuleUid=activation,provenance="pack",
        unlockRequirementRuleUid=unlock
    )

    @Test fun unlockWithoutRequirementAndPassFailMissingProviderAreAtomic(){open().use{db->
        CurrentSchema.ensure(db,"C")
        val provider=Provider().apply{rule("unlock-pass",RequirementGate.UNLOCK,result=true);rule("unlock-fail",RequirementGate.UNLOCK,result=false)}
        val s=Phase9Store(db,"C",provider)
        s.registerForms("W",listOf(form("OPEN"),form("PASS","unlock-pass"),form("FAIL","unlock-fail"),form("MISSING","unknown")))
        s.unlockForm(PlayerFormUnlock("C","P","OPEN",provenance="x"))
        s.unlockForm(PlayerFormUnlock("C","P","PASS",provenance="x"))
        fail{s.unlockForm(PlayerFormUnlock("C","P","FAIL",provenance="x"))}
        fail{s.unlockForm(PlayerFormUnlock("C","P","MISSING",provenance="x"))}
        assertEquals(setOf("OPEN","PASS"),s.formUnlocks("P").map{it.formUid}.toSet())
        assertTrue(s.activeForms("P").isEmpty())
        val noProvider=Phase9Store(db,"C")
        s.registerForms("W",listOf(form("NOPROVIDER","unlock-pass")))
        fail{noProvider.unlockForm(PlayerFormUnlock("C","P","NOPROVIDER",provenance="x"))}
        assertFalse(noProvider.formUnlocks("P").any{it.formUid=="NOPROVIDER"})
    }}

    @Test fun transitionRequirementPassFailPreservesCurrentAndHistory(){open().use{db->
        CurrentSchema.ensure(db,"C")
        val provider=Provider().apply{rule("transition-pass",RequirementGate.TRANSITION,result=true);rule("transition-fail",RequirementGate.TRANSITION,result=false)}
        val s=Phase9Store(db,"C",provider)
        s.registerEvolutionPaths("W",listOf(path("P")))
        s.registerEvolutionStages("W",listOf(stage("A","P"),stage("B","P"),stage("C","P")))
        s.registerEvolutionTransitions("W",listOf(
            EvolutionTransitionDefinition("ENTRY-A","W",null,"A","transition-pass",provenance="pack"),
            EvolutionTransitionDefinition("AB","W","A","B","transition-pass",provenance="pack"),
            EvolutionTransitionDefinition("BC","W","B","C","transition-fail",provenance="pack")
        ))
        s.transitionEvolution("PLAYER","ENTRY-A","start")
        s.transitionEvolution("PLAYER","AB","pass")
        assertEquals("B",s.evolutionStates("PLAYER").single().currentStageUid)
        val before=s.attainedStages("PLAYER").map{it.stageUid}
        fail{s.transitionEvolution("PLAYER","BC","fail")}
        assertEquals("B",s.evolutionStates("PLAYER").single().currentStageUid)
        assertEquals(before,s.attainedStages("PLAYER").map{it.stageUid})
        assertFalse(s.attainedStages("PLAYER").any{it.stageUid=="C"})
    }}

    @Test fun activationRequirementPassFailModifierAtomicityAndDeactivateNeedsNoRequirement(){open().use{db->
        CurrentSchema.ensure(db,"C")
        StatResourceStore(db,"C").registerStatDefinitions("W",listOf(StatDefinition("S","s","generic",worldPackUid="W")))
        val provider=Provider().apply{rule("activate-pass",RequirementGate.ACTIVATION,result=true);rule("activate-fail",RequirementGate.ACTIVATION,result=false)}
        val s=Phase9Store(db,"C",provider)
        s.registerForms("W",listOf(form("PASS",activation="activate-pass"),form("FAIL",activation="activate-fail")))
        s.registerFormModifierBindings("W",listOf(
            FormModifierBinding("MP","W","PASS","S",ModifierTargetKind.STAT_EFFECTIVE,ModifierOperation.ADD_FLAT,5.0,provenance="pack"),
            FormModifierBinding("MF","W","FAIL","S",ModifierTargetKind.STAT_EFFECTIVE,ModifierOperation.ADD_FLAT,7.0,provenance="pack")
        ))
        s.unlockForm(PlayerFormUnlock("C","P","PASS",provenance="unlock"));s.unlockForm(PlayerFormUnlock("C","P","FAIL",provenance="unlock"))
        s.activateForm(PlayerActiveForm("C","P","PASS",provenance="on"))
        assertEquals("PASS",s.activeForms("P").single().formUid)
        provider.results["activate-pass"]=false
        s.deactivateForm("P","PASS")
        assertTrue(s.activeForms("P").isEmpty())
        assertTrue(s.formUnlocks("P").any{it.formUid=="PASS"})
        fail{s.activateForm(PlayerActiveForm("C","P","FAIL",provenance="bad"))}
        assertTrue(s.formUnlocks("P").any{it.formUid=="FAIL"})
        assertFalse(s.activeForms("P").any{it.formUid=="FAIL"})
        assertFalse(ModifierStore(db,"C").modifiers("P").any{it.sourceUid=="FAIL" && it.sourceActive})
    }}

    @Test fun gatesCannotSubstituteForEachOtherAndVersionMissingMalformedCycleFail(){open().use{db->
        CurrentSchema.ensure(db,"C")
        val provider=Provider()
        provider.rule("unlock-only",RequirementGate.UNLOCK,result=true)
        provider.rule("transition-only",RequirementGate.TRANSITION,result=true)
        provider.rule("activation-only",RequirementGate.ACTIVATION,result=true)
        provider.rule("wrong-version",RequirementGate.UNLOCK,version=2,result=true)
        provider.rule("malformed",RequirementGate.UNLOCK,result=null)
        provider.rule("cycle-a",RequirementGate.UNLOCK,result=true,dependencies=listOf("cycle-b"))
        provider.rule("cycle-b",RequirementGate.UNLOCK,result=true,dependencies=listOf("cycle-a"))
        val s=Phase9Store(db,"C",provider)
        s.registerForms("W",listOf(
            form("TRANSITION-AS-UNLOCK","transition-only"),
            FormDefinition("WRONGVER","W","wrongver","WRONGVER",provenance="pack",unlockRequirementRuleUid="wrong-version",unlockRequirementRuleVersion=1),
            form("MALFORMED","malformed"),form("CYCLE","cycle-a"),
            form("UNLOCK-AS-ACTIVATION",activation="unlock-only")
        ))
        listOf("TRANSITION-AS-UNLOCK","WRONGVER","MALFORMED","CYCLE").forEach{uid->fail{s.unlockForm(PlayerFormUnlock("C","P",uid,provenance="x"))}}
        s.unlockForm(PlayerFormUnlock("C","P","UNLOCK-AS-ACTIVATION",provenance="x"))
        fail{s.activateForm(PlayerActiveForm("C","P","UNLOCK-AS-ACTIVATION",provenance="x"))}

        s.registerEvolutionPaths("W",listOf(path("P")));s.registerEvolutionStages("W",listOf(stage("A","P"),stage("B","P")))
        s.registerEvolutionTransitions("W",listOf(
            EvolutionTransitionDefinition("ENTRY-A","W",null,"A","transition-only",provenance="pack"),
            EvolutionTransitionDefinition("BADGATE","W","A","B","activation-only",provenance="pack")
        ))
        s.transitionEvolution("P","ENTRY-A","start")
        fail{s.transitionEvolution("P","BADGATE","x")}
        assertEquals("A",s.evolutionStates("P").single().currentStageUid)
    }}

    @Test fun successfulRequirementStatePersistsAndHotfixMigrationIsIdempotent(){
        open().use{db->
            CurrentSchema.ensure(db,"C");CurrentSchema.ensure(db,"C")
            assertEquals(1,scalar(db,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE9_REQUIREMENT_HOTFIX_MIGRATION_ID'"))
            val p=Provider().apply{rule("u",RequirementGate.UNLOCK);rule("a",RequirementGate.ACTIVATION)}
            val s=Phase9Store(db,"C",p)
            s.registerForms("W",listOf(form("F","u","a")))
            s.unlockForm(PlayerFormUnlock("C","P","F",provenance="x"))
            s.activateForm(PlayerActiveForm("C","P","F",provenance="x"))
            assertEquals(1,s.formUnlocks("P").size);assertEquals(1,s.activeForms("P").size)
            db.rawQuery("PRAGMA integrity_check",null).use{c->assertTrue(c.moveToFirst());assertEquals("ok",c.getString(0))}
            db.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}
        }
        open().use{db->
            CurrentSchema.ensure(db,"C")
            val s=Phase9Store(db,"C")
            assertEquals("F",s.formUnlocks("P").single().formUid)
            assertEquals("F",s.activeForms("P").single().formUid)
        }
    }

    private fun scalar(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{c->assertTrue(c.moveToFirst());c.getInt(0)}
}
