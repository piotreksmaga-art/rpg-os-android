package com.rpgos.app

data class VisualSuggestion(
    val kind: String,
    val title: String,
    val reason: String,
    val promptSeed: String,
    val priority: Int
)

class VisualSuggestionEngine {
    fun suggest(playerInput: String, context: ContextBundle): List<VisualSuggestion> {
        val out = mutableListOf<VisualSuggestion>()
        val lower = playerInput.lowercase()

        val hasCombat = listOf("atak", "walka", "uderzam", "kunai", "jutsu", "biję", "strzelam").any { lower.contains(it) }
        val hasTravel = listOf("idę", "podróż", "wchodzę", "docieram", "lokacja", "wioska", "las", "góry").any { lower.contains(it) }
        val importantNpc = context.relevantNpcs.firstOrNull()
        val activePressure = context.worldPressures.maxByOrNull { (it["magnitude"] as? Number)?.toDouble() ?: 0.0 }

        if (hasCombat) {
            out += VisualSuggestion(
                "scene",
                "Scena walki",
                "Wysoka dynamika i zmiana stanu świata.",
                playerInput,
                100
            )
        }
        if (hasTravel) {
            out += VisualSuggestion(
                "location",
                "Nowa sceneria",
                "Zmiana miejsca jest dobrym punktem do utrwalenia wyglądu świata.",
                playerInput,
                80
            )
        }
        if (importantNpc != null) {
            val name = importantNpc["name"]?.toString() ?: "NPC"
            out += VisualSuggestion(
                "character",
                "Portret: $name",
                "Istotna postać jest aktywna w bieżącym kontekście.",
                name,
                70
            )
        }
        if (activePressure != null) {
            out += VisualSuggestion(
                "scene",
                "Wydarzenie świata",
                "Silna presja przyszłego wydarzenia może zasługiwać na ilustrację.",
                activePressure["summary"]?.toString() ?: playerInput,
                60
            )
        }

        return out.sortedByDescending { it.priority }.take(3)
    }
}
