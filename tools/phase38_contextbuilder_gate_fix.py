from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def p(rel: str) -> Path:
    return ROOT / rel

def replace_once(rel: str, old: str, new: str) -> None:
    path = p(rel)
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one anchor in {rel}, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

# Extend the already-trusted gateway so projected consumers can borrow their existing DB handle
# without taking ownership/closing it, and expose the exact legacy ContextBundle truth-row shape.
rel = "app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt"
path = p(rel)
text = path.read_text(encoding="utf-8")

old_ctor = '''    private val openSaveDb: () -> SQLiteDatabase,
    private val campaignUid: String,
    private val activePlayer: () -> ActivePlayerRef?
) {
    private fun resolver() = TrustedPrincipalResolver { audience ->
'''
new_ctor = '''    private val openSaveDb: () -> SQLiteDatabase,
    private val campaignUid: String,
    private val activePlayer: () -> ActivePlayerRef?,
    private val closeDbAfterRead: Boolean = true
) {
    private fun <T> withSaveDb(block: (SQLiteDatabase) -> T): T {
        val db = openSaveDb()
        return if (closeDbAfterRead) db.use { block(it) } else block(db)
    }

    companion object {
        internal fun borrowed(
            saveDb: SQLiteDatabase,
            campaignUid: String,
            activePlayer: () -> ActivePlayerRef?
        ): ProtectedCampaignReadRepository =
            ProtectedCampaignReadRepository({ saveDb }, campaignUid, activePlayer, closeDbAfterRead = false)
    }

    private fun resolver() = TrustedPrincipalResolver { audience ->
'''
if text.count(old_ctor) != 1:
    raise SystemExit("ProtectedCampaignReadRepository constructor anchor missing/nonunique")
text = text.replace(old_ctor, new_ctor, 1)

old_use = "return openSaveDb().use { db ->"
if text.count(old_use) != 2:
    raise SystemExit(f"expected two owned gateway DB reads, found {text.count(old_use)}")
text = text.replace(old_use, "return withSaveDb { db ->")

insert = '''
    fun truthContextRows(
        audience: AudienceContext,
        purpose: PurposeContext,
        limit: Int = 80
    ): ProtectedReadResult<List<Map<String, Any?>>> {
        val req = VisibilityRequest(
            audience,
            purpose,
            VisibilitySubjectRef(campaignUid, VisibilitySubjectKinds.CAMPAIGN_TRUTH, "ACTIVE_TRUTH")
        )
        return withSaveDb { db ->
            ProtectedReadGateway(VisibilityAuthorityService(), resolver()).read(req) {
                CampaignTruthStore(db, campaignUid).activeForContext(limit)
            }
        }
    }
'''
last = text.rfind("\n}")
if last < 0:
    raise SystemExit("ProtectedCampaignReadRepository closing brace missing")
text = text[:last] + insert + text[last:]
path.write_text(text, encoding="utf-8")

# ContextBuilder remains a PROJECTED_CONSUMER. It requests typed projected results from the
# trusted repository and never constructs CampaignTruthStore / PlayerStateStore itself.
rel = "app/src/main/java/com/rpgos/app/ContextBuilder.kt"
path = p(rel)
text = path.read_text(encoding="utf-8")

old_player = '''        val playerUid = ActivePlayerStore(saveDb,campaignRef.campaignId).active()?.playerUid
'''
new_player = '''        val activePlayerRef = ActivePlayerStore(saveDb,campaignRef.campaignId).active()
        val playerUid = activePlayerRef?.playerUid
        val protectedReads = ProtectedCampaignReadRepository.borrowed(saveDb, campaignRef.campaignId) { activePlayerRef }
'''
if text.count(old_player) != 1:
    raise SystemExit("ContextBuilder active-player anchor missing/nonunique")
text = text.replace(old_player, new_player, 1)

old_auth = '''        val playerStateRequest = playerUid?.let { VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignRef.campaignId,VisibilitySubjectKinds.PLAYER_STATE,it)) }
        val playerStateAuthorized = playerStateRequest?.let { visibility.decide(it, trustedPrincipal).level != DisclosureLevel.DENY } == true
'''
new_auth = '''        val playerStateRead: ProtectedReadResult<PlayerStateSnapshot> = if (playerUid != null) {
            protectedReads.playerState(audience, purpose, playerUid)
        } else ProtectedReadResult.NoData
        val playerStateAuthorized = playerStateRead is ProtectedReadResult.Allow<*>
'''
if text.count(old_auth) != 1:
    raise SystemExit("ContextBuilder player-state authorization anchor missing/nonunique")
text = text.replace(old_auth, new_auth, 1)

old_truth = '''        val campaignTruth = protectedTruthRows(audience,purpose)
'''
new_truth = '''        val campaignTruthRead: ProtectedReadResult<List<Map<String, Any?>>> = protectedReads.truthContextRows(audience, purpose)
        val campaignTruth = when (campaignTruthRead) {
            is ProtectedReadResult.Allow -> campaignTruthRead.value
            else -> emptyList()
        }
'''
if text.count(old_truth) != 1:
    raise SystemExit("ContextBuilder campaign-truth call anchor missing/nonunique")
text = text.replace(old_truth, new_truth, 1)

old_state = '''        val playerState = if(playerUid!=null && playerStateAuthorized) PlayerStateStore(saveDb,campaignRef.campaignId).load()?.toContextMap() ?: emptyMap() else emptyMap()
'''
new_state = '''        val playerState = when (playerStateRead) {
            is ProtectedReadResult.Allow -> playerStateRead.value.toContextMap()
            else -> emptyMap()
        }
'''
if text.count(old_state) != 1:
    raise SystemExit("ContextBuilder direct PlayerStateStore anchor missing/nonunique")
text = text.replace(old_state, new_state, 1)

old_helper = '''    private fun protectedTruthRows(audience:AudienceContext,purpose:PurposeContext):List<Map<String,Any?>> {
        val req=VisibilityRequest(audience,purpose,VisibilitySubjectRef(audience.campaignUid,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"ACTIVE_TRUTH"))
        return visibility.project(req){CampaignTruthStore(saveDb,audience.campaignUid).activeForContext()}.value ?: emptyList()
    }

'''
if text.count(old_helper) != 1:
    raise SystemExit("ContextBuilder direct CampaignTruthStore helper anchor missing/nonunique")
text = text.replace(old_helper, "", 1)

old_meta = '''            "player_facing" to playerFacing
'''
new_meta = '''            "player_facing" to playerFacing,
            "campaign_truth_state" to campaignTruthRead.stateUid,
            "player_state_state" to playerStateRead.stateUid
'''
if text.count(old_meta) != 1:
    raise SystemExit("ContextBuilder context-meta state anchor missing/nonunique")
text = text.replace(old_meta, new_meta, 1)

for forbidden in ("CampaignTruthStore(", "PlayerStateStore(", "KnowledgeStore(", ".openWorldDb()", ".openCoreDb()"):
    if forbidden in text:
        raise SystemExit(f"ContextBuilder still contains forbidden protected entry point: {forbidden}")
path.write_text(text, encoding="utf-8")

# Add a focused regression: ContextBuilder stays projected, contains no direct protected entry point,
# while the trusted gateway and dummy ordinary consumer retain opposite classifications.
rel = "app/src/test/java/com/rpgos/app/Phase38VisibilityBoundaryTest.kt"
path = p(rel)
text = path.read_text(encoding="utf-8")
anchor = '''    @Test fun universalCoreContainsNoWorldSpecificSemanticBranches(){
'''
regression = '''    @Test fun contextBuilderUsesProtectedGatewayWithoutDirectProtectedEntryPoints(){
        val contextPath="app/src/main/java/com/rpgos/app/ContextBuilder.kt"
        val contextSource=source(contextPath)
        assertEquals(ProtectedConsumerCapability.PROJECTED_CONSUMER,VisibilityConsumerInventory.contractForSource(contextPath)?.capability)
        assertFalse(VisibilityConsumerInventory.hasForbiddenDirectProtectedEntryPoint(contextSource))
        assertEquals(ProtectedEntryPointClassification.PROJECTED_CONSUMER,VisibilityConsumerInventory.entryPointClassification(contextPath,contextSource))
        assertTrue(contextSource.contains("ProtectedCampaignReadRepository.borrowed"))
        assertTrue(contextSource.contains("campaign_truth_state"))
        assertTrue(contextSource.contains("player_state_state"))

        val gatewayPath="app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt"
        assertEquals(ProtectedEntryPointClassification.TRUSTED_GATEWAY,VisibilityConsumerInventory.entryPointClassification(gatewayPath,source(gatewayPath)))
        val ordinaryDirect="class NormalProjectedConsumer { val x = CampaignTruthStore(db, campaign) }"
        assertEquals(ProtectedEntryPointClassification.FORBIDDEN_DIRECT_CONSUMER,VisibilityConsumerInventory.entryPointClassification("app/src/main/java/com/rpgos/app/NormalProjectedConsumer.kt",ordinaryDirect))
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("Phase38VisibilityBoundaryTest insertion anchor missing/nonunique")
text = text.replace(anchor, regression + anchor, 1)
path.write_text(text, encoding="utf-8")

print("Phase38 ContextBuilder protected-read gateway fix applied")
