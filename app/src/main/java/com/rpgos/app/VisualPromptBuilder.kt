package com.rpgos.app

class VisualPromptBuilder {
    fun buildScenePrompt(
        playerInput: String,
        context: ContextBundle,
        style: String = "cinematic anime-inspired fantasy illustration"
    ): String {
        val time = context.time.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        val scene = context.scene.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        val npcs = context.relevantNpcs.take(6).joinToString("; ") { row ->
            row["name"]?.toString() ?: row["character_uid"]?.toString() ?: "NPC"
        }

        return """
            Create a scene illustration for an RPG campaign.
            Setting: Naruto-inspired shinobi world, but preserve the campaign's current established facts.
            Player action / scene intent: $playerInput
            Scene state: $scene
            World time: $time
            Relevant characters present or important: $npcs
            Style: $style.
            Focus on environment, readable silhouettes, cinematic composition, and continuity with the described scene.
            Do not invent extra named characters or lore-critical objects that are not implied by the scene.
        """.trimIndent()
    }

    fun buildCharacterPrompt(
        name: String,
        traits: List<String>,
        equipment: List<String>,
        worldNotes: String,
        style: String = "detailed anime-inspired RPG character concept art"
    ): String {
        return """
            Create character concept art for: $name.
            Traits: ${traits.joinToString(", ")}.
            Equipment: ${equipment.joinToString(", ")}.
            World continuity notes: $worldNotes.
            Style: $style.
            Full-body or three-quarter view, neutral readable pose, consistent costume details, no text labels.
        """.trimIndent()
    }

    fun buildLocationPrompt(
        name: String,
        description: String,
        era: String,
        style: String = "cinematic environment concept art"
    ): String {
        return """
            Create environment concept art for the RPG location "$name".
            Description: $description.
            Era: $era.
            Style: $style.
            Wide establishing shot, strong environmental storytelling, no people unless needed for scale, no text labels.
        """.trimIndent()
    }
}
