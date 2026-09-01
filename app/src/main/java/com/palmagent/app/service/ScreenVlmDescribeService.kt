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
 * 广告判定：系统提示词要求在识别到广告/推广/弹窗干扰界面时，描述末尾输出
 * "◆广告◆是|广告类型|关闭方式" 标记行（无广告不输出）。describeScreen 会从描述中
 * 剥离标记行并解析为 AdInfo，由上层（ScreenDescriptor）转换为广告关闭动作。
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
        "你是一个专业的UI界面分析师。针对提供的屏幕截图给出简短视觉描述，覆盖：整体页面类型、当前界面状态（弹窗/加载/错误）、主要可见文本与可点击元素。保持简洁精炼。" +
            "广告识别（重要）：仅当识别到明确的广告/商业推广/诱导下载类干扰界面时（特征：标注\"广告\"字样、\"跳过\"按钮、\"立即下载\"推广语、应用推广卡片、营销弹窗、倒计时免广告按钮、诱饵领取弹窗），" +
            "才在描述末尾**独立另起一行**输出广告标记，格式严格如下：\n" +
            "◆广告◆是|广告类型|能否自动跳过(是/否)|关闭按钮(文字=位置)\n" +
            "字段规则：\n" +
            "- 能否自动跳过=是：界面存在\"跳过/关闭/×\"按钮且点击不会跳转外部（开屏广告\"跳过\"、弹窗\"×\"）。\"关闭按钮\"填\"跳过=屏幕右上角\"等。\n" +
            "- 能否自动跳过=否：无跳过按钮/需用户手动点×。此时\"关闭按钮\"填**真实关闭入口及其位置**。\n" +
            "⚠️ **关闭叉必须主动扫描（关键）**：关闭叉通常尺寸小、对比度低（白色\"×\"印在彩色/渐变背景上、弹窗边缘半露的圆形关闭钮），**极易漏看**。判定\"无关闭按钮\"前必须逐区域检查弹窗的：右上角、左上角、下边缘、左右两侧边缘、四周角落。找到任何\"×/关闭/稍后再说/取消\"类按钮（哪怕很小、半露、透明），都必须填入其文字与位置（如\"×=弹窗右上角白色小叉\"、\"×=弹窗下边缘半露圆形按钮\"）。\n" +
            "⚠️ **转化按钮禁止当关闭**：\"收下/立即领取/去使用/立即下载/领券\"等是转化按钮（点击会领券/跳转外部），**一律不是关闭按钮**，不得填为此列。\n" +
            "⚠️ 只有当逐区域扫描后**确实不存在任何\"×\"类关闭入口**时，才填\"无关闭按钮\"。\n" +
            "- 位置描述用\"屏幕/弹窗\"+方位+外观特征（颜色/形状），供客户端自动定位点击。\n" +
            "所有应用内的正常功能弹窗（信息选择、确认对话框、预订/支付面板等）都不属于广告，不得输出该标记行。"

    /** 广告判定的"字样闸门"：真实广告界面（开屏/插屏/营销弹窗）合规上必然标注"广告"字样；
     *  业务功能弹窗（预订/确认/选择面板）描述中几乎不会出现"广告"。命中才认定广告，防误关。 */
    private val AD_KEYWORDS = listOf("广告")

    // 官方推荐参数
    private const val TEMPERATURE = 1.0
    private const val TOP_P = 0.95
    // 推理强度：屏描每轮偏快，用 low；thinking 无法关闭，但可显著降低耗时与token占用
    private const val REASONING_EFFORT = "low"
    // 失败重试次数（第1次之后的额外尝试）
    private const val RETRY_TIMES = 1
    // 广告标记行前缀（系统提示词约定的输出格式：◆广告◆是|广告类型|关闭方式）
    private const val AD_MARKER_PREFIX = "◆广告◆"

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(8000, TimeUnit.MILLISECONDS)
        .readTimeout(45000, TimeUnit.MILLISECONDS)
        .writeTimeout(15000, TimeUnit.MILLISECONDS)
        .build()

    data class DescribeResult(
        val success: Boolean,
        val answer: String = "",
        val durationMs: Long = 0,
        val error: String? = null,
        val adInfo: AdInfo? = null
    )

    /** 广告判定信息（由描述末尾的 ◆广告◆ 标记行解析得到；无标记行为 null 表示未识别到广告） */
    data class AdInfo(
        val isAd: Boolean,
        val adType: String = "",
        val autoSkip: Boolean = false,      // true=可自动跳过（有"跳过/关闭"按钮）；false=需手动关闭
        val closeButton: String = ""        // 关闭按钮（文字=位置）；"无关闭按钮"表示无任何关闭入口
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
                    var content = parseOpenAIResponse(responseBody)
                    if (content.isBlank()) {
                        lastBody = responseBody
                        lastError = "模型返回空内容"
                    } else {
                        val duration = System.currentTimeMillis() - startTime
                        // 提取广告标记行（◆广告◆是|类型|关闭方式），并从描述中剥离标记段
                        // （正则只删"◆广告◆"到行尾，兼容标记独立成行或与描述同行两种格式，避免误删描述）
                        val adInfo = parseAdMarker(content)
                        if (adInfo != null) {
                            content = content.replace(Regex("$AD_MARKER_PREFIX[^\n]*"), "").trim()
                        }
                        Log.d(TAG, "屏幕描述(GLM-5.3): '${content.take(60)}', ${duration}ms${if (adInfo?.isAd == true) ", AD=${adInfo.adType}" else ""}")
                        LiveLogBuffer.append("👁 屏幕描述: ${content.take(50)} (${duration}ms)")
                        return@withContext DescribeResult(true, content.trim(), duration, adInfo = adInfo)
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

    /**
     * 解析广告标记行：◆广告◆是|广告类型|能否自动跳过(是/否)|关闭按钮(文字=位置)
     *
     * - 未找到独立成行的标记 → null（无广告，维持原描述行为；模型拼在段落中间时忽略——宁可漏判不误关）
     * - 找到且判定"是" → 需过"广告字样闸门"（**剥离标记行后的**描述正文 + adType 字段含"广告"字样）
     *   才返回 AdInfo(isAd=true)，否则降级为 AdInfo(false)：防模型将业务功能弹窗（预订/确认面板）误判为广告
     * - 标记内容为"否/无" → AdInfo(false)（防御性兼容）
     * - 多标记行：取第一条有效"是"的；少于 4 字段视为格式异常降级为 AdInfo(false)
     */
    private fun parseAdMarker(content: String): AdInfo? {
        val markerLines = content.lineSequence().map { it.trim() }
            .filter { it.startsWith(AD_MARKER_PREFIX) }
            .toList()
        if (markerLines.isEmpty()) return null

        // 先剥离所有标记行得到纯正文，避免"◆广告◆"前缀本身恒含"广告"导致闸门失效
        val markerRegex = Regex("""\s*${Regex.escape(AD_MARKER_PREFIX)}[^\n]*""")
        val bodyText = markerRegex.replace(content, "").trim()

        for (segment in markerLines) {
            val body = segment.removePrefix(AD_MARKER_PREFIX).trim()
            if (body.startsWith("否") || body.startsWith("无")) {
                return AdInfo(false)
            }
            val parts = body.split("|").map { it.trim() }
            // 格式防御：协议要求 4 段，字段不足视为模型输出异常，降级不误关
            if (parts.size < 4) {
                Log.w(TAG, "广告标记字段不足(${parts.size}/4)，忽略：${segment.take(80)}")
                continue
            }
            // 精确匹配"是"，避免 contains 误命中"否/是否/不是"等子串
            val isAd = parts[0].startsWith("是")
            if (!isAd) continue

            // 字样闸门：仅检查"正文 + adType 字段"，不包含 AD_MARKER_PREFIX 自身
            val adTypeField = parts.getOrElse(1) { "" }
            val haystack = "$bodyText|$adTypeField"
            if (AD_KEYWORDS.none { it in haystack }) {
                Log.w(TAG, "广告标记被字样闸门拦截（正文与类型均无广告特征词）：${segment.take(80)}")
                return AdInfo(false)
            }

            return AdInfo(
                isAd = true,
                adType = adTypeField,
                autoSkip = parts.getOrElse(2) { "" }.startsWith("是"),
                closeButton = parts.getOrElse(3) { "" }
            )
        }
        return AdInfo(false)
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