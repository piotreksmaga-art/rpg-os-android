package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CanonCharacterProjectionReaderTest {
    private val audience = VisibilityAudienceFactory.player("C")
    private val purpose = PurposeContext("C", VisibilityPurposeKinds.PLAYER_UI)

    @Test fun optionalEngineApiOneProfileColumnsDefaultExplicitlyWithoutMaskingIdentity() {
        SQLiteDatabase.create(null).use { world ->
            SQLiteDatabase.create(null).use { save ->
                world.execSQL(
                    """CREATE TABLE canon_characters_v2(
                        character_uid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        clan_uid TEXT,
                        village_uid TEXT,
                        rank_title TEXT
                    )"""
                )
                world.execSQL(
                    "INSERT INTO canon_characters_v2(character_uid,name,clan_uid) VALUES('A','Alpha','CLAN-A')"
                )
                val reader = NpcWorldDashboardReader(world, save)

                val list = reader.npcsProjection("", audience, purpose)
                assertEquals(ProjectionDataState.DISCLOSED, list.dataState)
                assertEquals(NpcListItem("A", "Alpha", "CLAN-A", "", ""), list.value!!.single())

                val detail = reader.npcDetailProjection("A", audience, purpose)
                assertEquals(ProjectionDataState.DISCLOSED, detail.profile.dataState)
                val fields = detail.profile.value!!.associate { it.key to it.value }
                assertEquals("A", fields["character_uid"])
                assertEquals("Alpha", fields["name"])
                assertEquals("", fields["sex"])
                assertEquals("", fields["affiliation_summary"])
                assertEquals("", fields["status"])
                assertEquals(ProjectionDataState.DENIED, detail.memories.dataState)
            }
        }
    }

    @Test fun missingRequiredIdentityColumnIsTypedCorruption() {
        SQLiteDatabase.create(null).use { world ->
            SQLiteDatabase.create(null).use { save ->
                world.execSQL("CREATE TABLE canon_characters_v2(character_uid TEXT PRIMARY KEY, status TEXT)")
                world.execSQL("INSERT INTO canon_characters_v2(character_uid,status) VALUES('BROKEN','active')")

                val projection = NpcWorldDashboardReader(world, save).npcsProjection("", audience, purpose)

                assertEquals(ProjectionDataState.CORRUPTION, projection.dataState)
                assertNull(projection.value)
                assertNotNull(projection.decision.reasonCode)
                assertTrue(projection.decision.level == DisclosureLevel.DENY)
            }
        }
    }
}
