from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def p(path: str) -> Path:
    return ROOT / path

def read(path: str) -> str:
    return p(path).read_text()

def write(path: str, content: str) -> None:
    p(path).write_text(content)

def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    if text.count(old) != 1:
        raise SystemExit(f"expected one anchor in {path}, found {text.count(old)}: {old[:160]!r}")
    write(path, text.replace(old, new, 1))

# -----------------------------------------------------------------------------
# P38-POST-HARD-AUD-001 — normal WORLD_ACTOR reasoning consumes trusted Slice-D
# perception for observable world events. Raw objective event rows never enter the actor bundle.
# -----------------------------------------------------------------------------
replace_once(
    "app/src/main/java/com/rpgos/app/ContextBuilder.kt",
    """    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService(),
    private val protectedReadsOverride: ProtectedCampaignReadRepository? = null
) {""",
    """    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService(),
    private val protectedReadsOverride: ProtectedCampaignReadRepository? = null,
    private val worldActorPerceptionRuntime: Phase38WorldActorPerceptionRuntime? = null
) {"""
)

replace_once(
    "app/src/main/java/com/rpgos/app/ContextBuilder.kt",
    """        val threads = diagnosticRows(\"STORY_THREADS\") { queryMany(saveDb,\"SELECT thread_uid,title,thread_type,status,priority,last_advanced_chapter,description FROM story_threads WHERE status='active' ORDER BY priority DESC,last_advanced_chapter DESC LIMIT 20\") }
        val missions = queryMany(saveDb,\"SELECT mission_uid,title,mission_rank,status,objective_summary,reward_ryo,deadline_day,location_uid,consequence_on_failure FROM missions_v3 WHERE status IN ('available','active','assigned') ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'assigned' THEN 1 ELSE 2 END,reward_ryo DESC LIMIT 20\")
        val pressures = queryMany(saveDb,\"SELECT pressure_uid,target_type,target_uid,starts_day,peaks_day,pressure_type,magnitude,summary FROM future_world_pressure WHERE hidden=0 ORDER BY magnitude DESC LIMIT 20\")
        val activeWorldEvents = WorldReader(worldDb,saveDb,visibility).activeEvents(audience,purpose).map { e -> mapOf(\"name\" to e.name,\"status\" to e.status,\"summary\" to e.summary) }
""",
    """        val threads = diagnosticRows(\"STORY_THREADS\") { queryMany(saveDb,\"SELECT thread_uid,title,thread_type,status,priority,last_advanced_chapter,description FROM story_threads WHERE status='active' ORDER BY priority DESC,last_advanced_chapter DESC LIMIT 20\") }
        val worldActorReasoning = audience.audienceKindUid == AudienceKinds.WORLD_ACTOR && purpose.purposeUid == VisibilityPurposeKinds.WORLD_ACTOR_REASONING
        // Missions/future pressure are objective/system domains, not perception. Until a canonical
        // actor knowledge/access carrier exists for them they are category F and stay out of actor reasoning.
        val missions = if(worldActorReasoning) emptyList() else queryMany(saveDb,\"SELECT mission_uid,title,mission_rank,status,objective_summary,reward_ryo,deadline_day,location_uid,consequence_on_failure FROM missions_v3 WHERE status IN ('available','active','assigned') ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'assigned' THEN 1 ELSE 2 END,reward_ryo DESC LIMIT 20\")
        val pressures = if(worldActorReasoning) emptyList() else queryMany(saveDb,\"SELECT pressure_uid,target_type,target_uid,starts_day,peaks_day,pressure_type,magnitude,summary FROM future_world_pressure WHERE hidden=0 ORDER BY magnitude DESC LIMIT 20\")
        val objectiveWorldEvents = WorldReader(worldDb,saveDb,visibility).activeEvents(audience,purpose)
        val activeWorldEvents: List<Map<String,Any?>> = if(worldActorReasoning){
            val runtime = worldActorPerceptionRuntime
            val trusted = trustedPrincipal
            if(runtime == null || trusted == null) emptyList() else objectiveWorldEvents.mapNotNull { objective ->
                val projected = runtime.projectWorldEvent(audience,trusted,objective)
                if(projected.decision.dataState != ProjectionDataState.DISCLOSED) null else linkedMapOf<String,Any?>().apply {
                    putAll(projected.presentationPayload())
                    put(\"subject_uid\", projected.subject.subjectUid)
                    put(\"perception_disclosure\", projected.decision.level.name)
                }
            }
        } else objectiveWorldEvents.map { e -> mapOf(\"name\" to e.name,\"status\" to e.status,\"summary\" to e.summary) }
"""
)

# LocalGameStore owns the runtime sources; buildContext callers cannot inject them.
replace_once(
    "app/src/main/java/com/rpgos/app/LocalGameStore.kt",
    """    private val worldDir: File get() = File(baseDir, \"worldpacks/${selection.activeWorldPackDirName()}\")
    private val coreDir = File(baseDir, \"core\")
""",
    """    private val worldDir: File get() = File(baseDir, \"worldpacks/${selection.activeWorldPackDirName()}\")
    private val coreDir = File(baseDir, \"core\")
    private val worldActorPerceptionRuntime = Phase38WorldActorPerceptionRuntime()
"""
)

replace_once(
    "app/src/main/java/com/rpgos/app/LocalGameStore.kt",
    """                return ContextBuilder(save, world, protectedReadsOverride=reads).build(playerInput, chapter, audience, purpose)
""",
    """                return ContextBuilder(save, world, protectedReadsOverride=reads, worldActorPerceptionRuntime=worldActorPerceptionRuntime).build(playerInput, chapter, audience, purpose)
"""
)

replace_once(
    "app/src/main/java/com/rpgos/app/LocalGameStore.kt",
    """            return ContextBuilder(save,world,protectedReadsOverride=reads).build(playerInput,chapter,audience,purpose)
        }}
    }

    internal fun activeCampaignId(): String = selection.activeCampaignRef().campaignId
""",
    """            return ContextBuilder(save,world,protectedReadsOverride=reads,worldActorPerceptionRuntime=worldActorPerceptionRuntime).build(playerInput,chapter,audience,purpose)
        }}
    }

    internal fun issueWorldActorEventSignal(
        event:WorldEventItem,
        evidence:Map<String,Any?>,
        quality:Double=1.0,
        uncertainty:PerceptionUncertainty=PerceptionUncertainty(1.0,1.0,1.0),
        presentedSubject:VisibilitySubjectRef?=null
    ):PerceptionSignal = worldActorPerceptionRuntime.issueWorldEventSignal(
        activeCampaignId(),event,evidence,quality,uncertainty,presentedSubject
    )

    internal fun issueWorldActorEventCapability(
        audience:AudienceContext,
        minimumDetectionQuality:Double=0.0,
        maximumDisclosure:DisclosureLevel=DisclosureLevel.DISCLOSE_FULL,
        capabilityUid:String=\"WORLD_EVENT:${audience.principal?.kindUid}:${audience.principal?.uid}\"
    ):PerceptionCapability {
        requireActiveVisibility(audience,PurposeContext(audience.campaignUid,VisibilityPurposeKinds.WORLD_ACTOR_REASONING))
        require(audience.audienceKindUid==AudienceKinds.WORLD_ACTOR){\"RPGOS-P38-PERCEPTION:WORLD_ACTOR_REQUIRED\"}
        openGameplaySaveDb().use { db ->
            val active=ActivePlayerStore(db,activeCampaignId()).active()
            val reads=ProtectedCampaignReadRepository.borrowed(db,activeCampaignId()){active}
            val trusted=requireNotNull(reads.trustedPrincipal(audience)){\"RPGOS-P38-PERCEPTION:TRUSTED_OBSERVER_REQUIRED\"}
            return worldActorPerceptionRuntime.issueWorldEventCapability(trusted,minimumDetectionQuality,maximumDisclosure,capabilityUid)
        }
    }

    internal fun clearWorldActorPerception() = worldActorPerceptionRuntime.clearCampaign(activeCampaignId())

    internal fun activeCampaignId(): String = selection.activeCampaignRef().campaignId
"""
)

# Unified facade exposes runtime-feed hooks only as internal infrastructure. Public CampaignRepository
# remains incapable of supplying signals/capabilities to buildContext.
replace_once(
    "app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt",
    """    internal fun infrastructureBuildTrustedContext(playerInput:String,chapter:Int,audience:AudienceContext,purpose:PurposeContext,trusted:TrustedPrincipalContext):ContextBundle =
        store.buildTrustedContext(playerInput,chapter,audience,purpose,trusted)
""",
    """    internal fun infrastructureBuildTrustedContext(playerInput:String,chapter:Int,audience:AudienceContext,purpose:PurposeContext,trusted:TrustedPrincipalContext):ContextBundle =
        store.buildTrustedContext(playerInput,chapter,audience,purpose,trusted)
    internal fun infrastructureIssueWorldActorEventSignal(
        event:WorldEventItem,evidence:Map<String,Any?>,quality:Double=1.0,
        uncertainty:PerceptionUncertainty=PerceptionUncertainty(1.0,1.0,1.0),presentedSubject:VisibilitySubjectRef?=null
    ):PerceptionSignal = store.issueWorldActorEventSignal(event,evidence,quality,uncertainty,presentedSubject)
    internal fun infrastructureIssueWorldActorEventCapability(
        audience:AudienceContext,minimumDetectionQuality:Double=0.0,
        maximumDisclosure:DisclosureLevel=DisclosureLevel.DISCLOSE_FULL,capabilityUid:String=\"WORLD_EVENT:${audience.principal?.kindUid}:${audience.principal?.uid}\"
    ):PerceptionCapability = store.issueWorldActorEventCapability(audience,minimumDetectionQuality,maximumDisclosure,capabilityUid)
    internal fun infrastructureClearWorldActorPerception() = store.clearWorldActorPerception()
"""
)

print("P38-POST-HARD-AUD-001 materialized")
