package vn.vumap.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.vumap.core.model.GeoPoint
import vn.vumap.feature.search.FavoritesRepository
import vn.vumap.feature.search.SearchCoordinator
import vn.vumap.feature.search.SearchDistance
import vn.vumap.feature.search.SearchGrouping
import vn.vumap.feature.search.SearchOutcome
import vn.vumap.feature.search.SearchResult
import vn.vumap.feature.search.SearchStore
import vn.vumap.feature.search.VerificationLabels
import vn.vumap.feature.search.VerificationLevel

/**
 * P0 search screen (project plan section 5).
 *
 * - Opens with the field focused and the IME up; [imePadding] + the activity's
 *   `adjustResize` keep the result list above the keyboard (live device QA
 *   2026-10-21: the old ADJUST_PAN window let the keyboard cover results).
 * - One compact header row (back + field) and one single-line hint/legend
 *   replace the old duplicated app bar and four-line help paragraph.
 * - Results show honest PIN-PRECISION labels, not a claim that the place is
 *   the one the user meant. Vietnamese candidates are grouped by locality;
 *   foreign results (outside V1 scope) get one clearly labelled section.
 * - Distance is shown only when the caller passes a real live fix; no origin
 *   is invented.
 * - Saved favorites + recent history stay on-device only.
 */
@Composable
fun SearchScreen(
    coordinator: SearchCoordinator,
    favoritesRepo: FavoritesRepository,
    userLocation: GeoPoint?,
    onPick: (SearchResult) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<SearchOutcome?>(null) }

    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val favorites by favoritesRepo.favorites.collectAsStateWithLifecycle(initialValue = emptyList())
    val history by favoritesRepo.history.collectAsStateWithLifecycle(initialValue = emptyList())
    // Same id can exist in both lists (picking a favorite records it in
    // history). LazyColumn keys must be unique across the whole list, so the
    // recent section hides favorite ids instead of re-keying per section.
    val recent = SearchStore.recentExcludingFavorites(favorites, history)

    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    fun pick(result: SearchResult) {
        // Selecting must work with the IME up and must not leave the keyboard
        // covering the map/notice after the search screen closes.
        dismissKeyboard()
        scope.launch {
            coordinator.select(result)
            onPick(result)
        }
    }

    // Search entry is a text-entry action: focus immediately and raise the IME
    // instead of making the user tap the field again.
    LaunchedEffect(Unit) {
        delay(AUTOFOCUS_SETTLE_MS)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // Autocomplete debounce: wait 400ms after the last keystroke.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            outcome = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(AUTOCOMPLETE_DEBOUNCE_MS)
        outcome = coordinator.search(query, userLocation)
        searching = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { dismissKeyboard(); onClose() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại bản đồ",
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = { Text("Tìm địa điểm…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            // Keep the typed query; just reveal more results.
                            keyboard?.hide()
                        },
                    ),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Xóa tìm kiếm")
                            }
                        }
                    },
                )
            }

            // Exactly one compact line: scope/help while idle, badge meaning
            // while results are shown. The old screen stacked four help lines
            // plus two headers and wasted ~250 px of a 2340 px screen.
            Text(
                text = if (query.isBlank()) {
                    VerificationLabels.SEARCH_HINT
                } else {
                    VerificationLabels.LEGEND
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))

            if (searching) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Đang tìm…", style = MaterialTheme.typography.bodySmall)
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val current = outcome
                if (query.isBlank()) {
                    if (favorites.isNotEmpty()) {
                        item { SectionHeader("Đã lưu") }
                        items(favorites, key = { it.id }) { fav ->
                            ResultRow(
                                result = fav,
                                isFavorite = true,
                                userLocation = userLocation,
                                onPick = { pick(fav) },
                                onToggleFavorite = {
                                    scope.launch { favoritesRepo.removeFavorite(fav.id) }
                                },
                            )
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item { SectionHeader("Gần đây") }
                        items(recent, key = { it.id }) { h ->
                            ResultRow(
                                result = h,
                                isFavorite = false,
                                userLocation = userLocation,
                                onPick = { pick(h) },
                                onToggleFavorite = {
                                    scope.launch { favoritesRepo.addFavorite(h) }
                                },
                            )
                        }
                    }
                    if (favorites.isEmpty() && history.isEmpty()) {
                        item {
                            Text(
                                text = "Nhập địa điểm để tìm. Kết quả đã lưu và gần đây sẽ hiện ở đây.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (current is SearchOutcome.Failure) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = current.message,
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                } else if (current is SearchOutcome.Success) {
                    if (current.fallbackNotice != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = current.fallbackNotice,
                                    modifier = Modifier.padding(12.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    val sections = SearchGrouping.build(current.results)
                    sections.localityGroups.forEach { group ->
                        item(key = SearchGrouping.localityHeaderKey(group.localityKey)) {
                            SectionHeader("Khu vực: ${group.displayName}")
                        }
                        items(group.results, key = { it.id }) { r ->
                            ResultRow(
                                result = r,
                                isFavorite = false,
                                userLocation = userLocation,
                                onPick = { pick(r) },
                                onToggleFavorite = {
                                    scope.launch { favoritesRepo.addFavorite(r) }
                                },
                            )
                        }
                    }
                    if (sections.otherResults.isNotEmpty()) {
                        if (sections.localityGroups.isNotEmpty()) {
                            item(key = SearchGrouping.OTHER_HEADER_KEY) {
                                SectionHeader(SearchGrouping.OTHER_SECTION_TITLE)
                            }
                        }
                        items(sections.otherResults, key = { it.id }) { r ->
                            ResultRow(
                                result = r,
                                isFavorite = false,
                                userLocation = userLocation,
                                onPick = { pick(r) },
                                onToggleFavorite = {
                                    scope.launch { favoritesRepo.addFavorite(r) }
                                },
                            )
                        }
                    }
                    if (sections.foreignResults.isNotEmpty()) {
                        item(key = SearchGrouping.FOREIGN_HEADER_KEY) {
                            SectionHeader(SearchGrouping.FOREIGN_SECTION_TITLE)
                        }
                        items(sections.foreignResults, key = { it.id }) { r ->
                            ResultRow(
                                result = r,
                                isFavorite = false,
                                userLocation = userLocation,
                                onPick = { pick(r) },
                                onToggleFavorite = {
                                    scope.launch { favoritesRepo.addFavorite(r) }
                                },
                            )
                        }
                    }
                    if (sections.isEmpty) {
                        item {
                            Text(
                                text = "Không có kết quả.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ResultRow(
    result: SearchResult,
    isFavorite: Boolean,
    userLocation: GeoPoint?,
    onPick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    // Distance only from a real caller-supplied fix; null means "unknown
    // origin", never a fabricated one.
    val distance = userLocation?.let {
        SearchDistance.label(SearchDistance.meters(it, result.point))
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = result.name, style = MaterialTheme.typography.titleSmall)
                if (result.detail.isNotEmpty()) {
                    Text(
                        text = result.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VerificationBadge(result.verification)
                    if (distance != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = distance,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isFavorite) "Bỏ lưu" else "Lưu địa điểm",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Pin-precision badge. "Pin chính xác" means the pin has a house number or
 * named-POI precision; it does NOT mean this is the place the user meant.
 * Relevance is a separate heuristic and the screen legend says to check.
 */
@Composable
private fun VerificationBadge(level: VerificationLevel) {
    val container = when (level) {
        VerificationLevel.VERIFIED -> MaterialTheme.colorScheme.primaryContainer
        VerificationLevel.STREET_ONLY -> MaterialTheme.colorScheme.tertiaryContainer
        VerificationLevel.UNVERIFIED -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text(
            text = VerificationLabels.forLevel(level),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private const val AUTOCOMPLETE_DEBOUNCE_MS = 400L

/**
 * Small settle window before requesting focus, so the first frame is attached
 * to the window and the IME reliably opens on entry.
 */
private const val AUTOFOCUS_SETTLE_MS = 120L
