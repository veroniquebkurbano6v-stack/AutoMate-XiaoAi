package com.palmagent.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.palmagent.app.BuildConfig
import com.palmagent.app.agent.AgentConfig

object KVUtils {

    private const val TAG = "KVUtils"
    private const val PREFS_NAME = "palmagent_config"
    private lateinit var prefs: SharedPreferences

    // 加密存储文件（用于敏感信息如 API Key）
    private const val SECURE_PREFS_NAME = "palmagent_secure_config"
    private lateinit var securePrefs: SharedPreferences

    private const val KEY_LLM_API_KEY = "KEY_LLM_API_KEY"
    private const val KEY_LLM_BASE_URL = "KEY_LLM_BASE_URL"
    private const val KEY_LLM_MODEL_NAME = "KEY_LLM_MODEL_NAME"

    // 高德地图 MCP 配置
    private const val KEY_AMAP_MCP_ENABLED = "KEY_AMAP_MCP_ENABLED"
    private const val KEY_AMAP_API_KEY = "KEY_AMAP_API_KEY"

    private const val KEY_WECHAT_BOT_TOKEN = "KEY_WECHAT_BOT_TOKEN"
    private const val KEY_WECHAT_API_BASE_URL = "KEY_WECHAT_API_BASE_URL"
    private const val KEY_WECHAT_BOT_ID = "KEY_WECHAT_BOT_ID"
    private const val KEY_WECHAT_USER_ID = "KEY_WECHAT_USER_ID"
    private const val KEY_WECHAT_TO_USER_ID = "KEY_WECHAT_TO_USER_ID"

    private const val KEY_GUIDE_SHOWN = "KEY_GUIDE_SHOWN"

    private const val KEY_OCR_ENGINE = "KEY_OCR_ENGINE"

    private const val KEY_GUI_OWL_API_KEY = "KEY_GUI_OWL_API_KEY"
    private const val KEY_GUI_OWL_MODEL = "KEY_GUI_OWL_MODEL"
    private const val KEY_GUI_OWL_ENABLED = "KEY_GUI_OWL_ENABLED"
    private const val KEY_GUI_OWL_CONNECT_TIMEOUT = "KEY_GUI_OWL_CONNECT_TIMEOUT"
    private const val KEY_GUI_OWL_READ_TIMEOUT = "KEY_GUI_OWL_READ_TIMEOUT"
    private const val KEY_GUI_OWL_DECIDE_TIMEOUT = "KEY_GUI_OWL_DECIDE_TIMEOUT"
    private const val KEY_ACCESSIBILITY_REMIND_TS = "KEY_ACCESSIBILITY_REMIND_TS"
    private const val KEY_GUI_OWL_MAX_RETRIES = "KEY_GUI_OWL_MAX_RETRIES"
    private const val KEY_GUI_OWL_RETRY_DELAY_MS = "KEY_GUI_OWL_RETRY_DELAY_MS"

    private const val KEY_VLM_MODEL_NAME = "KEY_VLM_MODEL_NAME"
    private const val KEY_VLM_API_URL = "KEY_VLM_API_URL"
    private const val KEY_VLM_API_KEY = "KEY_VLM_API_KEY"
    private const val KEY_KEYBOARD_VLM_MODEL_NAME = "KEY_KEYBOARD_VLM_MODEL_NAME"
    private const val KEY_KEYBOARD_VLM_API_URL = "KEY_KEYBOARD_VLM_API_URL"
    private const val KEY_KEYBOARD_VLM_API_KEY = "KEY_KEYBOARD_VLM_API_KEY"

    // 决策模型配置（API Key 使用加密存储）
    private const val KEY_PLANNER_API_KEY = "KEY_PLANNER_API_KEY"        // 加密存储
    private const val KEY_PLANNER_API_URL = "KEY_PLANNER_API_URL"
    private const val KEY_PLANNER_MODEL = "KEY_PLANNER_MODEL"
    // KEY_PLANNER_ENABLE_SEARCH 已删除 — 联网搜索由 web_search 工具提供（与执行模型统一）

    // 上下文压缩模型配置（FailureCompactor 失败信息压缩，默认智谱 GLM-4.5-Flash）
    // 未配置时运行时回退使用决策模型（Planner）配置
    private const val KEY_COMPACT_API_KEY = "KEY_COMPACT_API_KEY"        // 加密存储
    private const val KEY_COMPACT_API_URL = "KEY_COMPACT_API_URL"
    private const val KEY_COMPACT_MODEL = "KEY_COMPACT_MODEL"

    // 本地知识库配置（端侧 RAG，无服务端依赖）
    private const val KEY_LOCAL_KB_ENABLED = "KEY_LOCAL_KB_ENABLED"

    // VL 视觉模型执行模式
    private const val KEY_VISION_MODE_ENABLED = "KEY_VISION_MODE_ENABLED"

    // 双模式切换：复杂模式（决策模型→执行模型） vs 简单模式（直接执行模型）
    private const val KEY_COMPLEX_MODE_ENABLED = "KEY_COMPLEX_MODE_ENABLED"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 初始化加密 SharedPreferences，用于存储敏感信息（API Key）
        securePrefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "加密存储初始化失败，降级为明文存储", e)
            context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        }

        // 每次启动从 BuildConfig 同步配置（覆盖 SharedPreferences 中的旧值）
        // 解决：local.properties 更新后 SharedPreferences 缓存旧值导致配置不生效
        val imported = forceImportFromBuildConfig()
        if (imported > 0) {
            Log.i(TAG, "启动时从 BuildConfig 导入 $imported 项配置")
        }
    }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    fun getString(key: String, defaultValue: String = ""): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    fun getInt(key: String, defaultValue: Int = 0): Int {
        return getString(key).toIntOrNull() ?: defaultValue
    }

    fun putInt(key: String, value: Int) = edit { putString(key, value.toString()) }

    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return getString(key).toLongOrNull() ?: defaultValue
    }

    fun putLong(key: String, value: Long) = edit { putString(key, value.toString()) }

    fun getFloat(key: String, defaultValue: Float = 0f): Float {
        return getString(key).toFloatOrNull() ?: defaultValue
    }

    fun putFloat(key: String, value: Float) = edit { putString(key, value.toString()) }

    fun putBoolean(key: String, value: Boolean) = edit { putBoolean(key, value) }
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    // LLM 配置
    fun getLlmApiKey(): String = getString(KEY_LLM_API_KEY).ifEmpty {
        com.palmagent.app.BuildConfig.LLM_API_KEY
    }
    fun setLlmApiKey(value: String) = edit { putString(KEY_LLM_API_KEY, value) }
    fun getLlmBaseUrl(): String = getString(KEY_LLM_BASE_URL).ifEmpty {
        com.palmagent.app.BuildConfig.LLM_API_URL
    }
    fun setLlmBaseUrl(value: String) = edit { putString(KEY_LLM_BASE_URL, value) }
    fun getLlmModelName(): String = getString(KEY_LLM_MODEL_NAME).ifEmpty {
        com.palmagent.app.BuildConfig.LLM_MODEL
    }
    fun setLlmModelName(value: String) = edit { putString(KEY_LLM_MODEL_NAME, value) }
    fun hasLlmConfig(): Boolean = getLlmApiKey().isNotEmpty()

    fun getAgentConfig(): AgentConfig {
        return AgentConfig.Builder()
            .apiKey(getLlmApiKey())
            .baseUrl(getLlmBaseUrl())
            .modelName(getLlmModelName())
            .temperature(0.1)
            .maxIterations(60)
            .build()
    }

    // 微信配置
    fun getWechatBotToken(): String = getString(KEY_WECHAT_BOT_TOKEN)
    fun setWechatBotToken(value: String) = edit { putString(KEY_WECHAT_BOT_TOKEN, value) }
    fun getWechatApiBaseUrl(): String = getString(KEY_WECHAT_API_BASE_URL)
    fun setWechatApiBaseUrl(value: String) = edit { putString(KEY_WECHAT_API_BASE_URL, value) }
    fun getWechatBotId(): String = getString(KEY_WECHAT_BOT_ID)
    fun setWechatBotId(value: String) = edit { putString(KEY_WECHAT_BOT_ID, value) }
    fun getWechatUserId(): String = getString(KEY_WECHAT_USER_ID)
    fun setWechatUserId(value: String) = edit { putString(KEY_WECHAT_USER_ID, value) }
    fun getWechatToUserId(): String = getString(KEY_WECHAT_TO_USER_ID)
    fun setWechatToUserId(value: String) = edit { putString(KEY_WECHAT_TO_USER_ID, value) }

    // IQS 搜索配置已删除（已切换到 WebSearchService + 博查 API）

    // 高德地图 MCP 配置
    fun getAmapMcpEnabled(): Boolean = getBoolean(KEY_AMAP_MCP_ENABLED, BuildConfig.AMAP_API_KEY.isNotEmpty())
    fun setAmapMcpEnabled(value: Boolean) = putBoolean(KEY_AMAP_MCP_ENABLED, value)

    /**
     * 高德 API Key（界面可配置：SharedPreferences 优先，为空回退 BuildConfig 注入值）
     */
    fun getAmapApiKey(): String = getString(KEY_AMAP_API_KEY).ifEmpty { BuildConfig.AMAP_API_KEY }
    fun setAmapApiKey(value: String) = edit { putString(KEY_AMAP_API_KEY, value) }

    /**
     * 高德 MCP 完整端点 URL（自动拼接 API Key）
     * 格式: https://mcp.amap.com/mcp?key=YOUR_API_KEY
     */
    fun getAmapMcpEndpointUrl(): String {
        val apiKey = getAmapApiKey()
        if (apiKey.isBlank()) return ""
        val baseUrl = BuildConfig.AMAP_MCP_BASE_URL
        val separator = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl${separator}key=$apiKey"
    }

    // 引导页
    fun isGuideShown(): Boolean = getBoolean(KEY_GUIDE_SHOWN)
    fun setGuideShown(shown: Boolean) = putBoolean(KEY_GUIDE_SHOWN, shown)

    // OCR 引擎选择
    fun getOcrEngineType(): String = getString(KEY_OCR_ENGINE, "rapidocr")
    fun setOcrEngineType(value: String) = edit { putString(KEY_OCR_ENGINE, value) }

    // GUI-Plus（阿里云百炼）界面交互模型配置
    // 基地址固定为百炼 DashScope OpenAI 兼容端点；
    // Key 优先级：SharedPreferences（设置界面）> BuildConfig（local.properties）
    fun getGuiOwlApiUrl(): String = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    fun getGuiOwlApiKey(): String = getString(KEY_GUI_OWL_API_KEY).ifEmpty {
        com.palmagent.app.BuildConfig.DASHSCOPE_API_KEY
    }
    fun setGuiOwlApiKey(value: String) = edit { putString(KEY_GUI_OWL_API_KEY, value) }
    fun getGuiOwlModel(): String = getString(KEY_GUI_OWL_MODEL).ifEmpty { "gui-plus-2026-02-26" }
    fun setGuiOwlModel(value: String) = edit { putString(KEY_GUI_OWL_MODEL, value) }
    fun isGuiOwlEnabled(): Boolean = getBoolean(KEY_GUI_OWL_ENABLED, true)
    fun setGuiOwlEnabled(value: Boolean) = putBoolean(KEY_GUI_OWL_ENABLED, value)
    fun getGuiOwlConnectTimeout(): Long = getString(KEY_GUI_OWL_CONNECT_TIMEOUT).toLongOrNull() ?: 30_000L
    fun setGuiOwlConnectTimeout(value: Long) = edit { putString(KEY_GUI_OWL_CONNECT_TIMEOUT, value.toString()) }
    fun getGuiOwlReadTimeout(): Long = getString(KEY_GUI_OWL_READ_TIMEOUT).toLongOrNull() ?: 120_000L
    fun setGuiOwlReadTimeout(value: Long) = edit { putString(KEY_GUI_OWL_READ_TIMEOUT, value.toString()) }
    // 视觉执行（DECIDE）超时：默认 20 秒，防止请求挂起长时间无响应（实机曾出现第一轮无响应、readTimeout=120s 等 2 分钟）
    fun getGuiOwlDecideTimeout(): Long = getString(KEY_GUI_OWL_DECIDE_TIMEOUT).toLongOrNull() ?: 20_000L
    fun setGuiOwlDecideTimeout(value: Long) = edit { putString(KEY_GUI_OWL_DECIDE_TIMEOUT, value.toString()) }
    // 无障碍引导上次提醒时间戳（启动自动弹引导的 24h 间隔防打扰）
    fun getAccessibilityRemindTs(): Long = getString(KEY_ACCESSIBILITY_REMIND_TS).toLongOrNull() ?: 0L
    fun setAccessibilityRemindTs(value: Long) = edit { putString(KEY_ACCESSIBILITY_REMIND_TS, value.toString()) }
    fun getGuiOwlMaxRetries(): Int = getString(KEY_GUI_OWL_MAX_RETRIES).toIntOrNull() ?: 2
    fun setGuiOwlMaxRetries(value: Int) = edit { putString(KEY_GUI_OWL_MAX_RETRIES, value.toString()) }
    fun getGuiOwlRetryDelayMs(): Long = getString(KEY_GUI_OWL_RETRY_DELAY_MS).toLongOrNull() ?: 1500L
    fun setGuiOwlRetryDelayMs(value: Long) = edit { putString(KEY_GUI_OWL_RETRY_DELAY_MS, value.toString()) }

    // VLM 视觉模型配置
    // SharedPreferences > BuildConfig（local.properties），两者都空时返回空（界面显示"未配置"）
    fun getVlmModelName(): String = getString(KEY_VLM_MODEL_NAME).ifEmpty {
        com.palmagent.app.BuildConfig.VLM_MODEL
    }
    fun setVlmModelName(value: String) = edit { putString(KEY_VLM_MODEL_NAME, value) }
    fun getVlmApiUrl(): String = getString(KEY_VLM_API_URL).ifEmpty {
        com.palmagent.app.BuildConfig.VLM_API_URL
    }
    fun setVlmApiUrl(value: String) = edit { putString(KEY_VLM_API_URL, value) }
    fun getVlmApiKey(): String = getString(KEY_VLM_API_KEY).ifEmpty {
        com.palmagent.app.BuildConfig.VLM_API_KEY
    }
    fun setVlmApiKey(value: String) = edit { putString(KEY_VLM_API_KEY, value) }

    // 键盘检测专用 VLM 配置（智谱 GLM-4V-Flash，减少成本）
    // 优先读自己的 SharedPreferences/BuildConfig；仅运行时调用（非界面显示）时 fallback 到主 VLM
    fun getKeyboardVlmModelName(): String {
        return getString(KEY_KEYBOARD_VLM_MODEL_NAME).ifEmpty {
            com.palmagent.app.BuildConfig.KEYBOARD_VLM_MODEL
        }
    }
    fun setKeyboardVlmModelName(value: String) = edit { putString(KEY_KEYBOARD_VLM_MODEL_NAME, value) }

    fun getKeyboardVlmApiUrl(): String {
        return getString(KEY_KEYBOARD_VLM_API_URL).ifEmpty {
            com.palmagent.app.BuildConfig.KEYBOARD_VLM_API_URL
        }
    }
    fun setKeyboardVlmApiUrl(value: String) = edit { putString(KEY_KEYBOARD_VLM_API_URL, value) }

    fun getKeyboardVlmApiKey(): String {
        return getString(KEY_KEYBOARD_VLM_API_KEY).ifEmpty {
            com.palmagent.app.BuildConfig.KEYBOARD_VLM_API_KEY
        }
    }
    fun setKeyboardVlmApiKey(value: String) = edit { putString(KEY_KEYBOARD_VLM_API_KEY, value) }

    /** 键盘检测模型是否已独立配置（API Key 非空即视为已配置） */
    fun hasKeyboardVlmConfig(): Boolean = getString(KEY_KEYBOARD_VLM_API_KEY).isNotEmpty()

    private const val KEY_SMART_WAIT_TIMEOUT_MS = "KEY_SMART_WAIT_TIMEOUT_MS"

    // 智能等待超时配置（默认8000ms，调研报告建议慢启动App需更长超时）
    fun getSmartWaitTimeoutMs(): Long = getString(KEY_SMART_WAIT_TIMEOUT_MS).toLongOrNull() ?: 4000L
    fun setSmartWaitTimeoutMs(value: Long) = edit { putString(KEY_SMART_WAIT_TIMEOUT_MS, value.toString()) }

    // ==================== 执行模型联网搜索配置 ====================
    // 与决策模型共用 web_search 工具（通过 WebSearchService 后端）
    private const val KEY_EXECUTION_ENABLE_SEARCH = "KEY_EXECUTION_ENABLE_SEARCH"
    private const val KEY_BOCHA_API_KEY = "KEY_BOCHA_API_KEY"  // 加密存储

    fun isExecutionSearchEnabled(): Boolean = getBoolean(
        KEY_EXECUTION_ENABLE_SEARCH,
        com.palmagent.app.BuildConfig.EXECUTION_ENABLE_SEARCH
    )
    fun setExecutionSearchEnabled(value: Boolean) = putBoolean(KEY_EXECUTION_ENABLE_SEARCH, value)

    fun getBochaApiKey(): String = securePrefs.getString(KEY_BOCHA_API_KEY, "")?.ifEmpty {
        com.palmagent.app.BuildConfig.BOCHA_API_KEY
    } ?: ""
    fun setBochaApiKey(value: String) {
        securePrefs.edit().putString(KEY_BOCHA_API_KEY, value).apply()
    }

    // ==================== 决策模型配置 ====================
    // API Key 使用加密存储（EncryptedSharedPreferences），其余配置使用普通存储

    fun getPlannerApiKey(): String = securePrefs.getString(KEY_PLANNER_API_KEY, "")?.ifEmpty {
        com.palmagent.app.BuildConfig.PLANNER_API_KEY
    } ?: ""

    fun setPlannerApiKey(value: String) {
        securePrefs.edit().putString(KEY_PLANNER_API_KEY, value).apply()
    }

    fun getPlannerApiUrl(): String = getString(KEY_PLANNER_API_URL).ifEmpty {
        com.palmagent.app.BuildConfig.PLANNER_API_URL
    }
    fun setPlannerApiUrl(value: String) = edit { putString(KEY_PLANNER_API_URL, value) }

    fun getPlannerModel(): String = getString(KEY_PLANNER_MODEL).ifEmpty {
        com.palmagent.app.BuildConfig.PLANNER_MODEL
    }
    fun setPlannerModel(value: String) = edit { putString(KEY_PLANNER_MODEL, value) }

    // ==================== 上下文压缩模型配置 ====================
    // 独立于决策模型（默认智谱 GLM-4.5-Flash），未配置时回退 Planner 配置

    fun getCompactApiKey(): String = securePrefs.getString(KEY_COMPACT_API_KEY, "")?.ifEmpty {
        com.palmagent.app.BuildConfig.COMPACT_API_KEY
    }?.ifEmpty {
        // 回退决策模型（Planner）配置
        getPlannerApiKey()
    } ?: ""

    fun setCompactApiKey(value: String) {
        securePrefs.edit().putString(KEY_COMPACT_API_KEY, value).apply()
    }

    fun getCompactApiUrl(): String = getString(KEY_COMPACT_API_URL).ifEmpty {
        com.palmagent.app.BuildConfig.COMPACT_API_URL
    }.ifEmpty {
        getPlannerApiUrl()
    }

    fun setCompactApiUrl(value: String) = edit { putString(KEY_COMPACT_API_URL, value) }

    fun getCompactModel(): String = getString(KEY_COMPACT_MODEL).ifEmpty {
        com.palmagent.app.BuildConfig.COMPACT_MODEL
    }.ifEmpty {
        getPlannerModel()
    }

    fun setCompactModel(value: String) = edit { putString(KEY_COMPACT_MODEL, value) }

    /** 决策模型是否已配置（API Key 非空即视为已配置） */
    fun hasPlannerConfig(): Boolean = getPlannerApiKey().isNotEmpty()

    // ==================== 上下文压缩模型配置 ====================
    // 独立于决策模型（默认智谱 GLM-4.5-Flash），未配置时回退 Planner 配置

    // 本地知识库配置
    fun isLocalKbEnabled(): Boolean {
        // 未在设置界面手动设置过时，默认启用（运行期 SharedPreferences 为准）
        val stored = getString(KEY_LOCAL_KB_ENABLED)
        return if (stored.isEmpty()) true else stored == "true"
    }
    fun setLocalKbEnabled(enabled: Boolean) = edit { putString(KEY_LOCAL_KB_ENABLED, enabled.toString()) }

    // VL 视觉模型执行模式：默认关闭，保持现有文本模型行为
    fun isVisionModeEnabled(): Boolean = getBoolean(KEY_VISION_MODE_ENABLED, false)
    fun setVisionModeEnabled(value: Boolean) = putBoolean(KEY_VISION_MODE_ENABLED, value)

    // 双模式切换：默认复杂模式（true），保持现有行为
    fun isComplexModeEnabled(): Boolean = getBoolean(KEY_COMPLEX_MODE_ENABLED, true)
    fun setComplexModeEnabled(value: Boolean) = putBoolean(KEY_COMPLEX_MODE_ENABLED, value)

    // ==================== TTS 语音播报配置 ====================
    private const val KEY_TTS_ENABLED = "KEY_TTS_ENABLED"
    private const val KEY_TTS_SPEECH_RATE = "KEY_TTS_SPEECH_RATE"

    /** TTS 语音播报是否启用（默认开启） */
    fun isTtsEnabled(): Boolean = getBoolean(KEY_TTS_ENABLED, true)
    fun setTtsEnabled(value: Boolean) = putBoolean(KEY_TTS_ENABLED, value)

    /** TTS 播报语速（0.5-2.0，1.0=正常，老年人推荐 0.85） */
    fun getTtsSpeechRate(): Float = getFloat(KEY_TTS_SPEECH_RATE, 0.85f)
    fun setTtsSpeechRate(value: Float) = putFloat(KEY_TTS_SPEECH_RATE, value)

    // ==================== 语音输入（ASR）配置 ====================
    private const val KEY_VOICE_INPUT_ENABLED = "KEY_VOICE_INPUT_ENABLED"

    /** 语音输入是否启用 */
    fun isVoiceInputEnabled(): Boolean = getBoolean(KEY_VOICE_INPUT_ENABLED, true)
    fun setVoiceInputEnabled(value: Boolean) = putBoolean(KEY_VOICE_INPUT_ENABLED, value)

    // ==================== BuildConfig 强制导入 ====================

    /**
     * 从 BuildConfig（编译期从 local.properties 注入）无条件覆盖所有模型配置到 SharedPreferences。
     *
     * 修复硬编码问题：旧逻辑仅在 BuildConfig 值非空时覆盖，导致 local.properties 清空后
     * SharedPreferences 仍保留旧值，App 内配置界面显示残留值。
     * 现改为无条件覆盖（含空字符串），确保 local.properties 为空时 SharedPreferences 也为空，
     * 配置界面正确显示"未配置"。
     *
     * getter 中的运行时 fallback（如 qwen3-vl-flash）仍保留，仅影响运行时调用不影响界面显示。
     *
     * @return 导入的配置项数量
     */
    fun forceImportFromBuildConfig(): Int {
        var count = 0

        // LLM 配置（无条件覆盖，空值也写入以清空旧残留）
        edit { putString(KEY_LLM_API_KEY, com.palmagent.app.BuildConfig.LLM_API_KEY) }
        edit { putString(KEY_LLM_BASE_URL, com.palmagent.app.BuildConfig.LLM_API_URL) }
        edit { putString(KEY_LLM_MODEL_NAME, com.palmagent.app.BuildConfig.LLM_MODEL) }
        count += 3

        // VLM 配置
        edit { putString(KEY_VLM_API_KEY, com.palmagent.app.BuildConfig.VLM_API_KEY) }
        edit { putString(KEY_VLM_API_URL, com.palmagent.app.BuildConfig.VLM_API_URL) }
        edit { putString(KEY_VLM_MODEL_NAME, com.palmagent.app.BuildConfig.VLM_MODEL) }
        count += 3

        // 键盘检测 VLM 配置（智谱 GLM-4V-Flash）
        edit { putString(KEY_KEYBOARD_VLM_API_KEY, com.palmagent.app.BuildConfig.KEYBOARD_VLM_API_KEY) }
        edit { putString(KEY_KEYBOARD_VLM_API_URL, com.palmagent.app.BuildConfig.KEYBOARD_VLM_API_URL) }
        edit { putString(KEY_KEYBOARD_VLM_MODEL_NAME, com.palmagent.app.BuildConfig.KEYBOARD_VLM_MODEL) }
        count += 3

        // Planner 配置（API Key 加密存储）
        securePrefs.edit().putString(KEY_PLANNER_API_KEY, com.palmagent.app.BuildConfig.PLANNER_API_KEY).apply()
        edit { putString(KEY_PLANNER_API_URL, com.palmagent.app.BuildConfig.PLANNER_API_URL) }
        edit { putString(KEY_PLANNER_MODEL, com.palmagent.app.BuildConfig.PLANNER_MODEL) }
        count += 3

        // 上下文压缩模型配置（FailureCompactor，API Key 加密存储）
        securePrefs.edit().putString(KEY_COMPACT_API_KEY, com.palmagent.app.BuildConfig.COMPACT_API_KEY).apply()
        edit { putString(KEY_COMPACT_API_URL, com.palmagent.app.BuildConfig.COMPACT_API_URL) }
        edit { putString(KEY_COMPACT_MODEL, com.palmagent.app.BuildConfig.COMPACT_MODEL) }
        count += 3

        Log.i(TAG, "从 BuildConfig 无条件覆盖 $count 项配置（含空值）")
        return count
    }

    // ==================== API Key 掩码工具 ====================

    /**
     * 将 API Key 掩码处理，仅显示后4位，前缀用 * 代替。
     * 例: sk-abcdef1234567890 → ****7890
     * 短于等于4位的 key 全部掩码为 ****
     */
    fun maskApiKey(key: String): String {
        if (key.length <= 4) return "****"
        return "****${key.takeLast(4)}"
    }
}