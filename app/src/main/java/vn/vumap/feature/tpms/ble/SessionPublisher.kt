package vn.vumap.feature.tpms.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Publishes captured packets so a deferred emission can never outlive (or leak into a
 * newer) the connection/window session that captured it — PR #1 review round 5.
 *
 * Contract with the caller:
 * - The caller copies the payload and stamps the capture time AT RECEIPT, while it
 *   still owns the connection/window, and passes that owning session token with the
 *   packet (never let a queued job do the capturing).
 * - [publish] validates the token before queueing, and the queued job validates it
 *   again immediately before emitting: a job that is only resumed after teardown, a
 *   reconnect or a restart is dropped instead of being delivered as fresh data.
 * - Jobs are tracked BEFORE they can start (lazy start), so [drain] /
 *   [cancelPending] always sees them: an emit parked by backpressure is cancelled and
 *   joined, and nothing captured earlier runs after stop() returns.
 *
 * Pure JVM: the session authority ([isCurrentSession]) and the emit sink are injected,
 * so the notification -> queued publication -> stop/reconnect -> old job resumed
 * interleaving is host-testable with a controlled dispatcher.
 *
 * @param scope where deferred emissions run.
 * @param isCurrentSession identity authority (link session/generation or window
 *   generation); must only ever accept tokens minted while that session was live.
 * @param emit the sink (the transport's packet flow).
 */
class SessionPublisher<T>(
    private val scope: CoroutineScope,
    private val isCurrentSession: (session: Long) -> Boolean,
    private val emit: suspend (packet: T) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayList<Job>()

    /** Queues [packet] captured under [session]; a stale session queues nothing. */
    fun publish(session: Long, packet: T) {
        if (!isCurrentSession(session)) return
        // Lazy start: the job is tracked before it can possibly emit, so a concurrent
        // teardown cannot miss it (add-then-start, never start-then-add).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Re-check at emission time: teardown/reconnect may have happened while this
            // job waited for the dispatcher.
            if (!isCurrentSession(session)) return@launch
            emit(packet)
        }
        synchronized(lock) {
            pending.removeAll { it.isCompleted }
            pending += job
        }
        job.start()
    }

    /** Cancels every tracked publication without waiting (terminal teardown). */
    fun cancelPending() {
        jobs().forEach { it.cancel() }
    }

    /**
     * Teardown semantics: cancels every tracked publication and WAITS for the jobs to
     * finish — work that has not emitted yet is dropped (a session ended, so late data
     * must not be delivered), and one parked inside a suspended emit is cancelled and
     * joined. After this returns, nothing captured earlier can still run.
     */
    suspend fun drain() {
        val jobs = jobs()
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    private fun jobs(): List<Job> = synchronized(lock) {
        val snapshot = pending.toList()
        pending.clear()
        snapshot
    }
}
