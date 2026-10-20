package vn.vumap.feature.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyntheticV1CodecTest {
    private val codec = SyntheticV1Codec()

    @Test fun validRoundTrip() {
        val packet = codec.encode("A1B2C3D4", 220f, 32.5f, 87, 1000L)
        val reading = codec.decode(packet)
        assertNotNull(reading)
        reading!!
        assertEquals("A1B2C3D4", reading.sensorId)
        assertEquals(220f, reading.pressureKpa!!, 0.15f)
        assertEquals(32.5f, reading.temperatureC!!, 0.02f)
        assertEquals(87, reading.batteryPercent)
    }

    @Test fun checksumFailRejected() {
        val packet = codec.encode("11223344", 220f, 30f, 80, 0L)
        val tampered = packet.bytes.copyOf()
        tampered[6] = (tampered[6] + 1).toByte()
        val out = codec.decode(packet.copy(bytes = tampered))
        assertNull(out)
    }

    @Test fun truncatedRejected() {
        val packet = codec.encode("11223344", 220f, 30f, 80, 0L)
        assertNull(codec.decode(packet.copy(bytes = packet.bytes.copyOf(6))))
    }

    @Test fun impossiblePressureRejected() {
        // Encode a plausible frame then overwrite pressure to 0 (invalid).
        val packet = codec.encode("11223344", 220f, 30f, 80, 0L)
        val bad = packet.bytes.copyOf()
        bad[6] = 0
        bad[7] = 0
        // Fix checksum so the only failure is the impossible value.
        var sum = 0
        for (i in 0 until 11) sum += bad[i].toInt() and 0xFF
        bad[11] = (sum and 0xFF).toByte()
        assertNull(codec.decode(packet.copy(bytes = bad)))
    }

    @Test fun sensorIdExtractedUppercase() {
        val packet = codec.encode("aabbccdd", 200f, 25f, 50, 5L)
        assertEquals("AABBCCDD", codec.decode(packet)!!.sensorId)
    }

    @Test fun endianScalingVerified() {
        // 220.0 kPa -> raw 2200 LE = 0x0898 -> bytes [0x98, 0x08].
        val packet = codec.encode("00112233", 220f, 0f, 100, 0L)
        assertEquals(0x98.toByte(), packet.bytes[6])
        assertEquals(0x08.toByte(), packet.bytes[7])
        assertEquals(220f, codec.decode(packet)!!.pressureKpa!!, 0.001f)
    }
}
