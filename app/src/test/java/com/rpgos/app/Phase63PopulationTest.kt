package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase63PopulationTest {
    private val aggregate=DomainRef("GROUP","G63")
    private val root=CampaignWorldSkeleton.legacy("C1",CampaignRuleSource(CampaignRuleSourceKind.CAMPAIGN_NATIVE,"C1","1"),"Era",DomainRef("PLACE","A"))
        .copy(latentRules=CoreLatentWorldRules.initial())
    private val manifest=WorldPopulationManifest(aggregate,3,root.domainSeed("POPULATION",aggregate.toString()))
    private fun database()=SQLiteDatabase.create(null).also { db->
        db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
        db.execSQL("CREATE TABLE active_combat_effects(active_effect_uid TEXT PRIMARY KEY,entity_uid TEXT,effect_key TEXT,magnitude REAL,started_chapter INTEGER,status TEXT,remaining_duration_sec INTEGER DEFAULT 0)")
        GroupATransactionTestFixtures.setupFinance(db)
        withAdministrativeMutationAuthority(db,"C1") {
            MechanicalActorStateStore(db,"C1").materializeIfMissing(Phase63ActorGeneration.groupSeed(aggregate,phase63Hash("fixture"),"Grupa").copy(
                aggregateCount=3,resources=listOf(MechanicalResource("HEALTH",17,23),MechanicalResource("STAMINA",11,19))))
            db.execSQL("UPDATE aggregate_combat_populations SET active_count=1,wounded_count=1,eliminated_count=1 WHERE entity_uid='G63'")
            db.execSQL("INSERT INTO mechanical_actor_tracks VALUES('C1','GROUP','G63','WOUND',7,1)")
        }
    }
    private fun proposal(db:SQLiteDatabase,ordinal:Long,order:Long)=proposal(db,listOf(ordinal),order)
    private fun proposal(db:SQLiteDatabase,ordinals:List<Long>,order:Long):Pair<TurnTransactionIdentity,CanonicalCampaignMutationProposal> {
        val drafts=ordinals.map { ordinal->WorldElementDraft("C1",manifest.member(ordinal),"Osoba $ordinal",WorldElementBaseKind.ACTOR,"LOCAL_PERSON",null,setOf("TALK"),"LOCAL_SITE",
            WorldEvidenceClassification.GENERATED_PLAUSIBLE,listOf(manifest.uid),null,null,null,slotOrdinal=ordinal) }
        fun proof(draft:WorldElementDraft)="RPGOS-CORE:WORLD-MATERIALIZATION:${draft.fingerprint()}"
        val body=requireNotNull(MechanicalActorStateStore(db,"C1").actor(aggregate))
        val world=Phase63WorldStore(db,"C1").root()
        val change=WorldSimulationChange("C1",HistoryGenerationStore(db,"C1").current(),world?.version?:0,if(world==null)root else null,
            populationManifests=if(WorldPopulationStore(db,"C1").forAggregate(aggregate)==null)listOf(manifest) else emptyList(),
            populationExtractions=drafts.mapIndexed { index,draft->WorldPopulationExtraction(manifest.uid,draft.slotOrdinal,draft.element,draft.displayName,body.stateVersion+index,proof(draft)) })
        val effects=drafts.map { draft->VerifiedMechanicsCommandEffect("MAT:${draft.slotOrdinal}","N1","RPGOS-CORE:WORLD-MATERIALIZER","WORLD_ELEMENT_MATERIALIZE",draft.element,1,
            draft.materializationPayload()+mapOf("p63_population_manifest" to manifest.uid),proof(draft),phase63Hash("input-${draft.slotOrdinal}"),phase63Hash("output-${draft.slotOrdinal}")) }
        val actor=CommandActorRef("PLAYER","P1")
        val identity=TurnTransactionIdentity("C1","T$order","CMD$order","TX$order")
        val command=PlayerCommand(commandUid=identity.commandUid,campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload("PLAN$order",effects,worldChanges=listOf(change)),provenance=CommandProvenance("P63-TEST"),requestedEffectiveOrder=order)
        val refs=(setOf(DomainRef("PLAYER","P1"),aggregate,DomainRef("CAMPAIGN","C1"))+drafts.map { it.element })
            .map { CampaignScopedDomainRef("C1",it) }.toSet()
        val admitted=CampaignMutationBoundary.resolveAndAdmit("C1",productionMechanicsPlayerDomainEngine(),command,
            PlayerResolutionContext.createUnboundGeneric("C1",actor,refs))
        assertTrue(admitted.toString(),admitted is CampaignMutationAdmission.Accepted)
        return identity to (admitted as CampaignMutationAdmission.Accepted).proposal
    }
    @Test fun oneBatchUsesStagedVersionsAndExactlyThePreviewAllocation()=database().use { db->
        val expected=listOf(2L,0L,1L).associateWith { Phase50PopulationPartition.preview(db,"C1",manifest,it) }
        val (identity,mutation)=proposal(db,listOf(2L,0L,1L),1)
        assertTrue(TurnTransactionBoundary.create(db,identity,mutation).commit() is TurnExecutionResult.Committed)
        expected.forEach { (ordinal,preview)->
            val actual=MechanicalActorStateStore(db,"C1").actor(manifest.member(ordinal))!!
            assertEquals(preview.resources,actual.resources);assertEquals(preview.conditions,actual.conditions)
            assertEquals(preview.attributes,actual.attributes)
        }
        assertEquals(0L,MechanicalActorStateStore(db,"C1").population(aggregate)!!.totalCount)
    }
    @Test fun legacyManifestPreparationIsReadOnlyAndNamedLineageSupportsCoarseProcessing()=database().use { db->
        val digest=AuthoritativeStateDigest.compute(db)
        val store=WorldPopulationStore(db,"C1")
        assertEquals(manifest,store.candidate(aggregate,root))
        assertNull(store.forAggregate(aggregate))
        assertEquals(digest,AuthoritativeStateDigest.compute(db))
        val draft=WorldElementDraft("C1",manifest.member(2),"Osoba",WorldElementBaseKind.ACTOR,"LOCAL_PERSON",null,
            setOf("TALK"),"LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE,
            listOf(manifest.uid,Phase63PopulationCodec.aggregateEvidence(aggregate)),null,null,null,slotOrdinal=2)
        assertEquals(manifest,store.candidateForDraft(draft,root))
        assertEquals(digest,AuthoritativeStateDigest.compute(db))
        val (identity,proposal)=proposal(db,2,1)
        assertTrue(TurnTransactionBoundary.create(db,identity,proposal).commit() is TurnExecutionResult.Committed)
        assertEquals(aggregate,store.aggregateForMember(draft.element))
        assertEquals(listOf(draft.element),store.namedMembers(aggregate))
        val after=AuthoritativeStateDigest.compute(db)
        val coarse=WorldLodWorkPlan(TemporalScope("C1","G",1,after),listOf(WorldLodSubject(aggregate,anonymousCount=2),WorldLodSubject(draft.element,aggregate)))
        assertEquals(setOf(draft.element),coarse.items.single().namedActors)
        assertTrue(coarse.individualDecisionActors.isEmpty())
        assertEquals(after,AuthoritativeStateDigest.compute(db))
    }
    @Test fun splittingConservesPoolsCasualtiesWoundsAndAllowsAnEmptyAggregate()=database().use { db->
        listOf(2L,0L,1L).forEachIndexed { index,ordinal->
            val preview=Phase50PopulationPartition.preview(db,"C1",manifest,ordinal)
            val (identity,proposal)=proposal(db,ordinal,index+1L)
            assertTrue(TurnTransactionBoundary.create(db,identity,proposal).commit() is TurnExecutionResult.Committed)
            val committed=requireNotNull(MechanicalActorStateStore(db,"C1").actor(manifest.member(ordinal)))
            assertEquals(preview.attributes,committed.attributes)
            assertEquals(preview.resources,committed.resources)
            assertEquals(preview.conditions,committed.conditions)
            assertEquals(preview.executableAbilityUids,committed.executableAbilityUids)
            val digest=AuthoritativeStateDigest.compute(db)
            assertTrue(TurnTransactionBoundary.create(db,identity,proposal).commit() is TurnExecutionResult.AlreadyCommitted)
            assertEquals(digest,AuthoritativeStateDigest.compute(db))
        }
        val store=MechanicalActorStateStore(db,"C1")
        val group=requireNotNull(store.actor(aggregate))
        assertEquals(AggregateMechanicalPopulation(0,0,0,0),group.aggregatePopulation)
        assertTrue(group.resources.all { it.current==0L && it.maximum==0L })
        val people=(0L..2L).map { requireNotNull(store.actor(manifest.member(it))) }
        assertEquals(17L,people.sumOf { it.resources.single { r->r.resourceUid=="HEALTH" }.current })
        assertEquals(23L,people.sumOf { it.resources.single { r->r.resourceUid=="HEALTH" }.maximum })
        assertEquals(7L,people.sumOf { it.conditions.singleOrNull { c->c.conditionUid=="WOUND" }?.intensity?:0 })
        assertEquals(1,people.count { it.conditions.any { c->c.conditionUid=="INCAPACITATED" } })
        assertEquals(1,people.count { it.conditions.any { c->c.conditionUid=="WOUNDED" } })
        assertEquals(setOf(0L,1L,2L),WorldPopulationStore(db,"C1").namedOrdinals(manifest.uid))
        assertEquals(emptyList<Pair<String,DomainRef>>(),store.aggregateTargets("Grupa"))
    }
    @Test fun failureRollsBackBodyLineageAndCanonicalHash()=database().use { db->
        val before=AuthoritativeStateDigest.compute(db)
        val (identity,proposal)=proposal(db,0,1)
        val failure=TurnFailureInjector { if(it==TurnFailurePoint.AFTER_FIRST_WRITE)error("injected") }
        assertTrue(runCatching { TurnTransactionBoundary.create(db,identity,proposal,failure).commit() }.isFailure)
        assertEquals(before,AuthoritativeStateDigest.compute(db))
        assertNull(MechanicalActorStateStore(db,"C1").actor(manifest.member(0)))
        assertNull(WorldPopulationStore(db,"C1").manifest(manifest.uid))
        assertTrue(TurnTransactionBoundary.create(db,identity,proposal).commit() is TurnExecutionResult.Committed)
    }
    @Test fun partitionMovesTheSameOwnedDamagedEquippedItemAndItsModifiers()=database().use { db->
        val item="P63-ITEM"
        val ordinal=phase63Hash("${manifest.uid}|$item").take(15).toLong(16)%manifest.originalCount
        withAdministrativeMutationAuthority(db,"C1") {
            StatResourceStore(db,"C1").registerStatDefinitions("W",listOf(StatDefinition("POWER","power","generic",worldPackUid="W")))
            val inventory=InventoryStore(db,"C1")
            inventory.registerDefinitions("W",listOf(ItemDefinition("ID","W","id","Item",storagePolicy=ItemStoragePolicy.UNIQUE_INSTANCE,provenance="pack")))
            inventory.createInstance(ItemInstance("C1",item,"ID",provenance="instance"))
            inventory.addUnique(aggregate.uid,item,"possessed")
            val equipment=EquipmentStore(db,"C1")
            equipment.registerSlots("W",listOf(EquipmentSlotDefinition("SL","W","sl","Slot",provenance="pack")))
            equipment.registerCompatibilityRules("W",listOf(EquipmentCompatibilityRule("EQ","W","ID",listOf("SL"),provenance="pack")))
            equipment.registerEquipmentModifiers(aggregate.uid,item,listOf(Modifier("M63","C1",aggregate.uid,"POWER",
                ModifierTargetKind.STAT_EFFECTIVE,ModifierLifecycle.EQUIPMENT,ModifierOperation.ADD_FLAT,2.0,
                sourceType=EQUIPMENT_MODIFIER_SOURCE_TYPE,sourceUid=item,sourceActive=true,provenance="equipment")))
            equipment.equip(aggregate.uid,item,"EQ",listOf("SL"),"E63","equipped")
            val owner=OwnershipOwnerRef("CHARACTER",aggregate.uid)
            OwnershipReferenceRegistry(db,"C1").registerOwner(owner,"fixture")
            OwnershipStore(db,"C1").acquire(OwnershipRecord("C1","O63",owner,OwnedAssetRef(OWNERSHIP_ASSET_KIND_ITEM_INSTANCE,item),
                "TITLE",OwnershipShare.full(),0,sourceEventUid="fixture",provenance="fixture"))
            db.execSQL("INSERT INTO mechanical_actor_components VALUES('C1','GROUP','G63',?,'EQUIPMENT',5,9,1)",arrayOf(item))
        }
        val preview=Phase50PopulationPartition.preview(db,"C1",manifest,ordinal)
        val (identity,mutation)=proposal(db,ordinal,1)
        assertTrue(TurnTransactionBoundary.create(db,identity,mutation).commit() is TurnExecutionResult.Committed)
        val member=manifest.member(ordinal)
        assertTrue(InventoryStore(db,"C1").typedUnique(aggregate.uid).isEmpty())
        assertEquals(item,InventoryStore(db,"C1").typedUnique(member.uid).single().first.itemInstanceUid)
        val equipped=EquipmentStore(db,"C1").equipment(member.uid).single()
        assertEquals("E63",equipped.equipment.equipmentEntryUid)
        assertEquals(2L,equipped.equipment.entryVersion)
        assertEquals(member.uid,OwnershipStore(db,"C1").currentOwnership(OwnedAssetRef(OWNERSHIP_ASSET_KIND_ITEM_INSTANCE,item)).single().owner.ownerUid)
        assertTrue(ModifierStore(db,"C1").modifiers(aggregate.uid).isEmpty())
        assertTrue(ModifierStore(db,"C1").modifiers(member.uid).single().sourceActive)
        assertEquals(preview.attributes,MechanicalActorStateStore(db,"C1").actor(member)!!.attributes)
        db.rawQuery("SELECT current_integrity,maximum_integrity FROM mechanical_actor_components WHERE entity_uid=? AND component_uid=?",arrayOf(member.uid,item)).use {
            assertTrue(it.moveToFirst());assertEquals(5L,it.getLong(0));assertEquals(9L,it.getLong(1))
        }
        val digest=AuthoritativeStateDigest.compute(db)
        assertTrue(TurnTransactionBoundary.create(db,identity,mutation).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(digest,AuthoritativeStateDigest.compute(db))
    }
    @Test fun identitiesAndCodecDoNotDependOnLodOrHistoryGeneration() {
        assertEquals(manifest.member(2),manifest.copy().member(2))
        assertEquals(manifest,Phase63PopulationCodec.readManifest(Phase63PopulationCodec.manifest(manifest)))
        val large=manifest.copy(originalCount=5000)
        assertEquals(5000,(0L until 5000).map(large::member).toSet().size)
        assertNotEquals(large.member(0),large.member(1))
    }
    private fun combat(db:SQLiteDatabase,area:Boolean,interaction:Boolean=false):UniversalCombatRequest {
        val store=MechanicalActorStateStore(db,"C1")
        val group=requireNotNull(store.actor(aggregate))
        val player=group.copy(actor=DomainRef("PLAYER","P1"),kind=MechanicalActorKind.ACTIVE_PLAYER,
            attributes=mapOf("POWER" to 10_000,"SKILL" to 10_000,"DEFENCE" to 100,"AGILITY" to 100),
            executableAbilityUids=setOf("ATTACK"),aggregatePopulation=null,conditions=emptyList())
        val members=WorldPopulationStore(db,"C1").namedMembers(aggregate).map { requireNotNull(store.actor(it)) }
        val actors=listOf(player,group)+members
        val snapshot=ImmutableCombatSnapshot("S63","C1",5,actors,emptyList(),emptyMap(),emptyMap(),"S63-FP")
        val ability=CombatAbilityContract("ATTACK",areaRadiusMillimetres=if(area)2000 else null,
            maximumTargets=if(area)16 else 1,aggregateAreaProfile=if(area)AggregateAreaImpactProfile(10_000,0) else null,
            aggregateDirectProfile=if(area)null else AggregateDirectImpactProfile(10_000,1,1,10_000),
            effectKinds=if(interaction)listOf(UniversalMechanicalEffectKind.INTERACTION) else listOf(UniversalMechanicalEffectKind.WOUND))
        return UniversalCombatRequest(CombatIntent("I63","C1",player.actor,aggregate,"ATTACK",VolitionalActionSource.VALIDATED_PLAYER_COMMAND,"DISABLE",5),
            snapshot,ability,CombatSpatialState(actors.associate { it.actor to CombatPosition.Zone("A") }))
    }
    @Test fun compositeAreaUsesPartitionedBodiesAndNeverCountsNamedMembersTwice()=database().use { db->
        val (identity,mutation)=proposal(db,0,1)
        assertTrue(TurnTransactionBoundary.create(db,identity,mutation).commit() is TurnExecutionResult.Committed)
        val request=combat(db,true)
        val group=request.snapshot.actors.single { it.actor==aggregate }.aggregatePopulation!!
        assertEquals(2L,group.totalCount)
        assertEquals(3,request.snapshot.actors.size)
        val digest=AuthoritativeStateDigest.compute(db)
        val engine=UniversalCombatEngine()
        val result=engine.resolve(request) as CombatResolution.Resolved
        assertEquals(result,engine.resolve(request))
        assertTrue(result.effects.filter { it.target==aggregate }.sumOf { it.magnitude }<=group.activeCount)
        assertEquals(setOf(manifest.member(0)),result.effects.filter { it.kind==UniversalMechanicalEffectKind.WOUND }.map { it.target }.toSet())
        assertEquals(result.effects.size,result.effects.map { it.effectUid }.toSet().size)
        assertEquals(digest,AuthoritativeStateDigest.compute(db))
    }
    @Test fun emptyAggregateIsOnlyAnAreaCentreNotAnExtraFighterOrObjective()=database().use { db->
        val (identity,mutation)=proposal(db,listOf(0,1,2),1)
        assertTrue(TurnTransactionBoundary.create(db,identity,mutation).commit() is TurnExecutionResult.Committed)
        val request=combat(db,true)
        assertEquals(0L,request.snapshot.actors.single { it.actor==aggregate }.aggregatePopulation!!.totalCount)
        val result=UniversalCombatEngine().resolve(request) as CombatResolution.Resolved
        assertTrue(result.effects.none { it.target==aggregate })
        assertEquals((0L..2L).map(manifest::member).toSet(),result.effects.map { it.target }.toSet())
        assertEquals("AGGREGATE_HAS_NO_ACTIVE_MEMBERS",(UniversalCombatEngine().resolve(combat(db,false)) as CombatResolution.Rejected).reasonUid)
        assertTrue(AggregateDirectImpactResolver().resolve(AggregateMechanicalPopulation(0,0),
            AggregateDirectImpactProfile(10_000,1,1,10_000),100,1,10_000) is AggregateDirectImpactResolution.Rejected)
        assertEquals(0L,AggregateGroupEngagementResolver().resolve(AggregateMechanicalPopulation(1,1),
            AggregateMechanicalPopulation(0,0),AggregateGroupEngagementProfile(),100,1,100).distribution.eliminated)
    }
    @Test fun nonDamagingContactNeverUsesAggregateCasualtyProfiles()=database().use { db->
        listOf(false,true).forEach { area->
            val result=UniversalCombatEngine().resolve(combat(db,area,interaction=true)) as CombatResolution.Resolved
            assertTrue(result.effects.isNotEmpty())
            assertTrue(result.effects.all { it.kind==UniversalMechanicalEffectKind.INTERACTION })
        }
    }
}
