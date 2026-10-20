package vn.vumap.feature.tpms.sim

import vn.vumap.feature.tpms.RawTpmsPacket
import vn.vumap.feature.tpms.SyntheticV1Codec
import vn.vumap.feature.tpms.TpmsSource

/**
 * Synthetic 4-sensor simulator for developer mode + host tests.
 *
 * Emits [RawTpmsPacket] via [SyntheticV1Codec] for fixed IDs; every packet is
 * labeled [TpmsSource.SIMULATED] and must NEVER be presented as a real
 * measurement. Real-sensor UI stays DEVICE_UNVERIFIED until TPMS-2.
 */
class TpmsSimulator(
    private val codec: SyntheticV1Codec = SyntheticV1Codec(),
) {
    data class SimWheel(
        val sensorIdHex: String,
        val pressureKpa: Float,
        val temperatureC: Float,
        val batteryPercent: Int,
    )

    val wheels: List<SimWheel> = listOf(
        SimWheel("A1B2C3D4", 220f, 32f, 87),
        SimWheel("E5F60718", 218f, 33f, 82),
        SimWheel("293A4B5C", 225f, 31f, 90),
        SimWheel("6D7E8F90", 221f, 34f, 78),
    )

    fun snapshot(capturedElapsedMs: Long): List<RawTpmsPacket> =
        wheels.map {
            codec.encode(
                sensorIdHex = it.sensorIdHex,
                pressureKpa = it.pressureKpa,
                temperatureC = it.temperatureC,
                batteryPercent = it.batteryPercent,
                capturedElapsedMs = capturedElapsedMs,
                source = TpmsSource.SIMULATED,
            )
        }

    /** Snapshot with one wheel deflated (rapid-loss / low-pressure drill). */
    fun snapshotWithLeak(
        capturedElapsedMs: Long,
        leakSensorIndex: Int = 0,
        leakPressureKpa: Float = 150f,
    ): List<RawTpmsPacket> =
        wheels.mapIndexed { index, w ->
            val p = if (index == leakSensorIndex) leakPressureKpa else w.pressureKpa
            codec.encode(w.sensorIdHex, p, w.temperatureC, w.batteryPercent, capturedElapsedMs)
        }
}
