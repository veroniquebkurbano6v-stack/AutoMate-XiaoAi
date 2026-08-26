package com.palmagent.app.service

import com.palmagent.app.model.AgentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ActionParser 对 web_search 新增字段 query / mode 的解析测试。
 *
 * 契约：query=搜索关键词（协议字段）；mode 白名单 web/ai，非法/缺失回落 null。
 */
class ActionParserWebSearchTest {

    private fun parse(json: String): AgentAction =
        ActionParser.parseActionFromResponse(json, screenInfo = null)

    @Test
    fun `web_search_query_mode 完整解析`() {
        val action = parse("""{"type":"web_search","query":"XX医院如何挂号","mode":"ai"}""")
        assertEquals("web_search", action.type)
        assertEquals("XX医院如何挂号", action.query)
        assertEquals("ai", action.mode)
    }

    @Test
    fun `web_search_缺 mode 默认 null(下游默认web)`() {
        val action = parse("""{"type":"web_search","query":"今日天气"}""")
        assertEquals("web_search", action.type)
        assertEquals("今日天气", action.query)
        assertNull(action.mode)
    }

    @Test
    fun `web_search_mode 大小写归一`() {
        val action = parse("""{"type":"web_search","query":"x","mode":"AI"}""")
        assertEquals("ai", action.mode)
    }

    @Test
    fun `web_search_mode 非法值回落 null`() {
        val action = parse("""{"type":"web_search","query":"x","mode":"deep"}""")
        assertEquals("deep 非法，mode 应回落 null", null, action.mode)
    }

    @Test
    fun `web_search_缺 query 不崩溃`() {
        val action = parse("""{"type":"web_search","mode":"web"}""")
        assertEquals("web_search", action.type)
        assertNull(action.query)
    }
}
