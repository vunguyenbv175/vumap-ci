package vn.vumap.feature.tpms.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding (PR #1): scan windows must not outlive stop/release and a quick
 * restart must not leave overlapping scans or accept results from a dead window.
 *
 * The platform scanner is a fake, so the stop / release / restart-during-a-window
 * sequences are host-testable without a device.
 */
class BleScanWindowTest {

    private class FakeScanner {
        val started = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        fun start(callback: String) {
            started += callback
        }

        fun stop(callback: String) {
            stopped += callback
        }
    }

    private fun newWindows(scanner: FakeScanner) = BleScanWindow<FakeScanner, String>(
        startScan = { s, callback -> s.start(callback) },
        stopScan = { s, callback -> s.stop(callback) },
    )

    @Test
    fun `open starts the platform scan and owns its generation`() {
        val scanner = FakeScanner()
        val windows = newWindows(scanner)

        val window = windows.open(scanner) { "cb$it" }

        assertEquals(listOf("cb1"), scanner.started)
        assertTrue(windows.isOpen)
        assertEquals(window.generation, windows.activeGeneration)
        assertTrue(windows.accepts(window.generation))
    }

    @Test
    fun `stop during a window unregisters it and rejects late results`() {
        val scanner = FakeScanner()
        val windows = newWindows(scanner)
        val window = windows.open(scanner) { "cb$it" }

        // stop() path: close the active window before returning.
        assertTrue(windows.closeActive())
        assertFalse(windows.isOpen)
        assertEquals(listOf("cb1"), scanner.stopped)
        // A result delivered after the stop must be dropped.
        assertFalse(windows.accepts(window.generation))
    }

    @Test
    fun `release closes the active window so no scan survives`() {
        val scanner = FakeScanner()
        val windows = newWindows(scanner)
        val window = windows.open(scanner) { "cb$it" }

        // release(): endScan() then scope cancel — the window is closed first.
        windows.closeActive()

        assertEquals(listOf("cb1"), scanner.stopped)
        assertFalse(windows.accepts(window.generation))
    }

    @Test
    fun `restart during a window stops the previous window before starting the next`() {
        val scanner = FakeScanner()
        val windows = newWindows(scanner)
        val first = windows.open(scanner) { "cb$it" }

        val second = windows.open(scanner) { "cb$it" }

        assertEquals(listOf("cb1"), scanner.stopped)
        assertEquals(listOf("cb1", "cb2"), scanner.started)
        assertFalse("first window must not keep owning the scan", windows.accepts(first.generation))
        assertTrue(windows.accepts(second.generation))
        assertEquals(second.generation, windows.activeGeneration)
    }

    @Test
    fun `closing a superseded window is a no-op and never stops the active scan`() {
        val scanner = FakeScanner()
        val windows = newWindows(scanner)
        val first = windows.open(scanner) { "cb$it" }
        windows.open(scanner) { "cb$it" }

        // The loop's finally can race with stop()/restart; only one close may unregister.
        assertFalse(windows.close(first))
        assertEquals(listOf("cb1"), scanner.stopped)

        assertTrue(windows.closeActive())
        assertEquals(listOf("cb1", "cb2"), scanner.stopped)
    }

    @Test
    fun `a failed platform start leaves nothing registered and does not block the next window`() {
        val scanner = FakeScanner()
        val windows = BleScanWindow<FakeScanner, String>(
            startScan = { s, callback ->
                if (callback == "cb1") throw IllegalStateException("scan refused")
                s.start(callback)
            },
            stopScan = { s, callback -> s.stop(callback) },
        )

        runCatching { windows.open(scanner) { "cb$it" } }

        assertFalse(windows.isOpen)
        assertNull(windows.activeGeneration)

        val retry = windows.open(scanner) { "cb$it" }
        assertEquals(listOf("cb2"), scanner.started)
        assertTrue(windows.accepts(retry.generation))
    }
}
