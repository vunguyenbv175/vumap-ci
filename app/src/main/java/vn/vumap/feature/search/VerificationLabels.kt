package vn.vumap.feature.search

/**
 * User-facing badge labels.
 *
 * The old "Đã xác minh" (verified) read like "this is the place you meant".
 * The badge can only describe PIN PRECISION (a house number or named POI), so
 * the label now says exactly that. Query relevance stays a separate heuristic
 * and the screen legend tells the user to check the pin before navigating.
 *
 * Pure Kotlin, JVM unit-tested (no Android dependency).
 */
object VerificationLabels {

    const val PRECISE_PIN = "Pin chính xác"
    const val STREET_ONLY = "Chỉ cấp đường"
    const val UNVERIFIED = "Chưa xác minh"

    /** One compact line; shown only while results are on screen. */
    const val LEGEND = "Pin chính xác = số nhà/POI, không xác nhận đúng chỗ cần tìm"

    /** One compact line; shown while the query is empty. */
    const val SEARCH_HINT = "Phạm vi Việt Nam · không dấu · hẻm/ngõ · tọa độ/link"

    fun forLevel(level: VerificationLevel): String = when (level) {
        VerificationLevel.VERIFIED -> PRECISE_PIN
        VerificationLevel.STREET_ONLY -> STREET_ONLY
        VerificationLevel.UNVERIFIED -> UNVERIFIED
    }
}
