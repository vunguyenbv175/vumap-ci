package vn.vumap.feature.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WheelBinderTest {
    @Test fun bindFourWheels() {
        val binder = WheelBinder()
        assertTrue(binder.bind("AA01", Wheel.FL) is WheelBinder.BindResult.Success)
        assertTrue(binder.bind("BB02", Wheel.FR) is WheelBinder.BindResult.Success)
        assertTrue(binder.bind("CC03", Wheel.RL) is WheelBinder.BindResult.Success)
        assertTrue(binder.bind("DD04", Wheel.RR) is WheelBinder.BindResult.Success)
        assertEquals(Wheel.FL, binder.wheelOf("AA01"))
        assertEquals("DD04", binder.sensorOf(Wheel.RR))
    }

    @Test fun duplicateIdToTwoWheelsRejected() {
        val binder = WheelBinder()
        assertTrue(binder.bind("SAME01", Wheel.FL) is WheelBinder.BindResult.Success)
        val second = binder.bind("SAME01", Wheel.FR)
        assertTrue(second is WheelBinder.BindResult.Failure)
        // Original mapping intact.
        assertEquals(Wheel.FL, binder.wheelOf("SAME01"))
        assertEquals(null, binder.sensorOf(Wheel.FR))
    }

    @Test fun noAutoAssignByArrivalOrder() {
        val binder = WheelBinder()
        // Merely observing IDs must not create mappings.
        listOf("X1", "X2", "X3").forEach { binder.wheelOf(it) }
        assertTrue(binder.snapshot().isEmpty())
    }

    @Test fun swapAndRotate() {
        val binder = WheelBinder()
        binder.bind("FL1", Wheel.FL)
        binder.bind("FR1", Wheel.FR)
        binder.bind("RL1", Wheel.RL)
        binder.bind("RR1", Wheel.RR)
        assertTrue(binder.swap(Wheel.FL, Wheel.FR))
        assertEquals(Wheel.FR, binder.wheelOf("FL1"))
        assertTrue(binder.rotateFrontBack())
        // After FL<->FR then front-back rotation, FL holds RR-origin sensor.
        assertEquals(4, binder.snapshot().size)
    }

    @Test fun unbindThenRebindAllowed() {
        val binder = WheelBinder()
        binder.bind("S1", Wheel.FL)
        assertTrue(binder.unbindWheel(Wheel.FL))
        val result = binder.bind("S1", Wheel.RR)
        assertTrue(result is WheelBinder.BindResult.Success)
        assertEquals(Wheel.RR, binder.wheelOf("S1"))
    }

    @Test fun caseInsensitiveLookup() {
        val binder = WheelBinder()
        binder.bind("ab12cd34", Wheel.FL)
        assertEquals(Wheel.FL, binder.wheelOf("AB12CD34"))
        assertFalse(binder.bind("", Wheel.FR) is WheelBinder.BindResult.Success)
    }
}
