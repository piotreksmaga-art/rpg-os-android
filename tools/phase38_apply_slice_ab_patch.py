from pathlib import Path


def replace_once(path, old, new):
    p=Path(path); s=p.read_text()
    if old not in s:
        raise SystemExit(f"missing patch anchor: {path}: {old[:80]!r}")
    if s.count(old)!=1:
        raise SystemExit(f"non-unique patch anchor: {path}: {s.count(old)}")
    p.write_text(s.replace(old,new,1))

# Core visibility subject for non-secret dashboards.
replace_once(
    "app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '    const val PUBLIC_WAR_SUMMARY = "PUBLIC_WAR_SUMMARY"\n',
    '    const val PUBLIC_WAR_SUMMARY = "PUBLIC_WAR_SUMMARY"\n    const val PUBLIC_DASHBOARD_DATA = "PUBLIC_DASHBOARD_DATA"\n'
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '        VisibilitySubjectKinds.PUBLIC_WAR_SUMMARY, VisibilitySubjectKinds.WORLD_PRESENTATION\n',
    '        VisibilitySubjectKinds.PUBLIC_WAR_SUMMARY, VisibilitySubjectKinds.PUBLIC_DASHBOARD_DATA, VisibilitySubjectKinds.WORLD_PRESENTATION\n'
)

# ContextBuilder typo from WorldEventItem.name and no hidden subject inference.
replace_once(
    "app/src/main/java/com/rpgos/app/ContextBuilder.kt",
    'mapOf("title" to e.title,"status" to e.status,"summary" to e.summary)',
    'mapOf("name" to e.name,"status" to e.status,"summary" to e.summary)'
)

# LocalGameStore: ContextBuilder is final authority; never append omniscient data after projection.
replace_once(
    "app/src/main/java/com/rpgos/app/LocalGameStore.kt",
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
'''fun npcs(search:String=""):List<NpcListItem>{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcs(search,a,p)}} }''',
'fun npcDetail(uid:String):NpcDetail{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcDetail(uid)}} }':
'''fun npcDetail(uid:String):NpcDetail{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).npcDetail(uid,a,p)}} }''',
'fun relationEdges():List<RelationEdge>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).relationEdges()}} }':
'''fun relationEdges():List<RelationEdge>{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).relationEdges(a,p)}} }''',
'fun economies():List<EconomySummary>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).economies()}} }':
'''fun economies():List<EconomySummary>{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).economies(a,p)}} }''',
'fun wars():List<WarSummary>{ openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).wars()}} }':
'''fun wars():List<WarSummary>{ val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use{world->openGameplaySaveDb().use{save->return NpcWorldDashboardReader(world,save).wars(a,p)}} }''',
'fun activeWorldEvents(): List<WorldEventItem> { openWorldDb().use { world -> openGameplaySaveDb().use { save -> return WorldReader(world, save).activeEvents() } } }':
'''fun activeWorldEvents(): List<WorldEventItem> { val c=activeCampaignId();val a=VisibilityAudienceFactory.player(c);val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI);openWorldDb().use { world -> openGameplaySaveDb().use { save -> return WorldReader(world, save).activeEvents(a,p) } } }'''
}
for old,new in replacements.items(): replace_once("app/src/main/java/com/rpgos/app/LocalGameStore.kt",old,new)

# Scene image gets a purpose-specific projection instead of narration/full context.
replace_once(
    "app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
    'val context = store.buildContext(scenePrompt, chapter)\n                val prompt = VisualPromptBuilder().buildScenePrompt(scenePrompt, context)',
    'val context = store.buildContextForPurpose(scenePrompt, chapter, VisibilityPurposeKinds.SCENE_VISUALIZATION)\n                val prompt = VisualPromptBuilder().buildScenePrompt(scenePrompt, context)'
)

# Fallback bundle is also explicitly low-privilege and purpose-bound.
replace_once(
    "app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
'''                        recentChronicle = emptyList(),
                        retrievedLongTermMemory = emptyList()
                    )''',
'''                        recentChronicle = emptyList(),
                        retrievedLongTermMemory = emptyList(),
                        visibilityEnvelope = VisibilityAuthorityService().envelope(
                            VisibilityAudienceFactory.player(store.activeCampaignId()),
                            PurposeContext(store.activeCampaignId(), VisibilityPurposeKinds.GAMEPLAY_NARRATION)
                        )
                    )'''
)

# Backend and local fallback receive the same already-projected bundle; no backend-specific expansion.

print("Phase38 Slice A+B integration patch applied")
