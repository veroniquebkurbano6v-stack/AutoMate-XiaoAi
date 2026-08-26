package com.palmagent.app.tool.impl

import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.service.RapidOcrService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

/**
 * 统一定位工具
 *
 * Grounding 优先 + OCR 兜底 混合定位策略：
 *
 * 1. Grounding 优先（~1.3s）：视觉定位，能处理图标和文字
 * 2. Grounding 失败 → OCR 兜底（~3.5s）：文字匹配，取第一个匹配
 *
 * 设计思路：
 * - Grounding 快且能处理图标，优先走
 * - OCR 慢但文字匹配稳定，兜底
 * - description 和 text 均为必填，确保两条路径都有足够信息
 * - 定位成功后自动点击，无需再调用 tap
 */
class LocateTool : BaseTool() {

    companion object {
        private const val TAG = "LocateTool"
    }

    override fun getName(): String = "locate"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "description",
            "string",
            "要定位的UI元素的具体描述（必填，Grounding视觉定位使用此描述）。必须包含：①位置信息（如右下角、顶部、底部导航栏）；②元素文字或图标含义；③可能的同义文字（如\"我的\"或\"个人中心\"、\"搜索\"或\"放大镜\"）。示例：'右下角的\"我的\"或\"个人中心\"入口'、'右上角的搜索/放大镜图标'、'底部输入框右侧的发送按钮'",
            true
        ),
        ToolParameter(
            "text",
            "string",
            "要定位的文字内容（必填，Grounding失败时OCR兜底使用）。有文字的按钮/标签填此项确保兜底路径可用；无文字的图标可填\"\"",
            true
        )
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val description = requireString(params, "description")
        if (description.isBlank()) return ToolResult.error("元素描述不能为空")

        val text = requireString(params, "text")

        val screenSize = getScreenSize()
        val screenWidth = screenSize[0]
        val screenHeight = screenSize[1]

        val screenshot: Bitmap? = takeScreenshot()
        if (screenshot == null) return ToolResult.error("无法获取屏幕截图")

        try {
            // 1. Grounding 优先
            val groundResult = locateByGuiOwl(description, screenshot, screenWidth, screenHeight)
            if (groundResult.isSuccess) return groundResult

            // 2. Grounding 失败 → OCR 兜底
            // 无文字图标（text为空）无法OCR兜底，直接返回Grounding错误
            if (text.isBlank()) {
                Log.d(TAG, "Grounding失败且text为空（无文字图标），无法OCR兜底")
                return groundResult
            }
            if (!RapidOcrService.isReady) {
                Log.w(TAG, "Grounding失败且OCR不可用，返回Grounding错误: ${groundResult.error}")
                return groundResult
            }

            val ocrResults = RapidOcrService.extractTextWithBboxes(screenshot)
            val matches = findTextMatches(ocrResults, text)
            if (matches.isEmpty()) {
                Log.d(TAG, "Grounding失败且OCR无匹配'$text'，返回Grounding错误")
                return groundResult
            }

            // OCR 兜底：直接取第一个匹配，不做多匹配消歧
            val match = matches[0]
            Log.d(TAG, "OCR兜底定位'$text': ${if (matches.size > 1) "${matches.size}个匹配,取第一个" else "唯一匹配"} '${match.text}' (${match.centerX},${match.centerY})")
            LiveLogBuffer.append("📍 OCR兜底定位'$text': '${match.text}' (${match.centerX}, ${match.centerY})")

            val clicked = performClick(match.centerX, match.centerY)
            return if (clicked) {
                ToolResult.success("OCR兜底定位并点击'${match.text}' (${match.centerX}, ${match.centerY})",
                    mapOf("x" to match.centerX, "y" to match.centerY))
            } else {
                ToolResult.error("点击手势被取消")
            }
        } finally {
            try { if (!screenshot.isRecycled) screenshot.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * 使用 GUI-Plus 视觉模型定位并点击
     */
    private suspend fun locateByGuiOwl(
        description: String,
        screenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int
    ): ToolResult {
        if (!GuiOwlService.isReady) {
            return ToolResult.error(
                "GUI-Plus服务未就绪: ${GuiOwlService.lastError ?: "请在设置中配置API地址"}",
                errorType = "FATAL",
                failureCategory = "SERVICE_UNAVAILABLE",
                code = "GUI_PLUS_UNAVAILABLE",
                suggestion = "GUI-Plus服务未配置，需用户在设置中配置API"
            )
        }

        val groundResult = GuiOwlService.ground(
            description, screenshot, screenWidth, screenHeight
        )

        if (!groundResult.success || groundResult.coordinate == null) {
            return ToolResult.error(
                "GUI-Plus定位失败: ${groundResult.error ?: "未知错误"}",
                errorType = "VALIDATION",
                failureCategory = "TARGET_NOT_FOUND",
                code = "TARGET_NOT_FOUND",
                suggestion = "目标元素可能不存在于当前屏幕，需滑动查找或换用其他方式"
            )
        }

        val groundX = groundResult.coordinate.x
        val groundY = groundResult.coordinate.y
        Log.d(TAG, "GUI-Plus定位: ($groundX,$groundY), 耗时${groundResult.durationMs}ms")
        LiveLogBuffer.append("✅ GUI-Plus定位'$description': ($groundX, $groundY) [${groundResult.durationMs}ms]")

        val clicked = performClick(groundX, groundY)
        return if (clicked) {
            ToolResult.success("GUI-Plus定位并点击'$description' ($groundX, $groundY)",
                mapOf("x" to groundX, "y" to groundY))
        } else {
            ToolResult.error("点击手势被取消")
        }
    }

    /**
     * 查找文本匹配（精确 > 包含 > 被包含）
     */
    private fun findTextMatches(
        ocrResults: List<RapidOcrService.OcrTextWithBbox>,
        text: String
    ): List<RapidOcrService.OcrTextWithBbox> {
        // 精确匹配
        val exact = ocrResults.filter { it.text.equals(text, ignoreCase = true) }
        if (exact.isNotEmpty()) return exact

        // 包含匹配
        val contains = ocrResults.filter { it.text.contains(text, ignoreCase = true) }
        if (contains.isNotEmpty()) return contains

        // 被包含匹配
        val contained = ocrResults.filter { text.contains(it.text, ignoreCase = true) }
        if (contained.isNotEmpty()) return contained

        return emptyList()
    }

    override fun getDescriptionEN(): String =
        "Locate and click a UI element. Uses GUI-Plus Grounding visual location first (~1.3s), " +
        "falls back to OCR text matching on failure (~3.5s). " +
        "Both 'description' and 'text' are required — description for Grounding, text for OCR fallback. " +
        "Works for both text buttons and textless icons."

    override fun getDescriptionCN(): String =
        "⭐视觉定位并自动点击（Grounding→OCR 兜底），无需再调tap。定位并点击UI元素。" +
        "Grounding视觉定位优先（~1.3s），失败时OCR文字匹配兜底（~3.5s）。description和text均为必填——" +
        "description用于Grounding视觉定位，text用于OCR兜底匹配。" +
        "适用于文字按钮和无文字图标。" +
        "重要：description必须包含位置信息、元素文字及可能的同义文字（如\"我的\"或\"个人中心\"、\"搜索\"或\"放大镜\"），" +
        "示例：'右下角的\"我的\"或\"个人中心\"入口'、'右上角的搜索/放大镜图标'、'底部输入框右侧的发送按钮'。"
}
