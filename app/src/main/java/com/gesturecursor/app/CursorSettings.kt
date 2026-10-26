package com.gesturecursor.app

import android.content.Context
import android.content.SharedPreferences

class CursorSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cursor_prefs", Context.MODE_PRIVATE)

    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, 1.5f)
        set(value) = prefs.edit().putFloat(KEY_SENSITIVITY, value).apply()

    var smoothingCutoff: Float
        get() = prefs.getFloat(KEY_SMOOTHING, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SMOOTHING, value).apply()

    var pinchThreshold: Float
        get() = prefs.getFloat(KEY_PINCH_THRESHOLD, 0.05f)
        set(value) = prefs.edit().putFloat(KEY_PINCH_THRESHOLD, value).apply()

    var isHapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    companion object {
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_SMOOTHING = "smoothing_cutoff"
        private const val KEY_PINCH_THRESHOLD = "pinch_threshold"
        private const val KEY_HAPTICS = "haptics_enabled"
    }
}
