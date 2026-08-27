package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GUI-Plus GROUND 定位请求响应解析测试（模拟执行模型发出的 gui 定位请求）：
 * 用真实截图尺寸（test_screenshot.png = 1305x942）喂入多种模型响应格式，
 * 验证解析判定路径与失败追溯载体（rawResponse 保留原始内容，配合失败日志 "| raw: ..." 追溯模型实际输出）。
 */
class GuiOwlServiceGroundingTest {

    /** test_screenshot.png 真实尺寸（assets 截图） */
    private val screenWidth = 1305
    private val screenHeight = 942

    @Test
    fun `grounding_正确tool_call格式_解析成功且坐标在屏幕范围内`() {
        val content =
            "<tool_call>{\"name\":\"mobile_use\",\"arguments\":{\"action\":\"click\",\"coordinate\":[500,400]}}</tool_call>"
        val result = GuiOwlService.parseGroundingResponse(content, screenWidth, screenHeight, 0)

        assertTrue("应解析成功: ${result.error}", result.success)
        assertNotNull("应有坐标", result.coordinate)
        result.coordinate!!
        assertTrue("x 应在屏幕范围内", result.coordinate!!.x > 0 && result.coordinate!!.x <= screenWidth)
        assertTrue("y 应在屏幕范围内", result.coordinate!!.y > 0 && result.coordinate!!.y <= screenHeight)
        assertEquals("动作应归一化", "click", result.action)
        assertTrue("rawResponse 应保留原始内容", result.rawResponse.contains("mobile_use"))
    }

    @Test
    fun `grounding_纯JSON无tool_call包裹_判定失败且rawResponse可追溯`() {
        val content = """{"name":"mobile_use","arguments":{"action":"click","coordinate":[500,400]}}"""
        val result = GuiOwlService.parseGroundingResponse(content, screenWidth, screenHeight, 0)

        assertTrue("无 tool_call 包裹应判定失败", !result.success)
        assertEquals("失败原因应为未找到有效坐标", "响应中未找到有效坐标", result.error)
        assertTrue("rawResponse 应保留模型原始输出供追溯", result.rawResponse.contains("mobile_use"))
    }

    @Test
    fun `grounding_coordinate为字符串形态_判定失败且rawResponse可追溯`() {
        // 模型把 coordinate 输出为字符串 "x,y"（与广告弹窗判定的字符串形态混淆）
        val content =
            "<tool_call>{\"name\":\"mobile_use\",\"arguments\":{\"action\":\"click\",\"coordinate\":\"500,400\"}}</tool_call>"
        val result = GuiOwlService.parseGroundingResponse(content, screenWidth, screenHeight, 0)

        assertTrue("字符串坐标应判定失败", !result.success)
        assertEquals("失败原因应为未找到有效坐标", "响应中未找到有效坐标", result.error)
        assertTrue("rawResponse 应保留模型原始输出供追溯", result.rawResponse.contains("500,400"))
    }

    @Test
    fun `grounding_coordinate数组不足2元素_判定失败`() {
        val content =
            "<tool_call>{\"name\":\"mobile_use\",\"arguments\":{\"action\":\"click\",\"coordinate\":[500]}}</tool_call>"
        val result = GuiOwlService.parseGroundingResponse(content, screenWidth, screenHeight, 0)

        assertTrue("坐标数组不足2元素应判定失败", !result.success)
        assertEquals("响应中未找到有效坐标", result.error)
    }

    @Test
    fun `grounding_响应截断_判定失败且rawResponse保留截断内容`() {
        val content = "<tool_call>{\"name\":\"mobile_use\",\"arguments\":{\"action\":\"click\",\"coordinat"
        val result = GuiOwlService.parseGroundingResponse(content, screenWidth, screenHeight, 0)

        assertTrue("截断响应应判定失败", !result.success)
        assertTrue("失败原因应非空", !result.error.isNullOrBlank())
        assertTrue("rawResponse 应保留截断原始内容供追溯", result.rawResponse.contains("coordinat"))
    }

    // ===== GROUND 专用定位 prompt 契约（fix：不复用官方通用 MOBILE_SYSTEM_PROMPT） =====

    @Test
    fun `groundPrompt_专用定位约束_仅定位动作且必带坐标`() {
        val prompt = GuiOwlService.buildGroundSystemPrompt()

        // 定位动作空间
        assertTrue("应包含 click", prompt.contains("click"))
        assertTrue("应包含 long_press", prompt.contains("long_press"))
        assertTrue("应包含 swipe", prompt.contains("swipe"))
        // 必须携带坐标数组
        assertTrue("应要求 coordinate 数组", prompt.contains("coordinate"))
        // 禁止自由动作（官方通用 prompt 允许、但会产生无坐标动作导致"未找到有效坐标"反复重试）
        assertTrue("应禁止 open", prompt.contains("open"))
        assertTrue("应禁止 type", prompt.contains("type"))
        assertTrue("应禁止 answer", prompt.contains("answer"))
        assertTrue("应禁止 terminate", prompt.contains("terminate"))
        assertTrue("应禁止 interact", prompt.contains("interact"))
        // 不再是官方通用 MOBILE_SYSTEM_PROMPT（其标志文本不应出现）
        assertTrue("不应复用官方通用 prompt（不含 You may call）", !prompt.contains("You may call one or more functions"))
        assertTrue("不应复用官方通用 prompt（不含 1000x1000 分辨率说明）", !prompt.contains("The screen's resolution is 1000x1000"))
    }
}
