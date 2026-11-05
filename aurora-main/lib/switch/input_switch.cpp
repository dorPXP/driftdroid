// Switch controller input. SDL3 has no Switch joystick backend, so the console's controllers are
// exposed to the rest of aurora as SDL virtual gamepads, one per player: everything downstream
// (mapping, port assignment, events) sees ordinary SDL_Gamepads.
//
// Player 1 is the console's player-1 slot plus the attached Joy-Cons (handheld), and is always
// present. Players 2-4 are the console's slots 2-4 and appear and disappear as controllers
// connect, in the order the console itself numbers them (the player lights on each controller).
#include "input_switch.hpp"

#include <SDL3/SDL_gamepad.h>
#include <SDL3/SDL_joystick.h>
#include <switch.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <iterator>

extern "C" void AuroraSwitchRestoreControllers();  // end of file

namespace aurora::input::switch_pad {
namespace {

constexpr size_t kPlayers = 4;
constexpr size_t kSentAxisCount = 6;

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

// One Joy-Con held sideways, one per player (paired on the controller screen with SL+SR). HID
// reports its buttons, stick and motion sensor as if it were upright, like devkitPro's SDL2 port
// assumes (SDL_sysjoystick.c), so they are turned here: the left Joy-Con is held turned a quarter
// anticlockwise (stick on the left), the right one a quarter clockwise (stick on the right).
enum class SingleJoyCon { None, Left, Right };

SingleJoyCon single_joycon(u32 style) {
  // A pair, a Pro Controller or the console itself is never a sideways Joy-Con, even when the
  // player's pad also covers a slot a single Joy-Con is in.
  if ((style & (HidNpadStyleTag_NpadJoyDual | HidNpadStyleTag_NpadFullKey | HidNpadStyleTag_NpadHandheld)) != 0) {
    return SingleJoyCon::None;
  }
  if ((style & HidNpadStyleTag_NpadJoyLeft) != 0) {
    return SingleJoyCon::Left;
  }
  if ((style & HidNpadStyleTag_NpadJoyRight) != 0) {
    return SingleJoyCon::Right;
  }
  return SingleJoyCon::None;
}

struct SidewaysButton {
  u64 physical;  // the button's own name, as HID reports it
  u64 as;        // the full-controller button it stands for, held sideways
};

// The four face buttons by position (A east, B south, X north, Y west, as on a full controller);
// SL/SR are the shoulders, used as ZL/ZR so they reach the game's L (item) and R (drift) as on
// Mario Kart 8; the stick click is the D-pad's Up (trick and wheelie). The one +/- button is not
// in these tables: a tap is Start (pause) and holding it opens the quick panel, see update().
constexpr SidewaysButton kLeftSideways[] = {
    {HidNpadButton_Down, HidNpadButton_A},       {HidNpadButton_Left, HidNpadButton_B},
    {HidNpadButton_Right, HidNpadButton_X},      {HidNpadButton_Up, HidNpadButton_Y},
    {HidNpadButton_LeftSL, HidNpadButton_ZL},    {HidNpadButton_LeftSR, HidNpadButton_ZR},
    {HidNpadButton_StickL, HidNpadButton_Up},
};
constexpr SidewaysButton kRightSideways[] = {
    {HidNpadButton_X, HidNpadButton_A},          {HidNpadButton_A, HidNpadButton_B},
    {HidNpadButton_Y, HidNpadButton_X},          {HidNpadButton_B, HidNpadButton_Y},
    {HidNpadButton_RightSL, HidNpadButton_ZL},   {HidNpadButton_RightSR, HidNpadButton_ZR},
    {HidNpadButton_StickR, HidNpadButton_Up},
};

// A sideways Joy-Con has neither two sticks to click nor a Minus on the right one, so its +/- button
// also opens the settings: held this long it presses Back (the quick panel, which leads to all
// settings); a shorter press is Start, sent when the button is released.
constexpr std::chrono::milliseconds kMenuHold{700};
constexpr std::chrono::milliseconds kMenuPulse{100};  // how long the Start or Back press lasts

u64 sideways_buttons(SingleJoyCon joycon, u64 held) {
  u64 out = 0;
  if (joycon == SingleJoyCon::Left) {
    for (const SidewaysButton& b : kLeftSideways) {
      out |= (held & b.physical) != 0 ? b.as : 0;
    }
  } else {
    for (const SidewaysButton& b : kRightSideways) {
      out |= (held & b.physical) != 0 ? b.as : 0;
    }
  }
  return out;
}

// SDL's virtual joystick keeps the last value it was given, and every SDL_SetJoystickVirtual*
// call looks the joystick up under SDL's object rwlock. Sending all 21 inputs on every update was
// ~0.9% of a race frame in lock traffic, so only send the ones that changed.
struct Player {
  PadState pad{};
  // Vibration devices of the controller this player last rumbled with (re-made when it changes).
  HidVibrationDeviceHandle vibration[2]{};
  HidNpadIdType vibrationId = HidNpadIdType_No1;
  u32 vibrationStyle = 0;
  s32 vibrationCount = 0;
  SDL_Joystick* joystick = nullptr;
  SDL_JoystickID joystickId = 0;
  bool sentButtons[std::size(kButtons)]{};
  Sint16 sentAxes[kSentAxisCount]{};
  bool sentAny = false;
  // Any button held, for the settings menu's player list (read on another thread).
  std::atomic<bool> pressing{false};
  // A sideways Joy-Con's +/- button: when it went down, whether the long press already fired, and
  // until when the Start tap or the Back press it caused is held.
  bool menuHeld = false;
  bool menuLongFired = false;
  std::chrono::steady_clock::time_point menuSince{};
  std::chrono::steady_clock::time_point tapUntil{};
  std::chrono::steady_clock::time_point panelUntil{};
};

Player g_players[kPlayers];
bool g_attached = false;
// Players 2-4 are looked for a few times a second, not on every poll.
uint32_t g_updateCount = 0;

constexpr const char* kNames[kPlayers] = {
    "Nintendo Switch Controller",
    "Nintendo Switch Controller (Player 2)",
    "Nintendo Switch Controller (Player 3)",
    "Nintendo Switch Controller (Player 4)",
};

void SDLCALL update(void* userdata);
bool SDLCALL rumble(void* userdata, Uint16 low, Uint16 high);

void open_player(size_t index) {
  Player& player = g_players[index];
  if (player.joystick != nullptr) {
    return;
  }
  SDL_VirtualJoystickDesc desc;
  SDL_INIT_INTERFACE(&desc);
  desc.type = SDL_JOYSTICK_TYPE_GAMEPAD;
  desc.naxes = kAxisCount;
  desc.nbuttons = kButtonCount;
  desc.button_mask = (1u << kButtonCount) - 1u;
  desc.axis_mask = (1u << kAxisCount) - 1u;
  desc.name = kNames[index];
  desc.userdata = &player;
  desc.Update = update;
  desc.Rumble = rumble;
  player.joystickId = SDL_AttachVirtualJoystick(&desc);
  if (player.joystickId == 0) {
    return;
  }
  player.joystick = SDL_OpenJoystick(player.joystickId);
  if (player.joystick != nullptr) {
    // The game's controller port follows the console's player number.
    SDL_SetJoystickPlayerIndex(player.joystick, static_cast<int>(index));
  }
  player.sentAny = false;  // a fresh joystick has none of the cached values yet
}

void close_player(size_t index) {
  Player& player = g_players[index];
  if (player.joystick != nullptr) {
    SDL_CloseJoystick(player.joystick);
    player.joystick = nullptr;
  }
  if (player.joystickId != 0) {
    SDL_DetachVirtualJoystick(player.joystickId);
    player.joystickId = 0;
  }
}

// Tilt steering, for each player who has it on: the roll of the console (handheld) or controller,
// read from its accelerometer as the direction of gravity, replaces the left stick's X axis. Same
// response curve as the Android app (MotionSteering.kt): a small dead zone, then linear to full
// lock. Players 2-4 were added 2026-10-27 (each controller's own sensor).
std::atomic<unsigned> g_motionPlayers{0};  // bit n = player n+1
std::atomic<bool> g_motionInverted{false};
std::atomic<float> g_motionSensitivity{1.0f};
std::atomic<unsigned> g_motionRecenter{0xFu};  // players whose "straight ahead" is taken next
// Set by the runtime each frame: tilt only steers while the player drives a race. In menus a
// controller held slightly tilted moved the cursor by itself.
std::atomic<bool> g_motionDriving{false};
// The settings menu is open: the game is not listening, and tilt read as a held stick direction
// there, which the menu's navigation put before D-pad presses (they seemed to lag or get lost).
std::atomic<bool> g_motionMenuOpen{false};
std::atomic<unsigned> g_motionSeen{0};       // players whose sensor has answered
std::atomic<float> g_motionValue[kPlayers]{}; // last reading, for the settings menu's tilt bar

struct MotionSensors {
  // handheld (player 1 only), full controller, Joy-Con pair (left, right), single left, single right
  HidSixAxisSensorHandle handles[6]{};
  bool started = false;
  u64 styles = 0;  // the controller styles when the sensors were last started
  float center = 0.0f;
  u32 loggedStyle = 0xFFFFFFFFu;
};
MotionSensors g_motion[kPlayers];

constexpr HidNpadIdType kSlotIds[kPlayers] = {HidNpadIdType_No1, HidNpadIdType_No2, HidNpadIdType_No3,
                                              HidNpadIdType_No4};

// Started again whenever the player's controller changes: a sensor started before its
// controller was connected stays silent, so a Pro Controller picked up mid-game had no tilt or
// shake until the app restarted (2026-10-24).
void start_motion_sensors(size_t index) {
  MotionSensors& motion = g_motion[index];
  const HidNpadIdType id = kSlotIds[index];
  const u64 handheld = index == 0 ? hidGetNpadStyleSet(HidNpadIdType_Handheld) : 0;
  const u64 styles = (handheld << 32) | hidGetNpadStyleSet(id);
  if (motion.started && styles == motion.styles) {
    return;
  }
  if (motion.started) {
    for (const HidSixAxisSensorHandle& handle : motion.handles) {
      hidStopSixAxisSensor(handle);
    }
  }
  motion.started = true;
  motion.styles = styles;
  std::printf("[switch-pad] player %zu motion sensors (re)started: handheld styles %#x, slot styles %#x\n",
              index + 1, static_cast<unsigned>(styles >> 32), static_cast<unsigned>(styles));
  if (index == 0) {
    hidGetSixAxisSensorHandles(&motion.handles[0], 1, HidNpadIdType_Handheld, HidNpadStyleTag_NpadHandheld);
  }
  hidGetSixAxisSensorHandles(&motion.handles[1], 1, id, HidNpadStyleTag_NpadFullKey);
  hidGetSixAxisSensorHandles(&motion.handles[2], 2, id, HidNpadStyleTag_NpadJoyDual);
  hidGetSixAxisSensorHandles(&motion.handles[4], 1, id, HidNpadStyleTag_NpadJoyLeft);
  hidGetSixAxisSensorHandles(&motion.handles[5], 1, id, HidNpadStyleTag_NpadJoyRight);
  for (size_t i = index == 0 ? 0 : 1; i < std::size(motion.handles); ++i) {
    hidStartSixAxisSensor(motion.handles[i]);
  }
}

// The accelerometer (in g) of the console or controller a player is using, or false.
bool read_acceleration(size_t index, const PadState& pad, HidVector& out) {
  start_motion_sensors(index);
  MotionSensors& motion = g_motion[index];
  const u32 style = padGetStyleSet(&pad);
  // A Joy-Con pair is read from the right Joy-Con, or the left one if the right gives nothing.
  int which[2] = {-1, -1};
  const SingleJoyCon single = single_joycon(style);
  if (single == SingleJoyCon::Left) {
    which[0] = 4;
  } else if (single == SingleJoyCon::Right) {
    which[0] = 5;
  } else if (index == 0 && (style & HidNpadStyleTag_NpadHandheld) != 0) {
    which[0] = 0;
  } else if ((style & HidNpadStyleTag_NpadFullKey) != 0) {
    which[0] = 1;
  } else if ((style & HidNpadStyleTag_NpadJoyDual) != 0) {
    which[0] = 3;
    which[1] = 2;
  }
  int used = -1;
  HidSixAxisSensorState state{};
  for (const int candidate : which) {
    if (candidate >= 0 && hidGetSixAxisSensorStates(motion.handles[candidate], &state, 1) != 0) {
      used = candidate;
      break;
    }
  }
  if (style != motion.loggedStyle) {
    motion.loggedStyle = style;
    std::printf("[switch-pad] player %zu motion: style %#x, sensor %d %s\n", index + 1, style, used,
                used >= 0 ? "answers" : "gives nothing");
  }
  if (used < 0) {
    return false;
  }
  out = state.acceleration;
  // Sideways, the Joy-Con's long axis (y) is the player's left-right: its top (the stick end of
  // the left Joy-Con) points left, the right Joy-Con's top points right. Tilt and shake then read
  // a sideways Joy-Con turned like a wheel the way they read an upright controller rolled.
  if (single == SingleJoyCon::Left) {
    out = {-state.acceleration.y, state.acceleration.x, state.acceleration.z};
  } else if (single == SingleJoyCon::Right) {
    out = {state.acceleration.y, -state.acceleration.x, state.acceleration.z};
  }
  return true;
}

// Steering from -1 (full left) to 1, or false when no sensor answers. "Straight ahead" is only
// taken while driving, so turning tilt on in the menu does not centre on how the menu was held.
bool motion_steering(size_t index, const PadState& pad, bool driving, float& out) {
  HidVector acceleration{};
  if (!read_acceleration(index, pad, acceleration)) {
    return false;
  }
  const float x = acceleration.x;
  const float rest = std::hypot(acceleration.y, acceleration.z);
  if (std::hypot(x, rest) < 0.08f) {
    return false;  // free fall or a glitch: no usable direction
  }
  g_motionSeen.fetch_or(1u << index, std::memory_order_relaxed);
  // Roll: how far gravity leans to the device's left or right, whatever its pitch.
  const float angle = std::atan2(x, rest);
  MotionSensors& motion = g_motion[index];
  if (driving && (g_motionRecenter.fetch_and(~(1u << index), std::memory_order_relaxed) & (1u << index)) != 0) {
    motion.center = angle;
  }
  constexpr float kDeadZone = 0.045f;
  const float sensitivity = std::fmin(2.0f, std::fmax(0.5f, g_motionSensitivity.load(std::memory_order_relaxed)));
  const float delta = angle - motion.center;
  const float magnitude = std::fabs(delta);
  if (magnitude <= kDeadZone) {
    out = 0.0f;
    return true;
  }
  const float fullLock = 0.70f / sensitivity;
  float value = std::fmin(1.0f, (magnitude - kDeadZone) / (fullLock - kDeadZone));
  value = delta < 0.0f ? -value : value;
  out = g_motionInverted.load(std::memory_order_relaxed) ? -value : value;
  return true;
}

// Shake to trick, for each player who has it on: a shake while driving presses the D-pad (the
// game's trick button) for a moment, in the direction of the shake, so up, down, left and right
// give the same tricks (and wheelies) as the D-pad. It used to hand player 1 to an emulated Wii
// Wheel, but the game keeps the controller type it started with: switching it on or off mid-game
// left the game waiting for the other controller (2026-10-27). In menus nothing is pressed.
std::atomic<unsigned> g_shakePlayers{0};  // bit n = player n+1

struct ShakeState {
  float magnitude = 1.0f;         // smoothed strength of the acceleration (1 g at rest)
  HidVector gravity{0.0f, 0.0f, 0.0f};  // smoothed acceleration: the direction of gravity
  bool primed = false;
  std::chrono::steady_clock::time_point last{};
  std::chrono::steady_clock::time_point pulseUntil{};
  std::chrono::steady_clock::time_point upUntil{};  // the Up that follows a sideways or down press
  std::chrono::steady_clock::time_point quietUntil{};
  SDL_GamepadButton pulse = SDL_GAMEPAD_BUTTON_INVALID;
};
ShakeState g_shake[kPlayers];

// Thresholds from the Wii Wheel version: 0.5 g of change in strength is a shake (a brisk wrist
// turn while tilt steering adds up to ~0.3 g); one press per shake.
constexpr float kShakeThresholdG = 0.55f;
constexpr auto kShakePress = std::chrono::milliseconds(120);
// A sideways or down shake presses its direction this long, then Up for kShakePress. In the air
// the game takes the first direction for the trick and ignores the rest; on the ground the other
// directions do nothing and the Up is a wheelie, so any shake on the ground wheelies ("you have
// to shake the controller UP to do a wheelie which is harder", 2026-10-27).
constexpr auto kShakeDirectionPress = std::chrono::milliseconds(80);
constexpr auto kShakeQuiet = std::chrono::milliseconds(400);

// The D-pad button a shake is pressing right now for this player, or INVALID.
SDL_GamepadButton update_shake(size_t index, const PadState& pad, bool driving) {
  ShakeState& shake = g_shake[index];
  const auto now = std::chrono::steady_clock::now();
  HidVector a{};
  if (!driving || !read_acceleration(index, pad, a)) {
    shake.primed = false;
    shake.pulse = SDL_GAMEPAD_BUTTON_INVALID;
    shake.upUntil = {};
    return SDL_GAMEPAD_BUTTON_INVALID;
  }
  constexpr float kGravitySmoothingSeconds = 0.25f;
  const float dt = std::chrono::duration<float>(now - shake.last).count();
  shake.last = now;
  const float magnitude = std::sqrt(a.x * a.x + a.y * a.y + a.z * a.z);
  if (!shake.primed || dt <= 0.0f || dt > 0.5f) {
    shake.primed = true;
    shake.magnitude = magnitude;
    shake.gravity = a;
    return SDL_GAMEPAD_BUTTON_INVALID;
  }
  // Only the strength of the acceleration decides whether it is a shake: tilting to steer turns
  // gravity across the axes (a quick 30-degree tilt of a Joy-Con read as a 0.5 g "shake" and did
  // wheelies) but leaves it at 1 g; a shake pushes the controller and changes the strength.
  const float change = std::fabs(magnitude - shake.magnitude);
  if (now >= shake.quietUntil && change > kShakeThresholdG) {
    // Direction: the push away from gravity. Along gravity is up or down, whichever way the
    // sensor counts (lifting adds to the reading along its own rest direction either way);
    // across it, the controller's own left-right axis.
    const HidVector d{a.x - shake.gravity.x, a.y - shake.gravity.y, a.z - shake.gravity.z};
    const float gLength = std::sqrt(shake.gravity.x * shake.gravity.x + shake.gravity.y * shake.gravity.y +
                                    shake.gravity.z * shake.gravity.z);
    float vertical = 0.0f;
    float sideways = d.x;
    if (gLength > 0.3f) {
      const HidVector up{shake.gravity.x / gLength, shake.gravity.y / gLength, shake.gravity.z / gLength};
      vertical = d.x * up.x + d.y * up.y + d.z * up.z;
      sideways = d.x - vertical * up.x;
    }
    // Up unless the shake was clearly sideways or downwards: up is the usual flick and the wheelie.
    if (std::fabs(sideways) > std::fabs(vertical) * 1.3f) {
      shake.pulse = sideways > 0.0f ? SDL_GAMEPAD_BUTTON_DPAD_RIGHT : SDL_GAMEPAD_BUTTON_DPAD_LEFT;
    } else if (vertical < -0.35f) {
      shake.pulse = SDL_GAMEPAD_BUTTON_DPAD_DOWN;
    } else {
      shake.pulse = SDL_GAMEPAD_BUTTON_DPAD_UP;
    }
    if (shake.pulse == SDL_GAMEPAD_BUTTON_DPAD_UP) {
      shake.pulseUntil = now + kShakePress;
      shake.upUntil = shake.pulseUntil;
    } else {
      shake.pulseUntil = now + kShakeDirectionPress;
      shake.upUntil = shake.pulseUntil + kShakePress;
    }
    shake.quietUntil = std::max(now + kShakeQuiet, shake.upUntil);
    std::printf("[switch-pad] player %zu shake -> %s (up %.2f, side %.2f, change %.2f g)\n", index + 1,
                shake.pulse == SDL_GAMEPAD_BUTTON_DPAD_UP     ? "up"
                : shake.pulse == SDL_GAMEPAD_BUTTON_DPAD_DOWN ? "down"
                : shake.pulse == SDL_GAMEPAD_BUTTON_DPAD_LEFT ? "left"
                                                              : "right",
                vertical, sideways, change);
  }
  // The smoothing only follows calm readings, so a shake does not drag "gravity" with it.
  if (now >= shake.quietUntil || change < kShakeThresholdG * 0.5f) {
    const float alpha = dt / (kGravitySmoothingSeconds + dt);
    shake.magnitude += alpha * (magnitude - shake.magnitude);
    shake.gravity.x += alpha * (a.x - shake.gravity.x);
    shake.gravity.y += alpha * (a.y - shake.gravity.y);
    shake.gravity.z += alpha * (a.z - shake.gravity.z);
  }
  if (now < shake.pulseUntil) {
    return shake.pulse;
  }
  shake.pulse = SDL_GAMEPAD_BUTTON_INVALID;
  return now < shake.upUntil ? SDL_GAMEPAD_BUTTON_DPAD_UP : SDL_GAMEPAD_BUTTON_INVALID;
}

// Rumble: the game's motor on/off (PADControlMotor -> SDL_RumbleGamepad) drives the vibration of
// the controller the player is holding, or of both Joy-Cons in handheld. Same signal SDL sends the
// same controllers on PC (SDL_hidapi_switch.c): BOTH bands at about 150 Hz (codes 0x74 / 0x3D),
// which is what gives it its depth; a 320 Hz high band felt thin.
bool SDLCALL rumble(void* userdata, Uint16 low, Uint16 high) {
  Player& player = *static_cast<Player*>(userdata);
  static constexpr HidNpadIdType kIds[kPlayers] = {HidNpadIdType_No1, HidNpadIdType_No2, HidNpadIdType_No3,
                                                   HidNpadIdType_No4};
  const size_t index = static_cast<size_t>(&player - g_players);
  HidNpadIdType id = kIds[index];
  u32 styleSet = hidGetNpadStyleSet(id);
  if (index == 0 && (padGetStyleSet(&player.pad) & HidNpadStyleTag_NpadHandheld) != 0) {
    id = HidNpadIdType_Handheld;
    styleSet = hidGetNpadStyleSet(id);
  }
  // One style, with the number of vibration motors it has.
  static constexpr struct {
    u32 style;
    s32 count;
  } kStyles[] = {
      {HidNpadStyleTag_NpadFullKey, 2}, {HidNpadStyleTag_NpadHandheld, 2}, {HidNpadStyleTag_NpadJoyDual, 2},
      {HidNpadStyleTag_NpadJoyLeft, 1}, {HidNpadStyleTag_NpadJoyRight, 1},
  };
  u32 style = 0;
  s32 count = 0;
  for (const auto& candidate : kStyles) {
    if ((styleSet & candidate.style) != 0) {
      style = candidate.style;
      count = candidate.count;
      break;
    }
  }
  if (style == 0) {
    return false;
  }
  if (player.vibrationCount == 0 || player.vibrationId != id || player.vibrationStyle != style) {
    const Result result =
        hidInitializeVibrationDevices(player.vibration, count, id, static_cast<HidNpadStyleTag>(style));
    std::printf("[switch-pad] player %zu rumble -> slot %d, style %#x, %d motors: %s\n", index + 1,
                static_cast<int>(id), style, count, R_SUCCEEDED(result) ? "ok" : "failed");
    if (R_FAILED(result)) {
      player.vibrationCount = 0;
      return false;
    }
    player.vibrationId = id;
    player.vibrationStyle = style;
    player.vibrationCount = count;
  }
  HidVibrationValue values[2];
  for (HidVibrationValue& value : values) {
    value.amp_low = static_cast<float>(low) / 65535.0f;
    value.freq_low = 150.0f;
    value.amp_high = static_cast<float>(high) / 65535.0f;
    value.freq_high = 150.0f;
  }
  return R_SUCCEEDED(hidSendVibrationValues(player.vibration, values, player.vibrationCount));
}

void set_axis(Player& player, size_t slot, SDL_GamepadAxis axis, Sint16 value) {
  if (!player.sentAny || player.sentAxes[slot] != value) {
    player.sentAxes[slot] = value;
    SDL_SetJoystickVirtualAxis(player.joystick, axis, value);
  }
}

void SDLCALL update(void* userdata) {
  Player& player = *static_cast<Player*>(userdata);
  if (player.joystick == nullptr) {
    return;
  }
  padUpdate(&player.pad);
  const SingleJoyCon single = single_joycon(padGetStyleSet(&player.pad));
  const u64 raw = padGetButtons(&player.pad);
  u64 held = single == SingleJoyCon::None ? raw : sideways_buttons(single, raw);
  if (single != SingleJoyCon::None) {
    // The +/- button: a tap is Start (sent on release, once it is known not to be a long press),
    // holding it presses Back, which opens the quick panel as Minus does on a full controller.
    const u64 menuButton = single == SingleJoyCon::Left ? HidNpadButton_Minus : HidNpadButton_Plus;
    const auto now = std::chrono::steady_clock::now();
    if ((raw & menuButton) != 0) {
      if (!player.menuHeld) {
        player.menuHeld = true;
        player.menuLongFired = false;
        player.menuSince = now;
      } else if (!player.menuLongFired && now - player.menuSince >= kMenuHold) {
        player.menuLongFired = true;
        player.panelUntil = now + kMenuPulse;
      }
    } else if (player.menuHeld) {
      if (!player.menuLongFired) {
        player.tapUntil = now + kMenuPulse;
      }
      player.menuHeld = false;
      player.menuLongFired = false;
    }
    if (now < player.tapUntil) {
      held |= HidNpadButton_Plus;  // Start
    }
    if (now < player.panelUntil) {
      held |= HidNpadButton_Minus;  // Back
    }
  } else {
    player.menuHeld = false;
  }
  player.pressing.store(raw != 0, std::memory_order_relaxed);
  const size_t index = static_cast<size_t>(&player - g_players);
  const bool driving = g_motionDriving.load(std::memory_order_relaxed);
  const bool menuOpen = g_motionMenuOpen.load(std::memory_order_relaxed);
  if (single != SingleJoyCon::None && menuOpen) {
    // A sideways Joy-Con has no D-pad (its four buttons are A/B/X/Y), so while a settings menu is
    // open its stick, turned the way the Joy-Con is held, also presses the D-pad: the menu is
    // navigated with those. One direction at a time, the larger of the two.
    const HidAnalogStickState stick = padGetStickPos(&player.pad, single == SingleJoyCon::Left ? 0 : 1);
    const s32 sx = single == SingleJoyCon::Left ? -stick.y : stick.y;
    const s32 sy = single == SingleJoyCon::Left ? stick.x : -stick.x;
    constexpr s32 kMenuStick = SDL_JOYSTICK_AXIS_MAX / 2;
    // SL and SR are the shoulder buttons (the game sees them as ZL and ZR); the menu switches pages
    // with L and R, so they press those too while it is open.
    if ((held & HidNpadButton_ZL) != 0) {
      held |= HidNpadButton_L;
    }
    if ((held & HidNpadButton_ZR) != 0) {
      held |= HidNpadButton_R;
    }
    if (std::abs(sx) >= std::abs(sy)) {
      if (sx <= -kMenuStick) {
        held |= HidNpadButton_Left;
      } else if (sx >= kMenuStick) {
        held |= HidNpadButton_Right;
      }
    } else if (sy >= kMenuStick) {
      held |= HidNpadButton_Up;
    } else if (sy <= -kMenuStick) {
      held |= HidNpadButton_Down;
    }
  }
  SDL_GamepadButton shakePress = SDL_GAMEPAD_BUTTON_INVALID;
  if ((g_shakePlayers.load(std::memory_order_relaxed) & (1u << index)) != 0) {
    shakePress = update_shake(index, player.pad, driving && !menuOpen);
  }
  for (size_t i = 0; i < std::size(kButtons); ++i) {
    const bool down = (held & kButtons[i].hid) != 0 || kButtons[i].sdl == shakePress;
    if (!player.sentAny || player.sentButtons[i] != down) {
      player.sentButtons[i] = down;
      SDL_SetJoystickVirtualButton(player.joystick, kButtons[i].sdl, down);
    }
  }
  HidAnalogStickState left = padGetStickPos(&player.pad, 0);
  HidAnalogStickState right = padGetStickPos(&player.pad, 1);
  // A sideways Joy-Con's one stick, turned with the Joy-Con, is the player's left stick.
  if (single == SingleJoyCon::Left) {
    left = {-left.y, left.x};
    right = {};
  } else if (single == SingleJoyCon::Right) {
    left = {right.y, -right.x};
    right = {};
  }
  // libnx sticks are +Y up; SDL gamepad axes are +Y down.
  s32 steerX = left.x;
  // Tilt steers a player who has it on, unless the stick itself is pushed sideways (menus stay
  // usable). It is also read in the settings menu, for its tilt bar.
  if ((g_motionPlayers.load(std::memory_order_relaxed) & (1u << index)) != 0) {
    float tilt = 0.0f;
    if ((driving || menuOpen) && motion_steering(index, player.pad, driving && !menuOpen, tilt)) {
      g_motionValue[index].store(tilt, std::memory_order_relaxed);
      if (driving && !menuOpen && std::abs(left.x) < SDL_JOYSTICK_AXIS_MAX / 3) {
        steerX = static_cast<s32>(tilt * static_cast<float>(SDL_JOYSTICK_AXIS_MAX));
      }
    }
  }
  set_axis(player, 0, SDL_GAMEPAD_AXIS_LEFTX, clamp_axis(steerX));
  set_axis(player, 1, SDL_GAMEPAD_AXIS_LEFTY, clamp_axis(-left.y));
  set_axis(player, 2, SDL_GAMEPAD_AXIS_RIGHTX, clamp_axis(right.x));
  set_axis(player, 3, SDL_GAMEPAD_AXIS_RIGHTY, clamp_axis(-right.y));
  // ZL/ZR are digital on every Switch controller.
  set_axis(player, 4, SDL_GAMEPAD_AXIS_LEFT_TRIGGER, (held & HidNpadButton_ZL) != 0 ? SDL_JOYSTICK_AXIS_MAX : 0);
  set_axis(player, 5, SDL_GAMEPAD_AXIS_RIGHT_TRIGGER, (held & HidNpadButton_ZR) != 0 ? SDL_JOYSTICK_AXIS_MAX : 0);
  player.sentAny = true;
}

} // namespace

void attach() noexcept {
  if (g_attached) {
    return;
  }
  g_attached = true;
  std::atexit(AuroraSwitchRestoreControllers);
  // Accept every player slot: supporting only No1+Handheld makes HID disconnect any other
  // controller at launch. Slots 5-8 have no port in the game, so they steer player 1 as before.
  padConfigureInput(8, HidNpadStyleSet_NpadStandard);
  // Single Joy-Cons are held sideways; the controller screen then offers "press SL and SR".
  // Sideways mode splits a Joy-Con pair, so it is not forced on a console whose first controller is
  // a pair, a Pro Controller or the handheld Joy-Cons: the controller screen sets it when the
  // player asks to hand out single Joy-Cons. Already-single Joy-Cons need it to work at all.
  {
    constexpr u32 kWholeController = HidNpadStyleTag_NpadJoyDual | HidNpadStyleTag_NpadFullKey |
                                     HidNpadStyleTag_NpadHandheld;
    const u32 first = hidGetNpadStyleSet(HidNpadIdType_No1) | hidGetNpadStyleSet(HidNpadIdType_Handheld);
    if ((first & kWholeController) == 0) {
      hidSetNpadJoyHoldType(HidNpadJoyHoldType_Horizontal);
    } else {
      std::fprintf(stderr, "[switch-pad] launch: first controller is a pair/Pro/handheld, hold type left alone\n");
    }
  }
  padInitialize(&g_players[0].pad, HidNpadIdType_No1, HidNpadIdType_Handheld, HidNpadIdType_No5, HidNpadIdType_No6,
                HidNpadIdType_No7, HidNpadIdType_No8);
  padInitialize(&g_players[1].pad, HidNpadIdType_No2);
  padInitialize(&g_players[2].pad, HidNpadIdType_No3);
  padInitialize(&g_players[3].pad, HidNpadIdType_No4);
  open_player(0);
  poll_connections();
}

void poll_connections() noexcept {
  if (!g_attached) {
    return;
  }
  if ((g_updateCount++ % 20u) != 0) {
    return;
  }
  static constexpr HidNpadIdType kIds[kPlayers] = {HidNpadIdType_No1, HidNpadIdType_No2, HidNpadIdType_No3,
                                                   HidNpadIdType_No4};
  for (size_t i = 1; i < kPlayers; ++i) {
    const bool connected = hidGetNpadStyleSet(kIds[i]) != 0;
    if (connected && g_players[i].joystick == nullptr) {
      open_player(i);
    } else if (!connected && g_players[i].joystick != nullptr) {
      close_player(i);
    }
  }
}

void detach() noexcept {
  for (size_t i = 0; i < kPlayers; ++i) {
    close_player(i);
  }
  g_attached = false;
}

} // namespace aurora::input::switch_pad

// Puts the console's hold type back to the default, for the end of the process.
//
// This only resets the hold type. It used to also join two single Joy-Cons back into a pair
// (hidMergeSingleJoyAsDualJoy, as libnx suggests); on third-party Joy-Cons that made the console
// drop BOTH of them (2026-11-04 test), so it is not done. A split pair is joined again from the
// console's Controllers > Change Grip/Order screen, or by clipping the Joy-Cons onto the rails.
// Safe to call twice.
extern "C" void AuroraSwitchRestoreControllers() {
  static std::atomic<bool> done{false};
  if (done.exchange(true)) {
    return;
  }
  hidSetNpadJoyHoldType(HidNpadJoyHoldType_Vertical);
  std::fprintf(stderr, "[switch-pad] exit: hold type restored\n");
}

// Called from the settings menu (runtime/src/settings_overlay.cpp).
extern "C" void AuroraSwitchSetMotionSteering(int enabled, float sensitivity, int inverted) {
  using namespace aurora::input::switch_pad;
  // enabled: bit n = player n+1 (a saved "1" from before players 2-4 is player 1 alone).
  const unsigned players = static_cast<unsigned>(enabled) & 0xFu;
  const unsigned was = g_motionPlayers.exchange(players, std::memory_order_relaxed);
  g_motionSensitivity.store(sensitivity, std::memory_order_relaxed);
  g_motionInverted.store(inverted != 0, std::memory_order_relaxed);
  // Straight ahead = how a newly enabled player holds it when they next drive.
  g_motionRecenter.fetch_or(players & ~was, std::memory_order_relaxed);
}

// Called by the settings menu: which players shake to trick (bit n = player n+1).
extern "C" void AuroraSwitchSetShakePlayers(int players) {
  aurora::input::switch_pad::g_shakePlayers.store(static_cast<unsigned>(players) & 0xFu, std::memory_order_relaxed);
}

// Called by the runtime every pad read (runtime/src/hle/input/pad.cpp): a race is under way and not
// paused.
extern "C" void AuroraSwitchSetDriving(int driving) {
  aurora::input::switch_pad::g_motionDriving.store(driving != 0, std::memory_order_relaxed);
}

// Called by the settings menu when it opens or closes (runtime/src/settings_overlay.cpp).
extern "C" void AuroraSwitchSetMenuOpen(int open) {
  aurora::input::switch_pad::g_motionMenuOpen.store(open != 0, std::memory_order_relaxed);
}

extern "C" void AuroraSwitchRecenterMotionSteering() {
  aurora::input::switch_pad::g_motionRecenter.store(0xFu, std::memory_order_relaxed);
}

// Bit n set once player n+1's tilt sensor has delivered a reading.
extern "C" int AuroraSwitchMotionSteeringSeen() {
  return static_cast<int>(aurora::input::switch_pad::g_motionSeen.load(std::memory_order_relaxed));
}

// Player 1-4's (0-3) last tilt, -1 (full left) to 1, for the settings menu.
extern "C" float AuroraSwitchMotionValue(int player) {
  if (player < 0 || player >= static_cast<int>(aurora::input::switch_pad::kPlayers)) {
    return 0.0f;
  }
  return aurora::input::switch_pad::g_motionValue[player].load(std::memory_order_relaxed);
}

// The settings menu's player list (runtime/src/settings_overlay.cpp, Controls page on Switch).
// Players are the console's own player numbers, the same as the lights on each controller.

// What player 1-4 (0-3) is holding, or nullptr when nobody is.
extern "C" const char* AuroraSwitchPlayerController(int player) {
  static constexpr HidNpadIdType kIds[] = {HidNpadIdType_No1, HidNpadIdType_No2, HidNpadIdType_No3,
                                           HidNpadIdType_No4};
  if (player < 0 || player >= 4) {
    return nullptr;
  }
  u32 style = hidGetNpadStyleSet(kIds[player]);
  if (style == 0 && player == 0) {
    style = hidGetNpadStyleSet(HidNpadIdType_Handheld);  // player 1 also plays in handheld
  }
  if (style == 0) {
    return nullptr;
  }
  if ((style & HidNpadStyleTag_NpadFullKey) != 0) {
    return "Pro Controller";
  }
  if ((style & HidNpadStyleTag_NpadHandheld) != 0) {
    return "Joy-Cons on the console";
  }
  if ((style & HidNpadStyleTag_NpadJoyDual) != 0) {
    return "Two Joy-Cons";
  }
  if ((style & HidNpadStyleTag_NpadJoyLeft) != 0) {
    return "Left Joy-Con";
  }
  if ((style & HidNpadStyleTag_NpadJoyRight) != 0) {
    return "Right Joy-Con";
  }
  if ((style & HidNpadStyleTag_NpadGc) != 0) {
    return "GameCube controller";
  }
  return "Controller";
}

// True while player 1-4 (0-3) holds any button: "press a button to see which player you are".
extern "C" int AuroraSwitchPlayerPressing(int player) {
  using namespace aurora::input::switch_pad;
  if (player < 0 || player >= static_cast<int>(kPlayers) || g_players[player].joystick == nullptr) {
    return 0;
  }
  return g_players[player].pressing.load(std::memory_order_relaxed) ? 1 : 0;
}

// Player colours, shared by the console's controller screen (its player box outlines) and the
// settings menu's player list, so each person finds the same colour in both.
extern "C" const unsigned char kAuroraSwitchPlayerColors[4][3] = {
    {230, 52, 52}, {45, 125, 240}, {240, 195, 30}, {60, 185, 75}};

// The console's own controller screen, the one games show before multiplayer ("press L and R"):
// players join, swap or leave there, and each controller's lights show its player number.
// Blocks until it closes. Handheld is not allowed with more than one player, as in games. A
// Joy-Con held sideways can join as its own player (SL and SR), one Joy-Con per player.
extern "C" int AuroraSwitchShowControllerApplet() {
  hidSetNpadJoyHoldType(HidNpadJoyHoldType_Horizontal);
  HidLaControllerSupportArg arg;
  hidLaCreateControllerSupportArg(&arg);
  arg.hdr.player_count_min = 1;
  arg.hdr.player_count_max = 4;
  arg.hdr.enable_identification_color = 1;
  for (int i = 0; i < 4; ++i) {
    arg.identification_color[i] = {kAuroraSwitchPlayerColors[i][0], kAuroraSwitchPlayerColors[i][1],
                                   kAuroraSwitchPlayerColors[i][2], 255};
  }
  // The version HOME's "Change Grip/Order" uses. The game version (hidLaShowControllerSupport)
  // opened and shut at once with LibAppletBadExit (0x5d59) every time on the console (2026-10-27).
  HidLaControllerSupportResultInfo info{};
  const Result rc = hidLaShowControllerSupportForSystem(&info, &arg, false);
  std::printf("[switch-pad] controller screen: rc %#x, %d player(s)\n", static_cast<unsigned>(rc),
              static_cast<int>(info.player_count));
  return R_SUCCEEDED(rc) ? 1 : 0;
}
