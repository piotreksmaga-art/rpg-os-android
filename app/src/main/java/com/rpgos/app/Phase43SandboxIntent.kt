package com.rpgos.app

import java.security.MessageDigest

const val PHASE43_INTENT_SCHEMA_VERSION = 2

enum class MeaningState { UNDERSTOOD, PARTIAL, UNINTERPRETABLE }
enum class IntentReferenceState { UNRESOLVED, RESOLVED_PROJECTED, AMBIGUOUS, DEFERRED, NOT_DISCLOSED, INVALID }
enum class IntentCommitmentState { ACTIVE, RETRACTED, REPLACED, CANCELLED }
enum class IntentForm { DIRECT_ACTION, SEQUENCE_MEMBER, CONDITIONAL_ACTION, GOAL, QUERY, WAIT, CORRECTION, CANCELLATION, COMMUNICATION }
enum class IntentPolarity { AFFIRMATIVE, NEGATED }
enum class IntentModality { ATTEMPT_NOW, INTEND, PLAN_FUTURE, CONDITIONAL_FUTURE, ASK_IF_POSSIBLE, PREFER, AVOID }
enum class IntentReferenceKind { EXISTING_ENTITY, DESCRIPTIVE, DEICTIC, DISCOURSE, SET, FUTURE_RESULT, RESOURCE_FROM_RESULT, UNRESOLVED_TEXT }
enum class IntentDependencyKind { BEFORE, AFTER_SUCCESS, AFTER_ATTEMPT, AFTER_COMPLETION, AFTER_EVENT, REQUIRES_RESULT, DURING, PURPOSE_FOR, ALTERNATIVE_TO, CORRECTS, CANCELS, REPLACES }
enum class DirectiveStrength { SOFT, ORDERED, HARD }
enum class IntentConstraintKind { EXACT, RANGE, APPROXIMATE, MINIMUM, MAXIMUM, QUALITATIVE, AFFORDABILITY, DISTANCE, PLAYER_AGENCY, WORLD_DEFINED }
enum class IntentInterpretationSource { AI_PROVIDER, LEGACY_RULE, PLAYER_CLARIFICATION, TRUSTED_REFERENCE_RESOLUTION }

data class IntentInterpretationProvenance(
    val source:IntentInterpretationSource,
    val sourceUid:String,
    val sourceVersion:String,
    val sourceInputHash:String
){init{require(sourceUid.isNotBlank()&&sourceVersion.isNotBlank()&&sourceInputHash.isNotBlank())}}

data class SemanticAction(
    val canonicalActionUid:String?=null,
    val semanticFamilyUid:String?=null,
    val rawPhrase:String,
    val attributes:Map<String,String> = emptyMap(),
    val confidenceUid:String?=null
){init{
    require(rawPhrase.isNotBlank())
    require(canonicalActionUid?.isNotBlank()!=false&&semanticFamilyUid?.isNotBlank()!=false)
    require(canonicalActionUid!=null||semanticFamilyUid!=null||rawPhrase.isNotBlank())
    require(attributes.keys.none{it.isBlank()}&&attributes.values.none{it.length>512})
}}

data class IntentReference(
    val referenceUid:String,
    val kind:IntentReferenceKind,
    val rawPhrase:String?=null,
    val roleUid:String,
    val semanticTypeHints:Set<String> = emptySet(),
    val descriptorHints:Map<String,String> = emptyMap(),
    val state:IntentReferenceState=IntentReferenceState.UNRESOLVED,
    val resolvedProjectedRef:DomainRef?=null,
    val candidateProjectedRefs:List<DomainRef> = emptyList(),
    val resolutionEvidenceUid:String?=null,
    val confidenceUid:String?=null
){init{
    require(referenceUid.isNotBlank()&&roleUid.isNotBlank())
    require(rawPhrase?.isBlank()!=true&&semanticTypeHints.none{it.isBlank()})
    require(descriptorHints.keys.none{it.isBlank()}&&descriptorHints.values.none{it.length>512})
    require(candidateProjectedRefs.distinct()==candidateProjectedRefs)
    require((state==IntentReferenceState.RESOLVED_PROJECTED)==(resolvedProjectedRef!=null))
    require(resolutionEvidenceUid?.isBlank()!=true)
}}

data class FutureResultReference(val resultUid:String,val roleUid:String,val resource:Boolean=false){
    init{require(resultUid.isNotBlank()&&roleUid.isNotBlank())}
}

data class IntentParticipant(
    val roleUid:String,
    val referenceUid:String?=null,
    val futureResult:FutureResultReference?=null,
    val literalValue:String?=null
){init{
    require(roleUid.isNotBlank())
    require(listOfNotNull(referenceUid,futureResult,literalValue).size==1)
    require(referenceUid?.isBlank()!=true&&literalValue?.isBlank()!=true)
}}

data class IntentCondition(
    val conditionUid:String,
    val predicateUid:String,
    val argumentReferenceUids:List<String> = emptyList(),
    val polarity:IntentPolarity=IntentPolarity.AFFIRMATIVE,
    val evaluationTimingUid:String="WHEN_REACHED"
){init{
    require(conditionUid.isNotBlank()&&predicateUid.isNotBlank()&&evaluationTimingUid.isNotBlank())
    require(argumentReferenceUids.none{it.isBlank()}&&argumentReferenceUids.distinct()==argumentReferenceUids)
}}

data class IntentDependency(val predecessorNodeUid:String,val kind:IntentDependencyKind){init{require(predecessorNodeUid.isNotBlank())}}
data class IntendedResult(val resultUid:String,val semanticTypeUid:String?=null,val description:String){
    init{require(resultUid.isNotBlank()&&description.isNotBlank()&&semanticTypeUid?.isBlank()!=true)}
}
data class IntentDirective(
    val directiveUid:String,
    val kind:IntentConstraintKind,
    val strength:DirectiveStrength,
    val valueCanonical:String,
    val scopeNodeUid:String?=null
){init{require(directiveUid.isNotBlank()&&valueCanonical.isNotBlank()&&scopeNodeUid?.isBlank()!=true)}}

data class PlayerContextClaim(
    val claimUid:String,
    val surfaceText:String,
    val meaningCanonical:String,
    val epistemicRoleUid:String="PLAYER_ASSERTION",
    val linkedIntentNodeUid:String?=null
){init{require(claimUid.isNotBlank()&&surfaceText.isNotBlank()&&meaningCanonical.isNotBlank()&&epistemicRoleUid.isNotBlank())}}

data class IntentNode(
    val nodeUid:String,
    val form:IntentForm,
    val semanticAction:SemanticAction,
    val participants:List<IntentParticipant> = emptyList(),
    val conditions:List<IntentCondition> = emptyList(),
    val dependencies:List<IntentDependency> = emptyList(),
    val intendedResult:IntendedResult?=null,
    val polarity:IntentPolarity=IntentPolarity.AFFIRMATIVE,
    val modality:IntentModality=IntentModality.ATTEMPT_NOW,
    val commitmentState:IntentCommitmentState=IntentCommitmentState.ACTIVE,
    val constraints:List<IntentDirective> = emptyList(),
    val preferences:List<IntentDirective> = emptyList(),
    val terminationConditionUid:String?=null,
    val sourceSpan:IntRange?=null,
    val confidenceUid:String?=null
){init{
    require(nodeUid.isNotBlank())
    require(terminationConditionUid?.isBlank()!=true&&confidenceUid?.isBlank()!=true)
    require(sourceSpan?.first?.let{it>=0}!=false)
}}

data class IntentDocument(
    val schemaVersion:Int=PHASE43_INTENT_SCHEMA_VERSION,
    val campaignUid:String,
    val actor:CommandActorRef,
    val rawInput:String,
    val meaningState:MeaningState,
    val nodes:List<IntentNode>,
    val references:List<IntentReference> = emptyList(),
    val globalConstraints:List<IntentDirective> = emptyList(),
    val globalPreferences:List<IntentDirective> = emptyList(),
    val uncertainties:List<String> = emptyList(),
    val playerContextClaims:List<PlayerContextClaim> = emptyList(),
    val provenance:IntentInterpretationProvenance
){init{
    require(campaignUid.isNotBlank()&&rawInput.isNotBlank())
    require(uncertainties.none{it.isBlank()})
}
    fun activeNodes()=nodes.filter{it.commitmentState==IntentCommitmentState.ACTIVE&&it.polarity==IntentPolarity.AFFIRMATIVE}
    fun canonicalFingerprint()=phase43Sha256(canonicalPayload())
    internal fun canonicalPayload():String=buildString{
        append("v=").append(schemaVersion).append("|campaign=").append(campaignUid)
        append("|actor=").append(actor.actorKindUid).append(':').append(actor.actorUid)
        append("|raw=").append(rawInput).append("|meaning=").append(meaningState.name)
        nodes.forEach{node->append("|node=").append(node)}
        references.forEach{ref->append("|ref=").append(ref)}
        globalConstraints.forEach{append("|constraint=").append(it)}
        globalPreferences.forEach{append("|preference=").append(it)}
        uncertainties.forEach{append("|uncertainty=").append(it)}
        playerContextClaims.forEach{append("|claim=").append(it)}
        append("|provenance=").append(provenance)
    }
}

data class Phase43IntentLimits(
    val maxNodes:Int=64,
    val maxReferences:Int=128,
    val maxConditionsPerNode:Int=16,
    val maxDependencies:Int=256,
    val maxDirectives:Int=256,
    val maxRawInputChars:Int=32_768
){init{require(maxNodes in 1..256&&maxReferences in 0..512&&maxConditionsPerNode in 0..64&&maxDependencies in 0..1024&&maxDirectives in 0..1024&&maxRawInputChars in 1..262_144)}}

sealed interface IntentValidationResult{
    data class Accepted(val document:IntentDocument,val canonicalHash:String):IntentValidationResult
    data class Rejected(val reasonUids:List<String>):IntentValidationResult{init{require(reasonUids.isNotEmpty())}}
}

class Phase43IntentValidator(private val limits:Phase43IntentLimits=Phase43IntentLimits()){
    fun validate(candidate:IntentDocument):IntentValidationResult{
        val errors=linkedSetOf<String>()
        if(candidate.schemaVersion!=PHASE43_INTENT_SCHEMA_VERSION)errors+="SCHEMA_VERSION_UNSUPPORTED"
        if(candidate.rawInput.length>limits.maxRawInputChars)errors+="RAW_INPUT_LIMIT"
        if(candidate.nodes.size>limits.maxNodes)errors+="NODE_LIMIT"
        if(candidate.references.size>limits.maxReferences)errors+="REFERENCE_LIMIT"
        if(candidate.nodes.map{it.nodeUid}.distinct().size!=candidate.nodes.size)errors+="DUPLICATE_NODE_UID"
        if(candidate.references.map{it.referenceUid}.distinct().size!=candidate.references.size)errors+="DUPLICATE_REFERENCE_UID"
        val nodes=candidate.nodes.map{it.nodeUid}.toSet()
        val references=candidate.references.map{it.referenceUid}.toSet()
        val results=candidate.nodes.mapNotNull{it.intendedResult?.resultUid}
        if(results.distinct().size!=results.size)errors+="DUPLICATE_RESULT_UID"
        val resultSet=results.toSet()
        val resultOwners=candidate.nodes.mapNotNull{node->node.intendedResult?.resultUid?.let{it to node.nodeUid}}.toMap()
        val directives=candidate.nodes.flatMap{it.constraints+it.preferences}+candidate.globalConstraints+candidate.globalPreferences
        if(directives.map{it.directiveUid}.distinct().size!=directives.size)errors+="DUPLICATE_DIRECTIVE_UID"
        val conditions=candidate.nodes.flatMap{it.conditions}
        if(conditions.map{it.conditionUid}.distinct().size!=conditions.size)errors+="DUPLICATE_CONDITION_UID"
        if(candidate.playerContextClaims.map{it.claimUid}.distinct().size!=candidate.playerContextClaims.size)errors+="DUPLICATE_CLAIM_UID"
        if(candidate.nodes.sumOf{it.dependencies.size}>limits.maxDependencies)errors+="DEPENDENCY_LIMIT"
        if(candidate.nodes.sumOf{it.constraints.size+it.preferences.size}+candidate.globalConstraints.size+candidate.globalPreferences.size>limits.maxDirectives)errors+="DIRECTIVE_LIMIT"
        candidate.nodes.forEach{node->
            if(node.conditions.size>limits.maxConditionsPerNode)errors+="CONDITION_LIMIT:${node.nodeUid}"
            if(node.sourceSpan!=null&&(node.sourceSpan.last>=candidate.rawInput.length||node.sourceSpan.first>node.sourceSpan.last))errors+="SOURCE_SPAN_INVALID:${node.nodeUid}"
            node.participants.forEach{participant->
                if(participant.referenceUid!=null&&participant.referenceUid !in references)errors+="DANGLING_REFERENCE:${node.nodeUid}"
                if(participant.futureResult!=null){
                    val owner=resultOwners[participant.futureResult.resultUid]
                    if(owner==null)errors+="DANGLING_FUTURE_RESULT:${node.nodeUid}"
                    else if(node.dependencies.none{it.predecessorNodeUid==owner&&it.kind in setOf(IntentDependencyKind.REQUIRES_RESULT,IntentDependencyKind.AFTER_SUCCESS,IntentDependencyKind.AFTER_COMPLETION)})errors+="FUTURE_RESULT_WITHOUT_DEPENDENCY:${node.nodeUid}"
                }
            }
            node.conditions.flatMap{it.argumentReferenceUids}.filter{it !in references}.forEach{errors+="DANGLING_CONDITION_REFERENCE:${node.nodeUid}"}
            node.dependencies.filter{it.predecessorNodeUid !in nodes}.forEach{errors+="DANGLING_DEPENDENCY:${node.nodeUid}"}
            (node.constraints+node.preferences).filter{it.scopeNodeUid!=null&&it.scopeNodeUid!=node.nodeUid}.forEach{errors+="DIRECTIVE_SCOPE_INVALID:${it.directiveUid}"}
            if(node.form==IntentForm.CORRECTION&&node.dependencies.none{it.kind==IntentDependencyKind.CORRECTS||it.kind==IntentDependencyKind.REPLACES})errors+="CORRECTION_RELATION_REQUIRED:${node.nodeUid}"
            if(node.form==IntentForm.CANCELLATION&&node.dependencies.none{it.kind==IntentDependencyKind.CANCELS})errors+="CANCELLATION_RELATION_REQUIRED:${node.nodeUid}"
        }
        (candidate.globalConstraints+candidate.globalPreferences).filter{it.scopeNodeUid!=null&&it.scopeNodeUid !in nodes}.forEach{errors+="DIRECTIVE_SCOPE_INVALID:${it.directiveUid}"}
        candidate.playerContextClaims.filter{it.linkedIntentNodeUid!=null&&it.linkedIntentNodeUid !in nodes}.forEach{errors+="DANGLING_CLAIM_NODE:${it.claimUid}"}
        if(hasDependencyCycle(candidate.nodes))errors+="DEPENDENCY_CYCLE"
        if(candidate.provenance.source==IntentInterpretationSource.AI_PROVIDER){
            if(candidate.references.any{it.resolvedProjectedRef!=null||it.resolutionEvidenceUid!=null})errors+="AI_CANNOT_RESOLVE_WORLD_UID"
            if(candidate.nodes.any{it.semanticAction.canonicalActionUid!=null})errors+="AI_CANNOT_ASSERT_CANONICAL_ACTION_UID"
        }
        if(candidate.meaningState==MeaningState.UNINTERPRETABLE&&candidate.activeNodes().isNotEmpty())errors+="UNINTERPRETABLE_HAS_ACTIVE_NODE"
        if(errors.isNotEmpty())return IntentValidationResult.Rejected(errors.sorted())
        val canonical=canonicalize(candidate)
        return IntentValidationResult.Accepted(canonical,canonical.canonicalFingerprint())
    }

    private fun canonicalize(document:IntentDocument)=document.copy(
        nodes=document.nodes.sortedBy{it.nodeUid}.map{node->node.copy(
            participants=node.participants.sortedWith(compareBy<IntentParticipant>{it.roleUid}.thenBy{it.referenceUid?:it.futureResult?.resultUid?:it.literalValue.orEmpty()}),
            conditions=node.conditions.sortedBy{it.conditionUid}.map{it.copy(argumentReferenceUids=it.argumentReferenceUids.sorted())},
            dependencies=node.dependencies.sortedWith(compareBy<IntentDependency>{it.predecessorNodeUid}.thenBy{it.kind.name}),
            constraints=node.constraints.sortedBy{it.directiveUid},
            preferences=node.preferences.sortedBy{it.directiveUid},
            semanticAction=node.semanticAction.copy(attributes=node.semanticAction.attributes.toSortedMap())
        )},
        references=document.references.sortedBy{it.referenceUid}.map{it.copy(
            semanticTypeHints=it.semanticTypeHints.toSortedSet(),
            descriptorHints=it.descriptorHints.toSortedMap(),
            candidateProjectedRefs=it.candidateProjectedRefs.sortedWith(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid})
        )},
        globalConstraints=document.globalConstraints.sortedBy{it.directiveUid},
        globalPreferences=document.globalPreferences.sortedBy{it.directiveUid},
        uncertainties=document.uncertainties.sorted(),
        playerContextClaims=document.playerContextClaims.sortedBy{it.claimUid}
    )

    private fun hasDependencyCycle(nodes:List<IntentNode>):Boolean{
        val incoming=nodes.associate{it.nodeUid to it.dependencies.map{d->d.predecessorNodeUid}}.toMap()
        val visiting=hashSetOf<String>();val visited=hashSetOf<String>()
        fun visit(uid:String):Boolean{
            if(uid in visiting)return true
            if(!visited.add(uid))return false
            visiting+=uid
            if(incoming[uid].orEmpty().any(::visit))return true
            visiting-=uid
            return false
        }
        return incoming.keys.sorted().any(::visit)
    }
}

/** Compatibility bridge. Legacy rule parsing stays deterministic but is no longer the canonical semantic schema. */
object LegacyIntentDocumentAdapter{
    fun toDocument(intent:NormalizedIntent):IntentDocument{
        val references=intent.targetRefs.mapIndexed{index,ref->IntentReference(
            referenceUid="LEGACY-REF:$index",kind=IntentReferenceKind.EXISTING_ENTITY,rawPhrase="@${ref.kindUid}:${ref.uid}",roleUid="TARGET",
            state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=ref,resolutionEvidenceUid="LEGACY_EXPLICIT_TYPED_REFERENCE"
        )}
        val participants=references.map{IntentParticipant("TARGET",referenceUid=it.referenceUid)}
        val attributes=buildMap{intent.methodUid?.let{put("method_uid",it)};intent.timeScopeUid?.let{put("time_scope_uid",it)}}
        val document=IntentDocument(
            campaignUid=intent.campaignUid,actor=intent.actor,rawInput=intent.rawInput,meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(IntentNode("LEGACY-NODE:0",IntentForm.DIRECT_ACTION,SemanticAction(intent.actionUid,intent.actionUid,intent.actionUid,attributes,intent.confidenceUid),participants=participants)),
            references=references,
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.LEGACY_RULE,"PHASE43_LEGACY_RULE_PARSER","1",phase43Sha256(intent.rawInput))
        )
        return (Phase43IntentValidator().validate(document) as IntentValidationResult.Accepted).document
    }
}

private fun phase43Sha256(value:String)=MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
