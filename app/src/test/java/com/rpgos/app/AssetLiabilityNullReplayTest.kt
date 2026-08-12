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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetLiabilityNullReplayTest {
    private lateinit var f: File

    @Before
    fun setUp() {
        f = File.createTempFile("p14-null-replay-", ".db")
        f.delete()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { d ->
            CurrentSchema.ensure(d, "C")
            val refs = OwnershipReferenceRegistry(d, "C")
            listOf("A", "B").forEach { refs.registerOwner(p(it), "null-replay") }
            FinancialStore(d, "C").registerCurrency(CurrencyDefinition("CUR", "cur", "Currency", 1, "null-replay"))
        }
    }

    @After
    fun tearDown() { f.delete() }

    private fun p(uid: String) = OwnershipOwnerRef("CHARACTER", uid)

    private fun seedObligation(uid: String = "OBL") {
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            AssetLiabilityStore(d, "C").createObligation(
                ObligationRecord(
                    "C", uid, "RPGOS-OBLIGATION-TYPE:DEBT", ObligationClass.DEBT,
                    p("A"), p("B"), 1, "null-replay", currencyUid = "CUR", principalMinor = 100
                ),
                "ACTIVE-$uid"
            )
        }
    }

    @Test
    fun sequentialExactStatusEventReplayWithNullSourceEventIsIdempotentAndConflictIsRejected() {
        seedObligation()
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            val s = AssetLiabilityStore(d, "C")
            s.changeObligationStatus("OBL", "STATUS-NULL", ObligationStatus.DEFAULTED, 2, "status-null", null)
            s.changeObligationStatus("OBL", "STATUS-NULL", ObligationStatus.DEFAULTED, 2, "status-null", null)

            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM obligation_status_history WHERE campaign_id='C' AND status_event_uid='STATUS-NULL'"))
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM obligation_status_history WHERE campaign_id='C' AND status_event_uid='STATUS-NULL' AND source_event_uid IS NULL"))

            var rejected = false
            try {
                s.changeObligationStatus("OBL", "STATUS-NULL", ObligationStatus.DEFAULTED, 2, "status-null", "DIFFERENT-SOURCE")
            } catch (_: Throwable) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM obligation_status_history WHERE campaign_id='C' AND status_event_uid='STATUS-NULL'"))
            checks(d)
        }
    }

    @Test
    fun concurrentExactStatusEventReplayWithNullSourceEventConvergesAcrossConnections() {
        seedObligation()
        val d1 = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        val d2 = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        val s1 = AssetLiabilityStore(d1, "C")
        val s2 = AssetLiabilityStore(d2, "C")
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        val ok = AtomicInteger()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val pool = Executors.newFixedThreadPool(2)

        fun submit(store: AssetLiabilityStore) = pool.submit {
            ready.countDown()
            go.await()
            try {
                store.changeObligationStatus("OBL", "STATUS-NULL-RACE", ObligationStatus.DEFAULTED, 2, "status-null-race", null)
                ok.incrementAndGet()
            } catch (t: Throwable) {
                errors.add(t)
            }
        }

        try {
            val a = submit(s1)
            val b = submit(s2)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            a.get(15, TimeUnit.SECONDS)
            b.get(15, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            d1.close()
            d2.close()
        }

        if (errors.isNotEmpty()) {
            val e = errors.first()
            throw AssertionError("null status replay worker failed: ${e.javaClass.name}: ${e.message}", e)
        }
        assertEquals(2, ok.get())
        assertEquals(0, errors.size)

        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM obligation_status_history WHERE campaign_id='C' AND status_event_uid='STATUS-NULL-RACE'"))
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM obligation_status_history WHERE campaign_id='C' AND status_event_uid='STATUS-NULL-RACE' AND source_event_uid IS NULL"))
            checks(d)
        }
    }

    @Test
    fun assetKindExactReplayWithNullWorldPackIsIdempotentAndConflictIsRejected() {
        SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { d ->
            val s = AssetLiabilityStore(d, "C")
            val kind = AssetKindDefinition(
                assetKindUid = "RPGOS-ASSET-KIND:NULL-WORLD-PACK",
                assetClass = AssetClass.OTHER,
                displayName = "Null World Pack",
                worldPackUid = null,
                provenance = "null-replay"
            )
            s.registerAssetKind(kind)
            s.registerAssetKind(kind)
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM asset_kind_definitions WHERE asset_kind_uid='RPGOS-ASSET-KIND:NULL-WORLD-PACK'"))

            var rejected = false
            try {
                s.registerAssetKind(kind.copy(worldPackUid = "WP-CONFLICT"))
            } catch (_: Throwable) {
                rejected = true
            }
            assertTrue(rejected)
            assertEquals(1L, scalar(d, "SELECT COUNT(*) FROM asset_kind_definitions WHERE asset_kind_uid='RPGOS-ASSET-KIND:NULL-WORLD-PACK' AND world_pack_uid IS NULL"))
            checks(d)
        }
    }

    private fun scalar(d: SQLiteDatabase, sql: String): Long = d.rawQuery(sql, null).use { c ->
        c.moveToFirst()
        c.getLong(0)
    }

    private fun checks(d: SQLiteDatabase) {
        d.rawQuery("PRAGMA integrity_check", null).use { c ->
            c.moveToFirst()
            assertEquals("ok", c.getString(0))
        }
        d.rawQuery("PRAGMA foreign_key_check", null).use { c -> assertFalse(c.moveToFirst()) }
    }
}
