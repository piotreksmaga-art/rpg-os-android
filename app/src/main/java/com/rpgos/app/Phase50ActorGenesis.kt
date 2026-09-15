package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

internal const val MECHANICAL_ACTOR_GENESIS_KIND="RPGOS-CHANGE:MECHANICAL_ACTOR_GENESIS"

/** Core's existing generic Phase50 generation profile, now carried by commit/replay rather
 * than a later administrative open. No AI-supplied attributes, abilities or player power. */
data class MechanicalActorGenesisChange(val actor:DomainRef,val displayName:String,val parentAnchorUid:String?,
                                      val worldProofUid:String,val profileVersion:Int=1):PlayerDomainChangePayload {
    init {
        require(actor.kindUid=="ACTOR");npcUid(actor.uid);npcText(displayName);parentAnchorUid?.let(::npcUid)
        require(profileVersion==1 && worldProofUid.matches(Regex("RPGOS-CORE:WORLD-MATERIALIZATION:[0-9a-f]{64}")))
    }
}

internal fun mechanicalActorGenesisCodec()=object:TypedPlayerChangeCodec<MechanicalActorGenesisChange>(MechanicalActorGenesisChange::class,
    ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,setOf("actor","name","parent","proof","profile")) {
    override fun encode(payload:MechanicalActorGenesisChange)=buildJsonObject {
        put("actor",NpcBrainCodec.ref(payload.actor));put("name",payload.displayName)
        put("parent",payload.parentAnchorUid?.let(::JsonPrimitive)?:JsonNull);put("proof",payload.worldProofUid);put("profile",payload.profileVersion)
    }
    override fun decodeKnownFields(obj:JsonObject)=MechanicalActorGenesisChange(NpcBrainCodec.readRef(obj.getValue("actor")),
        NpcBrainCodec.text(obj,"name"),obj.getValue("parent").takeUnless{it==JsonNull}?.let{NpcBrainCodec.text(obj,"parent")},
        NpcBrainCodec.text(obj,"proof"),NpcBrainCodec.integer(obj,"profile"))
    override fun conflictKeys(payload:MechanicalActorGenesisChange)=setOf("P50:GENESIS:${payload.actor}")
}

internal object MechanicalActorGenesis {
    fun from(effect:VerifiedMechanicsCommandEffect):MechanicalActorGenesisChange? {
        if(effect.target.kindUid!="ACTOR" || effect.effectKindUid!="WORLD_ELEMENT_MATERIALIZE" ||
            effect.mechanicsOwnerUid!="RPGOS-CORE:WORLD-MATERIALIZER")return null
        val fingerprint=effect.canonicalPayload["draft_fingerprint"]?:return null // legacy effect, not a retroactive grant
        require(fingerprint.matches(Regex("[0-9a-f]{64}")) && effect.proofUid=="RPGOS-CORE:WORLD-MATERIALIZATION:$fingerprint")
        return MechanicalActorGenesisChange(effect.target,requireNotNull(effect.canonicalPayload["display_name"]),
            effect.canonicalPayload["parent_anchor_uid"],effect.proofUid)
    }
    fun validate(change:MechanicalActorGenesisChange,set:PlayerChangeSet) {
        require(change.actor.uid!=set.actor.actorUid) { "P50:GENESIS_PLAYER_FORBIDDEN" }
        // Bind to the actual same-transaction materialization, never a cache projection or mention.
        val facts=set.changes.filter{it.sourceRuleUid==change.worldProofUid}.mapNotNull{it.payload as? CampaignTruthChange}
            .filter{it.subjectUid==change.actor.uid && it.kind==TruthKind.FACT}
        fun exact(predicate:String,value:String?)=facts.filter{it.predicate==predicate}.let{rows->
            if(value==null)rows.isEmpty() else rows.size==1 && rows.single().objectValue==value}
        require(exact(CampaignWorldFacts.KIND,"ACTOR") && exact(CampaignWorldFacts.NAME,change.displayName) &&
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
        // Receipt idempotency belongs to TurnTransaction; never overwrite a living actor.
        store.materializeIfMissing(WorldActorMechanicalBootstrap.seed(change.actor,MechanicalActorKind.NPC,change.displayName,null))
        db.execSQL("UPDATE mechanical_actor_states SET updated_order=? WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",
            arrayOf<Any?>(order,identity.campaignUid,change.actor.kindUid,change.actor.uid))
        change.parentAnchorUid?.let { parent ->
            db.execSQL("""INSERT INTO entity_positions(entity_uid,location_uid,x_coord,y_coord,last_updated_day,updated_chapter)
                VALUES(?,?,NULL,NULL,0,0)""",arrayOf(change.actor.uid,parent))
        }
        // Location anchor is not an exact position: never copy the player's coordinates.
    }
}
