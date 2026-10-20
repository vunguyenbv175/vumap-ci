package vn.vumap.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import vn.vumap.core.location.GpsState
import vn.vumap.core.location.LocationTracker
import vn.vumap.core.model.GeoPoint
import vn.vumap.core.model.UserSettings
import vn.vumap.core.storage.SettingsRepository
import vn.vumap.feature.map.GpsOverlay
import vn.vumap.feature.map.MapCamera
import vn.vumap.feature.map.MapRenderPolicy
import vn.vumap.feature.map.RouteOverlay
import vn.vumap.feature.nav.NavigationRenderPolicy
import vn.vumap.feature.nav.NavigationVoice
import vn.vumap.feature.nav.OfflineFallback
import vn.vumap.feature.nav.OrsRoutingProvider
import vn.vumap.feature.nav.OsrmRoutingProvider
import vn.vumap.feature.nav.Route
import vn.vumap.feature.nav.RouteCoordinator
import vn.vumap.feature.nav.RoutingChain
import vn.vumap.feature.nav.ValhallaRoutingProvider
import vn.vumap.feature.search.FavoritesRepository
import vn.vumap.feature.search.PhotonSearchProvider
import vn.vumap.feature.search.SearchCoordinator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.rememberUpdatedState
import vn.vumap.core.model.BoundingBox
import vn.vumap.feature.map.MapLibreSignsRenderer
import vn.vumap.feature.map.SignsOverlay
import vn.vumap.feature.signs.ActiveSignPackManager
import vn.vumap.feature.signs.BundledOfflinePackLoader
import vn.vumap.feature.signs.OfflineSignRepository
import vn.vumap.feature.signs.SignProvider
import vn.vumap.feature.signs.SignsOverlayCoordinator
import vn.vumap.feature.signs.SignsOverlayState

/**
 * P0 map screen: MapLibre map + OpenFreeMap online style + GPS dot + Routing line + TTS guidance.
 *
 * (project plan section 3, 4, 9; requirement 4)
 * - Draws first route line on real MapLibre map.
 * - Basic Vietnamese TTS for turn guidance.
 * - Displays active provider name and failure/cooldown status.
 * - Offline honest: cached prior route explicitly tagged and displayed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    tracker: LocationTracker,
    settingsRepo: SettingsRepository,
    routingChain: RoutingChain = remember {
        RoutingChain(
            listOf(
                OrsRoutingProvider(apiKeyProvider = { "" }),
                OsrmRoutingProvider(),
                ValhallaRoutingProvider(),
            ),
        )
    },
    searchCoordinator: SearchCoordinator = remember {
        SearchCoordinator(PhotonSearchProvider())
    },
    favoritesRepo: FavoritesRepository? = null,
    signProvider: SignProvider? = null,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = UserSettings())
    val gpsState by tracker.state.collectAsStateWithLifecycle(initialValue = GpsState.NoFix)

    val appContext = context.applicationContext

    // Background offline sign repository (lifecycle-owned, closed on dispose)
    var localManager by remember { mutableStateOf<ActiveSignPackManager?>(null) }
    LaunchedEffect(Unit) {
        if (signProvider == null) {
            val manager = BundledOfflinePackLoader.createActiveSignPackManager(appContext)
            manager.initialize()
            localManager = manager
        }
    }
    val currentLocalManager = localManager
    DisposableEffect(currentLocalManager) {
        onDispose {
            currentLocalManager?.close()
        }
    }

    val activeSignProvider = signProvider ?: localManager
    val signsCoordinator = remember(activeSignProvider) {
        activeSignProvider?.let { provider ->
            SignsOverlayCoordinator(
                signProvider = provider,
                coroutineScope = scope
            )
        }
    }
    val currentCoordinator = signsCoordinator
    DisposableEffect(currentCoordinator) {
        onDispose {
            currentCoordinator?.onDispose()
        }
    }

    val signsOverlayState by signsCoordinator?.state?.collectAsStateWithLifecycle(initialValue = SignsOverlayState.Disabled)
        ?: remember { mutableStateOf(SignsOverlayState.Disabled) }

    LaunchedEffect(signsCoordinator, settings.demoSignsEnabled) {
        signsCoordinator?.setEnabled(settings.demoSignsEnabled)
    }

    LaunchedEffect(activeSignProvider, signsCoordinator) {
        if (activeSignProvider is ActiveSignPackManager) {
            activeSignProvider.activePackState.collect {
                signsCoordinator?.refresh()
            }
        }
    }

    val coordinator = remember { RouteCoordinator(routingChain) }
    val navState by coordinator.state.collectAsStateWithLifecycle()

    // Key of the navigation session already announced (destination + provider),
    // so the departure summary speaks once while step prompts keep flowing.
    var announcedNavKey by remember { mutableStateOf<String?>(null) }
    // Arrival speaks once per navigation session, not on every progress update.
    var arrivalAnnounced by remember { mutableStateOf(false) }

    val voice = remember { NavigationVoice(context) }
    DisposableEffect(voice) {
        onDispose { voice.destroy() }
    }
    DisposableEffect(coordinator) {
        onDispose { coordinator.close() }
    }

    var hasPermission by remember { mutableStateOf(tracker.hasPermission()) }
    var mapboxMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var permissionDialogPending by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // Set when a search pick is refused for lack of a live GPS fix; shown as
    // a dismissible notice over the map (never silent, never an invented origin).
    var noOriginMessage by remember { mutableStateOf<String?>(null) }

    // On-device favorites/history (falls back to a local instance when the host
    // does not inject one). Search stays user-initiated; it is never polled.
    val searchFavorites = favoritesRepo ?: remember { FavoritesRepository(appContext) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionDialogPending = false
        hasPermission = tracker.hasPermission()
        if (hasPermission) tracker.start()
    }

    fun ensureTracking() {
        if (tracker.hasPermission()) {
            hasPermission = true
            permissionDialogPending = false
            tracker.start()
        } else if (!permissionDialogPending) {
            permissionDialogPending = true
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    LaunchedEffect(Unit) {
        ensureTracking()
    }
    DisposableEffect(Unit) {
        onDispose { tracker.stop() }
    }

    val currentSignsCoordinator by rememberUpdatedState(signsCoordinator)
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                mapboxMap = map
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = true
                map.addOnCameraIdleListener {
                    val bounds = map.projection.visibleRegion.latLngBounds
                    currentSignsCoordinator?.onCameraBoundsChanged(
                        BoundingBox(
                            minLat = bounds.latitudeSouth,
                            minLon = bounds.longitudeWest,
                            maxLat = bounds.latitudeNorth,
                            maxLon = bounds.longitudeEast
                        )
                    )
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        var destroyed = false
        fun destroyMapOnce() {
            if (!destroyed) {
                destroyed = true
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    mapView.onStart()
                    ensureTracking()
                }
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> {
                    mapView.onStop()
                    tracker.stop()
                }
                Lifecycle.Event.ON_DESTROY -> destroyMapOnce()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroyMapOnce()
        }
    }

    // (Re)load style: attach GPS + route overlays and restore the retained
    // point (live or stale). Camera follow stays restricted to live fixes
    // outside active navigation.
    LaunchedEffect(mapboxMap, settings.styleUrl) {
        val map = mapboxMap ?: return@LaunchedEffect
        // MapLibre invalidates the previous Style synchronously inside setStyle(),
        // so drop it before starting a load. Overlay effects must never touch a
        // Style while a newer style is loading (its accessors throw).
        loadedStyle = null
        map.setStyle(Style.Builder().fromUri(settings.styleUrl)) { style ->
            if (!style.isFullyLoaded) return@setStyle
            GpsOverlay.ensure(style)
            RouteOverlay.ensure(style)
            SignsOverlay.ensure(style)
            loadedStyle = style
            signsCoordinator?.onStyleReloaded(MapLibreSignsRenderer(style))
            MapRenderPolicy.renderedPoint(gpsState)?.let { point ->
                GpsOverlay.update(style, point)
                if (MapRenderPolicy.shouldFollow(gpsState, settings.followLocation) &&
                    navState !is RouteCoordinator.NavigationState.Navigating
                ) {
                    MapCamera.follow(map, point)
                }
            }

            // If navigating, restore route polyline
            val activeNav = navState as? RouteCoordinator.NavigationState.Navigating
            if (activeNav != null) {
                RouteOverlay.drawRoute(style, activeNav.route)
            }
        }
    }

    // When signs coordinator or loaded style updates, ensure the overlay is attached
    // and query visible camera bounds if available.
    LaunchedEffect(signsCoordinator, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        val coord = signsCoordinator ?: return@LaunchedEffect
        coord.onStyleReloaded(MapLibreSignsRenderer(style))
        mapboxMap?.projection?.visibleRegion?.latLngBounds?.let { bounds ->
            coord.onCameraBoundsChanged(
                BoundingBox(
                    minLat = bounds.latitudeSouth,
                    minLon = bounds.longitudeWest,
                    maxLat = bounds.latitudeNorth,
                    maxLon = bounds.longitudeEast
                )
            )
        }
    }

    // GPS updates: render retained point, follow live fixes, feed coordinator.
    // The coordinator call never suspends the effect (single-flight in a
    // stable scope), so 1 Hz fixes cannot cancel an in-flight re-route.
    LaunchedEffect(gpsState, loadedStyle, settings.followLocation) {
        val style = loadedStyle ?: return@LaunchedEffect
        val point = MapRenderPolicy.renderedPoint(gpsState) ?: return@LaunchedEffect
        GpsOverlay.update(style, point)

        // Pass live fixes to route coordinator (filters 1Hz ticks; re-routes only if off-route)
        (gpsState as? GpsState.Fix)?.fix?.let { coordinator.onGpsTick(it.point) }

        if (MapRenderPolicy.shouldFollow(gpsState, settings.followLocation) &&
            navState !is RouteCoordinator.NavigationState.Navigating
        ) {
            mapboxMap?.let { MapCamera.follow(it, point) }
        }
    }

    // React to navigation state changes.
    //
    // The painted route is retained across re-route attempts (Calculating /
    // Error) so the line never flickers, and cleared only on Idle. Drawing and
    // camera fitting then key off that route alone: step-progress updates
    // (quantized remaining distance) must never restart the 800 ms full-route
    // animation or overwrite a manual pan/zoom for an unchanged route.
    var drawnRoute by remember { mutableStateOf<Route?>(null) }
    LaunchedEffect(navState) {
        drawnRoute = NavigationRenderPolicy.paintedRoute(drawnRoute, navState)
    }
    LaunchedEffect(drawnRoute, loadedStyle) {
        val style = loadedStyle ?: return@LaunchedEffect
        val route = drawnRoute
        if (route == null) {
            RouteOverlay.clear(style)
        } else {
            RouteOverlay.drawRoute(style, route)
            mapboxMap?.let { RouteOverlay.fitCameraToRoute(it, route) }
        }
    }

    // Voice guidance follows every navigation state change: the departure
    // summary once per route, the upcoming maneuver with its remaining distance
    // on each update, and arrival once.
    LaunchedEffect(navState) {
        when (val state = navState) {
            is RouteCoordinator.NavigationState.Navigating -> {
                val navKey = "${state.destination.latitude},${state.destination.longitude}@${state.provider}"
                if (announcedNavKey != navKey) {
                    announcedNavKey = navKey
                    arrivalAnnounced = false
                    val distStr = formatDistance(state.route.distanceMeters)
                    voice.speak("Bắt đầu dẫn đường bằng ${state.provider}. Tuyến đường dài $distStr.")
                }
                if (state.hasArrived) {
                    if (!arrivalAnnounced) {
                        arrivalAnnounced = true
                        voice.speak("Bạn đã đến nơi.")
                    }
                } else {
                    state.route.steps.getOrNull(state.stepIndex)?.let { step ->
                        voice.announceStep(step, state.distanceToNextStepM)
                    }
                }
            }
            is RouteCoordinator.NavigationState.Idle -> {
                voice.stop()
                announcedNavKey = null
                arrivalAnnounced = false
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VUMAP") },
                actions = {
                    IconButton(onClick = { showSearch = true }) {
                        Icon(Icons.Filled.Search, contentDescription = "Tìm địa điểm")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Cài đặt")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            // TOP PANEL: NAVIGATION BANNER + DEMO SIGNS LABEL
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (val state = navState) {
                    is RouteCoordinator.NavigationState.Navigating -> {
                        NavigationCard(
                            navigating = state,
                            onCancel = { coordinator.stopNavigation() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is RouteCoordinator.NavigationState.Calculating -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text("Đang tính toán tuyến đường…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    is RouteCoordinator.NavigationState.Error -> {
                        val fallbackDest = coordinator.lastDestination()
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = "Lỗi: ${state.message}",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { coordinator.stopNavigation() }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Đóng")
                                    }
                                }
                                // Honest offline fallback: VUMAP P0 cannot route
                                // on-device, so hand the destination to an installed
                                // offline map app instead of claiming offline routing.
                                if (fallbackDest != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = "Không tính được tuyến offline trong app. " +
                                            "Bạn có thể mở điểm đến bằng app bản đồ " +
                                            "offline đã cài (Organic Maps/OsmAnd).",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Button(onClick = { openOfflineMap(context, fallbackDest) }) {
                                        Text("Mở app bản đồ ngoài")
                                    }
                                }
                            }
                        }
                    }
                    is RouteCoordinator.NavigationState.Idle -> Unit
                }

                // Opt-in Demo / Synthetic Signs Banner (Permanently visible when active)
                if (settings.demoSignsEnabled) {
                    DemoSignsBanner(
                        state = signsOverlayState,
                        onBrowseFixture = {
                            mapboxMap?.let { map ->
                                MapCamera.follow(map, SignsOverlayCoordinator.FIXTURE_BROWSE_POINT, zoom = 14.5)
                            }
                        },
                        onDisable = {
                            scope.launch { settingsRepo.setDemoSignsEnabled(false) }
                        },
                    )
                }
            }

            // BOTTOM PANEL: Quick Route Test Buttons + GPS Status Line.
            //
            // The status card is tappable only while the location permission is
            // missing: it opens this app's permission page in system settings. The
            // in-app permission dialog is re-launched on every foreground entry
            // while it is still available, but after the system stops offering it,
            // settings is the only truthful next step.
            val permissionMissing = !hasPermission || gpsState is GpsState.NoPermission
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                if (navState is RouteCoordinator.NavigationState.Idle) {
                    QuickRouteChips(
                        onSelectRoute = { from, to ->
                            scope.launch {
                                val currentFix = (gpsState as? GpsState.Fix)?.fix?.point
                                val effectiveFrom = currentFix ?: from
                                coordinator.requestRoute(effectiveFrom, to, force = true)
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (permissionMissing) {
                                Modifier.clickable { openAppPermissionSettings(context) }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(
                        text = gpsStatusLine(hasPermission, gpsState),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // P0 Task3: user-initiated search overlay. Picking a result routes
            // only from a live GPS fix; with no fix the user gets a clear
            // wait-for-GPS message and no navigation starts.
            if (showSearch) {
                SearchScreen(
                    coordinator = searchCoordinator,
                    favoritesRepo = searchFavorites,
                    userLocation = (gpsState as? GpsState.Fix)?.fix?.point,
                    onPick = { result ->
                        val origin = MapRenderPolicy.routeOrigin(gpsState)
                        if (origin == null) {
                            noOriginMessage = noOriginText(gpsState)
                        } else {
                            showSearch = false
                            scope.launch {
                                coordinator.requestRoute(origin, result.point, force = true)
                            }
                        }
                    },
                    onClose = { showSearch = false },
                )
            }
            // Refusal notice: picking a result with no live fix explains why
            // no route started instead of silently doing nothing.
            noOriginMessage?.let { message ->
                NoOriginNotice(
                    message = message,
                    onDismiss = { noOriginMessage = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                )
            }
        }
    }
}

/**
 * Dismissible notice shown when a search pick cannot start navigation because
 * there is no live GPS fix. Rendered over the map bottom panel; purely
 * informational — it never starts guidance by itself.
 */
@Composable
private fun NoOriginNotice(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onDismiss) {
                Text("Đã hiểu")
            }
        }
    }
}

/**
 * Explains why navigation cannot start from [state]: denied permission,
 * initial acquisition, stale fix, or unavailable provider. Never names an
 * invented origin — the user must wait for (or enable) a live fix.
 */
private fun noOriginText(state: GpsState): String = when (state) {
    is GpsState.NoPermission ->
        "Chưa được cấp quyền vị trí — hãy cấp quyền trong cài đặt hệ thống " +
            "rồi chọn lại điểm đến. Chưa bắt đầu dẫn đường."
    is GpsState.NoFix ->
        "Đang chờ tín hiệu GPS — hãy đợi có vị trí hiện tại rồi chọn lại " +
            "điểm đến. Chưa bắt đầu dẫn đường."
    is GpsState.Stale -> {
        val ageS = (state.fix.ageMs(SystemClock.elapsedRealtimeNanos()) / 1_000L).coerceAtLeast(0L)
        "Vị trí GPS đã cũ (~${ageS}s), đang chờ tín hiệu mới — " +
            "không dẫn đường từ vị trí cũ. Hãy đợi GPS ổn định rồi chọn lại."
    }
    is GpsState.Unavailable ->
        "Dịch vụ định vị không khả dụng trên thiết bị này — " +
            "không thể bắt đầu dẫn đường."
    is GpsState.Fix ->
        // Unreachable: callers only ask when routeOrigin() returned null.
        "Chưa có vị trí hiện tại — hãy đợi GPS rồi chọn lại điểm đến."
}

/**
 * Navigation banner displayed when actively navigating.
 */
@Composable
private fun NavigationCard(
    navigating: RouteCoordinator.NavigationState.Navigating,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val route = navigating.route
    val nextStep = route.steps.getOrNull(navigating.stepIndex)

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Chỉ dẫn",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = nextStep?.instruction ?: "Đi tiếp theo tuyến đường",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = "Hủy dẫn đường")
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${formatDistance(route.distanceMeters)} • ${formatDuration(route.durationSeconds)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Provider badge
                val badgeColor = if (navigating.isFromCache) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = badgeColor),
                ) {
                    Text(
                        text = navigating.provider,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * Quick route selector for testing on actual device (TP.HCM, Ha Noi, Da Nang).
 */
@Composable
private fun QuickRouteChips(
    onSelectRoute: (from: GeoPoint, to: GeoPoint) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Tuyến mẫu:", style = MaterialTheme.typography.labelMedium)

            Button(
                onClick = {
                    onSelectRoute(
                        GeoPoint(10.772500, 106.698000), // Bến Thành
                        GeoPoint(10.776800, 106.703200), // Nhà hát TP
                    )
                },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text("TP.HCM")
            }

            Button(
                onClick = {
                    onSelectRoute(
                        GeoPoint(21.028500, 105.854200), // Bờ Hồ
                        GeoPoint(21.036800, 105.834600), // Lăng Bác
                    )
                },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text("Hà Nội")
            }

            Button(
                onClick = {
                    onSelectRoute(
                        GeoPoint(16.061000, 108.223000), // Cầu Rồng
                        GeoPoint(16.100000, 108.277000), // Chùa Linh Ứng
                    )
                },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text("Đà Nẵng")
            }
        }
    }
}

/**
 * Hands [destination] to an installed offline map app via a `geo:` Intent.
 * Shows a toast instead of crashing when no handler is installed.
 */
private fun openOfflineMap(context: Context, destination: GeoPoint) {
    try {
        val uri = android.net.Uri.parse(OfflineFallback.geoUri(destination))
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "Không tìm thấy app bản đồ ngoài. Hãy cài Organic Maps hoặc OsmAnd.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun gpsStatusLine(hasPermission: Boolean, state: GpsState): String {
    if (!hasPermission || state is GpsState.NoPermission) {
        return "Chưa được cấp quyền vị trí — chạm để mở cài đặt quyền của ứng dụng."
    }
    (state as? GpsState.Fix)?.fix?.let { fix ->
        val acc = fix.accuracyMeters?.let { " ±${it.toInt()} m" } ?: ""
        return "GPS: ${formatDeg(fix.point)}, sai số$acc"
    }
    (state as? GpsState.Stale)?.fix?.let { fix ->
        val ageS = (fix.ageMs(SystemClock.elapsedRealtimeNanos()) / 1_000L).coerceAtLeast(0L)
        return "Vị trí GPS đã cũ (~${ageS}s), đang chờ tín hiệu mới… ${formatDeg(fix.point)}"
    }
    if (state is GpsState.Unavailable) {
        return "Dịch vụ định vị không khả dụng trên thiết bị này — thử mở lại màn hình."
    }
    return "Đang chờ tín hiệu GPS…"
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000) "${"%.1f".format(meters / 1000.0)} km" else "${meters.toInt()} m"

private fun formatDuration(seconds: Double): String {
    val mins = (seconds / 60).toInt()
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "$mins phút"
}

private fun formatDeg(p: GeoPoint): String =
    "${"%.5f".format(p.latitude)}, ${"%.5f".format(p.longitude)}"

/** Opens this app's permission page in system settings (used by the missing-permission card). */
private fun openAppPermissionSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ),
    )
}

/**
 * Permanently visible banner displayed whenever opt-in synthetic demo sign mode is active.
 *
 * Guarantees:
 * - Prominently displays "THỬ NGHIỆM / DỮ LIỆU GIẢ LẬP".
 * - Clearly states points are synthetic fixtures and NEVER surveyed real signs.
 * - Fixture browsing is explicitly offered as map browsing only, never fabricating live GPS origin.
 */
@Composable
private fun DemoSignsBanner(
    state: SignsOverlayState,
    onBrowseFixture: () -> Unit,
    onDisable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "THỬ NGHIỆM / DỮ LIỆU GIẢ LẬP",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDisable) {
                    Icon(Icons.Filled.Close, contentDescription = "Tắt chế độ thử nghiệm")
                }
            }

            Spacer(Modifier.height(4.dp))

            when (state) {
                is SignsOverlayState.Visible -> {
                    Text(
                        text = "Đang hiển thị ${state.signs.size} biển báo mẫu (${state.packStatus.source}) • Tuyệt đối không dùng cho dẫn đường thực tế",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                is SignsOverlayState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Đang tải dữ liệu biển báo mẫu…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                is SignsOverlayState.Unavailable -> {
                    Text(
                        text = "Gói biển báo ngoại tuyến chưa sẵn sàng: ${state.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is SignsOverlayState.Disabled -> {
                    Text(
                        text = "Đang tắt lớp phủ biển báo mẫu",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onBrowseFixture,
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text("Xem vùng mẫu (Duyệt bản đồ)")
                }
            }
        }
    }
}
