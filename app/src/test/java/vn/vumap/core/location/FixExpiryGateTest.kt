package vn.vumap.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Review finding (PR #1): "live fix -> duplicate/older seed -> no more updates ->
 * stale", exercising scheduling and not only the pure resolver.
 *
 * The expiry timer and the monotonic clock are injected, so the whole cycle runs on
 * the host: what matters is that ignored input re-schedules the retained fix instead
 * of cancelling its only expiry.
 */
class FixExpiryGateTest {

    private class FakeTimer : ExpiryTimer {
        val scheduledDelaysMs = mutableListOf<Long>()
        var cancelCount = 0
            private set
        private var pending: (() -> Unit)? = null

        override fun cancel() {
            cancelCount++
            pending = null
        }

        override fun schedule(delayMs: Long, onExpire: () -> Unit) {
            scheduledDelaysMs += delayMs
            pending = onExpire
        }

        val hasPending: Boolean get() = pending != null

        /** Fires the pending expiry like the main-looper Handler would. */
        fun fire() {
            val runnable = pending
            pending = null
            runnable?.invoke()
        }
    }

    private var nowNs = 1_000_000_000L

    private fun fixAt(elapsedNs: Long) = GpsFix(
        point = GeoPoint(10.7769, 106.7009),
        accuracyMeters = 5f,
        timeMs = 1_700_000_000_000L,
        elapsedRealtimeNs = elapsedNs,
    )

    private fun gateWith(timer: FakeTimer) = FixExpiryGate(timer, { nowNs })

    @Test
    fun `live fix then duplicate input reschedules the expiry and goes stale when updates cease`() {
        val timer = FakeTimer()
        val gate = gateWith(timer)
        val fix = fixAt(nowNs)

        // Live fix published (empty current state).
        gate.publish(LocationTracker.resolveCallbackFix(GpsState.NoFix, fix, nowNs))
        assertEquals(GpsState.Fix(fix), gate.state.value)
        assertEquals(listOf(30_000L), timer.scheduledDelaysMs)
        assertTrue(timer.hasPending)

        // The same fix is delivered again 5 s later (duplicate/older seed): the point is
        // ignored, but the expiry must be re-scheduled for its remaining 25 s instead of
        // being cancelled for good.
        nowNs += 5_000_000_000L
        gate.publish(LocationTracker.resolveCallbackFix(gate.state.value, fix, nowNs))
        assertEquals(GpsState.Fix(fix), gate.state.value)
        assertEquals(listOf(30_000L, 25_000L), timer.scheduledDelaysMs)
        assertTrue(timer.hasPending)

        // Updates cease: the scheduled expiry fires and the retained point is labeled stale.
        nowNs += 25_000_000_000L
        timer.fire()
        assertEquals(GpsState.Stale(fix), gate.state.value)
        assertEquals(false, timer.hasPending)
    }

    @Test
    fun `an older callback cannot cancel the pending expiry of a newer fix`() {
        val timer = FakeTimer()
        val gate = gateWith(timer)
        val newer = fixAt(nowNs)

        gate.publish(LocationTracker.resolveCallbackFix(GpsState.NoFix, newer, nowNs))
        nowNs += 10_000_000_000L
        // Delayed older callback arrives: still ignored, expiry still pending.
        gate.publish(
            LocationTracker.resolveCallbackFix(gate.state.value, fixAt(newer.elapsedRealtimeNs - 5_000_000_000L), nowNs),
        )

        assertEquals(GpsState.Fix(newer), gate.state.value)
        assertEquals(listOf(30_000L, 20_000L), timer.scheduledDelaysMs)
        assertTrue(timer.hasPending)

        nowNs += 20_000_000_000L
        timer.fire()
        assertEquals(GpsState.Stale(newer), gate.state.value)
    }

    @Test
    fun `an ignored input for an already expired fix demotes instead of leaving it live`() {
        val timer = FakeTimer()
        val gate = gateWith(timer)
        val fix = fixAt(nowNs)

        gate.publish(LocationTracker.resolveCallbackFix(GpsState.NoFix, fix, nowNs))
        // 40 s pass without any update, then an older callback arrives.
        nowNs += 40_000_000_000L
        gate.publish(LocationTracker.resolveCallbackFix(gate.state.value, fix, nowNs))

        assertEquals(GpsState.Stale(fix), gate.state.value)
        assertEquals(false, timer.hasPending)
    }

    @Test
    fun `stopping cancels the expiry and demotes the retained fix`() {
        val timer = FakeTimer()
        val gate = gateWith(timer)
        val fix = fixAt(nowNs)
        gate.publish(LocationTracker.resolveCallbackFix(GpsState.NoFix, fix, nowNs))

        gate.publish(LocationTracker.demoteOnStop(gate.state.value) to null)

        assertEquals(GpsState.Stale(fix), gate.state.value)
        assertEquals(false, timer.hasPending)
        assertTrue(timer.cancelCount >= 2)
    }

    @Test
    fun `restart inside the lifetime resumes only the remaining expiry`() {
        val timer = FakeTimer()
        val gate = gateWith(timer)
        val fix = fixAt(nowNs)
        gate.publish(LocationTracker.resolveCallbackFix(GpsState.NoFix, fix, nowNs))

        // Home pressed, 10 s pass, back to foreground with no new fix.
        nowNs += 10_000_000_000L
        gate.publish(LocationTracker.revalidateOnStart(gate.state.value, nowNs))

        assertEquals(GpsState.Fix(fix), gate.state.value)
        assertEquals(listOf(30_000L, 20_000L), timer.scheduledDelaysMs)
    }

    @Test
    fun `permission and failure states never keep a fix expiry alive`() {
        val timer = FakeTimer()
        val gate = gateWith(timer)

        gate.publish(GpsState.NoPermission to null)
        assertEquals(GpsState.NoPermission, gate.state.value)
        assertFalse(timer.hasPending)

        gate.publish(LocationTracker.resolveRegistrationFailure(GpsState.NoFix, "ApiException") to null)
        assertEquals(GpsState.Unavailable("ApiException"), gate.state.value)
        assertFalse(timer.hasPending)
        assertEquals(0, timer.scheduledDelaysMs.size)
    }

    @Test
    fun `the expiry boundary itself counts as expired, never leaving a live fix`() {
        val timer = FakeTimer()
        val gate = gateWith(timer)
        val fix = fixAt(nowNs)
        gate.publish(LocationTracker.resolveCallbackFix(GpsState.NoFix, fix, nowNs))

        // Exactly the scheduled lifetime passes (Handler fires at or after the delay).
        nowNs += 30_000_000_000L
        timer.fire()

        assertEquals(GpsState.Stale(fix), gate.state.value)
        assertFalse(timer.hasPending)
    }

    @Test
    fun `a timer that fires early is re-armed for the remaining lifetime`() {
        val timer = FakeTimer()
        val gate = gateWith(timer)
        val fix = fixAt(nowNs)
        gate.publish(LocationTracker.resolveCallbackFix(GpsState.NoFix, fix, nowNs))

        // Dispatched 2 s before the lifetime is exhausted.
        nowNs += 28_000_000_000L
        timer.fire()

        assertEquals(GpsState.Fix(fix), gate.state.value)
        assertEquals(listOf(30_000L, 2_000L), timer.scheduledDelaysMs)
        assertTrue(timer.hasPending)
    }
}
