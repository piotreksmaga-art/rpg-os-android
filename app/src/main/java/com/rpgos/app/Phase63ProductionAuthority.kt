package com.rpgos.app

/** A missing/corrupt source is not permission to run production with unbound rules. The
 * exception crosses only composition; the application adapter returns a typed failure before
 * a request, provider, pending marker, or canonical mutation can be started. */
internal class ProductionWorldAuthorityUnavailable : IllegalStateException(REASON) {
    companion object { const val REASON="P63:WORLD_RULE_SOURCE_UNAVAILABLE" }
}

internal fun productionWorldAuthority(campaignUid:String,read:()->CurrentWorldPackAuthority):CurrentWorldPackAuthority {
    val authority=try { read() }
        catch(cancel:java.util.concurrent.CancellationException) { throw cancel }
        catch(_:Exception) { throw ProductionWorldAuthorityUnavailable() }
    if(authority.campaignUid!=campaignUid)throw ProductionWorldAuthorityUnavailable()
    return authority
}
