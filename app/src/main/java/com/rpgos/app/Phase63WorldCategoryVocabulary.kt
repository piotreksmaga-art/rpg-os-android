package com.rpgos.app

/** Semantic aliases name a slot category only; they never establish existence or topology. */
internal object WorldCategoryVocabulary {
    private val aliases=mapOf(
        "POLIGON" to "TRAINING_SITE","TEREN_TRENINGOWY" to "TRAINING_SITE","MIEJSCE_TRENINGU" to "TRAINING_SITE",
        "TRAINING_GROUND" to "TRAINING_SITE","TRAINING_AREA" to "TRAINING_SITE","TRAINING_LOCATION" to "TRAINING_SITE",
        "MORZE" to "SEA","OCEAN" to "OCEAN","PUSTYNIA" to "DESERT","DESERT" to "DESERT",
        "WYBRZEŻE" to "COAST","COAST" to "COAST","GÓRA" to "MOUNTAIN","MOUNTAIN" to "MOUNTAIN",
        "RZEKA" to "RIVER","JEZIORO" to "LAKE","LAS" to "FOREST"
    )
    fun canonical(category:String):String=normalizedWorldToken(category).let { aliases[it]?:it }
    fun equivalentCategories(category:String):Set<String> {
        val key=canonical(category)
        return aliases.filterValues { it==key }.keys+key
    }
}
