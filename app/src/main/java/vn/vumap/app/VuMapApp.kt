package vn.vumap.app

import android.app.Application
import org.maplibre.android.MapLibre

/**
 * Application entry point.
 *
 * Initializes the MapLibre SDK once, before any [org.maplibre.android.maps.MapView]
 * is constructed. Without this, first-screen map construction throws
 * `MapLibreConfigurationException` instead of displaying the map.
 */
class VuMapApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // No API key: OpenFreeMap styles need none. Keeps the documented init order.
        MapLibre.getInstance(this)
    }
}
