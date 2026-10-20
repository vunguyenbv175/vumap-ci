package vn.vumap.feature.tpms.ble

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding (PR #1, round 5): packet publication must stay inside the lifetime
 * that captured it — capture (copy + timestamp) at receipt, session identity carried
 * into the deferred work, and teardown rejecting or draining pending emissions.
 *
 * Covers the requested controlled-dispatcher regression: notification -> queued
 * publication -> stop/reconnect -> old job resumed must emit nothing from the old
 * session. Production code under test: [SessionPublisher] with a fake session
 * authority standing in for [GattConnection.session] / the BleScanWindow generation.
 */
class SessionPublisherTest {

    /** Dispatcher that never runs anything until [pump]: full control over job resume. */
    private class PausedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queue) { queue.addLast(block) }
        }

        fun pump() {
            while (true) {
                val next = synchronized(queue) {
                    if (queue.isEmpty()) null else queue.removeFirst()
                } ?: return
                next.run()
            }
        }
    }

    private val emitted = CopyOnWriteArrayList<String>()

    /**
     * Fake authority mirroring the transport: [current] is the link generation (bumped
     * by openSession/closeSession/end) or the live window generation.
     */
    private class Authority {
        @Volatile var current: Long = 1L
        fun isCurrent(token: Long): Boolean = token == current
    }

    @Test
    fun `queued publication resumed after stop and reconnect emits nothing old`() {
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(dispatcher + Job())
        val authority = Authority()
        val publisher = SessionPublisher<String>(
            scope = scope,
            isCurrentSession = authority::isCurrent,
            emit = { packet -> emitted += packet },
        )

        // Notification arrives: capture is queued, the emission job has not run yet.
        publisher.publish(1, "old-session-packet")
        assertEquals(0, emitted.size)

        // stop(): generation bumped (closeSession) and pending work cancelled; then a
        // new session starts (openSession -> generation 2) and only THEN does the old
        // job resume on the dispatcher.
        authority.current = 2L
        publisher.cancelPending()
        dispatcher.pump()

        assertEquals("old packet must never be delivered into the new session", 0, emitted.size)
        scope.cancel()
    }

    @Test
    fun `emit-time recheck alone rejects a publication whose session ended`() {
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(dispatcher + Job())
        val authority = Authority()
        val publisher = SessionPublisher<String>(
            scope = scope,
            isCurrentSession = authority::isCurrent,
            emit = { packet -> emitted += packet },
        )

        publisher.publish(1, "packet")
        // Only the authority moves (teardown did not touch the job list): the job's own
        // emit-time re-validation must drop it when it finally resumes.
        authority.current = 0L
        dispatcher.pump()

        assertEquals(0, emitted.size)
        scope.cancel()
    }

    @Test
    fun `emit suspended by backpressure is cancelled and drained at teardown`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val authority = Authority()
        val emitEntered = CompletableDeferred<Unit>()
        val releaseEmit = CompletableDeferred<Unit>()
        val publisher = SessionPublisher<String>(
            scope = scope,
            isCurrentSession = authority::isCurrent,
            emit = { packet ->
                emitEntered.complete(Unit)
                releaseEmit.await() // suspending + cancellation-aware (backpressure stand-in)
                emitted += packet
            },
        )

        publisher.publish(1, "parked-packet")
        withTimeout(5_000) { emitEntered.await() }

        // stop(): bump + cancel + join — returns only when the parked emit is gone.
        authority.current = 0L
        publisher.drain()

        // Even if the sink later unblocks, nothing may have been delivered.
        releaseEmit.complete(Unit)
        assertEquals(0, emitted.size)
        scope.cancel()
    }

    @Test
    fun `capture from an already-stale session is never queued`() {
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(dispatcher + Job())
        val authority = Authority()
        val publisher = SessionPublisher<String>(
            scope = scope,
            isCurrentSession = authority::isCurrent,
            emit = { packet -> emitted += packet },
        )

        authority.current = 0L // session already ended before the capture was published
        publisher.publish(1, "late-capture")
        dispatcher.pump()

        assertEquals(0, emitted.size)
        scope.cancel()
    }

    @Test
    fun `live session emits exactly once with the packet captured at receipt`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val authority = Authority()
        val packets = listOf("p1", "p2")
        val allEmitted = CompletableDeferred<Unit>()
        val publisher = SessionPublisher<String>(
            scope = scope,
            isCurrentSession = authority::isCurrent,
            emit = { packet ->
                emitted += packet
                if (emitted.size == packets.size) allEmitted.complete(Unit)
            },
        )

        packets.forEach { publisher.publish(1, it) }
        // Wait for the emissions themselves — drain() is teardown semantics (cancel +
        // join), so it must never be used here as a "let everything finish" barrier.
        withTimeout(5_000) { allEmitted.await() }

        assertEquals(2, emitted.size)
        assertTrue(emitted.containsAll(packets))
        assertEquals("every packet exactly once", 2, emitted.distinct().size)
        scope.cancel()
    }

    @Test
    fun `drain without an emit in flight is harmless and repeatable`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val authority = Authority()
        val publisher = SessionPublisher<String>(
            scope = scope,
            isCurrentSession = authority::isCurrent,
            emit = { packet -> emitted += packet },
        )

        publisher.drain()
        publisher.cancelPending()
        publisher.drain()
        assertEquals(0, emitted.size)
        scope.cancel()
    }

    /**
     * Review round 6 regression (GATT shape): an emission that has ALREADY ENTERED the
     * suspending sink must be cancelled by connection termination alone — no stop(), no
     * drain() — and must not complete into the replacement connection's lifetime even
     * when backpressure later releases.
     */
    @Test
    fun `connection end cancels an already-entered emission without stop or drain`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val emitEntered = CompletableDeferred<Unit>()
            val releaseEmit = CompletableDeferred<Unit>()
            val emitSettled = CompletableDeferred<Unit>()
            val newPacketDelivered = CompletableDeferred<Unit>()
            var linkRef: GattConnection<String, String>? = null
            val publisher = SessionPublisher<String>(
                scope = scope,
                isCurrentSession = { token -> linkRef!!.isCurrentSession(token) },
                emit = { packet ->
                    try {
                        emitEntered.complete(Unit)
                        releaseEmit.await() // stands in for a full SharedFlow buffer
                        emitted += packet
                        if (packet == "new-connection-packet") newPacketDelivered.complete(Unit)
                    } catch (e: CancellationException) {
                        emitSettled.complete(Unit)
                        throw e
                    }
                },
            )
            val link = GattConnection<String, String>(
                connect = { address -> "handle:$address" },
                close = { },
                onTerminated = { publisher.cancelPending() },
            )
            linkRef = link
            link.openSession()

            // Connection A: notification captured, emission entered the sink and parked.
            val handleA = link.request("AA:BB:CC:00:00:01")!!
            link.ifOwned(handleA) { publisher.publish(link.session(), "old-connection-packet") }
            withTimeout(5_000) { emitEntered.await() }

            // Connection terminates WITHOUT stop()/drain(): only link.end() — its hook
            // must sweep the in-flight emission while still exclusive — and a
            // replacement connection opens immediately afterwards.
            assertTrue(link.end())
            val handleB = link.request("AA:BB:CC:00:00:02")!!
            assertTrue(link.owns(handleB))

            // Backpressure releases: the old emission must not be delivered.
            releaseEmit.complete(Unit)
            withTimeout(5_000) { emitSettled.await() }
            assertEquals("old packet leaked into the replacement connection", 0, emitted.size)

            // The replacement connection's own capture still publishes normally.
            link.ifOwned(handleB) { publisher.publish(link.session(), "new-connection-packet") }
            withTimeout(5_000) { newPacketDelivered.await() }
            assertEquals(listOf("new-connection-packet"), emitted.toList())
            scope.cancel()
        }

    /**
     * Review round 6 regression (advertisement shape): the equivalent lifetime handling
     * for window invalidation — closing the window (loop cycle, supersession or stop)
     * cancels an already-entered emission for that generation.
     */
    @Test
    fun `window invalidation cancels an already-entered emission for that generation`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + Job())
            val emitEntered = CompletableDeferred<Unit>()
            val releaseEmit = CompletableDeferred<Unit>()
            val emitSettled = CompletableDeferred<Unit>()
            var publisherRef: SessionPublisher<String>? = null
            val windows = BleScanWindow<String, String>(
                startScan = { _, _ -> },
                stopScan = { _, _ -> },
                onInvalidated = { publisherRef!!.cancelPending() },
            )
            val publisher = SessionPublisher<String>(
                scope = scope,
                isCurrentSession = { generation -> windows.accepts(generation) },
                emit = { packet ->
                    try {
                        emitEntered.complete(Unit)
                        releaseEmit.await() // stands in for a full SharedFlow buffer
                        emitted += packet
                    } catch (e: CancellationException) {
                        emitSettled.complete(Unit)
                        throw e
                    }
                },
            )
            publisherRef = publisher

            val window = windows.open("scanner") { generation -> "cb-$generation" }
            publisher.publish(window.generation, "old-window-packet")
            withTimeout(5_000) { emitEntered.await() }

            // The window dies WITHOUT stop() (loop cycle or supersession), then a
            // replacement window opens.
            assertTrue(windows.close(window))
            val next = windows.open("scanner") { generation -> "cb-$generation" }
            assertTrue(windows.accepts(next.generation))

            // Backpressure releases: the old emission must not be delivered.
            releaseEmit.complete(Unit)
            withTimeout(5_000) { emitSettled.await() }
            assertEquals("old packet leaked into the next window", 0, emitted.size)
            scope.cancel()
        }
}
