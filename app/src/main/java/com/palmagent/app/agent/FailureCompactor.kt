package com.palmagent.app.agent

import android.util.Log
import com.google.gson.Gson
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.model.ActionRecord
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 失败信息压缩器
 *
 * 职责（方案 A/B 落地后的定位）：
 * - 单条错误大小已由方案 A 在工具层锁死（≤80 字符），本组件不再承担"错误太长"的压缩
 * - 真正的价值是【跨轮失败记忆】+【多失败归纳】：actionHistory 上下文只注入最近 4 轮，
 *   早期失败会随历史区滚出而丢失；本组件把跨轮累积的失败记录转发给压缩模型
 *   （默认智谱 GLM-4.5-Flash，未配置时回退决策模型 Planner 配置），归纳为一段 ≤maxChars
 *   的摘要（共同失败原因 + 替代建议），随后续轮次注入，避免模型重复犯错。
 *
 * 设计约束：
 * - 任何异常都回退为截断原始文本，绝不阻塞主流程
 * - 调用带超时（默认 10s），压缩失败即跳过
 */
class FailureCompactor {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "FailureCompactor"
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        /** 单次压缩调用超时（毫秒）。压缩模型生成 300-500 字摘要 + 可能的思考模式耗时较长，
         *  10s 实测偏短（频繁超时回退），放宽到 30s；失败自动重试 1 次，总预算 ≤60s（后台执行不阻塞主流程） */
        private const val COMPACT_TIMEOUT_MS = 30_000L

        private val COMPACT_SYSTEM_PROMPT = """
你是一个任务失败分析助手。你将收到 Android 自动化任务执行过程中失败的操作记录（JSON 数组）。
请将多条失败记录归纳压缩为一段中文摘要，必须包含：
1. 已尝试的动作（精简列出，不逐字复述原始错误）
2. 共同/关键失败原因（保留原始错误的关键信息，如错误类型、定位失败的目标等）
3. 建议的替代方案（基于失败原因给出可执行的下一步建议）

要求：
- 输出纯文本摘要，不要输出 JSON、不要输出 markdown 代码块
- 严格控制在 300 字以内
- 不要遗漏会导致模型重复犯错的错误原因（如"目标元素未找到"必须保留具体目标）
""".trimIndent()

        /** P1-1：运行摘要（Running Summary）压缩提示词——归纳滚出滑动窗口的早期操作历史 */
        private val RUNNING_SUMMARY_SYSTEM_PROMPT = """
你是一个 Android 自动化任务的运行摘要器。你收到两部分输入：
1. 上一版运行摘要（可为空）
2. 已滚出滑动窗口的早期操作记录（JSON 数组，含轮次/动作类型/描述/成败/结果摘要）

请把两者合并归纳为一段精简的中文运行摘要，必须保留：
1. 已完成的关键步骤链（动作类型+目标，如"打开微信→搜索医院服务号→进入预约挂号"）
2. 失败操作及原因（避免重复犯错）
3. 当前所处阶段或已知上下文

要求：
- 输出纯文本，不要输出 JSON、不要输出 markdown 代码块
- 严格控制在 [MAX_CHARS] 字以内
- 新增记录优先保留，旧摘要可压缩
""".trimIndent()
    }

    /**
     * 单条失败记录（由 DefaultAgentService 在工具失败时收集）
     */
    data class FailedAction(
        val round: Int,
        val actionType: String,
        val description: String,
        val error: String,
        val errorType: String,
        val category: String?,
        val suggestion: String
    )

    /**
     * 压缩失败记录列表为摘要
     *
     * @param userRequest 用户任务原文（供压缩模型理解任务上下文）
     * @param failures    失败记录（按时间顺序）
     * @param maxChars    摘要最大字符数，默认 300
     * @return 压缩摘要；压缩模型不可用/调用失败时回退为截断的原始错误拼接
     */
    suspend fun compact(
        userRequest: String,
        failures: List<FailedAction>,
        maxChars: Int = 300
    ): String {
        if (failures.isEmpty()) return ""

        val apiKey = KVUtils.getCompactApiKey()
        val apiUrl = normalizeApiUrl(KVUtils.getCompactApiUrl())
        val model = KVUtils.getCompactModel()

        // 压缩模型不可用 → 直接回退原始截断（不尝试调用）
        if (apiKey.isEmpty() || apiUrl.isEmpty()) {
            Log.w(TAG, "压缩模型未配置，回退原始错误截断")
            return fallbackText(failures, maxChars)
        }

        val payload = buildPayload(model, userRequest, failures)
        if (payload == null) {
            return fallbackText(failures, maxChars)
        }

        Log.d(TAG, "调用压缩模型: model=$model, url=$apiUrl, 失败记录=${failures.size}条")

        return callWithRetry(apiUrl, apiKey, payload, "失败信息压缩")
            ?.takeIf { it.isNotBlank() }
            ?.take(maxChars)
            ?: fallbackText(failures, maxChars)
    }

    /**
     * P1-1：压缩早期操作历史为运行摘要（Running Summary）。
     * 输入：已有摘要（可为空）+ 滚出滑动窗口的早期操作记录；输出归纳后的 ≤maxChars 摘要。
     * 复用与 [compact] 相同的压缩模型配置 / HTTP 客户端 / 超时 / 回退链路。
     */
    suspend fun compactHistory(
        userRequest: String,
        existingSummary: String,
        earlyActions: List<ActionRecord>,
        maxChars: Int = 500
    ): String {
        if (earlyActions.isEmpty()) return existingSummary

        val apiKey = KVUtils.getCompactApiKey()
        val apiUrl = normalizeApiUrl(KVUtils.getCompactApiUrl())
        val model = KVUtils.getCompactModel()

        // 压缩模型不可用 → 直接回退原始截断（不尝试调用）
        if (apiKey.isEmpty() || apiUrl.isEmpty()) {
            Log.w(TAG, "压缩模型未配置，运行摘要回退原始截断")
            return fallbackHistory(existingSummary, earlyActions, maxChars)
        }

        val historyJson = gson.toJson(earlyActions.map { it.toCompactEntry() })
        val userMessage = buildString {
            appendLine("用户任务：$userRequest")
            appendLine()
            if (existingSummary.isNotBlank()) {
                appendLine("上一版运行摘要：")
                appendLine(existingSummary)
                appendLine()
            }
            appendLine("早期操作记录：")
            appendLine(historyJson)
        }
        val requestMap = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to RUNNING_SUMMARY_SYSTEM_PROMPT.replace("[MAX_CHARS]", maxChars.toString())),
                mapOf("role" to "user", "content" to userMessage)
            ),
            "temperature" to 0.1,
            "max_tokens" to 512
        )
        val payload = runCatching { gson.toJson(requestMap) }.getOrNull()
        if (payload == null) return fallbackHistory(existingSummary, earlyActions, maxChars)

        Log.d(TAG, "调用运行摘要压缩: model=$model, 早期记录=${earlyActions.size}条")

        return callWithRetry(apiUrl, apiKey, payload, "运行摘要压缩")
            ?.takeIf { it.isNotBlank() }
            ?.take(maxChars)
            ?: fallbackHistory(existingSummary, earlyActions, maxChars)
    }

    /**
     * 单次压缩调用（网络请求 + 响应解析），失败/超时返回 null
     */
    private suspend fun callOnce(apiUrl: String, apiKey: String, payload: String, tag: String): String? =
        withTimeoutOrNull(COMPACT_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(apiUrl)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
                        .post(payload.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                        .build()

                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (!response.isSuccessful || body.isNullOrBlank()) {
                            Log.w(TAG, "${tag}失败: HTTP ${response.code}")
                            LiveLogBuffer.append("⚠️ ${tag}失败(HTTP ${response.code})，使用原始错误")
                            null
                        } else {
                            val answer = parseContent(body)
                            if (answer.isBlank()) {
                                Log.w(TAG, "${tag}返回空内容")
                                null
                            } else {
                                answer
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "${tag}异常: ${e.message}")
                    null
                }
            }
        }

    /**
     * 带一次重试的压缩调用（后台执行，不阻塞主流程）；
     * 失败原因写入任务证据日志（agent_full.log）便于追溯
     */
    private suspend fun callWithRetry(apiUrl: String, apiKey: String, payload: String, tag: String): String? {
        var answer = callOnce(apiUrl, apiKey, payload, tag)
        if (answer == null) {
            LiveLogBuffer.append("↻ ${tag}首次调用失败，重试一次…")
            answer = callOnce(apiUrl, apiKey, payload, tag)
        }
        if (answer == null) {
            // 失败原因写入任务证据日志
            AgentLogger.log(
                AgentLogger.LogType.ERROR,
                "${tag}调用失败（已重试 1 次）",
                "可能原因：生成超时(${COMPACT_TIMEOUT_MS / 1000}s)/网络异常/模型返回空内容；已回退原始错误截断"
            )
        } else {
            LiveLogBuffer.append("📦 ${tag}成功: ${answer.take(60)}...")
        }
        return answer
    }

    /** 早期操作记录 → 压缩输入的精简 JSON 条目（收敛输入大小） */
    private fun ActionRecord.toCompactEntry(): Map<String, Any?> = mapOf(
        "round" to round,
        "type" to actionType,
        "description" to description.take(80),
        "success" to success,
        "result" to resultSummary.take(80)
    )

    /** P1-1：运行摘要回退方案——已有摘要 + 早期记录截断拼接 */
    private fun fallbackHistory(existingSummary: String, earlyActions: List<ActionRecord>, maxChars: Int): String {
        val raw = buildString {
            if (existingSummary.isNotBlank()) {
                appendLine(existingSummary)
            }
            earlyActions.forEach { a ->
                val status = if (a.success) "✓" else "✗"
                appendLine("第${a.round}轮: $status ${a.actionType} ${a.description.take(40)} → ${a.resultSummary.take(60)}")
            }
        }
        return raw.take(maxChars)
    }

    /**
     * 构建压缩请求体
     */
    private fun buildPayload(model: String, userRequest: String, failures: List<FailedAction>): String? {
        val failuresJson = gson.toJson(failures)
        val userMessage = buildString {
            appendLine("用户任务：$userRequest")
            appendLine()
            appendLine("失败操作记录：")
            appendLine(failuresJson)
        }
        val requestMap = mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf("role" to "system", "content" to COMPACT_SYSTEM_PROMPT),
                mapOf("role" to "user", "content" to userMessage)
            ),
            "temperature" to 0.1,
            "max_tokens" to 512
        )
        return runCatching { gson.toJson(requestMap) }.getOrNull()
    }

    /**
     * 从响应体中提取 content 字段（兼容 content 为字符串的常规响应；
     * 思考模式模型正文可能位于 reasoning_content，content 为空时兜底取之）
     */
    private fun parseContent(body: String): String {
        return runCatching {
            val obj = gson.fromJson(body, Map::class.java)
            val choices = obj["choices"] as? List<*> ?: return ""
            val message = (choices.firstOrNull() as? Map<*, *>)?.get("message") as? Map<*, *>
                ?: return ""
            (message["content"] as? String)?.takeIf { it.isNotBlank() }
                ?: (message["reasoning_content"] as? String) ?: ""
        }.getOrDefault("")
    }

    /**
     * 回退方案：原始错误拼接后截断，保证上下文有信息但不过度膨胀
     */
    private fun fallbackText(failures: List<FailedAction>, maxChars: Int): String {
        val raw = failures.joinToString("；") { f ->
            "第${f.round}轮 ${f.actionType}(${f.description.take(40)})失败: ${f.error.take(120)}" +
                if (f.suggestion.isNotBlank()) "[建议:${f.suggestion.take(60)}]" else ""
        }
        return raw.take(maxChars)
    }

    /**
     * 归一化 API 地址（兼容不带 /chat/completions 后缀的 base url，如智谱 https://open.bigmodel.cn/api/paas/v4）
     */
    private fun normalizeApiUrl(url: String): String {
        if (url.isBlank()) return ""
        return if (url.endsWith("/chat/completions")) {
            url
        } else {
            url.trimEnd('/') + "/chat/completions"
        }
    }
}
