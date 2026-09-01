package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase48To54RepairPlanTest{
    private val campaign="C-REPAIR"
    private val attacker=DomainRef("PLAYER","P1")
    private val defender=DomainRef("NPC","N1")

    @Test fun spatialContractSupportsAllRepresentationsAndFailsClosedOnUnknownSpace(){
        val snapshot=snapshot(actors=listOf(actor(attacker,setOf("STRIKE")),actor(defender,setOf("PARRY"))))
        val resolver=UniversalCombatSpatialResolver()
        val representations=listOf<Pair<CombatPosition,CombatPosition>>(
            CombatPosition.Exact(0,0) to CombatPosition.Exact(1_000,0),
            CombatPosition.Grid("G",0,0) to CombatPosition.Grid("G",1,0),
            CombatPosition.Zone("Z") to CombatPosition.Zone("Z"),
            CombatPosition.RangeBand(attacker,"NEAR",0) to CombatPosition.RangeBand(attacker,"FAR",1),
            CombatPosition.Formation("F","LEFT") to CombatPosition.Formation("F","RIGHT")
        )
        representations.forEach{(left,right)->
            assertTrue(resolver.evaluate(CombatSpatialQuery(attacker,defender,20_000),CombatSpatialState(mapOf(attacker to left,defender to right)),snapshot) is CombatSpatialResult.Feasible)
        }
        assertEquals("ACTOR_POSITION_UNKNOWN",(resolver.evaluate(CombatSpatialQuery(attacker,defender,2_000),CombatSpatialState(emptyMap()),snapshot) as CombatSpatialResult.Rejected).reasonUid)
        val intent=CombatIntent("I",campaign,attacker,defender,"STRIKE",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"DISABLE",5)
        assertEquals("ACTOR_POSITION_UNKNOWN",(UniversalCombatEngine().resolve(UniversalCombatRequest(intent,snapshot,CombatAbilityContract("STRIKE"))) as CombatResolution.Rejected).reasonUid)
    }

    @Test fun reactionRequiresCapabilityPerceptionTimeAndResource(){
        val intent=CombatIntent("I",campaign,attacker,defender,"STRIKE",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"DISABLE",5)
        val request=CombatReactionRequest(defender,"PARRY","FOCUS",2)
        val perception=CombatPerceptionEvidence(defender,attacker,"SEEN",9_000,5)
        fun evaluate(
            abilities:Set<String> = setOf("PARRY"),
            evidence:List<CombatPerceptionEvidence> = listOf(perception),
            resource:Long=3,
            timing:Map<String,Long> = emptyMap()
        )=CombatReactionGate().evaluate(request,intent,snapshot(
            listOf(actor(attacker,setOf("STRIKE")),actor(defender,abilities,listOf(MechanicalResource("FOCUS",resource,10)))),
            evidence,timing
        ),reactionAtOrder=5)
        assertEquals("REACTION_CAPABILITY_UNAVAILABLE",(evaluate(abilities=emptySet()) as CombatReactionEligibility.Rejected).reasonUid)
        assertEquals("ATTACK_NOT_PERCEIVED",(evaluate(evidence=emptyList()) as CombatReactionEligibility.Rejected).reasonUid)
        assertEquals("REACTION_WINDOW_NOT_OPEN",(evaluate(timing=mapOf("NPC:N1:reaction_available_at:PARRY" to 6)) as CombatReactionEligibility.Rejected).reasonUid)
        assertEquals("REACTION_RESOURCE_INSUFFICIENT",(evaluate(resource=1) as CombatReactionEligibility.Rejected).reasonUid)
        assertTrue(evaluate() is CombatReactionEligibility.Eligible)
    }

    @Test fun combatPipelineIsTimedInteractiveObjectiveAwareAndReplaySafe(){
        val intent=CombatIntent("I",campaign,attacker,defender,"STRIKE",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"BREAK",10)
        val opposing=CombatIntent("O",campaign,defender,attacker,"PARRY",VolitionalActionSource.NPC_DECISION_ENGINE,"PROTECT",10)
        val actors=listOf(
            actor(attacker,setOf("STRIKE"),attributes=mapOf("POWER" to 20_000,"SKILL" to 20_000,"DEFENCE" to 1,"AGILITY" to 1)),
            actor(defender,setOf("PARRY"),attributes=mapOf("POWER" to 1,"SKILL" to 1,"DEFENCE" to 0,"AGILITY" to 0))
        )
        val state=CombatSpatialState(mapOf(attacker to CombatPosition.Exact(0,0),defender to CombatPosition.Exact(1_000,0)))
        val snap=snapshot(actors,timing=mapOf("ability:STRIKE:prepare_ticks" to 2,"ability:STRIKE:execute_ticks" to 3,"ability:STRIKE:recovery_ticks" to 4))
        val schedule=DeterministicCombatScheduler().schedule(intent,snap)
        assertEquals(listOf(CombatActionPhase.DECLARE,CombatActionPhase.PREPARE,CombatActionPhase.ACTION_COMMIT,CombatActionPhase.EXECUTE,CombatActionPhase.IMPACT,CombatActionPhase.RECOVERY),schedule.windows.map{it.phase})
        val interaction=CombatActionInteraction("CLASH",CombatInteractionKind.INTERCEPTION,"I","O")
        val request=UniversalCombatRequest(intent,snap,CombatAbilityContract("STRIKE",effectKinds=listOf(UniversalMechanicalEffectKind.FORMATION)),state,
            opposingIntent=opposing,interaction=interaction,objective=CombatObjective("BREAK",CombatObjectiveKind.BREAK_FORMATION,1))
        val first=UniversalCombatEngine().resolve(request) as CombatResolution.Resolved
        val replay=UniversalCombatEngine().resolve(request) as CombatResolution.Resolved
        assertEquals("OBJECTIVE_SATISFIED",first.outcomeUid)
        assertEquals(first,replay)
        assertTrue(first.evidence.randomDraws.isEmpty())
        assertEquals(UniversalMechanicalEffectKind.FORMATION,first.effects.single().kind)
    }

    @Test fun nonDamagingContactContestProducesTypedOutcomeWithoutWound(){
        val intent=CombatIntent("TOUCH",campaign,attacker,defender,"SPAR_TOUCH",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"TOUCH_ONLY",10)
        val actors=listOf(
            actor(attacker,setOf("SPAR_TOUCH"),attributes=mapOf("POWER" to 20_000,"SKILL" to 20_000,"DEFENCE" to 1,"AGILITY" to 1)),
            actor(defender,emptySet(),attributes=mapOf("DEFENCE" to 0,"AGILITY" to 0,"ARMOR" to 50_000))
        )
        val state=CombatSpatialState(mapOf(attacker to CombatPosition.Exact(0,0),defender to CombatPosition.Exact(1_000,0)))
        val result=UniversalCombatEngine().resolve(UniversalCombatRequest(
            intent,snapshot(actors),CombatAbilityContract(
                "SPAR_TOUCH",effectKinds=listOf(UniversalMechanicalEffectKind.INTERACTION),damageTypeUid="NON_DAMAGING_CONTACT"
            ),state
        )) as CombatResolution.Resolved

        assertEquals(listOf(UniversalMechanicalEffectKind.INTERACTION),result.effects.map{it.kind})
        assertEquals("CONTEST:CONTACT_SUCCESS",result.effects.single().payload["track_uid"])
        assertFalse(result.effects.any{it.kind==UniversalMechanicalEffectKind.WOUND})
    }

    @Test fun areaAttackSelectsEveryActorInsideCoreAreaAndReplaysExactly(){
        val near=DomainRef("NPC","N2");val far=DomainRef("NPC","N3")
        val actors=listOf(
            actor(attacker,setOf("BLAST"),attributes=mapOf("POWER" to 20_000,"SKILL" to 20_000,"DEFENCE" to 1,"AGILITY" to 1)),
            actor(defender,emptySet(),attributes=mapOf("DEFENCE" to 0,"AGILITY" to 0)),
            actor(near,emptySet(),attributes=mapOf("DEFENCE" to 0,"AGILITY" to 0)),
            actor(far,emptySet(),attributes=mapOf("DEFENCE" to 0,"AGILITY" to 0))
        )
        val positions=CombatSpatialState(mapOf(
            attacker to CombatPosition.Exact(0,0),defender to CombatPosition.Exact(1_000,0),
            near to CombatPosition.Exact(2_000,0),far to CombatPosition.Exact(10_000,0)
        ))
        val intent=CombatIntent("AREA",campaign,attacker,defender,"BLAST",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"DISABLE",5)
        val request=UniversalCombatRequest(intent,snapshot(actors),CombatAbilityContract("BLAST",maximumRangeMillimetres=5_000,areaRadiusMillimetres=2_000,maximumTargets=8),positions)
        val first=UniversalCombatEngine().resolve(request) as CombatResolution.Resolved
        val replay=UniversalCombatEngine().resolve(request) as CombatResolution.Resolved
        assertEquals(setOf(defender,near),first.effects.map{it.target}.toSet())
        assertFalse(first.effects.any{it.target==far});assertEquals(first,replay)
    }

    @Test fun massAreaAttackResolvesFiveHundredAsOneAggregateWithoutPerActorLoop(){
        val group=DomainRef("GROUP","ARMY-500")
        val caster=actor(attacker,setOf("FIREBALL"),attributes=mapOf("POWER" to 20_000,"SKILL" to 20_000,"DEFENCE" to 1,"AGILITY" to 1))
        val formation=MechanicalActorView(campaign,group,MechanicalActorKind.GROUP,1,MechanicalStateMaterialization.FULL,
            mapOf("DEFENCE" to 0,"AGILITY" to 0),emptyList(),emptySet(),generationProvenanceUid="PHASE63:GROUP",
            aggregatePopulation=AggregateMechanicalPopulation(500,500))
        val intent=CombatIntent("FIREBALL",campaign,attacker,group,"FIREBALL",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"BREAK_FORMATION",5)
        val profile=AggregateAreaImpactProfile(2_000,2_000)
        val request=UniversalCombatRequest(intent,snapshot(listOf(caster,formation)),CombatAbilityContract(
            "FIREBALL",maximumRangeMillimetres=20_000,areaRadiusMillimetres=5_000,maximumTargets=1,aggregateAreaProfile=profile,
            statusApplications=listOf(AbilityStatusApplication("BURNING",1_000))
        ),CombatSpatialState(mapOf(attacker to CombatPosition.Exact(0,0),group to CombatPosition.Exact(5_000,0))))
        val result=UniversalCombatEngine().resolve(request) as CombatResolution.Resolved
        val counts=result.effects.associate{it.kind to it.magnitude}
        assertEquals(100L,counts[UniversalMechanicalEffectKind.AGGREGATE_ELIMINATION])
        assertEquals(100L,counts[UniversalMechanicalEffectKind.AGGREGATE_INJURY])
        assertEquals(50L,counts[UniversalMechanicalEffectKind.AGGREGATE_CONDITION])
        assertEquals(setOf(group),result.effects.map{it.target}.toSet())
        assertEquals(result,UniversalCombatEngine().resolve(request))
    }

    @Test fun overwhelmingDirectAttackTreatsFiveHundredWeakEnemiesAsOneBoundedGroup(){
        val group=DomainRef("GROUP","HORDE-500")
        val champion=actor(attacker,setOf("SWORD_SWEEP"),attributes=mapOf("POWER" to 50_000,"SKILL" to 50_000,"DEFENCE" to 1,"AGILITY" to 1))
        val horde=MechanicalActorView(campaign,group,MechanicalActorKind.GROUP,1,MechanicalStateMaterialization.FULL,
            mapOf("DEFENCE" to 10,"AGILITY" to 10),emptyList(),emptySet(),generationProvenanceUid="PHASE63:GROUP",
            aggregatePopulation=AggregateMechanicalPopulation(500,500))
        val intent=CombatIntent("SWORD-HORDE",campaign,attacker,group,"SWORD_SWEEP",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"DISABLE",5)
        val profile=AggregateDirectImpactProfile(
            minimumPowerRatioBasisPoints=100_000,
            maximumEliminationsPerAction=20,
            maximumInjuriesPerAction=30,
            engagementExposureBasisPoints=1_000
        )
        val request=UniversalCombatRequest(intent,snapshot(listOf(champion,horde)),CombatAbilityContract(
            "SWORD_SWEEP",maximumRangeMillimetres=2_000,aggregateDirectProfile=profile
        ),CombatSpatialState(mapOf(attacker to CombatPosition.Exact(0,0),group to CombatPosition.Exact(1_000,0))))
        val result=UniversalCombatEngine().resolve(request) as CombatResolution.Resolved
        assertEquals(mapOf(
            UniversalMechanicalEffectKind.AGGREGATE_ELIMINATION to 20L,
            UniversalMechanicalEffectKind.AGGREGATE_INJURY to 30L
        ),result.effects.associate{it.kind to it.magnitude})
        assertEquals(setOf(group),result.effects.map{it.target}.toSet())
        assertEquals("DIRECT_DOMINANCE",result.effects.first().payload["impact_mode"])
        assertEquals(result,UniversalCombatEngine().resolve(request))
    }

    @Test fun directGroupShortcutRejectsWhenPowerAdvantageIsNotExtreme(){
        val group=DomainRef("GROUP","PEERS")
        val actor=actor(attacker,setOf("PUNCH"),attributes=mapOf("POWER" to 20,"SKILL" to 20,"DEFENCE" to 1,"AGILITY" to 1))
        val peers=MechanicalActorView(campaign,group,MechanicalActorKind.GROUP,1,MechanicalStateMaterialization.FULL,
            mapOf("DEFENCE" to 20,"AGILITY" to 20),emptyList(),emptySet(),generationProvenanceUid="PHASE63:GROUP",
            aggregatePopulation=AggregateMechanicalPopulation(100,100))
        val intent=CombatIntent("PUNCH-PEERS",campaign,attacker,group,"PUNCH",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"DISABLE",5)
        val result=UniversalCombatEngine().resolve(UniversalCombatRequest(intent,snapshot(listOf(actor,peers)),CombatAbilityContract(
            "PUNCH",aggregateDirectProfile=AggregateDirectImpactProfile(50_000,5,10,1_000)
        ),CombatSpatialState(mapOf(attacker to CombatPosition.Exact(0,0),group to CombatPosition.Exact(1_000,0)))))
        assertEquals("AGGREGATE_POWER_ADVANTAGE_INSUFFICIENT",(result as CombatResolution.Rejected).reasonUid)
    }

    @Test fun groupVersusGroupResolvesAsOneBoundedAggregateEngagement(){
        val attackersRef=DomainRef("UNIT","ALLIANCE-1000");val defendersRef=DomainRef("GROUP","RAIDERS-500")
        val attackers=MechanicalActorView(campaign,attackersRef,MechanicalActorKind.UNIT,1,MechanicalStateMaterialization.FULL,
            mapOf("POWER" to 90,"SKILL" to 80,"DEFENCE" to 50,"AGILITY" to 40),emptyList(),setOf("FORMATION_ATTACK"),
            generationProvenanceUid="PHASE63:UNIT",aggregatePopulation=AggregateMechanicalPopulation(1_000,1_000))
        val defenders=MechanicalActorView(campaign,defendersRef,MechanicalActorKind.GROUP,1,MechanicalStateMaterialization.FULL,
            mapOf("POWER" to 30,"SKILL" to 25,"DEFENCE" to 30,"AGILITY" to 30),emptyList(),setOf("DEFEND"),
            generationProvenanceUid="PHASE63:GROUP",aggregatePopulation=AggregateMechanicalPopulation(500,500))
        val intent=CombatIntent("FORMATION-BATTLE",campaign,attackersRef,defendersRef,"FORMATION_ATTACK",VolitionalActionSource.NPC_DECISION_ENGINE,"BREAK_FORMATION",5)
        val request=UniversalCombatRequest(intent,snapshot(listOf(attackers,defenders)),CombatAbilityContract(
            "FORMATION_ATTACK",aggregateGroupProfile=AggregateGroupEngagementProfile()
        ),CombatSpatialState(mapOf(attackersRef to CombatPosition.Zone("BATTLEFIELD"),defendersRef to CombatPosition.Zone("BATTLEFIELD"))))
        val result=UniversalCombatEngine().resolve(request) as CombatResolution.Resolved
        assertTrue(result.effects.isNotEmpty())
        assertEquals(setOf(defendersRef),result.effects.map{it.target}.toSet())
        assertEquals(setOf("GROUP_ENGAGEMENT"),result.effects.map{it.payload["impact_mode"]}.toSet())
        assertTrue(result.effects.sumOf{it.magnitude}<=125)
        assertEquals(result,UniversalCombatEngine().resolve(request))
    }

    @Test fun aoeContractIsGenericAndWorldPackCanDefineItsOwnSecondaryEffect(){
        val neutral=requireNotNull(CombatAbilityContractPort.UNIVERSAL_FALLBACK.contractFor(
            CombatAbilityContractQuery(campaign,"SONIC_WAVE","AREA_ATTACK",1,true)
        ))
        assertNotNull(neutral.areaRadiusMillimetres);assertTrue(neutral.statusApplications.isEmpty())
        val poisonPort=CombatAbilityContractPort{query->CombatAbilityContract(
            query.abilityUid,areaRadiusMillimetres=8_000,maximumTargets=query.targetCount,
            aggregateAreaProfile=AggregateAreaImpactProfile(500,2_500),statusApplications=listOf(AbilityStatusApplication("POISONED",4_000))
        )}
        val poison=requireNotNull(poisonPort.contractFor(CombatAbilityContractQuery(campaign,"TOXIC_CLOUD","AREA_ATTACK",3,true)))
        assertEquals(8_000L,poison.areaRadiusMillimetres);assertEquals(AbilityStatusApplication("POISONED",4_000),poison.statusApplications.single())
        val fireball=CombatAbilityContract("FIREBALL",areaRadiusMillimetres=5_000,maximumTargets=8,
            aggregateAreaProfile=AggregateAreaImpactProfile(2_000,2_000),statusApplications=listOf(AbilityStatusApplication("BURNING",2_000)))
        assertEquals(2_000L,fireball.statusApplications.single().applicationChanceBasisPoints)
    }

    @Test fun universalStatusesAreCoreOwnedWhileAbilityChanceIsWorldPackOwnedAndReplaySafe(){
        assertTrue(setOf("BURNING","POISONED","PARALYZED","FROZEN").all{it in UniversalStatusEffectRegistry.definitions})
        assertTrue(runCatching{AbilityStatusApplication("WORLD_PACK_PRIVATE_STATUS",2_000)}.isFailure)
        val intent=CombatIntent("STATUS-HIT",campaign,attacker,defender,"ICE_BOLT",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"DISABLE",5)
        val request=UniversalCombatRequest(intent,snapshot(listOf(
            actor(attacker,setOf("ICE_BOLT"),attributes=mapOf("POWER" to 20_000,"SKILL" to 20_000,"DEFENCE" to 1,"AGILITY" to 1)),
            actor(defender,emptySet(),attributes=mapOf("DEFENCE" to 0,"AGILITY" to 0))
        )),CombatAbilityContract("ICE_BOLT",statusApplications=listOf(AbilityStatusApplication("FROZEN",10_000))),
            CombatSpatialState(mapOf(attacker to CombatPosition.Exact(0,0),defender to CombatPosition.Exact(1_000,0))))
        val first=UniversalCombatEngine().resolve(request) as CombatResolution.Resolved
        val status=first.effects.single{it.kind==UniversalMechanicalEffectKind.CONDITION}
        assertEquals("FROZEN",status.payload["condition_uid"]);assertEquals("ADD",status.payload["operation"])
        assertEquals(first,UniversalCombatEngine().resolve(request));assertTrue(first.evidence.randomDraws.isNotEmpty())
    }

    @Test fun confirmedNewCampaignCharacterIsValidatedAndCreatedAtomically(){
        SQLiteDatabase.create(null).use{db->
            db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
            db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,year_number INTEGER,year_label TEXT,season TEXT,hour INTEGER,minute INTEGER,era_key TEXT,era_name TEXT,canon_anchor_event_uid TEXT,updated_chapter INTEGER)")
            db.execSQL("INSERT INTO campaign_calendar VALUES(1,-14600,-40,'40 lat przed założeniem Konohy','spring',8,0,'warring_states','Era Walczących Klanów','BASE-WARRING',0)")
            db.execSQL("CREATE TABLE world_clock(id INTEGER PRIMARY KEY,campaign_day INTEGER,campaign_year INTEGER,season TEXT,era TEXT,updated_chapter INTEGER)")
            db.execSQL("INSERT INTO world_clock VALUES(1,-14600,-40,'spring','Era Walczących Klanów',0)")
            db.execSQL("CREATE TABLE active_world_events(active_event_uid TEXT PRIMARY KEY,status TEXT NOT NULL)")
            db.execSQL("INSERT INTO active_world_events VALUES('OLD-EPOCH-EVENT','active')")
            db.execSQL("CREATE TABLE timeline_events(timeline_uid TEXT PRIMARY KEY,status TEXT NOT NULL)")
            db.execSQL("INSERT INTO timeline_events VALUES('OLD-EPOCH-TIMELINE','active')")
            CurrentSchema.ensure(db,campaign)
            StatResourceStore(db,campaign).apply{
                registerStatDefinitions("WORLD",listOf(StatDefinition("POWER","power","BASE",minValue=0.0,maxValue=100.0,worldPackUid="WORLD")))
                registerResourceDefinitions("WORLD",listOf(ResourceDefinition("HEALTH","health","VITAL",minValue=0.0,maxValue=100.0,worldPackUid="WORLD")))
            }
            ProgressionProfileStore(db,campaign).registerDomains("WORLD",listOf(ProgressionDomainDefinition("BODY","WORLD","body","Body","BASE",provenance="WORLD")))
            SkillStore(db,campaign).registerDefinitions("WORLD",listOf(SkillDefinition("SWORD","WORLD","sword","Sword","COMBAT",minMastery=0.0,maxMastery=100.0,provenance="WORLD")))
            TechniqueStore(db,campaign).registerDefinitions("WORLD",listOf(TechniqueDefinition("SLASH","WORLD","slash","Slash","COMBAT",
                skillRequirements=listOf(TechniqueSkillRequirement("SWORD",TechniqueRequirementPhase.ACQUISITION,TechniqueSkillMasteryBasis.BASE,10.0,provenance="WORLD")),
                minMastery=0.0,maxMastery=100.0,provenance="WORLD")))
            Phase9Store(db,campaign).apply{
                registerOrigins("WORLD",listOf(OriginDefinition("VILLAGER","WORLD","villager","Villager","SOCIAL",provenance="WORLD")))
                registerInnateFeatures("WORLD",listOf(InnateFeatureDefinition("KEEN","WORLD","keen","Keen senses","TRAIT",provenance="WORLD")))
            }
            GameplayRuntimeBootstrap.initialize(db,campaign)
            val draft=PlayerCharacterCreationDraft(
                "CREATE-P1",campaign,"P1","Ari","NON_BINARY",mapOf("BACKGROUND" to "VILLAGER","ERA" to "NARUTO"),
                stats=listOf(CharacterCreationValueChoice("POWER",20.0)),resources=listOf(CharacterCreationValueChoice("HEALTH",100.0)),
                talents=listOf(CharacterCreationValueChoice("BODY",15.0)),potentials=listOf(CharacterCreationValueChoice("BODY",80.0,"MAXIMUM")),
                skills=listOf(CharacterCreationValueChoice("SWORD",20.0)),techniques=listOf(CharacterCreationValueChoice("SLASH",10.0)),
                originUids=listOf("VILLAGER"),innateFeatureUids=listOf("KEEN"),startingLocationUid="START"
            )
            val service=PlayerCharacterBootstrapService(db,campaign);val fp=PlayerCharacterBootstrapService.fingerprint(draft)
            assertTrue(runCatching{service.commit(draft,PlayerCharacterCreationConfirmation("WRONG","USER-CLICK"))}.isFailure)
            assertEquals(0L,scalar(db,"SELECT COUNT(*) FROM player_stats"))
            val receipt=service.commit(draft,PlayerCharacterCreationConfirmation(fp,"USER-CLICK"))
            assertFalse(receipt.idempotentReplay);assertEquals("P1",ActivePlayerStore(db,campaign).requireActive().playerUid)
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM player_stats WHERE character_uid='P1'"))
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM player_resources WHERE character_uid='P1'"))
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM talent_profile_entries WHERE character_uid='P1'"))
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM potential_profile_entries WHERE character_uid='P1'"))
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM player_skills_v2 WHERE character_uid='P1'"))
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM player_techniques_v2 WHERE character_uid='P1'"))
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM player_origins_v2 WHERE character_uid='P1'"))
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM player_innate_features WHERE character_uid='P1'"))
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM campaign_truth_records WHERE source_id='CREATE-P1' AND predicate='RPGOS:CHARACTER_CREATION:FINGERPRINT'"))
            assertEquals("naruto",text(db,"SELECT era_key FROM campaign_calendar WHERE id=1"))
            assertEquals("Era Naruto",text(db,"SELECT era_name FROM campaign_calendar WHERE id=1"))
            assertEquals("Era Naruto",text(db,"SELECT era FROM world_clock WHERE id=1"))
            assertEquals(0L,scalar(db,"SELECT absolute_day FROM campaign_calendar WHERE id=1"))
            assertEquals("cancelled",text(db,"SELECT status FROM active_world_events WHERE active_event_uid='OLD-EPOCH-EVENT'"))
            assertEquals("cancelled",text(db,"SELECT status FROM timeline_events WHERE timeline_uid='OLD-EPOCH-TIMELINE'"))
            val gmAudience=AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"TEST_DIRECTOR"))
            val gmPurpose=PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
            val gmTrusted=Phase38RuntimeAuthority.privileged(gmAudience,Phase38RuntimeAuthority.PRIV_GM)
            val truthRead=ProtectedCampaignReadRepository.borrowedTrusted(db,campaign,{ActivePlayerStore(db,campaign).active()},gmTrusted)
                .truthContextRows(gmAudience,gmPurpose)
            assertTrue(
                (truthRead as? ProtectedReadResult.Corruption)?.let{"${it.reasonCode}: ${it.error.stackTraceToString()}"} ?: truthRead.toString(),
                (truthRead as? ProtectedReadResult.Allow)?.value?.any{it["predicate"]=="RPGOS:CHARACTER_CREATION:FINGERPRINT"}==true
            )
            assertTrue(service.commit(draft,PlayerCharacterCreationConfirmation(fp,"USER-RETRY")).idempotentReplay)
        }
    }

    @Test fun characterCreationFloorIsGenreNeutralAndIsolatedPerWorldPack(){
        SQLiteDatabase.create(null).use{save->SQLiteDatabase.create(null).use{world->
            save.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
            CurrentSchema.ensure(save,campaign)
            val scienceFiction=WorldPackRuleBinding("WORLD:SCIENCE_FICTION","1")
            val fantasy=WorldPackRuleBinding("WORLD:FANTASY","1")
            CharacterCreationDefinitionBootstrap(save,world,scienceFiction).ensure()
            CharacterCreationDefinitionBootstrap(save,world,fantasy).ensure()
            val sciFi=CharacterCreationCatalogReader(save,campaign,scienceFiction.worldPackUid).read()
            val fantasyCatalog=CharacterCreationCatalogReader(save,campaign,fantasy.worldPackUid).read()
            val required=setOf(CharacterCreationDefinitionKind.STAT,CharacterCreationDefinitionKind.RESOURCE,CharacterCreationDefinitionKind.TALENT,
                CharacterCreationDefinitionKind.POTENTIAL,CharacterCreationDefinitionKind.SKILL,CharacterCreationDefinitionKind.TECHNIQUE)
            assertTrue(sciFi.options.map{it.kind}.toSet().containsAll(required));assertTrue(fantasyCatalog.options.map{it.kind}.toSet().containsAll(required))
            assertTrue(sciFi.options.map{it.definitionUid}.toSet().intersect(fantasyCatalog.options.map{it.definitionUid}.toSet()).isEmpty())
            val genreSpecificTerms=setOf("CHAKRA","NARUTO","KEKKEI","VILLAGE")
            assertTrue((sciFi.options+fantasyCatalog.options).none{option->genreSpecificTerms.any{term->term in option.definitionUid.uppercase()||term in option.displayName.uppercase()}})
        }}
    }

    @Test fun legacyCharacterCreationTruthProvenanceIsRepairedBeforePrivilegedRead(){
        SQLiteDatabase.create(null).use{db->
            CurrentSchema.ensure(db,campaign)
            db.execSQL("""INSERT INTO campaign_truth_records(
                truth_uid,campaign_id,truth_kind,subject_uid,predicate,object_value,source_type,source_id,
                created_turn,confidence,verified,method,engine_version,created_at,active
            ) VALUES('LEGACY-CREATION','$campaign','FACT','P1','RPGOS:CHARACTER_CREATION:FINGERPRINT','FP',
                'CHARACTER_CREATION','CREATE-P1',0,1.0,1,'EXPLICIT_USER_CONFIRMATION:CLICK',
                'RPGOS-CHARACTER-CREATION-V1',1,1)""".trimIndent())

            CurrentSchema.ensure(db,campaign)

            assertEquals("PLAYER_ACTION",text(db,"SELECT source_type FROM campaign_truth_records WHERE truth_uid='LEGACY-CREATION'"))
            val audience=AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"TEST_DIRECTOR"))
            val purpose=PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
            val trusted=Phase38RuntimeAuthority.privileged(audience,Phase38RuntimeAuthority.PRIV_GM)
            val read=ProtectedCampaignReadRepository.borrowedTrusted(db,campaign,{null},trusted).truthContextRows(audience,purpose)
            assertTrue(read.toString(),read is ProtectedReadResult.Allow && read.value.single()["source_type"]=="PLAYER_ACTION")
        }
    }

    @Test fun effectMaterializerRoutesEveryOwnedFamilyAndRejectsUnknown(){
        fun effect(uid:String,kind:String,target:DomainRef=defender,magnitude:Long=3,payload:Map<String,String> = emptyMap())=
            VerifiedMechanicsCommandEffect(uid,"N","OWNER",kind,target,magnitude,payload,"PROOF","IN","OUT")
        val cases=listOf(
            effect("R","RESOURCE_DELTA",attacker,-3,mapOf("resource_uid" to "HEALTH")) to ResourceChange::class,
            effect("C","CONDITION",payload=mapOf("condition_uid" to "STUNNED","operation" to "ADD")) to ConditionChange::class,
            effect("W","WOUND") to WoundChange::class,
            effect("M","MOVEMENT",attacker,1_000) to SpatialChange::class,
            effect("E","EQUIPMENT_DAMAGE") to EquipmentIntegrityChange::class,
            effect("S","STRUCTURE_DAMAGE") to StructureIntegrityChange::class,
            effect("MO","MORALE",magnitude=-3) to MechanicalTrackChange::class,
            effect("AE","AGGREGATE_ELIMINATION",DomainRef("GROUP","G1"),3) to AggregatePopulationChange::class
        )
        cases.forEach{(effect,type)->
            val result=MechanicalEffectMaterializer.materialize(effect) as MechanicalEffectMaterializationResult.Materialized
            assertTrue(type.java.isInstance(result.changes.single().payload));assertEquals(1,result.eventIntents.size)
        }
        assertTrue(MechanicalEffectMaterializer.materialize(effect("X","UNOWNED_EXTENSION")) is MechanicalEffectMaterializationResult.Rejected)
    }

    @Test fun aggregateMechanicsCommandRoundTripsAndResolutionUsesPlanOrderWithStagedState(){
        val command=PlayerCommand(
            commandUid="CMD",campaignUid=campaign,actor=CommandActorRef("PLAYER","P1"),commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN",listOf(VerifiedMechanicsCommandEffect("E","N1","OWNER","WOUND",defender,2,emptyMap(),"P","I","O"))),
            provenance=CommandProvenance("TEST")
        )
        val registry=PlayerCommandKindRegistry.core();val decoded=registry.decode(registry.encode(command))
        assertEquals(command,decoded)

        val nodes=listOf(
            IntentNode("N1",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="MOVE",rawPhrase="move")),
            IntentNode("N2",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="USE",rawPhrase="use"),dependencies=listOf(IntentDependency("N1",IntentDependencyKind.BEFORE)))
        )
        val document=IntentDocument(campaignUid=campaign,actor=CommandActorRef("PLAYER","P1"),rawInput="move then use",meaningState=MeaningState.UNDERSTOOD,nodes=nodes,
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"TEST","1","H"))
        val capabilities=listOf("MOVE","USE").map{family->CapabilityDescriptor("CAP:$family",1,semanticFamilyUids=setOf(family),executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="OWNER",composable=true)}
        val plan=(GraphTurnPlanner(capabilities).plan(document,VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)) as CanonicalPlanningResult.Planned).plan
        val context=CanonicalIterativeRetrievalPipeline(StructuredSqlRetriever(emptyList()),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()})
            .execute(plan,ContextRuntimeProfile("TEST",8_000,100,100,500,100)).budgeted
        val observed=mutableListOf<Pair<String,Int>>()
        val engine=MechanicsResolutionEngine(MechanicsResolverRegistry.fromCompositionRoot(mapOf("OWNER" to MechanicsRuleResolver{effect,resolution->
            observed+=effect.nodeUid to resolution.stagedEffects.size
            MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(effect.effectUid,effect.nodeUid,"OWNER",effect.effectKindUid,mapOf("magnitude" to "1"),"P:${effect.effectUid}"))
        })))
        val candidate=GmProposalCandidate(1,"P",campaign,plan.planUid,nodes.map{node->GmNodeProposal(node.nodeUid,"OK:${node.nodeUid}","ok",document.actor,node.semanticAction.semanticFamilyUid!!,emptyList(),node.modality,GmNodeOutcomeState.PROPOSED_SUCCESS)},
            mechanicsEffects=listOf(MechanicsEffectRequest("A","N2","OWNER","WOUND"),MechanicsEffectRequest("Z","N1","OWNER","WOUND")),
            narrativeBlueprint=NarrativeBlueprint(listOf("RESULT"),stopPointUid="PLAYER_AGENCY"),providerUid="TEST",modelUid="MODEL",intentFingerprint=document.canonicalFingerprint())
        assertTrue(engine.resolve(candidate,MechanicsResolutionContext(campaign,plan,context)) is MechanicsPipelineResult.Resolved)
        assertEquals(listOf("N1" to 0,"N2" to 1),observed)
    }

    @Test fun mechanicsEffectsCommitTogetherAndRollbackTogether(){
        SQLiteDatabase.create(null).use{db->
            createMechanicalTables(db);GroupATransactionTestFixtures.setupFinance(db,campaign)
            withAdministrativeMutationAuthority(db,campaign){
                MechanicalActorStateStore(db,campaign).materializeIfMissing(MechanicalActorSeed(
                    defender,MechanicalActorKind.NPC,"TEST","N1","TEST",mapOf("POWER" to 50,"SKILL" to 50,"DEFENCE" to 50,"AGILITY" to 50,"ARMOR" to 10),
                    listOf(MechanicalResource("HEALTH",100,100)),setOf("DEFEND")))
            }
            val effects=listOf(
                VerifiedMechanicsCommandEffect("W","N","OWNER","WOUND",defender,4,emptyMap(),"P","I","O"),
                VerifiedMechanicsCommandEffect("M","N","OWNER","MOVEMENT",attacker,1_000,emptyMap(),"P","I","O"),
                VerifiedMechanicsCommandEffect("C","N","OWNER","CONDITION",defender,1,mapOf("condition_uid" to "STUNNED","operation" to "ADD"),"P","I","O")
            )
            val proposal=mechanicsProposal(effects,"CMD-1")
            val identity=TurnTransactionIdentity(campaign,"TURN-1","CMD-1","TX-1")
            assertTrue(TurnTransactionBoundary.create(db,identity,proposal).commit() is TurnExecutionResult.Committed)
            assertEquals(1L,scalar(db,"SELECT COUNT(*) FROM active_combat_effects WHERE status='active'"))
            assertEquals(4L,scalar(db,"SELECT current_value FROM mechanical_actor_tracks WHERE campaign_id='$campaign' AND entity_uid='N1' AND track_uid='WOUND'"))
            assertEquals(1_000L,scalar(db,"SELECT CAST(x_coord AS INTEGER) FROM entity_positions WHERE entity_uid='P1'"))
            assertNotNull(TurnTransactionReceiptStore(db).committedTransaction("TX-1"))

            val beforeEffects=scalar(db,"SELECT COUNT(*) FROM active_combat_effects")
            val beforeX=scalar(db,"SELECT CAST(x_coord AS INTEGER) FROM entity_positions WHERE entity_uid='P1'")
            val second=effects.map{it.copy(effectUid="${it.effectUid}2")}
            val failed=runCatching{TurnTransactionBoundary.create(db,TurnTransactionIdentity(campaign,"TURN-2","CMD-2","TX-2"),mechanicsProposal(second,"CMD-2"),TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("FAULT")}).commit()}
            assertTrue(failed.isFailure);assertEquals(beforeEffects,scalar(db,"SELECT COUNT(*) FROM active_combat_effects"));assertEquals(beforeX,scalar(db,"SELECT CAST(x_coord AS INTEGER) FROM entity_positions WHERE entity_uid='P1'"))
            assertNull(TurnTransactionReceiptStore(db).committedTransaction("TX-2"))
        }
    }

    @Test fun universalWorldElementAndPlayerTransitionCommitAtomicallyAndRollbackWithoutGhosts(){
        SQLiteDatabase.create(null).use{db->
            createMechanicalTables(db);GroupATransactionTestFixtures.setupFinance(db,campaign)
            fun world(effectUid:String,targetUid:String,name:String)=VerifiedMechanicsCommandEffect(
                effectUid,"N","RPGOS-CORE:WORLD-MATERIALIZER","WORLD_ELEMENT_MATERIALIZE",DomainRef("PLACE",targetUid),1,
                mapOf(
                    "world_base_kind" to "PLACE","display_name" to name,"category_uid" to "CRAFTING_VENUE",
                    "parent_anchor_uid" to "VILLAGE","affordance_uids" to "CRAFTING,LEARNING",
                    "topology_class_uid" to "SETTLEMENT_FACILITY","source_classification" to "GENERATED_PLAUSIBLE",
                    "materialization_level_uid" to "PARTIAL"
                ),"PROOF-$effectUid","INPUT-$effectUid","OUTPUT-$effectUid"
            )
            fun transition(effectUid:String,targetUid:String)=VerifiedMechanicsCommandEffect(
                effectUid,"N","RPGOS-CORE:SPATIAL","LOCATION_TRANSITION",attacker,1,
                mapOf("destination_kind_uid" to "PLACE","destination_uid" to targetUid),
                "PROOF-$effectUid","INPUT-$effectUid","OUTPUT-$effectUid"
            )
            val committedTarget="WORLD-ATELIER"
            val committed=mechanicsProposal(listOf(world("WORLD-OK",committedTarget,"pracownia tkacka"),transition("MOVE-OK",committedTarget)),"CMD-WORLD-OK")
            assertTrue(TurnTransactionBoundary.create(
                db,TurnTransactionIdentity(campaign,"TURN-WORLD-OK","CMD-WORLD-OK","TX-WORLD-OK"),committed
            ).commit() is TurnExecutionResult.Committed)
            val shape=WorldReferenceShape(WorldReferenceShapeKind.CATEGORY,WorldElementBaseKind.PLACE,"CRAFTING_VENUE",setOf("CRAFTING"),"SETTLEMENT_FACILITY")
            assertEquals(committedTarget,CampaignWorldProjectionStore(db,campaign).searchPlayerVisible("pracownia tkacka",shape).single().element.uid)
            assertEquals(committedTarget,text(db,"SELECT location_uid FROM entity_positions WHERE entity_uid='P1'"))

            val beforeRepeat=scalar(db,"SELECT COUNT(*) FROM campaign_truth_records WHERE campaign_id='$campaign' AND subject_uid='$committedTarget'")
            val repeated=mechanicsProposal(listOf(world("WORLD-REPEAT",committedTarget,"pracownia tkacka")),"CMD-WORLD-REPEAT")
            assertTrue(TurnTransactionBoundary.create(
                db,TurnTransactionIdentity(campaign,"TURN-WORLD-REPEAT","CMD-WORLD-REPEAT","TX-WORLD-REPEAT"),repeated
            ).commit() is TurnExecutionResult.Committed)
            assertEquals(beforeRepeat,scalar(db,"SELECT COUNT(*) FROM campaign_truth_records WHERE campaign_id='$campaign' AND subject_uid='$committedTarget'"))
            assertEquals(committedTarget,CampaignWorldProjectionStore(db,campaign).searchPlayerVisible("pracownia tkacka",shape).single().element.uid)

            val rejectedTarget="WORLD-OBSERVATORY"
            val rejected=mechanicsProposal(listOf(world("WORLD-ROLLBACK",rejectedTarget,"obserwatorium"),transition("MOVE-ROLLBACK",rejectedTarget)),"CMD-WORLD-ROLLBACK")
            val failed=runCatching{TurnTransactionBoundary.create(
                db,TurnTransactionIdentity(campaign,"TURN-WORLD-ROLLBACK","CMD-WORLD-ROLLBACK","TX-WORLD-ROLLBACK"),rejected,
                TurnFailureInjector{if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("FAULT")}
            ).commit()}
            assertTrue(failed.isFailure)
            assertTrue(CampaignWorldProjectionStore(db,campaign).searchPlayerVisible("obserwatorium",shape.copy(categoryUid=null,affordanceUids=emptySet())).isEmpty())
            assertEquals(0L,db.rawQuery("SELECT COUNT(*) FROM campaign_truth_records WHERE campaign_id=? AND subject_uid=?",arrayOf(campaign,rejectedTarget)).use{it.moveToFirst();it.getLong(0)})
            assertEquals(committedTarget,text(db,"SELECT location_uid FROM entity_positions WHERE entity_uid='P1'"))
            assertNull(TurnTransactionReceiptStore(db).committedTransaction("TX-WORLD-ROLLBACK"))
        }
    }

    @Test fun committedNarrationRecoverySurvivesRestartPreservesClaimsAndNeverRecommits(){
        val root=File(System.getProperty("java.io.tmpdir"),"rpgos-p54-${System.nanoTime()}").also{it.mkdirs()}
        try{
            val deliveryDir=File(root,"delivery");val recoveryDir=File(root,"recovery")
            val order=7L
            val request=ChatTurnRequest("REQ",campaign,"TURN","CMD","TX",CommandActorRef("PLAYER","P1"),"atakuję","pl-PL",
                VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),order)
            val receipt=TurnCommitReceipt(campaign,"TURN","CMD","TX","SEM","RESULT",order,1,"MANIFEST")
            val authority=PersistedCommitReceiptAuthority(CommittedReceiptLookup{receipt})
            assertNull(authority.authorize(receipt.copy(turnUid="OTHER"),TurnTransactionIdentity(campaign,"TURN","CMD","TX")))
            FileNarrationRecoveryStore(recoveryDir).record(request,receipt)
            var routeCalls=0;var assemblerCalls=0;var commitCalls=0
            val narrator=DeterministicAiProvider(
                AiCapabilityContract("RECOVERY-NARRATOR","RECOVERY-PROVIDER","RECOVERY-MODEL",setOf(AiWorkload.NARRATIVE_RENDER,AiWorkload.NARRATIVE_REPAIR),maximumContextUnits=1_000),
                intentFunction={error("NOT_USED")},proposalFunction={error("NOT_USED")},
                narrativeFunction={narrativeRequest->RenderedNarrative(
                    "Przeciwnik został ranny.",narrativeRequest.context.stopPointUid,narrativeRequest.context.committedOrder,
                    listOf(NarrativeSemanticClaim("C1",NarrativeClaimKind.MECHANICAL_RESULT,"F1","WOUND","4"))
                )}
            )
            fun engine(providerAvailable:Boolean=false)=AiChatEngineFacade(
                AiModelRoutePort{_,_,_->routeCalls++;if(providerAvailable)AiRouteResult.Selected(narrator,true,"RECOVERY_TEST") else AiRouteResult.Unavailable(listOf("OFFLINE"))},Phase43IntentValidator(),TrustedIntentResolutionPort.NONE,
                IntentInterpretationFallback.NONE,GraphTurnPlanner(emptyList()),
                CanonicalIterativeRetrievalPipeline(StructuredSqlRetriever(emptyList()),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()}),
                ContextRuntimeProfile("TEST",1_000,10,10,100,10),
                BoundedProposalRepair(GmProposalEvaluator(StructuredGmProposalValidator(),MechanicsResolutionEngine(MechanicsResolverRegistry.fromCompositionRoot(emptyMap())))),
                CanonicalMutationAssembler{_,_,_->assemblerCalls++;null},AuthoritativeTurnCommitPort{_,_->commitCalls++;error("COMMIT_MUST_NOT_RUN")},
                PersistedCommitReceiptAuthority(CommittedReceiptLookup{if(it=="TX")receipt else null}),
                CommittedNarrationContextBuilder(CommittedNarrationReadPort{identity,_,_,_->PostCommitPlayerVisibleReadback(
                    identity.campaignUid,identity.turnUid,identity.commandUid,identity.transactionUid,order,"P38:AS-OF",emptyMap(),
                    listOf(CommittedNarrativeFact("F1",CommittedNarrativeFactKind.MECHANICAL_RESULT,"N1","WOUND","4",order)),
                    listOf("Przeciwnik został ranny."),emptySet(),emptySet(),"PLAYER_DECISION_POINT")}),
                deliveryStore=FileNarrativeDeliveryStore(deliveryDir),recoveryStore=FileNarrationRecoveryStore(recoveryDir)
            )
            val firstEngine=engine();assertEquals(request,firstEngine.pendingNarrationRecovery(campaign)?.request)
            assertTrue(firstEngine.recoverNarration(request) is NarrativeRecoveryResult.Unavailable)
            assertEquals(request,engine().pendingNarrationRecovery(campaign)?.request)
            val recovered=engine(providerAvailable=true).recoverNarration(request) as NarrativeRecoveryResult.Recovered
            assertTrue(recovered.rebuilt);assertEquals(0,assemblerCalls);assertEquals(0,commitCalls);assertEquals(2,routeCalls)
            val reopenedDelivery=FileNarrativeDeliveryStore(deliveryDir).find(recovered.delivery.identity)
            assertEquals(recovered.delivery,reopenedDelivery);assertEquals(recovered.delivery.narrative.claims,reopenedDelivery?.narrative?.claims)
            val volitionalNarrative=recovered.delivery.narrative.copy(assertsPlayerVolition=true)
            assertNotEquals(narrativeFingerprint(recovered.delivery.narrative),narrativeFingerprint(volitionalNarrative))
            val volitionalIdentity=NarrativeDeliveryIdentity("TX-VOLITION",order,"pl-PL")
            val volitionalReceipt=NarrativeDeliveryReceipt("DELIVERY-VOLITION",volitionalIdentity,recovered.delivery.contextFingerprint,
                volitionalNarrative,"TEST","MODEL",narrativeFingerprint(volitionalNarrative))
            FileNarrativeDeliveryStore(deliveryDir).record(volitionalReceipt)
            assertTrue(requireNotNull(FileNarrativeDeliveryStore(deliveryDir).find(volitionalIdentity)).narrative.assertsPlayerVolition)
            assertNull(engine().pendingNarrationRecovery(campaign));assertEquals(0,assemblerCalls);assertEquals(0,commitCalls)
        }finally{root.deleteRecursively()}
    }

    private fun mechanicsProposal(effects:List<VerifiedMechanicsCommandEffect>,commandUid:String):CanonicalCampaignMutationProposal{
        val command=PlayerCommand(commandUid=commandUid,campaignUid=campaign,actor=CommandActorRef("PLAYER","P1"),commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN",effects),provenance=CommandProvenance("TEST"),requestedEffectiveOrder=10)
        val refs=linkedSetOf(CampaignScopedDomainRef(campaign,DomainRef("PLAYER","P1")))
        effects.forEach{effect->
            refs+=CampaignScopedDomainRef(campaign,effect.target)
            when(val materialized=MechanicalEffectMaterializer.materialize(effect)){
                is MechanicalEffectMaterializationResult.Rejected->error(materialized.reasonUid)
                is MechanicalEffectMaterializationResult.Materialized->materialized.changes.forEach{change->when(val payload=change.payload){
                    is RuntimeChange->refs+=CampaignScopedDomainRef(campaign,DomainRef("RUNTIME_COUNTER",payload.runtimeCounterUid))
                    is ConditionChange->refs+=CampaignScopedDomainRef(campaign,DomainRef("CONDITION",payload.conditionUid))
                    else->Unit
                }}
            }
        }
        val context=PlayerResolutionContext.createUnboundGeneric(campaign,command.actor,refs)
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit(campaign,productionMechanicsPlayerDomainEngine(),command,context)){
            is CampaignMutationAdmission.Accepted->admission.proposal
            is CampaignMutationAdmission.Rejected->error("ADMISSION:${admission.reasonUid}")
        }
    }

    private fun createMechanicalTables(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE active_combat_effects(active_effect_uid TEXT PRIMARY KEY,entity_uid TEXT NOT NULL,source_entity_uid TEXT,effect_key TEXT NOT NULL,magnitude REAL NOT NULL,started_chapter INTEGER NOT NULL,remaining_duration_sec REAL,status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
    }
    private fun scalar(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{assertTrue(it.moveToFirst());it.getLong(0)}
    private fun text(db:SQLiteDatabase,sql:String)=db.rawQuery(sql,null).use{assertTrue(it.moveToFirst());it.getString(0)}
    private fun actor(ref:DomainRef,abilities:Set<String>,resources:List<MechanicalResource> = emptyList(),attributes:Map<String,Long> = mapOf("POWER" to 60,"SKILL" to 50,"DEFENCE" to 40,"AGILITY" to 35))=
        MechanicalActorView(campaign,ref,if(ref.kindUid=="PLAYER")MechanicalActorKind.ACTIVE_PLAYER else MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,attributes,resources,abilities,generationProvenanceUid="TEST")
    private fun snapshot(actors:List<MechanicalActorView>,perception:List<CombatPerceptionEvidence> = emptyList(),timing:Map<String,Long> = emptyMap())=
        ImmutableCombatSnapshot("S",campaign,5,actors,perception,emptyMap(),timing,"FINGERPRINT")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase48NativePackageAndProductionWiringTest{
    private val context:Context get()=RuntimeEnvironment.getApplication()
    @After fun cleanup(){
        context.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir,"ai-models").deleteRecursively();File(context.filesDir,"rpgos").deleteRecursively();File(context.filesDir,"narrative-delivery").deleteRecursively();File(context.filesDir,"narrative-recovery").deleteRecursively()
    }

    @Test fun execuTorchPackageImportIsSafeConcreteAndRemovable(){
        val bytes=ByteArrayOutputStream().also{buffer->ZipOutputStream(buffer).use{zip->
            zip.putNextEntry(ZipEntry("nested/model.pte"));zip.write(byteArrayOf(1,2,3));zip.closeEntry()
            zip.putNextEntry(ZipEntry("../../tokenizer.json"));zip.write("{}".toByteArray());zip.closeEntry()
            zip.putNextEntry(ZipEntry("ignored.bin"));zip.write(byteArrayOf(9));zip.closeEntry()
        }}.toByteArray()
        val profile=BielikLocalModelProfiles.BIELIK_4_5B_V3_EXECUTORCH;val variant=profile.variants.single()
        val store=AndroidLocalModelArtifactStore(context)
        val artifact=store.import(profile.modelUid,variant.variantUid,ByteArrayInputStream(bytes))
        assertTrue(File(artifact.absolutePath).isFile);assertTrue(File(requireNotNull(artifact.tokenizerAbsolutePath)).isFile)
        assertEquals(LocalArtifactFormat.EXECUTORCH,variant.format)
        assertTrue(store.remove(profile.modelUid,variant.variantUid));assertNull(store.find(profile.modelUid,variant.variantUid))
    }

    @Test fun timeReaderReturnsCanonicalCalendarInsteadOfDiscardingNestedCursorResult(){
        cleanup()
        val store=LocalGameStore(context)
        store.bootstrap()
        val campaignUid=CampaignSelectionManager(context).activeCampaignRef().campaignId
        store.openGameplaySaveDb().use{db->withAdministrativeMutationAuthority(db,campaignUid){
            db.execSQL(
                "UPDATE campaign_calendar SET year_label=?,era_name=?,season=?,hour=?,minute=? WHERE id=1",
                arrayOf<Any?>("Początek ery Naruto","Era Naruto","summer",9,7)
            )
        }}

        assertEquals(TimeSnapshot("Początek ery Naruto","Era Naruto","summer","09:07"),store.time())
    }

    @Test fun gameMasterCharacterDraftCannotMutateUntilSeparateUserConfirmation(){
        cleanup();val repository=UnifiedGameRepository(context);repository.bootstrap()
        assertNull(repository.activePlayerRef())
        val availableKinds=repository.characterCreationCatalog().options.groupingBy{it.kind}.eachCount()
        val requiredKinds=setOf(CharacterCreationDefinitionKind.STAT,CharacterCreationDefinitionKind.RESOURCE,CharacterCreationDefinitionKind.TALENT,
            CharacterCreationDefinitionKind.POTENTIAL,CharacterCreationDefinitionKind.SKILL,CharacterCreationDefinitionKind.TECHNIQUE)
        assertTrue("availableKinds=$availableKinds",availableKinds.keys.containsAll(requiredKinds))
        val selection=AiModelSelection("CONTROLLED-CREATOR","MODEL")
        val provider=DeterministicAiProvider(
            AiCapabilityContract("CREATOR",selection.providerUid,selection.modelUid,AiWorkload.entries.toSet(),maximumContextUnits=100_000),
            intentFunction={error("NOT_USED")},proposalFunction={error("NOT_USED")},narrativeFunction={error("NOT_USED")},
            characterCreationFunction={request->
                fun choices(kind:CharacterCreationDefinitionKind,all:Boolean=true)=request.catalog.options.filter{it.kind==kind}.let{if(all)it else it.take(1)}.map{option->
                    val value=when(kind){
                        CharacterCreationDefinitionKind.SKILL->option.maximumValue?:1_000_000.0
                        CharacterCreationDefinitionKind.RESOURCE->option.maximumValue?:option.minimumValue?:1.0
                        else->option.minimumValue?:0.0
                    }
                    CharacterCreationValueChoice(option.definitionUid,value,option.dimensionUid)
                }
                CharacterCreationGmCandidate.ReadyForConfirmation(PlayerCharacterCreationDraft(
                    "CREATE-LIVE","${request.campaignUid}","PLAYER-CREATED","Mika","PLAYER_CHOICE",
                    stats=choices(CharacterCreationDefinitionKind.STAT),resources=choices(CharacterCreationDefinitionKind.RESOURCE),
                    talents=choices(CharacterCreationDefinitionKind.TALENT),potentials=choices(CharacterCreationDefinitionKind.POTENTIAL),
                    skills=choices(CharacterCreationDefinitionKind.SKILL),techniques=choices(CharacterCreationDefinitionKind.TECHNIQUE,false),
                    startingLocationUid=repository.worldLocations().first().uid
                ),"Mika — pełny projekt postaci zgodny z wybranym światem.")
            }
        )
        val application=AiCharacterCreationApplication(FixedAiModelRoute(provider),repository)
        val creationOutcome=application.play("Chcę stworzyć postać Mika.")
        assertTrue("creationOutcome=$creationOutcome",creationOutcome is CharacterCreationApplicationOutcome.AwaitingExplicitConfirmation)
        val pending=creationOutcome as CharacterCreationApplicationOutcome.AwaitingExplicitConfirmation
        assertNull(repository.activePlayerRef())
        val created=application.confirm(pending.creationUid,"UI-CONFIRM-1") as CharacterCreationApplicationOutcome.Created
        assertEquals("PLAYER-CREATED",created.receipt.playerUid);assertEquals("PLAYER-CREATED",repository.activePlayerRef()?.playerUid)
    }

    @Test fun androidDefaultsToCanonicalChatAndPackagedRuntime(){
        val working=File(requireNotNull(System.getProperty("user.dir")));val module=if(File(working,"src/main").isDirectory)working else File(working,"app")
        val root=File(module,"src/main/java/com/rpgos/app")
        val viewModel=File(root,"RpgOsViewModel.kt").readText()
        val provider=File(root,"AiProviderCenter.kt").readText()
        val gradle=File(module,"build.gradle.kts").readText()
        assertTrue(viewModel.contains("DynamicCanonicalChatApplication"));assertFalse(viewModel.contains("NonAuthoritativeLegacyNarrationApplication("))
        assertTrue(provider.contains("IsolatedExecuTorchLocalInferenceDriver"))
        assertTrue(provider.contains("IsolatedLlamaCppLocalInferenceDriver"));assertTrue(provider.contains("NativeLocalInferenceBridge.available"))
        assertTrue(provider.contains("LocalCompactAiJsonCodec"))
        val execuTorchService=File(root,"ExecuTorchInferenceService.kt").readText()
        assertTrue(execuTorchService.contains(".dataPath(null)"))
        assertTrue(execuTorchService.contains("bielikChatPrompt(prompt)"))
        assertTrue(execuTorchService.contains("<|im_start|>assistant"))
        assertTrue(execuTorchService.contains("seedCharacterCreationJson(output.toString(),structuredSeed)"))
        assertTrue(execuTorchService.contains("RPGOS_CC_LOCAL_1"))
        assertTrue(execuTorchService.contains("minOf(maximumOutputUnits,512)"))
        assertTrue(execuTorchService.contains("tokens>=maximumOutputUnits||completeJsonObjectOrNull"))
        assertTrue(execuTorchService.contains("Nigdy nie przepisuj danych wejściowych"))
        val runtime=File(root,"Phase48ProductionAiRuntime.kt").readText()
        assertTrue(runtime.contains("AiWorkload.CHARACTER_CREATION->512"))
        assertTrue(runtime.contains("minOf(request.maximumOutputUnits,workloadLimit)"))
        val llamaNative=File(module,"src/main/cpp/rpgos_llama_jni.cpp").readText()
        assertTrue(llamaNative.contains("gpu_layers < 0 ? std::numeric_limits<int32_t>::max() : gpu_layers"))
        val manifest=File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".ExecuTorchInferenceService"));assertTrue(manifest.contains(".LlamaCppInferenceService"))
        assertTrue(manifest.contains("android:process=\":local_ai\""))
        assertTrue(gradle.contains("org.pytorch:executorch-android:1.3.0"))
    }

    @Test fun controlledRootE2ECommitsOneHundredTurns()=runBlocking{
        cleanup()
        val repository=UnifiedGameRepository(context);repository.bootstrap()
        val active=repository.activePlayerRef()?:activateFixturePlayer(repository);val campaign=active.campaignId
        val location=repository.worldLocations().first()
        val selection=AiModelSelection("CONTROLLED-PRODUCTION","MODEL-1")
        val provider=DeterministicAiProvider(
            AiCapabilityContract("CONTROLLED-CONTRACT",selection.providerUid,selection.modelUid,AiWorkload.entries.toSet(),maximumContextUnits=16_000),
            intentFunction={request->
                val reference=IntentReference("TARGET",IntentReferenceKind.DESCRIPTIVE,location.name,"TARGET",descriptorHints=mapOf("surface" to location.name))
                IntentDocument(campaignUid=request.campaignUid,actor=request.actor,rawInput=request.rawInput,meaningState=MeaningState.UNDERSTOOD,
                    nodes=listOf(IntentNode("MOVE",IntentForm.DIRECT_ACTION,SemanticAction(semanticFamilyUid="MOVE",rawPhrase=request.rawInput),participants=listOf(IntentParticipant("TARGET",referenceUid="TARGET")))),
                    references=listOf(reference),provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,selection.providerUid,"1",digest(request.rawInput)))
            },
            proposalFunction={request->
                val node=request.plan.intent.nodes.single();val target=requireNotNull(request.plan.intent.references.single().resolvedProjectedRef)
                GmProposalCandidate(1,"PROPOSAL:${request.requestUid}",campaign,request.plan.planUid,
                    listOf(GmNodeProposal(node.nodeUid,"MOVE-OK","Docierasz na miejsce.",request.plan.intent.actor,"MOVE",listOf(target),node.modality,GmNodeOutcomeState.PROPOSED_SUCCESS)),
                    mechanicsEffects=listOf(MechanicsEffectRequest("MOVE-EFFECT",node.nodeUid,"UNIVERSAL_MOVEMENT","MOVEMENT",target)),
                    narrativeBlueprint=NarrativeBlueprint(listOf("COMMITTED_MOVE"),stopPointUid="PLAYER_DECISION_POINT"),
                    providerUid=selection.providerUid,modelUid=selection.modelUid,intentFingerprint=request.plan.intent.canonicalFingerprint())
            },
            narrativeFunction={request->RenderedNarrative("Droga pozostaje za tobą, a przed tobą otwiera się nowe miejsce.",request.context.stopPointUid,request.context.committedOrder)}
        )
        val configuration=AiSystemConfiguration(gameMaster=AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,selection))
        fun application(repo:UnifiedGameRepository)=ProductionGameEngineCompositionRoot(
            context,repo,AndroidAiProviderCenterApplication(context),{configuration},{listOf(provider)}
        ).chatApplication()
        val first=application(repository).play("Idę do ${location.name}.",AiCancellationSignal.NONE)
        assertTrue(first is ChatApplicationOutcome.Narrated)
        val firstOrder=(first as ChatApplicationOutcome.Narrated).result.receipt.commitOrder!!
        assertEquals(1_000L,(repository.infrastructureMechanicalPersistence(active.playerUid).position as CombatPosition.Exact).xMillimetres)

        val reopened=UnifiedGameRepository(context)
        val second=application(reopened).play("Ponownie idę do ${location.name}.",AiCancellationSignal.NONE)
        assertTrue(second is ChatApplicationOutcome.Narrated)
        var previousOrder=(second as ChatApplicationOutcome.Narrated).result.receipt.commitOrder!!
        assertTrue(previousOrder>firstOrder)
        assertEquals(2_000L,(reopened.infrastructureMechanicalPersistence(active.playerUid).position as CombatPosition.Exact).xMillimetres)
        repeat(98){index->
            val turn=application(reopened).play("Tura ${index+3}: idę do ${location.name}.",AiCancellationSignal.NONE)
            assertTrue(turn is ChatApplicationOutcome.Narrated)
            val order=(turn as ChatApplicationOutcome.Narrated).result.receipt.commitOrder!!
            assertTrue(order>previousOrder);previousOrder=order
        }
        assertEquals(100_000L,(reopened.infrastructureMechanicalPersistence(active.playerUid).position as CombatPosition.Exact).xMillimetres)
    }

    @Test fun controlledProductionRootCommitsMultiActionThenCombatAndSurvivesRestart()=runBlocking{
        cleanup();val repository=UnifiedGameRepository(context);repository.bootstrap()
        val active=createControlledPlayer(repository);val campaign=active.campaignId
        val location=repository.worldLocations().first()
        val npc=repository.infrastructureOpenWorldDb().use{CanonCharacterProjectionReader(it).list("").first()}
        val group=LocalGameStore(context).openGameplaySaveDb().use{db->db.rawQuery(
            "SELECT display_name,entity_kind_uid,entity_uid FROM aggregate_combat_populations WHERE campaign_id=? ORDER BY display_name LIMIT 1",arrayOf(campaign)
        ).use{cursor->assertTrue(cursor.moveToFirst());cursor.getString(0) to DomainRef(cursor.getString(1),cursor.getString(2))}}
        LocalGameStore(context).openGameplaySaveDb().use{db->withAdministrativeMutationAuthority(db,campaign){
            db.execSQL("UPDATE player_stats SET base_value=20000 WHERE campaign_id=? AND character_uid=?",arrayOf<Any?>(campaign,active.playerUid))
            db.updateOrInsertCompat(
                "UPDATE entity_positions SET location_uid=?,x_coord=?,y_coord=?,last_updated_day=0,updated_chapter=0 WHERE entity_uid=?",
                arrayOf<Any?>(location.uid,2_500.0,0.0,npc.uid),
                "INSERT INTO entity_positions(entity_uid,location_uid,x_coord,y_coord,last_updated_day,updated_chapter) VALUES(?,?,?,?,0,0)",
                arrayOf<Any?>(npc.uid,location.uid,2_500.0,0.0)
            )
            db.updateOrInsertCompat(
                "UPDATE entity_positions SET location_uid=?,x_coord=?,y_coord=?,last_updated_day=0,updated_chapter=0 WHERE entity_uid=?",
                arrayOf<Any?>(location.uid,2_000.0,0.0,group.second.uid),
                "INSERT INTO entity_positions(entity_uid,location_uid,x_coord,y_coord,last_updated_day,updated_chapter) VALUES(?,?,?,?,0,0)",
                arrayOf<Any?>(group.second.uid,location.uid,2_000.0,0.0)
            )
        }}
        val selection=AiModelSelection("CONTROLLED-MULTI-COMBAT","MODEL-1")
        val provider=DeterministicAiProvider(
            AiCapabilityContract("CONTROLLED-MULTI-COMBAT-CONTRACT",selection.providerUid,selection.modelUid,AiWorkload.entries.toSet(),maximumContextUnits=32_000),
            intentFunction={request->
                if(request.rawInput.contains("kombin",true)){
                    val refs=listOf(
                        IntentReference("COMBO-MOVE-TARGET",IntentReferenceKind.DESCRIPTIVE,location.name,"TARGET",descriptorHints=mapOf("surface" to location.name)),
                        IntentReference("COMBO-ATTACK-TARGET",IntentReferenceKind.DESCRIPTIVE,npc.name,"TARGET",descriptorHints=mapOf("surface" to npc.name))
                    )
                    val nodes=listOf(
                        IntentNode("COMBO-MOVE",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="MOVE",rawPhrase="zbliżam się"),participants=listOf(IntentParticipant("TARGET",referenceUid="COMBO-MOVE-TARGET"))),
                        IntentNode("ATTACK-COMBO",IntentForm.SEQUENCE_MEMBER,SemanticAction("STRIKE","ATTACK","atakuję"),participants=listOf(IntentParticipant("TARGET",referenceUid="COMBO-ATTACK-TARGET")),dependencies=listOf(IntentDependency("COMBO-MOVE",IntentDependencyKind.BEFORE)))
                    )
                    IntentDocument(campaignUid=request.campaignUid,actor=request.actor,rawInput=request.rawInput,meaningState=MeaningState.UNDERSTOOD,nodes=nodes,references=refs,
                        provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,selection.providerUid,"1",digest(request.rawInput)))
                }else if(request.rawInput.contains("atak",true)){
                    val groupAttack=request.rawInput.contains(group.first,true)
                    val surface=if(groupAttack)group.first else npc.name
                    val ref=IntentReference("COMBAT-TARGET",IntentReferenceKind.DESCRIPTIVE,surface,"TARGET",descriptorHints=mapOf("surface" to surface))
                    IntentDocument(campaignUid=request.campaignUid,actor=request.actor,rawInput=request.rawInput,meaningState=MeaningState.UNDERSTOOD,
                        nodes=listOf(IntentNode(if(groupAttack)"ATTACK-GROUP" else "ATTACK",IntentForm.DIRECT_ACTION,
                            SemanticAction(if(groupAttack)"AOE" else "STRIKE",if(groupAttack)"AREA_ATTACK" else "ATTACK",request.rawInput),participants=listOf(IntentParticipant("TARGET",referenceUid="COMBAT-TARGET")))),
                        references=listOf(ref),provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,selection.providerUid,"1",digest(request.rawInput)))
                }else{
                    val refs=listOf("R1","R2").map{uid->IntentReference(uid,IntentReferenceKind.DESCRIPTIVE,location.name,"TARGET",descriptorHints=mapOf("surface" to location.name))}
                    val nodes=listOf(
                        IntentNode("MOVE-1",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="MOVE",rawPhrase="pierwszy krok"),participants=listOf(IntentParticipant("TARGET",referenceUid="R1"))),
                        IntentNode("MOVE-2",IntentForm.SEQUENCE_MEMBER,SemanticAction(semanticFamilyUid="MOVE",rawPhrase="drugi krok"),participants=listOf(IntentParticipant("TARGET",referenceUid="R2")),dependencies=listOf(IntentDependency("MOVE-1",IntentDependencyKind.BEFORE)))
                    )
                    IntentDocument(campaignUid=request.campaignUid,actor=request.actor,rawInput=request.rawInput,meaningState=MeaningState.UNDERSTOOD,nodes=nodes,references=refs,
                        provenance=IntentInterpretationProvenance(IntentInterpretationSource.AI_PROVIDER,selection.providerUid,"1",digest(request.rawInput)))
                }
            },
            proposalFunction={request->
                val proposals=request.plan.steps.map{step->
                    val node=request.plan.intent.nodes.single{it.nodeUid==step.nodeUid}
                    val targets=node.participants.mapNotNull{participant->participant.referenceUid?.let{uid->request.plan.intent.references.single{it.referenceUid==uid}.resolvedProjectedRef}}
                    GmNodeProposal(node.nodeUid,"OK:${node.nodeUid}","Skutek zostaje rozstrzygnięty.",request.plan.intent.actor,
                        node.semanticAction.canonicalActionUid?:requireNotNull(node.semanticAction.semanticFamilyUid),targets,node.modality,GmNodeOutcomeState.PROPOSED_SUCCESS)
                }
                val effects=proposals.map{proposal->
                    val combat=proposal.nodeUid.startsWith("ATTACK")
                    MechanicsEffectRequest("EFFECT:${proposal.nodeUid}",proposal.nodeUid,if(combat)"UNIVERSAL_COMBAT" else "UNIVERSAL_MOVEMENT",if(combat)"WOUND" else "MOVEMENT",proposal.targetProjectedRefs.single())
                }
                GmProposalCandidate(1,"PROPOSAL:${request.requestUid}",campaign,request.plan.planUid,proposals,mechanicsEffects=effects,
                    narrativeBlueprint=NarrativeBlueprint(listOf("COMMITTED_RESULTS"),stopPointUid="PLAYER_DECISION_POINT"),providerUid=selection.providerUid,modelUid=selection.modelUid,
                    intentFingerprint=request.plan.intent.canonicalFingerprint())
            },
            narrativeFunction={request->RenderedNarrative("MG opisuje wyłącznie zatwierdzone konsekwencje.",request.context.stopPointUid,request.context.committedOrder)}
        )
        val configuration=AiSystemConfiguration(gameMaster=AiRoleAssignment(AiRole.GAME_MASTER,AiAssignmentKind.PINNED,selection))
        fun app(repo:UnifiedGameRepository)=ProductionGameEngineCompositionRoot(context,repo,AndroidAiProviderCenterApplication(context),{configuration},{listOf(provider)}).chatApplication()
        val combo=app(repository).play("Wykonuję kombinację: zbliżam się i atakuję ${npc.name}.")
        assertTrue("combo=$combo",combo is ChatApplicationOutcome.Narrated)
        assertEquals(1_000L,(repository.infrastructureMechanicalPersistence(active.playerUid).position as CombatPosition.Exact).xMillimetres)
        assertTrue(repository.infrastructureMechanicalActor(DomainRef("NPC",npc.uid))?.conditions?.any{it.conditionUid=="WOUND"&&it.intensity>0}==true)
        val multi=app(repository).play("Idę dwa razy w stronę ${location.name}.") as ChatApplicationOutcome.Narrated
        assertEquals(3_000L,(repository.infrastructureMechanicalPersistence(active.playerUid).position as CombatPosition.Exact).xMillimetres)
        val combat=app(repository).play("Atakuję ${npc.name}.") as ChatApplicationOutcome.Narrated
        assertTrue(combat.result.receipt.commitOrder!!>multi.result.receipt.commitOrder!!)
        assertTrue(repository.infrastructureMechanicalActor(DomainRef("NPC",npc.uid))?.conditions?.any{it.conditionUid=="WOUND"&&it.intensity>0}==true)
        val populationBefore=requireNotNull(repository.infrastructureAggregatePopulation(group.second))
        val groupOutcome=app(repository).play("Atakuję ${group.first} atakiem obszarowym.")
        assertTrue("groupOutcome=$groupOutcome",groupOutcome is ChatApplicationOutcome.Narrated)
        val groupCombat=groupOutcome as ChatApplicationOutcome.Narrated
        assertTrue(groupCombat.result.receipt.commitOrder!!>combat.result.receipt.commitOrder!!)
        val populationAfter=requireNotNull(repository.infrastructureAggregatePopulation(group.second))
        assertTrue(populationAfter.activeCount<populationBefore.activeCount)
        assertTrue(populationAfter.activeCount+populationAfter.woundedCount+populationAfter.eliminatedCount<=populationAfter.totalCount)
        val reopened=UnifiedGameRepository(context)
        assertEquals(active.playerUid,reopened.activePlayerRef()?.playerUid)
        assertTrue(reopened.infrastructureMechanicalActor(DomainRef("NPC",npc.uid))?.conditions?.any{it.conditionUid=="WOUND"&&it.intensity>0}==true)
        assertEquals(populationAfter,reopened.infrastructureAggregatePopulation(group.second))
    }

    private fun createControlledPlayer(repository:UnifiedGameRepository):ActivePlayerRef{
        val catalog=repository.characterCreationCatalog()
        fun choices(kind:CharacterCreationDefinitionKind,all:Boolean=true)=catalog.options.filter{it.kind==kind}.let{if(all)it else it.take(1)}.map{option->
            CharacterCreationValueChoice(option.definitionUid,option.maximumValue?:option.minimumValue?:if(kind==CharacterCreationDefinitionKind.POTENTIAL)50.0 else 10.0,option.dimensionUid)
        }
        val draft=PlayerCharacterCreationDraft(
            "CREATE-CONTROLLED-E2E",catalog.campaignUid,"PLAYER-E2E","Alex","PLAYER_SELECTED",
            stats=choices(CharacterCreationDefinitionKind.STAT),resources=choices(CharacterCreationDefinitionKind.RESOURCE),
            talents=choices(CharacterCreationDefinitionKind.TALENT),potentials=choices(CharacterCreationDefinitionKind.POTENTIAL),
            skills=choices(CharacterCreationDefinitionKind.SKILL),techniques=choices(CharacterCreationDefinitionKind.TECHNIQUE,false),
            startingLocationUid=catalog.options.first{it.kind==CharacterCreationDefinitionKind.STARTING_LOCATION}.definitionUid
        )
        val fingerprint=PlayerCharacterBootstrapService.fingerprint(draft)
        repository.createPlayerCharacter(draft,PlayerCharacterCreationConfirmation(fingerprint,"CONTROLLED-E2E-CONFIRM"))
        return requireNotNull(repository.activePlayerRef())
    }

    private fun activateFixturePlayer(repository:UnifiedGameRepository):ActivePlayerRef{
        val sources=listOf(
            "character_status_snapshot" to "entity_uid","character_stats" to "entity_uid",
            "character_skills" to "entity_uid","character_techniques" to "entity_uid",
            "entity_positions" to "entity_uid","organization_memberships_v3" to "character_uid"
        )
        val candidate=LocalGameStore(context).openGameplaySaveDb().use{db->
            sources.asSequence().mapNotNull{(table,column)->runCatching{
                db.rawQuery("SELECT $column FROM $table WHERE $column IS NOT NULL AND TRIM($column)!='' ORDER BY $column LIMIT 1",null).use{cursor->
                    if(cursor.moveToFirst())cursor.getString(0)?.trim()?.takeIf{it.isNotBlank()} else null
                }
            }.getOrNull()}.firstOrNull()
        }
        return repository.setActivePlayer(requireNotNull(candidate){"Controlled campaign fixture has no player identity candidate"})
    }

    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}
