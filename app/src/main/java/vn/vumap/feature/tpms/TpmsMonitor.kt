package vn.vumap.feature.tpms

/**
 * Central TPMS state: last valid readings per sensor + per-wheel display.
 *
 * Pure JVM (no Android imports) so host unit tests cover mapping / alarms /
 * stale handling. Transports decode bytes via a [TpmsProtocolCodec] then call
 * [onReading]; UI reads [wheelDisplay]/[allDisplays].
 *
 * UNKNOWN/STALE contract: missing/invalid/stale frames produce
 * [DisplayState.UNKNOWN_STALE] (grey), and frames that cannot prove normality
 * (no pressure measurement, or no configured limit to judge against) produce
 * [DisplayState.UNKNOWN] (grey). Never synthesize 0 pressure or green.
 */
class TpmsMonitor(
    var thresholds: TpmsThresholds = TpmsThresholds(),
    val binder: WheelBinder = WheelBinder(),
) {
    private val latest = mutableMapOf<String, TpmsReading>()
    private val histories = mutableMapOf<String, MutableList<TpmsReading>>()

    /** Feed a decoded reading (null = decode rejected -> INVALID, no green). */
    fun onReading(reading: TpmsReading?) {
        if (reading == null || !reading.valid) return
        latest[reading.sensorId] = reading
        val hist = histories.getOrPut(reading.sensorId) { mutableListOf() }
        hist += reading
        if (hist.size > MAX_HISTORY) hist.removeAt(0)
    }

    fun readingOf(sensorId: String): TpmsReading? = latest[sensorId.trim().uppercase()]

    fun historyOf(sensorId: String): List<TpmsReading> =
        histories[sensorId.trim().uppercase()]?.toList() ?: emptyList()

    fun wheelDisplay(wheel: Wheel, nowElapsedMs: Long): WheelDisplay {
        val sensorId = binder.sensorOf(wheel)
        val reading = sensorId?.let { latest[it] }
        val history = sensorId?.let { historyOf(it) } ?: emptyList()
        val alarms = TpmsAlarmPolicy.evaluate(reading, wheel, thresholds, history, nowElapsedMs)
        val stale = reading == null ||
            (nowElapsedMs - reading.capturedElapsedMs) > thresholds.staleTimeoutMs
        // Normality has to be established, not assumed: the reading must be evaluable
        // against the relevant configured bounds (see TpmsThresholds.canProveNormal) —
        // an unrelated configured limit, a missing measurement or an unevaluated rapid
        // loss keeps the wheel UNKNOWN (grey) instead of green. Alarms are checked
        // first, so real problems still surface.
        val canAssess = thresholds.canProveNormal(reading, history)
        val state = when {
            // `stale` already covers reading == null (it is the first disjunct), so the
            // explicit null check here would be dead code.
            stale || !reading.valid -> DisplayState.UNKNOWN_STALE
            alarms.any { it.severity == Severity.CRITICAL } -> DisplayState.CRITICAL
            alarms.any { it.severity == Severity.WARNING } -> DisplayState.WARNING
            alarms.any { it.type == AlarmType.LOW_BATTERY } -> DisplayState.WARNING
            !canAssess -> DisplayState.UNKNOWN
            else -> DisplayState.NORMAL
        }
        return WheelDisplay(
            wheel = wheel,
            reading = if (stale) reading else reading,
            displayState = state,
            alarmTypes = alarms.map { it.type }.toSet(),
            lastUpdateElapsedMs = reading?.capturedElapsedMs,
        )
    }

    fun allDisplays(nowElapsedMs: Long): Map<Wheel, WheelDisplay> =
        Wheel.entries.associateWith { wheelDisplay(it, nowElapsedMs) }

    /** Worst severity across wheels for the nav mini-chip. */
    fun overallState(nowElapsedMs: Long): DisplayState {
        val states = allDisplays(nowElapsedMs).values.map { it.displayState }
        val unknownCount = states.count {
            it == DisplayState.UNKNOWN || it == DisplayState.UNKNOWN_STALE
        }
        return when {
            states.any { it == DisplayState.CRITICAL } -> DisplayState.CRITICAL
            states.any { it == DisplayState.WARNING } -> DisplayState.WARNING
            unknownCount == 0 -> DisplayState.NORMAL
            // Partial evidence: degrade, never green (same rule as a missing wheel).
            unknownCount < states.size -> DisplayState.WARNING
            states.any { it == DisplayState.UNKNOWN_STALE } -> DisplayState.UNKNOWN_STALE
            else -> DisplayState.UNKNOWN
        }
    }

    companion object {
        private const val MAX_HISTORY = 32
    }
}
