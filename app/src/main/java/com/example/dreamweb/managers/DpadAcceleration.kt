package com.example.dreamweb.managers

/**
 * Aceleración del cursor virtual por repeatCount del KeyEvent (FR-015, R14).
 * Kotlin puro — testeable en JVM.
 */
object DpadAcceleration {
    const val BASE_STEP = 25f
    const val MAX_STEP = 150f

    fun stepFor(repeatCount: Int): Float =
        (BASE_STEP * (1f + repeatCount * 0.10f)).coerceAtMost(MAX_STEP)
}
