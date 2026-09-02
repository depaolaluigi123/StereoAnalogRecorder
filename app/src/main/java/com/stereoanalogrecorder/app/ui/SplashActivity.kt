package com.stereoanalogrecorder.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.stereoanalogrecorder.app.StereoAnalogRecorderApp
import com.stereoanalogrecorder.app.R
import com.stereoanalogrecorder.app.audio.AlsaGainController
import com.stereoanalogrecorder.app.settings.AppConfigContext
import com.stereoanalogrecorder.app.settings.PreferencesRepository
import com.stereoanalogrecorder.app.settings.ThemeManager

/**
 * Launcher activity that performs the one-time root probe before handing off to
 * [MainActivity]. Because [AlsaGainController.initialize] is idempotent (guarded by
 * an `initialized` flag), the `su` probe runs only here — even when MainActivity
 * is later recreated by a theme or language change, the root check is skipped.
 *
 * The su probe timeout is 1 second (see [AlsaGainController.detectSuPrefix]),
 * so on non-rooted devices the splash adds at most ~1s of latency. Rooted
 * devices with Magisk auto-grant complete in milliseconds.
 */
class SplashActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = PreferencesRepository(newBase)
        super.attachBaseContext(AppConfigContext.wrap(newBase, prefs))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = (application as StereoAnalogRecorderApp).preferences
        setTheme(ThemeManager.styleRes(prefs.themeMode))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Run the root+ALSA probe once, on a background thread, then hand off.
        // startActivity/finish must run on the main thread — posting them
        // avoids crashes on Android 12+ (startActivity from a background
        // thread is restricted unless a background-activity-start token is
        // held).
        val mainHandler = Handler(Looper.getMainLooper())
        Thread {
            val controller = AlsaGainController.get(this)
            controller.initialize()
            mainHandler.post {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }.start()
    }
}
