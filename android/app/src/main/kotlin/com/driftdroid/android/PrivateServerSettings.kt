package com.driftdroid.android

import android.app.Activity
import android.content.Context
import android.system.Os
import com.driftdroid.android.ui.DriftSheet
import com.driftdroid.android.ui.DriftUi

/**
 * Experimental: redirects Wii game-service DNS lookups (retail *.nintendowifi.net and Retro
 * Rewind's *.rwfc.net) to a self-hosted, compatible service instead of the real WFC/RWFC
 * infrastructure - see network_deferred.cpp's PrivateWfcHostOverride()/IsWiiServiceHostname(),
 * which is the only place this env var is read. Everything else the guest resolves (unrelated
 * TLS/HTTP hosts) is untouched.
 *
 * The env var is read once, at native-library load time (see MainActivity.onCreate(), which
 * calls [configureLaunch] before System.loadLibrary), so a change here only takes effect after
 * fully closing and reopening the app.
 */
internal object PrivateServerSettings {
    private const val ENVIRONMENT_VARIABLE = "WIICOMPILED_PRIVATE_WFC_HOST"
    private const val PREFS_NAME = "private_wfc_server"
    private const val KEY_HOST = "host"

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun validHost(host: String): Boolean =
        host.length in 1..253 &&
            host.split('.').all { label ->
                label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                    label.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' }
            }

    /** Must run before System.loadLibrary() - see MainActivity.onCreate(). */
    fun configureLaunch(context: Context) {
        val host = preferences(context).getString(KEY_HOST, "").orEmpty()
        try {
            Os.unsetenv(ENVIRONMENT_VARIABLE)
            if (validHost(host)) {
                Os.setenv(ENVIRONMENT_VARIABLE, host, true)
            }
        } catch (_: Exception) {
            // Best-effort: worst case online play just uses the real service, same as if this
            // setting had never been touched.
        }
    }

    fun show(activity: Activity) {
        val prefs = preferences(activity)
        val sheet = DriftSheet(activity, T("Experimental server"), T("For people who run their own server"), compact = true)
        sheet.note(
            "Sends online play to a self-hosted, compatible Wii game service instead of the real " +
                "WFC / Retro WFC servers. It needs a service that is already running: not another " +
                "player's app or an ordinary web server. It does not bring back Nintendo WFC. Close the " +
                "app fully (from Recents) and open it again for a change to apply.",
        )
        val field = sheet.textField("Hostname or IPv4 address", prefs.getString(KEY_HOST, "").orEmpty())
        field.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        sheet.button("Use default") {
            prefs.edit().remove(KEY_HOST).apply()
            sheet.dismiss()
        }
        sheet.button("Save", DriftUi.Style.PRIMARY) {
            val host = field.text.toString().trim()
            if (host.isNotEmpty() && !validHost(host)) {
                field.error = T("Enter a hostname or IPv4 address, without a scheme, port or path.")
            } else {
                prefs.edit().putString(KEY_HOST, host).apply()
                sheet.dismiss()
            }
        }
        sheet.show()
    }
}
