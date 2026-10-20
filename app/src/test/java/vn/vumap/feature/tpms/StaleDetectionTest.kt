package vn.vumap.feature.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleDetectionTest {
    private val thresholds = TpmsThresholds(
        lowPressureKpa = 200f,
        highPressureKpa = 280f,
        highTempC = 70f,
        rapidLossKpaPerMin = null,
        lowBatteryPercent = 20,
        staleTimeoutMs = 120_000L,
    )

    private fun monitorWithOneWheel(at: Long = 0L): TpmsMonitor {
        val m = TpmsMonitor(thresholds = thresholds)
        m.binder.bind("S_FL", Wheel.FL)
        m.binder.bind("S_FR", Wheel.FR)
        m.binder.bind("S_RL", Wheel.RL)
        m.binder.bind("S_RR", Wheel.RR)
        m.onReading(TpmsReading("S_FL", 220f, 33f, 80, TpmsSource.SIMULATED, at, true))
        return m
    }

    @Test fun freshReadingIsNormal() {
        val m = monitorWithOneWheel(at = 0L)
        val d = m.wheelDisplay(Wheel.FL, 10_000L)
        assertEquals(DisplayState.NORMAL, d.displayState)
        assertTrue(d.isProvenNormal)
        assertFalse(d.pressureText() == "0")
        assertTrue(d.pressureText().contains("bar"))
    }

    @Test fun staleAfterTimeoutIsUnknownNeverGreenNeverZero() {
        val m = monitorWithOneWheel(at = 0L)
        val d = m.wheelDisplay(Wheel.FL, 120_001L)
        assertEquals(DisplayState.UNKNOWN_STALE, d.displayState)
        assertFalse(d.isProvenNormal)
        assertEquals("—", d.pressureText())
        assertEquals("—", d.temperatureText())
        assertEquals("Mất tín hiệu", d.statusLineVi())
        assertTrue(d.alarmTypes.contains(AlarmType.LOST_STALE))
    }

    @Test fun neverSeenWheelIsUnknownNotZero() {
        val m = TpmsMonitor(thresholds = thresholds)
        // No bindings, no readings at all.
        val d = m.wheelDisplay(Wheel.RR, 5_000L)
        assertEquals(DisplayState.UNKNOWN_STALE, d.displayState)
        assertFalse(d.isProvenNormal)
        assertEquals("—", d.pressureText())
        assertEquals("Chưa có dữ liệu", d.statusLineVi())
    }

    @Test fun staleThreshold30sAnd2min() {
        val short = thresholds.copy(staleTimeoutMs = 30_000L)
        val m = TpmsMonitor(thresholds = short)
        m.binder.bind("SX", Wheel.FL)
        m.onReading(TpmsReading("SX", 220f, 30f, 80, TpmsSource.SIMULATED, 0L, true))
        assertEquals(DisplayState.NORMAL, m.wheelDisplay(Wheel.FL, 29_999L).displayState)
        assertEquals(DisplayState.UNKNOWN_STALE, m.wheelDisplay(Wheel.FL, 30_001L).displayState)
        val long = thresholds.copy(staleTimeoutMs = 120_000L)
        val m2 = TpmsMonitor(thresholds = long)
        m2.binder.bind("SX", Wheel.FL)
        m2.onReading(TpmsReading("SX", 220f, 30f, 80, TpmsSource.SIMULATED, 0L, true))
        assertEquals(DisplayState.NORMAL, m2.wheelDisplay(Wheel.FL, 119_999L).displayState)
        assertEquals(DisplayState.UNKNOWN_STALE, m2.wheelDisplay(Wheel.FL, 120_001L).displayState)
    }

    @Test fun overallStateDegradesOnPartialStale() {
        val m = monitorWithOneWheel(at = 0L)
        // Only FL has data; other bound wheels never seen -> overall must not be NORMAL/green.
        val overall = m.overallState(10_000L)
        assertFalse(overall == DisplayState.NORMAL)
    }
}
