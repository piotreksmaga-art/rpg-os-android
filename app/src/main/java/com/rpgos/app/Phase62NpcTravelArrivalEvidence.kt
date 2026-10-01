package com.rpgos.app

/**
 * Post-commit proof for travel. Verified effects and pre-commit SpatialChange candidates are not
 * arrival receipts. This adapter accepts only a persisted V3 receipt plus the exact replay payload
 * written by the same TurnTransaction and bound to the exact route fingerprint.
 */
internal object NpcTravelArrivalEvidence {
    fun fromStore(
        db:android.database.sqlite.SQLiteDatabase,
        attempt:NpcActivityAttemptIdentity,
        route:NpcTravelRouteContract,
        transactionUid:String
    ):NpcActivityResolutionEvidence? {
        val receipt=TurnTransactionReceiptStore(db).committedTransaction(transactionUid)?:return null
        val order=receipt.commitOrder?:return null
        val replay=CommittedReplayPayloadStore(db).between(attempt.campaignUid,order-1L,order)
            .singleOrNull{it.identity.transactionUid==transactionUid}?:return null
        return fromCommitted(attempt,route,receipt,replay)
    }

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

        val sourcePrefix="P62:TRAVEL:${route.fingerprint}:"
        val travelChanges=replay.changeSet.changes.filter{it.sourceRuleUid?.startsWith(sourcePrefix)==true}

        val arrivals=travelChanges.filter { change->
            if(change.changeKindUid!=PlayerChangeKinds.SPATIAL)return@filter false
            val spatial=change.payload as? SpatialChange?:return@filter false
            spatial.subject==attempt.actor && spatial.destinationLocation==route.destination &&
                spatial.deltaXMillimetres==0L && spatial.deltaYMillimetres==0L
        }
        if(arrivals.size!=1)return null
        val arrival=arrivals.single()

        val expectedCosts=route.resourceCosts.filterValues{it>0}.toSortedMap()
        val actualCosts=travelChanges.mapNotNull { change->
            if(change.changeKindUid!=PlayerChangeKinds.RESOURCE)return@mapNotNull null
            val resource=change.payload as? ResourceChange?:return@mapNotNull null
            if(resource.subject!=attempt.actor || resource.delta.units>=0)return@mapNotNull null
            resource.resourceUid to Math.negateExact(resource.delta.units)
        }
        if(actualCosts.size!=actualCosts.map{it.first}.distinct().size)return null
        if(actualCosts.toMap().toSortedMap()!=expectedCosts)return null

        if(travelChanges.size!=expectedCosts.size+1)return null

        val sourceFingerprint=phase60Hash(
            "P62:TRAVEL_COMMITTED_ARRIVAL:2|" +
                listOf(receipt.transactionUid,order.toString(),receipt.semanticFingerprint,
                    receipt.resultFingerprint,replay.payloadSha256,arrival.changeUid,route.fingerprint,
                    expectedCosts.entries.joinToString(","){"${it.key}=${it.value}"}).joinToString("|")
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
