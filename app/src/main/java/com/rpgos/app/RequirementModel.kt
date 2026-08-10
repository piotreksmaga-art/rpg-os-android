package com.rpgos.app

enum class RequirementGate { UNLOCK, TRANSITION, ACTIVATION }

data class RequirementBinding(
    val ruleUid: String,
    val ruleVersion: Long
) {
    init {
        require(ruleUid.isNotBlank()) { "requirement ruleUid must not be blank" }
        require(ruleVersion >= 1L) { "requirement ruleVersion must be at least 1" }
    }
}

data class RequirementRuleDescriptor(
    val ruleUid: String,
    val version: Long,
    val allowedGates: Set<RequirementGate>,
    val dependencies: List<String> = emptyList()
) {
    init {
        require(ruleUid.isNotBlank()) { "requirement ruleUid must not be blank" }
        require(version >= 1L) { "requirement rule version must be at least 1" }
        require(allowedGates.isNotEmpty()) { "requirement rule must allow at least one gate" }
        require(dependencies.none { it.isBlank() }) { "requirement dependency UID must not be blank" }
        require(dependencies.size == dependencies.distinct().size) { "duplicate requirement dependency" }
    }
}

data class RequirementContext(
    val campaignId: String,
    val characterUid: String,
    val gate: RequirementGate,
    val subjectUid: String,
    val dependencyResults: Map<String, Boolean> = emptyMap()
) {
    init {
        require(campaignId.isNotBlank())
        require(characterUid.isNotBlank())
        require(subjectUid.isNotBlank())
    }
}

interface RequirementRuleProvider {
    val providerUid: String
    fun descriptor(ruleUid: String): RequirementRuleDescriptor?
    /** true = pass, false = failed requirement, null = malformed/indeterminate result. */
    fun evaluate(descriptor: RequirementRuleDescriptor, context: RequirementContext): Boolean?
}

class RequirementEvaluator(private val provider: RequirementRuleProvider?) {
    fun requirePass(binding: RequirementBinding?, context: RequirementContext) {
        if (binding == null) return
        val p = provider ?: error("Missing requirement rule provider for ${binding.ruleUid}")
        require(p.providerUid.isNotBlank()) { "requirement provider UID must not be blank" }
        val stack = mutableListOf<String>()
        val memo = linkedMapOf<String, Boolean>()

        fun evaluateRule(ruleUid: String, expectedVersion: Long?): Boolean {
            memo[ruleUid]?.let { return it }
            check(ruleUid !in stack) { "Requirement dependency cycle: ${(stack + ruleUid).joinToString(" -> ")}" }
            val descriptor = p.descriptor(ruleUid) ?: error("Missing requirement rule: $ruleUid")
            if (expectedVersion != null) {
                check(descriptor.version == expectedVersion) {
                    "Incompatible requirement rule version for $ruleUid: expected $expectedVersion, provider has ${descriptor.version}"
                }
            }
            check(context.gate in descriptor.allowedGates) {
                "Requirement rule $ruleUid is not valid for ${context.gate} gate"
            }
            stack += ruleUid
            val result = try {
                val dependencyResults = linkedMapOf<String, Boolean>()
                descriptor.dependencies.sorted().forEach { dependencyUid ->
                    dependencyResults[dependencyUid] = evaluateRule(dependencyUid, null)
                }
                if (dependencyResults.values.any { !it }) false else {
                    p.evaluate(descriptor, context.copy(dependencyResults = dependencyResults))
                        ?: error("Malformed requirement result for $ruleUid")
                }
            } finally {
                stack.removeAt(stack.lastIndex)
            }
            memo[ruleUid] = result
            return result
        }

        check(evaluateRule(binding.ruleUid, binding.ruleVersion)) {
            "Requirement ${binding.ruleUid} failed for ${context.gate} gate on ${context.subjectUid}"
        }
    }
}
