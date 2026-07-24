package com.example.watchfiles.interaction

import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticCue {
    ScrollBoundary,
    LongPress,
}

fun View.performWatchHaptic(cue: HapticCue): Boolean = runCatching {
    performHapticFeedback(
        when (cue) {
            HapticCue.ScrollBoundary -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticCue.LongPress -> HapticFeedbackConstants.LONG_PRESS
        },
    )
}.getOrDefault(false)
