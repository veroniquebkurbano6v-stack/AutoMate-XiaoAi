package com.palmagent.app.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * SwipeTool.planSwipe 纯函数单元测试
 *
 * 覆盖方向滚动合并（scroll_down/up/left/right → swipe+direction）后的坐标计算：
 * - 方向模式：默认起点居中、默认距离一屏80%、方向与手指滑动方向相反、越界 clamp
 * - custom 模式：显式起终点、缺终点返回 null、坐标越界返回 null
 * - distance 参数：显式值生效、过小 clamp 到下限、过大 clamp 到主轴
 * - 屏幕尺寸非法返回 null
 */
class SwipeToolTest {

    private lateinit var tool: SwipeTool

    private val screenW = 1080
    private val screenH = 2400

    @Before
    fun setUp() {
        tool = SwipeTool()
    }

    @Test
    fun `custom_with_explicit_points_returns_path`() {
        val path = tool.planSwipe(
            mapOf("direction" to "custom", "start_x" to 100, "start_y" to 200, "end_x" to 900, "end_y" to 2200),
            screenW, screenH
        )
        assertEquals(SwipePath(100, 200, 900, 2200), path)
    }

    /** 旧调用兼容：不传 direction 时默认走 custom，需要 end_x/end_y */
    @Test
    fun `legacy_call_without_direction_uses_custom`() {
        val path = tool.planSwipe(
            mapOf("start_x" to 540, "start_y" to 1200, "end_x" to 540, "end_y" to 800),
            screenW, screenH
        )
        assertEquals(SwipePath(540, 1200, 540, 800), path)
    }

    @Test
    fun `custom_missing_end_returns_null`() {
        val path = tool.planSwipe(
            mapOf("direction" to "custom", "start_x" to 100, "start_y" to 100),
            screenW, screenH
        )
        assertNull(path)
    }

    @Test
    fun `custom_start_out_of_bounds_returns_null`() {
        val path = tool.planSwipe(
            mapOf("direction" to "custom", "start_x" to 9999, "start_y" to 100, "end_x" to 900, "end_y" to 2200),
            screenW, screenH
        )
        assertNull(path)
    }

    @Test
    fun `custom_end_out_of_bounds_returns_null`() {
        val path = tool.planSwipe(
            mapOf("direction" to "custom", "start_x" to 100, "start_y" to 100, "end_x" to 9999, "end_y" to 2200),
            screenW, screenH
        )
        assertNull(path)
    }

    /** direction=down：看下方内容 = 手指向上滑 = endY < startY，X 不变（垂直居中） */
    @Test
    fun `direction_down_defaults_mid_screen_and_swipes_up`() {
        val path = tool.planSwipe(mapOf("direction" to "down"), screenW, screenH)
        // 起点主轴居中：startY = 2400*0.5 = 1200；默认距离 2400*0.8=1920 → endY = 1200-1920 → clamp 0
        assertEquals((screenH * 0.5).toInt(), path!!.startY)
        assertEquals(path.startX, path.endX)
        assertEquals(0, path.endY)
        assert(path.endY < path.startY)
    }

    /** direction=up：看上方内容 = 手指向下滑 = endY > startY */
    @Test
    fun `direction_up_swipes_down`() {
        val path = tool.planSwipe(mapOf("direction" to "up"), screenW, screenH)
        assertEquals((screenH * 0.5).toInt(), path!!.startY)
        assert(path.endY > path.startY)
        assertEquals(path.startX, path.endX)
    }

    /** direction=left：查看左侧内容 = 手指向右滑 = endX > startX，Y 不变 */
    @Test
    fun `direction_left_swipes_right`() {
        val path = tool.planSwipe(mapOf("direction" to "left"), screenW, screenH)
        assertEquals((screenW * 0.5).toInt(), path!!.startX)
        assert(path.endX > path.startX)
        assertEquals(path.startY, path.endY)
    }

    /** direction=right：查看右侧内容 = 手指向左滑 = endX < startX */
    @Test
    fun `direction_right_swipes_left`() {
        val path = tool.planSwipe(mapOf("direction" to "right"), screenW, screenH)
        assertEquals((screenW * 0.5).toInt(), path!!.startX)
        assert(path.endX < path.startX)
        assertEquals(path.startY, path.endY)
    }

    /** 显式 distance：滑动距离精确生效 */
    @Test
    fun `direction_down_with_explicit_distance`() {
        val path = tool.planSwipe(
            mapOf("direction" to "down", "distance" to 300),
            screenW, screenH
        )
        assertEquals((screenH * 0.5).toInt(), path!!.startY)
        assertEquals((screenH * 0.5).toInt() - 300, path.endY)
    }

    /** 显式 start_y：起点跟随用户指定 */
    @Test
    fun `direction_down_with_explicit_start_y`() {
        val path = tool.planSwipe(
            mapOf("direction" to "down", "start_y" to 2000, "distance" to 300),
            screenW, screenH
        )
        assertEquals(2000, path!!.startY)
        assertEquals(1700, path.endY)
    }

    /** distance 过小（0/负数经 optionalInt 提升）clamp 到最小距离，避免假滑动 */
    @Test
    fun `direction_down_tiny_distance_clamped_to_min`() {
        val path = tool.planSwipe(
            mapOf("direction" to "down", "distance" to 1),
            screenW, screenH
        )
        val startY = (screenH * 0.5).toInt()
        assertEquals(startY - 10, path!!.endY)
    }

    /** distance 过大 clamp 到主轴长度，终点不越界 */
    @Test
    fun `direction_down_huge_distance_clamped`() {
        val path = tool.planSwipe(
            mapOf("direction" to "down", "distance" to 99999),
            screenW, screenH
        )
        assertEquals(0, path!!.endY) // startY - 大距离 → 越界被 clamp 到 0
    }

    /** 非法 direction 视为 custom（需要 end），无 end 返回 null */
    @Test
    fun `invalid_direction_treated_as_custom`() {
        val path = tool.planSwipe(mapOf("direction" to "diagonal"), screenW, screenH)
        assertNull(path)
    }

    @Test
    fun `invalid_screen_size_returns_null`() {
        val path = tool.planSwipe(mapOf("direction" to "down"), 0, 0)
        assertNull(path)
    }

    /** 参数 schema：direction 枚举值正确声明（供 LLM 提示生成） */
    @Test
    fun `parameters_declare_direction_enum`() {
        val directionParam = tool.getParameters().first { it.name == "direction" }
        assertEquals(listOf("up", "down", "left", "right", "custom"), directionParam.enumValues)
        assertEquals("custom", directionParam.default)
    }
}