package com.palmagent.app.service

import android.util.Log
import com.google.gson.Gson
import com.palmagent.app.config.Config
import com.palmagent.app.model.*
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * AI 服务
 *
 * 重构后职责：
 * - API 调用（HTTP通信）
 * - 响应处理（reasoning_content回传）
 * - 上下文压缩调用
 *
 * 已拆分：
 * - 提示词构建 → PromptBuilder
 * - 响应解析 → ActionParser
 */
class AIService(
    private val actionParser: ActionParser = ActionParser
) {
    companion object {
        private const val TAG = "AIService"
    }

    private fun getApiUrl(): String {
        val userUrl = Config.LLM_API_URL
        if (userUrl.isBlank()) return ""
        val normalized = userUrl.trimEnd('/')
        return if (normalized.endsWith("/chat/completions")) normalized
        else if (normalized.endsWith("/v1")) "$normalized/chat/completions"
        else "$normalized/v1/chat/completions"
    }

    private fun getApiKey(): String {
        val userKey = Config.LLM_API_KEY
        if (userKey.isNotEmpty()) return userKey
        return ""
    }

    private fun getModelName(): String {
        val userModel = Config.LLM_MODEL
        if (userModel.isNotEmpty()) return userModel
        return ""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // 网络请求最大重试次数（针对超时/网络异常）
    private val maxRetries = 2
    // 重试间隔（毫秒），避免立即重试冲击服务端
    private val retryDelayMs = 1000L

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    data class ResponseFormat(val type: String = "json_object")

    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val max_tokens: Int = 8192,
        // v3.2 Bug-X 修复：改为可空，默认 null 由 Gson 跳过，避免向非 DeepSeek API 发送这些字段
        val reasoning_effort: String? = null,
        val thinking: ThinkingConfig? = null,
        // JSON Mode：从采样阶段强制保证 JSON 语法合法，根治 description 引号未转义问题
        val response_format: ResponseFormat? = null
    )

    data class ThinkingConfig(
        val type: String = "enabled"
    )

    data class ChatResponse(
        val choices: List<Choice>?
    ) {
        data class Choice(
            val message: ChatMessage?
        )
    }

    data class ErrorResponse(
        val error: ErrorDetail?
    ) {
        data class ErrorDetail(
            val message: String?,
            val type: String?
        )
    }

    suspend fun generateAction(
        userRequest: String,
        screenInfo: ScreenInfo?,
        knowledgeContext: String = ""
    ): AgentAction = withContext(Dispatchers.IO) {
        // B7 清理：buildPrompt 不再接收 screenInfo/actionHistory；actionHistory 参数已移除
        // （screenInfo 仍保留，parseActionFromResponse 解析坐标时需要）
        val prompt = PromptBuilder.buildPrompt(userRequest, knowledgeContext = knowledgeContext)
        val systemPrompt = PromptBuilder.getSystemPrompt()

        // 真机调试观测点：发送前打印 system prompt（含工具描述区），便于 logcat 核对模型实际收到的契约
        Log.d(TAG, "system_prompt[${systemPrompt.length}chars]\n$systemPrompt")

        val messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", prompt)
        )

        var lastError = "未知错误"
        val apiUrl = getApiUrl()
        val apiKey = getApiKey()
        // v3.2 Bug-X 修复：仅 DeepSeek API 注入 thinking 字段
        val isDeepSeekApi = apiUrl.contains("deepseek", ignoreCase = true)
        val request = ChatRequest(
            model = getModelName(),
            messages = messages,
            reasoning_effort = if (isDeepSeekApi) "low" else null,
            thinking = if (isDeepSeekApi) ThinkingConfig(type = "enabled") else null,
            response_format = ResponseFormat(type = "json_object")
        )

        val requestBody = gson.toJson(request).toRequestBody(jsonMediaType)

        if (apiKey.isEmpty()) {
            return@withContext AgentAction(
                type = "finish",
                description = "API Key 未配置，请在设置中配置API Key",
                confidence = 0f
            )
        }

        // 重试循环：超时/网络异常时自动重试，最多 maxRetries 次
        var attempt = 0
        while (attempt <= maxRetries) {
            attempt++
            try {
                val httpRequest = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                Log.d(TAG, "尝试API: $apiUrl, model: ${getModelName()}" +
                    (if (attempt > 1) " (重试 $attempt/${maxRetries + 1})" else ""))
                val (responseCode, responseBody) = client.newCall(httpRequest).execute().use { response ->
                    Pair(response.code, response.body?.string())
                }

                Log.d(TAG, "响应码: $responseCode")
                Log.d(TAG, "响应体: ${responseBody?.take(500) ?: "空"}")

                if (responseCode in 200..299 && !responseBody.isNullOrBlank()) {
                    val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                    val message = chatResponse.choices?.firstOrNull()?.message
                    var content = message?.content ?: ""
                    val reasoningContent = message?.reasoning_content ?: ""

                    if (content.isBlank() && reasoningContent.isNotBlank()) {
                        Log.w(TAG, "content为空，reasoning_content有内容(${reasoningContent.length}字)，重发请求让模型输出content")
                        val retryAction = retryWithReasoningContent(messages, reasoningContent)
                        if (retryAction != null) {
                            return@withContext retryAction
                        }
                        Log.w(TAG, "重发请求也失败，返回WAIT等待重试")
                        return@withContext AgentAction(
                            type = "wait",
                            description = "模型未输出有效操作，等待重试",
                            confidence = 0.3f
                        )
                    }

                    Log.d(TAG, "AI返回: ${content.take(200)}")
                    return@withContext actionParser.parseActionFromResponse(content, screenInfo)
                } else {
                    val errorMsg = try {
                        val errorResp = gson.fromJson(responseBody, ErrorResponse::class.java)
                        errorResp.error?.message ?: "API返回错误"
                    } catch (e: Exception) {
                        (responseBody ?: "无响应体").take(200)
                    }
                    lastError = errorMsg
                    Log.e(TAG, "API错误($apiUrl): $errorMsg")
                    // HTTP 错误码（4xx/5xx）不重试，直接返回
                    break
                }
            } catch (e: Exception) {
                lastError = e.message ?: "网络异常"
                Log.e(TAG, "网络错误($apiUrl): ${e.message} (尝试 $attempt/${maxRetries + 1})", e)
                // 超时/网络异常：未达最大重试次数则等待后重试
                if (attempt <= maxRetries) {
                    Log.w(TAG, "将在 ${retryDelayMs}ms 后重试...")
                    delay(retryDelayMs)
                }
            }
        }

        AgentAction(
            type = "finish",
            description = "API调用失败：$lastError",
            confidence = 0f
        )
    }

    /**
     * 当content为空但reasoning_content有内容时，重发请求让模型输出content
     */
    private suspend fun retryWithReasoningContent(
        originalMessages: List<ChatMessage>,
        reasoningContent: String
    ): AgentAction? = withContext(Dispatchers.IO) {
        try {
            val newMessages = originalMessages.toMutableList()
            newMessages.add(ChatMessage(
                role = "assistant",
                content = "",
                reasoning_content = reasoningContent
            ))
            newMessages.add(ChatMessage(
                role = "user",
                content = "你刚才只输出了思考过程但没有给出操作决策。请根据你的思考，在content中输出一个操作JSON。格式：{\"type\":\"操作类型\",\"text\":\"...\",\"description\":\"...\",\"confidence\":0.9}"
            ))

            val retryApiUrl = getApiUrl()
            val isDeepSeekApi = retryApiUrl.contains("deepseek", ignoreCase = true)
            val request = ChatRequest(
                model = getModelName(),
                messages = newMessages,
                reasoning_effort = if (isDeepSeekApi) "low" else null,
                thinking = if (isDeepSeekApi) ThinkingConfig(type = "enabled") else null,
                response_format = ResponseFormat(type = "json_object")
            )

            val requestBody = gson.toJson(request).toRequestBody(jsonMediaType)
            val httpRequest = Request.Builder()
                .url(retryApiUrl)
                .addHeader("Authorization", "Bearer ${getApiKey()}")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            Log.d(TAG, "重发请求: 回传reasoning_content(${reasoningContent.length}字)")
            val responseBody = client.newCall(httpRequest).execute().use { it.body?.string() }

            if (responseBody != null) {
                val chatResponse = gson.fromJson(responseBody, ChatResponse::class.java)
                val message = chatResponse.choices?.firstOrNull()?.message
                val retryContent = message?.content ?: ""

                if (retryContent.isNotBlank()) {
                    Log.d(TAG, "重发成功，获得content: ${retryContent.take(200)}")
                    return@withContext actionParser.parseActionFromResponse(retryContent, null)
                } else {
                    Log.w(TAG, "重发后content仍为空")
                    return@withContext null
                }
            } else {
                Log.w(TAG, "重发请求失败: 响应为空")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "重发请求异常: ${e.message}")
            return@withContext null
        }
    }

    
}
