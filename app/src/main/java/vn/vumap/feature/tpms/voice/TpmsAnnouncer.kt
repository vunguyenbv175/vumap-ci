package vn.vumap.feature.tpms.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import vn.vumap.feature.tpms.Severity
import vn.vumap.feature.tpms.TpmsAlarm
import vn.vumap.feature.tpms.TpmsAlertThrottle

/**
 * Vietnamese TPMS announcer with cooldown, never overriding ADAS emergency.
 *
 * - Locale vi-VN; single utterance per alarm key with [TpmsAlertThrottle].
 * - Uses QUEUE_ADD (never QUEUE_FLUSH) so an ADAS/collision utterance is not
 *   cut off.
 * - When [adasActive] is true, non-critical TPMS is deferred (returns false);
 *   critical TPMS is still queued behind ADAS, never interrupting it.
 * - Foreground/driving-session gating is owned by
 *   [vn.vumap.feature.tpms.service.TpmsForegroundService]; this class only
 *   speaks when the session allows it.
 */
class TpmsAnnouncer(
    context: Context,
    cooldownMs: Long = 60_000L,
) {
    private val appContext = context.applicationContext
    private val throttle = TpmsAlertThrottle(cooldownMs)

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            try {
                // Locale(String, String) is deprecated on newer JDKs; the BCP-47 tag
                // yields the identical locale (language vi, country VN).
                tts?.language = Locale.forLanguageTag("vi-VN")
            } catch (_: Exception) {
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = Unit
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = Unit
            })
        }
    }

    /**
     * @param adasActive true while a collision/ADAS emergency utterance owns
     * audio focus; TPMS info waits.
     * @return true when queued for speech, false when suppressed/deferred.
     */
    fun announce(alarm: TpmsAlarm, adasActive: Boolean, nowElapsedMs: Long): Boolean {
        if (adasActive && alarm.severity != Severity.CRITICAL) return false
        val key = "${alarm.wheel?.name ?: "?"}:${alarm.type}"
        if (!throttle.shouldAnnounce(key, nowElapsedMs)) return false
        val engine = tts ?: return false
        if (!ready) return false
        return try {
            val params = android.os.Bundle()
            // Do not request transient-may-duck focus that would lower ADAS;
            // speak at normal notification level behind any active emergency.
            engine.speak(
                alarm.messageVi,
                TextToSpeech.QUEUE_ADD,
                params,
                UUID.randomUUID().toString(),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}
