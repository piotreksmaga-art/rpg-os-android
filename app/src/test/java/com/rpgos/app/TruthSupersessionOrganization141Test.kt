package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class TruthSupersessionOrganization141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/GM141_Truth_Supersession_Org.campaign")
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
    fun publicationCannotRemainAuthorizedAfterItsSourceFactIsSuperseded() = runBlocking {
        val subject = EntityUid("SUBJECT-org-supersession")
        val oldUid = EntityUid("FACT-org-supersession-old")
        val newUid = EntityUid("FACT-org-supersession-new")
        val organization = EntityUid("ORG-supersession")
        val publication = OrganizationFactPublication141(
            publicationUid = EntityUid("PUBLICATION-org-supersession"),
            organizationUid = organization,
            truthUid = oldUid,
            subjectUid = subject,
            predicate = "classified.owner",
            minimumClearance = 1,
            validFromTurn = 1L,
            validUntilTurn = null
        )

        GameMasterRepositoryFactory(context, store).openActiveSession().use { active ->
            active.repository.writeTruth(
                CampaignTruth(
                    uid = oldUid,
                    kind = TruthKind.FACT,
                    subjectUid = subject,
                    predicate = publication.predicate,
                    value = "OLD",
                    validFromTurn = 0L,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.CAMPAIGN_EVENT,
                        sourceUid = EntityUid("EVENT-org-old"),
                        turnId = 0L,
                        confidence = 1.0,
                        verified = true
                    )
                )
            )
            val auth = requireNotNull(active.organizationAuthorizationStore)
            auth.appendPublication(active.campaignUid, publication)
            assertNotNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 4L))

            requireNotNull(active.truthSupersessionStore).supersedeFact(
                previousTruthUid = oldUid,
                replacement = CampaignTruth(
                    uid = newUid,
                    kind = TruthKind.FACT,
                    subjectUid = subject,
                    predicate = publication.predicate,
                    value = "NEW",
                    validFromTurn = null,
                    provenance = ProvenanceRecord(
                        type = ProvenanceType.CAMPAIGN_EVENT,
                        sourceUid = EntityUid("EVENT-org-new"),
                        turnId = 5L,
                        confidence = 1.0,
                        verified = true
                    )
                ),
                effectiveTurn = 5L
            )

            assertNotNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 4L))
            assertNull(auth.publicationByUid(active.campaignUid, publication.publicationUid, 5L))
            assertTrue(auth.publicationsForOrganization(active.campaignUid, organization, 5L).isEmpty())
        }
    }
}
