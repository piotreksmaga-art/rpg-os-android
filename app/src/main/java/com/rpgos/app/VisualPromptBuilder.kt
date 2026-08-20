package com.rpgos.app

class VisualPromptBuilder {
    fun buildScenePrompt(
        playerInput: String,
        context: ContextBundle,
        style: String = "cinematic RPG scene illustration"
    ): String {
        context.visibilityEnvelope.requirePurpose(VisibilityPurposeKinds.SCENE_VISUALIZATION)
        require(context.visibilityEnvelope.maximumDisclosure != DisclosureLevel.DENY) { "RPGOS-VISIBILITY:VISUAL_CONTEXT_DENIED" }
        val time = context.time.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        val scene = context.scene.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        val actors = context.relevantNpcs.take(6).joinToString("; ") { row ->
            row["name"]?.toString() ?: row["character_uid"]?.toString() ?: "world actor"
        }
        val worldPresentation = context.scene["world_presentation"]?.toString()?.takeIf { it.isNotBlank() }
            ?: context.contextMeta["world_presentation"]?.toString()?.takeIf { it.isNotBlank() }
            ?: "Use only the world presentation information disclosed in this projection."

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
        style: String = "detailed RPG character concept art"
    ): String = """
        Create character concept art for: $name.
        Traits: ${traits.joinToString(", ")}.
        Equipment: ${equipment.joinToString(", ")}.
        World continuity notes supplied for this visualization: $worldNotes.
        Style: $style.
        Full-body or three-quarter view, neutral readable pose, consistent costume details, no text labels.
    """.trimIndent()

    fun buildLocationPrompt(
        name: String,
        description: String,
        era: String,
        style: String = "cinematic environment concept art"
    ): String = """
        Create environment concept art for the RPG location "$name".
        Description: $description.
        Era: $era.
        Style: $style.
        Wide establishing shot, strong environmental storytelling, no people unless needed for scale, no text labels.
    """.trimIndent()
}
