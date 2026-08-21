package com.rpgos.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase38PostHardWorldActorPerceptionTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var concrete: UnifiedGameRepository
    private lateinit var publicRepository: CampaignRepository
    private lateinit var campaignUid: String
    private lateinit var objective: WorldEventItem

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.filesDir, "rpgos").also { it.deleteRecursively() }
        concrete = UnifiedGameRepository(context)
        concrete.bootstrap()
        publicRepository = concrete
        campaignUid = publicRepository.activeCampaignRef().campaignId
        val player = VisibilityAudienceFactory.player(campaignUid)
        val playerUi = PurposeContext(campaignUid, VisibilityPurposeKinds.PLAYER_UI)
        objective = publicRepository.activeWorldEvents(player, playerUi).firstOrNull()
            ?: error("P38 perception acceptance fixture requires one bundled active world event")
        concrete.infrastructureClearWorldActorPerception()
    }

    @After fun cleanup() {
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    private fun actor(uid:String, campaign:String=campaignUid) = AudienceContext(
        campaign,
        AudienceKinds.WORLD_ACTOR,
        VisibilityPrincipalRef("ENTITY",uid)
    )

    private fun reasoning(campaign:String=campaignUid) = PurposeContext(campaign,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)

    private fun build(audience:AudienceContext, tag:String) =
        publicRepository.buildContext(tag,1,audience,reasoning(audience.campaignUid))

    private fun issueEvidence(event:WorldEventItem=objective,name:String="P38-OBSERVED-X",summary:String="P38-OBSERVED-SUMMARY") =
        concrete.infrastructureIssueWorldActorEventSignal(
            event,
            mapOf("name" to name,"status" to "observed","summary" to summary),
            presentedSubject=VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,name)
        )

    @Test fun aud001A_worldActorWithoutCompatibleCapabilityCannotReceiveObservableEvent() {
        issueEvidence()
        val bundle=build(actor("ACTOR-A"),"P38-POST-HARD-AUD-001-A")
        assertTrue("observable event must not bypass perception capability",bundle.activeWorldEvents.isEmpty())
        assertFalse(bundle.toString().contains("P38-OBSERVED-X"))
    }

    @Test fun aud001B_trustedSignalAndCapabilityReachActorThroughPublicBuildContext() {
        val a=actor("ACTOR-A")
        issueEvidence()
        concrete.infrastructureIssueWorldActorEventCapability(a)
        val bundle=build(a,"P38-POST-HARD-AUD-001-B")
        val disclosed=bundle.activeWorldEvents.singleOrNull { it["name"]=="P38-OBSERVED-X" }
        assertNotNull("trusted perception must disclose the justified event representation",disclosed)
        assertEquals("P38-OBSERVED-SUMMARY",disclosed!!["summary"])
        assertEquals("P38-OBSERVED-X",disclosed["subject_uid"])
        assertEquals(DisclosureLevel.DISCLOSE_FULL.name,disclosed["perception_disclosure"])
    }

    @Test fun nonCharacterSubjectDoesNotRequireCharacterProfileEnrichment() {
        val a=actor("ACTOR-NON-CHARACTER-SUBJECT")
        issueEvidence(name="P38-NON-CHARACTER-X",summary="P38-NON-CHARACTER-SUMMARY")
        concrete.infrastructureIssueWorldActorEventCapability(a)
        val bundle=build(a,"P38-POST-HARD-AUD-001-NON-CHARACTER")
        assertTrue(bundle.activeWorldEvents.any { it["subject_uid"]=="P38-NON-CHARACTER-X" })
        assertTrue("PUBLIC_WORLD_EVENT disclosure must not require character-profile enrichment",bundle.relevantNpcs.isEmpty())
    }

    @Test fun typedUidCollisionDoesNotEstablishProfileDomainIdentity() {
        val eventSubject=VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"X")
        val profileSubject=VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,"X")
        assertNotEquals("same textual UID must not collapse distinct typed subjects",eventSubject,profileSubject)
        assertEquals(eventSubject.subjectUid,profileSubject.subjectUid)
        assertNotEquals(eventSubject.subjectKindUid,profileSubject.subjectKindUid)
    }

    @Test fun aud001C_callerOwnedStrongerCapabilityObjectCannotAlterProductionSources() {
        val a=actor("ACTOR-A")
        issueEvidence()
        val callerContext=Phase38RuntimeAuthority.application(a)!!
        val callerHeldCapability=Phase38PerceptionRuntimeAuthority.issueCapability(
            callerContext,
            PerceptionCapabilityRef(campaignUid,"CALLER-HELD-STRONGER"),
            a.principal!!,
            setOf(Phase38WorldActorPerceptionRuntime.WORLD_EVENT_CHANNEL),
            0.0,
            DisclosureLevel.DISCLOSE_FULL
        )
        assertEquals(DisclosureLevel.DISCLOSE_FULL,callerHeldCapability.maximumDisclosure)
        // CampaignRepository.buildContext has no capability argument; the object above is deliberately
        // never accepted by the production-owned source.
        val bundle=build(a,"P38-POST-HARD-AUD-001-C")
        assertTrue(bundle.activeWorldEvents.isEmpty())
    }

    @Test fun aud001D_perceptionCapabilityIsObserverScoped() {
        val observerA=actor("SHARED-TEXT-A")
        val observerB=actor("SHARED-TEXT-B")
        issueEvidence()
        concrete.infrastructureIssueWorldActorEventCapability(observerA)
        assertTrue(build(observerA,"P38-POST-HARD-AUD-001-D-A").activeWorldEvents.any { it["name"]=="P38-OBSERVED-X" })
        assertTrue("observer B must not inherit A capability",build(observerB,"P38-POST-HARD-AUD-001-D-B").activeWorldEvents.isEmpty())
    }

    @Test fun aud001E_decoyPresentedEvidenceDoesNotLeakObjectiveIdentity() {
        val a=actor("ACTOR-DECOY")
        val objectiveIdentity=objective.name
        concrete.infrastructureIssueWorldActorEventSignal(
            objective,
            mapOf("name" to "P38-DECOY-B","status" to "observed","summary" to "PRESENTED-B"),
            presentedSubject=VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.PUBLIC_WORLD_EVENT,"P38-DECOY-B")
        )
        concrete.infrastructureIssueWorldActorEventCapability(a)
        val disclosed=build(a,"P38-POST-HARD-AUD-001-E").activeWorldEvents
        assertTrue(disclosed.any { it["name"]=="P38-DECOY-B" && it["subject_uid"]=="P38-DECOY-B" })
        assertFalse("raw objective identity must not accompany decoy perception",disclosed.any { it["name"]==objectiveIdentity })
    }

    @Test fun aud001F_sameObserverUidFromAnotherCampaignCannotCrossPerceptionBoundary() {
        val local=actor("SAME-OBSERVER")
        issueEvidence()
        concrete.infrastructureIssueWorldActorEventCapability(local)
        assertTrue(build(local,"P38-POST-HARD-AUD-001-F-LOCAL").activeWorldEvents.isNotEmpty())
        val foreign=actor("SAME-OBSERVER","FOREIGN-CAMPAIGN")
        val failure=runCatching { build(foreign,"P38-POST-HARD-AUD-001-F-FOREIGN") }.exceptionOrNull()
        assertTrue("cross-campaign build must fail before perception acquisition",failure is VisibilityAuthorityFailure.CrossCampaign)
    }

    @Test fun combatFuture_hiddenObjectiveThreatIsAbsentWithoutPerception() {
        // The objective bundled event exists and may describe a threat, but no trusted signal/capability
        // has been installed for this actor. Future combat/decision code therefore receives no event row.
        val objectiveMarker=listOf(objective.name,objective.summary).firstOrNull { it.isNotBlank() }!!
        val bundle=build(actor("COMBAT-FUTURE-ACTOR"),"P38-COMBAT-FUTURE")
        assertTrue(bundle.activeWorldEvents.isEmpty())
        assertFalse("objective threat marker must not reach actor reasoning",bundle.activeWorldEvents.toString().contains(objectiveMarker))
    }

    @Test fun actorReasoningDoesNotReceiveObjectiveMissionPressureOrPrivilegedTruthDomains() {
        val bundle=build(actor("ACTOR-F-CHECK"),"P38-POST-HARD-CATEGORY-F")
        assertTrue(bundle.missions.isEmpty())
        assertTrue(bundle.worldPressures.isEmpty())
        assertTrue(bundle.campaignTruth.isEmpty())
        assertTrue(bundle.canonDivergences.isEmpty())
        assertTrue(bundle.canonConstraints.isEmpty())
        assertTrue(bundle.retrievedLongTermMemory.isEmpty())
    }
}
