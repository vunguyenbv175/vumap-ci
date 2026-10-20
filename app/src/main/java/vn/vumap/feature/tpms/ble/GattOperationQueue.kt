package vn.vumap.feature.tpms.ble

/**
 * Serialized GATT operation driver: at most one platform operation is in flight at a
 * time, and the next one is started from the previous operation's completion.
 *
 * Review contracts (PR #1):
 * - The Android GATT stack accepts one outstanding request per connection, so
 *   descriptor writes and characteristic reads must not be fired simultaneously.
 * - [completed] releases the slot and then STARTS the next queued operation; there is
 *   no code path that takes an operation out of the queue without handing it to the
 *   platform (an earlier version discarded `complete()`'s return value and stalled
 *   after the first callback).
 * - A start that is refused synchronously (e.g. API 33+ `writeDescriptor` returning
 *   `BluetoothStatusCodes.ERROR_*`, or a disconnect) never produces a completion
 *   callback; [pump] therefore releases such an operation immediately and moves on
 *   instead of leaving the queue permanently in flight.
 * - [clear] drops queued and in-flight state on disconnect/release, so a stalled
 *   operation cannot carry over to the next connection.
 *
 * Pure JVM (no Android types): the platform is injected as [start], which
 * returns whether the platform accepted the operation, so completion/refusal sequences
 * are covered by host tests with a fake platform.
 */
class GattOperationQueue<T>(
    private val start: (op: T) -> Boolean,
    private val maxPending: Int = 16,
) {

    private val pending = ArrayDeque<T>()
    private var inFlight: T? = null

    val pendingCount: Int get() = pending.size

    val inFlightOperation: T? get() = inFlight

    val hasInFlight: Boolean get() = inFlight != null

    /** Queues [op]; false when the bounded queue is full and the operation was dropped. */
    fun enqueue(op: T): Boolean {
        if (pending.size >= maxPending) return false
        pending.addLast(op)
        return true
    }

    /**
     * Starts the next queued operation, skipping any the platform refuses to accept.
     * Returns only when an operation is in flight or nothing is queued.
     */
    fun pump() {
        while (true) {
            if (inFlight != null) return
            val op = pending.removeFirstOrNull() ?: return
            inFlight = op
            if (start(op)) return
            // Refused synchronously: no completion callback will ever arrive for it.
            inFlight = null
        }
    }

    /** Releases the in-flight operation (its completion callback ran) and pumps the next. */
    fun completed() {
        inFlight = null
        pump()
    }

    /** Drops queued and in-flight state (disconnect / new service discovery / release). */
    fun clear() {
        pending.clear()
        inFlight = null
    }
}
