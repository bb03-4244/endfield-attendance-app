package com.danggai.endfield.assistant

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

object WebLoginManager {

    fun clearWebSession(context: Context) {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }

        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
        }

        try {
            WebView(context).apply {
                clearCache(true)
                clearHistory()
                clearFormData()
                destroy()
            }
        } catch (_: Exception) {
        }
    }
}