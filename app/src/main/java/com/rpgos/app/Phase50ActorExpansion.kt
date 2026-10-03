package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

/** The world asks the existing mechanical owner to fill missing components. This is neither
 * a new identity nor a reroll. The seed must already have been canonically committed. */
data class MechanicalActorExpansion(val actor:DomainRef,val expectedStateVersion:Long,
    val to:MechanicalStateMaterialization) {
    init { require(actor.kindUid=="ACTOR" && expectedStateVersion>0 && to!=MechanicalStateMaterialization.SEED_ONLY) }
}

internal object Phase50ActorExpansion {
    fun encode(value:MechanicalActorExpansion)=buildJsonObject {
        put("actor",Phase63WorldCodec.ref(value.actor));put("expected",value.expectedStateVersion);put("to",value.to.name)
    }
    fun decode(value:JsonObject):MechanicalActorExpansion {
        Phase63WorldCodec.keys(value,"actor","expected","to")
        return MechanicalActorExpansion(Phase63WorldCodec.readRef(value.getValue("actor")),Phase63WorldCodec.number(value,"expected"),
            enumValueOf<MechanicalStateMaterialization>(Phase63WorldCodec.text(value,"to")))
    }
    private fun seed(db:SQLiteDatabase,campaign:String,actor:DomainRef):String? =
        db.rawQuery("SELECT generation_seed_uid,template_uid FROM mechanical_actor_states WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",
            arrayOf(campaign,actor.kindUid,actor.uid)).use { c->
            if(!c.moveToFirst() || c.getString(1)!=Phase63ActorGeneration.RULE)null else c.getString(0)
        }

    fun preview(db:SQLiteDatabase,campaign:String,actor:MechanicalActorView):MechanicalActorView? {
        if(actor.materialization==MechanicalStateMaterialization.FULL)return actor
        val domainSeed=seed(db,campaign,actor.actor)?:return null
        val generated=Phase63ActorGeneration.seed(actor.actor,domainSeed,MechanicalStateMaterialization.FULL)
        val current=actor.resources.associateBy { it.resourceUid }
        val missing=generated.attributes.filterKeys { it !in actor.attributes }.toMutableMap()
        missing["DEFENCE"]?.let { raw->missing["DEFENCE"]=(raw-(actor.conditions.singleOrNull { it.conditionUid=="WOUND" }?.intensity?:0L)).coerceAtLeast(0) }
        missing["ARMOR"]?.let { raw->
            val damage=db.rawQuery("""SELECT COALESCE(SUM(maximum_integrity-current_integrity),0) FROM mechanical_actor_components
                WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=? AND component_kind_uid='EQUIPMENT'""",
                arrayOf(campaign,actor.actor.kindUid,actor.actor.uid)).use { it.moveToFirst();it.getLong(0) }
            missing["ARMOR"]=(raw-damage).coerceAtLeast(0)
        }
        return actor.copy(materialization=MechanicalStateMaterialization.FULL,
            attributes=missing+actor.attributes,
            resources=(generated.resources.filter { it.resourceUid !in current }+actor.resources).sortedBy { it.resourceUid },
            executableAbilityUids=generated.abilities+actor.executableAbilityUids,
            unwoundedDefence=actor.unwoundedDefence?:generated.attributes["DEFENCE"])
    }

    fun apply(db:SQLiteDatabase,identity:TurnTransactionIdentity,value:MechanicalActorExpansion,order:Long) {
        require(db.inTransaction());requireCanonicalGameplayMutation(db,identity.campaignUid)
        require(ActivePlayerStore(db,identity.campaignUid).active()?.playerUid!=value.actor.uid) { "P63:PLAYER_GENERATION_FORBIDDEN" }
        val before=MechanicalActorStateStore(db,identity.campaignUid).actor(value.actor)?:error("P63:EXPANSION_ACTOR_MISSING")
        require(before.stateVersion==value.expectedStateVersion && value.to.ordinal>before.materialization.ordinal) { "P63:EXPANSION_STALE_OR_DOWNGRADE" }
        val domainSeed=seed(db,identity.campaignUid,value.actor)?:error("P63:EXPANSION_RULE_UNREGISTERED")
        val generated=Phase63ActorGeneration.seed(value.actor,domainSeed,value.to)
        generated.attributes.toSortedMap().forEach { (uid,amount)->db.execSQL(
            "INSERT OR IGNORE INTO mechanical_actor_attributes VALUES(?,?,?,?,?,1)",
            arrayOf(identity.campaignUid,value.actor.kindUid,value.actor.uid,uid,amount)) }
        generated.resources.forEach { resource->db.execSQL(
            "INSERT OR IGNORE INTO mechanical_actor_resources VALUES(?,?,?,?,?,?,1)",
            arrayOf(identity.campaignUid,value.actor.kindUid,value.actor.uid,resource.resourceUid,resource.current,resource.maximum)) }
        generated.abilities.sorted().forEach { uid->db.execSQL("INSERT OR IGNORE INTO mechanical_actor_abilities VALUES(?,?,?,?,1)",
            arrayOf(identity.campaignUid,value.actor.kindUid,value.actor.uid,uid)) }
        db.execSQL("UPDATE mechanical_actor_states SET materialization_uid=?,state_version=state_version+1,updated_order=? WHERE campaign_id=? AND entity_kind_uid=? AND entity_uid=?",
            arrayOf(value.to.name,order,identity.campaignUid,value.actor.kindUid,value.actor.uid))
    }
}
