package com.palmagent.app.domain.usecase

import android.graphics.Bitmap
import android.util.Log
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
    companion object {
        private const val TAG = "BuildEnhancedContext"
    }

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
        val planCompletedCount: Int = 0,
        val planCurrentStep: String? = null,
        val compactedSummary: String = "",
        /** 失败信息压缩摘要（FailureCompactor 产出，注入最近操作回顾之前） */
        val failureSummary: String = "",
        val llmProgress: TaskProgress? = null,
        val scratchpad: List<ScratchpadEntry> = emptyList()
    )

    suspend operator fun invoke(params: Params): String {
        // 基础区块：ContextManager 内部已按 maxTokens 预算裁剪（device + 最近操作 + 屏幕文本）
        val assembled = ContextManager.assemble(
            deviceCtx = params.deviceCtx,
            screenText = params.screenText,
            actionHistory = params.actionHistory,
            isTreeEmpty = params.isTreeEmpty,
            waitConsecutiveCount = params.waitConsecutiveCount,
            maxTokens = params.config.contextMaxTokens,
            keepRecentRounds = params.config.contextKeepRecentRounds
        )
        val base = assembled.text

        // 叠加区块按注入顺序编号 seq（渲染时 head 按 seq 降序 = 原 prepend 顺序；tail 追加在 base 之后）。
        // priority 越低越先被预算裁剪丢弃：屏幕描述/状态警告等辅助信息优先丢，Plan/摘要必须保留。
        val blocks = mutableListOf<CtxBlock>()
        var seq = 0

        // 决策模型输出的结构化 plan（由 PlanFormatter 格式化为文本，直接传给执行模型消费）
        if (params.planContext != null) {
            val windowText = PlanFormatter.formatWindow(params.planContext, params.planCompletedCount, params.planCurrentStep)
            Log.d(TAG, "Plan窗口: ${windowText.take(120).replace("\n", " / ")}")
            blocks.add(CtxBlock(seq++, 8, windowText))
        }

        // v9.1: Running Summary（早期操作历史的压缩摘要）
        if (params.compactedSummary.isNotBlank()) {
            blocks.add(CtxBlock(seq++, 7, "【历史摘要】${params.compactedSummary}"))
        }

        // FailureCompactor: 失败信息压缩摘要（避免重复犯错）
        if (params.failureSummary.isNotBlank()) {
            blocks.add(CtxBlock(seq++, 6, "【失败处理摘要】${params.failureSummary}"))
        }

        if (params.autoScreenDescription.isNotBlank()) {
            blocks.add(CtxBlock(seq++, 0, params.autoScreenDescription))
        }

        // LLM 自管理的任务进度（始终注入，跨轮持久化）
        val llmProgressCtx = formatLlmProgress(params.llmProgress)
        if (llmProgressCtx.isNotBlank()) {
            blocks.add(CtxBlock(seq++, 5, llmProgressCtx))
        }

        // 系统级环境状态（始终注入，独立于任务进度）
        val systemProgressCtx = progressTracker.buildProgressContext()
        if (systemProgressCtx.isNotBlank()) {
            blocks.add(CtxBlock(seq++, 4, systemProgressCtx))
        }

        // Scratchpad 工作记忆
        if (params.scratchpad.isNotEmpty()) {
            blocks.add(CtxBlock(seq++, 3, buildString {
                appendLine("【工作记忆】")
                params.scratchpad.forEach { entry ->
                    appendLine("  [${entry.id}] ${entry.source}: ${entry.content.take(200)}")
                }
            }))
        }

        // 已问问题（防重追问）
        val askedQuestions = params.actionHistory
            .filter { it.actionType == "ask_user" }
            .mapNotNull { it.params["asked_questions"] as? List<*> }
            .flatten()
            .filterIsInstance<String>()
        if (askedQuestions.isNotEmpty()) {
            blocks.add(CtxBlock(seq++, 2, buildString {
                appendLine("【已问问题（禁止重复追问）】")
                askedQuestions.forEachIndexed { idx, q ->
                    appendLine("  ${idx + 1}. $q")
                }
            }))
        }

        if (params.stateWarning.isNotBlank()) {
            blocks.add(CtxBlock(seq++, 1, params.stateWarning, tail = true))
        }

        // P0-2：整体 token 预算核算——超限按优先级从低到高丢弃（最低优先级/最末辅助区块优先）
        val budget = (params.config.contextMaxTokens * 0.85).toInt()
        var totalTokens = ContextManager.estimateTokensSafe(base)
        val keptSeqs = mutableSetOf<Int>()
        for (block in blocks.sortedBy { it.priority }) {
            val blockTokens = ContextManager.estimateTokensSafe(block.content)
            if (totalTokens + blockTokens <= budget) {
                totalTokens += blockTokens
                keptSeqs.add(block.seq)
            } else {
                Log.d(TAG, "上下文超预算(${totalTokens}+$blockTokens>${budget})，丢弃区块 seq=${block.seq} priority=${block.priority}")
            }
        }

        val keptHead = blocks.filter { it.seq in keptSeqs && !it.tail }
            .sortedByDescending { it.seq }
            .joinToString("\n\n") { it.content }
        val keptTail = blocks.filter { it.seq in keptSeqs && it.tail }
            .joinToString("\n\n") { it.content }

        return buildString {
            if (keptHead.isNotBlank()) {
                appendLine(keptHead)
                appendLine()
            }
            append(base)
            if (keptTail.isNotBlank()) {
                appendLine()
                appendLine(keptTail)
            }
        }
    }

    /** P0-2：上下文区块（seq=注入序号用于保持渲染顺序，priority=丢弃优先级，tail=追加在 base 之后） */
    private data class CtxBlock(
        val seq: Int,
        val priority: Int,
        val content: String,
        val tail: Boolean = false
    )

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
