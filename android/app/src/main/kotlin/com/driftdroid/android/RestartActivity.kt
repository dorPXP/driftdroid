package com.driftdroid.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File

/**
 * An invisible screen in its own process (":restart" in the manifest) that brings the game back
 * after [MainActivity] kills its process to start clean. The old way, an AlarmManager alarm firing
 * after the process died, is a background activity start, which Android 10+ blocks (and Huawei's
 * system blocks outright), so "Restart" just closed the game. This screen is in the foreground
 * when it starts the game again, which is allowed.
 */
class RestartActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val product = intent.getStringExtra(MainActivity.EXTRA_PRODUCT) ?: MainActivity.PRODUCT_BASE
        val oldPid = intent.getIntExtra(EXTRA_OLD_PID, -1)
        val started = System.currentTimeMillis()

        // Wait for the old game process to be gone, so the new one doesn't find the native
        // libraries still loaded in it; give up waiting after a few seconds either way.
        fun relaunchWhenGone() {
            val alive = oldPid > 0 && File("/proc/$oldPid").exists()
            if (alive && System.currentTimeMillis() - started < MAX_WAIT_MS) {
                handler.postDelayed({ relaunchWhenGone() }, POLL_MS)
                return
            }
            val relaunch = Intent(this, MainActivity::class.java)
            relaunch.putExtra(MainActivity.EXTRA_PRODUCT, product)
            relaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(relaunch)
            finish()
            overridePendingTransition(0, 0)
            // Nothing else lives in this process.
            handler.postDelayed({ Process.killProcess(Process.myPid()) }, 500)
        }
        relaunchWhenGone()
    }

    companion object {
        const val EXTRA_OLD_PID = "old_pid"
        private const val POLL_MS = 50L
        private const val MAX_WAIT_MS = 4000L
    }
}
