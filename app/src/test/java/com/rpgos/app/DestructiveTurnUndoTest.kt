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
class DestructiveTurnUndoTest{
    private lateinit var root:File
    private lateinit var dbFile:File
    private lateinit var snapshots:File
    @Before fun setUp(){root=kotlin.io.path.createTempDirectory("undo-").toFile();dbFile=File(root,"campaign.db");snapshots=File(root,"snapshots")}
    @After fun tearDown(){root.deleteRecursively()}

    @Test fun replayAuthorityGateHasNoUnclassifiedOrFailClosedFamily(){
        CampaignReplayAuthorityMatrix.validateComplete()
        val authoritative=RuntimeTruthLayerRegistry.families.filter{it.isAuthoritative}.map{it.uid}.toSet()
        assertTrue(CampaignReplayAuthorityMatrix.nonReplayableFamilyUids.isEmpty())
        assertTrue(authoritative.all{CampaignReplayAuthorityMatrix.coverage(it)!=ReplayAuthorityCoverage.NON_REPLAYABLE_FAIL_CLOSED})
        assertTrue(setOf(
            "BASE_STATS_RESOURCES","SKILLS_TECHNIQUES","INVENTORY","EQUIPMENT_LOADOUT",
            "FINANCE_AUTHORITY","ASSET_LIABILITY_AUTHORITY","OWNERSHIP_REFERENCE_STATE","OWNERSHIP_HISTORY",
            "CAMPAIGN_TRUTH","CANON_DIVERGENCE","MECHANICAL_ACTOR_AND_AGGREGATE_STATE",
            "DEVELOPMENT_PROJECTS","NPC_KNOWLEDGE_STATE","ACCESS_AUTHORITY"
        ).all{CampaignReplayAuthorityMatrix.coverage(it)==ReplayAuthorityCoverage.REPLAYABLE})
        assertEquals(setOf(
            "ACTIVE_PLAYER_IDENTITY","PROGRESSION_PROFILES","INNATE_EVOLUTION","MODIFIER_INPUTS",
            "CURRENT_WORLD_AUTHORITY","NARRATIVE_PLANNING_STATE","TEMPORAL_SCHEDULE_STATE"
        ),CampaignReplayAuthorityMatrix.baselineDigestGuardedFamilyUids)
    }

    @Test fun confirmedUndoRebuildsPreviousPrefixAndPreservesManualBackup(){
        var db=SQLiteDatabase.openOrCreateDatabase(dbFile,null)
        GroupATransactionTestFixtures.setupFinance(db,openingBalance=100)
        val baseline=CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
        commit(db,"A",5);commit(db,"B",6)
        val manual=CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.MANUAL_BACKUP,true)
        val oldGeneration=HistoryGenerationStore(db,"C1").current()
        val coordinator=DestructiveTurnUndoCoordinator(db,"C1",snapshots,dbFile)
        val preview=coordinator.previewLastTurn()
        assertTrue(preview.canConfirm);assertEquals(2L,preview.currentCommitOrder);assertEquals(1L,preview.targetCommitOrder)
        val result=coordinator.confirm(preview)
        assertTrue(result is DestructiveUndoResult.Completed)
        assertFalse(db.isOpen)

        db=SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
        db.use{active->
            GameplayRuntimeBootstrap.requireReady(active,"C1")
            assertEquals(95L,FinancialStore(active,"C1").balance("A"))
            assertEquals(1L,count(active,"turn_transaction_receipts"))
            assertEquals(1L,count(active,"canonical_turn_replay_payloads"))
            assertEquals(1L,count(active,"canonical_gameplay_events"))
            assertNotEquals(oldGeneration,HistoryGenerationStore(active,"C1").current())
            val retained=CampaignSnapshotManager(active,"C1",snapshots).list()
            assertTrue(retained.any{it.snapshotUid==baseline.snapshotUid})
            assertTrue(retained.any{it.snapshotUid==manual.snapshotUid})
            assertTrue(File(manual.payloadPath).isFile)
        }
    }

    @Test fun previewBecomesStaleWhenAnotherTurnCommits(){
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->
            GroupATransactionTestFixtures.setupFinance(db,openingBalance=100)
            CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
            commit(db,"A",5)
            val coordinator=DestructiveTurnUndoCoordinator(db,"C1",snapshots,dbFile)
            val preview=coordinator.previewLastTurn()
            commit(db,"B",6)
            val result=coordinator.confirm(preview)
            assertEquals(UndoAvailabilityReason.STALE_PREVIEW,(result as DestructiveUndoResult.Rejected).reason)
            assertTrue(db.isOpen);assertEquals(89L,FinancialStore(db,"C1").balance("A"))
        }
    }

    @Test fun legacyHistoryWithoutVerifiedBaselineIsRejectedWithoutMutation(){
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->
            GroupATransactionTestFixtures.setupFinance(db,openingBalance=100)
            commit(db,"A",5)
            val before=AuthoritativeStateDigest.compute(db)
            val preview=DestructiveTurnUndoCoordinator(db,"C1",snapshots,dbFile).previewLastTurn()
            assertFalse(preview.canConfirm)
            assertEquals(UndoAvailabilityReason.NO_VERIFIED_BASELINE,preview.availability)
            assertEquals(before,AuthoritativeStateDigest.compute(db))
        }
    }

    @Test fun upgradeBoundaryBaselineAllowsUndoOfFirstNewTurnWithoutReplayingEarlierHistory(){
        var db=SQLiteDatabase.openOrCreateDatabase(dbFile,null)
        GroupATransactionTestFixtures.setupFinance(db,openingBalance=100)
        commit(db,"LEGACY-PREFIX",5)
        val baseline=CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.UNDO_BASELINE,true)
        assertEquals(1L,baseline.anchorCommitOrder)
        commit(db,"FIRST-POST-UPGRADE",6)

        val coordinator=DestructiveTurnUndoCoordinator(db,"C1",snapshots,dbFile)
        val result=coordinator.confirm(coordinator.previewLastTurn())
        assertTrue(result is DestructiveUndoResult.Completed)
        assertFalse(db.isOpen)

        db=SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
        db.use{active->
            assertEquals(95L,FinancialStore(active,"C1").balance("A"))
            assertEquals(1L,count(active,"turn_transaction_receipts"))
            assertEquals(1L,count(active,"canonical_turn_replay_payloads"))
        }
    }

    @Test fun everyInjectedActivationFailureLeavesTheOriginalHistoryAndManualBackupIntact(){
        UndoFailurePoint.entries.forEach(this::assertUndoFailureInjectionKeepsCanonicalStateIntact)
    }

    @Test fun abruptProcessLossBeforeOrAfterAtomicActivationRecoversOnReopenWithoutLosingManualBackup(){
        listOf(UndoFailurePoint.AFTER_ACTIVE_RENAME,UndoFailurePoint.AFTER_STAGING_ACTIVATION).forEach{point->
            val caseRoot=File(root,"abrupt-${point.name}").apply{mkdirs()}
            val caseDbFile=File(caseRoot,"campaign.db")
            val caseSnapshots=File(caseRoot,"snapshots")
            var db=SQLiteDatabase.openOrCreateDatabase(caseDbFile,null)
            try{
                GroupATransactionTestFixtures.setupFinance(db,openingBalance=100)
                val manager=CampaignSnapshotManager(db,"C1",caseSnapshots)
                manager.create(SnapshotKind.UNDO_BASELINE,true)
                commit(db,"ABRUPT-A-${point.name}",5)
                commit(db,"ABRUPT-B-${point.name}",6)
                val manual=manager.create(SnapshotKind.MANUAL_BACKUP,true)
                val manualSize=File(manual.payloadPath).length()
                val staged=manager.reconstructToVerifiedStagingAt(1L)

                try{
                    manager.activateVerifiedUndoStaging(
                        caseDbFile,staged,UndoFailureInjector{actual->
                            if(actual==point)throw AbruptUndoActivationInterruption()
                        }
                    )
                    fail("Expected abrupt process boundary at $point")
                }catch(expected:AbruptUndoActivationInterruption){
                    // Deliberately bypass the in-process rollback catch. Durable recovery must own
                    // the next decision exactly as after process death or power loss.
                }
                assertFalse(db.isOpen)
                assertTrue("atomic activation must never leave the live path missing",caseDbFile.isFile)
                assertTrue(File(caseRoot,".${caseDbFile.name}.undo-activation-v1.json").isFile)

                db=SQLiteDatabase.openDatabase(caseDbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)
                val recoveredManager=CampaignSnapshotManager(db,"C1",caseSnapshots)
                val expectedOrder=if(point==UndoFailurePoint.AFTER_ACTIVE_RENAME)2L else 1L
                assertEquals(expectedOrder,TurnTransactionReceiptStore(db).lastValidCommit("C1")?.commitOrder)
                assertTrue(db.isDatabaseIntegrityOk)
                assertTrue(recoveredManager.list().any{it.snapshotUid==manual.snapshotUid})
                assertTrue(File(manual.payloadPath).isFile)
                assertEquals(manualSize,File(manual.payloadPath).length())
                assertFalse(File(caseRoot,".${caseDbFile.name}.undo-activation-v1.json").exists())
                assertTrue(caseRoot.listFiles().orEmpty().none{it.name.startsWith(".${caseDbFile.name}.before_undo_activation-")})
                assertTrue(caseSnapshots.listFiles().orEmpty().none{it.name.startsWith(".u-")})
            }finally{if(db.isOpen)db.close()}
        }
    }

    @Test fun undoBaselinePruningKeepsTwoRequiredPrefixesAndNeverDeletesManualSnapshots(){
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use{db->
            GroupATransactionTestFixtures.setupFinance(db,openingBalance=100)
            val manager=CampaignSnapshotManager(db,"C1",snapshots)
            manager.create(SnapshotKind.UNDO_BASELINE,true)
            commit(db,"A",1);manager.create(SnapshotKind.UNDO_BASELINE,true)
            val manual=manager.create(SnapshotKind.MANUAL_BACKUP,true)
            commit(db,"B",1);manager.create(SnapshotKind.UNDO_BASELINE,true)

            manager.pruneUndoBaselines()

            assertEquals(2,manager.list().count{it.kind==SnapshotKind.UNDO_BASELINE&&it.state==SnapshotPublicationState.VALID})
            assertTrue(manager.list().any{it.snapshotUid==manual.snapshotUid})
            assertTrue(File(manual.payloadPath).isFile)
        }
    }

    private fun commit(db:SQLiteDatabase,suffix:String,amount:Long){
        val command="CMD-$suffix"
        TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN-$suffix",command,"TX-$suffix"),
            GroupATransactionTestFixtures.admittedFinancialProposal(commandUid=command,amountMinor=amount)).commit()
    }
    private fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getLong(0)}

    private fun assertUndoFailureInjectionKeepsCanonicalStateIntact(point:UndoFailurePoint){
        val caseRoot=File(root,point.name).apply{mkdirs()}
        val caseDbFile=File(caseRoot,"campaign.db")
        val caseSnapshots=File(caseRoot,"snapshots")
        var db=SQLiteDatabase.openOrCreateDatabase(caseDbFile,null)
        try{
            GroupATransactionTestFixtures.setupFinance(db,openingBalance=100)
            seedUndoDerivedArtifacts(db,"C1")
            CampaignSnapshotManager(db,"C1",caseSnapshots).create(SnapshotKind.UNDO_BASELINE,true)
            commit(db,"A",5)
            commit(db,"B",6)
            val manual=CampaignSnapshotManager(db,"C1",caseSnapshots).create(SnapshotKind.MANUAL_BACKUP,true)

            val beforeDigest=AuthoritativeStateDigest.compute(db)
            val beforeFileState=captureUndoFailureFiles(caseDbFile,caseSnapshots)
            assertTrue("$point must start with empty staging",beforeFileState.stagingFiles.isEmpty())
            assertTrue("$point must start with no rollback files",beforeFileState.rollbackFiles.isEmpty())
            assertWalShmPair(beforeFileState,"$point before")
            val beforeMemoryRows=memoryArtifactRowCounts(db,"C1")
            val beforeReceiptCount=count(db,"turn_transaction_receipts")
            val beforeReplayCount=count(db,"canonical_turn_replay_payloads")
            val beforeSnapshots=listSnapshotState(db,"C1",caseSnapshots)
            val beforeManualBackupSize=File(manual.payloadPath).length()
            val beforeDbLength=caseDbFile.length()
            val coordinator=DestructiveTurnUndoCoordinator(
                db,"C1",caseSnapshots,caseDbFile,
                failureInjector=UndoFailureInjector{actual->if(actual==point)error("INJECTED:${point.name}")}
            )
            val result=coordinator.confirm(coordinator.previewLastTurn())

            assertTrue("$point must reject",result is DestructiveUndoResult.Rejected)
            val expectDbOpenAfterFailure=when(point){
                UndoFailurePoint.BEFORE_STAGING,UndoFailurePoint.AFTER_STAGING_VERIFIED,UndoFailurePoint.BEFORE_ACTIVE_RENAME->true
                else->false
            }
            assertEquals("$point changed active db state unexpectedly",expectDbOpenAfterFailure,db.isOpen)
            if(!db.isOpen)db=SQLiteDatabase.openDatabase(caseDbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE)

            val afterDigest=AuthoritativeStateDigest.compute(db)
            val afterFileState=captureUndoFailureFiles(caseDbFile,caseSnapshots)
            assertWalShmPair(afterFileState,"$point after")
            val afterMemoryRows=memoryArtifactRowCounts(db,"C1")
            val afterReceiptCount=count(db,"turn_transaction_receipts")
            val afterReplayCount=count(db,"canonical_turn_replay_payloads")
            val afterSnapshots=listSnapshotState(db,"C1",caseSnapshots)
            val afterManualBackupSize=File(manual.payloadPath).length()
            val afterDbLength=caseDbFile.length()

            assertEquals("$point changed canonical digest",beforeDigest,afterDigest)
            assertEquals("$point changed snapshot catalog",beforeSnapshots,afterSnapshots)
            assertEquals("$point changed receipt count",beforeReceiptCount,afterReceiptCount)
            assertEquals("$point changed replay payload count",beforeReplayCount,afterReplayCount)
            assertEquals("$point changed memory rows",beforeMemoryRows,afterMemoryRows)
            when(point){
                UndoFailurePoint.BEFORE_STAGING,
                UndoFailurePoint.AFTER_STAGING_VERIFIED -> {
                    assertEquals("$point changed WAL visibility unexpectedly",beforeFileState.walExists,afterFileState.walExists)
                    assertEquals("$point changed SHM visibility unexpectedly",beforeFileState.shmExists,afterFileState.shmExists)
                }
                else -> {}
            }
            assertEquals("$point changed staging set unexpectedly",emptySet<String>(),afterFileState.stagingFiles)
            assertTrue("$point must never leave rollback files behind",afterFileState.rollbackFiles.isEmpty())
            assertEquals("$point changed DB file length",beforeDbLength,afterDbLength)
            assertTrue("$point removed manual backup file",File(manual.payloadPath).isFile)
            assertEquals("$point changed manual backup payload bytes",beforeManualBackupSize,afterManualBackupSize)
        }finally{if(db.isOpen)db.close()}
    }

    private fun seedUndoDerivedArtifacts(db:SQLiteDatabase,campaignUid:String){
        Phase55To58MemorySchema.ensureReady(db,campaignUid)
        val generation=HistoryGenerationStore(db,campaignUid).current()
        val leaf = MemorySourceLeafRef(
            sourceKind = "WORLD_EVENT",
            sourceUid = "seed-${campaignUid.length}",
            sourceVersion = campaignUid.length.toLong(),
            committedOrder = 1L,
            fingerprint = "seed-fingerprint"
        )
        val identity=MemoryArtifactIdentity(
            campaignUid = campaignUid,
            historyGenerationUid = generation,
            logicalArtifactUid = "UNDO-GATE-ARTIFACT",
            artifactRevisionUid = "UNDO-GATE-REV-1",
            artifactKind = MemoryArtifactKind.EPISODE_MANIFEST,
            sourceLeafRefs = listOf(leaf),
            sourceLeafSetFingerprint = memoryLeafFingerprint(listOf(leaf)),
            derivationRuleUid = "UNDO-GATE-RULE",
            derivationVersion = 1,
            asOfCommittedOrder = 1L,
            createdFromOrder = 1L,
            createdThroughOrder = 1L
        )
        MemoryArtifactStore(db).upsert(identity,MemoryArtifactStatus.DIRTY,"{\"scope\":\"undo-gate\"}")
        assertEquals(1L, count(db, Phase55To58MemorySchema.ARTIFACTS))
    }

    private fun memoryArtifactRowCounts(db:SQLiteDatabase,campaignUid:String)=mapOf(
        Phase55To58MemorySchema.GENERATIONS to count(db,Phase55To58MemorySchema.GENERATIONS),
        Phase55To58MemorySchema.ARTIFACTS to count(db,Phase55To58MemorySchema.ARTIFACTS),
        Phase55To58MemorySchema.LEAVES to count(db,Phase55To58MemorySchema.LEAVES),
        Phase55To58MemorySchema.DEPENDENCIES to count(db,Phase55To58MemorySchema.DEPENDENCIES),
        Phase55To58MemorySchema.EPISODE_MEMBERSHIP to count(db,Phase55To58MemorySchema.EPISODE_MEMBERSHIP),
        Phase55To58MemorySchema.RECEIPTS to count(db,Phase55To58MemorySchema.RECEIPTS),
        Phase55To58MemorySchema.STATE to count(db,Phase55To58MemorySchema.STATE)
    ).filterKeys{tableExists(db,it)}

    private fun listSnapshotState(db:SQLiteDatabase,campaignUid:String,snapshotDir:File)=CampaignSnapshotManager(db,campaignUid,snapshotDir).list().map{
        "${it.snapshotUid}:${it.kind.name}:${it.state.name}:${it.anchorCommitOrder}:${it.payloadPath}"
    }.sorted().toSet()

    private fun captureUndoFailureFiles(activeDbFile:File,snapshotDir:File):UndoFailureFileState{
        val parent=requireNotNull(activeDbFile.parentFile)
        return UndoFailureFileState(
            walExists=File(activeDbFile.absolutePath + "-wal").isFile,
            shmExists=File(activeDbFile.absolutePath + "-shm").isFile,
            stagingFiles=snapshotDir.listFiles()
                ?.filter{it.isFile && it.name.startsWith(".u-")}
                ?.map{it.name}
                ?.toSet()
                ?: emptySet(),
            rollbackFiles=parent.listFiles()
                ?.filter{it.isFile && it.name.startsWith(".${activeDbFile.name}.before_undo_activation-")}
                ?.map{it.name}
                ?.toSet()
                ?: emptySet()
        )
    }

    private fun assertWalShmPair(state:UndoFailureFileState,context:String){
        assertEquals("$context must not leave orphaned WAL/SHM pair",state.walExists,state.shmExists)
    }

    private fun tableExists(db:SQLiteDatabase,table:String)=db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)
    ).use{it.moveToFirst()}

    private data class UndoFailureFileState(
        val walExists:Boolean,
        val shmExists:Boolean,
        val stagingFiles:Set<String>,
        val rollbackFiles:Set<String>
    )
}
