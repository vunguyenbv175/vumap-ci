package vn.vumap.feature.tpms

import java.util.UUID

/**
 * Vendor candidate BLE identifiers (static analysis only).
 *
 * Service 0000FFF0 / 0000FFE0 and characteristics FFF1 / FFF2 / FFE1 are
 * vendor/custom GATT identifiers, NOT a universal TPMS BLE standard.
 * UUID discovery alone = [TpmsDeviceVerification.DISCOVERED_ONLY], never
 * [TpmsDeviceVerification.SUPPORTED]. Compatibility needs real
 * advertisement/GATT payload + checksum/scaling/sensorId proof per device.
 */
object TpmsCandidateUuids {
    const val SERVICE_FFF0 = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val SERVICE_FFE0 = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_FFF1 = "0000fff1-0000-1000-8000-00805f9b34fb"
    const val CHAR_FFF2 = "0000fff2-0000-1000-8000-00805f9b34fb"
    const val CHAR_FFE1 = "0000ffe1-0000-1000-8000-00805f9b34fb"

    const val CANDIDATE_ONLY_WARNING =
        "FFF0/FFE0/FFF1/FFE1 are candidate UUIDs from vendor static analysis; " +
            "not proven compatible with any sensor."

    val candidateServices: Set<UUID> = setOf(
        UUID.fromString(SERVICE_FFF0),
        UUID.fromString(SERVICE_FFE0),
    )

    val candidateCharacteristics: Set<UUID> = setOf(
        UUID.fromString(CHAR_FFF1),
        UUID.fromString(CHAR_FFF2),
        UUID.fromString(CHAR_FFE1),
    )

    /** True when a discovered UUID matches a vendor candidate (discovery only). */
    fun isCandidate(uuid: UUID): Boolean =
        candidateServices.contains(uuid) || candidateCharacteristics.contains(uuid)

    fun isCandidate(uuidString: String): Boolean = try {
        isCandidate(UUID.fromString(uuidString.lowercase()))
    } catch (_: IllegalArgumentException) {
        false
    }
}
