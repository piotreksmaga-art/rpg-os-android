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
class Phase32RegistryCompletenessDatabaseTest {
 private lateinit var file:File
 @Before fun setUp(){file=File.createTempFile("p32-registry-",".db").also{it.delete()}}
 @After fun tearDown(){file.delete()}
 private fun db()=SQLiteDatabase.openOrCreateDatabase(file,null)
 private fun tables(d:SQLiteDatabase):List<String>{val out=mutableListOf<String>();d.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",null).use{c->while(c.moveToNext())out+=c.getString(0)};return out}
 private fun campaignScoped(d:SQLiteDatabase,t:String):Boolean=d.rawQuery("PRAGMA table_info(`$t`)",null).use{c->while(c.moveToNext()){val n=c.getString(1);if(n=="campaign_id"||n=="campaign_uid")return@use true};false}
 private fun triggerTables(d:SQLiteDatabase):Set<String>{val out=linkedSetOf<String>();d.rawQuery("SELECT DISTINCT tbl_name FROM sqlite_master WHERE type='trigger' AND name LIKE 'rpgos_guard_%' ORDER BY tbl_name",null).use{c->while(c.moveToNext())out+=c.getString(0)};return out}

 @Test fun everyCampaignScopedProductionTableIsClassifiedAndAuthorityGuardSetMatchesRegistry(){db().use{d->
  Phase32ProductionReadyTestFixture.setup(d)
  RuntimeTruthLayerRegistry.validateCanonicalInventory()
  val scoped=tables(d).filter{campaignScoped(d,it)}.toSortedSet()
  val unclassified=scoped.filter{RuntimeTruthLayerRegistry.classificationForTable(it)==null}
  assertEquals("campaign-scoped persistent table(s) missing truth-layer classification: $unclassified",emptyList<String>(),unclassified)

  val existingAuthority=RuntimeTruthLayerRegistry.authoritativePersistentTables().filter{it in scoped}.toSortedSet()
  val guarded=triggerTables(d).toSortedSet()
  assertEquals("authoritative persistent guard set drift",existingAuthority,guarded)
  guarded.forEach{table->assertTrue("non-authoritative table received authority guard: $table",RuntimeTruthLayerRegistry.requireClassifiedTable(table).isAuthoritative)}
  listOf("financial_account_balances","canonical_gameplay_events","canonical_causal_relations","turn_transaction_receipts").forEach{assertFalse("non-domain authority table guarded as gameplay authority: $it",it in guarded)}
 }}

 @Test fun newlyNamedUnknownPersistentFamilyFailsClosed(){
  assertTrue(runCatching{RuntimeTruthLayerRegistry.requireClassifiedTable("future_campaign_authority")}.isFailure)
  assertTrue(runCatching{RuntimeTruthLayerRegistry.requireFamily("FUTURE_CAMPAIGN_AUTHORITY")}.isFailure)
 }
}
