package vn.vumap.feature.tpms.ble

/**
 * Single-connection ownership for the BLE GATT link, enforced on one lock so the
 * admission, teardown and callback paths cannot interleave across the transport's
 * execution model (scan results run as separate coroutine dispatches on
 * [kotlinx.coroutines.Dispatchers.Default], GATT callbacks arrive on the main looper,
 * stop()/release() run on the caller's context).
 *
 * Policy (PR #1 review):
 * - Exactly ONE connection. [request] checks the guard, calls the platform and stores
 *   the handle atomically under the lock, so two simultaneous scan results can never
 *   both observe "no connection" and open two handles (the loser's handle would leak).
 * - [closeSession] marks the session stopped AND closes the owned handle, all under the
 *   same lock: a connect admitted before stop publishes first and is then closed by the
 *   teardown, while an admission arriving after stop is rejected — an in-progress
 *   connection can never publish ownership after its session stopped.
 * - [end] handles link-level termination (failure/disconnect) without closing the
 *   session, so a later scan result may reconnect.
 * - [ifOwned] runs the caller's work while holding the lock, so identity check and the
 *   work itself (service discovery, queue completion, packet publication) are atomic
 *   with respect to teardown — not a check-then-act race.
 *
 * Pure JVM: the handle type and the connect/close platform calls are injected, so
 * two-simultaneous-requests, teardown-during-connect and late-callback lifecycles are
 * host-testable with a fake platform and barriers.
 *
 * @param connect starts a connection for an address; null = refused (nothing owned).
 * @param close releases one handle (disconnect + close on Android).
 */
class GattConnection<A, H>(
    private val connect: (address: A) -> H?,
    private val close: (H) -> Unit,
    /**
     * Runs inside [end] / [closeSession] while the termination is exclusive (still
     * under the ownership lock, so no admission or capture can overtake it) — the
     * transport cancels in-flight packet publication here, so an emission already
     * suspended inside the sink is cancelled instead of completing into a later
     * connection's lifetime.
     */
    private val onTerminated: () -> Unit = {},
) {

    /** Guards every field below and every block passed to [ifOwned]. */
    private val lock = Any()

    private var handle: H? = null
    private var address: A? = null

    /** True once the transport session stopped: no connection may be admitted. */
    private var stopped = false

    /**
     * Names the current connection/session: bumped by [openSession], [closeSession] and
     * [end]. Deferred work (packet publication) captures it at receipt and re-validates
     * it before emitting, so it can never deliver a capture from an ended connection
     * into a live or newer one.
     */
    private var generation = 0L

    /** The connection currently owned, null when none. */
    val current: H? get() = synchronized(lock) { handle }

    /** Address of the owned connection, null when none. */
    val currentAddress: A? get() = synchronized(lock) { address }

    /** True while a connection (connecting or connected) is owned. */
    val isActive: Boolean get() = synchronized(lock) { handle != null }

    /** True once [closeSession] ran for the current transport session. */
    val isStopped: Boolean get() = synchronized(lock) { stopped }

    /** True while [candidate] is the connection this object owns. */
    fun owns(candidate: H): Boolean = synchronized(lock) { handle === candidate }

    /**
     * Capture-time token for deferred work: read it while holding the owning gate (i.e.
     * inside [ifOwned]) so it always names the connection that produced the data.
     */
    fun session(): Long = synchronized(lock) { generation }

    /** Emit-time authority: true while [token] still names the live session/connection. */
    fun isCurrentSession(token: Long): Boolean = synchronized(lock) { generation == token }

    /**
     * Gate for every callback: the identity check and [block] run under the same lock as
     * admission/teardown, so work for a connection cannot start once teardown won, and
     * teardown cannot drop the connection mid-work. Returns null when [candidate] is not
     * owned (superseded / failed / stopped session). Reentrant: [block] may call [end].
     */
    fun <T> ifOwned(candidate: H, block: () -> T): T? = synchronized(lock) {
        if (handle === candidate) block() else null
    }

    /** Transport start(): opens a new session in which connections may be admitted. */
    fun openSession() {
        synchronized(lock) {
            generation++
            stopped = false
        }
    }

    /**
     * Transport stop()/release(): marks the session stopped (no further admission) and
     * closes the owned handle, all atomically under the lock. A [request] that is
     * already inside the platform connect publishes before this runs and is closed here;
     * one that arrives after returns null without touching the platform. The generation
     * bump invalidates every capture taken in this session for deferred publication.
     * Returns true when a handle was closed.
     */
    fun closeSession(): Boolean {
        val owned = synchronized(lock) {
            generation++
            stopped = true
            val currentHandle = handle
            handle = null
            address = null
            // Sweep publication while still exclusive: a replacement session cannot be
            // admitted until this critical section ends.
            onTerminated()
            currentHandle
        } ?: return false
        close(owned)
        return true
    }

    /**
     * Link-level end (failure/disconnect): releases and closes the handle but keeps the
     * session open, so a later scan result may reconnect. The generation bump drops any
     * packet captured on the ended connection. Returns true when there was a connection
     * to end; a second call is a no-op.
     */
    fun end(): Boolean {
        val owned = synchronized(lock) {
            val currentHandle = handle ?: return false
            generation++
            handle = null
            address = null
            // Cancel connection-owned publication while still exclusive: admission
            // ([request]) shares this lock, so a replacement connection cannot open
            // before this cleanup has run.
            onTerminated()
            currentHandle
        }
        close(owned)
        return true
    }

    /**
     * Starts a connection to [address] unless the session stopped or a link is already
     * active. Admission, platform connect and handle publication happen in one critical
     * section, so concurrent scan results serialize: exactly one connection is opened,
     * and a teardown cannot leave a handle published behind it. Returns the handle when
     * this call started the connection, null when deduplicated/refused/stopped.
     */
    fun request(address: A): H? = synchronized(lock) {
        if (stopped || handle != null) return null
        val newHandle = connect(address) ?: return null
        handle = newHandle
        this.address = address
        newHandle
    }
}
