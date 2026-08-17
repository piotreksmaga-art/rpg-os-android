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
class Phase32TruthLayerDatabaseTest {
 private lateinit var file:File
 @Before fun setUp(){file=File.createTempFile("p32-",".db").also{it.delete()}}
 @After fun tearDown(){file.delete()}
 private fun db()=SQLiteDatabase.openOrCreateDatabase(file,null)
 private fun count(d:SQLiteDatabase,t:String)=d.rawQuery("SELECT COUNT(*) FROM $t",null).use{it.moveToFirst();it.getLong(0)}
 private fun trigger(d:SQLiteDatabase,n:String)=d.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=?",arrayOf(n)).use{it.moveToFirst()}

 @Test fun financeBalanceProjectionDeletesAndRebuildsExactlyFromLedger(){db().use{d->
  Phase32ProductionReadyTestFixture.setup(d)
  val asset=OwnedAssetRef("G32-ASSET-KIND","G32-ASSET")
  withAdministrativeMutationAuthority(d,"C1"){
   val refs=OwnershipReferenceRegistry(d,"C1")
   refs.registerAssetKind(asset.assetKindUid,"G32")
   refs.registerAsset(asset,"G32")
   OwnershipStore(d,"C1").acquire(OwnershipRecord("C1","G32-OWN",OwnershipOwnerRef("CHARACTER","P1"),asset,"OWNER",OwnershipShare.full(),1L,provenance="G32"))
  }
  val f=FinancialStore(d,"C1")
  val before=f.balance("A")
  val ledger=count(d,"financial_ledger_transactions")
  val events=count(d,"canonical_gameplay_events")
  val causal=count(d,"canonical_causal_relations")
  val receipts=count(d,"turn_transaction_receipts")
  val ownership=OwnershipStore(d,"C1").history(asset)
  d.delete("financial_account_balances","campaign_id=? AND account_uid=?",arrayOf("C1","A"))
  assertTrue(runCatching{f.balance("A")}.isFailure)
  assertEquals(before,f.rebuildBalance("A"))
  assertEquals(before,f.balance("A"))
  assertEquals(ledger,count(d,"financial_ledger_transactions"))
  assertEquals(events,count(d,"canonical_gameplay_events"))
  assertEquals(causal,count(d,"canonical_causal_relations"))
  assertEquals(receipts,count(d,"turn_transaction_receipts"))
  assertEquals(ownership,OwnershipStore(d,"C1").history(asset))
 }}

 @Test fun receiptEventAndCausalHistoryHaveDatabaseImmutabilityGuards(){db().use{d->
  Phase32ProductionReadyTestFixture.setup(d)
  assertTrue(trigger(d,"rpgos_turn_receipts_no_update"));assertTrue(trigger(d,"rpgos_turn_receipts_no_delete"));assertTrue(trigger(d,"rpgos_event_store_no_update"));assertTrue(trigger(d,"rpgos_event_store_no_delete"));assertTrue(trigger(d,"rpgos_causal_graph_no_update"));assertTrue(trigger(d,"rpgos_causal_graph_no_delete"))
 }}

 @Test fun derivedProjectionCannotAcquireAuthorityFromFreshness(){
  listOf("CHARACTER_PANEL_SNAPSHOT_V2","PLAYER_SNAPSHOT_PROFILES","CONTEXT_BUNDLE","FINANCE_BALANCE_PROJECTION").forEach{uid->
   assertFalse(RuntimeTruthLayerRegistry.requireFamily(uid).isAuthoritative)
   assertTrue(runCatching{RuntimeTruthLayerRegistry.requireAuthoritativeMutation("CAMPAIGN_TRUTH",RuntimeMutationCapability.PRESENTATION_ONLY)}.isFailure)
  }
 }

 @Test fun eventAndCausalFamiliesCannotBecomeDomainAuthority(){
  assertFalse(RuntimeTruthLayerRegistry.requireFamily("EVENT_STORE").isAuthoritative);assertFalse(RuntimeTruthLayerRegistry.requireFamily("CAUSAL_GRAPH").isAuthoritative)
  listOf("CAMPAIGN_TRUTH","FINANCE_AUTHORITY","OWNERSHIP_HISTORY","INVENTORY","DEVELOPMENT_PROJECTS").forEach{assertTrue(RuntimeTruthLayerRegistry.requireFamily(it).isAuthoritative)}
 }

 @Test fun legacyProvenanceRuleRemainsUnknownNotRecorded(){db().use{d->Phase32ProductionReadyTestFixture.setup(d);val status=d.rawQuery("SELECT legacy_event_history_status FROM campaign_intelligence_activation WHERE campaign_uid='C1'",null).use{it.moveToFirst();it.getString(0)};assertEquals("UNKNOWN_NOT_RECORDED",status);assertEquals(0L,count(d,"canonical_gameplay_events"));assertEquals(0L,count(d,"canonical_causal_relations"))}}
}
