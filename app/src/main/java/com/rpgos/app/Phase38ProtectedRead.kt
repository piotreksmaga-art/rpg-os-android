package com.rpgos.app

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
