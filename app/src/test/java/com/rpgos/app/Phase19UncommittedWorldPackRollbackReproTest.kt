package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase19UncommittedWorldPackRollbackReproTest {
    @Test fun P19_C3_UNCOMMITTED_WORLDPACK_ROLLBACK_FAIL_01_preFixAuthorityAcceptsFailedNewA2() {
        val app = cleanApp()
        val root = File(app.filesDir, "rpgos")
        val saves = File(root, "saves")
        val worldpacks = File(root, "worldpacks")
        val campaign = campaign(File(saves, "C1.campaign"), "C1")
        val target = worldPack(File(worldpacks, "A.worldpack"), "WORLD-A", "1")
        val prepared = worldPack(File(worldpacks, ".A.worldpack.prepared-repro"), "WORLD-A", "2")
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit().putString("active_campaign", campaign.name).putString("active_worldpack", target.name).commit()

        val ops = object : CanonicalPackageFileOps {
            var renames = 0
            override fun rename(source: File, target: File): Boolean {
                renames++
                return when (renames) {
                    1, 2 -> source.renameTo(target)
                    3 -> false // canonical A2 -> .failed-* quarantine fails
                    else -> source.renameTo(target)
                }
            }
            override fun deleteRecursively(target: File): Boolean = target.deleteRecursively()
        }

        try {
            CanonicalPackageAuthorityGate.mutate {
                CanonicalPackageReplacement.activatePreparedUnderGate(prepared, target, ops) {
                    error("saveInstalled failed")
                }
            }
        } catch (_: IllegalStateException) {
            // expected rollback failure
        }

        assertEquals("2", PackageValidator().validateWorldPack(target).version)
        assertTrue(worldpacks.listFiles().orEmpty().any { it.name.startsWith(".A.worldpack.rollback-") })

        val authority = CanonicalSelectionWorldPackAuthoritySource(prefs, saves, worldpacks).currentAuthority()
        assertEquals("C1", authority.campaignUid)
        assertEquals(WorldPackRuleBinding("WORLD-A", "2"), authority.binding)
    }

    private fun cleanApp(): Context {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "rpgos").deleteRecursively()
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        return app
    }

    private fun campaign(dir: File, id: String): File {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "campaign.db"), null).close()
        File(dir, "campaign.json").writeText("""{"id":"$id","version":"1","core_api":"1"}""")
        return dir
    }

    private fun worldPack(dir: File, id: String, version: String): File {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText("""{"id":"$id","version":"$version","engine_api":"1"}""")
        return dir
    }
}
