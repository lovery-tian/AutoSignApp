package com.autosign.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class AutoSignService : Service() {

    companion object {
        const val CHANNEL_ID = "autosign_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.autosign.app.STOP"

        var isRunning = false
        var logCallback: ((String) -> Unit)? = null
    }

    private val signManager = SignManager()
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val username = intent?.getStringExtra("username") ?: ""
        val password = intent?.getStringExtra("password") ?: ""
        val proxyToken = intent?.getStringExtra("proxy_token") ?: ""
        val loginData = intent?.getStringExtra("login_data") ?: ""

        if (username.isEmpty() || password.isEmpty()) {
            sendLog("错误: 账号或密码为空")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("正在登录..."))
        acquireWakeLock()

        if (!running.getAndSet(true)) {
            isRunning = true
            workerThread = Thread { workerLoop(username, password, proxyToken, loginData) }.apply { start() }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        workerThread?.interrupt()
        releaseWakeLock()
        sendLog("自动签到已停止")
        super.onDestroy()
    }

    private fun workerLoop(username: String, password: String, proxyToken: String = "", loginData: String = "") {
        // 登录
        sendLog("正在登录...")

        var loginOk = false
        var loginMsg = ""

        // 优先使用WebView获取的登录数据
        if (loginData.isNotEmpty()) {
            val (ok, msg) = signManager.loginWithWebViewData(loginData)
            loginOk = ok
            loginMsg = msg
            if (ok) {
                sendLog("✅ [WebView数据] $loginMsg")
            }
        }

        // 如果没有loginData，尝试使用token
        if (!loginOk && proxyToken.isNotEmpty()) {
            val (ok, msg) = signManager.loginWithToken(proxyToken)
            loginOk = ok
            loginMsg = msg
            if (ok) {
                sendLog("✅ [Token] $loginMsg")
            }
        }

        // 如果都失败，尝试传统登录
        if (!loginOk) {
            val (ok, msg) = signManager.login(username, password)
            loginOk = ok
            loginMsg = msg
            if (ok) {
                sendLog("✅ $loginMsg")
            }
        }

        if (!loginOk) {
            sendLog("❌ $loginMsg")
            sendLog("请使用'WebView登录'按钮登录，或检查账号密码")
            updateNotification("登录失败: $loginMsg")
            Thread.sleep(3000)
            stopSelf()
            return
        }

        // 获取课程
        sendLog("正在获取课程...")
        val courses = signManager.getCourses()
        if (courses.isEmpty()) {
            sendLog("未找到当前学期课程")
            updateNotification("未找到课程")
            stopSelf()
            return
        }

        sendLog("找到 ${courses.size} 门课程:")
        courses.forEach { sendLog("  - ${it.name}") }
        MainActivity.courseCallback?.invoke(courses)

        updateNotification("监控中: ${courses.size} 门课程")

        // 冷却计数器
        val cooldown = mutableMapOf<String, Int>()

        // 主循环
        while (running.get()) {
            for (course in courses) {
                if (!running.get()) break

                // 冷却中跳过
                val cd = cooldown[course.id] ?: 0
                if (cd > 0) {
                    cooldown[course.id] = cd - 1
                    continue
                }

                try {
                    // 检查签到
                    val (isOpen, _) = signManager.checkSignOpen(course.id)

                    if (isOpen) {
                        sendLog("检测到签到: ${course.name}")

                        // 执行签到
                        val (signOk, signMsg) = signManager.doCheckin(course.id)
                        sendLog("${course.name}: $signMsg")

                        if (signOk) {
                            cooldown[course.id] = 60 // 成功后冷却60轮
                            updateNotification("刚签到: ${course.name}")
                        } else {
                            cooldown[course.id] = 10
                        }
                    }
                } catch (e: Exception) {
                    sendLog("异常: ${e.message}")
                }
            }

            // 等待15秒
            try {
                Thread.sleep(15_000)
            } catch (e: InterruptedException) {
                break
            }
        }

        isRunning = false
    }

    private fun sendLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        logCallback?.invoke(line)
    }

    // ==================== 通知 ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "自动签到",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "云班课自动签到服务"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val stopIntent = Intent(this, AutoSignService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("云班课自动签到")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "停止", stopPending)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(content))
    }

    // ==================== WakeLock ====================

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoSign::WakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // 最长12小时
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
