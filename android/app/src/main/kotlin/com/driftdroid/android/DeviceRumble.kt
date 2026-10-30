package com.driftdroid.android

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Vibrates the phone for player one when the game rumbles a connected controller that has no
 * motor of its own, such as a handheld's built-in controls. Adapted from KartPad's
 * KartPadDeviceRumble (Chris Sotraidis, KartPad 0.8.4). Native (aurora pad.cpp's PADControlMotor,
 * via android_jni_bridge.cpp) calls [setStrength] on each start/stop change; the vibrator service
 * is called on a separate thread, in order. MainActivity pauses it in the background.
 */
object DeviceRumble {
    // The game's motor command is on/off and it always sends a stop. This bound matches SDL's
    // controller pulses and limits a missed stop.
    private const val MAX_PULSE_MS = 5_000L
    private const val PULSE_PERIOD_MS = 20L
    private const val MIN_PULSE_ON_MS = 3L

    private val worker by lazy { Handler(HandlerThread("DeviceRumble").apply { start() }.looper) }
    private var vibrator: Vibrator? = null
    private var paused = true
    private var active = false

    // Until when (SystemClock.elapsedRealtimeNanos, the motion sensors' clock) the motor may still
    // be shaking the phone. Wii Wheel mode reads the accelerometer, which feels the buzz as the
    // player shaking and tilting the phone.
    @Volatile
    private var buzzingUntilNs = 0L

    /** Whether the phone's own vibration could be in an accelerometer reading taken at [nowNs]. */
    fun isBuzzing(nowNs: Long): Boolean = nowNs < buzzingUntilNs

    /** Whether this phone has a vibrator at all. Valid after [attach]. */
    val canVibrate: Boolean
        @Synchronized get() = vibrator != null

    @Synchronized
    fun attach(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }?.takeIf { it.hasVibrator() }
    }

    @Synchronized
    fun setPaused(value: Boolean) {
        paused = value
        if (value) worker.post { update(0) }
    }

    /** 0 stops; otherwise the player's vibration strength in percent (1 to 200). */
    fun setStrength(strengthPercent: Int) {
        worker.post { update(strengthPercent) }
    }

    @Synchronized
    private fun update(strengthPercent: Int) {
        if (strengthPercent <= 0 || paused) {
            stop()
            return
        }
        val target = vibrator ?: return
        val effect = if (target.hasAmplitudeControl()) {
            // Adjustable motors use one consistent scale, midpoint amplitude at 100% and full at
            // 200%. The device default is not always 128.
            val amplitude = (128 * strengthPercent.coerceAtMost(200) / 100).coerceIn(1, 255)
            VibrationEffect.createOneShot(MAX_PULSE_MS, amplitude)
        } else if (strengthPercent >= 100) {
            VibrationEffect.createOneShot(MAX_PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            // On/off-only motors (the Huawei nova 9's, for one) feel weaker when pulsed: the motor
            // is on for strength% of each short period and never reaches full speed. The period is
            // shorter than the game's own rumble bursts (about 17 ms each on a bumpy road). Above
            // 100% there is nothing stronger than always on.
            val onMs = (PULSE_PERIOD_MS * strengthPercent / 100).coerceAtLeast(MIN_PULSE_ON_MS)
            val offMs = (PULSE_PERIOD_MS - onMs).coerceAtLeast(0L)
            val pulses = (MAX_PULSE_MS / PULSE_PERIOD_MS).toInt()
            val timings = LongArray(pulses * 2) { if (it % 2 == 0) offMs else onMs }
            timings[0] = 0L // start on the first burst straight away
            VibrationEffect.createWaveform(timings, -1)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            target.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            target.vibrate(effect)
        }
        active = true
        buzzingUntilNs = Long.MAX_VALUE
    }

    private fun stop() {
        if (!active) return
        active = false
        vibrator?.cancel()
        buzzingUntilNs = SystemClock.elapsedRealtimeNanos() + SETTLE_NS
    }

    // The motor keeps spinning down for a moment after the cancel.
    private const val SETTLE_NS = 120_000_000L
}
