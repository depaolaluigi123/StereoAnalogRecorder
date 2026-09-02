package com.stereoanalogrecorder.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.stereoanalogrecorder.app.settings.PreferencesRepository
import com.stereoanalogrecorder.app.state.MicStateStore

class StereoAnalogRecorderApp : Application() {

    lateinit var preferences: PreferencesRepository
        private set

    lateinit var micStateStore: MicStateStore
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = PreferencesRepository(this)
        micStateStore = MicStateStore(preferences)
        // Theme/language are not driven by the AppCompat per-app locale API.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }
}