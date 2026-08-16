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
class Phase19ConsolidatedRecoveryHardeningTest {
    @Test fun P19_BOOTSTRAP_VALIDATION_01_campaignDbOnlyInvalidMetadataIsInvalid() {
        val dir = tempDir("campaign-db-only")
        SQLiteDatabase.openOrCreateDatabase(File(dir, "campaign.db"), null).close()
        assertFalse(PackageValidator().validateCampaign(dir).ok)
    }

    @Test fun P19_BOOTSTRAP_VALIDATION_02_worldDbOnlyInvalidManifestIsInvalid() {
        val dir = tempDir("world-db-only")
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText("{bad")
        assertFalse(runCatching { PackageValidator().validateWorldPack(dir).ok }.getOrDefault(false))
    }

    @Test fun P19_BOOTSTRAP_VALIDATION_03_malformedLiveValidRollbackRestored() {
        val root = tempDir("bootstrap-rollback")
        val target = File(root, "A.worldpack").apply { mkdirs(); SQLiteDatabase.openOrCreateDatabase(File(this, "world.db"), null).close(); File(this, "worldpack.json").writeText("{bad") }
        worldPack(File(root, ".A.worldpack.rollback-good"), "A", "1")
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        assertEquals("1", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_BOOTSTRAP_VALIDATION_04_validLiveStaleRollbackPreserved() {
        val root = tempDir("bootstrap-live")
        val target = worldPack(File(root, "A.worldpack"), "A", "2")
        worldPack(File(root, ".A.worldpack.rollback-stale"), "A", "1")
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        assertEquals("2", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_BOOTSTRAP_VALIDATION_05_fullCampaignCandidateRecoverable() {
        val root = tempDir("campaign-full")
        val target = File(root, "A.campaign")
        campaign(File(root, ".A.campaign.rollback-good"), "A", "1")
        CanonicalPackageReplacement.reconcile(target, ::validCampaign)
        assertTrue(PackageValidator().validateCampaign(target).ok)
    }

    @Test fun P19_BOOTSTRAP_VALIDATION_06_fullWorldPackCandidateRecoverable() {
        val root = tempDir("world-full")
        val target = File(root, "A.worldpack")
        worldPack(File(root, ".A.worldpack.rollback-good"), "A", "1")
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        assertTrue(PackageValidator().validateWorldPack(target).ok)
    }

    @Test fun P19_ROLLBACK_ATOMIC_01_callbackFailureRestoresOldTarget() = rollbackCleanupScenario(false)
    @Test fun P19_ROLLBACK_ATOMIC_02_failedNewCleanupFailureDoesNotPreventRestore() = rollbackCleanupScenario(true)
    @Test fun P19_ROLLBACK_ATOMIC_03_oldPackageCanonicalAfterCleanupFailure() = rollbackCleanupScenario(true)

    @Test fun P19_ROLLBACK_ATOMIC_04_uncommittedNewCannotWinLaterReconcile() {
        val root = tempDir("rollback-reconcile")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        val prepared = worldPack(File(root, ".A.worldpack.prepared-new"), "A", "2")
        val ops = cleanupFailingOps()
        runCatching { CanonicalPackageAuthorityGate.mutate { CanonicalPackageReplacement.activatePreparedUnderGate(prepared, target, ops) { error("callback") } } }
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        assertEquals("1", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_ROLLBACK_ATOMIC_05_readerNeverObservesAcceptedPartialTarget() {
        val root = tempDir("rollback-reader")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        val prepared = worldPack(File(root, ".A.worldpack.prepared-new"), "A", "2")
        runCatching { CanonicalPackageReplacement.activatePrepared(prepared, target, ::validWorldPack) { error("callback") } }
        assertEquals("1", CanonicalPackageAuthorityGate.observe { PackageValidator().validateWorldPack(target).version })
    }

    @Test fun P19_INACTIVE_RECOVERY_01_inactiveInterruptedAfterTargetToRollback() {
        val app = cleanApp()
        val root = File(app.filesDir, "rpgos/worldpacks").apply { mkdirs() }
        val target = worldPack(File(root, "Inactive.worldpack"), "I", "1")
        val rollback = File(root, ".Inactive.worldpack.rollback-crash")
        assertTrue(target.renameTo(rollback))
        worldPack(File(root, ".Inactive.worldpack.prepared-crash"), "I", "2")
        assertFalse(target.exists())
        assertTrue(rollback.exists())
    }
    @Test fun P19_INACTIVE_RECOVERY_02_restartDiscoversRecoveryArtifacts() { val (target, _) = inactiveRecovery(); assertTrue(target.exists()) }
    @Test fun P19_INACTIVE_RECOVERY_03_canonicalTargetRestored() { val (target, _) = inactiveRecovery(); assertEquals("1", PackageValidator().validateWorldPack(target).version) }
    @Test fun P19_INACTIVE_RECOVERY_04_packageAppearsInListing() { val app = cleanApp(); val root = File(app.filesDir,"rpgos/worldpacks").apply{mkdirs()}; val target=File(root,"Inactive.worldpack"); worldPack(File(root,".Inactive.worldpack.rollback-x"),"I","1"); LocalGameStore(app).bootstrap(); assertTrue(RpgPackageManager(app).listWorldPacks().any{it.path==target.absolutePath}) }
    @Test fun P19_INACTIVE_RECOVERY_05_secondRestartIdempotent() { val app=cleanApp(); val root=File(app.filesDir,"rpgos/worldpacks").apply{mkdirs()}; val target=File(root,"Inactive.worldpack"); worldPack(File(root,".Inactive.worldpack.rollback-x"),"I","1"); LocalGameStore(app).bootstrap(); val first=PackageValidator().validateWorldPack(target).version; LocalGameStore(app).bootstrap(); assertEquals(first,PackageValidator().validateWorldPack(target).version) }

    @Test fun P19_INACTIVE_RECOVERY_06_ambiguousInactiveFailsClosed() {
        val root=tempDir("inactive-ambiguous"); val target=File(root,"A.worldpack"); worldPack(File(root,".A.worldpack.rollback-a"),"A","1"); worldPack(File(root,".A.worldpack.rollback-b"),"A","1")
        try { CanonicalPackageReplacement.reconcileRoot(root,::validWorldPack); fail("must fail closed") } catch(e:IllegalStateException){ assertEquals("PACKAGE_REPLACEMENT_RECOVERY_AMBIGUOUS",e.message) }
        assertFalse(target.exists())
    }

    @Test fun P19_INACTIVE_RECOVERY_07_validInactiveLiveStaleMetadataRetained() {
        val root=tempDir("inactive-live"); val target=worldPack(File(root,"A.worldpack"),"A","2"); worldPack(File(root,".A.worldpack.rollback-old"),"A","1"); CanonicalPackageReplacement.reconcileRoot(root,::validWorldPack); assertEquals("2",PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_INACTIVE_RECOVERY_08_invalidCandidateNeverPromoted() {
        val root=tempDir("inactive-invalid"); val target=File(root,"A.worldpack"); File(root,".A.worldpack.rollback-bad").apply{mkdirs(); File(this,"world.db").writeText("bad")}
        runCatching { CanonicalPackageReplacement.reconcileRoot(root,::validWorldPack) }
        assertFalse(target.exists())
    }

    private fun rollbackCleanupScenario(cleanupFails: Boolean) {
        val root=tempDir("rollback-$cleanupFails"); val target=worldPack(File(root,"A.worldpack"),"A","1"); File(target,"old-marker").writeText("OLD"); val prepared=worldPack(File(root,".A.worldpack.prepared-new"),"A","2")
        val ops=if(cleanupFails) cleanupFailingOps() else object:CanonicalPackageFileOps{override fun rename(source:File,target:File)=source.renameTo(target);override fun deleteRecursively(target:File)=target.deleteRecursively()}
        runCatching { CanonicalPackageAuthorityGate.mutate { CanonicalPackageReplacement.activatePreparedUnderGate(prepared,target,ops){error("callback")}} }
        assertTrue(File(target,"old-marker").isFile); assertEquals("1",PackageValidator().validateWorldPack(target).version)
    }

    private fun cleanupFailingOps()=object:CanonicalPackageFileOps{
        override fun rename(source:File,target:File)=source.renameTo(target)
        override fun deleteRecursively(target:File):Boolean = if(target.name.contains(".failed-")) false else target.deleteRecursively()
    }

    private fun inactiveRecovery():Pair<File,File>{ val app=cleanApp(); val root=File(app.filesDir,"rpgos/worldpacks").apply{mkdirs()}; val target=worldPack(File(root,"Inactive.worldpack"),"I","1"); val rollback=File(root,".Inactive.worldpack.rollback-crash"); assertTrue(target.renameTo(rollback)); worldPack(File(root,".Inactive.worldpack.prepared-crash"),"I","2"); LocalGameStore(app).bootstrap(); return target to rollback }
    private fun validWorldPack(file:File)=runCatching{PackageValidator().validateWorldPack(file).ok}.getOrDefault(false)
    private fun validCampaign(file:File)=runCatching{PackageValidator().validateCampaign(file).ok}.getOrDefault(false)
    private fun worldPack(dir:File,id:String,version:String):File{dir.mkdirs();SQLiteDatabase.openOrCreateDatabase(File(dir,"world.db"),null).close();File(dir,"worldpack.json").writeText("""{"id":"$id","version":"$version","engine_api":"1"}""");return dir}
    private fun campaign(dir:File,id:String,version:String):File{dir.mkdirs();SQLiteDatabase.openOrCreateDatabase(File(dir,"campaign.db"),null).close();File(dir,"campaign.json").writeText("""{"id":"$id","version":"$version","core_api":"1"}""");return dir}
    private fun cleanApp():Context{val app=RuntimeEnvironment.getApplication();File(app.filesDir,"rpgos").deleteRecursively();app.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit();return app}
    private fun tempDir(name:String)=File(System.getProperty("java.io.tmpdir"),"rpgos-p19-hardening-$name-${System.nanoTime()}").apply{deleteRecursively();mkdirs()}
}
