package com.palmagent.app.tool.impl

import com.palmagent.app.model.ToolCallResult
import com.palmagent.app.service.WebSearchService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

/**
 * 联网搜索工具（注册到 ToolRegistry，仅执行模型使用）。
 *
 * 工具名：web_search
 * 参数：query(必填) + count(可选, 默认5, 范围1-10)
 * 内部调用 WebSearchService.search()，字段映射：
 *   ToolCallResult.success → ToolResult.isSuccess
 *   ToolCallResult.content → ToolResult.data
 */
class WebSearchTool : BaseTool() {

    private val webSearchService get() = WebSearchService

    override fun getName(): String = "web_search"

    override fun getDisplayName(): String = "联网搜索"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            name = "query",
            type = "string",
            description = "搜索关键词",
            isRequired = true
        ),
        ToolParameter(
            name = "count",
            type = "integer",
            description = "返回结果数（默认5，最大10）",
            isRequired = false,
            default = 5,
            minValue = 1,
            maxValue = 10
        )
    )

    override fun getDescriptionEN(): String =
        "Search the web for real-time information, news, prices, weather, etc."

    override fun getDescriptionCN(): String =
        "联网搜索互联网最新信息、新闻、实时数据、价格、天气等。当用户问题涉及实时信息、近期事件时必须调用此工具。"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val query = requireString(params, "query")
        // BaseTool.optionalInt 已含下限保护（coerceAtLeast），此处追加 coerceIn 限制上限 10
        val count = optionalInt(params, "count", 5).coerceIn(1, 10)
        val result: ToolCallResult = webSearchService.search(query, count)
        return if (result.success) {
            ToolResult.success(result.content)
        } else {
            ToolResult.error(result.error ?: "搜索失败")
        }
    }
}
