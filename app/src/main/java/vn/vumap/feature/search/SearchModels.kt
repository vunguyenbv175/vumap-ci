package vn.vumap.feature.search

import vn.vumap.core.model.GeoPoint

/**
 * P0 search models (project plan section 5; initial task queue item 3).
 *
 * Photon public geocoder + local favorites/history/aliases. No API key is used
 * anywhere in search (Photon public needs none), so there is nothing to leak.
 */

/** Where a [SearchResult] came from. */
enum class SearchSource {
    PHOTON,
    FAVORITE,
    HISTORY,
    GEO_LINK,
    PLUS_CODE,
}

/**
 * How much a result pin may be trusted.
 *
 * These are PIN-PRECISION labels, not a claim that the result answers the
 * query or is the place the user meant (that needs [MatchEvidence] and is
 * still only a heuristic):
 *
 * - [VERIFIED]: the pin carries structural precision — a house number or a
 *   named POI/building. It says nothing about query relevance.
 * - [STREET_ONLY]: only street-level evidence exists (hem/ngo/so-nha retry).
 *   UI must show [STREET_ONLY_MESSAGE] and must NOT render the pin as an
 *   exact house number.
 * - [UNVERIFIED]: local favorite/history or parsed link without fresh evidence,
 *   or a foreign result outside the supported V1 search scope.
 */
enum class VerificationLevel {
    VERIFIED,
    STREET_ONLY,
    UNVERIFIED,
}

/**
 * Supported-country scope for a result.
 *
 * V1 supports Vietnam only. The classification is deliberately about the
 * PROVIDER'S COUNTRY TAGS, not about where the user is or what they typed:
 * missing tags stay [UNKNOWN] instead of being guessed as Vietnamese or
 * foreign.
 */
enum class ResultScope {
    VIETNAM,
    UNKNOWN,
    FOREIGN,
}

/** Country classification from Photon `countrycode` / `country` properties. */
object SearchScope {

    /** ISO 3166-1 alpha-2 code sent to Photon's `countrycode` filter. */
    const val SUPPORTED_COUNTRY_CODE = "VN"

    /** Human label for the V1 search scope. */
    const val SUPPORTED_COUNTRY_LABEL = "Việt Nam"

    private val VIETNAM_NAME_FORMS = setOf("viet nam", "vietnam", "vn")

    fun classify(countryCode: String?, countryName: String?): ResultScope {
        val code = countryCode
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.length == 2 && it.all { c -> c.isLetter() } }
        val nameForm = countryName
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { VietnameseNormalizer.matchingForm(it) }
        return when {
            code == "vn" -> ResultScope.VIETNAM
            nameForm != null && nameForm in VIETNAM_NAME_FORMS -> ResultScope.VIETNAM
            code != null -> ResultScope.FOREIGN
            nameForm != null -> ResultScope.FOREIGN
            else -> ResultScope.UNKNOWN
        }
    }
}

/** Honest street-level fallback notice (project plan section 5, Search). */
const val STREET_ONLY_MESSAGE = "Chỉ tìm thấy cấp đường, chưa xác minh được số nhà"

/** A single place candidate shown to the user. */
data class SearchResult(
    val id: String,
    val name: String,
    val detail: String,
    val point: GeoPoint,
    val source: SearchSource,
    val verification: VerificationLevel,
    /** City/province key used to group ambiguous candidates (e.g. "tp. ho chi minh"). */
    val localityKey: String? = null,
    /** Provider ISO-3166-1 alpha-2 country code (lowercase), or null when absent. */
    val countryCode: String? = null,
    /** Provider country display name ("Việt Nam", "United States"…), or null. */
    val countryName: String? = null,
) {
    /** Country scope derived from provider tags; never from the query or GPS. */
    val scope: ResultScope
        get() = SearchScope.classify(countryCode, countryName)
}

/** Typed search failure reasons. */
enum class SearchError {
    EMPTY_QUERY,
    NETWORK_OFFLINE,
    TIMEOUT,
    RATE_LIMITED,
    UNAUTHORIZED,
    SERVER_UNAVAILABLE,
    NO_RESULTS,
    COOLDOWN_ACTIVE,
    UNRESOLVABLE_LINK,
}

/** One locality group shown in the disambiguation picker. */
data class LocalityGroup(
    val localityKey: String,
    val displayName: String,
    val results: List<SearchResult>,
)

/** Candidates spread over several cities; the user must pick one. */
data class Ambiguity(val groups: List<LocalityGroup>)

/** Outcome of a search request. */
sealed interface SearchOutcome {
    data class Success(
        val results: List<SearchResult>,
        /** Non-null when candidates span multiple localities and need a user pick. */
        val ambiguity: Ambiguity? = null,
        /** Non-null when the street-level fallback path was used. */
        val fallbackNotice: String? = null,
    ) : SearchOutcome

    data class Failure(
        val error: SearchError,
        val message: String,
        val httpStatusCode: Int? = null,
    ) : SearchOutcome
}
