package com.palmagent.app.tool

import com.palmagent.app.tool.impl.*

object ToolRegistry {

    private val tools = LinkedHashMap<String, BaseTool>()

    /** 所有工具类列表，新增工具只需在此添加一行 */
    private val toolClasses: List<Pair<String, () -> BaseTool>> = listOf(
        "tap" to ::TapTool,
        "long_press" to ::LongPressTool,
        "swipe" to ::SwipeTool,
        "scroll_down" to ::ScrollDownTool,
        "scroll_up" to ::ScrollUpTool,
        "scroll_left" to ::ScrollLeftTool,
        "scroll_right" to ::ScrollRightTool,
        "scroll_until" to ::ScrollUntilTool,
        "back" to ::BackTool,
        "home" to ::HomeTool,
        "wait" to ::WaitTool,
        "finish" to ::FinishTool,
        "open_app" to ::OpenAppTool,
        "list_apps" to ::ListAppsTool,
        "auto_input" to ::AutoInputTool,
        "locate" to ::LocateTool,
        "get_screen_info" to ::GetScreenInfoTool,
        "visual_describe" to ::VisualDescribeTool,
        "user_action" to ::UserActionTool,
        // 规格自动选取（无障碍树驱动：查选中态 → 节点直点 → 表单过长自动小步下滑 → 点确认）
        "select_spec" to ::SelectSpecTool,
        // 高德地图 MCP 工具
        "amap_search" to ::AmapSearchTool,
        "amap_nearby" to ::AmapNearbyTool,
        "amap_directions" to ::AmapDirectionsTool,
        "amap_weather" to ::AmapWeatherTool,
        // 联网搜索工具（执行模型 + 决策模型共用 WebSearchService 后端）
        "web_search" to ::WebSearchTool
    )

    fun initAllTools() {
        tools.clear()
        toolClasses.forEach { (_, factory) ->
            val tool = factory()
            register(tool)
        }
    }

    fun register(tool: BaseTool) {
        tools[tool.getName()] = tool
    }

    fun getTool(name: String): BaseTool? = tools[name]

    fun getDisplayName(name: String): String = tools[name]?.getDisplayName() ?: name

    fun getAllTools(): List<BaseTool> = tools.values.toList()

    suspend fun executeTool(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
        return try {
            tool.executeWithWaitAfter(params)
        } catch (e: Exception) {
            ToolResult.error("Tool execution failed: ${e.message}")
        }
    }

    fun getToolDescriptionsForAI(): String {
        val sb = StringBuilder()
        for (tool in tools.values) {
            sb.append("- ${tool.getName()}: ${tool.getDescription()}\n")
            sb.append("  Parameters:\n")
            for (param in tool.getParametersWithWaitAfter()) {
                val required = if (param.isRequired) " (required)" else " (optional)"
                val enumInfo = param.enumValues?.let { ", options: ${it.joinToString("|")}" } ?: ""
                val defaultInfo = param.default?.let { ", default: $it" } ?: ""
                sb.append("    ${param.name} (${param.type})$required: ${param.description}$enumInfo$defaultInfo\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }
}
