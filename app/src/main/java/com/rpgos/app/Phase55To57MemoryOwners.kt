package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

fun interface WorkingMemoryScopePort {
    fun scope(request:ChatTurnRequest):WorkingMemoryScope?
    companion object { val NONE=WorkingMemoryScopePort{null} }
}

/** Phase55 owns only the bounded, already-authorized Phase45 materialization for one consumer. */
object WorkingMemoryOwner {
    fun materialize(scope:WorkingMemoryScope,context:BudgetedCanonicalContext):WorkingMemorySnapshot {
        require(scope.campaignUid==context.candidate.plan.campaignUid){"RPGOS-MEMORY:WORKING_MEMORY_CROSS_CAMPAIGN"}
        val records=context.includedSegments
            .flatMap{segment->segment.records.map{segment to it}}
            .groupBy{it.second.record.recordUid}
            .map{(_,occurrences)->
                val selected=occurrences.sortedWith(
                    compareBy<Pair<CanonicalContextSegment,CanonicalContextRecord>>{importanceRank(it.first.requirement.importance)}
                        .thenBy{it.second.projectionBoundaryUid}
                ).first()
                val rawScore=(selected.second.record.values["semantic_score"] as? Number)?.toDouble()
                val relevance=rawScore?.coerceIn(0.0,1.0)?:importanceRelevance(selected.first.requirement.importance)
                WorkingMemoryRecord(
                    canonicalRecordUid=selected.second.record.recordUid,
                    sourceEpistemicStateUid=selected.second.epistemicState.name,
                    projectionBoundaryUid=selected.second.projectionBoundaryUid,
                    relevance=QueryRelevance(relevance),
                    pinned=selected.first.requirement.importance in setOf(RequirementImportance.REQUIRED,RequirementImportance.SAFETY)
                )
            }.sortedWith(compareByDescending<WorkingMemoryRecord>{it.pinned}.thenByDescending{it.relevance.value}.thenBy{it.canonicalRecordUid})
        val fingerprint=memorySha256(buildString{
            append(scope.campaignUid).append('|').append(scope.historyGenerationUid.value).append('|')
            append(scope.audienceKindUid).append('|').append(scope.principalUid).append('|').append(scope.purposeUid).append('|')
            append(scope.asOfCommittedOrder).append('|').append(scope.accessPolicyVersion)
            records.forEach{append('|').append(it.canonicalRecordUid).append(':').append(it.sourceEpistemicStateUid)
                .append(':').append(it.projectionBoundaryUid).append(':').append(it.relevance.value).append(':').append(it.pinned)}
        })
        return WorkingMemorySnapshot(scope,records,fingerprint)
    }

    private fun importanceRank(value:RequirementImportance)=when(value){
        RequirementImportance.SAFETY->0
        RequirementImportance.REQUIRED->1
        RequirementImportance.QUALITY->2
        RequirementImportance.OPTIONAL->3
    }
    private fun importanceRelevance(value:RequirementImportance)=when(value){
        RequirementImportance.SAFETY,RequirementImportance.REQUIRED->1.0
        RequirementImportance.QUALITY->0.75
        RequirementImportance.OPTIONAL->0.5
    }
}

data class Phase37EpisodeMemoryDerivation(
    val holderMemories:List<HolderEpisodeMemory>,
    val semanticAssertions:List<SemanticMemoryAssertion>
)

data class Phase37EpisodeMemoryPersistence(
    val producedRevisionUids:List<String>,
    val updatedRevisionUids:List<String>,
    val supersededRevisionUids:List<String>
)

/** Phase56/57 projector. It cannot invent holder memory: every output requires a recorded Phase37
 * acquisition and its evidence row. Absence of a row produces no assertion. */
class Phase37EpisodeMemoryProjector(private val db:SQLiteDatabase,private val campaignUid:String){
    private data class AcquisitionRow(
        val acquisitionUid:String,val claimUid:String,val holder:KnowledgeHolderRef,val methodUid:String,
        val eventUid:String,val createdOrder:Long,val epistemic:KnowledgeEpistemicState?,
        val confidence:Double?,val reliability:Double?,val stateVersion:Long?,val stateUid:String?,val stateUpdatedOrder:Long?,
        val subject:DomainRef,val predicateUid:String,val objectValue:String
    )
    private data class EvidenceRow(val evidenceUid:String,val acquisitionUid:String,val polarity:KnowledgeEvidencePolarity,val createdOrder:Long,val fingerprint:String)

    init{require(campaignUid.isNotBlank())}

    fun derive(episode:EpisodeManifest):Phase37EpisodeMemoryDerivation{
        require(episode.identity.campaignUid==campaignUid){"RPGOS-MEMORY:EPISODE_CROSS_CAMPAIGN"}
        if(!Phase37KnowledgeSchema.isReady(db))return Phase37EpisodeMemoryDerivation(emptyList(),emptyList())
        val acquisitions=acquisitions(episode)
        if(acquisitions.isEmpty())return Phase37EpisodeMemoryDerivation(emptyList(),emptyList())
        val evidence=evidence(acquisitions)
        val evidenceByAcquisition=evidence.groupBy{it.acquisitionUid}
        val evidenced=acquisitions.filter{evidenceByAcquisition[it.acquisitionUid].orEmpty().isNotEmpty()}
        val holders=evidenced.groupBy{it.holder}.toSortedMap(compareBy<KnowledgeHolderRef>{it.holderKindUid}.thenBy{it.holderUid})
        val generation=episode.identity.historyGenerationUid
        val holderMemories=holders.map{(holder,rows)->
            val leaves=rows.sortedBy{it.acquisitionUid}.map(::acquisitionLeaf)
            val fingerprint=memoryLeafFingerprint(leaves)
            val remembered=rows.map{it.eventUid}.distinct().sortedBy{episode.eventUids.indexOf(it)}
            val logical="RPGOS-P56-HOLDER:${episode.identity.logicalArtifactUid}:${holder.holderKindUid}:${holder.holderUid}"
            HolderEpisodeMemory(
                identity=MemoryArtifactIdentity(campaignUid,generation,logical,
                    "RPGOS-P56-HOLDER-REV:${memorySha256("$logical|${generation.value}|$fingerprint|${episode.identity.artifactRevisionUid}")}",
                    MemoryArtifactKind.HOLDER_EPISODE_MEMORY,leaves,fingerprint,"RPGOS-P56-PHASE37-ACQUISITION",1,
                    episode.endOrder,episode.startOrder,episode.endOrder),
                episodeLogicalUid=episode.identity.logicalArtifactUid,
                holder=holder,
                rememberedEventUids=remembered,
                omittedEventUids=episode.eventUids.filterNot{it in remembered.toSet()},
                accuracy=MemoryAccuracy(rows.map{row->
                    ((row.confidence?:methodReliability(row.methodUid))*(row.reliability?:methodReliability(row.methodUid))).coerceIn(0.0,1.0)
                }.average()),
                salience=HolderSalience((remembered.size.toDouble()/episode.eventUids.size).coerceIn(0.0,1.0)),
                recallStrength=RecallStrength(rows.map{(it.confidence?:methodReliability(it.methodUid)).coerceIn(0.0,1.0)}.average()),
                acquisitionUids=rows.map{it.acquisitionUid}.distinct().sorted()
            )
        }
        val assertions=evidenced.filter{it.epistemic!=null&&it.stateVersion!=null&&it.stateUid!=null&&it.stateUpdatedOrder!=null}
            .groupBy{Triple(it.holder,it.claimUid,requireNotNull(it.stateVersion))}.entries
            .sortedWith(compareBy({it.key.first.holderKindUid},{it.key.first.holderUid},{it.key.second},{it.key.third}))
            .map{(_,rows)->assertion(rows,evidenceByAcquisition,generation)}
        return Phase37EpisodeMemoryDerivation(holderMemories,assertions)
    }

    fun persist(episode:EpisodeManifest,derivation:Phase37EpisodeMemoryDerivation=derive(episode)):Phase37EpisodeMemoryPersistence{
        val store=MemoryArtifactStore(db)
        val produced=mutableListOf<String>();val updated=mutableListOf<String>();val superseded=mutableListOf<String>()
        fun existed(revisionUid:String)=db.rawQuery("SELECT 1 FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=? AND artifact_revision_uid=?",
            arrayOf(campaignUid,revisionUid)).use{it.moveToFirst()}
        derivation.holderMemories.forEach{memory->
            if(existed(memory.identity.artifactRevisionUid))updated+=memory.identity.artifactRevisionUid else produced+=memory.identity.artifactRevisionUid
            superseded+=store.supersedePriorRevisions(memory.identity)
            store.upsert(memory.identity,MemoryArtifactStatus.CLEAN,holderMemoryJson(memory),listOf(episode.identity.artifactRevisionUid to MemoryDependencyKind.DERIVED_FROM))
        }
        derivation.semanticAssertions.forEach{assertion->
            if(existed(assertion.identity.artifactRevisionUid))updated+=assertion.identity.artifactRevisionUid else produced+=assertion.identity.artifactRevisionUid
            superseded+=store.supersedePriorRevisions(assertion.identity)
            store.upsert(assertion.identity,MemoryArtifactStatus.CLEAN,semanticAssertionJson(assertion),listOf(episode.identity.artifactRevisionUid to MemoryDependencyKind.DERIVED_FROM))
        }
        return Phase37EpisodeMemoryPersistence(produced.distinct(),updated.distinct(),superseded.distinct())
    }

    fun serializedPayloadBytes(derivation:Phase37EpisodeMemoryDerivation):Long =
        derivation.holderMemories.sumOf { holderMemoryJson(it).toByteArray(Charsets.UTF_8).size.toLong() } +
            derivation.semanticAssertions.sumOf { semanticAssertionJson(it).toByteArray(Charsets.UTF_8).size.toLong() }

    private fun acquisitions(episode:EpisodeManifest):List<AcquisitionRow>{
        val placeholders=episode.eventUids.joinToString(","){"?"}
        return db.rawQuery("""SELECT a.acquisition_uid,a.claim_uid,a.holder_kind_uid,a.holder_uid,a.method_uid,a.created_event_uid,a.created_order,
            s.epistemic_state_uid,s.confidence,s.source_reliability,s.state_version,s.state_uid,s.updated_order,
            c.subject_kind_uid,c.subject_uid,c.predicate_uid,c.value_canonical
            FROM ${Phase37KnowledgeSchema.ACQUISITIONS} a
            JOIN ${Phase37KnowledgeSchema.CLAIMS} c ON c.campaign_uid=a.campaign_uid AND c.claim_uid=a.claim_uid
            LEFT JOIN ${Phase37KnowledgeSchema.STATES} s ON s.campaign_uid=a.campaign_uid AND s.holder_kind_uid=a.holder_kind_uid
                AND s.holder_uid=a.holder_uid AND s.claim_uid=a.claim_uid AND s.latest_acquisition_uid=a.acquisition_uid
                AND s.updated_order<=?
            WHERE a.campaign_uid=? AND a.provenance_status=? AND a.created_event_uid IN ($placeholders)
            ORDER BY a.created_order,a.acquisition_uid""",arrayOf(episode.endOrder.toString(),campaignUid,KnowledgeProvenanceStatus.RECORDED.name,*episode.eventUids.toTypedArray())).use{cursor->
            buildList{while(cursor.moveToNext())add(AcquisitionRow(
                cursor.getString(0),cursor.getString(1),KnowledgeHolderRef(cursor.getString(2),cursor.getString(3),campaignUid),cursor.getString(4),
                cursor.getString(5),cursor.getLong(6),if(cursor.isNull(7))null else KnowledgeEpistemicState.valueOf(cursor.getString(7)),
                if(cursor.isNull(8))null else cursor.getDouble(8),if(cursor.isNull(9))null else cursor.getDouble(9),if(cursor.isNull(10))null else cursor.getLong(10),
                if(cursor.isNull(11))null else cursor.getString(11),if(cursor.isNull(12))null else cursor.getLong(12),
                DomainRef(cursor.getString(13),cursor.getString(14)),cursor.getString(15),cursor.getString(16)
            ))}
        }
    }

    private fun evidence(acquisitions:List<AcquisitionRow>):List<EvidenceRow>{
        val uids=acquisitions.map{it.acquisitionUid}.distinct()
        val orderByUid=acquisitions.associate{it.acquisitionUid to it.createdOrder}
        val placeholders=uids.joinToString(","){"?"}
        return db.rawQuery("""SELECT evidence_uid,acquisition_uid,polarity_uid,evidence_kind_uid,source_event_uid,
            source_acquisition_uid,source_carrier_kind_uid,source_carrier_uid,source_ref_kind_uid,source_ref_uid
            FROM ${Phase37KnowledgeSchema.EVIDENCE} WHERE campaign_uid=? AND acquisition_uid IN ($placeholders)
            ORDER BY acquisition_uid,evidence_uid""",arrayOf(campaignUid,*uids.toTypedArray())).use{cursor->
            buildList{while(cursor.moveToNext()){
                fun string(index:Int)=if(cursor.isNull(index))"" else cursor.getString(index)
                val acquisitionUid=cursor.getString(1)
                val fingerprint=memorySha256((0 until cursor.columnCount).joinToString("|"){string(it)})
                add(EvidenceRow(cursor.getString(0),acquisitionUid,KnowledgeEvidencePolarity.valueOf(cursor.getString(2)),orderByUid.getValue(acquisitionUid),fingerprint))
            }}
        }
    }

    private fun acquisitionLeaf(row:AcquisitionRow)=MemorySourceLeafRef(
        "KNOWLEDGE_ACQUISITION",row.acquisitionUid,1,row.createdOrder,
        memorySha256(listOf(row.claimUid,row.holder.holderKindUid,row.holder.holderUid,row.methodUid,row.eventUid,row.createdOrder).joinToString("|"))
    )

    private fun assertion(rows:List<AcquisitionRow>,evidenceByAcquisition:Map<String,List<EvidenceRow>>,generation:HistoryGenerationUid):SemanticMemoryAssertion{
        val latest=rows.maxWith(compareBy<AcquisitionRow>{it.createdOrder}.thenBy{it.acquisitionUid})
        val evidence=evidenceByAcquisition[latest.acquisitionUid].orEmpty().ifEmpty{rows.flatMap{evidenceByAcquisition[it.acquisitionUid].orEmpty()}}
        check(evidence.isNotEmpty()){"RPGOS-MEMORY:PHASE37_EVIDENCE_REQUIRED"}
        val evidenceLeaves=evidence.map{MemorySourceLeafRef("KNOWLEDGE_EVIDENCE",it.evidenceUid,1,it.createdOrder,it.fingerprint)}
        val stateVersion=requireNotNull(latest.stateVersion);val stateUid=requireNotNull(latest.stateUid);val stateOrder=requireNotNull(latest.stateUpdatedOrder)
        val epistemicState=requireNotNull(latest.epistemic)
        val stateLeaf=MemorySourceLeafRef("KNOWLEDGE_STATE",stateUid,stateVersion,stateOrder,
            memorySha256(listOf(stateUid,stateVersion,stateOrder,epistemicState.name,latest.confidence,latest.reliability,latest.acquisitionUid).joinToString("|")))
        val leaves=evidenceLeaves+stateLeaf
        val fingerprint=memoryLeafFingerprint(leaves)
        val logical="RPGOS-P57-ASSERT:${latest.holder.holderKindUid}:${latest.holder.holderUid}:${latest.claimUid}"
        val polarity=when(epistemicState){
            KnowledgeEpistemicState.DISBELIEVED,KnowledgeEpistemicState.CONTRADICTED->SemanticAssertionPolarity.DENIED
            KnowledgeEpistemicState.PARTIALLY_KNOWN,KnowledgeEpistemicState.OUTDATED->SemanticAssertionPolarity.PARTIAL
            else->SemanticAssertionPolarity.AFFIRMED
        }
        val epistemic=when(epistemicState){
            KnowledgeEpistemicState.KNOWN->SemanticAssertionEpistemicKind.KNOWLEDGE
            KnowledgeEpistemicState.BELIEVED,KnowledgeEpistemicState.DOUBTED,KnowledgeEpistemicState.DISBELIEVED,KnowledgeEpistemicState.CONTRADICTED->SemanticAssertionEpistemicKind.BELIEF
            KnowledgeEpistemicState.SUSPECTED->SemanticAssertionEpistemicKind.HYPOTHESIS
            KnowledgeEpistemicState.PARTIALLY_KNOWN,KnowledgeEpistemicState.OUTDATED->SemanticAssertionEpistemicKind.MEMORY
        }
        return SemanticMemoryAssertion(
            identity=MemoryArtifactIdentity(campaignUid,generation,logical,
                "RPGOS-P57-ASSERT-REV:${memorySha256("$logical|${generation.value}|$fingerprint|$stateVersion|$epistemicState")}",
                MemoryArtifactKind.SEMANTIC_ASSERTION,leaves,fingerprint,"RPGOS-P57-PHASE37-STATE",1,
                stateOrder,rows.minOf{it.createdOrder},stateOrder),
            assertionUid=logical,holder=latest.holder,subjectRef=latest.subject,predicateUid=latest.predicateUid,objectValue=latest.objectValue,
            polarity=polarity,epistemicKind=epistemic,validFromOrder=rows.minOf{it.createdOrder},
            validUntilOrder=if(epistemicState==KnowledgeEpistemicState.OUTDATED)stateOrder else null,
            lifecycleState=when(epistemicState){KnowledgeEpistemicState.OUTDATED->SemanticAssertionLifecycle.EXPIRED;KnowledgeEpistemicState.CONTRADICTED->SemanticAssertionLifecycle.CONTESTED;else->SemanticAssertionLifecycle.ACTIVE},
            supportingLeafRefs=evidenceLeaves,contradictingLeafRefs=evidence.filter{it.polarity==KnowledgeEvidencePolarity.CONTRADICTS}.map{MemorySourceLeafRef("KNOWLEDGE_EVIDENCE",it.evidenceUid,1,it.createdOrder,it.fingerprint)}
        )
    }

    private fun methodReliability(methodUid:String)=when(methodUid){
        KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,KnowledgeAcquisitionMethods.EXPERIMENT->0.9
        KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION,KnowledgeAcquisitionMethods.DOCUMENT,KnowledgeAcquisitionMethods.REPORT->0.75
        KnowledgeAcquisitionMethods.RUMOR->0.35
        KnowledgeAcquisitionMethods.UNKNOWN_NOT_RECORDED,KnowledgeAcquisitionMethods.LEGACY->0.25
        else->0.6
    }

    private fun holderMemoryJson(value:HolderEpisodeMemory)=JSONObject().apply{
        put("episode_uid",value.episodeLogicalUid);put("holder_kind_uid",value.holder.holderKindUid);put("holder_uid",value.holder.holderUid)
        put("remembered_event_uids",JSONArray(value.rememberedEventUids));put("omitted_event_uids",JSONArray(value.omittedEventUids))
        put("accuracy",value.accuracy.value);put("salience",value.salience.value);put("recall_strength",value.recallStrength.value)
        put("acquisition_uids",JSONArray(value.acquisitionUids))
    }.toString()

    private fun semanticAssertionJson(value:SemanticMemoryAssertion)=JSONObject().apply{
        put("assertion_uid",value.assertionUid);put("holder_kind_uid",value.holder?.holderKindUid);put("holder_uid",value.holder?.holderUid)
        put("subject_kind_uid",value.subjectRef.kindUid);put("subject_uid",value.subjectRef.uid);put("predicate_uid",value.predicateUid);put("object_value",value.objectValue)
        put("polarity",value.polarity.name);put("epistemic_kind",value.epistemicKind.name);put("valid_from_order",value.validFromOrder);put("valid_until_order",value.validUntilOrder)
        put("lifecycle",value.lifecycleState.name);put("supporting_leaf_uids",JSONArray(value.supportingLeafRefs.map{it.sourceUid}));put("contradicting_leaf_uids",JSONArray(value.contradictingLeafRefs.map{it.sourceUid}))
    }.toString()
}
