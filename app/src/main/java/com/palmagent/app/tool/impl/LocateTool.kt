package com.palmagent.app.tool.impl

import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

/**
 * 统一定位工具
 *
 * GUI-Plus Grounding 视觉定位策略：
 *
 * 1. Grounding（~1.3s）：视觉定位，能处理图标和文字
 *
 * 设计思路：
 * - Grounding 快且能处理图标，优先走
 * - description 为必填，确保视觉定位有足够信息
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
            "要定位的文字内容。有文字的按钮/标签填此项供描述参考；无文字的图标可填\"\"",
            true
        )
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val description = requireString(params, "description")
        if (description.isBlank()) return ToolResult.error("元素描述不能为空")

        val screenSize = getScreenSize()
        val screenWidth = screenSize[0]
        val screenHeight = screenSize[1]

        val screenshot: Bitmap? = takeScreenshot()
        if (screenshot == null) return ToolResult.error("无法获取屏幕截图")

        try {
            // Grounding 视觉定位（唯一路径）
            val groundResult = locateByGuiOwl(description, screenshot, screenWidth, screenHeight)
            return groundResult
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

    override fun getDescriptionEN(): String =
        "Locate and click a UI element. Uses GUI-Plus Grounding visual location (~1.3s). " +
        "Works for both text buttons and textless icons."

    override fun getDescriptionCN(): String =
        "⭐视觉定位并自动点击，无需再调tap。定位并点击UI元素。" +
        "Grounding视觉定位（~1.3s）。description必填——" +
        "description用于Grounding视觉定位，text为待定位文字可参考。" +
        "适用于文字按钮和无文字图标。" +
        "重要：description必须包含位置信息、元素文字及可能的同义文字（如\"我的\"或\"个人中心\"、\"搜索\"或\"放大镜\"），" +
        "示例：'右下角的\"我的\"或\"个人中心\"入口'、'右上角的搜索/放大镜图标'、'底部输入框右侧的发送按钮'。"
}
