package com.palmagent.app.service

import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.agent.AgentLogger
import com.palmagent.app.agent.ScratchpadEntry
import com.palmagent.app.model.AgentAction
import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.model.ToolCallResult
import com.palmagent.app.utils.recycleSafely
import com.palmagent.app.LiveLogBuffer

class ToolDecisionEngine(
    private val aiService: AIService,
    private val log: (String) -> Unit
) {
    companion object {
        private const val TAG = "ToolDecision"
        /** 方案 B：同轮相同调用（同 tool+参数）拦截阈值，达到后强制换策略、不执行该调用 */
        private const val MAX_SAME_CALL = 3
        /** 方案 B：工具连续失败熔断阈值，达到后停止工具循环（防重试风暴烧 token） */
        private const val MAX_CONSECUTIVE_FAILURES = 3
        /** fetch_result 单次取回显示上限（字符），通用 fx- 条目按此截断 */
        private const val FETCH_OUTPUT_MAX_CHARS = 4000
    }

    suspend fun executeWithTools(
        userRequest: String,
        screenInfo: ScreenInfo?,
        initialKnowledgeContext: String = "",
        isCancelled: () -> Boolean = { false },
        round: Int = 0
    ): DecisionResult {
        log("正在请求AI决策...")

        // 收集 Scratchpad 条目（web_search 动作触发时写入）
        val scratchpadEntries = mutableListOf<ScratchpadEntry>()

        var action = aiService.generateAction(
            userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = initialKnowledgeContext,
        )

        log("AI决策：${action.type} - ${action.description}")

        val toolResults = mutableListOf<ToolCallResult>()
        var contextFromTools = initialKnowledgeContext
        var loopCount = 0
        // P2-6 修复：工具循环加 30s 整体 deadline，防止 5×15s=75s 超时
        val loopStartTime = System.currentTimeMillis()
        // 方案 B：同轮相同调用计数器（"tool:参数"签名 → 次数），连续失败计数器
        val callCounters = mutableMapOf<String, Int>()
        var consecutiveFailures = 0

        // 方案 B②：工具失败熔断辅助——连续失败达到阈值时返回 true，调用方 break 停止工具循环
        // （防重试风暴：连续失败且无成功意味着模型策略失效，继续重试只会烧 token）
        fun recordToolFailure(reason: String): Boolean {
            consecutiveFailures++
            log("工具调用失败（$reason），连续第 $consecutiveFailures 次")
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                log("【熔断】工具连续失败 $consecutiveFailures 次，停止工具循环")
                LiveLogBuffer.append("🛑 工具连续失败 $consecutiveFailures 次，系统熔断停止本轮工具调用")
                return true
            }
            return false
        }

        while (loopCount < 5 && !isCancelled() &&
            System.currentTimeMillis() - loopStartTime < 30_000L) {
            loopCount++

            when (action.type) {
                "web_search" -> {
                    val query = action.text?.takeIf { it.isNotBlank() } ?: action.description
                    if (query.isBlank()) {
                        log("WEB_SEARCH 缺少 query 参数，跳过")
                        toolResults.add(ToolCallResult(
                            toolName = "web_search",
                            success = false,
                            error = "query 参数为空"
                        ))
                        return DecisionResult(
                            finalAction = action,
                            toolResults = toolResults,
                            combinedContext = contextFromTools,
                            scratchpadEntries = scratchpadEntries
                        )
                    }

                    // 方案 B①：相同调用拦截——同轮相同 (tool, 参数) 达到阈值时
                    // 不执行搜索，注入强制换策略提示后重新请求 AI 决策，避免重试风暴烧 token
                    val callSig = "web_search:$query"
                    val sameCallCount = (callCounters[callSig] ?: 0) + 1
                    callCounters[callSig] = sameCallCount
                    if (sameCallCount >= MAX_SAME_CALL) {
                        log("【熔断】相同搜索调用第${sameCallCount}次: $callSig，强制换策略")
                        LiveLogBuffer.append("🛑 相同搜索调用 $sameCallCount 次，系统拦截强制换策略")
                        toolResults.add(ToolCallResult(
                            toolName = "web_search",
                            success = false,
                            error = "系统拦截：相同调用 $sameCallCount 次"
                        ))
                        contextFromTools = buildString {
                            if (contextFromTools.isNotBlank()) {
                                appendLine(contextFromTools)
                                appendLine()
                            }
                            appendLine("【系统拦截】你已连续 $sameCallCount 次发起完全相同的调用（$callSig），结果不会改变。")
                            appendLine("请立即更换策略：换一个不同的搜索关键词，或根据当前屏幕信息直接输出最终动作（如 FINISH/WAIT）。禁止重复相同调用。")
                        }
                        logToolLoopModelInput(userRequest, contextFromTools, round, loopCount)
                        action = aiService.generateAction(
                            userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = contextFromTools,
                        )
                        continue
                    }

                    log("AI请求联网搜索: ${query.take(80)}")
                    LiveLogBuffer.append("🔍 模型请求联网搜索: ${query.take(80)}")

                    val searchResult = WebSearchService.searchWithCache(query, count = 5, round = round)

                    toolResults.add(ToolCallResult(
                        toolName = "web_search",
                        success = searchResult.success,
                        content = searchResult.summaryText,
                        error = searchResult.error,
                        durationMs = searchResult.durationMs
                    ))

                    if (searchResult.success) {
                        consecutiveFailures = 0
                        log("搜索成功（${if (searchResult.hitCache) "缓存命中" else "已缓存"}）: ${searchResult.summaryText.take(80)}，重新请求AI决策...")
                        LiveLogBuffer.append(
                            if (searchResult.hitCache) "↩️ 搜索缓存命中(round ${searchResult.hitRound})，摘要已注入本轮"
                            else "✓ 搜索完成，结果已缓存，可按 ref 取回原文"
                        )
                    } else {
                        log("搜索失败: ${searchResult.error}")
                        LiveLogBuffer.append("❌ 搜索失败: ${searchResult.error}")
                        // 方案 B②：连续失败熔断——达到阈值直接退出工具循环
                        if (recordToolFailure("web_search: ${searchResult.error}")) break
                    }

                    // 摘要仅本轮注入（模型看后判断需取回的 ref；不写工作区，即看即弃）
                    val combined = buildString {
                        if (contextFromTools.isNotBlank()) {
                            appendLine(contextFromTools)
                            appendLine()
                        }
                        if (searchResult.success) {
                            appendLine(searchResult.summaryText)
                            appendLine("请判断哪些搜索结果与任务相关：需要查看某条完整内容时输出 fetch_result(ref)；无关信息不必保留。")
                        } else {
                            appendLine("【联网搜索】搜索失败：${searchResult.error}")
                            appendLine("请根据当前屏幕信息自行判断下一步操作。")
                        }
                    }
                    contextFromTools = combined

                    logToolLoopModelInput(userRequest, combined, round, loopCount)
                    action = aiService.generateAction(
                        userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = combined,
                    )
                }

                "fetch_result", "web_search_fetch" -> {
                    val ref = action.text?.takeIf { it.isNotBlank() } ?: action.description
                    if (ref.isBlank()) {
                        log("FETCH_RESULT 缺少 ref 参数，跳过")
                        toolResults.add(ToolCallResult(
                            toolName = "fetch_result",
                            success = false,
                            error = "ref 参数为空"
                        ))
                        return DecisionResult(
                            finalAction = action,
                            toolResults = toolResults,
                            combinedContext = contextFromTools,
                            scratchpadEntries = scratchpadEntries
                        )
                    }

                    log("AI请求取回缓存工具结果: $ref")
                    LiveLogBuffer.append("📄 模型取回缓存工具结果: ${ref.take(40)}")

                    val cached = ToolResultCache.get(ref)
                    val fetchContent = if (cached != null) {
                        if (cached.ref.startsWith("ws-")) {
                            // 结构化 web_search 条目：渲染 search 专属字段
                            buildString {
                                appendLine("【取回搜索结果 ${cached.ref}】${cached.title}")
                                if (cached.url.isNotBlank()) appendLine("URL: ${cached.url}")
                                if (cached.snippet.isNotBlank()) appendLine("片段: ${cached.snippet}")
                                if (!cached.summary.isNullOrBlank()) {
                                    val cut = if (cached.summary.length > 800) "${cached.summary.take(800)}…" else cached.summary
                                    appendLine("原文摘要: $cut")
                                }
                                appendLine("（本内容仅供本轮参考，不写入工作记忆；需要保留的要点请自行提炼）")
                            }
                        } else {
                            // 通用 fx- 条目：渲染实际缓存内容（含截断提示，执行流无 offset 续取，引导重调原工具获取完整内容）
                            buildString {
                                appendLine("【取回工具结果 ${cached.ref}】（${cached.tool}）")
                                append(cached.content.take(FETCH_OUTPUT_MAX_CHARS))
                                if (cached.content.length > FETCH_OUTPUT_MAX_CHARS) {
                                    appendLine("\n…（执行流仅展示前 ${FETCH_OUTPUT_MAX_CHARS} 字符，完整内容请重新调用原工具/重新搜索获取）")
                                }
                                appendLine("（本内容仅供本轮参考，不写入工作记忆；需要保留的要点请自行提炼）")
                            }
                        }
                    } else {
                        "【取回失败】ref=$ref 不存在或已清理，请重新搜索"
                    }

                    toolResults.add(ToolCallResult(
                        toolName = "fetch_result",
                        success = cached != null,
                        content = fetchContent,
                        error = if (cached != null) null else "取回失败: $ref",
                        durationMs = 0
                    ))
                    if (cached != null) {
                        consecutiveFailures = 0
                        log("取回成功: ${cached.ref}，重新请求AI决策...")
                        LiveLogBuffer.append("✓ 已取回 ${cached.ref} 原文")
                    } else {
                        log("取回失败: $ref")
                        LiveLogBuffer.append("❌ 取回失败: $ref")
                        if (recordToolFailure("fetch_result: $ref")) break
                    }

                    // 取回原文仅本轮注入，不写工作区
                    val combined = buildString {
                        if (contextFromTools.isNotBlank()) {
                            appendLine(contextFromTools)
                            appendLine()
                        }
                        appendLine(fetchContent)
                    }
                    contextFromTools = combined

                    logToolLoopModelInput(userRequest, combined, round, loopCount)
                    action = aiService.generateAction(
                        userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = combined,
                    )
                }

                "visual_describe" -> {
                    val question = action.text?.takeIf { it.isNotBlank() } ?: action.description

                    // 方案 B①：相同调用拦截——同轮相同 (tool, 参数) 达到阈值时
                    // 不执行 VLM 调用，注入强制换策略提示后重新请求 AI 决策
                    val callSig = "visual_describe:$question"
                    val sameCallCount = (callCounters[callSig] ?: 0) + 1
                    callCounters[callSig] = sameCallCount
                    if (sameCallCount >= MAX_SAME_CALL) {
                        log("【熔断】相同视觉描述调用第${sameCallCount}次: $callSig，强制换策略")
                        LiveLogBuffer.append("🛑 相同视觉描述调用 $sameCallCount 次，系统拦截强制换策略")
                        toolResults.add(ToolCallResult(
                            toolName = "visual_describe",
                            success = false,
                            error = "系统拦截：相同调用 $sameCallCount 次"
                        ))
                        contextFromTools = buildString {
                            if (contextFromTools.isNotBlank()) {
                                appendLine(contextFromTools)
                                appendLine()
                            }
                            appendLine("【系统拦截】你已连续 $sameCallCount 次发起完全相同的调用（$callSig），结果不会改变。")
                            appendLine("请立即更换策略：换一个问题，或根据当前屏幕信息直接输出最终动作（如 FINISH/WAIT）。禁止重复相同调用。")
                        }
                        logToolLoopModelInput(userRequest, contextFromTools, round, loopCount)
                        action = aiService.generateAction(
                            userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = contextFromTools,
                        )
                        continue
                    }

                    log("AI请求视觉描述: ${question.take(80)}")
                    LiveLogBuffer.append("👁 模型请求视觉描述: ${question.take(80)}")

                    val a11y = GUIAccessibilityService.instance
                    val screenshotBmp: Bitmap? = a11y?.takeScreenshot()

                    if (screenshotBmp != null) {
                        try {
                            val vlmResult = GuiOwlService.ask(screenshotBmp, question)
                                ?: GuiOwlService.VlmResult(success = false, error = "GUI-Plus 未返回结果")

                            AgentLogger.log(AgentLogger.LogType.GUI_PLUS_GROUNDING,
                                "VLM视觉描述: ${question.take(100)}",
                                vlmResult.answer.take(500), 0)

                            if (vlmResult.success) {
                                consecutiveFailures = 0
                                val resultContent = buildString {
                                    appendLine("视觉描述结果:")
                                    appendLine("  问题: $question")
                                    appendLine("  回答: ${vlmResult.answer}")
                                    appendLine("  耗时: ${vlmResult.durationMs}ms")
                                }

                                toolResults.add(ToolCallResult(
                                    toolName = "visual_describe",
                                    success = true,
                                    content = resultContent,
                                    durationMs = vlmResult.durationMs
                                ))

                                val combined = buildString {
                                    if (contextFromTools.isNotBlank()) {
                                        appendLine(contextFromTools)
                                        appendLine()
                                    }
                                    appendLine(resultContent)
                                }

                                log("视觉描述成功: ${vlmResult.answer.take(100)}，重新请求AI决策...")
                                LiveLogBuffer.append("✓ 视觉描述成功: ${vlmResult.answer.take(80)}")

                                logToolLoopModelInput(userRequest, combined, round, loopCount)
                                action = aiService.generateAction(
                                    userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = combined,
                                )
                                contextFromTools = combined
                            } else {
                                val errorMsg = vlmResult.error ?: "描述失败"
                                log("视觉描述失败: $errorMsg")
                                LiveLogBuffer.append("❌ 视觉描述失败: $errorMsg")
                                // 方案 B②：连续失败熔断——达到阈值直接退出工具循环
                                if (recordToolFailure("visual_describe: $errorMsg")) break

                                toolResults.add(ToolCallResult(
                                    toolName = "visual_describe",
                                    success = false,
                                    error = errorMsg,
                                    durationMs = vlmResult.durationMs
                                ))

                                val fallbackContext = buildString {
                                    if (contextFromTools.isNotBlank()) {
                                        appendLine(contextFromTools)
                                        appendLine()
                                    }
                                    appendLine("视觉描述失败（$errorMsg），请根据当前屏幕信息自行判断下一步操作。")
                                }
                                logToolLoopModelInput(userRequest, fallbackContext, round, loopCount)
                                action = aiService.generateAction(
                                    userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = fallbackContext,
                                )
                                contextFromTools = fallbackContext
                            }
                        } finally {
                            screenshotBmp.recycleSafely()
                        }
                    } else {
                        log("无法截图，视觉描述不可用")
                        LiveLogBuffer.append("❌ 视觉描述不可用: 无法获取截图")
                        // 方案 B②：连续失败熔断——达到阈值直接退出工具循环
                        if (recordToolFailure("visual_describe: 无法截图")) break

                        toolResults.add(ToolCallResult(
                            toolName = "visual_describe",
                            success = false,
                            error = "无法获取屏幕截图"
                        ))

                        val fallbackContext = buildString {
                            if (contextFromTools.isNotBlank()) {
                                appendLine(contextFromTools)
                                appendLine()
                            }
                            appendLine("视觉描述不可用（无法截图），请根据当前屏幕信息自行判断。")
                        }
                        logToolLoopModelInput(userRequest, fallbackContext, round, loopCount)
                        action = aiService.generateAction(
                            userRequest = userRequest,
            screenInfo = screenInfo,
            knowledgeContext = fallbackContext,
                        )
                        contextFromTools = fallbackContext
                    }
                }

                else -> return DecisionResult(
                    finalAction = action,
                    toolResults = toolResults,
                    combinedContext = contextFromTools,
                    scratchpadEntries = scratchpadEntries
                )
            }

            log("AI最新决策：${action.type} - ${action.description}")
        }

        return DecisionResult(
            finalAction = action,
            toolResults = toolResults,
            combinedContext = contextFromTools,
            scratchpadEntries = scratchpadEntries
        )
    }

    data class DecisionResult(
        val finalAction: AgentAction,
        val toolResults: List<ToolCallResult>,
        val combinedContext: String,
        val scratchpadEntries: List<ScratchpadEntry> = emptyList()
    )

    /**
     * 记录工具循环中的 LLM 输入到独立文件
     * 文件名: round_N_model_input_tool_M.txt（M 是工具循环序号，从 1 开始）
     * 触发场景: VISUAL_DESCRIBE 等工具调用后重新请求 AI 决策
     */
    private fun logToolLoopModelInput(
        userRequest: String,
        knowledgeContext: String,
        round: Int,
        attempt: Int
    ) {
        if (round <= 0) return  // round 未传时不记录
        val fullPrompt = PromptBuilder.buildPrompt(userRequest, knowledgeContext = knowledgeContext)
        AgentLogger.logToolLoopModelInput(fullPrompt, round, attempt)
    }
}
