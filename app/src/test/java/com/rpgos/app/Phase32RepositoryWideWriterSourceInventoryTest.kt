package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Phase32RepositoryWideWriterSourceInventoryTest {
    private enum class WriterClass {
        CANONICAL_DOMAIN,
        APPEND_ONLY_EVIDENCE,
        DERIVED_PRESENTATION,
        ADMINISTRATIVE_MIGRATION_RECOVERY,
        OPERATIONAL_GUARD,
        UI_SETTINGS
    }

    /**
     * Closed inventory of production source files that contain a durable state write sink.
     * A new writer-bearing source file must be classified deliberately or this test fails.
     * This complements table-level RuntimeTruthLayerRegistry completeness: it inventories code
     * entry points/sinks rather than inferring authority from table names.
     */
    private val classifiedWriterFiles: Map<String, WriterClass> = buildMap {
        listOf(
            "ActivePlayerStore.kt",
            "AssetLiabilityStore.kt",
            "CampaignTruthStore.kt",
            "DevelopmentProjectStore.kt",
            "EquipmentStore.kt",
            "FinancialStore.kt",
            "InventoryStore.kt",
            "ModifierStore.kt",
            "OwnershipReferenceRegistry.kt",
            "OwnershipStore.kt",
            "Phase9Store.kt",
            "ProgressionProfileStore.kt",
            "SkillStore.kt",
            "StatResourceStore.kt",
            "TechniqueStore.kt"
        ).forEach { put(it, WriterClass.CANONICAL_DOMAIN) }

        listOf(
            "CampaignEventStore.kt",
            "CampaignCausalGraph.kt",
            "TurnTransactionReceiptStore.kt"
        ).forEach { put(it, WriterClass.APPEND_ONLY_EVIDENCE) }

        listOf(
            "ChapterSaveManager.kt",
            "GalleryService.kt",
            "VisualLibrary.kt"
        ).forEach { put(it, WriterClass.DERIVED_PRESENTATION) }

        listOf(
            "AutoRepairEngine.kt",
            "BackupManager.kt",
            "CampaignSelectionManager.kt",
            "CanonicalPackageReplacement.kt",
            "ContentUpdateManager.kt",
            "FilePickerBridge.kt",
            "LocalGameStore.kt",
            "MigrationManager.kt",
            "PackageManager.kt",
            "Phase6Migration.kt",
            "Phase7Migration.kt",
            "Phase8Migration.kt",
            "Phase9Migration.kt",
            "Phase9RequirementMigration.kt",
            "Phase10Migration.kt",
            "Phase11Migration.kt",
            "Phase12Migration.kt",
            "Phase13BalanceGuards.kt",
            "Phase13ContractGuards.kt",
            "Phase13Migration.kt",
            "Phase14Hardening.kt",
            "Phase14Migration.kt",
            "Phase15Hardening.kt",
            "Phase15Migration.kt",
            "RestoreManager.kt",
            "UpdateBackupManager.kt",
            "UpdateManager.kt"
        ).forEach { put(it, WriterClass.ADMINISTRATIVE_MIGRATION_RECOVERY) }

        put("GameplayMutationGate.kt", WriterClass.OPERATIONAL_GUARD)
        put("DiagnosticLogger.kt", WriterClass.OPERATIONAL_GUARD)
        put("AppSettings.kt", WriterClass.UI_SETTINGS)
    }

    private val durableWriteMarkers = listOf(
        ".execSQL(",
        ".insert(",
        ".insertOrThrow(",
        ".insertWithOnConflict(",
        ".update(",
        ".delete(",
        ".compileStatement(",
        "FileOutputStream(",
        ".copyTo(",
        ".writeText(",
        ".appendText(",
        ".renameTo(",
        ".deleteRecursively(",
        ".openOutputStream(",
        "getSharedPreferences("
    )

    @Test
    fun everyProductionDurableWriterSourceIsExplicitlyClassifiedAndInventoryHasNoStaleEntries() {
        val sourceDir = productionSourceDir()
        val actualWriterFiles = sourceDir.listFiles { file -> file.isFile && file.extension == "kt" }
            .orEmpty()
            .filter { containsDurableWriteSink(it.readText()) }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            "production durable writer source inventory drift; classify every new writer explicitly",
            classifiedWriterFiles.keys.toSortedSet(),
            actualWriterFiles
        )

        classifiedWriterFiles.forEach { (fileName, writerClass) ->
            val source = File(sourceDir, fileName)
            assertTrue("classified writer source disappeared: $fileName", source.isFile)
            assertTrue("stale writer classification without durable sink: $fileName", containsDurableWriteSink(source.readText()))
            assertTrue("writer class missing for $fileName", writerClass.name.isNotBlank())
        }
    }

    @Test
    fun canonicalDomainEvidenceAndAdministrativeWritersRemainDistinctClasses() {
        val canonical = classifiedWriterFiles.filterValues { it == WriterClass.CANONICAL_DOMAIN }.keys
        val evidence = classifiedWriterFiles.filterValues { it == WriterClass.APPEND_ONLY_EVIDENCE }.keys
        val admin = classifiedWriterFiles.filterValues { it == WriterClass.ADMINISTRATIVE_MIGRATION_RECOVERY }.keys

        assertTrue(canonical.isNotEmpty())
        assertEquals(
            setOf("CampaignEventStore.kt", "CampaignCausalGraph.kt", "TurnTransactionReceiptStore.kt"),
            evidence
        )
        assertTrue("RestoreManager must remain administrative", "RestoreManager.kt" in admin)
        assertTrue("migration manager must remain administrative", "MigrationManager.kt" in admin)
        assertTrue("LocalGameStore must remain explicitly audited infrastructure", "LocalGameStore.kt" in admin)
        assertTrue(canonical.intersect(evidence).isEmpty())
        assertTrue(canonical.intersect(admin).isEmpty())
        assertTrue(evidence.intersect(admin).isEmpty())
    }

    private fun containsDurableWriteSink(source: String): Boolean = durableWriteMarkers.any(source::contains)

    private fun productionSourceDir(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val candidates = generateSequence(start) { current -> current.parentFile }
            .flatMap { base ->
                sequenceOf(
                    File(base, "app/src/main/java/com/rpgos/app"),
                    File(base, "src/main/java/com/rpgos/app")
                )
            }
            .toList()
        return candidates.firstOrNull { it.isDirectory }
            ?: error("RPGOS-G32:PRODUCTION_SOURCE_DIRECTORY_NOT_FOUND from ${start.absolutePath}")
    }
}
