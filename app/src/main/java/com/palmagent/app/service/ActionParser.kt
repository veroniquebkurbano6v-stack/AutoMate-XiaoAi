package com.palmagent.app.service

import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.palmagent.app.model.*
import com.palmagent.app.utils.KVUtils
import java.lang.reflect.Type

/**
 * AI 响应解析器
 *
 * 从 AIService 中拆分，负责：
 * - JSON 格式动作解析
 * - 文本回退解析
 * - 目标元素匹配
 */
object ActionParser {

    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapter(CoordinateJson::class.java, CoordinateJsonDeserializer())
        .create()

    // 批量重复执行的安全边界：次数上限 1-10，间隔 500-2000ms
    private const val MAX_REPEAT = 10
    private const val MIN_REPEAT_INTERVAL_MS = 500L
    private const val MAX_REPEAT_INTERVAL_MS = 2000L

    // 滚动查找（SCROLL_UNTIL）滚动次数安全边界（与 ScrollUntilTool.MAX_SCROLLS_MAX 对齐）
    private const val MAX_SCROLL_UNTIL_SCROLLS = 10

    /**
     * P3-11 修复：宽松解析布尔值，识别 "true"/"1"/"yes"（大小写不敏感）。
     * @param raw 兼容 JSON 原生布尔（true/false）与字符串（"true"/"1"/"yes"/"false"/"0"/"no"）
     * @return Boolean? 无法解析或未提供时返回 null（交由下游使用默认值）
     */
    private fun parseBooleanLoose(raw: JsonElement?): Boolean? {
        if (raw == null || !raw.isJsonPrimitive) return null
        val primitive = raw.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isString -> when (primitive.asString.lowercase()) {
                "true", "1", "yes", "是" -> true
                "false", "0", "no", "否" -> false
                else -> null
            }
            else -> null
        }
    }

    data class ActionJson(
        val type: String? = null,
        val target: String? = null,
        val coordinate: CoordinateJson? = null,
        val coordinate_end: CoordinateJson? = null,
        val text: String? = null,
        val description: String? = null,
        val confidence: Float? = null,
        val target_desc: String? = null,
        val action_desc: String? = null,
        val instruction: String? = null,
        val related_keywords: String? = null,
        val progress: ProgressJson? = null,
        val duration_ms: Long? = null,
        // 批量重复执行字段：repeat=连续执行同一动作的次数，interval_ms=每次间隔毫秒
        val repeat: Int? = null,
        val interval_ms: Long? = null,
        // 滑动方向（SWIPE 使用）：up/down/left/right/custom
        val direction: String? = null,
        // 方向模式滑动距离（像素，SWIPE 选填）
        val distance: JsonElement? = null,
        // 下一轮屏幕描述想额外确认的问题（透传给 GUI 模型按需回答）
        val visual_question: String? = null,
        // 批量提问结构化字段（ASK_USER 必填，对齐 GitHub Copilot ask_questions）
        // 旧字段 options 已删除，强制模型输出 questions 数组
        val questions: List<QuestionJson>? = null,
        // 规格自动选取（SELECT_SPEC 使用）：specs=需选取的规格列表（兼容 JSON 数组或逗号分隔字符串），confirm_text=确认按钮文本
        val specs: JsonElement? = null,
        val confirm_text: String? = null,
        // 滚动查找（SCROLL_UNTIL 使用）：max_scrolls=最大滚动次数，click_on_found=找到后是否自动定位并点击
        val max_scrolls: Int? = null,
        val click_on_found: JsonElement? = null,
        // 联网搜索（WEB_SEARCH 使用）：query=搜索关键词（协议字段），mode=检索模式 web/ai（默认web）
        val query: String? = null,
        val mode: String? = null
    )

    data class QuestionJson(
        val question: String? = null,
        val header: String? = null,
        val options: List<QuestionOptionJson>? = null,
        val multiSelect: Boolean? = null,
        val allowFreeInput: Boolean? = null
    )

    data class QuestionOptionJson(
        val label: String? = null,
        val description: String? = null,
        val recommended: Boolean? = null
    )

    data class CoordinateJson(
        val x: Int = 0,
        val y: Int = 0
    )

    /**
     * 坐标双格式反序列化器（GUI-Plus 兼容）：
     * - 对象格式 {"x":976,"y":2376}：PalmAgent 旧 Prompt 定义的格式
     * - 数组格式 [976,2376]：GUI-Plus 原生 tools schema（type: array），模型跟随训练数据输出
     * 其余格式（字符串/数字/布尔/null）返回 null 或默认值，交由下游兜底逻辑处理。
     */
    private class CoordinateJsonDeserializer : JsonDeserializer<CoordinateJson> {
        override fun deserialize(
            json: JsonElement,
            typeOfT: Type,
            context: JsonDeserializationContext
        ): CoordinateJson? {
            return when {
                json.isJsonObject -> {
                    val obj = json.asJsonObject
                    CoordinateJson(
                        x = obj.intField("x"),
                        y = obj.intField("y")
                    )
                }
                json.isJsonArray -> {
                    val arr = json.asJsonArray
                    CoordinateJson(
                        x = arr.intField(0),
                        y = arr.intField(1)
                    )
                }
                else -> null
            }
        }

        /** 对象格式：读取命名整数字段（兼容浮点/字符串数字） */
        private fun JsonObject.intField(name: String): Int {
            val el = get(name) ?: return 0
            return el.asIntOrZero()
        }

        /** 数组格式：按下标读取整数（越界返回 0） */
        private fun JsonArray.intField(index: Int): Int {
            if (index >= size()) return 0
            return get(index).asIntOrZero()
        }

        private fun JsonElement.asIntOrZero(): Int {
            if (!isJsonPrimitive) return 0
            return try {
                asJsonPrimitive.asFloat.toInt()
            } catch (_: Exception) {
                0
            }
        }
    }

    data class ProgressJson(
        val current_step: String? = null,
        val completed_steps: List<String>? = null,
        val remaining_steps: List<String>? = null,
        val status: String? = null
    )

    /**
     * 从 AI 响应文本解析动作
     */
    fun parseActionFromResponse(response: String, screenInfo: ScreenInfo?): AgentAction {
        return try {
            val jsonStart = response.indexOf("{")
            val jsonEnd = response.lastIndexOf("}") + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonStr = response.substring(jsonStart, jsonEnd)
                val actionJson = gson.fromJson(jsonStr, ActionJson::class.java)

                val actionType = actionJson.type?.lowercase() ?: "tap"

                // 复杂模式兜底，ASK_USER 降级为 WAIT（复杂模式下执行模型不准追问）
                val finalActionType = if (actionType == "ask_user" && KVUtils.isComplexModeEnabled()) {
                    android.util.Log.w("ActionParser", "复杂模式不支持 ASK_USER，降级为 WAIT")
                    "wait"
                } else actionType

                var targetElement: UIElement? = null
                if (actionJson.target != null && screenInfo != null) {
                    targetElement = screenInfo.uiElements.find {
                        it.id == actionJson.target ||
                        it.text == actionJson.target ||
                        it.contentDescription == actionJson.target
                    }
                    if (targetElement == null) {
                        targetElement = screenInfo.uiElements.find {
                            it.text?.contains(actionJson.target, ignoreCase = true) == true ||
                            it.contentDescription?.contains(actionJson.target, ignoreCase = true) == true
                        }
                    }
                }

                var coordinate: Coordinate? = null
                if (actionJson.coordinate != null) {
                    coordinate = Coordinate(actionJson.coordinate.x, actionJson.coordinate.y)
                }

                var coordinateEnd: Coordinate? = null
                if (actionJson.coordinate_end != null) {
                    coordinateEnd = Coordinate(actionJson.coordinate_end.x, actionJson.coordinate_end.y)
                }

                // ASK_USER 批量提问解析
                val parsedQuestions = if (finalActionType == "ask_user") {
                    parseQuestions(actionJson.questions)
                } else null

                // 严格校验：ASK_USER 必须有合法 questions 数组，否则降级为 WAIT（不保留旧格式兼容回退）
                if (finalActionType == "ask_user" && parsedQuestions.isNullOrEmpty()) {
                    android.util.Log.w("ActionParser", "ASK_USER 缺少合法 questions 字段，降级为 WAIT")
                    return AgentAction(
                        type = "wait",
                        description = "ASK_USER 缺少合法 questions 字段",
                        confidence = 0.1f
                    )
                }

                AgentAction(
                    type = finalActionType,
                    targetElement = targetElement,
                    targetId = actionJson.target,
                    coordinate = coordinate,
                    coordinateEnd = coordinateEnd,
                    text = actionJson.text,
                    description = actionJson.description ?: "执行${finalActionType}",
                    confidence = actionJson.confidence ?: 0.8f,
                    targetDesc = actionJson.target_desc,
                    actionDesc = actionJson.action_desc,
                    instruction = actionJson.instruction,
                    relatedKeywords = actionJson.related_keywords,
                    progress = actionJson.progress?.let { pj ->
                        TaskProgress(
                            currentStep = pj.current_step,
                            completedSteps = pj.completed_steps ?: emptyList(),
                            remainingSteps = pj.remaining_steps ?: emptyList(),
                            status = pj.status ?: "in_progress"
                        )
                    },
                    durationMs = actionJson.duration_ms,
                    questions = parsedQuestions,
                    repeat = actionJson.repeat?.coerceIn(1, MAX_REPEAT) ?: 1,
                    intervalMs = actionJson.interval_ms?.coerceIn(MIN_REPEAT_INTERVAL_MS, MAX_REPEAT_INTERVAL_MS),
                    direction = actionJson.direction?.lowercase()
                        ?.takeIf { it in setOf("up", "down", "left", "right", "custom") },
                    distance = parseDistance(actionJson.distance),
                    visualQuestion = actionJson.visual_question?.takeIf { it.isNotBlank() },
                    specs = parseSpecsField(actionJson.specs),
                    confirmText = actionJson.confirm_text?.takeIf { it.isNotBlank() },
                    maxScrolls = actionJson.max_scrolls?.takeIf { it in 1..MAX_SCROLL_UNTIL_SCROLLS },
                    clickOnFound = parseBooleanLoose(actionJson.click_on_found),
                    query = actionJson.query?.takeIf { it.isNotBlank() },
                    mode = actionJson.mode?.lowercase()?.takeIf { it in setOf("web", "ai") }
                )
            } else {
                parseErrorAction(response, "模型输出未包含合法动作 JSON")
            }
        } catch (e: Exception) {
            parseErrorAction(response, "动作 JSON 解析失败: ${e.message}")
        }
    }

    /**
     * 解析失败兜底：生成"解析错误"动作，错误描述注入历史操作上下文，
     * 提示模型重新输出含正确 type 字段的 JSON 动作（不再做关键字→工具映射）。
     */
    private fun parseErrorAction(response: String, reason: String): AgentAction {
        android.util.Log.w("ActionParser", "$reason，原始输出: ${response.take(200)}")
        return AgentAction(
            type = "wait",
            description = "【解析错误】$reason，请重新输出含正确 type 字段的 JSON 动作",
            confidence = 0.1f
        )
    }

    /**
     * 解析 SWIPE 的 distance 字段：兼容 JSON 数字与数字字符串，非法/非正数返回 null。
     * 模型可能输出 300 / "300" / 300.0。
     */
    private fun parseDistance(raw: JsonElement?): Int? {
        if (raw == null || !raw.isJsonPrimitive) return null
        return try {
            raw.asJsonPrimitive.asFloat.toInt().takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 解析 SELECT_SPEC 的 specs 字段：兼容 JSON 数组 / 逗号顿号分隔字符串 / 空。
     * 模型可能输出 ["大份","微辣"] 或 "大份,微辣"，统一转为 List<String>。
     */
    private fun parseSpecsField(raw: JsonElement?): List<String>? {
        if (raw == null || raw.isJsonNull) return null
        return when {
            raw.isJsonArray -> raw.asJsonArray
                .mapNotNull { if (it.isJsonPrimitive) it.asString.trim() else null }
                .filter { it.isNotEmpty() }
            raw.isJsonPrimitive -> raw.asString
                .split(Regex("[,，、;；]"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            else -> null
        }
    }

    /**
     * 解析批量提问的 questions 数组
     * 严格校验：每个问题必须有 question 文本和至少 2 个合法选项，否则该问题被过滤
     * 返回空列表表示整体非法，调用方应降级为 WAIT
     */
    private fun parseQuestions(raw: List<QuestionJson>?): List<Question> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.mapNotNull { qj ->
            val questionText = qj.question?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val headerText = qj.header?.takeIf { it.isNotBlank() } ?: questionText.take(12)
            val options = qj.options?.mapNotNull { oj ->
                val label = oj.label?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                QuestionOption(
                    label = label,
                    description = oj.description,
                    recommended = oj.recommended ?: false
                )
            } ?: emptyList()
            // 每问至少 2 个选项
            if (options.size < 2) return@mapNotNull null
            Question(
                question = questionText,
                header = headerText,
                options = options,
                multiSelect = qj.multiSelect ?: false,
                allowFreeInput = qj.allowFreeInput ?: true
            )
        }
    }
}
