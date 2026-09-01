package com.palmagent.app.tool

import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.tool.impl.*

object ToolRegistry {

    private const val TAG = "ToolRegistry"

    private val tools = LinkedHashMap<String, BaseTool>()

    /** 所有工具类列表，新增工具只需在此添加一行 */
    private val toolClasses: List<Pair<String, () -> BaseTool>> = listOf(
        "tap" to ::TapTool,
        "long_press" to ::LongPressTool,
        "swipe" to ::SwipeTool,
        "swipe_until" to ::SwipeUntilTool,
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
     * 执行模型可见的工具清单（单一事实源）。
     *
     * 过滤掉 isExposedToExecutionModel() = false 的内部工具（高德 MCP 工具 / list_apps /
     * get_screen_info）。PromptBuilder 的工具描述（getExecutionToolDescriptions）与未来
     * 检索链路（语义候选 / 向量库）都应基于本入口，保证"提示词即工具清单"各方一致。
     */
    fun getExecutionTools(): List<BaseTool> = tools.values
        .filter { it.isExposedToExecutionModel() }

    /**
     * 获取执行模型可见的工具描述文本（动态生成，替代 PromptBuilder 中的硬编码）
     * @param isVision 视觉模式（精简部分不适合视觉模型使用的工具）
     * @param isComplex 复杂模式（隐藏 ask_user 工具）
     */
    fun getExecutionToolDescriptions(isVision: Boolean, isComplex: Boolean, hideVisionUnused: Boolean = false): String {
        val sb = StringBuilder()
        val filtered = getExecutionTools()
            .filter { tool ->
                when {
                    // 视觉执行模式隐藏 locate/web_search/visual_describe/scroll_until/select_spec/swipe_until：减轻 VL 模型上下文压力
                    // （VL 自己看图滑动；目标驱动滑动工具仅文本执行模型使用）
                    hideVisionUnused && (tool.getName() == "locate" || tool.getName() == "web_search" ||
                        tool.getName() == "visual_describe" ||
                        tool.getName() == "scroll_until" || tool.getName() == "select_spec" ||
                        tool.getName() == "swipe_until") -> false
                    else -> when (tool.getName()) {
                        "tap", "visual_describe", "select_spec" -> !isVision
                        else -> true
                    }
                }
            }

        // 分组输出（定位与输入 → 导航与浏览 → 应用与等待 → 任务控制 → 信息查询）
        val groups = linkedMapOf(
            "定位与输入" to listOf("locate", "auto_input", "tap"),
            "导航与浏览" to listOf("swipe_until", "scroll_until", "back", "home"),
            "应用与等待" to listOf("open_app", "wait"),
            "任务控制" to listOf("request_user_action", "finish", "select_spec"),
            "信息查询" to listOf("web_search", "visual_describe")
        )

        // 虚拟协议工具：不在 ToolRegistry 注册，由 ActionParser / ActionExecutor /
        // ToolDecisionEngine 直接处理（与 PromptBuilderToolConsistencyTest 白名单一致）。
        // ask_user 仅简单模式注入；fetch_result / forget 恒注入（web_search 配套协议）。
        val virtualByGroup = mapOf(
            "任务控制" to buildList {
                if (!isComplex) add(ASK_USER_DESCRIPTION)
            },
            "信息查询" to buildList {
                // web_search 配套协议（fetch_result/forget）：视觉模式隐藏 web_search 时一并隐藏
                if (!hideVisionUnused) {
                    add(FETCH_RESULT_DESCRIPTION)
                    add(FORGET_DESCRIPTION)
                }
            }
        )

        for ((groupName, toolNames) in groups) {
            val groupTools = filtered.filter { it.getName() in toolNames }
            val virtualTools = virtualByGroup[groupName].orEmpty()
            if (groupTools.isEmpty() && virtualTools.isEmpty()) continue
            sb.appendLine()
            sb.appendLine("### $groupName")
            for (tool in groupTools) {
                sb.appendLine(buildToolDescriptionLine(tool))
            }
            virtualTools.forEach { sb.appendLine(it) }
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

        val result = sb.toString()
        // 真机调试观测点：工具描述区是执行模型的"动作空间"契约，打印全文（首次/内容变化时）
        // 便于 logcat 核对协议参数名、虚拟工具注入与隐藏工具过滤是否符合预期
        val signature = "${isVision}|${isComplex}|${result.hashCode()}"
        if (signature != lastToolDescSignature) {
            lastToolDescSignature = signature
            Log.d(TAG, "exec_tool_desc[$signature]\n$result")
            LiveLogBuffer.append("🛠 执行工具描述区(${if (isVision) "VL" else "TEXT"},${if (isComplex) "复杂" else "简单"}): ${result.lineSequence().count { "^- " in it }} 个工具")
        }
        return result
    }

    private var lastToolDescSignature: String? = null

    /**
     * 虚拟协议工具描述：ask_user / fetch_result / forget 不在 ToolRegistry 注册，
     * 由 ActionParser / ActionExecutor / ToolDecisionEngine 直接处理（白名单）。
     * 为保证"提示词即工具清单"，在分组输出时按协议注入（与 PromptBuilderToolConsistencyTest
     * 的 handledElsewhere 白名单一致）。
     */
    private val ASK_USER_DESCRIPTION =
        "- ask_user: questions(必填数组,1-4问,每问2-6选项,label与问题同任务语言,UI自动追加\"其他\"勿生成) — " +
            "仅缺必要信息时一次性追问。规则：multiSelect=true可叠加/否则互斥单选；recommended最多1个。红线：" +
            "已确认或屏幕/搜索可推断的不问，主观偏好用默认值，已问不重复问。误用 text/options 旧字段一律降级为 wait"

    private val FETCH_RESULT_DESCRIPTION =
        "- fetch_result: text(必填,ref如\"ws-3-2\"/\"fx-...\") — 按 ref 取回之前缓存的完整工具结果（搜索/工具），" +
            "仅供本轮参考，不写入工作记忆；需要保留的要点请自行提炼写入工作记忆"

    private val FORGET_DESCRIPTION =
        "- forget: text(必填,条目ID如\"sp-3-1\"或关键词) — 删除不再需要的工作记忆条目"

    /**
     * 面向执行模型的协议参数说明（优先表）。
     *
     * 提示词中模型看到的参数名是"协议参数名"（text / description / coordinate），
     * 与 ToolParameter.name（底层执行参数名，如 app_name / query / title）不同，
     * 两者由 ActionExecutor.buildActionParams 完成映射（如 open_app: text → app_name）。
     * 因此工具描述必须展示协议参数名 + 参数语义，模型才能按契约输出 JSON。
     * 未在此表中的工具回退用 ToolParameter 生成（name + description，均含语义）。
     */
    private val executionModelParamProtocol: Map<String, String> = mapOf(
        "auto_input" to "text(必填,输入文本), is_text_input_box(选填,布尔\"true\"=文本输入框,\"false\"=搜索图标；不填跳过定位)",
        "locate" to "description(必填,功能+图标特征+区域), text(必填,要定位的文字)",
        "tap" to "coordinate(必填,[x,y]数组如[976,2376]), description(必填)",
        "open_app" to "text(必填,应用中文名或包名)",
        "wait" to "description(必填), duration_ms(可选,默认1000,范围100-10000)",
        "request_user_action" to "text(必填,标题), description(选填)",
        "finish" to "description(必填,已完成摘要), text(必填,用户接下来做什么)",
        "web_search" to "query(必填,搜索关键词), mode(选填,web/ai,默认web)：web=网页检索(成本低)；ai=AI聚合答案+引用来源(需直接结论如'如何挂号'时用)",
        "visual_describe" to "text(问题)",
        "select_spec" to "specs(必填,需选取的规格数组如[\"大份\",\"微辣\",\"去冰\"]), confirm_text(选填,确认按钮文本,默认\"选好了\")",
        "scroll_until" to "target(必填,视觉可辨识描述), direction(选填,默认down), max_scrolls(选填,默认5,上限10), click_on_found(选填,默认true)",
        "swipe_until" to "target(必填,目标可见文本如'22:00'), container(选填,容器名——从【可横向滑动容器】/【可竖向滚动容器】段选取), max_swipes(选填,默认5,上限10)——工具自动滑动直到目标可见：横向容器左滑/竖向容器上滑（无容器默认横向左滑），滑动后界面签名连续2次无变化自动换反向试错；滑动前后自动检查目标可见性（可见即停；目标已可见会自动定位点击）；模型不控制滑动方向",
    )

    private fun buildToolDescriptionLine(tool: BaseTool): String {
        val name = tool.getName()
        val paramText = executionModelParamProtocol[name]
            ?: buildParamsFromToolParameters(tool)
        return "- $name: $paramText — ${tool.getDescriptionCN()}"
    }

    /** 未在协议优先表中的工具：从 ToolParameter 生成 "name(必填/选填,描述)"，保留参数语义 */
    private fun buildParamsFromToolParameters(tool: BaseTool): String {
        val params = tool.getParametersWithWaitAfter()
            .filter { it.name != "wait_after" }
        if (params.isEmpty()) return ""
        return params.joinToString(", ") { param ->
            val req = if (param.isRequired) "必填" else "选填"
            val desc = param.description.takeIf { it.isNotBlank() }?.let { ",$it" } ?: ""
            val enumStr = param.enumValues?.let { ", options: ${it.joinToString("|")}" } ?: ""
            val defaultStr = param.default?.let { ", default: $it" } ?: ""
            "${param.name}($req$desc)$enumStr$defaultStr"
        }
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
