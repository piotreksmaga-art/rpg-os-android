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
@Config(sdk = [34])
class ProgressionProfilePersistenceTest {
    private lateinit var dbFile: File
    @Before fun setUp(){ dbFile=File.createTempFile("rpgos-phase6-",".db"); dbFile.delete() }
    @After fun tearDown(){ dbFile.delete() }

    @Test fun fourQuadrantsPersistIndependentlyAcrossReopen(){
        open().use{db-> seed(db); val s=ProgressionProfileStore(db,"C");
            listOf("A" to (0.9 to 0.2),"B" to (0.2 to 0.9),"D" to (0.9 to 0.9),"E" to (0.2 to 0.2)).forEach{(p,v)->s.saveTalent(talent(p,v.first));s.savePotential(potential(p,v.second))}
        }
        open().use{db->MigrationManager().ensureV6(db,"C");val s=ProgressionProfileStore(db,"C");
            assertEquals(0.9,s.talentProfile("A").entries.single().baseValue,0.0); assertEquals(0.2,s.potentialProfile("A").entries.single().baseValue,0.0)
            assertEquals(0.2,s.talentProfile("B").entries.single().baseValue,0.0); assertEquals(0.9,s.potentialProfile("B").entries.single().baseValue,0.0)
            assertEquals(0.9,s.talentProfile("D").entries.single().baseValue,0.0); assertEquals(0.9,s.potentialProfile("D").entries.single().baseValue,0.0)
            assertEquals(0.2,s.talentProfile("E").entries.single().baseValue,0.0); assertEquals(0.2,s.potentialProfile("E").entries.single().baseValue,0.0)
        }
    }

    @Test fun talentAndPotentialUpdatesNeverCrossWrite(){ open().use{db->seed(db);val s=ProgressionProfileStore(db,"C");s.saveTalent(talent("P",0.4));s.savePotential(potential("P",0.7));
        s.saveTalent(talent("P",0.8,2,"talent-update")); assertEquals(0.7,s.potentialProfile("P").entries.single().baseValue,0.0)
        s.savePotential(potential("P",0.3,2,"potential-update")); assertEquals(0.8,s.talentProfile("P").entries.single().baseValue,0.0)
    } }

    @Test fun profileWritesDoNotTouchStatsSkillsResourcesOrModifiers(){ open().use{db->
        db.execSQL("CREATE TABLE character_skills(entity_uid TEXT,skill_key TEXT,mastery REAL)")
        db.execSQL("INSERT INTO character_skills VALUES('P','skill',60.0)")
        MigrationManager().ensureV6(db,"C"); val sr=StatResourceStore(db,"C"); sr.registerStatDefinitions("W",listOf(StatDefinition("S","stat","generic",worldPackUid="W"))); sr.savePlayerStat(PlayerStat("C","P","S",100.0))
        val s=ProgressionProfileStore(db,"C"); s.registerDomains("W",listOf(domain())); s.saveTalent(talent("P",0.9)); s.savePotential(potential("P",0.9))
        assertEquals(100.0,scalarDouble(db,"SELECT base_value FROM player_stats WHERE stat_uid='S'"),0.0); assertEquals(60.0,scalarDouble(db,"SELECT mastery FROM character_skills WHERE entity_uid='P'"),0.0); assertEquals(0,scalarInt(db,"SELECT COUNT(*) FROM modifiers"))
    } }

    @Test fun temporaryPhase5EffectsCannotPersistProfileChanges(){ open().use{db->
        MigrationManager().ensureV6(db,"C"); val s=ProgressionProfileStore(db,"C"); s.registerDomains("W",listOf(domain())); s.saveTalent(talent("P",0.5)); s.savePotential(potential("P",0.6))
        val sr=StatResourceStore(db,"C"); sr.registerStatDefinitions("W",listOf(StatDefinition("S","context","generic",worldPackUid="W"))); sr.savePlayerStat(PlayerStat("C","P","S",1.0))
        ModifierStore(db,"C").save(Modifier("M","C","P","S",ModifierTargetKind.STAT_EFFECTIVE,ModifierLifecycle.TEMPORARY,ModifierOperation.ADD_PERCENT,0.2,sourceType="TEST",sourceUid="BUFF",validUntil=100,provenance="temporary"))
        DerivedValueResolver().resolve(DerivedResolutionRequest("C","P",50,sr.statDefinitions(),emptyList(),sr.playerStats("P"),emptyList(),ModifierStore(db,"C").modifiers("P")))
        assertEquals(0.5,s.talentProfile("P").entries.single().baseValue,0.0); assertEquals(0.6,s.potentialProfile("P").entries.single().baseValue,0.0)
    } }

    @Test fun campaignPlayerDomainAndWorldPackIsolation(){ open().use{db->
        MigrationManager().ensureV6(db,"A"); MigrationManager().ensureV6(db,"B"); val a=ProgressionProfileStore(db,"A"); a.registerDomains("WA",listOf(domain("DA","WA","focus"))); a.registerDomains("WB",listOf(domain("DB","WB","focus")))
        a.saveTalent(TalentEntry("A","P1","DA",1.0,provenance="seed")); a.saveTalent(TalentEntry("A","P2","DB",2.0,provenance="seed"))
        assertEquals(1.0,a.talentProfile("P1").entries.single().baseValue,0.0); assertEquals("DB",a.talentProfile("P2").entries.single().domainUid); assertTrue(ProgressionProfileStore(db,"B").talentProfile("P1").entries.isEmpty())
        expectFailure{a.registerDomains("WB",listOf(domain("DA","WB","hijack")))}
    } }

    @Test fun duplicateUidKeyUnknownDomainCapabilitiesAndInvalidNumbersFailLoud(){ open().use{db->
        MigrationManager().ensureV6(db,"C"); val s=ProgressionProfileStore(db,"C"); s.registerDomains("W",listOf(domain()))
        expectFailure{s.registerDomains("W",listOf(domain().copy(displayName="changed")))}; expectFailure{s.registerDomains("W",listOf(domain("D2","W","focus")))}
        expectFailure{s.saveTalent(talent("P",Double.NaN))}; expectFailure{s.savePotential(potential("P",Double.POSITIVE_INFINITY))}; expectFailure{s.saveTalent(talent("P",-1.0))}; expectFailure{s.saveTalent(TalentEntry("C","P","MISSING",1.0,provenance="x"))}
        s.registerDomains("W",listOf(domain("PONLY","W","ponly").copy(appliesToTalent=false,appliesToPotential=true))); expectFailure{s.saveTalent(TalentEntry("C","P","PONLY",1.0,provenance="x"))}
    } }

    @Test fun migrationIsAdditiveIdempotentAndOldCampaignStateSurvives(){ open().use{db->
        db.execSQL("CREATE TABLE character_stats(entity_uid TEXT,stat_key TEXT,current_value REAL)"); db.execSQL("INSERT INTO character_stats VALUES('P','legacy',77.0)")
        MigrationManager().ensureV4(db,"C"); val sr=StatResourceStore(db,"C"); sr.registerStatDefinitions("W",listOf(StatDefinition("S","stat","generic",worldPackUid="W"))); sr.savePlayerStat(PlayerStat("C","P","S",100.0)); ModifierStore(db,"C").save(Modifier("M","C","P","S",ModifierTargetKind.STAT_EFFECTIVE,ModifierLifecycle.INJURY,ModifierOperation.ADD_FLAT,-10.0,sourceType="T",sourceUid="SRC",provenance="x"))
        MigrationManager().ensureV6(db,"C"); MigrationManager().ensureV6(db,"C")
        assertEquals(1,scalarInt(db,"SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id='RPGOS-6.0-TALENT-POTENTIAL'")); assertEquals(0,scalarInt(db,"SELECT COUNT(*) FROM talent_profile_entries")); assertEquals(0,scalarInt(db,"SELECT COUNT(*) FROM potential_profile_entries")); assertEquals(100.0,scalarDouble(db,"SELECT base_value FROM player_stats WHERE stat_uid='S'"),0.0); assertEquals(1,scalarInt(db,"SELECT COUNT(*) FROM modifiers")); assertEquals(77.0,scalarDouble(db,"SELECT current_value FROM character_stats WHERE entity_uid='P'"),0.0)
    } }

    @Test fun ambiguousLegacyEvidenceStaysUnresolvedUntilExplicitMapping(){ open().use{db->seed(db); val s=ProgressionProfileStore(db,"C"); val e=LegacyProgressionEvidence("E","C","P","gifted","yes","LEGACY","row-1",1,"preserved")
        s.preserveLegacyEvidence(e); assertEquals(1,s.unresolvedLegacyEvidence("P").size); assertTrue(s.talentProfile("P").entries.isEmpty()); assertTrue(s.potentialProfile("P").entries.isEmpty()); expectFailure{s.materializeMappedEvidence("E",0.8,provenance="bad")}
        s.registerLegacyMapping(LegacyProgressionMapping("C","E",ProgressionProfileAxis.TALENT,"D",worldPackUid="W",mappingVersion=1,provenance="explicit-pack-map")); s.materializeMappedEvidence("E",0.8,provenance="explicit migration")
        assertEquals(0.8,s.talentProfile("P").entries.single().baseValue,0.0); assertTrue(s.unresolvedLegacyEvidence("P").isEmpty())
    } }

    @Test fun hundredAndThousandDomainsAndProfilesPersistWithoutTruncation(){ open().use{db->
        MigrationManager().ensureV6(db,"C");val s=ProgressionProfileStore(db,"C"); val defs=(0 until 1005).map{i->domain("D%04d".format(i),"W","k%04d".format(i))}; s.registerDomains("W",defs); db.beginTransaction();try{defs.forEachIndexed{i,d->s.saveTalent(TalentEntry("C","P",d.domainUid,i.toDouble(),provenance="seed"));s.savePotential(PotentialEntry("C","P",d.domainUid,"growth",i.toDouble(),provenance="seed"))};db.setTransactionSuccessful()}finally{db.endTransaction()}; assertEquals(1005,s.talentProfile("P").entries.size);assertEquals(1005,s.potentialProfile("P").entries.size)
    } }

    @Test fun sqliteIntegrityAndForeignKeysClean(){ open().use{db->db.execSQL("PRAGMA foreign_keys=ON");seed(db);val s=ProgressionProfileStore(db,"C");s.saveTalent(talent("P",1.0));s.savePotential(potential("P",2.0));db.rawQuery("PRAGMA integrity_check",null).use{c->assertTrue(c.moveToFirst());assertEquals("ok",c.getString(0))};db.rawQuery("PRAGMA foreign_key_check",null).use{c->assertFalse(c.moveToFirst())}} }

    private fun seed(db:SQLiteDatabase){MigrationManager().ensureV6(db,"C");ProgressionProfileStore(db,"C").registerDomains("W",listOf(domain()))}
    private fun domain(uid:String="D",wp:String="W",key:String="focus")=ProgressionDomainDefinition(uid,wp,key,"Focus","generic",definitionVersion=1,provenance="world-pack-seed")
    private fun talent(p:String,v:Double,ver:Long=1,prov:String="creation")=TalentEntry("C",p,"D",v,ver,prov)
    private fun potential(p:String,v:Double,ver:Long=1,prov:String="creation")=PotentialEntry("C",p,"D","growth-scale",v,ver,prov)
    private fun open()=SQLiteDatabase.openOrCreateDatabase(dbFile,null)
    private fun scalarDouble(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{c->require(c.moveToFirst());c.getDouble(0)}
    private fun scalarInt(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{c->require(c.moveToFirst());c.getInt(0)}
    private fun expectFailure(block:()->Unit){try{block();fail("Expected failure")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
}
