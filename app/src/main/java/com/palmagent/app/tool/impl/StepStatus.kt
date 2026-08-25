package com.palmagent.app.tool.impl

/**
 * 步骤执行状态机
 *
 * 借鉴 Airflow TaskInstanceState，定义 6 状态模型：
 *
 * PENDING ──start──> RUNNING
 *                      │
 *                      ├─success──────────────────> SUCCESS
 *                      │
 *                      ├─transient_exception──> RETRYING ──retry──> RUNNING
 *                      │                              │
 *                      │                              └─max_attempts──> FAILED
 *                      │
 *                      ├─fatal_exception────────────────────────> FAILED
 *                      │
 *                      └─validation_error────────────────────────> SKIPPED
 */
enum class StepStatus {
    /** 待执行 */
    PENDING,

    /** 执行中 */
    RUNNING,

    /** 成功 */
    SUCCESS,

    /** 重试中（有重试次数剩余） */
    RETRYING,

    /** 最终失败（重试耗尽或 fatal 错误） */
    FAILED,

    /** 跳过（校验错误或策略决定不执行） */
    SKIPPED
}

/**
 * 失败传播策略
 *
 * 借鉴 Airflow Trigger Rules，将"失败后怎么办"声明式化
 */
enum class FailureStrategy {
    /** 立即终止整个任务（当前行为） */
    FAIL_FAST,

    /** 标记当前步骤 FAILED，继续执行后续步骤 */
    CONTINUE_ON_FAILURE,

    /** 重试 N 次，耗尽后 SKIP 当前步骤并继续后续 */
    RETRY_THEN_SKIP,

    /** 重试 N 次，耗尽后回调 LLM 重新规划剩余步骤 */
    RETRY_THEN_REPLAN;

    companion object {
        /**
         * 根据工具名获取默认失败策略
         *
         * 策略矩阵：
         * - open_app / wait → FAIL_FAST：失败意味着后续操作失去前提
         * - swipe → CONTINUE_ON_FAILURE：滑动失败不阻塞后续
         * - locate → RETRY_THEN_REPLAN：定位失败需 LLM 重新规划
         * - tap / long_press / type → RETRY_THEN_SKIP：偶发失败可跳过
         */
        fun defaultFor(toolName: String): FailureStrategy = when (toolName) {
            "open_app", "wait" -> FAIL_FAST
            "swipe" -> CONTINUE_ON_FAILURE
            "locate" -> RETRY_THEN_REPLAN
            "tap", "long_press", "type", "auto_input" -> RETRY_THEN_SKIP
            "back", "home" -> CONTINUE_ON_FAILURE
            else -> RETRY_THEN_SKIP
        }
    }
}
