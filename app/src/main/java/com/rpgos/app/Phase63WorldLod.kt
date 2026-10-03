package com.rpgos.app

/** A captured work frontier, not canonical population membership or a second NPC brain.
 * In particular, lowering LOD never deletes a named actor, inventory, knowledge or a plan. */
internal data class WorldLodSubject(
    val ref:DomainRef,val aggregate:DomainRef?=null,val anonymousCount:Long=0,
    val activePlayer:Boolean=false,val directInteraction:Boolean=false,
    val hasPendingProcess:Boolean=false,val formation:Boolean=false
) {
    init {
        require(anonymousCount>=0 && (aggregate==null || aggregate.kindUid in setOf("GROUP","UNIT")))
        require(anonymousCount==0L || ref.kindUid in setOf("GROUP","UNIT"))
        require(!activePlayer || aggregate==null)
    }
}

internal data class WorldLodWorkItem(val owner:DomainRef,val level:WorldSimulationLod,
    val anonymousCount:Long,val namedActors:Set<DomainRef>,val interactionActors:Set<DomainRef>,
    val pendingActors:Set<DomainRef>) {
    init {
        require(anonymousCount>=0 && interactionActors.all { it in namedActors } && pendingActors.all { it in namedActors })
        require(namedActors.none { it.kindUid in setOf("GROUP","UNIT") })
    }
}

/** The caller supplies only references demanded by an interaction/deadline/dependency change.
 * It never enumerates the world's population. A million anonymous members cost one work item. */
internal class WorldLodWorkPlan(val scope:TemporalScope,subjects:List<WorldLodSubject>) {
    val items:List<WorldLodWorkItem>
    val individualDecisionActors:List<DomainRef>
    init {
        require(subjects.size<=256 && subjects.map { it.ref }.distinct().size==subjects.size)
        val active=subjects.filter { it.activePlayer }.map { it.ref }.toSet()
        require(active.size<=1)
        items=subjects.groupBy { it.aggregate?:it.ref }.toSortedMap(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid})
            .map { (owner,members)->
                val group=members.singleOrNull { it.ref==owner && it.ref.kindUid in setOf("GROUP","UNIT") }
                val named=members.filter { it.ref.kindUid !in setOf("GROUP","UNIT") }
                val level=members.maxOf { member->WorldLodPolicy.level(WorldLodInterest(member.ref,
                    member.activePlayer,member.directInteraction,member.hasPendingProcess,member.formation)) }
                WorldLodWorkItem(owner,level,group?.anonymousCount?:0,named.map { it.ref }.toSet(),
                    named.filter { it.directInteraction && !it.activePlayer }.map { it.ref }.toSet(),
                    named.filter { it.hasPendingProcess && !it.activePlayer }.map { it.ref }.toSet())
            }
        individualDecisionActors=items.flatMap { it.interactionActors }.filter { it !in active && it.kindUid in setOf("ACTOR","NPC") }
            .distinct().sortedWith(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid})
        require(individualDecisionActors.size<=32) { "P63:INDIVIDUAL_WORK_BUDGET" }
    }
    /** Shared Phase60 uses a host-time budget and persists its cursor. This method supplies a
     * stable frontier; elapsed host time cannot change identities, casualties or deadlines. */
    fun batch(cursor:Int,maximum:Int=32):Pair<List<WorldLodWorkItem>,Int?> {
        require(cursor in 0..items.size && maximum in 1..32)
        val end=minOf(items.size,cursor+maximum)
        return items.subList(cursor,end) to end.takeIf { it<items.size }
    }
}
