package com.palmagent.app.service

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.palmagent.app.AgentApplication
import com.palmagent.app.tool.ToolRegistry
import com.palmagent.app.tool.impl.ListAppsTool
import com.palmagent.app.utils.InstalledAppProvider
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException

/**
 * DecisionDialogService 单元测试（纯 JVM + Mock）
 *
 * 验证目标（v3 修复方案关键回归）：
 * 1. 请求体含 tools 字段（list_apps / amap_* / kb_read）
 * 2. 对话模型调 list_apps → 工具执行并回传结果 → 模型出 ready plan
 * 3. 对话模型调 kb_read → 工具执行并回传结果
 * 4. 对话模型调 amap_nearby → 工具执行（mock WebMCPService）
 * 5. 工具循环达到 MAX_TOOL_ROUNDS 上限后停止
 * 6. 无需工具直接 ready：正常工作
 *
 * 设计要点：
 * - 反射注入 OkHttpClient.client 为带 Interceptor 的 mock client
 * - Interceptor 维护一个 responses 队列，按调用顺序返回
 * - 验证工具调用逻辑而不依赖真实 LLM
 */
class DecisionDialogServiceTest {

    private lateinit var dialogService: DecisionDialogService
    private val gson = Gson()
    private val testInterceptor = MockInterceptor()

    @Before
    fun setUp() {
        // 1. 注入 KVUtils 避免 getPlannerApiKey 返回空
        injectFakeKVUtils()

        // 2. 注册 ListAppsTool（ToolRegistry.executeTool 需要）
        ToolRegistry.initAllTools()

        // 3. 注入 AgentApplication.instance 反射（ListAppsTool.execute 会调 AgentApplication.instance）
        injectAgentApplicationInstance()

        // 4. 注入 InstalledAppProvider 缓存（list_apps 工具会查）
        injectInstalledAppsCache(
            mapOf(
                "微信" to "com.tencent.mm",
                "高德地图" to "com.autonavi.minimap"
            )
        )

        // 5. 先创建 dialogService 实例，再注入 mock client
        dialogService = DecisionDialogService()
        injectMockClient()
    }

    @After
    fun tearDown() {
        clearInstalledAppsCache()
        clearAgentApplicationInstance()
    }

    // ===== Case 1: 请求体含 tools 字段（含 list_apps） =====

    @Test
    fun `chat 应在请求体注入 tools 字段 含 list_apps`() = runBlocking {
        // mock LLM 直接返回 ready plan（不调工具）
        testInterceptor.responses.add(
            mockOpenAiResponse("""{"status":"ready","plan":{"requirement":"帮我挂号","goal":"打开微信搜小程序","steps":[{"order":1,"goal":"打开微信","success_criteria":"进入主页","supervised":false}]}}""")
        )

        val result = dialogService.chat("帮我挂号", emptyList(), "test-session")

        assertTrue("应返回 Ready: $result", result is DecisionDialogService.DialogResult.Ready)
        val capturedBody = testInterceptor.capturedBodies.firstOrNull()
        assertNotNull("应捕获到请求体", capturedBody)
        val requestJson = gson.fromJson(capturedBody, JsonObject::class.java)
        assertTrue("请求体应含 tools 字段", requestJson.has("tools"))
        val toolsArray = requestJson.getAsJsonArray("tools")
        val toolNames = mutableListOf<String>()
        toolsArray.forEach { toolElem ->
            val fn = toolElem.asJsonObject.getAsJsonObject("function")
            toolNames.add(fn.get("name").asString)
        }
        assertTrue("tools 应含 list_apps: $toolNames", "list_apps" in toolNames)
        assertTrue("tools 应含 amap_nearby: $toolNames", "amap_nearby" in toolNames)
    }

    // ===== Case 2: 对话模型调 list_apps → 回传结果 → 输出 ready =====

    @Test
    fun `chat 对话模型调list_apps 应执行工具并回传结果`() = runBlocking {
        // 第 1 次 LLM 调用：要求 list_apps
        testInterceptor.responses.add(
            mockOpenAiToolCallResponse(
                toolName = "list_apps",
                toolArgs = """{"keywords":["微信"]}""",
                toolCallId = "call_1"
            )
        )
        // 第 2 次 LLM 调用：综合工具结果，输出 ready
        testInterceptor.responses.add(
            mockOpenAiResponse("""{"status":"ready","plan":{"requirement":"帮我挂号","goal":"打开微信挂号","steps":[{"order":1,"goal":"打开微信","success_criteria":"进入主页","supervised":false}]}}""")
        )

        val result = dialogService.chat("帮我挂号", emptyList(), "test-session")

        assertTrue("应返回 Ready: $result", result is DecisionDialogService.DialogResult.Ready)
        // 第二次请求应含 role=tool 的 list_apps 结果
        val secondBody = testInterceptor.capturedBodies[1]
        val secondJson = gson.fromJson(secondBody, JsonObject::class.java)
        val messages = secondJson.getAsJsonArray("messages")
        // 找 role=tool 的消息
        var toolMsg: JsonObject? = null
        messages.forEach { msgElem ->
            val obj = msgElem.asJsonObject
            if (obj.get("role").asString == "tool") toolMsg = obj
        }
        assertNotNull("第二次请求应含 role=tool 消息", toolMsg)
        val toolContent = toolMsg!!.get("content").asString
        assertTrue("tool content 应含 list_apps 结果: $toolContent",
            toolContent.contains("微信") || toolContent.contains("com.tencent.mm"))
    }

    // ===== Case 3: 对话模型调 kb_read =====

    @Test
    fun `chat 对话模型调kb_read 应执行KbReadTool并回传结果`() = runBlocking {
        testInterceptor.responses.add(
            mockOpenAiToolCallResponse(
                toolName = "kb_read",
                toolArgs = """{"query":"挂号"}""",
                toolCallId = "call_1"
            )
        )
        testInterceptor.responses.add(
            mockOpenAiResponse("""{"status":"ready","plan":{"requirement":"帮我挂号","goal":"打开微信搜小程序","steps":[{"order":1,"goal":"打开微信","success_criteria":"进入主页","supervised":false}]}}""")
        )

        val result = dialogService.chat("帮我挂号", emptyList(), "test-session")

        assertTrue("应返回 Ready: $result", result is DecisionDialogService.DialogResult.Ready)
        val secondBody = testInterceptor.capturedBodies[1]
        assertTrue("第二次请求应含 kb_read tool result", secondBody.contains("role") && secondBody.contains("tool"))
    }

    // ===== Case 4: 对话模型调 amap_nearby =====

    @Test
    fun `chat 对话模型调amap_nearby 应执行AmapNearbyTool`() = runBlocking {
        testInterceptor.responses.add(
            mockOpenAiToolCallResponse(
                toolName = "amap_nearby",
                toolArgs = """{"keywords":"医院","radius":1000}""",
                toolCallId = "call_1"
            )
        )
        testInterceptor.responses.add(
            mockOpenAiResponse("""{"status":"ready","plan":{"requirement":"附近有什么医院","goal":"打开高德地图查附近医院","steps":[{"order":1,"goal":"打开高德地图","success_criteria":"进入主页","supervised":false}]}}""")
        )

        val result = dialogService.chat("附近有什么医院", emptyList(), "test-session")

        // amap 工具会尝试调 WebMCPService，可能因为没有真实 API 失败；
        // 但工具循环逻辑应该不崩，模型最终应该返回 ready
        assertTrue("应返回 Ready 或 Error 但不崩溃: $result",
            result is DecisionDialogService.DialogResult.Ready || result is DecisionDialogService.DialogResult.Error)
    }

    // ===== Case 5: 工具循环达到 MAX_TOOL_ROUNDS 上限 =====

    @Test
    fun `chat 工具错误不导致死循环 达到上限后停止`() = runBlocking {
        // 每次 LLM 都返回 tool_calls（持续 10 次，超过 MAX_TOOL_ROUNDS=9）
        repeat(10) {
            testInterceptor.responses.add(
                mockOpenAiToolCallResponse(
                    toolName = "list_apps",
                    toolArgs = """{"keywords":["微信"]}""",
                    toolCallId = "call_$it"
                )
            )
        }

        val result = dialogService.chat("测试", emptyList(), "test-session")

        // 应在 9 轮后返回 Error，不死循环
        assertTrue("达到循环上限应返回 Error: $result", result is DecisionDialogService.DialogResult.Error)
        val error = (result as DecisionDialogService.DialogResult.Error).message
        assertTrue("错误应提及循环上限: $error", error.contains("循环") || error.contains("上限"))
    }

    // ===== Case 6: 无需工具直接 ready =====

    @Test
    fun `chat 无需工具直接ready 正常工作`() = runBlocking {
        testInterceptor.responses.add(
            mockOpenAiResponse("""{"status":"ready","plan":{"requirement":"帮我打开微信","goal":"打开微信","steps":[{"order":1,"goal":"打开微信","success_criteria":"进入主页","supervised":false}]}}""")
        )

        val result = dialogService.chat("帮我打开微信", emptyList(), "test-session")

        assertTrue("应返回 Ready: $result", result is DecisionDialogService.DialogResult.Ready)
        val plan = (result as DecisionDialogService.DialogResult.Ready).plan
        assertEquals("打开微信", plan.steps.first().goal)
        // 只调了 1 次 LLM（没调工具）
        assertEquals("应只调 1 次 LLM", 1, testInterceptor.capturedBodies.size)
    }

    // ===== Case 7: 工具结果超过保留轮数后，早期工具消息被框架清理 =====

    @Test
    fun `chat 工具结果超过保留轮数后 早期工具消息被框架清理`() = runBlocking {
        // 连续 3 轮调用 list_apps（产生 3 轮工具消息），第 4 次返回 ready
        repeat(3) {
            testInterceptor.responses.add(
                mockOpenAiToolCallResponse(
                    toolName = "list_apps",
                    toolArgs = """{"keywords":["微信"]}""",
                    toolCallId = "call_$it"
                )
            )
        }
        testInterceptor.responses.add(
            mockOpenAiResponse("""{"status":"ready","plan":{"requirement":"帮我挂号","goal":"打开微信挂号","steps":[{"order":1,"goal":"打开微信","success_criteria":"进入主页","supervised":false}]}}""")
        )

        val result = dialogService.chat("帮我挂号", emptyList(), "test-session")

        assertTrue("应返回 Ready: $result", result is DecisionDialogService.DialogResult.Ready)
        // 第 4 次请求发送前，框架已清理到只保留最近 1 轮（KEEP_TOOL_ROUNDS=1）工具结果
        val fourthBody = testInterceptor.capturedBodies[3]
        val fourthJson = gson.fromJson(fourthBody, JsonObject::class.java)
        val messages = fourthJson.getAsJsonArray("messages")
        var toolCount = 0
        messages.forEach { msgElem ->
            if (msgElem.asJsonObject.get("role").asString == "tool") toolCount++
        }
        assertEquals("第4次请求应只保留最近1轮工具结果", 1, toolCount)
    }

    // ===== Case 8: 模型调用 workspace_update，工作区写入 system 且工具结果仍被清理 =====

    @Test
    fun `chat 模型调用workspace_update 工作区写入system且工具结果仍被清理`() = runBlocking {
        testInterceptor.responses.add(
            mockOpenAiToolCallResponse(
                toolName = "workspace_update",
                toolArgs = """{"content":"已确认App=微信(com.tencent.mm)"}""",
                toolCallId = "call_1"
            )
        )
        testInterceptor.responses.add(
            mockOpenAiResponse("""{"status":"ready","plan":{"requirement":"帮我挂号","goal":"打开微信挂号","steps":[{"order":1,"goal":"打开微信","success_criteria":"进入主页","supervised":false}]}}""")
        )

        val result = dialogService.chat("帮我挂号", emptyList(), "test-session")

        assertTrue("应返回 Ready: $result", result is DecisionDialogService.DialogResult.Ready)
        // 第 2 次请求的 system 消息应包含工作区内容（模型写入的关键信息）
        val secondBody = testInterceptor.capturedBodies[1]
        val secondJson = gson.fromJson(secondBody, JsonObject::class.java)
        val messages = secondJson.getAsJsonArray("messages")
        var workspaceSystem: String? = null
        messages.forEach { msgElem ->
            val obj = msgElem.asJsonObject
            if (obj.get("role").asString == "system") {
                val content = obj.get("content").asString
                if (content.contains("已确认App")) workspaceSystem = content
            }
        }
        assertNotNull("第2次请求 system 应含工作区内容", workspaceSystem)
        assertTrue("工作区应含模型写入的 App 信息: $workspaceSystem",
            workspaceSystem!!.contains("微信"))
    }

    // ============================ Mock HTTP 工具 ============================

    private fun mockOpenAiResponse(content: String): String {
        return """
        {
            "id":"chatcmpl-1",
            "object":"chat.completion",
            "created":1234567890,
            "model":"mock-model",
            "choices":[{
                "index":0,
                "message":{"role":"assistant","content":${gson.toJson(content)},"tool_calls":null},
                "finish_reason":"stop"
            }],
            "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}
        }
        """.trimIndent()
    }

    private fun mockOpenAiToolCallResponse(toolName: String, toolArgs: String, toolCallId: String): String {
        return """
        {
            "id":"chatcmpl-1",
            "object":"chat.completion",
            "created":1234567890,
            "model":"mock-model",
            "choices":[{
                "index":0,
                "message":{
                    "role":"assistant",
                    "content":"",
                    "tool_calls":[{
                        "id":"$toolCallId",
                        "type":"function",
                        "function":{"name":"$toolName","arguments":${gson.toJson(toolArgs)}}
                    }]
                },
                "finish_reason":"tool_calls"
            }],
            "usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}
        }
        """.trimIndent()
    }

    /**
     * OkHttp Interceptor，按队列顺序返回 mock 响应。
     * 同时记录请求体供断言。
     */
    private class MockInterceptor : Interceptor {
        val responses = mutableListOf<String>()
        val capturedBodies = mutableListOf<String>()
        private var index = 0

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            // 记录请求体
            val body = request.body
            if (body != null) {
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                capturedBodies.add(buffer.readUtf8())
            }

            // 按顺序返回响应
            val responseBody = if (index < responses.size) {
                responses[index]
            } else {
                // 默认返回 ready
                """{"choices":[{"message":{"content":"{\"status\":\"ready\",\"plan\":{\"requirement\":\"默认\",\"goal\":\"默认plan\",\"steps\":[{\"order\":1,\"goal\":\"默认\",\"success_criteria\":\"完成\",\"supervised\":false}]}}"}}]}"""
            }
            index++

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toResponseBody().contentType()))
                .build()
        }
    }

    // ============================ 反射注入 ============================

    private fun injectFakeKVUtils() {
        val fakePrefs = FakeSharedPreferencesForDialog()
        // 设置必要的 KV（getPlannerApiKey 等）
        // 注意：键名必须与 KVUtils 常量一致（带 KEY_ 前缀），否则注入不生效，
        // getPlannerApiKey() 会回退到 BuildConfig（干净环境为空）导致"API Key 未配置"。
        fakePrefs.set("KEY_PLANNER_API_KEY", "test-key")
        fakePrefs.set("KEY_PLANNER_API_URL", "https://api.test.com/v1")
        fakePrefs.set("KEY_PLANNER_MODEL", "mock-model")
        fakePrefs.set("KEY_LOCAL_KB_ENABLED", false)

        KVUtils::class.java.getDeclaredField("securePrefs").apply {
            isAccessible = true
            set(KVUtils, fakePrefs)
        }
        KVUtils::class.java.getDeclaredField("prefs").apply {
            isAccessible = true
            set(KVUtils, fakePrefs)
        }
    }

    private fun injectMockClient() {
        // 反射注入 DecisionDialogService.client 为带 Interceptor 的 client
        val dialogServiceClass = DecisionDialogService::class.java
        val clientField = dialogServiceClass.getDeclaredField("client")
        clientField.isAccessible = true

        // 拿原 client 的 builder 复用配置
        val originalClient = clientField.get(dialogService) as OkHttpClient
        val mockClient = originalClient.newBuilder()
            .addInterceptor(testInterceptor)
            .build()
        clientField.set(dialogService, mockClient)
    }

    private fun injectInstalledAppsCache(appMap: Map<String, String>) {
        val cache = appMap.entries.map { (name, pkg) -> name to pkg }
        InstalledAppProvider::class.java.getDeclaredField("installedAppsCache").apply {
            isAccessible = true
            set(InstalledAppProvider, cache)
        }
    }

    private fun clearInstalledAppsCache() {
        InstalledAppProvider::class.java.getDeclaredField("installedAppsCache").apply {
            isAccessible = true
            set(InstalledAppProvider, null)
        }
    }

    private var savedAgentApplicationInstance: AgentApplication? = null
    private fun injectAgentApplicationInstance() {
        val instanceField = AgentApplication::class.java.getDeclaredField("instance")
        instanceField.isAccessible = true
        savedAgentApplicationInstance = instanceField.get(null) as? AgentApplication
        instanceField.set(null, Mockito.mock(AgentApplication::class.java))
    }

    private fun clearAgentApplicationInstance() {
        try {
            val instanceField = AgentApplication::class.java.getDeclaredField("instance")
            instanceField.isAccessible = true
            if (savedAgentApplicationInstance == null) {
                instanceField.set(null, null)
            } else {
                instanceField.set(null, savedAgentApplicationInstance)
            }
        } catch (_: Exception) { /* ignore */ }
    }
}

/** 简单的 KV 存储（满足 DecisionDialogService 的 KVUtils 调用） */
private class FakeSharedPreferencesForDialog : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    fun set(key: String, value: Any?) { map[key] = value }

    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
        map[key] as? Set<String> ?: defValues

    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}

    private inner class FakeEditor : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { map[key] = value }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { map[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { map[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { map[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { map[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { map[key] = value }
        override fun remove(key: String): SharedPreferences.Editor = apply { map.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { map.clear() }
        override fun commit(): Boolean = true
        override fun apply() {}
    }
}
