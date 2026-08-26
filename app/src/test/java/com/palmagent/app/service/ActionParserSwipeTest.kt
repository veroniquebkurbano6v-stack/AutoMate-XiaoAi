package com.palmagent.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ActionParser SWIPE 方向/距离字段解析单元测试
 *
 * 验证方向滚动合并后模型的 swipe 输出能被正确解析：
 * - direction=up/down/left/right/custom → action.direction
 * - distance 数字/数字字符串 → action.distance（正数，非法→null）
 * - 非法 direction → null（SwipeTool 侧再按 custom 兜底）
 * - 旧坐标格式（coordinate/coordinate_end）回归：direction 缺省仍可精确滑动
 */
class ActionParserSwipeTest {

    @Test
    fun `swipe with direction and distance parses`() {
        val response = """{"type":"swipe","direction":"down","distance":500,"description":"向下滚动查看更多"}"""
        val action = ActionParser.parseActionFromResponse(response, null)
        assertEquals("swipe", action.type)
        assertEquals("down", action.direction)
        assertEquals(500, action.distance)
    }

    @Test
    fun `swipe direction up parses`() {
        val response = """{"type":"swipe","direction":"up","description":"回到顶部"}"""
        val action = ActionParser.parseActionFromResponse(response, null)
        assertEquals("swipe", action.type)
        assertEquals("up", action.direction)
    }

    /** 模型可能输出字符串距离（如 "600"），需兼容 */
    @Test
    fun `distance as numeric string parses`() {
        val response = """{"type":"swipe","direction":"left","distance":"600","description":"切换标签"}"""
        val action = ActionParser.parseActionFromResponse(response, null)
        assertEquals("swipe", action.type)
        assertEquals("left", action.direction)
        assertEquals(600, action.distance)
    }

    /** 浮点距离（300.0）转 Int */
    @Test
    fun `distance as float parses`() {
        val response = """{"type":"swipe","direction":"right","distance":300.0,"description":"切换标签"}"""
        val action = ActionParser.parseActionFromResponse(response, null)
        assertEquals(300, action.distance)
    }

    @Test
    fun `invalid direction is ignored`() {
        val response = """{"type":"swipe","direction":"diagonal","description":"非法方向"}"""
        val action = ActionParser.parseActionFromResponse(response, null)
        assertEquals("swipe", action.type)
        assertNull(action.direction)
    }

    /** 非法/非正数 distance → null，SwipeTool 侧回退默认距离 */
    @Test
    fun `invalid distance is ignored`() {
        val response = """{"type":"swipe","direction":"down","distance":-100,"description":"向下滚动"}"""
        val action = ActionParser.parseActionFromResponse(response, null)
        assertEquals("swipe", action.type)
        assertNull(action.distance)
    }

    @Test
    fun `nonNumeric distance is ignored`() {
        val response = """{"type":"swipe","direction":"down","distance":"abc","description":"向下滚动"}"""
        val action = ActionParser.parseActionFromResponse(response, null)
        assertEquals("swipe", action.type)
        assertNull(action.distance)
    }

    /** 缺省 direction/distance → null，不影响旧坐标格式解析 */
    @Test
    fun `legacy coordinate format still parses`() {
        val response = """{"type":"swipe","coordinate":[100,200],"coordinate_end":[540,800],"description":"全面屏返回"}"""
        val action = ActionParser.parseActionFromResponse(response, null)
        assertEquals("swipe", action.type)
        assertNull(action.direction)
        assertNull(action.distance)
        assertEquals(100, action.coordinate!!.x)
        assertEquals(200, action.coordinate!!.y)
        assertEquals(540, action.coordinateEnd!!.x)
        assertEquals(800, action.coordinateEnd!!.y)
    }
}