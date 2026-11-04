#include "settings_overlay.h"
#if defined(__ANDROID__)
#include "android_touch_overlay_bridge.h"
#endif
#include "audio_backend.h"
#include "boot_banner.h"
#include "controller_mapping_wizard.h"
#include "game_graphics_options.h"
#include "music_attenuation.h"
#include "music_player.h"
#include "thermal_quality.h"
#include "runtime_config.h"
#include "wii_remote_input.h"
#include "i18n.h"
#include "runtime_log.h"
#include "runtime_product.h"

#include <dolphin/pad.h>
#include <imgui.h>
#include <imgui_internal.h>
#include <SDL3/SDL_events.h>
#include <SDL3/SDL_gamepad.h>
#include <SDL3/SDL_keyboard.h>
#include <SDL3/SDL_mouse.h>
#include <SDL3/SDL_scancode.h>
#include <SDL3/SDL_timer.h>

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
#include <cstring>
#include <functional>
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
#include <dolphin/gx/GXAurora.h>

extern "C" int g_gxFrameCount;
// dynamic_aspect.cpp
void RequestMkwAspectMode(bool widescreen);

// Defined in runtime/src/hle/audio/ax_mix.cpp. That header is private to the HLE
// directory and is not on this target's include path.
namespace AxDspHle {
void SetMixWorkerEnabled(bool enabled);
}

void RuntimeSetLowDetailComputerKarts(bool enabled) noexcept;  // hle/low_detail_karts.cpp
void RuntimeSetThirtyFpsRaces(bool enabled) noexcept;          // hle/frame_rate_lock.cpp

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
    case BACKEND_DEKO3D: return "deko3d";
    case BACKEND_AUTO: return "Automatic";
    }
    return "Unknown";
}

bool g_topBarVisible = false;
// The quick panel (2026-10-27): the everyday switches in a strip at the top, without the full menu.
bool g_quickPanelVisible = false;
bool g_quickPanelWasVisible = false;
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
#if defined(__ANDROID__)
constexpr const char* kMediaMonitorWaitingText = "Waiting for media playback detection...";
constexpr const char* kMediaMonitorUnavailableText = "Media playback detection is unavailable.";
#else
constexpr const char* kMediaMonitorWaitingText = "Waiting for Windows Media Control...";
constexpr const char* kMediaMonitorUnavailableText = "Windows Media Control is unavailable.";
#endif
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
bool g_constantMatrixIndexing = RuntimeConfigFile::ConstantMatrixIndexing(false);
// Computer karts load the game's own split-screen models in single-player races: far fewer draws
// and no driver animation per kart. On by default on Switch, where a full race is CPU-bound.
#if defined(__SWITCH__)
constexpr bool kLowDetailComputerKartsDefault = true;
#else
constexpr bool kLowDetailComputerKartsDefault = false;
#endif
bool g_lowDetailComputerKarts = RuntimeConfigFile::LowDetailComputerKarts(kLowDetailComputerKartsDefault);
bool g_thirtyFpsRaces = RuntimeConfigFile::ThirtyFpsRaces(false);

// video.vertex_repack: the Adreno 8xx "vertex explosion" workaround. Fixed for the process once
// rendering starts, so the settings entry only takes effect on the next launch.
constexpr std::array<const char*, 4> kVertexRepackConfigValues{"auto", "off", "characters", "all"};
int ParseVertexRepackConfig(const std::string& value) {
    for (size_t i = 0; i < kVertexRepackConfigValues.size(); ++i) {
        if (value == kVertexRepackConfigValues[i]) {
            return static_cast<int>(i);
        }
    }
    return 0;
}
int g_vertexRepackChoice = ParseVertexRepackConfig(RuntimeConfigFile::VertexRepack("auto"));

AuroraVertexRepackMode VertexRepackModeForChoice(int choice) {
    switch (choice) {
    case 1:
        return AURORA_VERTEX_REPACK_OFF;
    case 2:
        return AURORA_VERTEX_REPACK_CHARACTERS;
    case 3:
        return AURORA_VERTEX_REPACK_ALL;
    default:
        // Repacking every draw is what fixes tracks and menus too and is confirmed good in the
        // original game. In Retro Rewind it made other karts and item boxes vanish for one
        // Adreno 840 tester, so automatic stays with the character-only form there.
        return RuntimeProduct::IsRetroRewind() ? AURORA_VERTEX_REPACK_AUTO_CHARACTERS
                                               : AURORA_VERTEX_REPACK_AUTO_ALL;
    }
}
#if defined(__SWITCH__)
// On by default on Switch: measured 18 -> 22 FPS in a 12-kart race at stock clock.
bool g_threadedGx = RuntimeConfigFile::ThreadedGx(true);
#else
bool g_threadedGx = RuntimeConfigFile::ThreadedGx(false);
#endif
bool g_thermalAutoQuality = RuntimeConfigFile::ThermalAutoQuality(false);
bool g_showFps = RuntimeConfigFile::ShowFps(true);
bool g_showShaderCompilation = RuntimeConfigFile::ShowShaderCompilation(true);
bool g_hideSettingsButton = RuntimeConfigFile::HideSettingsButton(false);
bool g_widescreen = RuntimeConfigFile::WidescreenEnabled(true);
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
    // GameCube buttons, with what each does in Mario Kart Wii (GitHub issue #15 asked for clearer names).
    {"a", TRN("A (accelerate, OK)"), PAD_BUTTON_A},
    {"b", TRN("B (brake, back)"), PAD_BUTTON_B},
    {"x", TRN("X"), PAD_BUTTON_X},
    {"y", TRN("Y"), PAD_BUTTON_Y},
    {"start", TRN("Start (pause)"), PAD_BUTTON_START},
    {"z", TRN("Z"), PAD_TRIGGER_Z},
    {"l", TRN("L (use item)"), PAD_TRIGGER_L},
    {"r", TRN("R (drift, hop)"), PAD_TRIGGER_R},
    {"up", TRN("D-pad Up (trick)"), PAD_BUTTON_UP},
    {"down", TRN("D-pad Down"), PAD_BUTTON_DOWN},
    {"left", TRN("D-pad Left"), PAD_BUTTON_LEFT},
    {"right", TRN("D-pad Right"), PAD_BUTTON_RIGHT},
}};

struct NativeButtonItem {
    const char* configName;
    const char* label;
    uint32_t nativeButton;
};

constexpr std::array<NativeButtonItem, SDL_GAMEPAD_BUTTON_COUNT + 3> kNativeButtons = {{
    {"unmapped", TRN("Nothing (L/R: the analog trigger)"), PAD_NATIVE_BUTTON_INVALID},
    {"left_trigger", TRN("Left trigger (L2 / ZL)"), PAD_NATIVE_BUTTON_LEFT_TRIGGER},
    {"right_trigger", TRN("Right trigger (R2 / ZR)"), PAD_NATIVE_BUTTON_RIGHT_TRIGGER},
    {"south", TRN("South (A / Cross)"), SDL_GAMEPAD_BUTTON_SOUTH},
    {"east", TRN("East (B / Circle)"), SDL_GAMEPAD_BUTTON_EAST},
    {"west", TRN("West (X / Square)"), SDL_GAMEPAD_BUTTON_WEST},
    {"north", TRN("North (Y / Triangle)"), SDL_GAMEPAD_BUTTON_NORTH},
    {"back", TRN("Back / Select"), SDL_GAMEPAD_BUTTON_BACK},
    {"guide", TRN("Guide / Home"), SDL_GAMEPAD_BUTTON_GUIDE},
    {"start", TRN("Start / Options"), SDL_GAMEPAD_BUTTON_START},
    {"left_stick", TRN("Left stick click"), SDL_GAMEPAD_BUTTON_LEFT_STICK},
    {"right_stick", TRN("Right stick click"), SDL_GAMEPAD_BUTTON_RIGHT_STICK},
    {"left_shoulder", TRN("Left shoulder"), SDL_GAMEPAD_BUTTON_LEFT_SHOULDER},
    {"right_shoulder", TRN("Right shoulder"), SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER},
    {"dpad_up", TRN("D-pad Up"), SDL_GAMEPAD_BUTTON_DPAD_UP},
    {"dpad_down", TRN("D-pad Down"), SDL_GAMEPAD_BUTTON_DPAD_DOWN},
    {"dpad_left", TRN("D-pad Left"), SDL_GAMEPAD_BUTTON_DPAD_LEFT},
    {"dpad_right", TRN("D-pad Right"), SDL_GAMEPAD_BUTTON_DPAD_RIGHT},
    {"misc1", TRN("Misc 1 / Share"), SDL_GAMEPAD_BUTTON_MISC1},
    {"right_paddle1", TRN("Right paddle 1"), SDL_GAMEPAD_BUTTON_RIGHT_PADDLE1},
    {"left_paddle1", TRN("Left paddle 1"), SDL_GAMEPAD_BUTTON_LEFT_PADDLE1},
    {"right_paddle2", TRN("Right paddle 2"), SDL_GAMEPAD_BUTTON_RIGHT_PADDLE2},
    {"left_paddle2", TRN("Left paddle 2"), SDL_GAMEPAD_BUTTON_LEFT_PADDLE2},
    {"touchpad", TRN("Touchpad"), SDL_GAMEPAD_BUTTON_TOUCHPAD},
    {"misc2", TRN("Misc 2"), SDL_GAMEPAD_BUTTON_MISC2},
    {"misc3", TRN("Misc 3 / GC L click"), SDL_GAMEPAD_BUTTON_MISC3},
    {"misc4", TRN("Misc 4 / GC R click"), SDL_GAMEPAD_BUTTON_MISC4},
    {"misc5", TRN("Misc 5"), SDL_GAMEPAD_BUTTON_MISC5},
    {"misc6", TRN("Misc 6"), SDL_GAMEPAD_BUTTON_MISC6},
}};

// Classic Controller Pro layout, indexed like kControllerButtons: the SNES-style
// diamond (A right, B bottom, X top, Y left) with digital bumpers driving the GC
// triggers and Z on Back/Select (the same home the NSO GC default gives it). A value
// "first,second" binds both (the same form the button list saves).
//
// On Switch the console's buttons reach SDL by label, not position (A is "south", see
// aurora-main/lib/switch/input_switch.cpp), so the positional names here swapped A with B and X
// with Y there ("the pro controller preset swaps a and b", 2026-10-27). The Switch's L/R are
// second bindings next to ZL/ZR there: with L on the bumper only, ZL and ZR did nothing.
constexpr std::array<const char*, PAD_BUTTON_COUNT> kClassicProPreset = {
#if defined(__SWITCH__)
    "south",          // A
    "east",           // B
    "west",           // X
    "north",          // Y
    "start",          // Start
    "back",           // Z
    "left_trigger,left_shoulder",    // L: ZL or L
    "right_trigger,right_shoulder",  // R: ZR or R
#else
    "east",           // A
    "south",          // B
    "north",          // X
    "west",           // Y
    "start",          // Start
    "back",           // Z
    "left_shoulder",  // L
    "right_shoulder", // R
#endif
    "dpad_up", "dpad_down", "dpad_left", "dpad_right",
};

#if defined(__SWITCH__)
// GameCube layout on Switch: aurora's default (L/R the analog ZL/ZR) plus the bumpers as second
// bindings, the same as a first start (ApplyConfiguredMappings). The plain default left L doing
// nothing and R on Z, undoing "i want the L and R button to do the same as ZL and ZR".
constexpr std::array<const char*, PAD_BUTTON_COUNT> kGameCubeSwitchPreset = {
    "south", "east", "west", "north", "start",
    "unmapped",                  // Z (its bumper is R's now; the game looks behind on X too)
    "unmapped,left_shoulder",    // L: ZL or L
    "unmapped,right_shoulder",   // R: ZR or R
    "dpad_up", "dpad_down", "dpad_left", "dpad_right",
};
#endif

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
// The cover stays up until the warning screen's scene has really gone (NotifyBootSceneExited).
// Counting frames from the accepted input alone lifted it early on slow starts - the Switch showed
// the warning screen stuttering through its fade. The long count is only a fallback.
constexpr uint64_t kStrapTransitionCoverFrames = 600;
constexpr uint64_t kSceneExitCoverFrames = 6;
std::atomic_bool g_bootSceneExited = false;
// The same two limits in wall time. Frames alone kept the cover up far too long whenever the first
// frames after the warning screen were slow (reported on Switch: the intro's sound started under
// the splash and the intro then appeared part-way through), and the 600-frame fallback is half a
// minute at 20 FPS.
constexpr int64_t kStrapTransitionCoverMs = 4000;
constexpr int64_t kSceneExitCoverMs = 150;
std::atomic_int64_t g_startupDismissAtMs = INT64_MAX;

int64_t StartupClockMs() noexcept {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now().time_since_epoch()).count();
}

// Nothing below 1x: the renderer cannot draw smaller than the game's own framebuffer, and the
// old 0.5x/0.75x entries crashed on the way into a race.
constexpr std::array<ResolutionItem, 8> kResolutions = {{
    {"Auto (window size)", 0.0f},
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

// Binds a preset (indexed like kControllerButtons) on a port and saves it to Config.toml.
void ApplyPreset(uint32_t port, const std::array<const char*, PAD_BUTTON_COUNT>& preset) {
    for (size_t i = 0; i < kControllerButtons.size(); ++i) {
        const ControllerBindingPair binding = SplitControllerBinding(preset[i]);
        const NativeButtonItem* primary = FindNativeButton(binding.primary);
        if (primary == nullptr) {
            continue;
        }
        const NativeButtonItem* secondary = binding.secondary.empty() ? nullptr : FindNativeButton(binding.secondary);
        PADSetButtonMapping(port, PADButtonMapping{primary->nativeButton, kControllerButtons[i].padButton});
        PADSetAltButtonMapping(port, PADButtonMapping{secondary != nullptr ? secondary->nativeButton
                                                                           : PAD_NATIVE_BUTTON_INVALID,
                                                      kControllerButtons[i].padButton});
        RuntimeConfigFile::SetControllerButton(i, preset[i]);
    }
}

const NativeButtonItem& NativeButtonForValue(uint32_t nativeButton) {
    const auto it = std::find_if(kNativeButtons.begin(), kNativeButtons.end(), [&](const NativeButtonItem& item) {
        return nativeButton == item.nativeButton;
    });
    return it == kNativeButtons.end() ? kNativeButtons.front() : *it;
}

#if defined(__SWITCH__)
extern "C" void AuroraSwitchSetMenuOpen(int open);  // aurora lib/switch/input_switch.cpp
#endif

void SetTopBarVisible(bool visible) {
    if (g_topBarVisible == visible) {
        return;
    }
    g_topBarVisible = visible;
    if (visible) {
        g_quickPanelVisible = false;  // the full menu replaces it
    }
    PADBlockInput(visible);
#if defined(__SWITCH__)
    AuroraSwitchSetMenuOpen(visible ? 1 : 0);  // tilt steering stays out of the menu's navigation
#endif
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
#if defined(__SWITCH__)
        // Requested directly: "i want the L and R button to do the same as ZL and ZR, use item and
        // well, drift". aurora's default puts GC L/R on the analog triggers (ZL/ZR) only and Z on
        // the right bumper, so the bumpers join as second bindings and Z gives its bumper up (the
        // game also looks behind on X). A binding saved in Config.toml still wins below.
        for (const uint16_t padButton : {PAD_TRIGGER_Z, PAD_TRIGGER_L, PAD_TRIGGER_R}) {
            const auto it = std::find_if(kControllerButtons.begin(), kControllerButtons.end(),
                                         [&](const auto& item) { return item.padButton == padButton; });
            if (it == kControllerButtons.end() ||
                RuntimeConfigFile::ControllerButton(static_cast<size_t>(it - kControllerButtons.begin()))) {
                continue;
            }
            if (padButton == PAD_TRIGGER_Z) {
                PADSetButtonMapping(port, PADButtonMapping{PAD_NATIVE_BUTTON_INVALID, padButton});
            } else {
                PADSetAltButtonMapping(
                    port, PADButtonMapping{static_cast<uint32_t>(padButton == PAD_TRIGGER_L
                                                                     ? SDL_GAMEPAD_BUTTON_LEFT_SHOULDER
                                                                     : SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER),
                                           padButton});
            }
        }
#endif
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

// Settings widgets (remaster, 2026-10-24): finger-sized rows that read the same on a phone, a TV
// and a PC. The user found the old panel cramped and PC-like; the approved look is plain solid
// colours (no gradients or glow), a switch on the right of each on/off row, and sliders and
// lists full width under their label. Same signatures as the ImGui calls they replace, so the
// pages below only changed names.
namespace ui {

constexpr ImVec4 kPanel{0.078f, 0.102f, 0.200f, 1.0f};    // #141A33
constexpr ImVec4 kRow{0.106f, 0.133f, 0.259f, 1.0f};       // #1B2242
constexpr ImVec4 kRowHover{0.133f, 0.169f, 0.333f, 1.0f};
constexpr ImVec4 kRowActive{0.165f, 0.212f, 0.400f, 1.0f};
constexpr ImVec4 kLine{0.165f, 0.200f, 0.376f, 1.0f};       // #2A3360
constexpr ImVec4 kText{0.933f, 0.945f, 0.980f, 1.0f};
constexpr ImVec4 kMuted{0.604f, 0.639f, 0.780f, 1.0f};      // #9AA3C7
constexpr ImVec4 kAccent{0.498f, 0.851f, 0.341f, 1.0f};     // #7FD957
constexpr ImVec4 kFocus{0.247f, 0.816f, 0.941f, 1.0f};      // #3FD0F0
constexpr ImVec4 kTrackOff{0.200f, 0.224f, 0.369f, 1.0f};

// The part of an ImGui label before "##".
const char* LabelEnd(const char* label) {
    const char* hashes = std::strstr(label, "##");
    return hashes != nullptr ? hashes : label + std::strlen(label);
}

// The visible text of a widget label (before any "##id"), translated. The label itself stays the ImGui
// ID, so widget IDs do not change with the language.
std::string Visible(const char* label) {
    const std::string english(label, LabelEnd(label));
    return i18n::Tr(english.c_str());
}

// Rows fill the panel; inside a PC menu, which sizes itself to its contents, they keep a width.
float RowWidth() {
    const float available = ImGui::GetContentRegionAvail().x;
    return available > ImGui::GetFontSize() * 12.0f ? available : ImGui::GetFontSize() * 26.0f;
}

void Label(const char* label) {
    const char* end = LabelEnd(label);
    if (end != label) {
        const std::string shown = Visible(label);
        ImGui::PushTextWrapPos(ImGui::GetCursorPosX() + RowWidth());
        ImGui::TextUnformatted(shown.c_str(), shown.c_str() + shown.size());
        ImGui::PopTextWrapPos();
    }
}

bool Toggle(const char* label, bool* value) {
    const float fs = ImGui::GetFontSize();
    const float width = RowWidth();
    const float pad = fs * 0.9f;
    const ImVec2 track(fs * 2.6f, fs * 1.5f);
    const std::string shown = Visible(label);
    const float wrap = width - track.x - pad * 3.0f;
    const ImVec2 text = ImGui::CalcTextSize(shown.c_str(), shown.c_str() + shown.size(), false, wrap);
    const float height = std::max(fs * 3.0f, text.y + fs * 1.4f);
    // EnableNav: an InvisibleButton is skipped by controller navigation without it, so on Switch
    // the D-pad jumped over every switch in the menu (Shake to trick could not be reached).
    const bool pressed = ImGui::InvisibleButton(label, ImVec2(width, height), ImGuiButtonFlags_EnableNav);
    if (pressed) {
        *value = !*value;
    }
    const ImVec2 min = ImGui::GetItemRectMin();
    const ImVec2 max = ImGui::GetItemRectMax();
    ImDrawList* draw = ImGui::GetWindowDrawList();
    const ImVec4 bg = ImGui::IsItemActive()                               ? kRowActive
                      : ImGui::IsItemHovered() || ImGui::IsItemFocused() ? kRowHover
                                                                          : kRow;
    draw->AddRectFilled(min, max, ImGui::GetColorU32(bg), fs * 0.6f);
    draw->AddText(ImGui::GetFont(), fs, ImVec2(min.x + pad, (min.y + max.y - text.y) * 0.5f),
                  ImGui::GetColorU32(ImGuiCol_Text), shown.c_str(), shown.c_str() + shown.size(), wrap);
    const ImVec2 trackMin(max.x - pad - track.x, (min.y + max.y - track.y) * 0.5f);
    const ImVec2 trackMax(trackMin.x + track.x, trackMin.y + track.y);
    draw->AddRectFilled(trackMin, trackMax, ImGui::GetColorU32(*value ? kAccent : kTrackOff), track.y * 0.5f);
    const float knob = track.y * 0.5f - fs * 0.2f;
    const float knobX = *value ? trackMax.x - track.y * 0.5f : trackMin.x + track.y * 0.5f;
    draw->AddCircleFilled(ImVec2(knobX, (trackMin.y + trackMax.y) * 0.5f), knob,
                          ImGui::GetColorU32(*value ? ImVec4(1.0f, 1.0f, 1.0f, 1.0f) : ImVec4(0.78f, 0.80f, 0.90f, 1.0f)));
    return pressed;
}

// A slider row: the label with the value at its right, then a thin track filled up to a round knob.
// The ImGui slider underneath is drawn invisible and still does the dragging, the controller steps
// and the focus frame.
bool SliderBody(const char* label, const char* valueText, float fraction, const std::function<bool()>& slider) {
    const float fs = ImGui::GetFontSize();
    const float width = RowWidth();
    const std::string shown = Visible(label);
    const float startX = ImGui::GetCursorPosX();
    ImGui::TextUnformatted(shown.c_str(), shown.c_str() + shown.size());
    ImGui::SameLine(startX + width - ImGui::CalcTextSize(valueText).x);
    ImGui::TextUnformatted(valueText);
    const ImVec4 clear(0.0f, 0.0f, 0.0f, 0.0f);
    for (const ImGuiCol col : {ImGuiCol_FrameBg, ImGuiCol_FrameBgHovered, ImGuiCol_FrameBgActive, ImGuiCol_SliderGrab,
                               ImGuiCol_SliderGrabActive, ImGuiCol_Text}) {
        ImGui::PushStyleColor(col, clear);
    }
    ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(fs * 0.8f, fs * 0.75f));
    ImGui::SetNextItemWidth(width);
    ImGui::PushID(label);
    const bool changed = slider();
    ImGui::PopID();
    ImGui::PopStyleVar();
    ImGui::PopStyleColor(6);
    const ImVec2 min = ImGui::GetItemRectMin();
    const ImVec2 max = ImGui::GetItemRectMax();
    const float knob = fs * 0.75f;
    const float left = min.x + knob;
    const float right = max.x - knob;
    const float y = (min.y + max.y) * 0.5f;
    const float x = left + (right - left) * std::clamp(fraction, 0.0f, 1.0f);
    ImDrawList* draw = ImGui::GetWindowDrawList();
    const float half = fs * 0.18f;
    draw->AddRectFilled(ImVec2(left, y - half), ImVec2(right, y + half), ImGui::GetColorU32(kTrackOff), half);
    draw->AddRectFilled(ImVec2(left, y - half), ImVec2(x, y + half), ImGui::GetColorU32(kAccent), half);
    if (ImGui::IsItemActive()) {
        draw->AddCircleFilled(ImVec2(x, y), knob * 1.25f, ImGui::GetColorU32(ImVec4(kAccent.x, kAccent.y, kAccent.z, 0.35f)));
    }
    draw->AddCircleFilled(ImVec2(x, y), knob, ImGui::GetColorU32(ImVec4(1.0f, 1.0f, 1.0f, 1.0f)));
    return changed;
}

bool SliderInt(const char* label, int* value, int min, int max, const char* format = "%d",
               ImGuiSliderFlags flags = 0) {
    if (LabelEnd(label) == label) {
        return ImGui::SliderInt(label, value, min, max, format, flags);  // caller placed it
    }
    char valueText[64];
    std::snprintf(valueText, sizeof(valueText), format, *value);
    const float fraction = max > min ? static_cast<float>(*value - min) / static_cast<float>(max - min) : 0.0f;
    return SliderBody(label, valueText, fraction,
                      [&] { return ImGui::SliderInt("##slider", value, min, max, format, flags); });
}

bool SliderFloat(const char* label, float* value, float min, float max, const char* format = "%.3f",
                 ImGuiSliderFlags flags = 0) {
    if (LabelEnd(label) == label) {
        return ImGui::SliderFloat(label, value, min, max, format, flags);
    }
    char valueText[64];
    std::snprintf(valueText, sizeof(valueText), format, *value);
    const float fraction = max > min ? (*value - min) / (max - min) : 0.0f;
    return SliderBody(label, valueText, fraction,
                      [&] { return ImGui::SliderFloat("##slider", value, min, max, format, flags); });
}

bool Combo(const char* label, int* current, const char* const items[], int count) {
    Label(label);
    ImGui::SetNextItemWidth(RowWidth());
    ImGui::PushID(label);
    struct Items {
        const char* const* list;
    } source{items};
    const bool changed = ImGui::Combo(
        "##combo", current,
        [](void* data, int index) -> const char* { return i18n::Tr(static_cast<Items*>(data)->list[index]); }, &source,
        count);
    ImGui::PopID();
    return changed;
}

bool BeginCombo(const char* label, const char* preview) {
    Label(label);
    ImGui::SetNextItemWidth(RowWidth());
    const std::string id = std::string("##") + label;
    return ImGui::BeginCombo(id.c_str(), preview);
}

void Section(const char* label) {
    ImGui::Dummy(ImVec2(0.0f, ImGui::GetFontSize() * 0.4f));
    const std::string upper = i18n::Upper(Visible(label));
    ImGui::PushStyleColor(ImGuiCol_Text, kFocus);
    ImGui::TextUnformatted(upper.c_str());
    ImGui::PopStyleColor();
}

// A row that opens something (a dialog, the layout editor): label, optional detail, and "›".
bool LinkRow(const char* label, const char* detail = nullptr) {
    const float fs = ImGui::GetFontSize();
    const float width = RowWidth();
    const float pad = fs * 0.9f;
    const std::string shown = Visible(label);
    const std::string shownDetail = detail != nullptr ? std::string(i18n::Tr(detail)) : std::string();
    const float height = detail != nullptr ? fs * 3.6f : fs * 3.0f;
    const bool pressed = ImGui::InvisibleButton(label, ImVec2(width, height), ImGuiButtonFlags_EnableNav);
    const ImVec2 min = ImGui::GetItemRectMin();
    const ImVec2 max = ImGui::GetItemRectMax();
    ImDrawList* draw = ImGui::GetWindowDrawList();
    const ImVec4 bg = ImGui::IsItemActive()                               ? kRowActive
                      : ImGui::IsItemHovered() || ImGui::IsItemFocused() ? kRowHover
                                                                          : kRow;
    draw->AddRectFilled(min, max, ImGui::GetColorU32(bg), fs * 0.6f);
    const float textTop = detail != nullptr ? min.y + fs * 0.6f : (min.y + max.y - fs) * 0.5f;
    draw->AddText(ImVec2(min.x + pad, textTop), ImGui::GetColorU32(ImGuiCol_Text), shown.c_str(), shown.c_str() + shown.size());
    if (detail != nullptr) {
        draw->AddText(ImGui::GetFont(), fs * 0.85f, ImVec2(min.x + pad, textTop + fs * 1.25f),
                      ImGui::GetColorU32(kMuted), shownDetail.c_str());
    }
    draw->AddText(ImVec2(max.x - pad - ImGui::CalcTextSize(">").x, (min.y + max.y - fs) * 0.5f),
                  ImGui::GetColorU32(kMuted), ">");
    return pressed;
}

// A button whose label is translated; the ImGui ID follows the shown text.
bool Button(const char* label, const ImVec2& size = ImVec2(0.0f, 0.0f)) {
    return ImGui::Button(i18n::TrLabel(label).c_str(), size);
}

// The panel's colours and spacing, around everything it draws.
struct Theme {
    Theme() {
        const float fs = ImGui::GetFontSize();
        const std::pair<ImGuiCol, ImVec4> colors[] = {
            {ImGuiCol_WindowBg, kPanel},          {ImGuiCol_ChildBg, ImVec4(0, 0, 0, 0)},
            {ImGuiCol_PopupBg, kPanel},           {ImGuiCol_Border, kLine},
            {ImGuiCol_Text, kText},               {ImGuiCol_TextDisabled, kMuted},
            {ImGuiCol_FrameBg, kRow},             {ImGuiCol_FrameBgHovered, kRowHover},
            {ImGuiCol_FrameBgActive, kRowActive}, {ImGuiCol_Button, kRow},
            {ImGuiCol_ButtonHovered, kRowHover},  {ImGuiCol_ButtonActive, kRowActive},
            {ImGuiCol_Header, kRowHover},         {ImGuiCol_HeaderHovered, kRowActive},
            {ImGuiCol_HeaderActive, kRowActive},  {ImGuiCol_SliderGrab, kAccent},
            {ImGuiCol_SliderGrabActive, kFocus},  {ImGuiCol_CheckMark, kAccent},
            {ImGuiCol_Separator, kLine},          {ImGuiCol_NavCursor, kFocus},
            {ImGuiCol_ScrollbarBg, ImVec4(0, 0, 0, 0)}, {ImGuiCol_ScrollbarGrab, kLine},
        };
        for (const auto& [index, color] : colors) {
            ImGui::PushStyleColor(index, color);
        }
        colorCount = static_cast<int>(std::size(colors));
        ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(fs * 1.1f, fs * 0.9f));
        ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(fs * 0.8f, fs * 0.7f));
        ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing, ImVec2(fs * 0.5f, fs * 0.45f));
        ImGui::PushStyleVar(ImGuiStyleVar_FrameRounding, fs * 0.6f);
        ImGui::PushStyleVar(ImGuiStyleVar_GrabRounding, fs * 0.6f);
        ImGui::PushStyleVar(ImGuiStyleVar_GrabMinSize, fs * 1.6f);
        ImGui::PushStyleVar(ImGuiStyleVar_PopupRounding, fs * 0.6f);
        ImGui::PushStyleVar(ImGuiStyleVar_ScrollbarSize, fs * 0.35f);
        ImGui::PushStyleVar(ImGuiStyleVar_WindowBorderSize, 0.0f);
        varCount = 9;
    }
    ~Theme() {
        ImGui::PopStyleVar(varCount);
        ImGui::PopStyleColor(colorCount);
    }
    int colorCount = 0;
    int varCount = 0;
};

} // namespace ui

// Vibration, every platform: rumble.cfg holds "1" or "0" (missing = on), then the strength in
// percent, 0 to 200 (missing = 100; files from before the strength setting have only the first).
std::filesystem::path RumbleSettingPath() { return RuntimeConfigFile::ApplicationDataDirectory() / "rumble.cfg"; }

void LoadRumbleSetting() {
    int enabled = 1;
    int strength = 100;
    if (FILE* file = std::fopen(RumbleSettingPath().string().c_str(), "rb")) {
        if (std::fscanf(file, "%d", &enabled) != 1) {
            enabled = 1;
        }
        if (std::fscanf(file, "%d", &strength) != 1) {
            strength = 100;
        }
        std::fclose(file);
    }
    PADSetRumbleEnabled(enabled != 0 ? TRUE : FALSE);
    PADSetRumbleStrength(static_cast<u32>(std::clamp(strength, 0, 200)));
}

void SaveRumbleSetting() {
    if (FILE* file = std::fopen(RumbleSettingPath().string().c_str(), "wb")) {
        std::fprintf(file, "%d %u\n", PADGetRumbleEnabled() != FALSE ? 1 : 0, PADGetRumbleStrength());
        std::fclose(file);
    }
}

void DrawRumbleSetting() {
    bool enabled = PADGetRumbleEnabled() != FALSE;
    if (ui::Toggle("Vibration", &enabled)) {
        PADSetRumbleEnabled(enabled ? TRUE : FALSE);
        SaveRumbleSetting();
    }
    // Adapted from KartPad 0.8.5's rumble strength. Phones without amplitude control vibrate at
    // their one strength.
    ImGui::BeginDisabled(!enabled);
    int strength = static_cast<int>(PADGetRumbleStrength());
    if (ui::SliderInt("Vibration strength", &strength, 0, 200, "%d%%")) {
        PADSetRumbleStrength(static_cast<u32>(strength));
    }
    if (ImGui::IsItemDeactivatedAfterEdit()) {
        SaveRumbleSetting();
    }
    ImGui::EndDisabled();
}

#if defined(__SWITCH__)
extern "C" const char* AuroraSwitchPlayerController(int player);  // aurora lib/switch/input_switch.cpp
extern "C" int AuroraSwitchPlayerPressing(int player);
extern "C" int AuroraSwitchShowControllerApplet();
} // namespace
} // namespace settings_overlay
// Global scope: GCC 15 gives an extern "C" variable declared in an unnamed namespace that namespace.
extern "C" const unsigned char kAuroraSwitchPlayerColors[4][3];
namespace settings_overlay {
namespace {

ImVec4 SwitchPlayerColor(int player) {
    return ImVec4(kAuroraSwitchPlayerColors[player][0] / 255.0f, kAuroraSwitchPlayerColors[player][1] / 255.0f,
                  kAuroraSwitchPlayerColors[player][2] / 255.0f, 1.0f);
}

// A round player badge ("1"-"4") in that player's colour.
void PlayerBadge(int player) {
    const float fs = ImGui::GetFontSize();
    const float size = fs * 1.6f;
    const ImVec2 min = ImGui::GetCursorScreenPos();
    ImGui::Dummy(ImVec2(size, size));
    char text[2] = {static_cast<char>('1' + player), 0};
    const ImVec2 textSize = ImGui::CalcTextSize(text);
    ImDrawList* draw = ImGui::GetWindowDrawList();
    draw->AddCircleFilled(ImVec2(min.x + size * 0.5f, min.y + size * 0.5f), size * 0.5f,
                          ImGui::GetColorU32(SwitchPlayerColor(player)));
    draw->AddText(ImVec2(min.x + (size - textSize.x) * 0.5f, min.y + (size - textSize.y) * 0.5f),
                  ImGui::GetColorU32(ImVec4(1.0f, 1.0f, 1.0f, 1.0f)), text);
}

// Who is which player: the console's own player numbers (the lights on each controller), each with
// a colour that the console's controller screen uses too. A row lights up while its controller
// holds a button, so everyone can find their own. Players join, swap or leave on the console's
// controller screen, the one games show; a dropdown here moved players off the port the game
// expected them on (2026-10-27).
void DrawSwitchPlayers() {
    ui::Section("Players");
    const float fs = ImGui::GetFontSize();
    const float width = ui::RowWidth();
    for (int player = 0; player < 4; ++player) {
        const char* controller = AuroraSwitchPlayerController(player);
        const bool pressing = controller != nullptr && AuroraSwitchPlayerPressing(player) != 0;
        const ImVec2 min = ImGui::GetCursorScreenPos();
        const float height = fs * 2.4f;
        ImDrawList* draw = ImGui::GetWindowDrawList();
        const ImVec4 color = SwitchPlayerColor(player);
        draw->AddRectFilled(min, ImVec2(min.x + width, min.y + height),
                            ImGui::GetColorU32(pressing ? ImVec4(color.x * 0.45f, color.y * 0.45f, color.z * 0.45f, 1.0f)
                                                        : ui::kRow),
                            fs * 0.6f);
        ImGui::SetCursorScreenPos(ImVec2(min.x + fs * 0.6f, min.y + (height - fs * 1.6f) * 0.5f));
        PlayerBadge(player);
        ImGui::SameLine(0.0f, fs * 0.6f);
        ImGui::SetCursorScreenPos(ImVec2(ImGui::GetCursorScreenPos().x, min.y + (height - fs) * 0.5f));
        if (controller != nullptr) {
            ImGui::Text(TR("Player %d   %s"), player + 1, TR(controller));
        } else {
            ImGui::TextDisabled(TR("Player %d   not connected"), player + 1);
        }
        ImGui::SetCursorScreenPos(ImVec2(min.x, min.y + height + fs * 0.3f));
        ImGui::Dummy(ImVec2(width, 0.0f));
    }
    ImGui::TextDisabled(TR("Press a button: your row lights up. The lights on each controller show its number too."));
    if (ui::LinkRow("Change controllers", "Add players, swap who is who, or change grip")) {
        AuroraSwitchShowControllerApplet();
    }
}
#endif

void DrawControllerSettings() {
#if defined(__SWITCH__)
    DrawSwitchPlayers();
    ui::Section("Buttons and vibration");
#endif
    DrawRumbleSetting();
#if defined(__ANDROID__)
    // Lives here (not under Graphics, where it was originally placed) since it's an input
    // setting, not a display one - confirmed directly that it wasn't found under Graphics. The
    // actual show/hide + persistence lives Kotlin-side (TouchControlsOverlay.kt) as a per-device
    // UI preference, same as its drag/resize/hide-per-button state - this checkbox is just the
    // settings-menu entry point into that, plus a live mirror so it displays the right state
    // (seeded via nativeSetTouchControlsVisibleCache, updated on every toggle here). Most useful
    // right after a real controller auto-connects and hides the touch overlay
    // (TouchControlsOverlay.setControllerConnected) but the player wants it back anyway.
    if (ui::Toggle("Touch controls", &g_androidTouchControlsVisibleCache)) {
        AndroidSetTouchOverlayVisible(g_androidTouchControlsVisibleCache);
    }
    // Requested feature (GitHub issue #4): holding an item behind you for protection while also
    // drifting/tricking is hard on touch controls since both need a thumb free. Double-tapping
    // the on-screen A button locks it "held" so that thumb frees up; double-tapping again
    // releases it. Off by default - opt-in, same reasoning as everything else in this section
    // being a per-device UI preference rather than a game setting. Actual detection/hold logic
    // lives in TouchButtonView.kt; this is just the settings-menu entry point plus a live mirror
    // of its current state (seeded via nativeSetDoubleTapAutoHoldCache).
    if (ui::Toggle("Double-tap A to auto-hold acceleration", &g_androidDoubleTapAutoHoldCache)) {
        AndroidSetDoubleTapAutoHold(g_androidDoubleTapAutoHoldCache);
    }
    ImGui::Separator();
#endif
    // Dropdowns, not radio buttons and a pop-out menu: the pop-out opened on top of the list
    // and read as a separate window on a phone.
    static const char* const kPlayerNames[] = {"Player 1", "Player 2", "Player 3", "Player 4"};
    static_assert(sizeof(kPlayerNames) / sizeof(kPlayerNames[0]) >= PAD_MAX_CONTROLLERS);
    ui::Combo("Player", &g_controllerPort, kPlayerNames, PAD_MAX_CONTROLLERS);

    controller_mapping_wizard::DrawSetupList();
    const uint32_t controllerCount = PADCount();
    if (controllerCount == 0) {
        ImGui::TextDisabled(TR("No controller connected"));
        return;
    }

#if !defined(__SWITCH__)
    // Not on Switch: players are the console's own player numbers there (DrawSwitchPlayers).
    const char* currentName = PADGetName(static_cast<uint32_t>(g_controllerPort));
    const s32 currentIndex = PADGetIndexForPort(static_cast<uint32_t>(g_controllerPort));
    if (ui::BeginCombo("Controller", currentName != nullptr ? currentName : "None")) {
        for (uint32_t index = 0; index < controllerCount; ++index) {
            const char* name = PADGetNameForControllerIndex(index);
            std::string label = name != nullptr ? name : "Unknown controller";
            for (int port = 0; port < PAD_MAX_CONTROLLERS; ++port) {
                if (port != g_controllerPort && PADGetIndexForPort(port) == static_cast<s32>(index)) {
                    label += " (" + std::string(TR(kPlayerNames[port])) + ")";
                    break;
                }
            }
            ImGui::PushID(static_cast<int>(index));
            if (ImGui::Selectable(label.c_str(), currentIndex == static_cast<s32>(index))) {
                PADSetPortForIndex(index, static_cast<uint32_t>(g_controllerPort));
                g_configuredControllerIndices.fill(std::numeric_limits<int32_t>::min());
                ApplyConfiguredMappings();
            }
            ImGui::PopID();
        }
        ImGui::EndCombo();
    }
#endif

    uint32_t mappingCount = 0;
    PADButtonMapping* mappings = PADGetButtonMappings(static_cast<uint32_t>(g_controllerPort), &mappingCount);
    if (mappings == nullptr || mappingCount != PAD_BUTTON_COUNT) {
        ImGui::TextDisabled(TR("Assign a controller to edit its buttons"));
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

    ui::Section("Presets");
    // The platform's default layout (the GameCube preset), also what "Reset mapping" restores.
    const auto applyGameCubeDefault = [&]() {
        const uint32_t port = static_cast<uint32_t>(g_controllerPort);
#if defined(__SWITCH__)
        ApplyPreset(port, kGameCubeSwitchPreset);
#else
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
#endif
        altRowExpanded.fill(false);
        PADSerializeMappings();
        mappings = PADGetButtonMappings(port, &mappingCount);
    };
    if (ui::Button("GameCube")) {
        applyGameCubeDefault();
    }
    ImGui::SameLine();
    if (ui::Button("Classic Controller Pro")) {
        const uint32_t port = static_cast<uint32_t>(g_controllerPort);
        ApplyPreset(port, kClassicProPreset);
        altRowExpanded.fill(false);
        PADSerializeMappings();
        mappings = PADGetButtonMappings(port, &mappingCount);
    }
    // Its own line: three buttons do not fit the Switch's narrow panel.
    if (ui::Button("Reset mapping")) {
        applyGameCubeDefault();
    }

    ui::Section("Button mapping");
    // The dropdowns hold the longest translated button name (capped at half the panel), not a fixed 190 px.
    const auto MappingComboWidth = [] {
        const ImGuiStyle& style = ImGui::GetStyle();
        float widest = 0.0f;
        for (const auto& candidate : kNativeButtons) {
            widest = std::max(widest, ImGui::CalcTextSize(TR(candidate.label)).x);
        }
        const float wanted = widest + style.FramePadding.x * 2.0f + ImGui::GetFrameHeight();
        return std::clamp(wanted, 190.0f, std::max(190.0f, ImGui::GetContentRegionAvail().x * 0.5f));
    };
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
        ImGui::SetNextItemWidth(MappingComboWidth());
        if (ImGui::BeginCombo("##primary", TR(current.label))) {
            for (const auto& candidate : kNativeButtons) {
                const bool selected = candidate.nativeButton == mappingIt->nativeButton;
                if (ImGui::Selectable(TR(candidate.label), selected)) {
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
        // With a second binding showing, the two dropdowns fill the row and the button's name would be
        // squeezed into a column a letter wide: it goes on its own line below instead.
        bool altShown = false;
        if (altIt != nullptr) {
            const bool altBound = altIt->nativeButton != PAD_NATIVE_BUTTON_INVALID;
            if (!altBound && !altRowExpanded[i]) {
                ImGui::SameLine();
                if (ImGui::SmallButton("+")) {
                    altRowExpanded[i] = true;
                }
                if (ImGui::IsItemHovered()) {
                    ImGui::SetTooltip(TR("Add a second binding; pressing either one works"));
                }
            } else {
                altShown = true;
                ImGui::SameLine();
                ImGui::TextUnformatted(TR("or"));
                ImGui::SameLine();
                const char* altLabel = altBound ? TR(NativeButtonForValue(altIt->nativeButton).label) : TR("None");
                ImGui::SetNextItemWidth(MappingComboWidth());
                if (ImGui::BeginCombo("##alt", altLabel)) {
                    for (const auto& candidate : kNativeButtons) {
                        const bool isNone = candidate.nativeButton == PAD_NATIVE_BUTTON_INVALID;
                        const bool selected = candidate.nativeButton == altIt->nativeButton;
                        if (ImGui::Selectable(isNone ? TR("None") : TR(candidate.label), selected)) {
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
        if (altShown) {
            ImGui::Indent(ImGui::GetFontSize());
            ImGui::TextDisabled("%s", TR(kControllerButtons[i].label));
            ImGui::Unindent(ImGui::GetFontSize());
        } else {
            ImGui::SameLine();
            ImGui::TextUnformatted(TR(kControllerButtons[i].label));
        }
        ImGui::PopID();
    }

}

void DrawAudioSettings() {
    if (ui::SliderInt("Master", &g_audioVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_audioVolumePercent) / 100.0f;
        AudioBackend::Instance().SetMasterVolume(volume);
        RuntimeConfigFile::SetAudioVolume(volume);
    }
    if (ui::SliderInt("Music", &g_musicVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_musicVolumePercent) / 100.0f;
        MusicAttenuation::SetMusicVolume(volume);
        RuntimeConfigFile::SetMusicVolume(volume);
    }
    if (ui::SliderInt("Sound Effects", &g_soundEffectsVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_soundEffectsVolumePercent) / 100.0f;
        MusicAttenuation::SetSoundEffectsVolume(volume);
        RuntimeConfigFile::SetSoundEffectsVolume(volume);
    }
    if (ui::SliderInt("UI", &g_uiVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_uiVolumePercent) / 100.0f;
        MusicAttenuation::SetUiVolume(volume);
        RuntimeConfigFile::SetUiVolume(volume);
    }
    if (ui::SliderInt("Voices", &g_voicesVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_voicesVolumePercent) / 100.0f;
        MusicAttenuation::SetVoicesVolume(volume);
        RuntimeConfigFile::SetVoicesVolume(volume);
    }
    if (ui::Toggle("Mute", &g_audioMuted)) {
        AudioBackend::Instance().SetMuted(g_audioMuted);
        RuntimeConfigFile::SetAudioMuted(g_audioMuted);
    }
    ImGui::Separator();
    // Android detects other apps' playback passively (ExternalMediaDetector.kt, never audio
    // focus); desktop uses the platform media-session monitor.
    if (ui::Toggle("Mute game music while external media is playing",
                        &g_attenuateMusicWhenMediaPlays)) {
        MusicAttenuation::SetEnabled(g_attenuateMusicWhenMediaPlays);
        RuntimeConfigFile::SetAttenuateMusicWhenMediaPlays(g_attenuateMusicWhenMediaPlays);
    }
    if (g_attenuateMusicWhenMediaPlays) {
        if (MusicAttenuation::IsExternalMediaPlaying()) {
            ImGui::TextDisabled(TR("External media is playing; game music is muted."));
        } else if (!MusicAttenuation::IsMediaControlInitializationComplete()) {
            ImGui::TextDisabled("%s", TR(kMediaMonitorWaitingText));
        } else if (!MusicAttenuation::IsMediaControlAvailable()) {
            ImGui::TextDisabled("%s", TR(kMediaMonitorUnavailableText));
        } else {
            ImGui::TextDisabled(TR("No external media is currently playing."));
        }
    }
}

#if defined(__SWITCH__)
extern "C" void AuroraSwitchSetMotionSteering(int enabled, float sensitivity, int inverted);
extern "C" void AuroraSwitchRecenterMotionSteering();
extern "C" int AuroraSwitchMotionSteeringSeen();
extern "C" float AuroraSwitchMotionValue(int player);

// Tilt to steer (aurora-main/lib/switch/input_switch.cpp). Kept in its own small file beside
// Config.toml, like the music player's settings.
struct MotionSteeringSettings {
    bool players[4] = {};  // per player since 2026-10-27; the file keeps them as a bit mask
    bool shake[4] = {};    // Shake to trick, per player (shake_trick.cfg, a bit mask)
    bool inverted = false;
    int sensitivityPercent = 100;
    bool loaded = false;
};
MotionSteeringSettings g_motionSteering;

int MotionPlayerMask() {
    int mask = 0;
    for (int i = 0; i < 4; ++i) {
        mask |= g_motionSteering.players[i] ? 1 << i : 0;
    }
    return mask;
}

void ApplyMotionSteering(bool save) {
    AuroraSwitchSetMotionSteering(MotionPlayerMask(), static_cast<float>(g_motionSteering.sensitivityPercent) / 100.0f,
                                  g_motionSteering.inverted ? 1 : 0);
    if (!save) {
        return;
    }
    const auto path = RuntimeConfigFile::ApplicationDataDirectory() / "motion_steering.cfg";
    if (FILE* file = std::fopen(path.string().c_str(), "wb")) {
        std::fprintf(file, "%d %d %d\n", MotionPlayerMask(), g_motionSteering.inverted ? 1 : 0,
                     g_motionSteering.sensitivityPercent);
        std::fclose(file);
    }
}

extern "C" void AuroraSwitchSetShakePlayers(int players);

void ApplyShakeToTrick(bool save) {
    int mask = 0;
    for (int i = 0; i < 4; ++i) {
        mask |= g_motionSteering.shake[i] ? 1 << i : 0;
    }
    AuroraSwitchSetShakePlayers(mask);
    if (!save) {
        return;
    }
    const auto path = RuntimeConfigFile::ApplicationDataDirectory() / "shake_trick.cfg";
    if (FILE* file = std::fopen(path.string().c_str(), "wb")) {
        std::fprintf(file, "%d\n", mask);
        std::fclose(file);
    }
}

void LoadShakeToTrick() {
    int mask = 0;
    const auto dir = RuntimeConfigFile::ApplicationDataDirectory();
    if (FILE* file = std::fopen((dir / "shake_trick.cfg").string().c_str(), "rb")) {
        if (std::fscanf(file, "%d", &mask) != 1) {
            mask = 0;
        }
        std::fclose(file);
    } else if (FILE* old = std::fopen((dir / "wii_wheel.cfg").string().c_str(), "rb")) {
        // Before 2026-10-27 it was player 1's Wii Wheel, saved there.
        int value = 0;
        mask = std::fscanf(old, "%d", &value) == 1 && value != 0 ? 1 : 0;
        std::fclose(old);
    }
    for (int i = 0; i < 4; ++i) {
        g_motionSteering.shake[i] = (mask & (1 << i)) != 0;
    }
    ApplyShakeToTrick(false);
}

void LoadMotionSteering() {
    if (g_motionSteering.loaded) {
        return;
    }
    g_motionSteering.loaded = true;
    const auto path = RuntimeConfigFile::ApplicationDataDirectory() / "motion_steering.cfg";
    if (FILE* file = std::fopen(path.string().c_str(), "rb")) {
        int enabled = 0, inverted = 0, sensitivity = 100;
        if (std::fscanf(file, "%d %d %d", &enabled, &inverted, &sensitivity) == 3) {
            for (int i = 0; i < 4; ++i) {
                g_motionSteering.players[i] = (enabled & (1 << i)) != 0;  // an old "1" is player 1
            }
            g_motionSteering.inverted = inverted != 0;
            g_motionSteering.sensitivityPercent = std::clamp(sensitivity, 50, 200);
        }
        std::fclose(file);
    }
    ApplyMotionSteering(false);
    LoadShakeToTrick();
}

// A thin bar showing a player's tilt right now, so they can see the sensor works before a race.
void TiltBar(int player) {
    const float fs = ImGui::GetFontSize();
    const float width = ui::RowWidth();
    const ImVec2 min = ImGui::GetCursorScreenPos();
    const float height = fs * 0.5f;
    ImGui::Dummy(ImVec2(width, height + fs * 0.4f));
    ImDrawList* draw = ImGui::GetWindowDrawList();
    const float midX = min.x + width * 0.5f;
    draw->AddRectFilled(min, ImVec2(min.x + width, min.y + height), ImGui::GetColorU32(ui::kTrackOff), height * 0.5f);
    const float value = std::clamp(AuroraSwitchMotionValue(player), -1.0f, 1.0f);
    const float x = midX + value * width * 0.5f;
    draw->AddRectFilled(ImVec2(std::min(midX, x), min.y), ImVec2(std::max(midX, x), min.y + height),
                        ImGui::GetColorU32(SwitchPlayerColor(player)), height * 0.5f);
    draw->AddLine(ImVec2(midX, min.y - fs * 0.15f), ImVec2(midX, min.y + height + fs * 0.15f),
                  ImGui::GetColorU32(ui::kText), 2.0f);
}

void DrawMotionSteeringSettings() {
    LoadMotionSteering();
    ui::Section("Tilt to steer");
    bool changed = false;
    bool any = false;
    const int seen = AuroraSwitchMotionSteeringSeen();
    for (int player = 0; player < 4; ++player) {
        const char* controller = AuroraSwitchPlayerController(player);
        char label[64];
        std::snprintf(label, sizeof(label), "Player %d##Tilt", player + 1);
        changed |= ui::Toggle(label, &g_motionSteering.players[player]);
        if (!g_motionSteering.players[player]) {
            continue;
        }
        any = true;
        if (controller == nullptr) {
            ImGui::TextDisabled(TR("Not connected"));
        } else if ((seen & (1 << player)) == 0) {
            ImGui::TextDisabled(TR("Waiting for the tilt sensor... (a single Joy-Con held sideways has none yet)"));
        } else {
            TiltBar(player);
        }
    }
    if (any) {
        changed |= ui::SliderInt("Sensitivity##Motion", &g_motionSteering.sensitivityPercent, 50, 200, "%d%%");
        changed |= ui::Toggle("Reverse direction", &g_motionSteering.inverted);
        if (ui::Button("Set straight ahead again (taken when you resume)", ImVec2(-1.0f, 48.0f))) {
            AuroraSwitchRecenterMotionSteering();
        }
    }
    if (changed) {
        ApplyMotionSteering(true);
    }

    // Shake to trick (aurora lib/switch/input_switch.cpp): a shake while driving presses the
    // D-pad in its direction on that player's own controller. Applies right away.
    ui::Section("Shake to trick");
    bool shakeChanged = false;
    for (int player = 0; player < 4; ++player) {
        char label[64];
        std::snprintf(label, sizeof(label), "Player %d##Shake", player + 1);
        shakeChanged |= ui::Toggle(label, &g_motionSteering.shake[player]);
    }
    if (shakeChanged) {
        ApplyShakeToTrick(true);
    }
    ImGui::TextWrapped(TR("While driving, shake the controller for tricks: up, down, left or right picks the trick, "
                       "like the D-pad. On the ground any shake does a wheelie. In menus the D-pad works as usual."));
}
#endif

// The player's own MP3s over the game (music_player.cpp). Big buttons and tall rows: on Switch this
// page is used with a finger as much as with the controller.
void DrawMusicPlayer() {
    static std::vector<std::string> tracks;
    static bool listed = false;
    if (!listed) {
        tracks = MusicPlayer::Rescan();
        listed = true;
    }
    const MusicPlayer::Status status = MusicPlayer::GetStatus();
    const float width = ImGui::GetContentRegionAvail().x;

    if (tracks.empty()) {
        ImGui::TextWrapped(TR("No music yet. Put MP3 files in %s on the SD card, then press Look again."),
                           MusicPlayer::FolderForDisplay().c_str());
        if (ui::Button("Look again", ImVec2(width, 52.0f))) {
            tracks = MusicPlayer::Rescan();
        }
        return;
    }

    const bool haveTrack = status.index >= 0 && status.index < static_cast<int>(tracks.size());
    ImGui::TextWrapped("%s", haveTrack ? tracks[static_cast<size_t>(status.index)].c_str() : TR("Nothing playing"));
    const auto clock = [](double seconds) {
        const int total = static_cast<int>(seconds);
        char text[16];
        std::snprintf(text, sizeof(text), "%d:%02d", total / 60, total % 60);
        return std::string(text);
    };
    // Drag the bar to move through the track; it jumps when the finger (or stick) lets go, so the
    // sound does not stutter along behind the drag.
    static float scrub = 0.0f;
    static bool scrubbing = false;
    const float duration = static_cast<float>(status.durationSeconds);
    float position = scrubbing ? scrub : static_cast<float>(status.positionSeconds);
    const std::string times = clock(position) + " / " + clock(status.durationSeconds);
    ImGui::BeginDisabled(!status.loaded || duration <= 0.0f);
    ImGui::SetNextItemWidth(width);
    ImGui::PushStyleVar(ImGuiStyleVar_FramePadding, ImVec2(ImGui::GetStyle().FramePadding.x, 12.0f));
    if (ui::SliderFloat("##MusicPlayerPosition", &position, 0.0f, std::max(duration, 1.0f),
                           status.loaded ? times.c_str() : "", ImGuiSliderFlags_NoInput)) {
        scrub = position;
        scrubbing = true;
    }
    if (scrubbing && !ImGui::IsItemActive()) {
        MusicPlayer::Seek(scrub);
        scrubbing = false;
    }
    ImGui::PopStyleVar();

    const float spacing = ImGui::GetStyle().ItemSpacing.x;
    const float half = (width - spacing) / 2.0f;
    if (ui::Button("Back 10 s", ImVec2(half, 48.0f))) {
        MusicPlayer::Seek(status.positionSeconds - 10.0);
    }
    ImGui::SameLine();
    if (ui::Button("Forward 10 s", ImVec2(half, 48.0f))) {
        // Past the end would restart the last moment over and over; go to the next track instead.
        if (status.positionSeconds + 10.0 >= status.durationSeconds) {
            MusicPlayer::Next();
        } else {
            MusicPlayer::Seek(status.positionSeconds + 10.0);
        }
    }
    ImGui::EndDisabled();

    const float third = (width - spacing * 2.0f) / 3.0f;
    if (ui::Button("Previous", ImVec2(third, 60.0f))) {
        MusicPlayer::Previous();
    }
    ImGui::SameLine();
    if (ui::Button(status.playing ? "Pause" : "Play", ImVec2(third, 60.0f))) {
        MusicPlayer::TogglePause();
    }
    ImGui::SameLine();
    if (ui::Button("Next", ImVec2(third, 60.0f))) {
        MusicPlayer::Next();
    }

    int volume = static_cast<int>(std::lround(MusicPlayer::Volume() * 100.0f));
    if (ui::SliderInt("Volume##MusicPlayer", &volume, 0, 100, "%d%%")) {
        MusicPlayer::SetVolume(static_cast<float>(volume) / 100.0f);
    }
    bool shuffle = MusicPlayer::Shuffle();
    if (ui::Toggle("Shuffle", &shuffle)) {
        MusicPlayer::SetShuffle(shuffle);
    }
    bool muteGame = MusicPlayer::MuteGameMusic();
    if (ui::Toggle("Turn off the game's own music while this plays", &muteGame)) {
        MusicPlayer::SetMuteGameMusic(muteGame);
    }
    if (status.loaded) {
        ImGui::SameLine();
        if (ui::Button("Stop")) {
            MusicPlayer::Stop();
        }
    }

    ImGui::Separator();
    for (size_t i = 0; i < tracks.size(); ++i) {
        ImGui::PushID(static_cast<int>(i));
        const bool current = static_cast<int>(i) == status.index;
        if (ImGui::Selectable(tracks[i].c_str(), current, 0, ImVec2(0.0f, 44.0f))) {
            MusicPlayer::Play(static_cast<int>(i));
        }
        ImGui::PopID();
    }
    if (ui::Button("Look for new files", ImVec2(width, 48.0f))) {
        tracks = MusicPlayer::Rescan();
    }
}

// Brightness / contrast / saturation of the picture on screen (GitHub issue #15), applied by
// aurora's final copy to the screen, so menus and screenshots of the settings stay as they are.
struct PictureSettings {
    bool loaded = false;
    int brightnessPercent = 100;
    int contrastPercent = 100;
    int saturationPercent = 100;
};
PictureSettings g_picture;

void ApplyPictureSettings(bool save) {
    aurora_set_picture_adjust(g_picture.brightnessPercent / 100.0f, g_picture.contrastPercent / 100.0f,
                              g_picture.saturationPercent / 100.0f);
    if (!save) {
        return;
    }
    const auto path = RuntimeConfigFile::ApplicationDataDirectory() / "picture.cfg";
    if (FILE* file = std::fopen(path.string().c_str(), "wb")) {
        std::fprintf(file, "%d %d %d\n", g_picture.brightnessPercent, g_picture.contrastPercent,
                     g_picture.saturationPercent);
        std::fclose(file);
    }
}

void LoadPictureSettings() {
    if (g_picture.loaded) {
        return;
    }
    g_picture.loaded = true;
    const auto path = RuntimeConfigFile::ApplicationDataDirectory() / "picture.cfg";
    if (FILE* file = std::fopen(path.string().c_str(), "rb")) {
        int brightness = 100, contrast = 100, saturation = 100;
        if (std::fscanf(file, "%d %d %d", &brightness, &contrast, &saturation) == 3) {
            g_picture.brightnessPercent = std::clamp(brightness, 25, 200);
            g_picture.contrastPercent = std::clamp(contrast, 25, 200);
            g_picture.saturationPercent = std::clamp(saturation, 0, 200);
        }
        std::fclose(file);
    }
    ApplyPictureSettings(false);
}

void DrawPictureSettings() {
    LoadPictureSettings();
    ui::Section("Picture");
    bool changed = false;
    changed |= ui::SliderInt("Brightness", &g_picture.brightnessPercent, 25, 200, "%d%%");
    changed |= ui::SliderInt("Contrast", &g_picture.contrastPercent, 25, 200, "%d%%");
    changed |= ui::SliderInt("Saturation", &g_picture.saturationPercent, 0, 200, "%d%%");
    if (ui::Button("Reset picture")) {
        g_picture.brightnessPercent = 100;
        g_picture.contrastPercent = 100;
        g_picture.saturationPercent = 100;
        changed = true;
    }
    if (changed) {
        ApplyPictureSettings(true);
    }
    ImGui::Separator();
}

// Render-resolution scale (0.5x-8x the Wii's native output), same list/logic as DrawTopBar's own
// resolution dropdown. That one lives inline in DrawTopBar (desktop's menu bar) and is left alone
// there; this is a separate, sidebar-appropriate (BeginCombo, not BeginMenu) copy for
// DrawAndroidSidebar, which never actually included this setting at all when it replaced the top
// bar on Android - confirmed directly as a real regression ("in Display settings add
// resolution(the scaling settings, as those were removed fully.)").
// The language of the menus. Saved by i18n::SaveChoice; the fonts are built at start-up, so a change
// applies the next time the game starts.
void DrawLanguageSetting() {
    static std::string choice = i18n::SavedChoice();
    std::string preview = i18n::TrString("Automatic");
    for (const i18n::LanguageInfo& language : i18n::Languages()) {
        if (choice == language.code) {
            preview = language.nativeName;
        }
    }
    if (ui::BeginCombo("Language", preview.c_str())) {
        const bool autoSelected = choice == "auto";
        if (ImGui::Selectable(TR("Automatic"), autoSelected)) {
            choice = "auto";
            i18n::SaveChoice(choice);
        }
        for (const i18n::LanguageInfo& language : i18n::Languages()) {
            const bool selected = choice == language.code;
            if (ImGui::Selectable(language.nativeName, selected)) {
                choice = language.code;
                i18n::SaveChoice(choice);
            }
            if (selected) {
                ImGui::SetItemDefaultFocus();
            }
        }
        ImGui::EndCombo();
    }
    const std::string wanted = choice == "auto" ? i18n::DetectSystemLanguage() : choice;
    if (wanted != i18n::Language()) {
        ImGui::TextDisabled("%s", TR("Applies the next time the game starts."));
    }
}

void DrawResolutionSettings() {
    const auto resolutionIt = std::find_if(kResolutions.begin(), kResolutions.end(), [](const ResolutionItem& item) {
        return std::fabs(item.scale - g_resolutionScale) < 0.001f;
    });
    const char* resolutionLabel = resolutionIt != kResolutions.end() ? TR(resolutionIt->label) : TR("Custom");
    if (ui::BeginCombo("Resolution", resolutionLabel)) {
        for (const auto& resolution : kResolutions) {
            const bool selected = std::fabs(resolution.scale - g_resolutionScale) < 0.001f;
            const bool disabled = IsHighFrameRateMode() && IsHighResolutionScale(resolution.scale);
            ImGui::BeginDisabled(disabled);
            const bool clicked = ImGui::Selectable(TR(resolution.label), selected);
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

    // Requested directly after a real device report (Retroid Pocket Nova, a 4:3-screened Android
    // handheld): the game was always told it's on a 16:9 TV (SCGetAspectRatio_HLE, hle/sc.cpp,
    // defaults to widescreen=true) regardless of the device's actual screen shape, so its OWN
    // HUD/menu layout math (not just output scaling) used 16:9-safe-area coordinates - on a
    // genuinely 4:3 screen those elements land toward the screen's center instead of the corners.
    // A picker here lets the player match this to their real screen instead of only auto-detecting
    // it (which would need to run before this menu can even show settings, and some 4:3-ish
    // devices - e.g. anything with on-screen nav bars eating into the aspect ratio - are
    // ambiguous enough that a manual override is worth having regardless).
    constexpr std::array<const char*, 2> kAspectRatioModes{"Widescreen (16:9)", "4:3 (original Wii)"};
    int aspectRatioIndex = g_widescreen ? 0 : 1;
    if (ui::Combo("Aspect Ratio", &aspectRatioIndex, kAspectRatioModes.data(),
                      static_cast<int>(kAspectRatioModes.size()))) {
        g_widescreen = aspectRatioIndex == 0;
        RuntimeConfigFile::SetWidescreen(g_widescreen);
        RequestMkwAspectMode(g_widescreen);
    }
    // The picture switches immediately, but the game lays out menus and the HUD when a screen
    // loads, so whatever is already on screen keeps its old layout until the next one.
    ImGui::PushTextWrapPos(ImGui::GetCursorPosX() + 380.0f);
    ImGui::TextDisabled(TR("The HUD and menus update on the next screen or race."));
    ImGui::PopTextWrapPos();
    ImGui::Separator();
}

void DrawGraphicsSettings() {
    DrawPictureSettings();
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
        if (ui::Toggle(effect.label, &disabled)) {
            if (disabled) {
                g_disabledPostProcessingPaths |= effect.flag;
            } else {
                g_disabledPostProcessingPaths &= ~effect.flag;
            }
            RuntimeGameGraphicsOptions::SetDisabledPostProcessingPaths(g_disabledPostProcessingPaths);
            RuntimeConfigFile::SetDisabledPostProcessingPaths(g_disabledPostProcessingPaths);
        }
    }
    ImGui::TextDisabled(TR("Applied when the next scene renderer is created."));
    ImGui::Separator();
    static constexpr const char* kDisplayModes[] = {
        "Windowed",
        "Borderless fullscreen",
        "Exclusive fullscreen",
    };
    if (ui::Combo("Display mode", &g_displayMode, kDisplayModes, static_cast<int>(std::size(kDisplayModes)))) {
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
            TR("Requests the closest native-resolution display mode to the output frame "
            "rate (60 Hz, or the frame interpolation target)."));
    }
    if (ui::Toggle("Simpler computer karts (faster races)", &g_lowDetailComputerKarts)) {
        RuntimeSetLowDetailComputerKarts(g_lowDetailComputerKarts);
        RuntimeConfigFile::SetLowDetailComputerKarts(g_lowDetailComputerKarts);
    }
    ImGui::TextDisabled(TR("Applies from the next race."));
    if (ui::Toggle("30 FPS races (normal speed, half the frames)", &g_thirtyFpsRaces)) {
        RuntimeSetThirtyFpsRaces(g_thirtyFpsRaces);
        RuntimeConfigFile::SetThirtyFpsRaces(g_thirtyFpsRaces);
    }
    ImGui::TextDisabled(TR("For when 60 can't be held. Applies from the next race."));
#if defined(__ANDROID__)
    // A phone that gets hot drops its clocks whatever the game does, so give up pixels before frames.
    if (ui::Toggle("Lower resolution when the phone gets hot", &g_thermalAutoQuality)) {
        ThermalQuality::SetEnabled(g_thermalAutoQuality);
        RuntimeConfigFile::SetThermalAutoQuality(g_thermalAutoQuality);
    }
    if (g_thermalAutoQuality) {
        const float headroom = ThermalQuality::LastHeadroom();
        const float factor = ThermalQuality::CurrentFactor();
        if (headroom < 0.0f) {
            ImGui::TextDisabled(TR("This device does not report thermal readings."));
        } else if (factor >= 1.0f) {
            ImGui::TextDisabled(TR("Running at full resolution (heat %.0f%% of the limit)."),
                                static_cast<double>(headroom) * 100.0);
        } else {
            ImGui::TextDisabled(TR("Rendering at %.0f%% resolution (heat %.0f%% of the limit)."),
                                static_cast<double>(factor) * 100.0,
                                static_cast<double>(headroom) * 100.0);
        }
    }
#endif
    if (ui::Toggle("Show FPS", &g_showFps)) {
        RuntimeConfigFile::SetShowFps(g_showFps);
    }
}

void DrawAdvancedSettings() {
    ImGui::TextWrapped(TR("Technical options. The defaults suit most people; change these when something looks or runs wrong."));
    constexpr std::array<const char*, 3> kFrameInterpolationModes{
        "Off", "120 FPS", "180 FPS",
    };
    const char* currentFrameInterpolationMode =
        TR(kFrameInterpolationModes[static_cast<size_t>(g_frameInterpolationMode)]);
    bool frameInterpolationModeChanged = false;
    if (ui::BeginCombo("Race frame interpolation (experimental)", currentFrameInterpolationMode)) {
        for (int mode = 0; mode < static_cast<int>(kFrameInterpolationModes.size()); ++mode) {
            const bool selected = g_frameInterpolationMode == mode;
            if (ImGui::Selectable(TR(kFrameInterpolationModes[static_cast<size_t>(mode)]), selected)) {
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
    ImGui::TextDisabled(TR("Frame interpolation is experimental, you might find visual artifacts"));
    ImGui::PopTextWrapPos();
    if (ui::Toggle("Disable copy filter", &g_disableCopyFilter)) {
        aurora_set_disable_copy_filter(g_disableCopyFilter);
        RuntimeConfigFile::SetDisableCopyFilter(g_disableCopyFilter);
    }
    // Decodes graphics commands on their own thread, freeing roughly a fifth of the game thread in
    // profiles. Experimental: off by default until it has been played on more devices.
    if (ui::Toggle("Multithreaded graphics (experimental)", &g_threadedGx)) {
        AuroraSetThreadedGx(g_threadedGx);
        RuntimeConfigFile::SetThreadedGx(g_threadedGx);
    }
#if !defined(__SWITCH__)
    // KartPad issue #193: on some Adreno phones (Galaxy S24 Ultra) characters render as only their
    // eyes while karts are fine. Off by default - it is an unconfirmed workaround.
    if (ui::Toggle("Fix missing characters (some Adreno GPUs)", &g_constantMatrixIndexing)) {
        AuroraSetConstantMatrixIndexing(g_constantMatrixIndexing);
        RuntimeConfigFile::SetConstantMatrixIndexing(g_constantMatrixIndexing);
    }
    {
        static constexpr std::array<const char*, 4> labels{
            "Automatic", "Off", "Characters only", "Everything (slower)"};
        if (ui::Combo("Fix stretched graphics (Snapdragon)", &g_vertexRepackChoice, labels.data(),
                         static_cast<int>(labels.size()))) {
            RuntimeConfigFile::SetVertexRepack(kVertexRepackConfigValues[static_cast<size_t>(g_vertexRepackChoice)]);
        }
        ImGui::TextDisabled(TR("Takes effect the next time the game starts."));
    }
#endif
    if (ui::Toggle("Skip draws while shaders compile", &g_skipUnreadyPipelines)) {
        aurora_set_skip_unready_pipelines(g_skipUnreadyPipelines);
        RuntimeConfigFile::SetSkipUnreadyPipelines(g_skipUnreadyPipelines);
    }
    // Requested in GitHub issue #5, alongside Show FPS.
    if (ui::Toggle("Show \"shaders compiling\" message", &g_showShaderCompilation)) {
        RuntimeConfigFile::SetShowShaderCompilation(g_showShaderCompilation);
    }
#if defined(__ANDROID__)
    if (ui::Toggle("Hide the settings button", &g_hideSettingsButton)) {
        RuntimeConfigFile::SetHideSettingsButton(g_hideSettingsButton);
        AndroidSetSettingsButtonHidden(g_hideSettingsButton);
    }
    ImGui::PushTextWrapPos(ImGui::GetCursorPosX() + 380.0f);
    ImGui::TextDisabled(TR("The phone's Back button always opens these settings."));
    ImGui::PopTextWrapPos();
#endif
    ImGui::Separator();
    ImGui::Text("Graphics API: %s", GraphicsApiDisplayName());
    // Names the driver in use: a custom driver from the launcher's picker reports itself here
    // (Turnip prefixes the chip name), the phone's own driver reports the bare chip.
    if (const char* adapter = aurora_get_adapter_name(); adapter != nullptr && adapter[0] != '\0') {
        ImGui::Text("GPU: %s", adapter);
    }
    ImGui::Separator();
    if (ui::Toggle("Mix audio on a worker thread", &g_audioMixWorker)) {
        // Applies immediately: SetMixWorkerEnabled joins any in-flight mix
        // before switching, so the change never lands mid-frame.
        AxDspHle::SetMixWorkerEnabled(g_audioMixWorker);
        RuntimeConfigFile::SetAudioMixWorker(g_audioMixWorker);
    }
    if (ImGui::IsItemHovered()) {
        ImGui::SetTooltip(
            TR("Runs the AX/DSP voice mix off the game thread. Turn this off if you "
            "suspect an audio problem; the mix then runs inline as it used to."));
    }
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
    if (!g_showShaderCompilation) {
        return;
    }
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
        // At start-up the queue is last session's pipelines being rebuilt, mostly straight from the
        // shader cache; "compiling" there read as the cache not working.
        if (aurora_is_pipeline_prewarm_active()) {
            ImGui::Text(TR("Loading saved shaders: %u left"), queuedPipelines);
        } else {
            if (queuedPipelines == 1) {
                ImGui::TextUnformatted(TR("1 shader compiling"));
            } else {
                ImGui::Text(TR("%u shaders compiling"), queuedPipelines);
            }
        }
    }
    ImGui::End();
    ImGui::PopStyleVar();
}

std::string g_backendFallbackNotice;
Clock::time_point g_backendFallbackNoticeUntil{};
bool g_backendFallbackNoticeStarted = false;

void DrawBackendFallbackNotice() {
    if (g_backendFallbackNotice.empty() || StartupScreenVisible()) {
        return;
    }
    // The clock starts with the first frame the player can actually see.
    if (!g_backendFallbackNoticeStarted) {
        g_backendFallbackNoticeStarted = true;
        g_backendFallbackNoticeUntil = Clock::now() + std::chrono::seconds(12);
    }
    if (Clock::now() >= g_backendFallbackNoticeUntil) {
        g_backendFallbackNotice.clear();
        return;
    }
    const ImGuiViewport* viewport = ImGui::GetMainViewport();
    ImGui::SetNextWindowPos(ImVec2(viewport->Pos.x + viewport->Size.x * 0.5f, viewport->Pos.y + 24.0f),
                            ImGuiCond_Always, ImVec2(0.5f, 0.0f));
    ImGui::SetNextWindowBgAlpha(0.8f);
    constexpr ImGuiWindowFlags kFlags = ImGuiWindowFlags_AlwaysAutoResize |
                                        ImGuiWindowFlags_NoDecoration |
                                        ImGuiWindowFlags_NoFocusOnAppearing |
                                        ImGuiWindowFlags_NoInputs |
                                        ImGuiWindowFlags_NoMove |
                                        ImGuiWindowFlags_NoNav |
                                        ImGuiWindowFlags_NoSavedSettings;
    if (ImGui::Begin("Backend Fallback Notice", nullptr, kFlags)) {
        ImGui::TextUnformatted(g_backendFallbackNotice.c_str());
    }
    ImGui::End();
}

void DrawStartupScreen() {
    if (!StartupScreenVisible()) {
#if defined(__SWITCH__)
        BootBanner::Finish();
#endif
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
#if defined(__SWITCH__)
        // The disc's own Wii Menu banner, carried on from the launcher's splash (switch_launcher.cpp).
        if (BootBanner::Draw(ImGui::GetWindowDrawList(), viewport->Pos.x, viewport->Pos.y, viewport->Size.x,
                             viewport->Size.y)) {
            ImGui::End();
            ImGui::PopStyleVar();
            ImGui::PopStyleColor();
            return;
        }
#endif
        ImGui::SetWindowFontScale(1.25f);
        // The shipped app branding is "DriftDroid" on both device ports (the Switch NRO carries
        // the DriftDroid name, icon and NACP too); desktop stays "WiiCompiled", the underlying
        // static-recompilation project's own name, unrelated to the port's rebrand.
#if defined(__ANDROID__) || defined(__SWITCH__)
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

bool HasConfiguredControllerBindings() {
    for (size_t i = 0; i < kControllerButtons.size(); ++i) {
        if (!RuntimeConfigFile::ControllerButton(i)) {
            return false;
        }
    }
    return true;
}

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
        // A controller with a saved mapping has been set up before - by this function on an
        // earlier launch, or by the player remapping it. Likewise, once Config.toml holds
        // bindings (the preset from a first run, or the player's own remap),
        // ApplyConfiguredMappings applies those to every newly connected controller, so writing
        // the preset again would only overwrite the player's choices. g_androidControllersAutoConfigured only
        // lasts for this process, so without this check every launch re-applied the preset and
        // overwrote the player's own bindings (reported on an Odin 2, whose built-in controls
        // are a real SDL gamepad: "controls aren't saved when saving settings").
        if (PADHasSavedMappingForIndex(index) || HasConfiguredControllerBindings()) {
            if (index < g_androidControllersAutoConfigured.size()) {
                g_androidControllersAutoConfigured.set(index);
            }
            RT_LOG(RT_TAG_CONFIG) << "AutoConfigureNewAndroidControllersIfPresent: index " << index
                                   << " has a saved mapping, keeping it";
            continue;
        }
        // Classic Controller Pro, not GameCube: matches TouchControlsOverlay's L/R buttons,
        // which send SDL_GAMEPAD_BUTTON_LEFT/RIGHT_SHOULDER (this preset's "left_shoulder"/
        // "right_shoulder"), not an analog trigger axis - and matches a real Switch Pro
        // Controller's physical wiring, so the same preset is correct for both.
        ApplyPreset(targetPort, kClassicProPreset);
        PADSerializeMappings();
        if (index < g_androidControllersAutoConfigured.size()) {
            g_androidControllersAutoConfigured.set(index);
        }
        RT_LOG(RT_TAG_CONFIG) << "AutoConfigureNewAndroidControllersIfPresent: configured index " << index
                               << " on port " << targetPort;
    }
}
#endif  // __ANDROID__

// The settings drawer (remaster, 2026-10-24; replaces the root-list sidebar). It slides in from
// the right over about two fifths of the screen, so the race stays in view; in vertical mode it is a
// sheet over the bottom half, where the touch buttons sit. Pages are tabs across the top: tapped on a
// phone, L / R on a controller (the Switch design the user approved, with A / B / + hints at the
// bottom). Every setting writes Config.toml the moment it changes, so there is no Save button.
// Music is a page on Switch and PC; the phone has no folder to put music in (and the user did not
// want it there).
static bool g_sidebarWasVisible = false;

// Lets a finger drag the current (child) window's content up and down from anywhere on it, the
// way every phone list scrolls. ImGui on its own only scrolls from the thin scrollbar, since a
// touch arrives as a left mouse button. A drag that is mostly vertical takes over: whatever
// widget the finger landed on is released without activating, and the content follows the
// finger, then coasts to a stop after it lifts. Call just before EndChild().
void ScrollCurrentWindowByTouchDrag() {
    static bool dragging = false;
    static bool pressBeganHere = false;
    static float velocity = 0.0f;
    ImGuiIO& io = ImGui::GetIO();
    const bool hovered = ImGui::IsWindowHovered(ImGuiHoveredFlags_ChildWindows |
                                                ImGuiHoveredFlags_AllowWhenBlockedByActiveItem);
    if (ImGui::IsMouseClicked(ImGuiMouseButton_Left)) {
        pressBeganHere = hovered;
        velocity = 0.0f;
    }
    if (!ImGui::IsMouseDown(ImGuiMouseButton_Left)) {
        dragging = false;
        pressBeganHere = false;
        // Coast after the finger lifts.
        if (std::fabs(velocity) > 0.5f) {
            ImGui::SetScrollY(ImGui::GetScrollY() - velocity);
            velocity *= 0.92f;
        } else {
            velocity = 0.0f;
        }
        return;
    }
    if (!pressBeganHere) {
        return;
    }
    if (!dragging) {
        const ImVec2 delta = ImGui::GetMouseDragDelta(ImGuiMouseButton_Left, 0.0f);
        const float threshold = 12.0f * std::max(1.0f, io.FontGlobalScale);
        if (std::fabs(delta.y) > threshold && std::fabs(delta.y) > std::fabs(delta.x) * 1.5f) {
            dragging = true;
            ImGui::ClearActiveID();
        }
    }
    if (dragging) {
        ImGui::SetScrollY(ImGui::GetScrollY() - io.MouseDelta.y);
        velocity = velocity * 0.5f + io.MouseDelta.y * 0.5f;
        // Keep widgets from reacting to the finger while it is scrolling.
        ImGui::ClearActiveID();
    }
}

enum class DrawerPage { Display, Controls, Sound, Music, Advanced };

struct DrawerTab {
    DrawerPage page;
    const char* label;
};

constexpr DrawerTab kDrawerTabs[] = {
    {DrawerPage::Display, "Display"},
    {DrawerPage::Controls, "Controls"},
    {DrawerPage::Sound, "Sound"},
#if !defined(__ANDROID__)
    {DrawerPage::Music, "Music"},
#endif
    {DrawerPage::Advanced, "Advanced"},
};
constexpr int kDrawerTabCount = static_cast<int>(std::size(kDrawerTabs));

// A round button-prompt badge ("A", "B", "+", "L", "R") in the Switch's style.
void HintBadge(const char* key) {
    const float fs = ImGui::GetFontSize();
    const ImVec2 textSize = ImGui::CalcTextSize(key);
    const float height = fs * 1.6f;
    const float width = std::max(height, textSize.x + fs * 0.9f);
    const ImVec2 min = ImGui::GetCursorScreenPos();
    ImGui::Dummy(ImVec2(width, height));
    ImDrawList* draw = ImGui::GetWindowDrawList();
    draw->AddRectFilled(min, ImVec2(min.x + width, min.y + height), ImGui::GetColorU32(ImVec4(0.91f, 0.93f, 0.96f, 1.0f)),
                        height * 0.5f);
    draw->AddText(ImVec2(min.x + (width - textSize.x) * 0.5f, min.y + (height - textSize.y) * 0.5f),
                  ImGui::GetColorU32(ui::kPanel), key);
}

void DrawerPageContent(DrawerPage page) {
    switch (page) {
    case DrawerPage::Display:
        DrawLanguageSetting();
        DrawResolutionSettings();
        DrawGraphicsSettings();
        // Texture packs are managed from the launcher only: its file picker is a separate Activity,
        // which backgrounds this live session's GPU surface (mid-race that lost the Vulkan surface
        // and crashed, confirmed on-device).
#if defined(__ANDROID__)
        ImGui::Spacing();
        ImGui::TextDisabled(TR("Texture packs are managed from the launcher screen."));
#endif
        break;
    case DrawerPage::Controls:
        DrawControllerSettings();
#if defined(__SWITCH__)
        DrawMotionSteeringSettings();
#endif
#if defined(__ANDROID__)
        ui::Section("Touch and motion");
        if (ui::LinkRow("Edit touch layout", "Move, resize or hide the on-screen buttons")) {
            // The editor and the dialog below are Kotlin views, not ImGui: close the drawer first so
            // it does not sit on top of them and take their touches.
            SetTopBarVisible(false);
            AndroidStartTouchLayoutEdit();
        }
        if (ui::LinkRow("Motion steering", "Tilt to steer, shake to trick")) {
            SetTopBarVisible(false);
            AndroidShowMotionSteeringDialog();
        }
#endif
        break;
    case DrawerPage::Sound:
        DrawAudioSettings();
        break;
    case DrawerPage::Music:
        DrawMusicPlayer();
        break;
    case DrawerPage::Advanced:
        DrawAdvancedSettings();
        break;
    }
}

void DrawSettingsDrawer() {
    if (!g_topBarVisible) {
        g_sidebarWasVisible = false;
        return;
    }
    static int tab = 0;
    static bool focusContent = false;
    const ImGuiIO& io = ImGui::GetIO();
#if defined(__SWITCH__)
    // See ForwardGamepadToImGui: slow frames while the menu is open.
    {
        static Uint64 lastNs = 0;
        const Uint64 nowNs = SDL_GetTicksNS();
        if (g_sidebarWasVisible && lastNs != 0 && nowNs - lastNs >= 80000000u) {
            std::printf("[menu] %llu ms between menu frames (page %d)\n",
                        static_cast<unsigned long long>((nowNs - lastNs) / 1000000u), tab);
        }
        lastNs = nowNs;
    }
#endif
    const bool portrait = io.DisplaySize.y > io.DisplaySize.x;
    ui::Theme theme;
    const float fs = ImGui::GetFontSize();

    // Controller navigation only moves inside the focused window: take focus when the panel opens.
    if (!g_sidebarWasVisible) {
        ImGui::SetNextWindowFocus();
        focusContent = true;
    }
    g_sidebarWasVisible = true;
    if (portrait) {
        ImGui::SetNextWindowPos(ImVec2(0.0f, io.DisplaySize.y), ImGuiCond_Always, ImVec2(0.0f, 1.0f));
        ImGui::SetNextWindowSize(ImVec2(io.DisplaySize.x, io.DisplaySize.y * 0.56f), ImGuiCond_Always);
    } else {
        const float width = std::clamp(io.DisplaySize.x * 0.40f, fs * 26.0f, io.DisplaySize.x * 0.62f);
        ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x, 0.0f), ImGuiCond_Always, ImVec2(1.0f, 0.0f));
        ImGui::SetNextWindowSize(ImVec2(width, io.DisplaySize.y), ImGuiCond_Always);
    }
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, portrait ? fs * 1.2f : 0.0f);
    constexpr ImGuiWindowFlags kFlags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                                         ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoCollapse |
                                         ImGuiWindowFlags_NoResize;
    const bool open = ImGui::Begin("Settings##Drawer", nullptr, kFlags);
    ImGui::PopStyleVar();
    if (!open) {
        ImGui::End();
        return;
    }

#if !defined(__ANDROID__) && !defined(__SWITCH__)
    // TEMP-DRAWER: MKW_TEST_SETTINGS_TAB_EVERY=<frames> steps through the pages, for captures.
    static const long long tabEvery = std::getenv("MKW_TEST_SETTINGS_TAB_EVERY")
                                          ? std::atoll(std::getenv("MKW_TEST_SETTINGS_TAB_EVERY"))
                                          : 0;
    if (tabEvery > 0) {
        tab = static_cast<int>((g_presentedFrame / static_cast<uint64_t>(tabEvery)) % kDrawerTabCount);
    } else if (const char* fixed = std::getenv("MKW_TEST_SETTINGS_TAB")) {
        tab = std::atoi(fixed) % kDrawerTabCount;
    }
#endif
    // L / R change page, B (with nothing being edited and no list open) or + closes.
    const bool popupOpen = ImGui::IsPopupOpen("", ImGuiPopupFlags_AnyPopupId);
    if (!popupOpen && !ImGui::IsAnyItemActive()) {
        if (ImGui::IsKeyPressed(ImGuiKey_GamepadL1, false)) {
            tab = (tab + kDrawerTabCount - 1) % kDrawerTabCount;
            focusContent = true;
        }
        if (ImGui::IsKeyPressed(ImGuiKey_GamepadR1, false)) {
            tab = (tab + 1) % kDrawerTabCount;
            focusContent = true;
        }
        if (ImGui::IsKeyPressed(ImGuiKey_GamepadFaceRight, false) || ImGui::IsKeyPressed(ImGuiKey_GamepadStart, false)) {
            SetTopBarVisible(false);
        }
    }

    // Tabs: the current one lifted and underlined.
#if defined(__SWITCH__)
    HintBadge("L");
    ImGui::SameLine();
#endif
    // L / R switch pages, so the D-pad focus never lands on the tabs or the close button.
    ImGui::PushItemFlag(ImGuiItemFlags_NoNav, true);
    for (int i = 0; i < kDrawerTabCount; ++i) {
        const bool selected = i == tab;
        const char* tabLabel = TR(kDrawerTabs[i].label);
        const ImVec2 textSize = ImGui::CalcTextSize(tabLabel);
        const ImVec2 size(textSize.x + fs * 1.2f, fs * 2.2f);
        ImGui::PushID(i);
        if (ImGui::InvisibleButton("tab", size) && !selected) {
            tab = i;
            focusContent = true;
        }
        ImGui::PopID();
        const ImVec2 min = ImGui::GetItemRectMin();
        const ImVec2 max = ImGui::GetItemRectMax();
        ImDrawList* draw = ImGui::GetWindowDrawList();
        if (selected) {
            draw->AddRectFilled(min, max, ImGui::GetColorU32(ui::kRow), fs * 0.5f);
            draw->AddRectFilled(ImVec2(min.x + fs * 0.4f, max.y - fs * 0.18f), ImVec2(max.x - fs * 0.4f, max.y),
                                ImGui::GetColorU32(ui::kFocus), fs * 0.1f);
        }
        draw->AddText(ImVec2(min.x + (size.x - textSize.x) * 0.5f, min.y + (size.y - textSize.y) * 0.5f),
                      ImGui::GetColorU32(selected ? ui::kText : ui::kMuted), tabLabel);
        ImGui::SameLine(0.0f, fs * 0.2f);
    }
#if defined(__SWITCH__)
    HintBadge("R");
#else
    const float closeSize = fs * 2.2f;
    ImGui::SameLine(ImGui::GetContentRegionMax().x - closeSize);
    if (ui::Button("X##CloseDrawer", ImVec2(closeSize, closeSize))) {
        SetTopBarVisible(false);
    }
#endif
    ImGui::PopItemFlag();
    ImGui::Separator();

    // The page, scrolled by finger drag or by moving the focus with a controller.
#if defined(__SWITCH__)
    const float footerHeight = fs * 2.6f;
#else
    const float footerHeight = fs * 3.6f;
#endif
    ImGui::BeginChild("DrawerPage", ImVec2(0.0f, -footerHeight), ImGuiChildFlags_NavFlattened);
    const bool pageChanged = focusContent;
    if (pageChanged) {
        ImGui::SetScrollY(0.0f);
        focusContent = false;
    }
    // Notes longer than the panel wrap at its edge instead of running off it.
    ImGui::PushTextWrapPos(0.0f);
    DrawerPageContent(kDrawerTabs[tab].page);
    ImGui::PopTextWrapPos();
    ImGui::Dummy(ImVec2(0.0f, fs * 0.5f));
    ScrollCurrentWindowByTouchDrag();
    ImGui::EndChild();
    // A new page puts the controller focus on its first setting. (SetKeyboardFocusHere would open a
    // slider's typing box instead.)
    if (pageChanged) {
        ImGui::NavInitWindow(ImGui::GetCurrentWindow(), true);
    }

    ImGui::Separator();
#if defined(__SWITCH__)
    const auto hint = [&](const char* key, const char* text) {
        HintBadge(key);
        ImGui::SameLine(0.0f, fs * 0.4f);
        ImGui::AlignTextToFramePadding();
        ImGui::TextDisabled("%s", TR(text));
        ImGui::SameLine(0.0f, fs * 1.4f);
    };
    hint("A", "Change");
    hint("B", "Close");
    hint("+", "Resume");
    ImGui::NewLine();
#else
    ImGui::PushStyleColor(ImGuiCol_Button, ui::kAccent);
    ImGui::PushStyleColor(ImGuiCol_ButtonHovered, ImVec4(0.58f, 0.90f, 0.42f, 1.0f));
    ImGui::PushStyleColor(ImGuiCol_ButtonActive, ImVec4(0.42f, 0.76f, 0.28f, 1.0f));
    ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.04f, 0.10f, 0.06f, 1.0f));
    if (ui::Button("Resume", ImVec2(-1.0f, fs * 2.6f))) {
        SetTopBarVisible(false);
    }
    ImGui::PopStyleColor(4);
#endif
    ImGui::End();
}

// ---- Quick panel -------------------------------------------------------------------------------
// Opened with Minus on Switch, F9 on PC and a pull down from the top on the phone. A strip of tiles for
// the settings people change in the middle of a race; "All settings" opens the full menu. The race keeps
// running underneath, but the game gets no input while it is open (the same as the full menu).
void SetQuickPanelVisible(bool visible) {
    if (visible && g_topBarVisible) {
        return;  // the full menu is open
    }
    if (g_quickPanelVisible == visible) {
        return;
    }
    g_quickPanelVisible = visible;
#if defined(__SWITCH__)
    AuroraSwitchSetMenuOpen(visible ? 1 : 0);  // tilt and shake stay out of the panel's navigation
#endif
#if defined(__ANDROID__)
    AndroidNotifySettingsVisibilityChanged(visible);
#endif
}

struct QuickTile {
    const char* label;
    std::string status;
    bool on = false;
    bool enabled = true;
    std::function<void()> flip;
};

void DrawQuickPanel() {
    if (!g_quickPanelVisible) {
        g_quickPanelWasVisible = false;
        return;
    }
    const ImGuiIO& io = ImGui::GetIO();
    const bool portrait = io.DisplaySize.y > io.DisplaySize.x;
    const float fs = ImGui::GetFontSize();
    ui::Theme theme;

    std::vector<QuickTile> tiles;
    tiles.push_back({"Vibration", PADGetRumbleEnabled() != FALSE ? "On" : "Off", PADGetRumbleEnabled() != FALSE, true, [] {
                         PADSetRumbleEnabled(PADGetRumbleEnabled() != FALSE ? FALSE : TRUE);
                         SaveRumbleSetting();
                     }});
    tiles.push_back({"Show FPS", g_showFps ? "On" : "Off", g_showFps, true, [] {
                         g_showFps = !g_showFps;
                         RuntimeConfigFile::SetShowFps(g_showFps);
                     }});
#if defined(__SWITCH__)
    LoadMotionSteering();
    tiles.push_back({"Tilt steer", g_motionSteering.players[0] ? "On" : "Off", g_motionSteering.players[0], true, [] {
                         g_motionSteering.players[0] = !g_motionSteering.players[0];
                         ApplyMotionSteering(true);
                     }});
    tiles.push_back({"Shake trick", g_motionSteering.shake[0] ? "On" : "Off", g_motionSteering.shake[0], true, [] {
                         g_motionSteering.shake[0] = !g_motionSteering.shake[0];
                         ApplyShakeToTrick(true);
                     }});
    {
        const MusicPlayer::Status music = MusicPlayer::GetStatus();
        tiles.push_back({"Music", music.loaded ? (music.playing ? "Playing" : "Paused") : "No track", music.playing,
                         music.loaded, [] { MusicPlayer::TogglePause(); }});
    }
#endif
#if defined(__ANDROID__)
    tiles.push_back({"Touch buttons", g_androidTouchControlsVisibleCache ? "Shown" : "Hidden",
                     g_androidTouchControlsVisibleCache, true, [] {
                         g_androidTouchControlsVisibleCache = !g_androidTouchControlsVisibleCache;
                         AndroidSetTouchOverlayVisible(g_androidTouchControlsVisibleCache);
                     }});
    tiles.push_back({"Motion", ">", false, true, [] {
                         SetQuickPanelVisible(false);
                         AndroidShowMotionSteeringDialog();
                     }});
#endif
    tiles.push_back({"All settings", ">", false, true, [] {
                         SetQuickPanelVisible(false);
                         SetTopBarVisible(true);
                     }});

    const int count = static_cast<int>(tiles.size());
    const float margin = fs * 0.8f;
    const float pad = fs * 0.9f;
    const int cols = portrait ? 3 : count;
    const int rows = (count + cols - 1) / cols;
    const float maxWidth = io.DisplaySize.x - 2.0f * margin;
    const float gap = fs * 0.5f;
    float tileW = std::min(fs * 8.5f, (maxWidth - 2.0f * pad - static_cast<float>(cols - 1) * gap) / static_cast<float>(cols));
    tileW = std::max(tileW, fs * 4.0f);
    const float tileH = fs * 4.2f;
    const float windowW = 2.0f * pad + static_cast<float>(cols) * tileW + static_cast<float>(cols - 1) * gap;
    const float headerH = fs * 1.8f;
    const float footerH = fs * 2.4f;
    const float windowH = pad + headerH + static_cast<float>(rows) * (tileH + gap) + footerH + pad;

    if (!g_quickPanelWasVisible) {
        ImGui::SetNextWindowFocus();
    }
    ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x * 0.5f, margin), ImGuiCond_Always, ImVec2(0.5f, 0.0f));
    ImGui::SetNextWindowSize(ImVec2(windowW, windowH), ImGuiCond_Always);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowRounding, fs * 1.2f);
    ImGui::PushStyleVar(ImGuiStyleVar_WindowPadding, ImVec2(pad, pad));
    constexpr ImGuiWindowFlags kFlags = ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove |
                                         ImGuiWindowFlags_NoSavedSettings | ImGuiWindowFlags_NoCollapse |
                                         ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoScrollbar;
    const bool open = ImGui::Begin("Quick settings##Panel", nullptr, kFlags);
    ImGui::PopStyleVar(2);
    if (!open) {
        ImGui::End();
        return;
    }

    // Close: B or + on a controller, or a tap anywhere outside the panel.
    bool close = false;
    if (g_quickPanelWasVisible && !ImGui::IsAnyItemActive()) {
        close |= ImGui::IsKeyPressed(ImGuiKey_GamepadFaceRight, false) || ImGui::IsKeyPressed(ImGuiKey_GamepadStart, false);
    }
    if (g_quickPanelWasVisible && ImGui::IsMouseClicked(ImGuiMouseButton_Left) &&
        !ImGui::IsWindowHovered(ImGuiHoveredFlags_RootAndChildWindows | ImGuiHoveredFlags_AllowWhenBlockedByActiveItem)) {
        close = true;
    }

    // Header: the title, and the frame rate at the right.
    {
        const ImVec2 min = ImGui::GetCursorScreenPos();
        ImDrawList* draw = ImGui::GetWindowDrawList();
        draw->AddText(ImVec2(min.x, min.y + fs * 0.1f), ImGui::GetColorU32(ui::kMuted), TR("Quick settings"));
        AuroraPresentTiming timing{};
        aurora_get_present_timing(&timing);
        if (timing.sampleCount != 0) {
            char fps[32];
            std::snprintf(fps, sizeof(fps), "%.0f FPS", timing.framesPerSecond);
            const ImVec2 size = ImGui::CalcTextSize(fps);
            draw->AddText(ImVec2(min.x + windowW - 2.0f * pad - size.x, min.y + fs * 0.1f), ImGui::GetColorU32(ui::kMuted), fps);
        }
        ImGui::Dummy(ImVec2(0.0f, headerH));
    }

    // Tiles.
    const ImVec2 gridOrigin = ImGui::GetCursorPos();
    for (int i = 0; i < count; ++i) {
        QuickTile& tile = tiles[static_cast<size_t>(i)];
        const int row = i / cols;
        const int col = i % cols;
        ImGui::SetCursorPos(ImVec2(gridOrigin.x + static_cast<float>(col) * (tileW + gap),
                                   gridOrigin.y + static_cast<float>(row) * (tileH + gap)));
        ImGui::PushID(i);
        ImGui::BeginDisabled(!tile.enabled);
        const bool pressed = ImGui::InvisibleButton("tile", ImVec2(tileW, tileH), ImGuiButtonFlags_EnableNav);
        ImGui::EndDisabled();
        const ImVec2 min = ImGui::GetItemRectMin();
        const ImVec2 max = ImGui::GetItemRectMax();
        ImDrawList* draw = ImGui::GetWindowDrawList();
        ImVec4 bg = tile.on ? ImVec4(0.133f, 0.220f, 0.110f, 1.0f) : ui::kRow;
        if (ImGui::IsItemActive()) {
            bg = ui::kRowActive;
        } else if (ImGui::IsItemHovered() || ImGui::IsItemFocused()) {
            bg = tile.on ? ImVec4(0.160f, 0.270f, 0.130f, 1.0f) : ui::kRowHover;
        }
        draw->AddRectFilled(min, max, ImGui::GetColorU32(bg), fs * 0.8f);
        if (ImGui::IsItemFocused()) {
            draw->AddRect(min, max, ImGui::GetColorU32(ui::kFocus), fs * 0.8f, 0, 2.5f);
        }
        const ImU32 labelColor = ImGui::GetColorU32(tile.enabled ? (tile.on ? ui::kText : ui::kMuted) : ImVec4(0.4f, 0.43f, 0.55f, 1.0f));
        // A long translation shrinks to fit its tile (down to about half size) instead of running into the next one.
        const auto drawCentered = [&](const char* text, float size, float y, ImU32 color) {
            ImFont* font = ImGui::GetFont();
            const float avail = tileW - fs * 0.7f;
            float drawSize = size;
            ImVec2 extent = font->CalcTextSizeA(drawSize, FLT_MAX, 0.0f, text);
            if (extent.x > avail) {
                drawSize = std::max(size * avail / extent.x, size * 0.5f);
                extent = font->CalcTextSizeA(drawSize, FLT_MAX, 0.0f, text);
            }
            draw->AddText(font, drawSize, ImVec2(min.x + (tileW - extent.x) * 0.5f, y + (size - drawSize) * 0.5f), color, text);
        };
        drawCentered(TR(tile.label), fs, min.y + tileH * 0.28f, labelColor);
        const std::string statusText = TR(tile.status.c_str());
        drawCentered(statusText.c_str(), fs * 0.85f, min.y + tileH * 0.60f,
                     ImGui::GetColorU32(tile.on ? ui::kAccent : ui::kMuted));
        ImGui::PopID();
        if (pressed && tile.enabled && tile.flip) {
            tile.flip();
            if (!g_quickPanelVisible) {
                ImGui::End();
                return;  // a tile closed the panel (All settings, Motion)
            }
        }
    }
    ImGui::SetCursorPos(ImVec2(gridOrigin.x, gridOrigin.y + static_cast<float>(rows) * (tileH + gap)));

    // Footer: the volume slider, or the button hints on Switch.
#if defined(__SWITCH__)
    {
        const auto hint = [&](const char* key, const char* text) {
            HintBadge(key);
            ImGui::SameLine(0.0f, fs * 0.4f);
            ImGui::AlignTextToFramePadding();
            ImGui::TextDisabled("%s", TR(text));
            ImGui::SameLine(0.0f, fs * 1.4f);
        };
        hint("A", "Flip");
        hint("B", "Close");
        ImGui::NewLine();
    }
#else
    if (ui::SliderInt("Volume", &g_audioVolumePercent, 0, 100, "%d%%")) {
        const float volume = static_cast<float>(g_audioVolumePercent) / 100.0f;
        AudioBackend::Instance().SetMasterVolume(volume);
        RuntimeConfigFile::SetAudioVolume(volume);
    }
#endif
    ImGui::End();
    if (!g_quickPanelWasVisible) {
        g_quickPanelWasVisible = true;
    }
    if (close) {
        SetQuickPanelVisible(false);
    }
}

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

    if (ImGui::BeginMenu("Advanced")) {
        ImGui::Dummy(ImVec2(420.0f, 0.0f));
        DrawAdvancedSettings();
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

    if (ImGui::BeginMenu("Music player")) {
        ImGui::Dummy(ImVec2(420.0f, 0.0f));
        DrawMusicPlayer();
        ImGui::EndMenu();
    }

    const float hideWidth = ImGui::CalcTextSize("Hide (F10)").x + ImGui::GetStyle().FramePadding.x * 2.0f;
    ImGui::SetCursorPosX(std::max(ImGui::GetCursorPosX(), ImGui::GetWindowWidth() - hideWidth - 8.0f));
    if (ImGui::MenuItem("Hide (F10)")) {
        SetTopBarVisible(false);
    }
    ImGui::EndMainMenuBar();
}

#if defined(__SWITCH__)
void ForwardGamepadToImGui(const SDL_Event& event) {
    ImGuiIO& io = ImGui::GetIO();
    // The user sees "a lot of delay sometimes" in the menu (2026-10-27): log presses that arrive
    // late, to tell a slow event path from slow menu frames (logged in DrawSettingsDrawer).
    if (g_topBarVisible && event.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) {
        const Uint64 lateMs = (SDL_GetTicksNS() - event.gbutton.timestamp) / 1000000u;
        if (lateMs >= 80) {
            std::printf("[menu] button %d reached the menu %llu ms after it was pressed\n",
                        static_cast<int>(event.gbutton.button), static_cast<unsigned long long>(lateMs));
        }
    }
    io.BackendFlags |= ImGuiBackendFlags_HasGamepad;
    // Every controller drives the menu, merged: a key is down while any controller holds it, a
    // stick direction is the furthest any controller pushes it. Passing each controller's events
    // straight through let one controller's "released" cancel another's "held" (with two
    // connected, ImGui then stopped reading its queue at every such clash and fell behind).
    struct MenuPad {
        SDL_JoystickID id = 0;
        uint32_t buttons = 0;     // bit n = SDL button n
        float stick[4] = {};      // left, right, up, down
    };
    static MenuPad pads[8];
    const SDL_JoystickID id = event.type == SDL_EVENT_GAMEPAD_AXIS_MOTION ? event.gaxis.which : event.gbutton.which;
    if (event.type != SDL_EVENT_GAMEPAD_BUTTON_DOWN && event.type != SDL_EVENT_GAMEPAD_BUTTON_UP &&
        event.type != SDL_EVENT_GAMEPAD_AXIS_MOTION) {
        if (event.type == SDL_EVENT_GAMEPAD_REMOVED) {
            for (MenuPad& pad : pads) {
                if (pad.id == event.gdevice.which) {
                    pad = MenuPad{};  // its held keys are let go below on the next event
                }
            }
        }
        return;
    }
    MenuPad* pad = nullptr;
    for (MenuPad& candidate : pads) {
        if (candidate.id == id) {
            pad = &candidate;
            break;
        }
    }
    if (pad == nullptr) {
        for (MenuPad& candidate : pads) {
            if (candidate.id == 0) {
                candidate.id = id;
                pad = &candidate;
                break;
            }
        }
    }
    if (pad == nullptr) {
        return;
    }
    if (event.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN || event.type == SDL_EVENT_GAMEPAD_BUTTON_UP) {
        ImGuiKey key = ImGuiKey_None;
        switch (event.gbutton.button) {
        case SDL_GAMEPAD_BUTTON_DPAD_UP: key = ImGuiKey_GamepadDpadUp; break;
        case SDL_GAMEPAD_BUTTON_DPAD_DOWN: key = ImGuiKey_GamepadDpadDown; break;
        case SDL_GAMEPAD_BUTTON_DPAD_LEFT: key = ImGuiKey_GamepadDpadLeft; break;
        case SDL_GAMEPAD_BUTTON_DPAD_RIGHT: key = ImGuiKey_GamepadDpadRight; break;
        case SDL_GAMEPAD_BUTTON_SOUTH: key = ImGuiKey_GamepadFaceDown; break;   // A: activate
        case SDL_GAMEPAD_BUTTON_EAST: key = ImGuiKey_GamepadFaceRight; break;   // B: back
        case SDL_GAMEPAD_BUTTON_WEST: key = ImGuiKey_GamepadFaceLeft; break;
        case SDL_GAMEPAD_BUTTON_NORTH: key = ImGuiKey_GamepadFaceUp; break;
        case SDL_GAMEPAD_BUTTON_LEFT_SHOULDER: key = ImGuiKey_GamepadL1; break;
        case SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER: key = ImGuiKey_GamepadR1; break;
        case SDL_GAMEPAD_BUTTON_START: key = ImGuiKey_GamepadStart; break;  // +: resume
        default: break;
        }
        if (key == ImGuiKey_None || event.gbutton.button >= 32) {
            return;
        }
        const uint32_t bit = 1u << event.gbutton.button;
        if (event.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) {
            pad->buttons |= bit;
        } else {
            pad->buttons &= ~bit;
        }
        bool down = false;
        for (const MenuPad& other : pads) {
            down |= other.id != 0 && (other.buttons & bit) != 0;
        }
        io.AddKeyEvent(key, down);  // ImGui drops it when nothing changed
    } else if (event.gaxis.axis == SDL_GAMEPAD_AXIS_LEFTX || event.gaxis.axis == SDL_GAMEPAD_AXIS_LEFTY) {
        constexpr float kDeadZone = 8000.0f;
        const float v = static_cast<float>(event.gaxis.value);
        const float neg = v < -kDeadZone ? std::min(1.0f, (-v - kDeadZone) / (32767.0f - kDeadZone)) : 0.0f;
        const float pos = v > kDeadZone ? std::min(1.0f, (v - kDeadZone) / (32767.0f - kDeadZone)) : 0.0f;
        const int first = event.gaxis.axis == SDL_GAMEPAD_AXIS_LEFTX ? 0 : 2;
        pad->stick[first] = neg;
        pad->stick[first + 1] = pos;
        static constexpr ImGuiKey kKeys[4] = {ImGuiKey_GamepadLStickLeft, ImGuiKey_GamepadLStickRight,
                                              ImGuiKey_GamepadLStickUp, ImGuiKey_GamepadLStickDown};
        for (int i = first; i < first + 2; ++i) {
            float merged = 0.0f;
            for (const MenuPad& other : pads) {
                if (other.id != 0) {
                    merged = std::max(merged, other.stick[i]);
                }
            }
            io.AddKeyAnalogEvent(kKeys[i], merged > 0.0f, merged);
        }
    }
}
#endif

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
    i18n::SetLanguage(i18n::SavedChoice());
    LoadPictureSettings();  // saved picture settings apply without opening the menu
    LoadRumbleSetting();
#if defined(__SWITCH__)
    LoadMotionSteering();  // a saved "tilt to steer" applies without opening the menu
#endif
#if defined(__ANDROID__)
    // The g_* globals above were initialized at static-init time, which on Android runs
    // before Kotlin's JNI call to SetAndroidFilesDir() - so RuntimeConfigFile::ApplicationDataDirectory()
    // still resolved to nothing back then, and every read silently fell back to its hardcoded
    // default (this is why persisted settings like display mode "didn't save": they were never
    // actually being read back, on any launch). SetAndroidFilesDir() has definitely run by the
    // time SDL_main calls this function, so re-read everything from the real config now.
    g_resolutionScale = RuntimeConfigFile::ResolutionMultiplier(1.0f);
    if (g_resolutionScale > 0.0f && g_resolutionScale < 1.0f) {
        g_resolutionScale = 1.0f;
    }
    g_audioVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::AudioVolume(1.0f) * 100.0f));
    g_musicVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::MusicVolume(1.0f) * 100.0f));
    g_soundEffectsVolumePercent =
        static_cast<int>(std::lround(RuntimeConfigFile::SoundEffectsVolume(1.0f) * 100.0f));
    g_uiVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::UiVolume(1.0f) * 100.0f));
    g_voicesVolumePercent = static_cast<int>(std::lround(RuntimeConfigFile::VoicesVolume(1.0f) * 100.0f));
    g_audioMuted = RuntimeConfigFile::AudioMuted(false);
    g_audioMixWorker = RuntimeConfigFile::AudioMixWorkerEnabled(true);
    g_attenuateMusicWhenMediaPlays = RuntimeConfigFile::AttenuateMusicWhenMediaPlays(false);
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
    // With no saved choice, the GPUs known to need the workaround get it.
    g_constantMatrixIndexing = RuntimeConfigFile::ConstantMatrixIndexing(AuroraAdapterWantsConstantMatrixIndexing());
    g_vertexRepackChoice = ParseVertexRepackConfig(RuntimeConfigFile::VertexRepack("auto"));
#if defined(__SWITCH__)
    // An Adreno driver workaround with no purpose on the Switch's GPU, and a harmful one: with it
    // on, item boxes rendered as opaque black/grey cubes instead of translucent rainbow ones
    // (2026-09-26, confirmed by toggling it on hardware). A config written by an older build or
    // the settings checkbox must not bring it back.
    g_constantMatrixIndexing = false;
#endif
    if (aurora_get_backend() == BACKEND_OPENGLES && !g_constantMatrixIndexing) {
        // Not optional on this backend. Dynamically indexed matrices make Qualcomm's GLSL
        // compiler segfault outright rather than merely miscompile: confirmed by a debuggerd
        // tombstone landing inside libllvm-glnext.so's CreateQGLCShader, called from Dawn's
        // ShaderModule::CompileShader, which took the whole process down during boot every
        // time. On Vulkan the same setting is a fix for missing characters and stays the user's
        // choice; here it is the difference between running and not.
        g_constantMatrixIndexing = true;
        RT_LOG(RT_TAG_RUNTIME)
            << "forcing constant matrix indexing on: the OpenGL ES driver cannot compile the "
               "dynamically indexed form"
            << std::endl;
    }
    g_threadedGx = RuntimeConfigFile::ThreadedGx(false);
    g_thermalAutoQuality = RuntimeConfigFile::ThermalAutoQuality(false);
    g_showFps = RuntimeConfigFile::ShowFps(true);
    g_showShaderCompilation = RuntimeConfigFile::ShowShaderCompilation(true);
    g_hideSettingsButton = RuntimeConfigFile::HideSettingsButton(false);
    g_disabledPostProcessingPaths = RuntimeConfigFile::DisabledPostProcessingPaths(0);
    // Same staleness class as everything else in this block, plus one more wrinkle: on a
    // brand-new install with no [video] widescreen key yet, android_jni_bridge.cpp's SDL_main
    // only just wrote the device's auto-detected default moments ago (right before the config
    // reload that made this whole re-read necessary) - without this line the very first launch's
    // settings sidebar would show "Widescreen (16:9)" regardless of the real screen shape, even
    // though the auto-detected value was already correctly in effect for actual gameplay.
    g_widescreen = RuntimeConfigFile::WidescreenEnabled(true);
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
    AuroraSetConstantMatrixIndexing(g_constantMatrixIndexing);
    g_lowDetailComputerKarts = RuntimeConfigFile::LowDetailComputerKarts(kLowDetailComputerKartsDefault);
    RuntimeSetLowDetailComputerKarts(g_lowDetailComputerKarts);
    g_thirtyFpsRaces = RuntimeConfigFile::ThirtyFpsRaces(false);
    RuntimeSetThirtyFpsRaces(g_thirtyFpsRaces);
#if !defined(__SWITCH__)
    AuroraSetVertexRepackMode(VertexRepackModeForChoice(g_vertexRepackChoice));
#endif
    AuroraSetThreadedGx(g_threadedGx);
    aurora_set_overlap_frame_encode(RuntimeConfigFile::OverlapFrameEncode(true));
    ThermalQuality::SetEnabled(g_thermalAutoQuality);
#if defined(__ANDROID__)
    AndroidSetSettingsButtonHidden(g_hideSettingsButton);
#endif
    aurora_set_skip_unready_pipelines(g_skipUnreadyPipelines);
    g_strapInputAccepted.store(false, std::memory_order_relaxed);
    g_bootSceneExited.store(false, std::memory_order_relaxed);
    g_startupDismissFrame.store(UINT64_MAX, std::memory_order_relaxed);
    g_startupDismissAtMs.store(INT64_MAX, std::memory_order_relaxed);
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
        if (IsToggleKey(ev->sdl, SDL_SCANCODE_F9)) {
            SetQuickPanelVisible(!g_quickPanelVisible);
        }
        // Minus opens the quick panel on a controller (the Switch has no function keys).
        if (ev->sdl.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN && ev->sdl.gbutton.button == SDL_GAMEPAD_BUTTON_BACK &&
            !g_topBarVisible) {
            SetQuickPanelVisible(!g_quickPanelVisible);
        }
        // Controller shortcut (the Switch has no F10): click both sticks together.
        if (ev->sdl.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN || ev->sdl.type == SDL_EVENT_GAMEPAD_BUTTON_UP) {
            static bool leftStickHeld = false;
            static bool rightStickHeld = false;
            const bool down = ev->sdl.type == SDL_EVENT_GAMEPAD_BUTTON_DOWN;
            const bool wasBoth = leftStickHeld && rightStickHeld;
            if (ev->sdl.gbutton.button == SDL_GAMEPAD_BUTTON_LEFT_STICK) {
                leftStickHeld = down;
            } else if (ev->sdl.gbutton.button == SDL_GAMEPAD_BUTTON_RIGHT_STICK) {
                rightStickHeld = down;
            }
            if (!wasBoth && leftStickHeld && rightStickHeld) {
                SetTopBarVisible(!g_topBarVisible);
            }
        }
#if defined(__SWITCH__)
        // Drive the settings panel's controller navigation straight from the gamepad events: the
        // Switch's virtual SDL gamepad is not guaranteed to be opened by ImGui's SDL backend.
        ForwardGamepadToImGui(ev->sdl);
#endif
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
    DrawBackendFallbackNotice();
#if defined(__ANDROID__) || defined(__SWITCH__)
    DrawSettingsDrawer();
#else
    // The PC keeps its menu bar; MKW_SETTINGS_DRAWER=1 shows the phone/Switch drawer instead.
    static const bool drawer = std::getenv("MKW_SETTINGS_DRAWER") != nullptr;
    // TEMP-DRAWER: MKW_TEST_SETTINGS_OPEN=<frame> opens it at that frame, for unattended captures.
    static const long long openAt =
        std::getenv("MKW_TEST_SETTINGS_OPEN") ? std::atoll(std::getenv("MKW_TEST_SETTINGS_OPEN")) : -1;
    if (openAt >= 0 && static_cast<long long>(g_presentedFrame) == openAt) {
        SetTopBarVisible(true);
    }
    if (drawer) {
        DrawSettingsDrawer();
    } else {
        DrawTopBar();
    }
#endif
    DrawQuickPanel();
    controller_mapping_wizard::Draw();
    // The wizard captures raw presses; keep them out of the game even when the
    // top bar is hidden mid-setup.
    PADBlockInput(g_topBarVisible || g_quickPanelVisible || controller_mapping_wizard::IsActive());
    DrawStartupScreen();
}

bool StartupScreenVisible() noexcept {
    return !g_strapInputAccepted.load(std::memory_order_acquire) ||
           (g_presentedFrame < g_startupDismissFrame.load(std::memory_order_relaxed) &&
            StartupClockMs() < g_startupDismissAtMs.load(std::memory_order_relaxed));
}

void ToggleTopBar() noexcept {
    SetTopBarVisible(!g_topBarVisible);
}

bool OpenQuickPanel() noexcept {
    const bool wasOpen = g_quickPanelVisible;
    SetQuickPanelVisible(true);
    return g_quickPanelVisible && !wasOpen;  // false when the full menu is open or the panel already was
}

void NotifyStrapInputAccepted() noexcept {
    bool expected = false;
    if (g_strapInputAccepted.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        g_startupDismissFrame.store(g_presentedFrame + kStrapTransitionCoverFrames,
                                    std::memory_order_release);
        g_startupDismissAtMs.store(StartupClockMs() + kStrapTransitionCoverMs, std::memory_order_release);
    }
}

void NotifyBootSceneExited() noexcept {
    if (!g_strapInputAccepted.load(std::memory_order_acquire)) {
        return;
    }
    bool expected = false;
    if (g_bootSceneExited.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        g_startupDismissFrame.store(g_presentedFrame + kSceneExitCoverFrames, std::memory_order_release);
        g_startupDismissAtMs.store(StartupClockMs() + kSceneExitCoverMs, std::memory_order_release);
    }
}

void AdvancePresentedFrame() noexcept { ++g_presentedFrame; }

void NotifyBackendFallback(const char* requested, const char* actual) noexcept {
    const auto pretty = [](const char* name) -> std::string {
        const std::string_view value = name != nullptr ? name : "";
        if (value == "vulkan") return "Vulkan";
        if (value == "opengles") return "OpenGL ES";
        return std::string(value);
    };
    try {
        g_backendFallbackNotice = pretty(requested) + " could not start on this device. Using " + pretty(actual) +
                                  " instead, which may be slower.";
    } catch (...) {
    }
}
} // namespace settings_overlay
