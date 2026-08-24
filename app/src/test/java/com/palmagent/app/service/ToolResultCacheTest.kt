package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
    fun 会话命名空间隔离_同key不同会话不碰撞() {
        // P2 #2 回归：决策侧工具结果按会话命名空间隔离（取代全局 clearGenerics）——
        // 会话 A 写下的 fx_ 不会被会话 B 命中复用，也不影响其他活跃会话。
        val dir = File(System.getProperty("java.io.tmpdir"), "trc-${System.nanoTime()}")
        try {
            ToolResultCache.initForTest(dir)
            try {
                val args = mapOf("query" to "医院")
                val key = ToolResultCache.buildKey("amap_search", args)
                val ea = ToolResultCache.put("amap_search", args, "会话A结果", "session-a")

                // 会话 A 同 key 命中
                assertNotNull(ToolResultCache.getByKey(key, "session-a"))
                // 会话 B 同 key 独立空间 → miss（不复用旧会话结果）
                assertNull(ToolResultCache.getByKey(key, "session-b"))
                // fetch 按会话定位：A 会话取回 A 的全文，B 会话取不到
                assertNotNull(ToolResultCache.get(ea.ref, "session-a"))
                assertNull(ToolResultCache.get(ea.ref, "session-b"))
            } finally {
                ToolResultCache.resetForTest()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun put_preview固化_同参数两次一致_and磁盘可读回() {
        val dir = File(System.getProperty("java.io.tmpdir"), "trc-${System.nanoTime()}")
        try {
            ToolResultCache.initForTest(dir)
            try {
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

                // 关键：磁盘回读路径必须真实工作（put 落盘、getByKey 读回一致）
                val back = ToolResultCache.getByKey(e1.key)
                assertNotNull("put 后应能按 key 从磁盘读回", back)
                assertEquals(e1.ref, back!!.ref)
                assertEquals(e1.preview, back.preview)
                assertEquals(content, back.content)
            } finally {
                ToolResultCache.resetForTest()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}