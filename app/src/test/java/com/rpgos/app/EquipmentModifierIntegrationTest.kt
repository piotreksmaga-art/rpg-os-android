package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class EquipmentModifierIntegrationTest {
    private val rules=object:DerivedRuleProvider{
        override val providerUid="equipment-test-rules"
        override fun descriptor(ruleUid:String)=if(ruleUid=="regen")DerivedRuleDescriptor("regen",1) else null
        override fun evaluate(descriptor:DerivedRuleDescriptor,context:DerivedRuleContext)=2.0
    }

    @Test fun equipmentLifecycleUsesPhase5ResolverAndNeverRewritesAuthority(){
        val f=File.createTempFile("equip-mod-",".db");f.delete()
        try{SQLiteDatabase.openOrCreateDatabase(f,null).use{d->
            CurrentSchema.ensure(d,"C")
            val sr=StatResourceStore(d,"C")
            val statDef=StatDefinition("S","s","generic",worldPackUid="W")
            val resDef=ResourceDefinition("R","r","generic",maxValue=100.0,regenerationRuleUid="regen",worldPackUid="W")
            sr.registerStatDefinitions("W",listOf(statDef));sr.registerResourceDefinitions("W",listOf(resDef))
            sr.savePlayerStat(PlayerStat("C","P","S",100.0));sr.savePlayerResource(PlayerResource("C","P","R",100.0))

            d.execSQL("INSERT INTO skill_definitions_v2(skill_uid,world_pack_uid,skill_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance) VALUES('K','W','k','K','generic',0,NULL,'ACTIVE',1,'pack')")
            d.execSQL("INSERT INTO player_skills_v2(campaign_id,character_uid,skill_uid,base_mastery,entry_version,provenance) VALUES('C','P','K',40,1,'player')")
            d.execSQL("INSERT INTO technique_definitions_v2(technique_uid,world_pack_uid,technique_key,display_name,category,min_mastery,max_mastery,definition_status,definition_version,provenance) VALUES('T','W','t','T','generic',0,NULL,'ACTIVE',1,'pack')")
            d.execSQL("INSERT INTO player_techniques_v2(campaign_id,character_uid,technique_uid,base_mastery,usage_count,success_count,failure_count,is_equipped,entry_version,provenance) VALUES('C','P','T',50,0,0,0,1,1,'player')")
            d.execSQL("INSERT INTO progression_domain_definitions(domain_uid,world_pack_uid,domain_key,display_name,category,parent_domain_uid,applies_to_talent,applies_to_potential,definition_version,provenance) VALUES('D','W','d','D','generic',NULL,1,1,1,'pack')")
            d.execSQL("INSERT INTO talent_profile_entries(campaign_id,character_uid,domain_uid,base_value,entry_version,provenance) VALUES('C','P','D',3,1,'player')")
            d.execSQL("INSERT INTO potential_profile_entries(campaign_id,character_uid,domain_uid,dimension_uid,base_value,entry_version,provenance) VALUES('C','P','D','ceiling',9,1,'player')")

            val inv=InventoryStore(d,"C");inv.registerDefinitions("W",listOf(ItemDefinition("ID","W","id","ID",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="pack")));inv.createInstance(ItemInstance("C","X","ID",provenance="instance"));inv.addUnique("P","X","possessed")
            val eq=EquipmentStore(d,"C");eq.registerSlots("W",listOf(EquipmentSlotDefinition("SL","W","sl","SL",provenance="pack")));eq.registerCompatibilityRules("W",listOf(EquipmentCompatibilityRule("RULE","W","ID",listOf("SL"),provenance="pack")))
            assertTrue(eq.equipment("P").isEmpty()) // Technique is_equipped is not physical Equipment.

            val mods=listOf(
                mod("MS","S",ModifierTargetKind.STAT_EFFECTIVE,20.0),
                mod("MM","R",ModifierTargetKind.RESOURCE_MAXIMUM,100.0),
                mod("MR","R",ModifierTargetKind.RESOURCE_REGENERATION,3.0),
                mod("MK","K",ModifierTargetKind.SKILL_EFFECTIVE,5.0),
                mod("MT","T",ModifierTargetKind.TECHNIQUE_EFFECTIVE,7.0)
            )
            eq.registerEquipmentModifiers("P","X",mods)
            assertTrue(ModifierStore(d,"C").modifiers("P").none{it.sourceActive})
            val beforeAuthority=authoritySnapshot(d)
            val before=resolve(d)
            assertEquals(100.0,before.resolvedStats.single().effectiveValue,0.0)
            assertEquals(100.0,before.resolvedResources.single().maximumValue!!,0.0)
            assertEquals(100.0,before.resolvedResources.single().currentValueObserved,0.0)
            assertEquals(2.0,before.resolvedResources.single().regenerationRate!!,0.0)
            assertEquals(40.0,before.resolvedSkills.single().effectiveMastery,0.0)
            assertEquals(50.0,before.resolvedTechniques.single().effectiveMastery,0.0)

            eq.equip("P","X","RULE",listOf("SL"),"E","equip")
            assertTrue(ModifierStore(d,"C").modifiers("P").all{it.sourceActive})
            val active=resolve(d)
            assertEquals(120.0,active.resolvedStats.single().effectiveValue,0.0)
            assertEquals(200.0,active.resolvedResources.single().maximumValue!!,0.0)
            assertEquals(100.0,active.resolvedResources.single().currentValueObserved,0.0)
            assertEquals(5.0,active.resolvedResources.single().regenerationRate!!,0.0)
            assertEquals(45.0,active.resolvedSkills.single().effectiveMastery,0.0)
            assertEquals(57.0,active.resolvedTechniques.single().effectiveMastery,0.0)
            assertEquals(beforeAuthority,authoritySnapshot(d))

            eq.unequip("P","E")
            assertTrue(ModifierStore(d,"C").modifiers("P").none{it.sourceActive})
            val after=resolve(d)
            assertEquals(100.0,after.resolvedStats.single().effectiveValue,0.0)
            assertEquals(100.0,after.resolvedResources.single().maximumValue!!,0.0)
            assertEquals(100.0,after.resolvedResources.single().currentValueObserved,0.0)
            assertEquals(2.0,after.resolvedResources.single().regenerationRate!!,0.0)
            assertEquals(40.0,after.resolvedSkills.single().effectiveMastery,0.0)
            assertEquals(50.0,after.resolvedTechniques.single().effectiveMastery,0.0)
            assertEquals(beforeAuthority,authoritySnapshot(d))
        }}finally{f.delete()}
    }

    @Test fun twoInstancesOfSameDefinitionHaveIsolatedModifierSources(){
        val f=File.createTempFile("equip-src-",".db");f.delete()
        try{SQLiteDatabase.openOrCreateDatabase(f,null).use{d->
            CurrentSchema.ensure(d,"C");val sr=StatResourceStore(d,"C");sr.registerStatDefinitions("W",listOf(StatDefinition("S","s","generic",worldPackUid="W")));sr.savePlayerStat(PlayerStat("C","P","S",10.0))
            val inv=InventoryStore(d,"C");inv.registerDefinitions("W",listOf(ItemDefinition("D","W","d","D",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="p")))
            listOf("X","Y").forEach{inv.createInstance(ItemInstance("C",it,"D",provenance="i"));inv.addUnique("P",it,"p")}
            val eq=EquipmentStore(d,"C");eq.registerSlots("W",listOf(EquipmentSlotDefinition("A","W","a","A",provenance="p"),EquipmentSlotDefinition("B","W","b","B",provenance="p")));eq.registerCompatibilityRules("W",listOf(EquipmentCompatibilityRule("RX","W","D",listOf("A"),provenance="p"),EquipmentCompatibilityRule("RY","W","D",listOf("B"),provenance="p")))
            eq.registerEquipmentModifiers("P","X",listOf(mod("MX","S",ModifierTargetKind.STAT_EFFECTIVE,1.0,"X")))
            eq.registerEquipmentModifiers("P","Y",listOf(mod("MY","S",ModifierTargetKind.STAT_EFFECTIVE,2.0,"Y")))
            eq.equip("P","X","RX",listOf("A"),"EX","x")
            val m=ModifierStore(d,"C").modifiers("P").associateBy{it.sourceUid};assertTrue(m.getValue("X").sourceActive);assertFalse(m.getValue("Y").sourceActive)
            eq.unequip("P","EX");assertTrue(ModifierStore(d,"C").modifiers("P").none{it.sourceActive})
        }}finally{f.delete()}
    }

    private fun mod(uid:String,target:String,kind:ModifierTargetKind,value:Double,source:String="X")=Modifier(uid,"C","P",target,kind,ModifierLifecycle.EQUIPMENT,ModifierOperation.ADD_FLAT,value,sourceType=EQUIPMENT_MODIFIER_SOURCE_TYPE,sourceUid=source,sourceActive=true,provenance="equipment")
    private fun resolve(d:SQLiteDatabase):DerivedResolutionResult{
        val sr=StatResourceStore(d,"C")
        val skill=PlayerSkill("C","P","K",40.0,provenance="player")
        val technique=PlayerTechnique("C","P","T",50.0,isEquipped=true,provenance="player")
        return DerivedValueResolver(rules).resolve(DerivedResolutionRequest("C","P",1,sr.statDefinitions("W"),sr.resourceDefinitions("W"),sr.playerStats("P"),sr.playerResources("P"),ModifierStore(d,"C").modifiers("P"),ruleVersions=mapOf("regen" to 1L),skillDefinitions=listOf(SkillDefinition("K","W","k","K","generic",provenance="pack")),playerSkills=listOf(skill),techniqueDefinitions=listOf(TechniqueDefinition("T","W","t","T","generic",provenance="pack")),playerTechniques=listOf(technique)))
    }
    private fun authoritySnapshot(d:SQLiteDatabase):List<String> = listOf(
        scalarText(d,"SELECT base_value||':'||version FROM player_stats WHERE campaign_id='C' AND character_uid='P' AND stat_uid='S'"),
        scalarText(d,"SELECT current_value||':'||version FROM player_resources WHERE campaign_id='C' AND character_uid='P' AND resource_uid='R'"),
        scalarText(d,"SELECT base_mastery||':'||entry_version FROM player_skills_v2 WHERE campaign_id='C' AND character_uid='P' AND skill_uid='K'"),
        scalarText(d,"SELECT base_mastery||':'||is_equipped||':'||entry_version FROM player_techniques_v2 WHERE campaign_id='C' AND character_uid='P' AND technique_uid='T'"),
        scalarText(d,"SELECT base_value||':'||entry_version FROM talent_profile_entries WHERE campaign_id='C' AND character_uid='P' AND domain_uid='D'"),
        scalarText(d,"SELECT base_value||':'||entry_version FROM potential_profile_entries WHERE campaign_id='C' AND character_uid='P' AND domain_uid='D' AND dimension_uid='ceiling'")
    )
    private fun scalarText(d:SQLiteDatabase,q:String)=d.rawQuery(q,null).use{c->c.moveToFirst();c.getString(0)}
}
