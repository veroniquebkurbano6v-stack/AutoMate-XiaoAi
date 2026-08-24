package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ToolResultCache 通用磁盘缓存的纯 JVM 单元测试（不依赖 Context/磁盘，只测纯逻辑）
 * 覆盖评审关注点：稳定键、head+tail 预览、预览固化一致性。
 */
class ToolResultCacheTest {

    @Test
    fun buildKey_参数顺序无关_相同参数同key() {
        val a = mapOf("query" to "医院", "count" to 5, "radius" to 1000)
        val b = mapOf("radius" to 1000, "count" to 5, "query" to "医院") // key 顺序不同
        assertEquals(ToolResultCache.buildKey("amap_nearby", a), ToolResultCache.buildKey("amap_nearby", b))
    }

    @Test
    fun buildKey_不同参数或工具_不同key() {
        val a = mapOf("query" to "医院", "count" to 5)
        val a3 = mapOf("query" to "医院", "count" to 3)
        assertNotEquals(ToolResultCache.buildKey("amap_nearby", a), ToolResultCache.buildKey("amap_nearby", a3))
        assertNotEquals(ToolResultCache.buildKey("amap_nearby", a), ToolResultCache.buildKey("list_apps", a))
    }

    @Test
    fun buildKey_语义不同参数不碰撞_精确序列化() {
        // 归一化会把 {"keywords":["美团","淘宝"]} 与 {"keywords":["美团淘宝"]} 撞成同一 key，这里必须区分
        val kwList = mapOf("keywords" to listOf("美团", "淘宝"))
        val kwJoined = mapOf("keywords" to listOf("美团淘宝"))
        assertNotEquals(
            ToolResultCache.buildKey("list_apps", kwList),
            ToolResultCache.buildKey("list_apps", kwJoined)
        )
        // 逗号/空格等轻写差异也必须是不同 key
        val q1 = mapOf("query" to "医院, 附近", "count" to 5)
        val q2 = mapOf("query" to "医院附近", "count" to 5)
        assertNotEquals(ToolResultCache.buildKey("amap_search", q1), ToolResultCache.buildKey("amap_search", q2))
    }

    @Test
    fun buildPreview_短内容原样返回() {
        val s = "短结果"
        assertEquals(s, ToolResultCache.buildPreview(s))
    }

    @Test
    fun buildPreview_长内容head加尾_含取回占位() {
        val long = "h".repeat(900) + "t".repeat(400) // 1300 字符
        val preview = ToolResultCache.buildPreview(long)
        assertTrue(preview.startsWith("h".repeat(700)))
        assertTrue(preview.endsWith("t".repeat(300)))
        assertTrue(preview.contains("fetch_result"))
        assertTrue(preview.length < long.length)
    }

    @Test
    fun buildPreview_确定性_相同输入相同输出() {
        val long = "内容".repeat(600)
        assertEquals(ToolResultCache.buildPreview(long), ToolResultCache.buildPreview(long))
    }

    @Test
    fun clearGenerics_只清通用fx_保留webSearch轮次_跨会话隔离() {
        // P2 #1 回归：新决策会话通过 clearGenerics 清空共享磁盘上的通用工具结果，
        // 避免跨会话复用上一对话的 amap/kb_read/list_apps 等结果，同时保留 web_search 轮次去重。
        val dir = File(System.getProperty("java.io.tmpdir"), "trc-${System.nanoTime()}")
        try {
            ToolResultCache.initForTest(dir)
            try {
                ToolResultCache.put("amap_weather", emptyMap(), "晴天 25°C")
                assertTrue("应生成 fx_ 通用条目", dir.listFiles { it.name.startsWith("fx_") }?.isNotEmpty() ?: false)
                ToolResultCache.putSearch(
                    1, "北京天气",
                    listOf(WebSearchService.SearchItem(title = "北京天气", url = "u", snippet = "晴"))
                )
                assertTrue("应生成 search_ 轮次文件", File(dir, "search_1.json").exists())

                ToolResultCache.clearGenerics()

                assertFalse("fx_ 通用条目应被清空", dir.listFiles { it.name.startsWith("fx_") }?.isNotEmpty() ?: false)
                assertTrue("web_search search_ 应保留", File(dir, "search_1.json").exists())
                // 通用条目已无法取回，跨会话隔离生效
                assertNull(ToolResultCache.getByKey(ToolResultCache.buildKey("amap_weather", emptyMap())))
            } finally {
                ToolResultCache.resetForTest()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun put_preview固化_同参数两次一致() {
        val args = mapOf("query" to "测试")
        val content = "结果".repeat(1500)
        val e1 = ToolResultCache.put("web_search", args, content)
        val e2 = ToolResultCache.put("web_search", args, content)
        assertEquals(ToolResultCache.buildKey("web_search", args), e1.key)
        assertEquals("fx-", e1.ref.take(3))
        assertEquals(ToolResultCache.buildPreview(content), e1.preview)
        // 同参数二次 put → key / preview 逐字节一致（轮间 prompt cache 稳定）
        assertEquals(e1.key, e2.key)
        assertEquals(e1.preview, e2.preview)
        assertFalse(e1.preview.contains(content)) // 长内容已截断，未整段保留到预览
    }
}