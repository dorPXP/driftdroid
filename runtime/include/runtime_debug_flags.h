#pragma once

// Switches for testing without a rebuild: a file with this name in the app's data folder
// (WiiCompiled/ on Android) turns the behaviour on. Used to find which recent change breaks a
// game; cache the result in a function-local static at the call site.

#include "runtime_config.h"

#include <filesystem>

namespace RuntimeDebugFlags {

inline bool Present(const char* name) {
    std::error_code ec;
    return std::filesystem::exists(RuntimeConfigFile::ApplicationDataDirectory() / name, ec);
}

} // namespace RuntimeDebugFlags
