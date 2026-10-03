package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

internal const val MECHANICAL_ACTOR_GENESIS_KIND="RPGOS-CHANGE:MECHANICAL_ACTOR_GENESIS"

/** Core's existing generic Phase50 generation profile, now carried by commit/replay rather
 * than a later administrative open. No AI-supplied attributes, abilities or player power. */
data class MechanicalActorGenesisChange(val actor:DomainRef,val displayName:String,val parentAnchorUid:String?,
                                      val worldProofUid:String,val profileVersion:Int=1,
                                      val domainSeed:String?=null,val materialization:MechanicalStateMaterialization=MechanicalStateMaterialization.FULL):PlayerDomainChangePayload {
    init {
        require(actor.kindUid=="ACTOR" || (actor.kindUid=="GROUP" && profileVersion==3));npcUid(actor.uid);npcText(displayName);parentAnchorUid?.let(::npcUid)
        require(profileVersion in 1..3 && worldProofUid.matches(Regex("RPGOS-CORE:WORLD-MATERIALIZATION:[0-9a-f]{64}")))
        require(if(profileVersion==1)domainSeed==null && materialization==MechanicalStateMaterialization.FULL else domainSeed?.matches(Regex("[0-9a-f]{64}"))==true)
        require(profileVersion!=3 || (actor.kindUid=="GROUP" && materialization==MechanicalStateMaterialization.FULL))
    }
}

internal fun mechanicalActorGenesisCodec()=object:TypedPlayerChangeCodec<MechanicalActorGenesisChange>(MechanicalActorGenesisChange::class,
    ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,setOf("actor","name","parent","proof","profile","domain_seed","materialization")) {
    override fun encode(payload:MechanicalActorGenesisChange)=buildJsonObject {
        put("actor",NpcBrainCodec.ref(payload.actor));put("name",payload.displayName)
        put("parent",payload.parentAnchorUid?.let(::JsonPrimitive)?:JsonNull);put("proof",payload.worldProofUid);put("profile",payload.profileVersion)
        if(payload.profileVersion>=2) { put("domain_seed",requireNotNull(payload.domainSeed));put("materialization",payload.materialization.name) }
    }
    override fun decodeKnownFields(obj:JsonObject):MechanicalActorGenesisChange {
        val profile=NpcBrainCodec.integer(obj,"profile")
        require(if(profile==1) "domain_seed" !in obj && "materialization" !in obj
            else profile in 2..3 && "domain_seed" in obj && "materialization" in obj) { "P50:GENESIS_PROFILE_FIELDS" }
        return MechanicalActorGenesisChange(NpcBrainCodec.readRef(obj.getValue("actor")),
            NpcBrainCodec.text(obj,"name"),obj.getValue("parent").takeUnless{it==JsonNull}?.let{NpcBrainCodec.text(obj,"parent")},
            NpcBrainCodec.text(obj,"proof"),profile,obj["domain_seed"]?.let{NpcBrainCodec.text(obj,"domain_seed")},
            obj["materialization"]?.let{enumValueOf<MechanicalStateMaterialization>(NpcBrainCodec.text(obj,"materialization"))}?:MechanicalStateMaterialization.FULL)
    }
    override fun conflictKeys(payload:MechanicalActorGenesisChange)=setOf("P50:GENESIS:${payload.actor}")
}

internal object MechanicalActorGenesis {
    fun from(effect:VerifiedMechanicsCommandEffect):MechanicalActorGenesisChange? {
        if(effect.target.kindUid !in setOf("ACTOR","GROUP") || effect.effectKindUid!="WORLD_ELEMENT_MATERIALIZE" ||
            effect.mechanicsOwnerUid!="RPGOS-CORE:WORLD-MATERIALIZER")return null
        if("p63_population_manifest" in effect.canonicalPayload)return null // Existing population owns this member; never create a second body.
        if(effect.target.kindUid=="GROUP" && effect.canonicalPayload["p63_actor_seed"]==null)return null
        val fingerprint=effect.canonicalPayload["draft_fingerprint"]?:return null // legacy effect, not a retroactive grant
        require(fingerprint.matches(Regex("[0-9a-f]{64}")) && effect.proofUid=="RPGOS-CORE:WORLD-MATERIALIZATION:$fingerprint")
        return MechanicalActorGenesisChange(effect.target,requireNotNull(effect.canonicalPayload["display_name"]),
            effect.canonicalPayload["parent_anchor_uid"],effect.proofUid,if(effect.target.kindUid=="GROUP")3 else if(effect.canonicalPayload["p63_actor_seed"]==null)1 else 2,
            effect.canonicalPayload["p63_actor_seed"])
    }
    fun validate(change:MechanicalActorGenesisChange,set:PlayerChangeSet) {
        require(change.actor.uid!=set.actor.actorUid) { "P50:GENESIS_PLAYER_FORBIDDEN" }
        // Bind to the actual same-transaction materialization, never a cache projection or mention.
        val facts=set.changes.filter{it.sourceRuleUid==change.worldProofUid}.mapNotNull{it.payload as? CampaignTruthChange}
            .filter{it.subjectUid==change.actor.uid && it.kind==TruthKind.FACT}
        fun exact(predicate:String,value:String?)=facts.filter{it.predicate==predicate}.let{rows->
            if(value==null)rows.isEmpty() else rows.size==1 && rows.single().objectValue==value}
        require(exact(CampaignWorldFacts.KIND,change.actor.kindUid) && exact(CampaignWorldFacts.NAME,change.displayName) &&
            exact(CampaignWorldFacts.PARENT,change.parentAnchorUid)) { "P50:GENESIS_WITHOUT_WORLD_MATERIALIZATION" }
    }
    fun apply(db:SQLiteDatabase,identity:TurnTransactionIdentity,set:PlayerChangeSet,change:MechanicalActorGenesisChange,order:Long) {
        validate(change,set)
        require(db.inTransaction() && identity.campaignUid==set.campaignUid) { "P50:GENESIS_OUTSIDE_TURN" }
        requireCanonicalGameplayMutation(db,identity.campaignUid)
        if(db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='active_player_ref'",null).use{it.moveToFirst()})
            require(ActivePlayerStore(db,identity.campaignUid).active()?.playerUid!=change.actor.uid) { "P50:GENESIS_ACTIVE_PLAYER_FORBIDDEN" }
        val store=MechanicalActorStateStore(db,identity.campaignUid)
        require(store.actor(change.actor)==null) { "P50:GENESIS_ALREADY_MATERIALIZED" }
        if(change.profileVersion>=2) {
            val skeleton=set.changes.mapNotNull { (it.payload as? WorldSimulationChange)?.skeleton }.singleOrNull()
                ?:Phase63WorldStore(db,identity.campaignUid).root()?.skeleton?:error("P63:GENESIS_ROOT_REQUIRED")
            val facts=set.changes.filter { it.sourceRuleUid==change.worldProofUid }.mapNotNull { it.payload as? CampaignTruthChange }
                .filter { it.subjectUid==change.actor.uid }
            val category=facts.singleOrNull { it.predicate==CampaignWorldFacts.SLOT_CATEGORY }?.objectValue?:facts.single { it.predicate==CampaignWorldFacts.CATEGORY }.objectValue!!
            val ordinal=facts.singleOrNull { it.predicate==CampaignWorldFacts.SLOT_ORDINAL }?.objectValue?.toLong()?:0L
            val slot=LatentWorldSlot(requireNotNull(change.parentAnchorUid),category,enumValueOf<WorldElementBaseKind>(change.actor.kindUid),ordinal)
            require(slot.ref(skeleton)==change.actor && change.domainSeed==skeleton.domainSeed("MECHANICS",slot.canonicalKey)) { "P63:GENESIS_SEED_MISMATCH" }
            require(CoreLatentWorldRules.select(skeleton,slot,facts.single { it.predicate==CampaignWorldFacts.TOPOLOGY }.objectValue!!)!=null) { "P63:GENESIS_RULE_REQUIRED" }
        }
        // Receipt idempotency belongs to TurnTransaction; never overwrite a living actor.
        store.materializeIfMissing(if(change.profileVersion==1)WorldActorMechanicalBootstrap.seed(change.actor,MechanicalActorKind.NPC,change.displayName,null)
            else if(change.profileVersion==3)Phase63ActorGeneration.groupSeed(change.actor,requireNotNull(change.domainSeed),change.displayName)
            else Phase63ActorGeneration.seed(change.actor,requireNotNull(change.domainSeed),change.materialization))
        db.execSQL("UPDATE mechanical_actor_states SET updated_order=? WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",
            arrayOf<Any?>(order,identity.campaignUid,change.actor.kindUid,change.actor.uid))
        change.parentAnchorUid?.let { parent ->
            db.execSQL("""INSERT INTO entity_positions(entity_uid,location_uid,x_coord,y_coord,last_updated_day,updated_chapter)
                VALUES(?,?,NULL,NULL,0,0)""",arrayOf(change.actor.uid,parent))
        }
        // Location anchor is not an exact position: never copy the player's coordinates.
    }
}
