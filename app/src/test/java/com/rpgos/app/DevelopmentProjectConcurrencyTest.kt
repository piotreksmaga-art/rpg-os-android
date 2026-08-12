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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DevelopmentProjectConcurrencyTest {
    private lateinit var f:File
    @Before fun setUp(){f=File.createTempFile("p15-race-",".db");f.delete();SQLiteDatabase.openOrCreateDatabase(f,null).use{d->CurrentSchema.ensure(d,"C");val r=OwnershipReferenceRegistry(d,"C");listOf("A","B").forEach{r.registerOwner(OwnershipOwnerRef("CHARACTER",it),"p15-race")}}}
    @After fun tearDown(){f.delete()}
    private fun owner()=OwnershipOwnerRef("CHARACTER","A")
    private fun project(uid:String,cap:Long?=100)=DevelopmentProject("C",uid,PROJECT_TYPE_RESEARCH,owner(),title=uid,objectiveSummary="race",targetDomainUid="RESEARCH",progressCapUnits=cap,createdOrder=1,provenance="race")
    private data class R(val ok:Int,val bad:Int,val errors:List<Throwable>)
    private fun race(a:(DevelopmentProjectStore)->Unit,b:(DevelopmentProjectStore)->Unit):R{
        val d1=SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE);val d2=SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE);val s1=DevelopmentProjectStore(d1,"C");val s2=DevelopmentProjectStore(d2,"C");val ready=CountDownLatch(2);val go=CountDownLatch(1);val ok=AtomicInteger();val bad=AtomicInteger();val errors=ConcurrentLinkedQueue<Throwable>();val pool=Executors.newFixedThreadPool(2)
        fun submit(s:DevelopmentProjectStore,op:(DevelopmentProjectStore)->Unit)=pool.submit{ready.countDown();go.await();try{op(s);ok.incrementAndGet()}catch(t:Throwable){errors.add(t);bad.incrementAndGet()}}
        val x=submit(s1,a);val y=submit(s2,b);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();x.get(15,TimeUnit.SECONDS);y.get(15,TimeUnit.SECONDS);pool.shutdownNow();d1.close();d2.close();return R(ok.get(),bad.get(),errors.toList())
    }
    private fun seedActive(uid:String="P",cap:Long?=100){SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->val s=DevelopmentProjectStore(d,"C");s.createProject(project(uid,cap),"$uid-I");s.changeStatus(ProjectStatusEvent("C","$uid-R",uid,ProjectStatus.REQUIREMENTS,2,provenance="r"));s.changeStatus(ProjectStatusEvent("C","$uid-P",uid,ProjectStatus.PROTOTYPE,3,provenance="p"));s.changeStatus(ProjectStatusEvent("C","$uid-A",uid,ProjectStatus.ACTIVE_WORK,4,provenance="a"))}}
    private fun check(block:(SQLiteDatabase)->Unit){SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use(block)}

    @Test fun p15Race01CompetingExactProjectCreationConverges(){val p=project("CREATE");val r=race({s->assertEquals(p,s.createProject(p,"CREATE-I"))},{s->assertEquals(p,s.createProject(p.copy(),"CREATE-I"))});assertEquals(2,r.ok);assertEquals(0,r.bad);check{d->assertEquals(1L,n(d,"SELECT COUNT(*) FROM development_projects WHERE project_uid='CREATE'"));assertEquals(1L,n(d,"SELECT COUNT(*) FROM project_status_history WHERE project_uid='CREATE'"));checks(d)}}

    @Test fun p15Race02CompetingProgressCannotExceedCap(){seedActive();val r=race({s->s.recordWork(ProjectWorkRecord("C","W-A","P","TEST",owner(),5,ProjectWorkResult.SUCCESS,60,provenance="a"))},{s->s.recordWork(ProjectWorkRecord("C","W-B","P","TEST",owner(),5,ProjectWorkResult.SUCCESS,60,provenance="b"))});assertEquals(1,r.ok);assertEquals(1,r.bad);check{d->assertEquals(60L,n(d,"SELECT COALESCE(SUM(progress_delta_units),0) FROM project_work_records WHERE project_uid='P'"));assertEquals(1L,n(d,"SELECT COUNT(*) FROM project_work_records WHERE project_uid='P'"));checks(d)}}

    @Test fun p15Race03ProgressVersusCancelHasOneCoherentOutcome(){seedActive("PC",100);val r=race({s->s.recordWork(ProjectWorkRecord("C","PC-W","PC","TEST",owner(),6,ProjectWorkResult.SUCCESS,10,provenance="work"))},{s->s.changeStatus(ProjectStatusEvent("C","PC-C","PC",ProjectStatus.CANCELLED,5,provenance="cancel"))});assertEquals(1,r.ok);assertEquals(1,r.bad);check{d->val work=n(d,"SELECT COUNT(*) FROM project_work_records WHERE project_uid='PC'");val cancelled=n(d,"SELECT COUNT(*) FROM project_status_history WHERE project_uid='PC' AND status='CANCELLED'");assertTrue((work==1L&&cancelled==0L)||(work==0L&&cancelled==1L));checks(d)}}

    @Test fun p15Race04DuplicateStableUidWorkExactReplayIsIdempotent(){seedActive("DUP",100);val w=ProjectWorkRecord("C","DUP-W","DUP","TEST",owner(),5,ProjectWorkResult.PARTIAL,10,commandUid="DUP-CMD",provenance="same");val r=race({s->assertEquals(w,s.recordWork(w))},{s->assertEquals(w,s.recordWork(w.copy()))});if(r.errors.isNotEmpty())throw AssertionError("exact replay worker failed",r.errors.first());assertEquals(2,r.ok);assertEquals(0,r.bad);check{d->assertEquals(1L,n(d,"SELECT COUNT(*) FROM project_work_records WHERE work_record_uid='DUP-W'"));assertEquals(10L,n(d,"SELECT SUM(progress_delta_units) FROM project_work_records WHERE project_uid='DUP'"));checks(d)}}

    @Test fun p15Race05ConflictingStableUidReplayRejectsOne(){seedActive("CON",100);val a=ProjectWorkRecord("C","SAME-W","CON","TEST",owner(),5,ProjectWorkResult.SUCCESS,10,provenance="same");val b=a.copy(progressDeltaUnits=20);val r=race({s->s.recordWork(a)},{s->s.recordWork(b)});assertEquals(1,r.ok);assertEquals(1,r.bad);check{d->assertEquals(1L,n(d,"SELECT COUNT(*) FROM project_work_records WHERE work_record_uid='SAME-W'"));val v=n(d,"SELECT progress_delta_units FROM project_work_records WHERE work_record_uid='SAME-W'");assertTrue(v==10L||v==20L);checks(d)}}

    @Test fun p15Race06CompetingLifecycleTransitionsCannotFork(){seedActive("LIFE",100);val r=race({s->s.changeStatus(ProjectStatusEvent("C","LIFE-S","LIFE",ProjectStatus.STABILIZATION,5,provenance="stabilize"))},{s->s.changeStatus(ProjectStatusEvent("C","LIFE-C","LIFE",ProjectStatus.CANCELLED,5,provenance="cancel"))});assertEquals(1,r.ok);assertEquals(1,r.bad);check{d->assertEquals(1L,n(d,"SELECT COUNT(*) FROM project_status_history WHERE project_uid='LIFE' AND effective_order=5"));checks(d)}}

    @Test fun dependencyCycleIsRejectedAtWriteBoundary(){SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->val s=DevelopmentProjectStore(d,"C");s.createProject(project("A"),"A-I");s.createProject(project("B"),"B-I");s.addDependency(ProjectDependency("C","D1","A","B","REQUIRES_COMPLETION",validFromOrder=2,provenance="dep"));var bad=false;try{s.addDependency(ProjectDependency("C","D2","B","A","REQUIRES_COMPLETION",validFromOrder=2,provenance="cycle"))}catch(_:Throwable){bad=true};assertTrue(bad);assertEquals(1L,n(d,"SELECT COUNT(*) FROM project_dependencies"));checks(d)}}

    private fun n(d:SQLiteDatabase,sql:String)=d.rawQuery(sql,null).use{c->c.moveToFirst();c.getLong(0)}
    private fun checks(d:SQLiteDatabase){d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))};d.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}}
}
