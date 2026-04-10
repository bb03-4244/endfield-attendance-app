package com.danggai.endfield.assistant

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import com.danggai.endfield.assistant.BuildConfig

class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val loginScope = CoroutineScope(Dispatchers.Main + Job())

    private var isHarvested = false
    private var bindingFetchStarted = false

    private val httpClient by lazy { OkHttpClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        logTrace("🚀 LoginActivity 시작")

        webView = findViewById(R.id.webView)
        setupWebView()
        webView.loadUrl("https://game.skport.com/endfield/sign-in?header=0&hg_media=launcher")
    }

    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                logLogin("📄 onPageFinished")
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString().orEmpty()
                val method = request?.method.orEmpty()

                if (BuildConfig.DEBUG) {
                    if (
                        url.contains("binding", ignoreCase = true) ||
                        url.contains("cookie_store", ignoreCase = true) ||
                        url.contains("grant", ignoreCase = true) ||
                        url.contains("token", ignoreCase = true) ||
                        url.contains("basic", ignoreCase = true) ||
                        url.contains("third_party", ignoreCase = true)
                    ) {
                        logLogin("📡 method=$method url=$url")
                    }
                }

                val isBindingGet =
                    method.equals("GET", ignoreCase = true) &&
                            url.contains("/api/v1/game/player/binding", ignoreCase = true)

                if (!bindingFetchStarted && isBindingGet) {
                    bindingFetchStarted = true

                    val headerSnapshot = try {
                        HashMap(request?.requestHeaders ?: emptyMap())
                    } catch (_: Exception) {
                        hashMapOf<String, String>()
                    }

                    logLogin("🎯 binding GET 감지 - 저장 플로우 시작")

                    if (BuildConfig.DEBUG) {
                        logLogin(
                            "🧾 binding GET headers(meta) = " +
                                    sanitizeHeaderLog(headerSnapshot)
                        )
                    }

                    loginScope.launch {
                        delay(120)
                        tryFetchAndSave(url, headerSnapshot)
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private suspend fun tryFetchAndSave(
        bindingUrl: String,
        bindingHeaders: Map<String, String>
    ) {
        if (isHarvested) return

        val token = waitForAccountToken()
        if (token.isNullOrEmpty()) {
            logLogin("❌ ACCOUNT_TOKEN 확보 실패", isError = true)
            bindingFetchStarted = false
            return
        }

        val roleInfo = fetchRoleInfoFromBinding(bindingUrl, bindingHeaders)
        if (roleInfo == null) {
            logLogin("❌ binding 응답에서 role 확보 실패", isError = true)
            bindingFetchStarted = false
            return
        }

        logLogin("------------------------------------------", isInfo = true)
        logLogin("🎁 최종 수집 성공!", isInfo = true)
        logLogin(" - Token: ${mask(token)}", isInfo = true)
        logLogin(" - RoleId: ${roleInfo.roleId}", isInfo = true)
        logLogin(" - Nickname: ${roleInfo.nickname}", isInfo = true)
        logLogin(" - ServerId: ${roleInfo.serverId}", isInfo = true)
        logLogin("------------------------------------------", isInfo = true)

        runOnUiThread {
            performSave(
                token = token,
                roleId = roleInfo.roleId,
                name = roleInfo.nickname,
                serverId = roleInfo.serverId
            )
        }
    }

    private suspend fun waitForAccountToken(): String? {
        repeat(8) { attempt ->
            CookieManager.getInstance().flush()
            val token = getRealAccountTokenFromCookie()
            if (!token.isNullOrEmpty()) {
                return token
            }

            if (BuildConfig.DEBUG) {
                logLogin("⚠️ ACCOUNT_TOKEN 대기 중... attempt=${attempt + 1}")
            }
            delay(250)
        }
        return null
    }

    private fun getRealAccountTokenFromCookie(): String? {
        val cm = CookieManager.getInstance()

        val targetUrls = listOf(
            "https://skport.com/cookie_store/account_token",
            "https://game.skport.com/cookie_store/account_token",
            "https://web-api.skport.com/cookie_store/account_token",
            "https://skport.com/cookie_store",
            "https://game.skport.com/cookie_store",
            "https://web-api.skport.com/cookie_store",
            "https://skport.com",
            "https://game.skport.com",
            "https://web-api.skport.com"
        )

        for (url in targetUrls) {
            val rawCookies = cm.getCookie(url)
            if (rawCookies.isNullOrBlank()) continue

            val encodedToken = rawCookies
                .split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("ACCOUNT_TOKEN=") }
                ?.substringAfter("=")
                ?.trim()

            if (!encodedToken.isNullOrEmpty()) {
                val decoded = try {
                    URLDecoder.decode(encodedToken, "UTF-8")
                } catch (_: Exception) {
                    encodedToken
                }

                if (BuildConfig.DEBUG) {
                    logLogin("✅ ACCOUNT_TOKEN 발견: ${mask(decoded)}")
                } else {
                    logLogin("✅ ACCOUNT_TOKEN 확보")
                }

                return decoded
            }
        }

        return null
    }

    private suspend fun fetchRoleInfoFromBinding(
        bindingUrl: String,
        bindingHeaders: Map<String, String>
    ): RoleInfo? {
        val cookieHeader = buildCookieHeader()
        if (cookieHeader.isNullOrBlank()) {
            logLogin("❌ Cookie header 구성 실패", isError = true)
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder()
                    .url(bindingUrl)
                    .get()

                for ((key, value) in bindingHeaders) {
                    if (key.equals("cookie", ignoreCase = true)) continue
                    if (key.startsWith(":")) continue
                    if (value.isBlank()) continue
                    if (key.equals("Access-Control-Request-Method", ignoreCase = true)) continue
                    if (key.equals("Access-Control-Request-Headers", ignoreCase = true)) continue

                    try {
                        builder.header(key, value)
                    } catch (_: Exception) {
                    }
                }

                builder.header("Cookie", cookieHeader)

                if (!bindingHeaders.containsKey("Accept")) {
                    builder.header("Accept", "*/*")
                }
                if (!bindingHeaders.containsKey("Origin")) {
                    builder.header("Origin", "https://game.skport.com")
                }
                if (!bindingHeaders.containsKey("Referer")) {
                    builder.header("Referer", "https://game.skport.com/")
                }
                if (!bindingHeaders.containsKey("platform")) {
                    builder.header("platform", "3")
                }
                if (!bindingHeaders.containsKey("sk-language")) {
                    builder.header("sk-language", "ko")
                }
                if (!bindingHeaders.containsKey("vName") &&
                    !bindingHeaders.containsKey("vname")
                ) {
                    builder.header("vName", "1.0.0")
                }
                if (!bindingHeaders.containsKey("User-Agent")) {
                    builder.header(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
                    )
                }

                if (BuildConfig.DEBUG) {
                    logLogin("📤 native binding request 시작")
                }

                val request = builder.build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string().orEmpty()

                logLogin("📦 binding code=${response.code}")

                if (BuildConfig.DEBUG) {
                    logLogin(
                        "📦 binding body(meta)=length=${body.length}, hasDefaultRole=${body.contains("defaultRole")}"
                    )
                }

                if (!response.isSuccessful || body.isBlank()) {
                    return@withContext null
                }

                return@withContext parseRoleFromBinding(body)
            } catch (e: Exception) {
                logLogin("❌ binding fetch 예외: ${e.message}", isError = true)
                return@withContext null
            }
        }
    }

    private fun buildCookieHeader(): String? {
        val cm = CookieManager.getInstance()
        val sources = listOf(
            "https://zonai.skport.com",
            "https://game.skport.com",
            "https://skport.com",
            "https://web-api.skport.com"
        )

        val cookieParts = linkedSetOf<String>()

        for (url in sources) {
            val raw = cm.getCookie(url)
            if (!raw.isNullOrBlank()) {
                raw.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.contains("=") }
                    .forEach { cookieParts.add(it) }
            }
        }

        if (BuildConfig.DEBUG) {
            logLogin("🍪 Cookie header 구성 완료: count=${cookieParts.size}")
        }

        return if (cookieParts.isEmpty()) null else cookieParts.joinToString("; ")
    }

    private fun parseRoleFromBinding(body: String): RoleInfo? {
        return try {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return null
            val list = data.optJSONArray("list") ?: return null

            for (i in 0 until list.length()) {
                val gameObj = list.optJSONObject(i) ?: continue
                val bindingList = gameObj.optJSONArray("bindingList") ?: continue

                for (j in 0 until bindingList.length()) {
                    val binding = bindingList.optJSONObject(j) ?: continue

                    val defaultRole = binding.optJSONObject("defaultRole")
                    val fromDefault = roleFromJson(defaultRole)
                    if (fromDefault != null) {
                        logLogin("✅ defaultRole 파싱 성공")
                        return fromDefault
                    }

                    val roles = binding.optJSONArray("roles")
                    val fromRoles = roleFromArray(roles)
                    if (fromRoles != null) {
                        logLogin("✅ roles[] 파싱 성공")
                        return fromRoles
                    }
                }
            }

            null
        } catch (e: Exception) {
            logLogin("❌ binding JSON 파싱 실패: ${e.message}", isError = true)
            null
        }
    }

    private fun roleFromArray(arr: JSONArray?): RoleInfo? {
        if (arr == null) return null
        for (i in 0 until arr.length()) {
            val found = roleFromJson(arr.optJSONObject(i))
            if (found != null) return found
        }
        return null
    }

    private fun roleFromJson(obj: JSONObject?): RoleInfo? {
        if (obj == null) return null

        val roleId = obj.opt("roleId")?.toString().orEmpty()
        val nickname = obj.optString("nickname")
            .ifBlank { obj.optString("nickName") }
        val serverId = obj.opt("serverId")?.toString().orEmpty().ifBlank { "2" }

        if (roleId.isBlank() || nickname.isBlank()) return null
        return RoleInfo(roleId, nickname, serverId)
    }

    private fun performSave(token: String, roleId: String, name: String, serverId: String) {
        if (isHarvested) return
        isHarvested = true

        PreferenceManager.saveUserData(this, token, roleId, name)
        getSharedPreferences("pref", MODE_PRIVATE)
            .edit()
            .putString("serverId", serverId)
            .apply()

        logTrace("✅ 모든 데이터 저장 완료")
        logTrace(" - token: ${mask(token)}")
        logTrace(" - roleId: $roleId")
        logTrace(" - nickname: $name")
        logTrace(" - serverId: $serverId")

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun sanitizeHeaderLog(headers: Map<String, String>): String {
        val masked = linkedMapOf<String, String>()

        headers.forEach { (key, value) ->
            val maskedValue = when {
                key.equals("cred", ignoreCase = true) -> mask(value)
                key.equals("sign", ignoreCase = true) -> mask(value)
                key.equals("timestamp", ignoreCase = true) -> "***"
                key.equals("cookie", ignoreCase = true) -> "***"
                else -> value
            }
            masked[key] = maskedValue
        }

        return masked.toString()
    }

    private fun mask(value: String?, keepStart: Int = 6, keepEnd: Int = 0): String {
        if (value.isNullOrEmpty()) return "null"
        if (value.length <= keepStart + keepEnd) return "***"

        val start = value.take(keepStart)
        val end = if (keepEnd > 0) value.takeLast(keepEnd) else ""
        return if (end.isEmpty()) "$start..." else "$start...$end"
    }

    private fun logLogin(message: String, isError: Boolean = false, isInfo: Boolean = false) {
        when {
            isError -> Log.e("LoginDebug", message)
            isInfo -> Log.i("LoginDebug", message)
            else -> Log.d("LoginDebug", message)
        }
    }

    private fun logTrace(message: String) {
        Log.i("TraceDebug", message)
    }

    override fun onDestroy() {
        super.onDestroy()
        loginScope.coroutineContext[Job]?.cancel()
    }

    data class RoleInfo(
        val roleId: String,
        val nickname: String,
        val serverId: String
    )
}