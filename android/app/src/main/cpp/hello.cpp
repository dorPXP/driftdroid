#include <jni.h>
#include <string>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

extern "C" JNIEXPORT jstring JNICALL
Java_com_wiicompiled_android_MainActivity_nativeToolchainCheck(JNIEnv* env, jobject /* this */) {
    std::string result = "libwii.so loaded via JNI.\n";

#if defined(__aarch64__)
    float32x2_t v = vdup_n_f32(1.0f);
    result += (vget_lane_f32(v, 0) > 0.5f) ? "NEON: OK\n" : "NEON: FAIL\n";
#else
    result += "NEON: not arm64 (unexpected on device)\n";
#endif

    result += "This is a toolchain smoke test, not the real app.";
    return env->NewStringUTF(result.c_str());
}
