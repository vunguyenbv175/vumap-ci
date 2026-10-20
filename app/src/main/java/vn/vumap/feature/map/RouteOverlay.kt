package vn.vumap.feature.map

import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import vn.vumap.core.model.GeoPoint
import vn.vumap.feature.nav.Route

/**
 * MapLibre route line overlay (project plan section 3, 9; requirement 4: "Ve route line dau tien tren MapLibre map thuc").
 *
 * Renders a crisp navigation polyline with round caps and dark-blue casing for contrast
 * on OpenFreeMap vector styles. Drawn beneath the GPS dot so the user position remains visible.
 */
object RouteOverlay {
    const val SOURCE_ID = "vumap-route-source"
    const val CASING_LAYER_ID = "vumap-route-casing"
    const val LINE_LAYER_ID = "vumap-route-line"

    fun ensure(style: Style) {
        if (style.getSource(SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(SOURCE_ID))
        }

        // Add below GPS dot if GPS layer is already attached
        val gpsLayerId = GpsOverlay.DOT_LAYER_ID
        val belowGps = style.getLayer(gpsLayerId) != null

        if (style.getLayer(CASING_LAYER_ID) == null) {
            val casing = LineLayer(CASING_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.lineColor("#0D47A1"),
                PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(0.9f),
            )
            if (belowGps) {
                style.addLayerBelow(casing, gpsLayerId)
            } else {
                style.addLayer(casing)
            }
        }

        if (style.getLayer(LINE_LAYER_ID) == null) {
            val line = LineLayer(LINE_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.lineColor("#1A73E8"),
                PropertyFactory.lineWidth(5.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOpacity(1f),
            )
            if (belowGps) {
                style.addLayerBelow(line, gpsLayerId)
            } else {
                style.addLayer(line)
            }
        }
    }

    /**
     * Updates the map route line with coordinates from [route].
     */
    fun drawRoute(style: Style, route: Route) {
        ensure(style)
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        val points = route.geometry.map { Point.fromLngLat(it.longitude, it.latitude) }
        if (points.size >= 2) {
            source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points)))
        } else {
            clear(style)
        }
    }

    /**
     * Clears the route line from the map.
     */
    fun clear(style: Style) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(emptyList())))
    }

    /**
     * Smoothly animates the camera to encompass the entire route.
     */
    fun fitCameraToRoute(map: MapLibreMap, route: Route, paddingPx: Int = 120) {
        if (route.geometry.size < 2) return
        val builder = LatLngBounds.Builder()
        for (pt in route.geometry) {
            builder.include(LatLng(pt.latitude, pt.longitude))
        }
        val bounds = builder.build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx), 800)
    }
}
