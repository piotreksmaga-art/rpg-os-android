package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetLiabilityConcurrencyTest {
    private lateinit var f: File

    @Before
    fun setUp() {
        f = File.createTempFile("p14-race-", ".db")
        f.delete()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { d ->
            CurrentSchema.ensure(d, "C")
            val r = OwnershipReferenceRegistry(d, "C")
            listOf("A", "B").forEach {
                r.registerOwner(OwnershipOwnerRef("CHARACTER", it), "race")
            }
            FinancialStore(d, "C").registerCurrency(
                CurrencyDefinition("CUR", "cur", "Currency", 1, "race")
            )
        }
    }

    @After
    fun tearDown() {
        f.delete()
    }

    private fun p(x: String) = OwnershipOwnerRef("CHARACTER", x)

    private data class R(val ok: Int, val bad: Int)

    private fun race(
        a: (SQLiteDatabase, AssetLiabilityStore) -> Unit,
        b: (SQLiteDatabase, AssetLiabilityStore) -> Unit
    ): R {
        val d1 = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        val d2 = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        val s1 = AssetLiabilityStore(d1, "C")
        val s2 = AssetLiabilityStore(d2, "C")
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val ok = AtomicInteger()
        val bad = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)

        fun submit(
            d: SQLiteDatabase,
            s: AssetLiabilityStore,
            op: (SQLiteDatabase, AssetLiabilityStore) -> Unit
        ) = pool.submit {
            ready.countDown()
            go.await()
            try {
                op(d, s)
                ok.incrementAndGet()
            } catch (_: Throwable) {
                bad.incrementAndGet()
            }
        }

        val x = submit(d1, s1, a)
        val y = submit(d2, s2, b)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        go.countDown()
        x.get(15, TimeUnit.SECONDS)
        y.get(15, TimeUnit.SECONDS)
        pool.shutdownNow()
        d1.close()
        d2.close()
        return R(ok.get(), bad.get())
    }

    private fun seedAsset(uid: String = "ASSET") {
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            AssetLiabilityStore(d, "C").createAsset(
                AssetRecord("C", uid, ASSET_KIND_PROPERTY, 1, "race")
            )
        }
    }

    private fun seedObligation(uid: String = "OBL", principal: Long = 100) {
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            AssetLiabilityStore(d, "C").createObligation(
                ObligationRecord(
                    "C",
                    uid,
                    "RPGOS-OBLIGATION-TYPE:DEBT",
                    ObligationClass.DEBT,
                    p("A"),
                    p("B"),
                    1,
                    "race",
                    currencyUid = "CUR",
                    principalMinor = principal
                ),
                "ACTIVE-$uid"
            )
        }
    }

    @Test
    fun p14Race01ConcurrentSameAssetIdentityHasOneCanonicalCreation() {
        val r = race(
            { _, s -> s.createAsset(AssetRecord("C", "SAME", ASSET_KIND_PROPERTY, 1, "race")) },
            { _, s -> s.createAsset(AssetRecord("C", "SAME", ASSET_KIND_PROPERTY, 1, "race")) }
        )
        assertEquals(2, r.ok + r.bad)
        assertTrue(r.ok >= 1)
        check { d ->
            assertEquals(1L, n(d, "SELECT COUNT(*) FROM asset_records WHERE asset_uid='SAME'"))
            checks(d)
        }
    }

    @Test
    fun p14Race02CompetingSettlementsCannotOverSettle() {
        seedObligation()
        val r = race(
            { _, s ->
                s.settle(ObligationSettlement("C", "S1", "OBL", SettlementKind.FORGIVENESS, 2, "race", 80))
            },
            { _, s ->
                s.settle(ObligationSettlement("C", "S2", "OBL", SettlementKind.WRITE_OFF, 2, "race", 80))
            }
        )
        assertEquals(1, r.ok)
        assertEquals(1, r.bad)
        check { d ->
            val s = AssetLiabilityStore(d, "C")
            assertEquals(20L, s.outstandingMinor("OBL"))
            assertEquals(1L, n(d, "SELECT COUNT(*) FROM obligation_settlements"))
            checks(d)
        }
    }

    @Test
    fun p14Race03SameValuationBasisCannotForkAuthority() {
        seedAsset()
        val r = race(
            { _, s ->
                s.recordValuation(
                    AssetValuation(
                        "C",
                        "V1",
                        OwnedAssetRef(ASSET_KIND_PROPERTY, "ASSET"),
                        "CUR",
                        100,
                        ValuationType.MARKET,
                        2,
                        "one"
                    )
                )
            },
            { _, s ->
                s.recordValuation(
                    AssetValuation(
                        "C",
                        "V2",
                        OwnedAssetRef(ASSET_KIND_PROPERTY, "ASSET"),
                        "CUR",
                        200,
                        ValuationType.MARKET,
                        2,
                        "two"
                    )
                )
            }
        )
        assertEquals(1, r.ok)
        assertEquals(1, r.bad)
        check { d ->
            assertEquals(1L, n(d, "SELECT COUNT(*) FROM asset_valuations"))
            checks(d)
        }
    }

    @Test
    fun p14Race04PartyRetirementVersusNewObligationHasOneCoherentWinner() {
        val r = race(
            { d, _ -> OwnershipReferenceRegistry(d, "C").retireOwner(p("A"), "race retire") },
            { _, s ->
                s.createObligation(
                    ObligationRecord(
                        "C",
                        "OBL-X",
                        "RPGOS-OBLIGATION-TYPE:DEBT",
                        ObligationClass.DEBT,
                        p("A"),
                        p("B"),
                        1,
                        "race",
                        currencyUid = "CUR",
                        principalMinor = 5
                    ),
                    "ACTIVE-X"
                )
            }
        )
        assertEquals(1, r.ok)
        assertEquals(1, r.bad)
        check { d ->
            val live = n(d, "SELECT COUNT(*) FROM obligation_records WHERE obligation_uid='OBL-X'")
            val active = n(
                d,
                "SELECT COUNT(*) FROM ownership_party_registry WHERE campaign_id='C' AND owner_uid='A' AND reference_status='ACTIVE'"
            )
            assertTrue((live == 1L && active == 1L) || (live == 0L && active == 0L))
            checks(d)
        }
    }

    @Test
    fun p14Race05CompetingTerminalStatusEventsCannotBothCommit() {
        seedObligation()
        val r = race(
            { _, s -> s.changeObligationStatus("OBL", "ST-D", ObligationStatus.DEFAULTED, 2, "race") },
            { _, s -> s.changeObligationStatus("OBL", "ST-C", ObligationStatus.CANCELLED, 2, "race") }
        )
        assertEquals(1, r.ok)
        assertEquals(1, r.bad)
        check { d ->
            assertEquals(
                2L,
                n(d, "SELECT COUNT(*) FROM obligation_status_history WHERE obligation_uid='OBL'")
            )
            checks(d)
        }
    }

    @Test
    fun p14Race06EncumbranceReleaseIsSingleCasTransition() {
        seedAsset()
        seedObligation()
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            AssetLiabilityStore(d, "C").addEncumbrance(
                "ENC",
                OwnedAssetRef(ASSET_KIND_PROPERTY, "ASSET"),
                "OBL",
                "LIEN",
                0,
                2,
                "race"
            )
        }
        val r = race(
            { _, s -> s.releaseEncumbrance("ENC", 3, "one") },
            { _, s -> s.releaseEncumbrance("ENC", 4, "two") }
        )
        assertEquals(1, r.ok)
        assertEquals(1, r.bad)
        check { d ->
            assertEquals(
                1L,
                n(
                    d,
                    "SELECT COUNT(*) FROM asset_encumbrances WHERE encumbrance_uid='ENC' AND released_order IS NOT NULL AND record_version=2"
                )
            )
            checks(d)
        }
    }

    private fun check(block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    }

    private fun n(d: SQLiteDatabase, sql: String) =
        d.rawQuery(sql, null).use { c ->
            c.moveToFirst()
            c.getLong(0)
        }

    private fun checks(d: SQLiteDatabase) {
        d.rawQuery("PRAGMA integrity_check", null).use { c ->
            c.moveToFirst()
            assertEquals("ok", c.getString(0))
        }
        d.rawQuery("PRAGMA foreign_key_check", null).use { c ->
            assertFalse(c.moveToFirst())
        }
    }
}
