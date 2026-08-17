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
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase26To29PostAuditBlockerRepairTest {
    private lateinit var file: File

    @Before fun setUp(){ file=File.createTempFile("work023-",".db").also{it.delete()} }
    @After fun tearDown(){ file.delete() }

    @Test fun A01_inventory_direct_writer_fails_and_canonical_succeeds(){
        db().use{d->
            CurrentSchema.ensure(d,"C1")
            val store=InventoryStore(d,"C1")
            store.registerDefinitions("WP", listOf(ItemDefinition("DEF-I","WP","i","Item",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="SETUP")))
            store.createInstance(ItemInstance("C1","ITEM-I","DEF-I",provenance="SETUP"))
            arm(d)
            assertGate{store.addUnique("P1","ITEM-I","DIRECT")}
            val p=admitted("INV","CMD-A01")
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-A01"),p).commit() is TurnExecutionResult.Committed)
            assertEquals("ITEM-I",store.typedUnique("P1").single().first.itemInstanceUid)
        }
    }

    @Test fun A02_finance_direct_writer_fails_and_canonical_succeeds(){
        db().use{d->
            GroupATransactionTestFixtures.setupFinance(d)
            val store=FinancialStore(d,"C1")
            arm(d)
            assertGate{store.transfer("DIRECT-FIN","A","B",5,9,"direct","DIRECT")}
            val p=admitted("FIN5","CMD-A02")
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-A02"),p).commit() is TurnExecutionResult.Committed)
            assertEquals(95L,store.balance("A"));assertEquals(5L,store.balance("B"))
        }
    }

    @Test fun A03_ownership_direct_writer_fails_and_canonical_succeeds(){
        db().use{d->
            setupOwnership(d);val store=OwnershipStore(d,"C1");val from=owner("P1");val to=owner("P2");val asset=asset()
            arm(d)
            assertGate{store.fullTransfer("DIRECT-OWN",from,to,asset,"OWNER",9,null,"DIRECT")}
            val p=admitted("OWN","CMD-A03")
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-A03"),p).commit() is TurnExecutionResult.Committed)
            assertEquals(to,store.currentOwnership(asset).single().owner)
        }
    }

    @Test fun A04_campaign_truth_direct_writer_fails_and_canonical_succeeds(){
        db().use{d->
            CurrentSchema.ensure(d,"C1");val store=CampaignTruthStore(d,"C1");arm(d)
            assertGate{store.record(TruthKind.FACT,"direct",Provenance(ProvenanceSourceType.PLAYER_ACTION),truthUid="TRUTH-DIRECT")}
            val p=admitted("TRUTH","CMD-A04")
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-A04"),p).commit() is TurnExecutionResult.Committed)
            assertEquals("TRUTH-CANON",store.active().single().truthUid)
        }
    }

    @Test fun A05_stat_and_resource_direct_writers_fail_and_canonical_succeed(){
        db().use{d->
            setupStatResource(d);val store=StatResourceStore(d,"C1");arm(d)
            assertGate{store.savePlayerStat(PlayerStat("C1","P1","STR",99.0,2))}
            assertGate{store.savePlayerResource(PlayerResource("C1","P1","ENERGY",99.0,2))}
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-STAT"),admitted("STAT","CMD-STAT")).commit() is TurnExecutionResult.Committed)
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-RES"),admitted("RESOURCE","CMD-RES")).commit() is TurnExecutionResult.Committed)
            assertEquals(12.0,store.playerStats("P1").single{it.statUid=="STR"}.baseValue,0.0)
            assertEquals(17.0,store.playerResources("P1").single{it.resourceUid=="ENERGY"}.currentValue,0.0)
        }
    }

    @Test fun A06_skill_and_technique_direct_writers_fail_and_canonical_succeed(){
        db().use{d->
            setupSkillTechnique(d);val skills=SkillStore(d,"C1");val techniques=TechniqueStore(d,"C1");arm(d)
            assertGate{skills.savePlayerSkill(PlayerSkill("C1","P1","SK1",10.0,99.0,"UNITS",2,"DIRECT"))}
            assertGate{techniques.savePlayerTechnique(PlayerTechnique("C1","P1","TECH1",10.0,99.0,"UNITS",entryVersion=2,provenance="DIRECT"))}
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-SK"),admitted("SKILL","CMD-SK")).commit() is TurnExecutionResult.Committed)
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-TECH"),admitted("TECH","CMD-TECH")).commit() is TurnExecutionResult.Committed)
            assertEquals(4.0,skills.playerSkills("P1").single().progressValue!!,0.0)
            assertEquals(5.0,techniques.playerTechniques("P1").single().progressValue!!,0.0)
        }
    }

    @Test fun A07_equipment_direct_writer_fails_and_canonical_succeeds(){
        db().use{d->
            setupEquipment(d);val store=EquipmentStore(d,"C1");arm(d)
            assertGate{store.equip("P1","ITEM-EQ","RULE1",listOf("SLOT1"),"DIRECT-EQ","DIRECT")}
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-EQ"),admitted("EQUIP","CMD-EQ")).commit() is TurnExecutionResult.Committed)
            assertEquals("ITEM-EQ",store.equipment("P1").single().itemInstance.itemInstanceUid)
        }
    }

    @Test fun A08_project_direct_writer_fails_and_canonical_succeeds(){
        db().use{d->
            setupProject(d);val store=DevelopmentProjectStore(d,"C1");arm(d)
            assertGate{store.recordWork(ProjectWorkRecord("C1","DIRECT-W","PROJ1","DIRECT",owner("P1"),9,ProjectWorkResult.SUCCESS,1,provenance="DIRECT"))}
            assertTrue(TurnTransactionBoundary.create(d,id("CMD-PROJ"),admitted("PROJECT","CMD-PROJ")).commit() is TurnExecutionResult.Committed)
            assertEquals(7L,store.progress("PROJ1").progressUnits)
            assertEquals(1L,store.historyCount("PROJ1"))
        }
    }

    @Test fun B01_canonical_proposal_and_turn_capability_are_not_ordinary_forgeable_api(){
        assertTrue(CanonicalCampaignMutationProposal::class.java.declaredConstructors.all{!Modifier.isPublic(it.modifiers) || it.parameterTypes.any{t->t==Any::class.java}})
        assertTrue(TurnTransaction::class.java.declaredConstructors.all{it.parameterTypes.any{t->t==Any::class.java}})
        assertFalse(CampaignMutationBoundary::class.java.methods.any{m->m.parameterTypes.any{it==PlayerResolutionOutcome.Resolved::class.java}})
        assertFalse(TurnTransaction::class.java.methods.any{it.name=="execute"})
        assertFalse(TurnTransaction::class.java.declaredMethods.any{it.name=="authoritativeWrite"})
    }

    @Test fun B02_fake_resolved_has_no_commit_admission_surface(){
        val fakeSet=PlayerChangeSet.create(
            changeSetUid="FAKE-CS",campaignUid="C1",sourceCommandUid="FAKE",actor=CommandActorRef("PLAYER","P1"),
            changes=listOf(PlayerDomainChange.create("FAKE-CH",PlayerChangeKinds.FINANCIAL,FinancialChange("A","B",1,"CUR","RPGOS-FIN-TYPE:TRANSFER"))),
            provenance=ChangeSetProvenance("FAKE","FAKE","1"))
        val fake=PlayerResolutionOutcome.Resolved(fakeSet,PlayerResolutionEvidence("fake",ResolutionEntropyEvidence.none(),"fake","1"))
        assertNotNull(fake)
        assertFalse(CampaignMutationBoundary::class.java.methods.any{m->m.parameterTypes.contains(fake.javaClass)})
        assertFalse(TurnTransactionBoundary::class.java.methods.any{m->m.parameterTypes.contains(fake.javaClass)})
    }

    @Test fun B03_canonical_admission_still_uses_engine_and_complete_legality_pipeline(){
        val p=admitted("FIN5","CMD-B03")
        assertEquals("CMD-B03",p.playerChangeSet.sourceCommandUid)
        assertTrue(p.isCanonical())
        val resolve=PlayerDomainEngine::class.java.methods.single{it.name=="resolve"}
        assertEquals(PlayerResolutionOutcome::class.java,resolve.returnType)
        assertEquals(1,WorldRuleEvaluationStage.values().count{it==WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK})
    }

    @Test fun C01_nonempty_proposal_cannot_receipt_without_full_effect(){
        db().use{d->
            GroupATransactionTestFixtures.setupFinance(d);val p=admitted("FIN5","CMD-C01")
            val result=TurnTransactionBoundary.create(d,id("CMD-C01"),p).commit() as TurnExecutionResult.Committed
            assertEquals(listOf("CH-FIN"),result.value.appliedChangeUids)
            assertEquals(95L,FinancialStore(d,"C1").balance("A"))
            assertEquals(PlayerChangeSetCodec.fingerprint(p.playerChangeSet),result.receipt.semanticFingerprint)
        }
    }

    @Test fun C02_multi_effect_receipt_requires_all_effects_and_subset_failure_rolls_back(){
        db().use{d->
            GroupATransactionTestFixtures.setupFinance(d);CurrentSchema.ensure(d,"C1")
            val p=admitted("MULTI","CMD-C02")
            val tx=TurnTransactionBoundary.create(d,id("CMD-C02"),p,TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("stop-after-subset")})
            assertTrue(runCatching{tx.commit()}.isFailure)
            assertEquals(100L,FinancialStore(d,"C1").balance("A"));assertTrue(CampaignTruthStore(d,"C1").active().isEmpty());assertEquals(0L,receiptCount(d))
            val ok=TurnTransactionBoundary.create(d,id("CMD-C02","TX-C02-RETRY"),p).commit() as TurnExecutionResult.Committed
            assertEquals(p.playerChangeSet.changes.map{it.changeUid},ok.value.appliedChangeUids)
            assertEquals(95L,FinancialStore(d,"C1").balance("A"));assertEquals("TRUTH-MULTI",CampaignTruthStore(d,"C1").active().single().truthUid)
        }
    }

    @Test fun C03_unsupported_effect_fails_closed_before_writes_and_no_receipt(){
        db().use{d->
            GroupATransactionTestFixtures.setupFinance(d);val p=admitted("UNSUPPORTED","CMD-C03")
            val failure=runCatching{TurnTransactionBoundary.create(d,id("CMD-C03"),p).commit()}.exceptionOrNull()
            assertTrue(failure is UnsupportedCanonicalChangeException)
            assertEquals(100L,FinancialStore(d,"C1").balance("A"));assertEquals(0L,receiptCount(d))
        }
    }

    @Test fun C04_failed_application_rolls_back_effects_and_receipt(){
        db().use{d->
            GroupATransactionTestFixtures.setupFinance(d);val p=admitted("FIN5","CMD-C04")
            val tx=TurnTransactionBoundary.create(d,id("CMD-C04"),p,TurnFailureInjector{if(it==TurnFailurePoint.AFTER_RECEIPT_BEFORE_COMMIT)error("crash")})
            assertTrue(runCatching{tx.commit()}.isFailure)
            assertEquals(100L,FinancialStore(d,"C1").balance("A"));assertEquals(0L,receiptCount(d))
        }
    }

    @Test fun C05_identical_retry_does_not_reapply_and_conflicting_retry_fails_closed(){
        db().use{d->
            GroupATransactionTestFixtures.setupFinance(d)
            val p=admitted("FIN5","CMD-C05");val identity=id("CMD-C05")
            assertTrue(TurnTransactionBoundary.create(d,identity,p).commit() is TurnExecutionResult.Committed)
            assertTrue(TurnTransactionBoundary.create(d,identity,p).commit() is TurnExecutionResult.AlreadyCommitted)
            assertEquals(95L,FinancialStore(d,"C1").balance("A"));assertEquals(1L,receiptCount(d))
            val conflict=runCatching{TurnTransactionBoundary.create(d,id("CMD-C05","TX-CONFLICT"),admitted("FIN6","CMD-C05")).commit()}.exceptionOrNull()
            assertTrue(conflict is TurnIdempotencyConflictException)
            assertEquals(95L,FinancialStore(d,"C1").balance("A"));assertEquals(1L,receiptCount(d))
        }
    }

    @Test fun D01_real_g28_v1_schema_rebuild_preserves_history_and_allows_v2(){
        db().use{d->
            GroupATransactionTestFixtures.setupFinance(d)
            val legacyProposal=admitted("FIN5","LEGACY-CMD")
            val semantic=PlayerChangeSetCodec.fingerprint(legacyProposal.playerChangeSet)
            createG28ReceiptTable(d)
            d.execSQL("INSERT INTO turn_transaction_receipts(transaction_uid,campaign_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,receipt_version,commit_state) VALUES(?,?,?,?,?,?,1,'COMMITTED')",
                arrayOf("LEGACY-TX","C1","LEGACY-TURN","LEGACY-CMD",semantic,"legacy-result"))
            TurnTransactionReceiptSchema.ensureReady(d)
            val migrated=TurnTransactionReceiptStore(d).committedTransaction("LEGACY-TX")!!
            assertEquals(1,migrated.receiptVersion);assertNull(migrated.commitOrder);assertEquals(semantic,migrated.semanticFingerprint);assertEquals("legacy-result",migrated.resultFingerprint)
            val ddl=tableSql(d,"turn_transaction_receipts").replace(" ","").lowercase()
            assertTrue(ddl.contains("receipt_versionin(1,2)"))
            assertTrue(TurnTransactionBoundary.create(d,TurnTransactionIdentity("C1","LEGACY-TURN","LEGACY-CMD","LEGACY-TX"),legacyProposal).commit() is TurnExecutionResult.AlreadyCommitted)
            val current=admitted("FIN5","NEW-CMD")
            val committed=TurnTransactionBoundary.create(d,TurnTransactionIdentity("C1","NEW-TURN","NEW-CMD","NEW-TX"),current).commit() as TurnExecutionResult.Committed
            assertEquals(2,committed.receipt.receiptVersion);assertEquals(1L,committed.receipt.commitOrder)
            TurnTransactionReceiptSchema.ensureReady(d)
            assertNull(TurnTransactionReceiptStore(d).committedTransaction("LEGACY-TX")!!.commitOrder)
            assertEquals(1L,TurnRecoveryReader(d).lastValidCommit("C1")!!.commitOrder)
        }
    }

    @Test fun E01_recovery_reader_ready_read_is_ddl_and_metadata_free(){
        db().use{d->
            TurnTransactionReceiptSchema.ensureReady(d)
            val schemaBefore=schemaSnapshot(d);val migrationBefore=migrationSnapshot(d)
            val reader=TurnRecoveryReader(d)
            assertNull(reader.lastValidCommit("C1"));assertEquals(TurnRecoveryState.NOT_RECORDED,reader.transaction("NONE").state)
            assertEquals(schemaBefore,schemaSnapshot(d));assertEquals(migrationBefore,migrationSnapshot(d))
        }
    }

    @Test fun E02_recovery_reader_missing_schema_fails_without_creating_anything(){
        db().use{d->
            val before=schemaSnapshot(d)
            val failure=runCatching{TurnRecoveryReader(d)}.exceptionOrNull()
            assertNotNull(failure);assertTrue(failure!!.message!!.contains("SCHEMA_NOT_READY"));assertEquals(before,schemaSnapshot(d))
        }
    }

    @Test fun F01_real_two_connection_same_identity_concurrency_applies_at_most_once(){
        setupFileFinance()
        val p=admitted("FIN5","CMD-CONCURRENT")
        val d1=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE)
        val d2=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE)
        try{
            val ready=CountDownLatch(2);val go=CountDownLatch(1);val done=CountDownLatch(2);val results=java.util.Collections.synchronizedList(mutableListOf<Any>())
            listOf(d1,d2).forEach{connection->Thread{
                ready.countDown();go.await()
                try{results+=TurnTransactionBoundary.create(connection,id("CMD-CONCURRENT"),p).commit()}catch(t:Throwable){results+=t}
                finally{done.countDown()}
            }.start()}
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();assertTrue(done.await(15,TimeUnit.SECONDS))
            val committed=results.count{it is TurnExecutionResult.Committed<*>};val replay=results.count{it is TurnExecutionResult.AlreadyCommitted}
            assertEquals(1,committed);assertEquals(1,replay)
            d1.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts WHERE campaign_uid='C1'",null).use{it.moveToFirst();assertEquals(1L,it.getLong(0))}
            assertEquals(95L,FinancialStore(d1,"C1").balance("A"))
        } finally {d1.close();d2.close()}
    }

    @Test fun F02_conflicting_semantics_same_identity_fail_closed(){
        setupFileFinance()
        val d1=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE)
        val d2=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE)
        try{
            assertTrue(TurnTransactionBoundary.create(d1,id("CMD-CONFLICT"),admitted("FIN5","CMD-CONFLICT")).commit() is TurnExecutionResult.Committed)
            val failure=runCatching{TurnTransactionBoundary.create(d2,id("CMD-CONFLICT"),admitted("FIN6","CMD-CONFLICT")).commit()}.exceptionOrNull()
            assertTrue(failure is TurnIdempotencyConflictException);assertEquals(95L,FinancialStore(d1,"C1").balance("A"))
        }finally{d1.close();d2.close()}
    }

    private fun admitted(caseUid:String,commandUid:String,effectiveOrder:Long=10):CanonicalCampaignMutationProposal{
        val actor=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(commandUid=commandUid,campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,
            payload=TransferFundsCommandPayload("A","B",5,"CUR"),provenance=CommandProvenance("WORK-023"),requestedEffectiveOrder=effectiveOrder)
        val refs=mutableSetOf(
            scoped("PLAYER","P1"),scoped(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A"),scoped(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B"),
            scoped(PlayerResolutionReferenceKinds.CURRENCY,"CUR"),scoped("STAT","STR"),scoped("RESOURCE","ENERGY"),scoped("SKILL","SK1"),scoped("TECHNIQUE","TECH1"),
            scoped("ITEM_INSTANCE","ITEM-I"),scoped("ITEM_INSTANCE","ITEM-EQ"),scoped("ASSET","A1"),scoped("CHARACTER","P1"),scoped("CHARACTER","P2"),
            scoped(PlayerResolutionReferenceKinds.PROJECT,"PROJ1"),scoped("INNATE","FAKE-INNATE"))
        val context=PlayerResolutionContext.createUnboundGeneric("C1",actor,refs)
        val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(CaseComponent(caseUid))))
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit("C1",engine,command,context)){
            is CampaignMutationAdmission.Accepted->admission.proposal
            is CampaignMutationAdmission.Rejected->error("admission rejected: ${admission.reasonUid}")
        }
    }

    private class CaseComponent(private val caseUid:String):PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"RPGOS-COMPONENT:WORK-023","1"){
        override fun resolve(command:PlayerCommand<TransferFundsCommandPayload>,context:PlayerResolutionContext):PlayerResolutionComponentOutcome{
            val player=DomainRef("PLAYER","P1")
            val changes=when(caseUid){
                "FIN5"->listOf(change("CH-FIN",PlayerChangeKinds.FINANCIAL,FinancialChange("A","B",5,"CUR","RPGOS-FIN-TYPE:TRANSFER")))
                "FIN6"->listOf(change("CH-FIN",PlayerChangeKinds.FINANCIAL,FinancialChange("A","B",6,"CUR","RPGOS-FIN-TYPE:TRANSFER")))
                "INV"->listOf(change("CH-INV",PlayerChangeKinds.INVENTORY,InventoryChange(player,"ITEM-I",ExactLongDelta.of(1))))
                "OWN"->listOf(change("CH-OWN",PlayerChangeKinds.OWNERSHIP,OwnershipChange("OWN-1",asset(),owner("P1"),owner("P2"),OwnershipShare.full())))
                "TRUTH"->listOf(change("CH-TRUTH",PlayerChangeKinds.CAMPAIGN_TRUTH,CampaignTruthChange("TRUTH-CANON",TruthKind.FACT,"P1","canonical","yes",null,null,null)))
                "STAT"->listOf(change("CH-STAT",PlayerChangeKinds.STAT,StatChange(player,"STR",ExactLongDelta.of(2))))
                "RESOURCE"->listOf(change("CH-RES",PlayerChangeKinds.RESOURCE,ResourceChange(player,"ENERGY",ExactLongDelta.of(-3))))
                "SKILL"->listOf(change("CH-SK",PlayerChangeKinds.SKILL,SkillChange(player,"SK1",ExactLongDelta.of(4))))
                "TECH"->listOf(change("CH-TECH",PlayerChangeKinds.TECHNIQUE,TechniqueChange(player,"TECH1",ExactLongDelta.of(5))))
                "EQUIP"->listOf(change("CH-EQ",PlayerChangeKinds.EQUIPMENT,EquipmentChange(player,"SLOT1",EquipmentOperation.EQUIP,"ITEM-EQ")))
                "PROJECT"->listOf(change("CH-PROJ",PlayerChangeKinds.DEVELOPMENT_PROJECT,DevelopmentProjectChange.create("PROJ1","SUCCESS",ProjectProgressDelta.of(7))))
                "MULTI"->listOf(
                    change("CH-MULTI-FIN",PlayerChangeKinds.FINANCIAL,FinancialChange("A","B",5,"CUR","RPGOS-FIN-TYPE:TRANSFER")),
                    change("CH-MULTI-TRUTH",PlayerChangeKinds.CAMPAIGN_TRUTH,CampaignTruthChange("TRUTH-MULTI",TruthKind.FACT,"P1","multi","yes",null,null,null)))
                "UNSUPPORTED"->listOf(
                    change("CH-U-FIN",PlayerChangeKinds.FINANCIAL,FinancialChange("A","B",5,"CUR","RPGOS-FIN-TYPE:TRANSFER")),
                    change("CH-U-INNATE",PlayerChangeKinds.INNATE,InnateChange(player,"FAKE-INNATE","ON")))
                else->error("unknown case $caseUid")
            }
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes=changes))
        }
        private fun change(uid:String,kind:String,payload:PlayerDomainChangePayload)=PlayerDomainChange.create(uid,kind,payload)
    }

    private fun setupOwnership(d:SQLiteDatabase){
        CurrentSchema.ensure(d,"C1");val refs=OwnershipReferenceRegistry(d,"C1");refs.registerAssetKind("ASSET","SETUP");refs.registerOwner(owner("P1"),"SETUP");refs.registerOwner(owner("P2"),"SETUP");refs.registerAsset(asset(),"SETUP")
        OwnershipStore(d,"C1").acquire(OwnershipRecord("C1","OWN-1",owner("P1"),asset(),"OWNER",OwnershipShare.full(),1,provenance="SETUP"))
    }

    private fun setupStatResource(d:SQLiteDatabase){
        CurrentSchema.ensure(d,"C1");val store=StatResourceStore(d,"C1")
        store.registerStatDefinitions("WP",listOf(StatDefinition("STR","str","CORE",minValue=0.0,maxValue=200.0,worldPackUid="WP")))
        store.registerResourceDefinitions("WP",listOf(ResourceDefinition("ENERGY","energy","CORE",minValue=0.0,maxValue=200.0,worldPackUid="WP")))
        store.savePlayerStat(PlayerStat("C1","P1","STR",10.0));store.savePlayerResource(PlayerResource("C1","P1","ENERGY",20.0))
    }

    private fun setupSkillTechnique(d:SQLiteDatabase){
        CurrentSchema.ensure(d,"C1");val skills=SkillStore(d,"C1");val techniques=TechniqueStore(d,"C1")
        skills.registerDefinitions("WP",listOf(SkillDefinition("SK1","WP","sk1","Skill","CORE",provenance="SETUP")))
        skills.savePlayerSkill(PlayerSkill("C1","P1","SK1",10.0,0.0,"UNITS",provenance="SETUP"))
        techniques.registerDefinitions("WP",listOf(TechniqueDefinition("TECH1","WP","tech1","Technique","CORE",provenance="SETUP")))
        techniques.savePlayerTechnique(PlayerTechnique("C1","P1","TECH1",10.0,0.0,"UNITS",entryVersion=1,provenance="SETUP"))
    }

    private fun setupEquipment(d:SQLiteDatabase){
        CurrentSchema.ensure(d,"C1");val inventory=InventoryStore(d,"C1")
        inventory.registerDefinitions("WP",listOf(ItemDefinition("DEF-EQ","WP","eq","Equip Item",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="SETUP")))
        inventory.createInstance(ItemInstance("C1","ITEM-EQ","DEF-EQ",provenance="SETUP"));inventory.addUnique("P1","ITEM-EQ","SETUP")
        val equipment=EquipmentStore(d,"C1");equipment.registerSlots("WP",listOf(EquipmentSlotDefinition("SLOT1","WP","slot","Slot",provenance="SETUP")))
        equipment.registerCompatibilityRules("WP",listOf(EquipmentCompatibilityRule("RULE1","WP","DEF-EQ",listOf("SLOT1"),provenance="SETUP")))
    }

    private fun setupProject(d:SQLiteDatabase){
        CurrentSchema.ensure(d,"C1");val store=DevelopmentProjectStore(d,"C1")
        store.registerProjectType(ProjectTypeDefinition("TYPE1","TEST",provenance="SETUP"))
        store.createProject(DevelopmentProject("C1","PROJ1","TYPE1",owner("P1"),title="P",objectiveSummary="O",targetDomainUid="TEST",createdOrder=1,provenance="SETUP"),"STATUS-1")
    }

    private fun arm(d:SQLiteDatabase){TurnTransactionReceiptSchema.ensureReady(d);GameplayMutationDatabaseGuards.ensureInstalled(d)}
    private fun assertGate(block:()->Unit){val e=runCatching(block).exceptionOrNull();assertNotNull("direct writer unexpectedly succeeded",e);assertTrue(e!!.message.orEmpty().contains("CANONICAL_TURN_TRANSACTION_REQUIRED"))}
    private fun createG28ReceiptTable(d:SQLiteDatabase){
        d.execSQL("DROP TABLE IF EXISTS turn_transaction_receipts")
        d.execSQL("""CREATE TABLE turn_transaction_receipts(
            transaction_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,turn_uid TEXT NOT NULL,command_uid TEXT NOT NULL,
            semantic_fingerprint TEXT NOT NULL,result_fingerprint TEXT NOT NULL,receipt_version INTEGER NOT NULL CHECK(receipt_version = 1),
            commit_state TEXT NOT NULL CHECK(commit_state='COMMITTED'),UNIQUE(campaign_uid,command_uid))""")
    }
    private fun schemaSnapshot(d:SQLiteDatabase):List<String>{val out=mutableListOf<String>();d.rawQuery("SELECT type||':'||name||':'||COALESCE(sql,'') FROM sqlite_master ORDER BY type,name",null).use{c->while(c.moveToNext())out+=c.getString(0)};return out}
    private fun migrationSnapshot(d:SQLiteDatabase):List<String>{if(!tableExists(d,"rpgos_schema_migrations"))return emptyList();val out=mutableListOf<String>();d.rawQuery("SELECT migration_id||':'||applied_at||':'||COALESCE(notes,'') FROM rpgos_schema_migrations ORDER BY migration_id",null).use{c->while(c.moveToNext())out+=c.getString(0)};return out}
    private fun tableSql(d:SQLiteDatabase,name:String)=d.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",arrayOf(name)).use{it.moveToFirst();it.getString(0)}
    private fun tableExists(d:SQLiteDatabase,name:String)=d.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(name)).use{it.moveToFirst()}
    private fun receiptCount(d:SQLiteDatabase)=d.rawQuery("SELECT COUNT(*) FROM turn_transaction_receipts",null).use{it.moveToFirst();it.getLong(0)}
    private fun scoped(kind:String,uid:String)=CampaignScopedDomainRef("C1",DomainRef(kind,uid))
    private fun owner(uid:String)=OwnershipOwnerRef("CHARACTER",uid)
    private fun asset()=OwnedAssetRef("ASSET","A1")
    private fun id(commandUid:String,transactionUid:String="TX-$commandUid")=TurnTransactionIdentity("C1","TURN-$commandUid",commandUid,transactionUid)
    private fun db()=SQLiteDatabase.openOrCreateDatabase(file,null)
    private fun setupFileFinance(){db().use{GroupATransactionTestFixtures.setupFinance(it);arm(it)}}
}
