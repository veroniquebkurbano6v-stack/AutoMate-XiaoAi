package com.palmagent.app.tool.impl

import com.palmagent.app.model.ToolCallResult
import com.palmagent.app.service.ToolResultCache
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

/**
 * 取回缓存工具结果工具（注册到 ToolRegistry，执行模型 + 决策模型共用）。
 *
 * 工具名：fetch_result
 * 参数：ref（必填，如 "ws-3-2" / "fx-12345678"）
 * 从 ToolResultCache 按 ref 取回该条完整结果（全文，含结构化摘要），仅供本轮参考。
 */
class FetchResultTool : BaseTool() {

    private val fetchOutputMaxChars = 4000

    override fun getName(): String = "fetch_result"

    override fun getDisplayName(): String = "取回工具结果"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            name = "ref",
            type = "string",
            description = "缓存条目 ref（如 ws-3-2 / fx-12345678），见台账/搜索结果摘要中每项 [ref]",
            isRequired = true
        )
    )

    override fun getDescriptionEN(): String =
        "Fetch the full cached content of a previous tool result by ref. The fetched content is for current-round reference only and is not persisted to working memory."

    override fun getDescriptionCN(): String =
        "按 ref 取回之前缓存工具结果（list_apps/kb_read/amap_*/web_search 等）的完整内容（仅本轮参考，不写入工作记忆；需要保留的要点请自行提炼）。单次取回有上限（约4000字符），需要更多可多次调用。"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val ref = requireString(params, "ref").trim()
        if (ref.isEmpty()) {
            return ToolResult.error("ref 不能为空")
        }
        val cached = ToolResultCache.get(ref)
        if (cached == null) {
            return ToolResult.error("ref=$ref 不存在或已清理，请重新调用原工具")
        }
        val content = if (cached.ref.startsWith("ws-")) {
            // 结构化 web_search 条目（putSearch 写入的 ws-<round>-<n>）
            buildString {
                appendLine("【取回搜索结果 ${cached.ref}】${cached.title}")
                if (cached.url.isNotBlank()) appendLine("URL: ${cached.url}")
                if (cached.snippet.isNotBlank()) appendLine("片段: ${cached.snippet}")
                if (!cached.summary.isNullOrBlank()) {
                    val cut = if (cached.summary.length > 800) "${cached.summary.take(800)}…" else cached.summary
                    appendLine("原文摘要: $cut")
                }
            }
        } else {
            buildString {
                appendLine("【取回工具结果 ${cached.ref}】（${cached.tool}）")
                append(cached.content.take(fetchOutputMaxChars))
                if (cached.content.length > fetchOutputMaxChars) appendLine("\n…（内容过长，已截取）")
            }
        }
        return ToolResult.success(content)
    }
}
