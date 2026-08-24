package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun buildLedgerContent_空台账返回暂无() {
        val state = DecisionDialogService.SessionDecisionState()
        assertTrue(service.buildLedgerContent(state).contains("暂无"))
    }

    @Test
    fun buildLedgerContent_聚合超预算_淘汰最旧() {
        val state = DecisionDialogService.SessionDecisionState()
        val preview = "x".repeat(1000) // 每条预览 1000 字符
        listOf("a", "b", "c", "d").forEach { k ->
            state.ledger[k] = DecisionDialogService.LedgerRow(k, "fx-$k", preview)
        }
        state.ledgerTokens = state.ledger.values.sumOf { service.estimateTokens(it.preview) }
        val beforeTokens = state.ledgerTokens

        service.buildLedgerContent(state)

        // 4×1000 > 3000 聚合预算 → 最旧 1 条被淘汰，保留最近 3 条
        assertEquals(3, state.ledger.size)
        assertFalse(state.ledger.containsKey("a"))
        assertTrue(state.ledger.containsKey("b"))
        assertTrue(state.ledger.containsKey("c"))
        assertTrue(state.ledger.containsKey("d"))
        // token 预算同步扣减最旧一条
        assertEquals(beforeTokens - service.estimateTokens(preview), state.ledgerTokens)
    }

    @Test
    fun evictLedgerIfNeeded_预算超限_FIFO淘汰_保护最近N条() {
        val state = DecisionDialogService.SessionDecisionState()
        val preview = "y".repeat(2000) // estimateTokens = (4000+1)/3 = 1333
        repeat(10) { i ->
            state.ledger["k$i"] = DecisionDialogService.LedgerRow("k$i", "fx-$i", preview)
        }
        state.ledgerTokens = 10 * service.estimateTokens(preview) // 13330 > 8000 预算

        service.evictLedgerIfNeeded(state)

        // k0..k3 被淘汰，k4、k5..k9 保留（k4 是第 6 旧，保护圈外但淘汰到预算即停）
        assertEquals(6, state.ledger.size)
        assertFalse(state.ledger.containsKey("k0"))
        assertFalse(state.ledger.containsKey("k3"))
        assertTrue(state.ledger.containsKey("k4"))
        assertTrue(state.ledger.containsKey("k9"))
        // 每次淘汰删 1333，4 次后回到预算内
        assertEquals(13330 - 4 * service.estimateTokens(preview), state.ledgerTokens)
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