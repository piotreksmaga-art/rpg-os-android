package com.rpgos.app

data class WorldLocationItem(
    val uid: String,
    val name: String,
    val type: String,
    val region: String,
    val description: String
)

data class WorldRegionItem(
    val uid: String,
    val name: String,
    val type: String,
    val description: String
)

data class WorldEventItem(
    val name: String,
    val status: String,
    val summary: String
)
