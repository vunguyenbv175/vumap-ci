package vn.vumap.feature.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import vn.vumap.core.model.PhysicalSign
import vn.vumap.feature.signs.SignsMapRenderer

/**
 * Concrete [SignsMapRenderer] implementation backed by MapLibre runtime styling.
 */
class MapLibreSignsRenderer(private val style: Style) : SignsMapRenderer {
    private var detached = false

    /**
     * `Style.isFullyLoaded()` is the only public MapLibre check that is safe while a
     * style is being replaced. MapLibre's `Style.clear()` flips it to false when a
     * newer style starts loading or the map is destroyed; every other accessor
     * (including `getSource`/`getSourceAs`) then throws IllegalStateException.
     */
    override val isAttached: Boolean
        get() = !detached && style.isFullyLoaded

    override fun renderSigns(signs: List<PhysicalSign>) {
        if (!isAttached) return
        SignsOverlay.drawSigns(style, signs)
    }

    override fun clearSigns() {
        if (!isAttached) return
        SignsOverlay.clear(style)
    }

    override fun detach() {
        detached = true
    }
}

/**
 * MapLibre signs overlay: renders road signs as distinct markers using GeoJsonSource.
 *
 * Placed below the GPS dot layer so the user's location is always on top.
 * Synthetic/demo points are styled with high-contrast amber/orange markers
 * to visibly distinguish them from production map styling.
 */
object SignsOverlay {
    const val SOURCE_ID = "vumap-signs-source"
    const val CIRCLE_LAYER_ID = "vumap-signs-circle"
    const val CASING_LAYER_ID = "vumap-signs-casing"

    fun ensure(style: Style) {
        if (style.getSource(SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(SOURCE_ID))
        }

        val gpsLayerId = GpsOverlay.DOT_LAYER_ID
        val belowGps = style.getLayer(gpsLayerId) != null

        if (style.getLayer(CASING_LAYER_ID) == null) {
            val casing = CircleLayer(CASING_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleColor("#FFFFFF"),
                PropertyFactory.circleOpacity(0.9f),
            )
            if (belowGps) {
                style.addLayerBelow(casing, gpsLayerId)
            } else {
                style.addLayer(casing)
            }
        }

        if (style.getLayer(CIRCLE_LAYER_ID) == null) {
            val circle = CircleLayer(CIRCLE_LAYER_ID, SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor("#E65100"), // Warning amber/orange for synthetic test signs
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleOpacity(1f),
            )
            if (belowGps) {
                style.addLayerBelow(circle, gpsLayerId)
            } else {
                style.addLayer(circle)
            }
        }
    }

    /**
     * Updates the map signs layer with [signs].
     */
    fun drawSigns(style: Style, signs: List<PhysicalSign>) {
        ensure(style)
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        val features = signs.map { sign ->
            val point = Point.fromLngLat(sign.location.longitude, sign.location.latitude)
            val feature = Feature.fromGeometry(point)
            feature.addStringProperty("id", sign.id)
            feature.addStringProperty("type", sign.type.name)
            feature.addBooleanProperty("isSynthetic", sign.isSynthetic)
            sign.valueKmh?.let { feature.addNumberProperty("valueKmh", it) }
            feature
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /**
     * Clears all sign markers from the map.
     */
    fun clear(style: Style) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
        source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }
}
