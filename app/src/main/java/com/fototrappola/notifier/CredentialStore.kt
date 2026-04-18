package com.fototrappola.notifier

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object CredentialStore {
    private const val PREFS = "fototrappola_secure_prefs"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASSWORD = "app_password"
    private const val KEY_FILTER_SENDER = "filter_sender"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun save(context: Context, email: String, appPassword: String, filterSender: String?) {
        prefs(context).edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASSWORD, appPassword)
            .putString(KEY_FILTER_SENDER, filterSender ?: "")
            .apply()
    }

    fun email(context: Context): String? = prefs(context).getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }
    fun password(context: Context): String? = prefs(context).getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() }
    fun filterSender(context: Context): String? = prefs(context).getString(KEY_FILTER_SENDER, null)?.takeIf { it.isNotBlank() }

    fun isConfigured(context: Context): Boolean =
        !email(context).isNullOrBlank() && !password(context).isNullOrBlank()

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
