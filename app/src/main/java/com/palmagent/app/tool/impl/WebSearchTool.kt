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
        ),
        ToolParameter(
            name = "mode",
            type = "string",
            description = "检索模式：web=网页检索（默认，成本低）；ai=AI聚合答案+引用来源（需要直接结论如'如何挂号'时用，成本更高）",
            isRequired = false,
            default = "web",
            enumValues = listOf("web", "ai")
        )
    )

    override fun getDescriptionEN(): String =
        "Search the web for real-time information, news, prices, weather, etc."

    override fun getDescriptionCN(): String =
        "联网搜索互联网最新信息、新闻、实时数据、价格、天气等。当用户问题涉及实时信息、近期事件时必须调用此工具。" +
            "默认 web 网页检索；需要直接结论/操作步骤（如医院挂号方式、事实问答）时用 mode=ai 获取 AI 聚合答案。" +
            "红线：医院、机构等信息必须基于检索到的真实来源，禁止编造挂号入口、电话、地址。"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val query = requireString(params, "query")
        // BaseTool.optionalInt 已含下限保护（coerceAtLeast），此处追加 coerceIn 限制上限 10
        val count = optionalInt(params, "count", 5).coerceIn(1, 10)
        // 宽松解析模式：默认 web，非法值回退 web（工具执行失败降级由 WebSearchService 内部处理）
        val mode = optionalString(params, "mode", "web").lowercase()
            .takeIf { it in setOf("web", "ai") } ?: "web"
        val result: ToolCallResult = webSearchService.search(query, count, mode)
        return if (result.success) {
            ToolResult.success(result.content)
        } else {
            ToolResult.error(result.error ?: "搜索失败")
        }
    }
}
