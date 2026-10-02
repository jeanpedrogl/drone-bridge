package io.github.jeanpedrogl.dronebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRelayConfigTest {
    @Test
    fun startsWithTheOriginalValues() {
        val c = VideoRelayConfig()
        assertEquals(8, c.fps)
        assertEquals(640, c.maxWidth)
        assertEquals(50, c.jpegQuality)
        assertEquals(125L, c.periodMs)
    }

    @Test
    fun updatesOnlyTheGivenValues() {
        val c = VideoRelayConfig()
        c.update(fps = 20, maxWidth = null, jpegQuality = 85)
        assertEquals(20, c.fps)
        assertEquals(640, c.maxWidth)
        assertEquals(85, c.jpegQuality)
        assertEquals(50L, c.periodMs)
    }

    @Test
    fun outOfRangeValueChangesNothing() {
        val c = VideoRelayConfig()
        val result = runCatching { c.update(fps = 20, maxWidth = 5000, jpegQuality = 60) }
        assertTrue(result.isFailure)
        assertEquals(8, c.fps)
        assertEquals(50, c.jpegQuality)
    }
}
