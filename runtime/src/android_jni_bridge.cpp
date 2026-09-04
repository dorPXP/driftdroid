// JNI entry points for the Android app. Only compiled into the Android product target - see
// runtime/CMakeLists.txt. nativeRealRuntimeCheck is a standalone P1-era smoke test (loads the
// real translated engine, resolves paths, runs the arm64 CPU baseline check). The real game
// entry point is SDL_main below, invoked by SDLActivity's own SDLMain thread (MainActivity.kt
// now extends SDLActivity) - not called directly from here.
#if defined(__ANDROID__)

#include <jni.h>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <string>
#include <unistd.h>

#include "runtime_config.h"
#include "runtime_product.h"
#include "settings_overlay.h"

extern "C" int MkwHostCpuBaselineInit();
int RuntimeMain(int argc, char** argv);  // NOT extern "C" - matches its real declaration in main.cpp

// Touch equivalent of the desktop F10 settings-overlay hotkey - see MainActivity.kt's gear button.
extern "C" JNIEXPORT void JNICALL
Java_com_wiicompiled_android_MainActivity_nativeToggleSettingsOverlay(JNIEnv*, jobject /* this */) {
    settings_overlay::ToggleTopBar();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wiicompiled_android_MainActivity_nativeRealRuntimeCheck(JNIEnv* env, jobject /* this */,
                                                                   jstring filesDir) {
    std::string result = "Real libWiiCompiled.so loaded via JNI.\n";

    const char* filesDirChars = env->GetStringUTFChars(filesDir, nullptr);
    RuntimeConfigFile::SetAndroidFilesDir(filesDirChars);
    env->ReleaseStringUTFChars(filesDir, filesDirChars);

    result += "Product: ";
    result += RuntimeProduct::Active().displayName;
    result += "\n";

    result += "App data dir: ";
    result += RuntimeConfigFile::ApplicationDataDirectory().string();
    result += "\n";

    result += "Config path: ";
    result += RuntimeConfigFile::ResolveConfigPath().string();
    result += "\n";

    const int baselineResult = MkwHostCpuBaselineInit();
    result += "CPU baseline check: ";
    result += (baselineResult == 0) ? "OK\n" : "FAILED\n";

    result += "\nThis proves the real engine (with your actual translated game code) loads and "
              "runs on this device. It does not start the game yet - that needs the on-device "
              "install pipeline, which isn't built yet.";

    return env->NewStringUTF(result.c_str());
}

// Paths handed over from Kotlin before MainActivity (now an SDLActivity subclass) calls
// super.onCreate(), i.e. before SDL's own SDLMain thread can possibly start - see
// nativeSetInstallPaths below. Plain globals, not synchronized: written exactly once from the
// UI thread, read exactly once from the SDLMain thread that starts strictly after.
namespace {
std::string g_androidDvdRoot;
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_wiicompiled_android_MainActivity_nativeSetInstallPaths(JNIEnv* env, jobject /* this */,
                                                                  jstring filesDir, jstring dvdRoot) {
    const char* filesDirChars = env->GetStringUTFChars(filesDir, nullptr);
    RuntimeConfigFile::SetAndroidFilesDir(filesDirChars);
    env->ReleaseStringUTFChars(filesDir, filesDirChars);

    const char* dvdRootChars = env->GetStringUTFChars(dvdRoot, nullptr);
    g_androidDvdRoot = dvdRootChars;
    env->ReleaseStringUTFChars(dvdRoot, dvdRootChars);
}

// The real SDL3 Android entry point. MainActivity now extends SDLActivity (see MainActivity.kt),
// so SDL's own Java glue drives the whole lifecycle we used to hand-roll here: it calls
// SDL.setupJNI()/SDL.initialize() from onCreate() (populating the mActivityClass/method-ID
// globals SDL_VideoInit's touch-input setup needs - see the git history of this file for the
// on-device tombstone that showed why that call is mandatory), creates the SDLSurface and hands
// SDL a real ANativeWindow, then - once the surface is ready and the activity has focus - spawns
// its own background SDLMain thread and dlsym's+calls exactly this function by name ("SDL_main"
// is SDLActivity.getMainFunction()'s default). No manual SDL_SetMainReady() or extra thread
// needed: SDL_RunApp (which nativeRunMain calls into) already does the real equivalent of both.
extern "C" int SDL_main(int argc, char** argv) {
    const std::filesystem::path configPath = RuntimeConfigFile::ResolveConfigPath();
    std::error_code ec;
    std::filesystem::create_directories(configPath.parent_path(), ec);

    // Overwrite (not EnsureConfigFile, which leaves dvd_root commented out) with a minimal config
    // that actually points at the staged disc data - the whole point of this test.
    {
        std::ofstream config(configPath, std::ios::trunc);
        config << "[video]\n"
                  "graphics_api = \"auto\"\n\n"
                  "[paths]\n"
                  "dvd_root = \"" << g_androidDvdRoot << "\"\n";
    }

    // RuntimeConfigFile::Get() memoizes into a function-local static on its first call, and
    // several unrelated globals (see settings_overlay.cpp: g_resolutionScale, g_audioMuted, etc.)
    // are themselves initialized directly from Get() at static-init time - which on Android fires
    // the instant System.loadLibrary("WiiCompiled") runs in MainActivity's companion object, well
    // before this function gets a chance to write the real Config.toml above. That first, early
    // Get() call reads whatever (nonexistent/default) file happens to exist at that moment and
    // freezes it for the rest of the process - confirmed on-device: dvd_root was never seen despite
    // being correctly present on disk. Force a reload from the file we just wrote, now that it
    // exists, before any real config consumer (DVD/NAND init, etc.) runs. This does not fix the
    // settings_overlay.cpp globals themselves (already-baked plain bool/float copies, not
    // references), only Get()'s own storage - sufficient for dvd_root/nand_root, which are read
    // live through Get() rather than cached into a copy.
    {
        std::ifstream reload(configPath, std::ios::binary);
        if (reload) {
            RuntimeConfigFile::Mutable() = RuntimeConfigFile::ParseConfig(reload, configPath.string());
        }
    }

    // Redirect stdout/stderr - RuntimeMain and everything it calls logs through these, not
    // through __android_log_print, so logcat alone won't show it. freopen()-ing stderr onto the
    // same path a second time (as this used to do) opens a SECOND, independent fd at its own
    // offset - two unbuffered writers racing on the same file then corrupt each other's bytes
    // (confirmed on-device: an "Executed 43 main DOL static constructors." line came back
    // truncated mid-word with an unrelated line spliced into it). dup2 makes stderr share
    // stdout's exact fd/offset instead, so every write - regardless of which stream - is a single
    // ordered sequence.
    const std::filesystem::path logPath = configPath.parent_path() / "android_runtime_attempt.log";
    std::freopen(logPath.string().c_str(), "w", stdout);
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    dup2(fileno(stdout), fileno(stderr));
    std::setvbuf(stderr, nullptr, _IONBF, 0);

    std::printf("[android_jni_bridge] SDL_main -> RuntimeMain\n");
    std::fflush(stdout);

    const int result = RuntimeMain(argc, argv);

    std::printf("[android_jni_bridge] RuntimeMain returned %d\n", result);
    std::fflush(stdout);
    return result;
}

#endif  // __ANDROID__
