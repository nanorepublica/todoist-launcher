package uk.co.softwarecrafts.contextlauncher.data.todoist

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The Todoist personal token, encrypted at rest with a key in the Android
 * Keystore. The file is excluded from auto-backup (see xml/backup_rules.xml).
 */
class TokenStore(private val context: Context) {

    private val prefs: SharedPreferences? by lazy {
        try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext, FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            Log.e(TAG, "encrypted preferences unavailable", e)
            null
        }
    }

    var token: String?
        get() = prefs?.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs?.edit { if (value.isNullOrBlank()) remove(KEY_TOKEN) else putString(KEY_TOKEN, value.trim()) }
        }

    val hasToken: Boolean get() = token != null

    companion object {
        const val FILE = "todoist_secure"
        private const val KEY_TOKEN = "token"
        private const val TAG = "TokenStore"
    }
}
