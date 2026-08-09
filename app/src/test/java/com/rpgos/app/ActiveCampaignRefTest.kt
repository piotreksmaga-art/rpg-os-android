package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ActiveCampaignRefTest {
    @Test
    fun defaultCampaignKeepsLegacyBackendId() {
        assertEquals(
            ActiveCampaignRef.DEFAULT_CAMPAIGN_ID,
            ActiveCampaignRef.fallbackCampaignId(ActiveCampaignRef.DEFAULT_DIRECTORY)
        )
    }

    @Test
    fun customCampaignGetsDeterministicFallbackId() {
        assertEquals(
            "my-long-campaign",
            ActiveCampaignRef.fallbackCampaignId("My_Long Campaign.campaign")
        )
    }

    @Test
    fun manifestIdOverridesDirectoryFallback() {
        val saves = Files.createTempDirectory("rpgos-saves").toFile()
        val campaign = File(saves, "Renamed.campaign").apply { mkdirs() }
        File(campaign, "campaign.json").writeText(
            """{"id":"campaign-stable-001","version":"1"}"""
        )

        val ref = ActiveCampaignRef.resolve(saves, campaign.name)

        assertEquals("Renamed.campaign", ref.directoryName)
        assertEquals("campaign-stable-001", ref.campaignId)
    }

    @Test
    fun databasePathResolvesSiblingManifestIdentity() {
        val saves = Files.createTempDirectory("rpgos-db-path").toFile()
        val campaign = File(saves, "Campaign_X.campaign").apply { mkdirs() }
        File(campaign, "campaign.json").writeText("""{"id":"campaign-x"}""")
        val db = File(campaign, "campaign.db")

        val ref = ActiveCampaignRef.fromDatabasePath(db.absolutePath)

        assertEquals("Campaign_X.campaign", ref.directoryName)
        assertEquals("campaign-x", ref.campaignId)
    }

    @Test
    fun directoryTraversalCannotBecomeCampaignIdentity() {
        val saves = Files.createTempDirectory("rpgos-traversal").toFile()

        assertThrows(IllegalArgumentException::class.java) {
            ActiveCampaignRef.resolve(saves, "../Other.campaign")
        }
    }
}
