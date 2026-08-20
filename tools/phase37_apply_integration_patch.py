from pathlib import Path


def replace(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))
    print(f"patched {path}")


# Phase 17 codec registry: preserve all accepted codecs and append the Phase-37 codec.
replace(
    "app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt",
    "fun core(): TypedPlayerChangeRegistry = TypedPlayerChangeRegistry(coreChangeCodecs())",
    "fun core(): TypedPlayerChangeRegistry = TypedPlayerChangeRegistry(\n            coreChangeCodecs() + mapOf(PHASE37_KNOWLEDGE_CHANGE_KIND to phase37KnowledgeChangeCodec())\n        )",
)

# TurnTransaction canonical applier and replay path.
replace(
    "app/src/main/java/com/rpgos/app/TurnTransaction.kt",
    "is EquipmentChange,is FinancialChange,is OwnershipChange,is CampaignTruthChange,\n                is DevelopmentProjectChange -> Unit",
    "is EquipmentChange,is FinancialChange,is OwnershipChange,is CampaignTruthChange,\n                is DevelopmentProjectChange,is KnowledgeAcquisitionChange -> Unit",
)
replace(
    "app/src/main/java/com/rpgos/app/TurnTransaction.kt",
    "is CampaignTruthChange->applyTruth(db,identity,changeSet,change.changeUid,payload)\n                is DevelopmentProjectChange->applyProject(db,identity,changeSet,change.changeUid,payload)",
    "is CampaignTruthChange->applyTruth(db,identity,changeSet,change.changeUid,payload)\n                is KnowledgeAcquisitionChange->applyKnowledge(db,identity,changeSet,change.changeUid,payload)\n                is DevelopmentProjectChange->applyProject(db,identity,changeSet,change.changeUid,payload)",
)
replace(
    "app/src/main/java/com/rpgos/app/TurnTransaction.kt",
    "    private fun applyProject(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:DevelopmentProjectChange){",
    '''    private fun applyKnowledge(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:KnowledgeAcquisitionChange){
        val intents=changeSet.eventIntents.filter{changeUid in it.causalChangeUids}
        require(intents.size==1){"RPGOS-KNOWLEDGE:ACQUISITION_REQUIRES_EXACT_EVENT"}
        val eventUid=CampaignEventStore(db,identity.campaignUid).eventUid(identity,changeSet,intents.single())
        KnowledgeStore(db,identity.campaignUid).stageRecorded(p,identity,eventUid,effectiveOrder(changeSet))
    }

    private fun applyProject(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:DevelopmentProjectChange){''',
)

# Event Store: knowledge is explicitly event-bearing and holder-scoped.
replace(
    "app/src/main/java/com/rpgos/app/CampaignEventStore.kt",
    "is DevelopmentProjectChange -> PlayerChangeKinds.DEVELOPMENT_PROJECT\n            else -> throw EventStoreIntegrityException(\"UNCLASSIFIED_CHANGE_KIND\")",
    "is DevelopmentProjectChange -> PlayerChangeKinds.DEVELOPMENT_PROJECT\n            is KnowledgeAcquisitionChange -> PHASE37_KNOWLEDGE_CHANGE_KIND\n            else -> throw EventStoreIntegrityException(\"UNCLASSIFIED_CHANGE_KIND\")",
)
replace(
    "app/src/main/java/com/rpgos/app/CampaignEventStore.kt",
    "is DevelopmentProjectChange -> DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid)\n        else -> throw EventStoreIntegrityException(\"UNCLASSIFIED_CHANGE_KIND\")",
    "is DevelopmentProjectChange -> DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid)\n        is KnowledgeAcquisitionChange -> DomainRef(payload.acquisition.holder.holderKindUid, payload.acquisition.holder.holderUid)\n        else -> throw EventStoreIntegrityException(\"UNCLASSIFIED_CHANGE_KIND\")",
)

# Phase-19 deterministic WorldRule snapshot includes Phase-37 proposal semantics.
replace(
    "app/src/main/java/com/rpgos/app/WorldRuleProvider.kt",
    "is DevelopmentProjectChange -> \"DEVELOPMENT_PROJECT_CHANGE\"\n    }",
    "is DevelopmentProjectChange -> \"DEVELOPMENT_PROJECT_CHANGE\"\n        is KnowledgeAcquisitionChange -> \"KNOWLEDGE_ACQUISITION_CHANGE\"\n    }",
)
replace(
    "app/src/main/java/com/rpgos/app/WorldRuleProvider.kt",
    '''            is DevelopmentProjectChange -> {
                field("PROJECT_UID", payload.projectUid); field("WORK_RESULT_KIND_UID", payload.workResultKindUid)
                longField("PROGRESS_DELTA", payload.progressDelta.units)
                list("EVIDENCE_REFS", payload.evidenceRefs) { ref -> domainRef("EVIDENCE_REF", ref) }
            }
        }
    }
}''',
    '''            is DevelopmentProjectChange -> {
                field("PROJECT_UID", payload.projectUid); field("WORK_RESULT_KIND_UID", payload.workResultKindUid)
                longField("PROGRESS_DELTA", payload.progressDelta.units)
                list("EVIDENCE_REFS", payload.evidenceRefs) { ref -> domainRef("EVIDENCE_REF", ref) }
            }
            is KnowledgeAcquisitionChange -> {
                section("CLAIM") {
                    field("UID", payload.claim.claimUid); field("SUBJECT_KIND", payload.claim.subjectKindUid)
                    field("SUBJECT_UID", payload.claim.subjectUid); field("PREDICATE", payload.claim.predicateUid)
                    field("VALUE", payload.claim.valueCanonical); nullableField("OBJECT_KIND", payload.claim.objectKindUid)
                    nullableField("OBJECT_UID", payload.claim.objectUid); field("DOMAIN", payload.claim.domainUid)
                }
                section("ACQUISITION") {
                    field("UID", payload.acquisition.acquisitionUid)
                    field("HOLDER_KIND", payload.acquisition.holder.holderKindUid); field("HOLDER_UID", payload.acquisition.holder.holderUid)
                    field("METHOD", payload.acquisition.methodUid); field("SCOPE", payload.acquisition.scope.name)
                    field("EPISTEMIC_STATE", payload.acquisition.epistemicState.name)
                    field("CONFIDENCE", payload.acquisition.quality.confidence.toString())
                    field("PRECISION", payload.acquisition.quality.precision.toString())
                    field("COMPLETENESS", payload.acquisition.quality.completeness.toString())
                    field("SOURCE_RELIABILITY", payload.acquisition.quality.sourceReliability.toString())
                    longField("CORROBORATION_COUNT", payload.acquisition.quality.corroborationCount.toLong())
                    nullableLongField("SOURCE_OBSERVED_ORDER", payload.acquisition.quality.sourceObservedOrder)
                    nullableField("PARENT_ACQUISITION", payload.acquisition.parentAcquisitionUid)
                    nullableField("SOURCE_HOLDER_KIND", payload.acquisition.sourceHolder?.holderKindUid)
                    nullableField("SOURCE_HOLDER_UID", payload.acquisition.sourceHolder?.holderUid)
                    nullableField("ROLE_UID", payload.acquisition.roleUid)
                    nullableField("CARRIER_KIND", payload.acquisition.carrier?.carrierKindUid)
                    nullableField("CARRIER_UID", payload.acquisition.carrier?.carrierUid)
                    field("PROVENANCE", payload.acquisition.provenanceStatus.name)
                }
                list("KNOWLEDGE_EVIDENCE", payload.evidence) { e ->
                    record("KNOWLEDGE_EVIDENCE") {
                        field("UID", e.evidenceUid); field("KIND", e.evidenceKindUid); field("POLARITY", e.polarity.name)
                        nullableField("SOURCE_ACQUISITION", e.sourceAcquisitionUid)
                        nullableField("SOURCE_CARRIER_KIND", e.sourceCarrier?.carrierKindUid)
                        nullableField("SOURCE_CARRIER_UID", e.sourceCarrier?.carrierUid)
                        nullableField("SOURCE_REF_KIND", e.sourceRefKindUid); nullableField("SOURCE_REF_UID", e.sourceRefUid)
                    }
                }
            }
        }
    }
}''',
)

# PlayerDomainEngine reference validation remains generic; holder/source-holder are scoped refs.
replace(
    "app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt",
    '''            is DevelopmentProjectChange -> {
                add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid))
                addAll(payload.evidenceRefs)
            }
        }
    }''',
    '''            is DevelopmentProjectChange -> {
                add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid))
                addAll(payload.evidenceRefs)
            }
            is KnowledgeAcquisitionChange -> {
                add(DomainRef(payload.acquisition.holder.holderKindUid, payload.acquisition.holder.holderUid))
                payload.acquisition.sourceHolder?.let { add(DomainRef(it.holderKindUid, it.holderUid)) }
                if (payload.evidence.any { it.sourceRefKindUid != null && it.sourceRefUid != null }) {
                    payload.evidence.forEach { e ->
                        if (e.sourceRefKindUid != null && e.sourceRefUid != null) add(DomainRef(e.sourceRefKindUid, e.sourceRefUid))
                    }
                }
            }
        }
    }''',
)

# Phase-33 replay coverage now includes the canonical Phase-37 writer family.
replace(
    "app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt",
    '"OWNERSHIP_HISTORY","FINANCE_AUTHORITY","DEVELOPMENT_PROJECTS"',
    '"OWNERSHIP_HISTORY","FINANCE_AUTHORITY","DEVELOPMENT_PROJECTS","NPC_KNOWLEDGE_STATE"',
)

# ContextBuilder consumes Phase-37 projection and no longer interprets information_knowledge directly.
replace(
    "app/src/main/java/com/rpgos/app/ContextBuilder.kt",
    '''        val relevantNpcIds=LinkedHashSet<String>();safeQueryMany(saveDb,"SELECT entity_uid,location_uid,priority,summary FROM npc_schedules WHERE visibility IN ('gm','public') ORDER BY priority DESC LIMIT 20").forEach{(it["entity_uid"] as? String)?.let(relevantNpcIds::add)};safeQueryMany(saveDb,"SELECT holder_uid FROM information_knowledge ORDER BY confidence DESC,learned_chapter DESC LIMIT 20").forEach{(it["holder_uid"] as? String)?.let(relevantNpcIds::add)}
        val npcRows=mutableListOf<Map<String,Any?>>();val knowledgeRows=mutableListOf<Map<String,Any?>>();val npcMemoryRows=mutableListOf<Map<String,Any?>>()
        for(id in relevantNpcIds.take(16)){val npc=safeQueryOne(worldDb,"SELECT character_uid,name,sex,birth_era,clan_uid,village_uid,rank_title,affiliation_summary,personality_summary,combat_summary FROM canon_characters_v2 WHERE character_uid=? LIMIT 1",arrayOf(id));if(npc.isNotEmpty())npcRows+=npc;knowledgeRows+=safeQueryMany(saveDb,"SELECT k.holder_uid,k.info_uid,k.confidence,k.accuracy,k.acquisition_method,k.learned_chapter,f.title,f.content_summary,f.secrecy_level FROM information_knowledge k LEFT JOIN information_facts f ON f.info_uid=k.info_uid WHERE k.holder_uid=? ORDER BY k.confidence DESC,k.learned_chapter DESC LIMIT 16",arrayOf(id));npcMemoryRows+=safeQueryMany(saveDb,"SELECT memory_uid,entity_uid,memory_type,subject_uid,chapter,day,importance,emotional_valence,accuracy,summary FROM npc_memories_v2 WHERE entity_uid=? AND active=1 ORDER BY importance DESC,chapter DESC LIMIT 12",arrayOf(id))}''',
    '''        val relevantNpcIds=LinkedHashSet<String>();safeQueryMany(saveDb,"SELECT entity_uid,location_uid,priority,summary FROM npc_schedules WHERE visibility IN ('gm','public') ORDER BY priority DESC LIMIT 20").forEach{(it["entity_uid"] as? String)?.let(relevantNpcIds::add)};KnowledgeContextHolderDiscovery.characterHolderUids(saveDb,campaignRef.campaignId,20).forEach(relevantNpcIds::add)
        val npcRows=mutableListOf<Map<String,Any?>>();val knowledgeRows=mutableListOf<Map<String,Any?>>();val npcMemoryRows=mutableListOf<Map<String,Any?>>();val knowledgeProjection=KnowledgeContextProjection(saveDb,campaignRef.campaignId)
        for(id in relevantNpcIds.take(16)){val npc=safeQueryOne(worldDb,"SELECT character_uid,name,sex,birth_era,clan_uid,village_uid,rank_title,affiliation_summary,personality_summary,combat_summary FROM canon_characters_v2 WHERE character_uid=? LIMIT 1",arrayOf(id));if(npc.isNotEmpty())npcRows+=npc;knowledgeRows+=knowledgeProjection.forHolders(listOf(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,id)));npcMemoryRows+=safeQueryMany(saveDb,"SELECT memory_uid,entity_uid,memory_type,subject_uid,chapter,day,importance,emotional_valence,accuracy,summary FROM npc_memories_v2 WHERE entity_uid=? AND active=1 ORDER BY importance DESC,chapter DESC LIMIT 12",arrayOf(id))}''',
)

print("Phase 37 integration patch complete")
