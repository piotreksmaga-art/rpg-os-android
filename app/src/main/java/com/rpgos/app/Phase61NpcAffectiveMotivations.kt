package com.rpgos.app

/** A personal, fallible fear, never a new relationship or proof that its object is dangerous.
 * Only new own acquired evidence can strengthen/weaken it; elapsed time alone does not invent
 * reassurance. Deltas and capacity are Core rules, not numbers chosen by the model. */
internal object NpcAffectiveMotivations {
    data class Update(val motivations:List<NpcMotivation>,val causes:List<NpcCauseRef>)
    fun derive(context:NpcDecisionContextEnvelope,candidates:List<NpcAppraisalCandidate>,previousWatermark:Long):Update {
        val values=context.brain.motivations.associateByTo(linkedMapOf()){it.uid}
        val causes=linkedSetOf<NpcCauseRef>()
        val perceived=candidates.filter{it.meaning in setOf(NpcAppraisalMeaning.THREAT,NpcAppraisalMeaning.SAFETY) &&
            it.subject!=null && it.subject!=context.brain.actor}.mapNotNull{candidate->
            val record=context.records.singleOrNull{it.uid==candidate.supportingRecordUid}?:return@mapNotNull null
            if(candidate.subject !in record.subjectRefs || record.sourceCommittedOrder<=previousWatermark ||
                record.memoryKind!=NpcMemoryRecordKind.CURRENT_KNOWLEDGE)return@mapNotNull null
            candidate to record
        }.groupBy{(candidate,record)->candidate.subject to record.acquisitionUid}
        perceived.entries.sortedWith(compareBy({it.key.first.toString()},{it.key.second})).forEach{(key,entries)->
            val meanings=entries.map{it.first.meaning}.distinct()
            if(meanings.size!=1)return@forEach // Ambivalent evidence is not net reassurance/threat.
            val subject=requireNotNull(key.first);val record=entries.first().second
            val uid="P61:FEAR:${phase60Hash(subject.toString()).take(32)}"
            val old=values[uid]
            if(old!=null && (old.kind!=NpcMotivationKind.FEAR || old.subject!=subject))return@forEach
            val threat=meanings.single()==NpcAppraisalMeaning.THREAT
            if(!threat && old==null)return@forEach
            if(old==null && values.size>=64)return@forEach
            val step=when(record.epistemicState){KnowledgeEpistemicState.KNOWN->500;KnowledgeEpistemicState.BELIEVED->375
                KnowledgeEpistemicState.PARTIALLY_KNOWN,KnowledgeEpistemicState.SUSPECTED->250;else->0}
            if(step==0)return@forEach
            val strength=NpcWeight(((old?.strength?.basisPoints?:0)+(if(threat)step else -step)).coerceIn(0,10000))
            if(old?.strength==strength)return@forEach
            values[uid]=NpcMotivation(uid,NpcMotivationKind.FEAR,"SECURITY",strength,subject)
            causes+=NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,record.acquisitionUid)
        }
        return Update(values.values.sortedBy{it.uid},causes.toList())
    }
}
