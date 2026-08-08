package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
class OrganizationKnowledgeEngineE2E141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    private val holder = EntityUid("NPC-org-engine")
    private val organization = EntityUid("ORG-org-engine")
    private val membershipUid = EntityUid("MEMBERSHIP-org-engine")
    private val publicationUid = EntityUid("PUBLICATION-org-engine")
    private val subject = EntityUid("SUBJECT-org-secret")
    private val sourceFactUid = EntityUid("FACT-org-secret")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        campaignDir = File(context.filesDir, "rpgos/saves/GM141_Organization_Engine_E2E.campaign")
        campaignDir.deleteRecursively()
        campaignDir.mkdirs()
        store = LocalGameStore(context)
        store.setActiveCampaign(campaignDir.name)
        store.bootstrap()
        require(File(campaignDir, "campaign.db").isFile)
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun authorizedPublicationFlowsThroughRuntimeCommitRestartAndDiagnostics() = runBlocking {
        val factory = GameMasterRepositoryFactory(context, store)
        val expectedCampaign = factory.openActiveSession().use { active ->
            val currentTurn = active.repository.currentTurnId(active.campaignUid)
            val transferTurn = currentTurn + 1L
            active.repository.inTransaction {
                writeTruth(
                    CampaignTruth(
                        uid = sourceFactUid,
                        kind = TruthKind.FACT,
                        subjectUid = subject,
                        predicate = "classified.location",
                        value = "VAULT-7",
                        validFromTurn = currentTurn,
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.CAMPAIGN_EVENT,
                            sourceUid = null,
                            turnId = currentTurn,
                            confidence = 1.0,
                            verified = true
                        )
                    )
                )
            }
            val auth = requireNotNull(active.organizationAuthorizationStore)
            auth.appendMembership(
                active.campaignUid,
                OrganizationMembership141(
                    membershipUid = membershipUid,
                    npcUid = holder,
                    organizationUid = organization,
                    clearance = 3,
                    validFromTurn = transferTurn
                )
            )
            auth.appendPublication(
                active.campaignUid,
                OrganizationFactPublication141(
                    publicationUid = publicationUid,
                    organizationUid = organization,
                    truthUid = sourceFactUid,
                    subjectUid = subject,
                    predicate = "classified.location",
                    minimumClearance = 2,
                    validFromTurn = transferTurn
                )
            )
            active.campaignUid
        }

        val gateway = object : GameMasterModelGateway {
            override suspend fun generateProposal(
                request: GameMasterTurnRequest,
                context: GameMasterContext
            ) = GameMasterProposal(
                narrativeDraft = "Organizacja przekazuje członkowi zatwierdzony meldunek.",
                proposedActions = listOf(
                    ProposedWorldAction(
                        actionType = "ORGANIZATION_KNOWLEDGE_PROPAGATE",
                        actorId = holder.value,
                        parametersJson = """{
                            "membership_id":"${membershipUid.value}",
                            "publication_id":"${publicationUid.value}"
                        }""".trimIndent(),
                        reason = "Autoryzowana publikacja organizacyjna."
                    )
                )
            )
        }

        val result = GameMasterRuntime141(context, store, gateway).play(
            playerAction = "Organizacja przekazuje raport.",
            currentChapter = 1L
        )

        val beliefWrite = result.truthWrites.single { it.kind == TruthKind.BELIEF }
        assertEquals(KnowledgeChannel141.ORGANIZATION, beliefWrite.knowledgeChannel)
        assertEquals(ProvenanceType.ORGANIZATION_REPORT, beliefWrite.sourceType)
        assertEquals(sourceFactUid.value, beliefWrite.sourceId)
        assertEquals(holder.value, beliefWrite.holderId)
        assertNull(beliefWrite.sourceNpcId)
        val orgWrite = result.npcKnowledgeWrites.organizationTransmissions.single()
        assertEquals(organization.value, orgWrite.organizationId)
        assertEquals(membershipUid.value, orgWrite.membershipId)
        assertEquals(publicationUid.value, orgWrite.publicationId)
        assertEquals(holder.value, orgWrite.receiverId)

        GameMasterRepositoryFactory(context, LocalGameStore(context)).openActiveSession().use { reopened ->
            assertEquals(expectedCampaign, reopened.campaignUid)
            val committedTurn = reopened.repository.currentTurnId(reopened.campaignUid)
            val beliefs = reopened.repository.getBeliefs(
                reopened.campaignUid,
                holder,
                subject,
                committedTurn,
                20
            )
            val durableBelief = beliefs.single { it.predicate == "classified.location" && it.value == "VAULT-7" }
            assertEquals(ProvenanceType.ORGANIZATION_REPORT, durableBelief.provenance.type)
            assertEquals(sourceFactUid, durableBelief.provenance.sourceUid)

            val generic = reopened.knowledgeStore.knowledgeTransmissionsForReceiver(
                reopened.campaignUid,
                holder,
                committedTurn,
                20
            ).single { it.resultingBeliefUid == durableBelief.uid }
            assertEquals(KnowledgeChannel141.ORGANIZATION, generic.channel)
            assertNull(generic.sourceNpcUid)
            assertEquals(sourceFactUid, generic.sourceTruthUid)
        }

        val db = LocalGameStore(context).openSaveDb()
        try {
            db.rawQuery(
                """
                SELECT organization_id,membership_id,publication_id,source_truth_id,receiver_id
                FROM gm_organization_knowledge_transmissions
                WHERE campaign_id=?
                """.trimIndent(),
                arrayOf(expectedCampaign.value)
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(organization.value, c.getString(0))
                assertEquals(membershipUid.value, c.getString(1))
                assertEquals(publicationUid.value, c.getString(2))
                assertEquals(sourceFactUid.value, c.getString(3))
                assertEquals(holder.value, c.getString(4))
            }
        } finally {
            db.close()
        }

        val diagnostics = GameMasterDiagnosticsService141(context, LocalGameStore(context)).report()
        assertTrue(diagnostics, diagnostics.contains("integrity=OK"))
        assertTrue(diagnostics, diagnostics.contains("knowledgeIntegrity=OK"))
        assertTrue(diagnostics, diagnostics.contains("npcLifecycleIntegrity=OK"))
    }

    @Test
    fun insufficientClearanceIsRejectedBeforeCanonicalTurnCommit() = runBlocking {
        val factory = GameMasterRepositoryFactory(context, store)
        val before = factory.openActiveSession().use { active ->
            val currentTurn = active.repository.currentTurnId(active.campaignUid)
            val transferTurn = currentTurn + 1L
            active.repository.inTransaction {
                writeTruth(
                    CampaignTruth(
                        uid = sourceFactUid,
                        kind = TruthKind.FACT,
                        subjectUid = subject,
                        predicate = "classified.location",
                        value = "VAULT-7",
                        validFromTurn = currentTurn,
                        provenance = ProvenanceRecord(
                            ProvenanceType.CAMPAIGN_EVENT,
                            null,
                            currentTurn,
                            1.0,
                            verified = true
                        )
                    )
                )
            }
            val auth = requireNotNull(active.organizationAuthorizationStore)
            auth.appendMembership(
                active.campaignUid,
                OrganizationMembership141(
                    membershipUid,
                    holder,
                    organization,
                    clearance = 1,
                    validFromTurn = transferTurn
                )
            )
            auth.appendPublication(
                active.campaignUid,
                OrganizationFactPublication141(
                    publicationUid,
                    organization,
                    sourceFactUid,
                    subject,
                    "classified.location",
                    minimumClearance = 5,
                    validFromTurn = transferTurn
                )
            )
            currentTurn
        }

        val gateway = object : GameMasterModelGateway {
            override suspend fun generateProposal(
                request: GameMasterTurnRequest,
                context: GameMasterContext
            ) = GameMasterProposal(
                narrativeDraft = "Próba odczytu tajnego raportu.",
                proposedActions = listOf(
                    ProposedWorldAction(
                        actionType = "ORGANIZATION_KNOWLEDGE_PROPAGATE",
                        actorId = holder.value,
                        parametersJson = """{
                            "membership_id":"${membershipUid.value}",
                            "publication_id":"${publicationUid.value}"
                        }""".trimIndent()
                    )
                )
            )
        }

        val failure = runCatching {
            GameMasterRuntime141(context, store, gateway).play("Odczytaj raport.", 1L)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("INSUFFICIENT_CLEARANCE"))

        factory.openActiveSession().use { reopened ->
            assertEquals(before, reopened.repository.currentTurnId(reopened.campaignUid))
            assertTrue(
                reopened.repository.getBeliefs(reopened.campaignUid, holder, subject, before, 20).isEmpty()
            )
        }
    }
}
