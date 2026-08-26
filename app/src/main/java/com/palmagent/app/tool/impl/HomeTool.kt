package com.palmagent.app.tool.impl

import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

class HomeTool : BaseTool() {
    override fun getName(): String = "home"

    override fun getParameters(): List<ToolParameter> = emptyList()

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val service = getA11yService()
        if (service != null) {
            val ok = service.performAccessibilityHome()
            return if (ok) ToolResult.success("已按主页键")
            else ToolResult.error("主页操作失败")
        }
        return ToolResult.error("无障碍服务未运行")
    }

    override fun getDescriptionEN(): String =
        "Press the system Home button to go to the launcher."

    override fun getDescriptionCN(): String =
        "按下系统主页键回到桌面/主屏幕。用于退出当前应用返回桌面。"
}