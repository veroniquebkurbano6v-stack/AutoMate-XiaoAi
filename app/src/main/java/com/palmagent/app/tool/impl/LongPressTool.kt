package com.palmagent.app.tool.impl

import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

class LongPressTool : BaseTool() {
    override fun getName(): String = "long_press"

    /** 执行模型不再使用 long_press：从统一执行工具入口隐藏（与 amap_* 等内部工具一致），描述/检索候选均不出现 */
    override fun isExposedToExecutionModel(): Boolean = false

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("x", "integer", "Long press X coordinate", true),
        ToolParameter("y", "integer", "Long press Y coordinate", true),
        ToolParameter("duration_ms", "integer", "Press duration in ms (default 1200)", false)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val x = requireInt(params, "x")
        val y = requireInt(params, "y")
        val duration = optionalLong(params, "duration_ms", 1200)

        validateCoordinates(x, y)?.let { return ToolResult.error(it) }

        val service = getA11yService()
            ?: return ToolResult.error("无障碍服务未运行")

        val ok = service.performAccessibilitySwipe(x, y, x, y, duration)

        return if (ok) ToolResult.success("已长按 ($x, $y, ${duration}ms)", mapOf("x" to x, "y" to y))
        else ToolResult.error("长按手势被取消")
    }

    override fun getDescriptionEN(): String =
        "Long press at the specified screen coordinates."

    override fun getDescriptionCN(): String =
        "长按屏幕上的指定坐标位置。用于触发上下文菜单、选择文本、拖拽图标等长按操作。"
}