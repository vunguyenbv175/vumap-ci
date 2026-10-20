package vn.vumap.feature.tpms.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Driver-level coverage (PR #1 review): a fake platform stands in for the GATT stack
 * and records which operations were actually handed to it, so completion/refusal
 * sequences prove the queue never takes an operation out without starting it — the
 * transport bug that queue-only tests missed (the first completion stalled the rest).
 */
class GattOperationQueueTest {

    /**
     * Fake platform: records the operations handed to it and reports whether the
     * platform ACCEPTED each one (true = a completion callback will follow, false =
     * immediate refusal with no callback, as with an API 33 status error).
     */
    private class FakeGattPlatform {
        val started = mutableListOf<String>()
        var accepts: (String) -> Boolean = { true }

        fun start(op: String): Boolean {
            started += op
            return accepts(op)
        }
    }

    private fun queueOf(
        platform: FakeGattPlatform,
        maxPending: Int = 16,
    ) = GattOperationQueue<String>(start = platform::start, maxPending = maxPending)

    // --- completion / refusal must actually start the next operation ---

    @Test
    fun `completion of the first operation starts the second`() {
        val platform = FakeGattPlatform()
        val queue = queueOf(platform)
        queue.enqueue("cccd:FFE1")
        queue.enqueue("read:FFF1")

        queue.pump()

        assertEquals(listOf("cccd:FFE1"), platform.started)
        assertEquals("cccd:FFE1", queue.inFlightOperation)

        // The completion callback: releases AND starts the next operation.
        queue.completed()

        assertEquals(listOf("cccd:FFE1", "read:FFF1"), platform.started)
        assertEquals("read:FFF1", queue.inFlightOperation)

        queue.completed()
        assertEquals(listOf("cccd:FFE1", "read:FFF1"), platform.started)
        assertFalse(queue.hasInFlight)
        assertNull(queue.inFlightOperation)
    }

    @Test
    fun `completion keeps starting operations until the queue is drained`() {
        val platform = FakeGattPlatform()
        val queue = queueOf(platform)
        (1..3).forEach { queue.enqueue("op$it") }

        queue.pump()
        queue.completed()
        queue.completed()
        queue.completed()

        assertEquals(listOf("op1", "op2", "op3"), platform.started)
        assertFalse(queue.hasInFlight)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `refusal without any completion callback starts the next operation immediately`() {
        val platform = FakeGattPlatform()
        // The platform refuses the first op synchronously (API 33 writeDescriptor
        // returning ERROR_GATT_WRITE_REQUEST_BUSY): no onDescriptorWrite will follow.
        platform.accepts = { op -> op != "cccd:FFE1" }
        val queue = queueOf(platform)
        queue.enqueue("cccd:FFE1")
        queue.enqueue("read:FFF1")

        queue.pump()

        assertEquals(listOf("cccd:FFE1", "read:FFF1"), platform.started)
        assertEquals("read:FFF1", queue.inFlightOperation)

        // The later completion still behaves normally.
        queue.completed()
        assertFalse(queue.hasInFlight)
    }

    @Test
    fun `an immediate writeDescriptor rejection does not leave the queue in flight`() {
        val platform = FakeGattPlatform()
        platform.accepts = { false } // every start is refused: no completion can be awaited
        val queue = queueOf(platform)
        queue.enqueue("cccd:FFE1")
        queue.enqueue("cccd:FFE2")

        queue.pump()

        // Both attempts reached the platform, none is stuck in flight, nothing pending.
        assertEquals(listOf("cccd:FFE1", "cccd:FFE2"), platform.started)
        assertFalse(queue.hasInFlight)
        assertEquals(0, queue.pendingCount)
        // A subsequent enqueue/pump round still works (no permanent stall).
        platform.accepts = { true }
        queue.enqueue("read:FFF1")
        queue.pump()
        assertEquals("read:FFF1", queue.inFlightOperation)
    }

    // --- one operation in flight, bounded, clearable ---

    @Test
    fun `only one operation is handed out until it completes`() {
        val platform = FakeGattPlatform()
        val queue = queueOf(platform)
        queue.enqueue("a")
        queue.enqueue("b")

        queue.pump()
        // A second pump while one is in flight must start nothing.
        queue.pump()
        assertEquals(listOf("a"), platform.started)
        assertEquals(1, queue.pendingCount)

        // Completion releases the slot AND starts the next operation, which is then
        // itself in flight until its own completion.
        queue.completed()
        assertEquals(listOf("a", "b"), platform.started)
        assertEquals("b", queue.inFlightOperation)
        assertEquals(0, queue.pendingCount)

        queue.completed()
        assertNull(queue.inFlightOperation)
        assertFalse(queue.hasInFlight)
    }

    @Test
    fun `disconnect clears queued and in-flight operations so nothing carries over`() {
        val platform = FakeGattPlatform()
        val queue = queueOf(platform)
        queue.enqueue("a")
        queue.enqueue("b")
        queue.pump()
        assertEquals("a", queue.inFlightOperation)

        queue.clear()

        assertFalse(queue.hasInFlight)
        assertEquals(0, queue.pendingCount)
        // The stale in-flight op's late completion must not start anything new.
        queue.completed()
        assertEquals(listOf("a"), platform.started)
        assertNull(queue.inFlightOperation)
    }

    @Test
    fun `bounded queue reports overflow instead of dropping the operation in flight`() {
        val platform = FakeGattPlatform()
        val queue = queueOf(platform, maxPending = 1)
        assertTrue(queue.enqueue("a"))
        assertFalse(queue.enqueue("b"))

        queue.pump()
        assertTrue(queue.hasInFlight)

        assertTrue(queue.enqueue("b"))
        assertFalse(queue.enqueue("c"))
        assertEquals(1, queue.pendingCount)

        // Completion releases "a" and starts "b" (now in flight): nothing was dropped.
        queue.completed()
        assertEquals(listOf("a", "b"), platform.started)
        assertEquals("b", queue.inFlightOperation)
    }

    @Test
    fun `a missing connection counts as refusal and releases the operation`() {
        // Mirrors the transport: gatt == null -> beginOperation returns false.
        val queue = GattOperationQueue<String>(start = { false })
        queue.enqueue("cccd:FFE1")

        queue.pump()

        assertFalse(queue.hasInFlight)
        assertEquals(0, queue.pendingCount)
    }
}
