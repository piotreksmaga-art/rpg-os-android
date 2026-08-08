package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrganizationKnowledgeAuthorizationPersistence141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/GM141_Org_Auth_Persistence.campaign")
        campaignDir.deleteRecursively()
        campaignDir.mkdirs()
        store = LocalGameStore(context)
        store.setActiveCampaign(campaignDir.name)
        store.bootstrap()
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun membershipAndPublicationSurviveRepositoryRestart() = runBlocking {
        val npc = EntityUid("NPC-org-auth")
        val org = EntityUid("ORG-org-auth")
        val membership = OrganizationMembership141(
            membershipUid = EntityUid("MEMBERSHIP-org-auth"),
            npcUid = npc,
            organizationUid = org,
            clearance = 3,
            validFromTurn = 2L,
            validUntilTurn = 20L
        )
        val publication = OrganizationFactPublication141(
            publicationUid = EntityUid("PUBLICATION-org-auth"),
            organizationUid = org,
            truthUid = EntityUid("FACT-org-auth"),
            subjectUid = EntityUid("SUBJECT-org-auth"),
            predicate = "classified.location",
            minimumClearance = 2,
            validFromTurn = 4L,
            validUntilTurn = 18L
        )

        val factory = GameMasterRepositoryFactory(context, store)
        val campaignUid = factory.openActiveSession().use { active ->
            writeSourceFact(active, publication)
            val auth = requireNotNull(active.organizationAuthorizationStore)
            auth.appendMembership(active.campaignUid, membership)
            auth.appendPublication(active.campaignUid, publication)
            active.campaignUid
        }

        GameMasterRepositoryFactory(context, LocalGameStore(context)).openActiveSession().use { reopened ->
            assertEquals(campaignUid, reopened.campaignUid)
            val auth = requireNotNull(reopened.organizationAuthorizationStore)
            assertEquals(membership, auth.membershipByUid(campaignUid, membership.membershipUid, 10L))
            assertEquals(publication, auth.publicationByUid(campaignUid, publication.publicationUid, 10L))
            assertEquals(listOf(membership), auth.membershipsForNpc(campaignUid, npc, 10L))
            assertEquals(listOf(publication), auth.publicationsForOrganization(campaignUid, org, 10L))
        }
    }

    @Test
    fun lookupRejectsAuthorizationOutsideItsTemporalWindow() = runBlocking {
        val npc = EntityUid("NPC-expiring")
        val org = EntityUid("ORG-expiring")
        val membership = OrganizationMembership141(
            membershipUid = EntityUid("MEMBERSHIP-expiring"),
            npcUid = npc,
            organizationUid = org,
            clearance = 1,
            validFromTurn = 5L,
            validUntilTurn = 8L
        )
        val publication = OrganizationFactPublication141(
            publicationUid = EntityUid("PUBLICATION-expiring"),
            organizationUid = org,
            truthUid = EntityUid("FACT-expiring"),
            subjectUid = EntityUid("SUBJECT-expiring"),
            predicate = "orders.target",
            minimumClearance = 1,
            validFromTurn = 6L,
            validUntilTurn = 9L
        )

        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            writeSourceFact(active, publication)
            val auth = requireNotNull(active.organizationAuthorizationStore)
            auth.appendMembership(active.campaignUid, membership)
            auth.appendPublication(active.campaignUid, publication)

            assertNull(auth.membershipByUid(active.campaignUid, membership.membershipUid, 4L))
            assertNotNull(auth.membershipByUid(active.campaignUid, membership.membershipUid, 7L))
            assertNull(auth.membershipByUid(active.campaignUid, membership.membershipUid, 9L))

            assertNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 5L))
            assertNotNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 8L))
            assertNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 10L))
            assertTrue(auth.membershipsForNpc(active.campaignUid, npc, 9L).isEmpty())
            assertTrue(auth.publicationsForOrganization(active.campaignUid, org, 10L).isEmpty())
        }
    }

    @Test
    fun appendPublicationRejectsUnknownTruthUid() = runBlocking {
        val publication = publication("unknown", "FACT-missing")

        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            val auth = requireNotNull(active.organizationAuthorizationStore)
            val failure = runCatching {
                auth.appendPublication(active.campaignUid, publication)
            }.exceptionOrNull()

            assertNotNull(failure)
            assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("nieistniejący truth_id"))
            assertNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 5L))
        }
    }

    @Test
    fun appendPublicationRejectsBeliefAndSemanticMismatch() = runBlocking {
        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            val auth = requireNotNull(active.organizationAuthorizationStore)

            val beliefPublication = publication("belief", "BELIEF-source")
            writeSourceFact(active, beliefPublication, kind = TruthKind.BELIEF)
            val beliefFailure = runCatching {
                auth.appendPublication(active.campaignUid, beliefPublication)
            }.exceptionOrNull()
            assertTrue(beliefFailure?.message.orEmpty(), beliefFailure?.message.orEmpty().contains("wyłącznie FACT"))

            val mismatchPublication = publication("mismatch", "FACT-mismatch")
            writeSourceFact(active, mismatchPublication, predicate = "different.predicate")
            val mismatchFailure = runCatching {
                auth.appendPublication(active.campaignUid, mismatchPublication)
            }.exceptionOrNull()
            assertTrue(mismatchFailure?.message.orEmpty(), mismatchFailure?.message.orEmpty().contains("predicate="))
        }
    }

    @Test
    fun appendPublicationCannotOutliveSourceFact() = runBlocking {
        val publication = publication("window", "FACT-window", validFrom = 4L, validUntil = 12L)

        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            writeSourceFact(active, publication, validFrom = 3L, validUntil = 8L)
            val auth = requireNotNull(active.organizationAuthorizationStore)
            val failure = runCatching {
                auth.appendPublication(active.campaignUid, publication)
            }.exceptionOrNull()

            assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("wykracza poza ważność FACT"))
            assertNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 6L))
        }
    }

    @Test
    fun sourceValueRebindingInvalidatesExistingPublication() = runBlocking {
        val publication = publication("rebound", "FACT-rebound", validFrom = 2L, validUntil = 10L)

        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            writeSourceFact(active, publication, value = "ORIGINAL")
            val auth = requireNotNull(active.organizationAuthorizationStore)
            auth.appendPublication(active.campaignUid, publication)
            assertNotNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 5L))

            // Legacy Source of Truth still allows CONFLICT_REPLACE on the same fact_id.
            // The publication fingerprint must make that replaced value unusable.
            writeSourceFact(active, publication, value = "TAMPERED")

            assertNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 5L))
            assertTrue(
                auth.publicationsForOrganization(active.campaignUid, publication.organizationUid, 5L).isEmpty()
            )
        }
    }

    private fun publication(
        suffix: String,
        truthUid: String,
        validFrom: Long = 2L,
        validUntil: Long? = 10L
    ) = OrganizationFactPublication141(
        publicationUid = EntityUid("PUBLICATION-$suffix"),
        organizationUid = EntityUid("ORG-$suffix"),
        truthUid = EntityUid(truthUid),
        subjectUid = EntityUid("SUBJECT-$suffix"),
        predicate = "classified.$suffix",
        minimumClearance = 1,
        validFromTurn = validFrom,
        validUntilTurn = validUntil
    )

    private suspend fun writeSourceFact(
        active: ActiveGameMasterRepository,
        publication: OrganizationFactPublication141,
        kind: TruthKind = TruthKind.FACT,
        subjectUid: EntityUid = publication.subjectUid,
        predicate: String = publication.predicate,
        value: String = "source-value",
        validFrom: Long = 0L,
        validUntil: Long? = null
    ) {
        active.repository.writeTruth(
            CampaignTruth(
                uid = publication.truthUid,
                kind = kind,
                subjectUid = subjectUid,
                predicate = predicate,
                value = value,
                holderUid = if (kind == TruthKind.BELIEF) EntityUid("NPC-source-holder") else null,
                validFromTurn = validFrom,
                validUntilTurn = validUntil,
                provenance = ProvenanceRecord(
                    type = ProvenanceType.SYSTEM_SIMULATION,
                    sourceUid = null,
                    turnId = validFrom,
                    confidence = 1.0,
                    verified = true
                )
            )
        )
    }
}
