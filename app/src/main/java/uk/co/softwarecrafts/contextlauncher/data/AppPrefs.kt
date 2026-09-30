package uk.co.softwarecrafts.contextlauncher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Small flags that are not part of the exportable config. */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("context_launcher", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(ONBOARDING_DONE, false)
        set(value) = prefs.edit { putBoolean(ONBOARDING_DONE, value) }

    private companion object {
        const val ONBOARDING_DONE = "onboarding_done"
    }
}
