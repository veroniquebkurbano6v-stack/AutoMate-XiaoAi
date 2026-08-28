package com.palmagent.app.tool.impl

import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

/**
 * 视觉描述工具
 *
 * 调用 GuiOwlService.query() 进行云端视觉问答，需要 API Key
 */
class VisualDescribeTool : BaseTool() {

    companion object {
        private const val TAG = "VisualDescribeTool"
    }

    override fun getName(): String = "visual_describe"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "question",
            "string",
            "关于当前屏幕的问题，如'有没有搜索按钮？'、'当前是什么页面？'、'描述屏幕内容'、'发送按钮在哪里？'",
            true
        )
    )

    override fun getDescriptionEN(): String =
        "Ask VLM a question about the current screen. Replaces exists/page_match/describe/ask. " +
        "Examples: 'Is there a search button?', 'What page is this?', 'Describe the screen', 'Where is the send button?' " +
        "If the screen contains a form (input fields/dropdowns/checkboxes), describe each field name and placeholder."

    override fun getDescriptionCN(): String =
        "向VLM视觉模型提问关于当前屏幕的问题。替代exists/page_match/describe/ask。 " +
        "示例：'有没有搜索按钮？'、'当前是什么页面？'、'描述屏幕内容'、'发送按钮在哪里？'。 " +
        "若屏幕包含表单（输入框/下拉/勾选等），必须逐项说明每个字段名与占位符。"

    override fun getDisplayName(): String = "视觉描述"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val question = requireString(params, "question")
        if (question.isBlank()) return ToolResult.error("问题不能为空")

        val screenshotBmp: Bitmap? = takeScreenshot()
        if (screenshotBmp == null) {
            return ToolResult.error("无法获取屏幕截图")
        }

        try {
            return executeCloud(question, screenshotBmp)
        } finally {
            if (!screenshotBmp.isRecycled) {
                try { screenshotBmp.recycle() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun executeCloud(question: String, bitmap: Bitmap): ToolResult {
        if (!GuiOwlService.isReady) {
            return ToolResult.error("VLM服务未就绪: ${GuiOwlService.lastError ?: "请确保LLM API已配置"}")
        }

        val result = GuiOwlService.ask(bitmap, question)
            ?: return ToolResult.error("视觉描述失败: GUI-Plus 未返回结果")

        if (!result.success) {
            return ToolResult.error("视觉描述失败: ${result.error ?: "未知错误"}")
        }

        val output = buildString {
            appendLine("视觉描述结果 (云端 VLM):")
            appendLine("  问题: $question")
            appendLine("  回答: ${result.answer}")
            appendLine("  耗时: ${result.durationMs}ms")
        }

        Log.d(TAG, "[visual_describe] 云端 VLM 成功: ${result.answer.take(100)} (${result.durationMs}ms)")
        return ToolResult.success(output)
    }
}
