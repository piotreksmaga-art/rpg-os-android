package com.rpgos.app

class VisualPromptBuilder {
    private fun requireVisualProjection(context: ContextBundle, purpose: String) {
        context.visibilityEnvelope.requirePurpose(purpose)
        require(context.visibilityEnvelope.maximumDisclosure != DisclosureLevel.DENY) { "RPGOS-VISIBILITY:VISUAL_CONTEXT_DENIED" }
    }

    fun buildScenePrompt(
        playerInput: String,
        context: ContextBundle,
        style: String = "cinematic RPG scene illustration"
    ): String {
        requireVisualProjection(context, VisibilityPurposeKinds.SCENE_VISUALIZATION)
        val time = context.time.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        val scene = context.scene.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        val actors = context.relevantNpcs.take(6).joinToString("; ") { row ->
            row["name"]?.toString() ?: row["character_uid"]?.toString() ?: "world actor"
        }
        val worldPresentation = worldPresentation(context)
        return """
            Create a scene illustration for an RPG campaign.
            World presentation: $worldPresentation
            Player action / scene intent: $playerInput
            Scene state: $scene
            World time: $time
            Relevant disclosed actors: $actors
            Style: $style.
            Focus on environment, readable silhouettes, cinematic composition, and continuity with the disclosed scene.
            Do not invent extra named actors or lore-critical objects that are not implied by the disclosed projection.
        """.trimIndent()
    }

    fun buildCharacterPrompt(
        name: String,
        traits: List<String>,
        equipment: List<String>,
        worldNotes: String,
        context: ContextBundle,
        style: String = "detailed RPG character concept art"
    ): String {
        requireVisualProjection(context, VisibilityPurposeKinds.CHARACTER_VISUALIZATION)
        return """
            Create character concept art for: $name.
            World presentation: ${worldPresentation(context)}
            Traits disclosed for this visualization: ${traits.joinToString(", ")}.
            Equipment disclosed for this visualization: ${equipment.joinToString(", ")}.
            World continuity notes disclosed for this visualization: $worldNotes.
            Style: $style.
            Full-body or three-quarter view, neutral readable pose, consistent costume details, no text labels.
        """.trimIndent()
    }

    fun buildLocationPrompt(
        name: String,
        description: String,
        era: String,
        context: ContextBundle,
        style: String = "cinematic environment concept art"
    ): String {
        requireVisualProjection(context, VisibilityPurposeKinds.LOCATION_VISUALIZATION)
        return """
            Create environment concept art for the RPG location "$name".
            World presentation: ${worldPresentation(context)}
            Description disclosed for this visualization: $description.
            Era disclosed for this visualization: $era.
            Style: $style.
            Wide establishing shot, strong environmental storytelling, no people unless needed for scale, no text labels.
        """.trimIndent()
    }

    private fun worldPresentation(context: ContextBundle): String =
        context.scene["world_presentation"]?.toString()?.takeIf { it.isNotBlank() }
            ?: context.contextMeta["world_presentation"]?.toString()?.takeIf { it.isNotBlank() }
            ?: "Use only the world presentation information disclosed in this projection."
}
