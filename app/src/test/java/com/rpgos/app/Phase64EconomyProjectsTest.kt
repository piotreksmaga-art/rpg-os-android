package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class Phase64EconomyProjectsTest {
    @Test fun resourceUnitsAreExactBoundedAndCompatibleWithOlderAndroidLibraries() {
        assertEquals(2L,phase64ExactResourceUnits(2.0))
        assertEquals(0L,phase64ExactResourceUnits(0.0))
        listOf(1.5,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,
            Long.MAX_VALUE.toDouble(),Long.MIN_VALUE.toDouble()).forEach { assertNull(phase64ExactResourceUnits(it)) }
        assertEquals(2L,Phase64PricePolicy.quote(Phase64PricePolicy.ADJUSTED_V1,1,1,1))
    }
    private val adapter = Phase64EconomyProjectsAdapter()
    private val actor = DomainRef("NPC", "seller")
    private val buyer = DomainRef("NPC", "buyer")
    private val destination = DomainRef("PLACE", "town")
    private val scope = BackgroundProcessEvaluationScope(TemporalScope("C", "H", 1, "STATE"), "SEED", "RULES")

    private class Reads : BackgroundWorldReadPort {
        val holdings = mutableMapOf<Phase64InventoryHolding, Long>()
        val balances = mutableMapOf<String, Long>()
        val capacity = mutableMapOf<String, Long>()
        var allowed = true
        var routeUid: String? = "route"
        val missing = mutableSetOf<DomainRef>()
        var ownerPlan = backgroundBlocked("P64:OWNER_UNAVAILABLE")
        var settlementPlan: WorldConsequencePlan? = null
        val ownerCalls = mutableListOf<Pair<String, Map<String, String>>>()
        override fun exists(ref: DomainRef) = ref !in missing
        override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>) = allowed
        override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>, staged: List<PlayerDomainChangePayload>) =
            allowed && staged.filterIsInstance<AccessAuthorityChange>().none {
                it.operation == AccessOperation.REVOKE_GRANT && it.principalKindUid == actor.kindUid &&
                    it.principalUid == actor.uid && it.valueUid == purpose
            }
        override fun route(actor: DomainRef, destination: DomainRef, at: WorldTimeTick) = routeUid
        override fun available(resource: DomainRef, staged: List<PlayerDomainChangePayload>): Long? {
            phase64InventoryHolding(resource)?.let { holding ->
                return (holdings[holding] ?: 0) + staged.filterIsInstance<InventoryChange>()
                    .filter { it.subject == holding.holder && it.itemInstanceUid == holding.itemInstanceUid }
                    .sumOf { it.quantityDelta.units }
            }
            return when (resource.kindUid) {
                "FINANCIAL_ACCOUNT" -> balances[resource.uid]?.let { initial ->
                    staged.filterIsInstance<FinancialChange>().fold(initial) { amount, change ->
                        amount - (if (change.fromAccountUid == resource.uid) change.amountMinor else 0) +
                            (if (change.toAccountUid == resource.uid) change.amountMinor else 0)
                    }
                }
                "LABOUR_CAPACITY" -> capacity[resource.uid]
                else -> null
            }
        }
        override fun prepareOwnedEffect(operation: String, actor: DomainRef, parameters: Map<String, String>,
            scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>): WorldConsequencePlan {
            ownerCalls += operation to parameters
            if (operation == Phase64EconomyOperations.OWNED_SETTLEMENT) return settlementPlan ?: Phase64EconomyOwnerRules.settlement(
                parameters, scope,
                FinancialAccount(scope.temporal.campaignUid, backgroundRequired(parameters, "payerAccountUid"),
                    OwnershipOwnerRef("NPC", "buyer"), FINANCIAL_ACCOUNT_TYPE_DEFAULT, backgroundRequired(parameters, "currencyUid"), 0, "WORLD:PAYER"),
                FinancialAccount(scope.temporal.campaignUid, backgroundRequired(parameters, "payeeAccountUid"),
                    OwnershipOwnerRef("NPC", "seller"), FINANCIAL_ACCOUNT_TYPE_DEFAULT, backgroundRequired(parameters, "currencyUid"), 0, "WORLD:PAYEE"),
                CurrencyDefinition(backgroundRequired(parameters, "currencyUid"), "coin", "Coin", 1, "WORLD:CURRENCY"),
                Phase64SettlementType(backgroundRequired(parameters, "transactionTypeUid"), FinancialFlowKind.INTERNAL, "ACTIVE", "WORLD:TRANSFER"))
            return ownerPlan
        }
    }

    private fun definition(operation: String, values: Map<String, String> = emptyMap()) = BackgroundProcessDefinition(
        "RULE", 1, if (operation in Phase64EconomyOperations.projects) "PROJECT" else "ECONOMY", operation, 1000,
        parameters = values
    )
    private fun process(values: Map<String, String> = emptyMap()) = BackgroundProcessInstance(
        "PROCESS", "RULE", 1, actor, 1, WorldTimeTick(0), WorldTimeTick(1000), parameters = values
    )
    private fun evaluate(operation: String, values: Map<String, String>, reads: Reads,
        staged: List<PlayerDomainChangePayload> = emptyList(), rule: Map<String, String> = emptyMap(), at: Long = 1000) =
        adapter.evaluate(definition(operation, rule), process(values), scope, WorldTimeTick(at), reads, staged)

    private fun stocked(vararg items: String) = Reads().also { reads ->
        items.forEach { reads.holdings[Phase64InventoryHolding(actor, it)] = 1 }
        reads.balances["payer"] = 100
        reads.balances["payee"] = 0
        reads.capacity["workshop"] = 5
    }
    private fun settlement() = mapOf("payerAccountUid" to "payer", "payeeAccountUid" to "payee",
        "currencyUid" to "coins", "transactionTypeUid" to "purchase")
    private fun trade() = settlement() + mapOf("inputItemUids" to "itemA|itemB", "buyerKind" to buyer.kindUid, "buyerUid" to buyer.uid)
    private fun prices() = mapOf("pricePolicyUid" to Phase64PricePolicy.FIXED_V1, "unitPriceMinor" to "7")

    @Test fun tradeConservesEachItemAndPaysExactlyTheRulePrice() {
        val plan = evaluate(Phase64EconomyOperations.TRADE, trade() + mapOf("unitPriceMinor" to "1"), stocked("itemA", "itemB"), rule = prices())
        assertEquals(BackgroundProcessStatus.COMPLETED, plan.status)
        val items = plan.changes.filterIsInstance<InventoryChange>()
        assertEquals(4, items.size)
        assertEquals(setOf("itemA", "itemB"), items.map { it.itemInstanceUid }.toSet())
        items.groupBy { it.itemInstanceUid }.values.forEach { moves ->
            assertEquals(0L, moves.sumOf { it.quantityDelta.units })
            assertEquals(listOf(actor, buyer), moves.map { it.subject })
        }
        assertEquals(FinancialChange("payer", "payee", 14, "coins", "purchase"), plan.changes.filterIsInstance<FinancialChange>().single())
        assertTrue(plan.claims.contains(WorldResourceClaim(DomainRef("FINANCIAL_ACCOUNT", "payer"), 14)))
        assertEquals(2, plan.claims.count { it.resource.kindUid == "ITEM_INSTANCE" })
        assertTrue(plan.sourceUids.containsAll(listOf("RULE", "PROCESS", "itemA", "itemB", "payer", "payee")))
    }

    @Test fun changedStagedCustodyOrBalanceBlocksWithoutPartialSale() {
        val reads = stocked("itemA", "itemB")
        for (staged in listOf(
            listOf<PlayerDomainChangePayload>(InventoryChange(actor, "itemA", ExactLongDelta.of(-1))),
            listOf<PlayerDomainChangePayload>(FinancialChange("payer", "other", 90, "coins", "other"))
        )) {
            val plan = evaluate(Phase64EconomyOperations.TRADE, trade(), reads, staged, prices())
            assertEquals(BackgroundProcessStatus.BLOCKED, plan.status)
            assertEquals("P64:RESOURCE_REQUIREMENT_UNSATISFIED", plan.reasonUid)
            assertTrue(plan.changes.isEmpty() && plan.effects.isEmpty() && plan.claims.isEmpty())
        }
    }

    @Test fun paymentCannotMintFundsOrIgnoreAuthorization() {
        val values = settlement() + mapOf("amountMinor" to "10")
        val reads = stocked()
        val paid = evaluate(Phase64EconomyOperations.PAYMENT, values, reads)
        assertEquals(FinancialChange("payer", "payee", 10, "coins", "purchase"), paid.changes.single())
        for (mutation in listOf("DENY", "MISSING", "NEGATIVE", "SELF")) {
            val current = stocked()
            val input = when (mutation) {
                "DENY" -> { current.allowed = false; values }
                "MISSING" -> { current.missing += DomainRef("FINANCIAL_ACCOUNT", "payee"); values }
                "NEGATIVE" -> values + mapOf("amountMinor" to "-1")
                else -> values + mapOf("payeeAccountUid" to "payer")
            }
            val plan = evaluate(Phase64EconomyOperations.PAYMENT, input, current)
            assertEquals(mutation, BackgroundProcessStatus.BLOCKED, plan.status)
            assertTrue(mutation, plan.changes.isEmpty())
        }
    }

    @Test fun settlementMustComeFromTheRealFinancialOwnerBeforeAnyGoodsMove() {
        val reads = stocked("itemA", "itemB")
        reads.settlementPlan = backgroundBlocked("P64:SETTLEMENT_ACCOUNT_NOT_OPEN")
        val unavailable = evaluate(Phase64EconomyOperations.TRADE, trade(), reads, rule = prices())
        assertEquals("P64:SETTLEMENT_ACCOUNT_NOT_OPEN", unavailable.reasonUid)
        assertTrue(unavailable.changes.isEmpty() && unavailable.claims.isEmpty())
        reads.settlementPlan = WorldConsequencePlan(changes = listOf(FinancialChange("payer", "payee", 1, "coins", "purchase")),
            sourceUids = listOf("payer", "payee", "coins", "purchase"))
        assertEquals("P64:SETTLEMENT_EFFECT_UNPROVEN", evaluate(Phase64EconomyOperations.TRADE, trade(), reads, rule = prices()).reasonUid)
        reads.settlementPlan = WorldConsequencePlan(changes = listOf(FinancialChange("payer", "payee", 14, "coins", "purchase")))
        assertEquals("P64:SETTLEMENT_EFFECT_UNPROVEN", evaluate(Phase64EconomyOperations.TRADE, trade(), reads, rule = prices()).reasonUid)
    }

    @Test fun financialOwnerRejectsClosedMismatchedRetiredAndExternalAccounts() {
        val values = settlement() + ("amountMinor" to "10")
        val payer = FinancialAccount("C", "payer", OwnershipOwnerRef(buyer.kindUid, buyer.uid), FINANCIAL_ACCOUNT_TYPE_DEFAULT,
            "coins", 0, "WORLD:PAYER")
        val payee = payer.copy(accountUid = "payee", holder = OwnershipOwnerRef(actor.kindUid, actor.uid), provenance = "WORLD:PAYEE")
        val currency = CurrencyDefinition("coins", "coins", "Coins", 100, "WORLD:CURRENCY")
        val type = Phase64SettlementType("purchase", FinancialFlowKind.INTERNAL, "ACTIVE", "WORLD:TRANSFER")
        fun prepare(p: FinancialAccount? = payer, q: FinancialAccount? = payee, c: CurrencyDefinition? = currency,
            t: Phase64SettlementType? = type, input: Map<String, String> = values) =
            Phase64EconomyOwnerRules.settlement(input, scope, p, q, c, t)
        val paid = prepare()
        assertEquals(listOf(FinancialChange("payer", "payee", 10, "coins", "purchase")), paid.changes)
        assertTrue(paid.sourceUids.containsAll(listOf("WORLD:PAYER", "WORLD:PAYEE", "WORLD:CURRENCY", "WORLD:TRANSFER")))
        val blocked = listOf(prepare(p = null), prepare(q = payee.copy(campaignId = "OTHER")),
            prepare(p = payer.copy(closedAt = 1)), prepare(q = payee.copy(currencyUid = "OTHER")),
            prepare(c = currency.copy(status = "RETIRED")), prepare(t = type.copy(flowKind = FinancialFlowKind.SOURCE)),
            prepare(t = type.copy(status = "RETIRED")), prepare(input = values + ("payerAccountVersion" to "2")),
            prepare(input = values + mapOf("payerHolderKind" to actor.kindUid, "payerHolderUid" to actor.uid)))
        blocked.forEach { plan ->
            assertEquals(plan.toString(), BackgroundProcessStatus.BLOCKED, plan.status)
            assertTrue(plan.changes.isEmpty() && plan.claims.isEmpty())
        }
    }

    @Test fun productionConsumesMaterialsOnlyWithTheExactOwnedOutputs() {
        val reads = stocked("material")
        val values = mapOf("inputItemUids" to "material", "outputItemUids" to "product")
        val unavailable = evaluate(Phase64EconomyOperations.PRODUCE, values, reads)
        assertEquals(BackgroundProcessStatus.BLOCKED, unavailable.status)
        assertTrue(unavailable.changes.isEmpty())
        reads.ownerPlan = WorldConsequencePlan(changes = listOf(InventoryChange(actor, "product", ExactLongDelta.of(1))),
            sourceUids = listOf("registeredRecipe"))
        val produced = evaluate(Phase64EconomyOperations.PRODUCE, values, reads)
        assertEquals(listOf(InventoryChange(actor, "material", ExactLongDelta.of(-1)),
            InventoryChange(actor, "product", ExactLongDelta.of(1))), produced.changes)
        assertEquals(Phase64EconomyOperations.OWNED_PRODUCTION, reads.ownerCalls.last().first)
        reads.ownerPlan = reads.ownerPlan.copy(changes = listOf(InventoryChange(actor, "wrong", ExactLongDelta.of(1))))
        assertEquals("P64:PRODUCTION_OUTPUT_UNPROVEN", evaluate(Phase64EconomyOperations.PRODUCE, values, reads).reasonUid)
    }

    @Test fun deliveryRechecksRouteAndDoesNotHandOverCargoBeforeOwnedMovement() {
        val reads = stocked("cargo")
        val values = mapOf("inputItemUids" to "cargo", "recipientKind" to buyer.kindUid, "recipientUid" to buyer.uid,
            "destinationKind" to destination.kindUid, "destinationUid" to destination.uid, "routeUid" to "route")
        assertEquals(BackgroundProcessStatus.ACTIVE, evaluate(Phase64EconomyOperations.DELIVER, values, reads, at = 999).status)
        assertTrue(reads.ownerCalls.isEmpty())
        reads.routeUid = "detour"
        assertEquals("P64:DELIVERY_ROUTE_CHANGED", evaluate(Phase64EconomyOperations.DELIVER, values, reads).reasonUid)
        assertTrue(reads.ownerCalls.isEmpty())
        reads.routeUid = "route"
        reads.ownerPlan = WorldConsequencePlan(changes = listOf(SpatialChange(actor, 0, 0, destination)), sourceUids = listOf("route"))
        val delivered = evaluate(Phase64EconomyOperations.DELIVER, values, reads)
        assertEquals(BackgroundProcessStatus.COMPLETED, delivered.status)
        assertEquals(0L, delivered.changes.filterIsInstance<InventoryChange>().sumOf { it.quantityDelta.units })
        assertEquals(Phase64EconomyOperations.OWNED_DELIVERY, reads.ownerCalls.last().first)
        reads.ownerPlan = WorldConsequencePlan(changes = listOf(SpatialChange(actor, 0, 0, DomainRef("PLACE", "wrong"))))
        assertEquals("P64:DELIVERY_MOVEMENT_UNPROVEN", evaluate(Phase64EconomyOperations.DELIVER, values, reads).reasonUid)
    }

    @Test fun projectWorkUsesExistingProjectAndDoesNotGrantLearning() {
        for (operation in listOf(Phase64EconomyOperations.BUILD, Phase64EconomyOperations.REPAIR, Phase64EconomyOperations.RESEARCH)) {
            val reads = stocked("material")
            val ownerOperation = when (operation) {
                Phase64EconomyOperations.BUILD -> Phase64EconomyOperations.OWNED_BUILD_CHECK
                Phase64EconomyOperations.REPAIR -> Phase64EconomyOperations.OWNED_REPAIR_CHECK
                else -> Phase64EconomyOperations.OWNED_RESEARCH_CHECK
            }
            reads.ownerPlan = WorldConsequencePlan(changes = listOf(BackgroundProjectWorkChange("C", "project", actor, ownerOperation,
                1, 1, 0, 3, "workshop", 2, "RULE", 1, listOf(actor, DomainRef("ITEM_INSTANCE", "material")))), sourceUids = listOf("project"))
            val values = mapOf("projectUid" to "project", "progressUnits" to "3", "inputItemUids" to "material",
                "labourResourceUid" to "workshop", "labourUnits" to "2")
            val plan = evaluate(operation, values, reads)
            assertEquals(operation, BackgroundProcessStatus.COMPLETED, plan.status)
            assertEquals(3L, plan.changes.filterIsInstance<BackgroundProjectWorkChange>().single().progressUnits)
            assertFalse(plan.changes.any { it is SkillChange || it is TechniqueChange || it is CampaignTruthChange })
            assertTrue(plan.claims.contains(WorldResourceClaim(DomainRef("LABOUR_CAPACITY", "workshop"), 2)))
            reads.ownerPlan = backgroundBlocked("P64:PROJECT_REQUIREMENTS_CHANGED")
            val blocked = evaluate(operation, values, reads)
            assertTrue(blocked.changes.isEmpty() && blocked.claims.isEmpty())
            assertEquals("P64:PROJECT_REQUIREMENTS_CHANGED", blocked.reasonUid)
        }
    }

    @Test fun completionRequiresTypedOwnerOutcomeAndIndependentResultEvidence() {
        val reads = stocked()
        val values = mapOf("projectUid" to "project", "resultEvidenceUid" to "proof")
        reads.ownerPlan = WorldConsequencePlan(sourceUids = listOf("proof"))
        assertEquals("P64:PROJECT_OUTCOME_UNPROVEN", evaluate(Phase64EconomyOperations.COMPLETE_PROJECT, values, reads).reasonUid)
        reads.ownerPlan = WorldConsequencePlan(changes = listOf(SkillChange(actor, "skill", ExactLongDelta.of(1))), sourceUids = listOf("proof"))
        assertEquals("P64:PROJECT_OUTCOME_UNPROVEN", evaluate(Phase64EconomyOperations.COMPLETE_PROJECT, values, reads).reasonUid)
        reads.ownerPlan = backgroundBlocked("P64:PROJECT_COMPLETION_RULE_UNAVAILABLE")
        assertEquals("P64:PROJECT_COMPLETION_RULE_UNAVAILABLE", evaluate(Phase64EconomyOperations.COMPLETE_PROJECT, values, reads).reasonUid)
    }

    @Test fun registeredLabourCapacityIsDebitedThroughItsExistingOwner() {
        val capacity=phase64MechanicalResource(actor,"WORK").uid
        val reads=stocked().also { it.capacity[capacity]=2 }
        reads.ownerPlan=WorldConsequencePlan(changes=listOf(BackgroundProjectWorkChange("C","project",actor,
            Phase64EconomyOperations.OWNED_RESEARCH_CHECK,1,1,0,1,capacity,2,"RULE",1,listOf(actor))),
            sourceUids=listOf("project"))
        val values=mapOf("projectUid" to "project","progressUnits" to "1","labourResourceUid" to capacity,"labourUnits" to "2")
        val plan=evaluate(Phase64EconomyOperations.RESEARCH,values,reads)
        assertEquals(BackgroundProcessStatus.COMPLETED,plan.status)
        assertEquals(ResourceChange(actor,"WORK",ExactLongDelta.of(-2)),plan.changes.filterIsInstance<ResourceChange>().single())
        assertEquals(plan,evaluate(Phase64EconomyOperations.RESEARCH,values,reads))
        reads.capacity[capacity]=0
        val blocked=evaluate(Phase64EconomyOperations.RESEARCH,values,reads)
        assertEquals("P64:RESOURCE_REQUIREMENT_UNSATISFIED",blocked.reasonUid)
        assertTrue(blocked.changes.isEmpty())
        assertTrue(runCatching { phase64LabourDebit(buyer,capacity,1) }.isFailure)
    }

    @Test fun retryAndLateBatchUseTheSameLogicalDeadline() {
        val reads = stocked("cargo")
        reads.ownerPlan = WorldConsequencePlan(changes = listOf(SpatialChange(actor, 0, 0, destination)), sourceUids = listOf("route"))
        val values = mapOf("inputItemUids" to "cargo", "recipientKind" to buyer.kindUid, "recipientUid" to buyer.uid,
            "destinationKind" to destination.kindUid, "destinationUid" to destination.uid, "routeUid" to "route")
        val first = evaluate(Phase64EconomyOperations.DELIVER, values, reads)
        val later = evaluate(Phase64EconomyOperations.DELIVER, values, reads, at = 20_000)
        assertEquals(first, later)
        assertEquals(reads.ownerCalls[0], reads.ownerCalls[1])
        assertEquals("1000", reads.ownerCalls.last().second["logicalEventTimeMillis"])
        val completed = process(values).copy(status = BackgroundProcessStatus.COMPLETED)
        assertTrue(adapter.evaluate(definition(Phase64EconomyOperations.DELIVER), completed, scope, WorldTimeTick(20_000), reads, emptyList()).changes.isEmpty())
        val outdated = process(values).copy(definitionVersion = 2)
        assertEquals("P64:RULE_VERSION_CHANGED", adapter.evaluate(definition(Phase64EconomyOperations.DELIVER), outdated, scope, WorldTimeTick(1000), reads, emptyList()).reasonUid)
    }

    @Test fun integerPricingAndCustodyResourceEncodingAreExact() {
        assertEquals(6L, Phase64PricePolicy.quote(Phase64PricePolicy.ADJUSTED_V1, 3, 2, -100))
        assertEquals(Long.MAX_VALUE, Phase64PricePolicy.quote(Phase64PricePolicy.FIXED_V1, Long.MAX_VALUE, 1))
        try {
            Phase64PricePolicy.quote(Phase64PricePolicy.FIXED_V1, Long.MAX_VALUE, 2)
            fail("overflow must not wrap price")
        } catch (_: ArithmeticException) { }
        val holding = Phase64InventoryHolding(DomainRef("NPC:TYPE", "a:2|b"), "item:|x")
        assertEquals(holding, phase64InventoryHolding(phase64InventoryResource(holding.holder, holding.itemInstanceUid)))
        assertNull(phase64InventoryHolding(DomainRef("INVENTORY_HOLDING", "-1:a")))
        assertNull(phase64InventoryHolding(DomainRef("INVENTORY_HOLDING", "999999999999999999:a")))
    }

    @Test fun productionOwnerEnforcesRegisteredRecipeAndTypedGenesis() {
        val recipe = Phase64ProductionRecipe("recipe", 1, mapOf("raw" to 1), mapOf("finished" to 1), "WORLD:RECIPE")
        val input = ItemInstance("C", "material", "raw", provenance = "WORLD:INPUT")
        val definition = ItemDefinition("finished", "PACK", "finished", "Finished", storagePolicy = ItemStoragePolicy.UNIQUE_INSTANCE,
            provenance = "WORLD:OUTPUT")
        val output = Phase64ProductionOutput(ItemInstance("C", "product", "finished", provenance = "WORLD:OUTPUT"), definition)
        val values = mapOf("recipeUid" to "recipe", "recipeVersion" to "1", "inputItemUids" to "material", "outputItemUids" to "product")
        val plan = Phase64EconomyOwnerRules.production(actor, values, scope, recipe, listOf(input), listOf(output))
        assertEquals(InventoryChange(actor, "product", ExactLongDelta.of(1)), plan.changes.single())
        assertTrue(plan.sourceUids.containsAll(listOf("recipe", "WORLD:RECIPE", "material", "product")))
        assertEquals("P64:PRODUCTION_RECIPE_UNAVAILABLE",
            Phase64EconomyOwnerRules.production(actor, values, scope, null, listOf(input), listOf(output)).reasonUid)
        assertEquals("P64:PRODUCTION_RECIPE_CHANGED",
            Phase64EconomyOwnerRules.production(actor, values, scope, recipe.copy(version = 2), listOf(input), listOf(output)).reasonUid)
        assertEquals("P64:PRODUCTION_RECIPE_REQUIREMENTS_CHANGED",
            Phase64EconomyOwnerRules.production(actor, values, scope, recipe, listOf(input.copy(itemDefinitionUid = "other")), listOf(output)).reasonUid)
        assertEquals("P64:PRODUCTION_GENESIS_UNPROVEN", Phase64EconomyOwnerRules.production(actor, values, scope, recipe,
            listOf(input), listOf(output.copy(sourceObject = DomainRef("OBJECT", "product"), materialization = universalInventoryItemMaterialization()))).reasonUid)
    }

    @Test fun publicResearchBindsTheInitiatingActorsExistingPoolAndRecordsActualWork() {
        val rule = definition(Phase64EconomyOperations.RESEARCH, mapOf("projectUid" to "@TARGET_UID",
            "labourPoolUid" to "STAMINA", "labourUnits" to "1", "progressUnits" to "1",
            Phase64ProcessActivation.ACTION_KEY to "WORK_ON_RESEARCH", Phase64ProcessActivation.PUBLIC_KEY to "true"))
        val bound = Phase64ProcessActivation.bind(rule, actor, DomainRef("PROJECT", "project"))
        val expected = phase64MechanicalResource(actor, "STAMINA")
        assertEquals(expected.uid, bound["labourResourceUid"])
        assertEquals("STAMINA", bound["labourPoolUid"])
        assertEquals(Phase64MechanicalResourceHolding(actor, "STAMINA"), phase64MechanicalResourceHolding(expected))
        assertFalse(bound.keys.any { it.startsWith("activation_") })
        val reads = stocked().apply { capacity[expected.uid] = 5 }
        reads.ownerPlan = Phase64EconomyOwnerRules.projectWork(Phase64EconomyOperations.OWNED_RESEARCH_CHECK,
            bound + mapOf("workerKind" to actor.kindUid, "workerUid" to actor.uid, "ruleUid" to rule.uid,
                "ruleVersion" to rule.version.toString()), scope, projectSnapshot(PROJECT_TYPE_RESEARCH), emptyList())
        val plan = adapter.evaluate(rule, process(bound), scope, WorldTimeTick(1000), reads, emptyList())
        assertEquals(BackgroundProcessStatus.COMPLETED, plan.status)
        val work = plan.changes.filterIsInstance<BackgroundProjectWorkChange>().single()
        assertEquals(actor, work.worker)
        assertEquals(expected.uid, work.labourResourceUid)
        assertEquals(1L, work.labourUnits)
        assertEquals(1L, work.progressUnits)
        assertEquals(listOf(WorldResourceClaim(DomainRef("LABOUR_CAPACITY", expected.uid), 1)), plan.claims)
        assertEquals(ResourceChange(actor,"STAMINA",ExactLongDelta.of(-1)),plan.changes.filterIsInstance<ResourceChange>().single())
        assertFalse(plan.changes.any { it is SkillChange || it is TechniqueChange })
        reads.capacity[expected.uid] = 0
        val exhausted = adapter.evaluate(rule, process(bound), scope, WorldTimeTick(1000), reads, emptyList())
        assertEquals("P64:RESOURCE_REQUIREMENT_UNSATISFIED", exhausted.reasonUid)
        assertTrue(exhausted.changes.isEmpty() && exhausted.claims.isEmpty())
    }

    @Test fun fixedLabourBindingRejectsAlternateWorkersPoolsAndNonProjectTargets() {
        val importedActor = DomainRef("NPC:TYPE", "worker:2|x")
        val target = DomainRef("PROJECT", "project")
        val rule = definition(Phase64EconomyOperations.RESEARCH,
            mapOf("projectUid" to "@TARGET_UID", "labourPoolUid" to "STAMINA", "labourUnits" to "1", "progressUnits" to "1"))
        val values = rule.parameters + ("projectUid" to target.uid)
        val bound = phase64BindProjectLabour(rule, importedActor, target, values)
        assertEquals(phase64MechanicalResource(importedActor, "STAMINA").uid, bound["labourResourceUid"])
        fun rejects(definition: BackgroundProcessDefinition = rule, reference: DomainRef = target,
            parameters: Map<String, String> = values) {
            try {
                phase64BindProjectLabour(definition, importedActor, reference, parameters)
                fail("a rule must not bind an alternate labour authority")
            } catch (_: IllegalArgumentException) { }
        }
        rejects(parameters = values + ("labourResourceUid" to phase64MechanicalResource(buyer, "STAMINA").uid))
        rejects(parameters = values + ("labourPoolUid" to "HEALTH"))
        rejects(reference = DomainRef("ITEM_INSTANCE", "project"))
        rejects(parameters = values + ("projectUid" to "another"))
        rejects(definition = rule.copy(parameters = rule.parameters + ("labourResourceUid" to "workshop")))
        rejects(definition = rule.copy(domain = "ECONOMY", operation = Phase64EconomyOperations.CONSUME))
        rejects(definition = rule.copy(parameters = rule.parameters + ("labourPoolUid" to "@TARGET_UID")),
            parameters = values + ("labourPoolUid" to "project"))
        val legacy = definition(Phase64EconomyOperations.RESEARCH, mapOf("labourResourceUid" to "workshop"))
        assertEquals(legacy.parameters, phase64BindProjectLabour(legacy, importedActor, target, legacy.parameters))
    }

    private fun deliveryBody() = MechanicalActorView("C", actor, MechanicalActorKind.NPC, 1,
        MechanicalStateMaterialization.FULL, emptyMap(), listOf(MechanicalResource("STAMINA", 5, 5),
            MechanicalResource("HEALTH", 10, 10)), setOf("WALK"), locationRef = DomainRef("PLACE", "warehouse"),
        generationProvenanceUid = "WORLD:CARRIER")

    private fun deliveryRoute() = WorldTravelPlan(DomainRef("PLACE", "warehouse"), destination, listOf(
        WorldTopologyEdge("road", 1, DomainRef("PLACE", "warehouse"), destination, ActionDuration(1000),
            mapOf("STAMINA" to 2), setOf("WALK"), WorldTimeTick(0), null, "WORLD:ROAD")))

    private fun deliveryParameters(route: WorldTravelPlan = deliveryRoute()) = mapOf("routeUid" to route.fingerprint,
        "destinationKind" to destination.kindUid, "destinationUid" to destination.uid,
        "processStartedAtMillis" to "0", "logicalEventTimeMillis" to "1000")

    @Test fun capturedDeliveryProducesActualDebitsAndRecognizedActorScopedClaims() {
        val route = deliveryRoute()
        val plan = Phase64EconomyOwnerRules.delivery(actor, deliveryParameters(route), scope, deliveryBody(), route, emptyList())
        assertEquals(BackgroundProcessStatus.COMPLETED, plan.status)
        assertEquals(listOf(ResourceChange(actor, "STAMINA", ExactLongDelta.of(-2)),
            SpatialChange(actor, 0, destinationLocation = destination)), plan.changes)
        assertEquals(listOf(WorldResourceClaim(phase64MechanicalResource(actor, "STAMINA"), 2)), plan.claims)
        assertEquals(Phase64MechanicalResourceHolding(actor, "STAMINA"), phase64MechanicalResourceHolding(plan.claims.single().resource))
        assertTrue(plan.sourceUids.containsAll(listOf("WORLD:ROAD", route.fingerprint, "WORLD:CARRIER")))
        assertTrue(plan.effects.isEmpty())
        val sameDeadline = Phase64EconomyOwnerRules.delivery(actor, deliveryParameters(route), scope, deliveryBody(), route,
            listOf(ResourceChange(buyer, "STAMINA", ExactLongDelta.of(-5))))
        assertEquals(plan, sameDeadline)
    }

    @Test fun capturedDeliveryRejectsStaleRouteTimingOriginAndStagedExhaustionWithoutEffects() {
        val body = deliveryBody()
        val route = deliveryRoute()
        val values = deliveryParameters(route)
        fun blocked(reason: String, parameters: Map<String, String> = values, captured: MechanicalActorView? = body,
            capturedRoute: WorldTravelPlan? = route, staged: List<PlayerDomainChangePayload> = emptyList()) {
            val plan = Phase64EconomyOwnerRules.delivery(actor, parameters, scope, captured, capturedRoute, staged)
            assertEquals(reason, plan.reasonUid)
            assertEquals(BackgroundProcessStatus.BLOCKED, plan.status)
            assertTrue(plan.changes.isEmpty() && plan.effects.isEmpty() && plan.claims.isEmpty())
        }
        blocked("P64:DELIVERY_ACTOR_UNAVAILABLE", captured = null)
        blocked("P64:DELIVERY_CAPTURE_SCOPE_MISMATCH", captured = body.copy(campaignUid = "OTHER"))
        blocked("P64:DELIVERY_ACTIVE_PLAYER_DECISION_REQUIRED", captured = body.copy(kind = MechanicalActorKind.ACTIVE_PLAYER))
        blocked("P64:DELIVERY_AUTHORIZED_ROUTE_UNAVAILABLE", capturedRoute = null)
        blocked("P64:DELIVERY_ROUTE_CHANGED", parameters = values + ("routeUid" to "old"))
        blocked("P64:DELIVERY_ROUTE_CHANGED", parameters = values + ("destinationUid" to "another"))
        blocked("P64:DELIVERY_ROUTE_DURATION_UNSATISFIED", parameters = values + ("logicalEventTimeMillis" to "999"))
        val closed = route.copy(edges = listOf(route.edges.single().copy(validThrough = WorldTimeTick(1000))))
        blocked("P64:DELIVERY_ROUTE_CLOSED", parameters = deliveryParameters(closed), capturedRoute = closed)
        blocked("P64:DELIVERY_ORIGIN_CHANGED", staged = listOf(SpatialChange(actor, 0, destinationLocation = DomainRef("PLACE", "elsewhere"))))
        blocked("P64:DELIVERY_ORIGIN_UNAVAILABLE", staged = listOf(SpatialChange(actor, 10)))
        blocked("P64:DELIVERY_ORIGIN_UNAVAILABLE", staged = listOf(SpatialChange(actor, 0, destinationLocation = route.origin), SpatialChange(actor, 10)))
        blocked("P64:DELIVERY_CAPABILITY_UNAVAILABLE", captured = body.copy(executableAbilityUids = emptySet()))
        blocked("P64:DELIVERY_RESOURCE_REQUIREMENT_UNSATISFIED", staged = listOf(ResourceChange(actor, "STAMINA", ExactLongDelta.of(-4))))
        blocked("P64:DELIVERY_ACTOR_INCAPACITATED", staged = listOf(ResourceChange(actor, "HEALTH", ExactLongDelta.of(-10))))
        val recovered = listOf(SpatialChange(actor, 10), SpatialChange(actor, 0, destinationLocation = route.origin))
        assertEquals(BackgroundProcessStatus.COMPLETED, Phase64EconomyOwnerRules.delivery(actor, values, scope, body, route, recovered).status)
    }

    private fun projectSnapshot(type: String = PROJECT_TYPE_INFRASTRUCTURE): Phase64ProjectSnapshot {
        val project = DevelopmentProject("C", "project", type, OwnershipOwnerRef(actor.kindUid, actor.uid),
            title = "Registered work", objectiveSummary = "Registered result", targetDomainUid = "WORLD", progressCapUnits = 10,
            createdOrder = 0, provenance = "WORLD:PROJECT")
        return Phase64ProjectSnapshot(project, ProjectTypeDefinition(type, "GENERIC", provenance = "WORLD:TYPE"),
            ProjectProgressSnapshot("project", ProjectStatus.ACTIVE_WORK, 2, 10, 0, 0, 1))
    }

    @Test fun projectOwnerChecksRequirementsDependenciesAndStagedProgress() {
        val values = mapOf("projectUid" to "project", "projectVersion" to "1", "progressUnits" to "3", "workerKind" to actor.kindUid,
            "workerUid" to actor.uid, "labourResourceUid" to "workshop", "labourUnits" to "2", "ruleUid" to "RULE", "ruleVersion" to "1")
        val original = projectSnapshot()
        val requirement = ProjectRequirement("C", "materialRequirement", "project", "MATERIAL", requiredFromOrder = 0, provenance = "WORLD:REQUIREMENT")
        val required = original.copy(requirements = listOf(requirement))
        assertEquals("P64:PROJECT_REQUIREMENTS_UNSATISFIED", Phase64EconomyOwnerRules.projectWork(
            Phase64EconomyOperations.OWNED_BUILD_CHECK, values, scope, required, emptyList()).reasonUid)
        val satisfied = required.copy(satisfactions = listOf(ProjectRequirementSatisfaction("C", "satisfied", "project", "materialRequirement", 1,
            provenance = "WORLD:SATISFACTION")))
        val permitted = Phase64EconomyOwnerRules.projectWork(Phase64EconomyOperations.OWNED_BUILD_CHECK, values, scope, satisfied, emptyList())
        assertEquals(BackgroundProcessStatus.COMPLETED, permitted.status)
        assertEquals(actor, permitted.changes.filterIsInstance<BackgroundProjectWorkChange>().single().worker)
        assertTrue(permitted.effects.isEmpty())
        assertTrue(permitted.sourceUids.containsAll(listOf("project", "satisfied")))
        val staged = listOf<PlayerDomainChangePayload>(DevelopmentProjectChange.create("project", ProjectWorkResult.SUCCESS.name, ProjectProgressDelta.of(6)))
        assertEquals("P64:PROJECT_PROGRESS_LIMIT_REACHED", Phase64EconomyOwnerRules.projectWork(
            Phase64EconomyOperations.OWNED_BUILD_CHECK, values, scope, satisfied, staged).reasonUid)
        val lastWork = Phase64EconomyOwnerRules.projectWork(Phase64EconomyOperations.OWNED_BUILD_CHECK, values + mapOf("progressUnits" to "8"),
            scope, satisfied, emptyList()).changes.filterIsInstance<BackgroundProjectWorkChange>().single()
        assertTrue(lastWork.readyToComplete)
        assertEquals("P64:PROJECT_READY_COMMIT_REQUIRED", Phase64EconomyOwnerRules.projectWork(
            Phase64EconomyOperations.OWNED_BUILD_CHECK, values, scope, satisfied, listOf(lastWork)).reasonUid)
        val dependency = ProjectDependency("C", "dependency", "project", "foundation", "REQUIRES", validFromOrder = 0, provenance = "WORLD:DEPENDENCY")
        assertEquals("P64:PROJECT_DEPENDENCY_UNSATISFIED", Phase64EconomyOwnerRules.projectWork(
            Phase64EconomyOperations.OWNED_BUILD_CHECK, values, scope, satisfied.copy(dependencies = listOf(dependency)), emptyList()).reasonUid)
        assertEquals("P64:PROJECT_TYPE_REQUIREMENT_UNSATISFIED", Phase64EconomyOwnerRules.projectWork(
            Phase64EconomyOperations.OWNED_RESEARCH_CHECK, values, scope, satisfied, emptyList()).reasonUid)
        assertEquals("P64:PROJECT_LIFECYCLE_CHANGED", Phase64EconomyOwnerRules.projectWork(
            Phase64EconomyOperations.OWNED_BUILD_CHECK, values, scope, satisfied.copy(progress = satisfied.progress.copy(status = ProjectStatus.PAUSED)), emptyList()).reasonUid)
    }

    @Test fun projectReadinessCannotCommitAnOutcomeOnItsOwn() {
        val original = projectSnapshot()
        val ready = original.copy(progress = original.progress.copy(status = ProjectStatus.READY_TO_COMPLETE, progressUnits = 10))
        val values = mapOf("projectUid" to "project", "resultEvidenceUid" to "workProof")
        val work = ProjectWorkRecord("C", "workProof", "project", "REGISTERED_RESULT", OwnershipOwnerRef(actor.kindUid, actor.uid),
            1, ProjectWorkResult.SUCCESS, progressDeltaUnits = 8, provenance = "WORLD:PROOF")
        val readiness = Phase64EconomyOwnerRules.projectCompletionReadiness(values, scope, ready, work)
        assertEquals(BackgroundProcessStatus.COMPLETED, readiness.status)
        assertTrue(readiness.changes.isEmpty() && readiness.effects.isEmpty())
        assertTrue("workProof" in readiness.sourceUids)
        assertEquals("P64:PROJECT_RESULT_EVIDENCE_UNAVAILABLE",
            Phase64EconomyOwnerRules.projectCompletionReadiness(values, scope, ready, work.copy(result = ProjectWorkResult.FAILURE)).reasonUid)
        assertEquals("P64:PROJECT_NOT_READY_TO_COMPLETE",
            Phase64EconomyOwnerRules.projectCompletionReadiness(values, scope, original, work).reasonUid)
    }

    @Test fun explicitWorkerAndCompletionCodecsRoundTripAndRejectUnknownFields() {
        val work = BackgroundProjectWorkChange("C", "project", actor, Phase64EconomyOperations.OWNED_RESEARCH_CHECK,
            1, 2, 4, 3, "workshop", 2, "RULE", 1, listOf(actor))
        val workCodec = phase64ProjectWorkCodec()
        assertEquals(work, workCodec.decode(workCodec.encode(work)))
        val completion = DevelopmentProjectCompletionChange("C", "project", 1, 2, 10, "WORK", "WORK_EVENT",
            PROJECT_OUTPUT_ITEM_INSTANCE, DomainRef("ITEM_INSTANCE", "result"), "RULE", 1)
        val codec = phase64ProjectCompletionCodec()
        assertEquals(completion, codec.decode(codec.encode(completion)))
        try {
            codec.decode(JsonObject(codec.encode(completion) + ("unexpected" to JsonPrimitive("hidden"))))
            fail("unknown top-level fields must fail")
        } catch (_: IllegalArgumentException) { }
        try {
            val nested = JsonObject(mapOf("kindUid" to JsonPrimitive("ITEM_INSTANCE"), "uid" to JsonPrimitive("result"), "extra" to JsonPrimitive("hidden")))
            codec.decode(JsonObject(codec.encode(completion) + ("outputRef" to nested)))
            fail("unknown nested reference fields must fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun typedCompletionRequiresTheDeclaredExistingOutput() {
        val original = projectSnapshot()
        val ready = original.copy(project = original.project.copy(intendedOutputKindUid = PROJECT_OUTPUT_ITEM_INSTANCE,
            targetKindUid = "ITEM_INSTANCE", targetUid = "result"), progress = original.progress.copy(status = ProjectStatus.READY_TO_COMPLETE, progressUnits = 10))
        val work = ProjectWorkRecord("C", "WORK", "project", "REGISTERED_RESULT", OwnershipOwnerRef(actor.kindUid, actor.uid),
            1, ProjectWorkResult.SUCCESS, progressDeltaUnits = 8, provenance = "WORLD:PROOF")
        val values = mapOf("projectUid" to "project", "resultEvidenceUid" to "WORK", "outputKindUid" to PROJECT_OUTPUT_ITEM_INSTANCE,
            "outputRefKindUid" to "ITEM_INSTANCE", "outputUid" to "result", "ruleUid" to "RULE", "ruleVersion" to "1")
        val evidence = Phase64ProjectResultEvidence(work, "WORK_EVENT")
        val plan = preparePhase64ProjectCompletion(values, scope, ready, evidence, true)
        assertEquals(BackgroundProcessStatus.COMPLETED, plan.status)
        assertEquals("WORK_EVENT", plan.changes.filterIsInstance<DevelopmentProjectCompletionChange>().single().resultEventUid)
        assertFalse(plan.changes.any { it is SkillChange || it is TechniqueChange || it is KnowledgeAcquisitionChange })
        assertEquals("P64:PROJECT_OUTPUT_UNAVAILABLE", preparePhase64ProjectCompletion(values, scope, ready, evidence, false).reasonUid)
        assertEquals("P64:PROJECT_OUTPUT_CONTRACT_CHANGED", preparePhase64ProjectCompletion(values + mapOf("outputUid" to "other"), scope, ready, evidence, true).reasonUid)
    }

    @Test fun stagedRevocationBlocksEveryEconomyAndProjectPathWithoutEffects() {
        for (operation in Phase64EconomyOperations.economy + Phase64EconomyOperations.projects) {
            val reads = stocked("itemA", "itemB", "material", "cargo")
            val values = when (operation) {
                Phase64EconomyOperations.CONSUME -> mapOf("inputItemUids" to "itemA")
                Phase64EconomyOperations.TRADE -> trade()
                Phase64EconomyOperations.PAYMENT -> settlement() + mapOf("amountMinor" to "10")
                Phase64EconomyOperations.PRODUCE -> {
                    reads.ownerPlan = WorldConsequencePlan(changes = listOf(InventoryChange(actor, "product", ExactLongDelta.of(1))), sourceUids = listOf("recipe"))
                    mapOf("inputItemUids" to "material", "outputItemUids" to "product")
                }
                Phase64EconomyOperations.DELIVER -> {
                    reads.ownerPlan = WorldConsequencePlan(changes = listOf(SpatialChange(actor, 0, 0, destination)), sourceUids = listOf("route"))
                    mapOf("inputItemUids" to "cargo", "recipientKind" to buyer.kindUid, "recipientUid" to buyer.uid,
                        "destinationKind" to destination.kindUid, "destinationUid" to destination.uid, "routeUid" to "route")
                }
                Phase64EconomyOperations.COMPLETE_PROJECT -> {
                    reads.ownerPlan = WorldConsequencePlan(changes = listOf(DevelopmentProjectCompletionChange("C", "project", 1, 1, 10,
                        "proof", "WORK_EVENT", PROJECT_OUTPUT_ITEM_INSTANCE, DomainRef("ITEM_INSTANCE", "result"), "RULE", 1)), sourceUids = listOf("proof"))
                    mapOf("projectUid" to "project", "resultEvidenceUid" to "proof")
                }
                else -> {
                    val ownerOperation = when (operation) {
                        Phase64EconomyOperations.BUILD -> Phase64EconomyOperations.OWNED_BUILD_CHECK
                        Phase64EconomyOperations.REPAIR -> Phase64EconomyOperations.OWNED_REPAIR_CHECK
                        else -> Phase64EconomyOperations.OWNED_RESEARCH_CHECK
                    }
                    reads.ownerPlan = WorldConsequencePlan(changes = listOf(BackgroundProjectWorkChange("C", "project", actor, ownerOperation,
                        1, 1, 0, 3, "workshop", 2, "RULE", 1, listOf(actor))), sourceUids = listOf("project"))
                    mapOf("projectUid" to "project", "progressUnits" to "3", "labourResourceUid" to "workshop", "labourUnits" to "2")
                }
            }
            val rule = if (operation == Phase64EconomyOperations.TRADE) prices() else emptyMap()
            assertEquals(operation, BackgroundProcessStatus.COMPLETED, evaluate(operation, values, reads, rule = rule).status)
            val beforeCalls = reads.ownerCalls.size
            val revoked = AccessAuthorityChange(AccessOperation.REVOKE_GRANT, "REVOCATION", actor.kindUid, actor.uid,
                AccessGrantKind.EXPLICIT.name, operation, validFromOrder = 1)
            val blocked = evaluate(operation, values, reads, listOf(revoked), rule)
            assertEquals(operation, "P64:ECONOMY_ACCESS_DENIED", blocked.reasonUid)
            assertTrue(operation, blocked.changes.isEmpty() && blocked.effects.isEmpty() && blocked.claims.isEmpty())
            assertEquals(operation, beforeCalls, reads.ownerCalls.size)
        }
    }
}
