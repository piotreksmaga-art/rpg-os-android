package com.rpgos.app

import android.content.Context
import android.database.Cursor
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

    @Test fun requiredEventManifestGeneratedForEventlessAuthoritativeChangeAndReceiptBindsIt(){SQLiteDatabase.create(null).use{db->
        GroupATransactionTestFixtures.setupFinance(db,"C1")
        val proposal=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-W36-EVENTLESS")
        assertTrue(proposal.playerChangeSet.eventIntents.isEmpty())
        val id=TurnTransactionIdentity("C1","TURN-W36-EVENTLESS","CMD-W36-EVENTLESS","TX-W36-EVENTLESS")
        val result=TurnTransactionBoundary.create(db,id,proposal).commit() as TurnExecutionResult.Committed
        assertEquals(1,result.receipt.requiredEventCount);assertNotNull(result.receipt.requiredEventManifestFingerprint)
        val row=db.rawQuery("SELECT committed_order,event_ordinal FROM canonical_gameplay_events WHERE campaign_uid='C1' AND transaction_uid=?",arrayOf(id.transactionUid)).use{c->assertTrue(c.moveToFirst());c.getLong(0) to c.getInt(1)}
        assertEquals(result.receipt.commitOrder,row.first);assertEquals(0,row.second);assertEquals(95L,FinancialStore(db,"C1").balance("A"))
    }}

    @Test fun conflictingOrIncompleteExplicitEventManifestFailsBeforeDomainMutation(){SQLiteDatabase.create(null).use{db->
        GroupATransactionTestFixtures.setupFinance(db,"C1")
        val base=GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-W36-BAD-EVENT")
        val change=base.playerChangeSet.changes.single()
        val actor=base.playerChangeSet.actor
        val failure=runCatching{PlayerChangeSet.create(
            changeSetUid=base.playerChangeSet.changeSetUid+":BAD",campaignUid="C1",actor=actor,sourceCommandUid="CMD-W36-BAD-EVENT",
            changes=listOf(change),eventIntents=listOf(PlayerEventIntent.create(
                eventIntentUid="BAD-EVENT",eventKindUid=PlayerEventIntentKinds.DOMAIN_EFFECT,actorRef=DomainRef("PLAYER","P1"),
                targetRefs=listOf(DomainRef("PLAYER","P1")),causalChangeUids=listOf("MISSING-CHANGE"),
                payload=DomainEffectEventIntentPayload(DomainRef("PLAYER","P1"),"BAD"))),
            requestedEffectiveOrder=10L,provenance=base.playerChangeSet.provenance
        )}.exceptionOrNull()
        assertTrue(failure is PlayerChangeSetStructuralException);assertEquals(100L,FinancialStore(db,"C1").balance("A"));assertEquals(0L,count(db,"turn_transaction_receipts"));assertEquals(0L,count(db,"canonical_gameplay_events"))
    }}

    @Test fun eventAndCausalUseOneReceiptOwnedOrderNoIndependentAllocator(){SQLiteDatabase.create(null).use{db->
        GroupATransactionTestFixtures.setupFinance(db,"C1")
        val e1=commitEvent(db,"O1");val e2=commitEvent(db,"O2")
        val proposal=CampaignMutationBoundary.withValidatedCausalPlan(
            GroupATransactionTestFixtures.admittedFinancialProposal(commandUid="CMD-W36-ORDER-C",amountMinor=1),
            listOf(CanonicalCausalRelationIntent("REL-W36-ORDER",CausalRelationClass.PROVENANCE,CausalRelationKinds.PROVENANCE_OF,e1,e2,provenanceEventUids=listOf(e1)))
        )
        val id=TurnTransactionIdentity("C1","TURN-W36-ORDER-C","CMD-W36-ORDER-C","TX-W36-ORDER-C")
        val committed=TurnTransactionBoundary.create(db,id,proposal).commit() as TurnExecutionResult.Committed
        val event=db.rawQuery("SELECT committed_order,event_ordinal FROM canonical_gameplay_events WHERE campaign_uid='C1' AND transaction_uid=?",arrayOf(id.transactionUid)).use{c->c.moveToFirst();c.getLong(0) to c.getInt(1)}
        val rel=db.rawQuery("SELECT committed_order,relation_ordinal FROM canonical_causal_relations WHERE campaign_uid='C1' AND transaction_uid=?",arrayOf(id.transactionUid)).use{c->c.moveToFirst();c.getLong(0) to c.getInt(1)}
        assertEquals(committed.receipt.commitOrder,event.first);assertEquals(committed.receipt.commitOrder,rel.first);assertEquals(0,event.second);assertEquals(0,rel.second)
        val eventSql=tableSql(db,"canonical_gameplay_events");val causalSql=tableSql(db,"canonical_causal_relations")
        assertFalse(eventSql.replace(" ","").contains("UNIQUE(campaign_uid,committed_order)"));assertFalse(causalSql.replace(" ","").contains("UNIQUE(campaign_uid,committed_order)"))
    }}

    @Test fun causalSelfEdgeAndAllDependencyCyclesFailClosedWhileNarrativeCycleIsLegal(){SQLiteDatabase.create(null).use{db->
        GroupATransactionTestFixtures.setupFinance(db,"C1")
        val a=commitEvent(db,"A");val b=commitEvent(db,"B");val c=commitEvent(db,"C")
        fun cause(uid:String,s:String,t:String,kind:String=CausalRelationKinds.CAUSES)=CanonicalCausalRelationIntent(uid,CausalRelationClass.CAUSAL,kind,s,t,evidenceEventUids=listOf(s))
        assertFails{CampaignCausalGraph(db,"C1").validate(listOf(cause("SELF",a,a)))}
        commitRelations(db,"AB",listOf(cause("AB",a,b)))
        assertFails{commitRelations(db,"BA",listOf(cause("BA",b,a)))}
        commitRelations(db,"BC",listOf(cause("BC",b,c)))
        assertFails{commitRelations(db,"CA",listOf(cause("CA",c,a)))}
        assertFails{commitRelations(db,"BATCH",listOf(cause("X1",a,b),cause("X2",b,c),cause("X3",c,a)))}
        listOf(CausalRelationKinds.DERIVED_FROM,CausalRelationKinds.SUPERSEDES).forEach{kind->
            val klass=CausalRelationClass.DERIVED
            assertFails{commitRelations(db,"CYCLE-$kind",listOf(CanonicalCausalRelationIntent("D1-$kind",klass,kind,a,b),CanonicalCausalRelationIntent("D2-$kind",klass,kind,b,a)))}
        }
        assertFails{commitRelations(db,"PREVENTS",listOf(cause("P1",a,b,CausalRelationKinds.PREVENTS),cause("P2",b,a,CausalRelationKinds.PREVENTS)))}
        commitRelations(db,"NARRATIVE",listOf(CanonicalCausalRelationIntent("N1",CausalRelationClass.NARRATIVE,CausalRelationKinds.NARRATIVE_ASSOCIATION,a,b),CanonicalCausalRelationIntent("N2",CausalRelationClass.NARRATIVE,CausalRelationKinds.NARRATIVE_ASSOCIATION,b,a)))
    }}

    @Test fun strongCausalProofMustBindToRelationEndpointAndCrossCampaignStillFails(){SQLiteDatabase.create(null).use{db->
        GroupATransactionTestFixtures.setupFinance(db,"C1");val a=commitEvent(db,"EA");val b=commitEvent(db,"EB");val unrelated=commitEvent(db,"EU")
        val graph=CampaignCausalGraph(db,"C1")
        assertFails{graph.validate(listOf(CanonicalCausalRelationIntent("UNREL-E",CausalRelationClass.CAUSAL,CausalRelationKinds.CAUSES,a,b,evidenceEventUids=listOf(unrelated))))}
        assertFails{graph.validate(listOf(CanonicalCausalRelationIntent("UNREL-P",CausalRelationClass.CAUSAL,CausalRelationKinds.CAUSES,a,b,provenanceEventUids=listOf(unrelated))))}
        graph.validate(listOf(CanonicalCausalRelationIntent("BOUND",CausalRelationClass.CAUSAL,CausalRelationKinds.CAUSES,a,b,evidenceEventUids=listOf(a))))
        GameplayRuntimeBootstrap.initialize(db,"C2")
        assertFails{CampaignCausalGraph(db,"C2").appendRequired(TurnTransactionIdentity("C2","T","C","X"),listOf(CanonicalCausalRelationIntent("CROSS",CausalRelationClass.PROVENANCE,CausalRelationKinds.PROVENANCE_OF,a,b)),1)}
    }}

    @Test fun normalCampaignRepositoryPathCarriesValidatedCausalPlanAndRetryIsIdempotent(){
        val dbFile=prepareDefaultCampaignDb();SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{db->GroupATransactionTestFixtures.setupFinance(db,campaignUid);val e1=commitEvent(db,"REPO1",campaignUid);val e2=commitEvent(db,"REPO2",campaignUid)
            val base=GroupATransactionTestFixtures.admittedFinancialProposal(campaignUid=campaignUid,commandUid="CMD-W36-REPO",amountMinor=1)
            val proposal=CampaignMutationBoundary.withValidatedCausalPlan(base,listOf(CanonicalCausalRelationIntent("REL-W36-REPO",CausalRelationClass.PROVENANCE,CausalRelationKinds.PROVENANCE_OF,e1,e2,provenanceEventUids=listOf(e1))))
            val id=TurnTransactionIdentity(campaignUid,"TURN-W36-REPO","CMD-W36-REPO","TX-W36-REPO")
            val repo=UnifiedGameRepository(context);assertTrue(repo.commitTurn(id,proposal) is TurnExecutionResult.Committed);assertTrue(repo.commitTurn(id,proposal) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1L,db.rawQuery("SELECT COUNT(*) FROM canonical_causal_relations WHERE campaign_uid=? AND relation_intent_uid='REL-W36-REPO'",arrayOf(campaignUid)).use{c->c.moveToFirst();c.getLong(0)})
        }
    }

    @Test fun adminWritesRequireReadinessAndAuthority(){
        val dbFile=prepareDefaultCampaignDb();SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{db->CurrentSchema.ensure(db,campaignUid);db.execSQL("CREATE TABLE IF NOT EXISTS character_stats(entity_uid TEXT,stat_key TEXT,current_value REAL)");db.execSQL("INSERT INTO character_stats(entity_uid,stat_key,current_value) VALUES('P1','x',1)")}
        val store=LocalGameStore(context);assertFails{store.setActivePlayer("P1")}
        SQLiteDatabase.openDatabase(dbFile.absolutePath,null,SQLiteDatabase.OPEN_READWRITE).use{db->GameplayRuntimeBootstrap.initialize(db,campaignUid);assertFails{StatResourceStore(db,campaignUid).registerStatDefinitions("W",listOf(StatDefinition("S-W36","s","CORE",worldPackUid="W")))};withAdministrativeMutationAuthority(db,campaignUid){StatResourceStore(db,campaignUid).registerStatDefinitions("W",listOf(StatDefinition("S-W36","s","CORE",worldPackUid="W")))}}
    }

    @Test fun canonicalStoreConstructionIsMutationFreeAndUnknownPersistentFamilyWriterFailClosed(){SQLiteDatabase.create(null).use{db->
        GroupATransactionTestFixtures.setupFinance(db,"C1");val before=sqliteObjects(db) to migrationInventory(db)
        FinancialStore(db,"C1");EquipmentStore(db,"C1");OwnershipStore(db,"C1");OwnershipReferenceRegistry(db,"C1");DevelopmentProjectStore(db,"C1");InventoryStore(db,"C1");Phase9Store(db,"C1");AssetLiabilityStore(db,"C1")
        assertEquals(before,sqliteObjects(db) to migrationInventory(db))
        db.execSQL("CREATE TABLE future_unknown_application_table(id INTEGER)");assertFails{RuntimePersistentTableInventory.requireComplete(db)};assertFails{RuntimePersistentWriterRegistry.requireContract("futureUnknownWriter")}
    }}

    private fun commitEvent(db:SQLiteDatabase,suffix:String,campaign:String="C1"):String{val cmd="CMD-W36-$suffix";val id=TurnTransactionIdentity(campaign,"TURN-W36-$suffix",cmd,"TX-W36-$suffix");TurnTransactionBoundary.create(db,id,GroupATransactionTestFixtures.admittedFinancialProposal(campaignUid=campaign,commandUid=cmd,amountMinor=1)).commit();return db.rawQuery("SELECT event_uid FROM canonical_gameplay_events WHERE campaign_uid=? AND transaction_uid=? ORDER BY event_ordinal LIMIT 1",arrayOf(campaign,id.transactionUid)).use{c->c.moveToFirst();c.getString(0)}}
    private fun commitRelations(db:SQLiteDatabase,suffix:String,relations:List<CanonicalCausalRelationIntent>){val cmd="CMD-W36-REL-$suffix";val p=CampaignMutationBoundary.withValidatedCausalPlan(GroupATransactionTestFixtures.admittedFinancialProposal(commandUid=cmd,amountMinor=1),relations);TurnTransactionBoundary.create(db,TurnTransactionIdentity("C1","TURN-$cmd",cmd,"TX-$cmd"),p).commit()}
    private fun canonicalForTest(changeSet:PlayerChangeSet):CanonicalCampaignMutationProposal{val actor=CommandActorRef("PLAYER","P1");val command=PlayerCommand(commandUid=changeSet.sourceCommandUid,campaignUid=changeSet.campaignUid,actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,payload=TransferFundsCommandPayload("A","B",1,"CUR"),provenance=CommandProvenance("W36"),requestedEffectiveOrder=10);val refs=setOf(CampaignScopedDomainRef("C1",DomainRef("PLAYER","P1")),CampaignScopedDomainRef("C1",DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A")),CampaignScopedDomainRef("C1",DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B")),CampaignScopedDomainRef("C1",DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR")));val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(object:PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"W36-FORGED","1"){override fun resolve(command:PlayerCommand<TransferFundsCommandPayload>,context:PlayerResolutionContext)=PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes=changeSet.changes,eventIntents=changeSet.eventIntents))})));return (CampaignMutationBoundary.resolveAndAdmit("C1",engine,command,PlayerResolutionContext.createUnboundGeneric("C1",actor,refs)) as CampaignMutationAdmission.Accepted).proposal}
    private fun prepareDefaultCampaignDb():File{val dir=File(root,"saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}").apply{mkdirs()};File(dir,"campaign.json").writeText("{\"id\":\"$campaignUid\"}");val world=File(root,"worldpacks/Naruto.worldpack").apply{mkdirs()};SQLiteDatabase.openOrCreateDatabase(File(world,"world.db"),null).close();return File(dir,"campaign.db").also{SQLiteDatabase.openOrCreateDatabase(it,null).close()}}
    private fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{c->c.moveToFirst();c.getLong(0)}
    private fun tableSql(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{c->c.moveToFirst();c.getString(0)}
    private fun sqliteObjects(db:SQLiteDatabase)=db.rawQuery("SELECT type||':'||name||':'||COALESCE(sql,'') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    private fun migrationInventory(db:SQLiteDatabase)=db.rawQuery("SELECT migration_id||':'||COALESCE(notes,'') FROM rpgos_schema_migrations ORDER BY migration_id",null).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
    private fun assertFails(block:()->Unit){assertTrue(runCatching(block).isFailure)}
}
