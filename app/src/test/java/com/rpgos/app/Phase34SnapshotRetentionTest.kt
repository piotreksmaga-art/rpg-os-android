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

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class Phase34SnapshotRetentionTest {
 private lateinit var root:File;private lateinit var dbFile:File
 @Before fun setup(){root=kotlin.io.path.createTempDirectory("p34-").toFile();dbFile=File(root,"campaign.db")}
 @After fun down(){root.deleteRecursively()}
 @Test fun onlyNewestSixUnpinnedAutomaticSnapshotsArePruned(){SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{d->GroupATransactionTestFixtures.setupFinance(d);val m=CampaignSnapshotManager(d,"C1",File(root,"snapshots"));val manual=m.create(SnapshotKind.MANUAL_BACKUP);val export=m.create(SnapshotKind.MANUAL_EXPORT);val safety=m.create(SnapshotKind.PRE_RESTORE);val pinned=m.create(SnapshotKind.USER_PINNED);val legacy=m.create(SnapshotKind.LEGACY_BACKUP);repeat(8){m.create(SnapshotKind.AUTOMATIC)};val all=m.list();assertEquals(6,all.count{it.kind==SnapshotKind.AUTOMATIC&&it.state==SnapshotPublicationState.VALID});listOf(manual,export,safety,pinned,legacy).forEach{assertTrue(m.list().any{s->s.snapshotUid==it.snapshotUid&&File(s.payloadPath).isFile})};assertEquals(0L,count(d,"canonical_gameplay_events"));assertEquals(0L,count(d,"canonical_causal_relations"));assertEquals(0L,count(d,"turn_transaction_receipts"))}}
 @Test fun filesystemMtimeDoesNotControlRetention(){SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{d->GroupATransactionTestFixtures.setupFinance(d);val m=CampaignSnapshotManager(d,"C1",File(root,"snapshots"));val first=m.create();repeat(6){m.create()};File(first.payloadPath).setLastModified(Long.MAX_VALUE);m.create();assertFalse(File(first.payloadPath).exists())}}
 @Test fun retentionIsCampaignScoped(){SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{d->GroupATransactionTestFixtures.setupFinance(d,"C1");GroupATransactionTestFixtures.setupFinance(d,"C2");val dir=File(root,"snapshots");val a=CampaignSnapshotManager(d,"C1",dir);val b=CampaignSnapshotManager(d,"C2",dir);repeat(7){a.create();b.create()};assertEquals(6,a.list().count{it.kind==SnapshotKind.AUTOMATIC&&it.state==SnapshotPublicationState.VALID});assertEquals(6,b.list().count{it.kind==SnapshotKind.AUTOMATIC&&it.state==SnapshotPublicationState.VALID})}}
 private fun count(d:SQLiteDatabase,t:String)=d.rawQuery("SELECT COUNT(*) FROM $t",null).use{it.moveToFirst();it.getLong(0)}
}
