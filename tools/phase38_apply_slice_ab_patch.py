from pathlib import Path
import subprocess

BASELINE="e4b539fa87113c5ec46da2facf282c8004dc7e44"

def replace_once(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s:
        raise SystemExit(f"missing patch anchor: {path}: {old[:100]!r}")
    if s.count(old)!=1:
        raise SystemExit(f"non-unique patch anchor: {path}: {s.count(old)}")
    p.write_text(s.replace(old,new,1))

# Core visibility subject for ordinary dashboards.
replace_once("app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '    const val PUBLIC_WAR_SUMMARY = "PUBLIC_WAR_SUMMARY"\n',
    '    const val PUBLIC_WAR_SUMMARY = "PUBLIC_WAR_SUMMARY"\n    const val PUBLIC_DASHBOARD_DATA = "PUBLIC_DASHBOARD_DATA"\n')
replace_once("app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '        VisibilitySubjectKinds.PUBLIC_WAR_SUMMARY, VisibilitySubjectKinds.WORLD_PRESENTATION\n',
    '        VisibilitySubjectKinds.PUBLIC_WAR_SUMMARY, VisibilitySubjectKinds.PUBLIC_DASHBOARD_DATA, VisibilitySubjectKinds.WORLD_PRESENTATION\n')

replace_once("app/src/main/java/com/rpgos/app/ContextBuilder.kt",
    'mapOf("title" to e.title,"status" to e.status,"summary" to e.summary)',
    'mapOf("name" to e.name,"status" to e.status,"summary" to e.summary)')

# LocalGameStore may consume a projection but may never widen it afterwards.
replace_once("app/src/main/java/com/rpgos/app/LocalGameStore.kt",
'''    fun buildContext(playerInput: String, chapter: Int): ContextBundle {
        openGameplaySaveDb().use { save ->
            openWorldDb().use { world ->
                val base = ContextBuilder(save, world).build(playerInput, chapter)
                val campaignId = selection.activeCampaignRef().campaignId
                val truth = CampaignTruthStore(save, campaignId).activeForContext(limit = 80)
                val state = PlayerStateStore(save, campaignId).load()
                val divergences = CanonDivergenceStore(save, campaignId).list()
                return base.copy(campaignTruth = truth, canonDivergences = divergences, playerState = state?.toContextMap() ?: emptyMap(), contextMeta = base.contextMeta + mapOf("campaign_truth_records" to truth.size, "canon_divergences" to divergences.size, "player_state_contract" to (state != null), "active_player_uid" to state?.activePlayer?.playerUid))
            }
        }
    }
''',
'''    fun buildContext(playerInput: String, chapter: Int): ContextBundle =
        buildContextForPurpose(playerInput, chapter, VisibilityPurposeKinds.GAMEPLAY_NARRATION)

    internal fun buildContextForPurpose(playerInput: String, chapter: Int, purposeUid: String): ContextBundle {
        val campaignId = selection.activeCampaignRef().campaignId
        val audience = VisibilityAudienceFactory.player(campaignId)
        val purpose = PurposeContext(campaignId, purposeUid)
        openGameplaySaveDb().use { save ->
            openWorldDb().use { world ->
                return ContextBuilder(save, world).build(playerInput, chapter, audience, purpose)
            }
        }
    }

    internal fun activeCampaignId(): String = selection.activeCampaignRef().campaignId
''')

replacements={
'fun npcs(search:String=""):List<NpcListItem>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcs(search)}} }':
'fun npcs(search:String=""):List<NpcListItem>{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcs(search,a,p)}} }',
'fun npcDetail(uid:String):NpcDetail{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcDetail(uid)}} }':
'fun npcDetail(uid:String):NpcDetail{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcDetail(uid,a,p)}} }',
'fun relationEdges():List<RelationEdge>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).relationEdges()}} }':
'fun relationEdges():List<RelationEdge>{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).relationEdges(a,p)}} }',
'fun economies():List<EconomySummary>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).economies()}} }':
'fun economies():List<EconomySummary>{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).economies(a,p)}} }',
'fun wars():List<WarSummary>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).wars()}} }':
'fun wars():List<WarSummary>{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).wars(a,p)}} }',
'fun relationships(): List<RelationshipItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).relationships() } } }':
'fun relationships(): List<RelationshipItem> { val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).relationships(a,p) } } }',
'fun organizations(): List<OrganizationItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).organizations() } } }':
'fun organizations(): List<OrganizationItem> { val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).organizations(a,p) } } }',
'fun politics(): List<PoliticalItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).politics() } } }':
'fun politics(): List<PoliticalItem> { val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).politics(a,p) } } }',
'fun activeWorldEvents(): List<WorldEventItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return WorldReader(world, save).activeEvents() } } }':
'fun activeWorldEvents(): List<WorldEventItem> { val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return WorldReader(world, save).activeEvents(a,p) } } }'
}
for old,new in replacements.items(): replace_once("app/src/main/java/com/rpgos/app/LocalGameStore.kt",old,new)

replace_once("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
    'val context = store.buildContext(scenePrompt, chapter)\n                val prompt = VisualPromptBuilder().buildScenePrompt(scenePrompt, context)',
    'val context = store.buildContextForPurpose(scenePrompt, chapter, VisibilityPurposeKinds.SCENE_VISUALIZATION)\n                val prompt = VisualPromptBuilder().buildScenePrompt(scenePrompt, context)')
replace_once("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
'''                        recentChronicle = emptyList(),
                        retrievedLongTermMemory = emptyList()
                    )''',
'''                        recentChronicle = emptyList(),
                        retrievedLongTermMemory = emptyList(),
                        visibilityEnvelope = VisibilityAuthorityService().envelope(
                            VisibilityAudienceFactory.player(store.activeCampaignId()),
                            PurposeContext(store.activeCampaignId(), VisibilityPurposeKinds.GAMEPLAY_NARRATION)
                        )
                    )''')

# Restore backend exactly from baseline, then make minimal Phase38 defense-in-depth edits.
backend=Path("backend/app.py")
backend.write_bytes(subprocess.check_output(["git","show",f"{BASELINE}:backend/app.py"]))
replace_once("backend/app.py",
    'client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))\n',
    'client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))\n\nPHASE38_AUTHORITY_UID = "RPGOS-P38-VISIBILITY-AUTHORITY-1"\nPHASE38_GM_PURPOSES = {"GAMEPLAY_NARRATION", "WORLD_ACTOR_REASONING"}\n')
replace_once("backend/app.py",
'''@app.post("/v1/gm/turn", response_model=TurnResponse)
def gm_turn(req: TurnRequest):
''',
'''def _require_phase38_projection(req: TurnRequest):
    envelope = req.context_bundle.get("visibility_envelope")
    if not isinstance(envelope, dict):
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:MISSING_PROJECTION_ENVELOPE")
    if envelope.get("authority_uid") != PHASE38_AUTHORITY_UID:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:INVALID_PROJECTION_AUTHORITY")
    if envelope.get("campaign_uid") != req.campaign_id:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:CROSS_CAMPAIGN_PROJECTION")
    if envelope.get("purpose_uid") not in PHASE38_GM_PURPOSES:
        raise HTTPException(status_code=400, detail="RPGOS-VISIBILITY:PURPOSE_NOT_AUTHORIZED_FOR_GM_BACKEND")
    if envelope.get("maximum_disclosure") == "DENY":
        raise HTTPException(status_code=403, detail="RPGOS-VISIBILITY:PROJECTION_DENIED")
    return envelope

@app.post("/v1/gm/turn", response_model=TurnResponse)
def gm_turn(req: TurnRequest):
''')
replace_once("backend/app.py",
'''    if not os.environ.get("OPENAI_API_KEY"):
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")

    model = os.environ.get("RPGOS_MODEL", "gpt-5.6")
''',
'''    if not os.environ.get("OPENAI_API_KEY"):
        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")
    _require_phase38_projection(req)

    model = os.environ.get("RPGOS_MODEL", "gpt-5.6")
''')
replace_once("backend/app.py",
    'SYSTEM_PROMPT = """You are the Game Master for RPG OS.\n',
    'SYSTEM_PROMPT = """You are the Game Master for RPG OS.\nThe supplied context_bundle is already a Phase38-authorized projection. Never reconstruct or infer protected information absent from that projection.\n')

# Strengthen inventory test across Kotlin + cloud backend markers.
replace_once("app/src/test/java/com/rpgos/app/Phase38VisibilityBoundaryTest.kt",
'''        val markers=listOf("gm_summary","npc_memories_v2","npc_beliefs","npc_schedules","npc_decisions","CampaignTruthStore(","KnowledgeContextProjection(")
        val unclassified=File(root,"app/src/main/java").walkTopDown().filter{it.isFile&&it.extension=="kt"}.mapNotNull{f->
            val text=f.readText();if(markers.any(text::contains)) f.relativeTo(root).invariantSeparatorsPath else null
        }.filter{VisibilityConsumerInventory.contractForSource(it)==null}.toList()
''',
'''        val markers=listOf("gm_summary","active_world_events","npc_memories_v2","npc_beliefs","npc_schedules","npc_decisions","CampaignTruthStore(","KnowledgeContextProjection(","political_entities","relationships_v2")
        val productionFiles=sequenceOf(File(root,"app/src/main/java"),File(root,"backend")).flatMap { dir ->
            if(!dir.isDirectory) emptySequence() else dir.walkTopDown().filter{it.isFile&&(it.extension=="kt"||it.extension=="py")}
        }
        val unclassified=productionFiles.mapNotNull{f->
            val text=f.readText();if(markers.any(text::contains)) f.relativeTo(root).invariantSeparatorsPath else null
        }.filter{VisibilityConsumerInventory.contractForSource(it)==null}.toList()
''')
replace_once("app/src/test/java/com/rpgos/app/Phase38VisibilityBoundaryTest.kt",
'''        assertTrue(runCatching{VisibilityConsumerInventory.requireClassified("app/src/main/java/com/rpgos/app/NewHiddenConsumer.kt")}.isFailure)
''',
'''        assertTrue(runCatching{VisibilityConsumerInventory.requireClassified("app/src/main/java/com/rpgos/app/NewHiddenConsumer.kt")}.isFailure)
        val backend=source("backend/app.py")
        assertTrue(backend.contains("_require_phase38_projection(req)"))
        assertTrue(backend.contains("PHASE38_AUTHORITY_UID"))
''')

print("Phase38 Slice A+B integration patch applied")
