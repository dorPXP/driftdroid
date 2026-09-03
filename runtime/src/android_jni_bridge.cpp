// JNI entry points for the Android app (P5 groundwork). Deliberately does NOT call RuntimeMain
// yet - that expects a fully set-up environment (a rendering surface, the disc data already
// staged on-device, real argv) that the Kotlin app doesn't provide yet. This is a safe first
// on-device proof that the REAL runtime .so (built from the actual translated game, not a
// placeholder) loads correctly under Android's dynamic linker and that real, already-verified
// runtime code (path resolution, the arm64 CPU baseline check) executes correctly when called
// from Java. Only compiled into the Android product target - see runtime/CMakeLists.txt.
#if defined(__ANDROID__)

#include <jni.h>
#include <string>

#include "runtime_config.h"
#include "runtime_product.h"

extern "C" int MkwHostCpuBaselineInit();

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

#endif  // __ANDROID__
