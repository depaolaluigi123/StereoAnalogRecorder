package com.stereoanalogrecorder.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.slider.Slider
import com.stereoanalogrecorder.app.StereoAnalogRecorderApp
import com.stereoanalogrecorder.app.R
import com.stereoanalogrecorder.app.audio.AlsaGainController
import com.stereoanalogrecorder.app.audio.CaptureSession
import com.stereoanalogrecorder.app.databinding.ActivityMainBinding
import com.stereoanalogrecorder.app.service.LiveCaptureService
import com.stereoanalogrecorder.app.service.MonitorService
import com.stereoanalogrecorder.app.service.RecordingService
import com.stereoanalogrecorder.app.settings.AppConfigContext
import com.stereoanalogrecorder.app.settings.GainControlMode
import com.stereoanalogrecorder.app.settings.MeterStyle
import com.stereoanalogrecorder.app.settings.PreferencesRepository
import com.stereoanalogrecorder.app.settings.RecordFormat
import com.stereoanalogrecorder.app.settings.ThemeManager
import com.stereoanalogrecorder.app.settings.ThemeMode
import com.stereoanalogrecorder.app.state.MicStateStore
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: PreferencesRepository
    private lateinit var stateStore: MicStateStore

    private var spinnerReady = false
    private var sampleRateSpinnerReady = false
    private var unsubscribe: (() -> Unit)? = null
    private var meter1: View? = null
    private var meter1Digital: DigitalMeterView? = null
    private var meter2Digital: DigitalMeterView? = null
    private var meterAnalog1: AnalogMeterView? = null
    private var meterAnalog2: AnalogMeterView? = null
    private var alsaStatusText: TextView? = null
    private var alsaController: AlsaGainController? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var deviceCallback: AudioDeviceCallback? = null
    private var monitorUiBound = false
    /** True while bindState() is programmatically updating the UI, so toggle
     *  listeners can short-circuit and avoid overwriting store/SharedPreferences
     *  with the *effective* mode (which differs from the stored mode when root
     *  is unavailable). Mirrors the monitorUiBound guard pattern. */
    private var bindingStateInProgress = false
    private var lastIsRecording = false

    /**
     * True while the user is actively dragging the mic1 / mic2 gain slider.
     *
     * When the user drags the slider slowly, the underlying `addOnChangeListener`
     * fires on every intermediate finger position. Each fire currently pushes a
     * new dB value into [MicStateStore], which (a) triggers a forked
     * `su -c tinymix` write from the capture loop and (b) refreshes the foreground
     * notification. Two issues with that:
     *
     *  1. The ALSA write inside [AlsaGainController.setAnalogGainDb] quantises
     *     the requested dB to the codec's raw step grid with `(db / stepDb).toInt()`,
     *     which truncates *toward zero*. For negative dB (attenuation) that means
     *     the codec lands on a *less* attenuated step than the user asked for
     *     (e.g. on WCD937x with stepDb=1.5: requesting -10 dB writes raw step
     *     -6, i.e. -9 dB applied). The meters therefore show a level that doesn't
     *     match the spinner the user just moved through, and the notification
     *     displays the truncated codec value, not the spinner value the user
     *     is still holding.
     *  2. One `su -c tinymix` fork per finger movement thrashes Magisk's su
     *     and the kernel mixer when the user dials slowly.
     *
     * To fix both, the change listener only updates the on-screen value label
     * while a drag is in progress and defers the actual store / codec /
     * notification update to `onStopTrackingTouch`. One write per gesture
     * instead of one per finger movement, and that single write is applied
     * to the value the user *released* on — which is the value the slider
     * thumb, the value label, and the notification all show in sync.
     *
     * [bindStateInternal] also respects these flags so an external store
     * change (e.g. notification button, recording state) can't yank the
     * slider out from under the user's finger mid-drag.
     */
    private var isDraggingMic1 = false
    private var isDraggingMic2 = false

    private val savedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val name = intent?.getStringExtra(RecordingService.EXTRA_SAVED_NAME) ?: ""
            if (name.isNotEmpty()) {
                Toast.makeText(this@MainActivity, getString(R.string.status_saved, name), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.RECORD_AUDIO] == true
            binding.recordButton.isEnabled = granted
            if (!granted) {
                Toast.makeText(this@MainActivity, getString(R.string.status_no_mic), Toast.LENGTH_LONG).show()
            }
            if (granted) startLiveCapture()
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun attachBaseContext(newBase: Context) {
        val prefs = PreferencesRepository(newBase)
        super.attachBaseContext(AppConfigContext.wrap(newBase, prefs))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as StereoAnalogRecorderApp
        preferences = app.preferences
        stateStore = app.micStateStore
        setTheme(ThemeManager.styleRes(preferences.themeMode))
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        applyWindowChrome()
        setupBitrateSpinner()
        setupSampleRateSpinner()
        bindUi()
        // Wire up the ALSA status indicator and controller BEFORE the initial
        // bindState() call so that root availability is known when the UI is
        // first rendered. If alsaController isn't set yet, rootAvailable falls
        // back to false and the control-type toggle shows Level mode even when
        // ANALOG_GAIN is the stored default — leaving the Gain button unchecked.
        alsaStatusText = binding.alsaStatusText
        alsaController = AlsaGainController.get(this)
        if (alsaController!!.isInitialized) {
            // Root probe already ran in SplashActivity — refresh the status
            // indicator AND re-sync the full UI so the Gain button is shown
            // as checked when root is available.
            updateAlsaStatus(alsaController!!)
            maybeShowRootPermissionAlert(alsaController!!)
        } else {
            // Fallback for when MainActivity is launched directly (e.g. via
            // notification), bypassing SplashActivity. Probe root once.
            Thread {
                alsaController!!.initialize()
                mainHandler.post {
                    updateAlsaStatus(alsaController!!)
                    maybeShowRootPermissionAlert(alsaController!!)
                    // Re-sync the UI now that root detection has completed: if the
                    // stored mode was ANALOG_GAIN and root just became available, the
                    // earlier bindState() call may have shown Level mode as a placeholder.
                    // This call re-evaluates effectiveMode and restores the correct UI
                    // (Gain button enabled + raw-ALSA slider bounds) without overwriting
                    // the stored mode, because bindState sets the bindingStateInProgress
                    // flag that suppresses re-entrant toggle listeners.
                    bindState(stateStore.snapshot())
                }
            }.start()
        }
        // Initial UI state from store snapshot — alsaController is now available,
        // so the control-type toggle correctly reflects root availability and the
        // Gain button appears checked when ANALOG_GAIN is the stored mode.
        bindState(stateStore.snapshot())
        maybeRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(savedReceiver, IntentFilter(RecordingService.ACTION_SAVED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        unsubscribe = stateStore.observe { state -> bindState(state) }
        startHeadphoneWatch()
        startLiveCapture()
        bindState(stateStore.snapshot())
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        stopHeadphoneWatch()
        stopLiveCaptureIfIdle()
        try { unregisterReceiver(savedReceiver) } catch (_: Throwable) {}
        super.onStop()
    }

    /**
     * Show a root-permission alert when the app does not currently have root access.
     *
     * The decision of *whether* to show the alert lives here in the caller; the
     * actual dialog rendering is delegated to the generic [AlertDialogHelper].
     * The message tells the user to grant root permission *if* their phone is
     * rooted — so it appears regardless, but only rooted users need act on it.
     *
     * The dialog has two buttons: "Cancel" and "Request root permission".
     * Pressing the action button re-probes `su`, which re-triggers the Magisk
     * permission prompt so the app can be granted access.
     */
    private fun maybeShowRootPermissionAlert(controller: AlsaGainController) {
        if (!controller.isRootAvailable) {
            AlertDialogHelper.showWithAction(
                context = this,
                title = getString(R.string.root_permission_dialog_title),
                message = getString(R.string.root_permission_needed),
                actionLabel = getString(R.string.request_root_action),
            ) {
                requestRootAndRefreshStatus(controller)
            }
        }
    }

    /**
     * Re-probe root access and refresh the on-screen root-status line + the
     * rest of the bound UI to reflect the new state.
     *
     * Called at the end of every dialog action that asks the user to grant
     * root permission. The single source of truth for "is root granted?" is
     * [AlsaGainController.status] (exposed via [AlsaGainController.isRootAvailable]
     * and [AlsaGainController.isAnalogGainReady]); this method is the only place
     * outside [AlsaGainController.initialize] that re-evaluates that variable
     * after a user action. Centralising the post-request refresh here means
     * a future dialog that adds a "Request root" button only has to call
     * this one method to keep the status line and the GAIN/LEVEL toggle in
     * sync with the freshly-probed state.
     *
     * Implementation notes:
     *  - [AlsaGainController.requestRootAccess] is invoked on a background
     *    thread because it spawns `su` (which can block on the Magisk
     *    permission prompt).
     *  - The UI refresh runs on the main thread via [mainHandler.post].
     *  - The actual update to [AlsaGainController.status] happens inside
     *    [AlsaGainController.requestRootAccess] → [AlsaGainController.initialize];
     *    by the time we post to the main thread, the variable is already
     *    settled. We just propagate it to the views.
     */
    private fun requestRootAndRefreshStatus(controller: AlsaGainController) {
        Thread {
            controller.requestRootAccess()
            mainHandler.post {
                updateAlsaStatus(controller)
                bindState(stateStore.snapshot())
            }
        }.start()
    }

    /** Update the persistent root-status line in the recording section.
     *
     * Distinguishes three readiness states so the line matches what the Gain
     * toggle will actually do when tapped:
     *  - `isAnalogGainReady` (root + tinymix + ADC controls all ready) →
     *    "Root: available" (active color)
     *  - `isRootAvailable` but not `isAnalogGainReady` (root granted but the
     *    ALSA helper or controls failed) → "Root: available, ALSA helper
     *    not ready" (warning color) — tapping Gain will now show a
     *    "retry" alert instead of silently reverting
     *  - no root → "Root: not available" (secondary color)
     */
    private fun updateAlsaStatus(controller: AlsaGainController) {
        val tv = alsaStatusText ?: return
        val textRes = when {
            controller.isAnalogGainReady -> R.string.root_status_available
            controller.isRootAvailable -> R.string.root_status_helper_missing
            else -> R.string.root_status_unavailable
        }
        val colorAttr = when {
            controller.isAnalogGainReady -> R.attr.alsaStatusActiveColor
            controller.isRootAvailable -> R.attr.alsaStatusWarningColor
            else -> R.attr.textSecondary
        }
        tv.text = getString(textRes)
        val typed = TypedValue()
        theme.resolveAttribute(colorAttr, typed, true)
        tv.setTextColor(typed.data)
        tv.visibility = View.VISIBLE
    }

    // ---- Live meter capture ---------------------------------------------------------

    private fun startLiveCapture() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) return
        if (CaptureSession.isActive()) return
        LiveCaptureService.start(this)
    }

    /**
     * Stop the always-on meter capture only when nothing else is using the mic — i.e.
     * neither a recording nor headphone monitoring is running. Otherwise we'd cut the
     * audio out from under a take or a live-listen.
     *
     * When the classic-controls notification is enabled we intentionally keep the service
     * alive in the background: the notification provides Rec/Stop and per-mic ±1
     * buttons that only work while the foreground service is running.
     */
    private fun stopLiveCaptureIfIdle() {
        val s = stateStore.snapshot()
        if (s.isRecording || s.isMonitoring) return
        if (preferences.showClassicNotification) return
        LiveCaptureService.stop(this)
    }

    private fun applyWindowChrome() {
        val lightBars = preferences.themeMode.modeIsLight()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        window.statusBarColor = ContextCompat.getColor(this,
            if (lightBars) R.color.light_status_bar else R.color.dark_status_bar)
        window.navigationBarColor = ContextCompat.getColor(this,
            if (lightBars) R.color.light_nav_bar else R.color.dark_nav_bar)
    }

    private fun setupBitrateSpinner() {
        val bitrates = PreferencesRepository.ALLOWED_BITRATES
        val labels = bitrates.map { getString(R.string.bitrate_format, it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.bitrateSpinner.adapter = adapter
        // Default to the highest supported bitrate — the encoder's full range
        // is now available, so we start at the top and let the user dial down.
        val maxBitrate = bitrates.last()
        stateStore.setAacBitrateKbps(maxBitrate)
        binding.bitrateSpinner.setSelection(bitrates.indexOf(maxBitrate))
        binding.bitrateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (spinnerReady) stateStore.setAacBitrateKbps(bitrates[position])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerReady = true
    }

    /**
     * Populate the sample-rate spinner from the device's actually supported
     * input rates (queried by [com.stereoanalogrecorder.app.audio.SampleRateSupport]).
     * The list is the intersection of what `AudioRecord` can capture and what
     * the hardware AAC encoder can accept, so any selection in this list is
     * guaranteed to be encodable into the .m4a container.
     *
     * Default selection: the stored preference if it is in the supported set,
     * otherwise the highest supported rate. The LiveCaptureService observer
     * picks up the change and re-opens the AudioRecord at the new rate; the
     * spinners are disabled while a recording is in progress because
     * AudioRecord is bound to its rate at construction.
     */
    private fun setupSampleRateSpinner() {
        val supported = PreferencesRepository.ALLOWED_SAMPLE_RATES
        val labels = supported.map { getString(R.string.sample_rate_format, it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.sampleRateSpinner.adapter = adapter

        val stored = preferences.sampleRateHz
        val initial = if (supported.isEmpty()) stored
            else (supported.firstOrNull { it == stored } ?: supported.last())
        if (initial != stored) preferences.sampleRateHz = initial
        stateStore.setSampleRateHz(initial)
        binding.sampleRateSpinner.setSelection(supported.indexOf(initial).coerceAtLeast(0))

        binding.sampleRateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (!sampleRateSpinnerReady) return
                val rate = supported.getOrNull(position) ?: return
                if (rate == stateStore.snapshot().sampleRateHz) return
                stateStore.setSampleRateHz(rate)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        sampleRateSpinnerReady = true
    }

    private fun bindUi() {
        // Mic 1 gain slider — see [isDraggingMic1] for why the store update
        // is deferred to `onStopTrackingTouch` instead of being pushed on
        // every intermediate finger position.
        binding.mic1Slider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (!fromUser) return@addOnChangeListener
            if (isDraggingMic1) {
                // Mid-drag: only refresh the value label so the user sees
                // their finger position. The store / codec / notification
                // are committed once, on touch end, via the touch listener
                // below — pushing them here would fork one `su -c tinymix`
                // per finger movement and let the codec's truncate-toward-zero
                // quantisation leave the meters out of sync with the spinner.
                updateMicValueLabel(mic = 1, sliderValue = value.toInt(), state = stateStore.snapshot())
                return@addOnChangeListener
            }
            val state = stateStore.snapshot()
            if (state.gainControlMode == GainControlMode.ANALOG_GAIN) {
                // GAIN mode: slider shows raw ALSA value; convert to dB equivalent.
                val db = rawToDb(value.toInt(), alsaController)
                stateStore.setGainMic1(db)
            } else {
                // LEVEL mode: slider shows dB directly.
                stateStore.setGainMic1(value.toInt())
            }
        }
        binding.mic1Slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isDraggingMic1 = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                isDraggingMic1 = false
                // Commit the final value the user released on. The store
                // observer fans this out to the capture loop (which writes
                // the analog gain to tinymix) and to [LiveCaptureService]
                // (which refreshes the foreground notification) — both
                // end up in sync with the slider thumb the user is still
                // looking at, instead of a mid-drag intermediate step.
                val state = stateStore.snapshot()
                if (state.gainControlMode == GainControlMode.ANALOG_GAIN) {
                    val db = rawToDb(slider.value.toInt(), alsaController)
                    stateStore.setGainMic1(db)
                } else {
                    stateStore.setGainMic1(slider.value.toInt())
                }
            }
        })
        // Mic 2 gain slider — same pattern, see [isDraggingMic2].
        binding.mic2Slider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            if (!fromUser) return@addOnChangeListener
            if (isDraggingMic2) {
                updateMicValueLabel(mic = 2, sliderValue = value.toInt(), state = stateStore.snapshot())
                return@addOnChangeListener
            }
            val state = stateStore.snapshot()
            if (state.gainControlMode == GainControlMode.ANALOG_GAIN) {
                val db = rawToDb(value.toInt(), alsaController)
                stateStore.setGainMic2(db)
            } else {
                stateStore.setGainMic2(value.toInt())
            }
        }
        binding.mic2Slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isDraggingMic2 = true
            }
            override fun onStopTrackingTouch(slider: Slider) {
                isDraggingMic2 = false
                val state = stateStore.snapshot()
                if (state.gainControlMode == GainControlMode.ANALOG_GAIN) {
                    val db = rawToDb(slider.value.toInt(), alsaController)
                    stateStore.setGainMic2(db)
                } else {
                    stateStore.setGainMic2(slider.value.toInt())
                }
            }
        })
        // Link switch — guarded against re-entrancy from bindState(), same pattern
        // as controlTypeToggle above. Without the bindingStateInProgress guard,
        // programmatic setChecked calls during bindState can fire the listener
        // and cancel a user's in-flight tap, requiring multiple presses.
        binding.linkSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (bindingStateInProgress) return@setOnCheckedChangeListener
            if (stateStore.snapshot().linkGains == isChecked) return@setOnCheckedChangeListener
            stateStore.setLinkGains(isChecked)
        }

        // Control type toggle (Gain vs Level). The Gain button is always
        // pressable — the gate that used to live in bindState() (`analogGainReady
        // && !recording`) has been removed, so the user can tap it even before
        // the root check completes. The actual mode switch is now gated here, on
        // the click, so a missing-root case shows an explanatory alert instead
        // of silently downgrading the toggle to Level. See root-button-fix
        // memory for context.
        binding.controlTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            // Guard against re-entrancy: bindState() calls check() programmatically
            // to reflect the effective mode when root is unavailable. Without this,
            // the listener would overwrite the user's stored ANALOG_GAIN with LEVEL
            // (or vice-versa) every time the UI is refreshed.
            if (!isChecked || bindingStateInProgress) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.controlGainButton -> handleGainTogglePressed()
                R.id.controlLevelButton -> {
                    if (stateStore.snapshot().gainControlMode != GainControlMode.LEVEL) {
                        stateStore.setGainControlMode(GainControlMode.LEVEL)
                        // Reset gains to 0 dB on mode switch: the dB value means
                        // fundamentally different things in LEVEL mode (digital
                        // DSP) vs GAIN mode (raw ALSA value converted to dB), so
                        // carrying over the previous value would be misleading.
                        stateStore.setGainMic1(0)
                        stateStore.setGainMic2(0)
                        bindState(stateStore.snapshot())
                    }
                }
                else -> { /* unknown button — ignore */ }
            }
        }

        // Format toggle — guarded against re-entrancy from bindState(), same pattern
        // as controlTypeToggle above.
        binding.formatToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || bindingStateInProgress) return@addOnButtonCheckedListener
            val fmt = when (checkedId) {
                R.id.formatWav16Button -> RecordFormat.WAV16
                R.id.formatWav24Button -> RecordFormat.WAV24
                R.id.formatM4aButton -> RecordFormat.M4A
                else -> RecordFormat.WAV16
            }
            if (fmt != preferences.recordFormat) {
                stateStore.setRecordFormat(fmt)
                preferences.recordFormat = fmt
            }
        }

        // Settings
        binding.settingsButton.setOnClickListener {
            SettingsBottomSheet(
                activity = this,
                preferences = preferences,
                stateStore = stateStore,
                onThemeOrLanguageChanged = { recreate() },
                onScaleChanged = {
                    // Push the new scale to the slider bounds; already done in bindState.
                },
                onMeterStyleChanged = { rebuildMeters() },
                onNotificationDisplayChanged = { /* service manages its own notification */ }
            ).show()
        }

        // Exit — stop all background services then close the app.
        binding.exitButton.setOnClickListener {
            // Stop live mic monitoring capture.
            LiveCaptureService.stop(this)
            // Stop headphone monitor (if running).
            MonitorService.stop(this)
            // Stop recording (if running) — this also stops the foreground
            // notification and finalizes any in-progress file.
            RecordingService.stop(this)
            // Close the activity and its task stack so the app fully exits.
            finishAffinity()
        }

        // Record / Stop buttons
        binding.recordButton.setOnClickListener {
            val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasMic) { maybeRequestPermissions(); return@setOnClickListener }
            val state = stateStore.snapshot()
            RecordingService.start(
                this,
                state.recordFormat,
                state.aacBitrateKbps,
                state.sampleRateHz
            )
        }
        binding.stopButton.setOnClickListener {
            RecordingService.stop(this)
        }

        // Tap a peak label below a meter to reset that channel's peak-hold (DAW-style):
        // the running max restarts from now, so the user can re-measure from this point.
        binding.peakMic1Text.setOnClickListener { resetPeakDbUi(channel = 1) }
        binding.peakMic2Text.setOnClickListener { resetPeakDbUi(channel = 2) }

        // Headphone monitor switch — toggles live-listen (works with or without recording).
        binding.monitorSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Avoid re-entrancy when bindState sets the switch programmatically.
            if (!monitorUiBound) return@setOnCheckedChangeListener
            if (stateStore.snapshot().isMonitoring == isChecked) return@setOnCheckedChangeListener
            val state = stateStore.snapshot()
            if (isChecked) {
                val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    maybeRequestPermissions()
                    // Revert the toggle; permission grant will re-enable the user.
                    binding.monitorSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (!state.headphonesConnected) {
                    // Defensive: should be disabled already, but keep coherent if state raced.
                    binding.monitorSwitch.isChecked = false
                    Toast.makeText(this, getString(R.string.monitor_no_headphones), Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                MonitorService.start(this)
            } else {
                MonitorService.stop(this)
            }
        }
    }

    // ---- Headphone detection ------------------------------------------------------

    private fun startHeadphoneWatch() {
        if (deviceCallback != null) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshHeadphones()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshHeadphones()
            }
        }
        am.registerAudioDeviceCallback(callback, mainHandler)
        deviceCallback = callback
        refreshHeadphones()
    }

    private fun stopHeadphoneWatch() {
        deviceCallback?.let {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.unregisterAudioDeviceCallback(it)
        }
        deviceCallback = null
    }

    private fun refreshHeadphones() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val connected = outs.any { isHeadphoneDevice(it) }
        stateStore.setHeadphonesConnected(connected)
    }

    private fun isHeadphoneDevice(info: AudioDeviceInfo): Boolean = when (info.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> false
    }

    private fun bindState(state: MicStateStore.State) {
        // Suppress re-entrant toggle-button listeners while we programmatically
        // update the UI, so that programmatic check() calls don't feed back
        // into the store (see controlTypeToggle.addOnButtonCheckedListener).
        bindingStateInProgress = true
        try {
            bindStateInternal(state)
        } finally {
            bindingStateInProgress = false
        }
    }

    private fun bindStateInternal(state: MicStateStore.State) {
        val scale = state.maxGainScale.toFloat()

        // Determine whether we're in GAIN mode and root is available.
        val isGainModeStored = state.gainControlMode == GainControlMode.ANALOG_GAIN
        // isAnalogGainReady (status == READY) means the full su→tinymix→ADC-controls
        // chain is working. isRootAvailable (status != NO_ROOT) is true even
        // when the helper or controls are missing — using it here would show
        // Gain mode toggle selected but fall through to Level slider bounds.
        val analogGainReady = alsaController?.isAnalogGainReady ?: false
        // When root is not fully available, force LEVEL mode for UI even if
        // ANALOG_GAIN stored.
        val effectiveMode = if (isGainModeStored && !analogGainReady) GainControlMode.LEVEL else state.gainControlMode
        val useGainMode = effectiveMode == GainControlMode.ANALOG_GAIN
        val controlRange = if (useGainMode) {
            alsaController?.mic1ControlRange()
        } else null

        // Control type toggle state — reflect effective mode.
        val targetToggleId = if (effectiveMode == GainControlMode.ANALOG_GAIN)
            R.id.controlGainButton else R.id.controlLevelButton
        if (binding.controlTypeToggle.checkedButtonId != targetToggleId) {
            binding.controlTypeToggle.check(targetToggleId)
        }
        // Lock the control-type toggle while recording — switching Gain↔Level mid-
        // take would reset the sliders and confuse the live capture's analog provider.
        val recording = state.isRecording
        binding.controlTypeToggle.isEnabled = !recording
        // The Gain button is always pressable when not recording: the actual
        // mode switch is gated inside the click handler, where missing root
        // shows an explanatory alert. Removing `analogGainReady` from this
        // gate fixes the bug where the button appeared disabled even after
        // "Root: available" appeared in the status line — see root-button-fix.
        binding.controlGainButton.isEnabled = !recording
        binding.controlLevelButton.isEnabled = !recording

        if (controlRange != null) {
            // ---- GAIN mode: slider uses raw ALSA values ----
            val cr = controlRange
            // Set value BEFORE changing bounds so the Slider doesn't throw
            // when the current dB value falls outside the new raw ALSA range.
            // Skip while the user is actively dragging: the slider thumb
            // belongs to them, and the value label is updated by the change
            // listener (see bindUi). A drag-scoped bindState call would
            // otherwise yank the thumb back to the store's pre-drag value.
            if (!isDraggingMic1) {
                val currentRaw1 = dbToRaw(state.gainMic1Db, alsaController)
                binding.mic1Slider.value = currentRaw1.toFloat()
                // Show raw value label (stepSize=1 so values are integers).
                binding.mic1Value.text = currentRaw1.toString()
            }
            if (!isDraggingMic2) {
                val currentRaw2 = dbToRaw(state.gainMic2Db, alsaController)
                binding.mic2Slider.value = currentRaw2.toFloat()
                binding.mic2Value.text = currentRaw2.toString()
            }
            // Now update bounds (value is already within new range). Bounds
            // are safe to update even mid-drag because they only change when
            // the effective control mode changes, and the mode toggle is
            // user-initiated and would interrupt the drag anyway.
            binding.mic1Slider.valueFrom = cr.min.toFloat()
            binding.mic1Slider.valueTo = cr.max.toFloat()
            binding.mic2Slider.valueFrom = cr.min.toFloat()
            binding.mic2Slider.valueTo = cr.max.toFloat()
        } else {
            // ---- LEVEL mode: slider uses dB values (existing behavior) ----
            // Set dB value BEFORE updating bounds (dB may fall outside the raw ALSA range currently set).
            if (!isDraggingMic1) {
                val db1 = state.gainMic1Db.coerceIn(-state.maxGainScale, state.maxGainScale)
                binding.mic1Slider.value = db1.toFloat()
                // Show dB value label.
                binding.mic1Value.text = formatGain(db1)
            }
            if (!isDraggingMic2) {
                val db2 = state.gainMic2Db.coerceIn(-state.maxGainScale, state.maxGainScale)
                binding.mic2Slider.value = db2.toFloat()
                binding.mic2Value.text = formatGain(db2)
            }
            // Now update bounds.
            val newFrom = (-scale).toFloat()
            val newTo = scale
            binding.mic1Slider.valueFrom = newFrom
            binding.mic1Slider.valueTo = newTo
            binding.mic2Slider.valueFrom = newFrom
            binding.mic2Slider.valueTo = newTo
        }

        // Value text color: reduce (blue) vs boost (orange).
        val mic1ColorAttr = if (state.gainMic1Db < 0) R.attr.gainReduceColor else R.attr.gainActiveColor
        val mic2ColorAttr = if (state.gainMic2Db < 0) R.attr.gainReduceColor else R.attr.gainActiveColor
        binding.mic1Value.setTextColor(resolveAttrColor(mic1ColorAttr))
        binding.mic2Value.setTextColor(resolveAttrColor(mic2ColorAttr))

        // Link switch — avoid re-entrancy when bindState sets it programmatically.
        if (binding.linkSwitch.isChecked != state.linkGains) {
            binding.linkSwitch.isChecked = state.linkGains
        }

        // Format selection — only re-armed when not actively recording.
        if (!state.isRecording) {
            val fmtId = when (state.recordFormat) {
                RecordFormat.WAV16 -> R.id.formatWav16Button
                RecordFormat.WAV24 -> R.id.formatWav24Button
                RecordFormat.M4A -> R.id.formatM4aButton
            }
            if (binding.formatToggle.checkedButtonId != fmtId) binding.formatToggle.check(fmtId)
            val showBitrate = state.recordFormat == RecordFormat.M4A
            binding.bitrateLabel.visibility = if (showBitrate) View.VISIBLE else View.GONE
            binding.bitrateSpinner.visibility = if (showBitrate) View.VISIBLE else View.GONE
            binding.bitrateHint.visibility = if (showBitrate) View.VISIBLE else View.GONE
        }

        // Gain sliders + link remain fully editable at all times.
        binding.mic1Slider.isEnabled = true
        binding.mic2Slider.isEnabled = true
        binding.linkSwitch.isEnabled = true
        binding.formatToggle.isEnabled = !state.isRecording
        binding.bitrateSpinner.isEnabled = !state.isRecording
        // Sample rate is locked during a take: AudioRecord is bound to its rate
        // at construction, so a mid-recording change would require tearing down
        // the live capture. The new value takes effect on the next session.
        binding.sampleRateSpinner.isEnabled = !state.isRecording

        // Buttons.
        binding.recordButton.visibility = if (state.isRecording) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (state.isRecording) View.VISIBLE else View.GONE

        // Recording panel is ALWAYS visible: meters monitor mic levels whether or not
        // a file is being written. Elapsed shows 00:00 in white when idle, red while
        // recording. While monitoring-only (no file) we still show 00:00 white.
        binding.recordingPanel.visibility = View.VISIBLE
        // `recording` was already declared above for the control-type toggle guard.

        // On a recording's rising edge, reset the visual peak-hold markers — the live
        // capture is long-lived, so without this the on-screen "highest level" line
        // would carry over from the previous take.
        if (recording && !lastIsRecording) {
            meter1Digital?.resetPeakHold()
            meter2Digital?.resetPeakHold()
        }
        lastIsRecording = recording
        binding.elapsedText.text = if (recording) formatElapsed(state.elapsedMs) else "00:00"
        binding.elapsedText.setTextColor(
            resolveAttrColor(if (recording) R.attr.dangerColor else R.attr.elapsedIdleColor))

        // Meters + peak labels.
        updateMeters(state)
        binding.peakMic1Text.text = formatPeak(state.peakDb1)
        binding.peakMic2Text.text = formatPeak(state.peakDb2)
        binding.peakMic1Text.setTextColor(
            resolveAttrColor(if (state.peakDb1 >= 0f) R.attr.meterPeakColor else R.attr.textPrimary))
        binding.peakMic2Text.setTextColor(
            resolveAttrColor(if (state.peakDb2 >= 0f) R.attr.meterPeakColor else R.attr.textPrimary))

        // Headphone monitor switch + status text.
        bindMonitorUi(state)
    }

    /**
     * Handle the user tapping the "Gain (root only)" toggle button.
     *
     * The button is always pressable (the binding-level `isEnabled` gate was
     * removed so the user isn't blocked by a race between the UI showing and
     * the root probe completing). The actual mode switch is gated on the
     * **full** controller readiness — not just the su probe — so the UI
     * never silently flips back to Level after the user taps Gain. The
     * controller's readiness is the right predicate here because it's the
     * same one [bindStateInternal] uses to drive the toggle's effective mode;
     * if we switched to GAIN here while [bindStateInternal] still reports
     * `effectiveMode = LEVEL`, the next bindState call would revert the
     * toggle with no feedback (the bug this method used to have when it
     * tested only [AlsaGainController.isRootAvailable]).
     *
     * Three outcomes:
     *  - `isAnalogGainReady`      → switch to GAIN mode.
     *  - root granted, helper /
     *    controls missing         → alert with a "Retry" action button that
     *                                re-runs the root+helper probe
     *                                ([AlsaGainController.requestRootAccess]).
     *                                Revert the toggle to Level.
     *  - no root at all           → alert with a "Request root permission"
     *                                action button (same UX as the
     *                                on-launch dialog). Revert the toggle
     *                                to Level.
     */
    private fun handleGainTogglePressed() {
        val controller = alsaController
        when {
            controller == null -> {
                // Should never happen — onCreate() always creates a controller.
                // Be defensive: tell the user something is off and revert.
                AlertDialogHelper.show(
                    context = this,
                    title = getString(R.string.root_permission_dialog_title),
                    message = getString(R.string.alsa_helper_missing),
                    onDismiss = { revertToggleToLevel() }
                )
            }
            controller.isAnalogGainReady -> {
                // Full chain ready (root + tinymix + ADC controls). Switch to
                // GAIN mode. The dB value means fundamentally different things
                // in LEVEL mode (digital DSP) vs GAIN mode (raw ALSA value
                // converted to dB), so reset the gains to 0 on mode switch.
                if (stateStore.snapshot().gainControlMode != GainControlMode.ANALOG_GAIN) {
                    stateStore.setGainControlMode(GainControlMode.ANALOG_GAIN)
                    stateStore.setGainMic1(0)
                    stateStore.setGainMic2(0)
                    bindState(stateStore.snapshot())
                }
            }
            controller.isRootAvailable -> {
                // Root is granted but the ALSA helper / ADC controls aren't
                // ready (e.g. tinymix binary broken or missing on disk). Show
                // an alert with a "Retry" action that re-runs the probe: a
                // fresh `tinymix contents` smoke test can succeed if the
                // helper was just deployed since the last launch.
                AlertDialogHelper.showWithAction(
                    context = this,
                    title = getString(R.string.root_permission_dialog_title),
                    message = getString(R.string.alsa_helper_missing),
                    actionLabel = getString(R.string.request_root_action),
                    onAction = { requestRootAndRefreshStatus(controller) },
                    onDismiss = { revertToggleToLevel() }
                )
            }
            else -> {
                // No root at all. Same UX as the on-launch dialog.
                AlertDialogHelper.showWithAction(
                    context = this,
                    title = getString(R.string.root_permission_dialog_title),
                    message = getString(R.string.root_permission_needed),
                    actionLabel = getString(R.string.request_root_action),
                    onAction = { requestRootAndRefreshStatus(controller) },
                    onDismiss = { revertToggleToLevel() }
                )
            }
        }
    }

    /**
     * Force the control-type toggle back to Level after the user pressed Gain
     * in a state where Gain mode isn't actually achievable (no full root, no
     * helper, etc.).
     *
     * Going through the two buttons directly (rather than [MaterialButtonToggleGroup.check])
     * avoids a subtle bug where calling `check()` while an [AlertDialog] is
     * animating in over the toggle can leave both buttons in an unchecked
     * state — `MaterialButtonToggleGroup` with `selectionRequired=true`
     * doesn't recover cleanly. Setting the buttons' `isChecked` flags in the
     * right order keeps the group coherent.
     */
    private fun revertToggleToLevel() {
        // Setting the previously-checked button to false first is the trick:
        // it un-checks Gain cleanly even when the toggle is in a partial
        // state, and then checking Level completes the swap.
        if (binding.controlGainButton.isChecked) binding.controlGainButton.isChecked = false
        if (!binding.controlLevelButton.isChecked) binding.controlLevelButton.isChecked = true
    }

    private fun bindMonitorUi(state: MicStateStore.State) {
        monitorUiBound = false // suppress listener while we mutate programmatically
        // Monitor is available whenever headphones are connected — recording or not.
        binding.monitorSwitch.isEnabled = state.headphonesConnected
        if (binding.monitorSwitch.isChecked != state.isMonitoring) {
            binding.monitorSwitch.isChecked = state.isMonitoring
        }
        monitorUiBound = true

        binding.monitorStatus.text = if (state.isMonitoring)
            getString(R.string.monitor_on) else getString(R.string.monitor_off)
        binding.monitorStatus.setTextColor(
            resolveAttrColor(if (state.isMonitoring) R.attr.monitorActiveColor else R.attr.monitorInactiveColor))

        // Hint reflects headphone availability.
        binding.monitorHint.text = if (state.headphonesConnected)
            getString(R.string.monitor_hint) else getString(R.string.monitor_no_headphones)
    }

    private fun updateMeters(state: MicStateStore.State) {
        if (meter1Digital == null && meterAnalog1 == null) rebuildMeters()
        meter1Digital?.setDb(state.meterDb1)
        meter2Digital?.setDb(state.meterDb2)
        meterAnalog1?.setDb(state.meterDb1)
        meterAnalog2?.setDb(state.meterDb2)
    }

    /**
     * DAW-style peak reset for ONE channel: clears that channel's running-max in the
     * live capture, resets the store's peak field for that channel, and clears that
     * meter's peak-hold marker — so the user can re-measure just that mic from this
     * instant onward. [channel] is 1 or 2.
     */
    private fun resetPeakDbUi(channel: Int) {
        CaptureSession.get()?.resetPeak(channel)
        stateStore.resetPeak(channel)
        if (channel == 1) meter1Digital?.resetPeakHold() else meter2Digital?.resetPeakHold()
        // Refresh the on-screen number immediately so the user sees it go to −∞.
        val s = stateStore.snapshot()
        if (channel == 1) binding.peakMic1Text.text = formatPeak(s.peakDb1)
        else binding.peakMic2Text.text = formatPeak(s.peakDb2)
    }

    /** (Re)create the meter views in both channel containers based on the current style. */
    private fun rebuildMeters() {
        binding.meterMic1Container.removeAllViews()
        binding.meterMic2Container.removeAllViews()
        meter1Digital = null; meter2Digital = null
        meterAnalog1 = null; meterAnalog2 = null
        val style = preferences.meterStyle
        if (style == MeterStyle.DIGITAL) {
            meter1Digital = DigitalMeterView(this).also { binding.meterMic1Container.addView(it) }
            meter2Digital = DigitalMeterView(this).also { binding.meterMic2Container.addView(it) }
        } else {
            meterAnalog1 = AnalogMeterView(this).also { binding.meterMic1Container.addView(it) }
            meterAnalog2 = AnalogMeterView(this).also { binding.meterMic2Container.addView(it) }
        }
        // Reset peak hold visually on a new recording if needed.
    }

    private fun formatGain(db: Int): String =
        if (db > 0) "+$db dB" else if (db < 0) "$db dB" else "0 dB"

    /**
     * Update the on-screen value label below the mic slider to mirror the
     * current slider thumb position during a drag, before the store is
     * committed. Matches the format [bindStateInternal] would have set had
     * the value come from the store — same "raw integer" in GAIN mode,
     * same "±N dB" string in LEVEL mode — so the label never diverges from
     * the thumb the user is holding.
     *
     * Called from [bindUi]'s `addOnChangeListener` only while
     * [isDraggingMic1] / [isDraggingMic2] is true (the listener short-
     * circuits the store update in that window). After touch end the
     * store commits and bindState re-renders the label from the store,
     * which now matches the slider value.
     */
    private fun updateMicValueLabel(mic: Int, sliderValue: Int, state: MicStateStore.State) {
        val isGainModeEffective = state.gainControlMode == GainControlMode.ANALOG_GAIN &&
            (alsaController?.isAnalogGainReady == true)
        val text = if (isGainModeEffective) sliderValue.toString() else formatGain(sliderValue)
        val view = if (mic == 1) binding.mic1Value else binding.mic2Value
        view.text = text
    }

    /** Convert a raw ALSA value to the dB equivalent using controller calibration.
     *
     *  Uses [roundToInt] (not truncate) so the dB the slider writes into the
     *  store round-trips losslessly through [dbToRaw] — see the
     *  [AlsaGainController.setAnalogGainDb] comment for the full rationale.
     *  In short: with a 1.5 dB codec step and int dB storage, truncating
     *  here makes odd-integer raws (e.g. 13) store as 1 dB instead of 2,
     *  which then reverse-engineers to raw 12 — the slider "drags back"
     *  by one and the notification's ±1 stalls.
     */
    private fun rawToDb(raw: Int, controller: AlsaGainController?): Int {
        val range = controller?.mic1ControlRange() ?: return raw
        return ((raw - range.defaultVal) * range.stepDb).roundToInt()
    }

    /** Convert a dB value to the nearest raw ALSA value using controller calibration. */
    private fun dbToRaw(db: Int, controller: AlsaGainController?): Int {
        val range = controller?.mic1ControlRange() ?: return db
        return ((db / range.stepDb) + range.defaultVal).roundToInt()
            .coerceIn(range.min, range.max)
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun formatPeak(db: Float): String {
        if (db.isInfinite() || db.isNaN()) return "−∞ dB"
        if (db >= 0f) return getString(R.string.clip_label)
        return getString(R.string.peak_format, db)
    }

    private fun resolveAttrColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun maybeRequestPermissions() {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            micPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            binding.recordButton.isEnabled = false
        } else {
            binding.recordButton.isEnabled = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

/** True when the theme mode is the light variant. */
private fun ThemeMode.modeIsLight(): Boolean = this == ThemeMode.LIGHT
