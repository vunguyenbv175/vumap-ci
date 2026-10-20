package vn.vumap.feature.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Text-to-Speech turn guidance engine for Vietnamese navigation prompts (project plan section 3, 9; requirement 4).
 *
 * Safe against missing TTS engine or missing Vietnamese voice data.
 * Applies anti-spam cooldown so the same prompt is not repeated repeatedly.
 */
class NavigationVoice(
    context: Context,
    private val onInitResult: ((Boolean) -> Unit)? = null,
) {

    private var tts: TextToSpeech? = null
    private var isReady = false
    private var lastSpokenText: String = ""
    private var lastSpokenTimeMs: Long = 0L

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val viLocale = Locale.forLanguageTag("vi-VN")
                val viLangResult = tts?.setLanguage(viLocale)
                isReady = when (viLangResult) {
                    TextToSpeech.LANG_AVAILABLE,
                    TextToSpeech.LANG_COUNTRY_AVAILABLE,
                    TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE,
                    -> true
                    else -> {
                        // Fall back to general Vietnamese or default locale
                        val fallback = tts?.setLanguage(Locale.forLanguageTag("vi"))
                        fallback == TextToSpeech.LANG_AVAILABLE
                    }
                }
            } else {
                isReady = false
            }
            onInitResult?.invoke(isReady)
        }
    }

    /**
     * Speaks the given [text] in Vietnamese if the engine is ready and the text is not spammed.
     */
    fun speak(text: String, force: Boolean = false): Boolean {
        if (!isReady || text.isBlank()) return false

        val now = System.currentTimeMillis()
        // Anti-spam debounce: suppress duplicate text spoken within 8 seconds
        if (!force && text == lastSpokenText && (now - lastSpokenTimeMs) < 8_000L) {
            return false
        }

        lastSpokenText = text
        lastSpokenTimeMs = now

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vumap_turn_${System.currentTimeMillis()}")
        return true
    }

    /**
     * Announces a turn maneuver step based on the distance remaining to it.
     */
    fun announceStep(step: RouteStep, distanceRemainingMeters: Double) {
        stepPrompt(step, distanceRemainingMeters)?.let { speak(it) }
    }

    fun stop() {
        tts?.stop()
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    companion object {
        /**
         * Vietnamese prompt for [step] at [distanceRemainingMeters], or null when
         * the maneuver is still too far away to prompt. Pure so the turn-guidance
         * thresholds stay testable without a TTS engine: an immediate command at
         * <= 40 m, an approach warning up to 250 m, a prepare warning up to 600 m.
         */
        fun stepPrompt(step: RouteStep, distanceRemainingMeters: Double): String? = when {
            distanceRemainingMeters <= 40 -> step.instruction
            distanceRemainingMeters <= 250 ->
                "Sau ${roundDistance(distanceRemainingMeters)} mét, ${step.instruction.lowercase(Locale.forLanguageTag("vi"))}"
            distanceRemainingMeters <= 600 ->
                "Sau ${roundDistance(distanceRemainingMeters)} mét, chuẩn bị ${step.instruction.lowercase(Locale.forLanguageTag("vi"))}"
            else -> null
        }

        private fun roundDistance(dist: Double): Int {
            val d = dist.toInt()
            return if (d > 100) (d / 50) * 50 else (d / 10) * 10
        }
    }
}
