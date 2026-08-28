package com.palmagent.app.ui.settings

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.palmagent.app.R
import com.palmagent.app.BuildConfig
import com.palmagent.app.channel.wechat.AuthResult
import com.palmagent.app.channel.wechat.WeChatApiClient
import com.palmagent.app.service.AccessibilityServiceHelper
import com.palmagent.app.service.ForegroundService
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.utils.KVUtils
import com.palmagent.app.ui.viewmodel.SettingsViewModel
import com.palmagent.app.ui.viewmodel.ApiTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.EnumMap
import java.util.concurrent.TimeUnit
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.viewModels

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    private var qrLoginJob: Job? = null
    private var qrLoginDialog: AlertDialog? = null

    // 【改动·新增】权限管理相关视图（从 HomeActivity.kt 移植而来）
    private lateinit var cardAccessibility: LinearLayout
    private lateinit var cardNotification: LinearLayout
    private lateinit var cardSystemWindow: LinearLayout
    private lateinit var cardBattery: LinearLayout
    private lateinit var cardLocation: LinearLayout
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvNotificationStatus: TextView
    private lateinit var tvSystemWindowStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvLocationStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 版本徽章动态化：跟随 BuildConfig（避免布局硬编码忘记更新）
        findViewById<TextView>(R.id.tvVersionBadge)?.text = "v${BuildConfig.VERSION_NAME}"

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        // 【改动·新增】初始化权限管理功能（从 HomeActivity 移植）
        setupPermissions()
        refreshLlmConfigDisplay()
        refreshKeyboardVlmConfigDisplay()
        refreshGuiOwlConfigDisplay()
        refreshAmapMcpConfigDisplay()
        refreshPlannerConfigDisplay()
        
        refreshOcrEngineDisplay()
        refreshTtsConfigDisplay()
        setupLLMConfig()
        setupKeyboardVlmConfig()
        setupVisionModeSwitch()
        setupGuiOwlConfig()
        setupAccessibilityGuide()
        setupAmapMcpConfig()
        setupPlannerConfig()
        
        setupOcrEngineConfig()
        setupTtsConfig()
        setupWeChatBinding()
        setupDiagnoseAll()
        setupKbConfig()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshConfig()
        // 【改动·新增】onResume 时刷新权限状态（从 HomeActivity 移植）
        updatePermissionStatus()
        refreshLlmConfigDisplay()
        refreshKeyboardVlmConfigDisplay()
        refreshGuiOwlConfigDisplay()
        refreshAmapMcpConfigDisplay()
        refreshPlannerConfigDisplay()
        
        refreshOcrEngineDisplay()
        refreshTtsConfigDisplay()
        refreshKbStatus()
    }

    // ==================== 权限管理 ====================

    private fun setupPermissions() {
        cardAccessibility = findViewById(R.id.cardAccessibility)
        cardNotification = findViewById(R.id.cardNotification)
        cardSystemWindow = findViewById(R.id.cardSystemWindow)
        cardBattery = findViewById(R.id.cardBattery)
        cardLocation = findViewById(R.id.cardLocation)

        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus)
        tvSystemWindowStatus = findViewById(R.id.tvSystemWindowStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvLocationStatus = findViewById(R.id.tvLocationStatus)

        cardAccessibility.setOnClickListener { requestAccessibilityPermission() }
        cardNotification.setOnClickListener { requestNotificationPermission() }
        cardSystemWindow.setOnClickListener { requestSystemWindowPermission() }
        cardBattery.setOnClickListener { requestBatteryPermission() }
        cardLocation.setOnClickListener { requestLocationPermission() }
    }

    private fun updatePermissionStatus() {
        updateAccessibilityStatus()
        updateNotificationStatus()
        updateSystemWindowStatus()
        updateBatteryStatus()
        updateLocationStatus()
    }

    private fun updateAccessibilityStatus() {
        val running = GUIAccessibilityService.isRunning
        val canAutoRestore = AccessibilityServiceHelper.canWriteSecureSettings(this)
        if (running) {
            tvAccessibilityStatus.text = "✓ 已开启"
            tvAccessibilityStatus.setTextColor(0xFF4CAF50.toInt())
        } else if (canAutoRestore) {
            tvAccessibilityStatus.text = "自动恢复中..."
            tvAccessibilityStatus.setTextColor(0xFFFF9800.toInt())
        } else {
            tvAccessibilityStatus.text = "✗ 未开启"
            tvAccessibilityStatus.setTextColor(0xFFFF5722.toInt())
        }
    }

    private fun updateNotificationStatus() {
        val running = ForegroundService.isRunning()
        tvNotificationStatus.text = if (running) "✓ 已开启" else "✗ 未开启"
        tvNotificationStatus.setTextColor(if (running) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt())
    }

    private fun updateSystemWindowStatus() {
        val enabled = Settings.canDrawOverlays(this)
        tvSystemWindowStatus.text = if (enabled) "✓ 已授权" else "✗ 未授权"
        tvSystemWindowStatus.setTextColor(if (enabled) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt())
    }

    private fun updateBatteryStatus() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val enabled = powerManager.isIgnoringBatteryOptimizations(packageName)
        tvBatteryStatus.text = if (enabled) "✓ 已优化" else "✗ 未优化"
        tvBatteryStatus.setTextColor(if (enabled) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt())
    }

    private fun updateLocationStatus() {
        val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        tvLocationStatus.text = if (granted) "✓ 已授权（高德地图可用）" else "✗ 未授权（高德地图周边搜索不可用）"
        tvLocationStatus.setTextColor(if (granted) 0xFF4CAF50.toInt() else 0xFFFF5722.toInt())
    }

    private fun requestAccessibilityPermission() {
        if (!GUIAccessibilityService.isRunning) {
            if (AccessibilityServiceHelper.canWriteSecureSettings(this)) {
                val restored = AccessibilityServiceHelper.ensureServiceEnabled(this)
                if (restored) {
                    Toast.makeText(this, "正在自动恢复无障碍服务...", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, R.string.home_enable_accessibility, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
                return
            }
        }
        val started = ForegroundService.start(applicationContext)
        if (started) {
            updateNotificationStatus()
            Toast.makeText(this, R.string.home_notification_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestSystemWindowPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestBatteryPermission() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        updateLocationStatus()
        if (granted) {
            Toast.makeText(this, "位置权限已授权，高德地图周边搜索可用", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "位置权限未授权，高德地图周边搜索不可用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            Toast.makeText(this, "位置权限已授权", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== LLM 配置 ====================

    private fun refreshLlmConfigDisplay() {
        val tvLlmConfigValue = findViewById<TextView>(R.id.tvLlmConfigValue)
        val state = viewModel.uiState.value
        if (state.llmModelName.isNotEmpty()) {
            tvLlmConfigValue.text = state.llmModelName
        } else if (state.llmBaseUrl.isNotEmpty()) {
            tvLlmConfigValue.text = "已配置API"
        } else {
            tvLlmConfigValue.text = "未配置"
        }
    }

    private fun setupLLMConfig() {
        val llmMenu = findViewById<LinearLayout>(R.id.menu_llm_config)
        llmMenu.setOnClickListener { showLLMConfigDialog() }
    }

    data class LlmPreset(val displayName: String, val baseUrl: String, val modelName: String)

    private val llmPresets = listOf(
        LlmPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        LlmPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        LlmPreset("智谱GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
        LlmPreset("月之暗面", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        LlmPreset("硅基流动", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
        LlmPreset("自定义", "", "")
    )

    data class KeyboardVlmPreset(val displayName: String, val baseUrl: String, val modelName: String)

    // 键盘检测模型预设：仅支持视觉的模型（区别于 LLM 文本模型预设）
    private val keyboardVlmPresets = listOf(
        KeyboardVlmPreset("智谱GLM-4V", "https://open.bigmodel.cn/api/paas/v4", "glm-4v-flash"),
        KeyboardVlmPreset("通义千问VL", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-vl-plus"),
        KeyboardVlmPreset("通义千问3-VL", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3-vl-flash"),
        KeyboardVlmPreset("自定义", "", "")
    )

    @SuppressLint("SetTextI18n")
    private fun showLLMConfigDialog() {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        fun addField(labelText: String, editText: EditText, inputType: Int = InputType.TYPE_CLASS_TEXT) {
            val label = TextView(this@SettingsActivity).apply {
                text = labelText
                textSize = 14f
                setTextColor(0xFF333333.toInt())
                setPadding(0, 20, 0, 8)
            }
            layout.addView(label)

            editText.apply {
                this.inputType = inputType
                setTextSize(14f)
                setPadding(24, 20, 24, 20)
                setBackgroundResource(R.drawable.bg_input_field)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            }
            layout.addView(editText)
        }

        // 预设服务商 Spinner
        val presetLabel = TextView(this).apply {
            text = "服务商预设"
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 20, 0, 8)
        }
        layout.addView(presetLabel)

        val presetSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }
        val presetNames = llmPresets.map { it.displayName }
        val spinnerAdapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, presetNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = spinnerAdapter
        layout.addView(presetSpinner)

        val apiKeyEdit = EditText(this).apply {
            hint = "当前: ${KVUtils.maskApiKey(KVUtils.getLlmApiKey())}（留空则不修改）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addField("API Key（必填）", apiKeyEdit)

        val baseUrlEdit = EditText(this).apply {
            // 预填推荐配置（DeepSeek-V4-Flash），用户可修改；已有配置则保留
            setText(viewModel.uiState.value.llmBaseUrl.ifEmpty { "https://api.deepseek.com/v1" })
        }
        addField("API 地址（例如 https://api.deepseek.com）", baseUrlEdit)

        val modelEdit = EditText(this).apply {
            setText(viewModel.uiState.value.llmModelName.ifEmpty { "deepseek-v4-flash" })
        }
        addField("模型名称（留空则自动选择默认模型）", modelEdit)

        // 联网搜索开关（执行模型 + 决策模型共用）
        val searchCheckbox = android.widget.CheckBox(this).apply {
            text = "启用联网搜索（推荐，可获取实时信息）"
            isChecked = KVUtils.isExecutionSearchEnabled()
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        layout.addView(searchCheckbox)

        // 博查 API Key（可选，未配置则用 DuckDuckGo 兜底）
        val bochaKeyEdit = EditText(this).apply {
            val current = KVUtils.getBochaApiKey()
            hint = if (current.isNotEmpty()) {
                "当前: ${KVUtils.maskApiKey(current)}（留空则不修改）"
            } else {
                "博查 API Key（留空则用 DuckDuckGo 免费兜底）"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addField("博查搜索 API Key（可选）", bochaKeyEdit)

        // 根据当前保存的 baseUrl 匹配预设，设置 Spinner 默认选中项
        val currentBaseUrl = viewModel.uiState.value.llmBaseUrl
        val matchedPresetIndex = llmPresets.indexOfFirst { it.baseUrl.isNotEmpty() && it.baseUrl == currentBaseUrl }
        presetSpinner.setSelection(if (matchedPresetIndex >= 0) matchedPresetIndex else llmPresets.size - 1)

        // Spinner 选择事件（初始化回调会覆盖预填的实际配置，首次跳过）
        var spinnerInitialized = false
        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!spinnerInitialized) { spinnerInitialized = true; return }
                val preset = llmPresets[position]
                if (preset.displayName == "自定义") {
                    baseUrlEdit.setText("")
                    modelEdit.setText("")
                } else {
                    baseUrlEdit.setText(preset.baseUrl)
                    modelEdit.setText(preset.modelName)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 8)
            visibility = TextView.GONE
        }
        layout.addView(statusText)

        val progressBar = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        layout.addView(progressBar)

        scrollView.addView(layout)

        val dialog = AlertDialog.Builder(this)
            .setTitle("大模型配置")
            .setView(scrollView)
            .setPositiveButton("测试并保存") { _, _ -> }
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val apiKey = apiKeyEdit.text.toString().trim()
                val baseUrl = baseUrlEdit.text.toString().trim()
                val modelName = modelEdit.text.toString().trim()

                val effectiveApiKey = apiKey.ifEmpty { KVUtils.getLlmApiKey() }
                if (effectiveApiKey.isEmpty()) {
                    Toast.makeText(this, "API Key 不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                statusText.visibility = TextView.VISIBLE
                statusText.text = "正在测试 API 连通性..."
                progressBar.visibility = ProgressBar.VISIBLE
                saveButton.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false

                lifecycleScope.launch(Dispatchers.IO) {
                    val testResult = testApiConnectionViaViewModel(baseUrl, effectiveApiKey, modelName)

                    withContext(Dispatchers.Main) {
                        progressBar.visibility = ProgressBar.GONE
                        saveButton.isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true

                        if (testResult.isSuccess) {
                            viewModel.saveLlmConfig(effectiveApiKey, baseUrl, modelName)
                            // 保存联网搜索配置（执行模型 + 决策模型共用）
                            KVUtils.setExecutionSearchEnabled(searchCheckbox.isChecked)
                            val bochaKey = bochaKeyEdit.text.toString().trim()
                            if (bochaKey.isNotEmpty()) {
                                KVUtils.setBochaApiKey(bochaKey)
                            }
                            statusText.text = "✓ 连接成功！配置已保存"
                            statusText.setTextColor(0xFF4CAF50.toInt())
                            Toast.makeText(this@SettingsActivity, "API 连接成功，配置已保存", Toast.LENGTH_SHORT).show()
                            refreshLlmConfigDisplay()
                            dialog.dismiss()
                        } else {
                            statusText.text = "✗ ${testResult.error}"
                            statusText.setTextColor(0xFFE53935.toInt())
                            Toast.makeText(this@SettingsActivity, "API 连接失败，请检查配置", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    data class TestResult(val isSuccess: Boolean, val error: String = "")

    private suspend fun testApiConnectionViaViewModel(baseUrl: String, apiKey: String, modelName: String): TestResult {
        // P0 修复：用 suspendCancellableCoroutine 等待异步回调，替代 delay(100) 竞态
        // 30s 超时兜底（OkHttp connectTimeout 10s + readTimeout 15s = 25s 上限）
        return withTimeoutOrNull(30_000L) {
            suspendCancellableCoroutine { cont ->
                viewModel.testLlmConnection(baseUrl, apiKey, modelName) { apiResult ->
                    if (cont.isActive) {
                        cont.resume(TestResult(apiResult.isSuccess, apiResult.error))
                    }
                }
            }
        } ?: TestResult(false, "连接测试超时")
    }

    private fun testApiConnection(baseUrl: String, apiKey: String, modelName: String): TestResult {
        return try {
            val normalizedUrl = if (baseUrl.isBlank()) {
                val defaultUrl = KVUtils.getLlmBaseUrl()
                if (defaultUrl.isBlank()) "" else {
                    val trimmed = defaultUrl.trimEnd('/')
                    if (trimmed.endsWith("/chat/completions")) trimmed
                    else if (trimmed.endsWith("/v1")) "$trimmed/chat/completions"
                    else "$trimmed/v1/chat/completions"
                }
            } else {
                val trimmed = baseUrl.trimEnd('/')
                if (trimmed.endsWith("/chat/completions")) trimmed
                else if (trimmed.endsWith("/v1")) "$trimmed/chat/completions"
                else "$trimmed/v1/chat/completions"
            }

            val effectiveModel = modelName.ifBlank { KVUtils.getLlmModelName() }

            val testBody = Gson().toJson(mapOf(
                "model" to effectiveModel,
                "messages" to listOf(mapOf("role" to "user", "content" to "Hi")),
                "max_tokens" to 5,
                "temperature" to 0.0
            ))

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(normalizedUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(testBody.toRequestBody("application/json".toMediaType()))
                .build()

            Log.d("SettingsActivity", "测试API: $normalizedUrl, model=$effectiveModel")
            val (testCode, body) = client.newCall(request).execute().use { resp ->
                Pair(resp.code, resp.body?.string() ?: "")
            }

            if (testCode in 200..299) {
                Log.d("SettingsActivity", "API测试成功: $testCode")
                TestResult(true)
            } else {
                val errorMsg = try {
                    val errorMap = Gson().fromJson(body, Map::class.java)
                    (errorMap["error"] as? Map<*, *>)?.get("message")?.toString()
                        ?: "HTTP $testCode"
                } catch (e: Exception) {
                    "HTTP $testCode: ${body.take(200)}"
                }
                Log.e("SettingsActivity", "API测试失败: $errorMsg")
                TestResult(false, errorMsg)
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "API测试异常: ${e.message}")
            TestResult(false, "连接失败：${e.message ?: "未知错误"}")
        }
    }

    // ==================== 高德地图 MCP 配置 ====================

    @SuppressLint("SetTextI18n")
    private fun refreshAmapMcpConfigDisplay() {
        val tv = findViewById<TextView>(R.id.tvAmapMcpConfigValue)
        val apiKey = KVUtils.getAmapApiKey()
        val enabled = KVUtils.getAmapMcpEnabled()
        if (apiKey.isNotBlank() && enabled) {
            tv.text = "已启用"
            tv.setTextColor(0xFF4CAF50.toInt())
        } else if (apiKey.isNotBlank()) {
            tv.text = "未启用"
            tv.setTextColor(0xFF999999.toInt())
        } else {
            tv.text = "未配置"
            tv.setTextColor(0xFF999999.toInt())
        }
    }

    private fun setupAmapMcpConfig() {
        val menu = findViewById<LinearLayout>(R.id.menu_amap_mcp_config)
        menu?.setOnClickListener { showAmapMcpConfigDialog() }
    }

    @SuppressLint("SetTextI18n")
    private fun showAmapMcpConfigDialog() {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val enabledCheckbox = android.widget.CheckBox(this).apply {
            text = "启用高德地图 MCP"
            isChecked = KVUtils.getAmapMcpEnabled()
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        layout.addView(enabledCheckbox)

        val apiKeyLabel = TextView(this).apply {
            text = "API Key（留空则使用 local.properties 导入值）"
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 20, 0, 8)
        }
        layout.addView(apiKeyLabel)

        val apiKeyEdit = EditText(this).apply {
            hint = "当前: ${KVUtils.maskApiKey(KVUtils.getAmapApiKey())}（留空则不修改）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextSize(14f)
            setPadding(24, 20, 24, 20)
            setBackgroundResource(R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }
        layout.addView(apiKeyEdit)

        val baseUrlLabel = TextView(this).apply {
            text = "MCP 端点 URL"
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 20, 0, 8)
        }
        layout.addView(baseUrlLabel)

        val baseUrlText = TextView(this).apply {
            text = BuildConfig.AMAP_MCP_BASE_URL
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(24, 20, 24, 20)
            setBackgroundResource(R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }
        layout.addView(baseUrlText)

        val testButton = Button(this).apply {
            text = "测试连通性"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 8 }
        }
        layout.addView(testButton)

        val descText = TextView(this).apply {
            text = "高德地图 MCP 提供地点搜索、周边搜索、路线规划、天气查询等功能。\n需要位置权限才能使用周边搜索和路线规划。"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 8)
        }
        layout.addView(descText)

        val statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 8)
        }
        layout.addView(statusText)

        scrollView.addView(layout)

        testButton.setOnClickListener {
            statusText.text = "测试中..."
            testButton.isEnabled = false
            lifecycleScope.launch {
                val result = testAmapMcpConnection()
                statusText.text = if (result.isSuccess) "✓ 连接成功" else "✗ ${result.error}"
                statusText.setTextColor(if (result.isSuccess) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
                testButton.isEnabled = true
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("高德地图 MCP 配置")
            .setView(scrollView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val newKey = apiKeyEdit.text.toString().trim()
                if (newKey.isNotEmpty()) {
                    KVUtils.setAmapApiKey(newKey)
                }
                val enabled = enabledCheckbox.isChecked
                KVUtils.setAmapMcpEnabled(enabled)
                refreshAmapMcpConfigDisplay()
                Toast.makeText(this, "高德地图 MCP 配置已保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private suspend fun testAmapMcpConnection(): TestResult = withContext(Dispatchers.IO) {
        try {
            val apiKey = KVUtils.getAmapApiKey()
            if (apiKey.isBlank()) {
                return@withContext TestResult(false, "API Key 未配置")
            }

            val url = KVUtils.getAmapMcpEndpointUrl()
            if (url.isBlank()) {
                return@withContext TestResult(false, "MCP 端点未配置")
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            // 发送 MCP initialize 请求
            val initBody = Gson().toJson(mapOf(
                "jsonrpc" to "2.0",
                "id" to 1,
                "method" to "initialize",
                "params" to mapOf(
                    "protocolVersion" to "2024-11-05",
                    "capabilities" to emptyMap<String, Any>(),
                    "clientInfo" to mapOf("name" to "PalmAgent", "version" to "1.0.0")
                )
            ))

            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json, text/event-stream")
                .post(initBody.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = client.newCall(request).execute().use { resp ->
                Pair(resp.code, resp.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("SettingsActivity", "高德 MCP 测试成功: $code")
                TestResult(true)
            } else {
                Log.e("SettingsActivity", "高德 MCP 测试失败: HTTP $code, body=${body.take(200)}")
                TestResult(false, "HTTP $code")
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "高德 MCP 测试异常: ${e.message}")
            TestResult(false, "连接失败: ${e.message}")
        }
    }

    // ==================== 任务决策模型配置 ====================

    @SuppressLint("SetTextI18n")
    private fun refreshPlannerConfigDisplay() {
        val tv = findViewById<TextView>(R.id.tvPlannerConfigValue)
        val model = KVUtils.getPlannerModel()
        val hasKey = KVUtils.hasPlannerConfig()
        if (hasKey) {
            tv.text = model
            tv.setTextColor(0xFF4CAF50.toInt())
        } else {
            tv.text = "未配置"
            tv.setTextColor(0xFF999999.toInt())
        }
    }

    private fun setupPlannerConfig() {
        val menu = findViewById<LinearLayout>(R.id.menu_planner_config)
        menu?.setOnClickListener { showPlannerConfigDialog() }
    }

    data class PlannerPreset(val displayName: String, val baseUrl: String, val modelName: String)

    private val plannerPresets = listOf(
        PlannerPreset("通义千问-Plus", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        PlannerPreset("通义千问-Max", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max"),
        PlannerPreset("通义千问-Turbo", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-turbo"),
        PlannerPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        PlannerPreset("自定义", "", "")
    )

    @SuppressLint("SetTextI18n")
    private fun showPlannerConfigDialog() {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        fun addField(labelText: String, editText: EditText, inputType: Int = InputType.TYPE_CLASS_TEXT) {
            val label = TextView(this@SettingsActivity).apply {
                text = labelText
                textSize = 14f
                setTextColor(0xFF333333.toInt())
                setPadding(0, 20, 0, 8)
            }
            layout.addView(label)

            editText.apply {
                this.inputType = inputType
                setTextSize(14f)
                setPadding(24, 20, 24, 20)
                setBackgroundResource(R.drawable.bg_input_field)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            }
            layout.addView(editText)
        }

        val descText = TextView(this).apply {
            text = "决策模型用于任务执行前的计划生成，推荐使用支持联网搜索的 Qwen 系列模型。API Key 将加密存储。"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 16)
        }
        layout.addView(descText)

        // 预设服务商 Spinner
        val presetLabel = TextView(this).apply {
            text = "模型预设"
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 20, 0, 8)
        }
        layout.addView(presetLabel)

        val presetSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }
        val presetNames = plannerPresets.map { it.displayName }
        val spinnerAdapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, presetNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = spinnerAdapter
        layout.addView(presetSpinner)

        val apiKeyEdit = EditText(this).apply {
            hint = "当前: ${KVUtils.maskApiKey(KVUtils.getPlannerApiKey())}（留空则不修改）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addField("API Key（必填，加密存储）", apiKeyEdit)

        val baseUrlEdit = EditText(this).apply {
            // 预填推荐配置（DeepSeek-V4-Flash），用户可修改；已有配置则保留
            setText(KVUtils.getPlannerApiUrl().ifEmpty { "https://api.deepseek.com/v1" })
            hint = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        }
        addField("API 地址", baseUrlEdit)

        val modelEdit = EditText(this).apply {
            setText(KVUtils.getPlannerModel().ifEmpty { "deepseek-v4-flash" })
            hint = "qwen-plus"
        }
        addField("模型名称", modelEdit)

        // 根据当前配置匹配预设
        val currentBaseUrl = KVUtils.getPlannerApiUrl()
        val currentModel = KVUtils.getPlannerModel()
        val matchedPresetIndex = plannerPresets.indexOfFirst {
            it.baseUrl.isNotEmpty() && it.baseUrl == currentBaseUrl && it.modelName == currentModel
        }
        presetSpinner.setSelection(if (matchedPresetIndex >= 0) matchedPresetIndex else plannerPresets.size - 1)

        // 初始化回调会覆盖预填的实际配置，首次跳过
        var plannerSpinnerInitialized = false
        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!plannerSpinnerInitialized) { plannerSpinnerInitialized = true; return }
                val preset = plannerPresets[position]
                if (preset.displayName != "自定义") {
                    baseUrlEdit.setText(preset.baseUrl)
                    modelEdit.setText(preset.modelName)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 8)
            visibility = TextView.GONE
        }
        layout.addView(statusText)

        val progressBar = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        layout.addView(progressBar)

        scrollView.addView(layout)

        val dialog = AlertDialog.Builder(this)
            .setTitle("任务决策模型配置")
            .setView(scrollView)
            .setPositiveButton("测试并保存") { _, _ -> }
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val apiKey = apiKeyEdit.text.toString().trim()
                val baseUrl = baseUrlEdit.text.toString().trim()
                val modelName = modelEdit.text.toString().trim()

                val effectiveApiKey = apiKey.ifEmpty { KVUtils.getPlannerApiKey() }
                if (effectiveApiKey.isEmpty()) {
                    Toast.makeText(this, "API Key 不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (modelName.isEmpty()) {
                    Toast.makeText(this, "模型名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                statusText.visibility = TextView.VISIBLE
                statusText.text = "正在测试决策模型连通性..."
                progressBar.visibility = ProgressBar.VISIBLE
                saveButton.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false

                lifecycleScope.launch(Dispatchers.IO) {
                    val testResult = testPlannerConnection(baseUrl, effectiveApiKey, modelName)

                    if (!isActive) return@launch
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = ProgressBar.GONE
                        saveButton.isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true

                        if (testResult.isSuccess) {
                            KVUtils.setPlannerApiKey(effectiveApiKey)
                            KVUtils.setPlannerApiUrl(baseUrl)
                            KVUtils.setPlannerModel(modelName)
                            viewModel.refreshConfig()
                            statusText.text = "✓ 连接成功！配置已保存"
                            statusText.setTextColor(0xFF4CAF50.toInt())
                            Toast.makeText(this@SettingsActivity, "决策模型配置已保存", Toast.LENGTH_SHORT).show()
                            refreshPlannerConfigDisplay()
                            dialog.dismiss()
                        } else {
                            statusText.text = "✗ ${testResult.error}"
                            statusText.setTextColor(0xFFE53935.toInt())
                            Toast.makeText(this@SettingsActivity, "连接失败，请检查配置", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    data class PlannerTestResult(val isSuccess: Boolean, val error: String = "")

    private suspend fun testPlannerConnection(baseUrl: String, apiKey: String, modelName: String): PlannerTestResult {
        return try {
            val normalizedUrl = if (baseUrl.isBlank()) {
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
            } else {
                val trimmed = baseUrl.trimEnd('/')
                when {
                    trimmed.endsWith("/chat/completions") -> trimmed
                    trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
                    else -> "$trimmed/v1/chat/completions"
                }
            }

            // 构建测试请求体（enable_search 已删除 — 联网搜索由 web_search 工具提供）
            val testBody = buildString {
                append("{")
                append("\"model\":\"$modelName\",")
                append("\"messages\":[{\"role\":\"user\",\"content\":\"你好\"}],")
                append("\"max_tokens\":5,")
                append("\"temperature\":0.0")
                append("}")
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(normalizedUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(testBody.toRequestBody("application/json".toMediaType()))
                .build()

            val (plannerCode, body) = client.newCall(request).execute().use { resp ->
                Pair(resp.code, resp.body?.string() ?: "")
            }

            if (plannerCode in 200..299) {
                PlannerTestResult(true)
            } else {
                val errorMsg = try {
                    val errorMap = Gson().fromJson(body, Map::class.java)
                    (errorMap["error"] as? Map<*, *>)?.get("message")?.toString()
                        ?: "HTTP $plannerCode"
                } catch (e: Exception) {
                    "HTTP $plannerCode: ${body.take(200)}"
                }
                PlannerTestResult(false, errorMsg)
            }
        } catch (e: java.net.SocketTimeoutException) {
            PlannerTestResult(false, "请求超时")
        } catch (e: Exception) {
            PlannerTestResult(false, "连接失败：${e.message ?: "未知错误"}")
        }
    }

    // ==================== 键盘检测模型配置 ====================

    @SuppressLint("SetTextI18n")
    private fun refreshKeyboardVlmConfigDisplay() {
        val tv = findViewById<TextView>(R.id.tvKeyboardVlmConfigValue)
        if (KVUtils.hasKeyboardVlmConfig()) {
            tv.text = KVUtils.getKeyboardVlmModelName()
            tv.setTextColor(0xFF4CAF50.toInt())
        } else {
            tv.text = "未配置"
            tv.setTextColor(0xFF999999.toInt())
        }
    }

    private fun setupKeyboardVlmConfig() {
        val menu = findViewById<LinearLayout>(R.id.menu_keyboard_vlm_config)
        menu?.setOnClickListener { showKeyboardVlmConfigDialog() }
    }

    @SuppressLint("SetTextI18n")
    private fun showKeyboardVlmConfigDialog() {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        fun addField(labelText: String, editText: EditText, inputType: Int = InputType.TYPE_CLASS_TEXT) {
            val label = TextView(this@SettingsActivity).apply {
                text = labelText
                textSize = 14f
                setTextColor(0xFF333333.toInt())
                setPadding(0, 20, 0, 8)
            }
            layout.addView(label)

            editText.apply {
                this.inputType = inputType
                setTextSize(14f)
                setPadding(24, 20, 24, 20)
                setBackgroundResource(R.drawable.bg_input_field)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            }
            layout.addView(editText)
        }

        val descText = TextView(this).apply {
            text = "键盘检测模型用于检测输入框点击后键盘是否弹出（是/否判断）。\n\n" +
                  "键盘检测模型：点击输入框后，检测屏幕键盘是否弹出（是/否判断），\n" +
                  "用于验证输入框是否聚焦成功、决定后续输入流程。\n" +
                  "请选择支持视觉（VLM）能力的模型。"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 16)
        }
        layout.addView(descText)

        // 预设服务商 Spinner（仅视觉模型，区别于文本模型预设）
        val presetLabel = TextView(this).apply {
            text = "服务商预设（视觉模型）"
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 20, 0, 8)
        }
        layout.addView(presetLabel)

        val presetSpinner = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }
        val presetNames = keyboardVlmPresets.map { it.displayName }
        val spinnerAdapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, presetNames)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = spinnerAdapter
        layout.addView(presetSpinner)

        val apiKeyEdit = EditText(this).apply {
            hint = "当前: ${KVUtils.maskApiKey(KVUtils.getKeyboardVlmApiKey())}（留空则不修改）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        addField("API Key（必填）", apiKeyEdit)

        val apiUrlEdit = EditText(this).apply {
            // 预填推荐配置（智谱 GLM-4V-Flash），用户可修改；已有配置则保留
            setText(KVUtils.getKeyboardVlmApiUrl().ifEmpty { "https://open.bigmodel.cn/api/paas/v4" })
            hint = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        }
        addField("API 地址", apiUrlEdit)

        val modelEdit = EditText(this).apply {
            setText(KVUtils.getKeyboardVlmModelName().ifEmpty { "glm-4v-flash" })
            hint = "glm-4v-flash"
        }
        addField("模型名称", modelEdit)

        // 根据当前保存的 baseUrl 匹配预设，设置 Spinner 默认选中项
        val currentKbUrl = KVUtils.getKeyboardVlmApiUrl()
        val matchedKbIndex = keyboardVlmPresets.indexOfFirst { it.baseUrl.isNotEmpty() && it.baseUrl == currentKbUrl }
        presetSpinner.setSelection(if (matchedKbIndex >= 0) matchedKbIndex else keyboardVlmPresets.size - 1)

        // Spinner 选择事件（初始化回调会覆盖预填的实际配置，首次跳过）
        var kbSpinnerInitialized = false
        presetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (!kbSpinnerInitialized) { kbSpinnerInitialized = true; return }
                val preset = keyboardVlmPresets[position]
                if (preset.displayName == "自定义") {
                    apiUrlEdit.setText("")
                    modelEdit.setText("")
                } else {
                    apiUrlEdit.setText(preset.baseUrl)
                    modelEdit.setText(preset.modelName)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val testButton = Button(this).apply {
            text = "测试连通性"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 8 }
        }
        layout.addView(testButton)

        val statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 8)
            visibility = TextView.GONE
        }
        layout.addView(statusText)

        val progressBar = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        layout.addView(progressBar)

        scrollView.addView(layout)

        testButton.setOnClickListener {
            statusText.visibility = TextView.VISIBLE
            statusText.text = "正在测试连通性..."
            statusText.setTextColor(0xFF333333.toInt())
            progressBar.visibility = ProgressBar.VISIBLE
            testButton.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                val effectiveUrl = apiUrlEdit.text.toString().trim().ifEmpty { KVUtils.getKeyboardVlmApiUrl() }
                val effectiveKey = apiKeyEdit.text.toString().trim().ifEmpty { KVUtils.getKeyboardVlmApiKey() }
                val effectiveModel = modelEdit.text.toString().trim().ifEmpty { KVUtils.getKeyboardVlmModelName() }

                val testResult = testApiConnection(effectiveUrl, effectiveKey, effectiveModel)

                if (!isActive) return@launch
                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    testButton.isEnabled = true
                    if (testResult.isSuccess) {
                        statusText.text = "✓ 连接成功"
                        statusText.setTextColor(0xFF4CAF50.toInt())
                    } else {
                        statusText.text = "✗ ${testResult.error}"
                        statusText.setTextColor(0xFFE53935.toInt())
                    }
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("键盘检测模型配置")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ -> }
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val apiKey = apiKeyEdit.text.toString().trim()
                val apiUrl = apiUrlEdit.text.toString().trim()
                val modelName = modelEdit.text.toString().trim()

                if (apiKey.isNotEmpty()) KVUtils.setKeyboardVlmApiKey(apiKey)
                if (apiUrl.isNotEmpty()) KVUtils.setKeyboardVlmApiUrl(apiUrl)
                if (modelName.isNotEmpty()) KVUtils.setKeyboardVlmModelName(modelName)

                viewModel.refreshConfig()
                refreshKeyboardVlmConfigDisplay()
                Toast.makeText(this, "键盘检测模型配置已保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    // ==================== GUI-Plus Grounding配置 ====================

    @SuppressLint("SetTextI18n")
    private fun refreshGuiOwlConfigDisplay() {
        val tv = findViewById<TextView>(R.id.tvGuiOwlConfigValue)
        // GUI-Plus 为唯一视觉模型，常驻启用；但 API-Key 未配置时提示未配置（回退 LLM_API_KEY 也算已配置）
        val key = KVUtils.getGuiOwlApiKey()
        if (key.isNotBlank()) {
            tv.text = "已启用"
            tv.setTextColor(0xFF4CAF50.toInt())
        } else {
            tv.text = "未配置"
            tv.setTextColor(0xFFE53935.toInt())
        }
    }

    private fun setupVisionModeSwitch() {
        val switch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switch_vision_mode)
        switch.isChecked = KVUtils.isVisionModeEnabled()
        switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && KVUtils.getGuiOwlApiKey().isBlank()) {
                Toast.makeText(this, "请先在 GUI-Plus 配置中填写 API Key（否则视觉执行将失败）", Toast.LENGTH_SHORT).show()
            }
            KVUtils.setVisionModeEnabled(isChecked)
            com.palmagent.app.LiveLogBuffer.append("VL模式: ${if (isChecked) "开启" else "关闭"}")
        }
    }

    private fun setupGuiOwlConfig() {
        val guiOwlMenu = findViewById<LinearLayout>(R.id.menu_gui_owl_config)
        guiOwlMenu?.setOnClickListener { showGuiOwlConfigDialog() }
    }

    /** 无障碍与后台保活引导入口（退后台被自动关闭权限时的自救指引） */
    private fun setupAccessibilityGuide() {
        val guideMenu = findViewById<LinearLayout>(R.id.menu_accessibility_guide)
        guideMenu?.setOnClickListener { AccessibilityServiceHelper.showAccessibilityGuideDialog(this) }
    }

    @SuppressLint("SetTextI18n")
    private fun showGuiOwlConfigDialog() {
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        fun addField(labelText: String, editText: EditText, inputType: Int = InputType.TYPE_CLASS_TEXT) {
            val label = TextView(this@SettingsActivity).apply {
                text = labelText
                textSize = 14f
                setTextColor(0xFF333333.toInt())
                setPadding(0, 20, 0, 8)
            }
            layout.addView(label)

            editText.apply {
                this.inputType = inputType
                setTextSize(14f)
                setPadding(24, 20, 24, 20)
                setBackgroundResource(R.drawable.bg_input_field)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4 }
            }
            layout.addView(editText)
        }

        // API-Key 输入区（留空则不修改，与 LLM/Planner 对话框一致；API 地址固定为百炼端点，不可修改）
        val apiKeyLabel = TextView(this).apply {
            text = "API Key（百炼，留空则复用 LLM_API_KEY）"
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 20, 0, 8)
        }
        layout.addView(apiKeyLabel)

        val apiKeyEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "当前: ${KVUtils.maskApiKey(KVUtils.getGuiOwlApiKey())}（留空则不修改）"
            setTextSize(14f)
            setPadding(24, 20, 24, 20)
            setBackgroundResource(R.drawable.bg_input_field)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }
        layout.addView(apiKeyEdit)

        val testButton = Button(this).apply {
            text = "测试连通性"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 8 }
        }
        layout.addView(testButton)

        val visualTestButton = Button(this).apply {
            text = "测试视觉执行（内置样图）"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8; bottomMargin = 8 }
        }
        layout.addView(visualTestButton)

        val descText = TextView(this).apply {
            text = "GUI-Plus（阿里云百炼）是云端 GUI 界面交互模型，接收截图与指令，直接返回" +
                  "操作决策（动作 + 像素坐标）与输入文本，无需本地部署 GUI 服务。\n\n" +
                  "配置说明：\n" +
                  "- API 地址固定为百炼 DashScope 兼容端点，不可修改\n" +
                  "- API Key 默认复用 local.properties 的 LLM_API_KEY（同为百炼 Key）\n" +
                  "- 模型默认 gui-plus-2026-02-26，支持思考模式与工具调用"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 8, 0, 8)
        }
        layout.addView(descText)

        val statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, 16, 0, 8)
            visibility = TextView.GONE
        }
        layout.addView(statusText)

        val progressBar = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        layout.addView(progressBar)

        scrollView.addView(layout)

        testButton.setOnClickListener {
            statusText.visibility = TextView.VISIBLE
            statusText.text = "正在测试 GUI-Plus（百炼）连通性..."
            statusText.setTextColor(0xFF333333.toInt())
            progressBar.visibility = ProgressBar.VISIBLE
            testButton.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                val testResult = testGuiOwlGroundingConnection(KVUtils.getGuiOwlApiUrl())

                if (!isActive) return@launch
                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    testButton.isEnabled = true

                    if (testResult.isSuccess) {
                        statusText.text = "✓ ${testResult.connectionType}直连成功"
                        statusText.setTextColor(0xFF4CAF50.toInt())
                    } else {
                        statusText.text = "✗ [${testResult.connectionType}] ${testResult.error}"
                        statusText.setTextColor(0xFFE53935.toInt())
                    }
                }
            }
        }

        visualTestButton.setOnClickListener {
            statusText.visibility = TextView.VISIBLE
            statusText.text = "正在测试 GUI-Plus 视觉执行（生成样图并推理，约需 10-30 秒）..."
            statusText.setTextColor(0xFF333333.toInt())
            progressBar.visibility = ProgressBar.VISIBLE
            visualTestButton.isEnabled = false
            testButton.isEnabled = false

            lifecycleScope.launch(Dispatchers.IO) {
                val visualResult = testGuiOwlVisualExecution(KVUtils.getGuiOwlApiUrl())

                if (!isActive) return@launch
                withContext(Dispatchers.Main) {
                    progressBar.visibility = ProgressBar.GONE
                    visualTestButton.isEnabled = true
                    testButton.isEnabled = true

                    if (visualResult.isSuccess) {
                        statusText.text = "✓ ${visualResult.connectionType}视觉执行成功\n" +
                                "坐标: ${visualResult.coordinate}\n" +
                                "动作: ${visualResult.action}\n" +
                                "耗时: ${visualResult.inferenceTime}s"
                        statusText.setTextColor(0xFF4CAF50.toInt())
                    } else {
                        statusText.text = "✗ [${visualResult.connectionType}] ${visualResult.error}"
                        statusText.setTextColor(0xFFE53935.toInt())
                    }
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("GUI-Plus 配置")
            .setView(scrollView)
            .setPositiveButton("保存") { _, _ -> }
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                // 保存 API-Key（留空则不修改，避免误覆盖）
                val newKey = apiKeyEdit.text.toString().trim()
                if (newKey.isNotEmpty()) {
                    KVUtils.setGuiOwlApiKey(newKey)
                }
                viewModel.saveGuiOwlConfig()
                refreshGuiOwlConfigDisplay()
                Toast.makeText(this, "GUI-Plus配置已保存", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    data class GroundingTestResult(
        val isSuccess: Boolean,
        val error: String = "",
        val connectionType: String = ""  // "Tailscale" / "LAN" / "Funnel" / "未知"
    )

    /**
     * 测试 GUI-Plus（阿里云百炼）API 连通性（chat/completions 最小请求）
     */
    private suspend fun testGuiOwlGroundingConnection(apiUrl: String): GroundingTestResult {
        val connectionType = "百炼云端"
        val chatUrl = normalizeGuiOwlUrl(apiUrl)
        val apiKey = KVUtils.getGuiOwlApiKey()
        if (apiKey.isBlank()) {
            return GroundingTestResult(false, "API Key 未配置（local.properties 的 LLM_API_KEY）", connectionType)
        }

        return try {
            val testBody = Gson().toJson(mapOf(
                "model" to KVUtils.getGuiOwlModel(),
                "messages" to listOf(mapOf("role" to "user", "content" to "Hi")),
                "max_tokens" to 5,
                "temperature" to 0.0
            ))

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url(chatUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(testBody.toRequestBody("application/json".toMediaType()))
                .build()

            Log.d("SettingsActivity", "测试GUI-Plus API: $chatUrl, model=${KVUtils.getGuiOwlModel()}")
            val (code, body) = client.newCall(request).execute().use { resp ->
                Pair(resp.code, resp.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("SettingsActivity", "GUI-Plus API测试成功: $code")
                GroundingTestResult(true, connectionType = connectionType)
            } else {
                val errorMsg = try {
                    val errorMap = Gson().fromJson(body, Map::class.java)
                    (errorMap["error"] as? Map<*, *>)?.get("message")?.toString()
                        ?: "HTTP $code"
                } catch (e: Exception) {
                    "HTTP $code: ${body.take(200)}"
                }
                Log.e("SettingsActivity", "GUI-Plus API测试失败: $errorMsg")
                GroundingTestResult(false, errorMsg, connectionType)
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "GUI-Plus API测试异常: ${e.message}")
            GroundingTestResult(false, "连接失败: ${e.message ?: "未知错误"}", connectionType)
        }
    }

    data class VisualExecutionTestResult(
        val isSuccess: Boolean,
        val error: String = "",
        val connectionType: String = "",
        val coordinate: String = "",
        val action: String = "",
        val inferenceTime: String = "",
        val rawOutput: String = ""
    )

    /**
     * 测试 GUI-Plus 视觉执行：生成内置样图（蓝色"搜索"按钮），调用云端 GUI-Plus，
     * 验证模型能否返回有效坐标。
     */
    private suspend fun testGuiOwlVisualExecution(apiUrl: String): VisualExecutionTestResult {
        val connectionType = "百炼云端"

        try {
            // 1. 生成 720x1280 内置样图：白色背景 + 蓝色圆角矩形"搜索"按钮（中心 360,640，宽 200，高 80）
            val bmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1976D2")
                style = Paint.Style.FILL
            }
            // 圆角矩形：left=260, top=600, right=460, bottom=680，圆角半径 16
            canvas.drawRoundRect(
                260f, 600f, 460f, 680f, 16f, 16f, btnPaint
            )

            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f
                textAlign = Paint.Align.CENTER
            }
            // 文字基线：垂直居中于按钮（640）+ 微调
            val textBaseline = 640f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("搜索", 360f, textBaseline, textPaint)

            // 2. 调用云端 GUI-Plus 定位
            Log.d("SettingsActivity", "测试GUI-Plus视觉执行: ${KVUtils.getGuiOwlApiUrl()} (${connectionType})")
            val result = GuiOwlService.ground("点击搜索按钮", bmp, 720, 1280)
            bmp.recycle()

            if (!result.success) {
                return VisualExecutionTestResult(
                    isSuccess = false,
                    error = result.error ?: "定位失败",
                    connectionType = connectionType,
                    rawOutput = result.rawResponse.take(500)
                )
            }

            val coordStr = result.coordinate?.let { "[${it.x}, ${it.y}]" } ?: "-"
            Log.d("SettingsActivity", "GUI-Plus视觉执行成功: coord=$coordStr action=${result.action}")
            return VisualExecutionTestResult(
                isSuccess = true,
                connectionType = connectionType,
                coordinate = coordStr,
                action = result.action,
                inferenceTime = "${result.durationMs / 1000.0}s",
                rawOutput = result.rawResponse.take(500)
            )
        } catch (e: Exception) {
            Log.e("SettingsActivity", "GUI-Plus视觉执行测试异常: ${e.message}")
            return VisualExecutionTestResult(
                isSuccess = false,
                error = "连接失败: ${e.message ?: "未知错误"}",
                connectionType = connectionType
            )
        }
    }

    private fun testGuiOwlApiConnection(apiUrl: String, apiKey: String, modelName: String): TestResult {
        return try {
            val chatUrl = normalizeGuiOwlUrl(apiUrl)

            val testBody = Gson().toJson(mapOf(
                "model" to modelName,
                "messages" to listOf(mapOf("role" to "user", "content" to "Hi")),
                "max_tokens" to 5,
                "temperature" to 0.0
            ))

            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val requestBuilder = Request.Builder()
                .url(chatUrl)
                .addHeader("Content-Type", "application/json")

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val request = requestBuilder
                .post(testBody.toRequestBody("application/json".toMediaType()))
                .build()

            Log.d("SettingsActivity", "测试GUI-Plus API: $chatUrl, model=$modelName")
            val (maiCode, body) = client.newCall(request).execute().use { resp ->
                Pair(resp.code, resp.body?.string() ?: "")
            }

            if (maiCode in 200..299) {
                Log.d("SettingsActivity", "GUI-Plus API测试成功: $maiCode")
                TestResult(true)
            } else {
                val errorMsg = try {
                    val errorMap = Gson().fromJson(body, Map::class.java)
                    (errorMap["error"] as? Map<*, *>)?.get("message")?.toString()
                        ?: "HTTP $maiCode"
                } catch (e: Exception) {
                    "HTTP $maiCode: ${body.take(200)}"
                }
                Log.e("SettingsActivity", "GUI-Plus API测试失败: $errorMsg")
                TestResult(false, errorMsg)
            }
        } catch (e: Exception) {
            Log.e("SettingsActivity", "GUI-Plus API测试异常: ${e.message}")
            TestResult(false, "连接失败：${e.message ?: "未知错误"}")
        }
    }

    private fun normalizeGuiOwlUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) {
            trimmed
        } else if (trimmed.endsWith("/v1")) {
            "$trimmed/chat/completions"
        } else {
            "$trimmed/v1/chat/completions"
        }
    }

    // ==================== OCR 引擎选择（原） ====================

    private fun refreshOcrEngineDisplay() {
        val tv = findViewById<TextView>(R.id.tvOcrEngineValue)
        tv.text = "RapidOCR（本地）"
    }

    private fun setupOcrEngineConfig() {
        findViewById<LinearLayout>(R.id.menu_ocr_engine).setOnClickListener {
            showOcrEngineDialog()
        }
    }

    private fun showOcrEngineDialog() {
        val engineTypes = arrayOf("RapidOCR（本地）")
        val currentIndex = 0

        AlertDialog.Builder(this)
            .setTitle("OCR引擎")
            .setSingleChoiceItems(engineTypes, currentIndex) { dialog, which ->
                val selected = "rapidocr"
                viewModel.saveOcrEngine(selected)
                refreshOcrEngineDisplay()
                val label = if (selected == "rapidocr") "RapidOCR" else "ML Kit"
                Toast.makeText(this, "已切换为$label", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== TTS 语音播报配置 ====================

    @SuppressLint("SetTextI18n")
    private fun refreshTtsConfigDisplay() {
        val tv = findViewById<TextView>(R.id.tvTtsConfigValue)
        if (KVUtils.isTtsEnabled()) {
            tv.text = "已开启"
            tv.setTextColor(0xFF4CAF50.toInt())
        } else {
            tv.text = "已关闭"
            tv.setTextColor(0xFF999999.toInt())
        }
    }

    private fun setupTtsConfig() {
        findViewById<LinearLayout>(R.id.menu_tts_config).setOnClickListener {
            showTtsConfigDialog()
        }
    }

    private fun showTtsConfigDialog() {
        val enabled = KVUtils.isTtsEnabled()
        val switchView = SwitchCompat(this).apply {
            text = "任务结果语音播报"
            textSize = 14f
            setTextColor(0xFF333333.toInt())
            isChecked = enabled
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val descText = TextView(this).apply {
            text = "开启后，模型完成任务或出错时将通过语音播报结果。\n\n关闭后，所有任务结果仅通过文字显示。"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, 16)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        layout.addView(switchView)
        layout.addView(descText)
        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("语音播报")
            .setView(scrollView)
            .setPositiveButton("确定") { _, _ ->
                val newValue = switchView.isChecked
                KVUtils.setTtsEnabled(newValue)
                refreshTtsConfigDisplay()
                Toast.makeText(this, if (newValue) "语音播报已开启" else "语音播报已关闭", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 微信绑定 ====================

    private fun setupWeChatBinding() {
        val wechatMenu = findViewById<LinearLayout>(R.id.menu_wechat_binding)
        val wechatStatusText = findViewById<TextView>(R.id.wechat_status_text)
        val wechatActionText = findViewById<TextView>(R.id.wechat_action_text)
        val wechatIcon = findViewById<ImageView>(R.id.wechat_status_icon)

        // 开发中：锁死微信通道（扫码绑定功能未完成），固定显示"开发中"，点击仅提示不进入绑定流程
        wechatStatusText.text = "开发中"
        wechatStatusText.setTextColor(0xFFE53935.toInt())
        wechatActionText.text = "敬请期待"
        wechatIcon.setImageResource(android.R.drawable.presence_offline)
        wechatMenu.setOnClickListener {
            Toast.makeText(this, "微信通道开发中，敬请期待", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshWeChatUI(
        statusText: TextView, actionText: TextView, icon: ImageView, isBound: Boolean
    ) {
        val state = viewModel.uiState.value
        if (isBound) {
            statusText.text = "已绑定 · ${state.wechatBotId}"
            actionText.text = "解除绑定"
            icon.setImageResource(android.R.drawable.presence_online)
        } else {
            statusText.text = "未绑定"
            actionText.text = "扫码绑定"
            icon.setImageResource(android.R.drawable.presence_offline)
        }
    }

    private fun showWechatLogoutDialog(
        statusText: TextView, actionText: TextView, icon: ImageView
    ) {
        AlertDialog.Builder(this)
            .setTitle("退出微信绑定")
            .setMessage("确定要解除微信绑定吗？\nBot ID: ${viewModel.uiState.value.wechatBotId}")
            .setPositiveButton("确定") { _, _ ->
                viewModel.clearWechatBinding()
                refreshWeChatUI(statusText, actionText, icon, false)
                Toast.makeText(this, "已解除微信绑定", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 二维码登录 ====================

    private fun startQrCodeLogin(
        statusText: TextView, actionText: TextView, icon: ImageView
    ) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wechat_qrcode, null)
        val qrImageView = dialogView.findViewById<ImageView>(R.id.qr_code_image)
        val qrStatusText = dialogView.findViewById<TextView>(R.id.qr_status_text)
        val qrRetryBtn = dialogView.findViewById<Button>(R.id.qr_retry_btn)

        val dialog = AlertDialog.Builder(this)
            .setTitle("微信绑定")
            .setView(dialogView)
            .setNegativeButton("取消") { _, _ -> cancelQrLogin() }
            .setCancelable(false)
            .create()

        qrLoginDialog = dialog
        dialog.show()

        startQrLoginCycle(qrImageView, qrStatusText, qrRetryBtn) { authResult ->
            dialog.dismiss()
            saveWeChatAuth(authResult)
            refreshWeChatUI(statusText, actionText, icon, true)
            Toast.makeText(this, "微信绑定成功！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancelQrLogin() {
        qrLoginJob?.cancel()
        qrLoginJob = null
        qrLoginDialog = null
    }

    @SuppressLint("SetTextI18n")
    private fun startQrLoginCycle(
        qrImageView: ImageView, statusText: TextView,
        retryBtn: Button, onSuccess: (AuthResult) -> Unit
    ) {
        retryBtn.setOnClickListener {
            qrLoginJob?.cancel()
            startQrLoginCycle(qrImageView, statusText, retryBtn, onSuccess)
        }
        retryBtn.visibility = Button.INVISIBLE

        qrLoginJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val configuredUrl = KVUtils.getWechatApiBaseUrl()
                val baseUrl = configuredUrl.ifEmpty { "https://ilinkai.weixin.qq.com" }
                val apiClient = WeChatApiClient(baseUrl)

                Log.d("SettingsActivity", "WeChat getQrCode: $baseUrl/ilink/bot/get_bot_qrcode")

                val qrResult = apiClient.getQrCode()
                if (qrResult == null) {
                    onQrError(qrImageView, statusText, retryBtn, "获取二维码失败，请检查网络连接")
                    return@launch
                }

                val qrcode = qrResult.qrcode
                val content = qrResult.qrcodeImgContent

                val bitmap = generateQrBitmap(content, 512)
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        qrImageView.setImageBitmap(bitmap)
                        statusText.text = "请使用微信扫一扫扫描二维码"
                    }
                } else {
                    onQrError(qrImageView, statusText, retryBtn, "二维码生成失败")
                    return@launch
                }

                var pollCount = 0
                val maxPollCount = 60

                while (isActive && pollCount < maxPollCount) {
                    delay(2000)
                    pollCount++

                    try {
                        val authResult = apiClient.pollQrCodeStatus(qrcode)
                        if (authResult != null && authResult.botToken.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                statusText.text = "扫码成功，正在绑定..."
                            }
                            withContext(Dispatchers.Main) { onSuccess(authResult) }
                            return@launch
                        }
                    } catch (e: Exception) {
                        // P3-8 修复：记录异常而非静默吞掉，便于诊断
                        android.util.Log.w("SettingsActivity", "QR轮询异常: ${e.message}")
                    }
                }

                if (!isActive) return@launch
                onQrError(qrImageView, statusText, retryBtn, "登录超时，请重新获取二维码")
            } catch (e: Exception) {
                onQrError(qrImageView, statusText, retryBtn, "登录异常：${e.message}")
            }
        }
    }

    private fun onQrError(qrImageView: ImageView, statusText: TextView, retryBtn: Button, errorMsg: String) {
        statusText.post { statusText.text = errorMsg }
        retryBtn.post { retryBtn.visibility = Button.VISIBLE }
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap? {
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.MARGIN, 1)
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
            }
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e("SettingsActivity", "generateQrBitmap失败: ${e.message}")
            null
        }
    }

    private fun saveWeChatAuth(auth: AuthResult) {
        viewModel.saveWechatAuth(auth.botToken, auth.baseUrl, auth.botId, auth.userId)
    }

    // ==================== 一键诊断 ====================

    private fun setupDiagnoseAll() {
        findViewById<Button>(R.id.btnDiagnoseAll).setOnClickListener {
            diagnoseAllConfigs()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun diagnoseAllConfigs() {
        val resultTv = findViewById<TextView>(R.id.tvDiagnoseResult)
        val btn = findViewById<Button>(R.id.btnDiagnoseAll)

        resultTv.visibility = TextView.VISIBLE
        resultTv.text = "正在诊断..."
        btn.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val results = mutableListOf<String>()

            // 1. 文本执行模型 (LLM)
            val llmKey = KVUtils.getLlmApiKey()
            if (llmKey.isNotEmpty()) {
                val llmResult = testApiConnection(KVUtils.getLlmBaseUrl(), llmKey, KVUtils.getLlmModelName())
                results.add(if (llmResult.isSuccess) "✓ 文本执行模型: 正常" else "✗ 文本执行模型: ${llmResult.error}")
            } else {
                results.add("○ 文本执行模型: 未配置")
            }

            // 2. 视觉描述/问答模型 (GUI-Plus，VLM 已迁移至此)
            val guiVlmKey = KVUtils.getGuiOwlApiKey()
            if (guiVlmKey.isNotEmpty()) {
                val guiVlmResult = testApiConnection(
                    KVUtils.getGuiOwlApiUrl(), guiVlmKey, KVUtils.getGuiOwlModel()
                )
                results.add(if (guiVlmResult.isSuccess) "✓ 视觉描述/问答模型 (GUI-Plus): 正常"
                            else "✗ 视觉描述/问答模型 (GUI-Plus): ${guiVlmResult.error}")
            } else {
                results.add("○ 视觉描述/问答模型 (GUI-Plus): 未配置")
            }

            // 3. 键盘检测模型
            val kbKey = KVUtils.getKeyboardVlmApiKey()
            if (kbKey.isNotEmpty()) {
                val kbResult = testApiConnection(KVUtils.getKeyboardVlmApiUrl(), kbKey, KVUtils.getKeyboardVlmModelName())
                results.add(if (kbResult.isSuccess) "✓ 键盘检测模型: 正常" else "✗ 键盘检测模型: ${kbResult.error}")
            } else {
                results.add("○ 键盘检测模型: 未配置")
            }

            // 4. 视觉定位模型 (GUI-Plus Grounding)
            val guiOwlUrl = KVUtils.getGuiOwlApiUrl()
            val guiOwlResult = testGuiOwlGroundingConnection(guiOwlUrl)
            val enabledTag = "已启用"
            if (guiOwlResult.isSuccess) {
                results.add("✓ 视觉定位模型 (GUI-Plus): ${guiOwlResult.connectionType} ($enabledTag)")
            } else {
                results.add("✗ 视觉定位模型 (GUI-Plus): ${guiOwlResult.error} ($enabledTag)")
            }

            // 5. 任务决策模型
            val plannerKey = KVUtils.getPlannerApiKey()
            if (plannerKey.isNotEmpty()) {
                val plannerResult = testPlannerConnection(KVUtils.getPlannerApiUrl(), plannerKey, KVUtils.getPlannerModel())
                results.add(if (plannerResult.isSuccess) "✓ 任务决策模型: 正常" else "✗ 任务决策模型: ${plannerResult.error}")
            } else {
                results.add("○ 任务决策模型: 未配置")
            }

            // 6. 其他服务
            results.add("✓ RapidOCR: 本地引擎（无需配置）")

            // 高德地图 MCP
            val amapApiKey = KVUtils.getAmapApiKey()
            val amapEnabled = KVUtils.getAmapMcpEnabled()
            if (amapApiKey.isNotBlank()) {
                val amapResult = testAmapMcpConnection()
                val amapTag = if (amapEnabled) "已启用" else "未启用"
                results.add(if (amapResult.isSuccess) "✓ 高德地图 MCP: 正常 ($amapTag)" else "✗ 高德地图 MCP: ${amapResult.error} ($amapTag)")
            } else {
                results.add("○ 高德地图 MCP: 未配置 API Key")
            }

            withContext(Dispatchers.Main) {
                resultTv.text = results.joinToString("\n")
                btn.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelQrLogin()
        qrLoginDialog?.dismiss()
    }

    // ==================== 从 BuildConfig 强制导入（功能保留，入口已从设置页移除） ====================

    /**
     * 从编译时内置配置（local.properties）强制导入，覆盖当前所有模型配置。
     * 保留此功能供未来入口调用；设置页诊断工具中的导入按钮已移除。
     */
    fun importFromBuildConfig(): Int {
        val count = KVUtils.forceImportFromBuildConfig()
        viewModel.refreshConfig()
        refreshLlmConfigDisplay()
        refreshKeyboardVlmConfigDisplay()
        refreshGuiOwlConfigDisplay()
        refreshAmapMcpConfigDisplay()
        refreshPlannerConfigDisplay()
        return count
    }

    // ==================== 端侧知识库配置（完全本地 RAG，无服务端） ====================

    @SuppressLint("SetTextI18n")
    private fun setupKbConfig() {
        // 端侧知识库默认启用（isLocalKbEnabled 默认 true），已移除启用复选框
        findViewById<Button>(R.id.kb_rebuild_btn).setOnClickListener { confirmRebuildKb() }

        refreshKbStatus()
    }

    /** 点击"重新入库"：弹确认框后后台重建知识库（删除 kb.db 并从 assets 重新嵌入）。 */
    private fun confirmRebuildKb() {
        AlertDialog.Builder(this)
            .setTitle("重新入库")
            .setMessage("将从 assets/kb 重新读取全部 SOP 并重新嵌入建库（约 30-60s），期间知识库检索不可用。确定继续？")
            .setPositiveButton("确定") { _, _ -> rebuildKb() }
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun rebuildKb() {
        val rebuildBtn = findViewById<Button>(R.id.kb_rebuild_btn)
        val statusText = findViewById<TextView>(R.id.kb_status_text)
        rebuildBtn.isEnabled = false
        statusText.text = "正在重建知识库（约 30-60s）..."
        statusText.setTextColor(0xFFFF9800.toInt())
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.palmagent.app.kb.LocalKbEngine.rebuild(applicationContext)
                }
                Toast.makeText(this@SettingsActivity, "知识库重建完成", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("SettingsActivity", "知识库重建失败", e)
                Toast.makeText(this@SettingsActivity, "重建失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                rebuildBtn.isEnabled = true
                refreshKbStatus()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refreshKbStatus() {
        val statusText = findViewById<TextView>(R.id.kb_status_text)
        val engine = com.palmagent.app.kb.LocalKbEngine.get()
        if (engine == null) {
            statusText.text = if (KVUtils.isLocalKbEnabled())
                "端侧引擎初始化中...（首次启动需建库，约 30-60s）"
            else
                "端侧知识库未启用"
            statusText.setTextColor(0xFF999999.toInt())
        } else {
            statusText.text = "✅ 端侧知识库就绪（bge-small-zh INT8，离线可用）"
            statusText.setTextColor(0xFF4CAF50.toInt())
        }
    }
}