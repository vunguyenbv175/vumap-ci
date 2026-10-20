package vn.vumap.feature.signs

import vn.vumap.core.model.PhysicalSign

/**
 * Rendering sink for displaying road signs on a map.
 *
 * Decouples sign querying and coordinator lifecycle from concrete map rendering engines
 * (e.g. MapLibre Native Style/Layers on Android), enabling host-side JVM unit tests
 * without OpenGL or Android runtime dependencies.
 */
interface SignsMapRenderer {
    /**
     * Whether the renderer's map style is still valid and ready to receive updates.
     *
     * Implementations must return false once their style has been detached, replaced,
     * or is being replaced by MapLibre. Callers must not invoke [renderSigns] or
     * [clearSigns] while this is false.
     */
    val isAttached: Boolean

    /**
     * Draws [signs] on the map layer.
     */
    fun renderSigns(signs: List<PhysicalSign>)

    /**
     * Clears all rendered signs from the map layer.
     */
    fun clearSigns()

    /**
     * Releases this renderer's map/style reference. After this call [isAttached]
     * must be false and the renderer must never touch the map again.
     *
     * The coordinator owns renderer attachment; the map style itself is owned by
     * MapLibre and must not be mutated here during teardown.
     */
    fun detach() = Unit
}
