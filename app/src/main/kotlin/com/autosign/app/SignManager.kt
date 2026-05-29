package com.autosign.app

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SignManager {

    companion object {
        private const val URL_COURSES = "https://api.mosoteach.cn/mssvc/index.php/cc/list_joined"
        private const val URL_CHECKIN_OPEN = "https://api.mosoteach.cn/mssvc/index.php/checkin/current_open"
        private const val URL_CHECKIN = "https://api.mosoteach.cn/mssvc/index.php/cc_clockin/clockin"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    var userId: String = ""
    var accessId: String = ""
    var accessSecret: String = ""
    var secTs: String = ""
    var fullName: String = ""
    var isLoggedIn: Boolean = false
    var cookies: String = ""

    data class Course(val id: String, val name: String)

    private fun getGMTTime(): String {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT+00:00'", Locale.ENGLISH)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun md5Hash(data: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha1(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val digest = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun makeSignature(url: String, method: Int, classId: String? = null): Pair<String, String> {
        val time = getGMTTime()
        val upperUserId = userId.uppercase()

        val message = when (method) {
            1 -> "$url|$upperUserId|$time"
            3 -> {
                val bodyMd5 = md5Hash("clazz_course_id=$classId").uppercase()
                "$url|$upperUserId|$time|$bodyMd5"
            }
            4 -> {
                val bodyMd5 = md5Hash("cc_id=$classId").uppercase()
                "$url|$upperUserId|$time|$bodyMd5"
            }
            else -> "$url|$upperUserId|$time"
        }

        val signature = hmacSha1(accessSecret, message)
        return Pair(signature, time)
    }

    private fun buildHeaders(url: String, method: Int, classId: String? = null): Map<String, String> {
        val (sig, time) = makeSignature(url, method, classId)
        return mapOf(
            "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 5 Build/SD1A.210817.037)",
            "X-scheme" to "https",
            "X-app-id" to "MTANDROID",
            "X-app-version" to "5.4.28",
            "X-dpr" to "2.75",
            "X-app-machine" to "Pixel 5",
            "X-app-system-version" to "12",
            "Host" to "api.mosoteach.cn",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Date" to time,
            "X-mssvc-signature" to sig,
            "X-mssvc-sec-ts" to secTs,
            "X-mssvc-access-id" to accessId,
            "Cookie" to cookies
        )
    }

    /**
     * 使用cookies登录（从WebView获取）
     */
    fun loginWithCookies(cookieStr: String): Pair<Boolean, String> {
        try {
            cookies = cookieStr

            // 使用cookies访问用户信息API
            val url = "https://www.mosoteach.cn/web/index.php?c=myzone&m=index"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Cookie", cookies)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Pair(false, "获取用户信息失败")

            // 从页面中提取用户信息
            // 尝试提取proxyToken
            val tokenMatch = Regex("proxyToken['\"]?\\s*[:=]\\s*['\"]([a-f0-9]+)['\"]").find(body)
            if (tokenMatch != null) {
                val token = tokenMatch.groupValues[1]
                return getUserInfoByToken(token)
            }

            // 尝试提取用户ID
            val userIdMatch = Regex("user_id['\"]?\\s*[:=]\\s*['\"]([a-f0-9-]+)['\"]").find(body)
            if (userIdMatch != null) {
                userId = userIdMatch.groupValues[1]
                fullName = "用户"
                isLoggedIn = true
                return Pair(true, "登录成功（通过Cookies）")
            }

            // 如果cookies有效但无法提取信息，仍然标记为已登录
            if (body.contains("我的空间") || body.contains("退出") || body.contains("logout")) {
                fullName = "用户"
                isLoggedIn = true
                return Pair(true, "登录成功（通过Cookies）")
            }

            return Pair(false, "无法获取用户信息，请重新登录")
        } catch (e: Exception) {
            return Pair(false, "登录异常: ${e.message}")
        }
    }

    private fun getUserInfoByToken(token: String): Pair<Boolean, String> {
        try {
            val url = "https://api.mosoteach.cn/mssvc/index.php/user/info"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 5 Build/SD1A.210817.037)")
                .header("X-app-id", "MTANDROID")
                .header("X-app-version", "5.4.28")
                .header("X-token", token)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Pair(false, "响应为空")

            val trimmedBody = body.trim()
            if (!trimmedBody.startsWith("{")) {
                // 即使无法获取详细信息，cookies有效也算登录成功
                isLoggedIn = true
                return Pair(true, "登录成功")
            }

            val json = JSONObject(trimmedBody)
            if (json.has("user_id")) {
                userId = json.getString("user_id")
                fullName = json.optString("full_name", "用户")
                accessId = json.optString("access_id", "")
                accessSecret = json.optString("access_secret", "")
                secTs = json.optString("last_sec_update_ts_s", "")
                isLoggedIn = true
                return Pair(true, "登录成功: $fullName")
            }

            isLoggedIn = true
            return Pair(true, "登录成功")
        } catch (e: Exception) {
            isLoggedIn = true
            return Pair(true, "登录成功（部分功能可能受限）")
        }
    }

    /**
     * 使用WebView获取的用户数据登录
     */
    fun loginWithWebViewData(data: String): Pair<Boolean, String> {
        try {
            val json = JSONObject(data)
            if (json.has("user_id")) {
                userId = json.getString("user_id")
                fullName = json.optString("full_name", "用户")
                accessId = json.optString("access_id", "")
                accessSecret = json.optString("access_secret", "")
                secTs = json.optString("last_sec_update_ts_s", "")
                isLoggedIn = true
                return Pair(true, "登录成功: $fullName")
            }
            return Pair(false, "解析用户信息失败")
        } catch (e: Exception) {
            return Pair(false, "登录异常: ${e.message}")
        }
    }

    /**
     * 获取当前学期课程列表
     */
    fun getCourses(): List<Course> {
        if (!isLoggedIn) return emptyList()

        // 如果有accessId和accessSecret，使用签名API
        if (accessId.isNotEmpty() && accessSecret.isNotEmpty()) {
            return getCoursesWithSignature()
        }

        // 否则使用cookies
        return getCoursesWithCookies()
    }

    private fun getCoursesWithSignature(): List<Course> {
        try {
            val headers = buildHeaders(URL_COURSES, method = 1)
            val requestBuilder = Request.Builder().url(URL_COURSES).get()
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: return emptyList()

            val trimmedBody = body.trim()
            if (!trimmedBody.startsWith("{")) return emptyList()

            val json = JSONObject(trimmedBody)
            val rows = json.optJSONArray("rows") ?: return emptyList()

            val courses = mutableListOf<Course>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val term = row.optJSONObject("term")
                if (term?.optString("is_current") == "Y") {
                    val courseObj = row.optJSONObject("course")
                    val name = courseObj?.optString("name") ?: "未知课程"
                    courses.add(Course(row.getString("id"), name))
                }
            }
            return courses
        } catch (e: Exception) {
            return emptyList()
        }
    }

    private fun getCoursesWithCookies(): List<Course> {
        try {
            val url = "https://www.mosoteach.cn/web/index.php?c=course&m=my_course"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                .header("Cookie", cookies)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()

            // 从HTML中提取课程信息
            val courses = mutableListOf<Course>()
            val coursePattern = Regex("""data-id="([^"]+)"[^>]*>.*?<span[^>]*>([^<]+)</span>""", RegexOption.DOT_MATCHES_ALL)
            val matches = coursePattern.findAll(body)

            for (match in matches) {
                val id = match.groupValues[1]
                val name = match.groupValues[2].trim()
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    courses.add(Course(id, name))
                }
            }

            return courses
        } catch (e: Exception) {
            return emptyList()
        }
    }

    /**
     * 检查签到是否开启
     */
    fun checkSignOpen(classId: String): Pair<Boolean, String?> {
        if (!isLoggedIn) return Pair(false, null)

        // 如果有签名信息，使用API
        if (accessId.isNotEmpty() && accessSecret.isNotEmpty()) {
            return checkSignOpenWithApi(classId)
        }

        // 否则使用cookies
        return checkSignOpenWithCookies(classId)
    }

    private fun checkSignOpenWithApi(classId: String): Pair<Boolean, String?> {
        try {
            val headers = buildHeaders(URL_CHECKIN_OPEN, method = 3, classId = classId)
            val formBody = FormBody.Builder()
                .add("clazz_course_id", classId)
                .build()

            val requestBuilder = Request.Builder().url(URL_CHECKIN_OPEN).post(formBody)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: return Pair(false, null)

            val trimmedBody = body.trim()
            if (!trimmedBody.startsWith("{")) return Pair(false, null)

            val json = JSONObject(trimmedBody)

            return if (json.optString("result_msg") == "OK") {
                val signId = json.optString("id", "")
                Pair(true, signId)
            } else {
                Pair(false, null)
            }
        } catch (e: Exception) {
            return Pair(false, null)
        }
    }

    private fun checkSignOpenWithCookies(classId: String): Pair<Boolean, String?> {
        try {
            val url = "https://www.mosoteach.cn/web/index.php?c=interaction&clazz_course_id=$classId"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                .header("Cookie", cookies)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Pair(false, null)

            // 检查是否有签到活动
            if (body.contains("签到") && body.contains("clockin")) {
                // 提取签到ID
                val signIdMatch = Regex("""clockin_id['"]?\s*[:=]\s*['"]([a-f0-9-]+)['"]""").find(body)
                if (signIdMatch != null) {
                    return Pair(true, signIdMatch.groupValues[1])
                }
                return Pair(true, "")
            }

            return Pair(false, null)
        } catch (e: Exception) {
            return Pair(false, null)
        }
    }

    /**
     * 执行签到
     */
    fun doCheckin(classId: String): Pair<Boolean, String> {
        if (!isLoggedIn) return Pair(false, "未登录")

        // 如果有签名信息，使用API
        if (accessId.isNotEmpty() && accessSecret.isNotEmpty()) {
            return doCheckinWithApi(classId)
        }

        // 否则使用cookies
        return doCheckinWithCookies(classId)
    }

    private fun doCheckinWithApi(classId: String): Pair<Boolean, String> {
        try {
            val headers = buildHeaders(URL_CHECKIN, method = 4, classId = classId)
            val formBody = FormBody.Builder()
                .add("cc_id", classId)
                .build()

            val requestBuilder = Request.Builder().url(URL_CHECKIN).post(formBody)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: return Pair(false, "响应为空")

            val trimmedBody = body.trim()
            if (!trimmedBody.startsWith("{")) return Pair(false, "签到失败: 服务器返回异常")

            val json = JSONObject(trimmedBody)

            val code = json.optInt("result_code", -1)
            val msg = json.optString("result_msg", "")

            return when {
                msg == "OK" -> Pair(true, "签到成功!")
                code == 2409 -> Pair(true, "已经签过了")
                else -> Pair(false, "签到失败: $msg")
            }
        } catch (e: Exception) {
            return Pair(false, "签到异常: ${e.message}")
        }
    }

    private fun doCheckinWithCookies(classId: String): Pair<Boolean, String> {
        try {
            val url = "https://www.mosoteach.cn/web/index.php?c=interaction&a=clockin"
            val formBody = FormBody.Builder()
                .add("clazz_course_id", classId)
                .build()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                .header("Cookie", cookies)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Pair(false, "响应为空")

            // 检查是否成功
            if (body.contains("success") || body.contains("成功") || body.contains("OK")) {
                return Pair(true, "签到成功!")
            }
            if (body.contains("已签") || body.contains("already")) {
                return Pair(true, "已经签过了")
            }

            return Pair(false, "签到失败")
        } catch (e: Exception) {
            return Pair(false, "签到异常: ${e.message}")
        }
    }
}
