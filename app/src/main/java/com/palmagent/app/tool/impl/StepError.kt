package com.palmagent.app.tool.impl

import com.palmagent.app.tool.ToolResult

/**
 * 步骤错误三分类模型
 *
 * 借鉴 LangGraph default_retry_on 设计哲学：编程 bug 不重试，环境故障才重试
 *
 * - Transient：瞬时错误，重试可能成功（如点击被取消、网络偶发失败）
 * - Fatal：致命错误，重试无意义（如服务未就绪、应用未安装）
 * - Validation：校验错误，需修正而非重试（如参数缺失、坐标越界、目标不存在）
 */
sealed class StepError {
    abstract val original: Throwable
    abstract val category: String

    /** 瞬时错误：环境波动，重试可能成功 */
    class Transient(override val original: Throwable) : StepError() {
        override val category = "transient"
    }

    /** 致命错误：不可恢复，重试无意义 */
    class Fatal(override val original: Throwable) : StepError() {
        override val category = "fatal"
    }

    /** 校验错误：输入不合法，需修正而非重试 */
    class Validation(override val original: Throwable) : StepError() {
        override val category = "validation"
    }
}

/**
 * 工具执行异常：将 ToolResult.error 包装为可分类的异常
 */
class ToolExecutionException(
    val errorMessage: String,
    val toolName: String,
    val params: Map<String, Any>,
    val toolResultMetadata: Map<String, Any> = emptyMap()
) : Exception(errorMessage)

/**
 * 错误分类器
 *
 * 职责边界（方案 A）：
 * - 正常路径：工具自报结构化错误信封（ToolResult.error 的 errorType/failureCategory），框架透传
 * - 兜底路径：工具抛异常冒泡到执行层时，按异常类型分类（业界标准做法，
 *   同 LangGraph RetryPolicy 的 retry_on=ConnectionError 思路），
 *   从 ToolExecutionException 的结构化 metadata 信封分类，不做关键词字符串匹配。
 *
 * 分类优先级：
 * 1. 异常类型匹配（优先级最高）：IllegalArgumentException → Validation
 * 2. 工具异常信封分类（次之）：读 toolResultMetadata 中的 error_type 等结构化字段
 * 3. 默认兜底（保守策略）：未知错误归为 Transient
 */
object ErrorClassifier {

    /**
     * 分类入口
     */
    fun classify(t: Throwable): StepError {
        return when (t) {
            // 1. Java 异常类型匹配（优先级最高）
            is IllegalArgumentException,
            is NumberFormatException -> StepError.Validation(t)

            is NullPointerException,
            is IllegalStateException,
            is SecurityException -> StepError.Fatal(t)

            // 2. 网络与超时类（瞬时）
            is kotlinx.coroutines.TimeoutCancellationException,
            is java.net.SocketTimeoutException,
            is java.io.IOException -> StepError.Transient(t)

            // 3. 工具执行异常：从结构化 metadata 信封分类（非字符串匹配）
            is ToolExecutionException -> classifyByEnvelope(t)

            // 4. 默认兜底：保守策略，认为可重试
            else -> StepError.Transient(t)
        }
    }

    /**
     * 工具执行异常分类：优先读结构化错误信封（ToolResult metadata），
     * 无信封信息时保守归为 Transient（可重试）。
     */
    private fun classifyByEnvelope(e: ToolExecutionException): StepError {
        val errorType = e.toolResultMetadata[ToolResult.META_ERROR_TYPE] as? String
        return when (errorType) {
            "VALIDATION" -> StepError.Validation(e)
            "FATAL" -> StepError.Fatal(e)
            "TRANSIENT" -> StepError.Transient(e)
            // 无信封信息：保守兜底，认为可重试
            else -> StepError.Transient(e)
        }
    }
}
