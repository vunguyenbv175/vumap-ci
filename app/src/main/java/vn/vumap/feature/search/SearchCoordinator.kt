package vn.vumap.feature.search

import vn.vumap.core.model.GeoPoint

/**
 * Offline recall contract: favorites/history lookup without network.
 * Implemented by [FavoritesRepository]; faked in JVM unit tests.
 */
interface LocalRecall {
    suspend fun findMatches(query: String, limit: Int = 5): List<SearchResult>
    suspend fun recordHistory(result: SearchResult)
}

/**
 * P0 search coordinator (project plan section 5; initial task queue item 3).
 *
 * Pipeline per user query (user-initiated only — never called per GPS tick):
 * 1. [QuerySanitizer] rejects empty/oversized input.
 * 2. [GeoLinkParser] fast-path: `geo:`, `lat,lon`, Google Maps links,
 *    Plus Codes — zero network for exact positions.
 * 3. Local favorites/history recall (diacritic-insensitive, offline).
 * 4. Photon text search over [AdminAliases] variants (original first).
 * 5. Hẻm/ngõ/số-nhà fallback: when a detailed query finds nothing, retry at
 *    street level and tag results [VerificationLevel.STREET_ONLY] with
 *    [STREET_ONLY_MESSAGE] — never a fake exact pin.
 * 6. Ambiguity: candidates across several localities are grouped so the user
 *    picks the right city instead of merging same-named streets.
 * 7. V1 scope: text queries ask Photon for Vietnam (`countrycode=VN`).
 *    Foreign results that still arrive are ranked after Vietnamese ones,
 *    labelled in one "outside Vietnam" section and never VERIFIED; results
 *    with missing country tags stay visible and are not guessed either way.
 *
 * Hammer protection: at most one network search per [minIntervalMs], with
 * trailing-edge scheduling — when a new query arrives inside the interval,
 * the local-recall part still answers immediately and the network part is
 * deferred: the latest pending query is eventually searched once the interval
 * elapses, instead of being rejected permanently. Photon-side 429/5xx/timeout
 * backoff lives in [PhotonSearchProvider] and is untouched by this throttle.
 */
class SearchCoordinator(
    private val provider: PhotonSearchProvider,
    private val favorites: LocalRecall? = null,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val delay: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {

    private var lastNetworkSearchMs = 0L

    /**
     * Runs one user search. [userLocation] biases Photon ranking and recovers
     * short Plus Codes; it is never used to fabricate results.
     */
    suspend fun search(
        rawQuery: String,
        userLocation: GeoPoint? = null,
    ): SearchOutcome {
        // Parse INTACT input first: the Photon text-query cap must never
        // shorten a shared URL before its destination is read (a cut inside
        // `!4d106.6980` would pin `106.69`, kilometers away, as VERIFIED).
        // Text searches are capped after parsing, before going outbound.
        val intact = QuerySanitizer.forLocationInput(rawQuery)
            ?: return SearchOutcome.Failure(SearchError.EMPTY_QUERY, "Nhập địa điểm cần tìm.")

        // Geo/link fast-path: exact positions without network.
        val parsed = GeoLinkParser.parse(intact, userLocation)
        when (parsed) {
            is GeoLinkParser.ParsedLocation.UnresolvableShortLink ->
                return SearchOutcome.Failure(SearchError.UNRESOLVABLE_LINK, parsed.message)
            is GeoLinkParser.ParsedLocation.ViewportOnly ->
                // Camera/viewport link: no destination to route to, never pin it.
                return SearchOutcome.Failure(SearchError.UNRESOLVABLE_LINK, parsed.message)
            is GeoLinkParser.ParsedLocation.PlaceQuery -> {
                // Fall through to Photon text search with the place name.
                val place = QuerySanitizer.forPhoton(parsed.query)
                    ?: return SearchOutcome.Failure(SearchError.EMPTY_QUERY, "Nhập địa điểm cần tìm.")
                return searchText(place, userLocation, allowFallback = false)
            }
            is GeoLinkParser.ParsedLocation.Coordinates,
            is GeoLinkParser.ParsedLocation.PlusCode,
            -> {
                val result = GeoLinkParser.toResult(parsed)!!
                favorites?.recordHistory(result)
                return SearchOutcome.Success(results = listOf(result))
            }
            null -> Unit
        }

        val query = QuerySanitizer.forPhoton(intact)
            ?: return SearchOutcome.Failure(SearchError.EMPTY_QUERY, "Nhập địa điểm cần tìm.")
        return searchText(query, userLocation, allowFallback = true)
    }

    private suspend fun searchText(
        query: String,
        userLocation: GeoPoint?,
        allowFallback: Boolean,
    ): SearchOutcome {
        val local = favorites?.findMatches(query).orEmpty()

        // Trailing-edge throttle: honor the interval between network searches,
        // then run the LATEST query instead of rejecting it. An early return
        // here would permanently drop the final keystrokes of a fast typist —
        // and skip local recall — because SearchScreen's effect reruns only on
        // query change. Each network search (alias variants + street fallback)
        // still goes out at most once per interval.
        waitForInterval()
        lastNetworkSearchMs = timeProvider()

        // Try alias variants in order (original query first).
        var photonResults: List<SearchResult> = emptyList()
        var lastFailure: SearchOutcome.Failure? = null
        for (variant in AdminAliases.variants(query)) {
            // V1 is Vietnam-only: ask Photon to filter by ISO country code so
            // the public worldwide index does not hand back a UK pub / US
            // driveways for a HCMC landmark query. Result-side handling below
            // still covers providers that ignore or lack the filter.
            when (
                val outcome = provider.search(
                    variant,
                    bias = userLocation,
                    countryCode = SearchScope.SUPPORTED_COUNTRY_CODE,
                )
            ) {
                is SearchOutcome.Success -> {
                    photonResults = outcome.results
                    lastFailure = null
                    break
                }
                is SearchOutcome.Failure -> {
                    // Only NO_RESULTS justifies trying the next alias variant;
                    // transport/cooldown failures are returned immediately.
                    if (outcome.error == SearchError.NO_RESULTS) {
                        lastFailure = outcome
                        continue
                    }
                    return mergeLocal(outcome, local)
                }
            }
        }

        // Structural precision is not match evidence: an exact-but-unrelated
        // pin (live evidence: church/zoo/Nha Trang for HCM queries) must not
        // keep a VERIFIED badge for this query. Results are never hidden.
        photonResults = withHonestBadges(query, photonResults)
        // V1 only verifies pins inside its supported scope. Foreign results are
        // still shown (in a clearly labelled section) but never as verified
        // V1 addresses; a missing-country result is left as-is, not guessed.
        photonResults = withScopeBadges(photonResults)
        // Rank before local recall is prepended: saved places stay first, while
        // provider results are ordered by match strength, supported scope and
        // (only with a real origin) distance.
        photonResults = SearchResultRanker.rank(photonResults, query, userLocation)

        if (photonResults.isEmpty() && allowFallback && HemNgoFallback.hasDetailedNumber(query)) {
            val streetQuery = HemNgoFallback.streetFallbackQuery(query)
            if (streetQuery != null && streetQuery != query) {
                when (
                    val retry = provider.search(
                        streetQuery,
                        bias = userLocation,
                        countryCode = SearchScope.SUPPORTED_COUNTRY_CODE,
                    )
                ) {
                    is SearchOutcome.Success -> {
                        val downgraded = SearchResultRanker.rank(
                            HemNgoFallback.markStreetOnly(retry.results),
                            query,
                            userLocation,
                        )
                        return SearchOutcome.Success(
                            results = mergeLocal(downgraded, local),
                            ambiguity = buildAmbiguity(mergeLocal(downgraded, local)),
                            fallbackNotice = STREET_ONLY_MESSAGE,
                        )
                    }
                    is SearchOutcome.Failure -> {
                        if (retry.error != SearchError.NO_RESULTS) {
                            return mergeLocal(retry, local)
                        }
                    }
                }
            }
            if (local.isNotEmpty()) {
                return SearchOutcome.Success(
                    results = local,
                    fallbackNotice = STREET_ONLY_MESSAGE,
                )
            }
            return SearchOutcome.Failure(
                SearchError.NO_RESULTS,
                "Không tìm thấy \"$query\" trong ${SearchScope.SUPPORTED_COUNTRY_LABEL}, " +
                    "kể cả ở cấp đường — chưa xác minh được số nhà.",
            )
        }

        if (photonResults.isEmpty() && local.isEmpty()) {
            // The provider was asked for Vietnam-only results. Say so instead
            // of implying that nothing exists worldwide (or silently dropping
            // a foreign destination the user typed).
            val scopedMessage = "Không tìm thấy \"$query\" trong " +
                "${SearchScope.SUPPORTED_COUNTRY_LABEL}. " +
                "V1 chỉ tìm kiếm trong ${SearchScope.SUPPORTED_COUNTRY_LABEL}."
            return lastFailure?.copy(message = scopedMessage)
                ?: SearchOutcome.Failure(SearchError.NO_RESULTS, scopedMessage)
        }

        val merged = mergeLocal(photonResults, local)
        return SearchOutcome.Success(
            results = merged,
            ambiguity = buildAmbiguity(merged),
        )
    }

    /**
     * Suspends until [minIntervalMs] has elapsed since the previous network
     * search, so consecutive user queries are spaced out but never dropped.
     * Injected [delay] makes this instant under a fake clock in tests.
     */
    private suspend fun waitForInterval() {
        if (minIntervalMs <= 0) return
        val remaining = minIntervalMs - (timeProvider() - lastNetworkSearchMs)
        if (remaining > 0) delay(remaining)
    }

    /**
     * Records a user pick into history (call from UI on selection).
     */
    suspend fun select(result: SearchResult) {
        favorites?.recordHistory(result)
    }

    /** Clears the autocomplete debounce window (tests). */
    fun resetDebounce() {
        lastNetworkSearchMs = 0L
    }

    private fun mergeLocal(
        photon: List<SearchResult>,
        local: List<SearchResult>,
    ): List<SearchResult> {
        if (local.isEmpty()) return photon
        val ids = HashSet<String>(photon.size)
        photon.forEach { ids.add(it.id) }
        return local.filter { it.id !in ids } + photon
    }

    private fun mergeLocal(
        failure: SearchOutcome.Failure,
        local: List<SearchResult>,
    ): SearchOutcome = if (local.isNotEmpty() && failure.error != SearchError.EMPTY_QUERY) {
        // Offline recall still serves saved places when the network fails.
        SearchOutcome.Success(results = local)
    } else {
        failure
    }

    /**
     * Drops the VERIFIED badge from results that share no content token with
     * the user's query ([MatchEvidence]): an unrelated house-number POI is
     * still shown, but never as the verified requested hem/address.
     */
    private fun withHonestBadges(query: String, results: List<SearchResult>): List<SearchResult> =
        results.map { result ->
            if (result.verification == VerificationLevel.VERIFIED &&
                !MatchEvidence.matchesQuery(query, result)
            ) {
                result.copy(verification = VerificationLevel.UNVERIFIED)
            } else {
                result
            }
        }

    /**
     * Drops structural VERIFIED evidence for results outside the supported V1
     * country scope. They remain visible in the clearly labelled foreign
     * section; only the "precise V1 pin" claim is withheld.
     */
    private fun withScopeBadges(results: List<SearchResult>): List<SearchResult> =
        results.map { result ->
            if (result.scope == ResultScope.FOREIGN &&
                result.verification == VerificationLevel.VERIFIED
            ) {
                result.copy(verification = VerificationLevel.UNVERIFIED)
            } else {
                result
            }
        }

    /**
     * Groups Vietnamese candidates by locality. Non-null when more than one
     * locality has candidates, so the UI shows a "pick your city"
     * disambiguation instead of mixing same-named streets from several cities.
     * Foreign results never enter this picker; the UI gives them one separate,
     * clearly labelled section.
     */
    fun buildAmbiguity(results: List<SearchResult>): Ambiguity? {
        val groups = SearchGrouping.build(results).localityGroups
        return if (groups.size > 1) Ambiguity(groups) else null
    }

    companion object {
        /** Autocomplete debounce: at most one network search per interval. */
        const val DEFAULT_MIN_INTERVAL_MS = 1_000L
    }
}
