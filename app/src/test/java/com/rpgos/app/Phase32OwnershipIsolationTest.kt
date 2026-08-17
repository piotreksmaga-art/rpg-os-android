package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32OwnershipIsolationTest {
    private lateinit var saveFile: File
    private lateinit var worldFile: File

    @Before
    fun setUp() {
        saveFile = File.createTempFile("g32-own-", ".db").also { it.delete() }
        worldFile = File.createTempFile("g32-own-world-", ".db").also { it.delete() }
    }

    @After
    fun tearDown() {
        saveFile.delete()
        worldFile.delete()
    }

    @Test
    fun ownershipHistoryChangesOnlyThroughOwnershipStoreNotEvidenceProjectionContextOrFinanceRebuild() {
        SQLiteDatabase.openOrCreateDatabase(saveFile, null).use { db ->
            Phase32ProductionReadyTestFixture.setup(db, "C1")
            val asset = OwnedAssetRef("G32-ASSET-KIND", "G32-ASSET")
            val owner = OwnershipOwnerRef("CHARACTER", "P1")
            withAdministrativeMutationAuthority(db, "C1") {
                val refs = OwnershipReferenceRegistry(db, "C1")
                refs.registerAssetKind(asset.assetKindUid, "G32")
                refs.registerAsset(asset, "G32")
                OwnershipStore(db, "C1").acquire(
                    OwnershipRecord(
                        campaignId = "C1",
                        ownershipRecordUid = "G32-OWNERSHIP-1",
                        owner = owner,
                        asset = asset,
                        ownershipTypeUid = "OWNER",
                        share = OwnershipShare.full(),
                        validFrom = 1L,
                        provenance = "G32"
                    )
                )
            }
            val ownership = OwnershipStore(db, "C1")
            val before = ownership.history(asset)
            assertEquals(1, before.size)

            val firstIdentity = TurnTransactionIdentity("C1", "TURN-G32-E1", "CMD-G32-E1", "TX-G32-E1")
            TurnTransactionBoundary.create(db, firstIdentity, eventfulProposal("CMD-G32-E1")).commit()
            assertEquals(before, ownership.history(asset))
            val firstEvent = eventUid(db, "TX-G32-E1")

            val secondIdentity = TurnTransactionIdentity("C1", "TURN-G32-E2", "CMD-G32-E2", "TX-G32-E2")
            TurnTransactionBoundary.create(db, secondIdentity, eventfulProposal("CMD-G32-E2")).commit()
            assertEquals(before, ownership.history(asset))
            val secondEvent = eventUid(db, "TX-G32-E2")

            val causalIdentity = TurnTransactionIdentity("C1", "TURN-G32-C", "CMD-G32-C", "TX-G32-C")
            val causal = CanonicalCausalRelationIntent(
                relationIntentUid = "REL-G32-OWNERSHIP-ISOLATION",
                relationClass = CausalRelationClass.PROVENANCE,
                relationKindUid = CausalRelationKinds.PROVENANCE_OF,
                sourceEventUid = firstEvent,
                targetEventUid = secondEvent
            )
            TurnTransactionBoundary.create(
                db,
                causalIdentity,
                GroupATransactionTestFixtures.admittedFinancialProposal(commandUid = "CMD-G32-C"),
                causalRelationIntents = listOf(causal)
            ).commit()
            assertEquals(before, ownership.history(asset))
            assertEquals(1L, count(db, "canonical_causal_relations"))

            val readSource = OwnershipProjectionReadSource(db, asset)
            val panel = CharacterPanelSnapshotV2Builder.build(readSource, "C1", "P1")
            assertEquals("G32-ASSET", panel.ownershipAndAssets.single().assetUid)
            assertEquals(before, ownership.history(asset))

            val snapshot = PlayerSnapshotBuilder.build(readSource, "C1", "P1", PlayerSnapshotProfile.ECONOMY)
            assertEquals(PlayerSnapshotClassification.DERIVED_PROJECTION, snapshot.classification)
            assertEquals("G32-ASSET", snapshot.panel.ownershipAndAssets.single().assetUid)
            assertEquals(before, ownership.history(asset))

            SQLiteDatabase.openOrCreateDatabase(worldFile, null).use { world ->
                ContextBuilder(db, world).build("inspect", 1)
            }
            assertEquals(before, ownership.history(asset))

            val finance = FinancialStore(db, "C1")
            val expectedBalance = finance.balance("A")
            db.delete("financial_account_balances", "campaign_id=? AND account_uid=?", arrayOf("C1", "A"))
            assertEquals(expectedBalance, finance.rebuildBalance("A"))
            assertEquals(before, ownership.history(asset))
        }
    }

    private fun eventfulProposal(commandUid: String): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = commandUid,
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 1L, "CUR"),
            provenance = CommandProvenance("G32-OWNERSHIP-ISOLATION"),
            requestedEffectiveOrder = 10L
        )
        val refs = setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        )
        val context = PlayerResolutionContext.createUnboundGeneric("C1", actor, refs)
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(EventfulFinancialComponent()))
        )
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit("C1", engine, command, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("canonical admission rejected: ${admission.reasonUid}")
        }
    }

    private class EventfulFinancialComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "RPGOS-COMPONENT:G32-OWNERSHIP-ISOLATION",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TransferFundsCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            val changeUid = "CHANGE-${command.commandUid}"
            val subject = DomainRef("PLAYER", "P1")
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(
                    changes = listOf(
                        PlayerDomainChange.create(
                            changeUid,
                            PlayerChangeKinds.FINANCIAL,
                            FinancialChange(
                                command.payload.fromAccountUid,
                                command.payload.toAccountUid,
                                command.payload.amountMinor,
                                command.payload.currencyUid,
                                "RPGOS-FIN-TYPE:TRANSFER"
                            )
                        )
                    ),
                    eventIntents = listOf(
                        PlayerEventIntent.create(
                            eventIntentUid = "EVENT-${command.commandUid}",
                            eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
                            actorRef = subject,
                            targetRefs = listOf(subject),
                            causalChangeUids = listOf(changeUid),
                            payload = DomainEffectEventIntentPayload(subject, "RPGOS-EFFECT:G32-OWNERSHIP-ISOLATION")
                        )
                    )
                )
            )
        }
    }

    private class OwnershipProjectionReadSource(
        private val db: SQLiteDatabase,
        private val asset: OwnedAssetRef
    ) : PlayerSnapshotReadSource {
        override fun identity(campaignUid: String, characterUid: String) = listOf(CharacterPanelIdentityV2("uid", characterUid))
        override fun stats(campaignUid: String, characterUid: String) = emptyList<CharacterPanelExactValueV2>()
        override fun resources(campaignUid: String, characterUid: String) = emptyList<CharacterPanelExactValueV2>()
        override fun skills(campaignUid: String, characterUid: String) = emptyList<CharacterPanelMasteryV2>()
        override fun techniques(campaignUid: String, characterUid: String) = emptyList<CharacterPanelMasteryV2>()
        override fun talent(campaignUid: String, characterUid: String) = emptyList<CharacterPanelProfileValueV2>()
        override fun potential(campaignUid: String, characterUid: String) = emptyList<CharacterPanelProfileValueV2>()
        override fun innateAndEvolution(campaignUid: String, characterUid: String) = emptyList<CharacterPanelInnateV2>()
        override fun inventory(campaignUid: String, characterUid: String) = emptyList<CharacterPanelInventoryV2>()
        override fun equipment(campaignUid: String, characterUid: String) = emptyList<CharacterPanelEquipmentV2>()
        override fun ownershipAndAssets(campaignUid: String, characterUid: String) =
            OwnershipStore(db, campaignUid).currentOwnership(asset).map {
                CharacterPanelOwnershipV2(it.asset.assetKindUid, it.asset.assetUid, it.owner.ownerUid)
            }
        override fun economy(campaignUid: String, characterUid: String) = emptyList<CharacterPanelEconomyV2>()
        override fun progression(campaignUid: String, characterUid: String) = emptyList<CharacterPanelProgressionV2>()
        override fun projects(campaignUid: String, characterUid: String) = emptyList<CharacterPanelProjectV2>()
        override fun relationships(campaignUid: String, characterUid: String) = emptyList<CharacterPanelRelationshipV2>()
        override fun goals(campaignUid: String, characterUid: String) = emptyList<CharacterPanelGoalV2>()
        override fun truthViews(campaignUid: String, characterUid: String) = emptyList<PlayerTruthView>()
    }

    private fun eventUid(db: SQLiteDatabase, transactionUid: String): String =
        db.rawQuery(
            "SELECT event_uid FROM canonical_gameplay_events WHERE campaign_uid='C1' AND transaction_uid=?",
            arrayOf(transactionUid)
        ).use { c ->
            assertTrue(c.moveToFirst())
            c.getString(0)
        }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }
}
