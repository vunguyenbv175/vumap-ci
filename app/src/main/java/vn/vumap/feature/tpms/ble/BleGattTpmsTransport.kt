package vn.vumap.feature.tpms.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
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
 * BLE GATT TPMS transport (candidate-UUID discovery).
 *
 * Flow: filtered scan for FFF0/FFE0 -> connect -> service discovery ->
 * enable notify/indicate on FFF1/FFF2/FFE1 (client-characteristic configuration
 * descriptor write) or read them -> [RawTpmsPacket].
 *
 * Status stays DISCOVERED_ONLY until a [vn.vumap.feature.tpms.TpmsProtocolCodec]
 * decodes real pressure/temperature frames; UUID match alone is NEVER
 * reported as SUPPORTED (see [TpmsDeviceVerification]).
 *
 * Review contracts implemented here:
 * - Remote notifications are enabled by writing the CCCD (local
 *   `setCharacteristicNotification` alone never makes a peripheral send data).
 * - GATT operations are serialized through [GattOperationQueue]: a completion callback
 *   releases the slot AND starts the next operation, a synchronous refusal (API 33+
 *   `writeDescriptor` status != SUCCESS, no connection, permission denied) releases the
 *   slot instead of waiting for a callback that cannot arrive, and disconnect clears
 *   the queue.
 * - Scan ownership is [BleScanWindow]-bounded and scoped per loop with
 *   [BleScanSession]: stop awaits loop termination, release is terminal, and a late
 *   cleanup from an old loop can never unregister a replacement session's scan.
 * - Connection ownership is single-link via [GattConnection], enforced on one lock: scan
 *   results are admitted atomically (two simultaneous results open exactly one
 *   connection), stop/release close the session so an in-flight connect can never
 *   publish after the session stopped, and every callback runs its work inside
 *   [GattConnection.ifOwned] (identity check + work atomic with teardown), so late
 *   callbacks cannot discover services, mutate the operation queue or publish packets.
 */
@SuppressLint("MissingPermission")
class BleGattTpmsTransport(
    private val context: Context,
    private val policy: BleScanPolicy = BleScanPolicy(),
) : BaseTpmsTransport() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val packetFlow = MutableSharedFlow<RawTpmsPacket>(extraBufferCapacity = 64)
    override val packets: Flow<RawTpmsPacket> = packetFlow

    @Volatile private var targetsKnown = false
    @Volatile private var running = false
    private var scanJob: Job? = null

    /** Serializes scan-window open/close against stop/release. */
    private val windowLock = Any()

    /** The scan-loop session currently owning a window (used by stop/release cleanup). */
    @Volatile private var loopSession: BleScanSession<BluetoothLeScanner, ScanCallback>? = null

    /**
     * Single-connection ownership: scan results are deduplicated while a link is active,
     * the handle is closed when the link ends or the transport stops, and every callback
     * below is gated on [GattConnection.owns] so a late callback from a superseded or
     * stopped connection cannot discover services, mutate the queue or publish packets.
     */
    private val link: GattConnection<String, BluetoothGatt> = GattConnection<String, BluetoothGatt>(
        connect = { address -> openConnection(address) },
        close = { connection -> closeConnection(connection) },
        // Runs inside end()/closeSession while exclusive: an emission already parked in
        // packetFlow.emit is cancelled, never allowed to complete into a later
        // connection's lifetime (review round 6).
        onTerminated = { publisher.cancelPending() },
    )

    private val windows = BleScanWindow<BluetoothLeScanner, ScanCallback>(
        startScan = { scanner, callback -> scanner.startScan(scanFilters(), scanSettings(), callback) },
        stopScan = { scanner, callback ->
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
                // Platform already tore the scan down; nothing left to unregister.
            }
        },
    )

    /** One GATT operation at a time; completion/refusal callbacks drive the sequence. */
    private val operations = GattOperationQueue<GattOp>(
        // Starts exactly the operation the queue hands over, against the live connection.
        start = { op -> beginOperation(op) },
    )

    /**
     * Deferred packet emission, session-scoped: captures are validated against the link
     * generation at queue time AND at emit time, and teardown drains pending jobs, so a
     * notification captured on one connection can never be emitted after stop() or into
     * a later connection.
     */
    private val publisher: SessionPublisher<RawTpmsPacket> = SessionPublisher<RawTpmsPacket>(
        scope = scope,
        isCurrentSession = { token -> link.isCurrentSession(token) },
        emit = { packet -> packetFlow.emit(packet) },
    )

    private sealed class GattOp {
        abstract val characteristic: BluetoothGattCharacteristic

        class EnableNotifications(
            override val characteristic: BluetoothGattCharacteristic,
            val descriptorValue: ByteArray,
        ) : GattOp()

        class Read(override val characteristic: BluetoothGattCharacteristic) : GattOp()
    }

    fun notifyTargetsKnown() {
        targetsKnown = true
    }

    override suspend fun start() {
        if (running) return
        running = true
        targetsKnown = false
        // Opens admission again: connections may only be created while a session runs.
        link.openSession()
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
        // Session-scoped: rejects any admission still in flight and closes the handle,
        // invalidating every captured session token for publication.
        link.closeSession()
        // Cancel + join pending emissions (including one parked by backpressure): once
        // stop() returns, no packet from this session can still be delivered.
        publisher.drain()
        operations.clear()
        mutableState.value = TpmsLinkState.IDLE
    }

    /**
     * Terminal teardown: after this the scope is cancelled, so [start] cannot launch a
     * replacement loop and no newer window/connection can appear behind the cleanups.
     */
    fun release() {
        running = false
        val job = scanJob
        scanJob = null
        job?.cancel()
        synchronized(windowLock) { loopSession?.cleanupOwn() }
        loopSession = null
        link.closeSession()
        // Non-suspending teardown: cancel pending emissions; scope.cancel() below also
        // stops anything the list could not reach.
        publisher.cancelPending()
        operations.clear()
        scope.cancel()
    }

    /**
     * Opens a platform connection for [address]. Kept side-effect-light: identity and
     * single-connection policy are owned by [link], which only calls this when no link
     * is active.
     */
    private fun openConnection(address: String): BluetoothGatt? {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val device = manager.adapter?.getRemoteDevice(address) ?: return null
        mutableState.value = TpmsLinkState.CONNECTING
        return try {
            device.connectGatt(context, false, gattCallback)
        } catch (_: SecurityException) {
            mutableState.value = TpmsLinkState.BLOCKED
            null
        } catch (_: Exception) {
            mutableState.value = TpmsLinkState.DISCONNECTED
            null
        }
    }

    /**
     * Releases one connection handle. Safe to call after the platform already dropped
     * the link; ownership is cleared before this runs, so callbacks re-entered here are
     * rejected by [GattConnection.owns].
     */
    private fun closeConnection(connection: BluetoothGatt) {
        try {
            connection.disconnect()
        } catch (_: Exception) {
            // Already gone / permission lost; closing below is what matters.
        }
        try {
            connection.close()
        } catch (_: Exception) {
            // Platform already released it.
        }
    }

    /**
     * Scan-result entry point: [link] accepts only while no connection is active, so
     * repeated advertisements (same sensor) and additional devices are deduplicated and
     * can never replace the handle behind in-flight operations.
     */
    private fun connectGatt(address: String) {
        if (!running) return
        link.request(address)
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
     * stop(), and the window is recorded on the owning session so only that session can
     * close it.
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

    private fun windowCallback(generation: Long) = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!windows.accepts(generation)) return
            val device = result.device ?: return
            scope.launch { connectGatt(device.address) }
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
                if (policy.stopWhenTargetKnown && targetsKnown) {
                    mutableState.value = TpmsLinkState.CONNECTED
                    return
                }
                // Opened and recorded on this session; cleanup is session-scoped below.
                synchronized(windowLock) { openWindowIfRunning(scanner, session) } ?: break
                try {
                    delay(policy.maxScanWindowMs)
                } finally {
                    synchronized(windowLock) { session.cleanupOwn() }
                }
                if (!running) break
                if (policy.stopWhenTargetKnown && targetsKnown) {
                    mutableState.value = TpmsLinkState.CONNECTED
                    return
                }
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

    /**
     * Hands [op] to the platform, returning false when nothing could be started (no
     * connection, permission denied, synchronous stack error) — the queue then releases
     * the slot instead of waiting for a completion callback that cannot arrive.
     */
    private fun beginOperation(op: GattOp): Boolean {
        // Only the connection the link currently owns may receive work.
        val current = link.current ?: return false
        return try {
            startOperation(current, op)
        } catch (_: SecurityException) {
            mutableState.value = TpmsLinkState.BLOCKED
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Starts the next operation. The legacy descriptor-write path is deliberately
     * deprecated (API < 33 has no value-carrying overload), hence the suppression.
     */
    @Suppress("DEPRECATION")
    private fun startOperation(gatt: BluetoothGatt, op: GattOp): Boolean = when (op) {
        is GattOp.EnableNotifications -> {
            gatt.setCharacteristicNotification(op.characteristic, true)
            val descriptor = op.characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
            if (descriptor == null) {
                false
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+ returns a status code: an immediate rejection (request busy,
                // device not connected) never produces an onDescriptorWrite callback,
                // so report it as refused and let the queue release/skip the operation
                // rather than waiting forever for a completion that cannot arrive.
                gatt.writeDescriptor(descriptor, op.descriptorValue) == BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = op.descriptorValue
                gatt.writeDescriptor(descriptor)
            }
        }

        is GattOp.Read -> gatt.readCharacteristic(op.characteristic)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            // Identity check + work run atomically under the link lock: teardown cannot
            // drop this connection mid-callback, and a dead connection cannot act on the
            // live one (a late callback from a superseded/stopped link returns null here).
            link.ifOwned(gatt) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        mutableState.value = TpmsLinkState.BLOCKED
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Release the handle (close) and the queue: a later scan result may
                    // then open a fresh connection.
                    operations.clear()
                    link.end()
                    mutableState.value = TpmsLinkState.DISCONNECTED
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            link.ifOwned(gatt) {
                if (status != BluetoothGatt.GATT_SUCCESS) return@ifOwned
                mutableState.value = TpmsLinkState.CONNECTED
                operations.clear()
                for (service in gatt.services) {
                    if (!TpmsCandidateUuids.isCandidate(service.uuid)) continue
                    for (char in service.characteristics) {
                        if (!TpmsCandidateUuids.isCandidate(char.uuid)) continue
                        val op = operationFor(char) ?: continue
                        operations.enqueue(op)
                    }
                }
                operations.pump()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            // Releases the completed operation AND starts the next queued one — but only
            // for the connection the link owns (a late write from a dead link must not
            // advance the current queue).
            link.ifOwned(gatt) { operations.completed() }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            // No packet is ever published from a connection that is no longer owned
            // (covers post-stop and post-disconnect deliveries).
            link.ifOwned(gatt) { emitPacket(value) }
        }

        /**
         * Legacy delivery path for API < 33 devices: the value-carrying overload above
         * is only invoked from API 33 on, so without this override no notification
         * would ever be emitted on minSdk 26..32 hardware.
         */
        @Deprecated("Required for API < 33 delivery")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            link.ifOwned(gatt) { emitPacket(value) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            link.ifOwned(gatt) {
                if (status == BluetoothGatt.GATT_SUCCESS) emitPacket(value)
                operations.completed()
            }
        }

        /**
         * Legacy read delivery for API < 33 devices, with the same completion drive:
         * the serialized queue must be released here too or it would stall forever on
         * minSdk 26..32 hardware.
         */
        @Deprecated("Required for API < 33 delivery")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            link.ifOwned(gatt) {
                if (status == BluetoothGatt.GATT_SUCCESS) emitPacket(value)
                operations.completed()
            }
        }
    }

    /** Maps a candidate characteristic to the operation that starts its data flow. */
    private fun operationFor(characteristic: BluetoothGattCharacteristic): GattOp? {
        val props = characteristic.properties
        val notify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val indicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        return when {
            notify -> GattOp.EnableNotifications(
                characteristic,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            )

            indicate -> GattOp.EnableNotifications(
                characteristic,
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
            )

            (props and BluetoothGattCharacteristic.PROPERTY_READ) != 0 -> GattOp.Read(characteristic)

            else -> null
        }
    }

    /**
     * Captures a notification NOW (payload copy + capture timestamp, inside the owning
     * connection gate) and hands it to [publisher] together with the link session token.
     * The deferred emission therefore carries an already-captured packet and re-validates
     * that token before emitting: a job resumed after stop() or after a reconnect is
     * dropped rather than delivered as fresh data.
     */
    private fun emitPacket(value: ByteArray) {
        publisher.publish(
            link.session(),
            RawTpmsPacket(
                bytes = value.copyOf(),
                source = TpmsSource.BLE_GATT,
                capturedElapsedMs = android.os.SystemClock.elapsedRealtime(),
            ),
        )
    }

    companion object {
        /** Standard client-characteristic configuration descriptor (CCCD). */
        val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
