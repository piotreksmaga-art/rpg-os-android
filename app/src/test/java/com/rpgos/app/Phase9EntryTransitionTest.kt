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
class Phase9EntryTransitionTest {
    private lateinit var dbFile: File
    @Before fun setUp(){dbFile=File.createTempFile("rpgos-p9-entry-",".db");dbFile.delete()}
    @After fun tearDown(){dbFile.delete()}
    private fun open()=SQLiteDatabase.openOrCreateDatabase(dbFile,null)
    private fun fail(block:()->Unit){var failed=false;try{block()}catch(_:Throwable){failed=true};assertTrue(failed)}
    private fun path(uid:String,pack:String="W")=EvolutionPathDefinition(uid,pack,uid.lowercase(),uid,provenance="pack")
    private fun stage(uid:String,path:String,pack:String="W")=EvolutionStageDefinition(uid,path,pack,uid.lowercase(),uid,provenance="pack")

    private class Provider(var pass:Boolean):RequirementRuleProvider{
        override val providerUid="ENTRY-RULES"
        override fun descriptor(ruleUid:String)=if(ruleUid=="entry-rule") RequirementRuleDescriptor("entry-rule",1,setOf(RequirementGate.TRANSITION)) else null
        override fun evaluate(descriptor:RequirementRuleDescriptor,context:RequirementContext)=pass
    }

    @Test fun onlyExplicitEntryTransitionCanStartPathAndIllegalStartingStagesFail(){open().use{db->
        CurrentSchema.ensure(db,"C");val s=Phase9Store(db,"C")
        s.registerEvolutionPaths("W",listOf(path("P")))
        s.registerEvolutionStages("W",listOf(stage("A","P"),stage("B","P"),stage("C","P")))
        s.registerEvolutionTransitions("W",listOf(
            EvolutionTransitionDefinition("ENTRY-A","W",null,"A",provenance="pack"),
            EvolutionTransitionDefinition("AB","W","A","B",provenance="pack"),
            EvolutionTransitionDefinition("BC","W","B","C",provenance="pack")
        ))
        fail{s.transitionEvolution("P1","ENTRY-B","bad")}
        assertTrue(s.evolutionStates("P1").isEmpty());assertTrue(s.attainedStages("P1").isEmpty())
        fail{
            val m=Phase9Store::class.java.getDeclaredMethod("enterEvolutionPath",String::class.java,String::class.java,String::class.java,java.lang.Long::class.java)
            m.invoke(s,"P1","B","direct",null)
        }
        assertTrue(s.evolutionStates("P1").isEmpty());assertTrue(s.attainedStages("P1").isEmpty())
        s.transitionEvolution("P1","ENTRY-A","start")
        s.transitionEvolution("P1","AB","advance")
        s.transitionEvolution("P1","BC","advance")
        assertEquals("C",s.evolutionStates("P1").single().currentStageUid)
        assertEquals(setOf("A","B","C"),s.attainedStages("P1").map{it.stageUid}.toSet())
    }}

    @Test fun multipleExplicitEntryPointsAreLegalForDifferentFreshPlayers(){open().use{db->
        CurrentSchema.ensure(db,"C");val s=Phase9Store(db,"C")
        s.registerEvolutionPaths("W",listOf(path("P")));s.registerEvolutionStages("W",listOf(stage("A","P"),stage("B","P")))
        s.registerEvolutionTransitions("W",listOf(EvolutionTransitionDefinition("ENTRY-A","W",null,"A",provenance="pack"),EvolutionTransitionDefinition("ENTRY-B","W",null,"B",provenance="pack")))
        s.transitionEvolution("ONE","ENTRY-A","x");s.transitionEvolution("TWO","ENTRY-B","x")
        assertEquals("A",s.evolutionStates("ONE").single().currentStageUid);assertEquals("B",s.evolutionStates("TWO").single().currentStageUid)
        assertEquals("ENTRY-A",s.attainedStages("ONE").single().attainedViaTransitionUid);assertEquals("ENTRY-B",s.attainedStages("TWO").single().attainedViaTransitionUid)
    }}

    @Test fun entryRequirementFailCreatesZeroStateAndPassCreatesExactlyOneCurrentAndAttained(){open().use{db->
        CurrentSchema.ensure(db,"C");val provider=Provider(false);val s=Phase9Store(db,"C",provider)
        s.registerEvolutionPaths("W",listOf(path("P")));s.registerEvolutionStages("W",listOf(stage("A","P")))
        s.registerEvolutionTransitions("W",listOf(EvolutionTransitionDefinition("ENTRY-A","W",null,"A","entry-rule",provenance="pack")))
        fail{s.transitionEvolution("P","ENTRY-A","fail")}
        assertTrue(s.evolutionStates("P").isEmpty());assertTrue(s.attainedStages("P").isEmpty());assertTrue(s.formUnlocks("P").isEmpty());assertTrue(s.playerInnateFeatures("P").isEmpty())
        provider.pass=true;s.transitionEvolution("P","ENTRY-A","pass")
        assertEquals(1,s.evolutionStates("P").size);assertEquals("A",s.evolutionStates("P").single().currentStageUid)
        assertEquals(1,s.attainedStages("P").size);assertEquals("ENTRY-A",s.attainedStages("P").single().attainedViaTransitionUid)
    }}

    @Test fun replayEntryAndEntryRollbackAlwaysFail(){open().use{db->
        CurrentSchema.ensure(db,"C");val s=Phase9Store(db,"C")
        s.registerEvolutionPaths("W",listOf(path("P")));s.registerEvolutionStages("W",listOf(stage("A","P"),stage("B","P")))
        s.registerEvolutionTransitions("W",listOf(EvolutionTransitionDefinition("ENTRY-A","W",null,"A",provenance="pack"),EvolutionTransitionDefinition("ENTRY-B","W",null,"B",provenance="pack"),EvolutionTransitionDefinition("AB","W","A","B",provenance="pack")))
        s.transitionEvolution("P","ENTRY-A","start");fail{s.transitionEvolution("P","ENTRY-A","replay")};fail{s.transitionEvolution("P","ENTRY-B","switch-entry")}
        s.transitionEvolution("P","AB","advance");fail{s.transitionEvolution("P","ENTRY-A","rollback")};fail{s.transitionEvolution("P","ENTRY-B","re-entry")}
        assertEquals("B",s.evolutionStates("P").single().currentStageUid);assertEquals(setOf("A","B"),s.attainedStages("P").map{it.stageUid}.toSet())
    }}

    @Test fun entryOwnershipAndIndependentPathIsolationAreExplicit(){open().use{db->
        CurrentSchema.ensure(db,"C");val s=Phase9Store(db,"C")
        s.registerEvolutionPaths("W",listOf(path("P1"),path("P2")));s.registerEvolutionPaths("X",listOf(path("PX","X")))
        s.registerEvolutionStages("W",listOf(stage("A","P1"),stage("B","P2")));s.registerEvolutionStages("X",listOf(stage("X","PX","X")))
        fail{s.registerEvolutionTransitions("W",listOf(EvolutionTransitionDefinition("BAD","W",null,"X",provenance="pack")))}
        s.registerEvolutionTransitions("W",listOf(EvolutionTransitionDefinition("ENTRY-A","W",null,"A",provenance="pack"),EvolutionTransitionDefinition("ENTRY-B","W",null,"B",provenance="pack")))
        s.transitionEvolution("P","ENTRY-A","p1");s.transitionEvolution("P","ENTRY-B","p2")
        assertEquals(setOf("P1","P2"),s.evolutionStates("P").map{it.pathUid}.toSet())
        assertEquals("A",s.evolutionStates("P").single{it.pathUid=="P1"}.currentStageUid);assertEquals("B",s.evolutionStates("P").single{it.pathUid=="P2"}.currentStageUid)
        assertTrue(Phase9Store(db,"OTHER").evolutionStates("P").isEmpty())
    }}

    @Test fun legalEntryPersistsAcrossReopen(){
        open().use{db->CurrentSchema.ensure(db,"C");val s=Phase9Store(db,"C");s.registerEvolutionPaths("W",listOf(path("P")));s.registerEvolutionStages("W",listOf(stage("A","P")));s.registerEvolutionTransitions("W",listOf(EvolutionTransitionDefinition("ENTRY-A","W",null,"A",provenance="pack")));s.transitionEvolution("PLAYER","ENTRY-A","start")}
        open().use{db->CurrentSchema.ensure(db,"C");val s=Phase9Store(db,"C");assertEquals("A",s.evolutionStates("PLAYER").single().currentStageUid);assertEquals("ENTRY-A",s.attainedStages("PLAYER").single().attainedViaTransitionUid)}
    }
}
