#pragma once

#include <aurora/aurora.h>
#include <aurora/event.h>

namespace settings_overlay {
// Apply persistent controller settings once Aurora has discovered host devices.
void InitializeRuntimeSettings() noexcept;
// Draw the F10 settings bar before each Aurora present.
void HandleEvents(const AuroraEvent* events) noexcept;
void Draw() noexcept;
bool StartupScreenVisible() noexcept;
// Android only: there is no F10 key to press, so the touch UI calls this directly instead.
void ToggleTopBar() noexcept;
void NotifyStrapInputAccepted() noexcept;
void AdvancePresentedFrame() noexcept;
// The renderer the player asked for could not start and another one is in use. Shown on screen
// for a few seconds, since the slower fallback otherwise looks like the game just running badly.
void NotifyBackendFallback(const char* requested, const char* actual) noexcept;
} // namespace settings_overlay
