package vn.vumap.feature.signs

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import vn.vumap.core.model.BoundingBox
import vn.vumap.core.model.GeoPoint
import vn.vumap.core.model.PhysicalSign

/**
 * Lifecycle-owned coordinator for querying and displaying road signs on the map.
 *
 * Implements:
 * - Opt-in demo/synthetic mode (strictly OFF by default).
 * - Bounded, debounced queries with coroutine cancellation (latest-query-wins).
 * - Stale result rejection and duplicate sign key deduplication.
 * - Style reload retention: keeps active signs rendered across style changes.
 * - Honest pack availability and provenance handling.
 * - Map browsing anchor for fixture region (clearly map browsing, never fabricated live GPS).
 */
class SignsOverlayCoordinator(
    private val signProvider: SignProvider,
    private var renderer: SignsMapRenderer? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 250L,
    private val maxLimit: Int = SignProvider.DEFAULT_LIMIT,
    coroutineScope: CoroutineScope? = null
) {
    private val scope: CoroutineScope = coroutineScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ownsScope = coroutineScope == null

    private val _state = MutableStateFlow<SignsOverlayState>(SignsOverlayState.Disabled)
    val state: StateFlow<SignsOverlayState> = _state.asStateFlow()

    private var isEnabled = false
    private var lastBounds: BoundingBox? = null
    private var activeQueryJob: Job? = null
    private var activeQuerySequence: Long = 0L
    private var retainedSigns: List<PhysicalSign> = emptyList()

    /** Set once teardown starts; all further renderer/query work is rejected. */
    private var disposed = false

    /**
     * Toggles demo/synthetic sign display.
     * When disabled, immediately cancels in-flight queries and clears the map overlay.
     */
    fun setEnabled(enabled: Boolean) {
        if (disposed || isEnabled == enabled) return
        isEnabled = enabled

        if (!enabled) {
            cancelActiveQuery()
            retainedSigns = emptyList()
            clearIfAttached()
            _state.value = SignsOverlayState.Disabled
        } else {
            // Trigger query if we already have visible bounds, or check status
            val bounds = lastBounds
            if (bounds != null) {
                dispatchQuery(bounds, immediate = true)
            } else {
                cancelActiveQuery()
                val querySeq = ++activeQuerySequence
                activeQueryJob = scope.launch {
                    val status = withContext(ioDispatcher) { signProvider.packStatus() }
                    if (!isQueryActive(querySeq)) return@launch
                    when (status) {
                        is PackStatus.Unavailable -> {
                            retainedSigns = emptyList()
                            clearIfAttached()
                            _state.value = SignsOverlayState.Unavailable(status.reason, status.errors)
                        }
                        is PackStatus.Ready -> {
                            // Ready but waiting for camera bounds
                            _state.value = SignsOverlayState.Loading(null)
                        }
                    }
                }
            }
        }
    }

    /**
     * Called when the map camera moves or settles.
     * Dispatches a debounced query; any in-flight query is cancelled (latest-query-wins).
     */
    fun onCameraBoundsChanged(bounds: BoundingBox) {
        if (disposed) return
        lastBounds = bounds
        if (!isEnabled) return
        dispatchQuery(bounds, immediate = false)
    }

    /**
     * Re-attaches a new renderer when MapLibre reloads its style.
     * Retains currently rendered signs on the new style without re-fetching.
     *
     * The previous renderer is detached and must not be used again: MapLibre
     * invalidates the old Style as soon as a replacement starts loading.
     */
    fun onStyleReloaded(newRenderer: SignsMapRenderer) {
        if (disposed) {
            newRenderer.detach()
            return
        }
        val previousRenderer = renderer
        renderer = newRenderer
        if (previousRenderer !== newRenderer) {
            previousRenderer?.detach()
        }
        if (!newRenderer.isAttached) return
        if (isEnabled && _state.value is SignsOverlayState.Visible && retainedSigns.isNotEmpty()) {
            newRenderer.renderSigns(retainedSigns)
        } else if (!isEnabled) {
            newRenderer.clearSigns()
        }
    }

    /**
     * Re-queries the active bounds immediately, e.g. after a pack update or switch.
     */
    fun refresh() {
        if (disposed) return
        val bounds = lastBounds
        if (isEnabled && bounds != null) {
            dispatchQuery(bounds, immediate = true)
        } else if (isEnabled) {
            cancelActiveQuery()
            val querySeq = ++activeQuerySequence
            activeQueryJob = scope.launch {
                val status = withContext(ioDispatcher) { signProvider.packStatus() }
                if (!isQueryActive(querySeq)) return@launch
                when (status) {
                    is PackStatus.Unavailable -> {
                        retainedSigns = emptyList()
                        clearIfAttached()
                        _state.value = SignsOverlayState.Unavailable(status.reason, status.errors)
                    }
                    is PackStatus.Ready -> {
                        _state.value = SignsOverlayState.Loading(null)
                    }
                }
            }
        }
    }

    /**
     * Cancels pending jobs and cleans up renderer resources on screen dispose.
     *
     * Idempotent: repeated teardown clears/detaches at most once. If MapLibre has
     * already invalidated the style (map destroyed or a newer style loading), the
     * renderer is only detached and the stale style is never touched.
     */
    fun onDispose() {
        if (disposed) return
        disposed = true
        activeQuerySequence++
        cancelActiveQuery()

        val currentRenderer = renderer
        renderer = null
        if (currentRenderer != null) {
            try {
                if (currentRenderer.isAttached) {
                    currentRenderer.clearSigns()
                }
            } finally {
                currentRenderer.detach()
            }
        }

        if (ownsScope) {
            scope.cancel()
        }
    }

    private fun isQueryActive(querySeq: Long): Boolean =
        !disposed && isEnabled && querySeq == activeQuerySequence

    private fun clearIfAttached() {
        val currentRenderer = renderer ?: return
        if (currentRenderer.isAttached) {
            currentRenderer.clearSigns()
        }
    }

    private fun renderIfAttached(signs: List<PhysicalSign>) {
        val currentRenderer = renderer ?: return
        if (currentRenderer.isAttached) {
            currentRenderer.renderSigns(signs)
        }
    }

    private fun cancelActiveQuery() {
        activeQueryJob?.cancel()
        activeQueryJob = null
    }

    private fun dispatchQuery(rawBounds: BoundingBox, immediate: Boolean) {
        cancelActiveQuery()
        val querySeq = ++activeQuerySequence

        activeQueryJob = scope.launch {
            if (!immediate && debounceMs > 0) {
                delay(debounceMs)
            }
            if (!isQueryActive(querySeq)) return@launch

            _state.value = SignsOverlayState.Loading(rawBounds)

            val queryResult = withContext(ioDispatcher) {
                val packStatus = signProvider.packStatus()
                if (packStatus is PackStatus.Unavailable) {
                    return@withContext QueryOutcome.Unavailable(packStatus.reason, packStatus.errors)
                }
                val ready = packStatus as PackStatus.Ready

                val clampedBounds = clampBounds(rawBounds)
                val rawSigns = signProvider.nearby(clampedBounds, limit = maxLimit)

                // Deduplicate by ID to prevent duplicate keys from crashing or corrupting overlay
                val distinctSigns = rawSigns.distinctBy { it.id }

                QueryOutcome.Success(
                    signs = distinctSigns,
                    bounds = rawBounds,
                    isSynthetic = ready.isSynthetic,
                    packStatus = ready
                )
            }

            // Drop results that completed after a newer query or after teardown.
            if (!isQueryActive(querySeq)) return@launch

            when (queryResult) {
                is QueryOutcome.Unavailable -> {
                    retainedSigns = emptyList()
                    clearIfAttached()
                    _state.value = SignsOverlayState.Unavailable(queryResult.reason, queryResult.errors)
                }
                is QueryOutcome.Success -> {
                    retainedSigns = queryResult.signs
                    renderIfAttached(queryResult.signs)
                    _state.value = SignsOverlayState.Visible(
                        signs = queryResult.signs,
                        bounds = queryResult.bounds,
                        isSynthetic = queryResult.isSynthetic,
                        packStatus = queryResult.packStatus
                    )
                }
            }
        }
    }

    /**
     * Clamps visible bounding box to prevent unbounded or absurdly large queries
     * when the map is zoomed out to a global view.
     */
    private fun clampBounds(bounds: BoundingBox): BoundingBox {
        val latSpan = bounds.maxLat - bounds.minLat
        val lonSpan = bounds.maxLon - bounds.minLon

        // Max query span is 1.0 degree in latitude and longitude (~110 km)
        if (latSpan <= MAX_QUERY_SPAN_DEG && lonSpan <= MAX_QUERY_SPAN_DEG) {
            return bounds
        }

        val centerLat = (bounds.minLat + bounds.maxLat) / 2.0
        val centerLon = (bounds.minLon + bounds.maxLon) / 2.0
        val halfSpan = MAX_QUERY_SPAN_DEG / 2.0

        return BoundingBox(
            minLat = centerLat - halfSpan,
            minLon = centerLon - halfSpan,
            maxLat = centerLat + halfSpan,
            maxLon = centerLon + halfSpan
        )
    }

    private sealed interface QueryOutcome {
        data class Unavailable(val reason: String, val errors: List<String>) : QueryOutcome
        data class Success(
            val signs: List<PhysicalSign>,
            val bounds: BoundingBox,
            val isSynthetic: Boolean,
            val packStatus: PackStatus.Ready
        ) : QueryOutcome
    }

    companion object {
        const val MAX_QUERY_SPAN_DEG = 1.0

        /**
         * Suggested center point for browsing synthetic fixture data (HCMC cluster, 13 points).
         * Explicitly for map browsing only — MUST NEVER be treated as a live GPS fix or origin!
         */
        val FIXTURE_BROWSE_POINT = GeoPoint(10.82310, 106.63000)

        /**
         * Bounding box encompassing the HCMC synthetic fixture cluster.
         */
        val FIXTURE_BOUNDS = BoundingBox(
            minLat = 10.82000,
            minLon = 106.62500,
            maxLat = 10.83500,
            maxLon = 106.64500
        )
    }
}
