package vn.vumap.feature.tpms

import java.util.Locale

/** Four wheel positions. Manual mapping only; never infer from RSSI/arrival order. */
enum class Wheel(val label: String, val labelVi: String) {
    FL("FL", "Trước trái"),
    FR("FR", "Trước phải"),
    RL("RL", "Sau trái"),
    RR("RR", "Sau phải"),
}

/** Where a raw packet came from. */
enum class TpmsSource {
    BLE_GATT,
    BLE_ADVERT,
    USB_HID,
    RF_RECEIVER,
    SIMULATED,
}

/** Undecoded bytes from a transport, with capture clock. */
data class RawTpmsPacket(
    val bytes: ByteArray,
    val source: TpmsSource,
    val rssiDbm: Int? = null,
    /** Monotonic ms (e.g. SystemClock.elapsedRealtime()). */
    val capturedElapsedMs: Long = 0L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawTpmsPacket) return false
        return source == other.source &&
            rssiDbm == other.rssiDbm &&
            capturedElapsedMs == other.capturedElapsedMs &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (rssiDbm ?: 0)
        result = 31 * result + capturedElapsedMs.hashCode()
        return result
    }
}

/** Decoded single-sensor reading. Null fields = unknown, never zero. */
data class TpmsReading(
    val sensorId: String,
    val pressureKpa: Float?,
    val temperatureC: Float?,
    val batteryPercent: Int?,
    val source: TpmsSource,
    val capturedElapsedMs: Long,
    val valid: Boolean,
)

/** UI display bucket. UNKNOWN/UNKNOWN_STALE must render grey, never 0/green. */
enum class DisplayState {
    NORMAL,
    WARNING,
    CRITICAL,

    /**
     * Frames are fresh and valid, but they cannot establish normality: a required
     * measurement is missing (e.g. no pressure at all) or no rule is configured to
     * judge against. Retained as unknown — never green, never "0 bar safe".
     */
    UNKNOWN,

    /** No usable frame: never seen, invalid, or older than the stale timeout. */
    UNKNOWN_STALE,
}

/**
 * Configurable thresholds. All pressure/temp/battery limits are nullable:
 * null = not configured (UNKNOWN). Do NOT hardcode 2.0/2.2 bar or any
 * universal default; the owner sets values from the door placard / manual.
 *
 * @param lowPressureKpa warn when pressure below this.
 * @param highPressureKpa warn when pressure above this.
 * @param highTempC warn when sensor temp above this.
 * @param rapidLossKpaPerMin warn when drop rate exceeds this (needs history).
 * @param lowBatteryPercent warn when battery below this (if sensor reports it).
 * @param staleTimeoutMs no valid frame within this window -> STALE.
 */
data class TpmsThresholds(
    val lowPressureKpa: Float? = null,
    val highPressureKpa: Float? = null,
    val highTempC: Float? = null,
    val rapidLossKpaPerMin: Float? = null,
    val lowBatteryPercent: Int? = null,
    val staleTimeoutMs: Long = 120_000L,
) {
    /**
     * True when [reading] (with its [history]) establishes normality against the
     * configured safety bounds — the only state that may render green.
     *
     * It requires a pressure measurement AND a configured pressure bound, because
     * pressure is the safety quantity the panel claims normal for: an unrelated bound
     * alone (battery only, say) cannot prove a tire at 50 kPa normal. Every other
     * configured bound must also be evaluable for this reading — a bound whose
     * measurement is missing, or rapid loss with no prior sample, was never evaluated,
     * so "no alarm fired" is still not evidence.
     *
     * Alarms are evaluated separately and always surface (see [TpmsAlarmPolicy]); this
     * predicate only decides whether NORMAL may be claimed. Pure function — covered by
     * JVM unit tests (battery-only, temperature-only/missing-temperature,
     * rapid-loss-without-history).
     */
    fun canProveNormal(reading: TpmsReading?, history: List<TpmsReading>): Boolean {
        if (reading == null || !reading.valid) return false
        if (reading.pressureKpa == null) return false
        if (lowPressureKpa == null && highPressureKpa == null) return false
        if (highTempC != null && reading.temperatureC == null) return false
        if (lowBatteryPercent != null && reading.batteryPercent == null) return false
        if (rapidLossKpaPerMin != null) {
            val hasPriorSample = history.any {
                it.valid && it.pressureKpa != null && it.capturedElapsedMs < reading.capturedElapsedMs
            }
            if (!hasPriorSample) return false
        }
        return true
    }

    /** Returns null when valid, else a Vietnamese reason. */
    fun validate(): String? {
        if (lowPressureKpa != null && highPressureKpa != null &&
            lowPressureKpa >= highPressureKpa
        ) {
            return "Ngưỡng thấp phải nhỏ hơn ngưỡng cao"
        }
        if (lowPressureKpa != null && (lowPressureKpa <= 0f || lowPressureKpa > 1000f)) {
            return "Ngưỡng áp suất thấp ngoài phạm vi"
        }
        if (highPressureKpa != null && (highPressureKpa <= 0f || highPressureKpa > 1000f)) {
            return "Ngưỡng áp suất cao ngoài phạm vi"
        }
        if (highTempC != null && (highTempC < -40f || highTempC > 125f)) {
            return "Ngưỡng nhiệt độ ngoài phạm vi"
        }
        if (lowBatteryPercent != null && (lowBatteryPercent < 0 || lowBatteryPercent > 100)) {
            return "Ngưỡng pin ngoài phạm vi 0-100"
        }
        if (staleTimeoutMs <= 0L) return "Timeout mất tín hiệu phải dương"
        return null
    }
}

/** Per-wheel UI snapshot. Unknown/stale never exposes 0 pressure or green. */
data class WheelDisplay(
    val wheel: Wheel,
    val reading: TpmsReading?,
    val displayState: DisplayState,
    val alarmTypes: Set<AlarmType> = emptySet(),
    /** Monotonic ms of the frame behind this display, null when never seen. */
    val lastUpdateElapsedMs: Long? = null,
) {
    /** True only for proven-normal; stale/unknown never green. */
    val isProvenNormal: Boolean
        get() = displayState == DisplayState.NORMAL && reading?.valid == true

    /** Pressure text for UI. Returns em-dash / stale label, never "0" for unknown. */
    fun pressureText(unit: PressureUnit = PressureUnit.BAR): String {
        val kpa = reading?.pressureKpa
        if (reading == null || !reading.valid || kpa == null ||
            displayState == DisplayState.UNKNOWN_STALE
        ) {
            return "—"
        }
        return when (unit) {
            PressureUnit.KPA -> "${kpa.toInt()} kPa"
            PressureUnit.BAR -> String.format(Locale.getDefault(), "%.2f bar", kpa / 100f)
            PressureUnit.PSI -> String.format(Locale.getDefault(), "%.1f psi", kpa * 0.145038f)
        }
    }

    fun temperatureText(): String {
        val t = reading?.temperatureC
        if (reading == null || !reading.valid || t == null ||
            displayState == DisplayState.UNKNOWN_STALE
        ) {
            return "—"
        }
        return String.format(Locale.getDefault(), "%.0f°C", t)
    }

    /** Accessibility/status line in Vietnamese. */
    fun statusLineVi(): String = when (displayState) {
        DisplayState.NORMAL -> "Bình thường"
        DisplayState.WARNING -> "Cảnh báo"
        DisplayState.CRITICAL -> "Nguy hiểm"
        DisplayState.UNKNOWN -> "Chưa đủ dữ liệu để xác nhận"
        DisplayState.UNKNOWN_STALE ->
            if (reading == null) "Chưa có dữ liệu" else "Mất tín hiệu"
    }
}

enum class PressureUnit { KPA, BAR, PSI }
