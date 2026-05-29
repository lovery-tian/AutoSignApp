package com.autosign.app

import android.annotation.SuppressLint
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
    private var isLoginHandled = false

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

                    if (isLoginHandled) return

                    // 自动填充表单
                    if (url?.contains("passport") == true && username.isNotEmpty()) {
                        val fillScript = """
                            (function() {
                                try {
                                    var inputs = document.querySelectorAll('input');
                                    for (var i = 0; i < inputs.length; i++) {
                                        if (inputs[i].name === 'account_name' || inputs[i].type === 'text' || inputs[i].type === 'tel') {
                                            inputs[i].value = '$username';
                                        }
                                        if (inputs[i].name === 'user_password' || inputs[i].type === 'password') {
                                            inputs[i].value = '$password';
                                        }
                                    }
                                } catch(e) {}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(fillScript, null)
                    }

                    // 检测登录成功（跳转到主页）
                    if (url != null && (url.contains("c=home") || url.contains("c=myzone") || url.contains("c=course"))) {
                        isLoginHandled = true
                        // 获取cookies
                        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                        // 返回结果
                        val resultIntent = Intent().apply {
                            putExtra("cookies", cookies)
                            putExtra("url", url)
                        }
                        setResult(RESULT_LOGIN_SUCCESS, resultIntent)
                        finish()
                    }
                }
            }

            webChromeClient = WebChromeClient()
        }

        setContentView(webView)
        webView.loadUrl("https://www.mosoteach.cn/web/index.php?c=passport")
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
