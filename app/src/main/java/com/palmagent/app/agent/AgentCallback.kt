package com.palmagent.app.agent

import com.palmagent.app.tool.ToolResult

interface AgentCallback {
    fun onLoopStart(round: Int)
    fun onContent(round: Int, content: String)
    fun onToolCall(round: Int, toolId: String, toolName: String, displayName: String, parameters: String)
    fun onToolResult(round: Int, toolId: String, toolName: String, displayName: String, parameters: String, result: ToolResult)
    fun onComplete(round: Int, finalAnswer: String, totalTokens: Int)
    fun onError(round: Int, error: Exception, totalTokens: Int)
    /**
     * 执行模型确认任务真正完成（finish 且非障碍失败）时触发——携带每步结构化执行摘要，
     * 供上层调用决策模型 reportResult() 生成任务完成报告。
     * 默认空实现：不影响现有实现；不实现则跳过报告环节（保持向后兼容）。
     */
    fun onExecutionFinished(executionSummary: String) {}
}