package vn.vumap.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapStylesTest {

    @Test
    fun `default style is the OpenFreeMap liberty style`() {
        assertEquals("https://tiles.openfreemap.org/styles/liberty", MapStyles.LIBERTY)
        assertEquals(MapStyles.LIBERTY, UserSettings().styleUrl)
    }

    @Test
    fun `catalogue contains only https style urls`() {
        assertTrue(MapStyles.ALL.isNotEmpty())
        MapStyles.ALL.forEach { (_, url) ->
            assertTrue("insecure or empty style url: $url", url.startsWith("https://"))
            assertTrue("unknown style url: $url", MapStyles.isKnown(url))
        }
    }

    @Test
    fun `unknown style url is sanitized back to liberty`() {
        val sanitized = UserSettings(styleUrl = "https://example.invalid/style.json").sanitized()
        assertEquals(MapStyles.LIBERTY, sanitized.styleUrl)
    }

    @Test
    fun `known style url survives sanitizing`() {
        val settings = UserSettings(styleUrl = MapStyles.BRIGHT).sanitized()
        assertEquals(MapStyles.BRIGHT, settings.styleUrl)
    }

    @Test
    fun `demo signs is off by default and survives sanitizing`() {
        val defaultSettings = UserSettings()
        org.junit.Assert.assertFalse("demoSignsEnabled must be false by default", defaultSettings.demoSignsEnabled)

        val enabledSettings = UserSettings(demoSignsEnabled = true).sanitized()
        assertTrue(enabledSettings.demoSignsEnabled)
    }
}
