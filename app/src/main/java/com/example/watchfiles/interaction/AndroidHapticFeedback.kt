package com.example.watchfiles.interaction

import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticCue {
    CrownTick,
    LongPress,
}

fun View.performWatchHaptic(cue: HapticCue): Boolean = runCatching {
    performHapticFeedback(
        when (cue) {
            HapticCue.CrownTick -> HapticFeedbackConstants.CLOCK_TICK
            HapticCue.LongPress -> HapticFeedbackConstants.LONG_PRESS
        },
    )
}.getOrDefault(false)
