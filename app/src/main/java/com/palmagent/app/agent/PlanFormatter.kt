package com.palmagent.app.agent

/**
 * 把 Plan 对象格式化为执行模型可读的文本。
 * 格式由代码控制，不依赖模型输出转义。
 */
object PlanFormatter {

    /** 格式化为执行模型 prompt 中的【决策模型任务计划】区域文本 */
    fun format(plan: Plan): String = buildString {
        if (plan.requirement.isNotBlank()) {
            appendLine("需求：${plan.requirement}")
            appendLine()
        }
        if (plan.goal.isNotBlank()) {
            appendLine("目标：${plan.goal}")
            appendLine()
        }
        if (plan.steps.isEmpty()) {
            appendLine("（无具体步骤，直接执行用户请求）")
        } else {
            plan.steps.forEach { step ->
                appendLine("步骤${step.order}：${step.goal}")
                appendLine("完成标志：${step.successCriteria}")
                if (step.supervised) {
                    appendLine("（此步骤需用户确认后执行）")
                }
                if (step.toolHint.isNotBlank()) {
                    appendLine("工具提示：${step.toolHint}")
                }
                appendLine()
            }
        }
    }

    /**
     * 窗口化渲染：按任务进度显示前两步/当前步/后两步 5 步窗口（替代全文注入）
     *
     * - completedCount = 已完成步骤数（执行模型 progress.completed_steps 数量，含提前标记校准）
     * - currentStep = 执行模型 progress.current_step（活性修订感知）：优先按该文本模糊匹配 Plan 步骤
     *   （模型修订/插入步骤后窗口跟随其实际当前步）；匹配不上则 fallback completedCount+1
     * - 初始（0 已完成）显示当前步+后两步（无前序）；推进后显示 ✅前序两步 + ▶当前步 + →后两步
     * - 全部完成显示完成态；需求/目标保留（窗口化只作用于步骤区）
     */
    fun formatWindow(plan: Plan, completedCount: Int, currentStep: String? = null): String = buildString {
        if (plan.requirement.isNotBlank()) {
            appendLine("需求：${plan.requirement}")
            appendLine()
        }
        if (plan.goal.isNotBlank()) {
            appendLine("目标：${plan.goal}")
            appendLine()
        }
        if (plan.steps.isEmpty()) {
            appendLine("（无具体步骤，直接执行用户请求）")
            return@buildString
        }
        val total = plan.steps.size
        val completed = completedCount.coerceIn(0, total)
        if (completed >= total) {
            appendLine("【任务计划】✅ 全部 $total 步已完成")
            return@buildString
        }
        // 当前步索引：优先按 currentStep 模糊匹配 Plan 步骤（模型活性修订感知——窗口跟随其实际当前步）
        val matched = currentStep?.takeIf { it.isNotBlank() }?.let { cs ->
            plan.steps.indexOfFirst { s ->
                val g = s.goal
                g.isNotBlank() && (g.contains(cs) || cs.contains(g))
            }.takeIf { it >= 0 }?.plus(1)
        }
        val current = matched ?: (completed + 1)
        appendLine("【任务计划（进度窗口 $completed/$total）】")
        // 5 步窗口：前两步 + 当前步 + 后两步（边界处只显示存在的步骤）
        for (i in (current - 2)..(current - 1)) {
            if (i >= 1 && i <= total) {
                val s = plan.steps[i - 1]
                appendLine("✅ 前序：步骤${s.order}：${s.goal}")
            }
        }
        if (current <= total) {
            val cur = plan.steps[current - 1]
            appendLine("▶ 当前步：步骤${cur.order}：${cur.goal}")
            appendLine("完成标志：${cur.successCriteria}")
            if (cur.supervised) appendLine("（此步骤需用户确认后执行）")
            if (cur.toolHint.isNotBlank()) appendLine("工具提示：${cur.toolHint}")
        }
        if (current + 1 <= total) {
            val next = plan.steps[current]
            appendLine("→ 下一步：步骤${next.order}：${next.goal}")
        }
        if (current + 2 <= total) {
            val next2 = plan.steps[current + 1]
            appendLine("→ 再下一步：步骤${next2.order}：${next2.goal}")
        }
    }

    /** 提取 user_summary 兜底值（从 Plan.goal） */
    fun extractSummary(plan: Plan): String {
        return plan.goal.takeIf { it.isNotBlank() }?.take(50) ?: "任务执行中"
    }

    /** 格式化为日志/持久化用的紧凑文本 */
    fun formatForLog(plan: Plan): String {
        return "需求=${plan.requirement.take(40)} 目标=${plan.goal.take(40)} 步骤数=${plan.steps.size}"
    }
}
