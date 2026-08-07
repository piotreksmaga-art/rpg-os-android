package com.rpgos.app

data class TechniqueBrowserItem(
    val name: String,
    val category: String,
    val rank: String,
    val element: String,
    val wikiUrl: String,
    val verification: String
)

data class MissionBrowserItem(
    val uid: String,
    val title: String,
    val rank: String,
    val status: String,
    val reward: String,
    val objective: String
)
