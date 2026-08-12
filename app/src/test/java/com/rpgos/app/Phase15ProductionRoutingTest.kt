package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase15ProductionRoutingTest {
    private lateinit var context:Context;private lateinit var root:File
    @Before fun setUp(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root=File(context.filesDir,"rpgos");root.deleteRecursively();root.mkdirs()}
    @After fun tearDown(){context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root.deleteRecursively()}

    @Test fun bootstrapRoutesBundledCampaignThroughV15(){LocalGameStore(context).bootstrap();assertV15(File(root,"saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}/campaign.db"))}
    @Test fun campaignSwitchRoutesV14ThroughV15(){create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val other=create("Other.campaign","other");LocalGameStore(context).setActiveCampaign("Other.campaign");assertV15(other)}
    @Test fun restoreRoutesV14ThroughV15WithoutLegacyProjectSynthesis(){val active=create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);val backup=File(active.parentFile,"backups/v14.db").apply{parentFile?.mkdirs()};v14(backup,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);SQLiteDatabase.openDatabase(backup.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->d.execSQL("CREATE TABLE legacy_projects(entity_uid TEXT,title TEXT,progress INTEGER)");d.execSQL("INSERT INTO legacy_projects VALUES('P','Legacy Research',77)")};val safety=LocalGameStore(context).restoreBackup(backup.absolutePath);assertTrue(File(safety).isFile);assertV15(active);SQLiteDatabase.openDatabase(active.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->assertEquals(0L,n(d,"SELECT COUNT(*) FROM development_projects"));assertEquals(77L,n(d,"SELECT progress FROM legacy_projects WHERE entity_uid='P'"));checks(d)}}
    @Test fun repeatedEnsureIsIdempotentAndKeepsProjectAuthorityEmpty(){val f=create(ActiveCampaignRef.DEFAULT_DIRECTORY,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->CurrentSchema.ensure(d,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);CurrentSchema.ensure(d,ActiveCampaignRef.DEFAULT_CAMPAIGN_ID);assertEquals(1L,n(d,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE15_MIGRATION_ID'"));assertEquals(0L,n(d,"SELECT COUNT(*) FROM development_projects"));checks(d)}}

    private fun create(dir:String,id:String):File{val d=File(root,"saves/$dir").apply{mkdirs()};File(d,"campaign.json").writeText("{\"id\":\"$id\"}");return File(d,"campaign.db").also{v14(it,id)}}
    private fun v14(f:File,id:String){f.parentFile?.mkdirs();SQLiteDatabase.openOrCreateDatabase(f,null).use{MigrationManager().ensureV14Hardening(it,id)}}
    private fun assertV15(f:File){SQLiteDatabase.openDatabase(f.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{d->assertEquals(1L,n(d,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='$PHASE15_MIGRATION_ID'"));assertEquals(10L,n(d,"SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('project_type_definitions','development_projects','project_status_history','project_requirements','project_requirement_satisfactions','project_milestone_definitions','project_milestone_achievements','project_work_records','project_dependencies','project_outcomes')"));assertTrue(n(d,"SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'trg_p15_%'")>=20);checks(d)}}
    private fun n(d:SQLiteDatabase,sql:String)=d.rawQuery(sql,null).use{c->c.moveToFirst();c.getLong(0)}
    private fun checks(d:SQLiteDatabase){d.rawQuery("PRAGMA integrity_check",null).use{c->c.moveToFirst();assertEquals("ok",c.getString(0))};listOf("development_projects","project_status_history","project_requirements","project_requirement_satisfactions","project_milestone_definitions","project_milestone_achievements","project_work_records","project_dependencies","project_outcomes").forEach{t->d.rawQuery("PRAGMA foreign_key_check($t)",null).use{c->assertFalse("Phase15 FK violation in $t",c.moveToFirst())}}}
}
