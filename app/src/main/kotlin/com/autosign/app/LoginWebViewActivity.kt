package com.autosign.app

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val RESULT_LOGIN_SUCCESS = 100
        const val RESULT_LOGIN_FAILED = 101
    }

    private lateinit var webView: WebView
    private var isLoginHandled = false
    private var lastUrl = ""
    private val handler = Handler(Looper.getMainLooper())

    // 定时检查登录状态
    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isLoginHandled) {
                checkLoginAndFinish()
                handler.postDelayed(this, 3000) // 每3秒检查一次
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    if (url != null) {
                        lastUrl = url
                    }

                    // 自动填充表单（只在登录页面）
                    if (url != null && url.contains("passport") && username.isNotEmpty()) {
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
                                // 填充完成
                            }
                        }, 1000)
                    }

                    // 检测是否离开了登录页面
                    if (!isLoginHandled && url != null && !url.contains("passport")) {
                        // 离开登录页面了，检查是否登录成功
                        checkLoginAndFinish()
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false // 不拦截URL加载
                }
            }

            webChromeClient = WebChromeClient()
        }

        setContentView(webView)
        webView.loadUrl("https://www.mosoteach.cn/web/index.php?c=passport")

        // 启动定时检查
        handler.postDelayed(checkRunnable, 5000) // 5秒后开始检查
    }

    private fun checkLoginAndFinish() {
        if (isLoginHandled) return

        // 检查当前URL
        val currentUrl = webView.url ?: return

        // 如果还在登录页面，说明还没登录
        if (currentUrl.contains("passport")) {
            return
        }

        // 获取cookies
        val cookies = CookieManager.getInstance().getCookie("https://www.mosoteach.cn") ?: ""
        val apiCookies = CookieManager.getInstance().getCookie("https://api.mosoteach.cn") ?: ""

        // 检查是否有登录相关的cookie
        val hasLoginCookie = cookies.contains("mooc_") ||
                cookies.contains("uid") ||
                cookies.contains("token") ||
                cookies.contains("session") ||
                apiCookies.isNotEmpty()

        // 检查页面内容
        webView.evaluateJavascript(
            """
            (function() {
                try {
                    var body = document.body ? document.body.innerText : '';
                    var title = document.title || '';
                    // 检查是否包含登录成功的标志
                    if (body.indexOf('退出') >= 0 || body.indexOf('注销') >= 0 ||
                        body.indexOf('我的课程') >= 0 || body.indexOf('个人中心') >= 0 ||
                        title.indexOf('首页') >= 0 || title.indexOf('我的') >= 0) {
                        return 'logged_in';
                    }
                    // 检查是否有课程相关内容
                    if (body.indexOf('课程') >= 0 && body.indexOf('登录') < 0) {
                        return 'probably_logged_in';
                    }
                    return 'not_logged_in';
                } catch(e) {
                    return 'error';
                }
            })();
            """.trimIndent()
        ) { result ->
            if (isLoginHandled) return@evaluateJavascript

            val isLoggedIn = result.contains("logged_in") ||
                    result.contains("probably_logged_in") ||
                    (hasLoginCookie && !currentUrl.contains("passport"))

            if (isLoggedIn) {
                isLoginHandled = true
                handler.removeCallbacks(checkRunnable)

                // 获取所有cookies
                val allCookies = CookieManager.getInstance().getCookie("https://www.mosoteach.cn") ?: ""
                val allApiCookies = CookieManager.getInstance().getCookie("https://api.mosoteach.cn") ?: ""
                val combinedCookies = if (allApiCookies.isNotEmpty()) "$allCookies; $allApiCookies" else allCookies

                Toast.makeText(this@LoginWebViewActivity, "登录成功！", Toast.LENGTH_SHORT).show()

                val resultIntent = Intent().apply {
                    putExtra("cookies", combinedCookies)
                    putExtra("url", currentUrl)
                }
                setResult(RESULT_LOGIN_SUCCESS, resultIntent)
                finish()
            }
        }
    }

    override fun onDestroy() {
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
