package vn.vumap.feature.tpms

/**
 * Device-specific versioned codec: raw bytes -> typed reading.
 * CRC/length/endian/unit/sensorId are per-device; never assume one payload
 * carries all 4 wheels.
 */
interface TpmsProtocolCodec {
    val codecId: String
    val version: Int
    fun decode(packet: RawTpmsPacket): TpmsReading?
}

/**
 * SYNTHETIC v1 codec for host tests + in-app simulator (developer mode).
 *
 * Frame (12 bytes, SYNTHETIC ONLY — not a real sensor protocol):
 * ```
 * [0]    0xAA magic
 * [1]    0x01 version
 * [2..5] sensorId uint32 BE -> 8-char uppercase hex
 * [6..7] pressure uint16 LE, kPa * 10 (e.g. 2200 = 220.0 kPa ~ 2.20 bar)
 * [8..9] temperature int16 LE, degC * 100
 * [10]   battery 0..100 %
 * [11]   checksum: (sum bytes[0..10]) & 0xFF
 * ```
 * Reject: wrong length/magic/version/checksum, impossible pressure
 * (<=0 or >1000 kPa), impossible temp (<-40 or >125C), battery >100.
 * Returns null on any rejection (caller marks INVALID/UNKNOWN, never green).
 */
class SyntheticV1Codec : TpmsProtocolCodec {
    override val codecId: String = "synthetic-v1"
    override val version: Int = 1

    override fun decode(packet: RawTpmsPacket): TpmsReading? {
        val b = packet.bytes
        if (b.size != FRAME_LEN) return null
        if (b[0] != MAGIC) return null
        if (b[1] != VERSION) return null
        if (checksum(b) != b[11]) return null

        val sensorId = "%02X%02X%02X%02X".format(
            b[2].toInt() and 0xFF,
            b[3].toInt() and 0xFF,
            b[4].toInt() and 0xFF,
            b[5].toInt() and 0xFF,
        )
        val pressureRaw = ((b[7].toInt() and 0xFF) shl 8) or (b[6].toInt() and 0xFF)
        val tempRaw = ((b[9].toInt() shl 8) or (b[8].toInt() and 0xFF)).toShort().toInt()
        val battery = b[10].toInt() and 0xFF

        val pressureKpa = pressureRaw / 10f
        val temperatureC = tempRaw / 100f

        if (pressureKpa <= 0f || pressureKpa > 1000f) return null
        if (temperatureC < -40f || temperatureC > 125f) return null
        if (battery > 100) return null

        return TpmsReading(
            sensorId = sensorId,
            pressureKpa = pressureKpa,
            temperatureC = temperatureC,
            batteryPercent = battery,
            source = packet.source,
            capturedElapsedMs = packet.capturedElapsedMs,
            valid = true,
        )
    }

    /** Encodes a synthetic frame (simulator + fixtures). Throws on bad input. */
    fun encode(
        sensorIdHex: String,
        pressureKpa: Float,
        temperatureC: Float,
        batteryPercent: Int,
        capturedElapsedMs: Long,
        source: TpmsSource = TpmsSource.SIMULATED,
    ): RawTpmsPacket {
        require(sensorIdHex.length == 8 && sensorIdHex.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
            "sensorIdHex must be 8 hex chars"
        }
        require(pressureKpa > 0f && pressureKpa <= 1000f) { "pressure out of range" }
        require(temperatureC >= -40f && temperatureC <= 125f) { "temp out of range" }
        require(batteryPercent in 0..100) { "battery out of range" }
        val b = ByteArray(FRAME_LEN)
        b[0] = MAGIC
        b[1] = VERSION
        for (i in 0 until 4) {
            b[2 + i] = sensorIdHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val pRaw = (pressureKpa * 10f).toInt().coerceIn(1, 10000)
        b[6] = (pRaw and 0xFF).toByte()
        b[7] = ((pRaw shr 8) and 0xFF).toByte()
        val tRaw = (temperatureC * 100f).toInt()
        b[8] = (tRaw and 0xFF).toByte()
        b[9] = ((tRaw shr 8) and 0xFF).toByte()
        b[10] = batteryPercent.toByte()
        b[11] = checksum(b)
        return RawTpmsPacket(b, source, null, capturedElapsedMs)
    }

    private fun checksum(b: ByteArray): Byte {
        var sum = 0
        for (i in 0 until FRAME_LEN - 1) sum += b[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }

    companion object {
        const val FRAME_LEN = 12
        const val MAGIC: Byte = 0xAA.toByte()
        const val VERSION: Byte = 0x01
    }
}
