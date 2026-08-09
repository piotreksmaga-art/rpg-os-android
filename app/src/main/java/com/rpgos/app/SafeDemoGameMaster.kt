package com.rpgos.app

class SafeDemoGameMaster {
    fun respond(playerInput: String, context: ContextBundle, chapter: Int): BackendTurnResult {
        require(context.playerStatus["fallback"] != true) {
            "LEGACY_FALLBACK_CONTEXT_REJECTED: SafeDemo nie może tworzyć narracji bez autorytatywnego ContextBundle."
        }

        val location = context.scene["location_uid"]?.toString()
        val locationText = if (location.isNullOrBlank()) "" else " Lokacja: $location."
        return BackendTurnResult(
            narration = "[TRYB AWARYJNY] Ruch przyjęty: \"$playerInput\".$locationText " +
                "Backend AI nie odpowiedział, ale RPG OS działa dalej. Rozdział: $chapter.",
            patch = null
        )
    }
}
