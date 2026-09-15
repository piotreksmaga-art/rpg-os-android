package com.rpgos.app

import kotlinx.serialization.json.*

/** Reuse the canonical command codec. These are Core proofs, never provider response fields. */
internal object TemporalMechanicsCodec {
    private val codec get()=coreCommandCodecs().getValue(PlayerCommandKinds.APPLY_VERIFIED_MECHANICS)
    fun encode(effects:List<VerifiedMechanicsCommandEffect>):JsonArray {
        require(effects.size<=4096)
        if(effects.isEmpty())return JsonArray(emptyList())
        return JsonArray(effects.map{effect->codec.encodeUntyped(ApplyVerifiedMechanicsCommandPayload("P62:PROCESS",listOf(effect)))
            .getValue("effects").jsonArray.single()})
    }
    fun decode(value:JsonElement):List<VerifiedMechanicsCommandEffect> {
        val rows=value.jsonArray;require(rows.size<=4096)
        return rows.map{row->
            val decoded=codec.decode(buildJsonObject{put("planUid","P62:PROCESS");put("effects",JsonArray(listOf(row)))})
                as ApplyVerifiedMechanicsCommandPayload
            decoded.effects.single()
        }.also { effects->require(effects.map{it.effectUid}.distinct().size==effects.size && encode(effects)==rows) }
    }
}

internal fun VerifiedMechanicsCommandEffect.asStagedMechanics()=VerifiedMechanicsEffect(effectUid,nodeUid,mechanicsOwnerUid,effectKindUid,
    canonicalPayload+mapOf("target_kind_uid" to target.kindUid,"target_uid" to target.uid,"magnitude" to magnitude.toString()),
    proofUid,deterministicInputFingerprint,deterministicOutputFingerprint)

/** Preserve distinct actors when several scalar effects share one canonical resource row. */
internal fun mechanicSourceActors(effect:VerifiedMechanicsCommandEffect):List<DomainRef?> {
    val count=effect.canonicalPayload["source_actor_count"]?.toIntOrNull()
    if(count!=null) {
        require(count in 1..128)
        return (0 until count).map { i->
            val kind=effect.canonicalPayload["source_actor_${i}_kind"]
            val uid=effect.canonicalPayload["source_actor_${i}_uid"]
            require((kind==null)==(uid==null))
            if(kind==null)null else DomainRef(kind,requireNotNull(uid))
        }.distinct()
    }
    val kind=effect.canonicalPayload["source_actor_kind_uid"]
    val uid=effect.canonicalPayload["source_actor_uid"]
    require((kind==null)==(uid==null))
    return listOf(if(kind==null)null else DomainRef(kind,requireNotNull(uid)))
}
internal fun mergedMechanicSourcePayload(effects:List<VerifiedMechanicsCommandEffect>):Map<String,String> {
    val sources=effects.flatMap(::mechanicSourceActors).distinct().sortedWith(compareBy<DomainRef?>{it?.kindUid.orEmpty()}.thenBy{it?.uid.orEmpty()})
    if(sources.all{it==null})return emptyMap()
    require(sources.size<=128)
    return buildMap {
        put("source_actor_count",sources.size.toString())
        sources.forEachIndexed { i,ref->if(ref!=null){put("source_actor_${i}_kind",ref.kindUid);put("source_actor_${i}_uid",ref.uid)} }
    }
}
