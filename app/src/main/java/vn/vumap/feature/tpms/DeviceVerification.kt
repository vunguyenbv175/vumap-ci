package vn.vumap.feature.tpms

/**
 * P2b TPMS device-verification gates.
 *
 * Source of truth: the private TPMS plan
 * (vendor app static notes) + project plan sections 7b / 9 P2b.
 *
 * - Vendor patterns TpmsBleLink / TpmsBleReading / TpmsFrame / TpmsHidPort are
 *   CANDIDATE UUIDs FFF0/FFE0/FFF1/FFE1 only, NOT proven compatible.
 * - Existing target hardware: 4x external screw-on valve sensors, solar display
 *   broken, previously identified as RF 433.92 MHz vendor-specific. Android BLE
 *   cannot receive 433.92 MHz directly. Reuse needs a compatible RF receiver
 *   or real BLE sensors; do not pretend the 4 valves pair with a BLE scan.
 * - No real sensor frames captured in this slice -> DEVICE_UNVERIFIED.
 *   Synthetic codec/logic may PASS host tests; that is synthetic PASS only and
 *   must never be reported as device pressure PASS.
 */
object TpmsDeviceVerification {
    /** No real BLE/USB pressure+temperature frames decoded yet. */
    const val DEVICE_UNVERIFIED = "DEVICE_UNVERIFIED"

    /** Synthetic host tests passed; no real-frame claim. */
    const val SYNTHETIC_PASS_ONLY = "SYNTHETIC_PASS_ONLY"

    /** UUID/service seen in scan only; compatibility NOT proven. */
    const val DISCOVERED_ONLY = "DISCOVERED_ONLY"

    /** Codec decoded + wheel mapping + stale handling proven on real frames. */
    const val SUPPORTED = "SUPPORTED"

    /** USB HID / 433 MHz receiver without VID/PID/protocol evidence. */
    const val BLOCKED_DEVICE_PROTOCOL = "BLOCKED_DEVICE_PROTOCOL"

    /** Legacy 433.92 MHz valves need a compatible RF receiver/decoder. */
    const val NEEDS_RF_RECEIVER = "NEEDS_RF_RECEIVER"

    /** Gate labels used in code + evidence docs. */
    const val GATE_STATIC = "STATIC"
    const val GATE_HOST_TESTED = "HOST_TESTED"
    const val GATE_DEVICE_OBSERVED = "DEVICE_OBSERVED"
    const val GATE_DEVICE_VERIFIED = "DEVICE_VERIFIED"

    /**
     * Current slice verdict. Keep DEVICE_UNVERIFIED until TPMS-2 real BLE
     * receiving gate passes with true pressure/temperature frames + mapping
     * + loss-of-signal test on the owner's handset.
     */
    const val CURRENT_VERDICT: String = DEVICE_UNVERIFIED
}
