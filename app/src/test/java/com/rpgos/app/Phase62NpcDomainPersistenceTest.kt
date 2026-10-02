package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One small shared database exercises the actual domain appliers and prefix replay, not a
 * replacement NPC simulation. Model quality/device conformance are deliberately separate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase62NpcDomainPersistenceTest {
    @get:Rule val folder=TemporaryFolder()
    private val npc=DomainRef("NPC","N")
    private val learning=NpcActivityContract("TRAIN","WORLD:LEARN",1,ActionDuration(1000),"TRAINING",
        learning=NpcLearningRule("SKILL","POTTERY","XP",3))
    private val reading=NpcActivityContract("READ","WORLD:READ",1,ActionDuration(1000),"READING",
        reading=NpcReadingRule(DomainRef("BOOK","B"),"READ_POLICY",
            KnowledgeClaim("BOOK_CLAIM","LOCATION","A","HAS_DRAGON","yes",domainUid=KnowledgeDomains.WORLD_SPECIFIC)))
    private val treatment=NpcTreatmentRule(mapOf("HEALTH" to 10),5)
    private fun setup(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        GroupATransactionTestFixtures.setupFinance(db)
        withAdministrativeMutationAuthority(db,"C1") {
            MechanicalActorStateStore(db,"C1").materializeIfMissing(MechanicalActorSeed(npc,MechanicalActorKind.NPC,"T","S","TEST",
                mapOf("DEFENCE" to 20),listOf(MechanicalResource("HEALTH",50,100),MechanicalResource("STAMINA",10,10)),setOf("TRAIN","READ","MEDICAL_CARE")))
            db.execSQL("INSERT INTO mechanical_actor_tracks VALUES('C1','NPC','N','WOUND',10,1)")
            val skills=SkillStore(db,"C1")
            skills.registerDefinitions("W",listOf(SkillDefinition("POTTERY","W","pottery","Pottery","CRAFT",provenance="TEST")))
            skills.savePlayerSkill(PlayerSkill("C1","N","POTTERY",10.0,0.0,"XP",provenance="TEST"))
        }
    }
    private fun effects(mode:String,uid:String):List<VerifiedMechanicsCommandEffect> {
        fun effect(kind:String,units:Long,fields:Map<String,String>)=VerifiedMechanicsCommandEffect("E:$uid:$kind","NODE:$uid",
            NpcActivityMechanics.OWNER,kind,npc,units,fields,"P62:ACTIVITY:${learning.fingerprint}:$uid","INPUT","OUTPUT")
        return when(mode) {
            "LEARN"->listOf(effect("INTERACTION",1,mapOf("track_uid" to "TRAINING","magnitude" to "1","p60_core_duration_ms" to "1000",
                "npc_activity_contract" to learning.fingerprint)+NpcLearningApplication.fields("C1",npc,learning,NpcLearningState(10.0,0.0,"XP",1,1))))
            "READ"->listOf(effect("INTERACTION",1,mapOf("track_uid" to "READING","magnitude" to "1","npc_activity_contract" to reading.fingerprint)+
                NpcReadingApplication.fields("C1",npc,reading)))
            "TREAT"->listOf(effect("WOUND_HEALING",5,mapOf("magnitude" to "5","npc_treatment_contract" to treatment.fingerprint,"expected_wound_units" to "10")),
                effect("RESOURCE_DELTA",10,mapOf("magnitude" to "10","resource_uid" to "HEALTH")))
            else->error("mode")
        }
    }
    private fun proposal(mode:String,uid:String,order:Long):CanonicalCampaignMutationProposal {
        val actor=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(commandUid="CMD:$uid",campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN:$uid",effects(mode,uid)),provenance=CommandProvenance("TEST"),requestedEffectiveOrder=order)
        val refs=setOf(npc,DomainRef("PLAYER","P1"),DomainRef("SKILL","POTTERY"),DomainRef("RESOURCE","HEALTH"),
            DomainRef("CHARACTER","N"),DomainRef("BOOK","B"),DomainRef("LOCATION","A")).map{CampaignScopedDomainRef("C1",it)}.toSet()
        val admission=CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",actor,refs))
        assertTrue(admission.toString(),admission is CampaignMutationAdmission.Accepted)
        return (admission as CampaignMutationAdmission.Accepted).proposal
    }
    private fun commit(db:SQLiteDatabase,mode:String,uid:String,order:Long=1,injector:TurnFailureInjector=TurnFailureInjector.NONE)=
        TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN:$uid","CMD:$uid","TX:$uid"),proposal(mode,uid,order),injector).commit()
    private fun progress(db:SQLiteDatabase)=SkillStore(db,"C1").playerSkills("N").single().progressValue!!
    private fun wound(db:SQLiteDatabase)=MechanicalActorStateStore(db,"C1").actor(npc)!!.conditions.single{it.conditionUid=="WOUND"}.intensity
    private fun health(db:SQLiteDatabase)=MechanicalActorStateStore(db,"C1").actor(npc)!!.resources.single{it.resourceUid=="HEALTH"}.current

    @Test fun eachDomainRollsBackAndRetriesWithoutDoubleEffect() {
        for(mode in listOf("LEARN","READ","TREAT"))SQLiteDatabase.create(null).use { db->
            setup(db);val before=AuthoritativeStateDigest.compute(db)
            assertTrue(runCatching{commit(db,mode,mode,injector=TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("injected")})}.isFailure)
            assertEquals(mode,before,AuthoritativeStateDigest.compute(db))
            assertTrue(commit(db,mode,mode) is TurnExecutionResult.Committed)
            val after=AuthoritativeStateDigest.compute(db)
            assertTrue(commit(db,mode,mode) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(mode,after,AuthoritativeStateDigest.compute(db))
            when(mode) {
                "LEARN"->{assertEquals(3.0,progress(db),0.0);assertEquals(10.0,SkillStore(db,"C1").playerSkills("N").single().baseMastery,0.0)}
                "READ"->db.rawQuery("SELECT epistemic_state_uid FROM world_actor_knowledge_states WHERE campaign_uid='C1' AND holder_uid='N' AND claim_uid='BOOK_CLAIM'",null).use{
                    assertTrue(it.moveToFirst());assertEquals(KnowledgeEpistemicState.BELIEVED.name,it.getString(0))}
                "TREAT"->{assertEquals(5L,wound(db));assertEquals(60L,health(db))}
            }
        }
    }
    @Test fun sharedPrefixReplayReopenUndoAndAlternativePreserveOtherDomains() {
        val file=File(folder.root,"domain.db");val snapshots=File(folder.root,"snapshots")
        var db=SQLiteDatabase.openOrCreateDatabase(file,null)
        try {
            setup(db);CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
            assertTrue(commit(db,"LEARN","L",1) is TurnExecutionResult.Committed)
            assertTrue(commit(db,"READ","R",2) is TurnExecutionResult.Committed)
            assertTrue(commit(db,"TREAT","T",3) is TurnExecutionResult.Committed)
            db.close();db=SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
            assertEquals(3.0,progress(db),0.0);assertEquals(5L,wound(db));assertEquals(60L,health(db))
            val undo=DestructiveTurnUndoCoordinator(db,"C1",snapshots,file)
            val preview=undo.previewLastTurn();assertTrue(preview.toString(),preview.canConfirm)
            val result=undo.confirm(preview);assertTrue(result.toString(),result is DestructiveUndoResult.Completed)
            db=SQLiteDatabase.openDatabase(file.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
            assertEquals(3.0,progress(db),0.0);assertEquals(10L,wound(db));assertEquals(50L,health(db))
            assertNull(TurnTransactionReceiptStore(db).committedCommand("C1","CMD:T"))
            assertTrue(commit(db,"TREAT","OTHER",3) is TurnExecutionResult.Committed)
            assertEquals(5L,wound(db));assertEquals(60L,health(db))
        } finally {if(db.isOpen)db.close()}
    }
}
