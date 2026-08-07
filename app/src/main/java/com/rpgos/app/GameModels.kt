package com.rpgos.app

data class ChatMessage(val role: String, val text: String)

data class StatusSnapshot(
    val name: String = "Smagi",
    val level: Int = 1,
    val age: String = "—",
    val rank: String = "—",
    val chakra: String = "—",
    val location: String = "—"
)

data class TimeSnapshot(
    val label: String = "~40 lat przed założeniem Konohy",
    val era: String = "Warring States Period",
    val season: String = "spring",
    val hour: String = "08:00"
)

data class ChronicleEntry(
    val chapter: Int,
    val title: String,
    val summary: String
)
