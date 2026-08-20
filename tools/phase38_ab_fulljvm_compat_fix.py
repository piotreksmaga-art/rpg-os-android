from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def p(rel: str) -> Path:
    return ROOT / rel

def rep(rel: str, old: str, new: str, count: int = 1) -> None:
    path = p(rel)
    text = path.read_text(encoding="utf-8")
    found = text.count(old)
    if found != count:
        raise SystemExit(f"expected {count} anchor(s) in {rel}, found {found}: {old[:140]!r}")
    path.write_text(text.replace(old, new, count), encoding="utf-8")

# ---------------------------------------------------------------------------
# 1-4 = FIXTURE_MIGRATION.
# Caller-created diagnostic descriptors intentionally remain untrusted. Tests
# that validate internal/canonical diagnostics must inject a runtime-issued
# trusted diagnostic context into the existing protected-read gateway.
# ContextBuilder remains a PROJECTED_CONSUMER and receives only the gateway.
# ---------------------------------------------------------------------------

rel = "app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt"
path = p(rel)
text = path.read_text(encoding="utf-8")

old_ctor = '''    private val activePlayer: () -> ActivePlayerRef?,
    private val closeDbAfterRead: Boolean = true
) {
'''
new_ctor = '''    private val activePlayer: () -> ActivePlayerRef?,
    private val closeDbAfterRead: Boolean = true,
    private val principalResolverOverride: TrustedPrincipalResolver? = null
) {
'''
if text.count(old_ctor) != 1:
    raise SystemExit("ProtectedCampaignReadRepository compatibility constructor anchor missing/nonunique")
text = text.replace(old_ctor, new_ctor, 1)

old_companion = '''    companion object {
        internal fun borrowed(
            saveDb: SQLiteDatabase,
            campaignUid: String,
            activePlayer: () -> ActivePlayerRef?
        ): ProtectedCampaignReadRepository =
            ProtectedCampaignReadRepository({ saveDb }, campaignUid, activePlayer, closeDbAfterRead = false)
    }

    private fun resolver() = TrustedPrincipalResolver { audience ->
'''
new_companion = '''    companion object {
        internal fun borrowed(
            saveDb: SQLiteDatabase,
            campaignUid: String,
            activePlayer: () -> ActivePlayerRef?
        ): ProtectedCampaignReadRepository =
            ProtectedCampaignReadRepository({ saveDb }, campaignUid, activePlayer, closeDbAfterRead = false)

        /** Test/internal infrastructure hook: authority is issued outside the projected consumer. */
        internal fun borrowedTrusted(
            saveDb: SQLiteDatabase,
            campaignUid: String,
            activePlayer: () -> ActivePlayerRef?,
            trusted: TrustedPrincipalContext
        ): ProtectedCampaignReadRepository {
            require(trusted.campaignUid == campaignUid) { "RPGOS-P38:TRUSTED_REPOSITORY_CAMPAIGN_MISMATCH" }
            val trustedResolver = TrustedPrincipalResolver { audience ->
                if (
                    audience.campaignUid == trusted.campaignUid &&
                    audience.audienceKindUid == trusted.audienceKindUid &&
                    audience.principal == trusted.principal
                ) trusted else null
            }
            return ProtectedCampaignReadRepository(
                { saveDb }, campaignUid, activePlayer,
                closeDbAfterRead = false,
                principalResolverOverride = trustedResolver
            )
        }
    }

    private fun resolver(): TrustedPrincipalResolver = principalResolverOverride ?: TrustedPrincipalResolver { audience ->
'''
if text.count(old_companion) != 1:
    raise SystemExit("ProtectedCampaignReadRepository companion/resolver anchor missing/nonunique")
text = text.replace(old_companion, new_companion, 1)
path.write_text(text, encoding="utf-8")

# ContextBuilder gets a typed protected-read repository, never a raw DB authority token.
rel = "app/src/main/java/com/rpgos/app/ContextBuilder.kt"
path = p(rel)
text = path.read_text(encoding="utf-8")
old_header = '''    private val worldDb: SQLiteDatabase,
    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService()
) {
'''
new_header = '''    private val worldDb: SQLiteDatabase,
    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService(),
    private val protectedReadsOverride: ProtectedCampaignReadRepository? = null
) {
'''
if text.count(old_header) != 1:
    raise SystemExit("ContextBuilder constructor anchor missing/nonunique")
text = text.replace(old_header, new_header, 1)
old_local = '''        val protectedReads = ProtectedCampaignReadRepository.borrowed(saveDb, campaignRef.campaignId) { activePlayerRef }
'''
new_local = '''        val protectedReads = protectedReadsOverride ?: ProtectedCampaignReadRepository.borrowed(saveDb, campaignRef.campaignId) { activePlayerRef }
'''
if text.count(old_local) != 1:
    raise SystemExit("ContextBuilder projected repository anchor missing/nonunique")
text = text.replace(old_local, new_local, 1)
path.write_text(text, encoding="utf-8")

# Extend the canonical test-only authority harness with an explicit trusted
# diagnostic ContextBuilder fixture. No production caller can forge the token.
rel = "app/src/test/java/com/rpgos/app/Phase38TrustedTestAuthority.kt"
path = p(rel)
text = path.read_text(encoding="utf-8")
if "import android.database.sqlite.SQLiteDatabase" not in text:
    text = text.replace("package com.rpgos.app\n", "package com.rpgos.app\n\nimport android.database.sqlite.SQLiteDatabase\n", 1)
insert_anchor = '''internal object Phase38TrustedTestAuthority {
'''
fixture_type = '''internal data class TrustedContextBuilderFixture(
    val builder: ContextBuilder,
    val audience: AudienceContext
)

'''
if text.count(insert_anchor) != 1:
    raise SystemExit("Phase38TrustedTestAuthority object anchor missing/nonunique")
text = text.replace(insert_anchor, fixture_type + insert_anchor, 1)
method_anchor = '''    fun diagnostic(campaignUid: String): TrustedAudienceFixture {
'''
method = '''    fun diagnosticContextBuilder(
        saveDb: SQLiteDatabase,
        worldDb: SQLiteDatabase,
        campaignUid: String
    ): TrustedContextBuilderFixture {
        val fixture = diagnostic(campaignUid)
        val reads = ProtectedCampaignReadRepository.borrowedTrusted(
            saveDb,
            campaignUid,
            { ActivePlayerStore(saveDb, campaignUid).active() },
            fixture.trusted
        )
        return TrustedContextBuilderFixture(
            ContextBuilder(saveDb, worldDb, protectedReadsOverride = reads),
            fixture.audience
        )
    }

'''
if text.count(method_anchor) != 1:
    raise SystemExit("Phase38TrustedTestAuthority diagnostic method anchor missing/nonunique")
text = text.replace(method_anchor, method + method_anchor, 1)
path.write_text(text, encoding="utf-8")

# Migrate the four Phase32 canonical diagnostic fixtures. Their assertions stay
# semantically unchanged: they still prove storage/type/provenance continuity,
# but now cross Phase38 through explicit trusted diagnostic authority.

def migrate_context_call(
    rel: str,
    campaign: str,
    player_input: str,
    chapter: int,
    variable: str = "bundle",
    trusted_variable: str = "trustedContext"
) -> None:
    path = p(rel)
    text = path.read_text(encoding="utf-8")
    old = f'''                val {variable} = run {{ Phase38LegacyContextFixtureSchema.ensure(save, world); ContextBuilder(save,world).build("{player_input}",{chapter},VisibilityAudienceFactory.diagnostic("{campaign}"),PurposeContext("{campaign}",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }}\n'''
    if old not in text:
        # Some tests name the save DB `db` rather than `save`.
        old = f'''                val {variable} = run {{ Phase38LegacyContextFixtureSchema.ensure(db, world); ContextBuilder(db,world).build("{player_input}",{chapter},VisibilityAudienceFactory.diagnostic("{campaign}"),PurposeContext("{campaign}",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }}\n'''
        db_name = "db"
    else:
        db_name = "save"
    if old not in text:
        raise SystemExit(f"trusted diagnostic migration anchor missing in {rel}: {player_input}")
    new = f'''                Phase38LegacyContextFixtureSchema.ensure({db_name}, world)\n                val {trusted_variable} = Phase38TrustedTestAuthority.diagnosticContextBuilder({db_name}, world, "{campaign}")\n                val {variable} = {trusted_variable}.builder.build(\n                    "{player_input}", {chapter}, {trusted_variable}.audience,\n                    PurposeContext("{campaign}", VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)\n                )\n'''
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

migrate_context_call(
    "app/src/test/java/com/rpgos/app/Phase32ContextBuilderTruthReadTest.kt",
    "C", "look", 1,
    trusted_variable = "trustedInitialContext"
)
migrate_context_call(
    "app/src/test/java/com/rpgos/app/Phase32ContextBuilderTruthReadTest.kt",
    "C", "look again", 2, "rebuilt",
    trusted_variable = "trustedRebuiltContext"
)
migrate_context_call(
    "app/src/test/java/com/rpgos/app/Phase32ContextCanonicalDomainsTest.kt",
    "C", "inspect canonical domains", 1,
    trusted_variable = "trustedInitialContext"
)
migrate_context_call(
    "app/src/test/java/com/rpgos/app/Phase32ContextCanonicalDomainsTest.kt",
    "C", "rebuild canonical domains", 2, "rebuilt",
    trusted_variable = "trustedRebuiltContext"
)

# Legacy unknown projection uses a chained builder expression; preserve the
# LEGACY record exactly and only change the diagnostic fixture authority.
rel = "app/src/test/java/com/rpgos/app/Phase32LegacyUnknownProjectionTest.kt"
path = p(rel)
text = path.read_text(encoding="utf-8")
old = '''                val projected = ContextBuilder(db, world)
                    .let { builder -> Phase38LegacyContextFixtureSchema.ensure(db, world); builder.build("inspect legacy history",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }
                    .campaignTruth
                    .single { it["truth_uid"] == "TRUTH-G32-LEGACY-UNKNOWN" }
'''
new = '''                Phase38LegacyContextFixtureSchema.ensure(db, world)
                val trustedContext = Phase38TrustedTestAuthority.diagnosticContextBuilder(db, world, "C1")
                val projected = trustedContext.builder
                    .build(
                        "inspect legacy history", 1, trustedContext.audience,
                        PurposeContext("C1", VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
                    )
                    .campaignTruth
                    .single { it["truth_uid"] == "TRUTH-G32-LEGACY-UNKNOWN" }
'''
if text.count(old) != 1:
    raise SystemExit("Phase32LegacyUnknownProjectionTest trusted fixture anchor missing/nonunique")
path.write_text(text.replace(old, new, 1), encoding="utf-8")

rel = "app/src/test/java/com/rpgos/app/Phase32TruthTypeEndToEndTest.kt"
path = p(rel)
text = path.read_text(encoding="utf-8")
old = '''                val contextTruth = run { Phase38LegacyContextFixtureSchema.ensure(db, world); ContextBuilder(db,world).build("inspect truth",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }.campaignTruth
                    .associate { it.getValue("truth_uid") as String to TruthKind.valueOf(it.getValue("truth_kind") as String) }
'''
new = '''                Phase38LegacyContextFixtureSchema.ensure(db, world)
                val trustedContext = Phase38TrustedTestAuthority.diagnosticContextBuilder(db, world, "C1")
                val contextTruth = trustedContext.builder
                    .build(
                        "inspect truth", 1, trustedContext.audience,
                        PurposeContext("C1", VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
                    )
                    .campaignTruth
                    .associate { it.getValue("truth_uid") as String to TruthKind.valueOf(it.getValue("truth_kind") as String) }
'''
if text.count(old) != 1:
    raise SystemExit("Phase32TruthTypeEndToEndTest trusted fixture anchor missing/nonunique")
path.write_text(text.replace(old, new, 1), encoding="utf-8")

# ---------------------------------------------------------------------------
# 5-6 = INTENTIONAL SECURITY CONTRACT STRENGTHENING.
# The normal CampaignRepository exposes no raw SQLiteDatabase handle at all;
# writer-reachability inventory now recognizes the typed protected read facade.
# ---------------------------------------------------------------------------
rel = "app/src/test/java/com/rpgos/app/Phase32WriterBypassInventoryTest.kt"
path = p(rel)
text = path.read_text(encoding="utf-8")
text = text.replace(
'''        READ_ONLY_NON_AUTHORITATIVE,\n        GAMEPLAY_UNREACHABLE\n''',
'''        READ_ONLY_NON_AUTHORITATIVE,\n        PROTECTED_PROJECTED_READ,\n        GAMEPLAY_UNREACHABLE\n''', 1)
for line in (
    '        "playerState" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,\n',
    '        "playerStats" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,\n',
    '        "playerResources" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,\n',
    '        "openWorldDb" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,\n',
    '        "openCoreDb" to WriterReachability.READ_ONLY_NON_AUTHORITATIVE,\n',
):
    if text.count(line) != 1:
        raise SystemExit(f"Phase32 writer inventory old entry missing/nonunique: {line.strip()}")
    text = text.replace(line, "", 1)
anchor = '        "setActivePlayer" to WriterReachability.ADMINISTRATIVE,\n'
if text.count(anchor) != 1:
    raise SystemExit("Phase32 writer inventory protectedReads insertion anchor missing/nonunique")
text = text.replace(anchor, anchor + '        "protectedReads" to WriterReachability.PROTECTED_PROJECTED_READ,\n', 1)
old_test = '''        assertEquals(setOf("openWorldDb", "openCoreDb"), sqliteReturning)
        assertEquals(WriterReachability.READ_ONLY_NON_AUTHORITATIVE, repositoryEntryPoints.getValue("openWorldDb"))
        assertEquals(WriterReachability.READ_ONLY_NON_AUTHORITATIVE, repositoryEntryPoints.getValue("openCoreDb"))
'''
new_test = '''        assertTrue("normal CampaignRepository must expose no raw SQLiteDatabase handle", sqliteReturning.isEmpty())
        val publicNames = CampaignRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .toSet()
        assertFalse(publicNames.contains("openWorldDb"))
        assertFalse(publicNames.contains("openCoreDb"))
        assertFalse(publicNames.contains("playerState"))
        assertFalse(publicNames.contains("playerStats"))
        assertFalse(publicNames.contains("playerResources"))
        assertEquals(WriterReachability.PROTECTED_PROJECTED_READ, repositoryEntryPoints.getValue("protectedReads"))
        val protectedReadsMethod = CampaignRepository::class.java.declaredMethods.single { it.name == "protectedReads" }
        assertEquals(ProtectedCampaignReadRepository::class.java, protectedReadsMethod.returnType)
'''
if text.count(old_test) != 1:
    raise SystemExit("Phase32 raw DB surface assertion anchor missing/nonunique")
text = text.replace(old_test, new_test, 1)
path.write_text(text, encoding="utf-8")

print("Phase38 A+B full-JVM compatibility fixes applied")
