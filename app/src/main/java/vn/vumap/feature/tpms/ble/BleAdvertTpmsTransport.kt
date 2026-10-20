package vn.vumap.feature.tpms.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import vn.vumap.feature.tpms.BaseTpmsTransport
import vn.vumap.feature.tpms.BleScanPolicy
import vn.vumap.feature.tpms.RawTpmsPacket
import vn.vumap.feature.tpms.TpmsCandidateUuids
import vn.vumap.feature.tpms.TpmsDeviceVerification
import vn.vumap.feature.tpms.TpmsLinkState
import vn.vumap.feature.tpms.TpmsSource

/**
 * BLE advertisement TPMS transport.
 *
 * Parses filtered advertisement manufacturer/service data ONLY after actual
 * captures prove the payload layout. Until then every match is
 * [TpmsDeviceVerification.DISCOVERED_ONLY], never SUPPORTED.
 *
 * Scan ownership (review): one [BleScanWindow] owns the platform scan and each loop
 * carries its own [BleScanSession], so cleanup is scoped to the window THAT loop
 * opened — cancellation cleanup finishing after a stop() -> start() can never
 * unregister the replacement session's scan. [stop] cancels and AWAITS the loop before
 * returning (a restart cannot interleave with old cleanup), [release] is terminal (the
 * scope dies with it), a stop that wins the window lock prevents a new window from
 * opening, the loop `finally` unregisters its own window on cancellation, and results
 * from a superseded generation are dropped.
 */
@SuppressLint("MissingPermission")
class BleAdvertTpmsTransport(
    private val context: Context,
    private val policy: BleScanPolicy = BleScanPolicy(),
) : BaseTpmsTransport() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val packetFlow = MutableSharedFlow<RawTpmsPacket>(extraBufferCapacity = 64)
    override val packets: Flow<RawTpmsPacket> = packetFlow

    @Volatile private var running = false
    private var scanJob: Job? = null

    /** Serializes window open/close against stop/release so no scan survives them. */
    private val windowLock = Any()

    /** The scan-loop session currently owning a window (used by stop/release cleanup). */
    @Volatile private var loopSession: BleScanSession<BluetoothLeScanner, ScanCallback>? = null

    private val windows: BleScanWindow<BluetoothLeScanner, ScanCallback> =
        BleScanWindow<BluetoothLeScanner, ScanCallback>(
        startScan = { scanner, callback -> scanner.startScan(scanFilters(), scanSettings(), callback) },
        stopScan = { scanner, callback ->
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
                // Platform already tore the scan down; nothing left to unregister.
            }
        },
        // Runs whenever a generation stops owning the scan (supersession, loop-cycle
        // close, stop/release): an emission already parked in packetFlow.emit is
        // cancelled, never allowed to complete into a later window's lifetime
        // (review round 6, equivalent to the GATT link-termination handling).
        onInvalidated = { publisher.cancelPending() },
    )

    /**
     * Deferred packet emission, session-scoped: the capture is validated against the
     * window generation that received it — at queue time and again at emit time — and
     * teardown drains pending jobs, so a scan packet captured in one window can never be
     * emitted after stop() or into a restarted session's flow.
     */
    private val publisher: SessionPublisher<RawTpmsPacket> =
        SessionPublisher<RawTpmsPacket>(
        scope = scope,
        isCurrentSession = { generation -> synchronized(windowLock) { windows.accepts(generation) } },
        emit = { packet -> packetFlow.emit(packet) },
    )

    override suspend fun start() {
        if (running) return
        running = true
        mutableState.value = TpmsLinkState.SCANNING
        // One session per loop, published before the loop can open a window, so
        // stop()/release() always clean up exactly what this loop owns.
        val session = BleScanSession(windows)
        loopSession = session
        scanJob = scope.launch { scanLoop(session) }
    }

    override suspend fun stop() {
        running = false
        // Await termination: a restart must never overlap this loop's cancellation
        // cleanup (an old loop could otherwise close the replacement's window).
        val job = scanJob
        scanJob = null
        job?.cancelAndJoin()
        synchronized(windowLock) { loopSession?.cleanupOwn() }
        loopSession = null
        // The window is closed, so captured tokens are stale; cancel + join any emission
        // still queued or parked so nothing from this session is delivered afterwards.
        publisher.drain()
        mutableState.value = TpmsLinkState.IDLE
    }

    /**
     * Terminal teardown: after this the scope is cancelled, so [start] cannot launch a
     * replacement loop and no newer window can appear behind the cleanup below.
     */
    fun release() {
        running = false
        val job = scanJob
        scanJob = null
        job?.cancel()
        synchronized(windowLock) { loopSession?.cleanupOwn() }
        loopSession = null
        // Non-suspending teardown: cancel pending emissions; scope.cancel() below also
        // stops anything the list could not reach.
        publisher.cancelPending()
        scope.cancel()
    }

    private fun scanFilters(): List<ScanFilter> =
        TpmsCandidateUuids.candidateServices.map { uuid ->
            ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
        }

    private fun scanSettings(): ScanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
        .build()

    /**
     * Opens a window for [session] unless stop()/release() already won the race. Under
     * the lock the decision and the platform registration are atomic with respect to
     * stop(), so a returning stop() cannot be followed by a fresh registration, and the
     * window is recorded on the owning session so only that session can close it.
     */
    private fun openWindowIfRunning(
        scanner: BluetoothLeScanner,
        session: BleScanSession<BluetoothLeScanner, ScanCallback>,
    ): BleScanWindow.Window<BluetoothLeScanner, ScanCallback>? {
        if (!running) return null
        return try {
            windows.open(scanner) { generation -> windowCallback(generation) }.also(session::opened)
        } catch (_: SecurityException) {
            mutableState.value = TpmsLinkState.BLOCKED
            null
        } catch (_: Exception) {
            mutableState.value = TpmsLinkState.DISCONNECTED
            null
        }
    }

    /**
     * Callback bound to one window generation: after stop/release/restart the platform
     * may still deliver a last result for a window that no longer owns the scan, and
     * that result must not be emitted as current data.
     */
    private fun windowCallback(generation: Long) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!windows.accepts(generation)) return
            val record = result.scanRecord ?: return
            val payload = record.getServiceData(ParcelUuid(TpmsCandidateUuids.candidateServices.first()))
                ?: record.bytes?.copyOf()
                ?: return
            // Capture NOW: payload copy + capture timestamp happen while this window
            // still owns the scan, and the deferred emission carries that window's
            // generation, so a job resumed after stop()/restart/window-renewal is
            // rejected instead of being delivered as fresh data.
            publisher.publish(
                generation,
                RawTpmsPacket(
                    bytes = payload.copyOf(),
                    source = TpmsSource.BLE_ADVERT,
                    rssiDbm = result.rssi,
                    capturedElapsedMs = android.os.SystemClock.elapsedRealtime(),
                ),
            )
        }
    }

    private suspend fun scanLoop(session: BleScanSession<BluetoothLeScanner, ScanCallback>) {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: run {
            mutableState.value = TpmsLinkState.BLOCKED
            return
        }
        val adapter = manager.adapter ?: run {
            mutableState.value = TpmsLinkState.BLOCKED
            return
        }
        if (!adapter.isEnabled) {
            mutableState.value = TpmsLinkState.BLOCKED
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            mutableState.value = TpmsLinkState.BLOCKED
            return
        }
        var attempt = 0
        try {
            while (running && attempt < policy.maxCycles) {
                // Opened and recorded on this session; cleanup is session-scoped below.
                synchronized(windowLock) { openWindowIfRunning(scanner, session) } ?: break
                try {
                    delay(policy.maxScanWindowMs)
                } finally {
                    // Guaranteed unregistration: also runs when the loop is cancelled
                    // by stop()/release() while the window is still open.
                    synchronized(windowLock) { session.cleanupOwn() }
                }
                if (!running) break
                attempt++
                if (attempt < policy.maxCycles) {
                    mutableState.value = TpmsLinkState.DISCONNECTED
                    delay(policy.backoffFor(attempt - 1))
                    mutableState.value = TpmsLinkState.SCANNING
                }
            }
        } finally {
            // Scoped to THIS loop's window only. A replacement session may already own
            // the platform scan (stop() -> start() during this cleanup), so closing
            // "whatever is active" here would unregister the newer session's scan.
            synchronized(windowLock) { session.cleanupOwn() }
            if (loopSession === session) loopSession = null
        }
        if (running && mutableState.value == TpmsLinkState.SCANNING) {
            mutableState.value = TpmsLinkState.DISCONNECTED
        }
    }
}
