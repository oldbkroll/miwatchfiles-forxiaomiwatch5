package com.example.watchfiles.interaction

internal const val DEFAULT_CROWN_HAPTIC_INTERVAL_MILLIS = 40L

class CrownHapticPolicy(
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val minIntervalMillis: Long = DEFAULT_CROWN_HAPTIC_INTERVAL_MILLIS,
) {
    private var lastCrownTickAt: Long? = null

    fun shouldEmitCrownTick(consumedPixels: Float): Boolean {
        if (consumedPixels == 0f) return false

        val now = clockMillis()
        val last = lastCrownTickAt
        if (last != null && now - last < minIntervalMillis) return false

        lastCrownTickAt = now
        return true
    }
}

fun shouldEmitLongPressHaptic(selectionMode: Boolean): Boolean = !selectionMode
