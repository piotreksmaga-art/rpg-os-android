package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

/** Lineage is independent of the processing LOD. A named slot never becomes anonymous again. */
data class WorldPopulationManifest(val aggregate:DomainRef,val originalCount:Long,val seed:String,
    val ruleUid:String=RULE,val version:Int=1) {
    init {
        require(aggregate.kindUid in setOf("GROUP","UNIT") && originalCount in 1..1_000_000)
        require(seed.matches(Regex("[0-9a-f]{64}")) && ruleUid==RULE && version==1)
    }
    val uid:String get()="P63:POPULATION:${phase63Hash("${aggregate.kindUid}|${aggregate.uid}|$seed|$ruleUid")}"
    fun member(ordinal:Long):DomainRef {
        require(ordinal in 0 until originalCount)
        return DomainRef("ACTOR","P63-MEMBER-${phase63Hash("$uid|$ordinal").take(32).uppercase()}")
    }
    companion object { const val RULE="RPGOS-P63:POPULATION_PARTITION:1" }
}

/** No AI-provided stats or inventory: the owners allocate the actual current pools. */
data class WorldPopulationExtraction(val manifestUid:String,val ordinal:Long,val member:DomainRef,
    val displayName:String,val expectedAggregateVersion:Long,val worldProofUid:String) {
    init {
        require(manifestUid.isNotBlank() && ordinal>=0 && member.kindUid=="ACTOR")
        require(displayName.isNotBlank() && displayName.length<=160 && expectedAggregateVersion>0)
        require(worldProofUid.matches(Regex("RPGOS-CORE:WORLD-MATERIALIZATION:[0-9a-f]{64}")))
    }
}

internal object Phase63PopulationCodec {
    private const val AGGREGATE_EVIDENCE="P63:POPULATION-AGGREGATE:"
    fun aggregateEvidence(ref:DomainRef):String {
        require(ref.kindUid in setOf("GROUP","UNIT"))
        return "$AGGREGATE_EVIDENCE${ref.kindUid}:${ref.uid}"
    }
    fun aggregateFromEvidence(value:String):DomainRef? {
        if(!value.startsWith(AGGREGATE_EVIDENCE))return null
        val suffix=value.removePrefix(AGGREGATE_EVIDENCE)
        val kind=suffix.substringBefore(':');val uid=suffix.substringAfter(':',"")
        return if(kind in setOf("GROUP","UNIT") && uid.isNotBlank())DomainRef(kind,uid) else null
    }
    fun manifest(value:WorldPopulationManifest)=buildJsonObject {
        put("aggregate",Phase63WorldCodec.ref(value.aggregate));put("count",value.originalCount)
        put("seed",value.seed);put("rule",value.ruleUid);put("version",value.version)
    }
    fun readManifest(o:JsonObject):WorldPopulationManifest {
        Phase63WorldCodec.keys(o,"aggregate","count","seed","rule","version")
        require(Phase63WorldCodec.number(o,"version")==1L)
        return WorldPopulationManifest(Phase63WorldCodec.readRef(o.getValue("aggregate")),Phase63WorldCodec.number(o,"count"),
            Phase63WorldCodec.text(o,"seed"),Phase63WorldCodec.text(o,"rule"))
    }
    fun extraction(value:WorldPopulationExtraction)=buildJsonObject {
        put("manifest",value.manifestUid);put("ordinal",value.ordinal);put("member",Phase63WorldCodec.ref(value.member))
        put("name",value.displayName);put("expected",value.expectedAggregateVersion);put("proof",value.worldProofUid)
    }
    fun readExtraction(o:JsonObject):WorldPopulationExtraction {
        Phase63WorldCodec.keys(o,"manifest","ordinal","member","name","expected","proof")
        return WorldPopulationExtraction(Phase63WorldCodec.text(o,"manifest"),Phase63WorldCodec.number(o,"ordinal"),
            Phase63WorldCodec.readRef(o.getValue("member")),Phase63WorldCodec.text(o,"name"),Phase63WorldCodec.number(o,"expected"),Phase63WorldCodec.text(o,"proof"))
    }
}

internal object Phase63PopulationSchema {
    const val MANIFESTS="phase63_population_manifests"
    const val MEMBERS="phase63_population_members"
    // This extension is owned by Phase50, not by the world lineage or a retrieval cache.
    const val PARTITIONS="mechanical_population_partitions"
    val worldTables=setOf(MANIFESTS,MEMBERS)
    fun ensureReady(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $MANIFESTS(campaign_uid TEXT NOT NULL,manifest_uid TEXT NOT NULL,
            aggregate_kind_uid TEXT NOT NULL,aggregate_uid TEXT NOT NULL,manifest_canonical TEXT NOT NULL,
            fingerprint TEXT NOT NULL,created_order INTEGER NOT NULL CHECK(created_order>0),
            PRIMARY KEY(campaign_uid,manifest_uid),UNIQUE(campaign_uid,aggregate_kind_uid,aggregate_uid))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $MEMBERS(campaign_uid TEXT NOT NULL,manifest_uid TEXT NOT NULL,
            slot_ordinal INTEGER NOT NULL CHECK(slot_ordinal>=0),member_uid TEXT NOT NULL,transfer_uid TEXT NOT NULL,
            created_order INTEGER NOT NULL CHECK(created_order>0),PRIMARY KEY(campaign_uid,manifest_uid,slot_ordinal),
            UNIQUE(campaign_uid,member_uid),UNIQUE(campaign_uid,transfer_uid))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $PARTITIONS(campaign_id TEXT NOT NULL,entity_kind_uid TEXT NOT NULL,
            entity_uid TEXT NOT NULL,named_count INTEGER NOT NULL CHECK(named_count>0),
            state_version INTEGER NOT NULL CHECK(state_version>0),updated_order INTEGER NOT NULL CHECK(updated_order>0),
            PRIMARY KEY(campaign_id,entity_kind_uid,entity_uid))""")
    }
    fun isReady(db:SQLiteDatabase)= (worldTables+PARTITIONS).all { table->
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use { it.moveToFirst() }
    }
}

internal class WorldPopulationStore(private val db:SQLiteDatabase,private val campaign:String) {
    fun manifest(uid:String):WorldPopulationManifest? {
        if(!Phase63PopulationSchema.isReady(db))return null
        return db.rawQuery("SELECT manifest_canonical,fingerprint FROM ${Phase63PopulationSchema.MANIFESTS} WHERE campaign_uid=? AND manifest_uid=?",
            arrayOf(campaign,uid)).use { c->if(!c.moveToFirst())null else Phase63PopulationCodec.readManifest(Json.parseToJsonElement(c.getString(0)).jsonObject).also {
                require(it.uid==uid && phase63Hash(Phase63PopulationCodec.manifest(it).toString())==c.getString(1)) { "P63:POPULATION_CORRUPT" }
            } }
    }
    fun forAggregate(ref:DomainRef):WorldPopulationManifest? {
        if(!Phase63PopulationSchema.isReady(db))return null
        val uid=db.rawQuery("SELECT manifest_uid FROM ${Phase63PopulationSchema.MANIFESTS} WHERE campaign_uid=? AND aggregate_kind_uid=? AND aggregate_uid=?",
            arrayOf(campaign,ref.kindUid,ref.uid)).use { if(it.moveToFirst())it.getString(0) else null }
        return uid?.let(::manifest)
    }
    /** Conservative legacy adoption is a candidate only. Registration happens with the first
     * accepted interaction, never in a reader. Existing casualties/current pools are retained. */
    fun candidate(ref:DomainRef,skeleton:CampaignWorldSkeleton):WorldPopulationManifest? {
        require(skeleton.campaignUid==campaign)
        forAggregate(ref)?.let { return it }
        if(ref.kindUid !in setOf("GROUP","UNIT"))return null
        require(Phase50PopulationPartition.namedCount(db,campaign,ref)==0L) { "P63:POPULATION_LINEAGE_MISSING" }
        val count=MechanicalActorStateStore(db,campaign).actor(ref)?.aggregatePopulation?.totalCount?.takeIf { it>0 }?:return null
        return WorldPopulationManifest(ref,count,skeleton.domainSeed("POPULATION",ref.toString()))
    }
    fun candidateForDraft(draft:WorldElementDraft,skeleton:CampaignWorldSkeleton):WorldPopulationManifest? {
        require(draft.campaignUid==campaign && skeleton.campaignUid==campaign)
        val existing=draft.sourceEvidenceUids.mapNotNull(::manifest).singleOrNull()
        val value=existing?:draft.sourceEvidenceUids.mapNotNull(Phase63PopulationCodec::aggregateFromEvidence).singleOrNull()?.let { candidate(it,skeleton) }
            ?:return null
        require(value.uid in draft.sourceEvidenceUids && value.member(draft.slotOrdinal)==draft.element) { "P63:POPULATION_FOREIGN_MEMBER" }
        return value
    }
    /** An indexed reverse lookup. Coarse processing does not erase this canonical lineage. */
    fun aggregateForMember(member:DomainRef):DomainRef? {
        if(member.kindUid!="ACTOR" || !Phase63PopulationSchema.isReady(db))return null
        return db.rawQuery("""SELECT p.aggregate_kind_uid,p.aggregate_uid FROM ${Phase63PopulationSchema.MEMBERS} m
            JOIN ${Phase63PopulationSchema.MANIFESTS} p ON p.campaign_uid=m.campaign_uid AND p.manifest_uid=m.manifest_uid
            WHERE m.campaign_uid=? AND m.member_uid=?""",arrayOf(campaign,member.uid)).use { c->
                if(c.moveToFirst())DomainRef(c.getString(0),c.getString(1)) else null
            }
    }
    fun namedMembers(ref:DomainRef,limit:Int=256):List<DomainRef> {
        require(ref.kindUid in setOf("GROUP","UNIT") && limit in 1..256)
        if(!Phase63PopulationSchema.isReady(db))return emptyList()
        return db.rawQuery("""SELECT m.member_uid FROM ${Phase63PopulationSchema.MEMBERS} m
            JOIN ${Phase63PopulationSchema.MANIFESTS} p ON p.campaign_uid=m.campaign_uid AND p.manifest_uid=m.manifest_uid
            WHERE m.campaign_uid=? AND p.aggregate_kind_uid=? AND p.aggregate_uid=? ORDER BY m.member_uid LIMIT ?""",
            arrayOf(campaign,ref.kindUid,ref.uid,(limit+1).toString())).use { c->buildList {
                while(c.moveToNext()) { require(size<limit) { "P63:FORMATION_READ_BUDGET" };add(DomainRef("ACTOR",c.getString(0))) }
            } }
    }
    fun namedOrdinals(uid:String,limit:Int=32):Set<Long> {
        require(limit in 1..512)
        if(!Phase63PopulationSchema.isReady(db))return emptySet()
        return db.rawQuery("SELECT slot_ordinal FROM ${Phase63PopulationSchema.MEMBERS} WHERE campaign_uid=? AND manifest_uid=? ORDER BY slot_ordinal LIMIT ?",
            arrayOf(campaign,uid,limit.toString())).use { c->buildSet { while(c.moveToNext())add(c.getLong(0)) } }
    }
    fun namedBefore(uid:String,ordinal:Long):Long = db.rawQuery("SELECT COUNT(*) FROM ${Phase63PopulationSchema.MEMBERS} WHERE campaign_uid=? AND manifest_uid=? AND slot_ordinal<?",
        arrayOf(campaign,uid,ordinal.toString())).use { it.moveToFirst();it.getLong(0) }
    fun isNamed(uid:String,ordinal:Long):Boolean = db.rawQuery("SELECT 1 FROM ${Phase63PopulationSchema.MEMBERS} WHERE campaign_uid=? AND manifest_uid=? AND slot_ordinal=?",
        arrayOf(campaign,uid,ordinal.toString())).use { it.moveToFirst() }
    fun validateExtraction(value:WorldPopulationExtraction,set:PlayerChangeSet) {
        val facts=set.changes.filter { it.sourceRuleUid==value.worldProofUid }.mapNotNull { it.payload as? CampaignTruthChange }
            .filter { it.subjectUid==value.member.uid && it.kind==TruthKind.FACT }
        fun exact(predicate:String,expected:String)=facts.singleOrNull { it.predicate==predicate }?.objectValue==expected
        require(exact(CampaignWorldFacts.KIND,"ACTOR") && exact(CampaignWorldFacts.NAME,value.displayName)) { "P63:POPULATION_WORLD_EVIDENCE_REQUIRED" }
    }
    fun register(identity:TurnTransactionIdentity,value:WorldPopulationManifest,order:Long) {
        require(db.inTransaction() && identity.campaignUid==campaign);requireCanonicalGameplayMutation(db,campaign)
        val body=MechanicalActorStateStore(db,campaign).actor(value.aggregate)?:error("P63:POPULATION_BODY_REQUIRED")
        require(body.aggregatePopulation?.totalCount==value.originalCount && forAggregate(value.aggregate)==null) { "P63:POPULATION_ORIGIN_MISMATCH" }
        val root=Phase63WorldStore(db,campaign).root()?:error("P63:POPULATION_ROOT_REQUIRED")
        require(value.seed==root.skeleton.domainSeed("POPULATION",value.aggregate.toString())) { "P63:POPULATION_SEED_MISMATCH" }
        val wire=Phase63PopulationCodec.manifest(value).toString()
        db.execSQL("INSERT INTO ${Phase63PopulationSchema.MANIFESTS} VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(campaign,value.uid,value.aggregate.kindUid,value.aggregate.uid,wire,phase63Hash(wire),order))
    }
    fun extract(identity:TurnTransactionIdentity,value:WorldPopulationExtraction,order:Long) {
        require(db.inTransaction() && identity.campaignUid==campaign);requireCanonicalGameplayMutation(db,campaign)
        val manifest=manifest(value.manifestUid)?:error("P63:POPULATION_MANIFEST_REQUIRED")
        require(value.member==manifest.member(value.ordinal) && !isNamed(manifest.uid,value.ordinal)) { "P63:POPULATION_SLOT_ALREADY_NAMED_OR_FOREIGN" }
        Phase50PopulationPartition.extract(db,identity,manifest,value,order)
        val transfer="P63:TRANSFER:${phase63Hash("${identity.commandUid}|${manifest.uid}|${value.ordinal}")}"
        db.execSQL("INSERT INTO ${Phase63PopulationSchema.MEMBERS} VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(campaign,manifest.uid,value.ordinal,value.member.uid,transfer,order))
    }
}

/** Mechanical and inventory ownership stay here/the existing InventoryStore. World lineage
 * never rolls a second person, duplicates equipment, or resurrects an eliminated member. */
internal object Phase50PopulationPartition {
    private const val MEMBER_RULE="RPGOS-P50:POPULATION_MEMBER:1"
    private data class Allocation(val aggregate:MechanicalActorView,val rank:Long,val seed:MechanicalActorSeed,
        val conditions:Set<String>,val wound:Long) {
        fun share(total:Long):Long {
            val count=requireNotNull(aggregate.aggregatePopulation).totalCount
            return total/count+if(rank<total%count)1 else 0
        }
    }
    /** The preview and writer use one allocation, not a generic reroll for an extracted person. */
    private fun allocation(db:SQLiteDatabase,campaign:String,manifest:WorldPopulationManifest,ordinal:Long):Allocation {
        val aggregate=MechanicalActorStateStore(db,campaign).actor(manifest.aggregate)?:error("P63:POPULATION_BODY_REQUIRED")
        val pop=aggregate.aggregatePopulation?:error("P63:POPULATION_COUNTS_REQUIRED")
        require(pop.totalCount>0 && pop.activeCount+pop.woundedCount+pop.eliminatedCount==pop.totalCount) { "P63:POPULATION_ACCOUNTING_INCOMPLETE" }
        require(manifest.originalCount-namedCount(db,campaign,manifest.aggregate)==pop.totalCount) { "P63:POPULATION_LINEAGE_MISMATCH" }
        val populations=WorldPopulationStore(db,campaign)
        require(ordinal in 0 until manifest.originalCount && !populations.isNamed(manifest.uid,ordinal)) { "P63:POPULATION_SLOT_ALREADY_NAMED_OR_FOREIGN" }
        val rank=ordinal-populations.namedBefore(manifest.uid,ordinal)
        require(rank in 0 until pop.totalCount)
        fun share(total:Long)=total/pop.totalCount+if(rank<total%pop.totalCount)1 else 0
        val eliminated=rank<pop.eliminatedCount
        val wounded=!eliminated && rank<pop.eliminatedCount+pop.woundedCount
        val conditions=pop.conditionCounts.filterValues { rank<it }.keys+
            if(eliminated)setOf("INCAPACITATED") else if(wounded)setOf("WOUNDED") else emptySet()
        val raw=db.rawQuery("SELECT attribute_uid,current_value FROM mechanical_actor_attributes WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",
            arrayOf(campaign,manifest.aggregate.kindUid,manifest.aggregate.uid)).use { c->buildMap { while(c.moveToNext())put(c.getString(0),c.getLong(1)) } }
        val wound=aggregate.conditions.singleOrNull { it.conditionUid=="WOUND" }?.intensity?:0L
        val seed=MechanicalActorSeed(manifest.member(ordinal),MechanicalActorKind.NPC,MEMBER_RULE,
            phase63Hash("${manifest.seed}|$ordinal"),"$MEMBER_RULE:${manifest.uid}:$ordinal",raw,
            aggregate.resources.map { MechanicalResource(it.resourceUid,share(it.current),share(it.maximum)) },
            aggregate.executableAbilityUids,aggregate.traitUids,aggregate.resistanceBasisPoints,
            materialization=if(raw.isNotEmpty() && aggregate.executableAbilityUids.isNotEmpty())MechanicalStateMaterialization.FULL else MechanicalStateMaterialization.PARTIAL)
        return Allocation(aggregate,rank,seed,conditions,share(wound))
    }
    fun preview(db:SQLiteDatabase,campaign:String,manifest:WorldPopulationManifest,ordinal:Long):MechanicalActorView {
        val a=allocation(db,campaign,manifest,ordinal)
        val damaged=a.aggregate.equipmentRefs.filter { equipment->assigned(manifest,ordinal,equipment.uid) }
        val damage=damaged.sumOf { equipment->db.rawQuery("SELECT maximum_integrity-current_integrity FROM mechanical_actor_components WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND component_uid=?",
            arrayOf(campaign,manifest.aggregate.kindUid,manifest.aggregate.uid,equipment.uid)).use { if(it.moveToFirst())it.getLong(0).coerceAtLeast(0) else 0L } }
        val attributes=a.seed.attributes.toMutableMap().apply {
            get("DEFENCE")?.let { put("DEFENCE",(it-a.wound).coerceAtLeast(0)) }
            get("ARMOR")?.let { put("ARMOR",(it-damage).coerceAtLeast(0)) }
        }
        return MechanicalActorView(campaign,a.seed.ref,a.seed.kind,1,a.seed.materialization,attributes,a.seed.resources,
            a.seed.abilities,a.seed.traits,a.seed.resistances,damaged,
            a.conditions.sorted().map { MechanicalCondition(it,1) }+if(a.wound>0)listOf(MechanicalCondition("WOUND",a.wound)) else emptyList(),
            a.aggregate.locationRef,a.seed.provenanceUid,unwoundedDefence=a.seed.attributes["DEFENCE"])
    }
    private fun assigned(manifest:WorldPopulationManifest,ordinal:Long,itemUid:String)=
        phase63Hash("${manifest.uid}|$itemUid").take(15).toLong(16)%manifest.originalCount==ordinal
    fun namedCount(db:SQLiteDatabase,campaign:String,ref:DomainRef):Long {
        if(!Phase63PopulationSchema.isReady(db))return 0
        return db.rawQuery("SELECT named_count FROM ${Phase63PopulationSchema.PARTITIONS} WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",
            arrayOf(campaign,ref.kindUid,ref.uid)).use { if(it.moveToFirst())it.getLong(0) else 0 }
    }
    fun extract(db:SQLiteDatabase,identity:TurnTransactionIdentity,manifest:WorldPopulationManifest,value:WorldPopulationExtraction,order:Long) {
        require(db.inTransaction());requireCanonicalGameplayMutation(db,identity.campaignUid)
        val campaign=identity.campaignUid;val store=MechanicalActorStateStore(db,campaign)
        val aggregate=store.actor(manifest.aggregate)?:error("P63:POPULATION_BODY_REQUIRED")
        require(aggregate.stateVersion==value.expectedAggregateVersion && store.actor(value.member)==null) { "P63:POPULATION_STALE_MECHANICS" }
        val allocated=allocation(db,campaign,manifest,value.ordinal)
        val pop=requireNotNull(aggregate.aggregatePopulation)
        val rank=allocated.rank
        fun share(total:Long)=allocated.share(total)
        val resources=allocated.seed.resources
        val eliminated=rank<pop.eliminatedCount
        val wounded=!eliminated && rank<pop.eliminatedCount+pop.woundedCount
        val conditions=allocated.conditions
        store.materializeIfMissing(allocated.seed)
        // Wounds are a current mechanical track, not newly invented past injury events.
        val woundShare=allocated.wound
        if(woundShare>0) {
            db.execSQL("INSERT INTO mechanical_actor_tracks VALUES(?,?,?,'WOUND',?,1)",arrayOf<Any?>(campaign,value.member.kindUid,value.member.uid,woundShare))
            db.execSQL("UPDATE mechanical_actor_tracks SET current_value=current_value-?,state_version=state_version+1 WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND track_uid='WOUND'",
                arrayOf<Any?>(woundShare,campaign,manifest.aggregate.kindUid,manifest.aggregate.uid))
        }
        resources.forEach { resource->
            db.execSQL("""UPDATE mechanical_actor_resources SET current_value=current_value-?,maximum_value=maximum_value-?,state_version=state_version+1
                WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND resource_uid=?""",
                arrayOf<Any?>(resource.current,resource.maximum,campaign,manifest.aggregate.kindUid,manifest.aggregate.uid,resource.resourceUid))
        }
        conditions.sorted().forEach { uid->Phase50MechanicalStateStore(db,campaign).applyCondition(identity,"P63:SLOT:${value.ordinal}:$uid",
            ConditionChange(value.member,uid,ConditionOperation.ADD),order) }
        pop.conditionCounts.filterValues { rank<it }.keys.forEach { uid->db.execSQL("""UPDATE aggregate_combat_conditions
            SET affected_count=affected_count-1,state_version=state_version+1 WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND condition_uid=?""",
            arrayOf(campaign,manifest.aggregate.kindUid,manifest.aggregate.uid,uid)) }
        db.execSQL("""UPDATE aggregate_combat_populations SET active_count=active_count-?,wounded_count=wounded_count-?,
            eliminated_count=eliminated_count-?,state_version=state_version+1 WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?""",
            arrayOf<Any?>(if(!eliminated && !wounded)1L else 0L,if(wounded)1L else 0L,if(eliminated)1L else 0L,campaign,manifest.aggregate.kindUid,manifest.aggregate.uid))
        val count=namedCount(db,campaign,manifest.aggregate)
        if(count==0L)db.execSQL("INSERT INTO ${Phase63PopulationSchema.PARTITIONS} VALUES(?,?,?,?,1,?)",
            arrayOf<Any?>(campaign,manifest.aggregate.kindUid,manifest.aggregate.uid,1L,order))
        else db.execSQL("""UPDATE ${Phase63PopulationSchema.PARTITIONS} SET named_count=named_count+1,state_version=state_version+1,
            updated_order=? WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?""",arrayOf<Any?>(order,campaign,manifest.aggregate.kindUid,manifest.aggregate.uid))
        db.execSQL("UPDATE mechanical_actor_states SET state_version=state_version+1,updated_order=? WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",
            arrayOf<Any?>(order,campaign,manifest.aggregate.kindUid,manifest.aggregate.uid))
        db.execSQL("UPDATE mechanical_actor_states SET updated_order=? WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",
            arrayOf<Any?>(order,campaign,value.member.kindUid,value.member.uid))
        aggregate.locationRef?.let { db.execSQL("INSERT INTO entity_positions VALUES(?,?,NULL,NULL,0,?)",arrayOf<Any?>(value.member.uid,it.uid,order)) }
        // Unique equipment is assigned to immutable slots; it is transferred by its owner.
        val inventory=InventoryStore(db,campaign)
        var recipientRegistered=false
        inventory.typedUnique(manifest.aggregate.uid).forEach { (entry,_)->
            if(assigned(manifest,value.ordinal,entry.itemInstanceUid)) {
                val provenance="P63:PARTITION:${identity.commandUid}:${manifest.uid}:${value.ordinal}"
                EquipmentStore(db,campaign).transferPopulationItem(manifest.aggregate.uid,value.member.uid,entry.itemInstanceUid,provenance)
                val asset=OwnedAssetRef(OWNERSHIP_ASSET_KIND_ITEM_INSTANCE,entry.itemInstanceUid)
                val ownership=OwnershipStore(db,campaign)
                val owned=ownership.currentOwnership(asset).filter { it.owner.ownerUid==manifest.aggregate.uid }
                if(owned.isNotEmpty()) {
                    val recipient=OwnershipOwnerRef("CHARACTER",value.member.uid)
                    if(!recipientRegistered) {
                        val status=db.rawQuery("SELECT reference_status FROM ownership_party_registry WHERE campaign_id=? AND owner_kind_uid=? AND owner_uid=?",
                            arrayOf(campaign,recipient.ownerKindUid,recipient.ownerUid)).use { if(it.moveToFirst())it.getString(0) else null }
                        require(status==null || status=="ACTIVE") { "P63:RETIRED_POPULATION_OWNER" }
                        if(status==null)OwnershipReferenceRegistry(db,campaign).registerOwner(recipient,provenance)
                        recipientRegistered=true
                    }
                    owned.forEach { record->ownership.transferShare("$provenance:${entry.itemInstanceUid}:${record.ownershipTypeUid}",record.owner,recipient,
                        asset,record.ownershipTypeUid,record.share,order,null,provenance) }
                }
                // Preserve the very same damaged equipment component; never copy or heal it.
                db.execSQL("""UPDATE mechanical_actor_components SET entity_kind_uid=?,entity_uid=?,state_version=state_version+1
                    WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND component_uid=? AND component_kind_uid='EQUIPMENT'""",
                    arrayOf(value.member.kindUid,value.member.uid,campaign,manifest.aggregate.kindUid,manifest.aggregate.uid,entry.itemInstanceUid))
            }
        }
        inventory.typedStacks(manifest.aggregate.uid).forEach { stack->
            val units=share(stack.quantity)
            if(units>0)inventory.transferStack(manifest.aggregate.uid,value.member.uid,stack.itemDefinitionUid,units,"P63:PARTITION:${identity.commandUid}")
        }
    }
}
