from pathlib import Path
import subprocess

BASELINE="e4b539fa87113c5ec46da2facf282c8004dc7e44"

def replace_once(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s:
        raise SystemExit(f"missing patch anchor: {path}: {old[:120]!r}")
    if s.count(old)!=1:
        raise SystemExit(f"non-unique patch anchor: {path}: {s.count(old)}")
    p.write_text(s.replace(old,new,1))

def replace_all(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s:
        raise SystemExit(f"missing patch anchor: {path}: {old[:120]!r}")
    p.write_text(s.replace(old,new))

# ContextBuilder is itself a protected-read boundary: no implicit audience and no Throwable->ignorance.
replace_once("app/src/main/java/com/rpgos/app/ContextBuilder.kt",
'''    /** Compatibility entry is deliberately low privilege, never omniscient. */
    fun build(playerInput: String, chapter: Int): ContextBundle {
        val campaignUid = ActiveCampaignRef.fromDatabasePath(saveDb.path).campaignId
        return build(
            playerInput, chapter,
            VisibilityAudienceFactory.player(campaignUid),
            PurposeContext(campaignUid, VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        )
    }

''','')
replace_once("app/src/main/java/com/rpgos/app/ContextBuilder.kt",
    'mapOf("title" to e.title,"status" to e.status,"summary" to e.summary)',
    'mapOf("name" to e.name,"status" to e.status,"summary" to e.summary)')
replace_once("app/src/main/java/com/rpgos/app/ContextBuilder.kt",
'''        } catch (t: Throwable) {
            throw VisibilityAuthorityFailure.CorruptAuthority("PROTECTED_READ:${t::class.simpleName}:${t.message}")
        }
''',
'''        } catch (failure: Exception) {
            throw VisibilityAuthorityFailure.CorruptRead("CONTEXT_QUERY", failure)
        }
''')

# LocalGameStore cannot invent authority and cannot re-escalate ContextBuilder output.
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
'''    fun buildContext(playerInput: String, chapter: Int, audience: AudienceContext, purpose: PurposeContext): ContextBundle {
        val campaignId = selection.activeCampaignRef().campaignId
        if (audience.campaignUid != campaignId || purpose.campaignUid != campaignId) throw VisibilityAuthorityFailure.CrossCampaign()
        openGameplaySaveDb().use { save ->
            openWorldDb().use { world ->
                return ContextBuilder(save, world).build(playerInput, chapter, audience, purpose)
            }
        }
    }

    internal fun activeCampaignId(): String = selection.activeCampaignRef().campaignId
''')
replace_once("app/src/main/java/com/rpgos/app/LocalGameStore.kt",
'    fun fullCharacterPanel(): CharacterPanelSnapshot { openGameplaySaveDb().use { db -> val playerUid = ActivePlayerStore(db, selection.activeCampaignRef().campaignId).active()?.playerUid; return CharacterPanelReader(db, playerUid).load() } }',
'    fun fullCharacterPanel(audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot { val campaign=activeCampaignId();if(audience.campaignUid!=campaign||purpose.campaignUid!=campaign)throw VisibilityAuthorityFailure.CrossCampaign();openGameplaySaveDb().use { db -> val playerUid = ActivePlayerStore(db, campaign).active()?.playerUid; return CharacterPanelReader(db, playerUid).load(audience,purpose) } }')
replacements={
'fun npcs(search:String=""):List<NpcListItem>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcs(search)}} }':
'fun npcs(search:String,audience:AudienceContext,purpose:PurposeContext):List<NpcListItem>{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcs(search,audience,purpose)}} }',
'fun npcDetail(uid:String):NpcDetail{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcDetail(uid)}} }':
'fun npcDetail(uid:String,audience:AudienceContext,purpose:PurposeContext):NpcDetail{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcDetail(uid,audience,purpose)}} }',
'fun relationEdges():List<RelationEdge>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).relationEdges()}} }':
'fun relationEdges(audience:AudienceContext,purpose:PurposeContext):List<RelationEdge>{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).relationEdges(audience,purpose)}} }',
'fun economies():List<EconomySummary>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).economies()}} }':
'fun economies(audience:AudienceContext,purpose:PurposeContext):List<EconomySummary>{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).economies(audience,purpose)}} }',
'fun wars():List<WarSummary>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).wars()}} }':
'fun wars(audience:AudienceContext,purpose:PurposeContext):List<WarSummary>{ requireActiveVisibility(audience,purpose);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).wars(audience,purpose)}} }',
'fun relationships(): List<RelationshipItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).relationships() } } }':
'fun relationships(audience:AudienceContext,purpose:PurposeContext): List<RelationshipItem> { requireActiveVisibility(audience,purpose);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).relationships(audience,purpose) } } }',
'fun organizations(): List<OrganizationItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).organizations() } } }':
'fun organizations(audience:AudienceContext,purpose:PurposeContext): List<OrganizationItem> { requireActiveVisibility(audience,purpose);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).organizations(audience,purpose) } } }',
'fun politics(): List<PoliticalItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).politics() } } }':
'fun politics(audience:AudienceContext,purpose:PurposeContext): List<PoliticalItem> { requireActiveVisibility(audience,purpose);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return SocialReader(world, save).politics(audience,purpose) } } }',
'fun activeWorldEvents(): List<WorldEventItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return WorldReader(world, save).activeEvents() } } }':
'fun activeWorldEvents(audience:AudienceContext,purpose:PurposeContext): List<WorldEventItem> { requireActiveVisibility(audience,purpose);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return WorldReader(world, save).activeEvents(audience,purpose) } } }'
}
for old,new in replacements.items(): replace_once("app/src/main/java/com/rpgos/app/LocalGameStore.kt",old,new)
replace_once("app/src/main/java/com/rpgos/app/LocalGameStore.kt",
'    internal fun activeCampaignId(): String = selection.activeCampaignRef().campaignId\n',
'''    internal fun activeCampaignId(): String = selection.activeCampaignRef().campaignId
    private fun requireActiveVisibility(audience:AudienceContext,purpose:PurposeContext){
        val campaign=activeCampaignId()
        if(audience.campaignUid!=campaign||purpose.campaignUid!=campaign)throw VisibilityAuthorityFailure.CrossCampaign()
    }
''')

# RpgOsViewModel is a classified presentation consumer; it must state audience/purpose at every protected call.
replace_once("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
'    private val appSettings = AppSettings(app)\n',
'''    private val appSettings = AppSettings(app)
    private fun playerAudience() = VisibilityAudienceFactory.player(store.activeCampaignId())
    private fun playerPurpose(uid:String) = PurposeContext(store.activeCampaignId(),uid)
    private fun diagnosticAudience() = VisibilityAudienceFactory.diagnostic(store.activeCampaignId())
    private fun diagnosticPurpose() = PurposeContext(store.activeCampaignId(),VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
''')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.fullCharacterPanel()', 'store.fullCharacterPanel(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.npcs()', 'store.npcs("",playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.relationEdges()', 'store.relationEdges(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.economies()', 'store.economies(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.wars()', 'store.wars(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.relationships()', 'store.relationships(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.organizations()', 'store.organizations(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.politics()', 'store.politics(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.activeWorldEvents()', 'store.activeWorldEvents(playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI))')
replace_once("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'fun searchNpcs(query:String){ _npcs.value=store.npcs(query) }', 'fun searchNpcs(query:String){ _npcs.value=store.npcs(query,playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI)) }')
replace_once("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'fun selectNpc(uid:String){ _selectedNpc.value=store.npcDetail(uid) }', 'fun selectNpc(uid:String){ _selectedNpc.value=store.npcDetail(uid,playerAudience(),playerPurpose(VisibilityPurposeKinds.PLAYER_UI)) }')

# Gameplay contexts are explicit; developer inspection is separately explicit.
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.buildContext("STARTUP_CONTEXT", chapter)', 'store.buildContext("STARTUP_CONTEXT", chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.GAMEPLAY_NARRATION))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.buildContext("DEV_SELF_TEST",chapter)', 'store.buildContext("DEV_SELF_TEST",chapter,diagnosticAudience(),diagnosticPurpose())')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.buildContext("CONTEXT_TEST",chapter)', 'store.buildContext("CONTEXT_TEST",chapter,diagnosticAudience(),diagnosticPurpose())')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.buildContext("BACKEND_TEST",chapter)', 'store.buildContext("BACKEND_TEST",chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.GAMEPLAY_NARRATION))')
replace_all("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt", 'store.buildContext(text, chapter)', 'store.buildContext(text, chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.GAMEPLAY_NARRATION))')
replace_once("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
'val context = store.buildContext(scenePrompt, chapter)\n                val prompt = VisualPromptBuilder().buildScenePrompt(scenePrompt, context)',
'val context = store.buildContext(scenePrompt,chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.SCENE_VISUALIZATION))\n                val prompt = VisualPromptBuilder().buildScenePrompt(scenePrompt, context)')

# Character/location visualization also require their own projection envelopes.
replace_once("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
'''                val prompt = VisualPromptBuilder().buildCharacterPrompt(
                    name,
                    traits.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    equipment.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    notes
                )''',
'''                val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
                val visualContext = store.buildContext("CHARACTER_VISUALIZATION:$name",chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.CHARACTER_VISUALIZATION))
                val prompt = VisualPromptBuilder().buildCharacterPrompt(
                    name,
                    traits.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    equipment.split(",").map { it.trim() }.filter { it.isNotBlank() },
                    notes,
                    visualContext
                )''')
replace_once("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
'''                val era = _time.value.era
                val prompt = VisualPromptBuilder().buildLocationPrompt(name, description, era)''',
'''                val era = _time.value.era
                val chapter = (_chronicle.value.maxOfOrNull { it.chapter } ?: 0) + 1
                val visualContext = store.buildContext("LOCATION_VISUALIZATION:$name",chapter,playerAudience(),playerPurpose(VisibilityPurposeKinds.LOCATION_VISUALIZATION))
                val prompt = VisualPromptBuilder().buildLocationPrompt(name, description, era, visualContext)''')

# Fallback narration context must carry a valid non-escalating envelope.
replace_once("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
'''                        recentChronicle = emptyList(),
                        retrievedLongTermMemory = emptyList()
                    )''',
'''                        recentChronicle = emptyList(),
                        retrievedLongTermMemory = emptyList(),
                        visibilityEnvelope = VisibilityAuthorityService().envelope(playerAudience(),playerPurpose(VisibilityPurposeKinds.GAMEPLAY_NARRATION))
                    )''')

# Backend defense-in-depth: it accepts only an already-authorized Phase38 projection.
backend=Path("backend/app.py")
backend.write_bytes(subprocess.check_output(["git","show",f"{BASELINE}:backend/app.py"]))
replace_once("backend/app.py", 'client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))\n', 'client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))\n\nPHASE38_AUTHORITY_UID = "RPGOS-P38-VISIBILITY-AUTHORITY-1"\nPHASE38_GM_PURPOSES = {"GAMEPLAY_NARRATION", "WORLD_ACTOR_REASONING"}\n')
replace_once("backend/app.py", '@app.post("/v1/gm/turn", response_model=TurnResponse)\ndef gm_turn(req: TurnRequest):\n', '''def _require_phase38_projection(req: TurnRequest):
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
replace_once("backend/app.py", '    if not os.environ.get("OPENAI_API_KEY"):\n        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")\n\n    model = os.environ.get("RPGOS_MODEL", "gpt-5.6")\n', '    if not os.environ.get("OPENAI_API_KEY"):\n        raise HTTPException(status_code=500, detail="OPENAI_API_KEY is not configured")\n    _require_phase38_projection(req)\n\n    model = os.environ.get("RPGOS_MODEL", "gpt-5.6")\n')
replace_once("backend/app.py", 'SYSTEM_PROMPT = """You are the Game Master for RPG OS.\n', 'SYSTEM_PROMPT = """You are the Game Master for RPG OS.\nThe supplied context_bundle is already a Phase38-authorized projection. Never reconstruct or infer protected information absent from that projection.\n')

print("Phase38 Slice A+B integration patch applied")
