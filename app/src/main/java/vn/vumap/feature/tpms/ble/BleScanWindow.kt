package vn.vumap.feature.tpms.ble

/**
 * Owns one platform BLE scan window for a transport.
 *
 * Contract (review: scan windows must not outlive stop/release):
 * - Exactly one window owns the platform scan. Opening a new window stops the
 *   previous one first, so a quick stop/start can never leave two scans registered.
 * - [close] is idempotent per window: the loop's `finally` and the stop/release path
 *   can both try to unregister the same window and the platform scan is stopped once.
 * - Results are only accepted while their generation is the active one, so callbacks
 *   delivered by the platform after stop/release/restart are dropped.
 *
 * Pure JVM: the platform scanner is injected as [startScan]/[stopScan], so the whole
 * stop / release / restart-during-a-window lifecycle is covered by host unit tests.
 */
class BleScanWindow<S, C>(
    private val startScan: (scanner: S, callback: C) -> Unit,
    private val stopScan: (scanner: S, callback: C) -> Unit,
    /**
     * Runs whenever a generation stops owning the scan (supersession, loop-cycle close,
     * stop/release) — the transports cancel in-flight packet publication here, so an
     * emission already suspended inside the sink cannot complete after its window died.
     * Invoked while the invalidation is exclusive (callers hold the window lock).
     */
    private val onInvalidated: () -> Unit = {},
) {

    /** A generation-scoped scan registration. */
    data class Window<S, C>(
        val generation: Long,
        val scanner: S,
        val callback: C,
    )

    private var nextGeneration = 0L
    private var active: Window<S, C>? = null

    val isOpen: Boolean get() = active != null

    val activeGeneration: Long? get() = active?.generation

    /**
     * Starts a new scan window, superseding (and unregistering) any previous one.
     * The callback is built from the generation this window owns, so result filtering
     * cannot be tied to a stale number. Exceptions from the platform start call
     * propagate to the caller after the superseded window has been stopped, so the
     * scan cannot be left registered.
     */
    fun open(scanner: S, callbackFor: (generation: Long) -> C): Window<S, C> {
        closeActive()
        val generation = ++nextGeneration
        val window = Window(generation = generation, scanner = scanner, callback = callbackFor(generation))
        active = window
        try {
            startScan(scanner, window.callback)
        } catch (e: Throwable) {
            // The platform rejected the start: nothing is registered for this window.
            if (active === window) {
                active = null
                // Its generation is dead before it ever delivered: sweep publication too.
                onInvalidated()
            }
            throw e
        }
        return window
    }

    /** True while [generation] still owns the platform scan. */
    fun accepts(generation: Long): Boolean = active?.generation == generation

    /**
     * Ends [window] if it still owns the scan and unregisters it from the platform.
     * Returns true when this call performed the unregistration (at most once).
     */
    fun close(window: Window<S, C>): Boolean {
        if (active?.generation != window.generation) return false
        active = null
        // The generation no longer accepts results: end publication for it as well, so a
        // packet emission already suspended in the sink is cancelled rather than allowed
        // to complete into a later window's lifetime.
        onInvalidated()
        stopScan(window.scanner, window.callback)
        return true
    }

    /** Ends the active window, if any. Returns true when unregistration ran. */
    fun closeActive(): Boolean = active?.let { close(it) } ?: false
}

/**
 * Cleanup scope for ONE scan-loop session.
 *
 * A loop records every window it opened here and cleans up only through [cleanupOwn],
 * so cancellation cleanup that finishes late — e.g. stop() returned, start() opened a
 * replacement scan, and only then does the cancelled old job resume its `finally` —
 * can never unregister the newer session's scan. This is the session-scoped
 * counterpart to [BleScanWindow.closeActive], which a loop must never call from its
 * own cleanup.
 *
 * Pure JVM: ownership is tracked by generation inside [BleScanWindow], so the
 * "pause old cleanup -> replacement starts -> old cleanup completes" interleaving is
 * host-testable.
 */
class BleScanSession<S, C>(private val windows: BleScanWindow<S, C>) {

    private var owned: BleScanWindow.Window<S, C>? = null

    /** Records the window this loop currently owns (replacing any previous one). */
    fun opened(window: BleScanWindow.Window<S, C>) {
        owned = window
    }

    /**
     * Closes this session's own window, if it still owns the platform scan. Returns
     * true when this call performed the unregistration (never for another session's).
     */
    fun cleanupOwn(): Boolean {
        val window = owned ?: return false
        owned = null
        return windows.close(window)
    }
}
