package com.palmagent.app.tool.impl

import android.util.Log
import com.palmagent.app.kb.LocalKbEngine
import com.palmagent.app.utils.KVUtils
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult

/**
 * 知识库查询工具（纯端侧，无 HTTP 回退）
 *
 * 调用 LocalKbEngine（bge-small-zh INT8 ONNX + SQLite BLOB 向量 + 内存检索），
 * 离线可用，端到端 <80ms，无 PC/Tailscale 依赖。
 *
 * 引擎在 App 启动时由 AgentApplication 初始化；未初始化时返回错误。
 */
class KbReadTool : BaseTool() {

    companion object {
        private const val TAG = "KbReadTool"
        private const val DEFAULT_TOP_K = 3
        private const val MIN_TOP_K = 1
        private const val MAX_TOP_K = 5
        private const val SCORE_THRESHOLD = 0.5
    }

    override fun getName(): String = "kb_read"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "query",
            "string",
            "检索关键词或问题，用于匹配知识库中的操作手册/SOP片段",
            true
        ),
        ToolParameter(
            "top_k",
            "integer",
            "返回结果数，默认3，范围1-5",
            false,
            default = DEFAULT_TOP_K,
            minValue = MIN_TOP_K,
            maxValue = MAX_TOP_K
        ),
        ToolParameter(
            "app_filter",
            "string",
            "可选，按App过滤检索范围，如\"微信\"、\"高德地图\"；不传时全量检索",
            false
        )
    )

    override fun getDescriptionEN(): String =
        "Query the on-device knowledge base to retrieve operation manual / SOP snippets " +
        "relevant to the query. Use when the model needs background knowledge to perform a task."

    override fun getDescriptionCN(): String =
        "查询本地知识库，检索与问题相关的操作手册/SOP片段。当任务需要先验知识（如某App的特定操作步骤）时调用。"

    override fun getDisplayName(): String = "知识库查询"

    /** 判定检索结果是否可用的纯函数：score >= SCORE_THRESHOLD 才视为相关（便于单测） */
    internal fun isScoreUsable(score: Double): Boolean = score >= SCORE_THRESHOLD

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        if (!KVUtils.isLocalKbEnabled()) {
            return ToolResult.error("本地知识库未启用，请在设置中开启")
        }

        val query = requireString(params, "query").trim()
        if (query.isBlank()) {
            return ToolResult.error("query 不能为空")
        }
        val topK = optionalInt(params, "top_k", DEFAULT_TOP_K).coerceIn(MIN_TOP_K, MAX_TOP_K)
        val appFilter = (params["app_filter"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val engine = LocalKbEngine.get()
            ?: return ToolResult.error(
                "端侧知识库引擎未初始化",
                errorType = "FATAL",
                failureCategory = "SERVICE_UNAVAILABLE",
                code = "KB_ENGINE_UNAVAILABLE",
                suggestion = "请重启 App，或检查 assets/kb/ 资源是否完整"
            )

        return try {
            val results = engine.search(query, appFilter, topK)
            if (results.isEmpty()) {
                Log.d(TAG, "查询 '$query' 无结果")
                return ToolResult.error("知识库检索无结果，请尝试更换关键词")
            }

            // 只取 score 最高的一条交付给决策模型（relevance density 原则，控上下文）
            val best = results.maxByOrNull { it.score }!!
            val output = buildString {
                if (!isScoreUsable(best.score)) {
                    appendLine("【知识库检索结果】共 ${results.size} 条，但最高相似度仅 ${"%.2f".format(best.score)}（< ${"%.1f".format(SCORE_THRESHOLD)}），无足够相关的 SOP")
                    appendLine("请基于通用操作常识生成 Plan，不要强行套用知识库内容。")
                } else {
                    appendLine("【知识库检索结果】最佳匹配 1 条（共检索 ${results.size} 条）")
                    appendLine("来源: ${best.appName} (相似度: ${"%.2f".format(best.score)})")
                    appendLine("摘要: ${best.taskName}")
                    if (best.steps.isNotEmpty()) {
                        appendLine("步骤:")
                        for (st in best.steps) {
                            appendLine("  ${st.stepOrder}. [${st.actionType}] ${st.goal}")
                            if (st.expected.isNotEmpty()) {
                                appendLine("     预期: ${st.expected}")
                            }
                        }
                    }
                }
            }
            ToolResult.success(output.trimEnd())
        } catch (e: Exception) {
            Log.e(TAG, "端侧知识库检索异常", e)
            ToolResult.error(
                "知识库检索异常: ${e.message}",
                errorType = "TRANSIENT",
                failureCategory = "SERVICE_UNAVAILABLE",
                code = "KB_READ_FAILED"
            )
        }
    }
}
