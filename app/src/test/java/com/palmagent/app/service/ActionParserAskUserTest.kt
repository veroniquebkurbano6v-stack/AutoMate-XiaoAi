package com.palmagent.app.service

import android.content.SharedPreferences
import com.palmagent.app.utils.KVUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ActionParser ASK_USER 批量提问解析单元测试
 *
 * 验证目标：
 * 1. 简单模式：ASK_USER 正常解析 questions 数组（含 question/header/options/multiSelect/allowFreeInput）
 * 2. recommended 标记被正确解析
 * 3. header 缺失时回退为 question.take(12)
 * 4. multiSelect/allowFreeInput 缺失时默认 false/true
 * 5. 选项 description 字段被正确解析
 * 6. ASK_USER 缺少 questions 字段 → 降级为 WAIT（不保留旧格式兼容回退）
 * 7. ASK_USER questions 中某问题选项 <2 → 该问题被过滤；全部过滤 → 降级为 WAIT
 * 8. 旧格式 text+options（无 questions）→ 降级为 WAIT（强制新格式）
 * 9. 复杂模式：ASK_USER 降级为 WAIT（Layer 2 兜底）
 *
 * 技术要点：
 * - 反射注入 FakeSharedPreferences 到 KVUtils.prefs / securePrefs 控制模式开关
 * - ActionParser 是 object，无状态，可直接调用 parseActionFromResponse
 * - android.util.Log 在 JVM 测试环境下返回 stub，但不影响解析逻辑
 */
class ActionParserAskUserTest {

    private lateinit var fakePrefs: FakeSharedPreferencesForParser

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferencesForParser()
        // 默认简单模式
        fakePrefs.set("KEY_COMPLEX_MODE_ENABLED", false)

        val prefsField = KVUtils::class.java.getDeclaredField("prefs")
        prefsField.isAccessible = true
        prefsField.set(KVUtils, fakePrefs)

        val secureField = KVUtils::class.java.getDeclaredField("securePrefs")
        secureField.isAccessible = true
        secureField.set(KVUtils, fakePrefs)
    }

    @After
    fun tearDown() {
        // 重置为复杂模式（默认值），避免污染其他测试
        fakePrefs.set("KEY_COMPLEX_MODE_ENABLED", true)
    }

    @Test
    fun `simple mode parses ASK_USER with questions array`() {
        val response = """
            {"type":"ASK_USER","description":"追问联系人","questions":[
                {"question":"需要发短信给哪个联系人？","header":"联系人","options":[
                    {"label":"张三","description":"最近联系人","recommended":true},
                    {"label":"李四"}
                ]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(ASK_USER, action.type)
        assertEquals("追问联系人", action.description)
        assertNotNull(action.questions)
        assertEquals(1, action.questions!!.size)

        val q = action.questions!![0]
        assertEquals("需要发短信给哪个联系人？", q.question)
        assertEquals("联系人", q.header)
        assertEquals(2, q.options.size)
        assertEquals("张三", q.options[0].label)
        assertEquals("最近联系人", q.options[0].description)
        assertTrue(q.options[0].recommended)
        assertEquals("李四", q.options[1].label)
        assertNull(q.options[1].description)
        assertFalse(q.options[1].recommended)
        // 缺省值
        assertFalse(q.multiSelect)
        assertTrue(q.allowFreeInput)
    }

    @Test
    fun `recommended flag parsed correctly for multiple options`() {
        val response = """
            {"type":"ASK_USER","questions":[
                {"question":"选择搜索方式","options":[
                    {"label":"精确匹配","recommended":true},
                    {"label":"模糊匹配"},
                    {"label":"正则匹配","recommended":true}
                ]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(ASK_USER, action.type)
        val q = action.questions!![0]
        assertEquals(3, q.options.size)
        assertTrue(q.options[0].recommended)
        assertFalse(q.options[1].recommended)
        assertTrue(q.options[2].recommended)
    }

    @Test
    fun `multiSelect field parsed correctly`() {
        val response = """
            {"type":"ASK_USER","questions":[
                {"question":"选择要通知的联系人","multiSelect":true,"options":[
                    {"label":"A"},{"label":"B"}
                ]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(ASK_USER, action.type)
        assertTrue(action.questions!![0].multiSelect)
    }

    @Test
    fun `allowFreeInput false parsed correctly`() {
        val response = """
            {"type":"ASK_USER","questions":[
                {"question":"选择性别","allowFreeInput":false,"options":[
                    {"label":"男"},{"label":"女"}
                ]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(ASK_USER, action.type)
        assertFalse(action.questions!![0].allowFreeInput)
    }

    @Test
    fun `header defaults to question take 12 when omitted`() {
        val longQuestion = "请选择需要执行的操作类型这是一个很长的问题文本超过十二个字"
        val response = """
            {"type":"ASK_USER","questions":[
                {"question":"$longQuestion","options":[
                    {"label":"A"},{"label":"B"}
                ]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(ASK_USER, action.type)
        val q = action.questions!![0]
        assertEquals(longQuestion.take(12), q.header)
    }

    @Test
    fun `option description field parsed correctly`() {
        val response = """
            {"type":"ASK_USER","questions":[
                {"question":"选择格式","options":[
                    {"label":"PDF","description":"不可编辑但排版稳定"},
                    {"label":"DOCX","description":"可编辑但可能错版"}
                ]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(ASK_USER, action.type)
        val q = action.questions!![0]
        assertEquals("不可编辑但排版稳定", q.options[0].description)
        assertEquals("可编辑但可能错版", q.options[1].description)
    }

    /**
     * 核心校验：ASK_USER 缺少 questions 字段 → 降级为 WAIT
     * 不保留旧格式兼容回退（旧 text+options 单问格式已废弃）
     */
    @Test
    fun `ASK_USER without questions field degrades to WAIT`() {
        val response = """
            {"type":"ASK_USER","text":"需要发短信给哪个联系人？","description":"追问联系人"}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals("缺少 questions 必须降级为 WAIT", WAIT, action.type)
        assertNull(action.questions)
    }

    /**
     * 旧格式兼容回退已移除：text+options（无 questions）→ 降级为 WAIT
     * 强制模型按新格式输出 questions 数组
     */
    @Test
    fun `legacy text plus options format degrades to WAIT`() {
        val response = """
            {"type":"ASK_USER","text":"需要验证码","options":["重新发送","手动输入"]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals("旧格式必须降级为 WAIT", WAIT, action.type)
        assertNull(action.questions)
    }

    /**
     * questions 中某问题选项 <2 → 该问题被过滤
     * 全部问题被过滤 → 整体降级为 WAIT
     */
    @Test
    fun `question with fewer than 2 options is filtered and degrades to WAIT if all filtered`() {
        val response = """
            {"type":"ASK_USER","questions":[
                {"question":"只有一个选项的问题","options":[{"label":"唯一选项"}]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals("全部问题被过滤后必须降级为 WAIT", WAIT, action.type)
        assertNull(action.questions)
    }

    /**
     * questions 中部分问题合法、部分非法：合法问题保留，非法问题过滤
     */
    @Test
    fun `valid questions kept while invalid ones filtered`() {
        val response = """
            {"type":"ASK_USER","questions":[
                {"question":"合法问题","options":[{"label":"A"},{"label":"B"}]},
                {"question":"非法问题","options":[{"label":"唯一"}]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(ASK_USER, action.type)
        assertEquals(1, action.questions!!.size)
        assertEquals("合法问题", action.questions!![0].question)
    }

    /**
     * questions 为空数组 → 降级为 WAIT
     */
    @Test
    fun `empty questions array degrades to WAIT`() {
        val response = """{"type":"ASK_USER","questions":[]}"""

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(WAIT, action.type)
        assertNull(action.questions)
    }

    /**
     * 多问题批量解析：保留顺序与内容
     */
    @Test
    fun `multiple questions parsed in order`() {
        val response = """
            {"type":"ASK_USER","questions":[
                {"question":"问题一","header":"h1","options":[{"label":"A"},{"label":"B"}]},
                {"question":"问题二","header":"h2","multiSelect":true,"options":[{"label":"X"},{"label":"Y"}]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(ASK_USER, action.type)
        assertEquals(2, action.questions!!.size)
        assertEquals("问题一", action.questions!![0].question)
        assertEquals("h1", action.questions!![0].header)
        assertFalse(action.questions!![0].multiSelect)
        assertEquals("问题二", action.questions!![1].question)
        assertEquals("h2", action.questions!![1].header)
        assertTrue(action.questions!![1].multiSelect)
    }

    /**
     * Layer 2 兜底验证：复杂模式下 ASK_USER 必须降级为 WAIT
     * 这是三层防护的关键第二层，避免复杂模式下执行模型追问绕过决策模型
     */
    @Test
    fun `complex mode degrades ASK_USER to WAIT`() {
        fakePrefs.set("KEY_COMPLEX_MODE_ENABLED", true)
        assertTrue(KVUtils.isComplexModeEnabled())

        val response = """
            {"type":"ASK_USER","questions":[
                {"question":"需要发短信给哪个联系人？","options":[
                    {"label":"张三"},{"label":"李四"}
                ]}
            ]}
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals("复杂模式必须降级 ASK_USER 为 WAIT", WAIT, action.type)
        assertFalse("降级后 type 不应为 ASK_USER", action.type == ASK_USER)
        // 复杂模式降级后 questions 不应被填充
        assertNull(action.questions)
    }

    /**
     * ASK_USER 降级为 WAIT 后，description 字段保留降级原因（便于日志追踪）
     */
    @Test
    fun `degraded WAIT action has descriptive reason`() {
        val response = """{"type":"ASK_USER","text":"无 questions 字段"}"""

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals(WAIT, action.type)
        assertNotNull(action.description)
    }
}

/**
 * 简单的 SharedPreferences Fake（满足 KVUtils.getBoolean/getString 调用）
 */
private class FakeSharedPreferencesForParser : SharedPreferences {
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
