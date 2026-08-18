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
class Work036Phase30To32PostAuditRepairTest {
    private lateinit var context:Context
    private lateinit var root:File
    private val campaignUid=ActiveCampaignRef.DEFAULT_CAMPAIGN_ID
    @Before fun setup(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root=File(context.filesDir,"rpgos").also{it.deleteRecursively()}}
    @After fun cleanup(){context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();root.deleteRecursively()}

    @Test fun requiredEventManifestAndReceiptShareOnePhase29Order(){SQLiteDatabase.create(null).use{db->
        GroupATransactionTestFixtures.setupFinance(db,"C1")
        val p=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-W36-EVENTLESS");assertTrue(p.playerChangeSet.eventIntents.isEmpty())
        val id=TurnTransactionIdentity("C1","TURN-W36-EVENTLESS","CMD-W36-EVENTLESS","TX-W36-EVENTLESS")
        val r=TurnTransactionBoundary.create(db,id,p).commit() as TurnExecutionResult.Committed
        assertEquals(1,r.receipt.requiredEventCount);assertNotNull(r.receipt.requiredEventManifestFingerprint);assertNotNull(r.receipt.commitOrder)
        val row=db.rawQuery("SELECT committed_order,event_ordinal FROM canonical_gameplay_events WHERE campaign_uid='C1' AND transaction_uid=?",arrayOf(id.transactionUid)).use{c->assertTrue(c.moveToFirst());c.getLong(0) to c.getInt(1)}
        assertEquals(r.receipt.commitOrder,row.first);assertEquals(0,row.second);assertEquals(95L,FinancialStore(db,"C1").balance("A"))
        assertTrue(TurnTransactionBoundary.create(db,id,p).commit() is TurnExecutionResult.AlreadyCommitted);assertEquals(1L,count(db,"canonical_gameplay_events"))
    }}

    @Test fun duplicateSemanticExplicitEventManifestFailsBeforeDomainMutation(){SQLiteDatabase.create(null).use{db->
        GroupATransactionTestFixtures.setupFinance(db,"C1")
        val p=duplicateEventProposal("CMD-W36-BAD-EVENT")
        val failure=runCatching{TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN-W36-BAD-EVENT","CMD-W36-BAD-EVENT","TX-W36-BAD-EVENT"),p).commit()}.exceptionOrNull()
        assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("DUPLICATE_SEMANTIC_EVENT"));assertEquals(100L,FinancialStore(db,"C1").balance("A"));assertEquals(0L,count(db,"turn_transaction_receipts"));assertEquals(0L,count(db,"canonical_gameplay_events"))
    }}

    @Test fun causalOrderDagAndStrongProofAreFailClosed(){SQLiteDatabase.create(null).use{db->
        GroupATransactionTestFixtures.setupFinance(db,"C1");val a=commitEvent(db,"A");val b=commitEvent(db,"B");val c=commitEvent(db,"C");val unrelated=commitEvent(db,"U")
        fun cause(uid:String,s:String,t:String,kind:String=CausalRelationKinds.CAUSES)=CanonicalCausalRelationIntent(uid,CausalRelationClass.CAUSAL,kind,s,t,evidenceEventUids=listOf(s))
        assertFails{CampaignCausalGraph(db,"C1").validate(listOf(cause("SELF",a,a)))}
        assertFails{CampaignCausalGraph(db,"C1").validate(listOf(CanonicalCausalRelationIntent("BAD-E",CausalRelationClass.CAUSAL,CausalRelationKinds.CAUSES,a,b,evidenceEventUids=listOf(unrelated))))}
        assertFails{CampaignCausalGraph(db,"C1").validate(listOf(CanonicalCausalRelationIntent("BAD-P",CausalRelationClass.CAUSAL,CausalRelationKinds.CAUSES,a,b,provenanceEventUids=listOf(unrelated))))}
        CampaignCausalGraph(db,"C1").validate(listOf(cause("BOUND",a,b)))
        commitRelations(db,"AB",listOf(cause("AB",a,b)));assertFails{commitRelations(db,"BA",listOf(cause("BA",b,a)))}
        commitRelations(db,"BC",listOf(cause("BC",b,c)));assertFails{commitRelations(db,"CA",listOf(cause("CA",c,a)))}
        assertFails{commitRelations(db,"BATCH",listOf(cause("X1",a,b),cause("X2",b,c),cause("X3",c,a)))}
        listOf(CausalRelationKinds.DERIVED_FROM,CausalRelationKinds.SUPERSEDES).forEach{kind->assertFails{commitRelations(db,"D-$kind",listOf(CanonicalCausalRelationIntent("D1-$kind",CausalRelationClass.DERIVED,kind,a,b),CanonicalCausalRelationIntent("D2-$kind",CausalRelationClass.DERIVED,kind,b,a)))}}
        assertFails{commitRelations(db,"P",listOf(cause("P1",a,b,CausalRelationKinds.PREVENTS),cause("P2",b,a,CausalRelationKinds.PREVENTS)))}
        commitRelations(db,"N",listOf(CanonicalCausalRelationIntent("N1",CausalRelationClass.NARRATIVE,CausalRelationKinds.NARRATIVE_ASSOCIATION,a,b),CanonicalCausalRelationIntent("N2",CausalRelationClass.NARRATIVE,CausalRelationKinds.NARRATIVE_ASSOCIATION,b,a)))
    }}

    @Test fun normalCampaignRepositoryPathCarriesCausalPlanWithAtomicRetry(){
        val dbFile=prepareDefaultCampaignDb();SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{db->GroupATransactionTestFixtures.setupFinance(db,campaignUid);val a=commitEvent(db,"REPO-A",campaignUid);val b=commitEvent(db,"REPO-B",campaignUid)
            val base=GroupATransactionTestFixtures.admittedFinancialProposal(campaignUid=campaignUid,commandUid="CMD-W36-REPO",amountMinor=1)
            val proposal=CampaignMutationBoundary.withValidatedCausalPlan(base,listOf(CanonicalCausalRelationIntent("REL-W36-REPO",CausalRelationClass.PROVENANCE,CausalRelationKinds.PROVENANCE_OF,a,b,provenanceEventUids=listOf(a))))
            val id=TurnTransactionIdentity(campaignUid,"TURN-W36-REPO","CMD-W36-REPO","TX-W36-REPO");val repo=UnifiedGameRepository(context)
            assertTrue(repo.commitTurn(id,proposal) is TurnExecutionResult.Committed);assertTrue(repo.commitTurn(id,proposal) is TurnExecutionResult.AlreadyCommitted)
            val receipt=db.rawQuery("SELECT commit_order FROM turn_transaction_receipts WHERE transaction_uid=?",arrayOf(id.transactionUid)).use{c->c.moveToFirst();c.getLong(0)}
            val relation=db.rawQuery("SELECT committed_order,relation_ordinal FROM canonical_causal_relations WHERE campaign_uid=? AND relation_intent_uid='REL-W36-REPO'",arrayOf(campaignUid)).use{c->c.moveToFirst();c.getLong(0) to c.getInt(1)}
            assertEquals(receipt,relation.first);assertEquals(0,relation.second);assertEquals(1L,db.rawQuery("SELECT COUNT(*) FROM canonical_causal_relations WHERE relation_intent_uid='REL-W36-REPO'",null).use{c->c.moveToFirst();c.getLong(0)})
        }
    }

    @Test fun readinessAdminDefinitionsConstructorsAndUnknownInventoriesAreEnforced(){
        val dbFile=prepareDefaultCampaignDb();SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{db->CurrentSchema.ensure(db,campaignUid);db.execSQL("CREATE TABLE IF NOT EXISTS character_stats(entity_uid TEXT,stat_key TEXT,current_value REAL)");db.execSQL("INSERT INTO character_stats(entity_uid,stat_key,current_value) VALUES('P1','x',1)")}
        assertFails{LocalGameStore(context).setActivePlayer("P1")}
        SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{db->
            GameplayRuntimeBootstrap.initialize(db,campaignUid)
            assertFails{StatResourceStore(db,campaignUid).registerStatDefinitions("W",listOf(StatDefinition("S-W36","s","CORE",worldPackUid="W")))}
            withAdministrativeMutationAuthority(db,campaignUid){StatResourceStore(db,campaignUid).registerStatDefinitions("W",listOf(StatDefinition("S-W36","s","CORE",worldPackUid="W")))}
            val before=sqliteObjects(db) to migrationInventory(db)
            FinancialStore(db,campaignUid);EquipmentStore(db,campaignUid);OwnershipStore(db,campaignUid);OwnershipReferenceRegistry(db,campaignUid);DevelopmentProjectStore(db,campaignUid);InventoryStore(db,campaignUid);Phase9Store(db,campaignUid);AssetLiabilityStore(db,campaignUid)
            assertEquals(before,sqliteObjects(db) to migrationInventory(db))
            db.execSQL("CREATE TABLE future_unknown_application_table(id INTEGER)");assertFails{RuntimePersistentTableInventory.requireComplete(db)};assertFails{RuntimePersistentWriterRegistry.requireContract("futureUnknownWriter")}
        }
    }

    private fun duplicateEventProposal(commandUid:String):CanonicalCampaignMutationProposal{
        val actor=CommandActorRef("PLAYER","P1");val cmd=PlayerCommand(commandUid=commandUid,campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,payload=TransferFundsCommandPayload("A","B",1,"CUR"),provenance=CommandProvenance("W36"),requestedEffectiveOrder=10)
        val refs=setOf(CampaignScopedDomainRef("C1",DomainRef("PLAYER","P1")),CampaignScopedDomainRef("C1",DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A")),CampaignScopedDomainRef("C1",DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B")),CampaignScopedDomainRef("C1",DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR")))
        val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(object:PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"W36-DUP-EVENT","1"){
            override fun resolve(command:PlayerCommand<TransferFundsCommandPayload>,context:PlayerResolutionContext):PlayerResolutionComponentOutcome{val changeUid="CHANGE-$commandUid";val subject=DomainRef("PLAYER","P1");val change=PlayerDomainChange.create(changeUid,PlayerChangeKinds.FINANCIAL,FinancialChange("A","B",1,"CUR","RPGOS-FIN-TYPE:TRANSFER"));fun e(uid:String)=PlayerEventIntent.create(uid,PlayerEventIntentKinds.DOMAIN_EFFECT,subject,listOf(subject),listOf(changeUid),DomainEffectEventIntentPayload(subject,"SAME"));return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes=listOf(change),eventIntents=listOf(e("E1"),e("E2"))))}
        })))
        return (CampaignMutationBoundary.resolveAndAdmit("C1",engine,cmd,PlayerResolutionContext.createUnboundGeneric("C1",actor,refs)) as CampaignMutationAdmission.Accepted).proposal
    }
    private fun commitEvent(db:SQLiteDatabase,suffix:String,campaign:String="C1"):String{val cmd="CMD-W36-$suffix";val id=TurnTransactionIdentity(campaign,"TURN-W36-$suffix",cmd,"TX-W36-$suffix");TurnTransactionBoundary.create(db,id,GroupATransactionTestFixtures.admittedFinancialProposal(campaignUid=campaign,commandUid=cmd,amountMinor=1)).commit();return db.rawQuery("SELECT event_uid FROM canonical_gameplay_events WHERE campaign_uid=? AND transaction_uid=? ORDER BY event_ordinal LIMIT 1",arrayOf(campaign,id.transactionUid)).use{c->c.moveToFirst();c.getString(0)}}
    private fun commitRelations(db:SQLiteDatabase,suffix:String,relations:List<CanonicalCausalRelationIntent>){val cmd="CMD-W36-REL-$suffix";val p=CampaignMutationBoundary.withValidatedCausalPlan(GroupATransactionTestFixtures.admittedFinancialProposal(commandUid=cmd,amountMinor=1),relations);TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN-$cmd",cmd,"TX-$cmd"),p).commit()}
    private fun prepareDefaultCampaignDb():File{val dir=File(root,"saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}").apply{mkdirs()};File(dir,"campaign.json").writeText("{\"id\":\"$campaignUid\"}");val world=File(root,"worldpacks/Naruto.worldpack").apply{mkdirs()};SQLiteDatabase.openOrCreateDatabase(File(world,"world.db"),null).close();return File(dir,"campaign.db").also{SQLiteDatabase.openOrCreateDatabase(it,null).close()}}
    private fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{c->c.moveToFirst();c.getLong(0)}
    private fun sqliteObjects(db:SQLiteDatabase)=db.rawQuery("SELECT type||':'||name||':'||COALESCE(sql,'') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    private fun migrationInventory(db:SQLiteDatabase)=db.rawQuery("SELECT migration_id||':'||COALESCE(notes,'') FROM rpgos_schema_migrations ORDER BY migration_id",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    private fun assertFails(block:()->Unit){assertTrue(runCatching(block).isFailure)}
}