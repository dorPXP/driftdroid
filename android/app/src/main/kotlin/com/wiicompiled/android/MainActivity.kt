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
        }
    }

    private external fun nativeToolchainCheck(): String
    private external fun nativeArm64FaultCheck(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)
        textView.text = nativeToolchainCheck() + "\n\n" + nativeArm64FaultCheck()
        textView.textSize = 16f
        textView.setPadding(48, 96, 48, 48)
        setContentView(textView)
    }
}
