package com.example.watchfiles.interaction

enum class CrownBoundary {
    Top,
    Bottom,
}

class CrownHapticPolicy {
    private var lastBoundary: CrownBoundary? = null

    fun boundaryReached(deltaPixels: Float, consumedPixels: Float): CrownBoundary? {
        if (deltaPixels == 0f) return null

        if (consumedPixels != 0f) {
            lastBoundary = null
            return null
        }

        val boundary = if (deltaPixels > 0f) CrownBoundary.Top else CrownBoundary.Bottom
        if (lastBoundary == boundary) return null

        lastBoundary = boundary
        return boundary
    }
}

fun shouldEmitLongPressHaptic(selectionMode: Boolean): Boolean = !selectionMode
