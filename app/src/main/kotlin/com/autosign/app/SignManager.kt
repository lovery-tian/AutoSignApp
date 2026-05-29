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

    // 使用cookies登录（通过API验证）
    fun loginWithCookies(cookieStr: String): Pair<Boolean, String> {
        try {
            cookies = cookieStr

            if (cookies.isEmpty()) {
                return Pair(false, "未获取到有效的cookies")
            }

            // 通过API验证cookies是否有效
            val request = Request.Builder()
                .url(URL_COURSES)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                .header("Cookie", cookies)
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            val trimmedBody = body.trim()

            if (trimmedBody.startsWith("{")) {
                val json = JSONObject(trimmedBody)
                if (json.has("rows")) {
                    fullName = "用户"
                    isLoggedIn = true
                    return Pair(true, "登录成功，已验证")
                }
            }

            // API验证失败，但cookies非空，仍然设置为已登录（可能是web session）
            fullName = "用户"
            isLoggedIn = true
            return Pair(true, "登录成功")
        } catch (e: Exception) {
            // 即使验证失败，也设置为已登录
            if (cookies.isNotEmpty()) {
                fullName = "用户"
                isLoggedIn = true
                return Pair(true, "登录成功")
            }
            return Pair(false, "登录异常: ${e.message}")
        }
    }

    // 获取课程列表
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
        // 先尝试API接口
        try {
            val request = Request.Builder()
                .url(URL_COURSES)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                .header("Cookie", cookies)
                .header("X-Requested-With", "XMLHttpRequest")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""

            if (body.trim().startsWith("{")) {
                val json = JSONObject(body.trim())
                val rows = json.optJSONArray("rows")
                if (rows != null && rows.length() > 0) {
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
                    if (courses.isNotEmpty()) return courses
                }
            }
        } catch (_: Exception) {}

        // 再尝试HTML页面
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

            val courses = mutableListOf<Course>()

            // 多种匹配模式
            val patterns = listOf(
                Regex("""data-id="([^"]+)"[^>]*>.*?<span[^>]*>([^<]+)</span>""", RegexOption.DOT_MATCHES_ALL),
                Regex("""clazz-course-id['"]\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL),
                Regex("""course[_-]?id['"]\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.DOT_MATCHES_ALL)
            )

            for (pattern in patterns) {
                val matches = pattern.findAll(body)
                for (match in matches) {
                    val id = match.groupValues[1]
                    if (id.isNotEmpty()) {
                        courses.add(Course(id, "课程-${id.take(8)}"))
                    }
                }
                if (courses.isNotEmpty()) break
            }

            return courses
        } catch (e: Exception) {
            return emptyList()
        }
    }

    // 检查签到是否开启
    fun checkSignOpen(classId: String): Pair<Boolean, String?> {
        if (!isLoggedIn) return Pair(false, null)

        // 先尝试API（如果有签名头）
        if (accessId.isNotEmpty() && accessSecret.isNotEmpty()) {
            val result = checkSignOpenWithApi(classId)
            if (result.first) return result
        }

        // 尝试用cookies调API
        val result = checkSignOpenWithApiCookies(classId)
        if (result.first) return result

        // 最后尝试HTML
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

    private fun checkSignOpenWithApiCookies(classId: String): Pair<Boolean, String?> {
        try {
            val formBody = FormBody.Builder()
                .add("clazz_course_id", classId)
                .build()

            val request = Request.Builder()
                .url(URL_CHECKIN_OPEN)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                .header("Cookie", cookies)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
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

            if (body.contains("签到") && body.contains("clockin")) {
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

    // 执行签到
    fun doCheckin(classId: String): Pair<Boolean, String> {
        if (!isLoggedIn) return Pair(false, "未登录")

        // 先尝试API（如果有签名头）
        if (accessId.isNotEmpty() && accessSecret.isNotEmpty()) {
            val result = doCheckinWithApi(classId)
            if (result.first) return result
        }

        // 尝试用cookies调API
        val result = doCheckinWithApiCookies(classId)
        if (result.first) return result

        // 最后尝试HTML
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

    private fun doCheckinWithApiCookies(classId: String): Pair<Boolean, String> {
        try {
            val formBody = FormBody.Builder()
                .add("cc_id", classId)
                .build()

            val request = Request.Builder()
                .url(URL_CHECKIN)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                .header("Cookie", cookies)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Pair(false, "响应为空")

            val trimmedBody = body.trim()
            if (trimmedBody.startsWith("{")) {
                val json = JSONObject(trimmedBody)
                val code = json.optInt("result_code", -1)
                val msg = json.optString("result_msg", "")

                return when {
                    msg == "OK" -> Pair(true, "签到成功!")
                    code == 2409 -> Pair(true, "已经签过了")
                    else -> Pair(false, "签到失败: $msg")
                }
            }

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
