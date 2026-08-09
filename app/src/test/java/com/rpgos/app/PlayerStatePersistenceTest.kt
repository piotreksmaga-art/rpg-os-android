package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerStatePersistenceTest {
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        dbFile = File.createTempFile("rpgos-player-state-", ".db")
        if (dbFile.exists()) dbFile.delete()
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun activePlayerPersistsAcrossCloseAndReopen() {
        open().use { db ->
            createActivePlayerTable(db)
            createSkillsTable(db)
            insertSkill(db, "PLAYER-A", "s1", 10)
            ActivePlayerStore(db, "campaign-a").set("PLAYER-A")
        }

        open().use { db ->
            val ref = ActivePlayerStore(db, "campaign-a").active()
            assertEquals("PLAYER-A", ref?.playerUid)
            assertEquals("campaign-a", ref?.campaignId)
        }
    }

    @Test
    fun nonExistingPlayerUidIsRejectedBeforePersistence() {
        open().use { db ->
            createActivePlayerTable(db)
            createSkillsTable(db)
            insertSkill(db, "PLAYER-A", "s1", 10)
            try {
                ActivePlayerStore(db, "campaign-a").set("MISSING")
                fail("Expected invalid player UID to be rejected")
            } catch (_: IllegalArgumentException) {
                // expected
            }
            assertNull(ActivePlayerStore(db, "campaign-a").active())
        }
    }

    @Test
    fun ambiguousLegacySeedDoesNotCreateAuthoritativeIdentity() {
        open().use { db ->
            createActivePlayerTable(db)
            createSkillsTable(db)
            createTechniquesTable(db)
            insertSkill(db, "ENTITY-A", "s1", 10)
            insertTechnique(db, "ENTITY-B", "t1", 10)

            val seeded = ActivePlayerStore(db, "campaign-a").seedFromLegacyIfMissing()
            assertNull(seeded)
            assertNull(ActivePlayerStore(db, "campaign-a").active())
        }
    }

    @Test
    fun corroboratedLegacySeedResolvesOnePlayer() {
        open().use { db ->
            createActivePlayerTable(db)
            createSkillsTable(db)
            createTechniquesTable(db)
            insertSkill(db, "PLAYER-A", "s1", 10)
            insertTechnique(db, "PLAYER-A", "t1", 10)
            insertSkill(db, "NPC-X", "s2", 5)

            val seeded = ActivePlayerStore(db, "campaign-a").seedFromLegacyIfMissing()
            assertEquals("PLAYER-A", seeded?.playerUid)
        }
    }

    @Test
    fun playerStateDoesNotSilentlyTruncateMoreThanOneHundredSkills() {
        open().use { db ->
            createActivePlayerTable(db)
            createSkillsTable(db)
            for (i in 1..125) insertSkill(db, "PLAYER-A", "skill-$i", i)
            ActivePlayerStore(db, "campaign-a").set("PLAYER-A")

            val state = PlayerStateStore(db, "campaign-a").load()
            val skills = state?.persistent?.get("skills") as List<*>
            assertEquals(125, skills.size)
        }
    }

    @Test
    fun playerStateIsIsolatedToActivePlayer() {
        open().use { db ->
            createActivePlayerTable(db)
            createSkillsTable(db)
            insertSkill(db, "PLAYER-A", "a-only", 10)
            insertSkill(db, "PLAYER-B", "b-only", 20)
            ActivePlayerStore(db, "campaign-a").set("PLAYER-A")

            val state = PlayerStateStore(db, "campaign-a").load()
            val skills = state?.persistent?.get("skills") as List<Map<String, Any?>>
            assertEquals(1, skills.size)
            assertEquals("PLAYER-A", skills.single()["entity_uid"])
            assertEquals("a-only", skills.single()["skill_uid"])
        }
    }

    @Test
    fun playerIdentityPolicyRejectsTiedMultiEntityLegacyData() {
        assertNull(PlayerIdentityPolicy.resolveUnambiguous(mapOf("A" to 2, "B" to 2)))
        assertNull(PlayerIdentityPolicy.resolveUnambiguous(mapOf("A" to 1, "B" to 1)))
        assertEquals("A", PlayerIdentityPolicy.resolveUnambiguous(mapOf("A" to 3, "B" to 1)))
        assertEquals("A", PlayerIdentityPolicy.resolveUnambiguous(mapOf("A" to 1)))
    }

    private fun open(): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(dbFile, null)

    private fun createActivePlayerTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE active_player_ref(
                campaign_id TEXT PRIMARY KEY,
                player_uid TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )"""
        )
    }

    private fun createSkillsTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE character_skills(
                entity_uid TEXT NOT NULL,
                skill_uid TEXT NOT NULL,
                mastery INTEGER NOT NULL,
                xp INTEGER NOT NULL DEFAULT 0,
                updated_chapter INTEGER
            )"""
        )
    }

    private fun createTechniquesTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE character_techniques(
                entity_uid TEXT NOT NULL,
                technique_uid TEXT NOT NULL,
                mastery INTEGER NOT NULL,
                xp INTEGER NOT NULL DEFAULT 0,
                is_equipped INTEGER NOT NULL DEFAULT 0,
                learned_chapter INTEGER,
                last_used_chapter INTEGER,
                usage_count INTEGER NOT NULL DEFAULT 0,
                success_count INTEGER NOT NULL DEFAULT 0,
                failure_count INTEGER NOT NULL DEFAULT 0,
                notes TEXT
            )"""
        )
    }

    private fun insertSkill(db: SQLiteDatabase, entityUid: String, skillUid: String, mastery: Int) {
        db.execSQL(
            "INSERT INTO character_skills(entity_uid,skill_uid,mastery,xp) VALUES(?,?,?,?)",
            arrayOf(entityUid, skillUid, mastery, mastery * 10)
        )
    }

    private fun insertTechnique(db: SQLiteDatabase, entityUid: String, techniqueUid: String, mastery: Int) {
        db.execSQL(
            "INSERT INTO character_techniques(entity_uid,technique_uid,mastery,xp,is_equipped) VALUES(?,?,?,?,0)",
            arrayOf(entityUid, techniqueUid, mastery, mastery * 10)
        )
    }
}
