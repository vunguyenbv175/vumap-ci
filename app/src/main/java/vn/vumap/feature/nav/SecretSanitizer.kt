package vn.vumap.feature.nav

/**
 * Secret sanitizer helper.
 *
 * Enforces the strict "khong leak key" rule (project plan section 0.8 & prompt requirement 2).
 * Ensures API keys or secrets are never exposed in exception messages, logs, or string representations.
 */
object SecretSanitizer {

    private const val REDACTED = "***REDACTED***"

    /**
     * Replaces any occurrence of [secret] in [text] with `***REDACTED***`.
     * If [secret] is null or blank, [text] is returned as-is.
     */
    fun redact(text: String, secret: String?): String {
        if (secret.isNullOrBlank()) return text
        return text.replace(secret, REDACTED)
    }

    /**
     * Masks an API key for safe display (e.g. "5b3c...a1f9").
     */
    fun mask(key: String): String {
        if (key.isBlank()) return "(none)"
        if (key.length <= 8) return REDACTED
        return "${key.take(4)}...${key.takeLast(4)}"
    }
}
