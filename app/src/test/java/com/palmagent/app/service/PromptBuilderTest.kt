package com.palmagent.app.service

import android.content.SharedPreferences
import com.palmagent.app.tool.ToolRegistry
import com.palmagent.app.utils.KVUtils
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PromptBuilder 系统提示词验证测试（v4.1）
 *
 * 验证目标：
 * 1. 系统提示词包含"输出协议"章节（替代旧"字段映射说明"）
 * 2. 操作工具列表中 OPEN_APP/REQUEST_USER_ACTION 的参数说明
 * 3. 防止后续修改提示词时破坏关键引导
 */
class PromptBuilderTest {

    @Before
    fun setUp() {
        val fakePrefs = FakeSharedPreferences()
        KVUtils::class.java.getDeclaredField("prefs").apply {
            isAccessible = true
            set(KVUtils, fakePrefs)
        }
        KVUtils::class.java.getDeclaredField("securePrefs").apply {
            isAccessible = true
            set(KVUtils, fakePrefs)
        }
        ToolRegistry.initAllTools()
    }

    @Test
    fun `system_prompt_OPEN_APP_mentions_app_name_as_text_param`() {
        val prompt = PromptBuilder.getSystemPrompt()
        assertTrue(
            "OPEN_APP 应在操作工具列表中说明 text 参数为应用中文名或包名: \n$prompt",
            prompt.contains("open_app") &&
                prompt.contains("text(必填,应用中文名或包名)")
        )
    }

    @Test
    fun `system_prompt_LOCATE_is_in_operation_tools_list`() {
        val prompt = PromptBuilder.getSystemPrompt()
        assertTrue(
            "LOCATE 应在操作工具列表中: \n$prompt",
            prompt.contains("locate") &&
                prompt.contains("视觉定位并自动点击")
        )
    }

    @Test
    fun `system_prompt_REQUEST_USER_ACTION_mentions_text_as_title`() {
        val prompt = PromptBuilder.getSystemPrompt()
        assertTrue(
            "REQUEST_USER_ACTION 应说明 text 为标题: \n$prompt",
            prompt.contains("request_user_action") &&
                prompt.contains("text(必填,标题)")
        )
    }

    @Test
    fun `system_prompt_contains_output_protocol_section`() {
        val prompt = PromptBuilder.getSystemPrompt()
        assertTrue(
            "提示词应包含 输出格式与运行规则 章节（替代旧 ## 字段映射说明（v7））: \n$prompt",
            prompt.contains("输出格式与运行规则")
        )
    }

    @Test
    fun `system_prompt_does_not_contain_old_field_mapping`() {
        val prompt = PromptBuilder.getSystemPrompt()
        assertTrue(
            "提示词不应再包含旧的 ## 字段映射说明（v7）章节: \n$prompt",
            !prompt.contains("## 字段映射说明（v7）")
        )
    }

    @Test
    fun `system_prompt_output_protocol_uses_content_for_all_actions`() {
        val prompt = PromptBuilder.getSystemPrompt()
        assertTrue(
            "输出协议应说明所有操作都通过 content 输出，不要用 tool_calls: \n$prompt",
            prompt.contains("所有操作都走 content") &&
                prompt.contains("不要用 tool_calls")
        )
    }

    @Test
    fun `system_prompt_contains_web_search_tool_description`() {
        val prompt = PromptBuilder.getSystemPrompt()
        assertTrue(
            "操作工具列表应包含 WEB_SEARCH 工具说明（query+mode 新契约）: \n$prompt",
            prompt.contains("web_search: query(必填,搜索关键词), mode(选填,web/ai,默认web)")
        )
    }

    @Test
    fun `buildPrompt_includes_user_task_when_request_not_blank`() {
        val result = PromptBuilder.buildPrompt(
            userRequest = "这题选什么",
            screenInfo = null,
            knowledgeContext = "测试上下文",
            actionHistory = emptyList()
        )
        assertTrue(
            "buildPrompt 应输出【用户任务】: \n$result",
            result.contains("【用户任务】这题选什么")
        )
    }

    @Test
    fun `buildPrompt_skips_user_task_when_request_blank`() {
        val result = PromptBuilder.buildPrompt(
            userRequest = "",
            screenInfo = null,
            knowledgeContext = "测试上下文",
            actionHistory = emptyList()
        )
        assertTrue(
            "userRequest 为空时不应输出【用户任务】: \n$result",
            !result.contains("【用户任务】")
        )
    }

    /**
     * 简单的 SharedPreferences Fake（满足 KVUtils.getBoolean/getString 调用）。
     * 复用自 ActionParserAskUserTest 的模式。
     */
    class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()
        override fun getAll() = map.mapValues { it.value }
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
        override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
        override fun getStringSet(key: String?, defValue: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    class FakeEditor(private val map: MutableMap<String, Any>) : SharedPreferences.Editor {
        override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) map[key] = value }
        override fun putString(key: String?, value: String?) = apply { if (key != null) map[key] = value ?: "" }
        override fun putInt(key: String?, value: Int) = apply { if (key != null) map[key] = value }
        override fun putLong(key: String?, value: Long) = apply { if (key != null) map[key] = value }
        override fun putFloat(key: String?, value: Float) = apply { if (key != null) map[key] = value }
        override fun putStringSet(key: String?, value: MutableSet<String>?) = apply { if (key != null) map[key] = value ?: mutableSetOf<String>() }
        override fun remove(key: String?) = apply { if (key != null) map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}