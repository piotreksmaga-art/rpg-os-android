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
class Phase33SnapshotSystemTest {
    private lateinit var root:File;private lateinit var dbFile:File;private lateinit var snapshots:File
    @Before fun setUp(){root=kotlin.io.path.createTempDirectory("p33-").toFile();dbFile=File(root,"campaign.db");snapshots=File(root,"snapshots")}
    @After fun tearDown(){root.deleteRecursively()}

    @Test fun snapshotThenCanonicalCommitReconstructsCompleteAuthorityAndEvidence(){
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->
            GroupATransactionTestFixtures.setupFinance(db,openingBalance=200)
            commit(db,"A",5)
            CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.AUTOMATIC)
            commit(db,"B",7)
            val authority=AuthoritativeStateDigest.compute(db)
            val receipts=TableDigest.compute(db,"turn_transaction_receipts")
            val staged=CampaignSnapshotManager(db,"C1",snapshots).reconstructToVerifiedStaging()
            SQLiteDatabase.openDatabase(staged.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{restored->
                assertEquals(authority,AuthoritativeStateDigest.compute(restored));assertEquals(receipts,TableDigest.compute(restored,"turn_transaction_receipts"))
                assertEquals(188L,FinancialStore(restored,"C1").balance("A"));assertEquals(2L,count(restored,"canonical_turn_replay_payloads"))
            }
        }
    }

    @Test fun rollbackCreatesNoReplayMaterial(){SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->GroupATransactionTestFixtures.setupFinance(db);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="BAD");assertTrue(runCatching{TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","T-BAD","BAD","TX-BAD"),p,TurnFailureInjector{if(it==TurnFailurePoint.AFTER_EVENT_APPEND)error("crash")}).commit()}.isFailure);assertEquals(0L,count(db,"canonical_turn_replay_payloads"));assertEquals(0L,count(db,"turn_transaction_receipts"))}}

    @Test fun legacyV1NullOrderBaselineDoesNotFabricateHistoryAndFutureTurnsAreReplayable(){
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->
            MigrationManager().ensureV1(db)
            db.execSQL("""CREATE TABLE turn_transaction_receipts(
                transaction_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,turn_uid TEXT NOT NULL,command_uid TEXT NOT NULL,
                semantic_fingerprint TEXT NOT NULL,result_fingerprint TEXT NOT NULL,
                receipt_version INTEGER NOT NULL CHECK(receipt_version=1),commit_state TEXT NOT NULL CHECK(commit_state='COMMITTED'),
                UNIQUE(campaign_uid,command_uid))""")
            db.execSQL("INSERT INTO turn_transaction_receipts VALUES(?,?,?,?,?,?,1,'COMMITTED')",
                arrayOf("OLD-X","C1","OLD-T","OLD-C","legacy-semantic","legacy-result"))
            GameplayRuntimeBootstrap.initialize(db,"C1")
            val legacy=TurnTransactionReceiptStore(db).committedTransaction("OLD-X")!!
            assertNull(legacy.commitOrder)
            assertNull(legacy.requiredEventCount)
            assertNull(legacy.requiredEventManifestFingerprint)
            assertEquals(0L,countWhere(db,"canonical_turn_replay_payloads","transaction_uid='OLD-X'"))
            assertEquals(0L,countWhere(db,"canonical_gameplay_events","transaction_uid='OLD-X'"))
            assertEquals(0L,countWhere(db,"canonical_causal_relations","transaction_uid='OLD-X'"))
            val baseline=CampaignSnapshotManager(db,"C1",snapshots).create()
            assertEquals(0L,baseline.anchorCommitOrder)
            assertNull(baseline.anchorTransactionUid)

            GroupATransactionTestFixtures.setupFinance(db)
            commit(db,"FUTURE",5)
            assertNull(TurnTransactionReceiptStore(db).committedTransaction("OLD-X")!!.commitOrder)
            assertEquals(1L,TurnTransactionReceiptStore(db).committedTransaction("TX-FUTURE")!!.commitOrder)
            assertEquals(1L,countWhere(db,"canonical_turn_replay_payloads","transaction_uid='TX-FUTURE'"))
        }
    }

    @Test fun publishedAnchorComesFromCapturedDatabaseBoundary(){SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->
        GroupATransactionTestFixtures.setupFinance(db);commit(db,"ANCHOR",5)
        val descriptor=CampaignSnapshotManager(db,"C1",snapshots).create()
        SQLiteDatabase.openDatabase(descriptor.payloadPath,null,SQLiteDatabase.OPEN_READONLY).use{captured->
            val receipt=TurnTransactionReceiptStore(captured).lastValidCommit("C1")!!
            val event=CampaignEventStore(captured,"C1").eventsForTransaction(receipt.transactionUid).last()
            assertEquals(receipt.commitOrder,descriptor.anchorCommitOrder)
            assertEquals(receipt.transactionUid,descriptor.anchorTransactionUid)
            assertEquals(receipt.turnUid,descriptor.anchorTurnUid)
            assertEquals(event.eventUid,descriptor.anchorEventUid)
        }
    }}

    @Test fun corruptNewestFallsBackToEarlierValid(){SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->GroupATransactionTestFixtures.setupFinance(db);val m=CampaignSnapshotManager(db,"C1",snapshots);val old=m.create();val newest=m.create();File(newest.payloadPath).writeText("corrupt");assertEquals(old.snapshotUid,m.latestValidCompatible()!!.snapshotUid)}}

    @Test fun deletingLatestFallsBackAndDeletingAllNeverDeletesCommitHistory(){SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->GroupATransactionTestFixtures.setupFinance(db,openingBalance=200);commit(db,"A",5);val m=CampaignSnapshotManager(db,"C1",snapshots);val first=m.create();commit(db,"B",7);val latest=m.create();commit(db,"C",9);assertTrue(m.delete(latest.snapshotUid));val staged=m.reconstructToVerifiedStaging();SQLiteDatabase.openDatabase(staged.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use{assertEquals(179L,FinancialStore(it,"C1").balance("A"))};val events=count(db,"canonical_gameplay_events");m.list().forEach{m.delete(it.snapshotUid)};assertEquals(events,count(db,"canonical_gameplay_events"));assertEquals(3L,count(db,"turn_transaction_receipts"));assertFalse(File(first.payloadPath).exists())}}

    @Test fun failedCapturePublishesNoValidSnapshotAndReadyReadsAreMutationFree(){SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->GroupATransactionTestFixtures.setupFinance(db);val impossible=File(root,"not-a-directory").apply{writeText("x")};val m=CampaignSnapshotManager(db,"C1",impossible);assertTrue(runCatching{m.create()}.isFailure);assertTrue(m.list().none{it.state==SnapshotPublicationState.VALID});val before=objects(db);m.list();m.latestValidCompatible();assertEquals(before,objects(db))}}

    @Test fun gameplayCannotInvokeRecoverySnapshotCapability(){SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->GroupATransactionTestFixtures.setupFinance(db);val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="DENY");val failure=runCatching{TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","T-DENY","DENY","TX-DENY"),p,TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)CampaignSnapshotManager(db,"C1",snapshots).create()}).commit()}.exceptionOrNull();assertTrue(failure!!.message.orEmpty().contains("GAMEPLAY_CANNOT_INVOKE_ADMIN_AUTHORITY"));assertEquals(0L,count(db,"turn_transaction_receipts"));assertTrue(CampaignSnapshotManager(db,"C1",snapshots).list().isEmpty())}}

    @Test fun verifiedStagingActivationSurvivesCloseAndLoad(){var db=SQLiteDatabase.openOrCreateDatabase(dbFile,null);GroupATransactionTestFixtures.setupFinance(db,openingBalance=100);commit(db,"A",5);val m=CampaignSnapshotManager(db,"C1",snapshots);m.create();commit(db,"B",6);val staged=m.reconstructToVerifiedStaging();m.activateVerifiedStaging(dbFile,staged);db=SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE);db.use{GameplayRuntimeBootstrap.requireReady(it,"C1");assertEquals(89L,FinancialStore(it,"C1").balance("A"));assertEquals(2L,count(it,"turn_transaction_receipts"));assertEquals(1,CampaignSnapshotManager(it,"C1",snapshots).list().size)}}

    @Test fun verifiedStagingCannotActivateAfterNewerTurnCommits(){
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->
            GroupATransactionTestFixtures.setupFinance(db,openingBalance=100)
            commit(db,"A",5)
            val m=CampaignSnapshotManager(db,"C1",snapshots)
            m.create()
            commit(db,"B",6)
            val staged=m.reconstructToVerifiedStaging()
            commit(db,"C",7)
            val failure=runCatching{m.activateVerifiedStaging(dbFile,staged)}.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("STALE_VERIFIED_STAGING"))
            assertTrue(db.isOpen)
            assertEquals(82L,FinancialStore(db,"C1").balance("A"))
            assertEquals(3L,count(db,"turn_transaction_receipts"))
        }
    }

    private fun commit(db:SQLiteDatabase,s:String,amount:Long){val c="CMD-$s";TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN-$s",c,"TX-$s"),GroupATransactionTestFixtures.admittedFinancialProposal(commandUid=c,amountMinor=amount)).commit()}
    private fun count(db:SQLiteDatabase,t:String)=db.rawQuery("SELECT COUNT(*) FROM $t",null).use{it.moveToFirst();it.getLong(0)}
    private fun countWhere(db:SQLiteDatabase,t:String,w:String)=db.rawQuery("SELECT COUNT(*) FROM $t WHERE $w",null).use{it.moveToFirst();it.getLong(0)}
    private fun objects(db:SQLiteDatabase)=db.rawQuery("SELECT type,name,sql FROM sqlite_master ORDER BY type,name",null).use{c->buildList{while(c.moveToNext())add(listOf(c.getString(0),c.getString(1),c.getString(2)))}}
}
