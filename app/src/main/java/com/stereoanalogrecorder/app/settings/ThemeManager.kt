package com.stereoanalogrecorder.app.settings

import com.stereoanalogrecorder.app.R

/**
 * Maps the user theme preference to an app theme style.
 * Colors come from colors_light.xml / colors_dark.xml via that style — not from system dark mode.
 */
object ThemeManager {

    fun styleRes(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> R.style.Theme_StereoAnalogRecorder_Light
        ThemeMode.DARK -> R.style.Theme_StereoAnalogRecorder_Dark
    }
}
