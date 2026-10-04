package com.rpgos.app

import java.math.BigInteger

/** Versioned operation keys imported with a background rule, never inferred from prose. */
object Phase64EconomyOperations {
    const val PRODUCE = "ECONOMY_PRODUCTION_V1"
    const val CONSUME = "ECONOMY_CONSUMPTION_V1"
    const val TRADE = "ECONOMY_TRADE_V1"
    const val PAYMENT = "ECONOMY_PAYMENT_V1"
    const val DELIVER = "ECONOMY_DELIVERY_V1"
    const val BUILD = "PROJECT_BUILD_V1"
    const val REPAIR = "PROJECT_REPAIR_V1"
    const val RESEARCH = "PROJECT_RESEARCH_V1"
    const val COMPLETE_PROJECT = "PROJECT_COMPLETE_V1"

    // These calls validate/prepare results using the existing authoritative owner.
    const val OWNED_PRODUCTION = "P64:OWNER:PRODUCTION_V1"
    const val OWNED_DELIVERY = "P64:OWNER:DELIVERY_V1"
    const val OWNED_SETTLEMENT = "P64:OWNER:SETTLEMENT_V1"
    const val OWNED_BUILD_CHECK = "P64:OWNER:PROJECT_BUILD_CHECK_V1"
    const val OWNED_REPAIR_CHECK = "P64:OWNER:PROJECT_REPAIR_CHECK_V1"
    const val OWNED_RESEARCH_CHECK = "P64:OWNER:PROJECT_RESEARCH_CHECK_V1"
    const val OWNED_COMPLETION = "P64:OWNER:PROJECT_COMPLETE_V1"

    val economy = setOf(PRODUCE, CONSUME, TRADE, PAYMENT, DELIVER)
    val projects = setOf(BUILD, REPAIR, RESEARCH, COMPLETE_PROJECT)
}

data class Phase64InventoryHolding(val holder: DomainRef, val itemInstanceUid: String)

/** A captured read identifies custody, while a claim uses the globally unique item UID.
 * Length-prefixing preserves identity even when imported UIDs contain ':' or '|'. */
fun phase64InventoryResource(holder: DomainRef, itemInstanceUid: String): DomainRef {
    require(holder.kindUid.isNotBlank() && holder.uid.isNotBlank() && itemInstanceUid.isNotBlank())
    fun field(value: String) = "${value.length}:$value"
    return DomainRef("INVENTORY_HOLDING", field(holder.kindUid) + field(holder.uid) + field(itemInstanceUid))
}

fun phase64InventoryHolding(resource: DomainRef): Phase64InventoryHolding? {
    if (resource.kindUid != "INVENTORY_HOLDING") return null
    return try {
        var cursor = 0
        fun field(): String {
            val separator = resource.uid.indexOf(':', cursor)
            require(separator > cursor)
            val size = resource.uid.substring(cursor, separator).toInt()
            require(size > 0)
            val start = separator + 1
            val end = Math.addExact(start, size)
            require(end <= resource.uid.length)
            cursor = end
            return resource.uid.substring(start, end)
        }
        val result = Phase64InventoryHolding(DomainRef(field(), field()), field())
        require(cursor == resource.uid.length)
        result
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

/** Fixed integer prices are imported rule parameters. Settlement never reads host time or RNG.
 * The optional basis-point adjustment is bounded, and rounding is towards the payable unit. */
object Phase64PricePolicy {
    const val FIXED_V1 = "P64:PRICE:FIXED_V1"
    const val ADJUSTED_V1 = "P64:PRICE:ADJUSTED_V1"

    fun quote(policyUid: String, unitPriceMinor: Long, units: Long, adjustmentBasisPoints: Long = 0): Long {
        require(unitPriceMinor > 0 && units > 0)
        require(policyUid in setOf(FIXED_V1, ADJUSTED_V1))
        require(adjustmentBasisPoints in -9_999L..100_000L)
        require(policyUid != FIXED_V1 || adjustmentBasisPoints == 0L)
        val numerator = BigInteger.valueOf(unitPriceMinor).multiply(BigInteger.valueOf(units))
            .multiply(BigInteger.valueOf(10_000L + adjustmentBasisPoints))
        return numerator.add(BigInteger.valueOf(9_999)).divide(BigInteger.valueOf(10_000)).toExactLongCompat()
            .also { require(it > 0) }
    }
}

/** Recipe definitions are captured from registered authoritative rule input, not model output.
 * Quantities count explicit unique instances of each definition. */
data class Phase64ProductionRecipe(
    val uid: String,
    val version: Int,
    val inputDefinitionCounts: Map<String, Long>,
    val outputDefinitionCounts: Map<String, Long>,
    val provenanceUid: String
) {
    init {
        require(uid.isNotBlank() && version > 0 && provenanceUid.isNotBlank())
        require(inputDefinitionCounts.isNotEmpty() && outputDefinitionCounts.isNotEmpty())
        require(inputDefinitionCounts.size <= 32 && outputDefinitionCounts.size <= 32)
        require((inputDefinitionCounts + outputDefinitionCounts).all { it.key.isNotBlank() && it.value > 0 })
    }
}

/** Existing items have no materialization. Legal new universal world-object instances require
 * a captured source object whose canonical UID equals the instance being materialized. */
data class Phase64ProductionOutput(
    val instance: ItemInstance,
    val definition: ItemDefinition,
    val sourceObject: DomainRef? = null,
    val materialization: InventoryItemMaterialization? = null
)

data class Phase64ProjectSnapshot(
    val project: DevelopmentProject,
    val type: ProjectTypeDefinition,
    val progress: ProjectProgressSnapshot,
    val requirements: List<ProjectRequirement> = emptyList(),
    val satisfactions: List<ProjectRequirementSatisfaction> = emptyList(),
    val dependencies: List<ProjectDependency> = emptyList(),
    val completedDependencyUids: Set<String> = emptySet()
)

/** A captured existing financial transaction-type definition, not a new payment ledger. */
data class Phase64SettlementType(val uid: String, val flowKind: FinancialFlowKind, val status: String, val provenanceUid: String)

/** Core binds a fixed rule-owned pool to the initiating body's existing resource. A public
 * action supplies references only; it cannot name another worker or invent a work balance. */
internal fun phase64BindProjectLabour(
    definition: BackgroundProcessDefinition,
    actor: DomainRef,
    target: DomainRef,
    boundParameters: Map<String, String>
): Map<String, String> {
    val pool = definition.parameters["labourPoolUid"] ?: return boundParameters
    require(definition.domain == "PROJECT" && definition.operation in setOf(
        Phase64EconomyOperations.BUILD, Phase64EconomyOperations.REPAIR, Phase64EconomyOperations.RESEARCH
    )) { "P64:PROJECT_LABOUR_RULE_REQUIRED" }
    require(pool.isNotBlank() && pool.length <= 160 && !pool.startsWith('@') &&
        boundParameters["labourPoolUid"] == pool) { "P64:FIXED_LABOUR_POOL_REQUIRED" }
    require("labourResourceUid" !in definition.parameters) { "P64:AMBIGUOUS_LABOUR_BINDING" }
    if (definition.parameters["projectUid"] == "@TARGET_UID") {
        require(target.kindUid == "PROJECT" && boundParameters["projectUid"] == target.uid) {
            "P64:PROJECT_ACTIVATION_TARGET_REQUIRED"
        }
    }
    val resource = phase64MechanicalResource(actor, pool).uid
    require(resource.length <= 320 && (boundParameters["labourResourceUid"] == null ||
        boundParameters["labourResourceUid"] == resource)) { "P64:ACTOR_LABOUR_BINDING_REQUIRED" }
    return boundParameters + ("labourResourceUid" to resource)
}

/** A relative movement does not prove a route anchor. A later explicit location can restore
 * it, but an earlier destination must not survive an intervening unanchored movement. */
internal fun phase64DeliveryOrigin(actor: MechanicalActorView, staged: List<PlayerDomainChangePayload>): DomainRef? =
    staged.filterIsInstance<SpatialChange>().filter { it.subject == actor.actor }
        .fold(actor.locationRef) { _, change -> change.destinationLocation }

/** Pure existing-owner helpers for the production read adapter. Null captured state gives an
 * explicit unavailable result; validation performs no recursive reads, mutations or fallback. */
object Phase64EconomyOwnerRules {
    /** Existing FinancialStore remains the writer. Preparing a transfer must reject closed,
     * wrong-currency or external-flow accounts before a candidate can hand over any goods. */
    fun settlement(
        parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope,
        payer: FinancialAccount?,
        payee: FinancialAccount?,
        currency: CurrencyDefinition?,
        type: Phase64SettlementType?
    ): WorldConsequencePlan = checked {
        if (payer == null || payee == null) return@checked backgroundBlocked("P64:SETTLEMENT_ACCOUNT_UNAVAILABLE")
        if (payer.campaignId != scope.temporal.campaignUid || payee.campaignId != scope.temporal.campaignUid ||
            payer.accountUid != parameters["payerAccountUid"] || payee.accountUid != parameters["payeeAccountUid"] || payer.accountUid == payee.accountUid)
            return@checked backgroundBlocked("P64:SETTLEMENT_ACCOUNT_SCOPE_CHANGED")
        FinancialPolicy.validateAccount(payer)
        FinancialPolicy.validateAccount(payee)
        if (payer.closedAt != null || payee.closedAt != null ||
            payer.openedAt > scope.temporal.baseCommitOrder || payee.openedAt > scope.temporal.baseCommitOrder)
            return@checked backgroundBlocked("P64:SETTLEMENT_ACCOUNT_NOT_OPEN")
        if (parameters["payerAccountVersion"]?.toLong()?.let { it != payer.version } == true ||
            parameters["payeeAccountVersion"]?.toLong()?.let { it != payee.version } == true)
            return@checked backgroundBlocked("P64:SETTLEMENT_ACCOUNT_VERSION_CHANGED")
        for ((prefix, account) in listOf("payer" to payer, "payee" to payee)) {
            val kind = parameters["${prefix}HolderKind"]
            val uid = parameters["${prefix}HolderUid"]
            if ((kind == null) != (uid == null) || kind != null &&
                (kind != account.holder.ownerKindUid || uid != account.holder.ownerUid))
                return@checked backgroundBlocked("P64:SETTLEMENT_ACCOUNT_HOLDER_CHANGED")
        }
        if (currency == null || currency.currencyUid != parameters["currencyUid"] || currency.status != "ACTIVE" ||
            payer.currencyUid != currency.currencyUid || payee.currencyUid != currency.currencyUid)
            return@checked backgroundBlocked("P64:SETTLEMENT_CURRENCY_UNAVAILABLE")
        FinancialPolicy.validateCurrency(currency)
        if (type == null || type.uid != parameters["transactionTypeUid"] || type.status != "ACTIVE" ||
            type.flowKind != FinancialFlowKind.INTERNAL || type.provenanceUid.isBlank())
            return@checked backgroundBlocked("P64:SETTLEMENT_INTERNAL_TYPE_REQUIRED")
        val change = FinancialChange(payer.accountUid, payee.accountUid, backgroundPositive(parameters, "amountMinor"),
            currency.currencyUid, type.uid)
        WorldConsequencePlan(changes = listOf(change), sourceUids = listOf(payer.accountUid, payee.accountUid,
            payer.provenance, payee.provenance, currency.currencyUid, currency.provenance, type.uid, type.provenanceUid).distinct())
    }

    /** The route is captured through the protected topology reader before this pure owner
     * check. Only actual resource debits and an exact location transition prove delivery. */
    fun delivery(
        actor: DomainRef,
        parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope,
        canonical: MechanicalActorView?,
        route: WorldTravelPlan?,
        staged: List<PlayerDomainChangePayload>
    ): WorldConsequencePlan = checked {
        if (canonical == null) return@checked backgroundBlocked("P64:DELIVERY_ACTOR_UNAVAILABLE")
        if (canonical.actor != actor || canonical.campaignUid != scope.temporal.campaignUid)
            return@checked backgroundBlocked("P64:DELIVERY_CAPTURE_SCOPE_MISMATCH")
        if (actor.kindUid == "PLAYER" || canonical.kind == MechanicalActorKind.ACTIVE_PLAYER)
            return@checked backgroundBlocked("P64:DELIVERY_ACTIVE_PLAYER_DECISION_REQUIRED")
        if (route == null) return@checked backgroundBlocked("P64:DELIVERY_AUTHORIZED_ROUTE_UNAVAILABLE")
        val destination = DomainRef(backgroundRequired(parameters, "destinationKind"), backgroundRequired(parameters, "destinationUid"))
        if (route.fingerprint != parameters["routeUid"] || route.destination != destination)
            return@checked backgroundBlocked("P64:DELIVERY_ROUTE_CHANGED")
        val origin = phase64DeliveryOrigin(canonical, staged)
            ?: return@checked backgroundBlocked("P64:DELIVERY_ORIGIN_UNAVAILABLE")
        if (!WorldTopologyAnchor.same(origin, route.origin)) return@checked backgroundBlocked("P64:DELIVERY_ORIGIN_CHANGED")
        val at = WorldTimeTick(backgroundRequired(parameters, "logicalEventTimeMillis").toLong())
        val started = WorldTimeTick(backgroundRequired(parameters, "processStartedAtMillis").toLong())
        if (at < started + route.duration) return@checked backgroundBlocked("P64:DELIVERY_ROUTE_DURATION_UNSATISFIED")
        if (route.edges.any { it.validFrom > started || (it.validThrough != null && at >= it.validThrough) })
            return@checked backgroundBlocked("P64:DELIVERY_ROUTE_CLOSED")
        val incapacitating = setOf("DEAD", "UNCONSCIOUS", "INCAPACITATED")
        val health = canonical.resources.singleOrNull { it.resourceUid == "HEALTH" }?.let { resource ->
            staged.filterIsInstance<ResourceChange>().filter { it.subject == actor && it.resourceUid == resource.resourceUid }
                .fold(resource.current) { total, change -> Math.addExact(total, change.delta.units) }
        }
        if (canonical.materialization != MechanicalStateMaterialization.FULL ||
            canonical.conditions.any { it.intensity > 0 && it.conditionUid in incapacitating } || health?.let { it <= 0 } == true ||
            staged.filterIsInstance<ConditionChange>().any { it.subject == actor && it.operation == ConditionOperation.ADD && it.conditionUid in incapacitating })
            return@checked backgroundBlocked("P64:DELIVERY_ACTOR_INCAPACITATED")
        if (route.edges.any { !canonical.executableAbilityUids.containsAll(it.requiredCapabilities) })
            return@checked backgroundBlocked("P64:DELIVERY_CAPABILITY_UNAVAILABLE")
        val costs = route.resourceCosts.toSortedMap().map { (resource, cost) ->
            val current = canonical.resources.singleOrNull { it.resourceUid == resource }?.current
                ?: return@checked backgroundBlocked("P64:DELIVERY_RESOURCE_UNAVAILABLE")
            val available = staged.filterIsInstance<ResourceChange>().filter { it.subject == actor && it.resourceUid == resource }
                .fold(current) { total, change -> Math.addExact(total, change.delta.units) }
            if (available < cost) return@checked backgroundBlocked("P64:DELIVERY_RESOURCE_REQUIREMENT_UNSATISFIED")
            ResourceChange(actor, resource, ExactLongDelta.of(Math.negateExact(cost)))
        }
        WorldConsequencePlan(changes = costs + SpatialChange(actor, 0, destinationLocation = route.destination),
            claims = route.resourceCosts.toSortedMap().map { (resource, cost) ->
                WorldResourceClaim(phase64MechanicalResource(actor, resource), cost)
            }, sourceUids = (route.edges.map { it.provenanceUid } + route.fingerprint + canonical.generationProvenanceUid).distinct())
    }

    fun production(
        actor: DomainRef,
        parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope,
        recipe: Phase64ProductionRecipe?,
        inputInstances: List<ItemInstance>,
        outputs: List<Phase64ProductionOutput>
    ): WorldConsequencePlan = checked {
        if (recipe == null) return@checked backgroundBlocked("P64:PRODUCTION_RECIPE_UNAVAILABLE")
        if (parameters["recipeUid"] != recipe.uid || parameters["recipeVersion"]?.toIntOrNull() != recipe.version)
            return@checked backgroundBlocked("P64:PRODUCTION_RECIPE_CHANGED")
        val inputUids = uidList(parameters, "inputItemUids")
        val outputUids = uidList(parameters, "outputItemUids")
        if (inputInstances.map { it.itemInstanceUid }.toSet() != inputUids.toSet() || inputInstances.size != inputUids.size ||
            outputs.map { it.instance.itemInstanceUid }.toSet() != outputUids.toSet() || outputs.size != outputUids.size)
            return@checked backgroundBlocked("P64:PRODUCTION_ITEMS_UNAVAILABLE")
        if (inputInstances.any { it.campaignId != scope.temporal.campaignUid } ||
            outputs.any { it.instance.campaignId != scope.temporal.campaignUid })
            return@checked backgroundBlocked("P64:PRODUCTION_CAMPAIGN_MISMATCH")
        fun counts(instances: List<ItemInstance>) = instances.groupingBy { it.itemDefinitionUid }.eachCount().mapValues { it.value.toLong() }
        if (counts(inputInstances) != recipe.inputDefinitionCounts || counts(outputs.map { it.instance }) != recipe.outputDefinitionCounts)
            return@checked backgroundBlocked("P64:PRODUCTION_RECIPE_REQUIREMENTS_CHANGED")
        val outputOwner = if (parameters["outputOwnerKind"] == null && parameters["outputOwnerUid"] == null) actor else
            DomainRef(backgroundRequired(parameters, "outputOwnerKind"), backgroundRequired(parameters, "outputOwnerUid"))
        for (output in outputs) {
            if (output.instance.itemDefinitionUid != output.definition.itemDefinitionUid ||
                output.definition.definitionStatus != ItemDefinitionStatus.ACTIVE ||
                output.definition.storagePolicy != ItemStoragePolicy.UNIQUE_INSTANCE)
                return@checked backgroundBlocked("P64:PRODUCTION_OUTPUT_DEFINITION_UNAVAILABLE")
            if (output.materialization != null && (output.materialization != universalInventoryItemMaterialization() ||
                    output.instance.itemDefinitionUid != UNIVERSAL_WORLD_OBJECT_ITEM_DEFINITION_UID ||
                    output.sourceObject != DomainRef("OBJECT", output.instance.itemInstanceUid)))
                return@checked backgroundBlocked("P64:PRODUCTION_GENESIS_UNPROVEN")
            if (output.materialization == null && output.sourceObject != null)
                return@checked backgroundBlocked("P64:PRODUCTION_GENESIS_UNPROVEN")
        }
        WorldConsequencePlan(changes = outputs.map {
            InventoryChange(outputOwner, it.instance.itemInstanceUid, ExactLongDelta.of(1), it.materialization)
        }, sourceUids = (listOf(recipe.uid, recipe.provenanceUid) + inputUids + outputUids).distinct())
    }

    fun projectWork(
        operation: String,
        parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope,
        snapshot: Phase64ProjectSnapshot?,
        staged: List<PlayerDomainChangePayload>
    ): WorldConsequencePlan = checked {
        if (snapshot == null) return@checked backgroundBlocked("P64:PROJECT_STATE_UNAVAILABLE")
        projectRequirements(parameters, scope, snapshot)?.let { return@checked it }
        if (staged.filterIsInstance<BackgroundProjectWorkChange>().any { it.projectUid == snapshot.project.projectUid && it.readyToComplete })
            return@checked backgroundBlocked("P64:PROJECT_READY_COMMIT_REQUIRED")
        val expectedTypes = when (operation) {
            Phase64EconomyOperations.OWNED_BUILD_CHECK -> setOf(PROJECT_TYPE_INFRASTRUCTURE, PROJECT_TYPE_CRAFTING)
            Phase64EconomyOperations.OWNED_REPAIR_CHECK -> setOf(PROJECT_TYPE_INFRASTRUCTURE, PROJECT_TYPE_CRAFTING, PROJECT_TYPE_ADAPTATION)
            Phase64EconomyOperations.OWNED_RESEARCH_CHECK -> setOf(PROJECT_TYPE_RESEARCH)
            else -> return@checked backgroundBlocked("P64:PROJECT_WORK_RULE_UNAVAILABLE")
        }
        if (snapshot.project.projectTypeUid !in expectedTypes)
            return@checked backgroundBlocked("P64:PROJECT_TYPE_REQUIREMENT_UNSATISFIED")
        if (snapshot.progress.status !in setOf(ProjectStatus.PROTOTYPE, ProjectStatus.ACTIVE_WORK, ProjectStatus.STABILIZATION))
            return@checked backgroundBlocked("P64:PROJECT_LIFECYCLE_CHANGED")
        val requested = backgroundPositive(parameters, "progressUnits")
        val accumulated = staged.fold(snapshot.progress.progressUnits) { total, change ->
            val delta = when (change) {
                is DevelopmentProjectChange -> if (change.projectUid == snapshot.project.projectUid) change.progressDelta.units else 0
                is BackgroundProjectWorkChange -> if (change.projectUid == snapshot.project.projectUid) change.progressUnits else 0
                else -> 0
            }
            Math.addExact(total, delta)
        }
        val proposed = Math.addExact(accumulated, requested)
        val cap = snapshot.progress.progressCapUnits ?: return@checked backgroundBlocked("P64:PROJECT_PROGRESS_RULE_UNAVAILABLE")
        if (proposed > cap) return@checked backgroundBlocked("P64:PROJECT_PROGRESS_LIMIT_REACHED")
        val worker = DomainRef(backgroundRequired(parameters, "workerKind"), backgroundRequired(parameters, "workerUid"))
        val evidence = listOf(worker) + (parameters["inputItemUids"]?.let { uidList(parameters, "inputItemUids") } ?: emptyList())
            .map { DomainRef("ITEM_INSTANCE", it) }
        val change = BackgroundProjectWorkChange(scope.temporal.campaignUid, snapshot.project.projectUid, worker, operation,
            snapshot.project.projectVersion, snapshot.type.definitionVersion, accumulated, requested,
            backgroundRequired(parameters, "labourResourceUid"), backgroundPositive(parameters, "labourUnits"),
            backgroundRequired(parameters, "ruleUid"), backgroundRequired(parameters, "ruleVersion").toInt(), evidence.distinct(),
            readyToComplete = proposed == cap && snapshot.progress.achievedRequiredMilestones >= snapshot.progress.requiredMilestones)
        WorldConsequencePlan(changes = listOf(change), sourceUids = projectSources(snapshot))
    }

    /** Readiness proof only: the caller must append the existing owner's separately validated
     * typed lifecycle/outcome change. This result alone deliberately cannot finish a process. */
    fun projectCompletionReadiness(
        parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope,
        snapshot: Phase64ProjectSnapshot?,
        resultEvidence: ProjectWorkRecord?
    ): WorldConsequencePlan = checked {
        if (snapshot == null) return@checked backgroundBlocked("P64:PROJECT_STATE_UNAVAILABLE")
        projectRequirements(parameters, scope, snapshot)?.let { return@checked it }
        val progress = snapshot.progress
        if (progress.status != ProjectStatus.READY_TO_COMPLETE || progress.progressCapUnits == null ||
            progress.progressUnits < progress.progressCapUnits || progress.achievedRequiredMilestones < progress.requiredMilestones)
            return@checked backgroundBlocked("P64:PROJECT_NOT_READY_TO_COMPLETE")
        if (resultEvidence == null || resultEvidence.workRecordUid != parameters["resultEvidenceUid"] ||
            resultEvidence.projectUid != snapshot.project.projectUid || resultEvidence.campaignId != scope.temporal.campaignUid ||
            resultEvidence.result !in setOf(ProjectWorkResult.SUCCESS, ProjectWorkResult.BREAKTHROUGH) ||
            resultEvidence.effectiveOrder > scope.temporal.baseCommitOrder)
            return@checked backgroundBlocked("P64:PROJECT_RESULT_EVIDENCE_UNAVAILABLE")
        WorldConsequencePlan(sourceUids = (projectSources(snapshot) + resultEvidence.workRecordUid).distinct())
    }

    private fun projectRequirements(parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        state: Phase64ProjectSnapshot): WorldConsequencePlan? {
        val project = state.project
        if (project.campaignId != scope.temporal.campaignUid || project.projectUid != parameters["projectUid"] ||
            state.progress.projectUid != project.projectUid || state.type.projectTypeUid != project.projectTypeUid ||
            state.type.definitionStatus != "ACTIVE" || state.progress.progressCapUnits != project.progressCapUnits)
            return backgroundBlocked("P64:PROJECT_IDENTITY_OR_RULE_CHANGED")
        if (parameters["projectVersion"]?.let { it.toInt() != project.projectVersion } == true)
            return backgroundBlocked("P64:PROJECT_VERSION_CHANGED")
        val activeRequirements = state.requirements.filter { it.projectUid == project.projectUid &&
            it.campaignId == scope.temporal.campaignUid && it.required && it.requiredFromOrder <= scope.temporal.baseCommitOrder }
        if (activeRequirements.any { requirement -> state.satisfactions.none {
                it.campaignId == scope.temporal.campaignUid && it.projectUid == project.projectUid &&
                    it.requirementUid == requirement.requirementUid && it.satisfiedOrder >= requirement.requiredFromOrder &&
                    it.satisfiedOrder <= scope.temporal.baseCommitOrder
            } }) return backgroundBlocked("P64:PROJECT_REQUIREMENTS_UNSATISFIED")
        if (state.dependencies.any { it.campaignId == scope.temporal.campaignUid && it.projectUid == project.projectUid &&
                it.validFromOrder <= scope.temporal.baseCommitOrder && it.dependsOnProjectUid !in state.completedDependencyUids })
            return backgroundBlocked("P64:PROJECT_DEPENDENCY_UNSATISFIED")
        return null
    }

    private fun projectSources(state: Phase64ProjectSnapshot) = (listOf(state.project.projectUid,
        state.project.provenance, state.type.projectTypeUid, state.type.provenance) + state.satisfactions.map { it.satisfactionUid } +
        state.dependencies.map { it.dependencyUid }).distinct()

    private fun uidList(parameters: Map<String, String>, key: String) = backgroundRequired(parameters, key).split('|').also {
        require(it.size in 1..32 && it.all(String::isNotBlank) && it.distinct().size == it.size)
    }

    private fun checked(block: () -> WorldConsequencePlan): WorldConsequencePlan = try { block() }
    catch (_: IllegalArgumentException) { backgroundBlocked("P64:OWNER_PARAMETERS_INVALID") }
    catch (_: ArithmeticException) { backgroundBlocked("P64:OWNER_AMOUNT_OVERFLOW") }
}

/**
 * Pure Phase64 economy/project rules. All quantities describe explicit unique instances because
 * the existing InventoryChange applier supports +1/-1 instances, not anonymous stack balances.
 *
 * Common parameters: sourceUid (optional recorded causal source), inputOwnerKind/inputOwnerUid
 * (both present or actor), inputItemUids (up to 32 distinct nonblank UIDs separated by '|'),
 * labourResourceUid/labourUnits (optional pair, authoritative LABOUR_CAPACITY allocation), and
 * payerAccountUid/payeeAccountUid/amountMinor/currencyUid/transactionTypeUid (optional full set
 * of internal settlement costs). Labour allocates capacity for this event, not a new work ledger.
 *
 * CONSUME requires inputItemUids. PRODUCE additionally requires recipeUid/recipeVersion,
 * outputOwnerKind/outputOwnerUid
 * (or actor) and outputItemUids; OWNED_PRODUCTION must prove every exact typed output and its
 * legal genesis, including the production source. Materials are consumed only with that proof.
 * TRADE requires inputItemUids (seller custody), buyerKind/buyerUid, payerAccountUid,
 * payeeAccountUid, currencyUid, transactionTypeUid. pricePolicyUid, unitPriceMinor and optional
 * priceAdjustmentBasisPoints come from the DEFINITION, never instance overrides.
 * PAYMENT requires the full settlement set. DELIVER requires inputItemUids (carrier custody),
 * recipientKind/recipientUid, destinationKind/destinationUid and routeUid captured at dispatch.
 * The route is rechecked at the logical deadline. OWNED_DELIVERY prepares movement using the
 * actual Phase63/50 owner; changed/unavailable routes block without transferring cargo.
 *
 * BUILD/REPAIR/RESEARCH require projectUid, progressUnits and labourResourceUid/labourUnits.
 * Public initiation derives labourResourceUid from the fixed definition labourPoolUid and
 * the initiating actor's existing Phase50 mechanical pool; no anonymous WORK resource exists.
 * Their owned CHECK validates the existing project's type, lifecycle, registered requirements,
 * dependencies and completion limit against staged changes, returning projectUid in sourceUids.
 * Checks prepare an explicit-worker BackgroundProjectWorkChange for the existing project ledger.
 * COMPLETE_PROJECT requires projectUid and resultEvidenceUid. OWNED_COMPLETION must supply the
 * real owner's typed completion/outcome effects and that evidence; knowledge/skill progression
 * remains separately admitted. No read here creates a project, skill or unknown item.
 *
 * prepareOwnedEffect receives resolved parameters plus processUid, ruleUid, ruleVersion and
 * logicalEventTimeMillis (the original due time, independent of batching/retry/reopen).
 * Unsupported owners must return typed BLOCKED; there is no fabricated scalar/text fallback.
 */
class Phase64EconomyProjectsAdapter : BackgroundDomainAdapter {
    override val domains = setOf("ECONOMY", "PROJECT")

    override fun evaluate(
        definition: BackgroundProcessDefinition,
        process: BackgroundProcessInstance,
        scope: BackgroundProcessEvaluationScope,
        at: WorldTimeTick,
        reads: BackgroundWorldReadPort,
        staged: List<PlayerDomainChangePayload>
    ): WorldConsequencePlan {
        if (definition.uid != process.definitionUid || definition.version != process.definitionVersion)
            return backgroundBlocked("P64:RULE_VERSION_CHANGED")
        if (definition.domain !in domains ||
            (definition.domain == "ECONOMY" && definition.operation !in Phase64EconomyOperations.economy) ||
            (definition.domain == "PROJECT" && definition.operation !in Phase64EconomyOperations.projects))
            return backgroundBlocked("P64:ECONOMY_OPERATION_UNAVAILABLE")
        if (!Phase64EconomyRuleCatalog.acceptsExecutor(definition, process.actor) ||
            definition.parameters["economyCampaignUid"]?.let { it != scope.temporal.campaignUid } == true)
            return backgroundBlocked("P64:REGISTERED_ECONOMY_EXECUTOR_REQUIRED")
        if (process.status !in setOf(BackgroundProcessStatus.ACTIVE, BackgroundProcessStatus.BLOCKED))
            return WorldConsequencePlan(status = process.status, reasonUid = process.reasonUid)
        if (at < process.due) return WorldConsequencePlan(status = BackgroundProcessStatus.ACTIVE)
        if (!reads.exists(process.actor)) return backgroundBlocked("P64:ACTOR_UNAVAILABLE")
        return try {
            evaluateDue(definition, process, scope, reads, staged)
        } catch (_: IllegalArgumentException) {
            backgroundBlocked("P64:ECONOMY_INVALID_PARAMETERS")
        } catch (_: ArithmeticException) {
            backgroundBlocked("P64:ECONOMY_AMOUNT_OVERFLOW")
        }
    }

    private fun evaluateDue(
        definition: BackgroundProcessDefinition,
        process: BackgroundProcessInstance,
        scope: BackgroundProcessEvaluationScope,
        reads: BackgroundWorldReadPort,
        staged: List<PlayerDomainChangePayload>
    ): WorldConsequencePlan {
        val values = backgroundParameters(definition, process)
        val inputOwner = owner(values, "inputOwner", process.actor)
        val inputItems = items(values, "inputItemUids")
        val claims = mutableListOf<WorldResourceClaim>()
        val changes = mutableListOf<PlayerDomainChangePayload>()
        val refs = mutableListOf(process.actor)
        val sources = mutableListOf(definition.uid, process.uid, process.actor.uid)
        values["sourceUid"]?.let { require(it.isNotBlank()); sources += it }
        fun requireRefs(candidates: List<DomainRef>): WorldConsequencePlan? {
            val missing = candidates.firstOrNull { !reads.exists(it) }
            if (missing != null) return backgroundBlocked("P64:REQUIRED_REFERENCE_UNAVAILABLE")
            refs += candidates
            sources += candidates.map { it.uid }
            return null
        }
        fun reserve(resource: DomainRef, quantity: Long, availabilityResource: DomainRef = resource): WorldConsequencePlan? {
            val available = reads.available(availabilityResource, staged) ?: return backgroundBlocked("P64:RESOURCE_STATE_UNAVAILABLE")
            if (available < quantity) return backgroundBlocked("P64:RESOURCE_REQUIREMENT_UNSATISFIED")
            claims += WorldResourceClaim(resource, quantity)
            return null
        }
        fun prepare(operation: String, extra: Map<String, String> = emptyMap()): WorldConsequencePlan =
            reads.prepareOwnedEffect(operation, process.actor, values + extra + mapOf(
                "processUid" to process.uid, "ruleUid" to definition.uid, "ruleVersion" to definition.version.toString(),
                "logicalEventTimeMillis" to process.due.milliseconds.toString(), "processStartedAtMillis" to process.startedAt.milliseconds.toString()
            ), scope, staged)
        fun blockedOwned(plan: WorldConsequencePlan): WorldConsequencePlan? =
            if (plan.status == BackgroundProcessStatus.COMPLETED) null else
                WorldConsequencePlan(status = plan.status, reasonUid = plan.reasonUid ?: "P64:OWNER_EFFECT_UNAVAILABLE",
                    sourceUids = plan.sourceUids)

        if (definition.operation != Phase64EconomyOperations.PAYMENT && definition.operation != Phase64EconomyOperations.COMPLETE_PROJECT) {
            requireRefs(listOf(inputOwner) + inputItems.map { DomainRef("ITEM_INSTANCE", it) })?.let { return it }
            inputItems.forEach { item ->
                reserve(DomainRef("ITEM_INSTANCE", item), 1, phase64InventoryResource(inputOwner, item))?.let { return it }
            }
        }
        val labour = values["labourResourceUid"]
        require((labour == null) == (values["labourUnits"] == null))
        if (labour != null) {
            val labourRef = DomainRef("LABOUR_CAPACITY", labour)
            requireRefs(listOf(labourRef))?.let { return it }
            reserve(labourRef, backgroundPositive(values, "labourUnits"))?.let { return it }
        }

        var owned = WorldConsequencePlan()
        when (definition.operation) {
            Phase64EconomyOperations.CONSUME -> {
                require(inputItems.isNotEmpty())
                changes += inputItems.map { InventoryChange(inputOwner, it, ExactLongDelta.of(-1)) }
            }
            Phase64EconomyOperations.PRODUCE -> {
                require(inputItems.isNotEmpty()) // every output has an explicit registered material source
                val outputOwner = owner(values, "outputOwner", process.actor)
                val outputItems = items(values, "outputItemUids")
                require(outputItems.isNotEmpty() && outputItems.intersect(inputItems.toSet()).isEmpty())
                requireRefs(listOf(outputOwner))?.let { return it }
                refs += outputItems.map { DomainRef("OBJECT", it) }
                if (!reads.authorize(process.actor, definition.operation, refs.distinct(), staged)) return backgroundBlocked("P64:ECONOMY_ACCESS_DENIED")
                owned = prepare(Phase64EconomyOperations.OWNED_PRODUCTION)
                blockedOwned(owned)?.let { return it }
                val outputs = owned.changes.filterIsInstance<InventoryChange>()
                if (outputs.size != outputItems.size || owned.changes.size != outputs.size ||
                    outputs.any { it.subject != outputOwner || it.quantityDelta.units != 1L } ||
                    outputs.map { it.itemInstanceUid }.toSet() != outputItems.toSet() || owned.sourceUids.isEmpty())
                    return backgroundBlocked("P64:PRODUCTION_OUTPUT_UNPROVEN")
                sources += outputItems
                changes += inputItems.map { InventoryChange(inputOwner, it, ExactLongDelta.of(-1)) }
            }
            Phase64EconomyOperations.TRADE -> {
                require(inputItems.isNotEmpty())
                val buyer = owner(values, "buyer", null)
                require(buyer != inputOwner)
                requireRefs(listOf(buyer))?.let { return it }
                changes += inputItems.flatMap { listOf(
                    InventoryChange(inputOwner, it, ExactLongDelta.of(-1)),
                    InventoryChange(buyer, it, ExactLongDelta.of(1))
                ) }
            }
            Phase64EconomyOperations.DELIVER -> {
                require(inputItems.isNotEmpty() && inputOwner == process.actor)
                val recipient = owner(values, "recipient", null)
                val destination = owner(values, "destination", null)
                require(destination.kindUid in setOf("PLACE", "LOCATION") && recipient != inputOwner)
                requireRefs(listOf(recipient, destination))?.let { return it }
                val routeUid = backgroundRequired(values, "routeUid")
                val currentRoute = reads.route(process.actor, destination, process.due)
                    ?: return backgroundBlocked("P64:DELIVERY_ROUTE_UNAVAILABLE")
                if (routeUid != currentRoute) return backgroundBlocked("P64:DELIVERY_ROUTE_CHANGED")
                if (!reads.authorize(process.actor, definition.operation, refs.distinct(), staged)) return backgroundBlocked("P64:ECONOMY_ACCESS_DENIED")
                owned = prepare(Phase64EconomyOperations.OWNED_DELIVERY)
                blockedOwned(owned)?.let { return it }
                val movementProven = owned.effects.any {
                    it.target == process.actor && it.effectKindUid.substringAfterLast(':').uppercase() == "LOCATION_TRANSITION" &&
                        it.canonicalPayload["destination_kind_uid"] == destination.kindUid &&
                        it.canonicalPayload["destination_uid"] == destination.uid
                } || owned.changes.any { it is SpatialChange && it.subject == process.actor && it.destinationLocation == destination }
                if (!movementProven ||
                    owned.changes.any { it is InventoryChange || it is FinancialChange })
                    return backgroundBlocked("P64:DELIVERY_MOVEMENT_UNPROVEN")
                sources += routeUid
                changes += inputItems.flatMap { listOf(
                    InventoryChange(inputOwner, it, ExactLongDelta.of(-1)),
                    InventoryChange(recipient, it, ExactLongDelta.of(1))
                ) }
            }
            Phase64EconomyOperations.BUILD, Phase64EconomyOperations.REPAIR, Phase64EconomyOperations.RESEARCH -> {
                val project = DomainRef("PROJECT", backgroundRequired(values, "projectUid"))
                requireRefs(listOf(project))?.let { return it }
                require(labour != null)
                val progress = backgroundPositive(values, "progressUnits")
                val operation = when (definition.operation) {
                    Phase64EconomyOperations.BUILD -> Phase64EconomyOperations.OWNED_BUILD_CHECK
                    Phase64EconomyOperations.REPAIR -> Phase64EconomyOperations.OWNED_REPAIR_CHECK
                    else -> Phase64EconomyOperations.OWNED_RESEARCH_CHECK
                }
                if (!reads.authorize(process.actor, definition.operation, refs.distinct(), staged)) return backgroundBlocked("P64:ECONOMY_ACCESS_DENIED")
                owned = prepare(operation, mapOf("workerKind" to process.actor.kindUid, "workerUid" to process.actor.uid))
                blockedOwned(owned)?.let { return it }
                val work = owned.changes.singleOrNull() as? BackgroundProjectWorkChange
                if (work == null || work.projectUid != project.uid || work.worker != process.actor || work.progressUnits != progress ||
                    owned.effects.isNotEmpty() || project.uid !in owned.sourceUids)
                    return backgroundBlocked("P64:PROJECT_REQUIREMENTS_UNPROVEN")
                changes += inputItems.map { InventoryChange(inputOwner, it, ExactLongDelta.of(-1)) }
            }
            Phase64EconomyOperations.COMPLETE_PROJECT -> {
                val project = DomainRef("PROJECT", backgroundRequired(values, "projectUid"))
                val evidence = DomainRef("PROJECT_RESULT_EVIDENCE", backgroundRequired(values, "resultEvidenceUid"))
                requireRefs(listOf(project, evidence))?.let { return it }
                if (!reads.authorize(process.actor, definition.operation, refs.distinct(), staged)) return backgroundBlocked("P64:ECONOMY_ACCESS_DENIED")
                owned = prepare(Phase64EconomyOperations.OWNED_COMPLETION)
                blockedOwned(owned)?.let { return it }
                if ((owned.changes.isEmpty() && owned.effects.isEmpty()) || evidence.uid !in owned.sourceUids ||
                    owned.changes.any { it is SkillChange || it is TechniqueChange })
                    return backgroundBlocked("P64:PROJECT_OUTCOME_UNPROVEN")
            }
            Phase64EconomyOperations.PAYMENT -> Unit
            else -> return backgroundBlocked("P64:ECONOMY_OPERATION_UNAVAILABLE")
        }

        val paymentKeys = listOf("payerAccountUid", "payeeAccountUid", "amountMinor", "currencyUid", "transactionTypeUid")
        val paymentRequired = definition.operation in setOf(Phase64EconomyOperations.PAYMENT, Phase64EconomyOperations.TRADE)
        if (paymentRequired || paymentKeys.any { it in values }) {
            val payer = backgroundRequired(values, "payerAccountUid")
            val payee = backgroundRequired(values, "payeeAccountUid")
            val currency = backgroundRequired(values, "currencyUid")
            val transactionType = backgroundRequired(values, "transactionTypeUid")
            require(payer != payee)
            val amount = if (definition.operation == Phase64EconomyOperations.TRADE) {
                // Prices are rule-owned. A process participant cannot override an imported quote.
                require(definition.parameters["pricePolicyVersion"]?.let { it == "1" } != false)
                Phase64PricePolicy.quote(backgroundRequired(definition.parameters, "pricePolicyUid"),
                    backgroundPositive(definition.parameters, "unitPriceMinor"), inputItems.size.toLong(),
                    definition.parameters["priceAdjustmentBasisPoints"]?.toLong() ?: 0)
            } else backgroundPositive(values, "amountMinor")
            requireRefs(listOf(DomainRef("FINANCIAL_ACCOUNT", payer), DomainRef("FINANCIAL_ACCOUNT", payee), DomainRef("CURRENCY", currency)))?.let { return it }
            reserve(DomainRef("FINANCIAL_ACCOUNT", payer), amount)?.let { return it }
            if (!reads.authorize(process.actor, definition.operation, refs.distinct(), staged)) return backgroundBlocked("P64:ECONOMY_ACCESS_DENIED")
            val settlement = prepare(Phase64EconomyOperations.OWNED_SETTLEMENT, mapOf("amountMinor" to amount.toString()))
            blockedOwned(settlement)?.let { return it }
            val expected = FinancialChange(payer, payee, amount, currency, transactionType)
            if (settlement.changes != listOf(expected) || settlement.effects.isNotEmpty() || settlement.claims.isNotEmpty() ||
                settlement.deadlineAdds.isNotEmpty() || settlement.deadlineRemovals.isNotEmpty() || settlement.ownerDelegations.isNotEmpty() ||
                !settlement.sourceUids.containsAll(listOf(payer, payee, currency, transactionType)))
                return backgroundBlocked("P64:SETTLEMENT_EFFECT_UNPROVEN")
            changes += settlement.changes
            sources += settlement.sourceUids
        }
        if (!reads.authorize(process.actor, definition.operation, refs.distinct(), staged)) return backgroundBlocked("P64:ECONOMY_ACCESS_DENIED")
        // A coded capacity is an actual owner resource, not merely an effort counter.
        // Reserving it during evaluation cannot replace its atomic debit at completion.
        val labourDebit=labour?.let { phase64LabourDebit(process.actor,it,backgroundPositive(values,"labourUnits")) }
        return WorldConsequencePlan(changes = changes + owned.changes + listOfNotNull(labourDebit), effects = owned.effects,
            claims = mergeClaims(claims + owned.claims), sourceUids = (sources + owned.sourceUids).distinct(),
            progressUnits = when (definition.operation) {
                Phase64EconomyOperations.BUILD, Phase64EconomyOperations.REPAIR, Phase64EconomyOperations.RESEARCH -> backgroundPositive(values, "progressUnits")
                else -> 1
            })
    }

    private fun owner(values: Map<String, String>, prefix: String, default: DomainRef?): DomainRef {
        val kind = values["${prefix}Kind"]
        val uid = values["${prefix}Uid"]
        if (kind == null && uid == null) return requireNotNull(default)
        require(!kind.isNullOrBlank() && !uid.isNullOrBlank())
        return DomainRef(kind, uid)
    }

    private fun items(values: Map<String, String>, key: String): List<String> {
        val raw = values[key] ?: return emptyList()
        return raw.split('|').also { items ->
            require(items.size in 1..32 && items.distinct().size == items.size && items.all { it.isNotBlank() && it.length <= 160 })
        }
    }

    private fun mergeClaims(claims: List<WorldResourceClaim>): List<WorldResourceClaim> = claims
        .groupBy { it.resource }.map { (resource, entries) ->
            WorldResourceClaim(resource, entries.fold(0L) { total, entry -> Math.addExact(total, entry.quantity) })
        }
}

/** Legacy non-coded capacities have no invented pool semantics. Production availability
 * already rejects them; registered coded pools must belong to this actual worker. */
internal fun phase64LabourDebit(worker:DomainRef,resourceUid:String,units:Long):ResourceChange? {
    require(units>0){"P64:LABOUR_UNITS_INVALID"}
    val holding=phase64MechanicalResourceHolding(DomainRef("MECHANICAL_RESOURCE",resourceUid))?:return null
    require(holding.holder==worker){"P64:LABOUR_WORKER_SCOPE"}
    return ResourceChange(worker,holding.resourceUid,ExactLongDelta.of(-units))
}
