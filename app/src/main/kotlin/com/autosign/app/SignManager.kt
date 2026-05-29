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

/**
 * 云班课 API 签到管理器
 */
class SignManager {

    companion object {
        private const val URL_LOGIN = "https://www.mosoteach.cn/web/index.php?c=passport&m=account_login"
        private const val URL_COURSES = "https://api.mosoteach.cn/mssvc/index.php/cc/list_joined"
        private const val URL_CHECKIN_OPEN = "https://api.mosoteach.cn/mssvc/index.php/checkin/current_open"
        private const val URL_CHECKIN = "https://api.mosoteach.cn/mssvc/index.php/cc_clockin/clockin"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // 用户信息
    var userId: String = ""
    var accessId: String = ""
    var accessSecret: String = ""
    var secTs: String = ""
    var fullName: String = ""
    var isLoggedIn: Boolean = false

    data class Course(val id: String, val name: String)

    // ==================== 工具函数 ====================

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

    /**
     * 构建签名
     * method: 1=课程列表, 3=签到状态, 4=执行签到
     */
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
            "X-mssvc-access-id" to accessId
        )
    }

    // ==================== API 调用 ====================

    /**
     * 登录
     * @return Pair<Boolean, String> = (是否成功, 消息)
     */
    fun login(username: String, password: String): Pair<Boolean, String> {
        try {
            val formBody = FormBody.Builder()
                .add("account_name", username)
                .add("user_pwd", password)
                .add("remember_me", "N")
                .build()

            val request = Request.Builder()
                .url(URL_LOGIN)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Pair(false, "响应为空")

            // 检查响应是否为JSON格式
            val trimmedBody = body.trim()
            if (!trimmedBody.startsWith("{")) {
                // 服务器返回了HTML或其他非JSON内容
                return when {
                    trimmedBody.contains("DOCTYPE") || trimmedBody.contains("<html") ->
                        Pair(false, "账号或密码错误")
                    trimmedBody.contains("error") || trimmedBody.contains("Error") ->
                        Pair(false, "服务器错误，请稍后重试")
                    else ->
                        Pair(false, "登录失败: 服务器返回异常响应")
                }
            }

            val json = JSONObject(trimmedBody)

            return if (json.has("user")) {
                val user = json.getJSONObject("user")
                userId = user.getString("user_id")
                accessId = user.getString("access_id")
                accessSecret = user.getString("access_secret")
                secTs = user.getString("last_sec_update_ts_s")
                fullName = user.optString("full_name", "用户")
                isLoggedIn = true
                Pair(true, "登录成功: $fullName")
            } else {
                val msg = json.optString("result_msg", "未知错误")
                val code = json.optInt("result_code", -1)
                when {
                    msg.contains("密码") || msg.contains("password") ->
                        Pair(false, "密码错误，请重新输入")
                    msg.contains("账号") || msg.contains("account") || msg.contains("exist") ->
                        Pair(false, "账号不存在或未注册")
                    code == 1001 ->
                        Pair(false, "账号或密码错误")
                    else ->
                        Pair(false, "登录失败: $msg")
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            return Pair(false, "网络超时，请检查网络连接")
        } catch (e: java.net.UnknownHostException) {
            return Pair(false, "无法连接服务器，请检查网络")
        } catch (e: Exception) {
            return Pair(false, "登录异常: ${e.message}")
        }
    }

    /**
     * 获取当前学期课程列表
     */
    fun getCourses(): List<Course> {
        if (!isLoggedIn) return emptyList()

        try {
            val headers = buildHeaders(URL_COURSES, method = 1)
            val requestBuilder = Request.Builder().url(URL_COURSES).get()
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: return emptyList()

            // 检查响应是否为JSON
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

    /**
     * 检查签到是否开启
     * @return Pair<Boolean, String?> = (是否开启, 签到ID或null)
     */
    fun checkSignOpen(classId: String): Pair<Boolean, String?> {
        if (!isLoggedIn) return Pair(false, null)

        try {
            val headers = buildHeaders(URL_CHECKIN_OPEN, method = 3, classId = classId)
            val formBody = FormBody.Builder()
                .add("clazz_course_id", classId)
                .build()

            val requestBuilder = Request.Builder().url(URL_CHECKIN_OPEN).post(formBody)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: return Pair(false, null)

            // 检查响应是否为JSON
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

    /**
     * 执行签到
     * @return Pair<Boolean, String> = (是否成功, 消息)
     */
    fun doCheckin(classId: String): Pair<Boolean, String> {
        if (!isLoggedIn) return Pair(false, "未登录")

        try {
            val headers = buildHeaders(URL_CHECKIN, method = 4, classId = classId)
            val formBody = FormBody.Builder()
                .add("cc_id", classId)
                .build()

            val requestBuilder = Request.Builder().url(URL_CHECKIN).post(formBody)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: return Pair(false, "响应为空")

            // 检查响应是否为JSON
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
}
