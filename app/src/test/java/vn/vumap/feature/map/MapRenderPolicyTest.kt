package vn.vumap.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.core.location.GpsFix
import vn.vumap.core.location.GpsState
import vn.vumap.core.model.GeoPoint

/**
 * Review: the retained stale point must render its dot (frozen, no follow) while
 * only a live Fix drives camera follow — including across style reloads and
 * Settings round trips with no new location.
 */
class MapRenderPolicyTest {

    private val point = GeoPoint(10.7769, 106.7009)
    private fun fix() = GpsFix(point, 5f, 1_700_000_000_000L, 100_000_000_000L)

    @Test
    fun `live fix renders its point and follows when enabled`() {
        val state = GpsState.Fix(fix())
        assertEquals(point, MapRenderPolicy.renderedPoint(state))
        assertTrue(MapRenderPolicy.shouldFollow(state, true))
    }

    @Test
    fun `live fix does not follow when the setting is off`() {
        assertFalse(MapRenderPolicy.shouldFollow(GpsState.Fix(fix()), false))
    }

    @Test
    fun `stale state renders the retained point but never follows`() {
        val state = GpsState.Stale(fix())
        assertEquals(point, MapRenderPolicy.renderedPoint(state))
        assertFalse(MapRenderPolicy.shouldFollow(state, true))
    }

    @Test
    fun `only a live fix may start navigation from a search pick`() {
        assertEquals(point, MapRenderPolicy.routeOrigin(GpsState.Fix(fix())))
    }

    @Test
    fun `stale fix never starts navigation even though it still renders a dot`() {
        assertNull(MapRenderPolicy.routeOrigin(GpsState.Stale(fix())))
    }

    @Test
    fun `no-fix states never start navigation`() {
        listOf(
            GpsState.NoFix,
            GpsState.NoPermission,
            GpsState.Unavailable("ApiException"),
        ).forEach { state ->
            assertNull(MapRenderPolicy.routeOrigin(state))
        }
    }

    @Test
    fun `no-fix states render nothing and never follow`() {
        listOf(
            GpsState.NoFix,
            GpsState.NoPermission,
            GpsState.Unavailable("ApiException"),
        ).forEach { state ->
            assertNull(MapRenderPolicy.renderedPoint(state))
            assertFalse(MapRenderPolicy.shouldFollow(state, true))
        }
    }
}
