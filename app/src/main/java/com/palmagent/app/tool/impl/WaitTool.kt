package com.palmagent.app.tool.impl

import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import kotlinx.coroutines.delay

class WaitTool : BaseTool() {
    override fun getName(): String = "wait"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("duration_ms", "integer", "Wait duration in ms (default 1000)", false)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        // P1-3 修复：clamp 到 100-10000ms，符合 project_memory 约束
        val targetDuration = optionalLong(params, "duration_ms", 1000).coerceIn(100L, 10_000L)
        val actualStart = System.currentTimeMillis()
        delay(targetDuration)
        val actualDuration = System.currentTimeMillis() - actualStart
        return ToolResult.success(
            "✓ 等待完成，累计等待 ${actualDuration}ms（目标 ${targetDuration}ms）"
        )
    }

    override fun getDescriptionEN(): String =
        "Wait for a specified duration (useful for page loading delays)."

    override fun getDescriptionCN(): String =
        "等待指定时长，用于等待页面加载、动画播放、网络请求完成等场景。duration_ms默认1000ms，范围100-10000ms。"
}