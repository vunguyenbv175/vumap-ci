package vn.vumap.core.model

/**
 * Map style catalogue for the P0 skeleton.
 *
 * Online raster/vector styles served by OpenFreeMap (https://openfreemap.org).
 * Default is the `liberty` style required by project plan section 3.
 * No offline tile region is bundled in P0; offline map is an explicit P1 goal and
 * MUST NOT be claimed from this code (offline-honest rule).
 */
object MapStyles {
    const val LIBERTY = "https://tiles.openfreemap.org/styles/liberty"
    const val BRIGHT = "https://tiles.openfreemap.org/styles/bright"
    const val POSITRON = "https://tiles.openfreemap.org/styles/positron"

    val ALL: List<Pair<String, String>> = listOf(
        "Liberty" to LIBERTY,
        "Bright" to BRIGHT,
        "Positron" to POSITRON,
    )

    fun isKnown(styleUrl: String): Boolean = ALL.any { it.second == styleUrl }
}

/**
 * Local, on-device user settings. Persisted with DataStore, never synced anywhere
 * (privacy/no-telemetry rule).
 */
data class UserSettings(
    val followLocation: Boolean = true,
    val styleUrl: String = MapStyles.LIBERTY,
    val demoSignsEnabled: Boolean = false,
) {
    /** Unknown style URLs (e.g. from a corrupt preferences file) fall back to Liberty. */
    fun sanitized(): UserSettings =
        if (MapStyles.isKnown(styleUrl)) this else copy(styleUrl = MapStyles.LIBERTY)
}
