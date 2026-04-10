package com.danggai.endfield.assistant

import android.content.Context
import android.content.SharedPreferences

object PreferenceManager {

    private const val PREF_NAME = "endfield_prefs"
    private const val KEY_TOKEN = "account_token"
    private const val KEY_UID = "user_uid"
    private const val KEY_NICKNAME = "user_nickname"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveUserData(context: Context, token: String, uid: String, nickname: String) {
        getPrefs(context).edit().apply {
            putString(KEY_TOKEN, token)
            putString(KEY_UID, uid)
            putString(KEY_NICKNAME, nickname)
            apply()
        }
    }

    fun getUserToken(context: Context): String? =
        getPrefs(context).getString(KEY_TOKEN, null)

    fun getUserUid(context: Context): String? =
        getPrefs(context).getString(KEY_UID, null)

    fun getUserNickname(context: Context): String? =
        getPrefs(context).getString(KEY_NICKNAME, null)

    fun clearUser(context: Context) {
        getPrefs(context).edit().clear().apply()

        context.getSharedPreferences("pref", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}