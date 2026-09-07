#include "runtime_product.h"

// Used only by the combined Android library (both products in one .so). Desktop targets
// keep linking base_product.cpp / retro_rewind_product.cpp instead - see runtime_product.h.

namespace RuntimeProduct {

namespace {
Kind g_activeKind = Kind::BaseGame;
}

void SetActive(Kind kind) noexcept {
    g_activeKind = kind;
}

const Descriptor& Active() noexcept {
    static constexpr Descriptor kBase{Kind::BaseGame, "WiiCompiled"};
    static constexpr Descriptor kRetroRewind{Kind::RetroRewind, "Retro Rewind"};
    return g_activeKind == Kind::RetroRewind ? kRetroRewind : kBase;
}

} // namespace RuntimeProduct
