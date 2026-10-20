package vn.vumap.feature.tpms.usb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import vn.vumap.feature.tpms.BaseTpmsTransport
import vn.vumap.feature.tpms.RawTpmsPacket
import vn.vumap.feature.tpms.TpmsDeviceVerification
import vn.vumap.feature.tpms.TpmsLinkState

/**
 * USB HID TPMS transport (optional, gated).
 *
 * the vendor HID port suggests a receiver HID-over-OTG, but NO generic claim
 * covers an arbitrary solar-display receiver or USB dongle. This transport
 * stays [TpmsDeviceVerification.BLOCKED_DEVICE_PROTOCOL] until a compatible
 * receiver enumerates with known VID/PID + HID report descriptor and
 * read-only frame evidence.
 *
 * OTG can conflict with charging / Android Auto USB / C2M setup — optional
 * only, never described as compatible without evidence.
 */
class UsbHidTpmsTransport : BaseTpmsTransport() {
    private val packetFlow = MutableSharedFlow<RawTpmsPacket>(extraBufferCapacity = 16)
    override val packets: Flow<RawTpmsPacket> = packetFlow

    /** Known-compatible receiver filter; empty = none proven. */
    data class UsbDeviceFilter(val vendorId: Int, val productId: Int)

    /** No proven VID/PID yet. */
    val supportedDevices: List<UsbDeviceFilter> = emptyList()

    val verdict: String = TpmsDeviceVerification.BLOCKED_DEVICE_PROTOCOL

    override suspend fun start() {
        mutableState.value = TpmsLinkState.BLOCKED
    }

    override suspend fun stop() {
        mutableState.value = TpmsLinkState.IDLE
    }
}
