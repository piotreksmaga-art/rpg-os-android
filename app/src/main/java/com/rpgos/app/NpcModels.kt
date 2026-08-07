package com.rpgos.app

data class NpcListItem(
    val uid: String,
    val name: String,
    val clan: String,
    val village: String,
    val status: String
)

data class NpcDetail(
    val uid: String,
    val name: String,
    val fields: List<StatLine>,
    val memories: List<String>,
    val beliefs: List<String>,
    val schedules: List<String>,
    val decisions: List<String>
)

data class RelationEdge(
    val source: String,
    val target: String,
    val type: String,
    val score: Float
)

data class WarSummary(
    val name: String,
    val status: String,
    val summary: String
)

data class EconomySummary(
    val name: String,
    val treasury: String,
    val prosperity: String,
    val stability: String
)
