package com.rpgos.app

/** Expand identity selection, not actions. The existing planner/mechanics still decide whether
 * one action can address several targets and charge its registered cost exactly once. */
internal object WorldReferenceSetExpansion {
    const val LIMIT=16
    fun expand(input:IntentDocument):IntentDocument {
        val replacements=mutableMapOf<String,List<IntentReference>>()
        val references=input.references.flatMap { reference->
            val count=reference.descriptorHints["quantity"]?.toIntOrNull()?:1
            if(count<=1)return@flatMap listOf(reference)
            val consumers=input.nodes.filter { n->n.participants.any { it.referenceUid==reference.referenceUid } }
            val shape=WorldReferenceShapeClassifier.classify(reference,consumers)
            val eligible=count<=LIMIT && shape.kind !in setOf(WorldReferenceShapeKind.NAMED_INSTANCE,WorldReferenceShapeKind.UNKNOWN) &&
                reference.kind !in setOf(IntentReferenceKind.DISCOURSE,IntentReferenceKind.DEICTIC,IntentReferenceKind.FUTURE_RESULT,IntentReferenceKind.RESOURCE_FROM_RESULT) &&
                consumers.isNotEmpty() && consumers.all { n->n.participants.filter { it.referenceUid==reference.referenceUid }.all { it.roleUid=="TARGET" } } &&
                input.nodes.none { n->n.conditions.any { reference.referenceUid in it.argumentReferenceUids } }
            if(!eligible || input.references.size+replacements.values.sumOf { it.size-1 }+count-1>128)
                return@flatMap listOf(reference.copy(state=IntentReferenceState.UNRESOLVED,resolvedProjectedRef=null,candidateProjectedRefs=emptyList(),
                    descriptorHints=reference.descriptorHints+("world_resolution_reason" to "P63:SET_SELECTION_REQUIRES_CLARIFICATION")))
            val first=shape.ordinal?:1
            if(first>Int.MAX_VALUE-count)return@flatMap listOf(reference.copy(state=IntentReferenceState.UNRESOLVED,resolvedProjectedRef=null,
                candidateProjectedRefs=emptyList(),descriptorHints=reference.descriptorHints+("world_resolution_reason" to "P63:SET_SELECTION_REQUIRES_CLARIFICATION")))
            List(count) { offset->reference.copy(referenceUid="${reference.referenceUid}:P63:MEMBER:${first+offset}",kind=IntentReferenceKind.DESCRIPTIVE,
                state=IntentReferenceState.UNRESOLVED,resolvedProjectedRef=null,candidateProjectedRefs=emptyList(),resolutionEvidenceUid=null,
                descriptorHints=reference.descriptorHints+mapOf("quantity" to "1","ordinal" to (first+offset).toString(),"shape" to "CATEGORY"))
            }.also { replacements[reference.referenceUid]=it }
        }
        return input.copy(references=references,nodes=input.nodes.map { node->node.copy(participants=node.participants.flatMap { participant->
            replacements[participant.referenceUid]?.map { participant.copy(referenceUid=it.referenceUid) }?:listOf(participant)
        }) })
    }
}
