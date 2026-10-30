package com.driftdroid.android.touch

/**
 * JNI bridge to the virtual SDL gamepad backing the on-screen touch controls
 * (runtime/src/android_touch_controls.cpp). The button/axis values below are SDL_GamepadButton /
 * SDL_GamepadAxis enum values from SDL3/SDL_gamepad.h - both sides must agree on these numbers,
 * hence named constants here instead of scattering raw ints through the touch-control views.
 * Because this rides SDL's real gamepad pipeline, touch buttons show up in - and can be remapped
 * from - the same in-game controller settings a physical pad uses.
 */
object TouchInputBridge {
    // A/B/X/Y send the NATIVE SDL button that the Classic Controller Pro preset
    // (kClassicProPreset in settings_overlay.cpp, auto-applied to this device by
    // AutoConfigureTouchControllerIfPresent) maps to each in-game PAD_BUTTON_* - matching how a
    // real Nintendo Switch Pro Controller's labeled buttons are physically wired (its "A" is
    // SDL EAST, not SOUTH, etc), not SDL's own generic South/East/West/North naming.
    const val BUTTON_A = 1 // SDL_GAMEPAD_BUTTON_EAST -> PAD_BUTTON_A
    const val BUTTON_B = 0 // SDL_GAMEPAD_BUTTON_SOUTH -> PAD_BUTTON_B
    const val BUTTON_X = 3 // SDL_GAMEPAD_BUTTON_NORTH -> PAD_BUTTON_X
    const val BUTTON_Y = 2 // SDL_GAMEPAD_BUTTON_WEST -> PAD_BUTTON_Y
    const val BUTTON_START = 6 // SDL_GAMEPAD_BUTTON_START ("+")
    const val BUTTON_L = 9 // SDL_GAMEPAD_BUTTON_LEFT_SHOULDER -> PAD_TRIGGER_L (item)
    const val BUTTON_R = 10 // SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER -> PAD_TRIGGER_R (drift)
    const val BUTTON_DPAD_UP = 11
    const val BUTTON_DPAD_DOWN = 12
    const val BUTTON_DPAD_LEFT = 13
    const val BUTTON_DPAD_RIGHT = 14

    const val AXIS_LEFT_X = 0
    const val AXIS_LEFT_Y = 1

    @JvmStatic
    external fun nativeSetTouchButton(button: Int, pressed: Boolean)

    @JvmStatic
    external fun nativeSetTouchAxis(axis: Int, value: Float)

    /** A race is under way and not paused (runtime/src/hle/input/pad.cpp): tilt steering only
     * steers then. */
    @JvmStatic
    external fun nativeIsDriving(): Boolean

    /** Tilt steering's value (-1..1) and whether it is steering now (MotionSteering.kt). The
     * native side also applies it to a real controller that is player 1, which the touch axis
     * above does not reach. */
    @JvmStatic
    external fun nativeSetMotionSteer(steering: Boolean, value: Float)

    /** Wii Wheel mode: one accelerometer reading in g, already in the Wii Remote's axes
     * (runtime/src/hle/input/wii_remote_input.cpp). */
    @JvmStatic
    external fun nativePublishWiiRemoteMotion(x: Float, y: Float, z: Float)

    /** Wii Wheel mode: a shake was detected; the runtime holds the same trick pulse as a D-pad press. */
    @JvmStatic
    external fun nativeWiiRemoteTrickPulse()
}
