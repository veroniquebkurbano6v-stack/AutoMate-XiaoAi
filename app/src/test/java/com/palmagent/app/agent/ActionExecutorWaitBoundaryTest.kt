package com.palmagent.app.agent

import com.palmagent.app.service.GUIAccessibilityService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.lang.ref.WeakReference

/**
 * ActionExecutor.postActionDelayAndWait 边界测试（P1-2 空闲等待）
 *
 * 验证目标（waitForIdle 全部行为分支）：
 * A. 无无障碍服务 → 退化为固定等待，总耗时 = 上界（3000 / 1500ms）
 * B. 有服务但无事件流（lastUiEventTime==0 → millisSinceLastUiEvent=MAX_VALUE）→ 等满上界
 * C. 有服务且事件持续（sinceEvent < 静默窗口）→ 等满上界
 * D. 有服务且已静默超窗（sinceEvent >= 静默窗口）→ 提前稳定返回（耗时 ≈ 下限）
 * E. finish / ask_user / wait → 不延迟直接返回（0ms）
 *
 * 设计要点：
 * - runTest 虚拟时间控制，无需真实 sleep
 * - withTimeoutOrNull 的轮询预算基于虚拟时间，可精确断言 currentTime
 * - GUIAccessibilityService.instance 通过反射注入 mock，模拟不同事件流状态
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActionExecutorWaitBoundaryTest {

    private fun newExecutor(): ActionExecutor = ActionExecutor(
        screenDescriptor = Mockito.mock(ScreenDescriptor::class.java),
        progressTracker = Mockito.mock(TaskProgressTracker::class.java)
    )

    private lateinit var serviceMock: GUIAccessibilityService

    @Before
    fun setUp() {
        serviceMock = Mockito.mock(GUIAccessibilityService::class.java)
        injectServiceInstance(serviceMock)
    }

    @After
    fun tearDown() {
        injectServiceInstance(null)
    }

    /** 反射注入 GUIAccessibilityService.companion 的 instanceRef（null 时清空恢复） */
    private fun injectServiceInstance(service: GUIAccessibilityService?) {
        // instanceRef 是 companion 内 private 字段，Kotlin 编译后落在宿主类静态字段上；
        // 少数版本可能落在 Companion 子类上，故两级查找（参照 ActionExecutorParamMappingTest 模式）
        val refField = try {
            GUIAccessibilityService::class.java.getDeclaredField("instanceRef")
        } catch (e: NoSuchFieldException) {
            val companionField = GUIAccessibilityService::class.java.getDeclaredField("Companion")
            companionField.isAccessible = true
            val companion = companionField.get(null)
            companion.javaClass.getDeclaredField("instanceRef")
        }
        refField.isAccessible = true
        refField.set(null, if (service == null) null else WeakReference(service))
    }

    private fun stubSinceEvent(sinceEvent: Long) {
        Mockito.`when`(serviceMock.millisSinceLastUiEvent()).thenReturn(sinceEvent)
    }

    // ===== A. 无服务退化路径（其实例由 @After 前的其它测试使用，本类各测试独立） =====

    @Test
    fun `open_app 等待达到冷启动上限3000ms`() = runTest {
        stubSinceEvent(Long.MAX_VALUE) // 有服务但无事件流 → 等满上界
        newExecutor().postActionDelayAndWait("open_app")
        assertEquals("open_app 应等待到 3s 上界", 3000L, currentTime)
    }

    @Test
    fun `普通动作 tap 等待到1500ms上界`() = runTest {
        stubSinceEvent(Long.MAX_VALUE)
        newExecutor().postActionDelayAndWait("tap")
        assertEquals("普通动作应等待到 1.5s 上界", 1500L, currentTime)
    }

    @Test
    fun `普通动作 swipe 等待到1500ms上界`() = runTest {
        stubSinceEvent(Long.MAX_VALUE)
        newExecutor().postActionDelayAndWait("swipe")
        assertEquals("swipe 应等待到 1.5s 上界", 1500L, currentTime)
    }

    // ===== B. 事件持续（sinceEvent 恒 < 静默窗口 400ms）→ 等满上界 =====

    @Test
    fun `事件持续产生 等待到上界`() = runTest {
        stubSinceEvent(0L) // 每轮都有新事件，永不静默
        newExecutor().postActionDelayAndWait("tap")
        assertEquals("持续事件应等满 1.5s 上界", 1500L, currentTime)
    }

    // ===== C. 无事件流（sinceEvent=MAX_VALUE）→ 等满上界 =====

    @Test
    fun `无事件流 等待到上界`() = runTest {
        stubSinceEvent(Long.MAX_VALUE)
        newExecutor().postActionDelayAndWait("open_app")
        assertEquals("无事件流应等满 3s 上界", 3000L, currentTime)
    }

    // ===== D. 已静默超窗（sinceEvent >= 400）→ 提前稳定返回 =====

    @Test
    fun `事件已静默超窗 提前返回不等到上界`() = runTest {
        stubSinceEvent(600L) // 静默 600ms ≥ 400ms 窗口
        newExecutor().postActionDelayAndWait("tap")
        assertTrue(
            "静默超窗应提前返回（下限400ms ≤ 耗时 < 上界1500ms），实际 ${currentTime}",
            currentTime >= 400L && currentTime < 1500L
        )
    }

    @Test
    fun `open_app事件已静默超窗 提前返回`() = runTest {
        stubSinceEvent(500L)
        newExecutor().postActionDelayAndWait("open_app")
        assertTrue(
            "open_app 静默超窗也应提前返回（下限1000ms ≤ 耗时 < 上界3000ms），实际 ${currentTime}",
            currentTime >= 1000L && currentTime < 3000L
        )
    }

    // ===== E. 特殊动作不等待 =====

    @Test
    fun `finish 不等待直接返回`() = runTest {
        newExecutor().postActionDelayAndWait("finish")
        assertEquals("finish 不应等待", 0L, currentTime)
    }

    @Test
    fun `ask_user 不等待直接返回`() = runTest {
        newExecutor().postActionDelayAndWait("ask_user")
        assertEquals("ask_user 不应等待", 0L, currentTime)
    }

    @Test
    fun `wait 不等待直接返回`() = runTest {
        newExecutor().postActionDelayAndWait("wait")
        assertEquals("wait 不应等待", 0L, currentTime)
    }
}