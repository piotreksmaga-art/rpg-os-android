package com.rpgos.app

/** The existing universal resolver supplies interpretation/evidence; this registered owner
 * binds it to the immutable slot and rule source. It does not write or acquire knowledge. */
internal class RegisteredLatentWorldResolver(
    private val draft:WorldElementDraft,
    private val existing:List<CampaignWorldElement>,
    private val current:(WorldResolutionScope)->Boolean
):LatentWorldResolverPort {
    override fun resolve(scope:WorldResolutionScope,skeleton:CampaignWorldSkeleton,slot:LatentWorldSlot):WorldResolutionResult {
        if(!current(scope))return WorldResolutionResult.Unavailable("P63:STALE_RESOLUTION_SCOPE")
        if(scope.campaignUid!=skeleton.campaignUid || draft.campaignUid!=scope.campaignUid ||
            scope.sourceVersions[skeleton.ruleSource.uid]!=skeleton.ruleSource.version)
            return WorldResolutionResult.Unavailable("P63:WORLD_SOURCE_SCOPE_MISMATCH")
        if(slot.ref(skeleton)!=draft.element || slot.regionUid!=draft.parentAnchorUid || slot.ordinal!=draft.slotOrdinal ||
            slot.categoryUid!=(draft.slotCategoryUid?:draft.categoryUid) || slot.baseKind!=draft.baseKind)
            return WorldResolutionResult.Contradicted("P63:SLOT_IDENTITY_MISMATCH")
        existing.singleOrNull { it.element==draft.element && it.audienceScopeUid==CampaignWorldAudience.PLAYER_VISIBLE }
            ?.let { return WorldResolutionResult.Existing(it) }
        if(CoreLatentWorldRules.select(skeleton,slot,draft.topologyClassUid)==null)
            return WorldResolutionResult.Unavailable("P63:LATENT_RULE_OR_SLOT_UNAVAILABLE")
        return WorldResolutionResult.Candidate(draft,slot)
    }
}

/** Binds the public materialization port to already-verified Core effects and delegates to the
 * existing production preparation. No second truth/mechanics/time writer is introduced. */
internal class CapturedWorldMaterializationPort(
    private val expected:WorldResolutionScope,
    private val admittedDrafts:List<WorldElementDraft>,
    private val current:(WorldResolutionScope)->Boolean,
    private val prepareOwner:()->List<WorldSimulationChange>
):WorldMaterializationPort {
    override fun prepare(scope:WorldResolutionScope,drafts:List<WorldElementDraft>):List<PlayerDomainChangePayload> {
        require(scope==expected && current(scope)) { "P63:STALE_MATERIALIZATION_SCOPE" }
        require(drafts==admittedDrafts && drafts.all { it.campaignUid==scope.campaignUid }) { "P63:MATERIALIZATION_NOT_ADMITTED" }
        val result=prepareOwner()
        require(current(scope) && result.all { it.campaignUid==scope.campaignUid && it.historyGenerationUid==scope.historyGenerationUid }) { "P63:STALE_MATERIALIZATION_SCOPE" }
        requireWorldSimulationChain(result)
        return result
    }
}
