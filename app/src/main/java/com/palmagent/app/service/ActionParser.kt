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

    /**
     * P3-11 修复：宽松解析布尔值，识别 "true"/"1"/"yes"（大小写不敏感）
     */
    private fun parseBooleanLoose(s: String): Boolean =
        s.equals("true", ignoreCase = true) || s == "1" || s.equals("yes", ignoreCase = true)

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
        // 下一轮屏幕描述想额外确认的问题（透传给 GUI 模型按需回答）
        val visual_question: String? = null,
        // 批量提问结构化字段（ASK_USER 必填，对齐 GitHub Copilot ask_questions）
        // 旧字段 options 已删除，强制模型输出 questions 数组
        val questions: List<QuestionJson>? = null,
        // 规格自动选取（SELECT_SPEC 使用）：specs=需选取的规格列表（兼容 JSON 数组或逗号分隔字符串），confirm_text=确认按钮文本
        val specs: JsonElement? = null,
        val confirm_text: String? = null
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
                    visualQuestion = actionJson.visual_question?.takeIf { it.isNotBlank() },
                    specs = parseSpecsField(actionJson.specs),
                    confirmText = actionJson.confirm_text?.takeIf { it.isNotBlank() }
                )
            } else {
                extractActionFromText(response, screenInfo)
            }
        } catch (e: Exception) {
            // 尝试从 JSON 中提取未知动作类型并降级映射
            try {
                val jsonStart = response.indexOf("{")
                val jsonEnd = response.lastIndexOf("}") + 1
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    val jsonStr = response.substring(jsonStart, jsonEnd)
                    val actionJson = gson.fromJson(jsonStr, ActionJson::class.java)
                    val unknownType = actionJson.type?.uppercase() ?: ""
                    if (unknownType.isNotBlank() && unknownType != "操作类型") {
                        android.util.Log.w("ActionParser", "未知动作类型 '$unknownType'，尝试降级处理")
                        val fallbackAction = tryMapUnknownType(unknownType, actionJson)
                        if (fallbackAction != null) return fallbackAction
                    }
                }
            } catch (_: Exception) {}
            extractActionFromText(response, screenInfo)
        }
    }

    /**
     * 将未知动作类型映射到合理默认值（防御性容错）
     * 当模型输出不在已知工具列表中的类型时，降级为 WAIT
     */
    private fun tryMapUnknownType(type: String, json: ActionJson): AgentAction? {
        android.util.Log.w("ActionParser", "未知动作类型 '$type'，降级为 WAIT")
        return AgentAction(
            type = "wait",
            description = "未知动作降级: $type",
            confidence = 0.1f
        )
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

    /**
     * 从自然语言文本中提取动作（回退策略）
     */
    fun extractActionFromText(text: String, screenInfo: ScreenInfo?): AgentAction {
        if (text.isBlank()) {
            return AgentAction(
                type = "wait",
                description = "模型返回空内容，等待重试",
                confidence = 0.3f
            )
        }

        val textLower = text.lowercase()

        return when {
            textLower.contains("视觉描述") || textLower.contains("visual_describe") ||
            textLower.contains("视觉问答") || textLower.contains("屏幕描述") ||
            textLower.contains("屏幕问答") ->
                AgentAction(
                    type = "visual_describe",
                    text = text,
                    description = "视觉描述屏幕",
                    confidence = 0.8f
                )
            textLower.contains("web_search") || textLower.contains("联网搜索") ||
            textLower.contains("搜索一下") ->
                AgentAction(
                    type = "web_search",
                    text = text,
                    description = "联网搜索",
                    confidence = 0.8f
                )
            textLower.contains("需要用户") || textLower.contains("用户操作") ||
            textLower.contains("手动操作") || textLower.contains("请求用户") ->
                AgentAction(
                    type = "request_user_action",
                    text = text,
                    description = text.take(200),
                    confidence = 0.8f
                )
            textLower.contains("tap") || textLower.contains("click") || textLower.contains("点击") ->
                AgentAction(
                    type = "wait",
                    description = "点击操作解析失败，等待下轮重试",
                    confidence = 0.3f
                )
            textLower.contains("完成") || textLower.contains("结束") -> AgentAction(
                type = "finish",
                description = text.takeIf { it.length < 200 } ?: "任务完成",
                confidence = 0.9f
            )
            textLower.contains("返回") && textLower.contains("主页") -> AgentAction(
                type = "home",
                description = "返回主页",
                confidence = 0.8f
            )
            textLower.contains("返回") -> AgentAction(
                type = "back",
                description = "返回上一页",
                confidence = 0.8f
            )
            textLower.contains("等待") -> AgentAction(
                type = "wait",
                description = "等待加载",
                confidence = 0.8f
            )
            else -> AgentAction(
                type = "wait",
                description = "无法解析操作：${text.take(100)}",
                confidence = 0.3f
            )
        }
    }
}
