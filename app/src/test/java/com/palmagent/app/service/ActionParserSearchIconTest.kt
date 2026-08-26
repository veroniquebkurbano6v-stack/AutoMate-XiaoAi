package com.palmagent.app.service

import com.palmagent.app.model.AgentAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ActionParser search_icon / is_text_input_box 字段解析兼容性测试
 *
 * v10（b634a85）：AUTO_INPUT 定位参数由 search_icon 重构为 is_text_input_box，
 * 字段承载位置从 ActionParser/AgentAction 移至 AutoInputTool 工具参数层
 * （由工具直接读取 params["is_text_input_box"]，不再经 ActionParser 映射）。
 *
 * 本文件验证重构后的解析行为：
 * - 遗留 search_icon / 新 is_text_input_box 字段在 ActionParser 中被忽略（Gson 不映射未知字段），
 *   且忽略不影响其余字段（type/target/confidence 等）的正确解析
 * - type 非 ASK_USER 时不触发 KVUtils/Android 依赖
 *
 * 测试策略：通过 parseActionFromResponse 传入含不同字段值的 JSON 间接验证解析鲁棒性。
 */
class ActionParserSearchIconTest {

    private fun buildResponse(field: String?): String {
        val fields = listOf(
            "\"type\":\"tap\"",
            "\"target\":\"搜索按钮\"",
            field
        ).filterNotNull().filter { it.isNotEmpty() }
        return "{${fields.joinToString(",")}}"
    }

    private fun parse(field: String?): AgentAction {
        val response = buildResponse(field)
        return ActionParser.parseActionFromResponse(response, screenInfo = null)
    }

    // ===== 遗留 search_icon 字段：解析被忽略但不崩溃 =====

    @Test
    fun `legacy_search_icon_true_ignored_but_action_parsed`() {
        val action = parse("\"search_icon\":\"true\"")
        assertEquals("tap", action.type)
        assertEquals("搜索按钮", action.targetId)
    }

    @Test
    fun `legacy_search_icon_one_ignored_but_action_parsed`() {
        val action = parse("\"search_icon\":\"1\"")
        assertEquals("tap", action.type)
        assertEquals("搜索按钮", action.targetId)
    }

    @Test
    fun `legacy_search_icon_false_ignored_but_action_parsed`() {
        val action = parse("\"search_icon\":\"false\"")
        assertEquals("tap", action.type)
        assertEquals("搜索按钮", action.targetId)
    }

    @Test
    fun `legacy_search_icon_random_string_ignored_but_action_parsed`() {
        val action = parse("\"search_icon\":\"maybe\"")
        assertEquals("tap", action.type)
    }

    // ===== 新 is_text_input_box 字段：由工具层消费，解析层忽略 =====

    @Test
    fun `is_text_input_box_true_ignored_by_parser`() {
        val action = parse("\"is_text_input_box\":\"true\"")
        assertEquals("tap", action.type)
        assertEquals("搜索按钮", action.targetId)
    }

    @Test
    fun `is_text_input_box_false_ignored_by_parser`() {
        val action = parse("\"is_text_input_box\":\"false\"")
        assertEquals("tap", action.type)
        assertEquals("搜索按钮", action.targetId)
    }

    // ===== 字段缺失 =====

    @Test
    fun `no_field_parses_normally`() {
        val action = parse(null)
        assertEquals("tap", action.type)
        assertEquals("搜索按钮", action.targetId)
    }

    // ===== 完整解析验证 =====

    /**
     * 验证 parseActionFromResponse 同时正确解析其他字段（search_icon 不影响）
     */
    @Test
    fun `parseActionWithLegacyField_otherFieldsAlsoCorrect`() {
        val response = """
            {
                "type": "tap",
                "target": "搜索按钮",
                "search_icon": "true",
                "confidence": 0.9
            }
        """.trimIndent()

        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)

        assertEquals("tap", action.type)
        assertEquals("搜索按钮", action.targetId)
        assertEquals(0.9f, action.confidence, 0.001f)
    }

    /**
     * 验证 type 非 ASK_USER 时不触发 KVUtils 依赖
     */
    @Test
    fun `parseActionWithClickType_doesNotTriggerKVUtils`() {
        val response = """{"type":"tap","search_icon":"1"}"""
        // 如果触发 KVUtils，会抛 RuntimeException("not mocked")
        val action = ActionParser.parseActionFromResponse(response, screenInfo = null)
        assertEquals("tap", action.type)
    }
}
