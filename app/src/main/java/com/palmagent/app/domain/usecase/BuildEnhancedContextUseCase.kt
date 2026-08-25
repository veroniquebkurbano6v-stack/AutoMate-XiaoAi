package com.palmagent.app.domain.usecase

import android.graphics.Bitmap
import com.palmagent.app.agent.AgentConfig
import com.palmagent.app.agent.ContextManager
import com.palmagent.app.agent.Plan
import com.palmagent.app.agent.PlanFormatter
import com.palmagent.app.agent.ScratchpadEntry
import com.palmagent.app.agent.ScreenDescriptor
import com.palmagent.app.agent.TaskProgressTracker
import com.palmagent.app.model.ActionRecord
import com.palmagent.app.model.TaskProgress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上下文组装 Use Case
 *
 * 将设备上下文、OCR 文本、屏幕描述、状态警告、进度信息
 * 组装为发送给 AI 的增强上下文。
 */
@Singleton
class BuildEnhancedContextUseCase @Inject constructor(
    private val progressTracker: TaskProgressTracker
) {
    data class Params(
        val deviceCtx: String,
        val screenText: String,
        val autoScreenDescription: String,
        val stateWarning: String,
        val isTreeEmpty: Boolean,
        val actionHistory: List<ActionRecord>,
        val waitConsecutiveCount: Int,
        val config: AgentConfig,
        val planContext: Plan? = null,
        val compactedSummary: String = "",
        /** 失败信息压缩摘要（FailureCompactor 产出，注入最近操作回顾之前） */
        val failureSummary: String = "",
        val llmProgress: TaskProgress? = null,
        val scratchpad: List<ScratchpadEntry> = emptyList()
    )

    suspend operator fun invoke(params: Params): String {
        val assembled = ContextManager.assemble(
            deviceCtx = params.deviceCtx,
            screenText = params.screenText,
            actionHistory = params.actionHistory,
            isTreeEmpty = params.isTreeEmpty,
            waitConsecutiveCount = params.waitConsecutiveCount,
            maxTokens = params.config.contextMaxTokens,
            keepRecentRounds = params.config.contextKeepRecentRounds
        )

        var enhancedContext = assembled.text

        // 注入决策模型输出的结构化 plan（由 PlanFormatter 格式化为文本，直接传给执行模型消费）
        // 双注入修复：plan 由调用方单独传入 planContext（复杂模式），经【决策模型任务计划】区域注入一次；
        // userPrompt 仅承载用户需求，二者内容不同，不会重复
        if (params.planContext != null) {
            enhancedContext = buildString {
                appendLine(PlanFormatter.format(params.planContext))
                appendLine()
                append(enhancedContext)
            }
        }

        // v9.1: 注入 Running Summary（早期操作历史的压缩摘要）
        // 位于 planContext 之后、最近操作历史之前，让模型知道之前做了什么但不会看到完整历史
        if (params.compactedSummary.isNotBlank()) {
            enhancedContext = buildString {
                appendLine("【历史摘要】${params.compactedSummary}")
                appendLine()
                append(enhancedContext)
            }
        }

        // FailureCompactor: 注入失败信息压缩摘要（位于最近操作回顾之前，
        // 让模型知道之前失败过什么、建议怎么改，避免重复犯错）
        if (params.failureSummary.isNotBlank()) {
            enhancedContext = buildString {
                appendLine("【失败处理摘要】${params.failureSummary}")
                appendLine()
                append(enhancedContext)
            }
        }

        if (params.autoScreenDescription.isNotBlank()) {
            enhancedContext = buildString {
                append(params.autoScreenDescription)
                appendLine()
                append(enhancedContext)
            }
        }

        if (params.stateWarning.isNotBlank()) {
            enhancedContext = buildString {
                append(enhancedContext)
                appendLine()
                appendLine(params.stateWarning)
            }
        }

        // 注入 LLM 自管理的任务进度（始终注入，跨轮持久化）
        // v8 修复：移除 planContext != null 互斥条件，让执行模型每轮都能看到自己上一轮的 progress 输出
        val llmProgressCtx = formatLlmProgress(params.llmProgress)
        if (llmProgressCtx.isNotBlank()) {
            enhancedContext = buildString {
                appendLine(llmProgressCtx)
                appendLine()
                append(enhancedContext)
            }
        }

        // 注入系统级环境状态（始终注入，独立于任务进度）
        // 包含：当前应用/桌面状态、HOME 键失效警告、桌面打开应用提示
        // v8 修复：移除 planContext != null 互斥条件，系统级状态不再被误伤
        val systemProgressCtx = progressTracker.buildProgressContext()
        if (systemProgressCtx.isNotBlank()) {
            enhancedContext = buildString {
                appendLine(systemProgressCtx)
                appendLine()
                append(enhancedContext)
            }
        }

        // 注入 Scratchpad 工作记忆
        if (params.scratchpad.isNotEmpty()) {
            enhancedContext = buildString {
                appendLine("【工作记忆】")
                params.scratchpad.forEach { entry ->
                    appendLine("  [${entry.id}] ${entry.source}: ${entry.content.take(200)}")
                }
                appendLine()
                append(enhancedContext)
            }
        }

        // B4 修复：注入已问问题（防重追问），与 VL 模式 buildVisionUserPrompt 对称
        // 文本模式此前缺失此区块，与系统提示词"④ actionHistory【已问问题】已记录的不重复问"承诺不一致
        val askedQuestions = params.actionHistory
            .filter { it.actionType == "ask_user" }
            .mapNotNull { it.params["asked_questions"] as? List<*> }
            .flatten()
            .filterIsInstance<String>()
        if (askedQuestions.isNotEmpty()) {
            enhancedContext = buildString {
                appendLine("【已问问题（禁止重复追问）】")
                askedQuestions.forEachIndexed { idx, q ->
                    appendLine("  ${idx + 1}. $q")
                }
                appendLine()
                append(enhancedContext)
            }
        }

        return enhancedContext
    }

    /**
     * 格式化 LLM 自管理的进度信息为上下文文本
     * v8：始终注入（独立于 planContext），标题改为【实时进度】与 planContext 的【阶段列表】区分
     */
    private fun formatLlmProgress(progress: TaskProgress?): String {
        if (progress == null) return ""
        return buildString {
            appendLine("【实时进度】（由你上一轮输出，请基于此继续推进）")
            progress.currentStep?.takeIf { it.isNotBlank() }?.let {
                appendLine("当前步骤: $it")
            }
            if (progress.completedSteps.isNotEmpty()) {
                val display = if (progress.completedSteps.size > 5) {
                    progress.completedSteps.take(3).joinToString(" ") { "✅$it" } + " ... 共${progress.completedSteps.size}步"
                } else {
                    progress.completedSteps.joinToString(" ") { "✅$it" }
                }
                appendLine("已完成: $display")
            }
            if (progress.remainingSteps.isNotEmpty()) {
                appendLine("剩余: ${progress.remainingSteps.joinToString(" ") { "⏳$it" }}")
            }
            appendLine("状态: ${if (progress.status == "completed") "已完成" else "进行中"}")
        }
    }
}
