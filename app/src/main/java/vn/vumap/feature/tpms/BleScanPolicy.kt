package vn.vumap.feature.tpms

/**
 * Bounded BLE scan + exponential backoff so TPMS never drains battery/thermal
 * budget with a continuous scan.
 *
 * Coexistence note (BT + 4G + GPS + Wi-Fi C2M): phone Bluetooth, cellular data
 * for map/route/search, GNSS, and the C2M camera AP can be active together.
 * TPMS uses short BLE scan windows and stops discovery once the target sensor
 * ID is known; camera sockets stay bound to the camera Network (never a global
 * process bind). Field test must cover nav + RTSP + 4G + Bluetooth together.
 */
data class BleScanPolicy(
    /** Single scan window before stopping radio. */
    val maxScanWindowMs: Long = 10_000L,
    /** Scan cycles before giving up until next driving session / retry. */
    val maxCycles: Int = 3,
    val baseBackoffMs: Long = 2_000L,
    val maxBackoffMs: Long = 60_000L,
    /** Stop discovery once all bound sensor IDs have been seen. */
    val stopWhenTargetKnown: Boolean = true,
) {
    /** Exponential backoff for [attempt] (0-based), capped. */
    fun backoffFor(attempt: Int): Long {
        if (attempt < 0) return baseBackoffMs
        var v = baseBackoffMs
        repeat(attempt.coerceAtMost(10)) {
            v = (v * 2).coerceAtMost(maxBackoffMs)
        }
        return v.coerceAtMost(maxBackoffMs)
    }

    companion object {
        const val COEXISTENCE_NOTE =
            "TPMS BLE dùng cửa sổ quét ngắn + backoff hàm mũ, dừng khi đã biết sensor; " +
                "test đồng thời Bluetooth + 4G + GPS + Wi-Fi C2M khi nav và RTSP chạy."
    }
}
