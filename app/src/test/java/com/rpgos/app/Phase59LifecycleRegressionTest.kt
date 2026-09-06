package com.rpgos.app

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase59LifecycleRegressionTest {
    @Test
    fun closeIsTerminalAndPreviouslyObtainedPortCannotReopenRuntime() {
        val root = File.createTempFile("phase59-terminal-close-", ".tmp").apply {
            delete()
            mkdirs()
        }
        val base = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
        }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("rpgos_bekko_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val repository = UnifiedGameRepository(context).also { it.bootstrap() }
        val application = BekkoSemanticApplication(context, repository)

        try {
            val campaign = repository.activeCampaignRef().campaignId
            val retainedPort = application.futureCandidatePorts().memoryConsolidation
            val request = SemanticSearchRequest(
                campaignUid = campaign,
                namespaceUid = SEMANTIC_NAMESPACE_CAMPAIGN,
                audienceUid = AudienceKinds.PLAYER,
                purposeUid = VisibilityPurposeKinds.GAMEPLAY_NARRATION,
                asOfOrder = Long.MAX_VALUE,
                authorizedRecordUids = setOf("REGRESSION-MISSING-RECORD"),
                queryVector = FloatArray(256).also { it[0] = 1f },
                topK = 1,
                minimumScore = -1f
            )

            retainedPort.candidates(request)
            assertNotNull("The pre-close call must exercise a real runtime", runtimeOf(application))

            application.close()
            assertNull("close() must dispose the active runtime", runtimeOf(application))

            // A composition root can retain this adapter after its owning application is closed.
            // Whether the stale call returns a fallback or rejects it is secondary: it must never
            // recreate native/index resources owned by the terminal application instance.
            runCatching { retainedPort.candidates(request) }
            assertNull("A retained port must not reopen runtime after close()", runtimeOf(application))
        } finally {
            (runtimeOf(application) as? AutoCloseable)?.let { runCatching { it.close() } }
            runCatching { application.close() }
            repository.closeBackgroundWorkForTest()
            context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("rpgos_bekko_settings", Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }

    @Test
    fun storageTransitionCancelsActiveCatchUpThenWaitsExclusivelyWithoutReaderBarging() {
        val activeCatchUpEntered = CountDownLatch(1)
        val releaseActiveCatchUp = CountDownLatch(1)
        val cancellationSignalled = CountDownLatch(1)
        val exclusiveCloseEntered = CountDownLatch(1)
        val storageTransitionEntered = CountDownLatch(1)
        val releaseStorageTransition = CountDownLatch(1)
        val lateReaderAttempted = CountDownLatch(1)
        val lateReaderEntered = CountDownLatch(1)
        val activeCatchUpInsideLease = AtomicBoolean(false)
        val cancellationPrecededExclusiveAccess = AtomicBoolean(false)
        val failures = ConcurrentLinkedQueue<Throwable>()

        val closeListener: () -> Unit = { exclusiveCloseEntered.countDown() }
        val cancellationListener: () -> Unit = {
            cancellationPrecededExclusiveAccess.set(
                activeCatchUpInsideLease.get() && exclusiveCloseEntered.count == 1L
            )
            cancellationSignalled.countDown()
        }
        SemanticCampaignTransitionRegistry.registerAndReload(closeListener, cancellationListener) {}

        var activeCatchUp: Thread? = null
        var transition: Thread? = null
        var lateReader: Thread? = null
        try {
            activeCatchUp = testThread("phase59-active-catch-up", failures) {
                SemanticCampaignTransitionRegistry.withSemanticRuntimeAccess {
                    activeCatchUpInsideLease.set(true)
                    activeCatchUpEntered.countDown()
                    check(releaseActiveCatchUp.await(5, TimeUnit.SECONDS)) {
                        "TEST_ACTIVE_CATCH_UP_RELEASE_TIMEOUT"
                    }
                    activeCatchUpInsideLease.set(false)
                }
            }
            assertTrue(activeCatchUpEntered.await(5, TimeUnit.SECONDS))

            transition = testThread("phase59-storage-transition", failures) {
                SemanticCampaignTransitionRegistry.withCampaignStorageTransition {
                    storageTransitionEntered.countDown()
                    check(releaseStorageTransition.await(5, TimeUnit.SECONDS)) {
                        "TEST_STORAGE_TRANSITION_RELEASE_TIMEOUT"
                    }
                }
            }

            assertTrue(
                "The active catch-up must receive cancellation before the transition waits for exclusive access",
                cancellationSignalled.await(5, TimeUnit.SECONDS)
            )
            assertTrue(cancellationPrecededExclusiveAccess.get())
            assertFalse(
                "Storage close must still wait for the active semantic lease",
                exclusiveCloseEntered.await(150, TimeUnit.MILLISECONDS)
            )
            assertTrue(
                "The transition writer should be queued behind the active catch-up",
                waitUntil(5_000) {
                    transition?.state == Thread.State.WAITING || transition?.state == Thread.State.TIMED_WAITING
                }
            )

            lateReader = testThread("phase59-late-semantic-reader", failures) {
                lateReaderAttempted.countDown()
                SemanticCampaignTransitionRegistry.withSemanticRuntimeAccess {
                    lateReaderEntered.countDown()
                }
            }
            assertTrue(lateReaderAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(
                "A reader arriving after a pending storage transition must not barge",
                lateReaderEntered.await(150, TimeUnit.MILLISECONDS)
            )

            releaseActiveCatchUp.countDown()
            assertTrue(exclusiveCloseEntered.await(5, TimeUnit.SECONDS))
            assertTrue(storageTransitionEntered.await(5, TimeUnit.SECONDS))
            assertFalse(
                "The queued transition must acquire exclusivity before the late reader",
                lateReaderEntered.await(150, TimeUnit.MILLISECONDS)
            )

            releaseStorageTransition.countDown()
            assertTrue(lateReaderEntered.await(5, TimeUnit.SECONDS))
            listOf(activeCatchUp, transition, lateReader).forEach { thread ->
                thread?.join(5_000)
                assertFalse("Lifecycle test thread did not terminate: ${thread?.name}", thread?.isAlive == true)
            }
            assertTrue("Worker failure(s): ${failures.joinToString()}", failures.isEmpty())
        } finally {
            releaseActiveCatchUp.countDown()
            releaseStorageTransition.countDown()
            listOf(activeCatchUp, transition, lateReader).forEach { it?.join(5_000) }
            SemanticCampaignTransitionRegistry.unregisterAndClose(closeListener, cancellationListener) {}
        }
    }

    private fun runtimeOf(application: BekkoSemanticApplication): Any? =
        BekkoSemanticApplication::class.java.getDeclaredField("runtime").let { field ->
            field.isAccessible = true
            field.get(application)
        }

    private fun testThread(
        name: String,
        failures: ConcurrentLinkedQueue<Throwable>,
        block: () -> Unit
    ): Thread = Thread({ runCatching(block).exceptionOrNull()?.let(failures::add) }, name).apply { start() }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.yield()
        }
        return condition()
    }
}
