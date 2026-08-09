package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModifierPersistenceTest {
    private lateinit var dbFile: File
    @Before fun setUp(){ dbFile=File.createTempFile("rpgos-phase5-",".db"); dbFile.delete() }
    @After fun tearDown(){ dbFile.delete() }

    @Test fun migrationIsAdditiveIdempotentAndInventsNoModifiers()=open().use{db->
        db.execSQL("CREATE TABLE character_stats(entity_uid TEXT,stat_key TEXT,current_value REAL)")
        db.execSQL("INSERT INTO character_stats VALUES('P','legacy_custom',77.0)")
        MigrationManager().ensureV4(db,"C")
        val s=StatResourceStore(db,"C"); s.registerStatDefinitions("W",listOf(statDef())); s.savePlayerStat(PlayerStat("C","P","S",100.0))
        val legacy=scalarDouble(db,"SELECT current_value FROM character_stats WHERE entity_uid='P'")
        val base=scalarDouble(db,"SELECT base_value FROM player_stats WHERE stat_uid='S'")
        MigrationManager().ensureV4(db,"C"); MigrationManager().ensureV4(db,"C")
        assertEquals(1,scalarInt(db,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='RPGOS-5.0-DERIVED-MODIFIERS'"))
        assertEquals(0,scalarInt(db,"SELECT COUNT(*) FROM modifiers"))
        assertEquals(legacy,scalarDouble(db,"SELECT current_value FROM character_stats WHERE entity_uid='P'"),0.0)
        assertEquals(base,scalarDouble(db,"SELECT base_value FROM player_stats WHERE stat_uid='S'"),0.0)
    }

    @Test fun modifierAndSourceStatePersistAcrossReopen(){
        val m=mod("M",ModifierLifecycle.INJURY,-40.0,10,20)
        open().use{db->seed(db); ModifierStore(db,"C").save(m)}
        open().use{db->
            MigrationManager().ensureV4(db,"C")
            val store=ModifierStore(db,"C")
            assertEquals(m,store.modifiers("P").single())
            assertTrue(store.modifiers("OTHER").isEmpty())
            assertEquals(1,store.setSourceActive("P","TEST","SRC-M",false))
            assertFalse(store.modifiers("P").single().sourceActive)
            val r=DerivedValueResolver().resolve(requestFromDb(db,100)).resolvedStats.single()
            assertEquals(100.0,r.effectiveValue,0.0)
        }
    }

    @Test fun duplicateCampaignMismatchMissingTargetLifetimeAndReservedTargetFail()=open().use{db->
        seed(db); val store=ModifierStore(db,"C"); store.save(mod("M"))
        expectFailure{store.save(mod("M"))}
        expectFailure{store.save(mod("X").copy(campaignId="OTHER"))}
        expectFailure{store.save(mod("Y").copy(targetDefinitionUid="MISSING"))}
        expectFailure{mod("BAD",validFrom=20,validUntil=10)}
        expectFailure{store.save(mod("LEG").copy(targetDefinitionUid=LegacyCompatibilityIdentity.statUidForKey("stat")))}
        assertEquals(1,store.modifiers("P").size)
    }

    @Test fun injuryEquipmentTemporaryRemovalNeverRewriteBaseAcrossReopen(){
        open().use{db->
            seed(db); val store=ModifierStore(db,"C")
            store.save(mod("INJ",ModifierLifecycle.INJURY,-40.0)); store.save(mod("EQ",ModifierLifecycle.EQUIPMENT,25.0)); store.save(mod("TMP",ModifierLifecycle.TEMPORARY,10.0,validUntil=150))
            assertEquals(95.0,DerivedValueResolver().resolve(requestFromDb(db,100)).resolvedStats.single().effectiveValue,0.0)
            assertEquals(100.0,scalarDouble(db,"SELECT base_value FROM player_stats WHERE stat_uid='S'"),0.0)
            store.remove("P","INJ"); store.remove("P","EQ"); store.setActive("P","TMP",false)
            assertEquals(100.0,DerivedValueResolver().resolve(requestFromDb(db,100)).resolvedStats.single().effectiveValue,0.0)
            assertEquals(100.0,scalarDouble(db,"SELECT base_value FROM player_stats WHERE stat_uid='S'"),0.0)
        }
        open().use{db->MigrationManager().ensureV4(db,"C"); assertEquals(100.0,scalarDouble(db,"SELECT base_value FROM player_stats WHERE stat_uid='S'"),0.0)}
    }

    @Test fun thousandPersistedModifiersNoTruncation()=open().use{db->
        seed(db); val store=ModifierStore(db,"C")
        db.beginTransaction(); try{ for(i in 0 until 1005) store.save(mod("M%04d".format(i),value=1.0)); db.setTransactionSuccessful() }finally{db.endTransaction()}
        assertEquals(1005,store.modifiers("P").size)
        assertEquals(1105.0,DerivedValueResolver().resolve(requestFromDb(db,100)).resolvedStats.single().effectiveValue,0.0)
    }

    @Test fun campaignAndPlayerIsolation()=open().use{db->
        MigrationManager().ensureV4(db,"A"); MigrationManager().ensureV4(db,"B")
        val s=StatResourceStore(db,"A"); s.registerStatDefinitions("W",listOf(statDef())); s.savePlayerStat(PlayerStat("A","P1","S",100.0)); s.savePlayerStat(PlayerStat("A","P2","S",200.0))
        ModifierStore(db,"A").save(mod("M",value=10.0).copy(campaignId="A",characterUid="P1"))
        assertEquals(1,ModifierStore(db,"A").modifiers("P1").size); assertEquals(0,ModifierStore(db,"A").modifiers("P2").size); assertEquals(0,ModifierStore(db,"B").modifiers("P1").size)
    }

    @Test fun mappedLegacyResolvesOnceAndUnmappedAmbiguityFailsBeforeResolver()=open().use{db->
        db.execSQL("CREATE TABLE character_stats(entity_uid TEXT,stat_key TEXT,current_value REAL)"); db.execSQL("INSERT INTO character_stats VALUES('P','strength',10.0)")
        MigrationManager().ensureV4(db,"C"); val s=StatResourceStore(db,"C"); val def=StatDefinition("S","strength","generic",worldPackUid="W"); s.registerStatDefinitions("W",listOf(def))
        expectState{ s.playerStats("P") }
        val alias=LegacyStatAlias("C",LegacyCompatibilityIdentity.statUidForKey("strength"),"S","W",1,"map"); s.registerLegacyStatAlias(alias)
        ModifierStore(db,"C").save(mod("BONUS",value=5.0))
        val stats=s.playerStats("P"); assertEquals(1,stats.size); assertEquals("S",stats.single().statUid)
        val result=DerivedValueResolver().resolve(DerivedResolutionRequest("C","P",100,s.statDefinitions(),emptyList(),stats,emptyList(),ModifierStore(db,"C").modifiers("P"),legacyStatAliases=listOf(alias)))
        assertEquals(15.0,result.resolvedStats.single().effectiveValue,0.0)
    }

    @Test fun resourceResolverNeverRegeneratesOrClampsStoredCurrent()=open().use{db->
        MigrationManager().ensureV4(db,"C"); val s=StatResourceStore(db,"C"); val def=ResourceDefinition("R","flux","resource",maxRuleUid="MAX",regenerationRuleUid="REGEN",worldPackUid="W")
        s.registerResourceDefinitions("W",listOf(def)); s.savePlayerResource(PlayerResource("C","P","R",150.0))
        val provider=object:DerivedRuleProvider{override val providerUid="TEST"; override fun descriptor(ruleUid:String)=when(ruleUid){"MAX"->DerivedRuleDescriptor("MAX",1);"REGEN"->DerivedRuleDescriptor("REGEN",1);else->null}; override fun evaluate(descriptor:DerivedRuleDescriptor,context:DerivedRuleContext)=if(descriptor.ruleUid=="MAX")100.0 else 3.5}
        val before=scalarDouble(db,"SELECT current_value FROM player_resources WHERE resource_uid='R'")
        val req=DerivedResolutionRequest("C","P",100,emptyList(),s.resourceDefinitions(),emptyList(),s.playerResources("P"),emptyList(),mapOf("MAX" to 1L,"REGEN" to 1L))
        val r=DerivedValueResolver(provider).resolve(req).resolvedResources.single()
        assertEquals(150.0,r.currentValueObserved,0.0); assertEquals(100.0,r.maximumValue!!,0.0); assertEquals(3.5,r.regenerationRate!!,0.0); assertTrue(r.diagnostics.any{it.code=="RESOURCE_CURRENT_ABOVE_DERIVED_MAX"})
        assertEquals(before,scalarDouble(db,"SELECT current_value FROM player_resources WHERE resource_uid='R'"),0.0)
    }

    @Test fun sqliteIntegrityAndForeignKeyChecksClean()=open().use{db->
        db.execSQL("PRAGMA foreign_keys=ON"); seed(db); ModifierStore(db,"C").save(mod("M"))
        db.rawQuery("PRAGMA integrity_check",null).use{c->assertTrue(c.moveToFirst());assertEquals("ok",c.getString(0))}
        db.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}
    }

    private fun seed(db:SQLiteDatabase){MigrationManager().ensureV4(db,"C");val s=StatResourceStore(db,"C");s.registerStatDefinitions("W",listOf(statDef()));s.savePlayerStat(PlayerStat("C","P","S",100.0))}
    private fun requestFromDb(db:SQLiteDatabase,epoch:Long):DerivedResolutionRequest{val s=StatResourceStore(db,"C");return DerivedResolutionRequest("C","P",epoch,s.statDefinitions(),s.resourceDefinitions(),s.playerStats("P"),s.playerResources("P"),ModifierStore(db,"C").modifiers("P"))}
    private fun statDef()=StatDefinition("S","stat","generic",worldPackUid="W")
    private fun mod(uid:String,lifecycle:ModifierLifecycle=ModifierLifecycle.PERMANENT,value:Double=1.0,validFrom:Long?=null,validUntil:Long?=null)=Modifier(modifierUid=uid,campaignId="C",characterUid="P",targetDefinitionUid="S",targetKind=ModifierTargetKind.STAT_EFFECTIVE,lifecycle=lifecycle,operation=ModifierOperation.ADD_FLAT,value=value,sourceType="TEST",sourceUid="SRC-$uid",validFrom=validFrom,validUntil=validUntil,provenance="test")
    private fun open()=SQLiteDatabase.openOrCreateDatabase(dbFile,null)
    private fun scalarDouble(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{c->require(c.moveToFirst());c.getDouble(0)}
    private fun scalarInt(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{c->require(c.moveToFirst());c.getInt(0)}
    private fun expectFailure(block:()->Unit){try{block();fail("Expected failure")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    private fun expectState(block:()->Unit){try{block();fail("Expected IllegalStateException")}catch(_:IllegalStateException){}}
}
