package com.palmagent.app.tool.impl

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import kotlinx.coroutines.delay
import org.json.JSONArray

/**
 * 规格自动选取工具（select_spec）
 *
 * 执行模型只传需要选取的规格列表，工具内部走无障碍树闭环：
 *   找节点 → 检查选中态(isChecked/isSelected) → 未选则节点直点(clickNode) → 校验翻转
 *   → 规格未出现(表单过长/懒加载)则小步慢速下滑 → 签名校验滚动生效 → 循环
 *   → 全部选好后点击确认按钮(confirm_text)
 *
 * 纯无障碍树驱动：无坐标、无视觉定位、无 shell 注入。
 */
class SelectSpecTool : BaseTool() {

    companion object {
        private const val TAG = "SelectSpecTool"
        private const val DEFAULT_CONFIRM_TEXT = "选好了"
        private const val DEFAULT_MAX_SCROLLS = 5
        private const val MAX_SCROLLS_MAX = 10
        private const val MIN_SCROLLS = 1
        /** 点击后等待状态刷新的时长 */
        private const val CLICK_SETTLE_MS = 200L
        /** 滑动后等待树稳定的时长 */
        private const val SCROLL_STABLE_MS = 350L
        /** 小步滑动的屏幕高度占比（比普通滑动小，避免滑过头） */
        private const val SMALL_SWIPE_RATIO = 0.3f
        /** 小步滑动时长（慢速，配合小路程） */
        private const val SLOW_SWIPE_MS = 600L
    }

    override fun getName(): String = "select_spec"

    // 已隐藏：规格选取改为 gui_agent 托管（需要持续视觉检测的操作由 GUI 模型自主完成）
    override fun isExposedToExecutionModel(): Boolean = false

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "specs",
            "string",
            "需要选取的规格列表（必填）：JSON 数组或逗号/顿号分隔字符串，如 [\"大份\",\"微辣\",\"去冰\"] 或 \"大份,微辣\"",
            true
        ),
        ToolParameter(
            "confirm_text",
            "string",
            "确认按钮文本（可选），默认\"选好了\"；不同 App 可能叫\"确定\"\"完成\"\"加入购物车\"",
            false
        ),
        ToolParameter(
            "max_scrolls",
            "integer",
            "表单过长时最大向下滑动轮数（可选），默认5，范围1-10",
            false,
            default = DEFAULT_MAX_SCROLLS,
            minValue = MIN_SCROLLS,
            maxValue = MAX_SCROLLS_MAX
        )
    )

    override fun getDescriptionEN(): String =
        "Select specs/options on the current form via the accessibility tree: " +
            "for each requested spec, find its node, check if already selected, tap it via node click if not, " +
            "auto scroll down (small slow steps) when the form is too long and specs are not visible, " +
            "repeat until all specs are selected, then tap the confirm button (default '选好了')."

    override fun getDescriptionCN(): String =
        "自动选取当前表单中的规格/选项（如外卖平台的份量/辣度/口味）：" +
            "依次检查每个规格是否已选中，未选中则节点直点；表单过长未显示时自动小步慢速下滑后继续检查，直到全部选取完成，" +
            "最后点击确认按钮（默认'选好了'）。全程走无障碍树，无需坐标。"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        // 1. 解析参数
        val specs = parseSpecs(params["specs"])
        if (specs.isEmpty()) {
            return ToolResult.error(
                "specs 不能为空，请传入需要选取的规格列表",
                errorType = "VALIDATION",
                code = "SPECS_EMPTY"
            )
        }
        val confirmText = optionalString(params, "confirm_text", DEFAULT_CONFIRM_TEXT)
        val maxScrolls = optionalInt(params, "max_scrolls", DEFAULT_MAX_SCROLLS)
            .coerceIn(MIN_SCROLLS, MAX_SCROLLS_MAX)

        val service = getA11yService()
            ?: return ToolResult.error(
                "无障碍服务未运行",
                errorType = "FATAL",
                failureCategory = "SERVICE_UNAVAILABLE",
                code = "A11Y_SERVICE_UNAVAILABLE",
                suggestion = "无障碍服务未运行，需用户开启服务"
            )

        Log.i(TAG, "开始选取规格: $specs, confirm=$confirmText, maxScrolls=$maxScrolls")

        val selected = LinkedHashSet<String>()   // 已确认选中的规格

        // 2. 主循环：检查 → 点击 → 不足则小步下滑
        // foundRetryPending：已对"节点存在但点击未生效(FOUND)"的规格做过一次同屏重试；
        // 防止 FOUND 永久阻塞滑动（否则表单过长的其他规格永远找不到）
        var foundRetryPending = false
        for (round in 1..maxScrolls) {
            // 2a. 对每个未选规格：找节点 → 查选中态 → 未选则节点直点
            var roundSelected = false
            var roundFound = false
            for (spec in specs) {
                if (spec in selected) continue
                when (trySelectSpec(service, spec)) {
                    SpecOutcome.SELECTED -> { selected.add(spec); roundSelected = true }
                    SpecOutcome.FOUND -> roundFound = true   // 节点存在但点击未生效（可能选中态刷新慢）
                    SpecOutcome.NOT_FOUND -> { /* 当前屏没有，等滚动后下一轮再找 */ }
                }
            }

            // 2b. 全部选中 → 跳出循环
            if (selected.size == specs.size) break

            // 2c. 本轮有规格被选中 → 同屏可能还有其它可点规格，多给一轮（不滑动）
            if (roundSelected) {
                delay(CLICK_SETTLE_MS)
                foundRetryPending = false
                continue
            }

            // 2d. 有节点存在但点击未生效 → 最多同屏重试一次（选中态刷新可能慢于 CLICK_SETTLE_MS）
            if (roundFound && !foundRetryPending) {
                foundRetryPending = true
                delay(CLICK_SETTLE_MS)
                continue
            }
            foundRetryPending = false

            // 2e. 本轮无任何进展（全部 NOT_FOUND，或 FOUND 已重试过）→ 小步慢速下滑
            val swipeResult = smallSlowScrollDown(service)
            if (!swipeResult.isSuccess) {
                return swipeResult
            }
            delay(SCROLL_STABLE_MS)
        }

        // 3. 循环结束仍未全选
        if (selected.size < specs.size) {
            return ToolResult.error(
                "达到最大滑动轮数($maxScrolls)仍未选完，未找到规格: ${specs.filterNot { it in selected }}",
                errorType = "TRANSIENT",
                failureCategory = "SPEC_NOT_FOUND",
                code = "SPEC_SCROLL_LIMIT",
                suggestion = "可尝试增加 max_scrolls 或确认规格名称"
            )
        }

        // 4. 点击确认按钮
        val confirmResult = clickConfirmButton(service, confirmText)
        if (!confirmResult.isSuccess) {
            return confirmResult
        }

        return ToolResult.success("已选取规格: ${selected.joinToString("、")}，并点击了'$confirmText'")
    }

    private enum class SpecOutcome { SELECTED, FOUND, NOT_FOUND }

    /** 尝试选中单个规格：找节点 → 查选中态 → 未选则节点直点 → 校验翻转 */
    private suspend fun trySelectSpec(service: GUIAccessibilityService, spec: String): SpecOutcome {
        val nodes = service.findNodesByText(spec)
        try {
            // 已选中（isChecked / isSelected）→ 幂等跳过
            if (nodes.any { it.isChecked || it.isSelected }) {
                return SpecOutcome.SELECTED
            }
            // 未选中：挑"文本精确匹配 && 可点"优先，其次"包含匹配 && 可点"，最后任意节点
            val clickable = nodes.firstOrNull { it.isClickable && it.text?.toString() == spec }
                ?: nodes.firstOrNull { it.isClickable && it.text?.toString()?.contains(spec) == true }
                ?: nodes.firstOrNull()
            if (clickable == null) return SpecOutcome.NOT_FOUND

            val ok = service.clickNode(clickable)
            if (!ok) return SpecOutcome.NOT_FOUND
            delay(CLICK_SETTLE_MS)

            // 点击后校验：重新查选中态是否翻转
            val verifyNodes = service.findNodesByText(spec)
            try {
                if (verifyNodes.any { it.isChecked || it.isSelected }) {
                    return SpecOutcome.SELECTED
                }
                return SpecOutcome.FOUND   // 节点存在但点击未生效（可能点的是父容器/选项不可选）
            } finally {
                verifyNodes.forEach { it.recycle() }
            }
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    /** 小步慢速下滑：路程小（30% 屏高）、速度慢（600ms），滑动后签名校验是否真的生效 */
    private suspend fun smallSlowScrollDown(service: GUIAccessibilityService): ToolResult {
        val size = getScreenSize()
        val screenH = size[1]
        val startX = size[0] / 2
        // 屏幕中部起滑，规避顶部分类 Tab 行 / 底部导航栏
        val startY = (screenH * 0.5f).toInt()
        // 内容向下看 = 手指向上滑（endY < startY）
        val endY = (startY - (screenH * SMALL_SWIPE_RATIO).toInt()).coerceAtLeast(0)

        val before = service.getTreeSignature()
        val ok = service.performAccessibilitySwipe(startX, startY, startX, endY, SLOW_SWIPE_MS)
        if (!ok) {
            return ToolResult.error(
                "下滑手势被取消",
                errorType = "TRANSIENT",
                code = "GESTURE_CANCELLED",
                suggestion = "手势被系统取消，可重试"
            )
        }
        delay(SCROLL_STABLE_MS)
        val after = service.getTreeSignature()
        if (after != null && before != null && after == before) {
            return ToolResult.error(
                "向下滑动后页面无变化，可能已到表单底部；未找到规格: 请确认规格是否存在",
                errorType = "VALIDATION",
                failureCategory = "SCROLL_NO_EFFECT",
                code = "SCROLL_NO_EFFECT",
                suggestion = "已到页面边界或内容不可滚动，请确认规格名称或改用视觉定位"
            )
        }
        return ToolResult.success("已小步下滑")
    }

    /** 点击确认按钮（精确匹配优先，其次任意可点节点） */
    private suspend fun clickConfirmButton(service: GUIAccessibilityService, confirmText: String): ToolResult {
        val nodes = service.findNodesByText(confirmText)
        try {
            val target = nodes.firstOrNull { it.isClickable && it.text?.toString() == confirmText }
                ?: nodes.firstOrNull { it.isClickable }
                ?: nodes.firstOrNull()
            if (target == null) {
                return ToolResult.error(
                    "未找到确认按钮'$confirmText'",
                    errorType = "VALIDATION",
                    failureCategory = "CONFIRM_BUTTON_NOT_FOUND",
                    code = "CONFIRM_BUTTON_NOT_FOUND",
                    suggestion = "确认按钮文本可能不同（如\"确定\"\"完成\"\"加入购物车\"），请传入实际文本"
                )
            }
            val ok = service.clickNode(target)
            return if (ok) {
                ToolResult.success("已点击'$confirmText'")
            } else {
                ToolResult.error(
                    "点击'$confirmText'失败",
                    errorType = "TRANSIENT",
                    code = "GESTURE_CANCELLED",
                    suggestion = "手势被取消，可重试"
                )
            }
        } finally {
            nodes.forEach { it.recycle() }
        }
    }

    /** 解析 specs：兼容 JSON 数组 / 逗号顿号分隔字符串 / List */
    private fun parseSpecs(raw: Any?): List<String> {
        if (raw == null) return emptyList()
        val result: List<String> = when (raw) {
            is List<*> -> raw.mapNotNull { it?.toString()?.trim() }
            is String -> {
                val s = raw.trim()
                if (s.isEmpty()) return emptyList()
                if (s.startsWith("[")) {
                    try {
                        val arr = JSONArray(s)
                        (0 until arr.length()).mapNotNull { arr.optString(it).trim() }
                    } catch (_: Exception) {
                        s.split(Regex("[,，、;；]")).map { it.trim() }
                    }
                } else {
                    s.split(Regex("[,，、;；]")).map { it.trim() }
                }
            }
            else -> raw.toString().split(Regex("[,，、;；]")).map { it.trim() }
        }
        // 去重：selected 是 Set，重复规格会导致 selected.size 永远追不上 specs.size → 误报 SPEC_SCROLL_LIMIT
        return result.filter { it.isNotEmpty() }.distinct()
    }
}
