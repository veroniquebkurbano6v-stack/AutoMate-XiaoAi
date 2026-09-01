package com.palmagent.app.agent

import com.palmagent.app.AgentApplication
import com.palmagent.app.model.AgentAction
import com.palmagent.app.model.Coordinate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.lang.reflect.Method

@Suppress("UNCHECKED_CAST")

/**
 * ActionExecutor.buildActionParams 工具参数映射测试（v7）
 *
 * 验证目标（OPEN_APP / LOCATE / REQUEST_USER_ACTION 的参数映射）：
 * 1. OPEN_APP: text → app_name，description 兜底
 * 2. LOCATE: description → tool.description，text → text
 * 3. REQUEST_USER_ACTION: text → title，description → steps
 * 4. 回归 AUTO_INPUT 不受影响
 *
 * 设计要点：
 * - 反射调用 private 方法 buildActionParams(action: AgentAction)
 * - 反射注入 AgentApplication.instance 为 mock（getScreenSize 不抛 NPE）
 * - getScreenSize 返回 0x0，coordinate 检查自动 false，不影响 text/description 映射
 */
class ActionExecutorParamMappingTest {

    private lateinit var executor: ActionExecutor
    private var originalInstance: AgentApplication? = null
    private lateinit var buildParamsMethod: Method

    @Before
    fun setUp() {
        // 注入 mock AgentApplication，避免 getScreenSize NPE
        // 注意：Kotlin companion object 的字段实际定义在外部类，但需要 setAccessible
        val instanceField = try {
            AgentApplication::class.java.getDeclaredField("instance")
        } catch (e: NoSuchFieldException) {
            // 字段在 Companion 子类上
            AgentApplication.Companion::class.java.getDeclaredField("instance")
        }
        instanceField.isAccessible = true
        originalInstance = instanceField.get(null) as? AgentApplication
        instanceField.set(null, Mockito.mock(AgentApplication::class.java))

        // 创建 ActionExecutor 实例
        executor = ActionExecutor(
            screenDescriptor = Mockito.mock(ScreenDescriptor::class.java),
            progressTracker = Mockito.mock(TaskProgressTracker::class.java)
        )

        // 反射拿到 private 方法
        buildParamsMethod = ActionExecutor::class.java.getDeclaredMethod(
            "buildActionParams", AgentAction::class.java
        )
        buildParamsMethod.isAccessible = true
    }

    @After
    fun tearDown() {
        val instanceField = try {
            AgentApplication::class.java.getDeclaredField("instance")
        } catch (e: NoSuchFieldException) {
            AgentApplication.Companion::class.java.getDeclaredField("instance")
        }
        instanceField.isAccessible = true
        instanceField.set(null, originalInstance)
    }

    // ==================== OPEN_APP 映射（3 个）====================

    @Test
    fun `OPEN_APP text_mapped_to_app_name`() {
        val action = AgentAction(
            type = "open_app",
            text = "com.tencent.mm",
            description = "打开微信应用",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertEquals("com.tencent.mm", params["app_name"])
    }

    @Test
    fun `OPEN_APP description_fallback_to_app_name`() {
        val action = AgentAction(
            type = "open_app",
            text = null,
            description = "微信",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertEquals("微信", params["app_name"])
    }

    @Test
    fun `OPEN_APP no_text_no_description_app_name_is_null`() {
        val action = AgentAction(
            type = "open_app",
            text = null,
            description = "",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertNull(params["app_name"])
    }

    // ==================== LOCATE 映射（3 个）====================

    @Test
    fun `LOCATE description_and_text_dual_field_mapping`() {
        val action = AgentAction(
            type = "locate",
            text = "搜索",
            description = "搜索图标,放大镜,顶部",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertEquals("搜索图标,放大镜,顶部", params["description"])
        assertEquals("搜索", params["text"])
    }

    @Test
    fun `LOCATE description_missing_targetDesc_fallback`() {
        val action = AgentAction(
            type = "locate",
            text = "搜索",
            description = "",
            targetDesc = "搜索框",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertEquals("搜索框", params["description"])
        assertEquals("搜索", params["text"])
    }

    @Test
    fun `LOCATE no_description_no_targetDesc_description_null`() {
        val action = AgentAction(
            type = "locate",
            text = "搜索",
            description = "",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertNull(params["description"])
        assertEquals("搜索", params["text"])
    }

    // ==================== REQUEST_USER_ACTION 映射（3 个）====================

    @Test
    fun `REQUEST_USER_ACTION text_to_title_description_to_steps`() {
        val action = AgentAction(
            type = "request_user_action",
            text = "请确认支付",
            description = "点击确认按钮",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertEquals("请确认支付", params["title"])
        assertEquals("点击确认按钮", params["steps"])
    }

    @Test
    fun `REQUEST_USER_ACTION text_missing_description_fallback_to_title`() {
        val action = AgentAction(
            type = "request_user_action",
            text = null,
            description = "请支付",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertEquals("请支付", params["title"])
    }

    @Test
    fun `REQUEST_USER_ACTION description_missing_text_fallback_to_steps`() {
        val action = AgentAction(
            type = "request_user_action",
            text = "请确认",
            description = "",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertEquals("请确认", params["title"])
        assertEquals("请确认", params["steps"])
    }

    // ==================== 回归测试（3 个）====================

    /**
     * v10（b634a85）：AUTO_INPUT 定位参数由 search_icon 重构为 is_text_input_box，
     * 该参数现由 AutoInputTool 直接从工具参数读取，不再经 AgentAction/buildActionParams 映射。
     * 回归验证：instruction 与 text 映射保持不受影响。
     */
    @Test
    fun `AUTO_INPUT instruction_and_text_regression`() {
        val action = AgentAction(
            type = "auto_input",
            text = "测试文本",
            description = "测试输入",
            instruction = "搜索框",
            confidence = 0.9f
        )
        val params = buildParamsMethod.invoke(executor, action) as Map<String, Any?>
        assertEquals("搜索框", params["instruction"])
        assertEquals("测试文本", params["text"])
    }

    // ==================== SWIPE 参数映射（方向滚动合并，5 个）====================

    /** 反射调用 private buildSwipeParams(action: AgentAction) */
    private fun buildSwipeParams(action: AgentAction): Map<String, Any> {
        val method = ActionExecutor::class.java.getDeclaredMethod("buildSwipeParams", AgentAction::class.java)
        method.isAccessible = true
        return method.invoke(executor, action) as Map<String, Any>
    }

    @Test
    fun `SWIPE direction_down_forwards_direction_and_start`() {
        val action = AgentAction(
            type = "swipe",
            direction = "down",
            description = "向下滚动",
            confidence = 0.9f
        )
        val params = buildSwipeParams(action)
        assertEquals("down", params["direction"])
        assertEquals(0, params["start_x"]) // mock 屏幕 0x0 + 无 coordinate → 默认中部
        assertEquals(0, params["start_y"])
    }

    @Test
    fun `SWIPE direction_mode_forwards_distance`() {
        val action = AgentAction(
            type = "swipe",
            direction = "up",
            distance = 400,
            description = "向上滚动",
            confidence = 0.9f
        )
        val params = buildSwipeParams(action)
        assertEquals("up", params["direction"])
        assertEquals(400, params["distance"])
    }

    @Test
    fun `SWIPE custom_coordinate_end_forwards_end_points`() {
        val action = AgentAction(
            type = "swipe",
            direction = "custom",
            coordinate = Coordinate(100, 200),
            coordinateEnd = Coordinate(540, 800),
            description = "精确滑动",
            confidence = 0.9f
        )
        val params = buildSwipeParams(action)
        assertEquals("custom", params["direction"])
        assertEquals(100, params["start_x"])
        assertEquals(200, params["start_y"])
        assertEquals(540, params["end_x"])
        assertEquals(800, params["end_y"])
    }

    /** 旧坐标格式（无 direction，仅 coordinate_end）→ 仍走 custom 精确滑动 */
    @Test
    fun `SWIPE legacy_coordinate_end_still_custom`() {
        val action = AgentAction(
            type = "swipe",
            coordinate = Coordinate(540, 1200),
            coordinateEnd = Coordinate(540, 400),
            description = "向上滑动",
            confidence = 0.9f
        )
        val params = buildSwipeParams(action)
        assertEquals("custom", params["direction"])
        assertEquals(540, params["start_x"])
        assertEquals(1200, params["start_y"])
        assertEquals(540, params["end_x"])
        assertEquals(400, params["end_y"])
    }
}
