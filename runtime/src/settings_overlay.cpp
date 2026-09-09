#include "settings_overlay.h"
#if defined(__ANDROID__)
#include "android_touch_overlay_bridge.h"
#endif
#include "audio_backend.h"
#include "controller_mapping_wizard.h"
#include "game_graphics_options.h"
#include "music_attenuation.h"
#include "runtime_config.h"
#include "runtime_log.h"

#include <imgui.h>
#include <SDL3/SDL_events.h>
#include <SDL3/SDL_gamepad.h>
#include <SDL3/SDL_keyboard.h>
#include <SDL3/SDL_mouse.h>
#include <SDL3/SDL_scancode.h>

#include <array>
#include <algorithm>
#include <atomic>
#include <bitset>
#include <cctype>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <limits>
#include <iostream>
#include <string>
#include <string_view>
#include <utility>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <Windows.h>
#include <shellapi.h>
#endif

#include <dolphin/pad.h>
#include <dolphin/vi.h>
#include <aurora/aurora.h>
#include <aurora/gfx.h>

extern "C" int g_gxFrameCount;

// Defined in runtime/src/hle/audio/ax_mix.cpp. That header is private to the HLE
// directory and is not on this target's include path.
namespace AxDspHle {
void SetMixWorkerEnabled(bool enabled);
}

namespace settings_overlay {
namespace {

const char* GraphicsApiDisplayName() {
    switch (aurora_get_backend()) {
    case BACKEND_D3D11: return "Direct3D 11";
    case BACKEND_D3D12: return "Direct3D 12";
    case BACKEND_METAL: return "Metal";
    case BACKEND_VULKAN: return "Vulkan";
    case BACKEND_OPENGL: return "OpenGL";
    case BACKEND_OPENGLES: return "OpenGL ES";
    case BACKEND_WEBGPU: return "WebGPU";
    case BACKEND_NULL: return "Null";
    case BACKEND_AUTO: return "Automatic";
    }
    return "Unknown";
}

bool g_topBarVisible = false;
int g_controllerPort = 0;
float g_resolutionScale = RuntimeConfigFile::ResolutionMultiplier(1.0f);
int g_audioVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::AudioVolume(1.0f) * 100.0f));
int g_musicVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::MusicVolume(1.0f) * 100.0f));
int g_soundEffectsVolumePercent =
    static_cast<int>(std::lround(RuntimeConfigFile::SoundEffectsVolume(1.0f) * 100.0f));
int g_uiVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::UiVolume(1.0f) * 100.0f));
int g_voicesVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::VoicesVolume(1.0f) * 100.0f));
bool g_audioMuted = RuntimeConfigFile::AudioMuted(false);
bool g_audioMixWorker = RuntimeConfigFile::AudioMixWorkerEnabled(true);
bool g_attenuateMusicWhenMediaPlays = RuntimeConfigFile::AttenuateMusicWhenMediaPlays(false);
int g_frameInterpolationMode = [] {
    switch (RuntimeConfigFile::FrameInterpolationFps(0)) {
    case 120:
        return 1;
    case 180:
        return 2;
    default:
        return 0;
    }
}();
int ParseDisplayModeConfig(const std::string& mode) {
    if (mode == "borderless") {
        return static_cast<int>(AURORA_DISPLAY_MODE_BORDERLESS);
    }
    if (mode == "exclusive") {
        return static_cast<int>(AURORA_DISPLAY_MODE_EXCLUSIVE);
    }
    return static_cast<int>(AURORA_DISPLAY_MODE_WINDOWED);
}

int g_displayMode = ParseDisplayModeConfig(RuntimeConfigFile::DisplayMode("borderless"));
bool g_skipUnreadyPipelines = RuntimeConfigFile::SkipUnreadyPipelines(true);
bool g_disableCopyFilter = RuntimeConfigFile::DisableCopyFilter(true);
bool g_showFps = RuntimeConfigFile::ShowFps(true);
uint32_t g_disabledPostProcessingPaths = RuntimeConfigFile::DisabledPostProcessingPaths(0);
std::array<int32_t, PAD_MAX_CONTROLLERS> g_configuredControllerIndices = [] {
    std::array<int32_t, PAD_MAX_CONTROLLERS> indices{};
    indices.fill(std::numeric_limits<int32_t>::min());
    return indices;
}();

struct ControllerButtonItem {
    const char* configKey;
    const char* label;
    PADButton padButton;
};

constexpr std::array<ControllerButtonItem, PAD_BUTTON_COUNT> kControllerButtons = {{
    {"a", "A", PAD_BUTTON_A},
    {"b", "B", PAD_BUTTON_B},
    {"x", "X", PAD_BUTTON_X},
    {"y", "Y", PAD_BUTTON_Y},
    {"start", "Start", PAD_BUTTON_START},
    {"z", "Z", PAD_TRIGGER_Z},
    {"l", "L", PAD_TRIGGER_L},
    {"r", "R", PAD_TRIGGER_R},
    {"up", "D-pad Up", PAD_BUTTON_UP},
    {"down", "D-pad Down", PAD_BUTTON_DOWN},
    {"left", "D-pad Left", PAD_BUTTON_LEFT},
    {"right", "D-pad Right", PAD_BUTTON_RIGHT},
}};

struct NativeButtonItem {
    const char* configName;
    const char* label;
    uint32_t nativeButton;
};

constexpr std::array<NativeButtonItem, SDL_GAMEPAD_BUTTON_COUNT + 1> kNativeButtons = {{
    {"unmapped", "Unmapped / analog trigger", PAD_NATIVE_BUTTON_INVALID},
    {"south", "South (A / Cross)", SDL_GAMEPAD_BUTTON_SOUTH},
    {"east", "East (B / Circle)", SDL_GAMEPAD_BUTTON_EAST},
    {"west", "West (X / Square)", SDL_GAMEPAD_BUTTON_WEST},
    {"north", "North (Y / Triangle)", SDL_GAMEPAD_BUTTON_NORTH},
    {"back", "Back / Select", SDL_GAMEPAD_BUTTON_BACK},
    {"guide", "Guide / Home", SDL_GAMEPAD_BUTTON_GUIDE},
    {"start", "Start / Options", SDL_GAMEPAD_BUTTON_START},
    {"left_stick", "Left stick click", SDL_GAMEPAD_BUTTON_LEFT_STICK},
    {"right_stick", "Right stick click", SDL_GAMEPAD_BUTTON_RIGHT_STICK},
    {"left_shoulder", "Left shoulder", SDL_GAMEPAD_BUTTON_LEFT_SHOULDER},
    {"right_shoulder", "Right shoulder", SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER},
    {"dpad_up", "D-pad Up", SDL_GAMEPAD_BUTTON_DPAD_UP},
    {"dpad_down", "D-pad Down", SDL_GAMEPAD_BUTTON_DPAD_DOWN},
    {"dpad_left", "D-pad Left", SDL_GAMEPAD_BUTTON_DPAD_LEFT},
    {"dpad_right", "D-pad Right", SDL_GAMEPAD_BUTTON_DPAD_RIGHT},
    {"misc1", "Misc 1 / Share", SDL_GAMEPAD_BUTTON_MISC1},
    {"right_paddle1", "Right paddle 1", SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1},
    {"left_paddle1", "Left paddle 1", SDL_GAMEPAD_BUTTON_LEFT_PADDLE1},
    {"right_paddle2", "Right paddle 2", SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2},
    {"left_paddle2", "Left paddle 2", SDL_GAMEPAD_BUTTON_LEFT_PADDLE2},
    {"touchpad", "Touchpad", SDL_GAMEPAD_BUTTON_TOUCHPAD},
    {"misc2", "Misc 2", SDL_GAMEPAD_BUTTON_MISC2},
    {"misc3", "Misc 3 / GC L click", SDL_GAMEPAD_BUTTON_MISC3},
    {"misc4", "Misc 4 / GC R click", SDL_GAMEPAD_BUTTON_MISC4},
    {"misc5", "Misc 5", SDL_GAMEPAD_BUTTON_MISC5},
    {"misc6", "Misc 6", SDL_GAMEPAD_BUTTON_MISC6},
}};

// Classic Controller Pro layout, indexed like kControllerButtons: the SNES-style
// diamond (A right, B bottom, X top, Y left) with digital bumpers driving the GC
// triggers and Z on Back/Select (the same home the NSO GC default gives it).
constexpr std::array<const char*, PAD_BUTTON_COUNT> kClassicProPreset = {
    "east",           // A
    "south",          // B
    "north",          // X
    "west",           // Y
    "start",          // Start
    "back",           // Z
    "left_shoulder",  // L
    "right_shoulder", // R
    "dpad_up", "dpad_down", "dpad_left", "dpad_right",
};

struct ResolutionItem {
    const char* label;
    float scale;
};

using Clock = std::chrono::steady_clock;

constexpr auto kCursorAutoHideDelay = std::chrono::seconds(5);
Clock::time_point g_lastMouseActivity{Clock::now()};
bool g_cursorHidden = false;

constexpr std::array<std::string_view, 3> kDisplayModeConfigNames = {
    "windowed", "borderless", "exclusive",
};

uint64_t g_presentedFrame = 0;
std::atomic_bool g_strapInputAccepted = false;
std::atomic_uint64_t g_startupDismissFrame = UINT64_MAX;
constexpr uint64_t kStrapTransitionCoverFrames = 60;

constexpr std::array<ResolutionItem, 10> kResolutions = {{
    {"Auto (window size)", 0.0f}, {"0.5x (low power)", 0.5f}, {"0.75x", 0.75f},
    {"Native (1x)", 1.0f}, {"1.5x", 1.5f}, {"2x", 2.0f},
    {"3x", 3.0f}, {"4x", 4.0f}, {"6x", 6.0f}, {"8x", 8.0f},
}};

constexpr std::array<uint32_t, 3> kFrameInterpolationTargetFps{0, 120, 180};

bool IsHighResolutionScale(float scale) {
    return std::fabs(scale - 6.0f) < 0.001f || std::fabs(scale - 8.0f) < 0.001f;
}

bool IsHighFrameRateMode() {
    return kFrameInterpolationTargetFps[static_cast<size_t>(g_frameInterpolationMode)] > 60;
}

void SetResolutionScale(float scale) {
    g_resolutionScale = scale;
    VISetFrameBufferScale(scale);
    RuntimeConfigFile::SetResolutionMultiplier(scale);
}

void LimitResolutionForFrameRate() {
    if (IsHighFrameRateMode() && IsHighResolutionScale(g_resolutionScale)) {
        SetResolutionScale(4.0f);
    }
}

const NativeButtonItem* FindNativeButton(std::string value) {
    const auto it = std::find_if(kNativeButtons.begin(), kNativeButtons.end(), [&](const NativeButtonItem& item) {
        return value == item.configName;
    });
    return it == kNativeButtons.end() ? nullptr : &*it;
}

struct ControllerBindingPair {
    std::string primary;
    std::string secondary;
};

std::string TrimBindingToken(const std::string& token) {
    const size_t begin = token.find_first_not_of(" \t");
    if (begin == std::string::npos) {
        return {};
    }
    const size_t end = token.find_last_not_of(" \t");
    return token.substr(begin, end - begin + 1);
}

// Config values hold up to two comma-separated button names ("dpad_up" or
// "dpad_up,left_shoulder"); pressing either one counts as the GC button.
ControllerBindingPair SplitControllerBinding(const std::string& value) {
    const size_t comma = value.find(',');
    if (comma == std::string::npos) {
        return {TrimBindingToken(value), {}};
    }
    return {TrimBindingToken(value.substr(0, comma)), TrimBindingToken(value.substr(comma + 1))};
}

const NativeButtonItem& NativeButtonForValue(uint32_t nativeButton) {
    const auto it = std::find_if(kNativeButtons.begin(), kNativeButtons.end(), [&](const NativeButtonItem& item) {
        return nativeButton == item.nativeButton;
    });
    return it == kNativeButtons.end() ? kNativeButtons.front() : *it;
}

void SetTopBarVisible(bool visible) {
    if (g_topBarVisible == visible) {
        return;
    }
    g_topBarVisible = visible;
    PADBlockInput(visible);
#if defined(__ANDROID__)
    AndroidNotifySettingsVisibilityChanged(visible);
#endif
}

void ApplyConfiguredMappings() {
    for (uint32_t port = 0; port < PAD_MAX_CONTROLLERS; ++port) {
        const int32_t controllerIndex = PADGetIndexForPort(port);
        if (controllerIndex == g_configuredControllerIndices[port]) {
            continue;
        }
        g_configuredControllerIndices[port] = controllerIndex;
        if (controllerIndex < 0) {
            continue;
        }

        uint32_t count = 0;
        if (PADGetButtonMappings(port, &count) == nullptr || count != PAD_BUTTON_COUNT) {
            continue;
        }
        for (size_t i = 0; i < kControllerButtons.size(); ++i) {
            const auto& configured = RuntimeConfigFile::ControllerButton(i);
            if (!configured) {
                continue;
            }
            const ControllerBindingPair binding = SplitControllerBinding(*configured);
            if (const NativeButtonItem* native = FindNativeButton(binding.primary)) {
                PADSetButtonMapping(port, PADButtonMapping{native->nativeButton, kControllerButtons[i].padButton});
            } else {
                RT_LOG(RT_TAG_CONFIG) << "Unknown controller." << kControllerButtons[i].configKey
                          << " button '" << binding.primary << "'" << std::endl;
            }
            uint32_t altNative = PAD_NATIVE_BUTTON_INVALID;
            if (!binding.secondary.empty()) {
                if (const NativeButtonItem* native = FindNativeButton(binding.secondary)) {
                    altNative = native->nativeButton;
                } else {
                    RT_LOG(RT_TAG_CONFIG) << "Unknown controller." << kControllerButtons[i].configKey
                              << " secondary button '" << binding.secondary << "'" << std::endl;
                }
            }
            PADSetAltButtonMapping(port, PADButtonMapping{altNative, kControllerButtons[i].padButton});
        }
    }
}

void DrawControllerSettings() {
#if defined(__ANDROID__)
    // Lives here (not under Graphics, where it was originally placed) since it's an input
    // setting, not a display one - confirmed directly that it wasn't found under Graphics. The
    // actual show/hide + persistence lives Kotlin-side (TouchControlsOverlay.kt) as a per-device
    // UI preference, same as its drag/resize/hide-per-button state - this checkbox is just the
    // settings-menu entry point into that, plus a live mirror so it displays the right state
    // (seeded via nativeSetTouchControlsVisibleCache, updated on every toggle here). Most useful
    // right after a real controller auto-connects and hides the touch overlay
    // (TouchControlsOverlay.setControllerConnected) but the player wants it back anyway.
    if (ImGui::Checkbox("Touch controls", &g_androidTouchControlsVisibleCache)) {
        AndroidSetTouchOverlayVisible(g_androidTouchControlsVisibleCache);
    }
    ImGui::Separator();
#endif
    for (int port = 0; port < PAD_MAX_CONTROLLERS; ++port) {
        const std::string label = "Port " + std::to_string(port + 1);
        ImGui::RadioButton(label.c_str(), &g_controllerPort, port);
        if (port + 1 < PAD_MAX_CONTROLLERS) {
            ImGui::SameLine();
        }
    }

    ImGui::Separator();
    controller_mapping_wizard::DrawSetupList();
    const uint32_t controllerCount = PADCount();
    if (controllerCount == 0) {
        ImGui::TextDisabled("No controller connected");
        return;
    }

    const char* currentName = PADGetName(static_cast<uint32_t>(g_controllerPort));
    ImGui::Text("Assigned: %s", currentName != nullptr ? currentName : "None");
    if (ImGui::BeginMenu("Assign connected controller")) {
        for (uint32_t index = 0; index < controllerCount; ++index) {
            const char* name = PADGetNameForControllerIndex(index);
            ImGui::PushID(static_cast<int>(index));
            if (ImGui::MenuItem(name != nullptr ? name : "Unknown controller")) {
                PADSetPortForIndex(index, static_cast<uint32_t>(g_controllerPort));
                g_configuredControllerIndices.fill(std::numeric_limits<int32_t>::min());
                ApplyConfiguredMappings();
            }
            ImGui::PopID();
        }
        ImGui::EndMenu();
    }

    uint32_t mappingCount = 0;
    PADButtonMapping* mappings = PADGetButtonMappings(static_cast<uint32_t>(g_controllerPort), &mappingCount);
    if (mappings == nullptr || mappingCount != PAD_BUTTON_COUNT) {
        ImGui::TextDisabled("Assign a controller to edit its buttons");
        return;
    }

    uint32_t altMappingCount = 0;
    PADButtonMapping* altMappings =
        PADGetAltButtonMappings(static_cast<uint32_t>(g_controllerPort), &altMappingCount);

    const auto writeBinding = [](size_t index, uint32_t primaryNative, uint32_t altNative) {
        std::string value = NativeButtonForValue(primaryNative).configName;
        if (altNative != PAD_NATIVE_BUTTON_INVALID) {
            value += ',';
            value += NativeButtonForValue(altNative).configName;
        }
        RuntimeConfigFile::SetControllerButton(index, value);
    };

    // Which rows show the second-binding combo without one being bound yet;
    // reset when the user switches ports so a stale "+" click doesn't linger.
    static std::array<bool, PAD_BUTTON_COUNT> altRowExpanded{};
    static int altRowExpandedPort = -1;
    if (altRowExpandedPort != g_controllerPort) {
        altRowExpandedPort = g_controllerPort;
        altRowExpanded.fill(false);
    }

    ImGui::SeparatorText("Presets");
    if (ImGui::Button("GameCube")) {
        const uint32_t port = static_cast<uint32_t>(g_controllerPort);
        PADRestoreDefaultMapping(port);
        uint32_t restoredCount = 0;
        if (PADButtonMapping* restored = PADGetButtonMappings(port, &restoredCount)) {
            for (size_t i = 0; i < kControllerButtons.size(); ++i) {
                const auto it = std::find_if(restored, restored + restoredCount, [&](const PADButtonMapping& mapping) {
                    return mapping.padButton == kControllerButtons[i].padButton;
                });
                if (it != restored + restoredCount) {
                    RuntimeConfigFile::SetControllerButton(i, NativeButtonForValue(it->nativeButton).configName);
                }
            }
        }
        altRowExpanded.fill(false);
        PADSerializeMappings();
        mappings = PADGetButtonMappings(port, &mappingCount);
    }
    ImGui::SameLine();
    if (ImGui::Button("Classic Controller Pro")) {
        const uint32_t port = static_cast<uint32_t>(g_controllerPort);
        for (size_t i = 0; i < kControllerButtons.size(); ++i) {
            if (const NativeButtonItem* native = FindNativeButton(kClassicProPreset[i])) {
                PADSetButtonMapping(port, PADButtonMapping{native->nativeButton, kControllerButtons[i].padButton});
                PADSetAltButtonMapping(port,
                                       PADButtonMapping{PAD_NATIVE_BUTTON_INVALID, kControllerButtons[i].padButton});
                RuntimeConfigFile::SetControllerButton(i, kClassicProPreset[i]);
            }
        }
        altRowExpanded.fill(false);
        PADSerializeMappings();
        mappings = PADGetButtonMappings(port, &mappingCount);
    }

    ImGui::SeparatorText("Button mapping");
    for (size_t i = 0; i < kControllerButtons.size(); ++i) {
        auto mappingIt = std::find_if(mappings, mappings + mappingCount, [&](const PADButtonMapping& mapping) {
            return mapping.padButton == kControllerButtons[i].padButton;
        });
        if (mappingIt == mappings + mappingCount) {
            continue;
        }
        PADButtonMapping* altIt = nullptr;
        if (altMappings != nullptr && altMappingCount == PAD_BUTTON_COUNT) {
            const auto it = std::find_if(altMappings, altMappings + altMappingCount, [&](const PADButtonMapping& mapping) {
                return mapping.padButton == kControllerButtons[i].padButton;
            });
            if (it != altMappings + altMappingCount) {
                altIt = it;
            }
        }

        const NativeButtonItem& current = NativeButtonForValue(mappingIt->nativeButton);
        ImGui::PushID(static_cast<int>(i));
        ImGui::SetNextItemWidth(190.0f);
        if (ImGui::BeginCombo("##primary", current.label)) {
            for (const auto& candidate : kNativeButtons) {
                const bool selected = candidate.nativeButton == mappingIt->nativeButton;
                if (ImGui::Selectable(candidate.label, selected)) {
                    const uint32_t port = static_cast<uint32_t>(g_controllerPort);
                    PADSetButtonMapping(port, PADButtonMapping{candidate.nativeButton, kControllerButtons[i].padButton});
                    writeBinding(i, candidate.nativeButton,
                                 altIt != nullptr ? altIt->nativeButton : PAD_NATIVE_BUTTON_INVALID);
                    PADSerializeMappings();
                    mappings = PADGetButtonMappings(port, &mappingCount);
                }
                if (selected) {
                    ImGui::SetItemDefaultFocus();
                }
            }
            ImGui::EndCombo();
        }
        if (altIt != nullptr) {
            const bool altBound = altIt->nativeButton != PAD_NATIVE_BUTTON_INVALID;
            if (!altBound && !altRowExpanded[i]) {
                ImGui::SameLine();
                if (ImGui::SmallButton("+")) {
                    altRowExpanded[i] = true;
                }
                if (ImGui::IsItemHovered()) {
                    ImGui::SetTooltip("Add a second binding; pressing either one works");
                }
            } else {
                ImGui::SameLine();
                ImGui::TextUnformatted("or");
                ImGui::SameLine();
                const char* altLabel = altBound ? NativeButtonForValue(altIt->nativeButton).label : "None";
                ImGui::SetNextItemWidth(190.0f);
                if (ImGui::BeginCombo("##alt", altLabel)) {
                    for (const auto& candidate : kNativeButtons) {
                        const bool isNone = candidate.nativeButton == PAD_NATIVE_BUTTON_INVALID;
                        const bool selected = candidate.nativeButton == altIt->nativeButton;
                        if (ImGui::Selectable(isNone ? "None" : candidate.label, selected)) {
                            const uint32_t port = static_cast<uint32_t>(g_controllerPort);
                            PADSetAltButtonMapping(
                                port, PADButtonMapping{candidate.nativeButton, kControllerButtons[i].padButton});
                            writeBinding(i, mappingIt->nativeButton, candidate.nativeButton);
                            if (isNone) {
                                altRowExpanded[i] = false;
                            }
                        }
                        if (selected) {
                            ImGui::SetItemDefaultFocus();
                        }
                    }
                    ImGui::EndCombo();
                }
            }
        }
        ImGui::SameLine();
        ImGui::TextUnformatted(kControllerButtons[i].label);
        ImGui::PopID();
    }

}

void DrawAudioSettings() {
    ImGui::SetNextItemWidth(220.0f);
    if (ImGui::SliderInt("Master", &g_audioVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_audioVolumePercent) / 100.0f;
        AudioBackend::Instance().SetMasterVolume(volume);
        RuntimeConfigFile::SetAudioVolume(volume);
    }
    if (ImGui::SliderInt("Music", &g_musicVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_musicVolumePercent) / 100.0f;
        MusicAttenuation::SetMusicVolume(volume);
        RuntimeConfigFile::SetMusicVolume(volume);
    }
    if (ImGui::SliderInt("Sound Effects", &g_soundEffectsVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_soundEffectsVolumePercent) / 100.0f;
        MusicAttenuation::SetSoundEffectsVolume(volume);
        RuntimeConfigFile::SetSoundEffectsVolume(volume);
    }
    if (ImGui::SliderInt("UI", &g_uiVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_uiVolumePercent) / 100.0f;
        MusicAttenuation::SetUiVolume(volume);
        RuntimeConfigFile::SetUiVolume(volume);
    }
    if (ImGui::SliderInt("Voices", &g_voicesVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_voicesVolumePercent) / 100.0f;
        MusicAttenuation::SetVoicesVolume(volume);
        RuntimeConfigFile::SetVoicesVolume(volume);
    }
    if (ImGui::Checkbox("Mute", &g_audioMuted)) {
        AudioBackend::Instance().SetMuted(g_audioMuted);
        RuntimeConfigFile::SetAudioMuted(g_audioMuted);
    }
    ImGui::Separator();
    if (ImGui::Checkbox("Mix audio on a worker thread", &g_audioMixWorker)) {
        // Applies immediately: SetMixWorkerEnabled joins any in-flight mix
        // before switching, so the change never lands mid-frame.
        AxDspHle::SetMixWorkerEnabled(g_audioMixWorker);
        RuntimeConfigFile::SetAudioMixWorker(g_audioMixWorker);
    }
    if (ImGui::IsItemHovered()) {
        ImGui::SetTooltip(
            "Runs the AX/DSP voice mix off the game thread. Turn this off if you "
            "suspect an audio problem; the mix then runs inline as it used to.");
    }
    ImGui::Separator();
#if !defined(__ANDROID__)
    // Android's version of this - detecting "is something else currently playing" via
    // AudioManager focus - turned out to have no reliable, non-disruptive implementation: the
    // only way to detect regain is to periodically re-request focus, which is an exclusive
    // request and can itself interrupt whatever the player is actually listening to. Confirmed
    // directly as still not working after two different mitigation attempts ("music ducking is
    // still not working well, its not unmuting" / "doesn't work, let's just leave music ducking
    // for the future, disable the toggle"). Hidden here rather than reworked further for now;
    // MusicAttenuation itself is untouched and this can come back once Android exposes a real,
    // passive way to answer "is anything else playing" (e.g. a properly attributable
    // getActivePlaybackConfigurations()).
    if (ImGui::Checkbox("Mute game music while external media is playing",
                        &g_attenuateMusicWhenMediaPlays)) {
        MusicAttenuation::SetEnabled(g_attenuateMusicWhenMediaPlays);
        RuntimeConfigFile::SetAttenuateMusicWhenMediaPlays(g_attenuateMusicWhenMediaPlays);
    }
    if (g_attenuateMusicWhenMediaPlays) {
        if (MusicAttenuation::IsExternalMediaPlaying()) {
            ImGui::TextDisabled("External media is playing; game music is muted.");
        } else if (!MusicAttenuation::IsMediaControlInitializationComplete()) {
            ImGui::TextDisabled("Waiting for Windows Media Control...");
        } else if (!MusicAttenuation::IsMediaControlAvailable()) {
            ImGui::TextDisabled("Windows Media Control is unavailable.");
        } else {
            ImGui::TextDisabled("No external media is currently playing.");
        }
    }
#endif
}

// Render-resolution scale (0.5x-8x the Wii's native output), same list/logic as DrawTopBar's own
// resolution dropdown. That one lives inline in DrawTopBar (desktop's menu bar) and is left alone
// there; this is a separate, sidebar-appropriate (BeginCombo, not BeginMenu) copy for
// DrawAndroidSidebar, which never actually included this setting at all when it replaced the top
// bar on Android - confirmed directly as a real regression ("in Display settings add
// resolution(the scaling settings, as those were removed fully.)").
void DrawResolutionSettings() {
    const auto resolutionIt = std::find_if(kResolutions.begin(), kResolutions.end(), [](const ResolutionItem& item) {
        return std::fabs(item.scale - g_resolutionScale) < 0.001f;
    });
    const char* resolutionLabel = resolutionIt != kResolutions.end() ? resolutionIt->label : "Custom";
    if (ImGui::BeginCombo("Resolution", resolutionLabel)) {
        for (const auto& resolution : kResolutions) {
            const bool selected = std::fabs(resolution.scale - g_resolutionScale) < 0.001f;
            const bool disabled = IsHighFrameRateMode() && IsHighResolutionScale(resolution.scale);
            ImGui::BeginDisabled(disabled);
            const bool clicked = ImGui::Selectable(resolution.label, selected);
            ImGui::EndDisabled();
            if (clicked) {
                SetResolutionScale(resolution.scale);
            }
            if (selected) {
                ImGui::SetItemDefaultFocus();
            }
        }
        ImGui::EndCombo();
    }
    ImGui::Separator();
}

void DrawGraphicsSettings() {
    g_displayMode = static_cast<int>(aurora_get_display_mode());
    struct EffectFlag {
        const char* label;
        uint32_t flag;
    };
    static constexpr std::array<EffectFlag, 1> kEffectFlags = {{
        {"Disable bloom", 0x10u},
    }};

    for (const auto& effect : kEffectFlags) {
        bool disabled = (g_disabledPostProcessingPaths & effect.flag) != 0;
        if (ImGui::Checkbox(effect.label, &disabled)) {
            if (disabled) {
                g_disabledPostProcessingPaths |= effect.flag;
            } else {
                g_disabledPostProcessingPaths &= ~effect.flag;
            }
            RuntimeGameGraphicsOptions::SetDisabledPostProcessingPaths(g_disabledPostProcessingPaths);
            RuntimeConfigFile::SetDisabledPostProcessingPaths(g_disabledPostProcessingPaths);
        }
    }
    ImGui::TextDisabled("Applied when the next scene renderer is created.");
    ImGui::Separator();
    static constexpr const char* kDisplayModes[] = {
        "Windowed",
        "Borderless fullscreen",
        "Exclusive fullscreen",
    };
    if (ImGui::Combo("Display mode", &g_displayMode, kDisplayModes, static_cast<int>(std::size(kDisplayModes)))) {
        const auto mode = static_cast<AuroraDisplayMode>(g_displayMode);
        aurora_set_display_mode(mode);
        const AuroraDisplayMode activeMode = aurora_get_display_mode();
        if (activeMode == mode) {
            RuntimeConfigFile::SetDisplayMode(std::string(kDisplayModeConfigNames[static_cast<size_t>(g_displayMode)]));
        } else {
            g_displayMode = static_cast<int>(activeMode);
        }
    }
    if (g_displayMode == AURORA_DISPLAY_MODE_EXCLUSIVE) {
        ImGui::TextDisabled(
            "Requests the closest native-resolution display mode to the output frame "
            "rate (60 Hz, or the frame interpolation target).");
    }
    constexpr std::array<const char*, 3> kFrameInterpolationModes{
        "Off", "120 FPS", "180 FPS",
    };
    const char* currentFrameInterpolationMode =
        kFrameInterpolationModes[static_cast<size_t>(g_frameInterpolationMode)];
    bool frameInterpolationModeChanged = false;
    if (ImGui::BeginCombo("Race frame interpolation (experimental)", currentFrameInterpolationMode)) {
        for (int mode = 0; mode < static_cast<int>(kFrameInterpolationModes.size()); ++mode) {
            const bool selected = g_frameInterpolationMode == mode;
            if (ImGui::Selectable(kFrameInterpolationModes[static_cast<size_t>(mode)], selected)) {
                g_frameInterpolationMode = mode;
                frameInterpolationModeChanged = true;
            }
            if (selected) {
                ImGui::SetItemDefaultFocus();
            }
        }
        ImGui::EndCombo();
    }
    if (frameInterpolationModeChanged) {
        const uint32_t targetFps = kFrameInterpolationTargetFps[static_cast<size_t>(g_frameInterpolationMode)];
        aurora_set_frame_interpolation_fps(targetFps);
        RuntimeConfigFile::SetFrameInterpolationFps(targetFps);
        LimitResolutionForFrameRate();
        if (aurora_get_display_mode() == AURORA_DISPLAY_MODE_EXCLUSIVE) {
            // Re-apply exclusive mode so the display refresh tracks the new target.
            aurora_set_display_mode(AURORA_DISPLAY_MODE_EXCLUSIVE);
        }
    }
    ImGui::PushTextWrapPos(ImGui::GetCursorPosX() + 380.0f);
    ImGui::TextDisabled("Frame interpolation is experimental, you might find visual artifacts");
    ImGui::PopTextWrapPos();
    if (ImGui::Checkbox("Disable copy filter", &g_disableCopyFilter)) {
        aurora_set_disable_copy_filter(g_disableCopyFilter);
        RuntimeConfigFile::SetDisableCopyFilter(g_disableCopyFilter);
    }
    if (ImGui::Checkbox("Skip draws while shaders compile", &g_skipUnreadyPipelines)) {
        aurora_set_skip_unready_pipelines(g_skipUnreadyPipelines);
        RuntimeConfigFile::SetSkipUnreadyPipelines(g_skipUnreadyPipelines);
    }
    if (ImGui::Checkbox("Show FPS", &g_showFps)) {
        RuntimeConfigFile::SetShowFps(g_showFps);
    }
    ImGui::Separator();
    ImGui::Text("Graphics API: %s", GraphicsApiDisplayName());
}

void DrawFpsOverlay() {
    AuroraPresentTiming presentTiming{};
    aurora_get_present_timing(&presentTiming);
    if (!g_showFps) {
        return;
    }

    const ImGuiIO& io = ImGui::GetIO();
    constexpr float kMargin = 10.0f;
    const float top = g_topBarVisible ? ImGui::GetFrameHeight() + kMargin : kMargin;
    ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x - kMargin, top), ImGuiCond_Always, ImVec2(1.0f, 0.0f));
    ImGui::SetNextWindowBgAlpha(0.55f);
    constexpr ImGuiWindowFlags kFlags = ImGuiWindowFlags_AlwaysAutoResize |
                                         ImGuiWindowFlags_NoDecoration |
                                         ImGuiWindowFlags_NoFocusOnAppearing |
                                         ImGuiWindowFlags_NoInputs |
                                         ImGuiWindowFlags_NoMove |
                                         ImGuiWindowFlags_NoNav |
                                         ImGuiWindowFlags_NoSavedSettings;
    if (ImGui::Begin("FPS Overlay", nullptr, kFlags)) {
        if (presentTiming.sampleCount == 0) {
            ImGui::TextUnformatted("FPS: --");
        } else {
            // Present timing includes the additional frames produced by
            // interpolation, so this remains the actual displayed FPS.
            ImGui::Text("FPS: %.1f", presentTiming.framesPerSecond);
            // Replay-unsafe frames hold the presented cadence with duplicated
            // slots, so the counter alone reads 180 while the motion on screen
            // is 60 Hz. Surface the divergence instead of hiding it.
            if (presentTiming.effectiveFramesPerSecond <
                presentTiming.framesPerSecond * 0.95) {
                ImGui::TextColored(ImVec4(1.0f, 0.75f, 0.25f, 1.0f), "Motion: %.1f",
                                   presentTiming.effectiveFramesPerSecond);
            }
        }
    }
    ImGui::End();
}

void DrawShaderCompilationStatus() {
    const uint32_t queuedPipelines = aurora_get_queued_pipeline_count();
    if (queuedPipelines == 0) {
        return;
    }

    constexpr float kMargin = 10.0f;
    // Left margin only for this overlay is much bigger than kMargin (used everywhere else for
    // top/right-edge spacing) - tucked right into the corner it was easy to miss entirely
    // (reported directly: "not very visible right now"), so it's pulled well clear of the edge.
    constexpr float kLeftMargin = 140.0f;
    const float top = g_topBarVisible ? ImGui::GetFrameHeight() + kMargin : kMargin;
    ImGui::SetNextWindowPos(ImVec2(kLeftMargin, top), ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(0.55f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(7.0f, 4.0f));
    constexpr ImGuiWindowFlags kFlags = ImGuiWindowFlags_AlwaysAutoResize |
                                        ImGuiWindowFlags_NoDecoration |
                                        ImGuiWindowFlags_NoFocusOnAppearing |
                                        ImGuiWindowFlags_NoInputs |
                                        ImGuiWindowFlags_NoMove |
                                        ImGuiWindowFlags_NoNav |
                                        ImGuiWindowFlags_NoSavedSettings;
    if (ImGui::Begin("Shader Compilation Status", nullptr, kFlags)) {
        ImGui::SetWindowFontScale(0.85f);
        ImGui::Text("%u shader%s compiling", queuedPipelines, queuedPipelines == 1 ? "" : "s");
    }
    ImGui::End();
    ImGui::PopStyleVar();
}

void DrawStartupScreen() {
    if (!StartupScreenVisible()) {
        return;
    }

    const ImGuiViewport* viewport = ImGui::GetMainViewport();
    ImGui::SetNextWindowPos(viewport->Pos, ImGuiCond_Always);
    ImGui::SetNextWindowSize(viewport->Size, ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(1.0f);
    ImGui::PushStyleColor(ImGuiCol_WindowBg, IM_COL32(0, 0, 0, 255));
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(0.0f, 0.0f));
    constexpr ImGuiWindowFlags kFlags = ImGuiWindowFlags_NoDecoration |
                                        ImGuiWindowFlags_NoFocusOnAppearing |
                                        ImGuiWindowFlags_NoInputs |
                                        ImGuiWindowFlags_NoMove |
                                        ImGuiWindowFlags_NoNav |
                                        ImGuiWindowFlags_NoSavedSettings |
                                        ImGuiWindowFlags_NoBringToFrontOnFocus;
    if (ImGui::Begin("Wiicompiled Startup", nullptr, kFlags)) {
        ImGui::SetWindowFontScale(1.25f);
        // Android app branding is "DriftDroid" - desktop stays "WiiCompiled" (the underlying
        // static-recompilation project's own name, unrelated to the Android port's rebrand).
#if defined(__ANDROID__)
        constexpr const char* kTitle = "DriftDroid";
#else
        constexpr const char* kTitle = "WiiCompiled";
#endif
        const ImVec2 titleSize = ImGui::CalcTextSize(kTitle);
        const float titleX = std::max(0.0f, (viewport->Size.x - titleSize.x) * 0.5f);
        const float startY = std::max(0.0f, (viewport->Size.y - titleSize.y) * 0.5f);
        ImGui::SetCursorPos(ImVec2(titleX, startY));
        ImGui::TextUnformatted(kTitle);
    }
    ImGui::End();
    ImGui::PopStyleVar();
    ImGui::PopStyleColor();
}

#if defined(__ANDROID__)
// Any controller landing on Android - the touch overlay's own virtual gamepad
// (runtime/src/android_touch_controls.cpp) or a real Bluetooth pad - presents itself as a
// brand-new SDL gamepad with no saved mapping, and would otherwise land on whatever raw/default
// bindings SDL happens to pick (confirmed on-device: B triggered drift, L and R did nothing
// useful for the touch device; a real Switch Pro Controller needed a manual visit to the settings
// menu's "Classic Controller Pro" button before it worked at all - confirmed directly: "if you
// have a controller right now you have to manually go to the settings menu"). Auto-apply the
// preset to EVERY newly-seen controller index, not just the touch device by name, the instant it
// shows up, so nothing on Android ever needs manual setup.
//
// Tracked once per controller INDEX (not a single global bool) so this can fire independently for
// the touch device and any number of real controllers connecting over a session, without
// repeatedly re-stomping a controller the player has since deliberately customized differently.
// Indices are small and session-scoped in practice (SDL assigns them in connection order), so a
// bitset sized generously above PAD_MAX_CONTROLLERS is a safe, simple tracking scheme - the same
// per-process "configure once" tradeoff the single-bool version already accepted for the touch
// device specifically.
std::bitset<64> g_androidControllersAutoConfigured{};

void AutoConfigureNewAndroidControllersIfPresent() {
    const uint32_t controllerCount = PADCount();
    RT_LOG(RT_TAG_CONFIG) << "AutoConfigureNewAndroidControllersIfPresent: scanning " << controllerCount
                           << " controller(s)";
    for (uint32_t index = 0; index < controllerCount; ++index) {
        if (index < g_androidControllersAutoConfigured.size() && g_androidControllersAutoConfigured[index]) {
            continue;
        }
        const char* name = PADGetNameForControllerIndex(index);
        RT_LOG(RT_TAG_CONFIG) << "  [" << index << "] " << (name != nullptr ? name : "(null)");

        // First port that is not already a *different* real controller - confirmed on-device this
        // matters: hard-coding port 0 silently evicted an already-connected, already
        // correctly-mapped controller the instant a second device showed up. An empty port, or a
        // port already holding this exact device (e.g. a re-run after some prior state reset), are
        // both fair game; anything else is a different pad and must be left alone.
        uint32_t targetPort = 0;
        bool foundPort = false;
        for (uint32_t port = 0; port < PAD_MAX_CONTROLLERS; ++port) {
            const char* portName = PADGetName(port);
            if (portName == nullptr || (name != nullptr && std::string_view(portName) == name)) {
                targetPort = port;
                foundPort = true;
                break;
            }
        }
        if (!foundPort) {
            RT_LOG(RT_TAG_CONFIG) << "AutoConfigureNewAndroidControllersIfPresent: all ports already "
                                      "hold a different controller, leaving index "
                                   << index << " unbound";
            continue;
        }
        PADSetPortForIndex(index, targetPort);
        // Classic Controller Pro, not GameCube: matches TouchControlsOverlay's L/R buttons,
        // which send SDL_GAMEPAD_BUTTON_LEFT/RIGHT_SHOULDER (this preset's "left_shoulder"/
        // "right_shoulder"), not an analog trigger axis - and matches a real Switch Pro
        // Controller's physical wiring, so the same preset is correct for both.
        for (size_t i = 0; i < kControllerButtons.size(); ++i) {
            if (const NativeButtonItem* native = FindNativeButton(kClassicProPreset[i])) {
                PADSetButtonMapping(targetPort,
                                     PADButtonMapping{native->nativeButton, kControllerButtons[i].padButton});
                PADSetAltButtonMapping(
                    targetPort, PADButtonMapping{PAD_NATIVE_BUTTON_INVALID, kControllerButtons[i].padButton});
                RuntimeConfigFile::SetControllerButton(i, kClassicProPreset[i]);
            }
        }
        PADSerializeMappings();
        if (index < g_androidControllersAutoConfigured.size()) {
            g_androidControllersAutoConfigured.set(index);
        }
        RT_LOG(RT_TAG_CONFIG) << "AutoConfigureNewAndroidControllersIfPresent: configured index " << index
                               << " on port " << targetPort;
    }
}
// Touch-friendly replacement for DrawTopBar() below - a top menu bar with hover-opened dropdowns
// is a desktop/mouse pattern that doesn't translate well to touch (small hit targets, no hover).
// Reuses DrawGraphicsSettings()/DrawControllerSettings()/DrawAudioSettings() as-is for content -
// only the outer navigation changes, to avoid re-deriving already-working settings logic.
// Requested directly: "the settings that already appear when you press the settings button but in
// the sidebar, cleaner."
// One full-width, left-aligned row in the root list - label plus an optional trailing glyph
// (">" for a row that drills into a sub-page, nothing for a plain action). Matches KartPad's own
// Android sidebar layout (icon+label+chevron rows in a flat scrollable list), which is what was
// actually requested after seeing a screenshot of it, rather than the tabbed layout this replaces.
bool DrawSidebarRow(const char* label, const char* trailing = ">") {
    const float rowHeight = 56.0f;
    const ImVec2 rowSize(ImGui::GetContentRegionAvail().x, rowHeight);
    const bool clicked = ImGui::Button((std::string(label) + "##Row").c_str(), rowSize);
    if (trailing && trailing[0] != '\0') {
        const float trailingWidth = ImGui::CalcTextSize(trailing).x;
        const ImVec2 rowMin = ImGui::GetItemRectMin();
        const ImVec2 rowMax = ImGui::GetItemRectMax();
        ImGui::GetWindowDrawList()->AddText(
            ImVec2(rowMax.x - trailingWidth - 16.0f, (rowMin.y + rowMax.y) * 0.5f - ImGui::GetTextLineHeight() * 0.5f),
            ImGui::GetColorU32(ImGuiCol_TextDisabled), trailing);
    }
    return clicked;
}

void DrawAndroidSidebar() {
    if (!g_topBarVisible) {
        return;
    }

    ImGuiIO& io = ImGui::GetIO();
    // ~42% of screen width, matching KartPad's own Android sidebar proportions (measured directly
    // from a screenshot of it, at the user's request to look at how it actually does this).
    // Deliberately reaches into where the touch R/X/Y/A/B buttons normally sit; those are hidden
    // for as long as the sidebar is open (TouchControlsOverlay.setSettingsOpen) rather than trying
    // to squeeze around them, since gameplay input is already blocked while it's up.
    const float sidebarWidth = io.DisplaySize.x * 0.42f;
    ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x, 0.0f), ImGuiCond_Always, ImVec2(1.0f, 0.0f));
    ImGui::SetNextWindowSize(ImVec2(sidebarWidth, io.DisplaySize.y), ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(0.94f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, 12.0f);
    constexpr ImGuiWindowFlags kFlags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                                         ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoCollapse |
                                         ImGuiWindowFlags_NoResize;
    if (!ImGui::Begin("Settings##AndroidSidebar", nullptr, kFlags)) {
        ImGui::End();
        ImGui::PopStyleVar();
        return;
    }

    enum class SidebarPage { Root, Display, Controller, Audio };
    static SidebarPage page = SidebarPage::Root;

    ImGui::SetWindowFontScale(1.15f);
    ImGui::TextUnformatted(page == SidebarPage::Root ? "Settings"
                            : page == SidebarPage::Display ? "Display"
                            : page == SidebarPage::Controller ? "Controller"
                                                               : "Audio");
    const float closeWidth = ImGui::CalcTextSize("Close").x + ImGui::GetStyle().FramePadding.x * 2.0f + 16.0f;
    ImGui::SameLine(ImGui::GetContentRegionMax().x - closeWidth);
    if (ImGui::Button("Close", ImVec2(closeWidth, 0.0f))) {
        SetTopBarVisible(false);
    }
    ImGui::SetWindowFontScale(1.0f);
    ImGui::Separator();
    ImGui::Spacing();

    ImGui::BeginChild("SidebarPageContent", ImVec2(0.0f, 0.0f), false);
    if (page == SidebarPage::Root) {
        if (DrawSidebarRow("Display")) {
            page = SidebarPage::Display;
        }
        if (DrawSidebarRow("Controller")) {
            page = SidebarPage::Controller;
        }
        if (DrawSidebarRow("Audio")) {
            page = SidebarPage::Audio;
        }
        ImGui::Separator();
        ImGui::Spacing();
        // Every slider/checkbox on every page already writes straight to Config.toml the instant
        // it changes (see e.g. DrawAudioSettings' RuntimeConfigFile::Set* calls) - there is
        // nothing this button actually needs to commit. It exists anyway, requested directly
        // while testing, as an explicit confirmation point rather than trusting that silently.
        static Clock::time_point savedConfirmationUntil{};
        if (ImGui::Button("Save Settings", ImVec2(-1.0f, 52.0f))) {
            savedConfirmationUntil = Clock::now() + std::chrono::seconds(2);
        }
        if (Clock::now() < savedConfirmationUntil) {
            ImGui::TextColored(ImVec4(0.4f, 0.9f, 0.5f, 1.0f), "Settings saved.");
        }
    } else {
        if (DrawSidebarRow("Back to Settings", "<")) {
            page = SidebarPage::Root;
        }
        ImGui::Separator();
        ImGui::Spacing();
        switch (page) {
        case SidebarPage::Display:
            DrawResolutionSettings();
            DrawGraphicsSettings();
            ImGui::Separator();
            ImGui::Spacing();
            // Texture pack import/management deliberately does NOT live here - it used to have a
            // button on this page that opened Kotlin's ModManager dialog, but that dialog
            // launches Android's system file picker as a separate Activity, which backgrounds this
            // live game session's GPU surface. Confirmed directly on-device: doing that mid-race
            // lost the WebGPU/Vulkan surface ("QueuePresent failed with VK_ERROR_SURFACE_LOST_KHR")
            // and crashed. No such surface exists yet on the launcher screen (ModePickerActivity),
            // so that's the only place it's safe - same reasoning as why Retro Rewind's own install
            // flow only ever runs from there too.
            ImGui::TextWrapped(
                "Manage texture packs from the launcher screen (before starting a race).");
            break;
        case SidebarPage::Controller:
            DrawControllerSettings();
            ImGui::Separator();
            ImGui::Spacing();
            if (ImGui::Button("Edit Touch Layout", ImVec2(-1.0f, 48.0f))) {
                // Editing happens in Kotlin's TouchControlsOverlay, not this ImGui panel - close
                // the sidebar first so it isn't sitting on top of (and eating touches meant for)
                // the layout editor that's about to appear.
                SetTopBarVisible(false);
                AndroidStartTouchLayoutEdit();
            }
            if (ImGui::Button("Motion Steering", ImVec2(-1.0f, 48.0f))) {
                // Same reasoning as Edit Touch Layout above - the dialog is a real Kotlin
                // AlertDialog, not an ImGui popup, so get the sidebar out of the way first.
                SetTopBarVisible(false);
                AndroidShowMotionSteeringDialog();
            }
            break;
        case SidebarPage::Audio:
            DrawAudioSettings();
            break;
        case SidebarPage::Root:
            break;
        }
    }
    ImGui::EndChild();

    ImGui::End();
    ImGui::PopStyleVar();
}

#endif  // __ANDROID__

void DrawTopBar() {
    if (!g_topBarVisible || !ImGui::BeginMainMenuBar()) {
        return;
    }

    ImGui::TextUnformatted("WiiCompiled");
    ImGui::Separator();
    const auto resolutionIt = std::find_if(kResolutions.begin(), kResolutions.end(), [](const ResolutionItem& item) {
        return std::fabs(item.scale - g_resolutionScale) < 0.001f;
    });
    const char* resolutionLabel = resolutionIt != kResolutions.end() ? resolutionIt->label : "Custom";
    const std::string resolutionMenuLabel = std::string("Resolution: ") + resolutionLabel;
    if (ImGui::BeginMenu(resolutionMenuLabel.c_str())) {
        for (const auto& resolution : kResolutions) {
            const bool selected = std::fabs(resolution.scale - g_resolutionScale) < 0.001f;
            const bool disabled = IsHighFrameRateMode() && IsHighResolutionScale(resolution.scale);
            ImGui::BeginDisabled(disabled);
            const bool clicked = ImGui::MenuItem(resolution.label, nullptr, selected);
            ImGui::EndDisabled();
            if (clicked) {
                SetResolutionScale(resolution.scale);
            }
        }
        ImGui::EndMenu();
    }

    if (ImGui::BeginMenu("Graphics")) {
        DrawGraphicsSettings();
        ImGui::EndMenu();
    }

    if (ImGui::BeginMenu("Controller settings")) {
        DrawControllerSettings();
        ImGui::EndMenu();
    }

    const std::string audioLabel = g_audioMuted
        ? "Audio: Muted"
        : "Audio: " + std::to_string(g_audioVolumePercent) + "%";
    // Keep the popup ID stable while the Master slider changes the visible
    // label. Without the ### suffix, ImGui treats every new percentage as a
    // different menu and closes the popup on the first drag update.
    const std::string audioMenuLabel = audioLabel + "###AudioSettingsMenu";
    if (ImGui::BeginMenu(audioMenuLabel.c_str())) {
        DrawAudioSettings();
        ImGui::EndMenu();
    }

    const float hideWidth = ImGui::CalcTextSize("Hide (F10)").x + ImGui::GetStyle().FramePadding.x * 2.0f;
    ImGui::SetCursorPosX(std::max(ImGui::GetCursorPosX(), ImGui::GetWindowWidth() - hideWidth - 8.0f));
    if (ImGui::MenuItem("Hide (F10)")) {
        SetTopBarVisible(false);
    }
    ImGui::EndMainMenuBar();
}

bool IsToggleKey(const SDL_Event& event, SDL_Scancode code) {
    return event.type == SDL_EVENT_KEY_DOWN && !event.key.repeat && event.key.scancode == code;
}

bool IsMouseActivity(const SDL_Event& event) {
    switch (event.type) {
    case SDL_EVENT_MOUSE_MOTION:
    case SDL_EVENT_MOUSE_BUTTON_DOWN:
    case SDL_EVENT_MOUSE_BUTTON_UP:
    case SDL_EVENT_MOUSE_WHEEL:
        return true;
    default:
        return false;
    }
}

// Runs on the thread that pumps SDL events (the same one that calls Draw), so
// the SDL cursor calls are safe here on platforms that have a real cursor to hide.
//
// Android has none - there is no mouse pointer on a touchscreen - and SDL_HideCursor()/
// SDL_ShowCursor() there route through Android_JNI_SetCustomCursor -> SDLActivity.setCustomCursor
// -> View.setPointerIcon -> a Binder call to IWindowSession.updatePointerIcon. Confirmed directly
// on-device via a real Android debuggerd tombstone (our own SIGABRT interception normally hides
// this - see main.cpp's AbortSignalHandler): that JNI chain aborted with "JNI DETECTED ERROR IN
// APPLICATION: JNI ERROR (app bug): jstring is an invalid JNI transition frame reference", called
// from settings_overlay::Draw() on the guest fiber thread - a call with no purpose on a device
// with no cursor, so skipping it here removes both the pointless work and the crash.
void UpdateCursorAutoHide() {
#if defined(__ANDROID__)
    return;
#else
    const bool shouldHide =
        !g_topBarVisible && Clock::now() - g_lastMouseActivity >= kCursorAutoHideDelay;
    if (shouldHide == g_cursorHidden) {
        return;
    }
    g_cursorHidden = shouldHide;
    if (shouldHide) {
        SDL_HideCursor();
    } else {
        SDL_ShowCursor();
    }
#endif
}

// Alt+Enter toggles the display mode inside aurora without going through the
// F10 combo, so the active mode is compared against the last persisted one
// every frame and written back on change.
void PersistDisplayModeIfChanged() {
    const int active = static_cast<int>(aurora_get_display_mode());
    if (active == g_displayMode) {
        return;
    }
    g_displayMode = active;
    RuntimeConfigFile::SetDisplayMode(std::string(kDisplayModeConfigNames[static_cast<size_t>(active)]));
}
} // namespace

void InitializeRuntimeSettings() noexcept {
#if defined(__ANDROID__)
    // The g_* globals above were initialized at static-init time, which on Android runs
    // before Kotlin's JNI call to SetAndroidFilesDir() - so RuntimeConfigFile::ApplicationDataDirectory()
    // still resolved to nothing back then, and every read silently fell back to its hardcoded
    // default (this is why persisted settings like display mode "didn't save": they were never
    // actually being read back, on any launch). SetAndroidFilesDir() has definitely run by the
    // time SDL_main calls this function, so re-read everything from the real config now.
    g_resolutionScale = RuntimeConfigFile::ResolutionMultiplier(1.0f);
    g_audioVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::AudioVolume(1.0f) * 100.0f));
    g_musicVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::MusicVolume(1.0f) * 100.0f));
    g_soundEffectsVolumePercent =
        static_cast<int>(std::lround(RuntimeConfigFile::SoundEffectsVolume(1.0f) * 100.0f));
    g_uiVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::UiVolume(1.0f) * 100.0f));
    g_voicesVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::VoicesVolume(1.0f) * 100.0f));
    g_audioMuted = RuntimeConfigFile::AudioMuted(false);
    g_audioMixWorker = RuntimeConfigFile::AudioMixWorkerEnabled(true);
    // Forced off, not just hidden - see DrawAudioSettings' matching #if for why. A player who had
    // this on before it was disabled here shouldn't stay stuck silently ducked (or silently NOT
    // ducked in a way they can no longer see/control) with no visible toggle to fix it.
    g_attenuateMusicWhenMediaPlays = false;
    g_frameInterpolationMode = [] {
        switch (RuntimeConfigFile::FrameInterpolationFps(0)) {
        case 120:
            return 1;
        case 180:
            return 2;
        default:
            return 0;
        }
    }();
    g_displayMode = ParseDisplayModeConfig(RuntimeConfigFile::DisplayMode("borderless"));
    g_skipUnreadyPipelines = RuntimeConfigFile::SkipUnreadyPipelines(true);
    g_disableCopyFilter = RuntimeConfigFile::DisableCopyFilter(true);
    g_showFps = RuntimeConfigFile::ShowFps(true);
    g_disabledPostProcessingPaths = RuntimeConfigFile::DisabledPostProcessingPaths(0);
#endif
    controller_mapping_wizard::LoadPersistedMappings();
    ApplyConfiguredMappings();
    AudioBackend::Instance().SetMasterVolume(static_cast<float>(g_audioVolumePercent) / 100.0f);
    AudioBackend::Instance().SetMuted(g_audioMuted);
    AxDspHle::SetMixWorkerEnabled(g_audioMixWorker);
    MusicAttenuation::SetMusicVolume(static_cast<float>(g_musicVolumePercent) / 100.0f);
    MusicAttenuation::SetSoundEffectsVolume(static_cast<float>(g_soundEffectsVolumePercent) / 100.0f);
    MusicAttenuation::SetUiVolume(static_cast<float>(g_uiVolumePercent) / 100.0f);
    MusicAttenuation::SetVoicesVolume(static_cast<float>(g_voicesVolumePercent) / 100.0f);
    MusicAttenuation::SetEnabled(g_attenuateMusicWhenMediaPlays);
    RuntimeGameGraphicsOptions::SetDisabledPostProcessingPaths(g_disabledPostProcessingPaths);
    const uint32_t targetFps = kFrameInterpolationTargetFps[static_cast<size_t>(g_frameInterpolationMode)];
    LimitResolutionForFrameRate();
    aurora_set_frame_interpolation_fps(targetFps);
    aurora_set_display_mode(static_cast<AuroraDisplayMode>(g_displayMode));
    g_displayMode = static_cast<int>(aurora_get_display_mode());
    aurora_set_disable_copy_filter(g_disableCopyFilter);
    aurora_set_skip_unready_pipelines(g_skipUnreadyPipelines);
    g_strapInputAccepted.store(false, std::memory_order_relaxed);
    g_startupDismissFrame.store(UINT64_MAX, std::memory_order_relaxed);
    PADBlockInput(g_topBarVisible);
}

void HandleEvents(const AuroraEvent* events) noexcept {
    if (!events) {
        return;
    }
    for (const AuroraEvent* ev = events; ev->type != AURORA_NONE; ++ev) {
        if (ev->type == AURORA_CONTROLLER_ADDED || ev->type == AURORA_CONTROLLER_REMOVED) {
            g_configuredControllerIndices.fill(std::numeric_limits<int32_t>::min());
#if defined(__ANDROID__)
            if (ev->type == AURORA_CONTROLLER_ADDED) {
                AutoConfigureNewAndroidControllersIfPresent();
            }
#endif
        }
        if (ev->type != AURORA_SDL_EVENT) {
            continue;
        }
        controller_mapping_wizard::HandleSdlEvent(ev->sdl);
        if (IsToggleKey(ev->sdl, SDL_SCANCODE_F10)) {
            SetTopBarVisible(!g_topBarVisible);
        }
        if (IsMouseActivity(ev->sdl)) {
            g_lastMouseActivity = Clock::now();
        }
    }
}

void Draw() noexcept {
    // Wait for the frame worker's DONE phase: it has replayed the previous frame's ImGui draw lists
    // and started the next ImGui frame, so all overlay callers can now safely issue ImGui commands.
    aurora_wait_for_frame_worker();
    ApplyConfiguredMappings();
    PersistDisplayModeIfChanged();
    UpdateCursorAutoHide();
    if (!StartupScreenVisible()) {
        DrawShaderCompilationStatus();
    }
    DrawFpsOverlay();
#if defined(__ANDROID__)
    DrawAndroidSidebar();
#else
    DrawTopBar();
#endif
    controller_mapping_wizard::Draw();
    // The wizard captures raw presses; keep them out of the game even when the
    // top bar is hidden mid-setup.
    PADBlockInput(g_topBarVisible || controller_mapping_wizard::IsActive());
    DrawStartupScreen();
}

bool StartupScreenVisible() noexcept {
    return !g_strapInputAccepted.load(std::memory_order_acquire) ||
           g_presentedFrame < g_startupDismissFrame.load(std::memory_order_relaxed);
}

void ToggleTopBar() noexcept {
    SetTopBarVisible(!g_topBarVisible);
}

void NotifyStrapInputAccepted() noexcept {
    bool expected = false;
    if (g_strapInputAccepted.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        g_startupDismissFrame.store(g_presentedFrame + kStrapTransitionCoverFrames,
                                    std::memory_order_release);
    }
}

void AdvancePresentedFrame() noexcept { ++g_presentedFrame; }
} // namespace settings_overlay
