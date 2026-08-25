package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DecisionDialogService 台账逻辑的纯 JVM 单元测试。
 * 覆盖评审关注点：estimateTokens 估算、buildLedgerContent 聚合预算（最旧淘汰）、
 * evictLedgerIfNeeded（FIFO + 保护最近 N 条 + token 预算）。
 */
class DecisionDialogLedgerTest {

    private val service = DecisionDialogService()

    @Test
    fun estimateTokens_按约15字符10token估算() {
        assertEquals((3 * 2 + 1) / 3, service.estimateTokens("abc"))        // 2
        assertEquals((4 * 2 + 1) / 3, service.estimateTokens("你好世界"))    // 3
    }

    @Test
    fun parseLedgerKey_解析tool与args() {
        // 正常：key = "tool::<args JSON>"
        val key = "amap_search::{\"query\":\"医院\",\"count\":5}"
        val p = service.parseLedgerKey(key)
        assertNotNull("应能解析台账行 key", p)
        assertEquals("amap_search", p!!.first)
        assertEquals("医院", p.second["query"])
        // gson 将 JSON 数字解析为 Double
        assertEquals(5.0, (p.second["count"] as Number).toDouble(), 0.0)
        // 非法 key（无 :: 分隔）返回 null
        assertNull(service.parseLedgerKey("no-separator"))
        assertNull(service.parseLedgerKey("tool::"))
    }

    @Test
    fun buildLedgerContent_空台账返回暂无() {
        val state = DecisionDialogService.SessionDecisionState()
        assertTrue(service.buildLedgerContent(state).contains("暂无"))
    }

    @Test
    fun buildLedgerContent_聚合超预算_只读不删行_注入最近条目() {
        val state = DecisionDialogService.SessionDecisionState()
        val preview = "x".repeat(1000) // 每条预览 1000 字符
        listOf("a", "b", "c", "d").forEach { k ->
            state.ledger[k] = DecisionDialogService.LedgerRow(k, "fx-$k", preview)
        }
        state.ledgerTokens = state.ledger.values.sumOf { service.estimateTokens(it.preview) }
        val beforeTokens = state.ledgerTokens

        val out = service.buildLedgerContent(state)

        // 只读：buildLedgerContent 不再删除条目，也不扣减 token（去重与保护不被字符预算破坏）
        assertEquals(4, state.ledger.size)
        assertEquals(beforeTokens, state.ledgerTokens)
        // 所有行 ref 恒输出（fetch_result 对所有台账行可达），正文按预算截断
        assertTrue(out.contains("fx-a"))
        assertTrue(out.contains("fx-b"))
        assertTrue(out.contains("fx-c"))
        assertTrue(out.contains("fx-d"))
        // 超预算行保留 ref、正文省略（不再整行丢弃）
        assertTrue(out.contains("内容超预算"))
    }

    @Test
    fun evictLedgerIfNeeded_预算超限_FIFO淘汰_保护最近N条() {
        val state = DecisionDialogService.SessionDecisionState()
        val preview = "y".repeat(2000)
        repeat(10) { i ->
            state.ledger["k$i"] = DecisionDialogService.LedgerRow("k$i", "fx-$i", preview)
        }
        // token 口径与注入成本一致（key+preview）
        val tokenPerRow = service.estimateTokens("k0" + preview)
        state.ledgerTokens = 10 * tokenPerRow // > 8000 预算

        service.evictLedgerIfNeeded(state)

        // 最旧被淘汰、最近 5 条（k5..k9）保护保留
        assertFalse(state.ledger.containsKey("k0"))
        assertTrue(state.ledger.containsKey("k9"))
        assertTrue("淘汰后至少保留保护圈 5 条", state.ledger.size >= 5)
        // token 与剩余行一致（每行 key+preview），且回到预算内
        assertEquals(state.ledger.size * tokenPerRow, state.ledgerTokens)
        assertTrue(state.ledgerTokens <= 8000)
    }

    @Test
    fun evictLedgerIfNeeded_预算未超_不淘汰() {
        val state = DecisionDialogService.SessionDecisionState()
        state.ledger["k0"] = DecisionDialogService.LedgerRow("k0", "fx-0", "p0")
        state.ledger["k1"] = DecisionDialogService.LedgerRow("k1", "fx-1", "p1")
        state.ledgerTokens = 10 // 远小于预算
        service.evictLedgerIfNeeded(state)
        assertEquals(2, state.ledger.size)
    }
}