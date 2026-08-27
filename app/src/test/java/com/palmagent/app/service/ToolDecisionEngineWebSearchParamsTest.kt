package com.palmagent.app.service

import com.palmagent.app.model.AgentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 决策主链路 web_search 参数透传测试。
 *
 * 背景：ToolDecisionEngine.executeWithTools 的 web_search 分支此前直接取 action.text，
 * 既未读协议字段 action.query，也未透传 action.mode 到 searchWithCache，导致决策模型
 * 输出 mode=ai 时静默走 web 端点（ai-search 在决策路径上不可达）。
 *
 * 本测试锁定 resolveWebSearchParams 契约：query 主取协议字段（text/description 兜底），
 * mode 缺省回退 web，query 全空返回 null（调用方跳过搜索）。
 */
class ToolDecisionEngineWebSearchParamsTest {

    private fun action(
        query: String? = null,
        text: String? = null,
        description: String = "执行web_search",
        mode: String? = null
    ) = AgentAction(
        type = "web_search",
        description = description,
        confidence = 0.8f,
        text = text,
        query = query,
        mode = mode
    )

    @Test
    fun `query 与 mode 完整透传`() {
        val params = ToolDecisionEngine.resolveWebSearchParams(
            action(query = "XX医院如何挂号", mode = "ai")
        )
        assertEquals("XX医院如何挂号" to "ai", params)
    }

    @Test
    fun `query 优先 text 兜底`() {
        val params = ToolDecisionEngine.resolveWebSearchParams(
            action(query = "Q主", text = "T兜底", mode = "ai")
        )
        assertEquals("应优先取协议字段 query", "Q主" to "ai", params)
    }

    @Test
    fun `query 缺失 text 兜底且 mode 缺省回退 web`() {
        val params = ToolDecisionEngine.resolveWebSearchParams(
            action(text = "T兜底")
        )
        assertEquals("T兜底" to "web", params)
    }

    @Test
    fun `query 与 text 均空 description 兜底`() {
        val params = ToolDecisionEngine.resolveWebSearchParams(
            action(description = "搜索XX医院")
        )
        assertEquals("搜索XX医院" to "web", params)
    }

    @Test
    fun `query text description 均空返回 null`() {
        val params = ToolDecisionEngine.resolveWebSearchParams(
            action(query = "", text = "", description = "")
        )
        assertNull("query 全空时应返回 null 让调用方跳过搜索", params)
    }

    @Test
    fun `mode 为 null 回退 web`() {
        val params = ToolDecisionEngine.resolveWebSearchParams(
            action(query = "Q", mode = null)
        )
        assertEquals("Q" to "web", params)
    }
}
