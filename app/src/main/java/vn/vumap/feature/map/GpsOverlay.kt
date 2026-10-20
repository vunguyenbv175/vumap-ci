package vn.vumap.feature.map

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import vn.vumap.core.model.GeoPoint

/**
 * GPS dot overlay drawn with plain MapLibre runtime styling (no extra plugin dependency).
 *
 * P0 shows a single "you are here" dot. Accuracy is reported as text in the status bar;
 * no accuracy ring is drawn because a correct meters-to-pixels conversion is zoom-dependent
 * and a fixed-pixel ring would mislead (offline-honest / no-fake-precision rule).
 */
object GpsOverlay {
    const val SOURCE_ID = "vumap-gps-source"
    const val DOT_LAYER_ID = "vumap-gps-dot"

    fun ensure(style: Style) {
        if (style.getSource(SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(SOURCE_ID))
        }
        if (style.getLayer(DOT_LAYER_ID) == null) {
            style.addLayer(
                CircleLayer(DOT_LAYER_ID, SOURCE_ID).withProperties(
                    PropertyFactory.circleRadius(9f),
                    PropertyFactory.circleColor("#1A73E8"),
                    PropertyFactory.circleStrokeWidth(3f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleOpacity(1f),
                ),
            )
        }
    }

    fun update(style: Style, point: GeoPoint) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
    }
}

/** Camera helpers. */
object MapCamera {
    const val DEFAULT_ZOOM = 16.0

    fun follow(map: MapLibreMap, point: GeoPoint, zoom: Double = DEFAULT_ZOOM) {
        map.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                LatLng(point.latitude, point.longitude),
                zoom,
            ),
            600,
        )
    }
}
