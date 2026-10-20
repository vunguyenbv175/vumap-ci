package vn.vumap.feature.search

/**
 * Shared search test inputs (pure Kotlin, no Android).
 */
object SearchTestUrls {

    /**
     * A Google Maps place link longer than [QuerySanitizer.MAX_QUERY_LENGTH]
     * (202 chars) whose text-cap cut at 200 lands INSIDE the `!4d106.6980`
     * longitude field: `forPhoton` ends at `…!4d106.69`.
     *
     * Padding math (0-based): head = 34 chars, place name = 84 `a`s, so the
     * data parameter's longitude digits start at index 194 and the string
     * ends at 202; `take(200)` keeps `106.69` and drops `80`.
     */
    fun longMapsUrl(): String =
        "https://www.google.com/maps/place/" + "a".repeat(84) +
            "/@10.7,106.6,17z/data=!3m1!4b1!4m6!3m5!1s0x3100000000000000" +
            "!8m2!3d10.7725!4d106.6980"
}
