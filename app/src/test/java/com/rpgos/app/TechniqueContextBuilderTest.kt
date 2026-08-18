package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TechniqueContextBuilderTest {
    private lateinit var root: File
    private lateinit var campaignDir: File
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        root = createTempDir(prefix = "rpgos-phase8-context-")
        campaignDir = File(root, "phase8-context.campaign").apply { mkdirs() }
        dbFile = File(campaignDir, "game.db")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun contextBuilderUsesFullReconciledTechniqueSetWithoutLegacyLimit() {
        val campaignId = "phase8-context"
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("CREATE TABLE technique_definitions(technique_uid TEXT PRIMARY KEY,name TEXT,category TEXT,base_chakra_cost REAL)")
            db.execSQL("CREATE TABLE character_techniques(entity_uid TEXT,technique_uid TEXT,mastery REAL,xp REAL,learned_chapter INTEGER,last_used_chapter INTEGER,usage_count INTEGER,success_count INTEGER,failure_count INTEGER,is_equipped INTEGER,notes TEXT,chakra_cost_override REAL)")
            db.execSQL("INSERT INTO character_techniques VALUES('P','legacy-orphan',7.0,3.0,1,NULL,2,1,1,0,'legacy note',NULL)")

            MigrationManager().ensureV8(db, campaignId)
            assertEquals("P", ActivePlayerStore(db, campaignId).requireActive().playerUid)

            val store = TechniqueStore(db, campaignId)
            val definitions = (0 until 1001).map { index ->
                val uid = "T%04d".format(index)
                TechniqueDefinition(
                    techniqueUid = uid,
                    worldPackUid = "W",
                    key = uid.lowercase(),
                    displayName = "Technique $uid",
                    category = "generic",
                    provenance = "pack"
                )
            }
            store.registerDefinitions("W", definitions)
            definitions.forEachIndexed { index, definition ->
                store.savePlayerTechnique(
                    PlayerTechnique(
                        campaignId = campaignId,
                        characterUid = "P",
                        techniqueUid = definition.techniqueUid,
                        baseMastery = index.toDouble(),
                        provenance = "bulk"
                    )
                )
            }

            assertEquals(1001, store.playerTechniques("P").size)
            assertEquals(1001, store.reconciled("P").techniques.size)
            assertEquals(1, store.reconciled("P").unresolvedLegacy.size)

            GameplayRuntimeBootstrap.ensureReady(db, campaignId)
            GameplayRuntimeBootstrap.requireReady(db, campaignId)

            val context = ContextBuilder(db, db).build("status", 1)
            assertEquals(1002, context.playerTechniques.size)
            assertEquals(1001, context.playerTechniques.count { it["canonical"] == true })
            assertEquals(1, context.playerTechniques.count { it["canonical"] == false })
            assertTrue(context.playerTechniques.any { it["technique_uid"] == "T1000" })
            val legacy = context.playerTechniques.single { it["technique_uid"] == "legacy-orphan" }
            assertEquals("LEGACY_UNRESOLVED", legacy["authority_source"])
            assertEquals("7.0", legacy["mastery_raw"])
            assertFalse(legacy["canonical"] as Boolean)
        }
    }
}
