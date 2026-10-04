package com.rpgos.app

/** Pure domain contracts. Existing owners remain the only writers of money, bodies,
 * inventory, projects and knowledge. A process is not a second copy of those records. */
data class BackgroundProcessDefinition(
    val uid:String, val version:Int, val domain:String, val operation:String,
    val durationMillis:Long, val priority:Int=0, val parameters:Map<String,String> = emptyMap()
) {
    init {
        require(uid.isNotBlank() && version>0 && domain in DOMAINS && operation.isNotBlank())
        require(durationMillis>0 && parameters.size<=64 && parameters.all { it.key.length in 1..160 && it.value.length<=16_384 })
    }
    companion object { val DOMAINS=setOf("ECONOMY","PROJECT","ORGANIZATION","INFORMATION","POPULATION","CONFLICT","EPIDEMIC") }
}

enum class BackgroundProcessStatus { ACTIVE, BLOCKED, COMPLETED, INTERRUPTED, FAILED }

data class BackgroundProcessInstance(
    val uid:String, val definitionUid:String, val definitionVersion:Int,
    val actor:DomainRef, val version:Long, val startedAt:WorldTimeTick, val due:WorldTimeTick,
    val status:BackgroundProcessStatus=BackgroundProcessStatus.ACTIVE,
    val parameters:Map<String,String> = emptyMap(), val dependencyUids:List<String> = emptyList(),
    val progressUnits:Long=0, val reasonUid:String?=null
) {
    init {
        require(uid.isNotBlank() && definitionUid.isNotBlank() && definitionVersion>0 && version>0)
        require(due>=startedAt && progressUnits>=0 && parameters.size<=64 && dependencyUids.size<=32)
        require(dependencyUids.distinct().size==dependencyUids.size && uid !in dependencyUids)
    }
}

data class BackgroundProcessEvaluationScope(val temporal:TemporalScope,val worldSeed:String,val ruleFingerprint:String)

data class WorldResourceClaim(val resource:DomainRef,val quantity:Long) {
    init { require(quantity>0) }
}

data class WorldProcessEvidence(val uid:String,val processUid:String,val ruleUid:String,val ruleVersion:Int,
    val sourceUids:List<String>,val at:WorldTimeTick,val reasonUid:String?=null)

/** Candidates only. The production adapter seals every payload through normal admission. */
data class WorldConsequencePlan(
    val changes:List<PlayerDomainChangePayload> = emptyList(),
    val effects:List<VerifiedMechanicsCommandEffect> = emptyList(),
    val claims:List<WorldResourceClaim> = emptyList(),
    val sourceUids:List<String> = emptyList(),
    val status:BackgroundProcessStatus=BackgroundProcessStatus.COMPLETED,
    val reasonUid:String?=null,
    val progressUnits:Long=0,
    val deadlineAdds:List<WorldProcessDeadline> = emptyList(),
    val deadlineRemovals:List<String> = emptyList(),
    val ownerDelegations:List<TemporalOwnerDelegation> = emptyList(),
    /** Reconcile effects already produced by their owner in this boundary; never execute twice. */
    val existingConsequenceFingerprints:List<String> = emptyList()
)

/** Core-only captured reads, with a speculative overlay, never an AI-facing global dump. */
interface BackgroundWorldReadPort {
    fun forEvaluation(input:TemporalOwnerInput):BackgroundWorldReadPort = this
    fun forEvaluation(input:TemporalOwnerInput,cancelled:()->Boolean):BackgroundWorldReadPort = forEvaluation(input)
    fun available(resource:DomainRef,staged:List<PlayerDomainChangePayload>):Long?
    fun exists(ref:DomainRef):Boolean
    fun exists(ref:DomainRef,staged:List<PlayerDomainChangePayload>):Boolean = exists(ref)
    fun route(actor:DomainRef,destination:DomainRef,at:WorldTimeTick):String?
    /** Route knowledge belongs to the decision principal; costs and movement to the subject.
     * A group does not inherit its commander's memories merely by being co-located. */
    fun authorizedRoute(principal:DomainRef,subject:DomainRef,destination:DomainRef,at:WorldTimeTick):String? =
        if(principal==subject)route(subject,destination,at) else null
    fun authorize(actor:DomainRef,purpose:String,refs:List<DomainRef>):Boolean
    fun authorize(actor:DomainRef,purpose:String,refs:List<DomainRef>,staged:List<PlayerDomainChangePayload>):Boolean =
        authorize(actor,purpose,refs)
    fun communicationAccess(actor:DomainRef,purpose:String,refs:List<DomainRef>,definition:BackgroundProcessDefinition,
        process:BackgroundProcessInstance,at:WorldTimeTick,staged:List<PlayerDomainChangePayload>):EffectiveAccessDecision? = null
    fun prepareOwnedEffect(operation:String,actor:DomainRef,parameters:Map<String,String>,
        scope:BackgroundProcessEvaluationScope,staged:List<PlayerDomainChangePayload>):WorldConsequencePlan
    fun prepareOwnedEffect(operation:String,actor:DomainRef,parameters:Map<String,String>,
        scope:BackgroundProcessEvaluationScope,staged:List<PlayerDomainChangePayload>,input:TemporalOwnerInput):WorldConsequencePlan =
        prepareOwnedEffect(operation,actor,parameters,scope,staged)
}

interface BackgroundDomainAdapter {
    val domains:Set<String>
    fun evaluate(definition:BackgroundProcessDefinition,process:BackgroundProcessInstance,
        scope:BackgroundProcessEvaluationScope,at:WorldTimeTick,reads:BackgroundWorldReadPort,
        staged:List<PlayerDomainChangePayload>):WorldConsequencePlan
}

internal fun backgroundParameters(definition:BackgroundProcessDefinition,process:BackgroundProcessInstance)=definition.parameters+process.parameters
internal fun backgroundBlocked(reason:String)=WorldConsequencePlan(status=BackgroundProcessStatus.BLOCKED,reasonUid=reason)
internal fun backgroundRequired(values:Map<String,String>,key:String)=requireNotNull(values[key]) { "P64:PARAMETER_REQUIRED:$key" }.also { require(it.isNotBlank()) }
internal fun backgroundPositive(values:Map<String,String>,key:String)=backgroundRequired(values,key).toLong().also { require(it>0) }
