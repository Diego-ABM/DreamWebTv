package com.example.dreamweb.managers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Curva de aceleración del cursor D-pad (FR-015, FR-017, R14). */
class DpadAccelerationTest {

    @Test
    fun `repeatCount 0 devuelve el paso base`() {
        assertEquals(DpadAcceleration.BASE_STEP, DpadAcceleration.stepFor(0), 0.001f)
        assertEquals(25f, DpadAcceleration.stepFor(0), 0.001f)
    }

    @Test
    fun `el paso es monotono creciente con repeatCount`() {
        var previous = DpadAcceleration.stepFor(0)
        for (repeat in 1..12) {
            val step = DpadAcceleration.stepFor(repeat)
            assertTrue("step($repeat)=$step debe ser >= $previous", step >= previous)
            previous = step
        }
    }

    @Test
    fun `el paso crece efectivamente antes del tope`() {
        assertTrue(DpadAcceleration.stepFor(1) > DpadAcceleration.stepFor(0))
        assertTrue(DpadAcceleration.stepFor(5) > DpadAcceleration.stepFor(1))
    }

    @Test
    fun `el tope MAX_STEP nunca se supera`() {
        for (repeat in listOf(12, 13, 50, 100, Int.MAX_VALUE / 1000)) {
            val step = DpadAcceleration.stepFor(repeat)
            assertTrue("step($repeat)=$step supera el tope", step <= DpadAcceleration.MAX_STEP)
        }
        assertEquals(DpadAcceleration.MAX_STEP, DpadAcceleration.stepFor(100), 0.001f)
    }

    @Test
    fun `volver a repeatCount 0 resetea al paso base`() {
        DpadAcceleration.stepFor(20) // acelerado
        assertEquals(DpadAcceleration.BASE_STEP, DpadAcceleration.stepFor(0), 0.001f)
    }
}
