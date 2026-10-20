package vn.vumap.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.StateFlow
import vn.vumap.core.model.GeoPoint

/** A single GPS fix delivered by the fused location provider. */
data class GpsFix(
    val point: GeoPoint,
    val accuracyMeters: Float?,
    /** Wall-clock time of the fix (for display only; never used for freshness). */
    val timeMs: Long,
    /** Monotonic clock ([android.os.SystemClock.elapsedRealtimeNanos]) at fix time. */
    val elapsedRealtimeNs: Long,
) {
    /** Age of this fix on the monotonic clock; immune to wall-clock jumps. */
    fun ageMs(nowElapsedNs: Long): Long = (nowElapsedNs - elapsedRealtimeNs) / 1_000_000L
}

/** UI-facing GPS state. `NoFix`/`Stale`/`Unavailable` are shown as-is; never invent a position. */
sealed interface GpsState {
    data object NoPermission : GpsState
    data object NoFix : GpsState

    /**
     * Location updates cannot be registered (e.g. no Play Services on device).
     * [reason] is a short machine tag for logs; the UI shows a generic line.
     * Tracking is NOT marked started, so the next foreground entry retries.
     */
    data class Unavailable(val reason: String) : GpsState

    /**
     * Last fix is too old to drive follow-mode or to be presented as current.
     * The dot freezes at [fix] and the UI must label it stale (see status line).
     */
    data class Stale(val fix: GpsFix) : GpsState

    data class Fix(val fix: GpsFix) : GpsState
}

/**
 * Foreground GPS tracker (project plan core/location).
 *
 * Uses the Android Fused Location Provider at ~1 Hz while the map screen is visible.
 * No background service in P0. Location is consumed on-device only.
 */
class LocationTracker(
    private val appContext: Context,
    expiryTimer: ExpiryTimer = HandlerExpiryTimer(),
    nowElapsedNs: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) {

    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    /** Published GPS state + the single expiry scheduler (see [FixExpiryGate]). */
    private val gate = FixExpiryGate(expiryTimer, nowElapsedNs)

    val state: StateFlow<GpsState> = gate.state

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            gate.publish(
                resolveCallbackFix(
                    gate.state.value,
                    toFix(loc),
                    SystemClock.elapsedRealtimeNanos(),
                ),
            )
        }
    }

    private fun toFix(loc: Location): GpsFix = GpsFix(
        point = GeoPoint(loc.latitude, loc.longitude),
        accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
        timeMs = loc.time,
        elapsedRealtimeNs = loc.elapsedRealtimeNanos,
    )

    // Guarded so double-start (composition entry + ON_START at cold launch) registers
    // the callback only once; stop() resets it.
    private var started = false

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) {
            gate.publish(GpsState.NoPermission to null)
            return
        }
        if (started) return
        started = true
        // Retry semantics: a previous registration failure parked the state in
        // Unavailable with the guard reset. A new attempt means "waiting" again;
        // the outcome (fix, seed, or another failure) will move it from there.
        if (gate.state.value is GpsState.Unavailable) {
            gate.publish(GpsState.NoFix to null)
        }
        // Restarting after stop(): the retained Fix (if any) aged while updates were
        // off. Revalidate it against the monotonic clock and resume only its remaining
        // lifetime — never present it as fresh indefinitely.
        gate.publish(
            revalidateOnStart(
                gate.state.value,
                SystemClock.elapsedRealtimeNanos(),
            ),
        )
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateDistanceMeters(1f)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { e ->
                // Registration can fail (e.g. no Play Services on device). Without this
                // listener the tracker would stay marked started with zero updates and
                // every retry would return early at the guard. Reset so the next
                // foreground entry can retry, and say so in the UI.
                Log.w(TAG, "requestLocationUpdates failed", e)
                started = false
                gate.publish(
                    resolveRegistrationFailure(gate.state.value, e.javaClass.simpleName) to null,
                )
            }
        // Seed quickly with the last known fix so the dot appears without waiting a full
        // interval — but ONLY if the cached fix is fresh on the monotonic clock. A
        // hours-old lastLocation must never be presented as current GPS.
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc == null) return@addOnSuccessListener
            val seed = toFix(loc)
            if (!isFreshAge(seed.ageMs(SystemClock.elapsedRealtimeNanos()), SEED_MAX_AGE_MS)) {
                return@addOnSuccessListener
            }
            gate.publish(
                resolveCallbackFix(
                    gate.state.value,
                    seed,
                    SystemClock.elapsedRealtimeNanos(),
                ),
            )
        }
    }

    fun stop() {
        started = false
        // Updates cease here, so a retained Fix is no longer current. Demote it now
        // rather than letting it masquerade as live until a (now-cancelled) expiry;
        // publishing through the gate also cancels the pending expiry.
        gate.publish(demoteOnStop(gate.state.value) to null)
        client.removeLocationUpdates(callback)
    }

    companion object {
        private const val TAG = "LocationTracker"

        /** Fused-provider request interval. */
        const val UPDATE_INTERVAL_MS = 1_000L

        /** Cached lastLocation older than this is rejected, never shown as current. */
        const val SEED_MAX_AGE_MS = 60_000L

        /** Live fix with no update for this long is demoted to Stale. */
        const val FIX_EXPIRY_MS = 30_000L

        /**
         * Freshness predicate on monotonic age. Negative ages (clock anomalies) and ages
         * beyond [maxAgeMs] are stale. Pure function — covered by JVM unit tests.
         */
        fun isFreshAge(ageMs: Long, maxAgeMs: Long): Boolean = ageMs in 0..maxAgeMs

        /**
         * Stop-time demotion: once updates cease, the last fix is history, not GPS.
         * Pure function — covered by JVM unit tests.
         */
        fun demoteOnStop(state: GpsState): GpsState =
            if (state is GpsState.Fix) GpsState.Stale(state.fix) else state

        /**
         * Restart validation. A still-fresh Fix resumes with only its remaining
         * lifetime (returned as reschedule delay); anything older demotes with no
         * reschedule. Non-Fix states pass through untouched.
         * Pure function — covered by JVM unit tests.
         */
        fun revalidateOnStart(state: GpsState, nowElapsedNs: Long): Pair<GpsState, Long?> {
            val fix = (state as? GpsState.Fix)?.fix ?: return state to null
            val age = fix.ageMs(nowElapsedNs)
            return if (isFreshAge(age, FIX_EXPIRY_MS)) {
                state to (FIX_EXPIRY_MS - age)
            } else {
                GpsState.Stale(fix) to null
            }
        }

        /**
         * Registration-failure resolution. Only a bare NoFix becomes Unavailable;
         * Fix and Stale already carry (labeled) position information governed by
         * their own expiry, so a failed re-registration must not clobber them.
         * Pure function — covered by JVM unit tests.
         */
        fun resolveRegistrationFailure(state: GpsState, reason: String): GpsState =
            if (state is GpsState.NoFix) GpsState.Unavailable(reason) else state

        /**
         * Single publish path for every incoming fix (live callbacks and seeds).
         * Returns the state plus the expiry delay to schedule (null = none).
         * - An incoming fix older than the currently displayed one is ignored, so
         *   delayed/out-of-order callbacks can never regress a newer fix — but the
         *   retained fix KEEPS its expiry: the publisher cancels the previous timer
         *   before applying the result, so ignoring input without re-deriving the
         *   remaining lifetime would let that fix stay current indefinitely.
         * - A fresh incoming fix becomes Fix with only its remaining lifetime,
         *   never a full new grant from delivery time.
         * - An already-stale arrival retains its point labeled Stale with no
         *   lifetime granted.
         * Pure function — covered by JVM unit tests.
         */
        fun resolveCallbackFix(
            current: GpsState,
            incoming: GpsFix,
            nowElapsedNs: Long,
        ): Pair<GpsState, Long?> {
            val currentFix = (current as? GpsState.Fix)?.fix
            if (currentFix != null && currentFix.elapsedRealtimeNs >= incoming.elapsedRealtimeNs) {
                return fixWithRemainingLifetime(currentFix, nowElapsedNs, current)
            }
            val age = incoming.ageMs(nowElapsedNs)
            return if (isFreshAge(age, FIX_EXPIRY_MS)) {
                GpsState.Fix(incoming) to (FIX_EXPIRY_MS - age)
            } else {
                GpsState.Stale(incoming) to null
            }
        }

        /**
         * Publish invariant for [applyResolved]: a state published as an active
         * [GpsState.Fix] always comes with an expiry to schedule. [delayMs] is the
         * resolver's decision; when a Fix reaches publication without one, its
         * remaining lifetime is derived here instead of leaving it current forever,
         * and an already-expired retained fix is demoted. Non-Fix states schedule
         * nothing. Pure function — covered by JVM unit tests.
         */
        fun ensureExpiry(state: GpsState, delayMs: Long?, nowElapsedNs: Long): Pair<GpsState, Long?> {
            if (state !is GpsState.Fix) return state to null
            if (delayMs != null) return state to delayMs.coerceAtLeast(0L)
            return fixWithRemainingLifetime(state.fix, nowElapsedNs, state)
        }

        /**
         * Re-derives the expiry of an already-published fix on the monotonic clock:
         * still-fresh fixes are re-published with only their remaining lifetime,
         * expired ones demote to [GpsState.Stale]. [unchanged] is returned as-is when
         * the state must not change. Pure function — covered by JVM unit tests.
         */
        private fun fixWithRemainingLifetime(
            fix: GpsFix,
            nowElapsedNs: Long,
            unchanged: GpsState,
        ): Pair<GpsState, Long?> {
            val age = fix.ageMs(nowElapsedNs)
            return if (isFreshAge(age, FIX_EXPIRY_MS)) {
                unchanged to (FIX_EXPIRY_MS - age)
            } else {
                GpsState.Stale(fix) to null
            }
        }
    }
}
