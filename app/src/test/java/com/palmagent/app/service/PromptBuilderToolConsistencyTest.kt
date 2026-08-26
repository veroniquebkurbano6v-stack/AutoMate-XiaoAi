package com.palmagent.app.service

import android.content.SharedPreferences
import com.palmagent.app.tool.ToolRegistry
import com.palmagent.app.utils.KVUtils
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 提示词工具清单 vs ToolRegistry 一致性校验测试
 *
 * 原则：提示词写什么工具，模型就输出什么工具（零归一化映射）。
 * 因此提示词"工具（动作空间）/操作工具"中列出的每个工具名，必须能在
 * ToolRegistry 注册表中找到，或属于由 when 分支 / ToolDecisionEngine 直接处理的
 * 白名单工具（ask_user/fetch_result/forget）。
 *
 * 防止提示词与注册表漂移（如历史上 request_user_action vs user_action 不一致，
 * 模型按提示词输出 request_user_action 但注册表只有 user_action）。
 */
class PromptBuilderToolConsistencyTest {

    private lateinit var fakePrefs: FakeSharedPreferencesForConsistency

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferencesForConsistency()
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

    /** 由 when 分支 / ToolDecisionEngine 直接处理、不在 ToolRegistry 中的白名单工具 */
    private val handledElsewhere = setOf("ask_user", "fetch_result", "forget")

    /** 注册在 ToolRegistry 但有意不在执行提示词中暴露的工具（决策模型/内部使用） */
    private val intentionallyHidden = setOf(
        "list_apps", "get_screen_info",
        "amap_search", "amap_nearby", "amap_directions", "amap_weather"
    )

    /** 提示词"输出格式"区以行首 "- 字段:" 形式出现的 JSON 字段名（非工具） */
    private val jsonFieldNames = setOf("type")

    @Test
    fun `prompt tool names must exist in ToolRegistry or handled-elsewhere whitelist`() {
        val registered = ToolRegistry.getAllTools().map { it.getName() }.toSet()
        val missing = linkedSetOf<String>()

        for (prompt in allPromptVariants()) {
            extractToolNames(prompt).forEach { name ->
                if (name !in registered && name !in handledElsewhere && name !in jsonFieldNames) {
                    missing.add(name)
                }
            }
        }

        assertTrue(
            "提示词列出的工具名不在 ToolRegistry 注册表（且不在白名单/JSON字段）中: $missing\n" +
                "已注册: ${registered.sorted()}",
            missing.isEmpty()
        )
    }

    @Test
    fun `registered execution tools must be advertised in prompt unless intentionally hidden`() {
        val registered = ToolRegistry.getAllTools().map { it.getName() }.toSet()
        val promptTools = allPromptVariants().flatMap { extractToolNames(it) }.toSet()
        val notAdvertised = registered - promptTools - intentionallyHidden

        assertTrue(
            "注册在 ToolRegistry 但未在执行提示词中列出（且不在隐藏白名单）: $notAdvertised\n" +
                "提示词已列出: ${promptTools.sorted()}",
            notAdvertised.isEmpty()
        )
    }

    /** 覆盖 4 个提示词变体：文本/视觉 × 简单/复杂 */
    private fun allPromptVariants(): List<String> {
        val variants = mutableListOf<String>()
        for (vision in listOf(false, true)) {
            for (complex in listOf(false, true)) {
                KVUtils.setVisionModeEnabled(vision)
                KVUtils.setComplexModeEnabled(complex)
                variants.add(PromptBuilder.getSystemPrompt())
            }
        }
        return variants
    }

    /** 提取提示词工具清单行 "- 工具名:"（含 back/home 斜杠合并形式） */
    private fun extractToolNames(prompt: String): Set<String> {
        val names = linkedSetOf<String>()
        val regex = Regex("(?m)^- ([a-z_]+(?:/[a-z_]+)?):")
        for (match in regex.findAll(prompt)) {
            match.groupValues[1].split("/").forEach { names.add(it) }
        }
        return names
    }
}

/** 最小 SharedPreferences 假实现，支持 KVUtils 读写布尔开关 */
private class FakeSharedPreferencesForConsistency : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

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
