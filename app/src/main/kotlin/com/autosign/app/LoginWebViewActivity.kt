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

class LoginWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val RESULT_LOGIN_SUCCESS = 100
        const val RESULT_LOGIN_FAILED = 101
        private const val TAG = "LoginWebView"
    }

    private lateinit var webView: WebView
    private var isLoginHandled = false
    private val handler = Handler(Looper.getMainLooper())
    private var pageLoadCount = 0

    // 定时检查登录状态
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isLoginHandled) {
                Log.d(TAG, "定时检查登录状态...")
                checkLoginAndFinish()
                if (!isLoginHandled) {
                    handler.postDelayed(this, 2000) // 每2秒检查一次
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
                    Log.d(TAG, "onPageFinished: url=$url, count=$pageLoadCount")

                    // 自动填充表单（只在登录页面）
                    if (url != null && url.contains("passport") && username.isNotEmpty() && pageLoadCount <= 2) {
                        handler.postDelayed({
                            val fillScript = """
                                (function() {
                                    try {
                                        var accountInput = document.querySelector('input[name="account_name"]');
                                        var pwdInput = document.querySelector('input[name="user_password"]');
                                        if (!accountInput) accountInput = document.querySelector('input[type="text"]');
                                        if (!pwdInput) pwdInput = document.querySelector('input[type="password"]');
                                        if (accountInput) {
                                            accountInput.value = '$username';
                                            accountInput.dispatchEvent(new Event('input', { bubbles: true }));
                                            accountInput.dispatchEvent(new Event('change', { bubbles: true }));
                                        }
                                        if (pwdInput) {
                                            pwdInput.value = '$password';
                                            pwdInput.dispatchEvent(new Event('input', { bubbles: true }));
                                            pwdInput.dispatchEvent(new Event('change', { bubbles: true }));
                                        }
                                        return 'filled';
                                    } catch(e) {
                                        return 'error: ' + e.message;
                                    }
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(fillScript) { result ->
                                Log.d(TAG, "填充表单结果: $result")
                            }
                        }, 1500)
                    }

                    // 每次页面加载完成后都检查登录状态
                    if (!isLoginHandled && pageLoadCount > 1) {
                        handler.postDelayed({
                            checkLoginAndFinish()
                        }, 500)
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

        // 启动定时检查（3秒后开始，每2秒检查一次）
        handler.postDelayed(checkRunnable, 3000)
    }

    private fun checkLoginAndFinish() {
        if (isLoginHandled) return

        val currentUrl = webView.url ?: run {
            Log.d(TAG, "webView.url is null")
            return
        }

        Log.d(TAG, "checkLoginAndFinish: currentUrl=$currentUrl")

        // 如果还在登录页面，说明还没登录
        if (currentUrl.contains("passport")) {
            Log.d(TAG, "还在登录页面，跳过检查")
            return
        }

        // 获取cookies
        val cookies = CookieManager.getInstance().getCookie("https://www.mosoteach.cn") ?: ""
        val apiCookies = CookieManager.getInstance().getCookie("https://api.mosoteach.cn") ?: ""

        Log.d(TAG, "cookies: ${cookies.take(100)}...")
        Log.d(TAG, "apiCookies: ${apiCookies.take(100)}...")

        // 简化判断：只要离开了登录页面，就认为登录成功
        // 因为云班课登录成功后会自动跳转到首页
        val isLoggedIn = !currentUrl.contains("passport")

        if (isLoggedIn) {
            Log.d(TAG, "登录成功！")
            isLoginHandled = true
            handler.removeCallbacks(checkRunnable)

            // 获取所有cookies
            val allCookies = CookieManager.getInstance().getCookie("https://www.mosoteach.cn") ?: ""
            val allApiCookies = CookieManager.getInstance().getCookie("https://api.mosoteach.cn") ?: ""
            val combinedCookies = if (allApiCookies.isNotEmpty()) "$allCookies; $allApiCookies" else allCookies

            Log.d(TAG, "combinedCookies长度: ${combinedCookies.length}")

            Toast.makeText(this@LoginWebViewActivity, "登录成功！正在启动签到...", Toast.LENGTH_SHORT).show()

            val resultIntent = Intent().apply {
                putExtra("cookies", combinedCookies)
                putExtra("url", currentUrl)
            }
            setResult(RESULT_LOGIN_SUCCESS, resultIntent)
            finish()
        } else {
            Log.d(TAG, "未检测到登录成功")
        }
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
