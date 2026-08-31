package com.palmagent.app.tool.impl

import android.util.Log
import com.palmagent.app.AgentApplication
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import com.palmagent.app.utils.InstalledAppProvider

/**
 * 已安装应用列表查询工具
 *
 * 返回设备上已安装应用的完整列表（含系统应用，应用名+包名），供对话/规划模型查询设备实际安装的 App。
 * - 不传 keywords：返回全量列表（受 max_results 限制）
 * - 传 keywords：按应用名模糊过滤（如 keywords=["地图"] 匹配"高德地图"，keywords=["地图","支付"] 同时匹配多个）
 *
 * 设计目的：解决对话模型不知道设备装了哪些 App、瞎猜 App 名/包名的问题。
 * App 启动时由 AgentApplication.onCreate 预热 InstalledAppProvider 缓存，工具查询走缓存。
 */
class ListAppsTool : BaseTool() {

    companion object {
        private const val TAG = "ListAppsTool"
        private const val DEFAULT_MAX_RESULTS = 50
        private const val MIN_MAX_RESULTS = 1
        private const val MAX_MAX_RESULTS = 200
    }

    override fun getName(): String = "list_apps"
    // 对执行模型隐藏：open_app 的应用名由决策模型在 Plan 中注明（决策模型侧用 list_apps 核实过真实名），
    // 执行模型严格遵循 Plan 中的应用名即可，无需自行查询（避免执行模型口语化取名/忽略 Plan 真实名）
    override fun isExposedToExecutionModel(): Boolean = false

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "keywords",
            "array",
            "可选关键词数组，按应用名模糊过滤（如[\"支付宝\"]或[\"支付宝\", \"交管12123\"]）。不传则返回全量列表。",
            false
        ),
        ToolParameter(
            "max_results",
            "integer",
            "最多返回结果数，默认 ${DEFAULT_MAX_RESULTS}，范围 ${MIN_MAX_RESULTS}-${MAX_MAX_RESULTS}",
            false,
            default = DEFAULT_MAX_RESULTS,
            minValue = MIN_MAX_RESULTS,
            maxValue = MAX_MAX_RESULTS
        )
    )

    override fun getDescriptionEN(): String =
        "List installed apps on the device with their package names. " +
        "Optional keyword filters by app name (case-insensitive fuzzy match)."

    override fun getDescriptionCN(): String =
        "查询设备上已安装应用的应用名和包名映射。" +
        "支持 keywords（关键词数组）按应用名模糊过滤，不传则返回全量列表。"

    override fun getDisplayName(): String = "查询已装应用"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val keywordsList = when (val kw = params["keywords"]) {
            is List<*> -> kw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        val maxResults = optionalInt(params, "max_results", DEFAULT_MAX_RESULTS)
            .coerceIn(MIN_MAX_RESULTS, MAX_MAX_RESULTS)

        val context = AgentApplication.instance
        val allApps = try {
            InstalledAppProvider.getInstalledAppsList(context)
        } catch (e: Exception) {
            Log.e(TAG, "获取已装应用列表异常", e)
            return ToolResult.error(
                "获取已装应用列表异常: ${e.message}",
                errorType = "TRANSIENT",
                code = "LIST_APPS_FAILED",
                suggestion = "PackageManager 查询失败，可重试一次"
            )
        }

        if (allApps.isEmpty()) {
            return ToolResult.error(
                "设备未查询到已安装应用，或缓存未就绪（AgentApplication 启动预热未完成）",
                errorType = "SERVICE_UNAVAILABLE",
                failureCategory = "SERVICE_UNAVAILABLE",
                code = "APP_LIST_EMPTY",
                suggestion = "等待 App 启动预热完成，或检查无障碍/读取应用列表权限"
            )
        }

        val filtered = if (keywordsList.isEmpty()) {
            allApps
        } else {
            allApps.filter { app -> keywordsList.any { kw -> app.first.contains(kw, ignoreCase = true) } }
        }

        if (filtered.isEmpty()) {
            return ToolResult.success(
                "【设备已安装应用】共 ${allApps.size} 个已安装应用，但未找到匹配关键词的应用。\n" +
                "建议：换用更宽泛的关键词，或先调 list_apps() 不传 keywords 查看全量列表。"
            )
        }

        val top = filtered.take(maxResults)
        val output = buildString {
            appendLine("【设备已安装应用】（共 ${allApps.size} 个已安装应用，匹配 ${top.size} 个）")
            if (keywordsList.isNotEmpty()) appendLine("关键词: ${keywordsList.joinToString(", ")}")
            top.forEach { (name, pkg) -> appendLine("- $name → $pkg") }
        }

        Log.d(TAG, "查询: keywords=${keywordsList.joinToString(",")}, 总=${allApps.size}, 匹配=${filtered.size}, 返回=${top.size}")
        return ToolResult.success(output.trimEnd())
    }
}
