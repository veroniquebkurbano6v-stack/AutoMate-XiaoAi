package com.palmagent.app.service

import com.palmagent.app.tool.ToolRegistry
import com.palmagent.app.utils.KVUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * PromptBuilder VL 系统提示词验证测试
 *
 * 验证目标：
 * 1. VL 系统提示词包含 LOCATE 失败处理指引
 * 2. VL 系统提示词禁止坐标类工具（TAP/CLICK/LONG_PRESS/SWIPE）
 * 3. VL 系统提示词包含工作记忆（Scratchpad）FORGET 动作
 * 4. VL 系统提示词包含任务进度自管理 progress 字段
 */
class PromptBuilderVLTest {

    @Before
    fun setUp() {
        val fakePrefs = FakeSharedPreferencesForVL()
        // getVisionSystemPrompt() 已废弃并委托 getSystemPrompt()，
        // 必须启用视觉模式开关才能拿到 VL 系统提示词（否则返回文本模式提示词）
        fakePrefs.edit().putBoolean("KEY_VISION_MODE_ENABLED", true).apply()
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
    fun `vl_system_prompt_contains_locate_failure_handling`() {
        val prompt = PromptBuilder.getVisionSystemPrompt()
        assertTrue(
            "VL 系统提示词应包含 LOCATE 失败处理章节:\n$prompt",
            prompt.contains("locate 失败处理")
        )
        assertTrue(
            "VL 系统提示词应包含'服务不可用'指引:\n$prompt",
            prompt.contains("服务不可用")
        )
        assertTrue(
            "VL 系统提示词应包含'连续2次失败必须切换策略':\n$prompt",
            prompt.contains("连续2次失败必须切换策略")
        )
    }

    @Test
    fun `vl_system_prompt_prohibits_coordinate_tools`() {
        val prompt = PromptBuilder.getVisionSystemPrompt()
        // VL 提示词中会提到这些工具名作为"禁止使用"说明，但不应在操作工具列表中定义它们
        // 操作工具定义格式为 "- TOOL_NAME: ..."，检查不存在这种定义行
        assertFalse(
            "VL 操作工具列表不应定义 TAP 工具: \n$prompt",
            prompt.contains("- TAP:") || prompt.contains("- TAP ")
        )
        assertFalse(
            "VL 操作工具列表不应定义 CLICK 工具: \n$prompt",
            prompt.contains("- CLICK:") || prompt.contains("- CLICK ")
        )
        assertFalse(
            "VL 操作工具列表不应定义 LONG_PRESS 工具: \n$prompt",
            prompt.contains("- LONG_PRESS:") || prompt.contains("- LONG_PRESS ")
        )
        assertFalse(
            "VL 操作工具列表不应定义 SWIPE 工具: \n$prompt",
            prompt.contains("- SWIPE:") || prompt.contains("- SWIPE ")
        )
        // 验证提示词中明确禁止坐标工具
        assertTrue(
            "VL 系统提示词应明确禁止坐标类工具: \n$prompt",
            prompt.contains("禁止") && prompt.contains("坐标")
        )
    }

    @Test
    fun `vl_system_prompt_contains_scratchpad_forget_action`() {
        val prompt = PromptBuilder.getVisionSystemPrompt()
        assertTrue(
            "VL 系统提示词应包含 FORGET 动作说明:\n$prompt",
            prompt.contains("forget") &&
                prompt.contains("工作记忆")
        )
    }

    @Test
    fun `vl_system_prompt_contains_progress_field_requirement`() {
        val prompt = PromptBuilder.getVisionSystemPrompt()
        assertTrue(
            "VL 系统提示词应要求 progress 字段:\n$prompt",
            prompt.contains("progress") &&
                prompt.contains("current_step")
        )
    }

    @Test
    fun `vl_system_prompt_contains_locate_tool`() {
        val prompt = PromptBuilder.getVisionSystemPrompt()
        assertTrue(
            "VL 系统提示词应包含 LOCATE 工具:\n$prompt",
            prompt.contains("locate")
        )
    }

    @Test
    fun `vl_system_prompt_contains_web_search_tool`() {
        val prompt = PromptBuilder.getVisionSystemPrompt()
        assertTrue(
            "VL 系统提示词应包含 WEB_SEARCH 工具:\n$prompt",
            prompt.contains("web_search")
        )
    }
}

private class FakeSharedPreferencesForVL : android.content.SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValues
    override fun getInt(key: String, defValue: Int) = (map[key] as? Int) ?: defValue
    override fun getLong(key: String, defValue: Long) = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String, defValue: Float) = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class FakeEditor : android.content.SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { map[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { map[key] = values }
        override fun putInt(key: String, value: Int) = apply { map[key] = value }
        override fun putLong(key: String, value: Long) = apply { map[key] = value }
        override fun putFloat(key: String, value: Float) = apply { map[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
        override fun remove(key: String) = apply { map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}
