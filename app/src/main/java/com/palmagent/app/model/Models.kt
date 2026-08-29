package com.palmagent.app.model

data class ScreenInfo(
    val screenshotPath: String? = null,
    val uiElements: List<UIElement> = emptyList(),
    val currentPackage: String? = null,
    val currentActivity: String? = null
)

data class UIElement(
    val id: String,
    val type: UIElementType,
    val text: String?,
    val bounds: Bounds,
    val contentDescription: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val groupInfo: GroupInfo? = null,
    val isSelected: Boolean = false,
    val isChecked: Boolean = false
)

data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * UI元素分组信息，来自AccessibilityNodeInfo.CollectionItemInfo
 * "7之1" 表示 totalItems=7, itemIndex=1
 */
data class GroupInfo(
    val groupId: String,       // 同一父容器 = 同一组
    val totalItems: Int,       // 集合总项数
    val itemIndex: Int,        // 当前项索引（从1开始）
    val selectionMode: Int = 0 // 0=NONE, 1=SINGLE, 2=MULTIPLE
) {
    companion object {
        const val SELECTION_NONE = 0
        const val SELECTION_SINGLE = 1
        const val SELECTION_MULTIPLE = 2
    }
}

enum class UIElementType {
    BUTTON, TAB, INPUT, ICON, SWITCH, LINK,
    TEXT, IMAGE, LIST, CARD, UNKNOWN;

    // P3-1 修复：用 when 替代 setOf，避免高频调用时每次创建新 Set
    fun isInteractive(): Boolean = when (this) {
        BUTTON, TAB, INPUT, ICON, SWITCH, LINK -> true
        else -> false
    }
}

data class AgentAction(
    val type: String,
    val targetElement: UIElement? = null,
    val targetId: String? = null,
    val coordinate: Coordinate? = null,
    val coordinateEnd: Coordinate? = null,
    val text: String? = null,
    val description: String,
    val confidence: Float,
    val targetDesc: String? = null,
    val actionDesc: String? = null,
    val instruction: String? = null,
    val relatedKeywords: String? = null,
    val progress: TaskProgress? = null,
    val durationMs: Long? = null,
    // 批量提问字段（仅 ASK_USER 类型使用，对齐 GitHub Copilot ask_questions / OpenSpace ask_user_question）
    val questions: List<Question>? = null,
    // 批量重复执行字段：repeat=连续执行同一动作的次数（默认1=单次），intervalMs=每次间隔毫秒（默认800）
    val repeat: Int = 1,
    val intervalMs: Long? = null,
    // 滑动方向（仅 SWIPE 类型使用）：up/down/left/right/custom
    val direction: String? = null,
    // 方向模式滑动距离（像素，SWIPE 选填）
    val distance: Int? = null,
    // 下一轮自动屏幕描述想额外确认的问题（GUI 模型按需回答，如"当前界面是美团App吗？"；空则仅结构描述）
    val visualQuestion: String? = null,
    // 规格自动选取字段（仅 SELECT_SPEC 类型使用）：specs=需选取的规格列表，confirmText=确认按钮文本（默认"选好了"）
    val specs: List<String>? = null,
    val confirmText: String? = null,
    // 滚动查找字段（仅 SCROLL_UNTIL 类型使用）：maxScrolls=最大滚动次数，clickOnFound=找到后是否自动点击
    val maxScrolls: Int? = null,
    val clickOnFound: Boolean? = null,
    // 联网搜索字段（仅 WEB_SEARCH 使用）：query=搜索关键词（协议字段，与 text 区分），mode=web/ai 检索模式（默认web）
    val query: String? = null,
    val mode: String? = null,
    // 目标驱动滑动字段（仅 SWIPE_UNTIL 使用）：container=容器名（选填）、maxSwipes=最大滑动次数（默认5，上限10）
    val container: String? = null,
    val maxSwipes: Int? = null
)

/**
 * LLM 自管理的任务进度（由执行模型每轮自行填写）
 */
data class TaskProgress(
    val currentStep: String? = null,
    val completedSteps: List<String> = emptyList(),
    val remainingSteps: List<String> = emptyList(),
    val status: String = "in_progress"
)

data class Coordinate(
    val x: Int,
    val y: Int
)

/**
 * 批量提问的问题项（对齐 GitHub Copilot ask_questions / OpenSpace ask_user_question）
 */
data class Question(
    val question: String,           // 问题文本（必填）
    val header: String,             // 短标签（≤12 字符，用作 UI 卡片标题）
    val options: List<QuestionOption>,  // 选项数组（2-6 个）
    val multiSelect: Boolean = false,   // 是否多选
    val allowFreeInput: Boolean = true  // 是否允许自由输入（UI 自动追加"其他"选项）
)

data class QuestionOption(
    val label: String,              // 选项显示文本
    val description: String? = null,// 选项说明（可选，解释取舍）
    val recommended: Boolean = false// 是否推荐项
)

/**
 * 用户对一个问题的回答
 */
data class QuestionAnswer(
    val question: String,           // 原问题文本（用于回传给模型）
    val answer: List<String>        // 答案数组（单选为 1 项，多选为多项，自由输入为 1 项）
)

data class AgentTask(
    val id: String,
    val userRequest: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val actions: List<AgentAction> = emptyList(),
    val currentStep: Int = 0,
    val result: String? = null,
    val error: String? = null
)

enum class TaskStatus {
    PENDING, IN_PROGRESS, COMPLETED, FAILED
}

data class ChatMessage(
    val role: String,
    val content: String,
    val reasoning_content: String? = null
)

data class ToolCallResult(
    val toolName: String,
    val success: Boolean,
    val content: String = "",
    val error: String? = null,
    val durationMs: Long = 0
)

enum class BlockageSeverity {
    INFO, WARNING, ERROR
}

data class BlockageInfo(
    val reason: String,
    val userSteps: List<String>,
    val severity: BlockageSeverity = BlockageSeverity.WARNING
)
