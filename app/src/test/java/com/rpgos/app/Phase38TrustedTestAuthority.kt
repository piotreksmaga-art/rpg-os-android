package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

internal data class TrustedAudienceFixture(
    val audience: AudienceContext,
    val trusted: TrustedPrincipalContext
)

internal data class TrustedContextBuilderFixture(
    val builder: ContextBuilder,
    val audience: AudienceContext
)

internal object Phase38TrustedTestAuthority {
    fun player(campaignUid: String, controlledSubjectUids: Set<String> = emptySet()): TrustedAudienceFixture {
        val audience = VisibilityAudienceFactory.player(campaignUid)
        return TrustedAudienceFixture(
            audience,
            requireNotNull(Phase38RuntimeAuthority.application(audience, controlledSubjectUids = controlledSubjectUids))
        )
    }

    fun playerCharacter(campaignUid: String, pcUid: String): TrustedAudienceFixture {
        val audience = AudienceContext(
            campaignUid,
            AudienceKinds.PLAYER_CHARACTER,
            VisibilityPrincipalRef("ENTITY", pcUid)
        )
        val holder = KnowledgeHolderRef(KnowledgeHolderKinds.PLAYER_CHARACTER, pcUid, campaignUid)
        val cognition = TrustedCognitionResolver { requestedCampaign, principal ->
            if (requestedCampaign == campaignUid && principal == audience.principal) setOf(holder) else emptySet()
        }
        return TrustedAudienceFixture(
            audience,
            requireNotNull(
                Phase38RuntimeAuthority.application(
                    audience,
                    controlledSubjectUids = setOf(pcUid),
                    cognitionResolver = cognition
                )
            )
        )
    }

    fun diagnosticContextBuilder(
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

    fun diagnostic(campaignUid: String): TrustedAudienceFixture {
        val audience = VisibilityAudienceFactory.diagnostic(campaignUid)
        return TrustedAudienceFixture(
            audience,
            Phase38RuntimeAuthority.privileged(audience, Phase38RuntimeAuthority.PRIV_DIAGNOSTIC)
        )
    }
}
