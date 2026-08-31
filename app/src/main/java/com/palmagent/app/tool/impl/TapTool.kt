package com.palmagent.app.tool.impl

import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

class TapTool : BaseTool() {
    override fun getName(): String = "tap"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("x", "integer", "Tap X coordinate on screen", true),
        ToolParameter("y", "integer", "Tap Y coordinate on screen", true),
        ToolParameter("duration_ms", "integer", "Tap duration in milliseconds (default 100)", false)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val x = requireInt(params, "x")
        val y = requireInt(params, "y")
        val duration = optionalLong(params, "duration_ms", 100)

        validateCoordinates(x, y)?.let { return ToolResult.error(it) }

        val service = getA11yService()
            ?: return ToolResult.error(
                "无障碍服务未运行",
                errorType = "FATAL",
                failureCategory = "SERVICE_UNAVAILABLE",
                code = "A11Y_SERVICE_UNAVAILABLE",
                suggestion = "无障碍服务未运行，需用户开启服务"
            )

        val ok = if (duration <= 100) {
            service.performAccessibilityClick(x, y)
        } else {
            service.performAccessibilitySwipe(x, y, x, y, duration)
        }

        return if (ok) ToolResult.success("已点击 ($x, $y)", mapOf("x" to x, "y" to y))
        else ToolResult.error(
            "点击手势被取消",
            errorType = "TRANSIENT",
            code = "GESTURE_CANCELLED",
            suggestion = "手势被系统取消，可重试"
        )
    }

    override fun getDescriptionEN(): String =
        "Tap at the specified screen coordinates."

    override fun getDescriptionCN(): String =
        "点击屏幕坐标(x, y)。用于：点击按钮/图标/输入框。需精确坐标，无节点时优先用GUI-Plus定位。"
}