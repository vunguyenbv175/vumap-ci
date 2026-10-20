package vn.vumap.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.model.GeoPoint

/**
 * Freshness rules for cached/live fixes (review: stale lastLocation must never be
 * presented as current GPS; live fixes expire when updates cease).
 *
 * The Handler-driven Fix->Stale transition itself needs a device/emulator (main Looper
 * + wall time); what is unit-tested here is the pure predicate and the monotonic age
 * math it relies on, so the thresholds cannot silently invert.
 */
class LocationFreshnessTest {

    private fun fixAt(elapsedNs: Long) = GpsFix(
        point = GeoPoint(10.7769, 106.7009),
        accuracyMeters = 5f,
        timeMs = 1_700_000_000_000L,
        elapsedRealtimeNs = elapsedNs,
    )

    @Test
    fun `age is measured on the monotonic clock, ignoring wall time`() {
        val fix = fixAt(elapsedNs = 100_000_000_000L)
        // 5 seconds later monotonically, wall time irrelevant.
        assertEquals(5_000L, fix.ageMs(105_000_000_000L))
    }

    @Test
    fun `fresh seed within max age is accepted`() {
        assertTrue(LocationTracker.isFreshAge(0L, LocationTracker.SEED_MAX_AGE_MS))
        assertTrue(LocationTracker.isFreshAge(59_999L, LocationTracker.SEED_MAX_AGE_MS))
        assertTrue(LocationTracker.isFreshAge(60_000L, LocationTracker.SEED_MAX_AGE_MS))
    }

    @Test
    fun `hours-old cached seed is rejected`() {
        // 3 hours old lastLocation must never become a Fix.
        assertFalse(LocationTracker.isFreshAge(3 * 3_600_000L, LocationTracker.SEED_MAX_AGE_MS))
    }

    @Test
    fun `live fix past expiry is stale`() {
        assertFalse(LocationTracker.isFreshAge(30_001L, LocationTracker.FIX_EXPIRY_MS))
        assertTrue(LocationTracker.isFreshAge(30_000L, LocationTracker.FIX_EXPIRY_MS))
    }

    @Test
    fun `negative age from clock anomaly is stale, not fresh`() {
        assertFalse(LocationTracker.isFreshAge(-1L, LocationTracker.SEED_MAX_AGE_MS))
    }

    @Test
    fun `expiry is well above the update interval so healthy 1Hz fixes never flicker`() {
        assertTrue(LocationTracker.FIX_EXPIRY_MS > 10 * LocationTracker.UPDATE_INTERVAL_MS)
    }

    // stop/wait/restart-with-no-new-fix regression: expiry must survive stop/start.

    @Test
    fun `stop demotes a live fix to stale, keeping the last point`() {
        val fix = fixAt(elapsedNs = 1_000_000_000L)
        val demoted = LocationTracker.demoteOnStop(GpsState.Fix(fix))
        assertEquals(GpsState.Stale(fix), demoted)
    }

    @Test
    fun `stop leaves non-fix states untouched`() {
        assertEquals(GpsState.NoFix, LocationTracker.demoteOnStop(GpsState.NoFix))
        assertEquals(GpsState.NoPermission, LocationTracker.demoteOnStop(GpsState.NoPermission))
        val stale = GpsState.Stale(fixAt(elapsedNs = 0L))
        assertEquals(stale, LocationTracker.demoteOnStop(stale))
    }

    @Test
    fun `restart with a still-fresh fix resumes only its remaining lifetime`() {
        val fix = fixAt(elapsedNs = 1_000_000_000L)
        // 5 s old fix, 30 s expiry -> 25 s remaining.
        val (state, remainingMs) = LocationTracker.revalidateOnStart(
            GpsState.Fix(fix),
            6_000_000_000L,
        )
        assertEquals(GpsState.Fix(fix), state)
        assertEquals(25_000L, remainingMs)
    }

    @Test
    fun `restart after the fix aged out demotes with no reschedule`() {
        val fix = fixAt(elapsedNs = 1_000_000_000L)
        // Home pressed, 40 s pass with GPS unavailable, back to foreground.
        val (state, remainingMs) = LocationTracker.revalidateOnStart(
            GpsState.Fix(fix),
            41_000_000_000L,
        )
        assertEquals(GpsState.Stale(fix), state)
        assertEquals(null, remainingMs)
    }

    @Test
    fun `full stop-wait-restart cycle never resurrects a fix without new updates`() {
        val fix = fixAt(elapsedNs = 1_000_000_000L)
        // Fix received, Home pressed.
        var state: GpsState = LocationTracker.demoteOnStop(GpsState.Fix(fix))
        // 60 s pass with no updates; back to foreground.
        val (restarted, remainingMs) = LocationTracker.revalidateOnStart(state, 61_000_000_000L)
        state = restarted
        assertEquals(GpsState.Stale(fix), state)
        assertEquals(null, remainingMs)
    }

    @Test
    fun `restart passes non-fix states through untouched`() {
        val (state, remainingMs) = LocationTracker.revalidateOnStart(GpsState.NoFix, 9_999_000_000L)
        assertEquals(GpsState.NoFix, state)
        assertEquals(null, remainingMs)
    }

    // Failed registration followed by retry.

    @Test
    fun `failed registration turns bare waiting into unavailable`() {
        val failed = LocationTracker.resolveRegistrationFailure(GpsState.NoFix, "ApiException")
        assertEquals(GpsState.Unavailable("ApiException"), failed)
    }

    @Test
    fun `failed registration keeps a live fix under its own expiry`() {
        val fix = fixAt(elapsedNs = 1_000_000_000L)
        assertEquals(
            GpsState.Fix(fix),
            LocationTracker.resolveRegistrationFailure(GpsState.Fix(fix), "ApiException"),
        )
    }

    @Test
    fun `failed registration keeps labeled stale state instead of clobbering it`() {
        val stale = GpsState.Stale(fixAt(elapsedNs = 0L))
        assertEquals(stale, LocationTracker.resolveRegistrationFailure(stale, "ApiException"))
    }

    @Test
    fun `failed registration twice stays unavailable`() {
        val once = LocationTracker.resolveRegistrationFailure(GpsState.NoFix, "ApiException")
        assertEquals(once, LocationTracker.resolveRegistrationFailure(once, "ApiException"))
    }

    // Old/delayed callback results.

    private fun fixAtNs(elapsedNs: Long) = GpsFix(
        point = GeoPoint(10.7769, 106.7009),
        accuracyMeters = 5f,
        timeMs = 1_700_000_000_000L,
        elapsedRealtimeNs = elapsedNs,
    )

    @Test
    fun `old callback arriving with nothing displayed is labeled stale, granted no lifetime`() {
        // Callback 40 s old on arrival: must never become a live Fix.
        val (state, delayMs) = LocationTracker.resolveCallbackFix(
            GpsState.NoFix,
            fixAtNs(1_000_000_000L),
            41_000_000_000L,
        )
        assertEquals(GpsState.Stale(fixAtNs(1_000_000_000L)), state)
        assertEquals(null, delayMs)
    }

    @Test
    fun `fresh callback publishes a fix with only its remaining lifetime`() {
        // 5 s old on arrival: Fix with 25 s left, not a fresh 30 s grant.
        val (state, delayMs) = LocationTracker.resolveCallbackFix(
            GpsState.NoFix,
            fixAtNs(1_000_000_000L),
            6_000_000_000L,
        )
        assertEquals(GpsState.Fix(fixAtNs(1_000_000_000L)), state)
        assertEquals(25_000L, delayMs)
    }

    @Test
    fun `older callback never regresses a newer displayed fix`() {
        val current = GpsState.Fix(fixAtNs(20_000_000_000L))
        val (state, delayMs) = LocationTracker.resolveCallbackFix(
            current,
            fixAtNs(10_000_000_000L),
            25_000_000_000L,
        )
        assertEquals(current, state)
        // Ignoring the input must not drop the displayed fix's expiry: the publisher
        // cancels the previous timer before applying this result, so the retained fix
        // has to keep (or get) a scheduled expiry for its remaining lifetime.
        assertEquals(25_000L, delayMs)
    }

    // Review finding (PR #1): an ignored/duplicate input must not leave a Fix current
    // forever by cancelling its expiry without rescheduling it.

    @Test
    fun `duplicate callback keeps the displayed fix under a rescheduled expiry`() {
        val current = GpsState.Fix(fixAtNs(20_000_000_000L))
        // Same timestamp delivered twice (overlapping callbacks / seed replay).
        val (state, delayMs) = LocationTracker.resolveCallbackFix(
            current,
            fixAtNs(20_000_000_000L),
            25_000_000_000L,
        )
        assertEquals(current, state)
        assertEquals(25_000L, delayMs)
    }

    @Test
    fun `ignored input for an already expired fix demotes instead of dropping the timer`() {
        // A retained fix that aged out while no newer input arrived: ignoring an older
        // callback must label it stale, not keep it as a live Fix with no expiry.
        val (state, delayMs) = LocationTracker.resolveCallbackFix(
            GpsState.Fix(fixAtNs(1_000_000_000L)),
            fixAtNs(500_000_000L),
            40_000_000_000L,
        )
        assertEquals(GpsState.Stale(fixAtNs(1_000_000_000L)), state)
        assertEquals(null, delayMs)
    }

    @Test
    fun `publishing a fix without a resolver delay still schedules its remaining lifetime`() {
        val (state, delayMs) = LocationTracker.ensureExpiry(
            GpsState.Fix(fixAtNs(1_000_000_000L)),
            null,
            6_000_000_000L,
        )
        assertEquals(GpsState.Fix(fixAtNs(1_000_000_000L)), state)
        assertEquals(25_000L, delayMs)
    }

    @Test
    fun `publishing an expired fix without a delay demotes it to stale`() {
        val (state, delayMs) = LocationTracker.ensureExpiry(
            GpsState.Fix(fixAtNs(1_000_000_000L)),
            null,
            31_001_000_000L,
        )
        assertEquals(GpsState.Stale(fixAtNs(1_000_000_000L)), state)
        assertEquals(null, delayMs)
    }

    @Test
    fun `every published fix carries the resolver delay unchanged`() {
        val (state, delayMs) = LocationTracker.ensureExpiry(
            GpsState.Fix(fixAtNs(1_000_000_000L)),
            7_000L,
            6_000_000_000L,
        )
        assertEquals(GpsState.Fix(fixAtNs(1_000_000_000L)), state)
        assertEquals(7_000L, delayMs)
    }

    @Test
    fun `non-fix states are never given an expiry`() {
        val (noFix, noFixDelay) = LocationTracker.ensureExpiry(GpsState.NoFix, 5_000L, 0L)
        assertEquals(GpsState.NoFix, noFix)
        assertEquals(null, noFixDelay)

        val stale = GpsState.Stale(fixAtNs(0L))
        val (staleState, staleDelay) = LocationTracker.ensureExpiry(stale, 5_000L, 0L)
        assertEquals(stale, staleState)
        assertEquals(null, staleDelay)
    }

    @Test
    fun `newer callback replaces an older displayed fix`() {
        val (state, delayMs) = LocationTracker.resolveCallbackFix(
            GpsState.Fix(fixAtNs(10_000_000_000L)),
            fixAtNs(20_000_000_000L),
            22_000_000_000L,
        )
        assertEquals(GpsState.Fix(fixAtNs(20_000_000_000L)), state)
        assertEquals(28_000L, delayMs)
    }

    @Test
    fun `stale arrival upgrades a stale display to the newer point, still labeled`() {
        val (state, delayMs) = LocationTracker.resolveCallbackFix(
            GpsState.Stale(fixAtNs(1_000_000_000L)),
            fixAtNs(5_000_000_000L),
            50_000_000_000L,
        )
        assertEquals(GpsState.Stale(fixAtNs(5_000_000_000L)), state)
        assertEquals(null, delayMs)
    }
}
