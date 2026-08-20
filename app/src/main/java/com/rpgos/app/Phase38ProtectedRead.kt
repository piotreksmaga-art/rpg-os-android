package com.rpgos.app

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

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
internal annotation class TrustedProtectedReadGateway

@TrustedProtectedReadGateway
class ProtectedCampaignReadRepository internal constructor(
    private val openSaveDb: () -> SQLiteDatabase,
    private val campaignUid: String,
    private val activePlayer: () -> ActivePlayerRef?,
    private val closeDbAfterRead: Boolean = true,
    private val principalResolverOverride: TrustedPrincipalResolver? = null
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
        val active = activePlayer()
        val controlled = if (audience.audienceKindUid == AudienceKinds.PLAYER && active != null) setOf(active.playerUid) else emptySet()
        Phase38RuntimeAuthority.application(audience, controlledSubjectUids = controlled)
    }
    fun playerState(audience: AudienceContext, purpose: PurposeContext, subjectUid: String): ProtectedReadResult<PlayerStateSnapshot> {
        val req = VisibilityRequest(audience, purpose, VisibilitySubjectRef(campaignUid, VisibilitySubjectKinds.PLAYER_STATE, subjectUid))
        return withSaveDb { db -> ProtectedReadGateway(VisibilityAuthorityService(), resolver()).read(req) { PlayerStateStore(db, campaignUid).load() } }
    }
    fun truth(audience: AudienceContext, purpose: PurposeContext, limit: Int = 100): ProtectedReadResult<List<CampaignTruthRecord>> {
        val req=VisibilityRequest(audience,purpose,VisibilitySubjectRef(campaignUid,VisibilitySubjectKinds.CAMPAIGN_TRUTH,"CAMPAIGN_TRUTH_RECORDS"))
        return withSaveDb { db -> ProtectedReadGateway(VisibilityAuthorityService(), resolver()).read(req) { CampaignTruthStore(db,campaignUid).active(limit=limit) } }
    }
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

}
