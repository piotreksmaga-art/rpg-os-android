package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase63RuleSourceImportTest {
    private val binding=WorldPackRuleBinding("PACK","2")
    @Test fun optionalPackDefinitionsAreExplicitVersionedAndReadOnly()=SQLiteDatabase.create(null).use { db->
        assertEquals(CoreLatentWorldRules.initial(),Phase63RuleSourceImport.read(db,binding).localRules)
        db.execSQL("CREATE TABLE ${Phase63RuleSourceImport.TABLE}(definition_uid TEXT PRIMARY KEY,contract_version INTEGER,definition_kind TEXT,canonical_value TEXT)")
        assertTrue(Phase63RuleSourceImport.read(db,binding).localRules.isEmpty())
        val rule=WorldMacroRegionRule("PACK:SEA",2,1,mapOf("SEA" to 1),ActionDuration(100000))
        db.execSQL("INSERT INTO ${Phase63RuleSourceImport.TABLE} VALUES(?,2,'MACRO_REGION',?)",arrayOf(rule.uid,Phase63MacroRegionCodec.encode(rule).toString()))
        db.execSQL("PRAGMA query_only=ON")
        assertEquals(listOf(rule),Phase63RuleSourceImport.read(db,binding).macroRules)
        assertThrows(IllegalArgumentException::class.java) { Phase63RuleSourceImport.read(db,binding.copy(sourceKind=CampaignRuleSourceKind.CAMPAIGN_NATIVE)) }
        Unit
    }
    @Test fun invalidContractNeverFallsBackToInventedRules()=SQLiteDatabase.create(null).use { db->
        db.execSQL("CREATE TABLE ${Phase63RuleSourceImport.TABLE}(definition_uid TEXT PRIMARY KEY,contract_version INTEGER,definition_kind TEXT,canonical_value TEXT)")
        db.execSQL("INSERT INTO ${Phase63RuleSourceImport.TABLE} VALUES('BAD',1,'MACRO_REGION','{}')")
        assertThrows(IllegalArgumentException::class.java) { Phase63RuleSourceImport.read(db,binding) }
        Unit
    }
}
