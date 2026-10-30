package com.driftdroid.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import com.driftdroid.android.touch.TouchInputBridge
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Wii Wheel mode (shake to trick): the phone is presented to the game as a Wii Remote in a Wii
 * Wheel, so the game's own wheel code steers and does tricks and wheelies from real motion.
 *
 * The runtime switches the game over to the remote at start-up when WiiCompiled/wii_wheel.cfg
 * says so (wii_remote_input.cpp); buttons come from the usual touch/controller layout. This class
 * only feeds the accelerometer. It uses TYPE_ACCELEROMETER, not the gravity sensor, because a
 * shake has to reach the game's trick detector.
 *
 * Axis mapping, measured on the PC by feeding the game known readings: the screen-up component
 * goes to remote (0.707, -0.707, 0), the towards-the-player component to (0.707, 0.707, 0), and
 * screen-right to remote Z (tilting right makes it negative, which the game reads as steering right).
 *
 * Shakes: a jolt along remote X/Y is the game's up/down flick (a trick in the air, a wheelie on a
 * bike on the ground) and needs about 1.3; a jolt along remote Z is the left/right flick. So X/Y
 * carry smoothed gravity, and the size of any shake is added along the resting wheel's up axis,
 * boosted so that an ordinary shake clears the threshold. It always goes up, so a shake whichever
 * way gives the up flick, which is the one that wheelies.
 *
 * In shake-only mode the phone's tilt is not used at all: the wheel always rests level plus the
 * shakes, and the runtime tilts it from the stick (wii_remote_input.cpp).
 */
internal class WiiWheelMode(private val context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var registered = false
    private val gravity = FloatArray(3)
    private var steer = 0f
    private var lastTimestampNs = 0L
    private var lastPulseMs = 0L

    // "Straight ahead": the phone's roll (in the screen plane) that counts as the wheel held
    // level. Taken from the first steady readings and again on recenter(), so a phone held a
    // little rotated (easy when upright in vertical mode) doesn't steer by itself.
    private var cosCenter = 1f
    private var sinCenter = 0f
    @Volatile private var recenterPending = true

    val sensorAvailable: Boolean get() = sensor != null

    /** What the next game start will use: [MODE_OFF], [MODE_TILT] or [MODE_SHAKE_ONLY]. */
    var mode: Int
        get() =
            configFile(context).let { file ->
                if (!file.isFile) MODE_OFF else file.readText().trim().toIntOrNull()?.takeIf { it in MODE_OFF..MODE_SHAKE_ONLY } ?: MODE_OFF
            }
        set(value) {
            val file = configFile(context)
            file.parentFile?.mkdirs()
            file.writeText("$value\n")
        }

    /** The mode this game session runs with (the runtime reads it once per process). */
    val sessionMode: Int = mode

    fun start() {
        if (registered || sensor == null || sessionMode == MODE_OFF) return
        lastTimestampNs = 0L
        recenterPending = true
        registered = sensorManager?.registerListener(this, sensor, SAMPLING_PERIOD_US) ?: false
    }

    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
    }

    /** Makes the way the phone is held right now the wheel's straight-ahead. */
    fun recenter() {
        recenterPending = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values.getOrElse(0) { 0f }
        val y = event.values.getOrElse(1) { 0f }
        val z = event.values.getOrElse(2) { 0f }
        // Device axes to screen axes for the current rotation (landscape either way, or upright in
        // vertical mode).
        @Suppress("DEPRECATION")
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
        val (rawRight, rawUp) =
            when (rotation) {
                Surface.ROTATION_90 -> -y to x
                Surface.ROTATION_180 -> -x to -y
                Surface.ROTATION_270 -> y to -x
                else -> x to y
            }
        // Turn the reading by the straight-ahead roll, so holding the phone at that roll reads level.
        val right = rawRight * cosCenter - rawUp * sinCenter
        val up = rawRight * sinCenter + rawUp * cosCenter
        val reading = floatArrayOf(up / SensorManager.GRAVITY_EARTH, z / SensorManager.GRAVITY_EARTH, right / SensorManager.GRAVITY_EARTH)

        val dtSeconds = if (lastTimestampNs == 0L) 0f else (event.timestamp - lastTimestampNs) / 1e9f
        lastTimestampNs = event.timestamp
        fun follow(seconds: Float) = if (dtSeconds <= 0f || dtSeconds > 0.5f) 1f else dtSeconds / (seconds + dtSeconds)
        // While the phone's own motor runs, its buzz reaches the accelerometer as fake shakes and
        // fake tilt ("it's just following my phone's vibration"): no shakes then, and the steering
        // is smoothed much harder until it stops.
        val buzzing = DeviceRumble.isBuzzing(event.timestamp)
        val alpha = follow(GRAVITY_SMOOTHING_SECONDS)
        var shake = 0f
        for (i in 0 until 3) {
            gravity[i] += alpha * (reading[i] - gravity[i])
            // With tilt steering a sideways shake is already in the right axis below.
            if (!buzzing && (sessionMode == MODE_SHAKE_ONLY || i != AXIS_RIGHT)) {
                shake = maxOf(shake, abs(reading[i] - gravity[i]))
            }
        }
        steer += follow(if (buzzing) BUZZ_STEER_SMOOTHING_SECONDS else STEER_SMOOTHING_SECONDS) * (reading[AXIS_RIGHT] - steer)

        if (recenterPending) {
            // Needs the phone upright enough for a roll to mean anything (not lying flat).
            val roll = atan2(rawRight, rawUp)
            if (hypot(rawRight, rawUp) > 0.5f * SensorManager.GRAVITY_EARTH && abs(roll) < MAX_CENTER_ROLL) {
                cosCenter = cos(roll)
                sinCenter = sin(roll)
                gravity.fill(0f)
                lastTimestampNs = 0L
                steer = 0f
            }
            recenterPending = false
            return
        }

        val boost = if (shake >= SHAKE_DEADZONE_G) shake * SHAKE_GAIN else 0f
        // A raw shake lasts a few samples, but the game reads once a frame. A deliberate shake also sends
        // the same 100 ms trick pulse a D-pad press does (the part known to reach the game).
        val nowMs = event.timestamp / 1_000_000L
        if (shake >= TRICK_SHAKE_G && nowMs - lastPulseMs >= PULSE_COOLDOWN_MS) {
            lastPulseMs = nowMs
            TouchInputBridge.nativeWiiRemoteTrickPulse()
        }
        if (sessionMode == MODE_SHAKE_ONLY) {
            TouchInputBridge.nativePublishWiiRemoteMotion(DIAGONAL * (1f + boost), -DIAGONAL * (1f + boost), 0f)
        } else {
            val upAxis = gravity[AXIS_UP] + boost
            val towardAxis = gravity[AXIS_TOWARD]
            TouchInputBridge.nativePublishWiiRemoteMotion(DIAGONAL * (upAxis + towardAxis), DIAGONAL * (towardAxis - upAxis), steer)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    companion object {
        private const val DIAGONAL = 0.70710677f
        private const val SAMPLING_PERIOD_US = 10_000
        private const val GRAVITY_SMOOTHING_SECONDS = 0.25f
        private const val SHAKE_DEADZONE_G = 0.25f
        private const val SHAKE_GAIN = 2.5f
        // How hard (g beyond the smoothed gravity) a shake must be to send a trick pulse: well above
        // walking and hand jitter, well below a real shake.
        private const val TRICK_SHAKE_G = 0.4f
        private const val PULSE_COOLDOWN_MS = 350L
        // Light enough to keep a quick sideways flick, heavy enough to drop sensor jitter.
        private const val STEER_SMOOTHING_SECONDS = 0.04f
        private const val BUZZ_STEER_SMOOTHING_SECONDS = 0.2f
        // About 50 degrees: further than that is not a wheel held level.
        private const val MAX_CENTER_ROLL = 0.87f
        private const val AXIS_UP = 0
        private const val AXIS_TOWARD = 1
        private const val AXIS_RIGHT = 2

        const val MODE_OFF = 0
        const val MODE_TILT = 1
        const val MODE_SHAKE_ONLY = 2

        fun configFile(context: Context): File = File(context.filesDir, "WiiCompiled/wii_wheel.cfg")
    }
}
