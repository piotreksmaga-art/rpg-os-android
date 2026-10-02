package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase62NpcAuthorityCompletionTest {
    private val n=DomainRef("NPC","N")
    private fun setup(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        GroupATransactionTestFixtures.setupFinance(db)
    }
    private val seed=MechanicalActorSeed(n,MechanicalActorKind.NPC,"T","S","TEST",mapOf("DEFENCE" to 20L,"MEDICINE" to 5L),
        listOf(MechanicalResource("HEALTH",100,100),MechanicalResource("STAMINA",20,20)),setOf("DUTY"))
    @Test fun additiveActivityDefinitionsPreserveStateDigestAndHaveTheirOwnAuthorityFingerprint()=SQLiteDatabase.create(null).use { db->
        db.execSQL("CREATE TABLE rpgos_schema_migrations(migration_id TEXT PRIMARY KEY,applied_at INTEGER,notes TEXT)")
        val historical=AuthoritativeStateDigest.compute(db)
        Phase62ActivitySchema.ensureReady(db)
        assertEquals(historical,AuthoritativeStateDigest.compute(db))
        assertTrue(RuntimeTruthLayerRegistry.requireClassifiedTable(Phase62ActivitySchema.TABLE).isMechanicsDefinitionAuthority)
        val emptyDefinitions=TableDigest.compute(db,Phase62ActivitySchema.TABLE)
        val rule=NpcActivityContract("TRAIN","RULE",1,ActionDuration(1000),"EFFORT")
        db.execSQL("INSERT INTO ${Phase62ActivitySchema.TABLE} VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("C1",rule.ruleUid,rule.version,rule.capabilityUid,NpcActivityContractCodec.encode(rule),rule.fingerprint,1,"PACK"))
        assertEquals(historical,AuthoritativeStateDigest.compute(db))
        assertNotEquals(emptyDefinitions,TableDigest.compute(db,Phase62ActivitySchema.TABLE))
        val populated=TableDigest.compute(db,Phase62ActivitySchema.TABLE)
        Phase62ActivitySchema.ensureReady(db)
        assertEquals(populated,TableDigest.compute(db,Phase62ActivitySchema.TABLE))
    }
    @Test fun legacyCompletionRequiresExplicitAdminPreservesComponentsAndIsIdempotent()=SQLiteDatabase.create(null).use { db->
        setup(db);val owner=MechanicalActorStateStore(db,"C1")
        assertThrows(IllegalArgumentException::class.java){owner.completeLegacyActor(seed)}
        withAdministrativeMutationAuthority(db,"C1") {
            owner.materializeIfMissing(seed)
            db.execSQL("UPDATE mechanical_actor_resources SET current_value=7 WHERE entity_uid='N' AND resource_uid='STAMINA'")
            db.execSQL("DELETE FROM mechanical_actor_attributes WHERE entity_uid='N' AND attribute_uid='MEDICINE'")
            db.execSQL("INSERT INTO mechanical_actor_tracks VALUES('C1','NPC','N','WOUND',4,1)")
            assertTrue(owner.completeLegacyActor(seed))
        }
        val repaired=owner.actor(n)!!
        assertEquals(7L,repaired.resources.single{it.resourceUid=="STAMINA"}.current)
        assertEquals(4L,repaired.conditions.single{it.conditionUid=="WOUND"}.intensity)
        assertEquals(5L,repaired.attributes["MEDICINE"])
        val digest=AuthoritativeStateDigest.compute(db)
        withAdministrativeMutationAuthority(db,"C1"){assertTrue(owner.completeLegacyActor(seed))}
        assertEquals(digest,AuthoritativeStateDigest.compute(db))
        withAdministrativeMutationAuthority(db,"C1"){assertFalse(owner.completeLegacyActor(seed.copy(templateUid="OTHER")))}
    }
    @Test fun latestActivityVersionWinsAndDowngradeCannotReactivateAnOldRule()=SQLiteDatabase.create(null).use { save->SQLiteDatabase.create(null).use { world->
        setup(save);world.execSQL("CREATE TABLE npc_activity_definitions(rule_uid TEXT,rule_version INTEGER,contract_json TEXT)")
        val first=NpcActivityContract("TRAIN","RULE",1,ActionDuration(1000),"EFFORT",learning=NpcLearningRule("SKILL","K","XP",1))
        val second=first.copy(version=2)
        world.execSQL("INSERT INTO npc_activity_definitions VALUES(?,?,?)",arrayOf<Any?>(first.ruleUid,1,NpcActivityContractCodec.encode(first)))
        world.execSQL("INSERT INTO npc_activity_definitions VALUES(?,?,?)",arrayOf<Any?>(second.ruleUid,2,NpcActivityContractCodec.encode(second)))
        withAdministrativeMutationAuthority(save,"C1"){NpcActivityDefinitionImport.importPack(save,world,"C1",WorldPackRuleBinding("W","1"))}
        assertEquals(listOf(second),SqliteNpcActivityContractPort(save).forCapability("C1","TRAIN"))
        world.execSQL("DELETE FROM npc_activity_definitions WHERE rule_version=2")
        withAdministrativeMutationAuthority(save,"C1"){NpcActivityDefinitionImport.importPack(save,world,"C1",WorldPackRuleBinding("W","1"))}
        assertEquals(listOf(second),SqliteNpcActivityContractPort(save).forCapability("C1","TRAIN"))
        world.execSQL("UPDATE npc_activity_definitions SET contract_json=?",arrayOf(NpcActivityContractCodec.encode(first.copy(effortUnits=10))))
        assertTrue(runCatching{withAdministrativeMutationAuthority(save,"C1"){NpcActivityDefinitionImport.importPack(save,world,"C1",WorldPackRuleBinding("W","1"))}}.isFailure)
    }}
    @Test fun importedDutyUsesExistingRolesAndCanonicalDeadlineAndCannotReassignOnReopen()=SQLiteDatabase.create(null).use { save->SQLiteDatabase.create(null).use { world->
        setup(save)
        val rule=NpcDutyRule("D",1,"ORG","ROLE","DEADLINE",WorldTimeTick(5000),"ASSIGN")
        val contract=NpcActivityContract("DUTY","WORLD:DUTY",1,ActionDuration(1000),"EFFORT",duty=rule)
        world.execSQL("CREATE TABLE npc_activity_definitions(rule_uid TEXT,rule_version INTEGER,contract_json TEXT)")
        world.execSQL("INSERT INTO npc_activity_definitions VALUES(?,?,?)",arrayOf<Any?>(contract.ruleUid,1,NpcActivityContractCodec.encode(contract)))
        world.execSQL("CREATE TABLE npc_duty_assignments(actor_kind_uid TEXT,actor_uid TEXT,rule_uid TEXT,rule_version INTEGER,assignment_uid TEXT)")
        world.execSQL("INSERT INTO npc_duty_assignments VALUES('NPC','N','WORLD:DUTY',1,'A')")
        withAdministrativeMutationAuthority(save,"C1") {
            MechanicalActorStateStore(save,"C1").materializeIfMissing(seed)
            val access=AccessAuthorityStore(save,"C1")
            val identity=TurnTransactionIdentity("C1","INITIAL","INITIAL","INITIAL")
            for((kind,value) in listOf(AccessBindingKind.ROLE to "ROLE",AccessBindingKind.ORGANIZATION to "ORG"))
                access.apply(identity,kind.name,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,kind.name,"NPC","N",kind.name,value,validFromOrder=0),0)
            NpcActivityDefinitionImport.importPack(save,world,"C1",WorldPackRuleBinding("W","1"))
            NpcDutyAssignmentImport.importPack(save,world,"C1",WorldPackRuleBinding("W","1"))
        }
        val port=SqliteNpcDutyAssignmentPort(save,"C1")
        assertTrue(port.admitted("C1",n,rule,WorldTimeTick(0)))
        assertFalse(port.admitted("OTHER",n,rule,WorldTimeTick(0)))
        assertFalse(port.admitted("C1",n,rule.copy(due=WorldTimeTick(6000)),WorldTimeTick(0)))
        assertFalse(port.admitted("C1",n,rule,WorldTimeTick(5001)))
        val digest=AuthoritativeStateDigest.compute(save)
        withAdministrativeMutationAuthority(save,"C1"){NpcDutyAssignmentImport.importPack(save,world,"C1",WorldPackRuleBinding("W","1"))}
        assertEquals(digest,AuthoritativeStateDigest.compute(save))
        withAdministrativeMutationAuthority(save,"C1") {
            AccessAuthorityStore(save,"C1").apply(TurnTransactionIdentity("C1","REVOKE","REVOKE","REVOKE"),"REVOKE",
                AccessAuthorityChange(AccessOperation.REVOKE_BINDING,"REVOKE","NPC","N",AccessBindingKind.ROLE.name,"ROLE",validFromOrder=1),1)
            NpcDutyAssignmentImport.importPack(save,world,"C1",WorldPackRuleBinding("W","1"))
        }
        assertFalse(port.admitted("C1",n,rule,WorldTimeTick(0)))
        val updated=contract.copy(version=contract.version+1,duty=rule.copy(version=rule.version+1))
        world.execSQL("UPDATE npc_activity_definitions SET rule_version=?,contract_json=?",arrayOf<Any?>(updated.version,NpcActivityContractCodec.encode(updated)))
        withAdministrativeMutationAuthority(save,"C1") {
            NpcActivityDefinitionImport.importPack(save,world,"C1",WorldPackRuleBinding("W","2"))
            NpcDutyAssignmentImport.importPack(save,world,"C1",WorldPackRuleBinding("W","2"))
        }
        // The old assignment still names v1; importing the new pack must not revive it.
        assertFalse(port.admitted("C1",n,updated.duty!!,WorldTimeTick(0)))
    }}

    @Test fun actorBindingImportsARegisteredRuleThroughExistingOwnersAndPreservesProgressOnReopen()=SQLiteDatabase.create(null).use { save->SQLiteDatabase.create(null).use { world->
        setup(save)
        val rule=NpcActivityContract("TRAIN","WORLD:TRAIN",1,ActionDuration(1000),"TRAINING",learning=NpcLearningRule("SKILL","K","XP",2))
        world.execSQL("CREATE TABLE npc_activity_definitions(rule_uid TEXT,rule_version INTEGER,contract_json TEXT)")
        world.execSQL("INSERT INTO npc_activity_definitions VALUES(?,?,?)",arrayOf<Any?>(rule.ruleUid,1,NpcActivityContractCodec.encode(rule)))
        world.execSQL("CREATE TABLE npc_activity_actor_bindings(actor_kind_uid TEXT,actor_uid TEXT,rule_uid TEXT,rule_version INTEGER,initial_mastery REAL)")
        world.execSQL("INSERT INTO npc_activity_actor_bindings VALUES('NPC','N','WORLD:TRAIN',1,5.0)")
        withAdministrativeMutationAuthority(save,"C1") {
            MechanicalActorStateStore(save,"C1").materializeIfMissing(seed)
            SkillStore(save,"C1").registerDefinitions("W",listOf(SkillDefinition("K","W","k","Skill","GENERAL",provenance="PACK")))
            NpcActivityDefinitionImport.importPack(save,world,"C1",WorldPackRuleBinding("W","1"))
            NpcActivityActorImport.importPack(save,world,"C1")
        }
        assertTrue("TRAIN" in MechanicalActorStateStore(save,"C1").actor(n)!!.executableAbilityUids)
        assertEquals(5.0,SkillStore(save,"C1").playerSkills(n.uid).single().baseMastery,0.0)
        val digest=AuthoritativeStateDigest.compute(save)
        withAdministrativeMutationAuthority(save,"C1"){NpcActivityActorImport.importPack(save,world,"C1")}
        assertEquals(digest,AuthoritativeStateDigest.compute(save))
        world.execSQL("UPDATE npc_activity_actor_bindings SET actor_uid='UNKNOWN'")
        assertTrue(runCatching{withAdministrativeMutationAuthority(save,"C1"){NpcActivityActorImport.importPack(save,world,"C1")}}.isFailure)
        assertEquals(digest,AuthoritativeStateDigest.compute(save))
    }}
}
