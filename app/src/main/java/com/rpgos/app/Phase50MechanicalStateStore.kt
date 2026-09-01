package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

internal const val PHASE50_MECHANICAL_SCHEMA_MIGRATION_ID = "RPGOS-50.0-MECHANICAL-ACTOR-STATE"

/**
 * Missing canonical owner filled for Phase50. Generation data is materialized once during the
 * administrative bootstrap; gameplay reads never regenerate an actor and all later changes cross
 * TurnTransaction.
 */
internal object Phase50MechanicalSchema {
    val authoritativeTables = setOf(
        "mechanical_actor_states",
        "mechanical_actor_attributes",
        "mechanical_actor_resources",
        "mechanical_actor_abilities",
        "mechanical_actor_traits",
        "mechanical_actor_resistances",
        "mechanical_actor_components",
        "mechanical_actor_tracks",
        "aggregate_combat_populations",
        "aggregate_combat_conditions"
    )

    fun ensureReady(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS mechanical_actor_states(
            campaign_id TEXT NOT NULL,
            entity_kind_uid TEXT NOT NULL,
            entity_uid TEXT NOT NULL,
            actor_kind_uid TEXT NOT NULL,
            materialization_uid TEXT NOT NULL,
            template_uid TEXT NOT NULL,
            generation_seed_uid TEXT NOT NULL,
            generation_provenance_uid TEXT NOT NULL,
            state_version INTEGER NOT NULL CHECK(state_version>=1),
            updated_order INTEGER NOT NULL CHECK(updated_order>=0),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS mechanical_actor_attributes(
            campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,entity_uid TEXT NOT NULL,
            attribute_uid TEXT NOT NULL,current_value INTEGER NOT NULL,state_version INTEGER NOT NULL CHECK(state_version>=1),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid,attribute_uid),
            FOREIGN KEY(campaign_id,entity_kind_uid,entity_uid) REFERENCES mechanical_actor_states(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS mechanical_actor_resources(
            campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,entity_uid TEXT NOT NULL,
            resource_uid TEXT NOT NULL,current_value INTEGER NOT NULL CHECK(current_value>=0),maximum_value INTEGER NOT NULL CHECK(maximum_value>=0),
            state_version INTEGER NOT NULL CHECK(state_version>=1),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid,resource_uid),
            CHECK(current_value<=maximum_value),
            FOREIGN KEY(campaign_id,entity_kind_uid,entity_uid) REFERENCES mechanical_actor_states(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS mechanical_actor_abilities(
            campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,entity_uid TEXT NOT NULL,ability_uid TEXT NOT NULL,
            state_version INTEGER NOT NULL CHECK(state_version>=1),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid,ability_uid),
            FOREIGN KEY(campaign_id,entity_kind_uid,entity_uid) REFERENCES mechanical_actor_states(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS mechanical_actor_traits(
            campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,entity_uid TEXT NOT NULL,trait_uid TEXT NOT NULL,
            state_version INTEGER NOT NULL CHECK(state_version>=1),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid,trait_uid),
            FOREIGN KEY(campaign_id,entity_kind_uid,entity_uid) REFERENCES mechanical_actor_states(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS mechanical_actor_resistances(
            campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,entity_uid TEXT NOT NULL,resistance_uid TEXT NOT NULL,
            basis_points INTEGER NOT NULL CHECK(basis_points BETWEEN -100000 AND 100000),state_version INTEGER NOT NULL CHECK(state_version>=1),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid,resistance_uid),
            FOREIGN KEY(campaign_id,entity_kind_uid,entity_uid) REFERENCES mechanical_actor_states(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS mechanical_actor_components(
            campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,entity_uid TEXT NOT NULL,component_uid TEXT NOT NULL,
            component_kind_uid TEXT NOT NULL,current_integrity INTEGER NOT NULL CHECK(current_integrity>=0),
            maximum_integrity INTEGER NOT NULL CHECK(maximum_integrity>0),state_version INTEGER NOT NULL CHECK(state_version>=1),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid,component_uid),CHECK(current_integrity<=maximum_integrity),
            FOREIGN KEY(campaign_id,entity_kind_uid,entity_uid) REFERENCES mechanical_actor_states(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS mechanical_actor_tracks(
            campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,entity_uid TEXT NOT NULL,track_uid TEXT NOT NULL,
            current_value INTEGER NOT NULL,state_version INTEGER NOT NULL CHECK(state_version>=1),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid,track_uid),
            FOREIGN KEY(campaign_id,entity_kind_uid,entity_uid) REFERENCES mechanical_actor_states(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS aggregate_combat_populations(
            campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,entity_uid TEXT NOT NULL,display_name TEXT NOT NULL,
            total_count INTEGER NOT NULL CHECK(total_count>0),active_count INTEGER NOT NULL CHECK(active_count>=0),
            wounded_count INTEGER NOT NULL CHECK(wounded_count>=0),eliminated_count INTEGER NOT NULL CHECK(eliminated_count>=0),
            state_version INTEGER NOT NULL CHECK(state_version>=1),generation_provenance_uid TEXT NOT NULL,
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid),
            CHECK(active_count+wounded_count+eliminated_count<=total_count),
            FOREIGN KEY(campaign_id,entity_kind_uid,entity_uid) REFERENCES mechanical_actor_states(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("""CREATE TABLE IF NOT EXISTS aggregate_combat_conditions(
            campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,entity_uid TEXT NOT NULL,condition_uid TEXT NOT NULL,
            affected_count INTEGER NOT NULL CHECK(affected_count>=0),state_version INTEGER NOT NULL CHECK(state_version>=1),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid,condition_uid),
            FOREIGN KEY(campaign_id,entity_kind_uid,entity_uid) REFERENCES aggregate_combat_populations(campaign_id,entity_kind_uid,entity_uid))""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_mechanical_actor_lookup ON mechanical_actor_states(campaign_id,entity_uid,entity_kind_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_aggregate_display_name ON aggregate_combat_populations(campaign_id,display_name)")
        db.execSQL("INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes) VALUES('$PHASE50_MECHANICAL_SCHEMA_MIGRATION_ID',strftime('%s','now'),'Canonical persistent mechanical state for world actors and bounded aggregate combat; templates are materialized once and gameplay writes require TurnTransaction')")
    }
}

internal data class MechanicalActorSeed(
    val ref:DomainRef,
    val kind:MechanicalActorKind,
    val templateUid:String,
    val seedUid:String,
    val provenanceUid:String,
    val attributes:Map<String,Long>,
    val resources:List<MechanicalResource>,
    val abilities:Set<String>,
    val traits:Set<String> = emptySet(),
    val resistances:Map<String,Long> = emptyMap(),
    val aggregateName:String? = null,
    val aggregateCount:Long? = null
)

/** Canonical Phase50 owner. It assumes its caller already owns ADMIN or TURN authority. */
internal class MechanicalActorStateStore(private val db:SQLiteDatabase,private val campaignUid:String){
    init{require(campaignUid.isNotBlank())}

    fun materializeIfMissing(seed:MechanicalActorSeed){
        require(seed.ref.kindUid.isNotBlank()&&seed.ref.uid.isNotBlank()&&seed.attributes.isNotEmpty()&&seed.abilities.isNotEmpty())
        val inserted=db.compileStatement("INSERT OR IGNORE INTO mechanical_actor_states(campaign_id,entity_kind_uid,entity_uid,actor_kind_uid,materialization_uid,template_uid,generation_seed_uid,generation_provenance_uid,state_version,updated_order) VALUES(?,?,?,?,?,?,?,?,1,0)").use{
            it.bindString(1,campaignUid);it.bindString(2,seed.ref.kindUid);it.bindString(3,seed.ref.uid);it.bindString(4,seed.kind.name)
            it.bindString(5,MechanicalStateMaterialization.FULL.name);it.bindString(6,seed.templateUid);it.bindString(7,seed.seedUid);it.bindString(8,seed.provenanceUid)
            it.executeInsert()!=-1L
        }
        if(!inserted)return
        seed.attributes.toSortedMap().forEach{(uid,value)->db.execSQL(
            "INSERT INTO mechanical_actor_attributes(campaign_id,entity_kind_uid,entity_uid,attribute_uid,current_value,state_version) VALUES(?,?,?,?,?,1)",
            arrayOf<Any?>(campaignUid,seed.ref.kindUid,seed.ref.uid,uid,value))}
        seed.resources.sortedBy{it.resourceUid}.forEach{resource->db.execSQL(
            "INSERT INTO mechanical_actor_resources(campaign_id,entity_kind_uid,entity_uid,resource_uid,current_value,maximum_value,state_version) VALUES(?,?,?,?,?,?,1)",
            arrayOf<Any?>(campaignUid,seed.ref.kindUid,seed.ref.uid,resource.resourceUid,resource.current,resource.maximum))}
        seed.abilities.sorted().forEach{uid->db.execSQL("INSERT INTO mechanical_actor_abilities(campaign_id,entity_kind_uid,entity_uid,ability_uid,state_version) VALUES(?,?,?,?,1)",arrayOf(campaignUid,seed.ref.kindUid,seed.ref.uid,uid))}
        seed.traits.sorted().forEach{uid->db.execSQL("INSERT INTO mechanical_actor_traits(campaign_id,entity_kind_uid,entity_uid,trait_uid,state_version) VALUES(?,?,?,?,1)",arrayOf(campaignUid,seed.ref.kindUid,seed.ref.uid,uid))}
        seed.resistances.toSortedMap().forEach{(uid,value)->db.execSQL("INSERT INTO mechanical_actor_resistances(campaign_id,entity_kind_uid,entity_uid,resistance_uid,basis_points,state_version) VALUES(?,?,?,?,?,1)",arrayOf<Any?>(campaignUid,seed.ref.kindUid,seed.ref.uid,uid,value))}
        if(seed.aggregateCount!=null){
            require(seed.kind in setOf(MechanicalActorKind.GROUP,MechanicalActorKind.UNIT)&&!seed.aggregateName.isNullOrBlank()&&seed.aggregateCount>0)
            db.execSQL("INSERT INTO aggregate_combat_populations(campaign_id,entity_kind_uid,entity_uid,display_name,total_count,active_count,wounded_count,eliminated_count,state_version,generation_provenance_uid) VALUES(?,?,?,?,?,?,0,0,1,?)",
                arrayOf<Any?>(campaignUid,seed.ref.kindUid,seed.ref.uid,seed.aggregateName,seed.aggregateCount,seed.aggregateCount,seed.provenanceUid))
        }
    }

    fun actor(ref:DomainRef):MechanicalActorView?{
        val header=db.rawQuery("SELECT actor_kind_uid,materialization_uid,generation_provenance_uid,state_version FROM mechanical_actor_states WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? LIMIT 1",arrayOf(campaignUid,ref.kindUid,ref.uid)).use{c->
            if(!c.moveToFirst())null else listOf(c.getString(0),c.getString(1),c.getString(2),c.getLong(3))
        }?:return null
        val attributes=longMap("mechanical_actor_attributes","attribute_uid","current_value",ref)
        val tracks=longMap("mechanical_actor_tracks","track_uid","current_value",ref)
        val wound=(tracks["WOUND"]?:0L).coerceAtLeast(0)
        val allComponents=components(ref)
        val equipmentDamage=allComponents.filter{it.kind=="EQUIPMENT"}.sumOf{(it.maximum-it.current).coerceAtLeast(0)}
        val effectiveAttributes=attributes.toMutableMap().apply{
            get("DEFENCE")?.let{put("DEFENCE",(it-wound).coerceAtLeast(0))}
            if(containsKey("ARMOR"))put("ARMOR",(getValue("ARMOR")-equipmentDamage).coerceAtLeast(0))
            tracks.filterKeys{it in setOf("MORALE","COHESION","FORMATION")}.forEach(::put)
        }
        val resources=mutableListOf<MechanicalResource>()
        db.rawQuery("SELECT resource_uid,current_value,maximum_value FROM mechanical_actor_resources WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? ORDER BY resource_uid",arrayOf(campaignUid,ref.kindUid,ref.uid)).use{c->while(c.moveToNext())resources+=MechanicalResource(c.getString(0),c.getLong(1),c.getLong(2))}
        val abilities=stringSet("mechanical_actor_abilities","ability_uid",ref)
        val traits=stringSet("mechanical_actor_traits","trait_uid",ref)
        val resistances=longMap("mechanical_actor_resistances","resistance_uid","basis_points",ref)
        val conditions=mutableListOf<MechanicalCondition>()
        if(tableExists("active_combat_effects"))db.rawQuery("SELECT effect_key,magnitude FROM active_combat_effects WHERE entity_uid=? AND status='active' AND effect_key LIKE 'CONDITION:%' ORDER BY effect_key",arrayOf(ref.uid)).use{c->while(c.moveToNext())conditions+=MechanicalCondition(c.getString(0).substringAfter("CONDITION:"),c.getDouble(1).toLong().coerceAtLeast(1))}
        if(wound>0)conditions+=MechanicalCondition("WOUND",wound)
        val position=position(ref.uid)
        return MechanicalActorView(
            campaignUid,ref,MechanicalActorKind.valueOf(header[0] as String),header[3] as Long,
            MechanicalStateMaterialization.valueOf(header[1] as String),effectiveAttributes,resources,abilities,traits,resistances,
            allComponents.filter{it.kind=="EQUIPMENT"}.map{DomainRef("MECHANICAL_COMPONENT",it.uid)},conditions,position?.let{DomainRef("POSITION",ref.uid)},header[2] as String,population(ref)
        )
    }

    fun population(ref:DomainRef):AggregateMechanicalPopulation?{
        val base=db.rawQuery("SELECT total_count,active_count,wounded_count,eliminated_count FROM aggregate_combat_populations WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? LIMIT 1",arrayOf(campaignUid,ref.kindUid,ref.uid)).use{c->
            if(!c.moveToFirst())null else longArrayOf(c.getLong(0),c.getLong(1),c.getLong(2),c.getLong(3))
        }?:return null
        val conditions=longMap("aggregate_combat_conditions","condition_uid","affected_count",ref)
        return AggregateMechanicalPopulation(base[0],base[1],base[2],base[3],conditions)
    }

    fun aggregateTargets(phrase:String):List<Pair<String,DomainRef>>{
        if(phrase.isBlank())return emptyList()
        val out=mutableListOf<Pair<String,DomainRef>>()
        db.rawQuery("SELECT display_name,entity_kind_uid,entity_uid FROM aggregate_combat_populations WHERE campaign_id=? AND lower(display_name) LIKE lower(?) ORDER BY display_name,entity_uid LIMIT 100",arrayOf(campaignUid,"%$phrase%")).use{c->while(c.moveToNext())out+=c.getString(0) to DomainRef(c.getString(1),c.getString(2))}
        return out
    }

    fun applyWound(identity:TurnTransactionIdentity,changeUid:String,change:WoundChange,effectiveOrder:Long)=applyTrack(identity,changeUid,MechanicalTrackChange(change.subject,"WOUND",change.severityDelta),effectiveOrder)

    fun applyResource(identity:TurnTransactionIdentity,changeUid:String,change:ResourceChange,effectiveOrder:Long){
        requireTurn(identity);requireActor(change.subject)
        val current=db.rawQuery("SELECT current_value,maximum_value FROM mechanical_actor_resources WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND resource_uid=? LIMIT 1",
            arrayOf(campaignUid,change.subject.kindUid,change.subject.uid,change.resourceUid)).use{c->if(!c.moveToFirst())null else c.getLong(0) to c.getLong(1)}
            ?:error("RPGOS-P50:MECHANICAL_RESOURCE_MISSING:${change.resourceUid}")
        val next=Math.addExact(current.first,change.delta.units);require(next in 0..current.second){"RPGOS-P50:MECHANICAL_RESOURCE_OUT_OF_RANGE:${change.resourceUid}"}
        db.execSQL("UPDATE mechanical_actor_resources SET current_value=?,state_version=state_version+1 WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND resource_uid=?",
            arrayOf<Any?>(next,campaignUid,change.subject.kindUid,change.subject.uid,change.resourceUid));bump(change.subject,effectiveOrder)
    }

    fun applySpatial(identity:TurnTransactionIdentity,changeUid:String,change:SpatialChange,effectiveOrder:Long){
        requireTurn(identity)
        val destination=change.destinationLocation
        val changed=if(destination!=null){
            db.compileStatement("UPDATE entity_positions SET location_uid=?,x_coord=?,y_coord=?,last_updated_day=?,updated_chapter=? WHERE entity_uid=?").use{
                it.bindString(1,destination.uid);it.bindDouble(2,change.deltaXMillimetres.toDouble());it.bindDouble(3,change.deltaYMillimetres.toDouble())
                it.bindLong(4,effectiveOrder);it.bindLong(5,effectiveOrder);it.bindString(6,change.subject.uid);it.executeUpdateDelete()
            }
        }else db.compileStatement("UPDATE entity_positions SET x_coord=COALESCE(x_coord,0)+?,y_coord=COALESCE(y_coord,0)+?,last_updated_day=?,updated_chapter=? WHERE entity_uid=?").use{
            it.bindDouble(1,change.deltaXMillimetres.toDouble());it.bindDouble(2,change.deltaYMillimetres.toDouble());it.bindLong(3,effectiveOrder);it.bindLong(4,effectiveOrder);it.bindString(5,change.subject.uid);it.executeUpdateDelete()
        }
        if(changed==0)db.execSQL("INSERT INTO entity_positions(entity_uid,location_uid,x_coord,y_coord,last_updated_day,updated_chapter) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(change.subject.uid,destination?.uid,change.deltaXMillimetres.toDouble(),change.deltaYMillimetres.toDouble(),effectiveOrder,effectiveOrder))
        bumpIfMaterialized(change.subject,effectiveOrder)
    }

    fun applyEquipmentIntegrity(identity:TurnTransactionIdentity,changeUid:String,change:EquipmentIntegrityChange,effectiveOrder:Long)=
        applyComponent(identity,change.subject,change.componentUid,"EQUIPMENT",change.damageDelta.units,effectiveOrder)

    fun applyStructureIntegrity(identity:TurnTransactionIdentity,changeUid:String,change:StructureIntegrityChange,effectiveOrder:Long)=
        applyComponent(identity,change.subject,change.componentUid?:"STRUCTURE", "STRUCTURE",change.damageDelta.units,effectiveOrder)

    fun applyTrack(identity:TurnTransactionIdentity,changeUid:String,change:MechanicalTrackChange,effectiveOrder:Long){
        requireTurn(identity);requireActor(change.subject)
        val current=track(change.subject,change.trackUid)
        val initial=if(change.trackUid in setOf("MORALE","COHESION","FORMATION"))10_000L else 0L
        val next=Math.addExact(current?:initial,change.delta.units);require(next>=0){"RPGOS-P50:NEGATIVE_MECHANICAL_TRACK:${change.trackUid}"}
        upsertTrack(change.subject,change.trackUid,next);bump(change.subject,effectiveOrder)
    }

    fun applyAggregate(identity:TurnTransactionIdentity,changeUid:String,change:AggregatePopulationChange,effectiveOrder:Long){
        requireTurn(identity)
        val current=population(change.subject)?:error("RPGOS-P50:AGGREGATE_STATE_MISSING:${change.subject.uid}")
        require(change.eliminatedDelta>=0&&change.woundedDelta>=0&&change.conditionAffectedDelta>=0)
        require(change.eliminatedDelta+change.woundedDelta<=current.activeCount){"RPGOS-P50:AGGREGATE_OVERCONSUMPTION"}
        val active=current.activeCount-change.eliminatedDelta-change.woundedDelta
        val wounded=Math.addExact(current.woundedCount,change.woundedDelta)
        val eliminated=Math.addExact(current.eliminatedCount,change.eliminatedDelta)
        require(active+wounded+eliminated<=current.totalCount){"RPGOS-P50:AGGREGATE_CONSERVATION"}
        db.execSQL("UPDATE aggregate_combat_populations SET active_count=?,wounded_count=?,eliminated_count=?,state_version=state_version+1 WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",
            arrayOf<Any?>(active,wounded,eliminated,campaignUid,change.subject.kindUid,change.subject.uid))
        change.conditionUid?.let{condition->
            val prior=current.conditionCounts[condition]?:0L;val next=Math.addExact(prior,change.conditionAffectedDelta)
            require(next<=current.totalCount){"RPGOS-P50:AGGREGATE_CONDITION_OVERFLOW"}
            db.updateOrInsertCompat(
                "UPDATE aggregate_combat_conditions SET affected_count=?,state_version=state_version+1 WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND condition_uid=?",
                arrayOf<Any?>(next,campaignUid,change.subject.kindUid,change.subject.uid,condition),
                "INSERT INTO aggregate_combat_conditions(campaign_id,entity_kind_uid,entity_uid,condition_uid,affected_count,state_version) VALUES(?,?,?,?,?,1)",
                arrayOf<Any?>(campaignUid,change.subject.kindUid,change.subject.uid,condition,next))
        }
        bump(change.subject,effectiveOrder)
    }

    private data class Component(val uid:String,val kind:String,val current:Long,val maximum:Long)
    private fun components(ref:DomainRef):List<Component>{val out=mutableListOf<Component>();db.rawQuery("SELECT component_uid,component_kind_uid,current_integrity,maximum_integrity FROM mechanical_actor_components WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? ORDER BY component_uid",arrayOf(campaignUid,ref.kindUid,ref.uid)).use{c->while(c.moveToNext())out+=Component(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3))};return out}
    private fun applyComponent(identity:TurnTransactionIdentity,subject:DomainRef,componentUid:String,kind:String,damage:Long,effectiveOrder:Long){
        requireTurn(identity);require(damage>0&&componentUid.isNotBlank());requireActor(subject)
        val existing=components(subject).singleOrNull{it.uid==componentUid};val maximum=existing?.maximum?:10_000L;val current=existing?.current?:maximum
        val next=Math.subtractExact(current,damage);require(next>=0){"RPGOS-P50:COMPONENT_INTEGRITY_EXHAUSTED:$componentUid"}
        db.updateOrInsertCompat(
            "UPDATE mechanical_actor_components SET current_integrity=?,state_version=state_version+1 WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND component_uid=?",
            arrayOf<Any?>(next,campaignUid,subject.kindUid,subject.uid,componentUid),
            "INSERT INTO mechanical_actor_components(campaign_id,entity_kind_uid,entity_uid,component_uid,component_kind_uid,current_integrity,maximum_integrity,state_version) VALUES(?,?,?,?,?,?,?,1)",
            arrayOf<Any?>(campaignUid,subject.kindUid,subject.uid,componentUid,kind,next,maximum))
        bump(subject,effectiveOrder)
    }
    private fun bump(ref:DomainRef,order:Long){db.execSQL("UPDATE mechanical_actor_states SET state_version=state_version+1,updated_order=? WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",arrayOf<Any?>(order,campaignUid,ref.kindUid,ref.uid))}
    private fun bumpIfMaterialized(ref:DomainRef,order:Long){db.execSQL("UPDATE mechanical_actor_states SET state_version=state_version+1,updated_order=? WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",arrayOf<Any?>(order,campaignUid,ref.kindUid,ref.uid))}
    private fun requireActor(ref:DomainRef){require(actor(ref)!=null){"RPGOS-P50:MECHANICAL_ACTOR_NOT_MATERIALIZED:${ref.kindUid}:${ref.uid}"}}
    private fun requireTurn(identity:TurnTransactionIdentity){require(identity.campaignUid==campaignUid&&db.inTransaction()){ "RPGOS-P50:MECHANICAL_WRITE_OUTSIDE_TURN" }}
    private fun track(ref:DomainRef,uid:String)=db.rawQuery("SELECT current_value FROM mechanical_actor_tracks WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND track_uid=? LIMIT 1",arrayOf(campaignUid,ref.kindUid,ref.uid,uid)).use{if(it.moveToFirst())it.getLong(0)else null}
    private fun upsertTrack(ref:DomainRef,uid:String,value:Long)=db.updateOrInsertCompat(
        "UPDATE mechanical_actor_tracks SET current_value=?,state_version=state_version+1 WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND track_uid=?",arrayOf<Any?>(value,campaignUid,ref.kindUid,ref.uid,uid),
        "INSERT INTO mechanical_actor_tracks(campaign_id,entity_kind_uid,entity_uid,track_uid,current_value,state_version) VALUES(?,?,?,?,?,1)",arrayOf<Any?>(campaignUid,ref.kindUid,ref.uid,uid,value))
    private fun longMap(table:String,key:String,value:String,ref:DomainRef):Map<String,Long>{val out=linkedMapOf<String,Long>();db.rawQuery("SELECT $key,$value FROM $table WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? ORDER BY $key",arrayOf(campaignUid,ref.kindUid,ref.uid)).use{c->while(c.moveToNext())out[c.getString(0)]=c.getLong(1)};return out}
    private fun stringSet(table:String,column:String,ref:DomainRef):Set<String>{val out=linkedSetOf<String>();db.rawQuery("SELECT $column FROM $table WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? ORDER BY $column",arrayOf(campaignUid,ref.kindUid,ref.uid)).use{c->while(c.moveToNext())out+=c.getString(0)};return out}
    private fun position(entityUid:String):CombatPosition?=if(!tableExists("entity_positions"))null else db.rawQuery("SELECT location_uid,x_coord,y_coord FROM entity_positions WHERE entity_uid=? LIMIT 1",arrayOf(entityUid)).use{c->if(!c.moveToFirst())null else when{!c.isNull(1)&&!c.isNull(2)->CombatPosition.Exact(c.getDouble(1).toLong(),c.getDouble(2).toLong());!c.isNull(0)->CombatPosition.Zone(c.getString(0));else->null}}
    private fun tableExists(name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(name)).use{it.moveToFirst()}
}

/** Administrative generation/materialization boundary. It never reads player power. */
internal object WorldActorMechanicalBootstrap{
    fun materialize(worldDb:SQLiteDatabase,saveDb:SQLiteDatabase,campaignUid:String){
        val ownsTransaction=!saveDb.inTransaction()
        if(ownsTransaction)saveDb.beginTransaction()
        try{
        Phase50MechanicalSchema.ensureReady(saveDb)
        val store=MechanicalActorStateStore(saveDb,campaignUid)
        ActivePlayerStore(saveDb,campaignUid).active()?.let{active->
            val statStore=StatResourceStore(saveDb,campaignUid)
            val keys=statStore.statDefinitions().associate{it.statUid to it.key.uppercase()}
            val raw=statStore.playerStats(active.playerUid).associate{(keys[it.statUid]?:it.statUid.uppercase()) to it.baseValue.toLong()}
            val fallback=raw.values.sorted().let{values->if(values.isEmpty())50L else values[values.size/2]}
            fun canonical(vararg hints:String)=raw.entries.firstOrNull{entry->hints.any{it in entry.key}}?.value?:fallback
            val attributes=raw+mapOf("POWER" to canonical("POWER","STRENGTH","ATTACK"),"SKILL" to canonical("SKILL","DEXTERITY","ACCURACY"),
                "DEFENCE" to canonical("DEFENCE","DEFENSE","ARMOR"),"AGILITY" to canonical("AGILITY","SPEED","REFLEX"),"ARMOR" to canonical("ARMOR","DEFENCE","DEFENSE"))
            val resources=statStore.playerResources(active.playerUid).map{MechanicalResource(it.resourceUid,it.currentValue.toLong().coerceAtLeast(0),it.currentValue.toLong().coerceAtLeast(1))}
            val abilities=(SkillStore(saveDb,campaignUid).playerSkills(active.playerUid).map{it.skillUid}+TechniqueStore(saveDb,campaignUid).playerTechniques(active.playerUid).map{it.techniqueUid}+"ATTACK").toSet()
            store.materializeIfMissing(MechanicalActorSeed(DomainRef("PLAYER",active.playerUid),MechanicalActorKind.ACTIVE_PLAYER,"PLAYER-DOMAIN",active.playerUid,
                "PLAYER-DOMAIN:${active.playerUid}",attributes,resources,abilities))
        }
        CanonCharacterProjectionReader(worldDb).list("").forEach{npc->store.materializeIfMissing(seed(DomainRef("NPC",npc.uid),MechanicalActorKind.NPC,npc.name,null))}
        WorldReader(worldDb,saveDb).locations().forEach{location->store.materializeIfMissing(seed(DomainRef("LOCATION",location.uid),MechanicalActorKind.WORLD_ACTOR,location.name,null))}
        materializeCampaignProjectionActors(saveDb,campaignUid,store)
        if(tableExists(worldDb,"organization_units"))worldDb.rawQuery("SELECT unit_uid,name,command_level FROM organization_units ORDER BY unit_uid",null).use{c->while(c.moveToNext()){
            val count=(c.getLong(2).coerceAtLeast(1)*20).coerceAtLeast(1);store.materializeIfMissing(seed(DomainRef("UNIT",c.getString(0)),MechanicalActorKind.UNIT,c.getString(1),count))
        }}
        if(tableExists(worldDb,"canon_summon_groups"))worldDb.rawQuery("SELECT summon_uid,name FROM canon_summon_groups ORDER BY summon_uid",null).use{c->while(c.moveToNext()){
            val uid=c.getString(0);val count=25L+(stable(uid,"POPULATION")%76L);store.materializeIfMissing(seed(DomainRef("GROUP",uid),MechanicalActorKind.GROUP,c.getString(1),count))
        }}
        if(tableExists(saveDb,"army_positions"))saveDb.rawQuery("SELECT force_uid,troop_count FROM army_positions WHERE troop_count>0 ORDER BY force_uid",null).use{c->while(c.moveToNext()){
            val count=c.getDouble(1).toLong().coerceAtLeast(1);store.materializeIfMissing(seed(DomainRef("UNIT",c.getString(0)),MechanicalActorKind.UNIT,c.getString(0),count))
        }}
        if(ownsTransaction)saveDb.setTransactionSuccessful()
        }finally{if(ownsTransaction&&saveDb.inTransaction())saveDb.endTransaction()}
    }

    /**
     * Campaign-created actors become mechanically usable on the next administrative preparation
     * of the campaign. Their public world projection proves identity and location; generic combat
     * values remain deterministic and never depend on the active player's power.
     */
    internal fun materializeCampaignProjectionActors(
        saveDb:SQLiteDatabase,
        campaignUid:String,
        store:MechanicalActorStateStore=MechanicalActorStateStore(saveDb,campaignUid)
    ){
        if(!CampaignWorldProjectionSchema.isReady(saveDb))return
        val activePlayer=if(tableExists(saveDb,"active_player_ref"))ActivePlayerStore(saveDb,campaignUid).active() else null
        val playerPosition=activePlayer?.let{active->saveDb.rawQuery(
            "SELECT location_uid,x_coord,y_coord FROM entity_positions WHERE entity_uid=? LIMIT 1",arrayOf(active.playerUid)
        ).use{cursor->if(!cursor.moveToFirst())null else Triple(
            if(cursor.isNull(0))null else cursor.getString(0),
            if(cursor.isNull(1))null else cursor.getDouble(1),
            if(cursor.isNull(2))null else cursor.getDouble(2)
        )}}
        saveDb.rawQuery("""SELECT element_kind_uid,element_uid,display_name,parent_anchor_uid
            FROM ${CampaignWorldProjectionSchema.TABLE}
            WHERE campaign_id=? AND audience_scope_uid=? AND element_kind_uid IN ('ACTOR','GROUP')
              AND display_name IS NOT NULL ORDER BY element_kind_uid,element_uid""",
            arrayOf(campaignUid,CampaignWorldAudience.PLAYER_VISIBLE)
        ).use{cursor->while(cursor.moveToNext()){
            val kind=cursor.getString(0)
            val ref=DomainRef(kind,cursor.getString(1))
            val population=if(kind=="GROUP")1L+(stable(ref.uid,"POPULATION")%20L) else null
            store.materializeIfMissing(seed(
                ref,if(kind=="GROUP")MechanicalActorKind.GROUP else MechanicalActorKind.NPC,
                cursor.getString(2),population
            ))
            val parent=if(cursor.isNull(3))null else cursor.getString(3)?.takeIf(String::isNotBlank)
            if(parent!=null&&tableExists(saveDb,"entity_positions")){
                val sameScene=playerPosition?.first==parent
                val x=playerPosition?.second.takeIf{sameScene}
                val y=playerPosition?.third.takeIf{sameScene}
                saveDb.execSQL("""INSERT OR IGNORE INTO entity_positions
                    (entity_uid,location_uid,x_coord,y_coord,last_updated_day,updated_chapter)
                    VALUES(?,?,?,?,0,0)""",arrayOf<Any?>(ref.uid,parent,x,y))
            }
        }}
    }

    private fun seed(ref:DomainRef,kind:MechanicalActorKind,name:String,population:Long?):MechanicalActorSeed{
        val seed="RPGOS-P50:${ref.kindUid}:${ref.uid}"
        fun value(uid:String,min:Long,max:Long)=min+(stable(seed,uid)%(max-min+1))
        return MechanicalActorSeed(ref,kind,"RPGOS-GENERIC-COMBATANT-V2",seed,"RPGOS-P50-MATERIALIZED:$seed",
            mapOf("POWER" to value("POWER",30,80),"SKILL" to value("SKILL",30,80),"DEFENCE" to value("DEFENCE",30,80),"AGILITY" to value("AGILITY",30,80),"ARMOR" to value("ARMOR",0,30)),
            listOf(MechanicalResource("HEALTH",100,100),MechanicalResource("STAMINA",100,100)),setOf("ATTACK","STRIKE","DEFEND"),
            aggregateName=population?.let{name},aggregateCount=population)
    }
    private fun stable(a:String,b:String)=MessageDigest.getInstance("SHA-256").digest("$a|$b".toByteArray()).take(8).fold(0L){acc,byte->(acc shl 8) or (byte.toLong() and 0xff)}.ushr(1)
    private fun tableExists(db:SQLiteDatabase,name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",arrayOf(name)).use{it.moveToFirst()}
}

/** Compatibility owner for pre-Phase50 generic conditions/runtime commands. New Phase50 effects do
 * not use this adapter; it remains so older canonical ChangeSets still replay deterministically. */
internal class Phase50MechanicalStateStore(private val db:SQLiteDatabase,private val campaignUid:String){
    fun applyCondition(identity:TurnTransactionIdentity,changeUid:String,change:ConditionChange,effectiveOrder:Long){
        require(identity.campaignUid==campaignUid&&db.inTransaction()){"RPGOS-P50:CONDITION_OUTSIDE_TURN"}
        when(change.operation){
            ConditionOperation.ADD->db.execSQL(
                "INSERT INTO active_combat_effects(active_effect_uid,entity_uid,effect_key,magnitude,started_chapter,status) VALUES(?,?,?,?,?,'active')",
                arrayOf<Any?>("${identity.transactionUid}:$changeUid",change.subject.uid,"CONDITION:${change.conditionUid}",1.0,effectiveOrder))
            ConditionOperation.REMOVE->{
                val statement=db.compileStatement("UPDATE active_combat_effects SET status='removed',remaining_duration_sec=0 WHERE entity_uid=? AND effect_key=? AND status='active'")
                statement.use{it.bindString(1,change.subject.uid);it.bindString(2,"CONDITION:${change.conditionUid}");require(it.executeUpdateDelete()>0){"RPGOS-P50:CONDITION_NOT_ACTIVE"}}
            }
        }
    }

    fun applyRuntime(identity:TurnTransactionIdentity,changeUid:String,change:RuntimeChange,effectiveOrder:Long){
        require(identity.campaignUid==campaignUid&&db.inTransaction()){"RPGOS-P50:RUNTIME_OUTSIDE_TURN"}
        require(change.runtimeCounterUid.startsWith("RPGOS-MECHANICS:")){"RPGOS-P50:UNOWNED_RUNTIME_COUNTER"}
        val kind=change.runtimeCounterUid.removePrefix("RPGOS-MECHANICS:").substringBefore(':')
        require(kind=="MOVEMENT"){"RPGOS-P50:LEGACY_RUNTIME_EFFECT_REJECTED:$kind"}
        val spatial=SpatialChange(change.subject,change.delta.units,0)
        MechanicalActorStateStore(db,campaignUid).applySpatial(identity,changeUid,spatial,effectiveOrder)
    }
}
