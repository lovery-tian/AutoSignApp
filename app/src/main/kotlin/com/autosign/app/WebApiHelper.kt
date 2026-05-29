package com.autosign.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 使用WebView执行web端操作的辅助类
 * 因为移动端API需要专用签名header，web页面是JS渲染的
 * 所以必须用WebView来获取课程和执行签到
 */
class WebApiHelper(private val context: Context) {

    companion object {
        private const val TAG = "WebApiHelper"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36"
        }
    }

    /**
     * 通过WebView获取课程列表
     * 加载课程页面 → JS提取课程信息
     */
    fun getCoursesViaWebView(cookies: String, callback: (List<SignManager.Course>) -> Unit) {
        Log.d(TAG, "getCoursesViaWebView 开始")

        mainHandler.post {
            val wv = createWebView()
            webView = wv

            // 设置cookies
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            // 设置web cookies
            val webCookieParts = cookies.split(";").map { it.trim() }
            for (cookie in webCookieParts) {
                if (cookie.isNotEmpty()) {
                    cookieManager.setCookie("https://www.mosoteach.cn", cookie)
                }
            }
            cookieManager.flush()

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "课程页面加载完成: $url")

                    // 等待JS渲染完成后提取课程
                    handler.postDelayed({
                        extractCourses(view) { courses ->
                            Log.d(TAG, "提取到 ${courses.size} 门课程")
                            callback(courses)
                            mainHandler.post {
                                view?.destroy()
                                webView = null
                            }
                        }
                    }, 3000) // 等3秒让JS渲染
                }
            }

            Log.d(TAG, "加载课程页面...")
            wv.loadUrl("https://www.mosoteach.cn/web/index.php?c=course&m=my_course")
        }
    }

    private fun extractCourses(view: WebView?, callback: (List<SignManager.Course>) -> Unit) {
        val script = """
            (function() {
                try {
                    var courses = [];

                    // 方法1: 查找课程卡片元素
                    var items = document.querySelectorAll('[data-id], .course-item, .clazz-item, .course-card');
                    items.forEach(function(item) {
                        var id = item.getAttribute('data-id') || item.getAttribute('data-clazz-course-id') || '';
                        var nameEl = item.querySelector('.course-name, .clazz-name, span, h3, h4, .name');
                        var name = nameEl ? nameEl.textContent.trim() : '';
                        if (id && name && name.length > 0 && name.length < 50) {
                            courses.push({id: id, name: name});
                        }
                    });

                    // 方法2: 查找链接中的课程ID
                    if (courses.length === 0) {
                        var links = document.querySelectorAll('a[href*="clazz_course_id"], a[href*="course_id"]');
                        links.forEach(function(link) {
                            var href = link.getAttribute('href') || '';
                            var match = href.match(/clazz_course_id[=:]([a-f0-9-]+)/);
                            if (!match) match = href.match(/course_id[=:]([a-f0-9-]+)/);
                            if (match) {
                                var name = link.textContent.trim();
                                if (name && name.length > 0 && name.length < 50) {
                                    courses.push({id: match[1], name: name});
                                }
                            }
                        });
                    }

                    // 方法3: 查找页面中所有看起来像UUID的元素
                    if (courses.length === 0) {
                        var allElements = document.querySelectorAll('*');
                        var uuidRegex = /[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/g;
                        var html = document.body.innerHTML;
                        var matches = html.match(uuidRegex) || [];
                        var uniqueIds = [];
                        matches.forEach(function(id) {
                            if (uniqueIds.indexOf(id) === -1) uniqueIds.push(id);
                        });
                        // 取前20个作为可能的课程ID
                        uniqueIds.slice(0, 20).forEach(function(id) {
                            courses.push({id: id, name: '课程-' + id.substring(0, 8)});
                        });
                    }

                    return JSON.stringify(courses);
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(script) { result ->
            Log.d(TAG, "extractCourses result: ${result?.take(200)}")
            try {
                val cleanResult = result?.replace("\\\"", "\"")?.removeSurrounding("\"") ?: "[]"
                if (cleanResult.startsWith("[")) {
                    val json = org.json.JSONArray(cleanResult)
                    val courses = mutableListOf<SignManager.Course>()
                    for (i in 0 until json.length()) {
                        val obj = json.getJSONObject(i)
                        val id = obj.getString("id")
                        val name = obj.getString("name")
                        if (id.isNotEmpty()) {
                            courses.add(SignManager.Course(id, name))
                        }
                    }
                    callback(courses)
                } else {
                    Log.d(TAG, "extractCourses 解析失败: $cleanResult")
                    callback(emptyList())
                }
            } catch (e: Exception) {
                Log.d(TAG, "extractCourses 异常: ${e.message}")
                callback(emptyList())
            }
        }
    }

    /**
     * 通过WebView检查签到是否开启
     */
    fun checkSignViaWebView(classId: String, cookies: String, callback: (Boolean, String?) -> Unit) {
        Log.d(TAG, "checkSignViaWebView: $classId")

        mainHandler.post {
            val wv = createWebView()
            webView = wv

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val webCookieParts = cookies.split(";").map { it.trim() }
            for (cookie in webCookieParts) {
                if (cookie.isNotEmpty()) {
                    cookieManager.setCookie("https://www.mosoteach.cn", cookie)
                }
            }
            cookieManager.flush()

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "签到页面加载完成: $url")

                    handler.postDelayed({
                        checkSignOnPage(view) { isOpen, signId ->
                            callback(isOpen, signId)
                            mainHandler.post {
                                view?.destroy()
                                webView = null
                            }
                        }
                    }, 2000)
                }
            }

            wv.loadUrl("https://www.mosoteach.cn/web/index.php?c=interaction&clazz_course_id=$classId")
        }
    }

    private fun checkSignOnPage(view: WebView?, callback: (Boolean, String?) -> Unit) {
        val script = """
            (function() {
                try {
                    // 查找签到按钮或签到活动
                    var signBtn = document.querySelector('[data-action="clockin"], .clockin-btn, .sign-btn, button[data-type="sign"]');
                    if (signBtn) {
                        var signId = signBtn.getAttribute('data-id') || signBtn.getAttribute('data-clockin-id') || '';
                        return JSON.stringify({isOpen: true, signId: signId});
                    }

                    // 查找页面中的签到相关内容
                    var body = document.body.innerHTML;
                    if (body.indexOf('签到') !== -1 && (body.indexOf('clockin') !== -1 || body.indexOf('正在进行') !== -1)) {
                        // 尝试提取签到ID
                        var match = body.match(/clockin[_-]?id['":\s]*['"]*([a-f0-9-]+)/);
                        var signId = match ? match[1] : '';
                        return JSON.stringify({isOpen: true, signId: signId});
                    }

                    return JSON.stringify({isOpen: false, signId: null});
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(script) { result ->
            Log.d(TAG, "checkSignOnPage result: $result")
            try {
                val cleanResult = result?.replace("\\\"", "\"")?.removeSurrounding("\"") ?: "{}"
                if (cleanResult.startsWith("{")) {
                    val json = org.json.JSONObject(cleanResult)
                    val isOpen = json.optBoolean("isOpen", false)
                    val signId = json.optString("signId", null)
                    callback(if (isOpen) true else false, signId)
                } else {
                    callback(false, null)
                }
            } catch (e: Exception) {
                Log.d(TAG, "checkSignOnPage 异常: ${e.message}")
                callback(false, null)
            }
        }
    }

    /**
     * 通过WebView执行签到
     */
    fun doSignViaWebView(classId: String, cookies: String, callback: (Boolean, String) -> Unit) {
        Log.d(TAG, "doSignViaWebView: $classId")

        mainHandler.post {
            val wv = createWebView()
            webView = wv

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val webCookieParts = cookies.split(";").map { it.trim() }
            for (cookie in webCookieParts) {
                if (cookie.isNotEmpty()) {
                    cookieManager.setCookie("https://www.mosoteach.cn", cookie)
                }
            }
            cookieManager.flush()

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.d(TAG, "签到执行页面加载完成: $url")

                    handler.postDelayed({
                        executeSignOnPage(view, classId) { success, msg ->
                            callback(success, msg)
                            mainHandler.post {
                                view?.destroy()
                                webView = null
                            }
                        }
                    }, 2000)
                }
            }

            wv.loadUrl("https://www.mosoteach.cn/web/index.php?c=interaction&clazz_course_id=$classId")
        }
    }

    private fun executeSignOnPage(view: WebView?, classId: String, callback: (Boolean, String) -> Unit) {
        val script = """
            (function() {
                try {
                    // 方法1: 查找并点击签到按钮
                    var signBtn = document.querySelector('[data-action="clockin"], .clockin-btn, .sign-btn, button[data-type="sign"]');
                    if (signBtn) {
                        signBtn.click();
                        return JSON.stringify({clicked: true, method: 'button'});
                    }

                    // 方法2: 查找签到表单并提交
                    var form = document.querySelector('form[action*="clockin"]');
                    if (form) {
                        form.submit();
                        return JSON.stringify({clicked: true, method: 'form'});
                    }

                    // 方法3: 通过JS发起签到请求
                    var xhr = new XMLHttpRequest();
                    xhr.open('POST', '/web/index.php?c=interaction&a=clockin', false);
                    xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
                    xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                    xhr.send('clazz_course_id=$classId');

                    if (xhr.status === 200) {
                        var resp = xhr.responseText;
                        if (resp.indexOf('success') !== -1 || resp.indexOf('OK') !== -1 || resp.indexOf('成功') !== -1) {
                            return JSON.stringify({clicked: true, method: 'xhr', response: 'success'});
                        }
                        if (resp.indexOf('已签') !== -1 || resp.indexOf('already') !== -1) {
                            return JSON.stringify({clicked: true, method: 'xhr', response: 'already'});
                        }
                        return JSON.stringify({clicked: false, method: 'xhr', response: resp.substring(0, 100)});
                    }

                    return JSON.stringify({clicked: false, method: 'none'});
                } catch(e) {
                    return JSON.stringify({error: e.message});
                }
            })();
        """.trimIndent()

        view?.evaluateJavascript(script) { result ->
            Log.d(TAG, "executeSignOnPage result: $result")
            try {
                val cleanResult = result?.replace("\\\"", "\"")?.removeSurrounding("\"") ?: "{}"
                if (cleanResult.startsWith("{")) {
                    val json = org.json.JSONObject(cleanResult)
                    val clicked = json.optBoolean("clicked", false)
                    val response = json.optString("response", "")

                    when {
                        response == "success" -> callback(true, "签到成功!")
                        response == "already" -> callback(true, "已经签过了")
                        clicked -> callback(true, "已尝试签到")
                        else -> callback(false, "签到失败: ${json.optString("method", "unknown")}")
                    }
                } else {
                    callback(false, "签到失败")
                }
            } catch (e: Exception) {
                Log.d(TAG, "executeSignOnPage 异常: ${e.message}")
                callback(false, "签到异常: ${e.message}")
            }
        }
    }

    fun destroy() {
        mainHandler.post {
            webView?.destroy()
            webView = null
        }
    }
}
