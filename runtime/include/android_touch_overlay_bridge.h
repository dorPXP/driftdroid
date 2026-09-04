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

#endif  // __ANDROID__
