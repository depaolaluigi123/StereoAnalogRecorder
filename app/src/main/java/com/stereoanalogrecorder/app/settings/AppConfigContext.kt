package com.stereoanalogrecorder.app.settings

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Builds an app-owned Context from user preferences only.
 * Does not use AppCompat/system night-mode or per-app language APIs.
 *
 * - Language → Configuration locale → loads values/ or values-it/ strings
 * - Theme → applied separately via setTheme(Theme.StereoAnalogRecorder.Light/Dark)
 */
object AppConfigContext {

    fun wrap(base: Context, preferences: PreferencesRepository): Context {
        val locale = Locale.forLanguageTag(preferences.language.localeTag)
        Locale.setDefault(locale)

        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        // Keep uiMode neutral: theme is not driven by system/AppCompat night mode.
        config.uiMode =
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_UNDEFINED

        return base.createConfigurationContext(config)
    }
}
