package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Legacy generic patch adapter.
 *
 * Phase 26 deliberately makes ordinary StatePatch application fail closed. A backend/AI table
 * allowlist is not a gameplay mutation capability. Authoritative gameplay writes must be expressed
 * as typed domain mutations admitted through CampaignMutationBoundary and, from Phase 27 onward,
 * committed by TurnTransaction. Migration/install/recovery code uses its explicit typed APIs rather
 * than this generic patch route.
 */
class StatePatchEngine(
    @Suppress("UNUSED_PARAMETER") saveDb: SQLiteDatabase,
    @Suppress("UNUSED_PARAMETER") registry: SourceOfTruthRegistry
) {
    fun apply(patch: StatePatch): PatchResult = PatchResult(
        success = false,
        appliedOperations = 0,
        message = "$GAMEPLAY_PATCH_BYPASS_BLOCKED ${patch.transactionId}"
    )

    companion object {
        const val GAMEPLAY_PATCH_BYPASS_BLOCKED =
            "RPGOS-MUTATION-GATE:GENERIC_STATE_PATCH_NOT_AUTHORIZED"
    }
}
