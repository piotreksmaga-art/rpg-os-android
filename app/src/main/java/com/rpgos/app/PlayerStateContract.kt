package com.rpgos.app

/**
 * Canonical identity of the player-controlled character inside one campaign.
 *
 * `campaignId` is included deliberately: a character UID is not allowed to
 * silently leak between campaigns even if two saves happen to use the same
 * legacy entity identifier.
 */
data class ActivePlayerRef(
    val campaignId: String,
    val playerUid: String
)

enum class PlayerStateClass {
    PERSISTENT,
    DERIVED,
    RUNTIME
}

/**
 * Phase 3 contract. It defines ownership of player state without yet imposing
 * Phase 4 dynamic-stat definitions or Phase 5 derived formulas.
 *
 * Persistent values are authoritative durable character data.
 * Derived values are rebuildable projections/calculations.
 * Runtime values are current transient conditions/resources.
 */
data class PlayerStateSnapshot(
    val activePlayer: ActivePlayerRef,
    val persistent: Map<String, Any?>,
    val derived: Map<String, Any?>,
    val runtime: Map<String, Any?>
)

object PlayerStatePolicy {
    fun validate(ref: ActivePlayerRef) {
        require(ref.campaignId.isNotBlank()) { "campaignId must not be blank" }
        require(ref.playerUid.isNotBlank()) { "playerUid must not be blank" }
    }

    fun classifyLegacyField(fieldName: String): PlayerStateClass {
        val key = fieldName.lowercase()
        return when {
            key.contains("current_hp") ||
                key == "hp" ||
                key.contains("current_chakra") ||
                key.contains("current_energy") ||
                key.contains("current_stamina") ||
                key.contains("fatigue") ||
                key.contains("cooldown") ||
                key.contains("bleeding") ||
                key.contains("pain") ||
                key.contains("temporary") ||
                key.contains("runtime") -> PlayerStateClass.RUNTIME

            key.contains("effective_") ||
                key.contains("derived_") ||
                key.contains("max_") ||
                key.contains("regeneration") ||
                key.contains("net_worth") ||
                key.contains("combat_rating") -> PlayerStateClass.DERIVED

            else -> PlayerStateClass.PERSISTENT
        }
    }
}
