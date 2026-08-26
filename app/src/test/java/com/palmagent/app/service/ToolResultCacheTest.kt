package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun clearAll_只清webSearch轮次_保留通用fx_() {
        // #5 #1/#2 修复：任务开始 clearAll 只清 web_search 轮次，保留通用 fx_（决策取回正文），
        // 且通用条目写读均在根命名空间（决策/执行两侧 fetch_result 读同一位置，杜绝读写不对称）。
        val dir = File(System.getProperty("java.io.tmpdir"), "trc-${System.nanoTime()}")
        try {
            ToolResultCache.initForTest(dir)
            try {
                val args = mapOf("query" to "医院")
                val key = ToolResultCache.buildKey("amap_search", args)
                ToolResultCache.put("amap_search", args, "根命名空间结果")
                ToolResultCache.putSearch(
                    1, "北京天气",
                    listOf(WebSearchService.SearchItem(title = "北京天气", url = "u", snippet = "晴"))
                )
                // 写读对称（根）：put 后按 key 可取回
                assertNotNull(ToolResultCache.getByKey(key))
                assertTrue("应生成 search_ 轮次文件", File(dir, "search_1.json").exists())

                ToolResultCache.clearAll()

                // 任务开始只清 web_search 轮次
                assertFalse("web_search search_ 应被清理", File(dir, "search_1.json").exists())
                // 通用 fx_ 保留，fetch_result 取回仍可用
                assertNotNull("通用 fx_ 应保留", ToolResultCache.getByKey(key))
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
                val e1 = ToolResultCache.put("web_search", args, content)!!
                val e2 = ToolResultCache.put("web_search", args, content)!!
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

    @Test
    fun 会话隔离_两会话同参数不覆盖_各自取回() {
        // #7 #1 回归：fx 文件名/ref 含会话分量，两会话对同一 (tool,args) 各自写入互不覆盖，
        // 会话 A 的 fetch_result 应返回 A 自己的内容，不会串扰成 B 的结果。
        val dir = File(System.getProperty("java.io.tmpdir"), "trc-${System.nanoTime()}")
        try {
            ToolResultCache.initForTest(dir)
            try {
                val args = mapOf("query" to "医院")
                val key = ToolResultCache.buildKey("amap_search", args)
                val eA = ToolResultCache.put("amap_search", args, "会话A医院", "sessA")!!
                val eB = ToolResultCache.put("amap_search", args, "会话B医院", "sessB")!!

                // 不同会话 → 不同 ref/文件（不互相覆盖）
                assertNotEquals("两会话同参数应产生不同 ref", eA.ref, eB.ref)
                // 各自按会话取回各自的全文
                assertEquals("会话A医院", ToolResultCache.get(eA.ref)?.content)
                assertEquals("会话B医院", ToolResultCache.get(eB.ref)?.content)
                // 按 key + session 定位各自全文
                assertEquals("会话A医院", ToolResultCache.getByKey(key, "sessA")?.content)
                assertEquals("会话B医院", ToolResultCache.getByKey(key, "sessB")?.content)
            } finally {
                ToolResultCache.resetForTest()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun webSearch缓存键_含mode维度_web与ai互不串用() {
        val dir = File(System.getProperty("java.io.tmpdir"), "trc-${System.nanoTime()}")
        try {
            ToolResultCache.initForTest(dir)
            try {
                val items = listOf(
                    WebSearchService.SearchItem(title = "网页A", url = "https://a.com", snippet = "sa"),
                    WebSearchService.SearchItem(title = "网页B", url = "https://b.com", snippet = "sb")
                )
                // 同 query 分别 web / ai 写入
                ToolResultCache.putSearch(1, "医院挂号", items, answer = null, mode = "web")
                ToolResultCache.putSearch(2, "医院挂号", items, answer = "AI聚合答案", mode = "ai")

                // 各自命中各自轮次，互不串用
                assertEquals("web 应命中 round=1", 1, ToolResultCache.hitRound("医院挂号", "web"))
                assertEquals("ai 应命中 round=2", 2, ToolResultCache.hitRound("医院挂号", "ai"))
                // 默认 mode=web 兼容旧调用方
                assertEquals("默认 mode=web", 1, ToolResultCache.hitRound("医院挂号"))

                // answer 落盘在首个条目，摘要视图渲染 AI摘要
                val entries = ToolResultCache.readEntries(2)!!
                assertEquals("首个条目承载 answer", "AI聚合答案", entries.first().answer)
                val summary = ToolResultCache.buildSummary("医院挂号", entries)
                assertTrue("buildSummary 应渲染 AI摘要: $summary", summary.contains("AI摘要: AI聚合答案"))
                // web 条目无 answer，不渲染 AI摘要 行
                val webSummary = ToolResultCache.buildSummary("医院挂号", ToolResultCache.readEntries(1)!!)
                assertFalse("web 摘要不应含 AI摘要 行: $webSummary", webSummary.contains("AI摘要:"))
            } finally {
                ToolResultCache.resetForTest()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun webSearch缓存键_旧文件无answer字段_读回不崩溃() {
        val dir = File(System.getProperty("java.io.tmpdir"), "trc-${System.nanoTime()}")
        try {
            ToolResultCache.initForTest(dir)
            try {
                // 手工写一份不含 answer 字段的旧格式 search_ 文件
                val legacy = """
                    [{"key":"web_search::x","ref":"ws-9-1","tool":"web_search","preview":"p","content":"c",
                    "createdAt":1,"title":"t","url":"u","snippet":"s","summary":null}]
                """.trimIndent()
                java.io.File(dir, "search_9.json").writeText(legacy)
                val entries = ToolResultCache.readEntries(9)
                assertNotNull(entries)
                assertNull("旧文件无 answer 字段 → 读回 null（向后兼容）", entries!!.first().answer)
            } finally {
                ToolResultCache.resetForTest()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}