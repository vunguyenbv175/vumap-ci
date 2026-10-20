package vn.vumap.feature.tpms

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Link state for any TPMS transport. */
enum class TpmsLinkState {
    IDLE,
    SCANNING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    /** Permission denied / BT off / blocked protocol (USB HID, 433 MHz). */
    BLOCKED,
}

/**
 * Transport contract: discovery/receive side emits raw packets; a
 * [TpmsProtocolCodec] decodes them and [TpmsMonitor] binds to wheels.
 */
interface TpmsTransport {
    val linkState: StateFlow<TpmsLinkState>
    val packets: Flow<RawTpmsPacket>
    suspend fun start()
    suspend fun stop()
}

/** Base with shared link-state holder for Android transports. */
abstract class BaseTpmsTransport : TpmsTransport {
    protected val mutableState = MutableStateFlow(TpmsLinkState.IDLE)
    override val linkState: StateFlow<TpmsLinkState> = mutableState
}
