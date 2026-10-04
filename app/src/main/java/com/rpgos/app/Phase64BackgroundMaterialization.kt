package com.rpgos.app

internal data class Phase64Materialization(val changes:List<PlayerDomainChange>,val events:List<PlayerEventIntent>,val ledgers:List<PlayerLedgerIntent>)

internal fun phase64BoundPayload(payload:PlayerDomainChangePayload,changes:List<PlayerDomainChangePayload>):Boolean {
    val receipts=changes.filterIsInstance<BackgroundProcessChange>()
    if(payload is BackgroundProcessChange)return receipts.any { it==payload }
    val fingerprint=Phase64BackgroundCodec.fingerprint(payload)
    return receipts.any { fingerprint in it.consequenceFingerprints }
}

/** Preserve typed transfers and one exact event per knowledge acquisition. AI never supplies
 * these payloads: they originate in registered owner evaluation and ordinary command admission. */
internal fun phase64MaterializeChanges(campaign:String,command:String,payloads:List<PlayerDomainChangePayload>):Phase64Materialization {
    if(payloads.isEmpty())return Phase64Materialization(emptyList(),emptyList(),emptyList())
    val receipts=payloads.filterIsInstance<BackgroundProcessChange>()
    require(receipts.isNotEmpty() && receipts.all { it.campaignUid==campaign }) { "P64:PROCESS_EVIDENCE_REQUIRED" }
    val changes=mutableListOf<PlayerDomainChange>();val events=mutableListOf<PlayerEventIntent>();val ledgers=mutableListOf<PlayerLedgerIntent>()
    payloads.forEachIndexed { index,payload->
        val worker=TypedPlayerChangeRegistry.core().encodeWorkerPayload(payload)
        val fingerprint=phase63Hash(worker.toString())
        val receipt=if(payload is BackgroundProcessChange)payload else receipts.firstOrNull { fingerprint in it.consequenceFingerprints }
            ?:error("P64:UNBOUND_CONSEQUENCE")
        val uid="P64:CHANGE:${phase63Hash("$command|$index|$fingerprint")}";val processRef=DomainRef("WORLD_PROCESS",receipt.process.uid)
        val kind=worker.getValue("kind").let { (it as kotlinx.serialization.json.JsonPrimitive).content }
        val target=phase64EventSubject(payload)?:processRef
        changes+=PlayerDomainChange.create(uid,kind,payload,
            sourceRuleUid="P64:OWNER:${receipt.evidence.ruleUid}:${receipt.evidence.ruleVersion}")
        events+=PlayerEventIntent.create("EVENT:$uid",PlayerEventIntentKinds.DOMAIN_EFFECT,actorRef=receipt.process.actor,
            targetRefs=listOf(target,processRef).distinct(),causalChangeUids=listOf(uid),payload=DomainEffectEventIntentPayload(target,kind))
        if(payload is FinancialChange)ledgers+=PlayerLedgerIntent.create("LEDGER:$uid",PlayerLedgerIntentKinds.FINANCIAL_TRANSFER,listOf(uid),
            FinancialTransferLedgerIntentPayload(payload.fromAccountUid,payload.toAccountUid,payload.amountMinor,payload.currencyUid,payload.transactionTypeUid))
    }
    return Phase64Materialization(changes,events,ledgers)
}

internal fun phase64EventSubject(payload:PlayerDomainChangePayload):DomainRef?=when(payload) {
    is BackgroundProjectWorkChange->DomainRef("PROJECT",payload.projectUid)
    is DevelopmentProjectCompletionChange->DomainRef("PROJECT",payload.projectUid)
    is PopulationCohortBirthChange->payload.cohort.aggregate
    is FormationMobilizationChange->payload.formation
    is KnowledgeAcquisitionChange->DomainRef(payload.acquisition.holder.holderKindUid,payload.acquisition.holder.holderUid)
    is AccessAuthorityChange->DomainRef(payload.principalKindUid,payload.principalUid)
    else->phase64References(payload).firstOrNull()
}

internal fun phase64References(payload:PlayerDomainChangePayload):List<DomainRef> = when(payload) {
    is BackgroundProcessChange->listOf(DomainRef("CAMPAIGN",payload.campaignUid),DomainRef("WORLD_PROCESS",payload.process.uid),payload.process.actor)
    is FinancialChange->listOf(DomainRef("FINANCIAL_ACCOUNT",payload.fromAccountUid),DomainRef("FINANCIAL_ACCOUNT",payload.toAccountUid),DomainRef("CURRENCY",payload.currencyUid))
    is InventoryChange->listOf(payload.subject,DomainRef(
        if(payload.quantityDelta.units>0L&&payload.itemMaterialization!=null)"OBJECT" else "ITEM_INSTANCE",payload.itemInstanceUid))
    is ResourceChange->listOf(payload.subject,DomainRef("RESOURCE",payload.resourceUid))
    is MechanicalTrackChange->listOf(payload.subject)
    is SpatialChange->listOf(payload.subject)+listOfNotNull(payload.destinationLocation)
    is ConditionChange->listOf(payload.subject,DomainRef("CONDITION",payload.conditionUid))
    is AggregatePopulationChange->listOf(payload.subject)
    is KnowledgeAcquisitionChange->buildList {
        add(DomainRef(payload.acquisition.holder.holderKindUid,payload.acquisition.holder.holderUid))
        add(DomainRef(payload.claim.subjectKindUid,payload.claim.subjectUid))
        payload.acquisition.sourceHolder?.let { add(DomainRef(it.holderKindUid,it.holderUid)) }
        payload.evidence.forEach { evidence->evidence.sourceRef?.takeIf { it.scope==KnowledgeReferenceScope.CAMPAIGN }
            ?.let { add(DomainRef(it.kindUid,it.entityUid)) } }
    }.distinct()
    is AccessAuthorityChange->buildList {
        add(DomainRef(payload.principalKindUid,payload.principalUid))
        if(payload.subjectKindUid!=null&&payload.subjectUid!=null)add(DomainRef(payload.subjectKindUid,payload.subjectUid))
    }
    is DevelopmentProjectChange->listOf(DomainRef("PROJECT",payload.projectUid))+payload.evidenceRefs
    is BackgroundProjectWorkChange->listOf(DomainRef("PROJECT",payload.projectUid),payload.worker)+payload.evidenceRefs
    is DevelopmentProjectCompletionChange->listOf(DomainRef("PROJECT",payload.projectUid))
    is PopulationCohortBirthChange->listOf(payload.source,payload.cohort.aggregate,payload.origin)
    is FormationMobilizationChange->listOf(payload.formation)
    else->emptyList()
}
