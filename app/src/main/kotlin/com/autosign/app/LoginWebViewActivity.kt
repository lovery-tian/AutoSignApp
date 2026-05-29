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
    private val handler = Handler(Looper.getMainLooper())

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

                    // 自动填充表单
                    if (url?.contains("passport") == true && username.isNotEmpty()) {
                        val fillScript = """
                            (function() {
                                try {
                                    var inputs = document.querySelectorAll('input');
                                    for (var i = 0; i < inputs.length; i++) {
                                        if (inputs[i].name === 'account_name' || inputs[i].type === 'text' || inputs[i].type === 'tel') {
                                            inputs[i].value = '$username';
                                            inputs[i].dispatchEvent(new Event('input', { bubbles: true }));
                                        }
                                        if (inputs[i].name === 'user_password' || inputs[i].type === 'password') {
                                            inputs[i].value = '$password';
                                            inputs[i].dispatchEvent(new Event('input', { bubbles: true }));
                                        }
                                    }
                                } catch(e) {}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(fillScript, null)
                    }

                    // 检测登录成功 - 多种方式
                    if (!isLoginHandled && url != null) {
                        val isMainPage = url.contains("c=home") ||
                                url.contains("c=myzone") ||
                                url.contains("c=course") ||
                                url.contains("index.php") && !url.contains("c=passport")

                        if (isMainPage) {
                            // 检查页面内容确认登录
                            val checkScript = """
                                (function() {
                                    try {
                                        var body = document.body ? document.body.innerText : '';
                                        if (body.indexOf('退出') >= 0 || body.indexOf('我的') >= 0 || body.indexOf('课程') >= 0) {
                                            return 'logged_in';
                                        }
                                        return 'not_logged_in';
                                    } catch(e) {
                                        return 'error';
                                    }
                                })();
                            """.trimIndent()

                            view?.evaluateJavascript(checkScript) { result ->
                                if (!isLoginHandled && (result.contains("logged_in") || isMainPage)) {
                                    handleLoginSuccess()
                                }
                            }
                        }
                    }
                }
            }

            webChromeClient = WebChromeClient()
        }

        setContentView(webView)
        webView.loadUrl("https://www.mosoteach.cn/web/index.php?c=passport")

        // 设置超时 - 2分钟后自动检查
        handler.postDelayed({
            if (!isLoginHandled) {
                checkLoginStatus()
            }
        }, 120000)
    }

    private fun checkLoginStatus() {
        if (isLoginHandled) return

        webView.evaluateJavascript(
            """
            (function() {
                try {
                    var url = window.location.href;
                    var body = document.body ? document.body.innerText : '';
                    if (url.indexOf('passport') < 0 || body.indexOf('退出') >= 0) {
                        return 'logged_in';
                    }
                    return 'not_logged_in';
                } catch(e) {
                    return 'error';
                }
            })();
            """.trimIndent()
        ) { result ->
            if (!isLoginHandled && result.contains("logged_in")) {
                handleLoginSuccess()
            }
        }
    }

    private fun handleLoginSuccess() {
        if (isLoginHandled) return
        isLoginHandled = true

        val cookies = CookieManager.getInstance().getCookie("https://www.mosoteach.cn") ?: ""

        Toast.makeText(this, "登录成功！正在启动签到...", Toast.LENGTH_SHORT).show()

        val resultIntent = Intent().apply {
            putExtra("cookies", cookies)
            putExtra("url", webView.url ?: "")
        }
        setResult(RESULT_LOGIN_SUCCESS, resultIntent)
        finish()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            setResult(RESULT_LOGIN_FAILED)
            super.onBackPressed()
        }
    }
}
