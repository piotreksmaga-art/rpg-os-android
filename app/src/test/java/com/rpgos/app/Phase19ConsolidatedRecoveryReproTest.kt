package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase19ConsolidatedRecoveryReproTest {
    @Test fun REPRO_A_bootstrapDbOnlyPredicateDestroysValidRollback() {
        val root = tempDir("weak-bootstrap")
        val target = File(root, "A.worldpack").apply {
            mkdirs()
            SQLiteDatabase.openOrCreateDatabase(File(this, "world.db"), null).close()
            File(this, "worldpack.json").writeText("{malformed")
        }
        val rollback = worldPack(File(root, ".A.worldpack.rollback-good"), "A", "1")
        val weakBootstrapPredicate: (File) -> Boolean = { File(it, "world.db").isFile }
        CanonicalPackageReplacement.reconcile(target, weakBootstrapPredicate)
        assertTrue("defect: malformed live survives", target.exists())
        assertFalse("defect: valid rollback is destroyed", rollback.exists())
        assertFalse(PackageValidator().validateWorldPack(target).ok)
    }

    @Test fun REPRO_B_partialDeleteAbortsOldRollbackRestoration() {
        val root = tempDir("rollback-delete")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        File(target, "old-marker").writeText("OLD")
        val prepared = worldPack(File(root, ".A.worldpack.prepared-current"), "A", "2")
        val ops = object : CanonicalPackageFileOps {
            override fun rename(source: File, target: File): Boolean = source.renameTo(target)
            override fun deleteRecursively(target: File): Boolean {
                if (target.name == "A.worldpack") {
                    File(target, "worldpack.json").delete()
                    return false
                }
                return target.deleteRecursively()
            }
        }
        try {
            CanonicalPackageAuthorityGate.mutate {
                CanonicalPackageReplacement.activatePreparedUnderGate(prepared, target, ops) {
                    error("callback failure")
                }
            }
            fail("expected explicit rollback failure")
        } catch (e: IllegalStateException) {
            assertEquals("PACKAGE_REPLACEMENT_ROLLBACK_FAILED", e.message)
        }
        assertTrue("defect: partially deleted new canonical target remains", target.exists())
        assertFalse(File(target, "old-marker").exists())
        assertEquals(1, root.listFiles()!!.count { it.name.startsWith(".A.worldpack.rollback-") })
    }

    @Test fun REPRO_C_inactiveWorldPackRollbackIsNotDiscoveredOnStartup() {
        val app = cleanApp()
        val root = File(app.filesDir, "rpgos")
        val wpRoot = File(root, "worldpacks").apply { mkdirs() }
        val inactiveTarget = worldPack(File(wpRoot, "Inactive.worldpack"), "INACTIVE", "1")
        val rollback = File(wpRoot, ".Inactive.worldpack.rollback-crash")
        assertTrue(inactiveTarget.renameTo(rollback))
        worldPack(File(wpRoot, ".Inactive.worldpack.prepared-crash"), "INACTIVE", "2")
        LocalGameStore(app).bootstrap()
        assertFalse("defect: inactive canonical target remains missing", inactiveTarget.exists())
        assertTrue(rollback.exists())
        assertFalse(RpgPackageManager(app).listWorldPacks().any { it.path == inactiveTarget.absolutePath })
    }

    private fun worldPack(dir: File, id: String, version: String): File {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText("""{"id":"$id","version":"$version","engine_api":"1"}""")
        return dir
    }

    private fun cleanApp(): Context {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "rpgos").deleteRecursively()
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        return app
    }

    private fun tempDir(name: String): File = File(
        System.getProperty("java.io.tmpdir"), "rpgos-p19-repro-$name-${System.nanoTime()}"
    ).apply { deleteRecursively(); mkdirs() }
}
