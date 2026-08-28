package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 视觉执行（DECIDE）20 秒超时机制守护测试。
 *
 * 背景：实机日志（1.log）曾出现视觉执行第一轮无响应——[DECIDE]请求发出后请求挂起，
 * 当时 GuiOwlService 共享 client 的 readTimeout 默认 120 秒（KVUtils.getGuiOwlReadTimeout），
 * 挂起需等 2 分钟才超时（日志在 22:03:57 [DECIDE]请求后无任何响应/重试日志即结束）。
 * 修复：DECIDE 改用专用 decideClient（connect 固定 20 秒 + read 默认 20 秒，
 * 可经 KVUtils.getGuiOwlDecideTimeout() 配置），超时后走既有
 * 「超时→DecideResult 失败→decideViaVision 返回 null→WAIT 重试」链路（decide 内 SocketTimeoutException catch）。
 */
class GuiOwlServiceDecideTimeoutTest {

    @Test
    fun `DECIDE 超时机制必须保持 20 秒`() {
        assertEquals(
            "视觉执行超时必须为 20 秒（防止回归到 120 秒导致请求挂起 2 分钟无响应）",
            20_000L,
            GuiOwlService.DECIDE_TIMEOUT_MS
        )
    }
}
