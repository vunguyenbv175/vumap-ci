package vn.vumap.feature.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.vumap.feature.tpms.sim.TpmsSimulator

class SimulatorTest {
    @Test fun simulatorEmitsFourDistinctDecodableSensors() {
        val codec = SyntheticV1Codec()
        val sim = TpmsSimulator(codec)
        val packets = sim.snapshot(42_000L)
        assertEquals(4, packets.size)
        val ids = packets.mapNotNull { codec.decode(it)?.sensorId }
        assertEquals(4, ids.toSet().size)
        packets.forEach {
            val r = codec.decode(it)!!
            assertNotNull(r.pressureKpa)
            assertNotNull(r.temperatureC)
            assertTrue(r.valid)
            assertEquals(TpmsSource.SIMULATED, r.source)
        }
    }

    @Test fun leakSnapshotTriggersLowPressure() {
        val codec = SyntheticV1Codec()
        val sim = TpmsSimulator(codec)
        val packets = sim.snapshotWithLeak(60_000L, leakSensorIndex = 0, leakPressureKpa = 150f)
        val readings = packets.mapNotNull { codec.decode(it) }
        assertEquals(4, readings.size)
        val leaked = readings.first { it.sensorId == sim.wheels[0].sensorIdHex }
        assertEquals(150f, leaked.pressureKpa!!, 0.2f)
        val thresholds = TpmsThresholds(lowPressureKpa = 200f, staleTimeoutMs = 120_000L)
        val alarms = TpmsAlarmPolicy.evaluate(leaked, Wheel.FL, thresholds, emptyList(), 61_000L)
        assertTrue(alarms.any { it.type == AlarmType.LOW_PRESSURE })
    }

    @Test fun monitorBindsAllFourSimulatedWheels() {
        val codec = SyntheticV1Codec()
        val sim = TpmsSimulator(codec)
        // Owner-configured limits: without them the monitor has no criterion and must
        // stay UNKNOWN (see NormalityGateTest), never green.
        val monitor = TpmsMonitor(
            thresholds = TpmsThresholds(
                lowPressureKpa = 200f,
                highPressureKpa = 280f,
                highTempC = 70f,
                staleTimeoutMs = 120_000L,
            ),
        )
        val now = 100_000L
        val packets = sim.snapshot(now)
        val readings = packets.mapNotNull { codec.decode(it) }
        val wheels = listOf(Wheel.FL, Wheel.FR, Wheel.RL, Wheel.RR)
        readings.forEachIndexed { i, r -> monitor.binder.bind(r.sensorId, wheels[i]) }
        readings.forEach { monitor.onReading(it) }
        val displays = monitor.allDisplays(now + 1_000L)
        assertEquals(4, displays.size)
        assertTrue(displays.values.all { it.displayState == DisplayState.NORMAL })
        assertEquals(DisplayState.NORMAL, monitor.overallState(now + 1_000L))
    }

    @Test fun monitorWithUnconfiguredThresholdsShowsUnknownNotGreen() {
        val codec = SyntheticV1Codec()
        val sim = TpmsSimulator(codec)
        val monitor = TpmsMonitor(thresholds = TpmsThresholds(staleTimeoutMs = 120_000L))
        val now = 100_000L
        val readings = sim.snapshot(now).mapNotNull { codec.decode(it) }
        val wheels = listOf(Wheel.FL, Wheel.FR, Wheel.RL, Wheel.RR)
        readings.forEachIndexed { i, r -> monitor.binder.bind(r.sensorId, wheels[i]) }
        readings.forEach { monitor.onReading(it) }
        val displays = monitor.allDisplays(now + 1_000L)
        assertTrue(displays.values.all { it.displayState == DisplayState.UNKNOWN })
        assertTrue(displays.values.none { it.isProvenNormal })
        assertEquals(DisplayState.UNKNOWN, monitor.overallState(now + 1_000L))
    }
}
