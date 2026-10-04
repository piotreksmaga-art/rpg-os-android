package com.rpgos.app

/** Explicit registered action policy. These values come from Core or an authoritative import,
 * never a model proposal. The catalog registers rules; it does not grant access or start work. */
internal data class Phase64EconomyActivationPolicy(
    val uid: String,
    val version: Int,
    val actionUid: String,
    val durationMillis: Long,
    val provenanceUid: String,
    val playerInitiated: Boolean,
    val npcInitiated: Boolean,
    val npcActivationPolicyUid: String? = null
) {
    init {
        listOf(uid, actionUid, provenanceUid).forEach(::phase64CatalogUid)
        require(version > 0 && durationMillis > 0 && (playerInitiated || npcInitiated))
        npcActivationPolicyUid?.let {
            phase64CatalogUid(it)
            require(npcInitiated) { "P64:NPC_ACTIVATION_POLICY_REQUIRES_NPC" }
        }
    }
}

/** The price is an imported integer quote policy, not an inferred market price or new stock. */
internal data class Phase64RegisteredPricePolicy(
    val uid: String,
    val version: Int,
    val unitPriceMinor: Long,
    val adjustmentBasisPoints: Long,
    val provenanceUid: String
) {
    init {
        phase64CatalogUid(provenanceUid)
        require(version == 1)
        Phase64PricePolicy.quote(uid, unitPriceMinor, 1, adjustmentBasisPoints)
    }
}

/** Captured existing financial owners. Only internal transfers are supported here; foreign
 * exchange, external credits and settlement agreements need their own admitted policy. */
internal data class Phase64RegisteredSettlementPolicy(
    val uid: String,
    val version: Int,
    val payer: FinancialAccount,
    val payee: FinancialAccount,
    val currency: CurrencyDefinition,
    val type: Phase64SettlementType,
    val provenanceUid: String
) {
    init {
        listOf(uid, provenanceUid, payer.accountUid, payee.accountUid, type.uid).forEach(::phase64CatalogUid)
        require(version > 0)
        FinancialPolicy.validateAccount(payer)
        FinancialPolicy.validateAccount(payee)
        FinancialPolicy.validateCurrency(currency)
        require(payer.campaignId == payee.campaignId && payer.accountUid != payee.accountUid)
        require(payer.closedAt == null && payee.closedAt == null && currency.status == "ACTIVE")
        require(payer.currencyUid == currency.currencyUid && payee.currencyUid == currency.currencyUid)
        require(type.flowKind == FinancialFlowKind.INTERNAL && type.status == "ACTIVE" && type.provenanceUid.isNotBlank())
    }
}

/** Complete executable recipes for the existing inventory, finance, topology and project
 * owners. No constructor creates currency, goods, accounts, projects, skills or topology.
 *
 * Native Core can publish consumption and one-unit project work without inventing world facts.
 * Production/trade/payment/delivery require explicit owner data below; absent policies must
 * remain unavailable, not be replaced with parameterless 'supported' action definitions.
 * The inputs are bounded trusted captures/imports; the ordinary owners recheck them at due time.
 */
internal object Phase64EconomyRuleCatalog {
    const val VERSION = "P64:ECONOMY-CATALOG:1"
    private const val CATALOG = "economyCatalogVersion"

    fun coreDefinitions(): List<BackgroundProcessDefinition> = listOf(
        consumption(core(Phase64EconomyOperations.CONSUME, "CONSUME_OWN_ITEM", "P64:CORE:CONSUMPTION_V1")),
        projectWork(core(Phase64EconomyOperations.BUILD, "WORK_ON_BUILD", "P64:CORE:BUILD_LABOUR_V1"), Phase64EconomyOperations.BUILD),
        projectWork(core(Phase64EconomyOperations.REPAIR, "WORK_ON_REPAIR", "P64:CORE:REPAIR_LABOUR_V1"), Phase64EconomyOperations.REPAIR),
        projectWork(core(Phase64EconomyOperations.RESEARCH, "WORK_ON_RESEARCH", "P64:CORE:RESEARCH_LABOUR_V1"), Phase64EconomyOperations.RESEARCH)
    )

    fun consumption(policy: Phase64EconomyActivationPolicy): BackgroundProcessDefinition = definition(policy, "ECONOMY",
        Phase64EconomyOperations.CONSUME, mapOf("inputOwnerKind" to "@ACTOR_KIND", "inputOwnerUid" to "@ACTOR_UID",
            "inputItemUids" to "@TARGET_UID", "activation_target_kind" to "ITEM_INSTANCE"))

    fun projectWork(policy: Phase64EconomyActivationPolicy, operation: String, labourPoolUid: String = "STAMINA",
        labourUnits: Long = 1, progressUnits: Long = 1): BackgroundProcessDefinition {
        require(operation in setOf(Phase64EconomyOperations.BUILD, Phase64EconomyOperations.REPAIR, Phase64EconomyOperations.RESEARCH))
        phase64CatalogUid(labourPoolUid)
        require(labourUnits > 0 && progressUnits > 0)
        return definition(policy, "PROJECT", operation, mapOf("projectUid" to "@TARGET_UID", "activation_target_kind" to "PROJECT",
            "labourPoolUid" to labourPoolUid, "labourUnits" to labourUnits.toString(), "progressUnits" to progressUnits.toString()))
    }

    fun production(policy: Phase64EconomyActivationPolicy, executor: DomainRef, recipe: Phase64ProductionRecipe,
        inputs: List<ItemInstance>, outputs: List<Phase64ProductionOutput>): BackgroundProcessDefinition {
        require(inputs.isNotEmpty() && outputs.isNotEmpty())
        val campaign = inputs.first().campaignId
        require((inputs + outputs.map { it.instance }).all { it.campaignId == campaign })
        fun instanceCounts(items: List<ItemInstance>) = items.groupingBy { it.itemDefinitionUid }.eachCount().mapValues { it.value.toLong() }
        require(instanceCounts(inputs) == recipe.inputDefinitionCounts && instanceCounts(outputs.map { it.instance }) == recipe.outputDefinitionCounts)
        val inputUids = inputs.map { it.itemInstanceUid }
        val outputUids = outputs.map { it.instance.itemInstanceUid }
        require(inputUids.intersect(outputUids.toSet()).isEmpty())
        outputs.forEach { output ->
            require(output.definition.definitionStatus == ItemDefinitionStatus.ACTIVE &&
                output.definition.storagePolicy == ItemStoragePolicy.UNIQUE_INSTANCE && output.instance.itemDefinitionUid == output.definition.itemDefinitionUid)
            require(if (output.materialization == null) output.sourceObject == null else
                output.materialization == universalInventoryItemMaterialization() &&
                    output.definition.itemDefinitionUid == UNIVERSAL_WORLD_OBJECT_ITEM_DEFINITION_UID &&
                    output.sourceObject == DomainRef("OBJECT", output.instance.itemInstanceUid))
        }
        val parameters = executorParameters(executor, campaign) + mapOf("inputItemUids" to uids(inputUids), "outputItemUids" to uids(outputUids),
            "recipeUid" to recipe.uid, "recipeVersion" to recipe.version.toString(), "recipeProvenanceUid" to recipe.provenanceUid,
            "recipeInputDefinitionCounts" to counts(recipe.inputDefinitionCounts), "recipeOutputDefinitionCounts" to counts(recipe.outputDefinitionCounts)) +
            outputs.filter { it.materialization != null }.map { it.instance.itemInstanceUid }.takeIf { it.isNotEmpty() }
                ?.let { mapOf("productionObjectUids" to uids(it)) }.orEmpty()
        return definition(policy, "ECONOMY", Phase64EconomyOperations.PRODUCE, parameters)
    }

    fun trade(policy: Phase64EconomyActivationPolicy, seller: DomainRef, buyer: DomainRef, inputs: List<ItemInstance>,
        settlement: Phase64RegisteredSettlementPolicy, price: Phase64RegisteredPricePolicy): BackgroundProcessDefinition {
        require(inputs.isNotEmpty() && seller != buyer && inputs.all { it.campaignId == settlement.payer.campaignId })
        require(settlement.payer.holder == OwnershipOwnerRef(buyer.kindUid, buyer.uid) &&
            settlement.payee.holder == OwnershipOwnerRef(seller.kindUid, seller.uid))
        Phase64PricePolicy.quote(price.uid, price.unitPriceMinor, inputs.size.toLong(), price.adjustmentBasisPoints)
        return definition(policy, "ECONOMY", Phase64EconomyOperations.TRADE, executorParameters(seller, settlement.payer.campaignId) +
            settlementParameters(settlement) + mapOf("inputItemUids" to uids(inputs.map { it.itemInstanceUid }), "buyerKind" to buyer.kindUid,
                "buyerUid" to buyer.uid, "pricePolicyUid" to price.uid, "pricePolicyVersion" to price.version.toString(),
                "unitPriceMinor" to price.unitPriceMinor.toString(), "priceAdjustmentBasisPoints" to price.adjustmentBasisPoints.toString(),
                "priceProvenanceUid" to price.provenanceUid))
    }

    fun payment(policy: Phase64EconomyActivationPolicy, settlement: Phase64RegisteredSettlementPolicy,
        amountMinor: Long): BackgroundProcessDefinition {
        require(amountMinor > 0)
        val holder = settlement.payer.holder
        return definition(policy, "ECONOMY", Phase64EconomyOperations.PAYMENT,
            executorParameters(DomainRef(holder.ownerKindUid, holder.ownerUid), settlement.payer.campaignId) + settlementParameters(settlement) +
                ("amountMinor" to amountMinor.toString()))
    }

    /** The supplied route must be the carrier's protected authorized capture, not a shared
     * parent or an arbitrary destination. Delivery never automates the active player's walk. */
    fun delivery(policy: Phase64EconomyActivationPolicy, carrier: MechanicalActorView, cargo: List<ItemInstance>,
        recipient: DomainRef, route: WorldTravelPlan): BackgroundProcessDefinition {
        require(!policy.playerInitiated && policy.npcInitiated)
        require(carrier.actor.kindUid != "PLAYER" && carrier.kind != MechanicalActorKind.ACTIVE_PLAYER &&
            carrier.materialization == MechanicalStateMaterialization.FULL && recipient != carrier.actor)
        require(carrier.locationRef?.let { WorldTopologyAnchor.same(it, route.origin) } == true)
        require(policy.durationMillis >= route.duration.milliseconds && route.edges.size <= 32)
        require(route.edges.all { carrier.executableAbilityUids.containsAll(it.requiredCapabilities) })
        require(route.resourceCosts.all { (uid, units) -> carrier.resources.singleOrNull { it.resourceUid == uid }?.current?.let { it >= units } == true })
        require(cargo.isNotEmpty() && cargo.all { it.campaignId == carrier.campaignUid })
        return definition(policy, "ECONOMY", Phase64EconomyOperations.DELIVER, executorParameters(carrier.actor, carrier.campaignUid) +
            mapOf("inputItemUids" to uids(cargo.map { it.itemInstanceUid }), "recipientKind" to recipient.kindUid,
                "recipientUid" to recipient.uid, "destinationKind" to route.destination.kindUid, "destinationUid" to route.destination.uid,
                "routeUid" to route.fingerprint, "routeEdgeUids" to uids(route.edges.map { it.uid })))
    }

    fun projectCompletion(policy: Phase64EconomyActivationPolicy, executor: DomainRef, snapshot: Phase64ProjectSnapshot,
        evidence: Phase64ProjectResultEvidence, output: DomainRef): BackgroundProcessDefinition {
        val project = snapshot.project
        val work = evidence.work
        require(snapshot.progress.status == ProjectStatus.READY_TO_COMPLETE && work.campaignId == project.campaignId &&
            work.projectUid == project.projectUid && work.result in setOf(ProjectWorkResult.SUCCESS, ProjectWorkResult.BREAKTHROUGH))
        require(snapshot.progress.progressCapUnits?.let { snapshot.progress.progressUnits >= it } == true &&
            snapshot.progress.achievedRequiredMilestones >= snapshot.progress.requiredMilestones && evidence.eventUid.isNotBlank())
        val kind = requireNotNull(project.intendedOutputKindUid)
        require(project.targetKindUid == output.kindUid && project.targetUid == output.uid)
        return definition(policy, "PROJECT", Phase64EconomyOperations.COMPLETE_PROJECT, executorParameters(executor, project.campaignId) +
            mapOf("projectUid" to project.projectUid, "projectVersion" to project.projectVersion.toString(),
                "resultEvidenceUid" to work.workRecordUid, "outputKindUid" to kind, "outputRefKindUid" to output.kindUid,
                "outputUid" to output.uid, "activation_target_kind" to "PROJECT", "activation_target_uid" to project.projectUid))
    }

    /** Root's activation binder calls this after the ordinary reference substitutions and
     * before its size/reserved-key checks. No extra placeholder or AI-supplied cost is needed. */
    fun bind(rule: BackgroundProcessDefinition, actor: DomainRef, target: DomainRef, parameters: Map<String, String>): Map<String, String> {
        if (rule.parameters[CATALOG] == null) return parameters
        require(rule.parameters[CATALOG] == VERSION && acceptsExecutor(rule, actor)) { "P64:REGISTERED_ECONOMY_EXECUTOR_REQUIRED" }
        rule.parameters["activation_target_kind"]?.let { require(target.kindUid == it) { "P64:REGISTERED_ECONOMY_TARGET_REQUIRED" } }
        rule.parameters["activation_target_uid"]?.let { require(target.uid == it) { "P64:REGISTERED_ECONOMY_TARGET_REQUIRED" } }
        return parameters
    }

    fun acceptsExecutor(rule: BackgroundProcessDefinition, actor: DomainRef): Boolean {
        val kind = rule.parameters["executorKind"]
        val uid = rule.parameters["executorUid"]
        return (kind == null && uid == null || kind == actor.kindUid && uid == actor.uid) &&
            (rule.parameters[CATALOG] == null || rule.parameters[CATALOG] == VERSION)
    }

    private fun core(operation: String, action: String, provenance: String) =
        Phase64EconomyActivationPolicy("P64:CORE:$operation", 1, action, 60_000, provenance, true, false)

    private fun definition(policy: Phase64EconomyActivationPolicy, domain: String, operation: String,
        parameters: Map<String, String>): BackgroundProcessDefinition = BackgroundProcessDefinition(policy.uid, policy.version, domain,
        operation, policy.durationMillis, parameters = parameters + mapOf(CATALOG to VERSION, "sourceUid" to policy.provenanceUid,
            Phase64ProcessActivation.ACTION_KEY to policy.actionUid, Phase64ProcessActivation.PUBLIC_KEY to policy.playerInitiated.toString(),
            "activation_npc" to policy.npcInitiated.toString()) +
            policy.npcActivationPolicyUid?.let { mapOf("activation_policy_uid" to it) }.orEmpty())

    private fun executorParameters(actor: DomainRef, campaign: String): Map<String, String> {
        listOf(actor.kindUid, actor.uid, campaign).forEach(::phase64CatalogUid)
        return mapOf("executorKind" to actor.kindUid, "executorUid" to actor.uid, "economyCampaignUid" to campaign)
    }

    private fun settlementParameters(policy: Phase64RegisteredSettlementPolicy) = mapOf("settlementPolicyUid" to policy.uid,
        "settlementPolicyVersion" to policy.version.toString(), "settlementProvenanceUid" to policy.provenanceUid,
        "payerAccountUid" to policy.payer.accountUid, "payeeAccountUid" to policy.payee.accountUid,
        "payerAccountVersion" to policy.payer.version.toString(), "payeeAccountVersion" to policy.payee.version.toString(),
        "payerHolderKind" to policy.payer.holder.ownerKindUid, "payerHolderUid" to policy.payer.holder.ownerUid,
        "payeeHolderKind" to policy.payee.holder.ownerKindUid, "payeeHolderUid" to policy.payee.holder.ownerUid,
        "currencyUid" to policy.currency.currencyUid, "transactionTypeUid" to policy.type.uid)

    private fun uids(values: List<String>): String {
        require(values.size in 1..32 && values.distinct().size == values.size)
        values.forEach(::phase64CatalogUid)
        return values.joinToString("|")
    }

    private fun counts(values: Map<String, Long>): String {
        values.keys.forEach(::phase64CatalogUid)
        return values.toSortedMap().entries.joinToString("|") { "${it.key}=${it.value}" }
    }
}

private fun phase64CatalogUid(value: String) {
    require(value.isNotBlank() && value.length <= 160 && '|' !in value && !value.startsWith('@')) { "P64:FIXED_CATALOG_UID_REQUIRED" }
}
