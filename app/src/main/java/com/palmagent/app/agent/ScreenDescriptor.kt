package com.palmagent.app.agent

import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.model.GroupInfo
import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.model.UIElement
import com.palmagent.app.model.UIElementType
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.service.ScreenVlmDescribeService
import com.palmagent.app.utils.KVUtils
import com.palmagent.app.utils.recycleSafely
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * 屏幕描述生成器
 *
 * 从 DefaultAgentService 中拆分，负责：
 * - 无障碍树文本提取
 * - 已选状态提取
 * - VLM 屏幕描述生成
 * - 屏幕树空判断
 */
class ScreenDescriptor {

    companion object {
        private const val TAG = "ScreenDescriptor"
        // v2 优化：放宽无障碍可用性判定
        // 旧规则：validRatio >= 0.5（11% 被判无效，触发视觉兜底）
        // 新规则：只要有 ≥5 个非空元素（或纯元素数 ≥3）即认为无障碍可用
        private const val MIN_ELEMENTS = 3                // 最小有效元素数
        private const val MIN_VALID_RATIO = 0.0f          // 最低有效元素比例
        private const val MIN_NON_EMPTY_ELEMENTS = 5      // ≥5 个带文本/描述元素算可用
        private const val MAX_TEXT_LENGTH = 80            // 文本最大长度，超过截断
        private const val MAX_ELEMENTS = 120              // 屏幕元素最大数量，超过按评分裁剪
        /** 方案C：广告弹窗处理/复确认最大重试次数（防连环弹窗死循环） */
        private const val MAX_AD_RETRY = 2

        // 文本清洗正则
        private val URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        private val HASH_PATTERN = Regex("""^[a-f0-9]{32,}$""", RegexOption.IGNORE_CASE)
        private val IMAGE_FILE_PATTERN = Regex("""\.(jpg|jpeg|png|gif|webp|svg|bmp)(@\w+)?$""", RegexOption.IGNORE_CASE)
        private val BASE64_PATTERN = Regex("""^[A-Za-z0-9+/]{40,}={0,2}$""")
        private val MULTI_SPACE_PATTERN = Regex("""\s{2,}""")

        // 噪音元素检测正则（预编译，避免每元素重复创建）
        private val SINGLE_SYMBOL_PATTERN = Regex("^[¥✓•|·…—–\\s]$")
        private val EMOJI_PATTERN = Regex("^[\\p{So}\\p{Sk}\\u2600-\\u27BF\\uFE00-\\uFE0F\\u1F600-\\u1F64F]+$")
        private val SHORT_NUMBER_PATTERN = Regex("^\\d{1,2}$")

        // cleanAccessibilityText 正则（预编译）
        private val PAGE_INDICATOR_PATTERN = Regex("""\d+之\d+[，,]?""")
        private val TRAILING_ROLE_PATTERN = Regex("""[，,]?(按钮|标签|链接)$""")

        // ===== 容器表（container_swipe 锚定数据源——每轮容器识别结果刷新；name → 容器信息）=====
        data class ContainerInfo(
            val name: String,
            val yScreen: Int,          // 屏幕像素 y（容器中心线）
            val selected: String,      // 当前选中值（滑动效果核对）
            val direction: String      // 滑动方向：horizontal（横向滑动）/ vertical（竖向滚动）
        )

        @Volatile
        var containerTable: Map<String, ContainerInfo> = emptyMap()
            private set

        fun updateContainerTable(containers: List<ContainerInfo>) {
            containerTable = containers.associateBy { it.name }
        }
    }

    /**
     * 无障碍可用性检查结果
     */
    data class AccessibilityCheckResult(
        val isAvailable: Boolean,
        val reason: String,
        val serviceHealthy: Boolean,
        val dataValid: Boolean,
        val dataQuality: Float,  // 0.0 ~ 1.0
        val elementCount: Int,
        val packageName: String?
    )

    @Volatile
    var lastScreenDescription: String = ""
        private set

    /**
     * 屏幕描述复用标记：上一轮动作是否为"只读屏幕描述"（VISUAL_DESCRIBE）。
     * 仅只读操作（不改变界面）时才可复用上一轮描述；
     * 任何实际操作（LOCATE/TAP/SCROLL 等，LOCATE 已内置自动点击）后界面必然变化，必须重新描述。
     */
    @Volatile
    var lastRoundOnlyGrounding: Boolean = false

    fun updateLastScreenDescription(desc: String) {
        lastScreenDescription = desc
    }

    fun reset() {
        lastScreenDescription = ""
        lastRoundOnlyGrounding = false
    }

    /**
     * 判断无障碍数据是否有效（优化版）
     * 多层次检查：空检查 → 元素数量 → 包名 → 元素数量下限 → 非空元素数
     * v2：validRatio 已降至 0（不再卡 0.5 门槛），改为检查"非空元素数"
     */
    fun isAccessibilityDataValid(screenInfo: ScreenInfo?): Boolean {
        // L1: 空检查
        if (screenInfo == null) {
            Log.w(TAG, "isAccessibilityDataValid: screenInfo is null")
            return false
        }

        // L2: 元素数量检查
        val elements = screenInfo.uiElements
        if (elements.isEmpty()) {
            Log.w(TAG, "isAccessibilityDataValid: uiElements is empty")
            return false
        }

        // L3: 包名检查
        val pkg = screenInfo.currentPackage
        if (pkg.isNullOrBlank()) {
            Log.w(TAG, "isAccessibilityDataValid: packageName is null or blank")
            return false
        }

        // L4: 元素数量下限检查（带可交互元素回退）
        if (elements.size < MIN_ELEMENTS) {
            // 特殊情况：某些简单界面元素较少，检查是否有可交互元素
            val hasInteractive = elements.any { it.type.isInteractive() }
            if (!hasInteractive) {
                Log.w(TAG, "isAccessibilityDataValid: elements count=${elements.size} < $MIN_ELEMENTS, no interactive element")
                return false
            }
            return true
        }

        // L5: v2 新增：非空元素数检查（替代旧的 validRatio 门槛）
        // 只要有 ≥5 个带文本或 contentDescription 的元素，就算可用
        val nonEmptyCount = elements.count { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }
        if (nonEmptyCount < MIN_NON_EMPTY_ELEMENTS) {
            Log.w(TAG, "isAccessibilityDataValid: nonEmptyCount=$nonEmptyCount < $MIN_NON_EMPTY_ELEMENTS")
            return false
        }

        return true
    }

    /**
     * 综合检查无障碍可用性
     */
    fun checkAccessibilityAvailability(screenInfo: ScreenInfo?): AccessibilityCheckResult {
        // 1. 服务健康检查
        val serviceHealthy = GUIAccessibilityService.instance?.isServiceHealthy() ?: false

        // 2. 数据有效性检查
        val dataValid = isAccessibilityDataValid(screenInfo)

        // 3. 数据质量评估
        val elementCount = screenInfo?.uiElements?.size ?: 0
        val validCount = screenInfo?.uiElements?.count { isValidElement(it) } ?: 0
        val dataQuality = if (elementCount > 0) validCount.toFloat() / elementCount else 0f

        // 4. 综合判断
        val isAvailable = serviceHealthy && dataValid
        val reason = when {
            !serviceHealthy -> "服务不健康"
            screenInfo == null -> "数据为空"
            screenInfo.uiElements.isEmpty() -> "元素列表为空"
            screenInfo.currentPackage.isNullOrBlank() -> "包名为空"
            elementCount < MIN_ELEMENTS && !screenInfo.uiElements.any { it.type.isInteractive() } -> "元素数量不足($elementCount < $MIN_ELEMENTS)"
            // v2：使用非空元素数判定原因（不再因 validRatio 低拒绝）
            else -> {
                val nonEmpty = screenInfo.uiElements.count { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }
                if (nonEmpty < MIN_NON_EMPTY_ELEMENTS) "非空元素不足($nonEmpty < $MIN_NON_EMPTY_ELEMENTS)" else "可用"
            }
        }

        return AccessibilityCheckResult(
            isAvailable = isAvailable,
            reason = reason,
            serviceHealthy = serviceHealthy,
            dataValid = dataValid,
            dataQuality = dataQuality,
            elementCount = elementCount,
            packageName = screenInfo?.currentPackage
        )
    }

    /**
     * 检查元素是否有效（bounds有效 + 有内容）
     */
    private fun isValidElement(element: UIElement): Boolean {
        val bounds = element.bounds
        // 检查 bounds 有效性
        val w = bounds.right - bounds.left
        val h = bounds.bottom - bounds.top
        if (w <= 0 || h <= 0) return false
        if (bounds.left < 0 || bounds.top < 0) return false

        // 检查是否有内容
        val hasContent = !element.text.isNullOrBlank() ||
                         !element.contentDescription.isNullOrBlank() ||
                         element.type.isInteractive()

        return hasContent
    }

    /**
     * 从无障碍树提取屏幕文本
     * 优化：文本清洗 + 去重 + 评分剪枝 + 截断 + CollectionInfo分组 + 中心坐标
     */
    fun extractScreenText(screenInfo: ScreenInfo?): String {
        if (screenInfo == null) return ""

        // 1. 过滤：保留有文字/描述的元素 + 可滚动容器
        // 改进1：无文字且无描述的可交互元素（纯图标噪音，如"[图标] 可交互元素 (x,y)"）
        // 对文本决策无意义（模型不知道图标含义，无法决策点击），直接剔除。
        // 对齐业界实践：sanitizer "四条件全空跳过" / AppClaw "无text低分" / A11y-Compressor 去冗余。
        // 注：有 contentDescription 的图标（如"搜索""返回"）仍有描述，会被保留；
        //     纯图标页面的操作依赖 GUI-Plus 视觉定位（LOCATE），不受本过滤影响。
        val total = screenInfo.uiElements.size
        val elements = screenInfo.uiElements
            .filter { el ->
                val hasTextOrDesc = !el.text.isNullOrBlank() || !el.contentDescription.isNullOrBlank()
                val isScrollableContainer = el.type == UIElementType.LIST
                hasTextOrDesc || isScrollableContainer
            }
        Log.d(TAG, "[extractScreenText] 内容过滤: $total → ${elements.size}")

        if (elements.isEmpty()) return ""

        // 2. 文本清洗：移除图片URL/hash/Base64等无意义文本（可交互元素无文本也保留）
        val cleaned = elements.filter { el ->
            val cleanText = sanitizeText(cleanAccessibilityText(el.text))
            val cleanDesc = sanitizeText(cleanAccessibilityText(el.contentDescription))
            cleanText != null || cleanDesc != null || el.type.isInteractive() || el.type == UIElementType.LIST
        }

        if (cleaned.isEmpty()) return ""

        // 2.5 噪音过滤：移除孤立符号、emoji、纯数字角标等无操作意义的元素
        val denoised = cleaned.filter { !isNoiseElement(it) }
        if (denoised.size != cleaned.size) {
            Log.d(TAG, "[extractScreenText] 噪音过滤: ${cleaned.size} → ${denoised.size}")
        }

        // 3. 去重：父子节点内容重叠时只保留父节点（可交互子节点不去重）
        val deduped = deduplicateElements(denoised)
        Log.d(TAG, "[extractScreenText] 清洗+去重: ${cleaned.size} → ${deduped.size}")

        // 4. 评分剪枝：超过MAX_ELEMENTS时按评分裁剪
        val pruned = pruneElements(deduped)
        if (pruned.size != deduped.size) {
            Log.d(TAG, "[extractScreenText] 评分裁剪: ${deduped.size} → ${pruned.size}")
        }

        // 5. 格式化每个元素：清理TalkBack标注 + 智能合并 + 截断 + 中心坐标
        val formatted = pruned.map { el ->
            val display = truncateText(smartMergeTextDesc(
                sanitizeText(cleanAccessibilityText(el.text)),
                sanitizeText(cleanAccessibilityText(el.contentDescription))
            ))
            ElementFormat(
                typeIcon = getTypeIcon(el.type),
                display = display,
                centerX = el.bounds.centerX,
                centerY = el.bounds.centerY,
                groupInfo = el.groupInfo,
                isSelected = el.isSelected,
                isInteractive = el.type.isInteractive()
            )
        }.filter { it.display.isNotBlank() || it.isInteractive || it.typeIcon == "[列表]" }

        if (formatted.isEmpty()) return ""

        // 5.5 近距离元素合并：中心点距离<30px的元素合并为一条（如按钮图标+文字标签）
        val merged = mergeNearbyElements(formatted)
        if (merged.size != formatted.size) {
            Log.d(TAG, "[extractScreenText] 近距离合并: ${formatted.size} → ${merged.size}")
        }

        // 6. 按分组信息+Y坐标聚类输出
        return formatWithGroups(merged)
    }

    /**
     * 提取无障碍树中"已选取/勾选"的内容（精简注入，替代全量元素列表）
     *
     * 每轮【屏幕元素】不再整体注入执行模型（视觉描述为主），仅保留选中态信息：
     * selected/checked 属性优先（文本"已选"兜底），供模型感知当前选中/勾选状态，
     * 例如选项卡选中项、Radio 勾选项、当前高亮条目。无选中态时返回空串。
     */
    fun extractSelectedScreenText(screenInfo: ScreenInfo?): String {
        if (screenInfo == null) return ""
        val selected = screenInfo.uiElements
            .filter { it.isSelected || it.isChecked || (it.text?.contains("已选") == true) }
            .mapNotNull { el ->
                val label = el.text?.takeIf { it.isNotBlank() }
                    ?: el.contentDescription?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val state = when {
                    el.isChecked -> "（已勾选）"
                    el.isSelected -> "（已选中）"
                    else -> ""
                }
                "$label$state"
            }
            .distinct()
        if (selected.isEmpty()) return ""
        val lines = selected.joinToString("\n") { "  - $it" }
        Log.d(TAG, "[extractSelectedScreenText] 已选内容: $lines")
        return "【屏幕已选内容】\n$lines"
    }

    // ======================== 屏幕元素格式化辅助 ========================

    private data class ElementFormat(
        val typeIcon: String,
        val display: String,
        val centerX: Int,
        val centerY: Int,
        val groupInfo: GroupInfo? = null,
        val isSelected: Boolean = false,
        val isInteractive: Boolean = false
    )

    /**
     * 近距离元素合并：中心点距离<30px的元素合并为一条
     * 解决按钮图标+文字标签重复问题（如 [按钮]待付款(115,680) + [文本]待付款(115,707)）
     * 规则：同一簇内优先保留交互元素，display取最长文本
     */
    private fun mergeNearbyElements(elements: List<ElementFormat>): List<ElementFormat> {
        if (elements.size <= 1) return elements
        val threshold = 30.0
        val used = mutableSetOf<Int>()
        val result = mutableListOf<ElementFormat>()

        for (i in elements.indices) {
            if (i in used) continue
            val cluster = mutableListOf(elements[i])
            used.add(i)

            for (j in i + 1 until elements.size) {
                if (j in used) continue
                val dist = Math.sqrt(
                    Math.pow((elements[i].centerX - elements[j].centerX).toDouble(), 2.0) +
                    Math.pow((elements[i].centerY - elements[j].centerY).toDouble(), 2.0)
                )
                if (dist < threshold) {
                    cluster.add(elements[j])
                    used.add(j)
                }
            }

            if (cluster.size == 1) {
                result.add(cluster[0])
            } else {
                // 交互元素优先，文本长的优先
                val best = cluster.maxWithOrNull(compareBy(
                    { if (it.isInteractive) 1 else 0 },
                    { it.display.length }
                )) ?: cluster[0]
                result.add(best)
            }
        }
        return result
    }

    /**
     * 父子节点去重：子节点text/desc是父节点的子串时只保留父节点
     * 优化：可交互子节点不去重（保留操作入口）
     */
    private fun deduplicateElements(elements: List<UIElement>): List<UIElement> {
        val result = mutableListOf<UIElement>()
        val removed = mutableSetOf<Int>()

        for (i in elements.indices) {
            if (i in removed) continue
            val parent = elements[i]
            val parentText = cleanAccessibilityText(parent.text) ?: ""
            val parentDesc = cleanAccessibilityText(parent.contentDescription) ?: ""
            val parentContent = listOfNotNull(parentText, parentDesc).joinToString(" ")

            for (j in i + 1 until elements.size) {
                if (j in removed) continue
                val child = elements[j]
                // 检查bounds包含关系
                if (!boundsContains(parent.bounds, child.bounds)) continue

                // 可交互子节点不去重（保留操作入口）
                if (child.isClickable || child.isEditable || child.isScrollable) continue

                val childText = cleanAccessibilityText(child.text) ?: ""
                val childDesc = cleanAccessibilityText(child.contentDescription) ?: ""
                val childContent = listOfNotNull(childText, childDesc).joinToString(" ")

                // 子节点内容是父节点内容的子串 → 去重
                if (childContent.isNotBlank() && parentContent.contains(childContent)) {
                    removed.add(j)
                }
            }
            result.add(parent)
        }
        return result
    }

    private fun boundsContains(parent: com.palmagent.app.model.Bounds, child: com.palmagent.app.model.Bounds): Boolean {
        return parent.left <= child.left && parent.top <= child.top &&
                parent.right >= child.right && parent.bottom >= child.bottom
    }

    /**
     * 清理TalkBack无障碍冗余信息
     * - "直播,7之1,标签" → "直播"
     * - "搜索栏，按钮" → "搜索栏"
     * - "游戏中心,1条未读,按钮" → "游戏中心,1条未读"
     */
    private fun cleanAccessibilityText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return text
            .replace(PAGE_INDICATOR_PATTERN, "")       // 去掉 "7之1," "5之2，"
            .replace(TRAILING_ROLE_PATTERN, "") // 去掉结尾的 "，按钮" ",标签"
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /**
     * 文本清洗：移除图片URL/hash/Base64等无意义文本
     * - "16ddec2c1da4b1aa818d6cfc9c9a41522da20fe0.jpg@120w_120h_1c" → null（移除）
     * - "https://example.com/image.png" → null（移除）
     * - 纯hash值 "a1b2c3d4e5f6..." → null（移除）
     */
    private fun sanitizeText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        var cleaned = text.trim()

        // 图片文件名（含CDN参数），如 xxx.jpg@120w_120h_1c
        if (IMAGE_FILE_PATTERN.containsMatchIn(cleaned)) return null

        // 纯hash值（32位以上的十六进制字符串）
        if (HASH_PATTERN.matches(cleaned)) return null

        // Base64编码字符串
        if (BASE64_PATTERN.matches(cleaned)) return null

        // URL替换为占位符（保留URL前后的其他文本）
        if (URL_PATTERN.containsMatchIn(cleaned)) {
            cleaned = cleaned.replace(URL_PATTERN, "[URL]")
        }

        // 换行替换为空格，多空格合并
        cleaned = cleaned.replace('\n', ' ').replace('\r', ' ')
        cleaned = cleaned.replace(MULTI_SPACE_PATTERN, " ")

        return cleaned.trim().takeIf { it.isNotBlank() && it != "[URL]" }
    }

    /**
     * 噪音元素判断：孤立符号、emoji、纯数字角标等对Agent操作无意义的元素
     * 交互元素不做噪音过滤（即使文本为符号也可能是操作目标）
     */
    private fun isNoiseElement(el: UIElement): Boolean {
        if (el.type.isInteractive()) return false

        val text = el.text?.trim()
        val desc = el.contentDescription?.trim()

        // 孤立符号：单字符 ¥ ✓ • | · … — – 等
        if (text != null && SINGLE_SYMBOL_PATTERN.matches(text)) return true
        if (desc != null && SINGLE_SYMBOL_PATTERN.matches(desc)) return true

        // emoji 表情（Unicode So/Sk 范围）
        if (text != null && EMOJI_PATTERN.matches(text)) return true
        if (desc != null && EMOJI_PATTERN.matches(desc)) return true

        // 纯数字1-2位（角标如 "1"、"56"，附属于按钮，单独出现无操作意义）
        if (text != null && SHORT_NUMBER_PATTERN.matches(text)) return true

        return false
    }

    /**
     * 文本截断：超长文本在最近的空格/标点处截断
     */
    private fun truncateText(text: String, maxLen: Int = MAX_TEXT_LENGTH): String {
        if (text.length <= maxLen) return text
        // 在最近的空格/标点处截断，避免截断词语中间
        val cutAt = text.substring(0, maxLen).lastIndexOfAny(charArrayOf(' ', '，', '。', '、', ',', '.', '！', '？', '；', '：'))
        return if (cutAt > maxLen / 2) {
            text.substring(0, cutAt) + "…"
        } else {
            text.substring(0, maxLen) + "…"
        }
    }

    /**
     * 元素评分：可交互(10) > 有文本(6) > 有描述(4) > 面积(0-5)
     * 参考 adb-mcp 的评分剪枝方案
     */
    private fun scoreElement(el: UIElement): Int {
        var score = 0
        if (el.type.isInteractive()) score += 10
        if (!el.text.isNullOrBlank()) score += 6
        if (!el.contentDescription.isNullOrBlank()) score += 4
        val area = (el.bounds.right - el.bounds.left) * (el.bounds.bottom - el.bounds.top)
        score += minOf(area / 10000, 5)
        return score
    }

    /**
     * 两阶段评分剪枝：
     * 1. 可交互元素为 must_keep，全部保留
     * 2. 剩余名额从非交互元素中按评分取
     * 参考 adb-mcp 的 _prune 方案
     */
    private fun pruneElements(elements: List<UIElement>, maxCount: Int = MAX_ELEMENTS): List<UIElement> {
        if (elements.size <= maxCount) return elements

        val (interactive, nonInteractive) = elements.partition { it.type.isInteractive() }

        return if (interactive.size >= maxCount) {
            // 可交互元素自身超限，按评分保留 top
            interactive.sortedByDescending { scoreElement(it) }.take(maxCount)
        } else {
            // 剩余名额从非交互元素中按评分取
            val remaining = maxCount - interactive.size
            val topNonInteractive = nonInteractive
                .sortedByDescending { scoreElement(it) }
                .take(remaining)
            // 合并后按Y坐标排序，保持屏幕空间顺序
            (interactive + topNonInteractive).sortedBy { it.bounds.centerY }
        }
    }

    /**
     * 智能合并 text 和 desc
     * 规则1: text == desc → 只输出text
     * 规则2: desc包含text → 只输出text（text更简洁）
     * 规则3: text包含desc → 只输出text
     * 规则4: 无包含关系 → 输出text（对模型更直观）
     * 规则5: text为空 → 输出desc
     */
    private fun smartMergeTextDesc(text: String?, desc: String?): String {
        val t = text?.trim()?.takeIf { it.isNotBlank() }
        val d = desc?.trim()?.takeIf { it.isNotBlank() }
        return when {
            t == null && d == null -> ""
            t == null -> d!!
            d == null -> t
            t == d -> t
            d.contains(t) -> t  // desc是text的扩展，取简洁的text
            t.contains(d) -> t
            else -> t  // text对模型更直观
        }
    }

    /**
     * 获取类型标签
     */
    private fun getTypeIcon(type: UIElementType): String = when (type) {
        UIElementType.BUTTON -> "[按钮]"
        UIElementType.TAB -> "[标签]"
        UIElementType.INPUT -> "[输入]"
        UIElementType.ICON -> "[图标]"
        UIElementType.SWITCH -> "[开关]"
        UIElementType.LINK -> "[链接]"
        UIElementType.TEXT -> "[文本]"
        else -> ""
    }

    /**
     * 按分组信息格式化输出
     * 优先级：CollectionInfo分组 > desc解析"N之M" > Y坐标聚类
     */
    private fun formatWithGroups(elements: List<ElementFormat>): String {
        val groups = mutableMapOf<String, MutableList<ElementFormat>>()
        val ungrouped = mutableListOf<ElementFormat>()

        // 第一优先级：CollectionInfo分组
        for (el in elements) {
            if (el.groupInfo != null) {
                groups.getOrPut(el.groupInfo.groupId) { mutableListOf() }.add(el)
            } else {
                ungrouped.add(el)
            }
        }

        // 第二优先级：从原始desc解析"N之M"模式
        // cleanAccessibilityText已清理了"N之M"，但groupInfo为null的元素
        // 可能在原始desc中包含该模式。由于cleanAccessibilityText已处理，
        // 这里直接进入Y坐标聚类
        val stillUngrouped = ungrouped

        // 第三优先级：Y坐标聚类
        val yClusters = clusterByY(stillUngrouped)

        return buildString {
            appendLine("【屏幕元素】")

            // 输出CollectionInfo分组
            val sortedGroups = groups.values.sortedBy { group -> group.minOf { it.centerY } }
            for (group in sortedGroups) {
                val firstEl = group.first()
                val totalItems = firstEl.groupInfo?.totalItems ?: group.size
                val selectionMode = firstEl.groupInfo?.selectionMode ?: GroupInfo.SELECTION_NONE
                val groupLabel = when (selectionMode) {
                    GroupInfo.SELECTION_SINGLE -> "标签栏"
                    GroupInfo.SELECTION_MULTIPLE -> "多选组"
                    else -> if (totalItems > 0) "列表" else "分组"
                }
                appendLine("── ${groupLabel}(${totalItems}项) ──")
                for (el in group.sortedBy { it.groupInfo?.itemIndex ?: it.centerX }) {
                    appendLine(formatElement(el))
                }
            }

            // 输出Y坐标聚类分组
            for (cluster in yClusters) {
                if (cluster.size >= 2) {
                    val minY = cluster.minOf { it.centerY }
                    val maxY = cluster.maxOf { it.centerY }
                    appendLine("── 区域 Y=${minY}~${maxY} ──")
                    for (el in cluster.sortedWith(compareBy({ it.centerY }, { it.centerX }))) {
                        appendLine(formatElement(el))
                    }
                } else {
                    for (el in cluster) {
                        appendLine(formatElement(el))
                    }
                }
            }
        }
    }

    private fun formatElement(el: ElementFormat): String {
        // P1-1：选中态显式标注（对齐 cccontrol "tree is your vision"）——
        // 模型直接看到"【当前选中】"，而非从 ✓ 标记推断（实机日志曾因"团购✓"未识别导致误判页面状态）
        val selected = if (el.isSelected) "【当前选中】" else ""
        val icon = if (el.typeIcon.isNotBlank()) "${el.typeIcon} " else "  "
        val display = if (el.display.isNotBlank()) el.display else if (el.isInteractive) "可交互元素" else ""
        return "  $icon$selected$display  (${el.centerX},${el.centerY})"
    }

    /**
     * 按Y坐标聚类：相邻元素centerY差值 < 屏幕高度*5% 归为同一行，相邻行差值 < 8% 归为同一区域
     */
    private fun clusterByY(elements: List<ElementFormat>): List<List<ElementFormat>> {
        if (elements.isEmpty()) return emptyList()

        // 估算屏幕高度（取最大Y值的1.1倍）
        val estimatedScreenHeight = (elements.maxOf { it.centerY } * 1.1).toInt().coerceAtLeast(1000)
        val rowThreshold = estimatedScreenHeight * 0.05
        val clusterThreshold = estimatedScreenHeight * 0.08

        // 按Y排序
        val sorted = elements.sortedBy { it.centerY }

        // 先按行分组
        val rows = mutableListOf<List<ElementFormat>>()
        var currentRow = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            if (Math.abs(sorted[i].centerY - currentRow[0].centerY) < rowThreshold) {
                currentRow.add(sorted[i])
            } else {
                rows.add(currentRow)
                currentRow = mutableListOf(sorted[i])
            }
        }
        rows.add(currentRow)

        // 再按区域聚类
        val clusters = mutableListOf<List<ElementFormat>>()
        var currentCluster = mutableListOf<ElementFormat>()
        var lastRowCenterY = 0.0

        for (row in rows) {
            val rowCenterY = row.map { it.centerY.toDouble() }.average()
            if (currentCluster.isEmpty() || Math.abs(rowCenterY - lastRowCenterY) < clusterThreshold) {
                currentCluster.addAll(row)
            } else {
                clusters.add(currentCluster)
                currentCluster = row.toMutableList()
            }
            lastRowCenterY = rowCenterY
        }
        if (currentCluster.isNotEmpty()) {
            clusters.add(currentCluster)
        }

        return clusters
    }

    /**
     * 生成屏幕描述（条件性VLM调用）
     *
     * 策略：
     * - 每轮强制调用视觉模型（GUI-Plus/VLM）生成屏幕描述，不跳过
     * - 上一轮仅定位无操作时：复用上轮描述（屏幕未变）
     */
    suspend fun generateScreenDescription(
        isTreeEmpty: Boolean,
        screenshotBmp: Bitmap?,
        screenInfo: ScreenInfo?,
        round: Int,
        visualQuestion: String? = null
    ): String {
        if (screenshotBmp == null || screenshotBmp.isRecycled) {
            return ""
        }

        if (lastRoundOnlyGrounding && lastScreenDescription.isNotBlank()) {
            Log.d(TAG, "复用上一轮屏幕描述（上轮为只读 VISUAL_DESCRIBE，界面未变）")
            return lastScreenDescription
        }

        val accessibilityCheck = checkAccessibilityAvailability(screenInfo)

        Log.d(TAG, "触发视觉描述: isTreeEmpty=$isTreeEmpty, available=${accessibilityCheck.isAvailable}, quality=${accessibilityCheck.dataQuality}, elements=${accessibilityCheck.elementCount}")

        val cropTopPx = getStatusBarHeight()
        val croppedBmp = if (screenshotBmp.height > cropTopPx && cropTopPx > 0) {
            Bitmap.createBitmap(screenshotBmp, 0, cropTopPx, screenshotBmp.width, screenshotBmp.height - cropTopPx)
        } else {
            screenshotBmp
        }
        val isCroppedNew = croppedBmp !== screenshotBmp

        return coroutineScope {
            try {
                // 提取无障碍树中的"已选"状态信息（selected/checked 属性优先——文本"已选"兜底），注入 VLM prompt
                val selectedHint = screenInfo?.uiElements
                    ?.filter { it.isSelected || it.isChecked || (it.text?.contains("已选") == true) }
                    ?.mapNotNull { el ->
                        val label = el.text?.takeIf { it.isNotBlank() }
                            ?: el.contentDescription?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        val state = when {
                            el.isChecked -> "（已勾选）"
                            el.isSelected -> "（已选中）"
                            else -> ""
                        }
                        "$label$state"
                    }
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { "  - $it" }
                    ?.let { "\n\n【无障碍已选状态】\n$it" }
                    ?: ""

                val enhancedQuestion = if (selectedHint.isNotEmpty() && visualQuestion != null) {
                    "$visualQuestion$selectedHint"
                } else visualQuestion

                // 每轮并行容器识别（独立请求——与视觉描述各自专注）：识别可横向滑动 + 可竖向滚动容器（[0,1000] 归一化坐标）
                val containerDeferred = async {
                    try {
                        GuiOwlService.recognizeContainers(croppedBmp)
                    } catch (e: Exception) {
                        Log.w(TAG, "容器识别失败: ${e.message}")
                        null
                    }
                }

                // 云端 VLM 屏幕描述：优先智谱 GLM-5.3-Flash 简短描述（含广告标记行解析）
                // （复用 GUI-Plus 无广告判定的轻量通道）；未配置或调用失败时回退 GUI-Plus
                // describeScreen（保留 GUI-Plus 的 XML 广告判定兼容）
                val vlmResult = if (ScreenVlmDescribeService.isConfigured) {
                    val short = try {
                        ScreenVlmDescribeService.describeScreen(croppedBmp)
                    } catch (e: Exception) {
                        Log.w(TAG, "GLM屏幕描述异常: ${e.message}")
                        null
                    }
                    if (short?.success == true && short.answer.isNotBlank()) {
                        // GLM 广告标记行 → AdJudgement：auto_skip=true 归入 auto（重新描述等待自动跳过，
                        // 不客户端点击；视觉描述本身耗时 ~5s，描述完广告多半已自动跳过）；
                        // auto_skip=false 归入 close，并携带 GLM 描述的关闭按钮位置供 grounding 定位点击
                        val glmAd = short.adInfo?.takeIf { it.isAd }?.let { info ->
                            Log.w(TAG, "GLM识别到广告[${info.adType}]，auto_skip=${info.autoSkip}，关闭按钮: ${info.closeButton}")
                            LiveLogBuffer.append("🚫 GLM广告判定: ${info.adType}（${info.closeButton}）")
                            if (info.autoSkip) {
                                GuiOwlService.AdJudgement(type = "auto", delaySeconds = null)
                            } else {
                                GuiOwlService.AdJudgement(
                                    type = "close", coordinate = null, conf = null,
                                    closeButton = info.closeButton
                                )
                            }
                        }
                        GuiOwlService.VlmResult(
                            success = true,
                            answer = short.answer,
                            durationMs = short.durationMs,
                            adJudgement = glmAd
                        )
                    } else if (short?.error != null) {
                        Log.w(TAG, "GLM屏幕描述失败(${short.error})，回退GUI-Plus")
                        fallbackDescribeScreen(croppedBmp, enhancedQuestion)
                    } else {
                        fallbackDescribeScreen(croppedBmp, enhancedQuestion)
                    }
                } else {
                    fallbackDescribeScreen(croppedBmp, enhancedQuestion)
                }

                // 方案C：广告弹窗判定处理（close→三层关闭 / auto→等待）
                // 复确认循环：最多 2 次，仍为广告则放弃，返回空描述（下轮自然重新取屏）
                val adJudgement = vlmResult?.adJudgement
                if (adJudgement != null && adJudgement.type != "normal") {
                    for (attempt in 1..MAX_AD_RETRY) {
                        handleAdPopup(adJudgement)
                        val cleanDesc = describeCleanScreen(enhancedQuestion)
                        if (cleanDesc != null) {
                            lastScreenDescription = cleanDesc
                            Log.d(TAG, "方案C 复确认成功(第${attempt}次): 弹窗已处理")
                            return@coroutineScope cleanDesc
                        }
                        Log.w(TAG, "方案C 复确认仍为广告(第${attempt}次/$MAX_AD_RETRY)，继续重试")
                    }
                    // auto 耗尽后转 close 兜底（文档：仍 auto 则转 close 三层关闭）
                    if (adJudgement.type == "auto") {
                        Log.w(TAG, "方案C auto 复确认耗尽，转 close 三层关闭兜底")
                        handleAdPopup(
                            GuiOwlService.AdJudgement(type = "close", coordinate = null, conf = null)
                        )
                        val cleanDesc = describeCleanScreen(enhancedQuestion)
                        if (cleanDesc != null) {
                            lastScreenDescription = cleanDesc
                            return@coroutineScope cleanDesc
                        }
                    }
                    // 复确认耗尽仍失败：返回空，下轮循环自然重新取屏
                    Log.w(TAG, "方案C 广告处理${MAX_AD_RETRY}次仍未成功，放弃本次处理，交下轮/方案A提示词兜底")
                    return@coroutineScope ""
                }

                if (vlmResult != null && vlmResult.success && vlmResult.answer.isNotBlank()) {
                    // 容器识别结果（[0,1000]）→ 屏幕像素注入文本（执行模型 swipe 起点直接用）
                    val containerSection = containerDeferred.await()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { buildContainerSection(it, croppedBmp.width, croppedBmp.height, cropTopPx) }
                        ?: ""
                    val desc = buildString {
                        val pkg = screenInfo?.currentPackage
                        if (!pkg.isNullOrBlank()) {
                            append("【屏幕视觉描述】应用: $pkg\n")
                        } else {
                            append("【屏幕视觉描述】")
                        }
                        appendLine(vlmResult.answer)
                        append(containerSection)
                    }
                    lastScreenDescription = desc
                    Log.d(TAG, "VLM屏幕描述: ${vlmResult.answer.take(100)} (${vlmResult.durationMs}ms)")
                    LiveLogBuffer.append("👁 VLM屏幕描述: ${vlmResult.answer.take(60)} (${vlmResult.durationMs}ms)")
                    return@coroutineScope desc
                }

                return@coroutineScope ""
            } finally {
                if (isCroppedNew && !croppedBmp.isRecycled) {
                    croppedBmp.recycle()
                }
            }
        }
    }

    /** 容器识别 JSON（name/y/type/selected）→ 双容器段（横向/竖向）注入文本（y 转屏幕像素）+ 更新容器表 */
    private fun buildContainerSection(
        containerJson: String,
        imgWidth: Int,
        imgHeight: Int,
        cropTopPx: Int
    ): String {
        return try {
            // 容错：剥离模型可能输出的 ```json 代码块包裹，再解析
            val cleanedJson = containerJson
                .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
                .trim()
            val arr = JSONArray(cleanedJson)
            if (arr.length() == 0) return ""
            val containers = mutableListOf<ContainerInfo>()
            val horizontalSb = StringBuilder("\n【可横向滑动容器】（屏幕像素 y——swipe_until 的 container 按容器名选取并横向滑动）\n")
            val verticalSb = StringBuilder("\n【可竖向滚动容器】（屏幕像素 y——swipe_until 的 container 按容器名选取并竖向滚动）\n")
            var hCount = 0
            var vCount = 0
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue
                val y1000 = obj.optInt("y", -1)
                if (y1000 < 0) continue
                val selected = obj.optString("selected", "")
                val direction = obj.optString("type", "horizontal").ifBlank { "horizontal" }
                val isVertical = direction.equals("vertical", ignoreCase = true)
                val sy = y1000 * imgHeight / 1000 + cropTopPx
                containers += ContainerInfo(name, sy, selected, if (isVertical) "vertical" else "horizontal")
                val line = "- $name: y=${sy}px, 当前选中${selected.ifBlank { "无" }}\n"
                if (isVertical) {
                    verticalSb.append(line)
                    vCount++
                } else {
                    horizontalSb.append(line)
                    hCount++
                }
            }
            if (containers.isNotEmpty()) {
                updateContainerTable(containers)
            }
            // 真机调试观测点：容器解析结果（横向/竖向数量 + 注入段），与 GuiOwlService[CONTAINER] 呼应形成链路闭环
            Log.d(TAG, "容器识别解析: 横向${hCount}个, 竖向${vCount}个 → ${buildString { if (hCount > 0) append("【可横向滑动容器】") ; if (vCount > 0) append("【可竖向滚动容器】") }}")
            buildString {
                if (hCount > 0) append(horizontalSb.toString())
                if (vCount > 0) append(verticalSb.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "容器识别结果解析失败: ${e.message}")
            ""
        }
    }

    /**
     * 获取状态栏高度（像素），用于裁剪截图顶部避免VLM描述状态栏信息
     * 业界做法：DisplayCutout.safeInsetTop（API 28+——刘海/挖孔屏动态 insets 权威来源）优先；
     * 资源兜底（getResourceId——非刘海屏）；降级 0（不再写死 20px——不引入偏移）
     */
    private fun getStatusBarHeight(): Int {
        return try {
            val context = com.palmagent.app.AgentApplication.instance
            // ① DisplayCutout 安全区顶部（刘海/挖孔屏真实 insets——权威来源）
            val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            val cutoutTop = wm?.defaultDisplay?.cutout?.safeInsetTop ?: 0
            if (cutoutTop > 0) return cutoutTop
            // ② 资源兜底（非刘海屏——状态栏资源）
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resourceId > 0) {
                context.resources.getDimensionPixelSize(resourceId)
            } else {
                0 // 降级：不引入偏移（不再写死 20px）
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取状态栏高度失败: ${e.message}")
            0
        }
    }

    // ==================== 方案C：广告弹窗处理 ====================

    /** 屏幕尺寸（用于模型归一化坐标换算） */
    private fun getScreenSizePx(): IntArray {
        return try {
            val metrics = android.util.DisplayMetrics()
            val wm = com.palmagent.app.AgentApplication.instance
                .getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
            wm?.defaultDisplay?.getRealMetrics(metrics)
            intArrayOf(metrics.widthPixels, metrics.heightPixels)
        } catch (e: Exception) {
            Log.w(TAG, "获取屏幕尺寸失败: ${e.message}")
            intArrayOf(0, 0) // 降级 0——fail-safe（不猜值——调用方防护；避免按错误尺寸换算坐标）
        }
    }

    /**
     * 方案C：广告弹窗处理。
     * close → 四层关闭：GLM关闭按钮grounding(第0层) → 模型坐标(conf≥0.6) → 无障碍树 → BACK
     * auto → GLM auto_skip=true 时不做客户端点击，交由复确认重新发起视觉描述；
     *        GUI-Plus auto（delaySeconds 非空）等待 delay(clamp 1-10s)
     */
    private suspend fun handleAdPopup(judgement: GuiOwlService.AdJudgement) {
        val service = GUIAccessibilityService.instance ?: return
        when (judgement.type) {
            "close" -> {
                // 保守兜底：仅当模型明确给出 conf 且 <0.6 才视为低置信按 normal 处理（文档 5.3）
                // conf=null（未给出/auto转close兜底）不拦截，执行关闭
                if (judgement.conf != null && judgement.conf < 0.6f) {
                    Log.d(TAG, "方案C close conf=${judgement.conf}<0.6 低置信，按 normal 处理，不关闭")
                    return
                }
                var closed = false
                // 第0层：GLM closeButton（关闭按钮文字=位置）→ GUI-Plus Grounding 定位点击。
                // GLM 视觉描述给出的关闭按钮位置（如 "×=弹窗右上角白色小叉"），由 grounding 精确定位后点击。
                if (!closed && !judgement.closeButton.isNullOrBlank() &&
                    !judgement.closeButton.contains("无关闭按钮")
                ) {
                    val size = getScreenSizePx()
                    val shot = service.takeScreenshot()
                    if (size[0] > 0 && size[1] > 0 && shot != null) {
                        try {
                            val g = GuiOwlService.ground(
                                instruction = "点击关闭广告弹窗的关闭按钮：${judgement.closeButton}",
                                bitmap = shot,
                                screenWidth = size[0],
                                screenHeight = size[1]
                            )
                            if (g.success && g.pixelCoordinate != null) {
                                closed = service.performAccessibilityClick(g.pixelCoordinate.x, g.pixelCoordinate.y)
                                Log.d(TAG, "方案C close第0层(GLM关闭按钮grounding): ${g.pixelCoordinate.x},${g.pixelCoordinate.y} -> $closed")
                            } else {
                                Log.w(TAG, "方案C close第0层 grounding失败: ${g.error ?: "无坐标"}")
                            }
                        } finally {
                            shot.recycleSafely()
                        }
                    } else {
                        Log.w(TAG, "方案C close第0层跳过：截图/尺寸获取失败（走第2层无障碍兜底）")
                    }
                }
                // 第1层：模型坐标
                if (!closed) {
                    val coord = judgement.coordinate?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }
                    if (coord != null && coord.size >= 2) {
                        val size = getScreenSizePx()
                        if (size[0] <= 0 || size[1] <= 0) {
                            Log.w(TAG, "方案C close第1层跳过：屏幕尺寸获取失败（走第2层无障碍兜底）")
                        } else {
                            val px = GuiOwlService.scaleCoordinate(
                                coord[0].toDouble(), coord[1].toDouble(), size[0], size[1]
                            )
                            closed = service.performAccessibilityClick(px.x, px.y)
                            Log.d(TAG, "方案C close第1层(模型坐标): ${px.x},${px.y} -> $closed")
                        }
                    }
                }
                // 第2层：无障碍树（模型坐标异常/点击无效时）
                if (!closed) {
                    for (kw in listOf("跳过", "关闭")) {
                        val nodes = service.findNodesByText(kw)
                        // 精确匹配优先；"关闭"需过滤"关闭通知"等误匹配（审查 #1-2）
                        val target = nodes.firstOrNull { it.isClickable && it.text?.toString() == kw }
                            ?: nodes.firstOrNull {
                                it.isClickable &&
                                it.text?.toString()?.contains(kw) == true &&
                                !it.text.toString().contains("关闭通知")
                            }
                        if (target != null) {
                            closed = service.clickNode(target)
                            nodes.forEach { it.recycle() }
                            if (closed) break
                        } else {
                            nodes.forEach { it.recycle() }
                        }
                    }
                }
                // 第3层：BACK
                if (!closed) {
                    closed = service.performAccessibilityBack()
                }
                Log.d(TAG, "方案C close处理结果: $closed")
                if (closed) delay(500)   // 等待弹窗关闭动画
            }
            "auto" -> {
                // GLM auto（可自动跳过）：delaySeconds=null，不做客户端点击，交由 describeCleanScreen
                // 重新发起一轮视觉描述（描述耗时 ~5s，广告大概率已自动跳过；仍广告则由主循环重试）。
                // GUI-Plus auto（delaySeconds 非空）保留原有等待语义。
                val delaySec = judgement.delaySeconds
                if (delaySec != null) {
                    val sec = delaySec.coerceIn(1, 10)
                    Log.d(TAG, "方案C auto等待 ${sec}s")
                    delay(sec * 1000L)
                } else {
                    Log.d(TAG, "方案C auto跳过等待：交由复确认重新发起视觉描述")
                }
            }
            else -> {}
        }
    }

    /**
     * 回退：GUI-Plus 屏幕描述（含广告判定）；服务未就绪返回 null
     */
    private suspend fun fallbackDescribeScreen(
        croppedBmp: Bitmap,
        question: String?
    ): GuiOwlService.VlmResult? {
        if (!GuiOwlService.isReady) return null
        return try {
            GuiOwlService.describeScreen(croppedBmp, question)
        } catch (e: Exception) {
            Log.w(TAG, "VLM屏幕描述失败: ${e.message}")
            null
        }
    }

    /**
     * 方案C：弹窗处理完成后重新截屏复确认，返回干净描述；仍为广告/失败返回 null。
     * auto 分支（GLM 可自动跳过广告）：不客户端点击，由本方法重新发起一轮 GLM 视觉描述
     * ——描述耗时 ~5s，广告大概率已自动跳过；复确认判定为非广告即返回干净描述。
     */
    private suspend fun describeCleanScreen(question: String?): String? {
        val service = GUIAccessibilityService.instance ?: return null
        val shot = service.takeScreenshot() ?: return null
        try {
            // 优先 GLM 视觉描述（含广告标记行解析），失败/未配置回退 GUI-Plus
            val reCheck = if (ScreenVlmDescribeService.isConfigured) {
                val glm = try {
                    ScreenVlmDescribeService.describeScreen(shot)
                } catch (e: Exception) {
                    Log.w(TAG, "GLM复确认描述异常: ${e.message}")
                    null
                }
                if (glm?.success == true && glm.answer.isNotBlank()) {
                    GuiOwlService.VlmResult(
                        success = true,
                        answer = glm.answer,
                        durationMs = glm.durationMs,
                        adJudgement = glm.adInfo?.takeIf { it.isAd }?.let { info ->
                            // 复确认仍判定为广告：按 GLM auto_skip 再转一次类型，便于主循环继续分流
                            if (info.autoSkip) {
                                GuiOwlService.AdJudgement(type = "auto", delaySeconds = null)
                            } else {
                                GuiOwlService.AdJudgement(
                                    type = "close", coordinate = null, conf = null,
                                    closeButton = info.closeButton
                                )
                            }
                        }
                    )
                } else null
            } else null
            val finalCheck = reCheck ?: (GuiOwlService.describeScreen(shot, question) ?: return null)
            if (!finalCheck.success || finalCheck.answer.isBlank()) return null
            // 复确认：仅当不再判定为广告/弹窗时才采用描述
            if (finalCheck.adJudgement != null && finalCheck.adJudgement.type != "normal") return null
            return buildString {
                append("【屏幕视觉描述】")
                appendLine(finalCheck.answer)
            }
        } finally {
            shot.recycleSafely()
        }
    }
}
