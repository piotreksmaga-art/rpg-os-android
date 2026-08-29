package com.rpgos.app

import java.security.MessageDigest

enum class CapabilityMatchState { EXACT, COMPOSED, GENERIC, REQUIRES_ADJUDICATION, NONE }
enum class CapabilityMatchMode { EXACT_ONLY, FAMILY, GENERIC_SAFE }
enum class CapabilityExecutionKind { READ_CONTEXT, MECHANICS_PROPOSAL, COMMUNICATION, WAIT, QUERY }
enum class CapabilitySideEffectClass { NONE, PROPOSED_WORLD_EFFECT, EXTERNAL_EFFECT }
enum class RequirementImportance { REQUIRED, SAFETY, QUALITY, OPTIONAL }

data class CapabilityEnvelope(
    val envelopeUid:String,
    val campaignUid:String,
    val providerUid:String,
    val operationUid:String,
    val allowedFilterKeys:Set<String>,
    val fixedFilters:Map<String,String> = emptyMap(),
    val maximumLimit:Int,
    val audience:AudienceContext,
    val purpose:PurposeContext,
    val atOrder:Long?=null,
    val cursorSupport:CursorSupport=CursorSupport.NONE
){
    init{
        require(envelopeUid.isNotBlank()&&campaignUid.isNotBlank()&&providerUid.isNotBlank()&&operationUid.isNotBlank())
        require(maximumLimit in 1..200&&atOrder?.let{it>=0L}!=false)
        require(audience.campaignUid==campaignUid&&purpose.campaignUid==campaignUid){"RPGOS-P44:CROSS_CAMPAIGN_ENVELOPE"}
        require(allowedFilterKeys.none{it.isBlank()}&&fixedFilters.keys.all{it in allowedFilterKeys})
        require(fixedFilters.values.none{it.length>512})
    }

    fun validate(request:StructuredRetrievalRequest):EnvelopeValidationResult{
        val reasons=linkedSetOf<String>()
        if(request.campaignUid!=campaignUid||request.audience!=audience||request.purpose!=purpose)reasons+="ENTITLEMENT_EXPANSION"
        if(request.providerUid!=providerUid||request.operationUid!=operationUid)reasons+="CAPABILITY_EXPANSION"
        if(request.atOrder!=atOrder||request.limit>maximumLimit)reasons+="QUERY_SCOPE_EXPANSION"
        if(request.filters.keys.any{it !in allowedFilterKeys})reasons+="FILTER_DIMENSION_EXPANSION"
        if(fixedFilters.any{(key,value)->request.filters[key]!=value})reasons+="FIXED_IDENTITY_EXPANSION"
        if(request.cursor!=null&&cursorSupport==CursorSupport.NONE)reasons+="CURSOR_EXPANSION"
        return if(reasons.isEmpty())EnvelopeValidationResult.Allowed else EnvelopeValidationResult.Rejected(reasons.sorted())
    }
}

sealed interface EnvelopeValidationResult{
    data object Allowed:EnvelopeValidationResult
    data class Rejected(val reasonUids:List<String>):EnvelopeValidationResult{init{require(reasonUids.isNotEmpty())}}
}

data class CapabilityRequirementTemplate(
    val requirementUid:String,
    val providerUid:String,
    val operationUid:String,
    val importance:RequirementImportance,
    val allowedFilterKeys:Set<String> = emptySet(),
    val maximumLimit:Int=50,
    val targetRoleUid:String?=null,
    val targetKindFilterKey:String?=null,
    val targetUidFilterKey:String?=null,
    val cursorSupport:CursorSupport=CursorSupport.NONE,
    /** Core derives this fixed query from accepted intent semantics; providers never receive raw UI authority. */
    val queryFilterKey:String?=null
){init{
    require(requirementUid.isNotBlank()&&providerUid.isNotBlank()&&operationUid.isNotBlank())
    require(maximumLimit in 1..200&&targetRoleUid?.isBlank()!=true)
    require(listOfNotNull(targetKindFilterKey,targetUidFilterKey).size in setOf(0,2))
    require(listOfNotNull(targetKindFilterKey,targetUidFilterKey).all{it in allowedFilterKeys})
    require(queryFilterKey?.let{it.isNotBlank()&&it in allowedFilterKeys}!=false)
}}

data class CapabilityDescriptor(
    val capabilityUid:String,
    val version:Int,
    val canonicalActionUids:Set<String> = emptySet(),
    val semanticFamilyUids:Set<String> = emptySet(),
    val allowedForms:Set<IntentForm> = IntentForm.entries.toSet(),
    val requiredParticipantRoles:Set<String> = emptySet(),
    val resolvedParticipantRoles:Set<String> = emptySet(),
    val matchMode:CapabilityMatchMode=CapabilityMatchMode.FAMILY,
    val executionKind:CapabilityExecutionKind,
    val sideEffectClass:CapabilitySideEffectClass,
    val mechanicsOwnerUid:String?=null,
    val composable:Boolean=false,
    val requirements:List<CapabilityRequirementTemplate> = emptyList()
){init{
    require(capabilityUid.isNotBlank()&&version>0)
    require(canonicalActionUids.none{it.isBlank()}&&semanticFamilyUids.none{it.isBlank()}&&requiredParticipantRoles.none{it.isBlank()}&&resolvedParticipantRoles.none{it.isBlank()})
    require(resolvedParticipantRoles.all{it in requiredParticipantRoles})
    require(canonicalActionUids.isNotEmpty()||semanticFamilyUids.isNotEmpty()||matchMode==CapabilityMatchMode.GENERIC_SAFE)
    require(matchMode!=CapabilityMatchMode.EXACT_ONLY||canonicalActionUids.isNotEmpty())
    require(matchMode!=CapabilityMatchMode.GENERIC_SAFE||sideEffectClass==CapabilitySideEffectClass.NONE){"RPGOS-P44:GENERIC_CAPABILITY_MUST_BE_READ_ONLY"}
    require(requirements.map{it.requirementUid}.distinct().size==requirements.size)
    require(mechanicsOwnerUid?.isBlank()!=true)
}}

data class PlannedRequirement(
    val requirementUid:String,
    val ownerNodeUid:String,
    val importance:RequirementImportance,
    val request:StructuredRetrievalRequest,
    val envelope:CapabilityEnvelope
){init{
    require(requirementUid.isNotBlank()&&ownerNodeUid.isNotBlank())
    require(envelope.validate(request)==EnvelopeValidationResult.Allowed)
}}

data class CanonicalTurnPlanStep(
    val stepUid:String,
    val nodeUid:String,
    val capabilityUid:String?,
    val matchState:CapabilityMatchState,
    val dependencyNodeUids:List<String>,
    val requirements:List<PlannedRequirement>,
    val executionKind:CapabilityExecutionKind?,
    val sideEffectClass:CapabilitySideEffectClass?,
    val mechanicsOwnerUid:String?=null,
    val reasonUids:List<String> = emptyList()
){init{
    require(stepUid.isNotBlank()&&nodeUid.isNotBlank())
    require(dependencyNodeUids.distinct()==dependencyNodeUids&&requirements.map{it.requirementUid}.distinct().size==requirements.size)
    require((matchState in setOf(CapabilityMatchState.EXACT,CapabilityMatchState.COMPOSED,CapabilityMatchState.GENERIC))==(capabilityUid!=null))
}}

data class CanonicalTurnPlan(
    val schemaVersion:Int=1,
    val planUid:String,
    val campaignUid:String,
    val intent:IntentDocument,
    val audience:AudienceContext,
    val purpose:PurposeContext,
    val steps:List<CanonicalTurnPlanStep>,
    val atOrder:Long?=null
){init{
    require(schemaVersion==1&&planUid.isNotBlank()&&campaignUid==intent.campaignUid)
    require(audience.campaignUid==campaignUid&&purpose.campaignUid==campaignUid){"RPGOS-P44:CROSS_CAMPAIGN_PLAN"}
    require(steps.map{it.stepUid}.distinct().size==steps.size&&steps.map{it.nodeUid}.distinct().size==steps.size)
}}

data class GraphTurnPlannerLimits(val maxSteps:Int=64,val maxRequirements:Int=256){init{require(maxSteps in 1..256&&maxRequirements in 0..1024)}}

sealed interface CanonicalPlanningResult{
    data class Planned(val plan:CanonicalTurnPlan):CanonicalPlanningResult
    data class Rejected(val reasonUids:List<String>):CanonicalPlanningResult{init{require(reasonUids.isNotEmpty())}}
}

/** Pure planner: it chooses registered capabilities and bounded requirements, but performs no read or mutation. */
class GraphTurnPlanner(
    capabilities:List<CapabilityDescriptor>,
    private val limits:GraphTurnPlannerLimits=GraphTurnPlannerLimits(),
    private val validator:Phase43IntentValidator=Phase43IntentValidator()
){
    private val capabilities=capabilities.sortedWith(compareBy<CapabilityDescriptor>{it.capabilityUid}.thenByDescending{it.version})
    init{require(this.capabilities.map{it.capabilityUid to it.version}.distinct().size==this.capabilities.size){"RPGOS-P44:DUPLICATE_CAPABILITY_VERSION"}}

    fun plan(candidate:IntentDocument,audience:AudienceContext,purpose:PurposeContext,atOrder:Long?=null):CanonicalPlanningResult{
        if(candidate.campaignUid!=audience.campaignUid||candidate.campaignUid!=purpose.campaignUid)return CanonicalPlanningResult.Rejected(listOf("CROSS_CAMPAIGN"))
        val accepted=when(val result=validator.validate(candidate)){
            is IntentValidationResult.Accepted->result.document
            is IntentValidationResult.Rejected->return CanonicalPlanningResult.Rejected(result.reasonUids)
        }
        if(accepted.meaningState==MeaningState.UNINTERPRETABLE)return CanonicalPlanningResult.Rejected(listOf("INTENT_UNINTERPRETABLE"))
        val ordered=topologicalOrder(accepted.activeNodes())?:return CanonicalPlanningResult.Rejected(listOf("DEPENDENCY_CYCLE"))
        if(ordered.size>limits.maxSteps)return CanonicalPlanningResult.Rejected(listOf("STEP_LIMIT"))
        val steps=ordered.mapIndexed{index,node->planNode(index,node,accepted,audience,purpose,atOrder)}
        if(steps.sumOf{it.requirements.size}>limits.maxRequirements)return CanonicalPlanningResult.Rejected(listOf("REQUIREMENT_LIMIT"))
        val seed=listOf(accepted.canonicalFingerprint(),audience.audienceKindUid,audience.principal,purpose.purposeUid,atOrder,steps).joinToString("|")
        return CanonicalPlanningResult.Planned(CanonicalTurnPlan(
            planUid="PLAN-V2:${phase44Sha256(seed)}",campaignUid=accepted.campaignUid,intent=accepted,audience=audience,purpose=purpose,steps=steps,atOrder=atOrder
        ))
    }

    private fun planNode(index:Int,node:IntentNode,document:IntentDocument,audience:AudienceContext,purpose:PurposeContext,atOrder:Long?):CanonicalTurnPlanStep{
        val participantRoles=node.participants.map{it.roleUid}.toSet()
        val ranked=capabilities.mapNotNull{capability->
            if(node.form !in capability.allowedForms||!participantRoles.containsAll(capability.requiredParticipantRoles)||!resolvedRolesSatisfied(node,document,capability.resolvedParticipantRoles))null
            else matchRank(node,capability)?.let{it to capability}
        }.sortedWith(compareBy<Pair<Int,CapabilityDescriptor>>{it.first}.thenBy{it.second.capabilityUid}.thenByDescending{it.second.version})
        if(ranked.isEmpty())return CanonicalTurnPlanStep(
            "STEP-V2:$index:${node.nodeUid}",node.nodeUid,null,CapabilityMatchState.REQUIRES_ADJUDICATION,
            node.dependencies.map{it.predecessorNodeUid}.sorted(),emptyList(),null,null,reasonUids=listOf("NO_REGISTERED_CAPABILITY")
        )
        val (rank,selected)=ranked.first()
        val ambiguous=ranked.drop(1).any{it.first==rank&&it.second.capabilityUid!=selected.capabilityUid}
        if(ambiguous)return CanonicalTurnPlanStep(
            "STEP-V2:$index:${node.nodeUid}",node.nodeUid,null,CapabilityMatchState.REQUIRES_ADJUDICATION,
            node.dependencies.map{it.predecessorNodeUid}.sorted(),emptyList(),null,null,reasonUids=listOf("CAPABILITY_MATCH_AMBIGUOUS")
        )
        val requirements=selected.requirements.sortedBy{it.requirementUid}.flatMap{template->
            requirements(node,document,template,audience,purpose,atOrder)
        }
        val missingTarget=selected.requirements.any{template->
            template.targetRoleUid!=null&&requirements.none{it.requirementUid.contains(":${template.requirementUid}:")}
        }
        if(missingTarget)return CanonicalTurnPlanStep(
            "STEP-V2:$index:${node.nodeUid}",node.nodeUid,null,CapabilityMatchState.REQUIRES_ADJUDICATION,
            node.dependencies.map{it.predecessorNodeUid}.sorted(),emptyList(),null,null,reasonUids=listOf("REQUIRED_REFERENCE_UNRESOLVED")
        )
        val match=when(rank){0->CapabilityMatchState.EXACT;1->CapabilityMatchState.COMPOSED;else->CapabilityMatchState.GENERIC}
        return CanonicalTurnPlanStep(
            "STEP-V2:$index:${node.nodeUid}",node.nodeUid,selected.capabilityUid,match,node.dependencies.map{it.predecessorNodeUid}.sorted(),requirements,
            selected.executionKind,selected.sideEffectClass,selected.mechanicsOwnerUid
        )
    }

    private fun matchRank(node:IntentNode,capability:CapabilityDescriptor):Int?=when{
        node.semanticAction.canonicalActionUid in capability.canonicalActionUids->0
        capability.matchMode!=CapabilityMatchMode.EXACT_ONLY&&node.semanticAction.semanticFamilyUid in capability.semanticFamilyUids->1
        capability.matchMode==CapabilityMatchMode.GENERIC_SAFE->2
        else->null
    }

    private fun resolvedRolesSatisfied(node:IntentNode,document:IntentDocument,roles:Set<String>)=roles.all{role->
        node.participants.filter{it.roleUid==role}.all{participant->
            participant.literalValue!=null||participant.futureResult!=null||participant.referenceUid?.let{uid->document.references.singleOrNull{it.referenceUid==uid}?.state==IntentReferenceState.RESOLVED_PROJECTED}==true
        }
    }

    private fun requirements(node:IntentNode,document:IntentDocument,template:CapabilityRequirementTemplate,audience:AudienceContext,purpose:PurposeContext,atOrder:Long?):List<PlannedRequirement>{
        val scoped=if(template.targetRoleUid==null)listOf("GLOBAL" to emptyMap()) else node.participants
            .filter{it.roleUid==template.targetRoleUid}.mapNotNull{participant->
                val referenceUid=participant.referenceUid?:return@mapNotNull null
                val projected=document.references.firstOrNull{it.referenceUid==referenceUid}?.resolvedProjectedRef?:return@mapNotNull null
                referenceUid to mapOf(template.targetKindFilterKey!! to projected.kindUid,template.targetUidFilterKey!! to projected.uid)
            }
        return scoped.sortedBy{it.first}.map{(scopeUid,targetFixed)->
            val fixed=buildMap{
                putAll(targetFixed)
                template.queryFilterKey?.let{key->
                    val semanticQuery=listOf(node.semanticAction.rawPhrase,document.rawInput)
                        .firstOrNull{it.isNotBlank()}!!.trim().take(512)
                    put(key,semanticQuery)
                }
            }
            val uid="REQ-V2:${node.nodeUid}:${template.requirementUid}:$scopeUid"
            val envelope=CapabilityEnvelope(
                envelopeUid="ENV:$uid",campaignUid=document.campaignUid,providerUid=template.providerUid,operationUid=template.operationUid,
                allowedFilterKeys=template.allowedFilterKeys,fixedFilters=fixed,maximumLimit=template.maximumLimit,audience=audience,purpose=purpose,
                atOrder=atOrder,cursorSupport=template.cursorSupport
            )
            val request=StructuredRetrievalRequest(uid,document.campaignUid,template.providerUid,template.operationUid,fixed,template.maximumLimit,audience,purpose,atOrder)
            PlannedRequirement(uid,node.nodeUid,template.importance,request,envelope)
        }
    }

    private fun topologicalOrder(nodes:List<IntentNode>):List<IntentNode>?{
        val byUid=nodes.associateBy{it.nodeUid};val emitted=linkedSetOf<String>();val ordered=mutableListOf<IntentNode>()
        while(ordered.size<nodes.size){
            val ready=nodes.filter{it.nodeUid !in emitted&&it.dependencies.all{dependency->dependency.predecessorNodeUid !in byUid||dependency.predecessorNodeUid in emitted}}.sortedBy{it.nodeUid}
            if(ready.isEmpty())return null
            ready.forEach{ordered+=it;emitted+=it.nodeUid}
        }
        return ordered
    }
}

private fun phase44Sha256(value:String)=MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
