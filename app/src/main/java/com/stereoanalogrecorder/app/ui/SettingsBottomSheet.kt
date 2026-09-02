package com.stereoanalogrecorder.app.ui

import android.view.LayoutInflater
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.stereoanalogrecorder.app.R
import com.stereoanalogrecorder.app.databinding.BottomSheetSettingsBinding
import com.stereoanalogrecorder.app.settings.AppLanguage
import com.stereoanalogrecorder.app.settings.MeterStyle
import com.stereoanalogrecorder.app.settings.PreferencesRepository
import com.stereoanalogrecorder.app.settings.ThemeMode
import com.stereoanalogrecorder.app.state.MicStateStore

/**
 * Settings sheet. Theme/language changes only update SharedPreferences, then recreate
 * the Activity so app XML resources reload. Gain scale and meter style update via
 * [MicStateStore] so all observers refresh.
 */
class SettingsBottomSheet(
    private val activity: MainActivity,
    private val preferences: PreferencesRepository,
    private val stateStore: MicStateStore,
    private val onThemeOrLanguageChanged: () -> Unit,
    private val onScaleChanged: () -> Unit,
    private val onMeterStyleChanged: () -> Unit,
    private val onNotificationDisplayChanged: () -> Unit
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = BottomSheetSettingsBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        when (preferences.themeMode) {
            ThemeMode.LIGHT -> binding.themeToggle.check(R.id.themeLightButton)
            ThemeMode.DARK -> binding.themeToggle.check(R.id.themeDarkButton)
        }
        when (preferences.language) {
            AppLanguage.ENGLISH -> binding.languageToggle.check(R.id.langEnglishButton)
            AppLanguage.ITALIAN -> binding.languageToggle.check(R.id.langItalianButton)
        }
        when (preferences.meterStyle) {
            MeterStyle.DIGITAL -> binding.meterStyleToggle.check(R.id.meterDigitalButton)
            MeterStyle.ANALOG -> binding.meterStyleToggle.check(R.id.meterAnalogButton)
        }

        binding.scaleSlider.value = stateStore.snapshot().maxGainScale.toFloat()
        binding.scaleValue.text =
            activity.getString(R.string.scale_value_format, stateStore.snapshot().maxGainScale)
        binding.scaleSlider.addOnChangeListener { _: Slider, value: Float, fromUser: Boolean ->
            val scale = value.toInt()
            binding.scaleValue.text = activity.getString(R.string.scale_value_format, scale)
            if (fromUser) {
                stateStore.setMaxGainScale(scale)
                onScaleChanged()
            }
        }

        binding.classicNotificationSwitch.isChecked = preferences.showClassicNotification
        binding.classicNotificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (preferences.showClassicNotification == checked) return@setOnCheckedChangeListener
            preferences.showClassicNotification = checked
            if (checked) onNotificationDisplayChanged()
        }

        binding.meterStyleToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val style = when (checkedId) {
                R.id.meterAnalogButton -> MeterStyle.ANALOG
                else -> MeterStyle.DIGITAL
            }
            if (style != preferences.meterStyle) {
                stateStore.setMeterStyle(style)
                onMeterStyleChanged()
            }
        }

        binding.themeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLightButton -> ThemeMode.LIGHT
                else -> ThemeMode.DARK
            }
            if (mode != preferences.themeMode) {
                preferences.themeMode = mode
                dialog.dismiss()
                onThemeOrLanguageChanged()
            }
        }

        binding.languageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val language = when (checkedId) {
                R.id.langItalianButton -> AppLanguage.ITALIAN
                else -> AppLanguage.ENGLISH
            }
            if (language != preferences.language) {
                preferences.language = language
                dialog.dismiss()
                onThemeOrLanguageChanged()
            }
        }

        dialog.show()
    }
}
