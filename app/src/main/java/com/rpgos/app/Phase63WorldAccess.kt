package com.rpgos.app

/** Infrastructure captures identity/order/source authority. Supplying a scope or a public
 * cache row is not a grant. This gate never acquires knowledge on behalf of an actor. */
internal class WorldResolutionReadAuthority(
    private val expected:WorldResolutionScope,
    private val current:()->Boolean,
    private val visibility:VisibilityAuthorityService=VisibilityAuthorityService()
) {
    fun accepts(scope:WorldResolutionScope)=scope==expected && current()
    fun project(scope:WorldResolutionScope,elements:List<CampaignWorldElement>):List<CampaignWorldElement> {
        if(!accepts(scope))return emptyList()
        val audience=AudienceContext(scope.campaignUid,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("PLAYER",scope.principalUid))
        val purpose=PurposeContext(scope.campaignUid,VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        return elements.filter { it.audienceScopeUid==CampaignWorldAudience.PLAYER_VISIBLE }.filter { element->
            visibility.decide(VisibilityRequest(audience,purpose,VisibilitySubjectRef(scope.campaignUid,
                VisibilitySubjectKinds.WORLD_PRESENTATION,element.element.uid))).level==DisclosureLevel.DISCLOSE_FULL
        }.takeIf { accepts(scope) }?:emptyList()
    }
}
