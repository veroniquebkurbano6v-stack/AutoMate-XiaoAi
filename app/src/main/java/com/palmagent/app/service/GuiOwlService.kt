package com.palmagent.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.model.Coordinate
import com.palmagent.app.tool.ToolRegistry
import com.palmagent.app.utils.BitmapPool
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * GUI-Plus（阿里云百炼）界面交互模型服务
 *
 * 已废弃本地 GUI-Plus 服务（palm-pulse/serve_gui_owl.py），全部改为直接调用云端百炼 GUI-Plus：
 * - decide(): VL 模式执行决策（截图 + 任务提示 → 动作 + 坐标）
 * - ground(): 定位（instruction + 截图 → 屏幕像素坐标）
 * - exists(): 元素甄别（target + 截图 → exists 布尔）
 *
 * 接口：POST {base}/chat/completions（OpenAI 兼容，Bearer 鉴权）
 * 请求：{ model, messages:[system, user(image_url data URL + text)], vl_high_resolution_images }
 * 响应：choices[0].message.content 内含
 *       <tool_call>{"name":"mobile_use","arguments":{"action":"click","coordinate":[x,y]}}</tool_call>
 *
 * 坐标换算：模型输出在 [0,1000] 归一化空间，需缩放至真实屏幕尺寸：
 *   pixelX = coordinate[0] × screenWidth / 1000
 *   pixelY = coordinate[1] × screenHeight / 1000
 */
object GuiOwlService {

    private const val TAG = "GuiOwl"
    private const val JPEG_QUALITY = 40
    private const val MAX_IMAGE_DIMENSION = 4096
    private const val MAX_PIXELS = 12845056 // 官方 vl_high_resolution_images=true 的固定像素上限：客户端不预压缩，真实屏幕均低于此值按原图直传（无重采样信息损失）
    private const val WRITE_TIMEOUT_MS = 10_000L
    /** 视觉执行（DECIDE）超时：默认 30 秒（大图推理 10-30s 量级；仍可防请求挂起长时间无响应） */
    internal const val DECIDE_TIMEOUT_MS = 30_000L
    /** DECIDE 连接超时：10 秒——与 read 差异化，实机从超时耗时区分（≤10s=连接层/网络问题；10-30s=响应慢/服务端处理） */
    internal const val DECIDE_CONNECT_TIMEOUT_MS = 10_000L

    data class ScreenSize(val width: Int, val height: Int)

    data class GroundingResult(
        val success: Boolean,
        val coordinate: Coordinate? = null,
        val pixelCoordinate: Coordinate? = null,
        val action: String = "",
        val thinking: String = "",
        val answer: String = "",
        val rawResponse: String = "",
        val error: String? = null,
        val durationMs: Long = 0
    )

    data class DecideResult(
        val success: Boolean,
        val action: String = "",
        val coordinate: Coordinate? = null,
        val coordinateEnd: Coordinate? = null,
        val text: String? = null,
        /** 统一协议 swipe 方向（up/down/left/right/custom），与文本执行模型协议对齐 */
        val direction: String? = null,
        /** 统一协议动作描述（模型输出，透传到 AgentAction 供 actionHistory/日志审计） */
        val description: String? = null,
        /** 统一协议任务进度（模型自维护，与文本执行模型一致：completed_steps 只增不减） */
        val progress: com.palmagent.app.model.TaskProgress? = null,
        val rawResponse: String = "",
        val error: String? = null,
        val durationMs: Long = 0
    )

    /** 元素甄别结果：只判断目标元素是否存在，不返回坐标（防止模型幻觉坐标） */
    data class ExistsResult(
        val success: Boolean,
        val exists: Boolean = false,
        val reason: String = "",
        val rawResponse: String = "",
        val error: String? = null,
        val durationMs: Long = 0
    )

    // ============ 视觉描述/问答结果（自 VlmService 迁移） ============

    /** 方案C：广告弹窗判定结果（从视觉描述响应中解析） */
    data class AdJudgement(
        val type: String,                  // normal / close / auto
        val coordinate: String? = null,    // "x,y" [0,1000] 归一化（close）
        val conf: Float? = null,           // 置信度 0-1（close）
        val delaySeconds: Int? = null      // 等待秒数（auto）
    )

    /** 屏幕描述/视觉问答结果（自 VlmService 迁移） */
    data class VlmResult(
        val success: Boolean,
        val answer: String = "",
        val durationMs: Long = 0,
        val error: String? = null,
        val adJudgement: AdJudgement? = null   // 方案C：广告判定（无标签/解析失败为 null）
    )

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String? = null
        private set

    // ============ HTTP 客户端 ============

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(KVUtils.getGuiOwlConnectTimeout(), TimeUnit.MILLISECONDS)
            .readTimeout(KVUtils.getGuiOwlReadTimeout(), TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 视觉执行（DECIDE）专用超时 client：connect 10 秒 + read 可配（默认 20 秒）
     *  connect/read 差异化：≤10s 超时 = 连接层（手机网络问题）；10-20s 超时 = 响应慢（服务端处理）——用于实机定位超时归属 */
    private val decideClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(DECIDE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(KVUtils.getGuiOwlDecideTimeout(), TimeUnit.MILLISECONDS)
            .build()
    }

    // ============ 初始化 ============

    fun init(): Boolean {
        val apiKey = KVUtils.getGuiOwlApiKey()
        if (apiKey.isBlank()) {
            lastError = "百炼 API Key 未配置"
            Log.w(TAG, lastError!!)
            return false
        }
        isReady = true
        lastError = null
        Log.d(TAG, "GuiOwlService(GUI-Plus) 初始化成功, 模型=${KVUtils.getGuiOwlModel()}")
        LiveLogBuffer.append("✓ GUI-Plus(百炼) 服务就绪")
        return true
    }

    // ============ 定位入口（供 tap/auto_input/locate 等工具调用） ============

    suspend fun ground(
        instruction: String,
        bitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val truncatedInstruction = instruction.take(500)

        val payload = compressAndEncodeImage(bitmap)
        if (payload == null) {
            return@withContext GroundingResult(
                success = false,
                error = "图片编码失败",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        var lastErrorMsg: String? = null

        for (attempt in 1..KVUtils.getGuiOwlMaxRetries()) {
            try {
                Log.d(TAG, "[GROUND]请求 (尝试$attempt/${KVUtils.getGuiOwlMaxRetries()})")
                LiveLogBuffer.append("🔍 GUI-Plus[GROUND]: (尝试$attempt)")

                val content = requestChat(
                    text = "定位指令：$truncatedInstruction",
                    payload = payload,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    mode = PromptMode.GROUND
                )

                val result = parseGroundingResponse(
                    content, screenWidth, screenHeight, System.currentTimeMillis() - startTime
                )
                if (result.success) {
                    LiveLogBuffer.append(
                        "🎯 GUI-Plus[GROUND]成功: (${result.coordinate?.x},${result.coordinate?.y}) ${result.durationMs}ms"
                    )
                    return@withContext result
                }
                lastErrorMsg = result.error ?: "解析失败"
                // 失败日志追加模型原始响应前 300 字符（rawResponse），用于追溯模型实际输出的格式
                Log.w(TAG, "[GROUND]失败(尝试$attempt): $lastErrorMsg | raw: ${content.take(300)}")
                LiveLogBuffer.append("❌ GUI-Plus[GROUND]失败: $lastErrorMsg")
            } catch (e: java.net.SocketTimeoutException) {
                lastErrorMsg = "请求超时: ${e.message}"
                Log.e(TAG, "[GROUND]超时(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("⚠ GUI-Plus超时(尝试$attempt)")
            } catch (e: java.net.ConnectException) {
                lastErrorMsg = "连接失败: ${e.message}"
                Log.e(TAG, "[GROUND]连接失败(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus连接失败(尝试$attempt)")
            } catch (e: Exception) {
                lastErrorMsg = "网络错误: ${e.message}"
                Log.e(TAG, "[GROUND]网络错误(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus网络错误(尝试$attempt)")
            }

            if (attempt < KVUtils.getGuiOwlMaxRetries()) {
                delay(KVUtils.getGuiOwlRetryDelayMs())
            }
        }

        return@withContext GroundingResult(
            success = false,
            error = lastErrorMsg ?: "未知错误",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 元素甄别：判断目标元素在当前屏幕是否可见
     * 只返回 exists=true/false，不返回坐标（防止模型幻觉坐标）
     *
     * @param target 目标元素的视觉可辨识描述（如"心相印金装经典抽纸"、"底部导航栏的购物车图标"）
     * @param bitmap 屏幕截图
     */
    suspend fun exists(
        target: String,
        bitmap: Bitmap
    ): ExistsResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val truncatedTarget = target.take(200)

        val payload = compressAndEncodeImage(bitmap)
        if (payload == null) {
            return@withContext ExistsResult(
                success = false,
                error = "图片编码失败",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        var lastErrorMsg: String? = null

        for (attempt in 1..KVUtils.getGuiOwlMaxRetries()) {
            try {
                Log.d(TAG, "[EXISTS]请求 (尝试$attempt/${KVUtils.getGuiOwlMaxRetries()})")
                LiveLogBuffer.append("🔍 GUI-Plus[EXISTS]: ${truncatedTarget.take(40)} (尝试$attempt)")

                val content = requestChat(
                    text = "目标元素：$truncatedTarget",
                    payload = payload,
                    screenWidth = payload.width,
                    screenHeight = payload.height,
                    mode = PromptMode.EXISTS
                )

                val result = parseExistsResponse(content, System.currentTimeMillis() - startTime)
                if (result.success) {
                    Log.d(TAG, "[EXISTS]成功: exists=${result.exists} reason=${result.reason} (${result.durationMs}ms)")
                    LiveLogBuffer.append("✓ GUI-Plus[EXISTS]: exists=${result.exists} ${result.reason.take(40)}")
                    return@withContext result
                }
                lastErrorMsg = result.error ?: "解析失败"
                Log.w(TAG, "[EXISTS]失败(尝试$attempt): $lastErrorMsg")
            } catch (e: java.net.SocketTimeoutException) {
                lastErrorMsg = "请求超时: ${e.message}"
                Log.e(TAG, "[EXISTS]超时(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("⚠ GUI-Plus超时(尝试$attempt)")
            } catch (e: java.net.ConnectException) {
                lastErrorMsg = "连接失败: ${e.message}"
                Log.e(TAG, "[EXISTS]连接失败(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus连接失败(尝试$attempt)")
            } catch (e: Exception) {
                lastErrorMsg = "网络错误: ${e.message}"
                Log.e(TAG, "[EXISTS]网络错误(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus网络错误(尝试$attempt)")
            }

            if (attempt < KVUtils.getGuiOwlMaxRetries()) {
                delay(KVUtils.getGuiOwlRetryDelayMs())
            }
        }

        return@withContext ExistsResult(
            success = false,
            error = lastErrorMsg ?: "未知错误",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ============ VL 模式执行决策入口 ============

    /**
     * 屏幕描述（自由文本问答）：替代 VlmService.describeScreen，由 GUI-Plus 负责。
     * 输出上/中/下三区域描述 + 广告判定 <ad> 标签（方案C），失败返回 null（调用方降级）。
     */
    suspend fun describeScreen(bitmap: Bitmap, question: String? = null): VlmResult? =
        withContext(Dispatchers.IO) {
            if (!isReady) {
                Log.w(TAG, "GUI-Plus[DESCRIBE]服务未就绪，跳过")
                return@withContext null
            }
            val startTime = System.currentTimeMillis()
            val payload = compressAndEncodeImage(bitmap)
            if (payload == null) {
                Log.w(TAG, "GUI-Plus[DESCRIBE]图片编码失败")
                return@withContext null
            }
            val q = question?.takeIf { it.isNotBlank() } ?: SCREEN_DESC_PROMPT
            return@withContext try {
                val content = requestChat(
                    text = q,
                    payload = payload,
                    screenWidth = payload.width,
                    screenHeight = payload.height,
                    mode = PromptMode.DESCRIBE
                )
                VlmResult(
                    success = true,
                    answer = content.trim(),
                    durationMs = System.currentTimeMillis() - startTime,
                    adJudgement = parseAdJudgement(content)
                )
            } catch (e: Exception) {
                Log.e(TAG, "GUI-Plus[DESCRIBE]失败: ${e.message}")
                null
            }
        }

    /** 容器识别：识别可横向滑动容器（返回容器 JSON 文本——[0,1000] 归一化坐标；失败返回 null） */
    suspend fun recognizeContainers(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        if (!isReady) {
            Log.w(TAG, "GUI-Plus[CONTAINER]服务未就绪，跳过")
            return@withContext null
        }
        val payload = compressAndEncodeImage(bitmap)
        if (payload == null) {
            Log.w(TAG, "GUI-Plus[CONTAINER]图片编码失败")
            return@withContext null
        }
        return@withContext try {
            val content = requestChat(
                text = CONTAINER_PROMPT,
                payload = payload,
                screenWidth = payload.width,
                screenHeight = payload.height,
                mode = PromptMode.CONTAINER
            )
            content.trim()
        } catch (e: Exception) {
            Log.e(TAG, "GUI-Plus[CONTAINER]失败: ${e.message}")
            null
        }
    }

    suspend fun decide(
        userPrompt: String,
        screenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): DecideResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val payload = compressAndEncodeImage(screenshot)
        if (payload == null) {
            return@withContext DecideResult(
                success = false,
                error = "图片编码失败",
                durationMs = System.currentTimeMillis() - startTime
            )
        }

        var lastErrorMsg: String? = null

        for (attempt in 1..KVUtils.getGuiOwlMaxRetries()) {
            try {
                Log.d(TAG, "[DECIDE]请求 (尝试$attempt/${KVUtils.getGuiOwlMaxRetries()})")
                LiveLogBuffer.append("🔍 GUI-Plus[DECIDE]: (尝试$attempt)")

                val content = requestChat(
                    text = userPrompt.take(4000),
                    payload = payload,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    mode = PromptMode.DECIDE,
                    client = decideClient
                )

                val result = parseDecideResponse(
                    content, screenWidth, screenHeight, System.currentTimeMillis() - startTime
                )
                if (result.success) {
                    LiveLogBuffer.append(
                        "🎯 GUI-Plus[DECIDE]成功: ${result.action} " +
                            "(${result.coordinate?.x},${result.coordinate?.y}) ${result.durationMs}ms" +
                            if (result.progress != null) " | progress: 已完成=${result.progress.completedSteps}" else ""
                    )
                    return@withContext result
                }
                lastErrorMsg = result.error ?: "解析失败"
                Log.w(TAG, "[DECIDE]失败(尝试$attempt): $lastErrorMsg")
                LiveLogBuffer.append("❌ GUI-Plus[DECIDE]失败: $lastErrorMsg")
            } catch (e: java.net.SocketTimeoutException) {
                lastErrorMsg = "视觉请求超时(${DECIDE_TIMEOUT_MS / 1000}秒): ${e.message}"
                Log.e(TAG, "[DECIDE]超时(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("⚠ GUI-Plus[DECIDE]超时(尝试$attempt)")
            } catch (e: java.net.ConnectException) {
                lastErrorMsg = "连接失败: ${e.message}"
                Log.e(TAG, "[DECIDE]连接失败(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus[DECIDE]连接失败(尝试$attempt)")
            } catch (e: Exception) {
                lastErrorMsg = "网络错误: ${e.message}"
                Log.e(TAG, "[DECIDE]网络错误(尝试$attempt): ${e.message}")
                LiveLogBuffer.append("❌ GUI-Plus[DECIDE]网络错误(尝试$attempt)")
            }

            if (attempt < KVUtils.getGuiOwlMaxRetries()) {
                delay(KVUtils.getGuiOwlRetryDelayMs())
            }
        }

        return@withContext DecideResult(
            success = false,
            error = lastErrorMsg ?: "未知错误",
            durationMs = System.currentTimeMillis() - startTime
        )
    }

    // ============ 云端请求 ============

    internal enum class PromptMode { GROUND, DECIDE, EXISTS, DESCRIBE, QA, CONTAINER }

    /**
     * 发送一次 GUI-Plus chat/completions 请求，返回 assistant 的 content 文本。
     * 失败时抛出异常（由调用方捕获重试）。
     */
    internal fun requestChat(
        text: String,
        payload: ImagePayload,
        screenWidth: Int,
        screenHeight: Int,
        mode: PromptMode,
        client: OkHttpClient = this.client
    ): String {
        val apiKey = KVUtils.getGuiOwlApiKey()
        require(apiKey.isNotBlank()) { "百炼 API Key 未配置" }

        val baseUrl = KVUtils.getGuiOwlApiUrl().trimEnd('/')
        val chatUrl = "$baseUrl/chat/completions"
        // 诊断日志：确认实际请求域名（专属域名 or DashScope）——实机超时排查用（覆盖 DECIDE/DESCRIBE/GROUND/QA 所有模式）
        Log.d(TAG, "GUI-Plus url=$chatUrl")

        val requestBody = JSONObject().apply {
            put("model", KVUtils.getGuiOwlModel())
            put("messages", buildMessages(text, payload, screenWidth, screenHeight, mode))
            put("vl_high_resolution_images", true)
            // 思考模式显式关闭（实测不传 enable_thinking 服务端默认开启思考——响应慢 + 输出预算被占用；DECIDE 输入大可能超思考上限）
            put("enable_thinking", false)
        }.toString().toRequestBody("application/json".toMediaType())

        val httpRequest = Request.Builder()
            .url(chatUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val apiError = runCatching { JSONObject(body).optJSONObject("error")?.optString("message", "") }
                    .getOrNull().orEmpty()
                throw IllegalStateException("HTTP ${response.code}: ${apiError.ifBlank { body.take(200) }}")
            }
            return extractAssistantContent(body)
        }
    }

    /** 从 chat/completions 响应中提取 assistant 内容；无 choices 时抛异常 */
    private fun extractAssistantContent(responseBody: String): String {
        val json = JSONObject(responseBody)
        val apiError = json.optJSONObject("error")
        if (apiError != null) {
            throw IllegalStateException(apiError.optString("message", "服务端错误"))
        }
        val choices = json.optJSONArray("choices")
        val message = choices?.optJSONObject(0)?.optJSONObject("message")
        val content = message?.optString("content", "")
        if (content.isNullOrBlank()) {
            throw IllegalStateException("响应为空")
        }
        return content
    }

    private fun buildMessages(
        text: String,
        payload: ImagePayload,
        screenWidth: Int,
        screenHeight: Int,
        mode: PromptMode
    ): JSONArray {
        val systemPrompt = when (mode) {
            PromptMode.GROUND -> buildGroundSystemPrompt()
            PromptMode.DECIDE -> buildDecideSystemPrompt()
            PromptMode.EXISTS -> buildExistsSystemPrompt()
            PromptMode.DESCRIBE -> buildDescribeSystemPrompt()
            PromptMode.QA -> buildQaSystemPrompt()
            PromptMode.CONTAINER -> CONTAINER_PROMPT
        }
        return JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", "data:image/jpeg;base64,${payload.base64}"))
                    })
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                    })
                })
            })
        }
    }

    /**
     * 定位模式：专用定位 prompt（不复用官方通用 MOBILE_SYSTEM_PROMPT——其动作空间含 open/type/answer 等
     * 无坐标动作，与"定位必须返回坐标"的用途冲突，是"响应中未找到有效坐标"反复重试的根因，经真实 API 实测确认）。
     * 精简原则（Anthropic 上下文工程）：最小高信号 token 集——白名单优于负面枚举、只输出解析器消费的内容。
     */
    internal fun buildGroundSystemPrompt(): String = """
        给定屏幕截图与定位指令，返回应点击的屏幕位置。

        # 输出
        只输出一个 <tool_call> 块（不要输出其他内容）：
        <tool_call>{"name": "mobile_use", "arguments": {"action": "click", "coordinate": [x, y]}}</tool_call>
        坐标 [x,y] 为 [0,1000] 归一化。

        # 约束
        - action 只能是 click/long_press/swipe，必须携带 coordinate。
        - 禁止其他动作（open/type/answer/terminate/interact 等）。
        - 指令是"打开/输入/搜索"等复合操作时，仍只返回应点击元素的坐标，不要输出打开或输入动作。
    """.trimIndent()

    /**
     * 决策模式：统一动作协议（与文本执行模型一致）——注入 ToolRegistry 统一工具描述，
     * 视觉模型直接输出统一 JSON action（不再使用 GUI-Plus mobile_use 动作体系，删除了 GuiOwlActionAdapter 桥接）。
     */
    private fun buildDecideSystemPrompt(): String = """
        # 角色
        你是手机屏幕上的 GUI 操作决策助手。给定屏幕截图与用户任务，输出下一步要执行的动作。

        # 工具（动作空间，与文本执行模型统一）
        ${ToolRegistry.getExecutionToolDescriptions(isVision = false, isComplex = false, hideVisionUnused = true)}

        # 规则（最高优先级，前置）
        - **progress.completed_steps 只增不减**（系统单调维护），禁止删减已完成项；progress 是唯一活性修订载体——发现计划不适用时调整 remaining_steps。
        - **动作前先确认当前界面**：先看截图确认当前前台应用/页面；若任务或步骤要求的目标界面尚未打开，必须先 open_app 打开目标应用，**禁止在非目标界面直接 auto_input/type/点击**（会把输入误送给当前前台 App）。
        - **涉及个人信息填写必须 request_user_action**：姓名/身份证号/手机号/住址/支付账号等表单字段——若 Plan/上下文无用户明确提供的数据，禁止编造填写，必须停下让用户输入（request_user_action），用户填完继续。

        # 输出格式（严格遵循）
        只输出一个 JSON 对象（不要输出任何其他内容，不要用 tool_calls），字段：
        - type: 动作名（只能来自上方工具列表中的工具名，如 tap/swipe/auto_input/open_app/finish）
        - 各动作参数：见上方工具描述（coordinate 一律用数组 [x,y]，[0,1000] 归一化坐标，x 先 y 后）
        - description: 本动作的简短说明
        - progress(必填): {"current_step":"当前步骤","completed_steps":["已完成,只增不减"],"remaining_steps":["剩余步骤"],"status":"in_progress"}
        示例：
        {"type":"tap","coordinate":[500,400],"description":"点击搜索框"}
        {"type":"auto_input","text":"高血压","description":"输入搜索关键词"}
        {"type":"finish","description":"任务已完成","text":"用户接下来可查看挂号方式","progress":{"current_step":"任务完成","completed_steps":["打开浏览器","输入网址"],"remaining_steps":[],"status":"completed"}}
    """.trimIndent()

    /** 元素甄别模式：只输出 JSON，不输出 tool_call */
    private fun buildExistsSystemPrompt(): String = """
        你是手机屏幕元素甄别助手。根据屏幕截图与目标元素描述，判断该目标元素当前是否可见。
        只输出一个 JSON 对象，不要输出任何其他内容，格式：
        {"exists": true 或 false, "reason": "简短原因"}
    """.trimIndent()

    /** 屏幕描述模式：自由文本输出（上/中/下描述 + 广告判定），不强制 tool_call */
    private fun buildDescribeSystemPrompt(): String = SCREEN_DESC_PROMPT + """

【回答用户问题】
若用户消息中包含需要确认的问题（如"XX是否已选中？"、"当前界面是否是XX？"），
请在上/中/下三区域描述之后，用一句话明确回答该问题（如"好评优先已选中，列表按评分排序"）；
若用户消息仅是常规描述请求（无问题），则无需回答，只输出描述。
""".trimIndent()

    /** 屏幕描述提示词（自 VlmService 迁移）：上/中/下描述 + 方案C 广告判定附加段 */
    /** 容器识别 prompt：识别可横向滑动容器（结构化 JSON——name/y/type/selected——[0,1000] y——测试实证模型完全遵守） */
    internal const val CONTAINER_PROMPT = """请仔细识别该手机屏幕截图中的所有【可横向滑动/拖动的内容行】（横向滚轮选择器、可左右滑动的横向列表等）。
输出 JSON 数组（不要输出任何其他文字）：
[{"name": "容器名（简短中文，如'时间选择栏'）", "y": 522, "type": "horizontal", "selected": "当前选中值（如'14:30'）", "usage": "用途描述"}]
- y 为容器行中心线纵向位置（[0,1000] 归一化坐标）；type 填 horizontal（可横向滑动）；selected 为当前选中的项（滑动效果核对用；无选中概念填空字符串）；若没有任何可横向滑动容器，输出 []。"""

    internal const val SCREEN_DESC_PROMPT = """你是一个移动端屏幕分析助手。请将屏幕垂直分为上、中、下三部分，描述各区域的关键UI元素。

【输出格式】
上：<顶部区域>
中：<中部区域>
下：<底部区域>

【要求】
- 只描述可见的UI元素（按钮、输入框、列表项、图标、文本标签等），不要判断页面类型
- 每个区域控制在25字以内
- 如果某区域无UI元素，写"无"
- **若屏幕包含表单（输入框/下拉/勾选等），必须逐项说明每个字段名与占位符**（如"输入框：姓名（请输入姓名）"），含表单的区域可突破25字限制
- 只输出上述三行，不要任何额外内容

【示例输出】
上：搜索栏、小程序标签、"全部"按钮
中：i莞家小程序入口、"企业有难题"推荐、服务号列表
下：底部导航栏"微信"等图标

【附加任务：广告弹窗判断】
先判断当前界面是否为广告/干扰弹窗，输出一行 XML 标签（在屏幕描述之前）：
- 自动关闭广告（【图中可见倒计时数字】，如 "3"、"5" 秒倒计时，或"广告将在X秒后关闭"提示）→ <ad>auto</ad><delay>秒数</delay>
- 需手动关闭的广告/干扰弹窗（有"跳过/Skip/关闭×"按钮，或无倒计时数字）→ <ad>close</ad><coordinate>x,y</coordinate><conf>0-1</conf>
- 正常界面 → <ad>normal</ad>

【强制规则】
1. auto 判定【必须】依赖图中真实可见的倒计时数字；若图上看不到倒计时，一律判 close，禁止猜测 auto。
2. close 分支【必须】输出 coordinate（[0,1000] 归一化），禁止漏掉。
3. 无"跳过/关闭"字样的【全屏遮罩弹窗】也是干扰弹窗：如未成年模式弹窗、新手引导、登录弹窗、福利红包弹窗、升级提示 → 判 close 并给出关闭按钮坐标。

【负例规则】以下情况是正常界面，不是广告弹窗：
- 应用首页/列表页/搜索页/播放页/详情页（含导航栏、图标网格、搜索框、内容卡片）
- 规格选择弹窗（份量/辣度/杯型/口味）、确认对话框、权限请求、底部菜单
- 页面内嵌的推广横幅、推荐卡片、活动入口（它们不是弹窗，不遮挡主内容）

【坐标规则】coordinate 的 x,y 为 [0,1000] 归一化坐标；判断不确定时 conf 给低值(<0.6)。
输出 <ad> 标签行后，再输出上/中/下三区域描述。
"""

    /**
     * 方案C：从视觉描述响应中解析广告弹窗判定（<ad>/<delay>/<coordinate>/<conf>）。
     * 容错：坐标带空格、conf 标签未闭合、标签缺失均安全返回（null 表示无判定→按 normal 处理）。
     */
    internal fun parseAdJudgement(content: String): AdJudgement? {
        if (content.isBlank()) return null
        val adMatch = Regex("<ad>\\s*(\\w+)\\s*</ad>").find(content)
            ?: return null
        val type = adMatch.groupValues[1].lowercase()
        if (type !in setOf("normal", "close", "auto")) return null

        val coordMatch = Regex("<coordinate>\\s*([\\d.,\\s]+)\\s*</coordinate>").find(content)
        val coord = coordMatch?.groupValues?.get(1)?.trim()
            ?.let { if (it.contains(",")) it else null }
        val confMatch = Regex("<conf>\\s*([\\d.]+)").find(content)
        val conf = confMatch?.groupValues?.get(1)?.toFloatOrNull()
        val delayMatch = Regex("<delay>\\s*(\\d+)\\s*</delay>").find(content)
        val delay = delayMatch?.groupValues?.get(1)?.toIntOrNull()

        return AdJudgement(
            type = type,
            coordinate = coord,
            conf = conf,
            delaySeconds = delay
        )
    }

    /** 自由问答模式：回答用户关于截图的问题（替代 VlmService.query 的语义） */
    private fun buildQaSystemPrompt(): String =
        "你是移动端屏幕分析助手。根据屏幕截图简洁回答用户的问题，语言与问题一致。"

    /**
     * 自由视觉问答（替代 VlmService.query）：回答用户关于截图的问题，失败返回 null。
     */
    suspend fun ask(bitmap: Bitmap, question: String): VlmResult? =
        withContext(Dispatchers.IO) {
            if (!isReady) {
                Log.w(TAG, "GUI-Plus[QA]服务未就绪，跳过")
                return@withContext null
            }
            val startTime = System.currentTimeMillis()
            val payload = compressAndEncodeImage(bitmap)
            if (payload == null) {
                Log.w(TAG, "GUI-Plus[QA]图片编码失败")
                return@withContext null
            }
            return@withContext try {
                val content = requestChat(
                    text = question.take(500),
                    payload = payload,
                    screenWidth = payload.width,
                    screenHeight = payload.height,
                    mode = PromptMode.QA
                )
                VlmResult(
                    success = true,
                    answer = content.trim(),
                    durationMs = System.currentTimeMillis() - startTime,
                    adJudgement = null   // 自由问答无广告判定
                )
            } catch (e: Exception) {
                Log.e(TAG, "GUI-Plus[QA]失败: ${e.message}")
                null
            }
        }

    // ============ 响应解析 ============

    /** 发送图的尺寸信息（用于坐标缩放还原） */
    internal data class ImagePayload(val base64: String, val width: Int, val height: Int)

    /** 从 assistant content 中提取 <tool_call> JSON 的 arguments */
    private fun extractToolCall(content: String): JSONObject? {
        val match = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
            .find(content)
            ?: return null
        return runCatching {
            val toolJson = JSONObject(match.groupValues[1])
            toolJson.optJSONObject("arguments") ?: toolJson
        }.getOrNull()
    }

    /** 容错提取统一 JSON action：优先 <tool_call> 包裹（extractToolCall），其次提取文本中首个 {...} JSON 对象 */
    private fun extractActionJson(content: String): JSONObject? {
        extractToolCall(content)?.let { return it }
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(content.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    // internal 供同模块单测调用（GuiOwlServiceGroundingTest），模拟执行模型 GUI 定位请求的响应解析
    internal fun parseGroundingResponse(
        content: String,
        screenWidth: Int,
        screenHeight: Int,
        durationMs: Long
    ): GroundingResult {
        return try {
            val args = extractToolCall(content)
            val coordinateArr = args?.optJSONArray("coordinate")
            if (coordinateArr != null && coordinateArr.length() >= 2) {
                val pixel = scaleCoordinate(
                    coordinateArr.optDouble(0, 0.0), coordinateArr.optDouble(1, 0.0),
                    screenWidth, screenHeight
                )
                val coordinate = Coordinate(pixel.x, pixel.y)
                return GroundingResult(
                    success = true,
                    coordinate = coordinate,
                    pixelCoordinate = coordinate,
                    action = normalizeAction(args?.optString("action", "") ?: ""),
                    answer = "坐标: (${pixel.x}, ${pixel.y})",
                    rawResponse = content,
                    durationMs = durationMs
                )
            }
            GroundingResult(
                success = false,
                error = "响应中未找到有效坐标",
                rawResponse = content.take(300),
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析GROUND响应失败: ${e.message}")
            GroundingResult(
                success = false,
                error = "解析失败: ${e.message}",
                rawResponse = content.take(300),
                durationMs = durationMs
            )
        }
    }

    private fun parseDecideResponse(
        content: String,
        screenWidth: Int,
        screenHeight: Int,
        durationMs: Long
    ): DecideResult {
        return try {
            // 统一动作协议：模型输出纯 JSON action（{"type":...,"coordinate":[...]}），容错兼容 <tool_call> 包裹
            val json = extractActionJson(content)
            val action = json?.optString("type", "")?.trim().orEmpty()
            if (action.isBlank()) {
                return DecideResult(
                    success = false,
                    error = "响应中未找到有效动作",
                    rawResponse = content.take(300),
                    durationMs = durationMs
                )
            }

            var coordinate: Coordinate? = null
            val coordinateArr = json?.optJSONArray("coordinate")
            if (coordinateArr != null && coordinateArr.length() >= 2) {
                val pixel = scaleCoordinate(
                    coordinateArr.optDouble(0, 0.0), coordinateArr.optDouble(1, 0.0),
                    screenWidth, screenHeight
                )
                coordinate = Coordinate(pixel.x, pixel.y)
            }

            var coordinateEnd: Coordinate? = null
            // 统一协议兼容 coordinate_end 与 coordinate2 两种字段名
            val endArr = json?.optJSONArray("coordinate_end") ?: json?.optJSONArray("coordinate2")
            if (endArr != null && endArr.length() >= 2) {
                val pixel = scaleCoordinate(
                    endArr.optDouble(0, 0.0), endArr.optDouble(1, 0.0),
                    screenWidth, screenHeight
                )
                coordinateEnd = Coordinate(pixel.x, pixel.y)
            }

            val text = json?.optString("text", null)
            val direction = json?.optString("direction", null)?.lowercase()
                ?.takeIf { it in setOf("up", "down", "left", "right", "custom") }
            val description = json?.optString("description", null)
            // 统一协议任务进度（模型自维护）：current_step/completed_steps/remaining_steps/status（与 ActionParser 解析对齐）
            val progress = json?.optJSONObject("progress")?.let { pj ->
                fun strArr(name: String): List<String> {
                    val arr = pj.optJSONArray(name) ?: return emptyList()
                    return (0 until arr.length()).map { arr.optString(it) }
                }
                com.palmagent.app.model.TaskProgress(
                    currentStep = pj.optString("current_step", "").ifBlank { null },
                    completedSteps = strArr("completed_steps"),
                    remainingSteps = strArr("remaining_steps"),
                    status = pj.optString("status", "in_progress")
                )
            }

            DecideResult(
                success = true,
                action = action,
                coordinate = coordinate,
                coordinateEnd = coordinateEnd,
                text = text,
                direction = direction,
                description = description,
                progress = progress,
                rawResponse = content,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析DECIDE响应失败: ${e.message}")
            DecideResult(
                success = false,
                error = "解析失败: ${e.message}",
                rawResponse = content.take(300),
                durationMs = durationMs
            )
        }
    }

    private fun parseExistsResponse(content: String, durationMs: Long): ExistsResult {
        return try {
            // 允许模型输出被 ```json 代码块包裹，先剥掉
            val stripped = content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = JSONObject(stripped)
            ExistsResult(
                success = true,
                exists = json.optBoolean("exists", false),
                reason = json.optString("reason", ""),
                rawResponse = content,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "[EXISTS]解析失败: ${e.message}")
            ExistsResult(
                success = false,
                error = "解析失败: ${e.message}",
                rawResponse = content.take(300),
                durationMs = durationMs
            )
        }
    }

    /** 归一化动作名：兼容模型原生 computer_use 动作与自有动作集 */
    private fun normalizeAction(action: String): String = when (action.lowercase().trim()) {
        "click", "left_click", "right_click", "double_click", "middle_click", "mouse_move", "triple_click" -> "click"
        "long_press" -> "long_press"
        "swipe", "scroll", "hscroll", "left_click_drag", "drag" -> "swipe"
        "type", "key" -> "type"
        "system_button", "back", "home" -> "system_button"
        "open" -> "open"
        "wait" -> "wait"
        "answer", "interact" -> "answer"
        "terminate" -> "terminate"
        else -> action.lowercase().trim()
    }

    /** 坐标缩放还原：模型输出在 [0,1000] 归一化空间 → 真实屏幕像素（方案C 复用，故 internal） */
    internal fun scaleCoordinate(
        x: Double, y: Double,
        screenWidth: Int, screenHeight: Int
    ): Coordinate {
        val pixelX = (x * screenWidth / 1000.0).toInt().coerceIn(0, screenWidth)
        val pixelY = (y * screenHeight / 1000.0).toInt().coerceIn(0, screenHeight)
        return Coordinate(pixelX, pixelY)
    }

    fun getStatus(): String = buildString {
        appendLine("GUI-Plus(百炼) 状态:")
        appendLine("  就绪: $isReady")
        appendLine("  API: ${KVUtils.getGuiOwlApiUrl()}")
        appendLine("  模型: ${KVUtils.getGuiOwlModel()}")
        appendLine("  Key: ${if (KVUtils.getGuiOwlApiKey().isBlank()) "未配置" else "已配置"}")
        if (lastError != null) appendLine("  最后错误: $lastError")
    }

    // ============ 图片编码 ============

    internal fun compressAndEncodeImage(bitmap: Bitmap): ImagePayload? {
        return try {
            val srcBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
                try {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: Exception) {
                    Log.w(TAG, "HARDWARE→ARGB_8888 转换失败，回退原 bitmap: ${e.message}")
                    bitmap
                }
            } else {
                bitmap
            }

            var width = srcBitmap.width
            var height = srcBitmap.height

            val pixels = width * height
            // 预处理降采样：像素超过上限（KVUtils 可配，默认 150 万）时按比例缩放——加速服务端推理且保留足够细节
            val maxPixels = KVUtils.getGuiOwlImageMaxPixels()
            if (pixels > maxPixels) {
                val scale = Math.sqrt(maxPixels.toDouble() / pixels.toDouble())
                width = (width * scale).toInt().coerceAtLeast(1)
                height = (height * scale).toInt().coerceAtLeast(1)
                Log.d(TAG, "缩放图片: ${srcBitmap.width}x${srcBitmap.height} -> ${width}x${height}（上限 ${maxPixels} 像素）")
            }

            if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / maxOf(width, height)
                width = (width * scale).toInt()
                height = (height * scale).toInt()
            }

            val scaledBitmap = if (width != srcBitmap.width || height != srcBitmap.height) {
                val scaled = BitmapPool.acquire(width, height, Bitmap.Config.RGB_565)
                val canvas = Canvas(scaled)
                canvas.drawBitmap(srcBitmap, null, Rect(0, 0, width, height), null)
                scaled
            } else {
                srcBitmap
            }

            val estimatedSize = (width * height * 3 / 14).coerceAtLeast(8192)
            val outputStream = ByteArrayOutputStream(estimatedSize)

            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)

            if (scaledBitmap !== srcBitmap && !scaledBitmap.isRecycled) {
                BitmapPool.release(scaledBitmap)
            }
            if (srcBitmap !== bitmap && !srcBitmap.isRecycled) {
                srcBitmap.recycle()
            }

            ImagePayload(base64, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "图片编码失败: ${e.message}")
            null
        }
    }
}
