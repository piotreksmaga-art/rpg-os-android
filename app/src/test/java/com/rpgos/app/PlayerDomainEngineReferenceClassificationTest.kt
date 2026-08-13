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
        val outcome = equipmentEngine("SLOT:HAND").resolve(equipCommand("SLOT:HAND"), context(
            DomainRef("ITEM_INSTANCE", "ITEM:1")
        ))
        assertTrue(outcome is PlayerResolutionOutcome.Resolved)
    }

    @Test fun p18Class03_slotKnownOnlyElsewhereDoesNotBecomeWrongCampaign() {
        val refs = setOf(
            CampaignScopedDomainRef("C1", DomainRef("ITEM_INSTANCE", "ITEM:1")),
            CampaignScopedDomainRef("C2", DomainRef("EQUIPMENT_SLOT", "SLOT:HAND"))
        )
        val outcome = equipmentEngine("SLOT:HAND").resolve(
            equipCommand("SLOT:HAND"),
            PlayerResolutionContext.create("C1", actor, refs)
        )
        assertTrue(outcome is PlayerResolutionOutcome.Resolved)
    }

    @Test fun p18Class04_slotSurvivesProposalUnchanged() {
        val outcome = equipmentEngine("SLOT:HAND").resolve(equipCommand("SLOT:HAND"), context(
            DomainRef("ITEM_INSTANCE", "ITEM:1")
        )) as PlayerResolutionOutcome.Resolved
        val change = outcome.proposal.changes.single().payload as EquipmentChange
        assertEquals("SLOT:HAND", change.slotUid)
    }

    @Test fun p18Class05_phase18DoesNotImplementSlotCompatibilityRules() {
        val outcome = equipmentEngine("SLOT:NOT-CHECKED-BY-P18").resolve(
            equipCommand("SLOT:NOT-CHECKED-BY-P18"),
            context(DomainRef("ITEM_INSTANCE", "ITEM:1"))
        )
        assertTrue(outcome is PlayerResolutionOutcome.Resolved)
    }

    @Test fun p18Class06_commandAndDraftSlotClassificationAgree() {
        assertFalse(DomainRef("EQUIPMENT_SLOT", "SLOT:HAND") in commandReferences(equipCommand("SLOT:HAND")))
        assertFalse(DomainRef("EQUIPMENT_SLOT", "SLOT:HAND") in draftReferences(equipmentDraft("SLOT:HAND")))
    }

    @Test fun p18Class10_existingFullOwnedAssetAccepts() {
        assertResolved(ownershipOutcome(ownershipChange(), baseOwnershipRefs()))
    }

    @Test fun p18Class11_unknownAssetRejects() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("ASSET_KIND:A", "ASSET:1") }.toSet()
        assertRejected(ownershipOutcome(ownershipChange(), refs), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, DomainRef("ASSET_KIND:A", "ASSET:1"))
    }

    @Test fun p18Class12_sameAssetUidWrongKindDoesNotSatisfyLookup() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("ASSET_KIND:A", "ASSET:1") }.toMutableSet()
        refs += CampaignScopedDomainRef("C1", DomainRef("ASSET_KIND:B", "ASSET:1"))
        assertRejected(ownershipOutcome(ownershipChange(), refs), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, DomainRef("ASSET_KIND:A", "ASSET:1"))
    }

    @Test fun p18Class13_wrongCampaignAssetRejects() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("ASSET_KIND:A", "ASSET:1") }.toMutableSet()
        refs += CampaignScopedDomainRef("C2", DomainRef("ASSET_KIND:A", "ASSET:1"))
        assertRejected(ownershipOutcome(ownershipChange(), refs), PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, DomainRef("ASSET_KIND:A", "ASSET:1"))
    }

    @Test fun p18Class14_existingFromOwnerAccepts() {
        assertResolved(ownershipOutcome(ownershipChange(), baseOwnershipRefs()))
    }

    @Test fun p18Class15_unknownFromOwnerRejects() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("OWNER_KIND:PARTY", "OWNER:FROM") }.toSet()
        assertRejected(ownershipOutcome(ownershipChange(), refs), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, DomainRef("OWNER_KIND:PARTY", "OWNER:FROM"))
    }

    @Test fun p18Class16_existingToOwnerAccepts() {
        assertResolved(ownershipOutcome(ownershipChange(), baseOwnershipRefs()))
    }

    @Test fun p18Class17_unknownToOwnerRejects() {
        val refs = baseOwnershipRefs().filterNot { it.ref == DomainRef("OWNER_KIND:PARTY", "OWNER:TO") }.toSet()
        assertRejected(ownershipOutcome(ownershipChange(), refs), PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, DomainRef("OWNER_KIND:PARTY", "OWNER:TO"))
    }

    @Test fun p18Class18_componentCannotSubstituteGhostOwnershipIdentity() {
        val command = transferOwnershipCommand(DomainRef("ASSET_KIND:A", "ASSET:CMD"), DomainRef("OWNER_KIND:PARTY", "OWNER:CMD-TO"))
        val refs = setOf(
            CampaignScopedDomainRef("C1", DomainRef("ASSET_KIND:A", "ASSET:CMD")),
            CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:CMD-TO")),
            CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:FROM")),
            CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:TO"))
        )
        val outcome = ownershipEngine(ownershipChange(assetUid = "ASSET:GHOST")).resolve(command, PlayerResolutionContext.create("C1", actor, refs))
        assertRejected(outcome, PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, DomainRef("ASSET_KIND:A", "ASSET:GHOST"))
    }

    @Test fun p18Class19_commandValidIdentityDoesNotBlessUnrelatedDraftIdentity() {
        p18Class18_componentCannotSubstituteGhostOwnershipIdentity()
    }

    @Test fun p18Class20_fullOwnershipNamespaceSurvivesProposal() {
        val change = ownershipChange()
        val outcome = ownershipOutcome(change, baseOwnershipRefs()) as PlayerResolutionOutcome.Resolved
        val actual = outcome.proposal.changes.single().payload as OwnershipChange
        assertEquals(change.asset, actual.asset)
        assertEquals(change.fromOwner, actual.fromOwner)
        assertEquals(change.toOwner, actual.toOwner)
        assertEquals(change.ownershipRecordUid, actual.ownershipRecordUid)
    }

    private fun equipmentDraft(slot: String) = PlayerResolutionDraft.create(changes = listOf(
        PlayerDomainChange("CHANGE:EQUIP", PlayerChangeKinds.EQUIPMENT, EquipmentChange(DomainRef("PLAYER", "P1"), slot, EquipmentOperation.EQUIP, "ITEM:1"))
    ))

    private fun equipCommand(slot: String) = PlayerCommand(
        commandUid = "CMD:EQUIP", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.EQUIP_ITEM,
        payload = EquipItemCommandPayload(DomainRef("ITEM_INSTANCE", "ITEM:1"), slot), provenance = CommandProvenance("TEST")
    )

    private fun equipmentEngine(slot: String) = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(
        object : PlayerResolutionComponent<EquipItemCommandPayload>(PlayerCommandKinds.EQUIP_ITEM, EquipItemCommandPayload::class, "COMP:EQUIP-CLASS", "1") {
            override fun resolve(command: PlayerCommand<EquipItemCommandPayload>, context: PlayerResolutionContext) =
                PlayerResolutionComponentOutcome.Resolved(equipmentDraft(slot))
        }
    )))

    private fun ownershipChange(assetUid: String = "ASSET:1") = OwnershipChange(
        ownershipRecordUid = "OWNERSHIP:NEW",
        asset = OwnedAssetRef("ASSET_KIND:A", assetUid),
        fromOwner = OwnershipOwnerRef("OWNER_KIND:PARTY", "OWNER:FROM"),
        toOwner = OwnershipOwnerRef("OWNER_KIND:PARTY", "OWNER:TO"),
        share = OwnershipShare.full()
    )

    private fun transferOwnershipCommand(subject: DomainRef = DomainRef("ASSET_KIND:A", "ASSET:1"), toParty: DomainRef = DomainRef("OWNER_KIND:PARTY", "OWNER:TO")) = PlayerCommand(
        commandUid = "CMD:OWNERSHIP", campaignUid = "C1", actor = actor, commandKindUid = PlayerCommandKinds.TRANSFER_OWNERSHIP,
        payload = TransferOwnershipCommandPayload(subject, toParty, OWNERSHIP_SHARE_SCALE), provenance = CommandProvenance("TEST")
    )

    private fun ownershipEngine(change: OwnershipChange) = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(
        object : PlayerResolutionComponent<TransferOwnershipCommandPayload>(PlayerCommandKinds.TRANSFER_OWNERSHIP, TransferOwnershipCommandPayload::class, "COMP:OWNERSHIP-CLASS", "1") {
            override fun resolve(command: PlayerCommand<TransferOwnershipCommandPayload>, context: PlayerResolutionContext) =
                PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(
                    PlayerDomainChange("CHANGE:OWNERSHIP", PlayerChangeKinds.OWNERSHIP, change)
                )))
        }
    )))

    private fun ownershipOutcome(change: OwnershipChange, refs: Set<CampaignScopedDomainRef>): PlayerResolutionOutcome =
        ownershipEngine(change).resolve(transferOwnershipCommand(), PlayerResolutionContext.create("C1", actor, refs))

    private fun baseOwnershipRefs() = setOf(
        CampaignScopedDomainRef("C1", DomainRef("ASSET_KIND:A", "ASSET:1")),
        CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:FROM")),
        CampaignScopedDomainRef("C1", DomainRef("OWNER_KIND:PARTY", "OWNER:TO"))
    )

    private fun context(vararg refs: DomainRef) = PlayerResolutionContext.create(
        "C1", actor, refs.map { CampaignScopedDomainRef("C1", it) }.toSet()
    )

    private fun assertResolved(outcome: PlayerResolutionOutcome) = assertTrue(outcome is PlayerResolutionOutcome.Resolved)

    private fun assertRejected(outcome: PlayerResolutionOutcome, reason: PlayerResolutionRejectionReason, ref: DomainRef) {
        assertTrue(outcome is PlayerResolutionOutcome.Rejected)
        val rejection = (outcome as PlayerResolutionOutcome.Rejected).rejection
        assertEquals(reason, rejection.reason)
        assertEquals(listOf(ref), rejection.relatedRefs)
    }
}
