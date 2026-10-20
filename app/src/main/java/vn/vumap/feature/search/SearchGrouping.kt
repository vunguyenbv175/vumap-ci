package vn.vumap.feature.search

/**
 * UI-ready grouping for one result list.
 *
 * - [localityGroups] is the Vietnamese "pick your city" disambiguation, only
 *   when more than one Vietnamese locality is present. Group identity is the
 *   normalized locality key, so LazyColumn header keys stay stable across
 *   renders and cannot collide.
 * - [otherResults] holds Vietnamese results without a locality plus results
 *   whose country tags are missing (never silently reclassified as VN).
 * - [foreignResults] is a single clearly-labelled "outside Vietnam" section.
 *   It is NOT grouped per city: displaying two separate "United States"
 *   headers for Stafford and Brattleboro made the old picker useless (live
 *   evidence 2026-10-21). Foreign results are shown, never silently dropped.
 *
 * Pure Kotlin, JVM unit-tested.
 */
data class SearchSections(
    val localityGroups: List<LocalityGroup>,
    val otherResults: List<SearchResult>,
    val foreignResults: List<SearchResult>,
) {
    val isEmpty: Boolean
        get() = localityGroups.isEmpty() && otherResults.isEmpty() && foreignResults.isEmpty()
}

object SearchGrouping {

    const val OTHER_SECTION_TITLE = "Kết quả khác"
    const val FOREIGN_SECTION_TITLE = "Ngoài Việt Nam — ngoài phạm vi hỗ trợ"

    const val OTHER_HEADER_KEY = "header:other"
    const val FOREIGN_HEADER_KEY = "header:foreign"

    /** Stable LazyColumn key for a Vietnamese locality group header. */
    fun localityHeaderKey(localityKey: String): String = "header:vn:$localityKey"

    fun build(results: List<SearchResult>): SearchSections {
        val foreign = results.filter { it.scope == ResultScope.FOREIGN }
        val domestic = results.filter { it.scope != ResultScope.FOREIGN }

        val vietnam = domestic.filter { it.scope == ResultScope.VIETNAM }
        val byLocality = vietnam.groupBy { it.localityKey?.takeIf { key -> key.isNotBlank() } ?: "" }
        val named = byLocality.filterKeys { it.isNotEmpty() }

        // A single locality needs no picker: a flat list is clearer and saves
        // a redundant header (and vertical space) on the common case.
        val groups = if (named.size > 1) {
            named.entries.map { (key, list) ->
                LocalityGroup(
                    localityKey = key,
                    displayName = displayLocality(list.first()) ?: key,
                    results = list,
                )
            }
        } else {
            emptyList()
        }

        val groupedIds = groups.flatMap { group -> group.results.map { it.id } }.toSet()
        val other = domestic.filter { it.id !in groupedIds }
        return SearchSections(
            localityGroups = groups,
            otherResults = other,
            foreignResults = foreign,
        )
    }

    /**
     * City/province label for a Vietnamese group, never the country name.
     * Photon writes detail as street, district, city/state, country; the
     * country is appended last, so taking the last non-country segment yields
     * the locality the picker needs.
     */
    private fun displayLocality(result: SearchResult): String? {
        val parts = result.detail.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val countryForm = result.countryName
            ?.let { VietnameseNormalizer.matchingForm(it) }
        val locality = parts.filterNot { part ->
            val form = VietnameseNormalizer.matchingForm(part)
            form in COUNTRY_SEGMENTS || (countryForm != null && form == countryForm)
        }
        return locality.lastOrNull() ?: parts.last()
    }

    private val COUNTRY_SEGMENTS = setOf("viet nam", "vietnam")
}
