package vn.vumap.feature.tpms.ble

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding (PR #1, rounds 3-4): GATT connection ownership, including its
 * execution model — scan results are dispatched on worker threads while stop()/release()
 * run on the caller's context, so admission, teardown and callback work must be atomic.
 *
 * Fake-platform coverage for: two simultaneous requests (barrier), teardown while a
 * connect is paused (barrier), admission after the session stopped, repeated results
 * for one address, a second device, late/terminated-session callbacks, and connection
 * failure. Production code under test: [GattConnection] (single lock covering
 * admission, closeSession/end, and the [GattConnection.ifOwned] callback gate).
 */
class GattConnectionTest {

    private class FakeHandle(val id: String, val address: String)

    /**
     * Fake platform: hands out handles and records the closes it was asked for. When
     * [pauseConnect] is set, connect blocks until it is released, which lets a test
     * hold a thread inside the admission critical section.
     */
    private class FakePlatform {
        private var nextHandleId = 0
        val connects = CopyOnWriteArrayList<String>()
        val closes = CopyOnWriteArrayList<String>()
        var refuseConnect = false

        @Volatile var pauseConnect: CountDownLatch? = null
        @Volatile var connectEntered: CountDownLatch? = null

        fun connect(address: String): FakeHandle? {
            connects += address
            connectEntered?.countDown()
            pauseConnect?.await()
            if (refuseConnect) return null
            return FakeHandle("h${++nextHandleId}", address)
        }

        fun close(handle: FakeHandle) {
            closes += handle.id
        }
    }

    private fun linkOf(platform: FakePlatform) = GattConnection<String, FakeHandle>(
        connect = platform::connect,
        close = platform::close,
    )

    // --- concurrency: simultaneous requests, teardown during connect ---

    @Test
    fun `two simultaneous scan results admit exactly one connection`() {
        val platform = FakePlatform()
        val link = linkOf(platform)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        platform.connectEntered = entered
        platform.pauseConnect = release

        val handles = CopyOnWriteArrayList<FakeHandle?>()
        val first = Thread { handles += link.request("AA:BB:CC:00:00:01") }
        val second = Thread { handles += link.request("AA:BB:CC:00:00:02") }
        first.start()
        // The first thread is now inside the admission critical section (platform connect
        // paused); the second one is queued behind it on the same lock.
        entered.await()
        second.start()
        release.countDown()
        first.join()
        second.join()
        platform.pauseConnect = null

        // At most one live connection: the loser was deduplicated, not double-opened.
        assertEquals(1, platform.connects.size)
        assertEquals(1, handles.count { it != null })
        assertEquals(1, handles.count { it == null })
        val winner = handles.firstNotNullOfOrNull { it }
        assertNotNull(winner)
        assertTrue(link.owns(winner!!))
        assertTrue(platform.closes.isEmpty())
    }

    @Test
    fun `teardown while connect is paused closes the handle that publish produced`() {
        val platform = FakePlatform()
        val link = linkOf(platform)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        platform.connectEntered = entered
        platform.pauseConnect = release

        val results = CopyOnWriteArrayList<FakeHandle?>()
        val requester = Thread { results += link.request("AA:BB:CC:00:00:01") }
        requester.start()
        entered.await() // requester holds the admission lock, platform connect paused

        val closedByTeardown = CopyOnWriteArrayList<Boolean>()
        val stopper = Thread { closedByTeardown += link.closeSession() }
        stopper.start() // blocks on the lock until the in-flight connect publishes
        release.countDown()
        requester.join()
        stopper.join()
        platform.pauseConnect = null

        // stop()/release() could not observe "no handle": whatever connect published is
        // closed before closeSession returns, so nothing leaks past the session.
        assertEquals(1, platform.connects.size)
        val published = results.firstNotNullOfOrNull { it }
        assertNotNull(published)
        assertEquals(listOf(published!!.id), platform.closes.toList())
        assertEquals(listOf(true), closedByTeardown.toList())
        assertFalse(link.isActive)
        assertNull(link.current)
    }

    @Test
    fun `a request that reaches admission after stop cannot publish a connection`() {
        val platform = FakePlatform()
        val link = linkOf(platform)

        link.closeSession()

        // The scan-result job resumed after stop(): admission is rejected outright and
        // the platform is never touched, so no handle can outlive the session.
        assertNull(link.request("AA:BB:CC:00:00:01"))
        assertEquals(0, platform.connects.size)
        assertFalse(link.isActive)
        assertNull(link.current)
        assertTrue(platform.closes.isEmpty())
    }

    @Test
    fun `a new session admits connections again after the stop one`() {
        val platform = FakePlatform()
        val link = linkOf(platform)
        val handle = link.request("AA:BB:CC:00:00:01")
        assertNotNull(handle)

        assertTrue(link.closeSession())
        assertTrue(link.isStopped)
        assertNull(link.request("AA:BB:CC:00:00:02"))

        link.openSession()
        assertFalse(link.isStopped)
        val second = link.request("AA:BB:CC:00:00:02")
        assertNotNull(second)
        assertTrue(link.owns(second!!))
    }

    // --- deduplication / identity ---

    @Test
    fun `second scan result from another device cannot replace an active connection`() {
        val platform = FakePlatform()
        val link = linkOf(platform)

        val first = link.request("AA:BB:CC:00:00:01")
        assertNotNull(first)

        // Another sensor advertises while the link is active: ignored, nothing closed.
        val second = link.request("AA:BB:CC:00:00:02")

        assertNull(second)
        assertEquals(listOf("AA:BB:CC:00:00:01"), platform.connects)
        assertTrue(platform.closes.isEmpty())
        assertEquals(first, link.current)
        assertEquals("AA:BB:CC:00:00:01", link.currentAddress)
        assertTrue(link.owns(first!!))
        assertFalse(link.owns(FakeHandle("h2", "AA:BB:CC:00:00:02")))
    }

    @Test
    fun `repeated results for one address open exactly one connection`() {
        val platform = FakePlatform()
        val link = linkOf(platform)

        val handle = link.request("AA:BB:CC:00:00:01")
        repeat(5) { link.request("AA:BB:CC:00:00:01") }

        assertEquals(listOf("AA:BB:CC:00:00:01"), platform.connects)
        assertTrue(link.owns(handle!!))
        assertTrue(platform.closes.isEmpty())
    }

    @Test
    fun `ownership is identity-based, not address-based`() {
        val platform = FakePlatform()
        val link = linkOf(platform)
        val handle = link.request("AA:BB:CC:00:00:01")!!

        // A different object for the same address is NOT the owned connection.
        assertFalse(link.owns(FakeHandle(handle.id, handle.address)))
        assertTrue(link.owns(handle))
    }

    // --- termination and late callbacks ---

    @Test
    fun `late callbacks from a dead connection are rejected after the link reconnects`() {
        val platform = FakePlatform()
        val link = linkOf(platform)

        val first = link.request("AA:BB:CC:00:00:01")!!
        // Disconnect/failure ends the link (handle closed, ownership released).
        assertTrue(link.end())
        assertEquals(listOf(first.id), platform.closes)

        // A new scan result now connects again.
        val second = link.request("AA:BB:CC:00:00:01")!!
        assertFalse(second.id == first.id)

        // Late callbacks carrying the OLD handle must do nothing.
        assertFalse(link.owns(first))
        assertNull(link.ifOwned(first) { "discovered" })
        assertNull(link.ifOwned(first) { "completed" })
        assertNull(link.ifOwned(first) { "packet published" })

        // While the new handle is accepted.
        assertTrue(link.owns(second))
        assertEquals("discovered", link.ifOwned(second) { "discovered" })
    }

    @Test
    fun `connection failure releases ownership so the next scan result may connect`() {
        val platform = FakePlatform()
        val link = linkOf(platform)

        link.request("AA:BB:CC:00:00:01")
        assertTrue(link.isActive)

        // Platform reports the link dead (transport calls end() from the callback).
        assertTrue(link.end())
        assertFalse(link.isActive)
        assertNull(link.current)

        // Second end() is a no-op: the handle is closed exactly once.
        assertFalse(link.end())
        assertEquals(1, platform.closes.size)

        // The next result is allowed to connect.
        assertNotNull(link.request("AA:BB:CC:00:00:02"))
        assertEquals(listOf("AA:BB:CC:00:00:01", "AA:BB:CC:00:00:02"), platform.connects)
    }

    @Test
    fun `stop closes the owned connection and blocks callback and queue work after it`() {
        val platform = FakePlatform()
        val link = linkOf(platform)
        val handle = link.request("AA:BB:CC:00:00:01")!!

        assertTrue(link.closeSession())

        assertEquals(listOf(handle.id), platform.closes)
        assertNull(link.current)
        assertFalse(link.isActive)
        // No stale-session callback may run work: neither discovery/queue mutation nor
        // packet publication is possible for the closed handle.
        assertNull(link.ifOwned(handle) { "queue advanced" })
        assertNull(link.ifOwned(handle) { "packet published" })
        assertFalse(link.owns(handle))
        // And a further teardown is a no-op (handle closed exactly once).
        assertFalse(link.closeSession())
        assertEquals(1, platform.closes.size)
    }

    @Test
    fun `termination hook runs while exclusive on end and on session close`() {
        val platform = FakePlatform()
        var sweeps = 0
        val link = GattConnection<String, FakeHandle>(
            connect = platform::connect,
            close = platform::close,
            // The transports wire this to SessionPublisher.cancelPending().
            onTerminated = { sweeps++ },
        )
        link.openSession()

        val first = link.request("AA:BB:CC:00:00:01")
        assertNotNull(first)
        // Real connection end: publication is swept inside the exclusive section.
        assertTrue(link.end())
        assertEquals(1, sweeps)

        // Nothing to terminate: no connection -> no capture could exist -> no sweep.
        assertFalse(link.end())
        assertEquals(1, sweeps)

        val second = link.request("AA:BB:CC:00:00:02")
        assertNotNull(second)
        assertTrue(link.closeSession())
        assertEquals(2, sweeps)
        assertTrue(link.isStopped)
    }

    @Test
    fun `session tokens are minted per lifetime and invalidated by end and teardown`() {
        val platform = FakePlatform()
        val link = linkOf(platform)

        link.openSession() // transport start()
        val startedSession = link.session()
        val first = link.request("AA:BB:CC:00:00:01")!!
        // Still the same transport session: captures taken now carry its token.
        assertEquals(startedSession, link.session())
        assertTrue(link.isCurrentSession(startedSession))
        assertEquals(true, link.ifOwned(first) { link.isCurrentSession(startedSession) })

        // stop(): the token every capture carried becomes dead at once.
        assertTrue(link.closeSession())
        assertFalse(link.isCurrentSession(startedSession))
        assertNull(link.ifOwned(first) { "post-stop work" })

        // restart: a fresh token, and the old one stays dead.
        link.openSession()
        val newSession = link.session()
        assertTrue(newSession != startedSession)
        val second = link.request("AA:BB:CC:00:00:01")!!
        assertTrue(link.isCurrentSession(newSession))
        assertFalse(link.isCurrentSession(startedSession))

        // A connection ending also invalidates captures taken on it, so a deferred
        // emission from the dead connection cannot land in the replacement.
        assertTrue(link.end())
        assertFalse(link.isCurrentSession(newSession))
    }

    @Test
    fun `a refused platform connection leaves nothing owned`() {
        val platform = FakePlatform()
        platform.refuseConnect = true
        val link = linkOf(platform)

        assertNull(link.request("AA:BB:CC:00:00:01"))
        assertFalse(link.isActive)
        assertNull(link.current)
        assertFalse(link.end())

        // Recovery: once the platform accepts, ownership is taken.
        platform.refuseConnect = false
        val handle = link.request("AA:BB:CC:00:00:01")
        assertNotNull(handle)
        assertTrue(link.owns(handle!!))
    }
}
