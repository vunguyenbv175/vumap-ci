package vn.vumap.feature.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding (PR #1): "no evidence of a problem" is not "proven normal".
 *
 * Fresh frames with default (null) thresholds, or readings that carry no pressure at
 * all, must stay UNKNOWN/grey instead of turning NORMAL/green, while a configured
 * alarm still wins and real measurements are still displayed.
 */
class NormalityGateTest {

    private fun reading(
        sensorId: String = "S1",
        pressureKpa: Float? = 220f,
        temperatureC: Float? = 33f,
        batteryPercent: Int? = 80,
        at: Long = 0L,
    ) = TpmsReading(
        sensorId = sensorId,
        pressureKpa = pressureKpa,
        temperatureC = temperatureC,
        batteryPercent = batteryPercent,
        source = TpmsSource.SIMULATED,
        capturedElapsedMs = at,
        valid = true,
    )

    @Test
    fun `default thresholds never prove normal`() {
        val m = TpmsMonitor(thresholds = TpmsThresholds())
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(at = 0L))

        val d = m.wheelDisplay(Wheel.FL, 1_000L)

        assertEquals(DisplayState.UNKNOWN, d.displayState)
        assertFalse(d.isProvenNormal)
        assertEquals("Chưa đủ dữ liệu để xác nhận", d.statusLineVi())
        // The measurement itself is still shown honestly, and never as 0 bar.
        assertTrue(d.pressureText().contains("bar"))
        assertFalse(d.pressureText() == "0")
    }

    @Test
    fun `configured thresholds with pressure prove normal`() {
        val m = TpmsMonitor(
            thresholds = TpmsThresholds(lowPressureKpa = 200f, highPressureKpa = 280f),
        )
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(at = 0L))

        val d = m.wheelDisplay(Wheel.FL, 1_000L)

        assertEquals(DisplayState.NORMAL, d.displayState)
        assertTrue(d.isProvenNormal)
    }

    @Test
    fun `valid reading without pressure is not proven normal`() {
        val m = TpmsMonitor(
            thresholds = TpmsThresholds(lowPressureKpa = 200f, highPressureKpa = 280f),
        )
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(pressureKpa = null, temperatureC = 33f, batteryPercent = null, at = 0L))

        val d = m.wheelDisplay(Wheel.FL, 1_000L)

        assertEquals(DisplayState.UNKNOWN, d.displayState)
        assertFalse(d.isProvenNormal)
        assertEquals("—", d.pressureText())
        assertTrue(d.temperatureText().contains("C"))
    }

    @Test
    fun `a configured alarm still wins over the unknown gate`() {
        val m = TpmsMonitor(thresholds = TpmsThresholds(lowPressureKpa = 250f))
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(pressureKpa = 220f, at = 0L))

        val d = m.wheelDisplay(Wheel.FL, 1_000L)

        assertEquals(DisplayState.CRITICAL, d.displayState)
        assertTrue(d.alarmTypes.contains(AlarmType.LOW_PRESSURE))
    }

    @Test
    fun `unconfigured wheels never turn the overall chip green`() {
        val m = TpmsMonitor(thresholds = TpmsThresholds())
        Wheel.entries.forEachIndexed { i, wheel ->
            m.binder.bind("S$i", wheel)
            m.onReading(reading(sensorId = "S$i", at = 0L))
        }

        assertEquals(DisplayState.UNKNOWN, m.overallState(1_000L))
    }

    @Test
    fun `partial evidence degrades the overall chip instead of showing green`() {
        val m = TpmsMonitor(
            thresholds = TpmsThresholds(lowPressureKpa = 200f, highPressureKpa = 280f),
        )
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(sensorId = "S1", at = 0L))

        // Only FL has data; the other three wheels were never seen.
        assertEquals(DisplayState.WARNING, m.overallState(1_000L))
    }

    @Test
    fun `stale wheels still take the stale bucket over unknown`() {
        val m = TpmsMonitor(thresholds = TpmsThresholds(lowPressureKpa = 200f))
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(at = 0L))

        // Past the stale timeout: stale wins over "not enough data".
        assertEquals(DisplayState.UNKNOWN_STALE, m.wheelDisplay(Wheel.FL, 200_000L).displayState)
    }

    @Test
    fun `battery-only configuration cannot prove a low-pressure tire normal`() {
        // Review case: only a battery limit is configured, a healthy battery must not
        // turn a 50 kPa tire green — there is no configured pressure bound to judge.
        val m = TpmsMonitor(thresholds = TpmsThresholds(lowBatteryPercent = 20))
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(pressureKpa = 50f, batteryPercent = 80, at = 0L))

        val d = m.wheelDisplay(Wheel.FL, 1_000L)

        assertEquals(DisplayState.UNKNOWN, d.displayState)
        assertFalse(d.isProvenNormal)
        assertEquals("Chưa đủ dữ liệu để xác nhận", d.statusLineVi())
        // The measured value is still shown, not hidden as if stale.
        assertTrue(d.pressureText().contains("bar"))
    }

    @Test
    fun `temperature-only configuration with no temperature cannot prove normal`() {
        val m = TpmsMonitor(thresholds = TpmsThresholds(highTempC = 70f))
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(pressureKpa = 220f, temperatureC = null, batteryPercent = null, at = 0L))

        val d = m.wheelDisplay(Wheel.FL, 1_000L)

        assertEquals(DisplayState.UNKNOWN, d.displayState)
        assertFalse(d.isProvenNormal)
    }

    @Test
    fun `temperature-only configuration cannot prove normal even with a temperature`() {
        // No pressure bound is configured, so nothing judges the primary safety value.
        val m = TpmsMonitor(thresholds = TpmsThresholds(highTempC = 70f))
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(pressureKpa = 50f, temperatureC = 33f, at = 0L))

        val d = m.wheelDisplay(Wheel.FL, 1_000L)

        assertEquals(DisplayState.UNKNOWN, d.displayState)
        assertFalse(d.isProvenNormal)
    }

    @Test
    fun `rapid loss without a prior sample cannot prove normal`() {
        val m = TpmsMonitor(
            thresholds = TpmsThresholds(
                lowPressureKpa = 200f,
                highPressureKpa = 280f,
                rapidLossKpaPerMin = 20f,
            ),
        )
        m.binder.bind("S1", Wheel.FL)
        // First frame: the configured rapid-loss rule has no history to evaluate against.
        m.onReading(reading(pressureKpa = 220f, batteryPercent = null, at = 0L))

        val d = m.wheelDisplay(Wheel.FL, 1_000L)

        assertEquals(DisplayState.UNKNOWN, d.displayState)
        assertFalse(d.isProvenNormal)
    }

    @Test
    fun `rapid loss with a prior sample proves normal once both samples are in`() {
        val m = TpmsMonitor(
            thresholds = TpmsThresholds(
                lowPressureKpa = 200f,
                highPressureKpa = 280f,
                rapidLossKpaPerMin = 20f,
            ),
        )
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(pressureKpa = 221f, temperatureC = null, batteryPercent = null, at = 0L))
        m.onReading(reading(pressureKpa = 220f, temperatureC = null, batteryPercent = null, at = 60_000L))

        val d = m.wheelDisplay(Wheel.FL, 61_000L)

        assertEquals(DisplayState.NORMAL, d.displayState)
        assertTrue(d.isProvenNormal)
    }

    @Test
    fun `a configured battery bound with no battery measurement blocks the claim`() {
        val m = TpmsMonitor(
            thresholds = TpmsThresholds(lowPressureKpa = 200f, lowBatteryPercent = 20),
        )
        m.binder.bind("S1", Wheel.FL)
        m.onReading(reading(pressureKpa = 220f, batteryPercent = null, at = 0L))

        val d = m.wheelDisplay(Wheel.FL, 1_000L)

        assertEquals(DisplayState.UNKNOWN, d.displayState)
        assertFalse(d.isProvenNormal)
    }

    @Test
    fun `canProveNormal requires a fresh valid pressure reading with a pressure bound`() {
        val reading = reading(pressureKpa = 220f, at = 0L)
        assertTrue(TpmsThresholds(lowPressureKpa = 200f).canProveNormal(reading, emptyList()))
        assertTrue(TpmsThresholds(highPressureKpa = 280f).canProveNormal(reading, emptyList()))
        // No bound at all / missing pressure / missing reading / invalid reading.
        assertFalse(TpmsThresholds().canProveNormal(reading, emptyList()))
        assertFalse(TpmsThresholds(lowPressureKpa = 200f).canProveNormal(reading.copy(pressureKpa = null), emptyList()))
        assertFalse(TpmsThresholds(lowPressureKpa = 200f).canProveNormal(null, emptyList()))
        assertFalse(
            TpmsThresholds(lowPressureKpa = 200f)
                .canProveNormal(reading.copy(valid = false), emptyList()),
        )
        // The stale timeout is a freshness rule, not a normality criterion.
        assertFalse(TpmsThresholds(staleTimeoutMs = 30_000L).canProveNormal(reading, emptyList()))
    }
}
