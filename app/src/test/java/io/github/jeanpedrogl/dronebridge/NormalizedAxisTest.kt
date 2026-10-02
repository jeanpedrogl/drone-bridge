package io.github.jeanpedrogl.dronebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizedAxisTest {
    @Test
    fun valuesInsideTheRangePassThrough() {
        assertEquals(0.25f, normalizedAxis(0.25f), 0f)
        assertEquals(-1f, normalizedAxis(-1f), 0f)
        assertEquals(0f, normalizedAxis(0f), 0f)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        assertEquals(1f, normalizedAxis(5f), 0f)
        assertEquals(-1f, normalizedAxis(-250f), 0f)
        assertEquals(1f, normalizedAxis(Float.POSITIVE_INFINITY), 0f)
    }

    @Test
    fun nanBecomesZeroInsteadOfReachingTheAircraft() {
        assertEquals(0f, normalizedAxis(Float.NaN), 0f)
    }
}
