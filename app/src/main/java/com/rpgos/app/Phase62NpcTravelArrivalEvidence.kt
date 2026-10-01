package com.rpgos.app

/**
 * Post-commit proof for travel. Verified effects and pre-commit SpatialChange candidates are not
 * arrival receipts. This adapter accepts only a persisted V3 receipt plus the exact replay payload
 * written by the same TurnTransaction.
 */
internal object NpcTravelArrivalEvidence {
    fun fromCommitted(
        attempt:NpcActivityAttemptIdentity,
        route:NpcTravelRouteContract,
        receipt:TurnCommitReceipt,
        replay:CommittedReplayPayload
    ):NpcActivityResolutionEvidence? {
        val order=receipt.commitOrder?:return null
        if(receipt.receiptVersion<TURN_TRANSACTION_RECEIPT_VERSION)return null
        if(attempt.campaignUid!=route.campaignUid || attempt.campaignUid!=receipt.campaignUid)return null
        val owner=NpcTravelAffordances.ownerContract(route)
        if(attempt.ownerContractFingerprint!=owner.fingerprint || attempt.actor.kindUid=="PLAYER")return null
        if(attempt.capabilityUid!=route.capabilityUid)return null
        if(replay.identity.campaignUid!=receipt.campaignUid ||
            replay.identity.turnUid!=receipt.turnUid ||
            replay.identity.commandUid!=receipt.commandUid ||
            replay.identity.transactionUid!=receipt.transactionUid ||
            replay.commitOrder!=order ||
            replay.semanticFingerprint!=receipt.semanticFingerprint)return null
        if(replay.changeSet.campaignUid!=receipt.campaignUid ||
            replay.changeSet.sourceCommandUid!=receipt.commandUid)return null

        val arrivals=replay.changeSet.changes.filter { change->
            if(change.changeKindUid!=PlayerChangeKinds.SPATIAL)return@filter false
            val spatial=change.payload as? SpatialChange?:return@filter false
            spatial.subject==attempt.actor && spatial.destinationLocation==route.destination
        }
        if(arrivals.size!=1)return null
        val arrival=arrivals.single()
        val sourceFingerprint=phase60Hash(
            "P62:TRAVEL_COMMITTED_ARRIVAL:1|" +
                listOf(receipt.transactionUid,order.toString(),receipt.semanticFingerprint,
                    receipt.resultFingerprint,replay.payloadSha256,arrival.changeUid,route.fingerprint).joinToString("|")
        )
        return NpcActivityResolutionEvidence(
            attemptFingerprint=attempt.fingerprint,
            ownerUid=owner.resultOwnerUid,
            evidenceKindUid=owner.evidenceKindUid,
            resolutionUid="P62:TRAVEL_ARRIVAL:${receipt.transactionUid}",
            resolutionKind=NpcActivityResolutionKind.SUCCEEDED,
            resolvedAt=attempt.dueAt,
            canonicalEvidence=listOf(NpcActivityCanonicalEvidence(arrival.changeUid,PlayerChangeKinds.SPATIAL)),
            sourceFingerprint=sourceFingerprint
        )
    }
}
