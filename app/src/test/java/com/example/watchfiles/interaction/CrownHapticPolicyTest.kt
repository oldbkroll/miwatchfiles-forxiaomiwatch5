package com.example.watchfiles.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownHapticPolicyTest {
    @Test
    fun crownScrollDoesNotEmitContinuousHaptic() {
        val policy = CrownHapticPolicy()

        assertNull(policy.boundaryReached(deltaPixels = -12f, consumedPixels = -12f))
    }

    @Test
    fun reachingTopEmitsOneBoundaryCue() {
        val policy = CrownHapticPolicy()

        assertEquals(
            CrownBoundary.Top,
            policy.boundaryReached(deltaPixels = 12f, consumedPixels = 0f),
        )
        assertNull(policy.boundaryReached(deltaPixels = 12f, consumedPixels = 0f))
    }

    @Test
    fun reachingBottomEmitsOneBoundaryCue() {
        val policy = CrownHapticPolicy()

        assertEquals(
            CrownBoundary.Bottom,
            policy.boundaryReached(deltaPixels = -12f, consumedPixels = 0f),
        )
        assertNull(policy.boundaryReached(deltaPixels = -12f, consumedPixels = 0f))
    }

    @Test
    fun boundaryCueCanEmitAgainAfterLeavingAndReturning() {
        val policy = CrownHapticPolicy()

        assertEquals(
            CrownBoundary.Top,
            policy.boundaryReached(deltaPixels = 12f, consumedPixels = 0f),
        )
        assertNull(policy.boundaryReached(deltaPixels = -12f, consumedPixels = -12f))

        assertEquals(
            CrownBoundary.Top,
            policy.boundaryReached(deltaPixels = 12f, consumedPixels = 0f),
        )
    }

    @Test
    fun longPressFeedbackOnlyAppliesWhenEnteringSelectionMode() {
        assertTrue(shouldEmitLongPressHaptic(selectionMode = false))
        assertFalse(shouldEmitLongPressHaptic(selectionMode = true))
    }
}
