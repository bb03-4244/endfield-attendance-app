package com.danggai.endfield.assistant

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import androidx.core.view.WindowCompat
import com.danggai.endfield.assistant.worker.AttendanceScheduler

class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val loginScope = CoroutineScope(Dispatchers.Main + Job())
    private var isHarvested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_login)

        Log.d("TraceDebug", "🚀 LoginActivity 시작")

        webView = findViewById(R.id.webView)

        setupWebView()

        webView.loadUrl(
            "https://game.skport.com/endfield/sign-in?header=0&hg_media=launcher"
        )
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

        webView.addJavascriptInterface(object {

            @JavascriptInterface
            fun onStepLog(msg: String) {
                Log.d("LoginDebug", "🌐 $msg")
            }

            @JavascriptInterface
            fun onRoleCaptured(
                name: String,
                roleId: String,
                serverId: String
            ) {

                loginScope.launch {

                    if (isHarvested) return@launch

                    Log.d("LoginDebug", "role 확보 → ACCOUNT_TOKEN 생성 시도")

                    generateAccountToken()

                    delay(3000)
                    CookieManager.getInstance().flush()


                    CookieManager.getInstance().flush()

                    val token = getCookieAccountToken()

                    if (!token.isNullOrEmpty()) {

                        Log.i("LoginDebug", "--------------------------------")
                        Log.i("LoginDebug", "🎁 계정 정보 수집 성공")
                        Log.i("LoginDebug", "Name: $name")
                        Log.i("LoginDebug", "Token: ${token.take(20)}...")
                        Log.i("LoginDebug", "--------------------------------")

                        performSave(
                            token,
                            roleId,
                            name,
                            serverId
                        )

                    } else {

                        Log.e("LoginDebug", "❌ ACCOUNT_TOKEN 추출 실패")

                    }
                }
            }

        }, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                injectHarvestScript()
            }
        }
    }

    /**
     * JS로 ACCOUNT_TOKEN 생성
     */
    private fun generateAccountToken() {

        val js = """
(function(){

    window.AndroidBridge.onStepLog("ACCOUNT_TOKEN iframe 생성");

    var iframe = document.createElement("iframe");
    iframe.style.display = "none";
    iframe.src = "https://web-api.skport.com";

    iframe.onload = function(){

        window.AndroidBridge.onStepLog("iframe 로드 완료");

        fetch("https://web-api.skport.com/cookie_store/account_token", {
            method: "GET",
            credentials: "include"
        })
        .then(res => {
            window.AndroidBridge.onStepLog("ACCOUNT_TOKEN 응답: " + res.status);
        })
        .catch(e => {
            window.AndroidBridge.onStepLog("ACCOUNT_TOKEN fetch 실패");
        });

    };

    document.body.appendChild(iframe);

})();
""".trimIndent()

        webView.evaluateJavascript(js, null)
    }



    /**
     * 쿠키에서 ACCOUNT_TOKEN 추출
     */
    private fun getCookieAccountToken(): String? {

        val cm = CookieManager.getInstance()

        val urls = listOf(
            "https://skport.com/cookie_store/account_token",
            "https://web-api.skport.com/cookie_store/account_token",
            "https://game.skport.com/cookie_store/account_token"
        )

        for (url in urls) {

            val cookies = cm.getCookie(url) ?: continue

            Log.d("LoginDebug", "COOKIE [$url] : $cookies")

            val token = cookies.split(";")
                .map { it.trim() }
                .firstOrNull {
                    it.startsWith("ACCOUNT_TOKEN=")
                }
                ?.substringAfter("=")

            if (!token.isNullOrEmpty()) {

                return try {
                    java.net.URLDecoder.decode(token, "UTF-8")
                } catch (e: Exception) {
                    token
                }
            }
        }

        return null
    }


    /**
     * 로그인 감시 JS
     */
    private fun injectHarvestScript() {

        val jsCode = """
(function() {

    if (window.endfieldWatcher) return;
    window.endfieldWatcher = true;

    let role = null;
    let sent = false;

    function trySend(){

        if(sent) return;

        if(role){

            sent = true;

            window.AndroidBridge.onStepLog("🚀 role 확보");

            window.AndroidBridge.onRoleCaptured(
                role.nickName,
                role.roleId.toString(),
                role.serverId.toString()
            );
        }
    }

    const oldFetch = window.fetch;

    window.fetch = async function(url, options){

        const res = await oldFetch(url, options);

        const urlStr = (typeof url === 'string') ? url : url.url;

        try{

            if(urlStr.includes("binding_list")){

                const json = await res.clone().json();

                const r = json?.data?.list
                    ?.flatMap(e => e.bindingList || [])
                    ?.flatMap(e => e.roles || [])?.[0];

                if(r){

                    role = r;

                    window.AndroidBridge.onStepLog("✅ 캐릭터 발견: "+r.nickName);

                    trySend();
                }
            }

        }catch(e){}

        return res;
    };

    window.AndroidBridge.onStepLog("JS 스캐너 활성화");

})();
""".trimIndent()

        webView.evaluateJavascript(jsCode, null)
    }

    /**
     * 계정 저장
     */
    private fun performSave(
        token: String,
        roleId: String,
        name: String,
        serverId: String
    ) {

        if (isHarvested) return

        isHarvested = true

        PreferenceManager.saveUserData(
            this,
            token,
            roleId,
            name
        )
        AttendanceScheduler.onUserLoggedIn(this)

        val pref = getSharedPreferences("pref", MODE_PRIVATE)

        pref.edit()
            .putString("serverId", serverId)
            .putString("userAgent", webView.settings.userAgentString)
            .apply()

        Log.i("TraceDebug", "✅ 계정 정보 저장 완료")

        startActivity(
            Intent(
                this,
                MainActivity::class.java
            )
        )

        finish()
    }
}
