// Switch controller input. SDL3 has no Switch joystick backend, so every connected controller
// (handheld Joy-Cons or any player slot, merged via libnx's pad API) is exposed to the rest of aurora as an
// SDL virtual gamepad: everything downstream (mapping, port assignment, events) sees an ordinary
// SDL_Gamepad.
#include "input_switch.hpp"

#include <SDL3/SDL_gamepad.h>
#include <SDL3/SDL_joystick.h>
#include <switch.h>

#include <cstdint>

namespace aurora::input::switch_pad {
namespace {

PadState g_pad;
SDL_Joystick* g_joystick = nullptr;
SDL_JoystickID g_joystickId = 0;

constexpr uint16_t kButtonCount = SDL_GAMEPAD_BUTTON_DPAD_RIGHT + 1;
constexpr uint16_t kAxisCount = SDL_GAMEPAD_AXIS_RIGHT_TRIGGER + 1;

struct ButtonMap {
  u64 hid;
  SDL_GamepadButton sdl;
};

// SDL names buttons by position; the Switch's A is the east face button, B the south one.
constexpr ButtonMap kButtons[] = {
    // By label, not position: aurora binds the game's A/B/X/Y to SOUTH/EAST/WEST/NORTH (Xbox
    // layout), so a positional mapping would swap A<->B and X<->Y on Nintendo controllers.
    {HidNpadButton_A, SDL_GAMEPAD_BUTTON_SOUTH},
    {HidNpadButton_B, SDL_GAMEPAD_BUTTON_EAST},
    {HidNpadButton_X, SDL_GAMEPAD_BUTTON_WEST},
    {HidNpadButton_Y, SDL_GAMEPAD_BUTTON_NORTH},
    {HidNpadButton_Minus, SDL_GAMEPAD_BUTTON_BACK},
    {HidNpadButton_Plus, SDL_GAMEPAD_BUTTON_START},
    {HidNpadButton_StickL, SDL_GAMEPAD_BUTTON_LEFT_STICK},
    {HidNpadButton_StickR, SDL_GAMEPAD_BUTTON_RIGHT_STICK},
    {HidNpadButton_L, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER},
    {HidNpadButton_R, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER},
    {HidNpadButton_Up, SDL_GAMEPAD_BUTTON_DPAD_UP},
    {HidNpadButton_Down, SDL_GAMEPAD_BUTTON_DPAD_DOWN},
    {HidNpadButton_Left, SDL_GAMEPAD_BUTTON_DPAD_LEFT},
    {HidNpadButton_Right, SDL_GAMEPAD_BUTTON_DPAD_RIGHT},
};

Sint16 clamp_axis(s32 value) {
  if (value > SDL_JOYSTICK_AXIS_MAX) {
    return SDL_JOYSTICK_AXIS_MAX;
  }
  if (value < SDL_JOYSTICK_AXIS_MIN) {
    return SDL_JOYSTICK_AXIS_MIN;
  }
  return static_cast<Sint16>(value);
}

void SDLCALL update(void*) {
  if (g_joystick == nullptr) {
    return;
  }
  padUpdate(&g_pad);
  const u64 held = padGetButtons(&g_pad);
  for (const auto& button : kButtons) {
    SDL_SetJoystickVirtualButton(g_joystick, button.sdl, (held & button.hid) != 0);
  }
  const HidAnalogStickState left = padGetStickPos(&g_pad, 0);
  const HidAnalogStickState right = padGetStickPos(&g_pad, 1);
  // libnx sticks are +Y up; SDL gamepad axes are +Y down.
  SDL_SetJoystickVirtualAxis(g_joystick, SDL_GAMEPAD_AXIS_LEFTX, clamp_axis(left.x));
  SDL_SetJoystickVirtualAxis(g_joystick, SDL_GAMEPAD_AXIS_LEFTY, clamp_axis(-left.y));
  SDL_SetJoystickVirtualAxis(g_joystick, SDL_GAMEPAD_AXIS_RIGHTX, clamp_axis(right.x));
  SDL_SetJoystickVirtualAxis(g_joystick, SDL_GAMEPAD_AXIS_RIGHTY, clamp_axis(-right.y));
  // ZL/ZR are digital on every Switch controller.
  SDL_SetJoystickVirtualAxis(g_joystick, SDL_GAMEPAD_AXIS_LEFT_TRIGGER,
                             (held & HidNpadButton_ZL) != 0 ? SDL_JOYSTICK_AXIS_MAX : 0);
  SDL_SetJoystickVirtualAxis(g_joystick, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER,
                             (held & HidNpadButton_ZR) != 0 ? SDL_JOYSTICK_AXIS_MAX : 0);
}

} // namespace

void attach() noexcept {
  if (g_joystick != nullptr) {
    return;
  }
  // Accept every player slot: supporting only No1+Handheld makes HID disconnect any other
  // controller at launch (including a sys-autopilot virtual Pro Controller), and the pad merges
  // all of them into one input.
  padConfigureInput(8, HidNpadStyleSet_NpadStandard);
  padInitializeAny(&g_pad);

  SDL_VirtualJoystickDesc desc;
  SDL_INIT_INTERFACE(&desc);
  desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
  desc.naxes = kAxisCount;
  desc.nbuttons = kButtonCount;
  desc.button_mask = (1u << kButtonCount) - 1u;
  desc.axis_mask = (1u << kAxisCount) - 1u;
  desc.name = "Nintendo Switch Controller";
  desc.Update = update;
  g_joystickId = SDL_AttachVirtualJoystick(&desc);
  if (g_joystickId == 0) {
    return;
  }
  g_joystick = SDL_OpenJoystick(g_joystickId);
}

void detach() noexcept {
  if (g_joystick != nullptr) {
    SDL_CloseJoystick(g_joystick);
    g_joystick = nullptr;
  }
  if (g_joystickId != 0) {
    SDL_DetachVirtualJoystick(g_joystickId);
    g_joystickId = 0;
  }
}

} // namespace aurora::input::switch_pad
