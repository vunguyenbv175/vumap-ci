package vn.vumap.feature.tpms

/**
 * Manual sensorId <-> wheel mapping (FL/FR/RL/RR).
 *
 * Rules:
 * - User must pair each unique sensorId explicitly; no silent assignment by
 *   RSSI, arrival order, or Android MAC (which may rotate).
 * - One sensorId maps to at most one wheel: binding the same ID to a second
 *   wheel fails until explicitly unbound/moved.
 * - Rebinding a wheel to a new sensor is allowed (tire swap/rotate path) via
 *   [bind] (replaces) or [swap]/[rotate].
 */
class WheelBinder {
    private val sensorToWheel = mutableMapOf<String, Wheel>()
    private val wheelToSensor = mutableMapOf<Wheel, String>()

    /** Bind sensor to wheel. Returns failure when ID is taken by another wheel. */
    fun bind(sensorId: String, wheel: Wheel): BindResult {
        val norm = sensorId.trim().uppercase()
        if (norm.isEmpty()) return BindResult.Failure("Mã cảm biến trống")
        val owner = sensorToWheel[norm]
        if (owner != null && owner != wheel) {
            return BindResult.Failure(
                "Mã $norm đã gắn ${owner.name}, gỡ trước khi gắn ${wheel.name}",
            )
        }
        val prevSensor = wheelToSensor[wheel]
        if (prevSensor != null && prevSensor != norm) {
            sensorToWheel.remove(prevSensor)
        }
        sensorToWheel[norm] = wheel
        wheelToSensor[wheel] = norm
        return BindResult.Success
    }

    fun unbindWheel(wheel: Wheel): Boolean {
        val sensor = wheelToSensor.remove(wheel) ?: return false
        sensorToWheel.remove(sensor)
        return true
    }

    fun unbindSensor(sensorId: String): Boolean {
        val wheel = sensorToWheel.remove(sensorId.trim().uppercase()) ?: return false
        wheelToSensor.remove(wheel)
        return true
    }

    fun clear() {
        sensorToWheel.clear()
        wheelToSensor.clear()
    }

    fun wheelOf(sensorId: String): Wheel? = sensorToWheel[sensorId.trim().uppercase()]

    fun sensorOf(wheel: Wheel): String? = wheelToSensor[wheel]

    fun snapshot(): Map<Wheel, String> = wheelToSensor.toMap()

    /** Swap two wheels' sensors (tire swap). False when either wheel unmapped. */
    fun swap(a: Wheel, b: Wheel): Boolean {
        val sa = wheelToSensor[a] ?: return false
        val sb = wheelToSensor[b] ?: return false
        wheelToSensor[a] = sb
        wheelToSensor[b] = sa
        sensorToWheel[sa] = b
        sensorToWheel[sb] = a
        return true
    }

    /**
     * Front-back rotation: FL<->RL and FR<->RR.
     * False when any wheel unmapped.
     */
    fun rotateFrontBack(): Boolean {
        if (Wheel.entries.any { wheelToSensor[it] == null }) return false
        return swap(Wheel.FL, Wheel.RL) && swap(Wheel.FR, Wheel.RR)
    }

    sealed interface BindResult {
        data object Success : BindResult
        data class Failure(val reasonVi: String) : BindResult
    }
}
