package com.palmagent.app.service

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 屏幕视觉描述服务（VLM）— 智谱 GLM-5.3-Flash
 *
 * 用途：每轮给执行模型注入"当前屏幕有什么"的简短描述（替代无障碍元素全文注入）。
 * 描述要求简短（≤80字），聚焦主要页面内容/可见文本/可点击元素，降低模型上下文压力。
 * 与 GuiOwlService.describeScreen（GUI-Plus，含广告判定）互斥：本服务已配置时优先使用，
 * 未配置/失败回退 GuiOwlService.describeScreen（保持广告判定兼容）。
 *
 * 实现参考 KeyboardVisionDetector（同为智谱 v4 多模态端点）。
 */
object ScreenVlmDescribeService {

    private const val TAG = "ScreenVlmDescribe"

    // 缩放比例（全图，保文本可读性的前提下降低传输体积）
    private const val SCALE_FACTOR = 0.7f
    // JPEG 压缩质量
    private const val JPEG_QUALITY = 40
    // 描述输出上限（token）。注意：GLM-5.3-Flash 是先思考(thinking)后回答的推理模型，
    // 需要给 reasoning_content 预留足够额度，否则 content 为空。128 实测会被thinking吃光。
    private const val MAX_TOKENS = 512

    /** 用户侧简短指令 */
    private const val DESCRIBE_PROMPT =
        "请用1-3个短句描述这张手机屏幕：列出页面类型、主要可见文本和可点击元素（如顶部标题、中部列表项、底部按钮），不超过80字，直接输出，不要任何前缀。"

    /** 系统角色：UI 分析师（官方推荐，提升结构化与稳定性） */
    private const val SYSTEM_PROMPT =
        "你是一个专业的UI界面分析师。针对提供的屏幕截图给出简短视觉描述，覆盖：整体页面类型、当前界面状态（弹窗/加载/错误）、主要可见文本与可点击元素。保持简洁精炼。"

    // 官方推荐参数
    private const val TEMPERATURE = 1.0
    private const val TOP_P = 0.95
    // 推理强度：屏描每轮偏快，用 low；thinking 无法关闭，但可显著降低耗时与token占用
    private const val REASONING_EFFORT = "low"
    // 失败重试次数（第1次之后的额外尝试）
    private const val RETRY_TIMES = 1

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8000, TimeUnit.MILLISECONDS)
        .readTimeout(45000, TimeUnit.MILLISECONDS)
        .writeTimeout(15000, TimeUnit.MILLISECONDS)
        .build()

    data class DescribeResult(
        val success: Boolean,
        val answer: String = "",
        val durationMs: Long = 0,
        val error: String? = null
    )

    /** 是否已配置（API Key 非空即视为已配置） */
    val isConfigured: Boolean
        get() = KVUtils.hasScreenVlmConfig()

    /**
     * 生成屏幕简短描述
     *
     * @param screenshot 全屏截图
     * @return 描述文本；失败返回 error 的 DescribeResult
     */
    suspend fun describeScreen(screenshot: Bitmap): DescribeResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        val apiUrl = KVUtils.getScreenVlmApiUrl()
        val apiKey = KVUtils.getScreenVlmApiKey()
        val modelName = KVUtils.getScreenVlmModelName()

        if (apiUrl.isBlank() || apiKey.isBlank()) {
            return@withContext DescribeResult(false, error = "屏幕描述VLM未配置(API地址或Key为空)", durationMs = System.currentTimeMillis() - startTime)
        }

        // 缩放 + 压缩编码
        val base64Image = compressAndEncode(screenshot)
        if (base64Image == null) {
            return@withContext DescribeResult(false, error = "图片编码失败", durationMs = System.currentTimeMillis() - startTime)
        }

        val messages = listOf(
            mapOf("role" to "system", "content" to SYSTEM_PROMPT),
            mapOf(
                "role" to "user",
                "content" to listOf(
                    mapOf("type" to "text", "text" to DESCRIBE_PROMPT),
                    mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64Image")
                    )
                )
            )
        )

        val requestMap = mapOf(
            "model" to modelName,
            "messages" to messages,
            "max_tokens" to MAX_TOKENS,
            "temperature" to TEMPERATURE,
            "top_p" to TOP_P,
            "reasoning_effort" to REASONING_EFFORT
        )

        val requestBody = JSONObject(requestMap).toString()
            .toRequestBody("application/json".toMediaType())

        val chatUrl = normalizeUrl(apiUrl)

        val httpRequest = Request.Builder()
            .url(chatUrl)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        try {
            var lastError: String? = "未知错误"
            var lastBody: String? = null
            val maxAttempts = RETRY_TIMES + 1
            for (attempt in 0 until maxAttempts) {
                val responseBody = try {
                    client.newCall(httpRequest).execute().use { it.body?.string() }
                } catch (e: Exception) {
                    lastError = e.message ?: "请求异常"
                    lastBody = null
                    null
                }
                if (responseBody.isNullOrBlank()) {
                    lastError = "响应为空或连接异常"
                } else {
                    val content = parseOpenAIResponse(responseBody)
                    if (content.isBlank()) {
                        lastBody = responseBody
                        lastError = "模型返回空内容"
                    } else {
                        val duration = System.currentTimeMillis() - startTime
                        Log.d(TAG, "屏幕描述(GLM-5.3): '${content.take(60)}', ${duration}ms")
                        LiveLogBuffer.append("👁 屏幕描述: ${content.take(50)} (${duration}ms)")
                        return@withContext DescribeResult(true, content.trim(), duration)
                    }
                }
                if (attempt < maxAttempts - 1) {
                    Log.w(TAG, "屏幕描述第${attempt + 1}次失败($lastError)，重试...")
                }
            }
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "屏幕描述${maxAttempts}次均失败: $lastError; resp=${lastBody?.take(300)}")
            return@withContext DescribeResult(false, error = lastError, durationMs = duration)

        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "屏幕描述异常: ${e.message}")
            return@withContext DescribeResult(false, error = e.message, durationMs = duration)
        }
    }

    /** 缩放 + JPEG 压缩 + Base64 编码 */
    private fun compressAndEncode(bitmap: Bitmap): String? {
        return try {
            val newW = (bitmap.width * SCALE_FACTOR).toInt().coerceAtLeast(1)
            val newH = (bitmap.height * SCALE_FACTOR).toInt().coerceAtLeast(1)
            val scaled = if (newW != bitmap.width || newH != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            } else {
                bitmap
            }
            val outputStream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            if (scaled !== bitmap) {
                try { scaled.recycle() } catch (_: Exception) {}
            }
            val byteArray = outputStream.toByteArray()
            Log.d(TAG, "屏幕描述图片: ${newW}x${newH}, ${byteArray.size / 1024}KB")
            Base64.encodeToString(byteArray, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "图片编码失败: ${e.message}")
            null
        }
    }

    private fun parseOpenAIResponse(responseBody: String): String {
        return try {
            val responseMap = JSONObject(responseBody)
            val choices = responseMap.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val message = choices.getJSONObject(0).optJSONObject("message")
                message?.optString("content", "") ?: ""
            } else ""
        } catch (e: Exception) {
            Log.e(TAG, "解析OpenAI响应失败: ${e.message}")
            ""
        }
    }

    private fun normalizeUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v4") -> "$trimmed/chat/completions"
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }
}