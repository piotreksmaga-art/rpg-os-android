package com.rpgos.app

import java.security.MessageDigest

/** Pure generic derived projection. Authoritative base/current/mastery inputs are never mutated or persisted. */
class DerivedValueResolver(private val ruleProvider: DerivedRuleProvider? = null) {
    private data class NodeKey(val kind: ModifierTargetKind, val uid: String) {
        override fun toString(): String = "${kind.name}:$uid"
    }
    private data class AppliedValue(val value: Double, val contributions: List<ModifierContribution>)

    fun resolve(request: DerivedResolutionRequest): DerivedResolutionResult {
        require(request.campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(request.characterUid.isNotBlank()) { "characterUid must not be blank" }
        val statDefs = uniqueBy(request.statDefinitions, { it.statUid }, "stat definition")
        val resourceDefs = uniqueBy(request.resourceDefinitions, { it.resourceUid }, "resource definition")
        val skillDefs = uniqueBy(request.skillDefinitions, { it.skillUid }, "skill definition")
        val techniqueDefs = uniqueBy(request.techniqueDefinitions, { it.techniqueUid }, "technique definition")
        val stats = uniqueBy(request.playerStats, { it.statUid }, "player stat")
        uniqueBy(request.playerResources, { it.resourceUid }, "player resource")
        val skills = uniqueBy(request.playerSkills, { it.skillUid }, "player skill")
        val techniques = uniqueBy(request.playerTechniques, { it.techniqueUid }, "player technique")
        uniqueBy(request.modifiers, { it.modifierUid }, "modifier")

        request.playerStats.forEach {
            require(it.campaignId == request.campaignId && it.characterUid == request.characterUid) { "PlayerStat ${it.statUid} belongs to another campaign/player" }
            require(statDefs.containsKey(it.statUid)) { "PlayerStat targets missing definition ${it.statUid}" }
            require(it.baseValue.isFinite()) { "PlayerStat ${it.statUid} must be finite" }
        }
        request.playerResources.forEach {
            require(it.campaignId == request.campaignId && it.characterUid == request.characterUid) { "PlayerResource ${it.resourceUid} belongs to another campaign/player" }
            require(resourceDefs.containsKey(it.resourceUid)) { "PlayerResource targets missing definition ${it.resourceUid}" }
            require(it.currentValue.isFinite()) { "PlayerResource ${it.resourceUid} must be finite" }
        }
        request.playerSkills.forEach {
            SkillPolicy.validatePlayerSkill(it)
            require(it.campaignId == request.campaignId && it.characterUid == request.characterUid) { "PlayerSkill ${it.skillUid} belongs to another campaign/player" }
            require(skillDefs.containsKey(it.skillUid)) { "PlayerSkill targets missing definition ${it.skillUid}" }
        }
        request.playerTechniques.forEach {
            TechniquePolicy.validatePlayerTechnique(it)
            require(it.campaignId == request.campaignId && it.characterUid == request.characterUid) { "PlayerTechnique ${it.techniqueUid} belongs to another campaign/player" }
            require(techniqueDefs.containsKey(it.techniqueUid)) { "PlayerTechnique targets missing definition ${it.techniqueUid}" }
        }
        request.modifiers.forEach { modifier ->
            ModifierPolicy.validate(modifier)
            require(modifier.campaignId == request.campaignId && modifier.characterUid == request.characterUid) { "Modifier ${modifier.modifierUid} belongs to another campaign/player" }
            require(!LegacyCompatibilityIdentity.isReservedDefinitionUid(modifier.targetDefinitionUid)) { "Modifier ${modifier.modifierUid} must target canonical typed UID" }
            when (modifier.targetKind) {
                ModifierTargetKind.STAT_EFFECTIVE -> require(statDefs.containsKey(modifier.targetDefinitionUid)) { "Missing stat target ${modifier.targetDefinitionUid}" }
                ModifierTargetKind.RESOURCE_MAXIMUM, ModifierTargetKind.RESOURCE_REGENERATION -> require(resourceDefs.containsKey(modifier.targetDefinitionUid)) { "Missing resource target ${modifier.targetDefinitionUid}" }
                ModifierTargetKind.SKILL_EFFECTIVE -> {
                    require(skillDefs.containsKey(modifier.targetDefinitionUid)) { "Missing skill target ${modifier.targetDefinitionUid}" }
                    require(skills.containsKey(modifier.targetDefinitionUid)) { "Skill modifier targets unlearned skill ${modifier.targetDefinitionUid}" }
                }
                ModifierTargetKind.TECHNIQUE_EFFECTIVE -> {
                    require(techniqueDefs.containsKey(modifier.targetDefinitionUid)) { "Missing Technique target ${modifier.targetDefinitionUid}" }
                    require(techniques.containsKey(modifier.targetDefinitionUid)) { "Technique modifier targets unlearned Technique ${modifier.targetDefinitionUid}" }
                }
            }
        }
        request.legacyStatAliases.forEach { require(it.campaignId == request.campaignId) { "LegacyStatAlias belongs to another campaign" } }
        request.legacyResourceAliases.forEach { require(it.campaignId == request.campaignId) { "LegacyResourceAlias belongs to another campaign" } }

        val diagnostics = mutableListOf<DerivedDiagnostic>()
        request.modifiers.sortedBy { it.modifierUid }.forEach { modifier ->
            when {
                !modifier.active -> diagnostics += DerivedDiagnostic("MODIFIER_INACTIVE", modifier.targetDefinitionUid, modifier.modifierUid)
                !modifier.sourceActive -> diagnostics += DerivedDiagnostic("MODIFIER_SOURCE_INACTIVE", modifier.targetDefinitionUid, modifier.modifierUid)
                modifier.validFrom != null && request.resolutionEpoch < modifier.validFrom -> diagnostics += DerivedDiagnostic("MODIFIER_FUTURE", modifier.targetDefinitionUid, modifier.modifierUid)
                modifier.validUntil != null && request.resolutionEpoch > modifier.validUntil -> diagnostics += DerivedDiagnostic("MODIFIER_EXPIRED", modifier.targetDefinitionUid, modifier.modifierUid)
            }
        }
        val activeModifiers = request.modifiers.filter { ModifierPolicy.isEffectiveAt(it, request.resolutionEpoch) }
        val statCache = linkedMapOf<String, ResolvedStat>()
        val skillCache = linkedMapOf<String, ResolvedSkill>()
        val techniqueCache = linkedMapOf<String, ResolvedTechnique>()
        val maxCache = linkedMapOf<String, AppliedValue?>()
        val regenCache = linkedMapOf<String, AppliedValue?>()
        val usedRules = linkedMapOf<String, DerivedRuleDescriptor>()
        val stack = mutableListOf<NodeKey>()
        lateinit var resolveNode: (DerivedDependency) -> Double

        fun <T> cycleGuard(node: NodeKey, block: () -> T): T {
            val existing = stack.indexOf(node)
            check(existing < 0) { "Derived dependency cycle: ${(stack.subList(existing, stack.size) + node).joinToString(" -> ")}" }
            stack += node
            return try { block() } finally { stack.removeAt(stack.lastIndex) }
        }

        fun evaluateRule(ruleUid: String, target: NodeKey): Double {
            val provider = ruleProvider ?: error("Missing rule provider for $ruleUid")
            require(provider.providerUid.isNotBlank()) { "rule provider UID must not be blank" }
            val descriptor = provider.descriptor(ruleUid) ?: error("Missing derived rule: $ruleUid")
            val expectedVersion = request.ruleVersions[ruleUid] ?: error("Missing rule version binding for $ruleUid")
            check(descriptor.version == expectedVersion) { "Incompatible rule version for $ruleUid: expected $expectedVersion, provider has ${descriptor.version}" }
            val dependencies = descriptor.dependencies.sortedWith(compareBy<DerivedDependency>({ it.targetKind.ordinal }, { it.targetDefinitionUid }))
            check(dependencies.size == dependencies.distinct().size) { "Duplicate dependency in rule $ruleUid" }
            val values = linkedMapOf<DerivedDependency, Double>()
            dependencies.forEach { values[it] = resolveNode(it) }
            usedRules[ruleUid] = descriptor.copy(dependencies = dependencies)
            return finite(provider.evaluate(descriptor, DerivedRuleContext(request.campaignId, request.characterUid, target.kind, target.uid, request.resolutionEpoch, values)), "rule $ruleUid")
        }

        fun applyModifiers(base: Double, kind: ModifierTargetKind, uid: String): AppliedValue {
            var current = finite(base, "initial $kind $uid")
            val trace = mutableListOf<ModifierContribution>()
            var sequence = 0
            val target = activeModifiers.filter { it.targetKind == kind && it.targetDefinitionUid == uid }
            val comparator = compareBy<Modifier>({ it.priority }, { it.modifierUid })
            fun record(m: Modifier, input: Double, output: Double) {
                trace += ModifierContribution(sequence++, m.modifierUid, m.lifecycle, m.operation, m.priority, m.sourceType, m.sourceUid, zero(input), zero(m.value), zero(output), m.provenance)
            }
            ModifierLifecycle.values().forEach { lifecycle ->
                val stage = target.filter { it.lifecycle == lifecycle }
                stage.filter { it.operation == ModifierOperation.ADD_FLAT }.sortedWith(comparator).forEach { m -> val input=current; current=finite(current+m.value,"ADD_FLAT ${m.modifierUid}"); record(m,input,current) }
                val percents = stage.filter { it.operation == ModifierOperation.ADD_PERCENT }.sortedWith(comparator)
                if (percents.isNotEmpty()) {
                    val stageInput=current; var cumulative=0.0
                    percents.forEach { m -> cumulative=finite(cumulative+m.value,"ADD_PERCENT sum ${m.modifierUid}"); val output=finite(stageInput+stageInput*cumulative,"ADD_PERCENT ${m.modifierUid}"); record(m,stageInput,output); current=output }
                }
                stage.filter { it.operation == ModifierOperation.MULTIPLY }.sortedWith(comparator).forEach { m -> val input=current; current=finite(current*m.value,"MULTIPLY ${m.modifierUid}"); record(m,input,current) }
                stage.filter { it.operation == ModifierOperation.OVERRIDE }.sortedWith(comparator).forEach { m -> val input=current; current=finite(m.value,"OVERRIDE ${m.modifierUid}"); record(m,input,current) }
                stage.filter { it.operation == ModifierOperation.MIN_FLOOR }.sortedWith(comparator).forEach { m -> val input=current; current=finite(kotlin.math.max(current,m.value),"MIN_FLOOR ${m.modifierUid}"); record(m,input,current) }
                stage.filter { it.operation == ModifierOperation.MAX_CAP }.sortedWith(comparator).forEach { m -> val input=current; current=finite(kotlin.math.min(current,m.value),"MAX_CAP ${m.modifierUid}"); record(m,input,current) }
            }
            return AppliedValue(zero(current), trace)
        }

        fun resolveStat(uid: String): ResolvedStat {
            statCache[uid]?.let { return it }
            val definition=statDefs[uid] ?: error("Missing stat definition $uid")
            return cycleGuard(NodeKey(ModifierTargetKind.STAT_EFFECTIVE,uid)) {
                val player=stats[uid]
                val ruleValue=definition.derivationRuleUid?.let { evaluateRule(it,NodeKey(ModifierTargetKind.STAT_EFFECTIVE,uid)) }
                val starting=ruleValue ?: player?.baseValue ?: error("Stat $uid has neither authoritative base nor derivation rule")
                val applied=applyModifiers(starting,ModifierTargetKind.STAT_EFFECTIVE,uid)
                val preBounds=applied.value; var finalValue=preBounds
                definition.minValue?.let { finalValue=kotlin.math.max(finalValue,it) }; definition.maxValue?.let { finalValue=kotlin.math.min(finalValue,it) }
                finalValue=finite(finalValue,"definition bounds $uid")
                val local=if(finalValue!=preBounds) listOf(DerivedDiagnostic("STAT_DEFINITION_BOUND_APPLIED",uid,"preBound=${canon(preBounds)} final=${canon(finalValue)}")) else emptyList()
                ResolvedStat(uid,player?.baseValue?.let(::zero),ruleValue?.let(::zero),zero(preBounds),zero(finalValue),applied.contributions,local).also{statCache[uid]=it}
            }
        }

        fun resolveSkill(uid: String): ResolvedSkill {
            skillCache[uid]?.let { return it }
            val definition=skillDefs[uid] ?: error("Missing skill definition $uid")
            val player=skills[uid] ?: error("Missing learned PlayerSkill $uid")
            return cycleGuard(NodeKey(ModifierTargetKind.SKILL_EFFECTIVE,uid)) {
                val applied=applyModifiers(player.baseMastery,ModifierTargetKind.SKILL_EFFECTIVE,uid)
                val preBounds=applied.value; var finalValue=preBounds
                definition.minMastery?.let { finalValue=kotlin.math.max(finalValue,it) }; definition.maxMastery?.let { finalValue=kotlin.math.min(finalValue,it) }
                finalValue=finite(finalValue,"skill definition bounds $uid")
                val local=if(finalValue!=preBounds) listOf(DerivedDiagnostic("SKILL_DEFINITION_BOUND_APPLIED",uid,"preBound=${canon(preBounds)} final=${canon(finalValue)}")) else emptyList()
                ResolvedSkill(uid,zero(player.baseMastery),zero(preBounds),zero(finalValue),applied.contributions,local).also{skillCache[uid]=it}
            }
        }

        fun resolveTechnique(uid: String): ResolvedTechnique {
            techniqueCache[uid]?.let { return it }
            val definition=techniqueDefs[uid] ?: error("Missing Technique definition $uid")
            val player=techniques[uid] ?: error("Missing learned PlayerTechnique $uid")
            return cycleGuard(NodeKey(ModifierTargetKind.TECHNIQUE_EFFECTIVE,uid)) {
                val applied=applyModifiers(player.baseMastery,ModifierTargetKind.TECHNIQUE_EFFECTIVE,uid)
                val preBounds=applied.value; var finalValue=preBounds
                definition.minMastery?.let { finalValue=kotlin.math.max(finalValue,it) }; definition.maxMastery?.let { finalValue=kotlin.math.min(finalValue,it) }
                finalValue=finite(finalValue,"Technique definition bounds $uid")
                val local=if(finalValue!=preBounds) listOf(DerivedDiagnostic("TECHNIQUE_DEFINITION_BOUND_APPLIED",uid,"preBound=${canon(preBounds)} final=${canon(finalValue)}")) else emptyList()
                ResolvedTechnique(uid,zero(player.baseMastery),zero(preBounds),zero(finalValue),applied.contributions,local).also{techniqueCache[uid]=it}
            }
        }

        fun resolveMaximum(uid:String):AppliedValue? {
            if(maxCache.containsKey(uid)) return maxCache[uid]
            val definition=resourceDefs[uid] ?: error("Missing resource definition $uid"); val node=NodeKey(ModifierTargetKind.RESOURCE_MAXIMUM,uid)
            val result=if(definition.maxRuleUid==null && definition.maxValue==null){check(activeModifiers.none{it.targetKind==node.kind&&it.targetDefinitionUid==uid}){"Resource $uid has maximum modifiers but no maximum base/rule"};null}
            else cycleGuard(node){val starting=definition.maxRuleUid?.let{evaluateRule(it,node)}?:definition.maxValue!!;applyModifiers(starting,node.kind,uid)}
            maxCache[uid]=result;return result
        }

        fun resolveRegeneration(uid:String):AppliedValue? {
            if(regenCache.containsKey(uid)) return regenCache[uid]
            val definition=resourceDefs[uid] ?: error("Missing resource definition $uid"); val node=NodeKey(ModifierTargetKind.RESOURCE_REGENERATION,uid)
            val result=if(definition.regenerationRuleUid==null){check(activeModifiers.none{it.targetKind==node.kind&&it.targetDefinitionUid==uid}){"Resource $uid has regeneration modifiers but no regeneration rule"};null}
            else cycleGuard(node){applyModifiers(evaluateRule(definition.regenerationRuleUid,node),node.kind,uid)}
            regenCache[uid]=result;return result
        }

        resolveNode={dependency->when(dependency.targetKind){
            ModifierTargetKind.STAT_EFFECTIVE->resolveStat(dependency.targetDefinitionUid).effectiveValue
            ModifierTargetKind.RESOURCE_MAXIMUM->resolveMaximum(dependency.targetDefinitionUid)?.value?:error("Undefined maximum dependency ${dependency.targetDefinitionUid}")
            ModifierTargetKind.RESOURCE_REGENERATION->resolveRegeneration(dependency.targetDefinitionUid)?.value?:error("Undefined regeneration dependency ${dependency.targetDefinitionUid}")
            ModifierTargetKind.SKILL_EFFECTIVE->resolveSkill(dependency.targetDefinitionUid).effectiveMastery
            ModifierTargetKind.TECHNIQUE_EFFECTIVE->resolveTechnique(dependency.targetDefinitionUid).effectiveMastery
        }}

        val statTargets=linkedSetOf<String>();request.playerStats.forEach{statTargets+=it.statUid};request.statDefinitions.filter{it.derivationRuleUid!=null}.forEach{statTargets+=it.statUid};activeModifiers.filter{it.targetKind==ModifierTargetKind.STAT_EFFECTIVE}.forEach{statTargets+=it.targetDefinitionUid};statTargets.sorted().forEach(::resolveStat)
        val skillTargets=linkedSetOf<String>();request.playerSkills.forEach{skillTargets+=it.skillUid};activeModifiers.filter{it.targetKind==ModifierTargetKind.SKILL_EFFECTIVE}.forEach{skillTargets+=it.targetDefinitionUid};skillTargets.sorted().forEach(::resolveSkill)
        val techniqueTargets=linkedSetOf<String>();request.playerTechniques.forEach{techniqueTargets+=it.techniqueUid};activeModifiers.filter{it.targetKind==ModifierTargetKind.TECHNIQUE_EFFECTIVE}.forEach{techniqueTargets+=it.targetDefinitionUid};techniqueTargets.sorted().forEach(::resolveTechnique)
        val resolvedResources=request.playerResources.sortedBy{it.resourceUid}.map{player->
            val max=resolveMaximum(player.resourceUid);val regen=resolveRegeneration(player.resourceUid)
            val local=if(max!=null&&player.currentValue>max.value) listOf(DerivedDiagnostic("RESOURCE_CURRENT_ABOVE_DERIVED_MAX",player.resourceUid,"current=${canon(player.currentValue)} maximum=${canon(max.value)}; no authoritative mutation performed")) else emptyList()
            ResolvedResource(player.resourceUid,zero(player.currentValue),max?.value?.let(::zero),regen?.value?.let(::zero),max?.contributions?:emptyList(),regen?.contributions?:emptyList(),local)
        }
        val ruleFingerprint=ruleFingerprint(usedRules.values.toList())
        return DerivedResolutionResult(
            statCache.values.sortedBy{it.statUid},
            resolvedResources,
            diagnostics+statCache.values.flatMap{it.diagnostics}+resolvedResources.flatMap{it.diagnostics}+skillCache.values.flatMap{it.diagnostics}+techniqueCache.values.flatMap{it.diagnostics},
            inputFingerprint(request,ruleFingerprint),
            ruleFingerprint,
            skillCache.values.sortedBy{it.skillUid},
            techniqueCache.values.sortedBy{it.techniqueUid}
        )
    }

    private fun ruleFingerprint(descriptors:List<DerivedRuleDescriptor>):String {
        if(descriptors.isEmpty())return sha256("NO_RULES")
        val provider=ruleProvider?:error("Rule descriptors used without provider")
        return sha256(buildString{append(provider.providerUid);descriptors.sortedBy{it.ruleUid}.forEach{d->append('|').append(d.ruleUid).append(':').append(d.version);d.dependencies.sortedWith(compareBy({it.targetKind.ordinal},{it.targetDefinitionUid})).forEach{append('>').append(it.targetKind.name).append(':').append(it.targetDefinitionUid)}}})
    }

    private fun inputFingerprint(request:DerivedResolutionRequest,ruleFingerprint:String):String=sha256(buildString{
        append(request.campaignId).append('|').append(request.characterUid).append('|').append(request.resolutionEpoch)
        request.statDefinitions.sortedBy{it.statUid}.forEach{append("|SD:").append(it.statUid).append(':').append(it.key).append(':').append(it.category).append(':').append(it.unit?:"").append(':').append(it.minValue?.let(::canon)?:"").append(':').append(it.maxValue?.let(::canon)?:"").append(':').append(it.growthRuleUid?:"").append(':').append(it.derivationRuleUid?:"").append(':').append(it.worldPackUid)}
        request.resourceDefinitions.sortedBy{it.resourceUid}.forEach{append("|RD:").append(it.resourceUid).append(':').append(it.key).append(':').append(it.category).append(':').append(it.unit?:"").append(':').append(it.minValue?.let(::canon)?:"").append(':').append(it.maxValue?.let(::canon)?:"").append(':').append(it.maxRuleUid?:"").append(':').append(it.regenerationRuleUid?:"").append(':').append(it.worldPackUid)}
        request.skillDefinitions.sortedBy{it.skillUid}.forEach{append("|KD:").append(it.skillUid).append(':').append(it.worldPackUid).append(':').append(it.key).append(':').append(it.category).append(':').append(it.minMastery?.let(::canon)?:"").append(':').append(it.maxMastery?.let(::canon)?:"").append(':').append(it.status.name).append(':').append(it.definitionVersion);it.progressionDomainUids.sorted().forEach{d->append('>').append(d)}}
        request.techniqueDefinitions.sortedBy{it.techniqueUid}.forEach{d->append("|TD:").append(d.techniqueUid).append(':').append(d.worldPackUid).append(':').append(d.key).append(':').append(d.category).append(':').append(d.minMastery?.let(::canon)?:"").append(':').append(d.maxMastery?.let(::canon)?:"").append(':').append(d.status.name).append(':').append(d.definitionVersion);d.skillRequirements.sortedBy{it.skillUid}.forEach{r->append(">S:").append(r.skillUid).append(':').append(r.masteryBasis.name).append(':').append(canon(r.minimumMastery)).append(':').append(r.requirementVersion)};d.resourceCosts.sortedBy{it.resourceUid}.forEach{c->append(">R:").append(c.resourceUid).append(':').append(canon(c.amount)).append(':').append(c.costVersion)}}
        request.playerStats.sortedBy{it.statUid}.forEach{append("|PS:").append(it.statUid).append(':').append(canon(it.baseValue)).append(':').append(it.version)}
        request.playerResources.sortedBy{it.resourceUid}.forEach{append("|PR:").append(it.resourceUid).append(':').append(canon(it.currentValue)).append(':').append(it.version)}
        request.playerSkills.sortedBy{it.skillUid}.forEach{append("|PK:").append(it.skillUid).append(':').append(canon(it.baseMastery)).append(':').append(it.progressValue?.let(::canon)?:"").append(':').append(it.progressSemanticsUid?:"").append(':').append(it.entryVersion)}
        request.playerTechniques.sortedBy{it.techniqueUid}.forEach{append("|PT:").append(it.techniqueUid).append(':').append(canon(it.baseMastery)).append(':').append(it.progressValue?.let(::canon)?:"").append(':').append(it.progressSemanticsUid?:"").append(':').append(it.learnedChapter?:"").append(':').append(it.lastUsedChapter?:"").append(':').append(it.usageCount).append(':').append(it.successCount).append(':').append(it.failureCount).append(':').append(it.isEquipped).append(':').append(it.entryVersion)}
        request.modifiers.sortedBy{it.modifierUid}.forEach{append("|M:").append(it.modifierUid).append(':').append(it.targetKind.name).append(':').append(it.targetDefinitionUid).append(':').append(it.lifecycle.name).append(':').append(it.operation.name).append(':').append(canon(it.value)).append(':').append(it.priority).append(':').append(it.sourceType).append(':').append(it.sourceUid).append(':').append(it.sourceActive).append(':').append(it.validFrom?:"").append(':').append(it.validUntil?:"").append(':').append(it.active).append(':').append(it.provenance).append(':').append(it.version)}
        request.ruleVersions.toSortedMap().forEach{(uid,version)->append("|RV:").append(uid).append(':').append(version)}
        request.legacyStatAliases.sortedBy{it.legacyStatUid}.forEach{append("|SA:").append(it.legacyStatUid).append(':').append(it.canonicalStatUid).append(':').append(it.worldPackUid).append(':').append(it.mappingVersion).append(':').append(it.provenance)}
        request.legacyResourceAliases.sortedBy{it.legacyResourceUid}.forEach{append("|RA:").append(it.legacyResourceUid).append(':').append(it.canonicalResourceUid).append(':').append(it.worldPackUid).append(':').append(it.mappingVersion).append(':').append(it.provenance)}
        append("|RF:").append(ruleFingerprint)
    })

    private fun<T> uniqueBy(values:List<T>,uid:(T)->String,label:String):Map<String,T>{val out=linkedMapOf<String,T>();values.forEach{v->val key=uid(v);require(out.put(key,v)==null){"Duplicate $label UID: $key"}};return out}
    private fun finite(value:Double,source:String):Double{check(value.isFinite()){"Non-finite derived value at $source"};return zero(value)}
    private fun zero(value:Double):Double=if(value==0.0)0.0 else value
    private fun canon(value:Double):String=java.lang.Double.toHexString(zero(value))
    private fun sha256(value:String):String=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){(it.toInt()and 0xff).toString(16).padStart(2,'0')}
}
