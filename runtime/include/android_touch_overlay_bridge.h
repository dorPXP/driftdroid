#pragma once

// Bridges the ImGui settings overlay (settings_overlay.cpp) to the Kotlin-side touch control
// overlay (TouchControlsOverlay.kt), which owns the actual show/hide + persistence - see that
// file's own comment for why it deliberately doesn't go through RuntimeConfigFile/Config.toml.
// Defined in android_jni_bridge.cpp; both call sites live in runtime/src so a shared header is
// simplest here (unlike the gamepad-connect hook in aurora-main/lib/window.cpp, which declares its
// own extern inline rather than pulling in a runtime/ header from aurora/).
#if defined(__ANDROID__)

// Mirrors whatever Kotlin's TouchControlsOverlay last reported as the user's own on/off
// preference (seeded once at startup via nativeSetTouchControlsVisibleCache, updated whenever
// AndroidSetTouchOverlayVisible below is called) - purely for the settings checkbox to display
// the right initial/current state, not the source of truth for actual visibility.
extern bool g_androidTouchControlsVisibleCache;

// Tells Kotlin to show/hide the touch overlay and persist the user's choice.
extern "C" void AndroidSetTouchOverlayVisible(bool visible);

// Tells Kotlin to enter the touch layout editor (drag/resize/hide controls) - the settings
// sidebar's Controller section replaces the old "hold the gear button for 5 seconds" gesture with
// an explicit button that calls this.
extern "C" void AndroidStartTouchLayoutEdit();

// Tells Kotlin the settings sidebar just opened/closed, so touch controls (R/X/Y/A/B etc., docked
// to the same screen edge the sidebar occupies) can hide for as long as it's up - confirmed
// directly on-device that they otherwise sit underneath the sidebar, half-covering its content and
// eating touches meant for it. Gameplay input is already blocked while the sidebar is open
// (PADBlockInput), so the buttons have nothing to do anyway.
extern "C" void AndroidNotifySettingsVisibilityChanged(bool visible);

// Tells Kotlin to show the Motion Steering action dialog (Turn On/Off, Recenter Now, Invert
// Direction, Cycle Sensitivity) - MainActivity.showMotionSteeringOptionsDialog, matching KartPad's
// own Motion Steering menu exactly.
extern "C" void AndroidShowMotionSteeringDialog();

#endif  // __ANDROID__
