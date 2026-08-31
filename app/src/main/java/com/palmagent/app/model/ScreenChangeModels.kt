package com.palmagent.app.model

/**
 * 界面快照 - 记录某一时刻的界面状态（多层数据源）
 */
data class ScreenSnapshot(
    val timestamp: Long,
    val packageName: String?,
    val activityName: String?,
    val elementCount: Int,
    val textElements: List<String>,
    val keyElements: List<KeyElement>,
    val screenHash: String,
    /** 图像感知哈希（pHash），8x8=64bit，用于图像层面变化检测 */
    val imageHash: String = "",
    /** 数据源可用性标记 */
    val hasAccessibility: Boolean = false,
    val hasImage: Boolean = false,
    /** 纯色占比（0.0~1.0），用于白屏/黑屏陷阱检测 */
    val solidColorRatio: Float = 0f
)

/**
 * 关键界面元素 - 用于变化检测
 */
data class KeyElement(
    val id: String,
    val type: UIElementType,
    val text: String?,
    val centerX: Int,
    val centerY: Int,
    val hash: String
)

/**
 * 界面变化类型
 */
enum class ScreenChangeType {
    /** 无变化 */
    NO_CHANGE,
    /** 页面跳转（包名或Activity变化） */
    PAGE_NAVIGATION,
    /** 元素新增 */
    ELEMENT_ADDED,
    /** 元素消失 */
    ELEMENT_REMOVED,
    /** 元素位置变化 */
    ELEMENT_MOVED,
    /** 文本内容变化 */
    TEXT_CHANGED,
    /** 列表滚动 */
    LIST_SCROLLED,
    /** 对话框出现 */
    DIALOG_APPEARED,
    /** 键盘弹出/收起 */
    KEYBOARD_TOGGLE,
    /** 加载状态变化 */
    LOADING_STATE_CHANGED,
    /** 多个变化组合 */
    COMPLEX_CHANGE
}

/**
 * 检测数据源
 */
enum class DetectionSource {
    /** 无障碍服务（最精确，首选） */
    ACCESSIBILITY,
    /** 图像哈希对比（最低精度，最终兜底） */
    IMAGE_HASH
}

/**
 * 界面变化详情
 */
data class ScreenChange(
    val changeType: ScreenChangeType,
    val description: String,
    val addedElements: List<KeyElement> = emptyList(),
    val removedElements: List<KeyElement> = emptyList(),
    val changedElements: List<ChangedElement> = emptyList(),
    val packageChanged: Boolean = false,
    val activityChanged: Boolean = false,
    val scrollDelta: Int = 0,
    val keyboardVisible: Boolean? = null,
    val loadingFinished: Boolean = false,
    /** 实际使用的检测数据源 */
    val detectionSource: DetectionSource = DetectionSource.ACCESSIBILITY,
    /** 检测置信度 0.0~1.0，无障碍=0.95, 图像=0.6, 无数据源=0.3 */
    val confidence: Float = 0.8f,
    /** 检测耗时(ms) */
    val latencyMs: Long = 0L
)

/**
 * 元素变化记录
 */
data class ChangedElement(
    val element: KeyElement,
    val previousText: String?,
    val previousX: Int,
    val previousY: Int
)

/**
 * 操作结果反馈 - 包含操作前后的界面变化
 */
data class ActionFeedback(
    val actionType: String,
    val actionDescription: String,
    val beforeSnapshot: ScreenSnapshot?,
    val afterSnapshot: ScreenSnapshot?,
    val screenChange: ScreenChange?,
    val actionSuccess: Boolean,
    val executionTimeMs: Long
)
