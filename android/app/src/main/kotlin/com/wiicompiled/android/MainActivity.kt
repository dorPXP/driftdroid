package com.wiicompiled.android

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Toolchain smoke-test screen only (P1 de-risking step). Not the real
 * launcher UI — that comes in P5. Confirms the Kotlin app builds, loads
 * libwii.so via JNI, and that the native side is reachable.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        init {
            System.loadLibrary("wii")
            System.loadLibrary("png16")
            System.loadLibrary("WiiCompiled")
        }
    }

    private external fun nativeToolchainCheck(): String
    private external fun nativeArm64FaultCheck(): String
    private external fun nativeRealRuntimeCheck(filesDir: String): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)
        val realRuntimeResult = try {
            nativeRealRuntimeCheck(filesDir.absolutePath)
        } catch (e: UnsatisfiedLinkError) {
            "Real runtime check FAILED to load: ${e.message}"
        }
        textView.text = nativeToolchainCheck() + "\n\n" + nativeArm64FaultCheck() +
            "\n\n" + realRuntimeResult
        textView.textSize = 14f
        textView.setPadding(48, 96, 48, 48)
        setContentView(textView)
    }
}
