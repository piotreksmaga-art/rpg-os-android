package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

internal const val PHASE64_COHORT_BIRTH_KIND = "RPGOS-CHANGE:POPULATION_COHORT_BIRTH"
internal const val PHASE64_FORMATION_MOBILIZATION_KIND = "RPGOS-CHANGE:FORMATION_MOBILIZATION"

/** A new fixed cohort, never a resize or regeneration of the parent's named slots. The
 * mechanical profile is derived by Core; this payload cannot carry invented statistics. */
data class PopulationCohortBirthChange internal constructor(
    val campaignUid: String,
    val historyGenerationUid: String,
    val sourceManifestUid: String,
    val source: DomainRef,
    val expectedSourceVersion: Long,
    val cohort: WorldPopulationManifest,
    val origin: DomainRef,
    val name: String,
    val ruleUid: String,
    val ruleVersion: Int,
    val processUid: String,
    val logicalEventUid: String,
    val profileUid: String = Phase64DemographyPreparation.NEWBORN_PROFILE
) : PlayerDomainChangePayload {
    init {
        require(listOf(campaignUid, historyGenerationUid, sourceManifestUid, name, ruleUid, processUid, logicalEventUid).none(String::isBlank))
        require(source.kindUid in setOf("GROUP", "UNIT") && cohort.aggregate.kindUid == "GROUP" && source != cohort.aggregate)
        require(expectedSourceVersion > 0 && ruleVersion > 0 && name.length <= 160)
        require(origin.kindUid in setOf("PLACE", "LOCATION") && profileUid == Phase64DemographyPreparation.NEWBORN_PROFILE)
        require(cohort.aggregate == Phase64DemographyPreparation.cohortRef(logicalEventUid))
    }
}

/** Headcount is a commitment against the existing unit's body, not a new population pool.
 * Version one mobilizes the entire active anonymous formation and creates no new bodies. */
data class FormationMobilizationChange internal constructor(
    val campaignUid: String,
    val historyGenerationUid: String,
    val formation: DomainRef,
    val expectedBodyVersion: Long,
    val mobilizedCount: Long,
    val sourceManifestUid: String,
    val ruleUid: String,
    val ruleVersion: Int,
    val processUid: String,
    val logicalEventUid: String
) : PlayerDomainChangePayload {
    init {
        require(listOf(campaignUid, historyGenerationUid, sourceManifestUid, ruleUid, processUid, logicalEventUid).none(String::isBlank))
        require(formation.kindUid == "UNIT" && expectedBodyVersion > 0 && mobilizedCount > 0 && ruleVersion > 0)
    }
}

internal object Phase64DemographyCodec {
    fun birth(p: PopulationCohortBirthChange) = buildJsonObject {
        put("campaign", p.campaignUid); put("generation", p.historyGenerationUid); put("source_manifest", p.sourceManifestUid)
        put("source", Phase63WorldCodec.ref(p.source)); put("expected_source", p.expectedSourceVersion)
        put("cohort", Phase63PopulationCodec.manifest(p.cohort)); put("origin", Phase63WorldCodec.ref(p.origin)); put("name", p.name)
        put("rule", p.ruleUid); put("rule_version", p.ruleVersion); put("process", p.processUid); put("event", p.logicalEventUid); put("profile", p.profileUid)
    }
    fun readBirth(o: JsonObject): PopulationCohortBirthChange {
        Phase63WorldCodec.keys(o, "campaign", "generation", "source_manifest", "source", "expected_source", "cohort", "origin", "name", "rule", "rule_version", "process", "event", "profile")
        return PopulationCohortBirthChange(text(o, "campaign"), text(o, "generation"), text(o, "source_manifest"),
            Phase63WorldCodec.readRef(o.getValue("source")), number(o, "expected_source"), Phase63PopulationCodec.readManifest(o.getValue("cohort").jsonObject),
            Phase63WorldCodec.readRef(o.getValue("origin")), text(o, "name"), text(o, "rule"), Math.toIntExact(number(o, "rule_version")),
            text(o, "process"), text(o, "event"), text(o, "profile"))
    }
    fun mobilization(p: FormationMobilizationChange) = buildJsonObject {
        put("campaign", p.campaignUid); put("generation", p.historyGenerationUid); put("formation", Phase63WorldCodec.ref(p.formation))
        put("expected_body", p.expectedBodyVersion); put("count", p.mobilizedCount); put("source_manifest", p.sourceManifestUid)
        put("rule", p.ruleUid); put("rule_version", p.ruleVersion); put("process", p.processUid); put("event", p.logicalEventUid)
    }
    fun readMobilization(o: JsonObject): FormationMobilizationChange {
        Phase63WorldCodec.keys(o, "campaign", "generation", "formation", "expected_body", "count", "source_manifest", "rule", "rule_version", "process", "event")
        return FormationMobilizationChange(text(o, "campaign"), text(o, "generation"), Phase63WorldCodec.readRef(o.getValue("formation")),
            number(o, "expected_body"), number(o, "count"), text(o, "source_manifest"), text(o, "rule"), Math.toIntExact(number(o, "rule_version")),
            text(o, "process"), text(o, "event"))
    }
    private fun text(o: JsonObject, key: String) = Phase63WorldCodec.text(o, key)
    private fun number(o: JsonObject, key: String) = Phase63WorldCodec.number(o, key)
}

internal fun phase64CohortBirthCodec() = object : TypedPlayerChangeCodec<PopulationCohortBirthChange>(PopulationCohortBirthChange::class,
    ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
    setOf("campaign", "generation", "source_manifest", "source", "expected_source", "cohort", "origin", "name", "rule", "rule_version", "process", "event", "profile")) {
    override fun encode(payload: PopulationCohortBirthChange) = Phase64DemographyCodec.birth(payload)
    override fun decodeKnownFields(obj: JsonObject) = Phase64DemographyCodec.readBirth(obj)
    override fun conflictKeys(payload: PopulationCohortBirthChange) = setOf("P64:COHORT:${payload.campaignUid}:${payload.cohort.aggregate.uid}", "P64:DEMOGRAPHIC_EVENT:${payload.campaignUid}:${payload.logicalEventUid}")
}

internal fun phase64FormationMobilizationCodec() = object : TypedPlayerChangeCodec<FormationMobilizationChange>(FormationMobilizationChange::class,
    ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
    setOf("campaign", "generation", "formation", "expected_body", "count", "source_manifest", "rule", "rule_version", "process", "event")) {
    override fun encode(payload: FormationMobilizationChange) = Phase64DemographyCodec.mobilization(payload)
    override fun decodeKnownFields(obj: JsonObject) = Phase64DemographyCodec.readMobilization(obj)
    override fun conflictKeys(payload: FormationMobilizationChange) = setOf("P64:MOBILIZATION:${payload.campaignUid}:${payload.formation.uid}", "P64:DEMOGRAPHIC_EVENT:${payload.campaignUid}:${payload.logicalEventUid}")
}

internal object Phase64DemographySchema {
    const val COHORT_LINEAGE = "phase64_population_cohort_lineage"
    const val MOBILIZATIONS = "phase64_formation_mobilizations"
    val tables = setOf(COHORT_LINEAGE, MOBILIZATIONS)
    fun ensureReady(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $COHORT_LINEAGE(campaign_uid TEXT NOT NULL,event_uid TEXT NOT NULL,
            source_manifest_uid TEXT NOT NULL,cohort_manifest_uid TEXT NOT NULL,process_uid TEXT NOT NULL,
            canonical TEXT NOT NULL,created_order INTEGER NOT NULL CHECK(created_order>0),
            PRIMARY KEY(campaign_uid,event_uid),UNIQUE(campaign_uid,cohort_manifest_uid))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p64_cohort_parent ON $COHORT_LINEAGE(campaign_uid,source_manifest_uid,cohort_manifest_uid)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $MOBILIZATIONS(campaign_uid TEXT NOT NULL,formation_uid TEXT NOT NULL,
            event_uid TEXT NOT NULL,mobilized_count INTEGER NOT NULL CHECK(mobilized_count>0),status TEXT NOT NULL CHECK(status='ACTIVE'),
            canonical TEXT NOT NULL,created_order INTEGER NOT NULL CHECK(created_order>0),
            PRIMARY KEY(campaign_uid,formation_uid),UNIQUE(campaign_uid,event_uid))""")
    }
    fun isReady(db: SQLiteDatabase) = tables.all { table ->
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
    }
}

/** Preparation consumes captured views only. The registered newborn profile deliberately
 * carries partial mechanics; birth never grants adult combat abilities or learned skills. */
internal object Phase64DemographyPreparation {
    const val NEWBORN_PROFILE = "P64:NEWBORN_COHORT:1"
    fun cohortRef(event: String) = DomainRef("GROUP", "P64-COHORT-${phase63Hash(event).take(32).uppercase()}")

    fun birth(source: MechanicalActorView, manifest: WorldPopulationManifest, skeleton: CampaignWorldSkeleton,
        definition: BackgroundProcessDefinition, parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope): WorldConsequencePlan {
        if (definition.domain != "POPULATION" || definition.operation != "BIRTH" ||
            parameters["cohort_rule_uid"] != NEWBORN_PROFILE || parameters["cohort_rule_version"] != "1")
            return backgroundBlocked("P64:COHORT_OWNER_RULE_REQUIRED")
        if (source.actor != manifest.aggregate || source.campaignUid != scope.temporal.campaignUid || skeleton.campaignUid != source.campaignUid)
            return backgroundBlocked("P64:COHORT_SOURCE_MISMATCH")
        val count = parameters["count"]?.toLongOrNull()?.takeIf { it in 1..1_000_000 }
            ?: return backgroundBlocked("P64:COHORT_COUNT_REQUIRED")
        val cap = definition.parameters["max_birth_count"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: definition.parameters["count"]?.toLongOrNull()?.takeIf { it > 0 }
            ?: return backgroundBlocked("P64:COHORT_COUNT_POLICY_REQUIRED")
        if (count > cap) return backgroundBlocked("P64:COHORT_COUNT_POLICY_EXCEEDED")
        val event = parameters["p64_logical_event_uid"]?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:LOGICAL_EVENT_REQUIRED")
        val process = parameters["p64_process_uid"]?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:PROCESS_REQUIRED")
        val origin = source.locationRef ?: return backgroundBlocked("P64:COHORT_ORIGIN_REQUIRED")
        val population = source.aggregatePopulation ?: return backgroundBlocked("P64:AGGREGATE_POPULATION_REQUIRED")
        if (population.activeCount == 0L && population.woundedCount == 0L) return backgroundBlocked("P64:COHORT_SOURCE_UNAVAILABLE")
        val ref = cohortRef(event)
        val cohort = WorldPopulationManifest(ref, count, skeleton.domainSeed("POPULATION", ref.toString()))
        val payload = PopulationCohortBirthChange(source.campaignUid, scope.temporal.historyGenerationUid, manifest.uid, source.actor,
            source.stateVersion, cohort, origin, "Nowa kohorta", definition.uid, definition.version, process, event)
        val facts = listOf(CampaignWorldFacts.KIND to "GROUP", CampaignWorldFacts.NAME to payload.name, CampaignWorldFacts.PARENT to origin.uid)
            .map { (predicate, value) -> CampaignTruthChange("P64:COHORT-FACT:${phase63Hash("$event|$predicate")}", TruthKind.FACT,
                ref.uid, predicate, value, null, null, null) }
        return WorldConsequencePlan(changes = listOf(payload) + facts, sourceUids = listOf(definition.uid, manifest.uid, event, NEWBORN_PROFILE))
    }

    fun mobilize(formation: MechanicalActorView, manifest: WorldPopulationManifest, definition: BackgroundProcessDefinition,
        parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope, alreadyMobilized: Boolean,
        staged: List<PlayerDomainChangePayload> = emptyList()): WorldConsequencePlan {
        if (definition.domain != "CONFLICT" || definition.operation != "MOBILIZE") return backgroundBlocked("P64:MOBILIZATION_RULE_REQUIRED")
        if (formation.actor.kindUid != "UNIT" || formation.actor != manifest.aggregate || formation.campaignUid != scope.temporal.campaignUid)
            return backgroundBlocked("P64:WHOLE_EXISTING_FORMATION_REQUIRED")
        val count = parameters["count"]?.toLongOrNull()?.takeIf { it > 0 } ?: return backgroundBlocked("P64:MOBILIZATION_COUNT_REQUIRED")
        val population = formation.aggregatePopulation ?: return backgroundBlocked("P64:AGGREGATE_POPULATION_REQUIRED")
        if (count != population.activeCount) return backgroundBlocked("P64:PARTIAL_MOBILIZATION_OWNER_REQUIRED")
        if (alreadyMobilized || staged.filterIsInstance<FormationMobilizationChange>().any { it.formation == formation.actor })
            return backgroundBlocked("P64:FORMATION_ALREADY_MOBILIZED")
        if (staged.filterIsInstance<AggregatePopulationChange>().any { it.subject == formation.actor && (it.eliminatedDelta > 0 || it.woundedDelta > 0) })
            return backgroundBlocked("P64:MOBILIZATION_BODY_CHANGED")
        val event = parameters["p64_logical_event_uid"]?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:LOGICAL_EVENT_REQUIRED")
        val process = parameters["p64_process_uid"]?.takeIf(String::isNotBlank) ?: return backgroundBlocked("P64:PROCESS_REQUIRED")
        val payload = FormationMobilizationChange(formation.campaignUid, scope.temporal.historyGenerationUid, formation.actor,
            formation.stateVersion, count, manifest.uid, definition.uid, definition.version, process, event)
        return WorldConsequencePlan(changes = listOf(payload), claims = listOf(WorldResourceClaim(formation.actor, count)),
            sourceUids = listOf(definition.uid, manifest.uid, event, formation.generationProvenanceUid))
    }

    fun seed(payload: PopulationCohortBirthChange, skeleton: CampaignWorldSkeleton): MechanicalActorSeed =
        Phase63ActorGeneration.seed(payload.cohort.aggregate, skeleton.domainSeed("MECHANICS", "P64:COHORT:${payload.logicalEventUid}"),
            MechanicalStateMaterialization.PARTIAL).copy(kind = MechanicalActorKind.GROUP, templateUid = NEWBORN_PROFILE,
            provenanceUid = "$NEWBORN_PROFILE:${payload.logicalEventUid}", aggregateName = payload.name, aggregateCount = payload.cohort.originalCount)
}

/** Only TurnTransaction invokes these writers. The existing population and mechanical
 * owners perform body/manifest creation; this owner stores lineage and commitments only. */
internal class Phase64PopulationOwnerStore(private val db: SQLiteDatabase, private val campaign: String) {
    fun mobilized(formation: DomainRef): Boolean {
        if (formation.kindUid != "UNIT" || !Phase64DemographySchema.isReady(db)) return false
        return db.rawQuery("SELECT 1 FROM ${Phase64DemographySchema.MOBILIZATIONS} WHERE campaign_uid=? AND formation_uid=? AND status='ACTIVE'",
            arrayOf(campaign, formation.uid)).use { it.moveToFirst() }
    }

    fun applyBirth(identity: TurnTransactionIdentity, set: PlayerChangeSet, p: PopulationCohortBirthChange, order: Long) {
        requireTurn(identity, p.campaignUid, p.historyGenerationUid)
        val definition = rule(set, p.processUid, p.logicalEventUid, p.ruleUid, p.ruleVersion, "POPULATION", "BIRTH", p.source)
        require(definition.parameters["cohort_rule_uid"] == Phase64DemographyPreparation.NEWBORN_PROFILE &&
            definition.parameters["cohort_rule_version"] == "1") { "P64:COHORT_PROFILE_NOT_REGISTERED" }
        val cap = (definition.parameters["max_birth_count"] ?: definition.parameters["count"])?.toLongOrNull()
            ?: error("P64:COHORT_COUNT_POLICY_REQUIRED")
        require(p.cohort.originalCount <= cap) { "P64:COHORT_COUNT_POLICY_EXCEEDED" }
        val populations = WorldPopulationStore(db, campaign)
        val sourceManifest = populations.manifest(p.sourceManifestUid) ?: error("P64:COHORT_SOURCE_MANIFEST_REQUIRED")
        require(sourceManifest.aggregate == p.source) { "P64:COHORT_SOURCE_MISMATCH" }
        val mechanical = MechanicalActorStateStore(db, campaign)
        val source = mechanical.actor(p.source) ?: error("P64:COHORT_SOURCE_BODY_REQUIRED")
        require(source.stateVersion == p.expectedSourceVersion && WorldTopologyAnchor.same(requireNotNull(source.locationRef), p.origin)) { "P64:COHORT_SOURCE_CHANGED" }
        require(source.aggregatePopulation?.let { it.activeCount > 0 || it.woundedCount > 0 } == true) { "P64:COHORT_SOURCE_UNAVAILABLE" }
        if (Phase64PopulationRuleCatalog.matches(definition)) require(source.aggregatePopulation?.activeCount?.let { it > 0 } == true &&
            definition.parameters["required_subject_ability_uid"] in source.executableAbilityUids &&
            !source.generationProvenanceUid.startsWith(Phase64DemographyPreparation.NEWBORN_PROFILE)) { "P64:REPRODUCTIVE_COHORT_REQUIRED" }
        require(sourceManifest.originalCount - Phase50PopulationPartition.namedCount(db, campaign, p.source) == source.aggregatePopulation?.totalCount) { "P64:COHORT_SOURCE_LINEAGE_CHANGED" }
        val skeleton = Phase63WorldStore(db, campaign).root()?.skeleton ?: error("P64:COHORT_WORLD_REQUIRED")
        require(p.cohort.seed == skeleton.domainSeed("POPULATION", p.cohort.aggregate.toString())) { "P64:COHORT_SEED_MISMATCH" }
        require(mechanical.actor(p.cohort.aggregate) == null && populations.forAggregate(p.cohort.aggregate) == null) { "P64:COHORT_ALREADY_EXISTS" }
        require(!hasEvent(Phase64DemographySchema.COHORT_LINEAGE, p.logicalEventUid)) { "P64:COHORT_EVENT_ALREADY_SETTLED" }
        val facts = set.changes.mapNotNull { it.payload as? CampaignTruthChange }.filter { it.subjectUid == p.cohort.aggregate.uid && it.kind == TruthKind.FACT }
        require(listOf(CampaignWorldFacts.KIND to "GROUP", CampaignWorldFacts.NAME to p.name, CampaignWorldFacts.PARENT to p.origin.uid)
            .all { (predicate, value) -> facts.singleOrNull { it.predicate == predicate }?.objectValue == value }) { "P64:COHORT_WORLD_FACTS_REQUIRED" }
        mechanical.materializeIfMissing(Phase64DemographyPreparation.seed(p, skeleton))
        mechanical.applySpatial(identity, "${p.logicalEventUid}:ORIGIN", SpatialChange(p.cohort.aggregate, 0, destinationLocation = p.origin), order)
        populations.register(identity, p.cohort, order)
        db.execSQL("INSERT INTO ${Phase64DemographySchema.COHORT_LINEAGE} VALUES(?,?,?,?,?,?,?)",
            arrayOf(campaign, p.logicalEventUid, p.sourceManifestUid, p.cohort.uid, p.processUid, Phase64DemographyCodec.birth(p).toString(), order))
    }

    fun applyMobilization(identity: TurnTransactionIdentity, set: PlayerChangeSet, p: FormationMobilizationChange, order: Long) {
        requireTurn(identity, p.campaignUid, p.historyGenerationUid)
        val definition = rule(set, p.processUid, p.logicalEventUid, p.ruleUid, p.ruleVersion, "CONFLICT", "MOBILIZE", p.formation)
        val manifest = WorldPopulationStore(db, campaign).manifest(p.sourceManifestUid) ?: error("P64:FORMATION_MANIFEST_REQUIRED")
        require(manifest.aggregate == p.formation) { "P64:FORMATION_MANIFEST_MISMATCH" }
        val body = MechanicalActorStateStore(db, campaign).actor(p.formation) ?: error("P64:FORMATION_BODY_REQUIRED")
        require(body.stateVersion == p.expectedBodyVersion && body.aggregatePopulation?.activeCount == p.mobilizedCount) { "P64:MOBILIZATION_BODY_CHANGED" }
        require(manifest.originalCount - Phase50PopulationPartition.namedCount(db, campaign, p.formation) == body.aggregatePopulation?.totalCount) {
            "P64:FORMATION_LINEAGE_CHANGED"
        }
        require(!mobilized(p.formation) && !hasEvent(Phase64DemographySchema.MOBILIZATIONS, p.logicalEventUid)) { "P64:FORMATION_ALREADY_MOBILIZED" }
        if (Phase64PopulationRuleCatalog.matches(definition)) require(body.conditions.none {
            it.intensity > 0 && it.conditionUid in setOf("DEAD", "INCAPACITATED", "UNCONSCIOUS")
        }) { "P64:EXISTING_ACTIVE_FORMATION_REQUIRED" }
        db.execSQL("INSERT INTO ${Phase64DemographySchema.MOBILIZATIONS} VALUES(?,?,?,?,'ACTIVE',?,?)",
            arrayOf(campaign, p.formation.uid, p.logicalEventUid, p.mobilizedCount, Phase64DemographyCodec.mobilization(p).toString(), order))
    }

    private fun requireTurn(identity: TurnTransactionIdentity, scopedCampaign: String, generation: String) {
        require(db.inTransaction() && identity.campaignUid == campaign && scopedCampaign == campaign && Phase64DemographySchema.isReady(db)) { "P64:DEMOGRAPHY_OUTSIDE_TURN" }
        requireCanonicalGameplayMutation(db, campaign)
        // Live admission checks history generation. Verified replay after undo retains the
        // original payload generation and must restore that receipt through this same owner.
        require(generation.isNotBlank()) { "P64:HISTORY_SCOPE_REQUIRED" }
        require(Phase64BackgroundStore(db, campaign).policy() != null) { "P64:CAMPAIGN_NOT_ENABLED" }
    }

    private fun rule(set: PlayerChangeSet, process: String, event: String, uid: String, version: Int, domain: String, operation: String, actor: DomainRef): BackgroundProcessDefinition {
        val definition = Phase64BackgroundStore(db, campaign).definition(uid, version) ?: error("P64:RULE_REQUIRED")
        require(definition.domain == domain && definition.operation == operation) { "P64:OWNER_RULE_MISMATCH" }
        require(set.changes.mapNotNull { it.payload as? BackgroundProcessChange }.any {
            val subject = Phase64PopulationRuleCatalog.subject(definition, it.process.parameters, it.process.actor)
            it.process.uid == process && it.process.definitionUid == uid && it.process.definitionVersion == version &&
                subject == actor && it.process.status == BackgroundProcessStatus.COMPLETED && event in it.evidence.sourceUids &&
                (!Phase64PopulationRuleCatalog.matches(definition) ||
                    it.process.actor.kindUid != "PLAYER" && it.process.parameters["p64_initiator_kind_uid"] == it.process.actor.kindUid &&
                        it.process.parameters["p64_initiator_uid"] == it.process.actor.uid &&
                        it.process.parameters["p64_start_proof_uid"]?.startsWith(Phase64ProcessActivation.START_PROOF) == true)
        }) { "P64:DEMOGRAPHY_PROCESS_RECEIPT_REQUIRED" }
        return definition
    }

    private fun hasEvent(table: String, event: String) = db.rawQuery("SELECT 1 FROM $table WHERE campaign_uid=? AND event_uid=?", arrayOf(campaign, event)).use { it.moveToFirst() }
}
