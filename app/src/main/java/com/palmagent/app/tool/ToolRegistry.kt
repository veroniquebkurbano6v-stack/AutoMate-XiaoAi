package com.palmagent.app.tool

import com.palmagent.app.tool.impl.*

object ToolRegistry {

    private val tools = LinkedHashMap<String, BaseTool>()

    /** 所有工具类列表，新增工具只需在此添加一行 */
    private val toolClasses: List<Pair<String, () -> BaseTool>> = listOf(
        "tap" to ::TapTool,
        "long_press" to ::LongPressTool,
        "swipe" to ::SwipeTool,
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
        "request_user_action" to ::UserActionTool,
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

    /**
     * 获取执行模型可见的工具描述文本（动态生成，替代 PromptBuilder 中的硬编码）
     * @param isVision 视觉模式（精简部分不适合视觉模型使用的工具）
     * @param isComplex 复杂模式（隐藏 ask_user 工具）
     */
    fun getExecutionToolDescriptions(isVision: Boolean, isComplex: Boolean): String {
        val sb = StringBuilder()
        val filtered = tools.values
            .filter { it.isExposedToExecutionModel() }
            .filter { tool ->
                when (tool.getName()) {
                    "ask_user" -> !isComplex
                    "tap", "long_press", "visual_describe", "select_spec" -> !isVision
                    else -> true
                }
            }

        // 分组输出（定位与输入 → 导航与浏览 → 应用与等待 → 任务控制 → 信息查询）
        val groups = linkedMapOf(
            "定位与输入" to listOf("locate", "auto_input", "tap"),
            "导航与浏览" to listOf("swipe", "scroll_until", "back", "home", "long_press"),
            "应用与等待" to listOf("open_app", "wait"),
            "任务控制" to listOf("request_user_action", "finish", "ask_user", "select_spec"),
            "信息查询" to listOf("web_search", "fetch_result", "forget", "visual_describe")
        )

        for ((groupName, toolNames) in groups) {
            val groupTools = filtered.filter { it.getName() in toolNames }
            if (groupTools.isEmpty()) continue
            sb.appendLine()
            sb.appendLine("### $groupName")
            for (tool in groupTools) {
                sb.appendLine(buildToolDescriptionLine(tool))
            }
        }

        // 未分组的工具（兜底，一般不会触发）
        val groupedNames = groups.values.flatten().toSet()
        val ungrouped = filtered.filter { it.getName() !in groupedNames }
        if (ungrouped.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### 其他")
            for (tool in ungrouped) {
                sb.appendLine(buildToolDescriptionLine(tool))
            }
        }

        return sb.toString()
    }

    private fun buildToolDescriptionLine(tool: BaseTool): String {
        val sb = StringBuilder()
        sb.append("- ${tool.getName()}")

        val params = tool.getParametersWithWaitAfter()
            .filter { it.name != "wait_after" }
        if (params.isNotEmpty()) {
            sb.append(": ")
            sb.append(params.joinToString(", ") { param ->
                val req = if (param.isRequired) "必填" else "选填"
                val enumStr = param.enumValues?.let { ", options: ${it.joinToString("|")}" } ?: ""
                val defaultStr = param.default?.let { ", default: $it" } ?: ""
                "${param.name}($req)$enumStr$defaultStr"
            })
        }

        sb.append(" — ${tool.getDescriptionCN()}")
        return sb.toString()
    }

    @Deprecated("改用 getExecutionToolDescriptions()", ReplaceWith("getExecutionToolDescriptions()"))
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
