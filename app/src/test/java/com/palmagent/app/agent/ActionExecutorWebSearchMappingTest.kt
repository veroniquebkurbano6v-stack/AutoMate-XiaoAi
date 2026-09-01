package com.palmagent.app.agent

import com.palmagent.app.AgentApplication
import com.palmagent.app.model.AgentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

/**
 * ActionExecutor.buildActionParams 对 WEB_SEARCH 的参数映射测试。
 *
 * 契约：query 主取（协议字段）、text 兜底兼容旧格式；mode 透传。
 * 使用反射调用 private 方法（无法通过公开入口构造 AgentAction 输入）。
 */
class ActionExecutorWebSearchMappingTest {

    private lateinit var executor: ActionExecutor
    private lateinit var buildActionParams: Method

    @Before
    fun setUp() {
        // 反射替换 AgentApplication.instance（getScreenSize 依赖），防真实 Android 单例报错
        try {
            val field = AgentApplication::class.java.getDeclaredField("instance")
            field.isAccessible = true
            if (!field.isAccessible || field.get(null) == null) {
                field.set(null, AgentApplication())
            }
        } catch (_: Exception) {
            // 测试环境无 Application：若失败则跳过屏幕尺寸依赖路径（web_search 分支不依赖 getScreenSize）
        }

        executor = ActionExecutor(
            screenDescriptor = ScreenDescriptor(),
            progressTracker = TaskProgressTracker()
        )
        val m = ActionExecutor::class.java.getDeclaredMethod("buildActionParams", AgentAction::class.java)
        m.isAccessible = true
        buildActionParams = m
    }

    @Suppress("UNCHECKED_CAST")
    private fun map(action: AgentAction): Map<String, Any?> =
        buildActionParams.invoke(executor, action) as Map<String, Any?>

    @Test
    fun `web_search_query_mode_完整映射`() {
        val action = AgentAction(
            type = "web_search",
            query = "XX医院如何挂号",
            mode = "ai",
            description = "搜索",
            confidence = 0.9f
        )
        val params = map(action)
        assertEquals("query 应映射", "XX医院如何挂号", params["query"])
        assertEquals("mode 应透传", "ai", params["mode"])
    }

    @Test
    fun `web_search_text兜底_当query缺失`() {
        val action = AgentAction(
            type = "web_search",
            text = "XX医院电话",
            description = "搜索",
            confidence = 0.8f
        )
        val params = map(action)
        assertEquals("text 应兜底为 query", "XX医院电话", params["query"])
    }

    @Test
    fun `web_search_query优先于text`() {
        val action = AgentAction(
            type = "web_search",
            query = "主字段",
            text = "兜底字段",
            description = "搜索",
            confidence = 0.8f
        )
        val params = map(action)
        assertEquals("query 应优先", "主字段", params["query"])
    }

    @Test
    fun `web_search_mode缺省_不透传`() {
        val action = AgentAction(
            type = "web_search",
            query = "今日天气",
            description = "搜索",
            confidence = 0.8f
        )
        val params = map(action)
        assertEquals("今日天气", params["query"])
        assertFalse("mode 缺失时不注入（下游默认 web）", params.containsKey("mode"))
    }

    @Test
    fun `web_search_query与mode均缺失_不崩溃`() {
        val action = AgentAction(type = "web_search", description = "搜索", confidence = 0.5f)
        val params = map(action)
        assertNotNull("不崩溃且返回 map", params)
        // web_search 分支不注入空 query/mode（缺参属模型违约，由工具层校验）
        assertFalse(params.containsKey("query"))
        assertFalse(params.containsKey("mode"))
    }
}
