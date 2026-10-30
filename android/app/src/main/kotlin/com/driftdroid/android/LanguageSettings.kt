package com.driftdroid.android

import android.app.Activity
import com.driftdroid.android.ui.DriftSheet

/** The language chooser: "Automatic" (the phone's language) or one of the translated languages. */
object LanguageSettings {
    fun show(activity: Activity) {
        val sheet = DriftSheet(activity, "Language")
        val choice = I18n.savedChoice(activity)
        fun pick(code: String) {
            I18n.saveChoice(activity, code)
            I18n.init(activity)
            sheet.dismiss()
            activity.recreate()
        }
        sheet.radio("Automatic", null, choice == "auto") { pick("auto") }
        for ((code, name) in I18n.languages(activity)) {
            sheet.radio(name, null, choice == code) { pick(code) }
        }
        sheet.note("The game's menus follow it the next time the game starts.")
        sheet.show()
    }
}
