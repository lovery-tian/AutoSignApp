package com.autosign.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity

class LoginWebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val RESULT_LOGIN_SUCCESS = 100
        const val RESULT_LOGIN_FAILED = 101
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // 检查是否登录成功（页面跳转到主页）
                    if (url?.contains("index.php?c=home") == true ||
                        url?.contains("index.php?c=myzone") == true) {

                        // 获取用户信息
                        val script = """
                            (function() {
                                try {
                                    var userInfo = localStorage.getItem('userInfo');
                                    if (userInfo) return userInfo;
                                    return '';
                                } catch(e) {
                                    return '';
                                }
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(script) { result ->
                            if (result.isNotEmpty() && result != "null") {
                                // 登录成功，返回用户信息
                                val intent = Intent().apply {
                                    putExtra("login_data", result)
                                }
                                setResult(RESULT_LOGIN_SUCCESS, intent)
                                finish()
                            }
                        }
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                }
            }

            webChromeClient = WebChromeClient()
        }

        setContentView(webView)

        // 加载登录页面
        webView.loadUrl("https://www.mosoteach.cn/web/index.php?c=passport")

        // 自动填充账号密码（延迟执行，等待页面加载）
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""

        if (username.isNotEmpty()) {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)

                    // 自动填充表单
                    val fillScript = """
                        (function() {
                            try {
                                var accountInput = document.querySelector('input[name="account_name"]') || document.querySelector('input[type="text"]');
                                var pwdInput = document.querySelector('input[name="user_password"]') || document.querySelector('input[type="password"]');
                                if (accountInput) accountInput.value = '$username';
                                if (pwdInput) pwdInput.value = '$password';
                            } catch(e) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(fillScript, null)

                    // 检查是否已登录
                    if (url?.contains("index.php?c=home") == true ||
                        url?.contains("index.php?c=myzone") == true) {
                        // 已登录，获取信息
                        val getinfoScript = """
                            (function() {
                                try {
                                    var userInfo = localStorage.getItem('proxyToken') || '';
                                    return userInfo;
                                } catch(e) {
                                    return '';
                                }
                            })();
                        """.trimIndent()

                        view?.evaluateJavascript(getinfoScript) { result ->
                            if (result.isNotEmpty() && result != "null") {
                                val intent = Intent().apply {
                                    putExtra("token", result.replace("\"", ""))
                                }
                                setResult(RESULT_LOGIN_SUCCESS, intent)
                                finish()
                            }
                        }
                    }
                }
            }
        }
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
