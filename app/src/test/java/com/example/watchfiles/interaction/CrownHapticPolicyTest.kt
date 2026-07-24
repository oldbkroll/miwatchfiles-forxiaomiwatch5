package com.example.watchfiles.interaction

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrownHapticPolicyTest {
    @Test
    fun firstNonZeroScrollAllowsCrownTick() {
        val policy = CrownHapticPolicy(clockMillis = { 1_000L })

        assertTrue(policy.shouldEmitCrownTick(consumedPixels = 12f))
    }

    @Test
    fun zeroConsumedScrollDoesNotAllowCrownTick() {
        val policy = CrownHapticPolicy(clockMillis = { 1_000L })

        assertFalse(policy.shouldEmitCrownTick(consumedPixels = 0f))
    }

    @Test
    fun crownTickIsSuppressedInsideFortyMillisecondWindow() {
        var now = 1_000L
        val policy = CrownHapticPolicy(clockMillis = { now })

        assertTrue(policy.shouldEmitCrownTick(consumedPixels = 1f))
        now = 1_039L

        assertFalse(policy.shouldEmitCrownTick(consumedPixels = 1f))
    }

    @Test
    fun crownTickIsAllowedAtFortyMillisecondBoundary() {
        var now = 1_000L
        val policy = CrownHapticPolicy(clockMillis = { now })

        assertTrue(policy.shouldEmitCrownTick(consumedPixels = 1f))
        now = 1_040L

        assertTrue(policy.shouldEmitCrownTick(consumedPixels = 1f))
    }

    @Test
    fun longPressFeedbackOnlyAppliesWhenEnteringSelectionMode() {
        assertTrue(shouldEmitLongPressHaptic(selectionMode = false))
        assertFalse(shouldEmitLongPressHaptic(selectionMode = true))
    }
}
