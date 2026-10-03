package com.rpgos.app

/** One resolver invocation shares the network budget across all its references. No persistent
 * campaign text, principal data or model conversation is sent to the evidence provider. */
internal class TurnBudgetedWorldScout(
    private val consent:()->Boolean,
    private val search:(WorldEvidenceRequest,Long)->List<WorldEvidenceCandidate>,
    private val nanoTime:()->Long=System::nanoTime
):WorldEvidenceProviderPort {
    override fun candidates(request:WorldEvidenceRequest)=forTurn().candidates(request)
    override fun forTurn():WorldEvidenceProviderPort=object:WorldEvidenceProviderPort {
        private val attempted=mutableSetOf<String>()
        private var usedNanos=0L
        override fun candidates(request:WorldEvidenceRequest):List<WorldEvidenceCandidate> {
            if(!consent())return emptyList()
            val key=listOf(request.campaignUid,normalizedWorldText(request.phrase),request.shape.baseKind.name).joinToString("|")
            if(!attempted.add(key))return emptyList()
            val remaining=(5_000_000_000L-usedNanos)/1_000_000L
            if(remaining<=0)return emptyList()
            val bounded=request.copy(phrase=request.phrase.take(256),worldContextHint=request.worldContextHint?.take(160),
                maximumCandidates=minOf(request.maximumCandidates,5))
            val start=nanoTime()
            return try {
                val result=search(bounded,remaining).take(5)
                if(consent())result else emptyList()
            } catch(_:Exception) { emptyList() }
            finally { usedNanos=(usedNanos+(nanoTime()-start).coerceAtLeast(0)).coerceAtMost(5_000_000_000L) }
        }
    }
}
