package com.palmagent.app.tool.impl

import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

class GetScreenInfoTool : BaseTool() {

    override fun getName(): String = "get_screen_info"
    override fun isExposedToExecutionModel(): Boolean = false

    override fun getParameters(): List<ToolParameter> = emptyList()

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val service = getA11yService()
            ?: return ToolResult.error("无障碍服务未运行")

        val screenInfo = service.getCurrentScreenInfo()

        if (screenInfo.uiElements.isEmpty()) {
            return ToolResult.error("无障碍服务返回数据为空，无法获取屏幕UI信息")
        }

        val result = buildString {
            appendLine("【无障碍服务屏幕信息】")
            appendLine("包名: ${screenInfo.currentPackage ?: "未知"}")
            appendLine("Activity: ${screenInfo.currentActivity ?: "未知"}")
            appendLine("UI元素数: ${screenInfo.uiElements.size}")
            appendLine()

            val elements = screenInfo.uiElements
            for ((_, el) in elements.withIndex()) {
                val typeIcon = when {
                    el.isClickable && el.isEditable -> "[输入框]"
                    el.isClickable && el.isScrollable -> "[可滚动]"
                    el.isClickable -> "[可点击]"
                    el.isEditable -> "[输入]"
                    el.isScrollable -> "[滚动]"
                    else -> "[${el.type.name}]"
                }
                append("  $typeIcon ")
                if (!el.text.isNullOrBlank()) append("\"${el.text}\" ")
                if (!el.contentDescription.isNullOrBlank()) append(" desc:\"${el.contentDescription}\" ")
                append("(${el.bounds.left},${el.bounds.top})-(${el.bounds.right},${el.bounds.bottom})")
                if (el.isClickable) append(" 可点击")
                if (el.isEditable) append(" 可编辑")
                if (el.isScrollable) append(" 可滚动")
                if (!el.id.isNullOrBlank() && el.id != "null") append(" id=${el.id}")
                appendLine()
            }
        }

        return ToolResult.success(result)
    }

    override fun getDescriptionEN(): String =
        "Get current screen UI information via accessibility service. " +
        "Use when you need to understand page structure or find clickable elements."

    override fun getDescriptionCN(): String =
        "获取当前屏幕的UI信息（通过无障碍服务）。" +
        "用于：了解页面结构、查找可点击按钮、获取控件坐标。"
}