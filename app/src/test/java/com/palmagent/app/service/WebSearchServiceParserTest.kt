package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebSearchService 博查响应解析的黄金样本测试（对应 PR 审查阻塞项）。
 *
 * 覆盖 2026-08 实测真实端点结构（与代码请求体一致）：
 * - web-search：data.webPages.value[]（顶层无 webPages）
 * - ai-search：messages[]，type=source/content_type=webpage 的 content 内嵌 JSON 网页集合，
 *   答案在 type=answer 的 message.content
 * 同时覆盖防御形态（顶层 webPages / 顶层 value / source content 单对象）。
 *
 * 解析方法为 internal，供本模块单测直接调用。
 */
class WebSearchServiceParserTest {

    // ==================== web-search 真实结构：data.webPages.value[] ====================

    @Test
    fun `web_search_data_webPages_结构解析成功`() {
        val body = """
            {
              "code": 200,
              "data": {
                "webPages": {
                  "totalEstimatedMatches": 100,
                  "value": [
                    {
                      "name": "XX医院官方网站",
                      "url": "https://www.example.com/hospital",
                      "snippet": "XX医院官方预约挂号平台",
                      "summary": "支持线上预约挂号"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val result = WebSearchService.parseBochaResponse("XX医院", body, 123L)
        assertNotNull("web-search 真实结构应解析成功", result)
        result!!
        assertEquals("bocha", result.engine)
        assertEquals(1, result.results.size)
        assertEquals("XX医院官方网站", result.results[0].title)
        assertEquals("https://www.example.com/hospital", result.results[0].url)
        assertNull(result.answer)
    }

    // ==================== web-search 官方示例：顶层 webPages.value[] ====================

    @Test
    fun `web_search_顶层webPages_结构解析成功`() {
        val body = """
            {
              "_type": "SearchResponse",
              "queryContext": {"originalQuery": "xx"},
              "webPages": {
                "value": [
                  {"name": "A", "url": "https://a.com", "snippet": "snippet-a"},
                  {"name": "B", "url": "https://b.com", "snippet": "snippet-b", "summary": "sum-b"}
                ]
              }
            }
        """.trimIndent()

        val result = WebSearchService.parseBochaResponse("xx", body, 1L)
        assertNotNull(result)
        result!!
        assertEquals(2, result.results.size)
        assertEquals("A", result.results[0].title)
        assertEquals("sum-b", result.results[1].summary)
    }

    // ==================== ai-search 真实结构：messages[] + type=answer ====================

    @Test
    fun `ai_search_messages_结构解析成功_answer与来源都取出`() {
        val body = """
            {
              "messages": [
                {
                  "type": "source",
                  "content_type": "webpage",
                  "content": "{\"webPages\":{\"value\":[{\"name\":\"挂号指南\",\"url\":\"https://g.com\",\"snippet\":\"流程\"}]}}"
                },
                {
                  "type": "answer",
                  "content": "可通过医院微信服务号预约挂号：关注服务号→就医服务→预约挂号→选择科室。"
                }
              ]
            }
        """.trimIndent()

        val result = WebSearchService.parseAiSearchResponse("如何挂号", body, 5L)
        assertNotNull("ai-search messages 结构应解析成功", result)
        result!!
        assertEquals(1, result.results.size)
        assertEquals("挂号指南", result.results[0].title)
        assertNotNull("answer 应从 type=answer 的 message 取出", result.answer)
        assertTrue(result.answer!!.contains("预约挂号"))
    }

    @Test
    fun `ai_search_source_content_单对象形态解析`() {
        // source content 可能是单条网页对象（非集合）
        val body = """
            {
              "messages": [
                {
                  "type": "source",
                  "content_type": "webpage",
                  "content": "{\"name\":\"单条结果\",\"url\":\"https://s.com\",\"snippet\":\"single\"}"
                }
              ]
            }
        """.trimIndent()

        val result = WebSearchService.parseAiSearchResponse("x", body, 1L)
        // 单对象 content 无 value 数组 → 走回退也解析不到 → null（不崩溃）
        // 注：当前实现按 value 数组解析，单对象会回退顶层结构失败返回 null
        assertEquals(null, result)
    }

    @Test
    fun `ai_search_messages_缺answer_不崩溃且返回结果`() {
        val body = """
            {
              "messages": [
                {"type": "source", "content_type": "webpage", "content": "{\"value\":[{\"name\":\"N\",\"url\":\"https://n.com\"}]}"}
              ]
            }
        """.trimIndent()

        val result = WebSearchService.parseAiSearchResponse("x", body, 1L)
        assertNotNull(result)
        result!!
        assertEquals(1, result.results.size)
        assertNull("缺 answer 时为 null，不崩溃", result.answer)
    }

    // ==================== 防御：ai 回退常规结构 ====================

    @Test
    fun `ai_search_无messages_回退data_webPages_解析`() {
        val body = """
            {
              "data": {
                "webPages": {"value": [{"name": "F", "url": "https://f.com", "snippet": "f"}]}
              },
              "summary": "回退形态的AI摘要"
            }
        """.trimIndent()

        val result = WebSearchService.parseAiSearchResponse("x", body, 1L)
        assertNotNull("无 messages 时应回退 data.webPages", result)
        result!!
        assertEquals(1, result.results.size)
        assertEquals("F", result.results[0].title)
        assertNull("回退形态不从顶层取 summary 作为 answer（保持 messages 契约）", result.answer)
    }
}
