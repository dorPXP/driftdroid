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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val textView = TextView(this)
        textView.text = nativeToolchainCheck()
        textView.textSize = 18f
        textView.setPadding(48, 96, 48, 48)
        setContentView(textView)
    }
}
