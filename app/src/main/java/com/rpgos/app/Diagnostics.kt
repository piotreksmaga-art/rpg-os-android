package com.rpgos.app

data class DiagnosticsSnapshot(
    val activeCampaign: String,
    val activeWorldPack: String,
    val backupCount: Int,
    val worldPackCount: Int,
    val campaignCount: Int,
    val contextSummary: String,
    val sourceOfTruthDomains: Int,
    val openTimelineAlerts: Int
)
