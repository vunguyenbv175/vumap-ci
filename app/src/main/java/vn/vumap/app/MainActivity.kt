package vn.vumap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import vn.vumap.app.ui.MapScreen
import vn.vumap.app.ui.SettingsScreen
import vn.vumap.app.ui.theme.VuMapTheme
import vn.vumap.core.location.LocationTracker
import vn.vumap.core.storage.SettingsRepository
import vn.vumap.feature.nav.OrsRoutingProvider
import vn.vumap.feature.nav.OsrmRoutingProvider
import vn.vumap.feature.nav.RoutingChain
import vn.vumap.feature.nav.ValhallaRoutingProvider
import vn.vumap.feature.search.FavoritesRepository
import vn.vumap.feature.search.PhotonSearchProvider
import vn.vumap.feature.search.SearchCoordinator
import vn.vumap.feature.signs.BundledOfflinePackLoader

/**
 * P0 single-activity host.
 *
 * Provides LocationTracker, SettingsRepository, and the P0 RoutingChain
 * (ORS optional key -> OSRM public -> Valhalla public).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge + android:windowSoftInputMode="adjustResize" is the
        // documented pair that delivers IME insets to Compose. Without it the
        // search screen ran in ADJUST_PAN and the keyboard covered results
        // (live device QA 2026-10-21). Scaffolds/TopAppBars in Map/Settings/
        // Search handle the system-bar insets.
        enableEdgeToEdge()
        val tracker = LocationTracker(applicationContext)
        val settingsRepo = SettingsRepository(applicationContext)

        val routingChain = RoutingChain(
            providers = listOf(
                // ORS: optional key (skips cleanly if empty, zero network call, no key leakage)
                OrsRoutingProvider(apiKeyProvider = { "" }),
                // OSRM: public demo provider
                OsrmRoutingProvider(),
                // Valhalla: public format=osrm provider
                ValhallaRoutingProvider(),
            ),
        )

        // P0 Task3: Photon public search (no key) + on-device favorites/history.
        val favoritesRepo = FavoritesRepository(applicationContext)
        val searchCoordinator = SearchCoordinator(
            provider = PhotonSearchProvider(),
            favorites = favoritesRepo,
        )

        val signPackManager = BundledOfflinePackLoader.createActiveSignPackManager(applicationContext)

        setContent {
            VuMapTheme {
                DisposableEffect(signPackManager) {
                    onDispose {
                        signPackManager.close()
                    }
                }
                var showSettings by remember { mutableStateOf(false) }
                if (showSettings) {
                    SettingsScreen(
                        settingsRepo = settingsRepo,
                        signPackManager = signPackManager,
                        onBack = { showSettings = false }
                    )
                } else {
                    MapScreen(
                        tracker = tracker,
                        settingsRepo = settingsRepo,
                        routingChain = routingChain,
                        searchCoordinator = searchCoordinator,
                        favoritesRepo = favoritesRepo,
                        signProvider = signPackManager,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
