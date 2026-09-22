#pragma once

namespace aurora::input::switch_pad {

// Exposes the Switch's player-1 controller as an SDL virtual gamepad. Call after
// SDL_INIT_JOYSTICK | SDL_INIT_GAMEPAD.
void attach() noexcept;
void detach() noexcept;

} // namespace aurora::input::switch_pad
