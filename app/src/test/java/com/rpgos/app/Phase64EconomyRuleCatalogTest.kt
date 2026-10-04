package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

/** Catalog recipes execute the real pure domain owners from bounded captured inputs. SQL,
 * initiation/decision and commit integration remain covered by the separate device tests. */
class Phase64EconomyRuleCatalogTest {
    private val actor = DomainRef("NPC", "SELLER")
    private val buyer = DomainRef("NPC", "BUYER")
    private val origin = DomainRef("LOCATION", "ORIGIN")
    private val destination = DomainRef("LOCATION", "DESTINATION")
    private val scope = BackgroundProcessEvaluationScope(TemporalScope("C", "G", 1, "STATE"), "SEED", "RULES")
    private val raw = ItemInstance("C", "MATERIAL", "RAW", provenance = "WORLD:RAW")
    private val finished = Phase64ProductionOutput(ItemInstance("C", "PRODUCT", "FINISHED", provenance = "WORLD:PRODUCT"),
        ItemDefinition("FINISHED", "PACK", "finished", "Finished", storagePolicy = ItemStoragePolicy.UNIQUE_INSTANCE,
            provenance = "WORLD:PRODUCT"))
    private val recipe = Phase64ProductionRecipe("RECIPE", 1, mapOf("RAW" to 1), mapOf("FINISHED" to 1), "WORLD:RECIPE")
    private val body = MechanicalActorView("C", actor, MechanicalActorKind.NPC, 1, MechanicalStateMaterialization.FULL,
        emptyMap(), listOf(MechanicalResource("HEALTH", 10, 10), MechanicalResource("STAMINA", 5, 5)), setOf("WALK"),
        locationRef = origin, generationProvenanceUid = "WORLD:BODY")
    private val route = WorldTravelPlan(origin, destination, listOf(WorldTopologyEdge("ROAD", 1, origin, destination,
        ActionDuration(1000), mapOf("STAMINA" to 2), setOf("WALK"), WorldTimeTick(0), null, "WORLD:ROAD")))
    private val payer = FinancialAccount("C", "PAYER", OwnershipOwnerRef(buyer.kindUid, buyer.uid), FINANCIAL_ACCOUNT_TYPE_DEFAULT,
        "COIN", 0, "WORLD:PAYER")
    private val payee = payer.copy(accountUid = "PAYEE", holder = OwnershipOwnerRef(actor.kindUid, actor.uid), provenance = "WORLD:PAYEE")
    private val currency = CurrencyDefinition("COIN", "coin", "Coin", 100, "WORLD:COIN")
    private val transfer = Phase64SettlementType("PURCHASE", FinancialFlowKind.INTERNAL, "ACTIVE", "WORLD:PURCHASE")
    private val settlement = Phase64RegisteredSettlementPolicy("SETTLEMENT", 1, payer, payee, currency, transfer, "WORLD:SETTLEMENT")
    private val price = Phase64RegisteredPricePolicy(Phase64PricePolicy.ADJUSTED_V1, 1, 7, -100, "WORLD:PRICE")

    private fun policy(uid: String, player: Boolean = false, npc: Boolean = true) =
        Phase64EconomyActivationPolicy(uid, 1, "ACTION:$uid", 1000, "WORLD:POLICY:$uid", player, npc)

    private fun project(type: String): Phase64ProjectSnapshot {
        val state = DevelopmentProject("C", "PROJECT", type, OwnershipOwnerRef(actor.kindUid, actor.uid), title = "Registered work",
            objectiveSummary = "Registered outcome", targetDomainUid = "WORLD", progressCapUnits = 2, createdOrder = 0,
            provenance = "WORLD:PROJECT")
        return Phase64ProjectSnapshot(state, ProjectTypeDefinition(type, "GENERIC", provenance = "WORLD:TYPE"),
            ProjectProgressSnapshot("PROJECT", ProjectStatus.PROTOTYPE, 0, 2, 0, 0, 0))
    }

    private fun bound(rule: BackgroundProcessDefinition, worker: DomainRef, target: DomainRef): Map<String, String> {
        val values = rule.parameters.filterKeys { !it.startsWith("activation_") }.mapValues { (_, value) ->
            when (value) { "@ACTOR_KIND" -> worker.kindUid; "@ACTOR_UID" -> worker.uid; "@TARGET_UID" -> target.uid; else -> value }
        }
        return phase64BindProjectLabour(rule, worker, target, Phase64EconomyRuleCatalog.bind(rule, worker, target, values))
    }

    private fun evaluate(rule: BackgroundProcessDefinition, worker: DomainRef = actor, target: DomainRef = worker,
        snapshot: Phase64ProjectSnapshot? = null, staged: List<PlayerDomainChangePayload> = emptyList()): WorldConsequencePlan {
        val captures = object : BackgroundWorldReadPort {
            override fun exists(ref: DomainRef) = when (ref.kindUid) {
                "LABOUR_CAPACITY" -> phase64MechanicalResourceHolding(DomainRef("MECHANICAL_RESOURCE", ref.uid))?.holder == worker
                "ITEM_INSTANCE" -> ref.uid == raw.itemInstanceUid
                "FINANCIAL_ACCOUNT" -> ref.uid in setOf(payer.accountUid, payee.accountUid)
                "CURRENCY" -> ref.uid == currency.currencyUid
                "PROJECT" -> ref.uid == snapshot?.project?.projectUid
                else -> ref in setOf(worker, actor, buyer, destination, origin)
            }
            override fun route(actor: DomainRef, destination: DomainRef, at: WorldTimeTick) = route.fingerprint
            override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>) = true // explicit fixture grants
            override fun available(resource: DomainRef, staged: List<PlayerDomainChangePayload>): Long? {
                phase64InventoryHolding(resource)?.let { holding ->
                    val initial = if (holding.holder == worker && holding.itemInstanceUid == raw.itemInstanceUid) 1L else 0L
                    return staged.filterIsInstance<InventoryChange>().filter { it.subject == holding.holder && it.itemInstanceUid == holding.itemInstanceUid }
                        .fold(initial) { total, change -> Math.addExact(total, change.quantityDelta.units) }
                }
                val pool = phase64MechanicalResourceHolding(if (resource.kindUid == "LABOUR_CAPACITY")
                    DomainRef("MECHANICAL_RESOURCE", resource.uid) else resource)
                if (pool != null) return body.resources.singleOrNull { it.resourceUid == pool.resourceUid }?.current
                return when (resource.kindUid) {
                    "ITEM_INSTANCE" -> if (resource.uid == raw.itemInstanceUid) 1L else 0L
                    "FINANCIAL_ACCOUNT" -> if (resource.uid == payer.accountUid) 100L else 0L
                    else -> null
                }
            }
            override fun prepareOwnedEffect(operation: String, actor: DomainRef, parameters: Map<String, String>,
                scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>) = when (operation) {
                Phase64EconomyOperations.OWNED_PRODUCTION -> Phase64EconomyOwnerRules.production(actor, parameters, scope, recipe, listOf(raw), listOf(finished))
                Phase64EconomyOperations.OWNED_DELIVERY -> Phase64EconomyOwnerRules.delivery(actor, parameters, scope, body, route, staged)
                Phase64EconomyOperations.OWNED_SETTLEMENT -> Phase64EconomyOwnerRules.settlement(parameters, scope, payer, payee, currency, transfer)
                Phase64EconomyOperations.OWNED_BUILD_CHECK, Phase64EconomyOperations.OWNED_REPAIR_CHECK,
                Phase64EconomyOperations.OWNED_RESEARCH_CHECK -> Phase64EconomyOwnerRules.projectWork(operation, parameters, scope, snapshot, staged)
                else -> backgroundBlocked("P64:OWNER_UNAVAILABLE")
            }
        }
        val process = BackgroundProcessInstance("PROCESS", rule.uid, rule.version, worker, 1, WorldTimeTick(0),
            WorldTimeTick(rule.durationMillis), parameters = bound(rule, worker, target))
        return Phase64EconomyProjectsAdapter().evaluate(rule, process, scope, process.due, captures, staged)
    }

    @Test fun neutralCoreActionsHaveCompleteExecutableConsumptionAndWorkContracts() {
        val definitions = Phase64EconomyRuleCatalog.coreDefinitions()
        assertEquals(setOf(Phase64EconomyOperations.CONSUME, Phase64EconomyOperations.BUILD,
            Phase64EconomyOperations.REPAIR, Phase64EconomyOperations.RESEARCH), definitions.map { it.operation }.toSet())
        val consume = definitions.single { it.operation == Phase64EconomyOperations.CONSUME }
        assertEquals(listOf(InventoryChange(actor, raw.itemInstanceUid, ExactLongDelta.of(-1))),
            evaluate(consume, target = DomainRef("ITEM_INSTANCE", raw.itemInstanceUid)).changes)
        definitions.filter { it.domain == "PROJECT" }.forEach { rule ->
            val type = if (rule.operation == Phase64EconomyOperations.RESEARCH) PROJECT_TYPE_RESEARCH else PROJECT_TYPE_INFRASTRUCTURE
            val plan = evaluate(rule, target = DomainRef("PROJECT", "PROJECT"), snapshot = project(type))
            assertEquals(plan.toString(), BackgroundProcessStatus.COMPLETED, plan.status)
            val work = plan.changes.filterIsInstance<BackgroundProjectWorkChange>().single()
            assertEquals(phase64MechanicalResource(actor, "STAMINA").uid, work.labourResourceUid)
            assertEquals(1L, work.progressUnits)
            assertTrue(plan.effects.isEmpty())
            assertFalse(plan.changes.any { it is SkillChange || it is TechniqueChange || it is DevelopmentProjectCompletionChange })
        }
        assertEquals(4, definitions.map { it.parameters[Phase64ProcessActivation.ACTION_KEY] }.distinct().size)
    }

    @Test fun explicitProductionRecipeMakesOnlyItsExactOwnedOutputsAndConsumesRegisteredMaterials() {
        val rule = Phase64EconomyRuleCatalog.production(policy("PRODUCE"), actor, recipe, listOf(raw), listOf(finished))
        assertEquals("RAW=1", rule.parameters["recipeInputDefinitionCounts"])
        assertEquals("FINISHED=1", rule.parameters["recipeOutputDefinitionCounts"])
        val plan = evaluate(rule)
        assertEquals(BackgroundProcessStatus.COMPLETED, plan.status)
        assertEquals(listOf(InventoryChange(actor, raw.itemInstanceUid, ExactLongDelta.of(-1)),
            InventoryChange(actor, finished.instance.itemInstanceUid, ExactLongDelta.of(1))), plan.changes)
        assertTrue(plan.sourceUids.containsAll(listOf(recipe.uid, recipe.provenanceUid)))
        assertEquals("P64:REGISTERED_ECONOMY_EXECUTOR_REQUIRED",
            Phase64EconomyProjectsAdapter().evaluate(rule, BackgroundProcessInstance("OTHER", rule.uid, rule.version, buyer, 1,
                WorldTimeTick(0), WorldTimeTick(1000)), scope, WorldTimeTick(1000), object : BackgroundWorldReadPort {
                override fun exists(ref: DomainRef) = error("The foreign executor must fail before any read")
                override fun available(resource: DomainRef, staged: List<PlayerDomainChangePayload>): Long? = error("not reached")
                override fun route(actor: DomainRef, destination: DomainRef, at: WorldTimeTick): String? = error("not reached")
                override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>) = error("not reached")
                override fun prepareOwnedEffect(operation: String, actor: DomainRef, parameters: Map<String, String>,
                    scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>): WorldConsequencePlan = error("not reached")
            }, emptyList()).reasonUid)
    }

    @Test fun registeredTradeAndPaymentUseTheExactQuoteAndExistingInternalSettlement() {
        val trade = Phase64EconomyRuleCatalog.trade(policy("TRADE"), actor, buyer, listOf(raw), settlement, price)
        val sold = evaluate(trade)
        assertEquals(BackgroundProcessStatus.COMPLETED, sold.status)
        assertEquals(FinancialChange(payer.accountUid, payee.accountUid, 7, currency.currencyUid, transfer.uid),
            sold.changes.filterIsInstance<FinancialChange>().single())
        assertEquals(0L, sold.changes.filterIsInstance<InventoryChange>().sumOf { it.quantityDelta.units })
        assertEquals(listOf(actor, buyer), sold.changes.filterIsInstance<InventoryChange>().map { it.subject })
        val payment = Phase64EconomyRuleCatalog.payment(policy("PAY"), settlement, 3)
        val paid = evaluate(payment, worker = buyer)
        assertEquals(listOf(FinancialChange(payer.accountUid, payee.accountUid, 3, currency.currencyUid, transfer.uid)), paid.changes)
        assertTrue(paid.changes.none { it is InventoryChange })
    }

    @Test fun registeredDeliveryBindsExactCapturedRouteAndDebitsTheExistingCarrierPool() {
        val rule = Phase64EconomyRuleCatalog.delivery(policy("DELIVER"), body, listOf(raw), buyer, route)
        val plan = evaluate(rule)
        assertEquals(BackgroundProcessStatus.COMPLETED, plan.status)
        assertEquals(listOf(ResourceChange(actor, "STAMINA", ExactLongDelta.of(-2))), plan.changes.filterIsInstance<ResourceChange>())
        assertEquals(listOf(SpatialChange(actor, 0, destinationLocation = destination)), plan.changes.filterIsInstance<SpatialChange>())
        assertEquals(listOf(actor, buyer), plan.changes.filterIsInstance<InventoryChange>().map { it.subject })
        assertTrue(plan.claims.contains(WorldResourceClaim(phase64MechanicalResource(actor, "STAMINA"), 2)))
    }

    @Test fun explicitNpcActivationPolicySurvivesCanonicalImportWithoutCreatingADefaultPolicy() {
        val registered = policy("DELIVER").copy(npcActivationPolicyUid = "WORLD:NPC:DELIVERY_POLICY")
        val rule = Phase64EconomyRuleCatalog.delivery(registered, body, listOf(raw), buyer, route)
        assertEquals(registered.npcActivationPolicyUid, rule.parameters["activation_policy_uid"])
        assertEquals("true", rule.parameters["activation_npc"])
        assertEquals("false", rule.parameters[Phase64ProcessActivation.PUBLIC_KEY])
        val imported = Phase64BackgroundCodec.readDefinition(Phase64BackgroundCodec.definition(rule))
        assertEquals(rule, imported)
        assertEquals(registered.npcActivationPolicyUid, imported.parameters["activation_policy_uid"])
        assertFalse(Phase64EconomyRuleCatalog.delivery(policy("DEFAULT"), body, listOf(raw), buyer, route)
            .parameters.containsKey("activation_policy_uid"))
        assertTrue(Phase64EconomyRuleCatalog.coreDefinitions().none { it.parameters.containsKey("activation_policy_uid") })
        val playerOnly = Phase64EconomyRuleCatalog.consumption(policy("PLAYER", player = true, npc = false))
        assertFalse(playerOnly.parameters.containsKey("activation_policy_uid"))
    }

    @Test fun npcActivationPolicyMustBeAnExplicitFixedUidForAnNpcEnabledAction() {
        listOf("", " ", "@INFERRED_POLICY", "POLICY|OTHER", "P".repeat(161)).forEach { uid ->
            assertThrows(IllegalArgumentException::class.java) {
                policy("DELIVER").copy(npcActivationPolicyUid = uid)
            }
        }
        val failure = assertThrows(IllegalArgumentException::class.java) {
            policy("PLAYER", player = true, npc = false).copy(npcActivationPolicyUid = "WORLD:NPC:POLICY")
        }
        assertEquals("P64:NPC_ACTIVATION_POLICY_REQUIRES_NPC", failure.message)
    }

    @Test fun missingOrInventedOwnerContractsCannotBecomeCatalogActions() {
        fun rejects(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }
        rejects { Phase64EconomyRuleCatalog.production(policy("BAD"), actor, recipe, listOf(raw), emptyList()) }
        rejects { Phase64EconomyRuleCatalog.production(policy("BAD"), actor, recipe, listOf(raw.copy(itemDefinitionUid = "INVENTED")), listOf(finished)) }
        rejects { Phase64RegisteredPricePolicy(Phase64PricePolicy.FIXED_V1, 2, 1, 0, "WORLD:PRICE") }
        rejects { Phase64RegisteredPricePolicy(Phase64PricePolicy.FIXED_V1, 1, -1, 0, "WORLD:PRICE") }
        rejects { settlement.copy(payer = payer.copy(closedAt = 1)) }
        rejects { settlement.copy(currency = currency.copy(status = "RETIRED")) }
        rejects { settlement.copy(type = transfer.copy(flowKind = FinancialFlowKind.SOURCE)) }
        rejects { Phase64EconomyRuleCatalog.delivery(policy("BAD", player = true), body, listOf(raw), buyer, route) }
        rejects { Phase64EconomyRuleCatalog.delivery(policy("BAD"), body.copy(kind = MechanicalActorKind.ACTIVE_PLAYER), listOf(raw), buyer, route) }
        val consume = Phase64EconomyRuleCatalog.coreDefinitions().single { it.operation == Phase64EconomyOperations.CONSUME }
        rejects { bound(consume, actor, DomainRef("NPC", raw.itemInstanceUid)) }
        val production = Phase64EconomyRuleCatalog.production(policy("PRODUCE"), actor, recipe, listOf(raw), listOf(finished))
        rejects { bound(production, buyer, buyer) }
    }

    @Test fun completionCatalogRequiresRealEarlierWorkAndTheDeclaredExistingOutputContract() {
        val base = project(PROJECT_TYPE_RESEARCH)
        val snapshot = base.copy(project = base.project.copy(intendedOutputKindUid = PROJECT_OUTPUT_ITEM_INSTANCE,
            targetKindUid = "ITEM_INSTANCE", targetUid = finished.instance.itemInstanceUid),
            progress = base.progress.copy(status = ProjectStatus.READY_TO_COMPLETE, progressUnits = 2))
        val work = ProjectWorkRecord("C", "WORK", "PROJECT", "REGISTERED_RESULT", OwnershipOwnerRef(actor.kindUid, actor.uid),
            1, ProjectWorkResult.SUCCESS, progressDeltaUnits = 2, provenance = "WORLD:WORK")
        val evidence = Phase64ProjectResultEvidence(work, "WORK_EVENT")
        val output = DomainRef("ITEM_INSTANCE", finished.instance.itemInstanceUid)
        val rule = Phase64EconomyRuleCatalog.projectCompletion(policy("COMPLETE"), actor, snapshot, evidence, output)
        val values = bound(rule, actor, DomainRef("PROJECT", "PROJECT")) + mapOf("ruleUid" to rule.uid, "ruleVersion" to "1")
        val result = preparePhase64ProjectCompletion(values, scope, snapshot, evidence, true)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        assertEquals(output, result.changes.filterIsInstance<DevelopmentProjectCompletionChange>().single().outputRef)
        assertTrue(result.changes.none { it is SkillChange || it is TechniqueChange })
        assertThrows(IllegalArgumentException::class.java) {
            Phase64EconomyRuleCatalog.projectCompletion(policy("BAD"), actor, snapshot,
                evidence.copy(work = work.copy(result = ProjectWorkResult.FAILURE)), output)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Phase64EconomyRuleCatalog.projectCompletion(policy("BAD"), actor, snapshot, evidence, DomainRef("ITEM_INSTANCE", "INVENTED"))
        }
    }
}
