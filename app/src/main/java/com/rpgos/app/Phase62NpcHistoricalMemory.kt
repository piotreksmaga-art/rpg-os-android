package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Phase56-58 are retrieval hints, not additional knowledge authority. Every leaf is reopened
 * through Phase37 with the caller's already trusted holder/roles. No summary or omitted-event
 * list is ever sent to a model. A stale/missing/corrupt cache only removes optional recall. */
internal class NpcHistoricalMemoryProjection(private val db:SQLiteDatabase,private val campaign:String) {
    private data class Metadata(val artifactRevisionUid:String,val artifactKind:MemoryArtifactKind,
        val sourceLeafSetFingerprint:String,val asOfCommittedOrder:Long)
    fun read(holder:KnowledgeHolderRef,generation:HistoryGenerationUid,order:Long,roles:Set<String>,limit:Int=16):List<NpcKnownRecord> {
        require(holder.campaignUid==campaign && order>=0 && limit in 1..16 && roles.size<=128)
        if(!Phase55To58MemorySchema.isReady(db) || !Phase37KnowledgeSchema.isReady(db))return emptyList()
        if(HistoryGenerationStore(db,campaign).current()!=generation)return emptyList()
        val selected=roles.sorted()
        fun access(alias:String)=if(selected.isEmpty())"($alias.scope_uid='PERSONAL' AND COALESCE($alias.role_uid,'')='')"
            else "(($alias.scope_uid='PERSONAL' AND COALESCE($alias.role_uid,'')='') OR ($alias.scope_uid='ROLE_ACCESSIBLE' AND $alias.role_uid IN (${selected.joinToString{"?"}})))"
        // Join canonical holder ownership, and read metadata only. Even a huge/corrupt cached
        // summary never enters the cursor window or runtime; canonical leaves supply all text.
        val artifacts=db.rawQuery("""SELECT m.artifact_revision_uid,m.artifact_kind_uid,
            m.source_leaf_set_fingerprint,m.as_of_committed_order FROM ${Phase55To58MemorySchema.ARTIFACTS} m
            WHERE m.campaign_uid=? AND m.history_generation_uid=? AND m.status_uid='CLEAN'
              AND m.as_of_committed_order<=? AND m.derivation_version=1
              AND ((m.artifact_kind_uid='HOLDER_EPISODE_MEMORY' AND m.derivation_rule_uid='RPGOS-P56-PHASE37-ACQUISITION'
                AND EXISTS(SELECT 1 FROM ${Phase55To58MemorySchema.LEAVES} l JOIN ${Phase37KnowledgeSchema.ACQUISITIONS} a
                  ON a.campaign_uid=l.campaign_uid AND a.acquisition_uid=l.source_uid
                  WHERE l.campaign_uid=m.campaign_uid AND l.artifact_revision_uid=m.artifact_revision_uid
                    AND l.source_kind_uid='KNOWLEDGE_ACQUISITION' AND a.holder_kind_uid=? AND a.holder_uid=? AND ${access("a")}))
               OR (m.artifact_kind_uid='SEMANTIC_ASSERTION' AND m.derivation_rule_uid='RPGOS-P57-PHASE37-STATE'
                AND EXISTS(SELECT 1 FROM ${Phase55To58MemorySchema.LEAVES} l JOIN ${Phase37KnowledgeSchema.STATES} s
                  ON s.campaign_uid=l.campaign_uid AND s.state_uid=l.source_uid
                  WHERE l.campaign_uid=m.campaign_uid AND l.artifact_revision_uid=m.artifact_revision_uid
                    AND l.source_kind_uid='KNOWLEDGE_STATE' AND s.holder_kind_uid=? AND s.holder_uid=? AND ${access("s")})))
            ORDER BY m.as_of_committed_order DESC,m.artifact_revision_uid LIMIT 16""",
            (listOf(campaign,generation.value,order.toString(),holder.holderKindUid,holder.holderUid)+selected+
                listOf(holder.holderKindUid,holder.holderUid)+selected).toTypedArray()).use{c->buildList{
                    while(c.moveToNext())add(Metadata(c.getString(0),MemoryArtifactKind.valueOf(c.getString(1)),c.getString(2),c.getLong(3)))}}
        if(artifacts.isEmpty())return emptyList()
        val owner=LeafOwner(holder,order,roles)
        return artifacts
            .flatMap { artifact -> runCatching { rehydrate(artifact,owner) }.getOrDefault(emptyList()) }
            .distinctBy{it.uid}.take(limit)
    }

    private fun rehydrate(artifact:Metadata,owner:LeafOwner):List<NpcKnownRecord> {
        val leaves=db.rawQuery("""SELECT source_kind_uid,source_uid,source_version,committed_order,source_fingerprint
            FROM ${Phase55To58MemorySchema.LEAVES} WHERE campaign_uid=? AND artifact_revision_uid=?
            ORDER BY source_kind_uid,source_uid LIMIT 257""",arrayOf(campaign,artifact.artifactRevisionUid)).use{c->buildList{
            while(c.moveToNext())add(MemorySourceLeafRef(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),c.getString(4)))
        }}
        if(leaves.isEmpty() || leaves.size>256 || memoryLeafFingerprint(leaves)!=artifact.sourceLeafSetFingerprint)return emptyList()
        if(leaves.size>owner.remainingLeaves)return emptyList()
        owner.remainingLeaves-=leaves.size
        if(leaves.any{it.committedOrder>artifact.asOfCommittedOrder})return emptyList()
        val canonical=leaves.map{leaf->owner.resolve(leaf)?:return emptyList()}
        // Materialize from canonical leaves only. Cache payload (including AI summaries) is ignored.
        return when(artifact.artifactKind) {
            MemoryArtifactKind.HOLDER_EPISODE_MEMORY -> {
                if(leaves.any{it.sourceKind!="KNOWLEDGE_ACQUISITION"})return emptyList()
                canonical.mapNotNull{it.record}
            }
            MemoryArtifactKind.SEMANTIC_ASSERTION -> {
                if(leaves.count{it.sourceKind=="KNOWLEDGE_STATE"}!=1 || leaves.none{it.sourceKind=="KNOWLEDGE_EVIDENCE"} ||
                    leaves.any{it.sourceKind !in setOf("KNOWLEDGE_STATE","KNOWLEDGE_EVIDENCE")})return emptyList()
                val state=canonical.single{it.kind=="KNOWLEDGE_STATE"}
                // All evidence must still support the exact holder/state acquisition, not an
                // unrelated old event whose UID was spliced into a rebuildable manifest.
                if(canonical.any{it.acquisitionUid!=state.acquisitionUid})return emptyList()
                listOfNotNull(state.record)
            }
            else -> emptyList()
        }
    }

    private data class Resolved(val kind:String,val acquisitionUid:String,val record:NpcKnownRecord?)
    private inner class LeafOwner(val holder:KnowledgeHolderRef,val order:Long,val roles:Set<String>) {
        var remainingLeaves=256
        private fun allowed(kind:String,uid:String,scope:String,role:String?)=kind==holder.holderKindUid && uid==holder.holderUid &&
            ((scope==KnowledgeScope.PERSONAL.name && role.isNullOrBlank()) || (scope==KnowledgeScope.ROLE_ACCESSIBLE.name && role in roles))
        private fun acquisition(uid:String):Pair<MemorySourceLeafRef,NpcKnownRecord>? = db.rawQuery("""SELECT
            a.claim_uid,a.holder_kind_uid,a.holder_uid,a.method_uid,a.created_event_uid,a.created_order,a.scope_uid,a.role_uid,
            c.subject_kind_uid,c.subject_uid,c.predicate_uid,c.value_canonical
            FROM ${Phase37KnowledgeSchema.ACQUISITIONS} a JOIN ${Phase37KnowledgeSchema.CLAIMS} c
              ON c.campaign_uid=a.campaign_uid AND c.claim_uid=a.claim_uid
            WHERE a.campaign_uid=? AND a.acquisition_uid=? AND a.provenance_status='RECORDED' AND a.created_order<=?
              AND EXISTS(SELECT 1 FROM ${Phase37KnowledgeSchema.EVIDENCE} e WHERE e.campaign_uid=a.campaign_uid AND e.acquisition_uid=a.acquisition_uid)
              AND length(c.subject_uid)+length(c.predicate_uid)+length(c.value_canonical)<=440""",
            arrayOf(campaign,uid,order.toString())).use { c ->
            if(!c.moveToFirst() || !allowed(c.getString(1),c.getString(2),c.getString(6),c.getString(7)) || c.isNull(4))return@use null
            val fingerprint=memorySha256(listOf(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5)).joinToString("|"))
            val text="Dawniej pozyskana informacja: ${c.getString(9)}: ${c.getString(10)} = ${c.getString(11)}"
            // Remembering receipt is not knowing the claim is still true, nor perceiving its subject now.
            MemorySourceLeafRef("KNOWLEDGE_ACQUISITION",uid,1,c.getLong(5),fingerprint) to
                NpcKnownRecord("P62:MEMORY:${phase60Hash(uid)}",KnowledgeEpistemicState.OUTDATED,text,uid,1,
                    emptySet(),c.getLong(5),NpcMemoryRecordKind.HISTORICAL_ACQUISITION)
        }
        fun resolve(leaf:MemorySourceLeafRef):Resolved? {
            if(leaf.committedOrder>order)return null
            return when(leaf.sourceKind) {
                "KNOWLEDGE_ACQUISITION" -> acquisition(leaf.sourceUid)?.takeIf{it.first==leaf}?.let{Resolved(leaf.sourceKind,leaf.sourceUid,it.second)}
                "KNOWLEDGE_STATE" -> state(leaf)
                "KNOWLEDGE_EVIDENCE" -> evidence(leaf)
                else -> null
            }
        }
        private fun state(leaf:MemorySourceLeafRef):Resolved? = db.rawQuery("""SELECT state_version,updated_order,epistemic_state_uid,
            confidence,source_reliability,latest_acquisition_uid,holder_kind_uid,holder_uid,scope_uid,role_uid
            FROM ${Phase37KnowledgeSchema.STATES} WHERE campaign_uid=? AND state_uid=?""",arrayOf(campaign,leaf.sourceUid)).use { c ->
            if(!c.moveToFirst() || !allowed(c.getString(6),c.getString(7),c.getString(8),c.getString(9)))return@use null
            val fp=memorySha256(listOf(leaf.sourceUid,c.getLong(0),c.getLong(1),c.getString(2),c.getDouble(3),c.getDouble(4),c.getString(5)).joinToString("|"))
            if(leaf!=MemorySourceLeafRef("KNOWLEDGE_STATE",leaf.sourceUid,c.getLong(0),c.getLong(1),fp))return@use null
            val acquisitionUid=c.getString(5)
            if(acquisition(acquisitionUid)==null)return@use null
            val record=KnowledgeContextProjection(db,campaign).boundedForNpc(holder,order,1,roles,setOf(acquisitionUid))
                .singleOrNull{it.uid==leaf.sourceUid && it.acquisitionUid==acquisitionUid}?:return@use null
            Resolved(leaf.sourceKind,acquisitionUid,record.copy(memoryKind=NpcMemoryRecordKind.SEMANTIC_ASSERTION))
        }
        private fun evidence(leaf:MemorySourceLeafRef):Resolved? = db.rawQuery("""SELECT evidence_uid,acquisition_uid,polarity_uid,
            evidence_kind_uid,source_event_uid,source_acquisition_uid,source_carrier_kind_uid,source_carrier_uid,source_ref_kind_uid,source_ref_uid
            FROM ${Phase37KnowledgeSchema.EVIDENCE} WHERE campaign_uid=? AND evidence_uid=?""",arrayOf(campaign,leaf.sourceUid)).use { c ->
            if(!c.moveToFirst())return@use null
            val acquisition=acquisition(c.getString(1))?:return@use null
            val fp=memorySha256((0 until c.columnCount).joinToString("|"){if(c.isNull(it))"" else c.getString(it)})
            if(leaf!=MemorySourceLeafRef("KNOWLEDGE_EVIDENCE",leaf.sourceUid,1,acquisition.first.committedOrder,fp))return@use null
            Resolved(leaf.sourceKind,c.getString(1),null)
        }
    }
}
