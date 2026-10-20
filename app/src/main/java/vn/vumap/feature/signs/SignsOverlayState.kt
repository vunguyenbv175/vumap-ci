package vn.vumap.feature.signs

import vn.vumap.core.model.BoundingBox
import vn.vumap.core.model.PhysicalSign

/**
 * Observable state of the offline signs map overlay.
 */
sealed interface SignsOverlayState {
    /**
     * Demo/synthetic sign overlay is disabled by the user (the default state).
     */
    data object Disabled : SignsOverlayState

    /**
     * Query is currently executing in background for [bounds].
     */
    data class Loading(
        val bounds: BoundingBox?
    ) : SignsOverlayState

    /**
     * Signs are queried and rendered on the map.
     *
     * [isSynthetic] must always be true for synthetic fixture packs,
     * enforcing the display of the test/demo label.
     */
    data class Visible(
        val signs: List<PhysicalSign>,
        val bounds: BoundingBox,
        val isSynthetic: Boolean,
        val packStatus: PackStatus.Ready
    ) : SignsOverlayState

    /**
     * Pack is unavailable, corrupted, or failed validation.
     * Reported honestly with zero fabricated points.
     */
    data class Unavailable(
        val reason: String,
        val errors: List<String> = emptyList()
    ) : SignsOverlayState
}
