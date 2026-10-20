package vn.vumap.feature.tpms

import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPolicyTest {
    private val thresholds = TpmsThresholds(
        lowPressureKpa = 200f,
        highPressureKpa = 280f,
        highTempC = 70f,
        rapidLossKpaPerMin = 30f,
        lowBatteryPercent = 20,
        staleTimeoutMs = 120_000L,
    )

    private fun reading(
        pressure: Float? = 220f,
        temp: Float? = 35f,
        batt: Int? = 80,
        at: Long = 10_000L,
    ) = TpmsReading("S1", pressure, temp, batt, TpmsSource.SIMULATED, at, true)

    @Test fun lowPressureCriticalVi() {
        val alarms = TpmsAlarmPolicy.evaluate(
            reading(pressure = 180f), Wheel.FL, thresholds, emptyList(), 11_000L,
        )
        assertTrue(alarms.any { it.type == AlarmType.LOW_PRESSURE })
        assertTrue(alarms.any { it.severity == Severity.CRITICAL })
        val msg = alarms.first { it.type == AlarmType.LOW_PRESSURE }.messageVi
        assertTrue(msg.contains("FL"))
        assertTrue(msg.contains("p suất"))
    }

    @Test fun lowPressureMessageMentionsWheel() {
        val alarms = TpmsAlarmPolicy.evaluate(
            reading(pressure = 180f), Wheel.FL, thresholds, emptyList(), 11_000L,
        )
        val msg = alarms.first { it.type == AlarmType.LOW_PRESSURE }.messageVi
        assertTrue(msg.contains("FL"))
    }

    @Test fun highPressureWarns() {
        val alarms = TpmsAlarmPolicy.evaluate(
            reading(pressure = 300f), Wheel.RR, thresholds, emptyList(), 11_000L,
        )
        assertTrue(alarms.any { it.type == AlarmType.HIGH_PRESSURE })
    }

    @Test fun highTempWarns() {
        val alarms = TpmsAlarmPolicy.evaluate(
            reading(temp = 85f), Wheel.FR, thresholds, emptyList(), 11_000L,
        )
        assertTrue(alarms.any { it.type == AlarmType.HIGH_TEMP })
    }

    @Test fun lowBatteryInfo() {
        val alarms = TpmsAlarmPolicy.evaluate(
            reading(batt = 10), Wheel.RL, thresholds, emptyList(), 11_000L,
        )
        assertTrue(alarms.any { it.type == AlarmType.LOW_BATTERY })
    }

    @Test fun rapidLossNeedsHistory() {
        val old = reading(pressure = 230f, at = 0L)
        val cur = reading(pressure = 180f, at = 60_000L) // -50 kPa in 1 min
        val alarms = TpmsAlarmPolicy.evaluate(cur, Wheel.FL, thresholds, listOf(old), 61_000L)
        assertTrue(alarms.any { it.type == AlarmType.RAPID_LOSS })
        // Single sample alone cannot claim rapid loss.
        val solo = TpmsAlarmPolicy.evaluate(cur, Wheel.FL, thresholds, emptyList(), 61_000L)
        assertTrue(solo.none { it.type == AlarmType.RAPID_LOSS })
    }

    @Test fun nullThresholdDisablesRule() {
        val noThresholds = TpmsThresholds(staleTimeoutMs = 120_000L)
        val alarms = TpmsAlarmPolicy.evaluate(
            reading(pressure = 50f, temp = 120f, batt = 1),
            Wheel.FL, noThresholds, emptyList(), 11_000L,
        )
        assertTrue(alarms.isEmpty())
    }

    @Test fun throttleCooldown() {
        val throttle = TpmsAlertThrottle(60_000L)
        assertTrue(throttle.shouldAnnounce("FL:LOW_PRESSURE", 0L))
        assertTrue(!throttle.shouldAnnounce("FL:LOW_PRESSURE", 10_000L))
        assertTrue(throttle.shouldAnnounce("FL:LOW_PRESSURE", 61_000L))
        assertTrue(throttle.shouldAnnounce("FR:LOW_PRESSURE", 10_000L))
    }
}
