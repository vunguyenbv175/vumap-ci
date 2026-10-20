package vn.vumap.feature.tpms

/** Alarm kinds for TPMS rules engine. */
enum class AlarmType {
    LOW_PRESSURE,
    HIGH_PRESSURE,
    HIGH_TEMP,
    RAPID_LOSS,
    LOW_BATTERY,
    LOST_STALE,
    INVALID_PAYLOAD,
}

enum class Severity { INFO, WARNING, CRITICAL }

data class TpmsAlarm(
    val type: AlarmType,
    val wheel: Wheel?,
    val sensorId: String?,
    val severity: Severity,
    /** Vietnamese user-facing message. */
    val messageVi: String,
    val observedElapsedMs: Long,
)

/**
 * Rules-based alarm policy. Thresholds are owner-configured (door placard);
 * null threshold disables that rule (UNKNOWN, no guess).
 *
 * Pressure conversions: kPa -> bar (/100), psi (*0.145038).
 * Temperature is sensor-reported, NOT inferred tire-core temp.
 * Rapid loss needs >=2 valid samples; stale/invalid never yields green.
 */
object TpmsAlarmPolicy {
    fun evaluate(
        reading: TpmsReading?,
        wheel: Wheel?,
        thresholds: TpmsThresholds,
        history: List<TpmsReading>,
        nowElapsedMs: Long,
    ): List<TpmsAlarm> {
        val alarms = mutableListOf<TpmsAlarm>()
        val wheelTag = wheel?.name ?: "?"
        if (reading == null || !reading.valid) {
            alarms += TpmsAlarm(
                AlarmType.LOST_STALE, wheel, reading?.sensorId,
                Severity.WARNING,
                "Lốp $wheelTag: Chưa có dữ liệu",
                nowElapsedMs,
            )
            return alarms
        }
        val staleMs = nowElapsedMs - reading.capturedElapsedMs
        if (staleMs > thresholds.staleTimeoutMs) {
            alarms += TpmsAlarm(
                AlarmType.LOST_STALE, wheel, reading.sensorId,
                Severity.WARNING,
                "Lốp $wheelTag: Mất tín hiệu",
                nowElapsedMs,
            )
            return alarms
        }
        reading.pressureKpa?.let { kpa ->
            thresholds.lowPressureKpa?.let { low ->
                if (kpa < low) {
                    alarms += TpmsAlarm(
                        AlarmType.LOW_PRESSURE, wheel, reading.sensorId,
                        Severity.CRITICAL,
                        "Áp suất lốp $wheelTag thấp: ${bar(kpa)} bar (ngưỡng ${bar(low)} bar)",
                        nowElapsedMs,
                    )
                }
            }
            thresholds.highPressureKpa?.let { high ->
                if (kpa > high) {
                    alarms += TpmsAlarm(
                        AlarmType.HIGH_PRESSURE, wheel, reading.sensorId,
                        Severity.WARNING,
                        "Áp suất lốp $wheelTag cao: ${bar(kpa)} bar (ngưỡng ${bar(high)} bar)",
                        nowElapsedMs,
                    )
                }
            }
            thresholds.rapidLossKpaPerMin?.let { rate ->
                rapidLoss(reading, history, rate, wheel, nowElapsedMs)?.let { alarms += it }
            }
        }
        reading.temperatureC?.let { t ->
            thresholds.highTempC?.let { hi ->
                if (t > hi) {
                    alarms += TpmsAlarm(
                        AlarmType.HIGH_TEMP, wheel, reading.sensorId,
                        Severity.WARNING,
                        "Nhiệt độ lốp $wheelTag cao: ${"%.0f".format(t)}°C (ngưỡng ${"%.0f".format(hi)}°C)",
                        nowElapsedMs,
                    )
                }
            }
        }
        reading.batteryPercent?.let { bat ->
            thresholds.lowBatteryPercent?.let { lo ->
                if (bat < lo) {
                    alarms += TpmsAlarm(
                        AlarmType.LOW_BATTERY, wheel, reading.sensorId,
                        Severity.INFO,
                        "Pin cảm biến $wheelTag yếu: $bat% (ngưỡng $lo%)",
                        nowElapsedMs,
                    )
                }
            }
        }
        return alarms
    }

    private fun rapidLoss(
        current: TpmsReading,
        history: List<TpmsReading>,
        thresholdKpaPerMin: Float,
        wheel: Wheel?,
        nowElapsedMs: Long,
    ): TpmsAlarm? {
        val curKpa = current.pressureKpa ?: return null
        val prior = history
            .filter { it.valid && it.pressureKpa != null && it.capturedElapsedMs < current.capturedElapsedMs }
            .maxByOrNull { it.capturedElapsedMs } ?: return null
        val dtMin = (current.capturedElapsedMs - prior.capturedElapsedMs) / 60000f
        if (dtMin <= 0f) return null
        val dropPerMin = ((prior.pressureKpa ?: return null) - curKpa) / dtMin
        if (dropPerMin >= thresholdKpaPerMin) {
            return TpmsAlarm(
                AlarmType.RAPID_LOSS, wheel, current.sensorId,
                Severity.CRITICAL,
                "Lốp ${wheel?.name ?: "?"} xì hơi nhanh: -${"%.0f".format(dropPerMin)} kPa/phút",
                nowElapsedMs,
            )
        }
        return null
    }

    private fun bar(kpa: Float): String = "%.2f".format(kpa / 100f)
}

/**
 * Cooldown/dedup for Vietnamese voice + visual alerts.
 * Key is typically "${wheel.name}:${alarm.type}".
 */
class TpmsAlertThrottle(private val cooldownMs: Long = 60_000L) {
    private val lastFired = mutableMapOf<String, Long>()

    /** True when the alert may fire now (first time or cooldown elapsed). */
    fun shouldAnnounce(key: String, nowElapsedMs: Long): Boolean {
        val last = lastFired[key] ?: return true.also { lastFired[key] = nowElapsedMs }
        if (nowElapsedMs - last >= cooldownMs) {
            lastFired[key] = nowElapsedMs
            return true
        }
        return false
    }

    fun reset(key: String) {
        lastFired.remove(key)
    }

    fun resetAll() {
        lastFired.clear()
    }
}
