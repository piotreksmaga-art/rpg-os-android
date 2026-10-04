package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Captured implementation of the specialized economy owners. Recipe counts/provenance are
 * read back from the persisted rule definition, never accepted from process/model overrides.
 * Count parameters use definitionUid=count entries separated by '|'. A new universal output
 * additionally needs productionObjectUids in that rule and a real existing OBJECT world fact.
 * Delivery records routeEdgeUids in dispatch order and the exact WorldTravelPlan fingerprint. */
internal fun preparePhase64EconomyOwnedEffect(
    db: SQLiteDatabase, campaign: String, operation: String, actor: DomainRef,
    parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
    staged: List<PlayerDomainChangePayload>
): WorldConsequencePlan {
    if (campaign != scope.temporal.campaignUid) return backgroundBlocked("P64:ECONOMY_CAPTURE_SCOPE_MISMATCH")
    return try {
        when (operation) {
            Phase64EconomyOperations.OWNED_BUILD_CHECK, Phase64EconomyOperations.OWNED_REPAIR_CHECK,
            Phase64EconomyOperations.OWNED_RESEARCH_CHECK -> {
                val snapshot = capturePhase64ProjectSnapshot(db, campaign, backgroundRequired(parameters, "projectUid"))
                if (staged.filterIsInstance<DevelopmentProjectCompletionChange>().any { it.projectUid == parameters["projectUid"] })
                    backgroundBlocked("P64:PROJECT_ALREADY_COMPLETED_IN_TURN") else
                    Phase64EconomyOwnerRules.projectWork(operation, parameters + mapOf("workerKind" to actor.kindUid, "workerUid" to actor.uid),
                        scope, snapshot, staged)
            }
            Phase64EconomyOperations.OWNED_COMPLETION -> {
                val uid = backgroundRequired(parameters, "projectUid")
                if (staged.filterIsInstance<DevelopmentProjectCompletionChange>().any { it.projectUid == uid })
                    return backgroundBlocked("P64:PROJECT_ALREADY_COMPLETED_IN_TURN")
                if (staged.filterIsInstance<BackgroundProjectWorkChange>().any { it.projectUid == uid && it.readyToComplete })
                    return backgroundBlocked("P64:PROJECT_READY_COMMIT_REQUIRED")
                val canonical = capturePhase64ProjectSnapshot(db, campaign, uid)
                val progress = staged.fold(canonical?.progress?.progressUnits ?: 0) { total, change ->
                    val delta = when (change) {
                        is BackgroundProjectWorkChange -> if (change.projectUid == uid) change.progressUnits else 0
                        is DevelopmentProjectChange -> if (change.projectUid == uid) change.progressDelta.units else 0
                        else -> 0
                    }
                    Math.addExact(total, delta)
                }
                val snapshot = canonical?.copy(progress = canonical.progress.copy(progressUnits = progress))
                val result = capturePhase64ProjectResult(db, campaign, uid, backgroundRequired(parameters, "resultEvidenceUid"))
                val kind = backgroundRequired(parameters, "outputKindUid")
                val ref = DomainRef(backgroundRequired(parameters, "outputRefKindUid"), backgroundRequired(parameters, "outputUid"))
                preparePhase64ProjectCompletion(parameters, scope, snapshot, result, phase64ProjectOutputExists(db, campaign, kind, ref))
            }
            Phase64EconomyOperations.OWNED_PRODUCTION -> prepareCapturedProduction(db, campaign, actor, parameters, scope, staged)
            Phase64EconomyOperations.OWNED_DELIVERY -> prepareCapturedDelivery(db, campaign, actor, parameters, scope, staged)
            Phase64EconomyOperations.OWNED_SETTLEMENT -> prepareCapturedSettlement(db, campaign, parameters, scope)
            else -> backgroundBlocked("P64:ECONOMY_OWNER_OPERATION_UNAVAILABLE")
        }
    } catch (_: IllegalArgumentException) {
        backgroundBlocked("P64:ECONOMY_OWNER_PARAMETERS_INVALID")
    } catch (_: ArithmeticException) {
        backgroundBlocked("P64:ECONOMY_OWNER_AMOUNT_OVERFLOW")
    }
}

private fun prepareCapturedSettlement(db: SQLiteDatabase, campaign: String, parameters: Map<String, String>,
    scope: BackgroundProcessEvaluationScope): WorldConsequencePlan {
    val rule = Phase64BackgroundStore(db, campaign).definition(backgroundRequired(parameters, "ruleUid"),
        backgroundRequired(parameters, "ruleVersion").toInt()) ?: return backgroundBlocked("P64:SETTLEMENT_RULE_UNAVAILABLE")
    val fixed = rule.parameters
    val keys = listOf("payerAccountUid", "payeeAccountUid", "currencyUid", "transactionTypeUid",
        "payerAccountVersion", "payeeAccountVersion", "payerHolderKind", "payerHolderUid", "payeeHolderKind", "payeeHolderUid")
    if (keys.any { parameters[it] != fixed[it] }) return backgroundBlocked("P64:SETTLEMENT_RULE_CHANGED")
    val amount = if (rule.operation == Phase64EconomyOperations.TRADE) {
        if (fixed["pricePolicyVersion"]?.let { it != "1" } == true) return backgroundBlocked("P64:SETTLEMENT_PRICE_VERSION_UNAVAILABLE")
        Phase64PricePolicy.quote(backgroundRequired(fixed, "pricePolicyUid"), backgroundPositive(fixed, "unitPriceMinor"),
            p64EconomyItems(parameters, "inputItemUids").size.toLong(), fixed["priceAdjustmentBasisPoints"]?.toLong() ?: 0)
    } else backgroundPositive(fixed, "amountMinor")
    if (parameters["amountMinor"]?.toLongOrNull() != amount) return backgroundBlocked("P64:SETTLEMENT_AMOUNT_CHANGED")
    fun account(key: String): FinancialAccount? = db.rawQuery(
        "SELECT account_uid,holder_kind_uid,holder_uid,account_type_uid,currency_uid,opened_order,closed_order,account_version,provenance FROM financial_accounts WHERE campaign_id=? AND account_uid=?",
        arrayOf(campaign, backgroundRequired(parameters, key))).use { c ->
        if (!c.moveToFirst()) null else FinancialAccount(campaign, c.getString(0), OwnershipOwnerRef(c.getString(1), c.getString(2)),
            c.getString(3), c.getString(4), c.getLong(5), c.getString(8), if (c.isNull(6)) null else c.getLong(6), c.getLong(7))
    }
    val currency = db.rawQuery("SELECT currency_uid,currency_key,display_name,minor_unit_scale,provenance,definition_status FROM currency_definitions WHERE currency_uid=?",
        arrayOf(backgroundRequired(parameters, "currencyUid"))).use { c ->
        if (!c.moveToFirst()) null else CurrencyDefinition(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4), c.getString(5))
    }
    val type = db.rawQuery("SELECT transaction_type_uid,flow_kind,type_status,provenance FROM financial_transaction_type_definitions WHERE transaction_type_uid=?",
        arrayOf(backgroundRequired(parameters, "transactionTypeUid"))).use { c ->
        if (!c.moveToFirst()) null else Phase64SettlementType(c.getString(0), FinancialFlowKind.valueOf(c.getString(1)), c.getString(2), c.getString(3))
    }
    val result = Phase64EconomyOwnerRules.settlement(parameters, scope, account("payerAccountUid"), account("payeeAccountUid"), currency, type)
    return result.copy(sourceUids = (result.sourceUids + listOfNotNull(fixed["settlementPolicyUid"], fixed["settlementProvenanceUid"],
        fixed["pricePolicyUid"], fixed["priceProvenanceUid"])).distinct())
}

private fun prepareCapturedProduction(db: SQLiteDatabase, campaign: String, actor: DomainRef,
    parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>): WorldConsequencePlan {
    val uid = backgroundRequired(parameters, "ruleUid")
    val version = backgroundRequired(parameters, "ruleVersion").toInt()
    val rule = Phase64BackgroundStore(db, campaign).definition(uid, version)
        ?: return backgroundBlocked("P64:PRODUCTION_RULE_UNAVAILABLE")
    if (rule.domain != "ECONOMY" || rule.operation != Phase64EconomyOperations.PRODUCE)
        return backgroundBlocked("P64:PRODUCTION_RULE_MISMATCH")
    val registered = rule.parameters
    fun counts(key: String): Map<String, Long> {
        val entries = backgroundRequired(registered, key).split('|').map { entry ->
            val split = entry.lastIndexOf('=')
            require(split > 0)
            entry.substring(0, split) to entry.substring(split + 1).toLong().also { require(it > 0) }
        }
        require(entries.size in 1..32 && entries.map { it.first }.distinct().size == entries.size)
        return entries.toMap()
    }
    val recipe = Phase64ProductionRecipe(backgroundRequired(registered, "recipeUid"), backgroundRequired(registered, "recipeVersion").toInt(),
        counts("recipeInputDefinitionCounts"), counts("recipeOutputDefinitionCounts"), backgroundRequired(registered, "recipeProvenanceUid"))
    fun instance(itemUid: String): ItemInstance? = db.rawQuery(
        "SELECT item_definition_uid,instance_version,provenance FROM item_instances WHERE campaign_id=? AND item_instance_uid=?",
        arrayOf(campaign, itemUid)).use { c -> if (!c.moveToFirst()) null else ItemInstance(campaign, itemUid, c.getString(0), c.getLong(1), c.getString(2)) }
    fun definition(itemDefinitionUid: String): ItemDefinition? = db.rawQuery(
        "SELECT world_pack_uid,item_key,display_name,category,storage_policy,definition_status,definition_version,provenance FROM item_definitions_v2 WHERE item_definition_uid=?",
        arrayOf(itemDefinitionUid)).use { c -> if (!c.moveToFirst()) null else ItemDefinition(itemDefinitionUid, c.getString(0), c.getString(1), c.getString(2),
            if (c.isNull(3)) null else c.getString(3), ItemStoragePolicy.valueOf(c.getString(4)), ItemDefinitionStatus.valueOf(c.getString(5)), c.getLong(6), c.getString(7)) }
    val inputUids = p64EconomyItems(parameters, "inputItemUids")
    val outputUids = p64EconomyItems(parameters, "outputItemUids")
    val inputs = inputUids.map { itemUid -> instance(itemUid) ?: return backgroundBlocked("P64:PRODUCTION_INPUT_UNAVAILABLE") }
    val legalObjects = registered["productionObjectUids"]?.split('|')?.toSet() ?: emptySet()
    val outputs = outputUids.map { itemUid ->
        val held = db.rawQuery("SELECT COUNT(*) FROM player_inventory_unique WHERE campaign_id=? AND item_instance_uid=?",
            arrayOf(campaign, itemUid)).use { c -> check(c.moveToFirst()); c.getLong(0) }
        val stagedHeld = staged.filterIsInstance<InventoryChange>().filter { it.itemInstanceUid == itemUid }
            .fold(held) { total, change -> Math.addExact(total, change.quantityDelta.units) }
        if (stagedHeld != 0L) return backgroundBlocked("P64:PRODUCTION_OUTPUT_ALREADY_HELD")
        val current = instance(itemUid)
        if (current != null) {
            val itemDefinition = definition(current.itemDefinitionUid) ?: return backgroundBlocked("P64:PRODUCTION_OUTPUT_DEFINITION_UNAVAILABLE")
            Phase64ProductionOutput(current, itemDefinition)
        } else {
            if (itemUid !in legalObjects) return backgroundBlocked("P64:PRODUCTION_GENESIS_UNPROVEN")
            val exists = db.rawQuery("SELECT 1 FROM campaign_truth_records WHERE campaign_id=? AND subject_uid=? AND predicate=? AND object_value='OBJECT' AND truth_kind='FACT' AND active=1 LIMIT 1",
                arrayOf(campaign, itemUid, CampaignWorldFacts.KIND)).use { it.moveToFirst() }
            if (!exists) return backgroundBlocked("P64:PRODUCTION_SOURCE_OBJECT_UNAVAILABLE")
            val universal = definition(UNIVERSAL_WORLD_OBJECT_ITEM_DEFINITION_UID)
                ?: return backgroundBlocked("P64:PRODUCTION_OUTPUT_DEFINITION_UNAVAILABLE")
            Phase64ProductionOutput(ItemInstance(campaign, itemUid, universal.itemDefinitionUid, provenance = recipe.provenanceUid), universal,
                DomainRef("OBJECT", itemUid), universalInventoryItemMaterialization())
        }
    }
    // Preserve authoritative recipe identity even if an instance tried to override it.
    return Phase64EconomyOwnerRules.production(actor, parameters + mapOf("recipeUid" to recipe.uid, "recipeVersion" to recipe.version.toString()),
        scope, recipe, inputs, outputs)
}

/** Bounded exact-edge capture, including actor-scoped imported Phase62 edges. Every edge also
 * needs legal route knowledge through the existing Phase37/38 protected reader. */
internal fun phase64CapturedDeliveryRoute(db: SQLiteDatabase, campaign: String, actor: DomainRef,
    parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
    staged: List<PlayerDomainChangePayload>): WorldTravelPlan? {
    val canonical = MechanicalActorStateStore(db, campaign).actor(actor) ?: return null
    val origin = phase64DeliveryOrigin(canonical, staged) ?: return null
    val destination = DomainRef(backgroundRequired(parameters, "destinationKind"), backgroundRequired(parameters, "destinationUid"))
    val edgeUids = p64EconomyItems(parameters, "routeEdgeUids")
    val holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, actor.uid, campaign)
    val audience = AudienceContext(campaign, AudienceKinds.WORLD_ACTOR, VisibilityPrincipalRef(actor.kindUid, actor.uid))
    val authority = UniversalAccessAuthority(AccessAuthorityStore(db, campaign))
    val trusted = authority.trustedContext(audience, scope.temporal.baseCommitOrder)?.copy(cognitionHolders = setOf(holder)) ?: return null
    val reader = ProtectedCampaignReadRepository.borrowedTrusted(db, campaign, { null }, trusted)
    val purpose = PurposeContext(campaign, VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
    var current = origin
    val edges = edgeUids.map { edgeUid ->
        val edge = db.rawQuery("SELECT edge_canonical,edge_fingerprint FROM ${Phase63WorldSchema.EDGES} WHERE campaign_uid=? AND edge_uid=?",
            arrayOf(campaign, edgeUid)).use { c ->
            if (!c.moveToFirst()) null else Phase63WorldCodec.readEdge(Json.parseToJsonElement(c.getString(0)).jsonObject).also {
                require(it.fingerprint == c.getString(1))
            }
        } ?: SqliteNpcTravelRoutePort(db).routes(campaign, actor, current).singleOrNull { it.routeUid == edgeUid }?.let { route ->
            WorldTopologyEdge(route.routeUid, route.version.toLong(), route.origin, route.destination, route.duration,
                route.resourceCosts.filterValues { it > 0 }, route.requiredCapabilities, WorldTimeTick(Long.MIN_VALUE), null, "P62:ROUTE:${route.fingerprint}")
        } ?: return null
        if (!WorldTopologyAnchor.same(edge.origin, current)) return null
        val required = WorldRouteKnowledge.claimUid(edge)
        val known = reader.npcRequiredClaims(audience, purpose, holder, scope.temporal.baseCommitOrder, setOf(required))
        var permitted = required in ((known as? ProtectedReadResult.Allow)?.value ?: emptySet())
        if (!permitted && edge.provenanceUid.startsWith("P62:ROUTE:")) {
            val records = reader.npcKnowledge(audience, purpose, holder, scope.temporal.baseCommitOrder, 64)
            permitted = (records as? ProtectedReadResult.Allow)?.value.orEmpty().any { record -> record.subjectRefs.any {
                (it.kindUid == "WORLD_ROUTE" && it.uid == edge.uid) || WorldTopologyAnchor.same(it, edge.destination)
            } }
        }
        if (!permitted) return null
        current = edge.destination
        edge
    }
    return WorldTravelPlan(origin, destination, edges)
}

private fun prepareCapturedDelivery(db: SQLiteDatabase, campaign: String, actor: DomainRef,
    parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>): WorldConsequencePlan {
    val canonical = MechanicalActorStateStore(db, campaign).actor(actor)
    val route = phase64CapturedDeliveryRoute(db, campaign, actor, parameters, scope, staged)
    return Phase64EconomyOwnerRules.delivery(actor, parameters, scope, canonical, route, staged)
}

private fun p64EconomyItems(parameters: Map<String, String>, key: String) = backgroundRequired(parameters, key).split('|').also {
    require(it.size in 1..32 && it.distinct().size == it.size && it.none(String::isBlank))
}
