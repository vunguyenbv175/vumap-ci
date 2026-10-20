package vn.vumap.core.location

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Timer surface for the live-fix expiry; injectable so scheduling is host-testable. */
interface ExpiryTimer {
    /** Cancels any pending expiry. */
    fun cancel()

    /** Replaces any pending expiry with one that fires [onExpire] after [delayMs]. */
    fun schedule(delayMs: Long, onExpire: () -> Unit)
}

/** Main-looper expiry timer used on device (host tests inject a fake instead). */
internal class HandlerExpiryTimer(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : ExpiryTimer {

    private var pending: Runnable? = null

    override fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }

    override fun schedule(delayMs: Long, onExpire: () -> Unit) {
        cancel()
        val runnable = Runnable {
            pending = null
            onExpire()
        }
        pending = runnable
        handler.postDelayed(runnable, delayMs)
    }
}

/**
 * Publication gate for the displayed GPS state: the single place that turns a
 * resolver result into a published state plus a scheduled expiry.
 *
 * Invariant: a state published as a live [GpsState.Fix] always leaves exactly one
 * pending expiry. Ignored input (a duplicate or older fix) re-schedules the retained
 * fix's remaining lifetime instead of dropping its timer, and a retained fix that has
 * already aged out demotes to [GpsState.Stale] — so a Fix can never stay current after
 * updates cease.
 *
 * Pure JVM: the timer and the monotonic clock are injected, so the whole
 * `live fix -> duplicate input -> updates cease -> stale` cycle, including scheduling,
 * is host-testable without a device or a main Looper.
 */
class FixExpiryGate(
    private val timer: ExpiryTimer,
    private val nowElapsedNs: () -> Long,
) {
    private val _state = MutableStateFlow<GpsState>(GpsState.NoFix)
    val state: StateFlow<GpsState> = _state.asStateFlow()

    /**
     * Publishes a result from [LocationTracker.resolveCallbackFix],
     * [LocationTracker.revalidateOnStart] or a direct state transition
     * (`state to null`), applying the expiry invariant before anything is scheduled.
     */
    fun publish(resolved: Pair<GpsState, Long?>) {
        val (state, delayMs) = LocationTracker.ensureExpiry(
            resolved.first,
            resolved.second,
            nowElapsedNs(),
        )
        _state.value = state
        timer.cancel()
        delayMs?.let { delay -> timer.schedule(delay) { onExpired() } }
    }

    /**
     * Fired by the timer. The timer is scheduled for the moment the fix's lifetime is
     * exhausted, so that boundary counts as expired: a live Fix must never be left
     * without a pending expiry (it would stay current forever). A timer that fires
     * early is re-armed for the remaining lifetime.
     */
    private fun onExpired() {
        val fix = (_state.value as? GpsState.Fix)?.fix ?: return
        val age = fix.ageMs(nowElapsedNs())
        if (age < 0L || age >= LocationTracker.FIX_EXPIRY_MS) {
            _state.value = GpsState.Stale(fix)
        } else {
            timer.schedule(LocationTracker.FIX_EXPIRY_MS - age) { onExpired() }
        }
    }
}
