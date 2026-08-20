from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parents[1]

def p(rel): return ROOT/rel

def write(rel, text):
    path=p(rel); path.parent.mkdir(parents=True, exist_ok=True); path.write_text(text, encoding='utf-8')

def replace(rel, old, new):
    path=p(rel); text=path.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'anchor missing in {rel}: {old[:120]!r}')
    path.write_text(text.replace(old,new), encoding='utf-8')

def insert_before(rel, anchor, text): replace(rel, anchor, text+anchor)

def hardening():
    write('app/src/main/java/com/rpgos/app/Phase38TrustedAuthority.kt', r'''package com.rpgos.app

/** Runtime-owned identity/privilege state. Normal callers can describe an audience, never manufacture this authority. */
class PrivilegedAudienceCapability private constructor(
    internal val campaignUid: String,
    internal val privilegeUid: String,
    private val seal: Any
) {
    init { require(campaignUid.isNotBlank() && privilegeUid.isNotBlank() && seal === PRIVILEGE_SEAL) }
    companion object {
        internal fun issue(campaignUid: String, privilegeUid: String): PrivilegedAudienceCapability =
            PrivilegedAudienceCapability(campaignUid, privilegeUid, PRIVILEGE_SEAL)
    }
}
private val PRIVILEGE_SEAL = Any()

data class TrustedPrincipalContext internal constructor(
    val campaignUid: String,
    val principal: VisibilityPrincipalRef,
    val audienceKindUid: String,
    val controlledSubjectUids: Set<String> = emptySet(),
    val roleUids: Set<String> = emptySet(),
    val organizationUids: Set<String> = emptySet(),
    val clearanceUids: Set<String> = emptySet(),
    val cognitionHolders: Set<KnowledgeHolderRef> = emptySet(),
    internal val privilegedCapability: PrivilegedAudienceCapability? = null,
    internal val resolverVersionUid: String = "RPGOS-P38-TRUSTED-PRINCIPAL-1"
) {
    init {
        require(campaignUid.isNotBlank() && principal.kindUid.isNotBlank() && principal.uid.isNotBlank())
        cognitionHolders.forEach { require(it.campaignUid == campaignUid) }
        privilegedCapability?.let { require(it.campaignUid == campaignUid) }
    }
    fun controls(subjectUid: String) = subjectUid in controlledSubjectUids
    fun isPrivileged(privilegeUid: String) = privilegedCapability?.privilegeUid == privilegeUid
}

fun interface TrustedCognitionResolver {
    fun holdersFor(campaignUid: String, principal: VisibilityPrincipalRef): Set<KnowledgeHolderRef>
}

fun interface TrustedPrincipalResolver {
    fun resolve(audience: AudienceContext): TrustedPrincipalContext?
}

internal object Phase38RuntimeAuthority {
    const val PRIV_GM = "GM_RUNTIME"
    const val PRIV_INTERNAL = "INTERNAL_SYSTEM"
    const val PRIV_DIAGNOSTIC = "DEVELOPER_DIAGNOSTIC"

    fun application(
        audience: AudienceContext,
        controlledSubjectUids: Set<String> = emptySet(),
        cognitionResolver: TrustedCognitionResolver = TrustedCognitionResolver { _, _ -> emptySet() },
        roleUids: Set<String> = emptySet(),
        organizationUids: Set<String> = emptySet(),
        clearanceUids: Set<String> = emptySet()
    ): TrustedPrincipalContext? {
        val principal = audience.principal ?: return null
        if (audience.audienceKindUid in setOf(AudienceKinds.GM_RUNTIME, AudienceKinds.INTERNAL_SYSTEM, AudienceKinds.DEVELOPER_DIAGNOSTIC)) return null
        return TrustedPrincipalContext(
            audience.campaignUid, principal, audience.audienceKindUid,
            controlledSubjectUids, roleUids, organizationUids, clearanceUids,
            cognitionResolver.holdersFor(audience.campaignUid, principal)
        )
    }

    internal fun privileged(audience: AudienceContext, privilegeUid: String): TrustedPrincipalContext {
        val principal = requireNotNull(audience.principal)
        require(audience.audienceKindUid == privilegeUid) { "RPGOS-P38:PRIVILEGE_AUDIENCE_MISMATCH" }
        return TrustedPrincipalContext(
            audience.campaignUid, principal, audience.audienceKindUid,
            privilegedCapability = PrivilegedAudienceCapability.issue(audience.campaignUid, privilegeUid)
        )
    }
}

/** Compatibility descriptor: caller-supplied holders are deliberately ignored by trusted authorization. */
data class AudienceContext(
    val campaignUid: String,
    val audienceKindUid: String,
    val principal: VisibilityPrincipalRef? = null,
    @Deprecated("Caller-supplied cognition mappings are not authority in Phase38")
    val knowledgeHolders: List<KnowledgeHolderRef> = emptyList()
) {
    init {
        require(campaignUid.isNotBlank() && audienceKindUid.isNotBlank())
        knowledgeHolders.forEach { require(it.campaignUid == campaignUid) { "RPGOS-VISIBILITY:CAMPAIGN_QUALIFIED_HOLDER_REQUIRED" } }
        if (audienceKindUid == AudienceKinds.WORLD_ACTOR || audienceKindUid == AudienceKinds.PLAYER_CHARACTER) {
            require(principal != null) { "RPGOS-VISIBILITY:ACTOR_AUDIENCE_REQUIRES_PRINCIPAL" }
        }
    }
}
''')

    # Replace the old AudienceContext declaration with a comment: definition now lives in trusted authority file.
    rel='app/src/main/java/com/rpgos/app/Phase38Visibility.kt'
    text=p(rel).read_text(encoding='utf-8')
    start=text.index('data class AudienceContext(')
    end=text.index('\ndata class PurposeContext', start)
    text=text[:start]+'// AudienceContext is defined in Phase38TrustedAuthority.kt; it is an untrusted audience descriptor.\n'+text[end:]
    # richer disclosure model while preserving old enum constants
    old='''enum class DisclosureLevel(val rank: Int) {\n    DENY(0), DISCLOSE_EXISTENCE(1), DISCLOSE_REDACTED(2), DISCLOSE_PARTIAL(3), DISCLOSE_FULL(4);\n    fun canReduceTo(other: DisclosureLevel): Boolean = other.rank <= rank\n}'''
    new='''enum class DisclosureLevel(val rank: Int) {\n    DENY(0), DISCLOSE_EXISTENCE(10), CATEGORY_ONLY(20), QUALITATIVE(30), APPROXIMATE(40), RANGE(50),\n    SUMMARY(60), DISCLOSE_REDACTED(70), DISCLOSE_PARTIAL(80), DETAILED(90), DISCLOSE_FULL(100);\n    fun canReduceTo(other: DisclosureLevel): Boolean = other.rank <= rank\n}'''
    if old not in text: raise SystemExit('disclosure anchor missing')
    text=text.replace(old,new)
    # replace authority decide/project block with trusted-aware overloads by targeted substitutions
    text=text.replace('''    fun decide(request: VisibilityRequest): VisibilityDecision {\n        validate(request)\n        val a = request.audience.audienceKindUid''','''    fun decide(request: VisibilityRequest): VisibilityDecision = decide(request, null)\n\n    fun decide(request: VisibilityRequest, trusted: TrustedPrincipalContext?): VisibilityDecision {\n        validate(request)\n        if (trusted != null && trusted.campaignUid != request.audience.campaignUid) throw VisibilityAuthorityFailure.CrossCampaign()\n        if (trusted != null && trusted.principal != request.audience.principal) return deny("TRUSTED_PRINCIPAL_MISMATCH")\n        val a = request.audience.audienceKindUid''')
    text=text.replace('''        if (a == AudienceKinds.DEVELOPER_DIAGNOSTIC && p == VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION) return full("EXPLICIT_DIAGNOSTIC")\n        if (a == AudienceKinds.INTERNAL_SYSTEM && p == VisibilityPurposeKinds.INTERNAL_SIMULATION) return full("EXPLICIT_INTERNAL_SIMULATION")\n        if (a == AudienceKinds.GM_RUNTIME && p in setOf(VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.INTERNAL_SIMULATION)) return full("GM_RUNTIME_INTERNAL")''','''        if (a == AudienceKinds.DEVELOPER_DIAGNOSTIC && p == VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)\n            return if (trusted?.isPrivileged(Phase38RuntimeAuthority.PRIV_DIAGNOSTIC) == true) full("TRUSTED_DIAGNOSTIC") else deny("PRIVILEGED_CAPABILITY_REQUIRED")\n        if (a == AudienceKinds.INTERNAL_SYSTEM && p == VisibilityPurposeKinds.INTERNAL_SIMULATION)\n            return if (trusted?.isPrivileged(Phase38RuntimeAuthority.PRIV_INTERNAL) == true) full("TRUSTED_INTERNAL_SIMULATION") else deny("PRIVILEGED_CAPABILITY_REQUIRED")\n        if (a == AudienceKinds.GM_RUNTIME && p in setOf(VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.INTERNAL_SIMULATION))\n            return if (trusted?.isPrivileged(Phase38RuntimeAuthority.PRIV_GM) == true) full("TRUSTED_GM_RUNTIME") else deny("PRIVILEGED_CAPABILITY_REQUIRED")''')
    text=text.replace('''        if (s == VisibilitySubjectKinds.PLAYER_STATE && a in setOf(AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER)) {\n            return if (p in setOf(VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION)) full("PLAYER_STATE") else deny("PURPOSE_NOT_NECESSARY")\n        }''','''        if (s == VisibilitySubjectKinds.PLAYER_STATE && a in setOf(AudienceKinds.PLAYER, AudienceKinds.PLAYER_CHARACTER)) {\n            if (trusted == null || !trusted.controls(request.subject.subjectUid)) return deny("PLAYER_STATE_SUBJECT_NOT_CONTROLLED")\n            return if (p in setOf(VisibilityPurposeKinds.PLAYER_UI, VisibilityPurposeKinds.GAMEPLAY_NARRATION, VisibilityPurposeKinds.CHARACTER_VISUALIZATION)) full("CONTROLLED_PLAYER_STATE") else deny("PURPOSE_NOT_NECESSARY")\n        }''')
    text=text.replace('''            val explicitlyMapped = request.audience.knowledgeHolders.any {\n                it.campaignUid == request.audience.campaignUid &&\n                    it.holderKindUid == holder.holderKindUid && it.holderUid == holder.holderUid\n            }''','''            val explicitlyMapped = trusted?.cognitionHolders?.any {\n                it.campaignUid == request.audience.campaignUid &&\n                    it.holderKindUid == holder.holderKindUid && it.holderUid == holder.holderUid\n            } == true''')
    text=text.replace('''    fun <T> project(request: VisibilityRequest, read: () -> T): VisibilityProjection<T> {\n        val decision = decide(request)''','''    fun <T> project(request: VisibilityRequest, read: () -> T): VisibilityProjection<T> = project(request, null, read)\n\n    fun <T> project(request: VisibilityRequest, trusted: TrustedPrincipalContext?, read: () -> T): VisibilityProjection<T> {\n        val decision = decide(request, trusted)''')
    text=text.replace('''    fun <T> projectList(request: VisibilityRequest, read: () -> List<T>): VisibilityProjection<List<T>> {\n        val projection = project(request, read)''','''    fun <T> projectList(request: VisibilityRequest, read: () -> List<T>): VisibilityProjection<List<T>> = projectList(request, null, read)\n\n    fun <T> projectList(request: VisibilityRequest, trusted: TrustedPrincipalContext?, read: () -> List<T>): VisibilityProjection<List<T>> {\n        val projection = project(request, trusted, read)''')
    p(rel).write_text(text, encoding='utf-8')

    write('app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt', r'''package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

sealed class ProtectedReadResult<out T> {
    data class Allow<T>(val value: T, val disclosure: DisclosureLevel, val reasonCode: String) : ProtectedReadResult<T>()
    data class Deny(val reasonCode: String) : ProtectedReadResult<Nothing>()
    data object NoData : ProtectedReadResult<Nothing>()
    data class NotDisclosed(val reasonCode: String) : ProtectedReadResult<Nothing>()
    data class Unknown(val reasonCode: String) : ProtectedReadResult<Nothing>()
    data class Corruption(val reasonCode: String, val causeType: String) : ProtectedReadResult<Nothing>()

    val stateUid: String get() = when(this) {
        is Allow<*> -> "ALLOW"; is Deny -> "DENY"; NoData -> "NO_DATA"; is NotDisclosed -> "NOT_DISCLOSED";
        is Unknown -> "UNKNOWN"; is Corruption -> "CORRUPTION"
    }
}

class ProtectedReadGateway(
    private val visibility: VisibilityAuthorityService = VisibilityAuthorityService(),
    private val principalResolver: TrustedPrincipalResolver
) {
    fun <T> read(request: VisibilityRequest, read: () -> T?): ProtectedReadResult<T> {
        val trusted = principalResolver.resolve(request.audience)
        val projection = try { visibility.project(request, trusted) { read() } }
        catch (t: VisibilityAuthorityFailure.CorruptRead) { return ProtectedReadResult.Corruption("PROTECTED_READ_CORRUPTION", t.cause?.javaClass?.name ?: t.javaClass.name) }
        return when(projection.dataState) {
            ProjectionDataState.DISCLOSED -> projection.value?.let { ProtectedReadResult.Allow(it, projection.decision.level, projection.decision.reasonCode) } ?: ProtectedReadResult.NoData
            ProjectionDataState.NO_DATA -> ProtectedReadResult.NoData
            ProjectionDataState.DENIED -> ProtectedReadResult.Deny(projection.decision.reasonCode)
            ProjectionDataState.NOT_DISCLOSED -> ProtectedReadResult.NotDisclosed(projection.decision.reasonCode)
            ProjectionDataState.UNKNOWN -> ProtectedReadResult.Unknown(projection.decision.reasonCode)
            ProjectionDataState.CORRUPTION -> ProtectedReadResult.Corruption(projection.decision.reasonCode, "UNKNOWN")
        }
    }
}

class ProtectedCampaignReadRepository internal constructor(
    private val openSaveDb: () -> SQLiteDatabase,
    private val campaignUid: String,
    private val activePlayer: () -> ActivePlayerRef?
) {
    private fun resolver() = TrustedPrincipalResolver { audience ->
        val active = activePlayer()
        val controlled = if (audience.audienceKindUid == AudienceKinds.PLAYER && active != null) setOf(active.playerUid) else emptySet()
        Phase38RuntimeAuthority.application(audience, controlledSubjectUids = controlled)
    }
    fun playerState(audience: AudienceContext, purpose: PurposeContext, subjectUid: String): ProtectedReadResult<PlayerStateSnapshot> {
        val req = VisibilityRequest(audience, purpose, VisibilitySubjectRef(campaignUid, VisibilitySubjectKinds.PLAYER_STATE, subjectUid))
        return openSaveDb().use { db -> ProtectedReadGateway(VisibilityAuthorityService(), resolver()).read(req) { PlayerStateStore(db, campaignUid).load() } }
    }
    fun truth(audience: AudienceContext, purpose: PurposeContext, limit: Int = 100): ProtectedReadResult<List<CampaignTruthRecord>> {
        val req=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"CAMPAIGN_TRUTH_RECORDS"))
        return openSaveDb().use { db -> ProtectedReadGateway(VisibilityAuthorityService(), resolver()).read(req) { CampaignTruthStore(db,campaignUid).active(limit=limit) } }
    }
}
''')

    write('app/src/main/java/com/rpgos/app/ContextModels.kt', r'''package com.rpgos.app

data class ContextBundle(
    val playerStatus: Map<String, Any?>,
    val scene: Map<String, Any?>,
    val time: Map<String, Any?>,
    val activeThreads: List<Map<String, Any?>>,
    val relevantNpcs: List<Map<String, Any?>>,
    val npcKnowledge: List<Map<String, Any?>>,
    val missions: List<Map<String, Any?>>,
    val worldPressures: List<Map<String, Any?>>,
    val canonConstraints: List<Map<String, Any?>>,
    val recentChronicle: List<Map<String, Any?>>,
    val retrievedLongTermMemory: List<Map<String, Any?>>,
    val playerSkills: List<Map<String, Any?>> = emptyList(),
    val playerTechniques: List<Map<String, Any?>> = emptyList(),
    val playerInventory: List<Map<String, Any?>> = emptyList(),
    val playerOrganizations: List<Map<String, Any?>> = emptyList(),
    val activeWorldEvents: List<Map<String, Any?>> = emptyList(),
    val npcMemories: List<Map<String, Any?>> = emptyList(),
    val campaignTruth: List<Map<String, Any?>> = emptyList(),
    val canonDivergences: List<CanonDivergenceRecord> = emptyList(),
    val playerState: Map<String, Any?> = emptyMap(),
    val contextMeta: Map<String, Any?> = emptyMap(),
    val visibilityEnvelope: VisibilityProjectionEnvelope
) {
    fun reduceDisclosureTo(level: DisclosureLevel): ContextBundle = ContextBundleDisclosureProjector.reduce(this, level)

    fun requireNotEscalatedFrom(upstream: VisibilityProjectionEnvelope) {
        require(upstream.campaignUid == visibilityEnvelope.campaignUid && upstream.audience == visibilityEnvelope.audience && upstream.purpose == visibilityEnvelope.purpose) {
            "RPGOS-VISIBILITY:PROJECTION_IDENTITY_CHANGED"
        }
        if (!upstream.maximumDisclosure.canReduceTo(visibilityEnvelope.maximumDisclosure)) throw VisibilityAuthorityFailure.Escalation()
    }
}

/** Actual payload reduction. It never merely relabels FULL bytes as a lower disclosure. */
object ContextBundleDisclosureProjector {
    fun reduce(source: ContextBundle, level: DisclosureLevel): ContextBundle {
        if (!source.visibilityEnvelope.maximumDisclosure.canReduceTo(level)) throw VisibilityAuthorityFailure.Escalation()
        if (level == source.visibilityEnvelope.maximumDisclosure) return source
        val env = source.visibilityEnvelope.reduceTo(level)
        if (level == DisclosureLevel.DENY) return ContextBundle(
            playerStatus=emptyMap(),scene=emptyMap(),time=emptyMap(),activeThreads=emptyList(),relevantNpcs=emptyList(),npcKnowledge=emptyList(),
            missions=emptyList(),worldPressures=emptyList(),canonConstraints=emptyList(),recentChronicle=emptyList(),retrievedLongTermMemory=emptyList(),visibilityEnvelope=env,
            contextMeta=mapOf("disclosure_reduced" to true,"maximum_disclosure" to level.name)
        )
        if (level.rank <= DisclosureLevel.DISCLOSE_REDACTED.rank) return source.copy(
            playerStatus=source.playerStatus.filterKeys { it in setOf("chapter","player_input") },
            scene=source.scene.filterKeys { it in setOf("query") },
            activeThreads=emptyList(), npcKnowledge=emptyList(), canonConstraints=emptyList(), retrievedLongTermMemory=emptyList(),
            playerSkills=emptyList(),playerTechniques=emptyList(),playerInventory=emptyList(),playerOrganizations=emptyList(),npcMemories=emptyList(),
            campaignTruth=emptyList(),canonDivergences=emptyList(),playerState=emptyMap(),
            contextMeta=source.contextMeta.filterKeys { it in setOf("engine","schema","campaign_id","chapter","audience_kind_uid","purpose_uid") } + ("disclosure_reduced" to true),
            visibilityEnvelope=env
        )
        if (level.rank <= DisclosureLevel.DISCLOSE_PARTIAL.rank) return source.copy(
            canonConstraints=emptyList(),retrievedLongTermMemory=emptyList(),npcMemories=emptyList(),campaignTruth=emptyList(),canonDivergences=emptyList(),
            playerState=emptyMap(),contextMeta=source.contextMeta + ("disclosure_reduced" to true),visibilityEnvelope=env
        )
        return source.copy(visibilityEnvelope=env, contextMeta=source.contextMeta + ("disclosure_reduced" to true))
    }
}

data class PatchOperation(val op:String,val table:String,val key:Map<String,Any?>,val values:Map<String,Any?>)
data class StatePatch(val transactionId:String,val operations:List<PatchOperation>,val chapterManifest:Map<String,Any?> = emptyMap(),val requiresValidation:Boolean = true)
data class PatchResult(val success:Boolean,val appliedOperations:Int,val message:String)
''')

    # ContextBuilder: trusted subject-bound active player and trusted cognition source.
    rel='app/src/main/java/com/rpgos/app/ContextBuilder.kt'; text=p(rel).read_text(encoding='utf-8')
    anchor='''        val playerUid = ActivePlayerStore(saveDb,campaignRef.campaignId).active()?.playerUid\n        val position = if(playerUid!=null) queryOne'''
    repl='''        val playerUid = ActivePlayerStore(saveDb,campaignRef.campaignId).active()?.playerUid\n        val trustedPrincipal = Phase38RuntimeAuthority.application(\n            audience,\n            controlledSubjectUids = if (audience.audienceKindUid == AudienceKinds.PLAYER && playerUid != null) setOf(playerUid) else emptySet(),\n            cognitionResolver = TrustedCognitionResolver { campaign, principal ->\n                if (purpose.purposeUid == VisibilityPurposeKinds.WORLD_ACTOR_REASONING && principal.uid.isNotBlank())\n                    setOf(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, principal.uid, campaign)) else emptySet()\n            }\n        )\n        val playerStateRequest = playerUid?.let { VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignRef.campaignId,VisibilitySubjectKinds.PLAYER_STATE,it)) }\n        val playerStateAuthorized = playerStateRequest?.let { visibility.decide(it, trustedPrincipal).level != DisclosureLevel.DENY } == true\n        val position = if(playerUid!=null && playerStateAuthorized) queryOne'''
    if anchor not in text: raise SystemExit('ContextBuilder player anchor missing')
    text=text.replace(anchor,repl)
    # Gate direct player domains throughout by changing common playerUid checks.
    text=text.replace('if(playerUid!=null)queryMany(saveDb,"SELECT stat_uid', 'if(playerUid!=null && playerStateAuthorized)queryMany(saveDb,"SELECT stat_uid')
    text=text.replace('if(playerUid!=null)queryMany(saveDb,"SELECT resource_uid', 'if(playerUid!=null && playerStateAuthorized)queryMany(saveDb,"SELECT resource_uid')
    text=text.replace('if(playerUid!=null)queryMany(saveDb,"SELECT ownership_record_uid', 'if(playerUid!=null && playerStateAuthorized)queryMany(saveDb,"SELECT ownership_record_uid')
    text=text.replace('if(playerUid!=null)queryMany(saveDb,"SELECT project_uid', 'if(playerUid!=null && playerStateAuthorized)queryMany(saveDb,"SELECT project_uid')
    text=text.replace('''            if(playerUid!=null){''','''            if(playerUid!=null && playerStateAuthorized){''')
    text=text.replace('''            audience.knowledgeHolders.forEach { holder ->''','''            trustedPrincipal?.cognitionHolders.orEmpty().forEach { holder ->''')
    text=text.replace('''                visibility.project(req){projection.forHolders(listOf(holder))}.value?.let(knowledgeRows::addAll)''','''                visibility.project(req,trustedPrincipal){projection.forHolders(listOf(holder))}.value?.let(knowledgeRows::addAll)''')
    text=text.replace('''        val skills=if(playerUid!=null){''','''        val skills=if(playerUid!=null && playerStateAuthorized){''')
    text=text.replace('''        val techniques=if(playerUid!=null){''','''        val techniques=if(playerUid!=null && playerStateAuthorized){''')
    text=text.replace('''        val inventory=if(playerUid!=null){''','''        val inventory=if(playerUid!=null && playerStateAuthorized){''')
    text=text.replace('''        val organizations=if(playerUid!=null)queryMany''','''        val organizations=if(playerUid!=null && playerStateAuthorized)queryMany''')
    text=text.replace('''        val playerState = if(playerUid!=null) PlayerStateStore(saveDb,campaignRef.campaignId).load()?.toContextMap() ?: emptyMap() else emptyMap()''','''        val playerState = if(playerUid!=null && playerStateAuthorized) PlayerStateStore(saveDb,campaignRef.campaignId).load()?.toContextMap() ?: emptyMap() else emptyMap()''')
    # truth private helpers use trusted only for privilege; public player remains denied anyway.
    p(rel).write_text(text, encoding='utf-8')

    # Safe repository interface: raw protected reads/handles leave application facade.
    rel='app/src/main/java/com/rpgos/app/GameRepository.kt'; text=p(rel).read_text(encoding='utf-8')
    for line in [
        '    fun playerState(): PlayerStateSnapshot?\n',
        '    fun playerStats(): List<PlayerStat>\n',
        '    fun playerResources(): List<PlayerResource>\n',
        '    fun openWorldDb(): SQLiteDatabase\n',
        '    fun openCoreDb(): SQLiteDatabase\n'
    ]: text=text.replace(line,'')
    text=text.replace('''    fun setActivePlayer(playerUid: String): ActivePlayerRef\n''','''    fun setActivePlayer(playerUid: String): ActivePlayerRef\n    fun protectedReads(): ProtectedCampaignReadRepository\n''')
    text=text.replace('import android.database.sqlite.SQLiteDatabase\n','')
    p(rel).write_text(text,encoding='utf-8')

    rel='app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt'; text=p(rel).read_text(encoding='utf-8')
    text=text.replace('''    override fun playerState(): PlayerStateSnapshot? = store.playerState()''','''    internal fun infrastructurePlayerState(): PlayerStateSnapshot? = store.playerState()\n    override fun protectedReads(): ProtectedCampaignReadRepository = ProtectedCampaignReadRepository(::openGameplaySaveDb, activeCampaignRef().campaignId, ::activePlayerRef)''')
    text=text.replace('''    override fun playerStats(): List<PlayerStat> = store.playerStats()''','''    internal fun infrastructurePlayerStats(): List<PlayerStat> = store.playerStats()''')
    text=text.replace('''    override fun playerResources(): List<PlayerResource> = store.playerResources()''','''    internal fun infrastructurePlayerResources(): List<PlayerResource> = store.playerResources()''')
    text=text.replace('''    override fun openWorldDb(): SQLiteDatabase = store.openWorldDb()\n    override fun openCoreDb(): SQLiteDatabase = store.openCoreDb()''','''    internal fun infrastructureOpenWorldDb(): SQLiteDatabase = store.openWorldDb()\n    internal fun infrastructureOpenCoreDb(): SQLiteDatabase = store.openCoreDb()''')
    p(rel).write_text(text,encoding='utf-8')

    # Full semantic visual request binding.
    write('app/src/main/java/com/rpgos/app/Phase38VisualAuthorization.kt', r'''package com.rpgos.app

import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

object VisualInputOrigins { const val CAMPAIGN_PROJECTION="CAMPAIGN_PROJECTION"; const val USER_STANDALONE="USER_STANDALONE" }
object VisualRequestKinds { const val GENERATE="GENERATE"; const val EDIT="EDIT" }

data class VisualSemanticRequest(
    val campaignUid:String,
    val audienceKindUid:String,
    val principalUid:String?,
    val purposeUid:String,
    val subjectKindUid:String,
    val subjectUid:String,
    val requestUid:String,
    val requestKindUid:String,
    val promptOrInstruction:String,
    val relatedEntityUid:String?=null,
    val sourceVisualUid:String?=null,
    val sourceImageSha256:String?=null
) {
    init {
        require(campaignUid.isNotBlank()&&audienceKindUid.isNotBlank()&&purposeUid.isNotBlank()&&subjectKindUid.isNotBlank()&&subjectUid.isNotBlank()&&requestUid.isNotBlank())
        require(requestKindUid in setOf(VisualRequestKinds.GENERATE,VisualRequestKinds.EDIT))
        if(requestKindUid==VisualRequestKinds.EDIT){require(!sourceVisualUid.isNullOrBlank());require(sourceImageSha256?.matches(Regex("[0-9a-f]{64}"))==true)}
    }
    fun semanticDigest():String=Phase38VisualAuthorization.digest(listOf(campaignUid,audienceKindUid,principalUid?:"",purposeUid,subjectKindUid,subjectUid,requestUid,requestKindUid,promptOrInstruction,relatedEntityUid?:"",sourceVisualUid?:"",sourceImageSha256?:"").joinToString("\u001f"))
}

data class Phase38VisualAuthorization(
    val campaignUid:String,val audienceKindUid:String,val audienceUid:String?,val purposeUid:String,
    val projectionAuthorityUid:String,val projectionVersionUid:String,val disclosureCeiling:DisclosureLevel,val payloadDisclosure:DisclosureLevel,
    val subjectKindUid:String,val subjectUid:String,val requestUid:String,val requestKindUid:String,
    val payloadSha256:String,val semanticRequestSha256:String,val inputOriginUid:String,
    val relatedEntityUid:String?=null,val sourceVisualUid:String?=null,val sourceImageSha256:String?=null
){
    init{
        require(projectionAuthorityUid==VisibilityAuthorityService.AUTHORITY_UID);require(projectionVersionUid==VisibilityAuthorityService.PROJECTION_VERSION_UID)
        require(audienceKindUid in setOf(AudienceKinds.PLAYER,AudienceKinds.PLAYER_CHARACTER));require(purposeUid in visualPurposes)
        require(disclosureCeiling!=DisclosureLevel.DENY&&disclosureCeiling.canReduceTo(payloadDisclosure));require(payloadSha256.matches(Regex("[0-9a-f]{64}")));require(semanticRequestSha256.matches(Regex("[0-9a-f]{64}")))
        if(requestKindUid==VisualRequestKinds.EDIT){require(!sourceVisualUid.isNullOrBlank());require(sourceImageSha256?.matches(Regex("[0-9a-f]{64}"))==true)}
    }
    fun requireRequest(request:VisualSemanticRequest){
        if(campaignUid!=request.campaignUid)throw VisibilityAuthorityFailure.CrossCampaign()
        require(audienceKindUid==request.audienceKindUid&&audienceUid==request.principalUid){"RPGOS-VISIBILITY:VISUAL_PRINCIPAL_MISMATCH"}
        require(purposeUid==request.purposeUid&&subjectKindUid==request.subjectKindUid&&subjectUid==request.subjectUid){"RPGOS-VISIBILITY:VISUAL_SUBJECT_OR_PURPOSE_MISMATCH"}
        require(requestUid==request.requestUid&&requestKindUid==request.requestKindUid){"RPGOS-VISIBILITY:VISUAL_REQUEST_IDENTITY_MISMATCH"}
        require(payloadSha256==digest(request.promptOrInstruction)){"RPGOS-VISIBILITY:VISUAL_PAYLOAD_SUBSTITUTION"}
        require(semanticRequestSha256==request.semanticDigest()){"RPGOS-VISIBILITY:VISUAL_SEMANTIC_SUBSTITUTION"}
        require(relatedEntityUid==request.relatedEntityUid&&sourceVisualUid==request.sourceVisualUid&&sourceImageSha256==request.sourceImageSha256){"RPGOS-VISIBILITY:VISUAL_SOURCE_SUBSTITUTION"}
    }
    fun requireRequest(campaignUid:String,expectedPurpose:String,payload:String){
        require(requestKindUid==VisualRequestKinds.GENERATE){"RPGOS-VISIBILITY:LEGACY_CHECK_FORBIDDEN_FOR_EDIT"}
        if(this.campaignUid!=campaignUid)throw VisibilityAuthorityFailure.CrossCampaign();require(purposeUid==expectedPurpose);require(payloadSha256==digest(payload))
    }
    fun toJson()=JSONObject().apply{
        put("campaign_uid",campaignUid);put("audience_kind_uid",audienceKindUid);put("audience_uid",audienceUid);put("purpose_uid",purposeUid);put("authority_uid",projectionAuthorityUid);put("projection_version_uid",projectionVersionUid)
        put("disclosure_ceiling",disclosureCeiling.name);put("payload_disclosure",payloadDisclosure.name);put("subject_kind_uid",subjectKindUid);put("subject_uid",subjectUid);put("request_uid",requestUid);put("request_kind_uid",requestKindUid)
        put("payload_sha256",payloadSha256);put("semantic_request_sha256",semanticRequestSha256);put("input_origin_uid",inputOriginUid);put("related_entity_uid",relatedEntityUid);put("source_visual_uid",sourceVisualUid);put("source_image_sha256",sourceImageSha256)
    }
    companion object{
        val visualPurposes=setOf(VisibilityPurposeKinds.SCENE_VISUALIZATION,VisibilityPurposeKinds.CHARACTER_VISUALIZATION,VisibilityPurposeKinds.LOCATION_VISUALIZATION,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION)
        fun authorize(envelope:VisibilityProjectionEnvelope,expectedPurpose:String,subjectKindUid:String,subjectUid:String,payload:String,inputOriginUid:String=VisualInputOrigins.CAMPAIGN_PROJECTION,requestUid:String=UUID.randomUUID().toString(),payloadDisclosure:DisclosureLevel=envelope.maximumDisclosure,relatedEntityUid:String?=null,sourceVisualUid:String?=null,sourceImageSha256:String?=null):Phase38VisualAuthorization{
            envelope.requirePurpose(expectedPurpose);if(envelope.maximumDisclosure==DisclosureLevel.DENY)throw VisibilityAuthorityFailure.Escalation()
            val kind=if(expectedPurpose==VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION)VisualRequestKinds.EDIT else VisualRequestKinds.GENERATE
            val req=VisualSemanticRequest(envelope.campaignUid,envelope.audience.audienceKindUid,envelope.audience.principal?.uid,expectedPurpose,subjectKindUid,subjectUid,requestUid,kind,payload,relatedEntityUid,sourceVisualUid,sourceImageSha256)
            return Phase38VisualAuthorization(envelope.campaignUid,envelope.audience.audienceKindUid,envelope.audience.principal?.uid,expectedPurpose,envelope.authorityUid,envelope.projectionVersionUid,envelope.maximumDisclosure,payloadDisclosure,subjectKindUid,subjectUid,requestUid,kind,digest(payload),req.semanticDigest(),inputOriginUid,relatedEntityUid,sourceVisualUid,sourceImageSha256)
        }
        fun digest(payload:String)=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
}
''')

    # Strengthen consumer inventory to explicit protected entry-point patterns.
    rel='app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt'
    if p(rel).exists():
        text=p(rel).read_text(encoding='utf-8')
        text=text.replace('''    fun looksProtected(source: String): Boolean =''','''    private val forbiddenDirectSymbols = listOf("CampaignTruthStore(", "PlayerStateStore(", "KnowledgeStore(", ".openWorldDb()", ".openCoreDb()")\n    fun hasForbiddenDirectProtectedEntryPoint(source: String): Boolean = forbiddenDirectSymbols.any(source::contains)\n\n    fun looksProtected(source: String): Boolean =''')
        p(rel).write_text(text,encoding='utf-8')

    # A+B regression tests independent of legacy test expectations.
    write('app/src/test/java/com/rpgos/app/Phase38StructuralHardeningTest.kt', r'''package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase38StructuralHardeningTest {
    private val c="C1"
    @Test fun untrustedPrivilegedAudienceCannotSelfAuthorize(){
        val a=AudienceContext(c,AudienceKinds.DEVELOPER_DIAGNOSTIC,VisibilityPrincipalRef("RUNTIME","x"))
        val p=PurposeContext(c,VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)
        val r=VisibilityRequest(a,p,VisibilitySubjectRef(c,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"T"))
        assertEquals(DisclosureLevel.DENY,VisibilityAuthorityService().decide(r).level)
    }
    @Test fun callerSuppliedHolderMappingIsNotAuthority(){
        val forged=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,"B",c)
        val a=AudienceContext(c,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"),listOf(forged))
        val p=PurposeContext(c,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        val r=VisibilityRequest(a,p,VisibilitySubjectRef(c,VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE,"B",holder=forged))
        assertEquals(DisclosureLevel.DENY,VisibilityAuthorityService().decide(r).level)
    }
    @Test fun pcBDoesNotReceivePcAState(){
        val a=AudienceContext(c,AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","B"))
        val trusted=Phase38RuntimeAuthority.application(a,controlledSubjectUids=setOf("B"))!!
        val p=PurposeContext(c,VisibilityPurposeKinds.PLAYER_UI)
        val r=VisibilityRequest(a,p,VisibilitySubjectRef(c,VisibilitySubjectKinds.PLAYER_STATE,"A"))
        assertEquals(DisclosureLevel.DENY,VisibilityAuthorityService().decide(r,trusted).level)
    }
    @Test fun worldActorReasoningNeverGetsPlayerStateByDefault(){
        val a=AudienceContext(c,AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"));val t=Phase38RuntimeAuthority.application(a)!!
        val r=VisibilityRequest(a,PurposeContext(c,VisibilityPurposeKinds.WORLD_ACTOR_REASONING),VisibilitySubjectRef(c,VisibilitySubjectKinds.PLAYER_STATE,"PC"))
        assertEquals(DisclosureLevel.DENY,VisibilityAuthorityService().decide(r,t).level)
    }
    @Test fun lowerDisclosureActuallyRemovesSecretPayload(){
        val a=VisibilityAudienceFactory.player(c);val e=VisibilityAuthorityService().envelope(a,PurposeContext(c,VisibilityPurposeKinds.GAMEPLAY_NARRATION))
        val b=ContextBundle(mapOf("chapter" to 1,"secret" to "S"),mapOf("query" to "q","secret" to "S"),emptyMap(),emptyList(),emptyList(),listOf(mapOf("secret" to "S")),emptyList(),emptyList(),listOf(mapOf("secret" to "S")),emptyList(),listOf(mapOf("secret" to "S")),campaignTruth=listOf(mapOf("secret" to "S")),playerState=mapOf("secret" to "S"),visibilityEnvelope=e)
        val reduced=b.reduceDisclosureTo(DisclosureLevel.DISCLOSE_REDACTED)
        assertFalse(reduced.toString().contains("secret=S"));assertEquals(DisclosureLevel.DISCLOSE_REDACTED,reduced.visibilityEnvelope.maximumDisclosure)
    }
    @Test fun typedDeniedAndNoDataAreDistinct(){
        assertNotEquals(ProtectedReadResult.Deny("x").stateUid,ProtectedReadResult.NoData.stateUid)
    }
    @Test fun visualAuthorizationBindsEditSourceAndImageDigest(){
        val a=VisibilityAudienceFactory.player(c);val e=VisibilityAuthorityService().envelope(a,PurposeContext(c,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION))
        val digest=Phase38VisualAuthorization.digest("bytes")
        val auth=Phase38VisualAuthorization.authorize(e,VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1","edit",sourceVisualUid="V1",sourceImageSha256=digest,requestUid="R1")
        val ok=VisualSemanticRequest(c,AudienceKinds.PLAYER,"HUMAN_PLAYER",VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,"VISUAL","V1","R1",VisualRequestKinds.EDIT,"edit",sourceVisualUid="V1",sourceImageSha256=digest)
        auth.requireRequest(ok)
        assertTrue(runCatching{auth.requireRequest(ok.copy(sourceVisualUid="V2"))}.isFailure)
    }
}
''')

    # Phase37 source assertion must now require trusted mapping, not caller list.
    rel='app/src/test/java/com/rpgos/app/Phase37WorldActorKnowledgeTest.kt'
    text=p(rel).read_text(encoding='utf-8').replace('assertTrue(code.contains("audience.knowledgeHolders"))','assertTrue(code.contains("trustedPrincipal?.cognitionHolders"))')
    p(rel).write_text(text,encoding='utf-8')


def slice_c():
    write('app/src/main/java/com/rpgos/app/Phase38AccessAuthority.kt', r'''package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class AccessBindingKind { ROLE, ORGANIZATION, CLEARANCE, CONTROL }
enum class AccessGrantKind { EXPLICIT, DELEGATED, TEMPORARY, WORLD_RULE }
enum class AccessOperation { UPSERT_BINDING, REVOKE_BINDING, GRANT, REVOKE_GRANT, SET_CARRIER_ACCESS, BIND_COGNITION }

data class InformationCarrierRef(val campaignUid:String,val carrierKindUid:String,val carrierUid:String){init{require(campaignUid.isNotBlank()&&carrierKindUid.isNotBlank()&&carrierUid.isNotBlank())}}
data class AccessRequirement(
    val policyUid:String,
    val requiredRoleUids:Set<String> = emptySet(),
    val requiredOrganizationUids:Set<String> = emptySet(),
    val requiredClearanceUids:Set<String> = emptySet(),
    val explicitGrantRequired:Boolean=false,
    val carrier:InformationCarrierRef?=null
){init{require(policyUid.isNotBlank())}}

data class AccessPath(val mechanismUid:String,val evidenceUid:String,val authorized:Boolean,val effectiveNow:Boolean){init{require(mechanismUid.isNotBlank()&&evidenceUid.isNotBlank())}}
data class AuthorizationDecision(val authorized:Boolean,val reasonCode:String)
data class EffectiveAccessDecision(val accessible:Boolean,val reasonCode:String,val path:AccessPath?=null)

data class AccessAuthorityChange(
    val operation:AccessOperation,
    val recordUid:String,
    val principalKindUid:String,
    val principalUid:String,
    val bindingOrGrantKindUid:String,
    val valueUid:String,
    val subjectKindUid:String?=null,
    val subjectUid:String?=null,
    val validFromOrder:Long,
    val validUntilOrder:Long?=null,
    val delegatedByPrincipalUid:String?=null
):PlayerDomainChangePayload{
    init{
        require(recordUid.isNotBlank()&&principalKindUid.isNotBlank()&&principalUid.isNotBlank()&&bindingOrGrantKindUid.isNotBlank()&&valueUid.isNotBlank())
        require(validFromOrder>=0L);require(validUntilOrder==null||validUntilOrder>=validFromOrder)
        require(subjectKindUid?.isBlank()!=true&&subjectUid?.isBlank()!=true)
    }
}

object Phase38AccessAuthoritySchema{
    const val VERSION=1
    const val RECORDS="phase38_access_authority_records"
    fun ensureReady(db:SQLiteDatabase){
        db.execSQL("""CREATE TABLE IF NOT EXISTS $RECORDS(
            campaign_uid TEXT NOT NULL,record_uid TEXT NOT NULL,operation_uid TEXT NOT NULL,principal_kind_uid TEXT NOT NULL,principal_uid TEXT NOT NULL,
            binding_or_grant_kind_uid TEXT NOT NULL,value_uid TEXT NOT NULL,subject_kind_uid TEXT,subject_uid TEXT,
            valid_from_order INTEGER NOT NULL,valid_until_order INTEGER,delegated_by_principal_uid TEXT,transaction_uid TEXT NOT NULL,turn_uid TEXT NOT NULL,change_uid TEXT NOT NULL,
            created_order INTEGER NOT NULL,PRIMARY KEY(campaign_uid,record_uid,created_order))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p38_access_principal ON $RECORDS(campaign_uid,principal_kind_uid,principal_uid,created_order)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p38_access_subject ON $RECORDS(campaign_uid,subject_kind_uid,subject_uid,created_order)")
    }
    fun isReady(db:SQLiteDatabase)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(RECORDS)).use{it.moveToFirst()}
}

data class AccessAuthorityRecord(
    val recordUid:String,val operation:AccessOperation,val principal:VisibilityPrincipalRef,val kindUid:String,val valueUid:String,
    val subjectKindUid:String?,val subjectUid:String?,val validFromOrder:Long,val validUntilOrder:Long?,val createdOrder:Long
)

class AccessAuthorityStore(private val db:SQLiteDatabase,private val campaignUid:String){
    init{require(campaignUid.isNotBlank())}
    internal fun apply(identity:TurnTransactionIdentity,changeUid:String,p:AccessAuthorityChange,createdOrder:Long){
        require(db.inTransaction());require(identity.campaignUid==campaignUid)
        db.execSQL("""INSERT INTO ${Phase38AccessAuthoritySchema.RECORDS}(
            campaign_uid,record_uid,operation_uid,principal_kind_uid,principal_uid,binding_or_grant_kind_uid,value_uid,subject_kind_uid,subject_uid,
            valid_from_order,valid_until_order,delegated_by_principal_uid,transaction_uid,turn_uid,change_uid,created_order)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(campaignUid,p.recordUid,p.operation.name,p.principalKindUid,p.principalUid,p.bindingOrGrantKindUid,p.valueUid,p.subjectKindUid,p.subjectUid,p.validFromOrder,p.validUntilOrder,p.delegatedByPrincipalUid,identity.transactionUid,identity.turnUid,changeUid,createdOrder))
    }
    fun effective(principal:VisibilityPrincipalRef,atOrder:Long=Long.MAX_VALUE):List<AccessAuthorityRecord>{
        check(Phase38AccessAuthoritySchema.isReady(db)){"RPGOS-P38-ACCESS:SCHEMA_NOT_READY"}
        return db.rawQuery("""SELECT record_uid,operation_uid,binding_or_grant_kind_uid,value_uid,subject_kind_uid,subject_uid,valid_from_order,valid_until_order,created_order
            FROM ${Phase38AccessAuthoritySchema.RECORDS} WHERE campaign_uid=? AND principal_kind_uid=? AND principal_uid=? AND valid_from_order<=? ORDER BY created_order""",
            arrayOf(campaignUid,principal.kindUid,principal.uid,atOrder.toString())).use{c->
            val timeline=mutableListOf<AccessAuthorityRecord>();while(c.moveToNext()) timeline+=AccessAuthorityRecord(c.getString(0),AccessOperation.valueOf(c.getString(1)),principal,c.getString(2),c.getString(3),if(c.isNull(4))null else c.getString(4),if(c.isNull(5))null else c.getString(5),c.getLong(6),if(c.isNull(7))null else c.getLong(7),c.getLong(8))
            val revoked=timeline.filter{it.operation in setOf(AccessOperation.REVOKE_BINDING,AccessOperation.REVOKE_GRANT)}.map{it.valueUid}.toSet()
            timeline.filter{it.operation in setOf(AccessOperation.UPSERT_BINDING,AccessOperation.GRANT,AccessOperation.SET_CARRIER_ACCESS,AccessOperation.BIND_COGNITION)&&it.valueUid !in revoked&&(it.validUntilOrder==null||atOrder<=it.validUntilOrder)}
        }
    }
}

class UniversalAccessAuthority(private val store:AccessAuthorityStore){
    fun trustedContext(audience:AudienceContext,atOrder:Long=Long.MAX_VALUE,cognitionResolver:TrustedCognitionResolver=TrustedCognitionResolver{_,_->emptySet()}):TrustedPrincipalContext?{
        val principal=audience.principal?:return null
        val records=store.effective(principal,atOrder)
        val roles=records.filter{it.kindUid==AccessBindingKind.ROLE.name}.map{it.valueUid}.toSet()
        val orgs=records.filter{it.kindUid==AccessBindingKind.ORGANIZATION.name}.map{it.valueUid}.toSet()
        val clearances=records.filter{it.kindUid==AccessBindingKind.CLEARANCE.name}.map{it.valueUid}.toSet()
        val controls=records.filter{it.kindUid==AccessBindingKind.CONTROL.name}.mapNotNull{it.subjectUid}.toSet()
        return Phase38RuntimeAuthority.application(audience,controls,cognitionResolver,roles,orgs,clearances)
    }
    fun authorize(trusted:TrustedPrincipalContext,requirement:AccessRequirement):AuthorizationDecision{
        if(requirement.requiredRoleUids.any{it !in trusted.roleUids})return AuthorizationDecision(false,"ROLE_REQUIRED")
        if(requirement.requiredOrganizationUids.any{it !in trusted.organizationUids})return AuthorizationDecision(false,"ORGANIZATION_REQUIRED")
        if(requirement.requiredClearanceUids.any{it !in trusted.clearanceUids})return AuthorizationDecision(false,"CLEARANCE_REQUIRED")
        val hasExplicit=store.effective(trusted.principal).any{it.kindUid in AccessGrantKind.entries.map(AccessGrantKind::name)&&it.valueUid==requirement.policyUid}
        if(requirement.explicitGrantRequired&&!hasExplicit)return AuthorizationDecision(false,"EXPLICIT_GRANT_REQUIRED")
        return AuthorizationDecision(true,"AUTHORIZED")
    }
    fun effectiveAccess(authorization:AuthorizationDecision,path:AccessPath?=null):EffectiveAccessDecision{
        if(authorization.authorized)return EffectiveAccessDecision(true,"AUTHORIZED_ACCESS",path)
        if(path?.effectiveNow==true)return EffectiveAccessDecision(true,"EFFECTIVE_BYPASS:${path.mechanismUid}",path)
        return EffectiveAccessDecision(false,"NO_EFFECTIVE_ACCESS",path)
    }
}
''')

    # PlayerChangeSet model gains first-class access mutation.
    rel='app/src/main/java/com/rpgos/app/PlayerChangeSetModel.kt'
    replace(rel,'sealed interface PlayerDomainChangePayload\n','sealed interface PlayerDomainChangePayload\n')
    insert_before(rel,'data class StatChange(','''// Phase38 access authority is a first-class canonical domain, distinct from campaign truth.\n''')
    replace(rel,'    const val DEVELOPMENT_PROJECT = "RPGOS-CHANGE:DEVELOPMENT_PROJECT_WORK"\n','    const val DEVELOPMENT_PROJECT = "RPGOS-CHANGE:DEVELOPMENT_PROJECT_WORK"\n    const val ACCESS_AUTHORITY = "RPGOS-CHANGE:ACCESS_AUTHORITY"\n')

    # Register codec before CONDITION entry.
    rel='app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt'
    anchor='''    PlayerChangeKinds.CONDITION to simpleCodec('''
    codec=r'''    PlayerChangeKinds.ACCESS_AUTHORITY to simpleCodec(
        AccessAuthorityChange::class, ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,
        setOf("operation","recordUid","principalKindUid","principalUid","bindingOrGrantKindUid","valueUid","subjectKindUid","subjectUid","validFromOrder","validUntilOrder","delegatedByPrincipalUid"),
        encode = { pcsObj("operation" to pcsJ(it.operation.name),"recordUid" to pcsJ(it.recordUid),"principalKindUid" to pcsJ(it.principalKindUid),"principalUid" to pcsJ(it.principalUid),"bindingOrGrantKindUid" to pcsJ(it.bindingOrGrantKindUid),"valueUid" to pcsJ(it.valueUid),"subjectKindUid" to pcsJn(it.subjectKindUid),"subjectUid" to pcsJn(it.subjectUid),"validFromOrder" to pcsJ(it.validFromOrder),"validUntilOrder" to (it.validUntilOrder?.let(::JsonPrimitive)?:JsonNull),"delegatedByPrincipalUid" to pcsJn(it.delegatedByPrincipalUid)) },
        decode = { AccessAuthorityChange(enumValue(it.pcsReqString("operation"),"INVALID_ACCESS_OPERATION"),it.pcsReqString("recordUid"),it.pcsReqString("principalKindUid"),it.pcsReqString("principalUid"),it.pcsReqString("bindingOrGrantKindUid"),it.pcsReqString("valueUid"),it.pcsOptString("subjectKindUid"),it.pcsOptString("subjectUid"),it.pcsReqLong("validFromOrder"),it.pcsOptLong("validUntilOrder"),it.pcsOptString("delegatedByPrincipalUid")) },
        validate = { buildList { if(it.recordUid.isBlank()||it.principalKindUid.isBlank()||it.principalUid.isBlank()||it.bindingOrGrantKindUid.isBlank()||it.valueUid.isBlank())add("INVALID_ACCESS_AUTHORITY_CHANGE");if(it.validUntilOrder!=null&&it.validUntilOrder<it.validFromOrder)add("INVALID_ACCESS_AUTHORITY_CHANGE") } },
        conflicts = { setOf(compositeConflictKey("ACCESS",it.principalKindUid,it.principalUid,it.recordUid,it.validFromOrder.toString())) }
    ),
'''
    insert_before(rel,anchor,codec)

    # TurnTransaction preflight/applier support.
    rel='app/src/main/java/com/rpgos/app/TurnTransaction.kt'
    replace(rel,'is DevelopmentProjectChange,is KnowledgeAcquisitionChange -> Unit','is DevelopmentProjectChange,is KnowledgeAcquisitionChange,is AccessAuthorityChange -> Unit')
    replace(rel,'is DevelopmentProjectChange->applyProject(db,identity,changeSet,change.changeUid,payload)','is DevelopmentProjectChange->applyProject(db,identity,changeSet,change.changeUid,payload)\n                is AccessAuthorityChange->applyAccessAuthority(db,identity,changeSet,change.changeUid,payload)')
    # Insert applier before applyStat.
    insert_before(rel,'    private fun applyStat(','''    private fun applyAccessAuthority(db:SQLiteDatabase,identity:TurnTransactionIdentity,changeSet:PlayerChangeSet,changeUid:String,p:AccessAuthorityChange){\n        AccessAuthorityStore(db,identity.campaignUid).apply(identity,changeUid,p,effectiveOrder(changeSet))\n    }\n\n''')

    # Runtime classification, replay coverage and writer family.
    rel='app/src/main/java/com/rpgos/app/RuntimeTruthLayerRegistry.kt'
    insert_before(rel,'        f("CAMPAIGN_TRUTH"', '        RuntimeStateFamily("ACCESS_AUTHORITY",setOf(RuntimeTruthLayer.AUTHORITATIVE,RuntimeTruthLayer.AUTHORITATIVE_DOMAIN_HISTORY),setOf(Phase38AccessAuthoritySchema.RECORDS)),\n')
    rel='app/src/main/java/com/rpgos/app/RuntimePersistentInventory.kt'
    replace(rel,'        "COMMITTED_REPLAY_MATERIAL"\n','        "COMMITTED_REPLAY_MATERIAL",\n        "ACCESS_AUTHORITY"\n')
    rel='app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt'
    replace(rel,'"OWNERSHIP_HISTORY","FINANCE_AUTHORITY","DEVELOPMENT_PROJECTS","NPC_KNOWLEDGE_STATE"','"OWNERSHIP_HISTORY","FINANCE_AUTHORITY","DEVELOPMENT_PROJECTS","NPC_KNOWLEDGE_STATE","ACCESS_AUTHORITY"')

    # Phase36 family/version and schema activation.
    rel='app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt'
    replace(rel,'    CANON_DIVERGENCE, KNOWLEDGE, FINANCE, INVENTORY, OWNERSHIP, DEVELOPMENT_PROJECT\n','    CANON_DIVERGENCE, KNOWLEDGE, FINANCE, INVENTORY, OWNERSHIP, DEVELOPMENT_PROJECT, ACCESS_AUTHORITY\n')
    replace(rel,'        SchemaFamilyContract(SchemaFamilyUid.DEVELOPMENT_PROJECT, 1, 1, setOf(SchemaFamilyUid.PLAYER))\n','        SchemaFamilyContract(SchemaFamilyUid.DEVELOPMENT_PROJECT, 1, 1, setOf(SchemaFamilyUid.PLAYER)),\n        SchemaFamilyContract(SchemaFamilyUid.ACCESS_AUTHORITY, Phase38AccessAuthoritySchema.VERSION, 1, setOf(SchemaFamilyUid.CAMPAIGN,SchemaFamilyUid.EVENT))\n')
    replace(rel,'            Phase35CanonDivergenceSchema.ensureReady(db)\n','            Phase35CanonDivergenceSchema.ensureReady(db)\n            Phase38AccessAuthoritySchema.ensureReady(db)\n')

    write('app/src/test/java/com/rpgos/app/Phase38AccessAuthorityTest.kt', r'''package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class Phase38AccessAuthorityTest {
    @Test fun roleBindingIsPrincipalScopedAndCannotBeSelfAsserted(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val a=AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","A"))
        val forged=Phase38RuntimeAuthority.application(a,roleUids=setOf("ROLE"))!!
        val noStore=AccessAuthorityStore(db,"C");val auth=UniversalAccessAuthority(noStore)
        assertTrue(auth.authorize(forged,AccessRequirement("P",requiredRoleUids=setOf("ROLE"))).authorized)
        val trustedFromCanonical=auth.trustedContext(a)!!
        assertFalse(auth.authorize(trustedFromCanonical,AccessRequirement("P",requiredRoleUids=setOf("ROLE"))).authorized)
        db.close()
    }
    @Test fun authorizationAndEffectiveAccessAreDistinct(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db);val auth=UniversalAccessAuthority(AccessAuthorityStore(db,"C"))
        val denied=AuthorizationDecision(false,"NO_GRANT");assertFalse(auth.effectiveAccess(denied).accessible)
        assertTrue(auth.effectiveAccess(denied,AccessPath("INTERCEPT","E1",false,true)).accessible);db.close()
    }
    @Test fun accessDoesNotCreatePhase37Acquisition(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db);Phase37KnowledgeSchema.ensureReady(db)
        fun count()=db.rawQuery("SELECT COUNT(*) FROM ${Phase37KnowledgeSchema.ACQUISITIONS}",null).use{it.moveToFirst();it.getLong(0)}
        val before=count();UniversalAccessAuthority(AccessAuthorityStore(db,"C")).effectiveAccess(AuthorizationDecision(true,"OK"));assertEquals(before,count());db.close()
    }
    @Test fun temporaryGrantValidityIsTemporalAndNonDestructive(){
        val p=AccessAuthorityChange(AccessOperation.GRANT,"G","ENTITY","A",AccessGrantKind.TEMPORARY.name,"POLICY",validFromOrder=10,validUntilOrder=20)
        assertEquals(10,p.validFromOrder);assertEquals(20,p.validUntilOrder)
    }
}
''')


def slice_d():
    write('app/src/main/java/com/rpgos/app/Phase38PerceptionDisclosure.kt', r'''package com.rpgos.app

data class SignalRef(val campaignUid:String,val signalTypeUid:String,val signalUid:String,val sourceRefUid:String?=null){init{require(campaignUid.isNotBlank()&&signalTypeUid.isNotBlank()&&signalUid.isNotBlank())}}
data class ObservableEvidence(val signal:SignalRef,val observedProperties:Map<String,String>,val freshnessOrder:Long?,val confidence:Double){init{require(confidence in 0.0..1.0)}}
data class DetectionCapabilityRef(val capabilityTypeUid:String,val capabilityUid:String){init{require(capabilityTypeUid.isNotBlank()&&capabilityUid.isNotBlank())}}
data class PerceptionContext(val campaignUid:String,val principal:VisibilityPrincipalRef,val capability:DetectionCapabilityRef,val purposeUid:String)

enum class DetectionStage { NOT_DETECTED, DETECTED, LOCALIZED }
enum class RecognitionStage { UNRECOGNIZED, CATEGORY_RECOGNIZED, IDENTITY_CANDIDATE, RECOGNIZED }

data class Uncertainty(
    val confidence:Double,
    val precision:Double,
    val freshness:Double,
    val completeness:Double,
    val candidateUids:Set<String> = emptySet(),
    val numericRange:ClosedFloatingPointRange<Double>?=null
){init{listOf(confidence,precision,freshness,completeness).forEach{require(it in 0.0..1.0)}}}

data class PerceptionDecision(
    val detection:DetectionStage,
    val recognition:RecognitionStage,
    val classificationUid:String?,
    val interpretation:Map<String,String>,
    val uncertainty:Uncertainty,
    val evidenceUids:Set<String>
)

fun interface DetectionResolver { fun detect(context:PerceptionContext,evidence:List<ObservableEvidence>):DetectionStage }
fun interface RecognitionResolver { fun recognize(context:PerceptionContext,evidence:List<ObservableEvidence>,expertiseUids:Set<String>):Pair<RecognitionStage,String?> }
fun interface InterpretationResolver { fun interpret(context:PerceptionContext,evidence:List<ObservableEvidence>,expertiseUids:Set<String>):Map<String,String> }

class UniversalPerceptionEngine(
    private val detectionResolver:DetectionResolver,
    private val recognitionResolver:RecognitionResolver,
    private val interpretationResolver:InterpretationResolver
){
    fun perceive(context:PerceptionContext,evidence:List<ObservableEvidence>,expertiseUids:Set<String> = emptySet()):PerceptionDecision{
        if(evidence.isEmpty())return PerceptionDecision(DetectionStage.NOT_DETECTED,RecognitionStage.UNRECOGNIZED,null,emptyMap(),Uncertainty(0.0,0.0,1.0,0.0),emptySet())
        val detection=detectionResolver.detect(context,evidence)
        if(detection==DetectionStage.NOT_DETECTED)return PerceptionDecision(detection,RecognitionStage.UNRECOGNIZED,null,emptyMap(),Uncertainty(0.0,0.0,1.0,0.0),evidence.map{it.signal.signalUid}.toSet())
        val (recognition,classification)=recognitionResolver.recognize(context,evidence,expertiseUids)
        val interpretation=interpretationResolver.interpret(context,evidence,expertiseUids)
        val conf=evidence.map{it.confidence}.average().coerceIn(0.0,1.0)
        return PerceptionDecision(detection,recognition,classification,interpretation,Uncertainty(conf,if(recognition==RecognitionStage.RECOGNIZED)0.9 else 0.4,0.8,(evidence.size/4.0).coerceAtMost(1.0)),evidence.map{it.signal.signalUid}.toSet())
    }
}

data class DisclosureProjection<T>(val level:DisclosureLevel,val value:T?,val uncertainty:Uncertainty?,val removedPropertyUids:Set<String> = emptySet())
fun interface DomainDisclosurePolicy<T>{fun project(value:T,level:DisclosureLevel,uncertainty:Uncertainty?):DisclosureProjection<T>}
class DisclosureResolver{
    fun <T> project(value:T,level:DisclosureLevel,uncertainty:Uncertainty?,policy:DomainDisclosurePolicy<T>):DisclosureProjection<T>{
        if(level==DisclosureLevel.DENY)return DisclosureProjection(level,null,uncertainty)
        return policy.project(value,level,uncertainty).also{require(it.level.rank<=level.rank){"RPGOS-P38:DISCLOSURE_POLICY_ESCALATION"}}
    }
}

/** Future combat hook: physics may receive fact; volition receives only actor-available perception. */
data class CombatPreparationInput<FACT>(val objectivePhysicsFact:FACT,val actorPerception:PerceptionDecision,val actorKnowledgeEvidenceUids:Set<String>)
''')

    write('app/src/test/java/com/rpgos/app/Phase38PerceptionDisclosureTest.kt', r'''package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase38PerceptionDisclosureTest {
    private val ctx=PerceptionContext("C",VisibilityPrincipalRef("OBSERVER","A"),DetectionCapabilityRef("CAP","C1"),"OBSERVE")
    private val engine=UniversalPerceptionEngine(
        DetectionResolver{_,e->if(e.isEmpty())DetectionStage.NOT_DETECTED else DetectionStage.DETECTED},
        RecognitionResolver{_,e,expertise->if("EXPERT" in expertise) RecognitionStage.CATEGORY_RECOGNIZED to e.first().observedProperties["category"] else RecognitionStage.UNRECOGNIZED to null},
        InterpretationResolver{_,e,expertise->mapOf("quality" to if("EXPERT" in expertise)"HIGH" else "LOW","observed" to e.first().observedProperties.keys.sorted().joinToString(","))}
    )
    @Test fun absentSignalMeansNoDetection(){assertEquals(DetectionStage.NOT_DETECTED,engine.perceive(ctx,emptyList()).detection)}
    @Test fun detectionDoesNotImplyRecognition(){val e=listOf(ObservableEvidence(SignalRef("C","GENERIC","S"),mapOf("shape" to "x"),1,.8));val p=engine.perceive(ctx,e);assertEquals(DetectionStage.DETECTED,p.detection);assertEquals(RecognitionStage.UNRECOGNIZED,p.recognition)}
    @Test fun hiddenObjectiveIdentityCannotCorrectPerception(){val e=listOf(ObservableEvidence(SignalRef("C","GENERIC","DECOY",sourceRefUid="OBJECTIVE-SECRET"),mapOf("category" to "DECOY"),1,.7));val p=engine.perceive(ctx,e,setOf("EXPERT"));assertEquals("DECOY",p.classificationUid);assertFalse(p.interpretation.values.contains("OBJECTIVE-SECRET"))}
    @Test fun expertiseChangesInterpretationNotEvidence(){val e=listOf(ObservableEvidence(SignalRef("C","GENERIC","S"),mapOf("category" to "UNKNOWN"),1,.6));val a=engine.perceive(ctx,e);val b=engine.perceive(ctx,e,setOf("EXPERT"));assertEquals(a.evidenceUids,b.evidenceUids);assertNotEquals(a.interpretation,b.interpretation)}
    @Test fun disclosurePolicyActuallyRemovesExactValueAndPreservesUncertainty(){
        val u=Uncertainty(.6,.4,.8,.5,numericRange=90.0..110.0)
        val policy=DomainDisclosurePolicy<Map<String,String>>{v,l,unc->if(l.rank<DisclosureLevel.DETAILED.rank)DisclosureProjection(l,mapOf("category" to (v["category"]?:"unknown")),unc,setOf("exact")) else DisclosureProjection(l,v,unc)}
        val p=DisclosureResolver().project(mapOf("category" to "unit","exact" to "100"),DisclosureLevel.RANGE,u,policy)
        assertFalse(p.value!!.containsKey("exact"));assertEquals(90.0,p.uncertainty!!.numericRange!!.start,0.0)
    }
    @Test fun genericCoreHandlesOrdinaryTechSupernaturalAndCollectiveWithoutBranches(){
        listOf("ORDINARY","TECH_PLACEHOLDER","SUPERNATURAL_PLACEHOLDER","COLLECTIVE").forEach{kind->val c=ctx.copy(principal=VisibilityPrincipalRef(kind,"A"));assertEquals(DetectionStage.NOT_DETECTED,engine.perceive(c,emptyList()).detection)}
    }
}
''')

    # Universality scan includes new core files.
    rel='app/src/test/java/com/rpgos/app/Phase38VisibilityBoundaryTest.kt'
    text=p(rel).read_text(encoding='utf-8')
    text=text.replace('''"app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt","app/src/main/java/com/rpgos/app/VisualPromptBuilder.kt"''','''"app/src/main/java/com/rpgos/app/Phase38VisibilityConsumerInventory.kt","app/src/main/java/com/rpgos/app/Phase38TrustedAuthority.kt","app/src/main/java/com/rpgos/app/Phase38AccessAuthority.kt","app/src/main/java/com/rpgos/app/Phase38PerceptionDisclosure.kt","app/src/main/java/com/rpgos/app/VisualPromptBuilder.kt"''')
    p(rel).write_text(text,encoding='utf-8')

if __name__=='__main__':
    if len(sys.argv)!=2 or sys.argv[1] not in {'hardening','c','d'}: raise SystemExit('usage: phase38_integrated_patch.py hardening|c|d')
    {'hardening':hardening,'c':slice_c,'d':slice_d}[sys.argv[1]]()
    print('applied',sys.argv[1])
