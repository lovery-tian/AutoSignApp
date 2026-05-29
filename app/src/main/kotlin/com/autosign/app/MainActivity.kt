package com.autosign.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        var courseCallback: ((List<SignManager.Course>) -> Unit)? = null
    }

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var switchAutoSign: Switch
    private lateinit var tvStatus: TextView
    private lateinit var tvSwitchHint: TextView
    private lateinit var tvCourses: TextView
    private lateinit var tvLog: TextView
    private lateinit var prefs: SharedPreferences

    private val logLines = mutableListOf<String>()
    private val maxLogLines = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("autosign", Context.MODE_PRIVATE)

        // 绑定控件
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        switchAutoSign = findViewById(R.id.switchAutoSign)
        tvStatus = findViewById(R.id.tvStatus)
        tvSwitchHint = findViewById(R.id.tvSwitchHint)
        tvCourses = findViewById(R.id.tvCourses)
        tvLog = findViewById(R.id.tvLog)

        // 加载保存的账号
        etUsername.setText(prefs.getString("username", ""))
        etPassword.setText(prefs.getString("password", ""))

        // 同步开关状态
        switchAutoSign.isChecked = AutoSignService.isRunning
        updateUI(AutoSignService.isRunning)

        // 日志回调
        AutoSignService.logCallback = { line ->
            runOnUiThread { appendLog(line) }
        }

        // 课程回调
        courseCallback = { courses ->
            runOnUiThread {
                val text = courses.mapIndexed { i, c -> "${i + 1}. ${c.name}" }.joinToString("\n")
                tvCourses.text = text
            }
        }

        // 开关监听
        switchAutoSign.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startAutoSign()
            } else {
                stopAutoSign()
            }
        }

        // 请求通知权限 (Android 13+)
        requestNotificationPermission()
    }

    private fun startAutoSign() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "请输入账号和密码", Toast.LENGTH_SHORT).show()
            switchAutoSign.isChecked = false
            return
        }

        // 保存账号
        prefs.edit()
            .putString("username", username)
            .putString("password", password)
            .apply()

        // 清空日志
        logLines.clear()
        tvLog.text = ""
        tvCourses.text = "正在获取..."

        // 启动前台服务
        val intent = Intent(this, AutoSignService::class.java).apply {
            putExtra("username", username)
            putExtra("password", password)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateUI(true)
        appendLog("[系统] 自动签到服务启动中...")
    }

    private fun stopAutoSign() {
        val intent = Intent(this, AutoSignService::class.java)
        intent.action = AutoSignService.ACTION_STOP
        startService(intent)

        updateUI(false)
    }

    private fun updateUI(running: Boolean) {
        if (running) {
            tvStatus.text = "状态: 运行中"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            tvSwitchHint.text = "签到服务正在后台运行"
            etUsername.isEnabled = false
            etPassword.isEnabled = false
        } else {
            tvStatus.text = "状态: 未启动"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            tvSwitchHint.text = "打开后自动检测并完成签到"
            etUsername.isEnabled = true
            etPassword.isEnabled = true
        }
    }

    private fun appendLog(line: String) {
        logLines.add(line)
        if (logLines.size > maxLogLines) {
            logLines.removeAt(0)
        }
        tvLog.text = logLines.joinToString("\n")

        // 自动滚动到底部
        val scrollView = tvLog.parent as? ScrollView
        scrollView?.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    100
                )
            }
        }
    }
}
