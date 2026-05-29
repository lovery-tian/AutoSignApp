package com.autosign.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        var courseCallback: ((List<SignManager.Course>) -> Unit)? = null
        private const val REQUEST_WEB_LOGIN = 1001
    }

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnTogglePassword: ImageButton
    private lateinit var btnWebViewLogin: Button
    private lateinit var switchAutoSign: Switch
    private lateinit var tvStatus: TextView
    private lateinit var tvSwitchHint: TextView
    private lateinit var tvCourses: TextView
    private lateinit var tvLog: TextView
    private lateinit var prefs: SharedPreferences
    private var isPasswordVisible = false

    private val logLines = mutableListOf<String>()
    private val maxLogLines = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("autosign", Context.MODE_PRIVATE)

        // 绑定控件
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
        btnWebViewLogin = findViewById(R.id.btnWebViewLogin)
        switchAutoSign = findViewById(R.id.switchAutoSign)
        tvStatus = findViewById(R.id.tvStatus)
        tvSwitchHint = findViewById(R.id.tvSwitchHint)
        tvCourses = findViewById(R.id.tvCourses)
        tvLog = findViewById(R.id.tvLog)

        // 密码显示/隐藏切换
        btnTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                btnTogglePassword.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            } else {
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                btnTogglePassword.setImageResource(android.R.drawable.ic_menu_view)
            }
            etPassword.setSelection(etPassword.text.length)
        }

        // WebView登录按钮
        btnWebViewLogin.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "请先输入账号和密码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 保存账号
            prefs.edit()
                .putString("username", username)
                .putString("password", password)
                .apply()

            // 打开WebView登录
            val intent = Intent(this, LoginWebViewActivity::class.java).apply {
                putExtra(LoginWebViewActivity.EXTRA_USERNAME, username)
                putExtra(LoginWebViewActivity.EXTRA_PASSWORD, password)
            }
            startActivityForResult(intent, REQUEST_WEB_LOGIN)
        }

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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_WEB_LOGIN) {
            if (resultCode == LoginWebViewActivity.RESULT_LOGIN_SUCCESS) {
                val token = data?.getStringExtra("token") ?: ""
                val loginData = data?.getStringExtra("login_data") ?: ""

                if (token.isNotEmpty()) {
                    // 使用token登录
                    appendLog("[系统] WebView登录成功，正在获取信息...")
                    // 保存token并启动服务
                    prefs.edit().putString("proxy_token", token).apply()
                    Toast.makeText(this, "登录成功！", Toast.LENGTH_SHORT).show()
                } else if (loginData.isNotEmpty()) {
                    appendLog("[系统] 获取到用户信息")
                    prefs.edit().putString("login_data", loginData).apply()
                    Toast.makeText(this, "登录成功！", Toast.LENGTH_SHORT).show()
                }
            } else {
                appendLog("[系统] WebView登录取消或失败")
            }
        }
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

        // 获取保存的token和登录数据
        val token = prefs.getString("proxy_token", "") ?: ""
        val loginData = prefs.getString("login_data", "") ?: ""

        // 启动前台服务
        val intent = Intent(this, AutoSignService::class.java).apply {
            putExtra("username", username)
            putExtra("password", password)
            putExtra("proxy_token", token)
            putExtra("login_data", loginData)
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
            btnWebViewLogin.isEnabled = false
        } else {
            tvStatus.text = "状态: 未启动"
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            tvSwitchHint.text = "打开后自动检测并完成签到"
            etUsername.isEnabled = true
            etPassword.isEnabled = true
            btnWebViewLogin.isEnabled = true
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
