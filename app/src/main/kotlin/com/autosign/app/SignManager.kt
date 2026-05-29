package com.autosign.app

import android.util.Log
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SignManager {

    companion object {
        private const val TAG = "SignManager"

        // Legacy mobile API endpoints
        private const val URL_LOGIN = "https://api.mosoteach.cn/mssvc/index.php/passport/login"
        private const val URL_COURSES = "https://api.mosoteach.cn/mssvc/index.php/cc/list_joined"
        private const val URL_CHECKIN_OPEN = "https://api.mosoteach.cn/mssvc/index.php/checkin/current_open"
        private const val URL_CHECKIN = "https://api.mosoteach.cn/mssvc/index.php/cc_clockin/clockin"
        private const val URL_CHECKIN_GPS = "https://checkin.mosoteach.cn:19528/checkin"

        // Hardcoded login key from multiple open-source repos
        private const val LOGIN_KEY = "526EBA802E6FCF44661DE4393A82ABDA"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    var userId: String = ""
    var accessId: String = ""
    var accessSecret: String = ""
    var secTs: String = ""
    var fullName: String = ""
    var isLoggedIn: Boolean = false

    data class Course(val id: String, val name: String)
    data class CheckinInfo(val isOpen: Boolean, val checkinId: String, val type: String)

    // ==================== 工具方法 ====================

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
     * 生成签名
     * 登录: message = url|GMT时间|表单字段（原始，不MD5）
     * GET:  message = url|USER_ID|GMT时间
     * POST: message = url|USER_ID|GMT时间|MD5(表单字段).大写
     */
    private fun makeSignature(url: String, formFields: String? = null, isLogin: Boolean = false): Pair<String, String> {
        val time = getGMTTime()

        val message = if (isLogin) {
            // 登录签名: url|time|form_fields (raw, pipe-separated, not MD5)
            "$url|$time|$formFields"
        } else if (formFields != null) {
            // POST: url|USER_ID|time|MD5(fields).uppercase
            val bodyMd5 = md5Hash(formFields).uppercase()
            "$url|${userId.uppercase()}|$time|$bodyMd5"
        } else {
            // GET: url|USER_ID|time
            "$url|${userId.uppercase()}|$time"
        }

        val key = if (isLogin) LOGIN_KEY else accessSecret
        val signature = hmacSha1(key, message)

        Log.d(TAG, "签名 key: ${key.take(8)}...")
        Log.d(TAG, "签名 message: ${message.take(150)}...")
        Log.d(TAG, "签名 result: $signature")
        return Pair(signature, time)
    }

    private fun buildHeaders(url: String, formFields: String? = null, isLogin: Boolean = false): Map<String, String> {
        val (sig, time) = makeSignature(url, formFields, isLogin)
        return mapOf(
            "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 5 Build/SD1A.210817.037)",
            "X-scheme" to "https",
            "X-app-id" to "MTANDROID",
            "X-app-version" to "5.4.33",
            "X-dpr" to "3.5",
            "X-app-machine" to "Pixel 5",
            "X-app-system-version" to "14",
            "Host" to "api.mosoteach.cn",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Date" to time,
            "X-mssvc-signature" to sig,
            "X-mssvc-sec-ts" to secTs,
            "X-mssvc-access-id" to accessId
        )
    }

    // ==================== 登录 ====================

    /**
     * API直接登录（不需要WebView）
     * 使用硬编码密钥签名，调用移动端登录接口
     */
    fun login(username: String, password: String): Pair<Boolean, String> {
        try {
            // 构建表单字段（按字母顺序，用|分隔，与签名一致）
            val formFields = "account_name=$username|app_id=MTANDROID|app_version_name=5.4.33|device_type=ANDROID|dpr=3.5|remember_me=N|system_version=14|user_pwd=$password"

            val (sig, time) = makeSignature(URL_LOGIN, formFields, isLogin = true)

            val formBody = FormBody.Builder()
                .add("account_name", username)
                .add("user_pwd", password)
                .add("remember_me", "N")
                .add("app_id", "MTANDROID")
                .add("app_version_name", "5.4.33")
                .add("device_type", "ANDROID")
                .add("dpr", "3.5")
                .add("system_version", "14")
                .build()

            val request = Request.Builder()
                .url(URL_LOGIN)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 5 Build/SD1A.210817.037)")
                .header("X-scheme", "https")
                .header("X-app-id", "MTANDROID")
                .header("X-app-version", "5.4.33")
                .header("X-dpr", "3.5")
                .header("X-app-machine", "Pixel 5")
                .header("X-app-system-version", "14")
                .header("Host", "api.mosoteach.cn")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Date", time)
                .header("X-mssvc-signature", sig)
                .post(formBody)
                .build()

            Log.d(TAG, "登录请求: $URL_LOGIN")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            Log.d(TAG, "登录响应前200字: ${body.take(200)}")

            val trimmedBody = body.trim()
            if (!trimmedBody.startsWith("{")) {
                return Pair(false, "服务器返回非JSON: ${trimmedBody.take(100)}")
            }

            val json = JSONObject(trimmedBody)
            val resultCode = json.optInt("result_code", -1)
            val resultMsg = json.optString("result_msg", "")

            if (resultCode == 0 && resultMsg == "OK") {
                val user = json.optJSONObject("user")
                if (user != null) {
                    userId = user.optString("user_id", "")
                    accessId = user.optString("access_id", "")
                    accessSecret = user.optString("access_secret", "")
                    secTs = user.optString("last_sec_update_ts_s", "")
                    fullName = user.optString("full_name", "用户")
                    isLoggedIn = true

                    Log.d(TAG, "登录成功: userId=$userId, accessId=$accessId, fullName=$fullName")
                    return Pair(true, "登录成功: $fullName")
                }
                return Pair(false, "登录响应缺少user字段")
            }

            return Pair(false, "登录失败: $resultMsg (code=$resultCode)")
        } catch (e: Exception) {
            Log.e(TAG, "登录异常", e)
            return Pair(false, "登录异常: ${e.message}")
        }
    }

    /**
     * 使用cookies登录（WebView登录后的回调）
     * 仅设置cookies，不验证有效性（后续API调用会验证）
     */
    fun loginWithCookies(cookieStr: String): Pair<Boolean, String> {
        if (cookieStr.isEmpty()) {
            return Pair(false, "未获取到有效的cookies")
        }
        fullName = "用户"
        isLoggedIn = true
        return Pair(true, "登录成功(cookies)")
    }

    // ==================== 获取课程 ====================

    fun getCourses(): List<Course> {
        if (!isLoggedIn) return emptyList()

        // 优先用签名API
        if (accessId.isNotEmpty() && accessSecret.isNotEmpty()) {
            val result = getCoursesWithSignature()
            if (result.isNotEmpty()) return result
        }

        return emptyList()
    }

    private fun getCoursesWithSignature(): List<Course> {
        try {
            val headers = buildHeaders(URL_COURSES)
            val formBody = FormBody.Builder().build() // POST with empty body
            val requestBuilder = Request.Builder().url(URL_COURSES).post(formBody)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: return emptyList()
            val trimmedBody = body.trim()

            Log.d(TAG, "课程响应前200字: ${trimmedBody.take(200)}")

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
            Log.d(TAG, "获取到 ${courses.size} 门当前学期课程")
            return courses
        } catch (e: Exception) {
            Log.e(TAG, "获取课程异常", e)
            return emptyList()
        }
    }

    // ==================== 检查签到 ====================

    fun checkSignOpen(classId: String): CheckinInfo {
        if (!isLoggedIn) return CheckinInfo(false, "", "")

        if (accessId.isNotEmpty() && accessSecret.isNotEmpty()) {
            return checkSignOpenWithSignature(classId)
        }

        return CheckinInfo(false, "", "")
    }

    private fun checkSignOpenWithSignature(classId: String): CheckinInfo {
        try {
            val formFields = "clazz_course_id=$classId"
            val headers = buildHeaders(URL_CHECKIN_OPEN, formFields)

            val formBody = FormBody.Builder()
                .add("clazz_course_id", classId)
                .build()

            val requestBuilder = Request.Builder().url(URL_CHECKIN_OPEN).post(formBody)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: return CheckinInfo(false, "", "")
            val trimmedBody = body.trim()

            Log.d(TAG, "签到检测响应: ${trimmedBody.take(200)}")

            if (!trimmedBody.startsWith("{")) return CheckinInfo(false, "", "")

            val json = JSONObject(trimmedBody)
            val resultCode = json.optInt("result_code", -1)
            val status = json.optBoolean("status", false)

            if (resultCode == 0 && status) {
                val checkin = json.optJSONObject("checkin")
                val checkinId = checkin?.optString("id", "") ?: ""
                val type = checkin?.optString("type", "CLOCKIN") ?: "CLOCKIN"
                Log.d(TAG, "检测到签到: type=$type, checkinId=$checkinId")
                return CheckinInfo(true, checkinId, type)
            }

            return CheckinInfo(false, "", "")
        } catch (e: Exception) {
            Log.e(TAG, "检测签到异常", e)
            return CheckinInfo(false, "", "")
        }
    }

    // ==================== 执行签到 ====================

    fun doCheckin(classId: String, checkinId: String = "", type: String = "CLOCKIN"): Pair<Boolean, String> {
        if (!isLoggedIn) return Pair(false, "未登录")

        if (accessId.isNotEmpty() && accessSecret.isNotEmpty()) {
            return if (type == "CLOCKIN") {
                doClockinWithSignature(classId)
            } else {
                doGpsCheckinWithSignature(checkinId)
            }
        }

        return Pair(false, "未获取到API凭证")
    }

    /**
     * 普通签到（CLOCKIN类型）
     */
    private fun doClockinWithSignature(classId: String): Pair<Boolean, String> {
        try {
            val formFields = "cc_id=$classId"
            val headers = buildHeaders(URL_CHECKIN, formFields)

            val formBody = FormBody.Builder()
                .add("cc_id", classId)
                .build()

            val requestBuilder = Request.Builder().url(URL_CHECKIN).post(formBody)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }

            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: return Pair(false, "响应为空")
            val trimmedBody = body.trim()

            Log.d(TAG, "签到响应: ${trimmedBody.take(200)}")

            if (!trimmedBody.startsWith("{")) return Pair(false, "签到失败: 服务器返回异常")

            val json = JSONObject(trimmedBody)
            val code = json.optInt("result_code", -1)
            val msg = json.optString("result_msg", "")

            return when {
                code == 0 && msg == "OK" -> Pair(true, "签到成功!")
                code == 2409 -> Pair(true, "已经签过了")
                else -> Pair(false, "签到失败: $msg (code=$code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "签到异常", e)
            return Pair(false, "签到异常: ${e.message}")
        }
    }

    /**
     * GPS签到
     * 注意：这里使用默认坐标，实际使用时应获取真实位置
     */
    private fun doGpsCheckinWithSignature(checkinId: String): Pair<Boolean, String> {
        try {
            // GPS签到的签名不包含表单字段
            val (sig, time) = makeSignature(URL_CHECKIN_GPS)

            val formBody = FormBody.Builder()
                .add("checkin_id", checkinId)
                .add("report_pos_flag", "Y")
                .add("lat", "30.0")  // 默认坐标
                .add("lng", "120.0") // 默认坐标
                .build()

            val request = Request.Builder()
                .url(URL_CHECKIN_GPS)
                .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 5 Build/SD1A.210817.037)")
                .header("X-scheme", "https")
                .header("X-app-id", "MTANDROID")
                .header("X-app-version", "5.4.33")
                .header("X-dpr", "3.5")
                .header("X-app-machine", "Pixel 5")
                .header("X-app-system-version", "14")
                .header("Host", "checkin.mosoteach.cn:19528")
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .header("Date", time)
                .header("X-mssvc-signature", sig)
                .header("X-mssvc-sec-ts", secTs)
                .header("X-mssvc-access-id", accessId)
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Pair(false, "响应为空")
            val trimmedBody = body.trim()

            Log.d(TAG, "GPS签到响应: ${trimmedBody.take(200)}")

            if (!trimmedBody.startsWith("{")) return Pair(false, "签到失败: 服务器返回异常")

            val json = JSONObject(trimmedBody)
            val code = json.optInt("result_code", -1)
            val msg = json.optString("result_msg", "")

            return when {
                code == 0 -> Pair(true, "GPS签到成功!")
                code == 2409 -> Pair(true, "已经签过了")
                code == 2404 -> Pair(false, "签到未开始")
                else -> Pair(false, "GPS签到失败: $msg (code=$code)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "GPS签到异常", e)
            return Pair(false, "GPS签到异常: ${e.message}")
        }
    }
}
