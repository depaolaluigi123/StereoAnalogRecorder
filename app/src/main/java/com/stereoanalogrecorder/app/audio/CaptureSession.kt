package com.stereoanalogrecorder.app.audio

import java.util.concurrent.atomic.AtomicReference

/**
 * In-process registry of the currently-running [MicCapture] (if any).
 *
 * `RecordingService` advertises its capture here so companion services (notably the
 * headphone-monitor service) can attach a [MicCapture.MonitorTap] to the same
 * stream without opening a second `AudioRecord`. When no capture is registered,
 * consumers should open their own.
 */
object CaptureSession {
    private val ref = AtomicReference<MicCapture?>(null)

    fun set(capture: MicCapture?) { ref.set(capture) }
    fun get(): MicCapture? = ref.get()
    fun isActive(): Boolean = ref.get()?.isRunning() == true
}
