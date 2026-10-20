package vn.vumap.feature.tpms.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding (PR #1, round 2): cancellation cleanup of an OLD scan loop can finish
 * after stop() already let a replacement scan start — it must then be a no-op, never an
 * unregistration of the newer session's window.
 *
 * This reproduces the interleaving the finding describes: pause the old job's cleanup,
 * start the replacement scan, then let the old cleanup run and verify the replacement
 * stays registered. Production code under test: [BleScanSession] (session-scoped
 * cleanup, as used by both transports' loops) on top of [BleScanWindow].
 */
class BleScanSessionTest {

    private class FakeScanner {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()
    }

    private fun newWindows() = BleScanWindow<FakeScanner, String>(
        startScan = { scanner, callback -> scanner.started += callback },
        stopScan = { scanner, callback -> scanner.stopped += callback },
    )

    @Test
    fun `old loop cleanup that runs after a restart cannot unregister the replacement scan`() =
        runBlocking {
            val scanner = FakeScanner()
            val windows = newWindows()

            // The old loop owns generation 1.
            val oldSession = BleScanSession(windows)
            oldSession.opened(windows.open(scanner) { "old$it" })

            val running = CompletableDeferred<Unit>()
            val cleanupGate = CompletableDeferred<Unit>()
            val cleanupDone = CompletableDeferred<Unit>()

            val oldJob = launch(Dispatchers.Default) {
                withContext(NonCancellable) {
                    running.complete(Unit)
                    // Paused here while the test performs stop() -> start().
                    cleanupGate.await()
                    oldSession.cleanupOwn()
                    cleanupDone.complete(Unit)
                }
            }

            // The loop is inside its window: stop() cancels it, but its cleanup has not
            // run yet (NonCancellable keeps the teardown going after cancellation).
            running.await()
            oldJob.cancel()

            // start(): a replacement scan registers before the old cleanup completes.
            val replacement = windows.open(scanner) { "new$it" }

            // Only now does the old job's cleanup resume.
            cleanupGate.complete(Unit)
            cleanupDone.await()
            oldJob.join()

            // The replacement's registration survived the old session's cleanup.
            assertTrue(windows.isOpen)
            assertEquals(replacement.generation, windows.activeGeneration)
            assertTrue(windows.accepts(replacement.generation))
            // The superseded window was unregistered exactly once (by the new open),
            // and the old session's late cleanup did not stop the replacement scan.
            assertEquals(listOf("old1"), scanner.stopped)
            assertEquals(listOf("old1", "new2"), scanner.started)
            // A second late cleanup is a no-op, and the old generation stays rejected.
            assertFalse(oldSession.cleanupOwn())
            assertFalse(windows.accepts(1L))
            assertEquals(listOf("old1"), scanner.stopped)
        }

    @Test
    fun `a session only ever closes the window it opened`() {
        val scanner = FakeScanner()
        val windows = newWindows()
        val sessionA = BleScanSession(windows)
        sessionA.opened(windows.open(scanner) { "a$it" })

        // A newer session supersedes it (what a restart does).
        val sessionB = BleScanSession(windows)
        sessionB.opened(windows.open(scanner) { "b$it" })

        // A's cleanup must be a no-op for B's window.
        assertFalse(sessionA.cleanupOwn())
        assertTrue(windows.isOpen)
        assertEquals(2L, windows.activeGeneration)

        // B's cleanup closes its own window.
        assertTrue(sessionB.cleanupOwn())
        assertFalse(windows.isOpen)
        assertEquals(listOf("a1", "b2"), scanner.stopped)
    }

    @Test
    fun `cleanup with no window ever opened is a harmless no-op`() {
        val scanner = FakeScanner()
        val windows = newWindows()
        val session = BleScanSession(windows)

        assertFalse(session.cleanupOwn())
        assertFalse(windows.isOpen)
        assertTrue(scanner.stopped.isEmpty())
    }
}
