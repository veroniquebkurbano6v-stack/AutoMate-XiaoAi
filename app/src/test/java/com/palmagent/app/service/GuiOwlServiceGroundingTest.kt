package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GUI-Plus GROUND 定位请求响应解析测试（模拟执行模型发出的 gui 定位请求）：
 * 只验证"模型遵照提示词输出合理格式"的正反两面——
 * ① 合规输出（<tool_call> + click + coordinate 数组）能解析成功；
 * ② 违规输出（无坐标动作，如 open）被拦截判定失败（根因回归）。
 * 用真实截图尺寸（test_screenshot.png = 1305x942）。
 */
class GuiOwlServiceGroundingTest {

    /** test_screenshot.png 真实尺寸（assets 截图） */
    private val screenWidth = 1305
    private val screenHeight = 942

    @Test
    fun `grounding_遵照提示词的合规输出_解析成功且坐标在屏幕范围内`() {
        val content =
            "<tool_call>{\"name\":\"mobile_use\",\"arguments\":{\"action\":\"click\",\"coordinate\":[500,400]}}</tool_call>"
        val result = GuiOwlService.parseGroundingResponse(content, screenWidth, screenHeight, 0)

        assertTrue("合规输出应解析成功: ${result.error}", result.success)
        assertNotNull("应有坐标", result.coordinate)
        result.coordinate!!
        assertTrue("x 应在屏幕范围内", result.coordinate!!.x > 0 && result.coordinate!!.x <= screenWidth)
        assertTrue("y 应在屏幕范围内", result.coordinate!!.y > 0 && result.coordinate!!.y <= screenHeight)
        assertEquals("动作应归一化", "click", result.action)
    }

    @Test
    fun `grounding_违规输出无坐标动作_判定失败被拦截`() {
        // 模型不遵照定位提示词（输出 open 等无坐标动作）→ 应被拦截判定失败（根因回归）
        val content =
            "<tool_call>{\"name\":\"mobile_use\",\"arguments\":{\"action\":\"open\",\"text\":\"39互联网医院\"}}</tool_call>"
        val result = GuiOwlService.parseGroundingResponse(content, screenWidth, screenHeight, 0)

        assertTrue("无坐标动作应判定失败", !result.success)
        assertEquals("响应中未找到有效坐标", result.error)
    }
}
