package com.autosign.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LoginWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val RESULT_LOGIN_SUCCESS = 100
        const val RESULT_LOGIN_FAILED = 101
        private const val TAG = "LoginWebView"
        private const val VERIFY_URL = "https://api.mosoteach.cn/mssvc/index.php/cc/list_joined"
    }

    private lateinit var webView: WebView
    private var isLoginHandled = false
    private val handler = Handler(Looper.getMainLooper())
    private var pageLoadCount = 0
    private var verifyCount = 0
    private var lastKnownUrl = ""

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 定时检查登录状态（通过API验证）
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isLoginHandled) {
                verifyLoginWithApi()
                if (!isLoginHandled) {
                    handler.postDelayed(this, 3000)
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        Log.d(TAG, "onCreate: username=$username")

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageLoadCount++
                    lastKnownUrl = url ?: ""
                    Log.d(TAG, "onPageFinished [$pageLoadCount]: $url")

                    // 自动填充表单（只在登录页面，前几次加载）
                    if (url != null && url.contains("passport") && username.isNotEmpty() && pageLoadCount <= 3) {
                        handler.postDelayed({
                            fillLoginForm(view, username, password)
                        }, 2000)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    Log.d(TAG, "shouldOverrideUrlLoading: ${request?.url}")
                    return false
                }
            }

            webChromeClient = WebChromeClient()
        }

        setContentView(webView)
        webView.loadUrl("https://www.mosoteach.cn/web/index.php?c=passport")

        // 启动定时检查（5秒后开始）
        handler.postDelayed(checkRunnable, 5000)
    }

    private fun fillLoginForm(view: WebView?, username: String, password: String) {
        // 使用React兼容的方式设置input value
        val fillScript = """
            (function() {
                try {
                    // 找到输入框
                    var accountInput = document.querySelector('input[name="account_name"]')
                        || document.querySelector('input[type="text"]')
                        || document.querySelector('input[placeholder*="手机"]')
                        || document.querySelector('input[placeholder*="邮箱"]')
                        || document.querySelector('input[placeholder*="账号"]');
                    var pwdInput = document.querySelector('input[name="user_password"]')
                        || document.querySelector('input[type="password"]');

                    // 使用React兼容方式设置值
                    function setInputValue(input, val) {
                        if (!input) return false;
                        var nativeInputValueSetter = Object.getOwnPropertyDescriptor(
                            window.HTMLInputElement.prototype, 'value'
                        ).set;
                        nativeInputValueSetter.call(input, val);
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        input.dispatchEvent(new Event('change', { bubbles: true }));
                        input.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
                        return true;
                    }

                    var r1 = setInputValue(accountInput, '$username');
                    var r2 = setInputValue(pwdInput, '$password');

                    return 'account=' + r1 + ', pwd=' + r2;
                } catch(e) {
                    return 'error: ' + e.message;
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(fillScript) { result ->
            Log.d(TAG, "填充表单结果: $result")
        }
    }

    /**
     * 验证是否真正登录成功
     * 策略：URL已离开登录页 + 存在session cookie + web端API返回有效数据
     */
    private fun verifyLoginWithApi() {
        if (isLoginHandled) return

        verifyCount++
        Log.d(TAG, "verifyLoginWithApi [$verifyCount]")

        // 先检查URL是否还在登录页面
        val currentUrl = webView.url ?: ""
        if (currentUrl.contains("passport")) {
            Log.d(TAG, "还在登录页面，等待用户操作...")
            return
        }

        // 获取cookies
        val webCookies = CookieManager.getInstance().getCookie("https://www.mosoteach.cn") ?: ""
        val apiCookies = CookieManager.getInstance().getCookie("https://api.mosoteach.cn") ?: ""

        if (webCookies.isEmpty() && apiCookies.isEmpty()) {
            Log.d(TAG, "cookies为空，等待...")
            return
        }

        val combinedCookies = buildString {
            if (webCookies.isNotEmpty()) append(webCookies)
            if (apiCookies.isNotEmpty()) {
                if (isNotEmpty()) append("; ")
                append(apiCookies)
            }
        }

        Log.d(TAG, "webCookies: ${webCookies.take(80)}...")
        Log.d(TAG, "cookies长度: ${combinedCookies.length}")

        // 检查是否有session cookie（登录成功的标志）
        val hasSession = combinedCookies.contains("mysession") ||
                combinedCookies.contains("PHPSESSID") ||
                combinedCookies.contains("mosoteach") ||
                combinedCookies.contains("mt_") ||
                combinedCookies.length > 100  // 有足够多的cookie说明已登录

        // 在后台线程用web端验证
        Thread {
            try {
                // 用web端的课程页面验证（不是移动端API）
                val verifyUrl = "https://www.mosoteach.cn/web/index.php?c=course&m=my_course"
                val request = Request.Builder()
                    .url(verifyUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                    .header("Cookie", combinedCookies)
                    .get()
                    .build()

                val response = apiClient.newCall(request).execute()
                val body = response.body?.string() ?: ""
                val finalUrl = response.request.url.toString()

                Log.d(TAG, "web验证响应码: ${response.code}")
                Log.d(TAG, "web验证最终URL: $finalUrl")
                Log.d(TAG, "web验证响应前200字: ${body.take(200)}")

                // 判断是否登录成功：
                // 1. 没有被重定向到登录页
                // 2. 响应中包含课程相关内容或用户信息
                // 3. 不是错误页面
                val notLoginPage = !finalUrl.contains("passport") && !body.contains("passport")
                val hasCourseContent = body.contains("course") || body.contains("课程") ||
                        body.contains("my_course") || body.contains("clazz")
                val notErrorPage = response.code == 200 && !body.contains("error") && body.length > 500

                val isValidLogin = notLoginPage && hasSession && (hasCourseContent || notErrorPage)

                Log.d(TAG, "验证结果: notLoginPage=$notLoginPage, hasSession=$hasSession, hasCourseContent=$hasCourseContent, notErrorPage=$notErrorPage")

                if (isValidLogin && !isLoginHandled) {
                    Log.d(TAG, "登录验证成功!")
                    isLoginHandled = true

                    handler.removeCallbacks(checkRunnable)
                    handler.post {
                        Toast.makeText(this, "登录成功！正在启动签到...", Toast.LENGTH_SHORT).show()

                        val resultIntent = Intent().apply {
                            putExtra("cookies", combinedCookies)
                            putExtra("url", webView.url ?: "")
                        }
                        setResult(RESULT_LOGIN_SUCCESS, resultIntent)
                        finish()
                    }
                } else {
                    Log.d(TAG, "登录验证未通过，等待下一次检查...")
                }
            } catch (e: Exception) {
                Log.d(TAG, "验证异常: ${e.message}")
                // 网络异常时，如果URL已离开登录页且有session，也认为成功
                if (hasSession && !isLoginHandled) {
                    Log.d(TAG, "网络异常但有session，认为登录成功")
                    isLoginHandled = true
                    handler.removeCallbacks(checkRunnable)
                    handler.post {
                        Toast.makeText(this, "登录成功！正在启动签到...", Toast.LENGTH_SHORT).show()
                        val resultIntent = Intent().apply {
                            putExtra("cookies", combinedCookies)
                            putExtra("url", webView.url ?: "")
                        }
                        setResult(RESULT_LOGIN_SUCCESS, resultIntent)
                        finish()
                    }
                }
            }
        }.start()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        handler.removeCallbacks(checkRunnable)
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            handler.removeCallbacks(checkRunnable)
            setResult(RESULT_LOGIN_FAILED)
            super.onBackPressed()
        }
    }
}
