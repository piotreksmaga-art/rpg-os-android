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
class DevelopmentProjectPersistenceTest {
    private lateinit var f:File
    @Before fun setUp(){ f=File.createTempFile("p15-project-",".db");f.delete() }
    @After fun tearDown(){ f.delete() }
    private fun db()=SQLiteDatabase.openOrCreateDatabase(f,null)
    private fun p(uid:String)=OwnershipOwnerRef("CHARACTER",uid)
    private fun setup(d:SQLiteDatabase,campaign:String="C"):DevelopmentProjectStore{
        CurrentSchema.ensure(d,campaign)
        val refs=OwnershipReferenceRegistry(d,campaign)
        listOf("A","B").forEach{runCatching{refs.registerOwner(p(it),"p15-test")}}
        return DevelopmentProjectStore(d,campaign)
    }
    private fun project(uid:String="P",campaign:String="C",output:String?=null,cap:Long?=100)=DevelopmentProject(campaign,uid,PROJECT_TYPE_RESEARCH,p("A"),p("B"),"Project $uid","Long-running objective","RESEARCH",intendedOutputKindUid=output,progressCapUnits=cap,createdOrder=1,provenance="p15-test")
    private fun fail(block:()->Unit){var failed=false;try{block()}catch(_:Throwable){failed=true};assertTrue(failed)}

    @Test fun lifecycleRequirementsMilestonesProgressAndFailuresRemainAuditable(){db().use{d->
        val s=setup(d);val p=project();assertEquals(p,s.createProject(p,"S-IDEA"));assertEquals(p,s.createProject(p.copy(),"S-IDEA"));assertEquals(1L,n(d,"SELECT COUNT(*) FROM development_projects"));assertEquals(ProjectStatus.IDEA,s.currentStatus("P"))
        val r=ProjectRequirement("C","REQ","P","RPGOS-REQ:KNOWLEDGE",requiredFromOrder=1,provenance="requirement");s.addRequirement(r);s.changeStatus(ProjectStatusEvent("C","S-REQ","P",ProjectStatus.REQUIREMENTS,2,provenance="requirements"));fail{s.changeStatus(ProjectStatusEvent("C","S-PROTO-BAD","P",ProjectStatus.PROTOTYPE,3,provenance="bad"))}
        s.satisfyRequirement(ProjectRequirementSatisfaction("C","SAT","P","REQ",3,provenance="verified"));s.changeStatus(ProjectStatusEvent("C","S-PROTO","P",ProjectStatus.PROTOTYPE,4,provenance="prototype"))
        val m=ProjectMilestoneDefinition("C","M1","P",1,"RPGOS-MILESTONE:STABLE","Stable prototype",true,"milestone");s.addMilestone(m);s.changeStatus(ProjectStatusEvent("C","S-WORK","P",ProjectStatus.ACTIVE_WORK,5,provenance="work"))
        s.recordWork(ProjectWorkRecord("C","W1","P","EXPERIMENT",p("A"),6,ProjectWorkResult.FAILURE,0,10,provenance="failed attempt"));s.recordWork(ProjectWorkRecord("C","W2","P","EXPERIMENT",p("A"),7,ProjectWorkResult.PARTIAL,40,10,provenance="partial"));s.recordWork(ProjectWorkRecord("C","W3","P","EXPERIMENT",p("A"),8,ProjectWorkResult.BREAKTHROUGH,60,10,provenance="breakthrough"));fail{s.recordWork(ProjectWorkRecord("C","W4","P","EXPERIMENT",p("A"),9,ProjectWorkResult.SUCCESS,1,provenance="overflow cap"))}
        val snap=s.progress("P");assertEquals(100L,snap.progressUnits);assertEquals(3L,snap.workRecordCount);assertEquals(100L,snap.progressCapUnits)
        s.changeStatus(ProjectStatusEvent("C","S-STAB","P",ProjectStatus.STABILIZATION,9,provenance="stabilize"));fail{s.changeStatus(ProjectStatusEvent("C","S-READY-BAD","P",ProjectStatus.READY_TO_COMPLETE,10,provenance="missing milestone"))}
        s.achieveMilestone(ProjectMilestoneAchievement("C","ACH","P","M1",10,"W3",provenance="verified milestone"));s.changeStatus(ProjectStatusEvent("C","S-READY","P",ProjectStatus.READY_TO_COMPLETE,11,provenance="ready"));s.changeStatus(ProjectStatusEvent("C","S-DONE","P",ProjectStatus.COMPLETED,12,provenance="complete"));assertEquals(ProjectStatus.COMPLETED,s.currentStatus("P"));assertEquals(7L,n(d,"SELECT COUNT(*) FROM project_status_history WHERE project_uid='P'"));assertEquals(3L,n(d,"SELECT COUNT(*) FROM project_work_records WHERE project_uid='P'"));checks(d)
    }}

    @Test fun stableUidExactReplayReturnsCanonicalAndConflictsReject(){db().use{d->
        val s=setup(d);val type=ProjectTypeDefinition("WP:P15:TYPE","CUSTOM","WP:LIFE","WP-X","ACTIVE",2,"typed",null);assertEquals(type,s.registerProjectType(type));assertEquals(type,s.registerProjectType(type.copy()));fail{s.registerProjectType(type.copy(genericCategoryUid="OTHER"))}
        val p=project("R");s.createProject(p,"R-IDEA");assertEquals(p,s.createProject(p.copy(),"R-IDEA"));fail{s.createProject(p.copy(title="conflict"),"R-IDEA")}
        s.changeStatus(ProjectStatusEvent("C","R-REQ","R",ProjectStatus.REQUIREMENTS,2,provenance="r"));s.changeStatus(ProjectStatusEvent("C","R-PROT","R",ProjectStatus.PROTOTYPE,3,provenance="r"));s.changeStatus(ProjectStatusEvent("C","R-ACT","R",ProjectStatus.ACTIVE_WORK,4,provenance="r"))
        val w=ProjectWorkRecord("C","RW","R","TEST",p("A"),5,ProjectWorkResult.PARTIAL,10,sourceEventUid="EV",commandUid="CMD",provenance="work");assertEquals(w,s.recordWork(w));assertEquals(w,s.recordWork(w.copy()));fail{s.recordWork(w.copy(progressDeltaUnits=11))};fail{s.recordWork(w.copy(sourceEventUid="OTHER"))};assertEquals(1L,n(d,"SELECT COUNT(*) FROM project_work_records WHERE work_record_uid='RW'"));checks(d)
    }}

    @Test fun financeEvidenceIsReferenceOnlyAndProjectCannotFabricateMoney(){db().use{d->
        val s=setup(d);val fs=FinancialStore(d,"C");fs.registerCurrency(CurrencyDefinition("CUR","cur","Currency",1,"p15"));fs.openAccount(FinancialAccount("C","ACC",p("A"),FINANCIAL_ACCOUNT_TYPE_DEFAULT,"CUR",0,"p15"));fs.creditExternal("FUND","ACC",100,1,"funding","p15");val before=fs.balance("ACC")
        val p=project("F",cap=20);s.createProject(p,"F-I");s.changeStatus(ProjectStatusEvent("C","F-R","F",ProjectStatus.REQUIREMENTS,2,provenance="r"));s.changeStatus(ProjectStatusEvent("C","F-P","F",ProjectStatus.PROTOTYPE,3,provenance="p"));s.changeStatus(ProjectStatusEvent("C","F-A","F",ProjectStatus.ACTIVE_WORK,4,provenance="a"));s.recordWork(ProjectWorkRecord("C","FW","F","FUNDED_TEST",p("A"),5,ProjectWorkResult.SUCCESS,5,financialTransactionUid="FUND",provenance="evidence"));assertEquals(before,fs.balance("ACC"));fail{s.recordWork(ProjectWorkRecord("C","BAD-TX","F","TEST",p("A"),6,ProjectWorkResult.SUCCESS,1,financialTransactionUid="NO-TX",provenance="bad"))};checks(d)
    }}

    @Test fun durableOutputMustPreexistInItsAuthorityBeforeCompletion(){db().use{d->
        val s=setup(d);val p=project("OUT",output=PROJECT_OUTPUT_ITEM_INSTANCE,cap=null);s.createProject(p,"O-I");s.changeStatus(ProjectStatusEvent("C","O-R","OUT",ProjectStatus.REQUIREMENTS,2,provenance="r"));s.changeStatus(ProjectStatusEvent("C","O-P","OUT",ProjectStatus.PROTOTYPE,3,provenance="p"));s.changeStatus(ProjectStatusEvent("C","O-A","OUT",ProjectStatus.ACTIVE_WORK,4,provenance="a"));s.changeStatus(ProjectStatusEvent("C","O-S","OUT",ProjectStatus.STABILIZATION,5,provenance="s"));s.changeStatus(ProjectStatusEvent("C","O-READY","OUT",ProjectStatus.READY_TO_COMPLETE,6,provenance="ready"));fail{s.changeStatus(ProjectStatusEvent("C","O-DONE-BAD","OUT",ProjectStatus.COMPLETED,7,provenance="no output"))};fail{s.commitOutcome(ProjectOutcome("C","O-BAD","OUT",PROJECT_OUTPUT_ITEM_INSTANCE,null,"GHOST",7,provenance="bad"))}
        val inv=InventoryStore(d,"C");inv.registerDefinitions("WP",listOf(ItemDefinition("DEF","WP","crafted","Crafted","CRAFT",ItemStoragePolicy.UNIQUE_INSTANCE,provenance="typed")));inv.createInstance(ItemInstance("C","ITEM-1","DEF",provenance="typed output"));val out=ProjectOutcome("C","O-1","OUT",PROJECT_OUTPUT_ITEM_INSTANCE,null,"ITEM-1",7,provenance="link only");assertEquals(out,s.commitOutcome(out));s.changeStatus(ProjectStatusEvent("C","O-DONE","OUT",ProjectStatus.COMPLETED,8,provenance="done"));assertEquals(ProjectStatus.COMPLETED,s.currentStatus("OUT"));assertEquals(1L,n(d,"SELECT COUNT(*) FROM item_instances WHERE item_instance_uid='ITEM-1'"));checks(d)
    }}

    @Test fun noLegacySynthesisStatePatchBlockedScaleReopenAndCampaignIsolation(){db().use{d->
        d.execSQL("CREATE TABLE legacy_projects(entity_uid TEXT,title TEXT,progress INTEGER)");d.execSQL("INSERT INTO legacy_projects VALUES('A','Old research',90)");val s=setup(d);assertEquals(0L,n(d,"SELECT COUNT(*) FROM development_projects"));val blocked=listOf("project_type_definitions","development_projects","project_status_history","project_requirements","project_requirement_satisfactions","project_milestone_definitions","project_milestone_achievements","project_work_records","project_dependencies","project_outcomes");blocked.forEach{assertFalse(SourceOfTruthRegistry(d).canWrite(it))}
        val p=project("LONG",cap=2000);s.createProject(p,"L-I");s.changeStatus(ProjectStatusEvent("C","L-R","LONG",ProjectStatus.REQUIREMENTS,2,provenance="r"));s.changeStatus(ProjectStatusEvent("C","L-P","LONG",ProjectStatus.PROTOTYPE,3,provenance="p"));s.changeStatus(ProjectStatusEvent("C","L-A","LONG",ProjectStatus.ACTIVE_WORK,4,provenance="a"));for(i in 0..1000)s.recordWork(ProjectWorkRecord("C","LW-$i","LONG","TRAINING",p("A"),(i+5).toLong(),ProjectWorkResult.PARTIAL,1,provenance="work-$i"));assertEquals(1001L,s.historyCount("LONG"));assertEquals(1001L,s.progress("LONG").progressUnits);checks(d)
        val sd=setup(d,"D");sd.createProject(project("LONG","D",cap=10),"D-I");assertEquals(1L,n(d,"SELECT COUNT(*) FROM development_projects WHERE campaign_id='D' AND project_uid='LONG'"));assertEquals(1L,n(d,"SELECT COUNT(*) FROM development_projects WHERE campaign_id='C' AND project_uid='LONG'"));checks(d)
    }}
        SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->CurrentSchema.ensure(d,"C");val s=DevelopmentProjectStore(d,"C");assertEquals(1001L,s.historyCount("LONG"));assertEquals(1001L,s.progress("LONG").progressUnits);checks(d)}
    }

    private fun n(d:SQLiteDatabase,sql:String)=d.rawQuery(sql,null).use{c->c.moveToFirst();c.getLong(0)}
    private fun checks(d:SQLiteDatabase){d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))};listOf("development_projects","project_status_history","project_requirements","project_requirement_satisfactions","project_milestone_definitions","project_milestone_achievements","project_work_records","project_dependencies","project_outcomes").forEach{t->d.rawQuery("PRAGMA foreign_key_check($t)",null).use{c->assertFalse("Phase15 FK violation in $t",c.moveToFirst())}}}
}
