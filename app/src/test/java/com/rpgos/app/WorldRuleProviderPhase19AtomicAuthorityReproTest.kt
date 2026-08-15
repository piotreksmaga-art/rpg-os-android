package com.rpgos.app

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorldRuleProviderPhase19AtomicAuthorityReproTest {
    private val c1Dir = "C1.campaign"
    private val c2Dir = "C2.campaign"
    private val c1Uid = "C1"
    private val c2Uid = "C2"
    private val a = WorldPackRuleBinding("WORLD-A", "1")
    private val b = WorldPackRuleBinding("WORLD-B", "1")

    @Before
    fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos")
        root.deleteRecursively()
        createCampaign(File(root, "saves/$c1Dir"), c1Uid)
        createCampaign(File(root, "saves/$c2Dir"), c2Uid)
        createWorldPack(File(root, "worldpacks/A.worldpack"), a)
        createWorldPack(File(root, "worldpacks/B.worldpack"), b)

        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("active_campaign", c1Dir)
            .putString("active_campaign_id", c1Uid)
            .putString("active_worldpack", "A.worldpack")
            .commit()
    }

    @Test
    fun preFixControlledInterleavingMustNotProduceC1PlusBHybridAuthority() {
        val app = RuntimeEnvironment.getApplication()
        val selection = CampaignSelectionManager(app)
        val realPrefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        val switched = AtomicBoolean(false)

        val scriptedPrefs = object : SharedPreferences by realPrefs {
            override fun getString(key: String?, defValue: String?): String? {
                val value = realPrefs.getString(key, defValue)
                if (key == "active_campaign" && switched.compareAndSet(false, true)) {
                    println("P19-C3-REPRO step1 resolver campaign read -> $value / $c1Uid")
                    realPrefs.edit()
                        .putString("active_campaign", c2Dir)
                        .putString("active_campaign_id", c2Uid)
                        .putString("active_worldpack", "B.worldpack")
                        .commit()
                    println("P19-C3-REPRO step2 canonical switch completed -> $c2Uid / WORLD-B@1")
                } else if (key == "active_worldpack") {
                    println("P19-C3-REPRO step3 resolver world-pack read -> $value")
                }
                return value
            }
        }

        val prefsField = CampaignSelectionManager::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        prefsField.set(selection, scriptedPrefs)

        val authority = selection.activeWorldPackAuthorityResolver().bindingForCampaign(c1Uid)
        println("P19-C3-REPRO step4 resolver returned authority for requested $c1Uid -> $authority")

        // Correct atomic behavior must never return the new C2/B binding as authority for old C1.
        // On failed candidate 8bb463e9 this assertion is expected to FAIL with actual WORLD-B@1.
        assertNull("torn read produced hybrid C1 + WORLD-B authority: $authority", authority)
    }

    private fun createCampaign(dir: File, uid: String) {
        dir.mkdirs()
        File(dir, "campaign.json").writeText("""{"id":"$uid"}""")
    }

    private fun createWorldPack(dir: File, binding: WorldPackRuleBinding) {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText(
            """{"id":"${binding.worldPackUid}","version":"${binding.worldPackVersion}","engine_api":"1"}"""
        )
    }
}
