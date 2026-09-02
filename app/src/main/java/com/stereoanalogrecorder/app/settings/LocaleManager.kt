package com.stereoanalogrecorder.app.settings

import android.content.Context

/**
 * Language helpers. Strings are loaded from values/strings.xml (English)
 * or values-it/strings.xml (Italian) through [AppConfigContext] only.
 */
object LocaleManager {

    fun wrapContext(base: Context, preferences: PreferencesRepository): Context =
        AppConfigContext.wrap(base, preferences)
}
