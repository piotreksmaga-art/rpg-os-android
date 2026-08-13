package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class PlayerDomainEngineReferenceClassificationTest {
    private val actor = CommandActorRef("PLAYER", "P1")

    @Test fun p18Class01_worldPackSlotIsNotCampaignReference() {
        val refs = draftReferences(equipmentDraft("SLOT:HAND"))
        assertFalse(DomainRef("EQUIPMENT_SLOT", "SLOT:HAND") in refs)
    }

    @Test fun p18Class02_missingSlotCampaignRefDoesNotReject() {
        val outcome = equipmentEngine("SLOT:HAND").resolve(equipCommand("SLOT:HAND"), context(DomainRef("ITEM_INSTANCE", "ITEM:1")))
        assertTrue(outcome is PlayerResolutionOutcome.Resolved)
    }

    @Test fun p18Class03_slotKnownOnlyElsewhereDoesNotBecomeWrongCampaign() {
        val refs = setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef("ITEM_INSTANCE", "ITEM:1")),
            CampaignScopedDomainRef("C2", DomainRef("EQUIPMENT_SLOT", "SLOT:HAND"))
        )
        val outcome = equipmentEngine("SLOT:HAND").resolve(equipCommand("SLOT:HAND"), PlayerResolutionContext.create("C1", actor, refs))
        assertTrue(outcome is PlayerResolutionOutcome.Resolved)
    }

    @Test fun p18Class04_slotSurvivesProposalUnchanged() {
        val outcome = equipmentEngine("SLOT:HAND").resolve(equipCommand("SLOT:HAND"), context(DomainRef("ITEM_INSTANCE", "ITEM:1"))) as PlayerResolutionOutcome.Resolved
        assertEquals("SLOT:HAND", (outcome.proposal.changes.single().payload as EquipmentChange).slotUid)
    }

    @Test fun p18Class05_phase18DoesNotImplementSlotCompatibilityRules() {
        val outcome = equipmentEngine("SLOT:NOT-CHECKED-BY-P18").resolve(equipCommand("SLOT:NOT-CHECKED-BY-P18"), context(DomainRef("ITEM_INSTANCE", "ITEM:1")))
        assertTrue(outcome is PlayerResolutionOutcome.Resolved)
    }

    @Test fun p18Class06_commandAndDraftSlotClassificationAgree() {
        assertFalse(DomainRef("EQUIPMENT_SLOT", "SLOT:HAND") in commandReferences(equipCommand("SLOT:HAND")))
        assertFalse(DomainRef("EQUIPMENT_SLOT", "SLOT:HAND") in draftReferences(equipmentDraft("SLOT:HAND")))
    }

    @Test fun p18Class10_existingFullOwnedAssetAccepts() = assertResolved(ownershipOutcome("ASSET:1", baseOwnershipRefs()))

    @Test fun p18Class11_unknownAssetRejects() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("ASSET_KIND:A", "ASSET:1") }.toSet()
        assertRejected(ownershipOutcome("ASSET:1", refs), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, DomainRef("ASSET_KIND:A", "ASSET:1"))
    }

    @Test fun p18Class12_sameAssetUidWrongKindDoesNotSatisfyLookup() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("ASSET_KIND:A", "ASSET:1") }.toMutableSet()
        refs += CampaignScopedDomainRef("C1", DomainRef("ASSET_KIND:B", "ASSET:1"))
        assertRejected(ownershipOutcome("ASSET:1", refs), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, DomainRef("ASSET_KIND:A", "ASSET:1"))
    }

    @Test fun p18Class13_wrongCampaignAssetRejects() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("ASSET_KIND:A", "ASSET:1") }.toMutableSet()
        refs += CampaignScopedDomainRef("C2", DomainRef("ASSET_KIND:A", "ASSET:1"))
        assertRejected(ownershipOutcome("ASSET:1", refs), PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, DomainRef("ASSET_KIND:A", "ASSET:1"))
    }

    @Test fun p18Class14_existingFromOwnerAccepts() = assertResolved(ownershipOutcome("ASSET:1", baseOwnershipRefs()))

    @Test fun p18Class15_unknownFromOwnerRejects() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("OWNER_KIND:PARTY", "OWNER:FROM") }.toSet()
        assertRejected(ownershipOutcome("ASSET:1", refs), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, DomainRef("OWNER_KIND:PARTY", "OWNER:FROM"))
    }

    @Test fun p18Class16_existingToOwnerAccepts() = assertResolved(ownershipOutcome("ASSET:1", baseOwnershipRefs()))

    @Test fun p18Class17_unknownToOwnerRejects() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("OWNER_KIND:PARTY", "OWNER:TO") }.toSet()
        assertRejected(ownershipOutcome("ASSET:1", refs), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, DomainRef("OWNER_KIND:PARTY", "OWNER:TO"))
    }

    @Test fun p18Class18_componentCannotSubstituteGhostOwnershipIdentity() {
        val command = transferOwnershipCommand(DomainRef("ASSET_KIND:A", "ASSET:CMD"), DomainRef("OWNER_KIND:PARTY", "OWNER:CMD-TO"))
        val refs = setOf(
            CampaignScopedDomainRef("C1", DomainRef("ASSET_KIND:A", "ASSET:CMD")),
            CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:CMD-TO")),
            CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:FROM")),
            CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:TO"))
        )
        assertRejected(
            ownershipEngine("ASSET:GHOST").resolve(command, PlayerResolutionContext.create("C1", actor, refs)),
            PlayerResolutionRejectionReason.UNKNOWN_REFERENCE,
            DomainRef("ASSET_KIND:A", "ASSET:GHOST")
        )
    }

    @Test fun p18Class19_commandValidIdentityDoesNotBlessUnrelatedDraftIdentity() {
        val command = transferOwnershipCommand(DomainRef("ASSET_KIND:A", "ASSET:COMMAND"), DomainRef("OWNER_KIND:PARTY", "OWNER:TO"))
        val refs = baseOwnershipRefs().toMutableSet()
        refs += CampaignScopedDomainRef("C1", DomainRef("ASSET_KIND:A", "ASSET:COMMAND"))
        assertRejected(
            ownershipEngine("ASSET:DRAFT-ONLY").resolve(command, PlayerResolutionContext.create("C1", actor, refs)),
            PlayerResolutionRejectionReason.UNKNOWN_REFERENCE,
            DomainRef("ASSET_KIND:A", "ASSET:DRAFT-ONLY")
        )
    }

    @Test fun p18Class20_fullOwnershipNamespaceSurvivesProposal() {
        val expected = ownershipChange("ASSET:1")
        val outcome = ownershipOutcome("ASSET:1", baseOwnershipRefs()) as PlayerResolutionOutcome.Resolved
        val actual = outcome.proposal.changes.single().payload as OwnershipChange
        assertEquals(expected.asset, actual.asset)
        assertEquals(expected.fromOwner, actual.fromOwner)
        assertEquals(expected.toOwner, actual.toOwner)
        assertEquals(expected.ownershipRecordUid, actual.ownershipRecordUid)
    }

    private fun equipmentDraft(slot: String) = PlayerResolutionDraft.create(changes = listOf(
        PlayerDomainChange("CHANGE:EQUIP", PlayerChangeKinds.EQUIPMENT, EquipmentChange(DomainRef("PLAYER", "P1"), slot, EquipmentOperation.EQUIP, "ITEM:1"))
    ))

    private fun equipCommand(slot: String) = PlayerCommand(
        commandUid = "CMD:EQUIP", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.EQUIP_ITEM,
        payload = EquipItemCommandPayload(DomainRef("ITEM_INSTANCE", "ITEM:1"), slot), provenance = CommandProvenance("TEST")
    )

    private fun equipmentEngine(slot: String) = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(EquipmentComponent(slot))))

    private fun ownershipChange(assetUid: String) = OwnershipChange(
        ownershipRecordUid = "OWNERSHIP:NEW",
        asset = OwnedAssetRef("ASSET_KIND:A", assetUid),
        fromOwner = OwnershipOwnerRef("OWNER_KIND:PARTY", "OWNER:FROM"),
        toOwner = OwnershipOwnerRef("OWNER_KIND:PARTY", "OWNER:TO"),
        share = OwnershipShare.full()
    )

    private fun transferOwnershipCommand(
        subject: DomainRef = DomainRef("ASSET_KIND:A", "ASSET:1"),
        toParty: DomainRef = DomainRef("OWNER_KIND:PARTY", "OWNER:TO")
    ) = PlayerCommand(
        commandUid = "CMD:OWNERSHIP", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.TRANSFER_OWNERSHIP,
        payload = TransferOwnershipCommandPayload(subject, toParty, OWNERSHIP_SHARE_SCALE), provenance = CommandProvenance("TEST")
    )

    private fun ownershipEngine(assetUid: String) = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(OwnershipComponent(assetUid))))

    private fun ownershipOutcome(assetUid: String, refs: Set<CampaignScopedDomainRef>) =
        ownershipEngine(assetUid).resolve(transferOwnershipCommand(), PlayerResolutionContext.create("C1", actor, refs))

    private fun baseOwnershipRefs() = setOf(
        CampaignScopedDomainRef("C1", DomainRef("ASSET_KIND:A", "ASSET:1")),
        CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:FROM")),
        CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:TO"))
    )

    private fun context(vararg refs: DomainRef) = PlayerResolutionContext.create(
        "C1", actor, (listOf(DomainRef("PLAYER", "P1")) + refs).map { CampaignScopedDomainRef("C1", it) }.toSet()
    )

    private fun assertResolved(outcome: PlayerResolutionOutcome) = assertTrue(outcome is PlayerResolutionOutcome.Resolved)

    private fun assertRejected(outcome: PlayerResolutionOutcome, reason: PlayerResolutionRejectionReason, ref: DomainRef) {
        assertTrue(outcome is PlayerResolutionOutcome.Rejected)
        val rejection = (outcome as PlayerResolutionOutcome.Rejected).rejection
        assertEquals(reason, rejection.reason)
        assertEquals(listOf(ref), rejection.relatedRefs)
    }

    private class EquipmentComponent(private val slot: String) : PlayerResolutionComponent<EquipItemCommandPayload>(
        PlayerCommandKinds.EQUIP_ITEM, EquipItemCommandPayload::class, "COMP:EQUIP-CLASS", "1"
    ) {
        override fun resolve(command: PlayerCommand<EquipItemCommandPayload>, context: PlayerResolutionContext) =
            PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(
                PlayerDomainChange("CHANGE:EQUIP", PlayerChangeKinds.EQUIPMENT, EquipmentChange(DomainRef("PLAYER", "P1"), slot, EquipmentOperation.EQUIP, "ITEM:1"))
            )))
    }

    private class OwnershipComponent(private val assetUid: String) : PlayerResolutionComponent<TransferOwnershipCommandPayload>(
        PlayerCommandKinds.TRANSFER_OWNERSHIP, TransferOwnershipCommandPayload::class, "COMP:OWNERSHIP-CLASS", "1"
    ) {
        override fun resolve(command: PlayerCommand<TransferOwnershipCommandPayload>, context: PlayerResolutionContext) =
            PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(
                PlayerDomainChange(
                    "CHANGE:OWNERSHIP",
                    PlayerChangeKinds.OWNERSHIP,
                    OwnershipChange(
                        ownershipRecordUid = "OWNERSHIP:NEW",
                        asset = OwnedAssetRef("ASSET_KIND:A", assetUid),
                        fromOwner = OwnershipOwnerRef("OWNER_KIND:PARTY", "OWNER:FROM"),
                        toOwner = OwnershipOwnerRef("OWNER_KIND:PARTY", "OWNER:TO"),
                        share = OwnershipShare.full()
                    )
                )
            )))
    }
}
