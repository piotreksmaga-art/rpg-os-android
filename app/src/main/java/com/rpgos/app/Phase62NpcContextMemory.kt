package com.rpgos.app

/** Inputs have already passed the holder projection. Current canonical knowledge dominates
 * a rebuildable recollection of the SAME acquisition, regardless of their different UI UIDs.
 * No text/score-based deduplication: separately acquired contradictions must stay distinct. */
internal fun npcContextMemoryRecords(current:List<NpcKnownRecord>,historical:List<NpcKnownRecord>,
                                    requiredAcquisitionUids:Set<String>):List<NpcKnownRecord> {
    require(current.size<=64 && current.map{it.uid}.distinct().size==current.size)
    val currentUids=current.mapTo(hashSetOf()){it.uid}
    val currentAcquisitions=current.mapTo(hashSetOf()){it.acquisitionUid}
    val requiredCount=current.count{it.acquisitionUid in requiredAcquisitionUids}
    val extras=historical.asSequence().filter{it.uid !in currentUids && it.acquisitionUid !in currentAcquisitions}
        .distinctBy{it.acquisitionUid}.distinctBy{it.uid}.take(minOf(16,64-requiredCount)).toList()
    return current.sortedBy{it.acquisitionUid !in requiredAcquisitionUids}.take(64-extras.size)+extras
}
