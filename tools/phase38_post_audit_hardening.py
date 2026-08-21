from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def p(path): return ROOT / path

def text(path): return p(path).read_text()

def write(path, content): p(path).write_text(content)

def rep(path, old, new):
    s = text(path)
    if old not in s:
        raise SystemExit(f"anchor missing in {path}: {old[:120]!r}")
    if s.count(old) != 1:
        raise SystemExit(f"anchor not unique in {path}: count={s.count(old)} {old[:120]!r}")
    write(path, s.replace(old, new, 1))

# -----------------------------------------------------------------------------
# P38-ABCD-AUD-003: one normal protected-read chain, with explicit access mode.
# P38-ABCD-AUD-002: canonical cognition mapping comes from Slice-C authority.
# -----------------------------------------------------------------------------
protected_read = r'''package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
internal annotation class TrustedProtectedReadGateway

sealed interface ProtectedReadResult<out T> {
    val stateUid: String
    data class Allow<T>(val value:T, val disclosure:DisclosureLevel, val reasonCode:String):ProtectedReadResult<T>{override val stateUid="ALLOW"}
    data class Deny(val reasonCode:String):ProtectedReadResult<Nothing>{override val stateUid="DENY"}
    data object NoData:ProtectedReadResult<Nothing>{override val stateUid="NO_DATA"}
    data class NotDisclosed(val reasonCode:String):ProtectedReadResult<Nothing>{override val stateUid="NOT_DISCLOSED"}
    data class Unknown(val reasonCode:String):ProtectedReadResult<Nothing>{override val stateUid="UNKNOWN"}
    data class Corruption(val reasonCode:String,val error:Throwable):ProtectedReadResult<Nothing>{override val stateUid="CORRUPTION"}
}

enum class ProtectedSubjectAccessMode { PUBLIC_OPEN, CONTROL_AUTHORITY, COGNITION_AUTHORITY, PRIVILEGED_AUTHORITY, POLICY_AUTHORITY, UNKNOWN }

object ProtectedSubjectAccessRegistry {
    private val publicKinds = setOf(
        VisibilitySubjectKinds.PUBLIC_WORLD_EVENT, VisibilitySubjectKinds.PUBLIC_WORLD_ACTOR_PROFILE,
        VisibilitySubjectKinds.PUBLIC_WAR_SUMMARY, VisibilitySubjectKinds.WORLD_PRESENTATION
    )
    private val privilegedKinds = setOf(
        VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL, VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_MEMORY,
        VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_BELIEF, VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_SCHEDULE,
        VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_DECISION, VisibilitySubjectKinds.CAMPAIGN_TRUTH,
        VisibilitySubjectKinds.CANON_DIVERGENCE, VisibilitySubjectKinds.HIDDEN_PRESSURE,
        VisibilitySubjectKinds.DIAGNOSTIC_CONTEXT
    )
    private val policyKinds = setOf(
        VisibilitySubjectKinds.RELATIONSHIP_DATA, VisibilitySubjectKinds.ECONOMY_DATA,
        VisibilitySubjectKinds.POLITICS_DATA, VisibilitySubjectKinds.ORGANIZATION_DATA
    )
    fun modeFor(subjectKindUid:String):ProtectedSubjectAccessMode = when(subjectKindUid){
        in publicKinds -> ProtectedSubjectAccessMode.PUBLIC_OPEN
        VisibilitySubjectKinds.PLAYER_STATE -> ProtectedSubjectAccessMode.CONTROL_AUTHORITY
        VisibilitySubjectKinds.PHASE37_HOLDER_KNOWLEDGE -> ProtectedSubjectAccessMode.COGNITION_AUTHORITY
        in privilegedKinds -> ProtectedSubjectAccessMode.PRIVILEGED_AUTHORITY
        in policyKinds -> ProtectedSubjectAccessMode.POLICY_AUTHORITY
        else -> ProtectedSubjectAccessMode.UNKNOWN
    }
}

fun interface TrustedAccessResolver {
    fun effectiveAccess(request:VisibilityRequest,trusted:TrustedPrincipalContext,requirement:AccessRequirement):EffectiveAccessDecision
}

class ProtectedReadGateway(
    private val visibility:VisibilityAuthorityService,
    private val principalResolver:TrustedPrincipalResolver,
    private val accessResolver:TrustedAccessResolver?=null
){
    fun <T> read(request:VisibilityRequest,requirement:AccessRequirement?=null,read:()->T):ProtectedReadResult<T>{
        val mode=ProtectedSubjectAccessRegistry.modeFor(request.subject.subjectKindUid)
        if(mode==ProtectedSubjectAccessMode.UNKNOWN)return ProtectedReadResult.Unknown("UNKNOWN_ACCESS_POLICY")
        val trusted=principalResolver.resolve(request.audience)
        var effectiveAccess:EffectiveAccessDecision?=null
        if(mode==ProtectedSubjectAccessMode.POLICY_AUTHORITY){
            val policy=requirement?:return ProtectedReadResult.Unknown("ACCESS_POLICY_REQUIRED")
            val principal=trusted?:return ProtectedReadResult.Deny("TRUSTED_PRINCIPAL_REQUIRED")
            val resolver=accessResolver?:return ProtectedReadResult.Deny("ACCESS_AUTHORITY_UNAVAILABLE")
            effectiveAccess=try{resolver.effectiveAccess(request,principal,policy)}catch(failure:Throwable){return ProtectedReadResult.Corruption("ACCESS_AUTHORITY_CORRUPTION",failure)}
            if(!effectiveAccess.accessible)return ProtectedReadResult.Deny("ACCESS_AUTHORITY_DENIED:${effectiveAccess.reasonCode}")
        } else if(requirement!=null) return ProtectedReadResult.Unknown("ACCESS_POLICY_NOT_APPLICABLE")
        return try{
            val projection=visibility.project(request,trusted,effectiveAccess,read)
            when(projection.dataState){
                ProjectionDataState.DISCLOSED->ProtectedReadResult.Allow(projection.value as T,projection.decision.level,projection.decision.reasonCode)
                ProjectionDataState.NO_DATA->ProtectedReadResult.NoData
                ProjectionDataState.DENIED->ProtectedReadResult.Deny(projection.decision.reasonCode)
                ProjectionDataState.NOT_DISCLOSED->ProtectedReadResult.NotDisclosed(projection.decision.reasonCode)
                ProjectionDataState.UNKNOWN->ProtectedReadResult.Unknown(projection.decision.reasonCode)
                ProjectionDataState.CORRUPTION->ProtectedReadResult.Corruption(projection.decision.reasonCode,IllegalStateException(projection.decision.reasonCode))
            }
        }catch(failure:VisibilityAuthorityFailure){
            if(failure is VisibilityAuthorityFailure.CrossCampaign)throw failure
            ProtectedReadResult.Corruption("AUTHORITY_FAILURE",failure)
        }catch(failure:Throwable){ProtectedReadResult.Corruption("PROTECTED_READ_FAILURE",failure)}
    }
}

internal fun <T> ProtectedReadResult<T>.toVisibilityProjection(request:VisibilityRequest):VisibilityProjection<T> = when(this){
    is ProtectedReadResult.Allow -> VisibilityProjection(request,VisibilityDecision(disclosure,reasonCode),value,
        if(value is Collection<*> && value.isEmpty()) ProjectionDataState.NO_DATA else ProjectionDataState.DISCLOSED)
    is ProtectedReadResult.Deny -> VisibilityProjection(request,VisibilityDecision(DisclosureLevel.DENY,reasonCode),null,ProjectionDataState.DENIED)
    is ProtectedReadResult.NoData -> VisibilityProjection(request,VisibilityDecision(DisclosureLevel.DISCLOSE_FULL,"NO_DATA"),null,ProjectionDataState.NO_DATA)
    is ProtectedReadResult.NotDisclosed -> VisibilityProjection(request,VisibilityDecision(DisclosureLevel.DENY,reasonCode),null,ProjectionDataState.NOT_DISCLOSED)
    is ProtectedReadResult.Unknown -> VisibilityProjection(request,VisibilityDecision(DisclosureLevel.DENY,reasonCode),null,ProjectionDataState.UNKNOWN)
    is ProtectedReadResult.Corruption -> VisibilityProjection(request,VisibilityDecision(DisclosureLevel.DENY,reasonCode),null,ProjectionDataState.CORRUPTION)
}

@TrustedProtectedReadGateway
class ProtectedCampaignReadRepository private constructor(
    private val saveDbProvider:()->SQLiteDatabase,
    private val campaignUid:String,
    private val activePlayer:()->ActivePlayerRef?,
    private val closeProviderDb:Boolean,
    private val principalResolverOverride:TrustedPrincipalResolver?=null
){
    private val visibility=VisibilityAuthorityService()

    private inline fun <T> withSaveDb(block:(SQLiteDatabase)->T):T{
        val db=saveDbProvider()
        return if(closeProviderDb)db.use(block) else block(db)
    }

    private fun resolver(db:SQLiteDatabase)=TrustedPrincipalResolver{audience->
        principalResolverOverride?.resolve(audience) ?: run {
            val base=if(Phase38AccessAuthoritySchema.isReady(db)){
                UniversalAccessAuthority(AccessAuthorityStore(db,campaignUid)).trustedContext(audience)
            }else Phase38RuntimeAuthority.application(audience)
            base?.let{trusted->
                val active=activePlayer()
                val activeControl=if(audience.audienceKindUid==AudienceKinds.PLAYER&&active?.campaignId==campaignUid)setOf(active.playerUid)else emptySet()
                TrustedPrincipalContext(
                    trusted.campaignUid,trusted.principal,trusted.audienceKindUid,
                    trusted.controlledSubjectUids+activeControl,trusted.roleUids,trusted.organizationUids,
                    trusted.clearanceUids,trusted.cognitionHolders,trusted.privilegedCapability,trusted.resolverVersionUid
                )
            }
        }
    }

    private fun gateway(db:SQLiteDatabase):ProtectedReadGateway{
        val access=TrustedAccessResolver{request,trusted,requirement->
            if(!Phase38AccessAuthoritySchema.isReady(db))EffectiveAccessDecision(false,"ACCESS_SCHEMA_NOT_READY")
            else{
                val authority=UniversalAccessAuthority(AccessAuthorityStore(db,campaignUid))
                authority.effectiveAccess(authority.authorize(trusted,requirement))
            }
        }
        return ProtectedReadGateway(visibility,resolver(db),access)
    }

    internal fun trustedPrincipal(audience:AudienceContext):TrustedPrincipalContext?=withSaveDb{db->resolver(db).resolve(audience)}

    fun playerState(audience:AudienceContext,purpose:PurposeContext,playerUid:String):ProtectedReadResult<PlayerStateSnapshot> = withSaveDb{db->
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.PLAYER_STATE,playerUid))
        gateway(db).read(request){PlayerStateStore(db,campaignUid).load()?.takeIf{it.activePlayer.playerUid==playerUid}}
    }

    fun truth(audience:AudienceContext,purpose:PurposeContext,limit:Int=100):ProtectedReadResult<List<CampaignTruthRecord>> =
        truthFiltered(audience,purpose,null,null,null,limit)

    fun truthFiltered(audience:AudienceContext,purpose:PurposeContext,kind:TruthKind?=null,subjectUid:String?=null,perspectiveUid:String?=null,limit:Int=100):ProtectedReadResult<List<CampaignTruthRecord>> = withSaveDb{db->
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"CAMPAIGN_TRUTH_RECORDS"))
        gateway(db).read(request){CampaignTruthStore(db,campaignUid).active(kind,subjectUid,perspectiveUid,limit)}
    }

    fun truthContextRows(audience:AudienceContext,purpose:PurposeContext,limit:Int=200):ProtectedReadResult<List<Map<String,Any?>>> = withSaveDb{db->
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"CAMPAIGN_TRUTH_CONTEXT"))
        gateway(db).read(request){CampaignTruthStore(db,campaignUid).activeForContext(limit)}
    }

    fun canonDivergences(audience:AudienceContext,purpose:PurposeContext):ProtectedReadResult<List<CanonDivergenceRecord>> = withSaveDb{db->
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.CANON_DIVERGENCE,"CANON_DIVERGENCES"))
        gateway(db).read(request){CanonDivergenceStore(db,campaignUid).list()}
    }

    internal fun <T> diagnosticRows(audience:AudienceContext,purpose:PurposeContext,subjectUid:String,read:()->List<T>):ProtectedReadResult<List<T>> = withSaveDb{db->
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.DIAGNOSTIC_CONTEXT,subjectUid))
        gateway(db).read(request,read=read)
    }

    internal fun <T> policyRows(
        audience:AudienceContext,purpose:PurposeContext,subjectKindUid:String,subjectUid:String,requirement:AccessRequirement,read:()->List<T>
    ):ProtectedReadResult<List<T>> = withSaveDb{db->
        val request=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignUid,subjectKindUid,subjectUid))
        gateway(db).read(request,requirement,read)
    }

    companion object{
        fun borrowed(saveDb:SQLiteDatabase,campaignUid:String,activePlayer:()->ActivePlayerRef?)=
            ProtectedCampaignReadRepository({saveDb},campaignUid,activePlayer,false)
        internal fun borrowedTrusted(saveDb:SQLiteDatabase,campaignUid:String,activePlayer:()->ActivePlayerRef?,trusted:TrustedPrincipalContext):ProtectedCampaignReadRepository{
            val exact=TrustedPrincipalResolver{audience->if(audience.campaignUid==trusted.campaignUid&&audience.principal==trusted.principal&&audience.audienceKindUid==trusted.audienceKindUid)trusted else null}
            return ProtectedCampaignReadRepository({saveDb},campaignUid,activePlayer,false,exact)
        }
        internal fun owned(saveDbProvider:()->SQLiteDatabase,campaignUid:String,activePlayer:()->ActivePlayerRef?)=
            ProtectedCampaignReadRepository(saveDbProvider,campaignUid,activePlayer,true)
    }
}
'''
write("app/src/main/java/com/rpgos/app/Phase38ProtectedRead.kt", protected_read)

# Visibility: add diagnostic context, explicit access-authority decision, typed corruption mapping.
rep("app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '    const val WORLD_PRESENTATION = "WORLD_PRESENTATION"\n',
    '    const val WORLD_PRESENTATION = "WORLD_PRESENTATION"\n    const val DIAGNOSTIC_CONTEXT = "DIAGNOSTIC_CONTEXT"\n')
rep("app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '            require(dataState in setOf(ProjectionDataState.DENIED, ProjectionDataState.NOT_DISCLOSED, ProjectionDataState.UNKNOWN))\n',
    '            require(dataState in setOf(ProjectionDataState.DENIED, ProjectionDataState.NOT_DISCLOSED, ProjectionDataState.UNKNOWN, ProjectionDataState.CORRUPTION))\n')
rep("app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '        VisibilitySubjectKinds.RELATIONSHIP_DATA, VisibilitySubjectKinds.ECONOMY_DATA,\n        VisibilitySubjectKinds.POLITICS_DATA, VisibilitySubjectKinds.ORGANIZATION_DATA,\n        VisibilitySubjectKinds.CAMPAIGN_TRUTH, VisibilitySubjectKinds.CANON_DIVERGENCE,\n        VisibilitySubjectKinds.HIDDEN_PRESSURE, VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL\n    )\n',
    '        VisibilitySubjectKinds.CAMPAIGN_TRUTH, VisibilitySubjectKinds.CANON_DIVERGENCE,\n        VisibilitySubjectKinds.HIDDEN_PRESSURE, VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL, VisibilitySubjectKinds.DIAGNOSTIC_CONTEXT\n    )\n    private val accessControlledKinds = setOf(\n        VisibilitySubjectKinds.RELATIONSHIP_DATA, VisibilitySubjectKinds.ECONOMY_DATA,\n        VisibilitySubjectKinds.POLITICS_DATA, VisibilitySubjectKinds.ORGANIZATION_DATA\n    )\n')
rep("app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '    fun decide(request: VisibilityRequest, trusted: TrustedPrincipalContext?): VisibilityDecision {\n',
    '    fun decide(request: VisibilityRequest, trusted: TrustedPrincipalContext?): VisibilityDecision = decide(request, trusted, null)\n\n    fun decide(request: VisibilityRequest, trusted: TrustedPrincipalContext?, effectiveAccess: EffectiveAccessDecision?): VisibilityDecision {\n')
rep("app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '        if (s in protectedKinds) return deny("PROTECTED_SUBJECT")\n',
    '        if (s in accessControlledKinds) return if (effectiveAccess?.accessible == true) full("TRUSTED_ACCESS_AUTHORITY") else deny("ACCESS_AUTHORITY_REQUIRED")\n        if (s in protectedKinds) return deny("PROTECTED_SUBJECT")\n')
rep("app/src/main/java/com/rpgos/app/Phase38Visibility.kt",
    '    fun <T> project(request: VisibilityRequest, trusted: TrustedPrincipalContext?, read: () -> T): VisibilityProjection<T> {\n        val decision = decide(request, trusted)\n',
    '    fun <T> project(request: VisibilityRequest, trusted: TrustedPrincipalContext?, read: () -> T): VisibilityProjection<T> = project(request, trusted, null, read)\n\n    fun <T> project(request: VisibilityRequest, trusted: TrustedPrincipalContext?, effectiveAccess: EffectiveAccessDecision?, read: () -> T): VisibilityProjection<T> {\n        val decision = decide(request, trusted, effectiveAccess)\n')

# -----------------------------------------------------------------------------
# P38-ABCD-AUD-001/002: ContextBuilder consumes trusted gateway state only.
# -----------------------------------------------------------------------------
rep("app/src/main/java/com/rpgos/app/ContextBuilder.kt",
'''        val trustedPrincipal = Phase38RuntimeAuthority.application(
            audience,
            controlledSubjectUids = if (audience.audienceKindUid == AudienceKinds.PLAYER && playerUid != null) setOf(playerUid) else emptySet(),
            cognitionResolver = TrustedCognitionResolver { campaign, principal ->
                if (purpose.purposeUid == VisibilityPurposeKinds.WORLD_ACTOR_REASONING && principal.uid.isNotBlank())
                    setOf(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, principal.uid, campaign)) else emptySet()
            }
        )
''',
'''        val trustedPrincipal = protectedReads.trustedPrincipal(audience)
''')
rep("app/src/main/java/com/rpgos/app/ContextBuilder.kt",
'''        val playerFacing = audience.audienceKindUid == AudienceKinds.PLAYER
        val diagnostic = audience.audienceKindUid == AudienceKinds.DEVELOPER_DIAGNOSTIC && purpose.purposeUid == VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION
        val threads = if (diagnostic) queryMany(saveDb,"SELECT thread_uid,title,thread_type,status,priority,last_advanced_chapter,description FROM story_threads WHERE status='active' ORDER BY priority DESC,last_advanced_chapter DESC LIMIT 20") else emptyList()
''',
'''        val playerFacing = audience.audienceKindUid == AudienceKinds.PLAYER
        val trustedDiagnostic = purpose.purposeUid == VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION &&
            trustedPrincipal?.isPrivileged(Phase38RuntimeAuthority.PRIV_DIAGNOSTIC) == true
        fun diagnosticRows(uid:String, read:()->List<Map<String,Any?>>):List<Map<String,Any?>> =
            if(!trustedDiagnostic) emptyList() else when(val result=protectedReads.diagnosticRows(audience,purpose,uid,read)){
                is ProtectedReadResult.Allow -> result.value
                else -> emptyList()
            }
        val threads = diagnosticRows("STORY_THREADS") { queryMany(saveDb,"SELECT thread_uid,title,thread_type,status,priority,last_advanced_chapter,description FROM story_threads WHERE status='active' ORDER BY priority DESC,last_advanced_chapter DESC LIMIT 20") }
''')
rep("app/src/main/java/com/rpgos/app/ContextBuilder.kt",
'''        val chronicle = if(diagnostic) queryMany(saveDb,"SELECT chapter,title,active_threads_json,decisions_json,consequences_json,quests_json,continuity_warnings_json FROM chapter_manifests_v2 ORDER BY chapter DESC LIMIT 10") else queryMany(saveDb,"SELECT chapter,title FROM chapter_manifests_v2 ORDER BY chapter DESC LIMIT 10")
        val longTermMemory = if(diagnostic) protectedPrivateRows(audience,purpose,VisibilitySubjectKinds.WORLD_ACTOR_PRIVATE_MEMORY,"ALL_MEMORIES") {
            queryMany(saveDb,"SELECT memory_uid,entity_uid,memory_type,subject_uid,chapter,day,importance,emotional_valence,accuracy,summary FROM npc_memories_v2 WHERE active=1 ORDER BY importance DESC,chapter DESC LIMIT 30")
        } else emptyList()
''',
'''        val chronicle = if(trustedDiagnostic) diagnosticRows("CHRONICLE_FULL") { queryMany(saveDb,"SELECT chapter,title,active_threads_json,decisions_json,consequences_json,quests_json,continuity_warnings_json FROM chapter_manifests_v2 ORDER BY chapter DESC LIMIT 10") } else queryMany(saveDb,"SELECT chapter,title FROM chapter_manifests_v2 ORDER BY chapter DESC LIMIT 10")
        val longTermMemory = diagnosticRows("ALL_MEMORIES") {
            queryMany(saveDb,"SELECT memory_uid,entity_uid,memory_type,subject_uid,chapter,day,importance,emotional_valence,accuracy,summary FROM npc_memories_v2 WHERE active=1 ORDER BY importance DESC,chapter DESC LIMIT 30")
        }
''')
rep("app/src/main/java/com/rpgos/app/ContextBuilder.kt",
    '        val constraints=if(diagnostic)queryMany(worldDb,"SELECT constraint_uid,subject_type,subject_uid,constraint_key,constraint_value,canon_scope,notes FROM canon_constraints_v2 WHERE status=\'active\' OR status IS NULL LIMIT 40")else emptyList()\n',
    '        val constraints=diagnosticRows("CANON_CONSTRAINTS") { queryMany(worldDb,"SELECT constraint_uid,subject_type,subject_uid,constraint_key,constraint_value,canon_scope,notes FROM canon_constraints_v2 WHERE status=\'active\' OR status IS NULL LIMIT 40") }\n')
rep("app/src/main/java/com/rpgos/app/ContextBuilder.kt",
    '        val canonDivergences = if(diagnostic) CanonDivergenceStore(saveDb,campaignRef.campaignId).list() else emptyList()\n',
    '''        val canonDivergences = if(trustedDiagnostic) when(val result=protectedReads.canonDivergences(audience,purpose)){
            is ProtectedReadResult.Allow -> result.value
            else -> emptyList()
        } else emptyList()
''')
# Remove obsolete untrusted helper entirely.
s = text("app/src/main/java/com/rpgos/app/ContextBuilder.kt")
start = s.find("    private fun protectedPrivateRows(")
end = s.find("    private fun emptyDeniedBundle", start)
if start < 0 or end < 0: raise SystemExit("protectedPrivateRows block missing")
write("app/src/main/java/com/rpgos/app/ContextBuilder.kt", s[:start] + s[end:])

# -----------------------------------------------------------------------------
# P38-ABCD-AUD-008 + production trusted diagnostic path.
# -----------------------------------------------------------------------------
rep("app/src/main/java/com/rpgos/app/CharacterPanel.kt",
'''    fun load(audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot {
        val uid = playerUid?.trim().orEmpty()
        if (uid.isBlank()) return CharacterPanelSnapshot.unresolved()
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(audience.campaignUid, VisibilitySubjectKinds.PLAYER_STATE, uid))
        return visibility.project(request) { loadProjectedPlayerState(uid, audience, purpose) }.value ?: CharacterPanelSnapshot.unresolved()
    }
''',
'''    @Deprecated("PLAYER_STATE authorization must come from ProtectedCampaignReadRepository")
    fun load(audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot = CharacterPanelSnapshot.unresolved()

    fun load(audience: AudienceContext, purpose: PurposeContext, playerStateRead: ProtectedReadResult<PlayerStateSnapshot>): CharacterPanelSnapshot {
        val uid = playerUid?.trim().orEmpty()
        if (uid.isBlank() || playerStateRead !is ProtectedReadResult.Allow) return CharacterPanelSnapshot.unresolved()
        if (playerStateRead.value.activePlayer.playerUid != uid || playerStateRead.value.activePlayer.campaignId != audience.campaignUid) return CharacterPanelSnapshot.unresolved()
        return loadProjectedPlayerState(uid, audience, purpose)
    }
''')
rep("app/src/main/java/com/rpgos/app/LocalGameStore.kt",
'''        openGameplaySaveDb().use { save ->
            openWorldDb().use { world ->
                return ContextBuilder(save, world).build(playerInput, chapter, audience, purpose)
            }
        }
''',
'''        openGameplaySaveDb().use { save ->
            openWorldDb().use { world ->
                val reads=ProtectedCampaignReadRepository.borrowed(save,campaignId){ActivePlayerStore(save,campaignId).active()}
                return ContextBuilder(save, world, protectedReadsOverride=reads).build(playerInput, chapter, audience, purpose)
            }
        }
''')
# Insert internal trusted production path immediately after buildContext.
rep("app/src/main/java/com/rpgos/app/LocalGameStore.kt",
'''    internal fun activeCampaignId(): String = selection.activeCampaignRef().campaignId
''',
'''    internal fun buildTrustedContext(playerInput:String,chapter:Int,audience:AudienceContext,purpose:PurposeContext,trusted:TrustedPrincipalContext):ContextBundle {
        val campaignId=selection.activeCampaignRef().campaignId
        if(audience.campaignUid!=campaignId||purpose.campaignUid!=campaignId||trusted.campaignUid!=campaignId)throw VisibilityAuthorityFailure.CrossCampaign()
        openGameplaySaveDb().use{save->openWorldDb().use{world->
            val reads=ProtectedCampaignReadRepository.borrowedTrusted(save,campaignId,{ActivePlayerStore(save,campaignId).active()},trusted)
            return ContextBuilder(save,world,protectedReadsOverride=reads).build(playerInput,chapter,audience,purpose)
        }}
    }

    internal fun activeCampaignId(): String = selection.activeCampaignRef().campaignId
''')
rep("app/src/main/java/com/rpgos/app/LocalGameStore.kt",
'''    fun fullCharacterPanel(audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot { val campaign=activeCampaignId();if(audience.campaignUid!=campaign||purpose.campaignUid!=campaign)throw VisibilityAuthorityFailure.CrossCampaign();openGameplaySaveDb().use { db -> val playerUid = ActivePlayerStore(db, campaign).active()?.playerUid; return CharacterPanelReader(db, playerUid).load(audience,purpose) } }
''',
'''    fun fullCharacterPanel(audience: AudienceContext, purpose: PurposeContext): CharacterPanelSnapshot { val campaign=activeCampaignId();if(audience.campaignUid!=campaign||purpose.campaignUid!=campaign)throw VisibilityAuthorityFailure.CrossCampaign();openGameplaySaveDb().use { db -> val active=ActivePlayerStore(db,campaign).active();val playerUid=active?.playerUid?:return CharacterPanelSnapshot.unresolved();val reads=ProtectedCampaignReadRepository.borrowed(db,campaign){active};val authorized=reads.playerState(audience,purpose,playerUid);return CharacterPanelReader(db,playerUid).load(audience,purpose,authorized) } }
''')

# Unified repository protected APIs use the same gateway; trusted build remains internal.
rep("app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt",
'''    override fun buildContext(playerInput: String, chapter: Int, audience: AudienceContext, purpose: PurposeContext): ContextBundle =
        store.buildContext(playerInput, chapter, audience, purpose)
''',
'''    override fun buildContext(playerInput: String, chapter: Int, audience: AudienceContext, purpose: PurposeContext): ContextBundle =
        store.buildContext(playerInput, chapter, audience, purpose)
    internal fun infrastructureBuildTrustedContext(playerInput:String,chapter:Int,audience:AudienceContext,purpose:PurposeContext,trusted:TrustedPrincipalContext):ContextBundle =
        store.buildTrustedContext(playerInput,chapter,audience,purpose,trusted)
''')
old_truth='''        val campaign = activeCampaignRef().campaignId
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(campaign, VisibilitySubjectKinds.CAMPAIGN_TRUTH, "CAMPAIGN_TRUTH_RECORDS"))
        return visibility.projectList(request) {
            openGameplaySaveDb().use { db -> CampaignTruthStore(db, campaign).active(kind, subjectUid, perspectiveUid, limit) }
        }
'''
new_truth='''        val campaign = activeCampaignRef().campaignId
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(campaign, VisibilitySubjectKinds.CAMPAIGN_TRUTH, "CAMPAIGN_TRUTH_RECORDS"))
        return protectedReads().truthFiltered(audience,purpose,kind,subjectUid,perspectiveUid,limit).toVisibilityProjection(request)
'''
rep("app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt",old_truth,new_truth)
rep("app/src/main/java/com/rpgos/app/UnifiedGameRepository.kt",
'''        val campaign = activeCampaignRef().campaignId
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(campaign, VisibilitySubjectKinds.CANON_DIVERGENCE, "CANON_DIVERGENCES"))
        return visibility.projectList(request) { store.canonDivergences() }
''',
'''        val campaign = activeCampaignRef().campaignId
        val request = VisibilityRequest(audience, purpose, VisibilitySubjectRef(campaign, VisibilitySubjectKinds.CANON_DIVERGENCE, "CANON_DIVERGENCES"))
        return protectedReads().canonDivergences(audience,purpose).toVisibilityProjection(request)
''')

# -----------------------------------------------------------------------------
# P38-ABCD-AUD-004: codec + canonical applier share the semantic validator.
# -----------------------------------------------------------------------------
rep("app/src/main/java/com/rpgos/app/PlayerChangeSetCodec.kt",
'''        validate = { buildList { if(it.recordUid.isBlank()||it.principalKindUid.isBlank()||it.principalUid.isBlank()||it.bindingOrGrantKindUid.isBlank()||it.valueUid.isBlank())add("INVALID_ACCESS_AUTHORITY_CHANGE");if(it.validUntilOrder!=null&&it.validUntilOrder<it.validFromOrder)add("INVALID_ACCESS_AUTHORITY_CHANGE") } },
''',
'''        validate = { AccessAuthorityChangeValidator.errors(it) },
''')
rep("app/src/main/java/com/rpgos/app/TurnTransaction.kt",
'''                is EquipmentChange,is FinancialChange,is OwnershipChange,is CampaignTruthChange,
                is DevelopmentProjectChange,is KnowledgeAcquisitionChange,is AccessAuthorityChange -> Unit
''',
'''                is EquipmentChange,is FinancialChange,is OwnershipChange,is CampaignTruthChange,
                is DevelopmentProjectChange,is KnowledgeAcquisitionChange -> Unit
                is AccessAuthorityChange -> AccessAuthorityChangeValidator.requireValid(change.payload)
''')

# -----------------------------------------------------------------------------
# P38-ABCD-AUD-006: every reduction below FULL physically transforms/removes.
# -----------------------------------------------------------------------------
rep("app/src/main/java/com/rpgos/app/ContextModels.kt",
'''        return source.copy(visibilityEnvelope=env, contextMeta=source.contextMeta + ("disclosure_reduced" to true))
''',
'''        if (level.rank <= DisclosureLevel.DETAILED.rank) return source.copy(
            activeThreads=source.activeThreads.map { it.filterKeys { key -> key in setOf("thread_uid","title","status") } },
            npcKnowledge=source.npcKnowledge.map { it.filterKeys { key -> key in setOf("subject_uid","predicate","epistemic_state") } },
            canonConstraints=emptyList(),retrievedLongTermMemory=emptyList(),npcMemories=emptyList(),campaignTruth=emptyList(),canonDivergences=emptyList(),playerState=emptyMap(),
            recentChronicle=source.recentChronicle.map { it.filterKeys { key -> key in setOf("chapter","title") } },
            contextMeta=source.contextMeta.filterKeys { it !in setOf("campaign_truth_state","player_state_state") } + ("disclosure_reduced" to true),visibilityEnvelope=env
        )
        return source.copy(visibilityEnvelope=env, contextMeta=source.contextMeta + ("disclosure_reduced" to true))
''')

# -----------------------------------------------------------------------------
# P38-ABCD-AUD-005: perception inputs become runtime-issued opaque inputs.
# -----------------------------------------------------------------------------
perception_path="app/src/main/java/com/rpgos/app/Phase38PerceptionDisclosure.kt"
s=text(perception_path)
s=s.replace("data class PerceptionSignal(\n", "class PerceptionSignal private constructor(\n",1)
# Insert factory just before signal class closes: anchor on metadata + wellformed block.
old='''    internal fun isWellFormed(): Boolean =
        ref.campaignUid == campaignUid && signalKindUid.isNotBlank() && quality.isFinite() && quality in 0.0..1.0 &&
            evidence.keys.none { it.isBlank() } && worldMetadata.keys.none { it.isBlank() }
}
'''
new='''    internal fun isWellFormed(): Boolean =
        ref.campaignUid == campaignUid && signalKindUid.isNotBlank() && quality.isFinite() && quality in 0.0..1.0 &&
            evidence.keys.none { it.isBlank() } && worldMetadata.keys.none { it.isBlank() }
    companion object { internal fun issue(campaignUid:String,ref:PerceptionSignalRef,signalKindUid:String,quality:Double,evidence:Map<String,Any?>,uncertainty:PerceptionUncertainty?,presentedSubject:VisibilitySubjectRef?,worldMetadata:Map<String,Any?>)=PerceptionSignal(campaignUid,ref,signalKindUid,quality,evidence,uncertainty,presentedSubject,worldMetadata) }
}
'''
if old not in s: raise SystemExit("PerceptionSignal end anchor missing")
s=s.replace(old,new,1)
s=s.replace("data class PerceptionCapability(\n", "class PerceptionCapability private constructor(\n",1)
old='''    internal fun isWellFormed(): Boolean =
        ref.campaignUid == campaignUid && channelUids.isNotEmpty() && channelUids.none { it.isBlank() } &&
            minimumSignalQuality.isFinite() && minimumSignalQuality in 0.0..1.0
}
'''
new='''    internal fun isWellFormed(): Boolean =
        ref.campaignUid == campaignUid && channelUids.isNotEmpty() && channelUids.none { it.isBlank() } &&
            minimumSignalQuality.isFinite() && minimumSignalQuality in 0.0..1.0
    companion object { internal fun issue(campaignUid:String,ref:PerceptionCapabilityRef,owner:VisibilityPrincipalRef,channelUids:Set<String>,minimumSignalQuality:Double,maximumDisclosure:DisclosureLevel)=PerceptionCapability(campaignUid,ref,owner,channelUids,minimumSignalQuality,maximumDisclosure) }
}

/** Runtime-owned issuer. Callers can request observation but cannot manufacture signal/capability authority. */
internal object Phase38PerceptionRuntimeAuthority {
    fun issueSignal(campaignUid:String,ref:PerceptionSignalRef,signalKindUid:String,quality:Double,evidence:Map<String,Any?>,uncertainty:PerceptionUncertainty?=null,presentedSubject:VisibilitySubjectRef?=null,worldMetadata:Map<String,Any?>=emptyMap()):PerceptionSignal =
        PerceptionSignal.issue(campaignUid,ref,signalKindUid,quality,evidence.toMap(),uncertainty,presentedSubject,worldMetadata.toMap())
    fun issueCapability(trusted:TrustedPrincipalContext,ref:PerceptionCapabilityRef,owner:VisibilityPrincipalRef,channelUids:Set<String>,minimumSignalQuality:Double,maximumDisclosure:DisclosureLevel):PerceptionCapability {
        require(ref.campaignUid==trusted.campaignUid&&owner==trusted.principal){"RPGOS-P38-PERCEPTION:CAPABILITY_AUTHORITY_MISMATCH"}
        return PerceptionCapability.issue(trusted.campaignUid,ref,owner,channelUids.toSet(),minimumSignalQuality,maximumDisclosure)
    }
}
'''
if old not in s: raise SystemExit("PerceptionCapability end anchor missing")
s=s.replace(old,new,1)
s=s.replace("data class PerceptionContext(\n", "data class PerceptionContext internal constructor(\n",1)
s=s.replace("class PerceptionResolver {\n", "class PerceptionResolver internal constructor() {\n",1)
# Add runtime gateway before PerceptionResolver.
anchor="class PerceptionResolver internal constructor() {\n"
gateway='''fun interface TrustedPerceptionSignalSource { fun signal(campaignUid:String,signalRef:PerceptionSignalRef):PerceptionSignal? }
fun interface TrustedPerceptionCapabilitySource { fun capabilities(campaignUid:String,principal:VisibilityPrincipalRef):List<PerceptionCapability> }

class PerceptionRuntimeGateway internal constructor(
    private val principalResolver:TrustedPrincipalResolver,
    private val signalSource:TrustedPerceptionSignalSource,
    private val capabilitySource:TrustedPerceptionCapabilitySource
){
    fun evaluate(audience:AudienceContext,signalRef:PerceptionSignalRef,rules:PerceptionWorldRules,interference:List<PerceptionInterference> = emptyList(),expertise:List<PerceptionExpertise> = emptyList()):PerceptionDecision{
        if(audience.campaignUid!=signalRef.campaignUid)throw VisibilityAuthorityFailure.CrossCampaign()
        val principal=requireNotNull(audience.principal){"RPGOS-P38-PERCEPTION:PRINCIPAL_REQUIRED"}
        val trusted=principalResolver.resolve(audience)?:return PerceptionDecision(audience.campaignUid,principal,signalRef,null,PerceptionResultState.DENIED,"TRUSTED_OBSERVER_REQUIRED")
        val signal=signalSource.signal(audience.campaignUid,signalRef)?:return PerceptionDecision(audience.campaignUid,principal,signalRef,null,PerceptionResultState.NO_DATA,"NO_SIGNAL")
        val capabilities=capabilitySource.capabilities(audience.campaignUid,principal)
        val context=PerceptionContext(audience.campaignUid,trusted,capabilities,rules,interference,expertise)
        return PerceptionResolver().evaluate(PerceptionRequest(context,signal))
    }
}

'''
if anchor not in s: raise SystemExit("PerceptionResolver anchor missing")
s=s.replace(anchor,gateway+anchor,1)
write(perception_path,s)

# Update Slice-D fixture helpers to use runtime-issued objects.
rep("app/src/test/java/com/rpgos/app/Phase38SliceDPerceptionDisclosureTest.kt",
'''    ) = PerceptionCapability(
        campaignUid, PerceptionCapabilityRef(campaignUid, "CAP-${owner.uid}-$channel"), owner,
        setOf(channel), minimum, ceiling
    )
''',
'''    ) = Phase38PerceptionRuntimeAuthority.issueCapability(
        fixture.trusted, PerceptionCapabilityRef(campaignUid, "CAP-${owner.uid}-$channel"), owner,
        setOf(channel), minimum, ceiling
    )
''')
rep("app/src/test/java/com/rpgos/app/Phase38SliceDPerceptionDisclosureTest.kt",
'''    ) = PerceptionSignal(
        campaignUid, PerceptionSignalRef(campaignUid, "SIG-1"), kind, quality, evidence, uncertainty,
        VisibilitySubjectRef(campaignUid, "OBSERVED_SUBJECT", presentedUid), mapOf("source" to "fixture")
    )
''',
'''    ) = Phase38PerceptionRuntimeAuthority.issueSignal(
        campaignUid, PerceptionSignalRef(campaignUid, "SIG-1"), kind, quality, evidence, uncertainty,
        VisibilitySubjectRef(campaignUid, "OBSERVED_SUBJECT", presentedUid), mapOf("source" to "fixture")
    )
''')
# Typed no-data regression has its own raw signal helper; replace generic constructor call.
s=text("app/src/test/java/com/rpgos/app/Phase38SliceDTypedNoDataRegressionTest.kt")
s=s.replace("PerceptionSignal(\n", "Phase38PerceptionRuntimeAuthority.issueSignal(\n")
write("app/src/test/java/com/rpgos/app/Phase38SliceDTypedNoDataRegressionTest.kt",s)

# -----------------------------------------------------------------------------
# P38-ABCD-AUD-007: prepare exact bytes before authorization; send same bytes.
# -----------------------------------------------------------------------------
rep("app/src/main/java/com/rpgos/app/ImageEditModels.kt",
'''data class ImageEditRequest(
''',
'''data class PreparedImageEditSource internal constructor(
    val sourceVisualUid:String,
    val bytes:ByteArray,
    val sha256:String
)

data class ImageEditRequest(
''')
# Replace edit client body with prepare/editPrepared while preserving external edit compatibility.
client=r'''package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ImageEditBackendClient(
    private val context: Context,
    private val baseUrl: String
) {
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    suspend fun prepareSource(sourceVisualUid:String,sourceUri:String):PreparedImageEditSource = withContext(Dispatchers.IO){
        require(sourceVisualUid.isNotBlank())
        val uri=android.net.Uri.parse(sourceUri)
        val bytes=context.contentResolver.openInputStream(uri).use{input->requireNotNull(input){"Nie można odczytać obrazu źródłowego."};input.readBytes()}
        PreparedImageEditSource(sourceVisualUid,bytes,Phase38VisualAuthorization.digestBytes(bytes))
    }

    suspend fun edit(reqData:ImageEditRequest):GeneratedImageResult = editPrepared(reqData,prepareSource(reqData.sourceVisualUid,reqData.sourceUri))

    suspend fun editPrepared(reqData:ImageEditRequest,prepared:PreparedImageEditSource):GeneratedImageResult = withContext(Dispatchers.IO){
        require(baseUrl.isNotBlank()&&!baseUrl.contains("YOUR-BACKEND")){"Backend nie jest skonfigurowany."}
        require(prepared.sourceVisualUid==reqData.sourceVisualUid){"RPGOS-VISIBILITY:VISUAL_SOURCE_SUBSTITUTION"}
        val sourceDigest=Phase38VisualAuthorization.digestBytes(prepared.bytes)
        require(sourceDigest==prepared.sha256){"RPGOS-VISIBILITY:VISUAL_SOURCE_DIGEST_MISMATCH"}
        reqData.authorization.requireRequest(VisualSemanticRequest(
            reqData.authorization.campaignUid,reqData.authorization.audienceKindUid,reqData.authorization.audienceUid,
            VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,reqData.authorization.subjectKindUid,reqData.authorization.subjectUid,
            reqData.authorization.requestUid,VisualRequestKinds.EDIT,reqData.instruction,
            relatedEntityUid=reqData.authorization.relatedEntityUid,sourceVisualUid=reqData.sourceVisualUid,sourceImageSha256=sourceDigest
        ))
        val multipart=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("title",reqData.title).addFormDataPart("instruction",reqData.instruction)
            .addFormDataPart("campaign_uid",reqData.authorization.campaignUid).addFormDataPart("source_visual_uid",reqData.sourceVisualUid)
            .addFormDataPart("visibility_envelope",reqData.authorization.toJson().toString())
            .addFormDataPart("image","source.png",prepared.bytes.toRequestBody("image/png".toMediaType())).build()
        val request=Request.Builder().url(baseUrl.trimEnd('/')+"/v1/images/edit").post(multipart).build()
        client.newCall(request).execute().use{resp->
            if(!resp.isSuccessful)error("Image edit backend HTTP ${resp.code}")
            val body=JSONObject(resp.body.string())
            GeneratedImageResult(body.getString("title"),body.optString("mime_type","image/png"),body.getString("base64_data"),body.optString("revised_prompt").takeIf{it.isNotBlank()})
        }
    }
}
'''
write("app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt",client)
rep("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt",
'''                val editEnvelope = VisibilityAuthorityService().envelope(
                    playerAudience(),
                    playerPurpose(VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION)
                )
                val editAuthorization = Phase38VisualAuthorization.authorize(
                    editEnvelope,
                    VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
                    "VISUAL",
                    source.visualUid,
                    instruction,
                    VisualInputOrigins.USER_STANDALONE
                )
                val result = ImageEditBackendClient(contextApp, _settings.value.backendUrl).edit(
                    ImageEditRequest(
                        sourceVisualUid = source.visualUid,
                        sourceUri = source.uri,
                        title = source.title + "_edit",
                        instruction = instruction,
                        authorization = editAuthorization
                    )
                )
''',
'''                val editClient = ImageEditBackendClient(contextApp, _settings.value.backendUrl)
                val prepared = editClient.prepareSource(source.visualUid, source.uri)
                val editEnvelope = VisibilityAuthorityService().envelope(
                    playerAudience(),
                    playerPurpose(VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION)
                )
                val editAuthorization = Phase38VisualAuthorization.authorize(
                    editEnvelope,
                    VisibilityPurposeKinds.IMAGE_EDIT_VISUALIZATION,
                    "VISUAL",
                    source.visualUid,
                    instruction,
                    VisualInputOrigins.CAMPAIGN_PROJECTION,
                    sourceVisualUid = source.visualUid,
                    sourceImageSha256 = prepared.sha256
                )
                val result = editClient.editPrepared(
                    ImageEditRequest(
                        sourceVisualUid = source.visualUid,
                        sourceUri = source.uri,
                        title = source.title + "_edit",
                        instruction = instruction,
                        authorization = editAuthorization
                    ), prepared
                )
''')

# -----------------------------------------------------------------------------
# Post-audit regression matrix. Robolectric used only where durable SQLite is needed.
# -----------------------------------------------------------------------------
test = r'''package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class Phase38PostAuditHardeningTest {
    private fun apply(db:SQLiteDatabase,campaign:String,order:Long,p:AccessAuthorityChange){
        if(!Phase38AccessAuthoritySchema.isReady(db))Phase38AccessAuthoritySchema.ensureReady(db)
        db.beginTransaction();try{AccessAuthorityStore(db,campaign).apply(TurnTransactionIdentity(campaign,"T$order","CMD$order","TX$order"),"CH$order:${p.recordUid}",p,order);db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    private fun player(campaign:String="C")=VisibilityAudienceFactory.player(campaign)
    private fun purpose(campaign:String="C")=PurposeContext(campaign,VisibilityPurposeKinds.PLAYER_UI)

    @Test fun accessMutationValidatorRejectsOperationKindAndBadDelegation(){
        assertTrue(runCatching{AccessAuthorityChange(AccessOperation.GRANT,"X","ENTITY","A",AccessBindingKind.ROLE.name,"ROLE",validFromOrder=1)}.isFailure)
        assertTrue(runCatching{AccessAuthorityChange(AccessOperation.BIND_COGNITION,"X","ENTITY","A",AccessBindingKind.ROLE.name,"H",subjectKindUid="ORG",subjectUid="H",validFromOrder=1)}.isFailure)
        assertTrue(runCatching{AccessAuthorityChange(AccessOperation.GRANT,"X","ENTITY","A",AccessGrantKind.DELEGATED.name,"POLICY",validFromOrder=1)}.isFailure)
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val delegated=AccessAuthorityChange(AccessOperation.GRANT,"DG","ENTITY","A",AccessGrantKind.DELEGATED.name,"POLICY",validFromOrder=1,delegatedByPrincipalUid="B")
        db.beginTransaction();try{assertTrue(runCatching{AccessAuthorityStore(db,"C").apply(TurnTransactionIdentity("C","T","CMD","TX"),"CH",delegated,1)}.isFailure)}finally{db.endTransaction();db.close()}
    }

    @Test fun canonicalCognitionSupportsZeroOneManyAndNeverInfersUidAsCharacter(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val orgAudience=AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ORGANIZATION","X"))
        var reads=ProtectedCampaignReadRepository.borrowed(db,"C"){null}
        assertTrue(reads.trustedPrincipal(orgAudience)?.cognitionHolders.orEmpty().isEmpty())
        apply(db,"C",1,AccessAuthorityChange(AccessOperation.BIND_COGNITION,"C1","ORGANIZATION","X",AccessBindingKind.COGNITION.name,"MAP",subjectKindUid="ORGANIZATION",subjectUid="H1",validFromOrder=1))
        apply(db,"C",2,AccessAuthorityChange(AccessOperation.BIND_COGNITION,"C2","ORGANIZATION","X",AccessBindingKind.COGNITION.name,"MAP",subjectKindUid="SHARED_COLLECTIVE",subjectUid="H2",validFromOrder=1))
        reads=ProtectedCampaignReadRepository.borrowed(db,"C"){null}
        val holders=reads.trustedPrincipal(orgAudience)!!.cognitionHolders
        assertEquals(setOf(KnowledgeHolderRef("ORGANIZATION","H1","C"),KnowledgeHolderRef("SHARED_COLLECTIVE","H2","C")),holders)
        assertFalse(holders.contains(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,"X","C")))
        val injected=AudienceContext("C",AudienceKinds.WORLD_ACTOR,VisibilityPrincipalRef("ENTITY","U"),listOf(KnowledgeHolderRef("FAKE","F","C")))
        assertTrue(reads.trustedPrincipal(injected)?.cognitionHolders.orEmpty().isEmpty())
        db.close()
    }

    @Test fun persistedRoleClearanceGrantRevocationExpiryAndCampaignIsolationFlowThroughGateway(){
        val db=SQLiteDatabase.create(null);Phase38AccessAuthoritySchema.ensureReady(db)
        val a=player();val reads=ProtectedCampaignReadRepository.borrowed(db,"C"){null}
        val roleReq=AccessRequirement("ROLE-POLICY",requiredRoleUids=setOf("R"))
        fun read(req:AccessRequirement,uid:String="S")=reads.policyRows(a,purpose(),VisibilitySubjectKinds.RELATIONSHIP_DATA,uid,req){listOf("SECRET")}
        assertTrue(read(roleReq) is ProtectedReadResult.Deny)
        apply(db,"C",1,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"R1",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.ROLE.name,"R",validFromOrder=1))
        assertTrue(read(roleReq) is ProtectedReadResult.Allow)
        val clearanceReq=AccessRequirement("CLR-POLICY",requiredClearanceUids=setOf("C2"))
        assertTrue(read(clearanceReq) is ProtectedReadResult.Deny)
        apply(db,"C",2,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"CL1",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.CLEARANCE.name,"C2",validFromOrder=2))
        assertTrue(read(clearanceReq) is ProtectedReadResult.Allow)
        val grantReq=AccessRequirement("SECRET-POLICY",explicitGrantRequired=true,carrier=InformationCarrierRef("C",VisibilitySubjectKinds.RELATIONSHIP_DATA,"S"))
        apply(db,"C",3,AccessAuthorityChange(AccessOperation.SET_CARRIER_ACCESS,"G1",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.EXPLICIT.name,"SECRET-POLICY",VisibilitySubjectKinds.RELATIONSHIP_DATA,"S",3))
        assertTrue(read(grantReq) is ProtectedReadResult.Allow)
        apply(db,"C",4,AccessAuthorityChange(AccessOperation.REVOKE_GRANT,"G2",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.EXPLICIT.name,"SECRET-POLICY",VisibilitySubjectKinds.RELATIONSHIP_DATA,"S",4))
        assertTrue(read(grantReq) is ProtectedReadResult.Deny)
        apply(db,"C",5,AccessAuthorityChange(AccessOperation.GRANT,"TMP",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessGrantKind.TEMPORARY.name,"TEMP-P",validFromOrder=5,validUntilOrder=5))
        apply(db,"C",6,AccessAuthorityChange(AccessOperation.UPSERT_BINDING,"ADV",AudienceKinds.PLAYER,"HUMAN_PLAYER",AccessBindingKind.ORGANIZATION.name,"ORG",validFromOrder=6))
        assertTrue(read(AccessRequirement("TEMP-P",explicitGrantRequired=true)) is ProtectedReadResult.Deny)
        assertTrue(read(AccessRequirement("UNRELATED",explicitGrantRequired=true)) is ProtectedReadResult.Deny)
        val cross=AccessRequirement("X",explicitGrantRequired=true,carrier=InformationCarrierRef("C2",VisibilitySubjectKinds.RELATIONSHIP_DATA,"S"))
        assertTrue(read(cross) is ProtectedReadResult.Deny)
        db.close()
    }

    @Test fun fullToDetailedReductionPhysicallyRemovesExactProtectedPayload(){
        val a=player();val env=VisibilityAuthorityService().envelope(a,purpose())
        val bundle=ContextBundle(emptyMap(),emptyMap(),emptyMap(),listOf(mapOf("thread_uid" to "T","title" to "safe","description" to "THREAD-SECRET")),emptyList(),listOf(mapOf("predicate" to "p","object" to "KNOWLEDGE-SECRET")),emptyList(),emptyList(),listOf(mapOf("secret" to "CONSTRAINT-SECRET")),listOf(mapOf("chapter" to 1,"title" to "safe","decisions_json" to "DECISION-SECRET")),listOf(mapOf("summary" to "MEMORY-SECRET")),campaignTruth=listOf(mapOf("exact" to "TRUTH-SECRET")),playerState=mapOf("runtime" to "STATE-SECRET"),visibilityEnvelope=env)
        val detailed=bundle.reduceDisclosureTo(DisclosureLevel.DETAILED)
        val dump=detailed.toString()
        listOf("THREAD-SECRET","KNOWLEDGE-SECRET","CONSTRAINT-SECRET","DECISION-SECRET","MEMORY-SECRET","TRUTH-SECRET","STATE-SECRET").forEach{assertFalse(dump.contains(it))}
        assertEquals(DisclosureLevel.DETAILED,detailed.visibilityEnvelope.maximumDisclosure)
    }

    @Test fun perceptionInputsRequireRuntimeIssuerAndGatewayRejectsUntrustedDescriptor(){
        assertTrue(java.lang.reflect.Modifier.isPrivate(PerceptionSignal::class.java.declaredConstructors.single().modifiers))
        assertTrue(java.lang.reflect.Modifier.isPrivate(PerceptionCapability::class.java.declaredConstructors.single().modifiers))
        val fixture=Phase38TrustedTestAuthority.playerCharacter("C","PC")
        val cap=Phase38PerceptionRuntimeAuthority.issueCapability(fixture.trusted,PerceptionCapabilityRef("C","CAP"),fixture.trusted.principal,setOf("CH"),0.1,DisclosureLevel.DISCLOSE_FULL)
        val sig=Phase38PerceptionRuntimeAuthority.issueSignal("C",PerceptionSignalRef("C","SIG"),"K",1.0,mapOf("presence" to true))
        val rules=PerceptionWorldRules("R",mapOf("K" to setOf("CH")),emptyMap(),emptyMap())
        val gateway=PerceptionRuntimeGateway(TrustedPrincipalResolver{aud->if(aud==fixture.audience)fixture.trusted else null},TrustedPerceptionSignalSource{_,_->sig},TrustedPerceptionCapabilitySource{_,_->listOf(cap)})
        assertEquals(PerceptionResultState.DETECTED,gateway.evaluate(fixture.audience,sig.ref,rules).state)
        val forged=AudienceContext("C",AudienceKinds.PLAYER_CHARACTER,VisibilityPrincipalRef("ENTITY","OTHER"))
        assertEquals(PerceptionResultState.DENIED,gateway.evaluate(forged,sig.ref,rules).state)
    }

    @Test fun productionIntegrationUsesTrustedContextGatewayPreparedEditAndNoDescriptorDiagnosticBypass(){
        val context=source("app/src/main/java/com/rpgos/app/ContextBuilder.kt")
        assertFalse(context.contains("val diagnostic = audience.audienceKindUid"))
        assertFalse(context.contains("KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, principal.uid"))
        assertTrue(context.contains("protectedReads.trustedPrincipal(audience)"))
        assertTrue(context.contains("protectedReads.diagnosticRows"))
        val panel=source("app/src/main/java/com/rpgos/app/CharacterPanel.kt")
        assertTrue(panel.contains("ProtectedReadResult<PlayerStateSnapshot>"));assertFalse(panel.contains("visibility.project(request)"))
        val store=source("app/src/main/java/com/rpgos/app/LocalGameStore.kt")
        assertTrue(store.contains("reads.playerState(audience,purpose,playerUid)"))
        val view=source("app/src/main/java/com/rpgos/app/RpgOsViewModel.kt")
        assertTrue(view.contains("prepareSource(source.visualUid, source.uri)"));assertTrue(view.contains("sourceImageSha256 = prepared.sha256"));assertTrue(view.contains("editPrepared"))
        val edit=source("app/src/main/java/com/rpgos/app/ImageEditBackendClient.kt")
        assertTrue(edit.contains("digestBytes(prepared.bytes)"));assertTrue(edit.contains("prepared.bytes.toRequestBody"))
    }

    private fun repoRoot():File{var f=File(System.getProperty("user.dir")).canonicalFile;repeat(8){if(File(f,"app/src/main/java").isDirectory)return f;f=f.parentFile?:return@repeat};error("repo root not found")}
    private fun source(path:String)=File(repoRoot(),path).readText()
}
'''
write("app/src/test/java/com/rpgos/app/Phase38PostAuditHardeningTest.kt",test)

print("Phase38 post-audit hardening materialized")
