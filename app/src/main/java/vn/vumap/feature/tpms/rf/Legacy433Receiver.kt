package vn.vumap.feature.tpms.rf

import vn.vumap.feature.tpms.TpmsDeviceVerification

/**
 * Legacy 433.92 MHz external-valve receiver (NOT MVP).
 *
 * Target hardware: four external screw-on valve sensors with a broken solar
 * display, previously identified from device notes as 433.92 MHz vendor-specific RF.
 * Phone Bluetooth cannot receive 433.92 MHz. A compatible replacement
 * 433 MHz display/receiver is usually easier than converting RF to BLE; a
 * generic 433.92 MHz receiver or RTL-SDR + OTG + decoded protocol is NOT
 * claimed compatible without framing proof.
 *
 * Verdict: [TpmsDeviceVerification.NEEDS_RF_RECEIVER].
 */
object Legacy433Receiver {
    const val FREQUENCY_MHZ = 433.92
    const val VERDICT: String = TpmsDeviceVerification.NEEDS_RF_RECEIVER

    const val GUIDANCE_VI =
        "Bộ van 433.92 MHz hiện có không thu được bằng Bluetooth điện thoại. " +
            "Muốn tái dùng cần bộ nhận RF tương thích hoặc cảm biến BLE thực sự; " +
            "không tự mua phụ kiện khi chưa có bằng chứng giao thức."
}
