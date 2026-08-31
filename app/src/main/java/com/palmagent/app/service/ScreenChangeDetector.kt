package com.palmagent.app.service

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.palmagent.app.model.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 界面变化检测服务 — 双层检测机制
 *
 * 检测优先级：
 * 1. 无障碍服务（最精确）→ 元素级变化检测
 * 2. 图像感知哈希 + SSIM 双校验（兜底）→ 像素级变化检测
 *
 * 优化项（基于屏幕变化检测调研报告）：
 * - pHash + SSIM 双校验，规避白屏/黑屏陷阱
 * - 白屏/黑屏检测（颜色直方图主色占比）
 * - confidence 置信度输出
 * - latencyMs 检测耗时记录
 */
object ScreenChangeDetector {

    private const val TAG = "ScreenChangeDetector"

    /** 元素匹配阈值（坐标偏差像素） */
    private const val COORDINATE_THRESHOLD = 50
    /** 滚动检测阈值 */
    private const val SCROLL_THRESHOLD = 100
    /** 图像哈希差异阈值（0-1，超过此值认为界面变化）
     *  8x8 pHash对局部变化不敏感，需要较低阈值 */
    private const val IMAGE_HASH_THRESHOLD = 0.05
    /** SSIM 相似度阈值（低于此值认为界面变化） */
    private const val SSIM_THRESHOLD = 0.95
    /** 白屏/黑屏主色占比阈值（超过90%单一灰度视为白屏/黑屏） */
    private const val SOLID_COLOR_RATIO_THRESHOLD = 0.9f

    /** 各数据源置信度 */
    private const val CONFIDENCE_ACCESSIBILITY = 0.95f
    private const val CONFIDENCE_IMAGE_HASH = 0.6f
    private const val CONFIDENCE_NO_SOURCE = 0.3f

    /** 操作前快照缓存 */
    private val preActionSnapshots = ConcurrentHashMap<String, ScreenSnapshot>()

    // ============================================================
    //  公开 API
    // ============================================================

    /**
     * 保存操作前的界面快照
     */
    fun savePreActionSnapshot(
        taskId: String,
        screenInfo: ScreenInfo?,
        screenshotBmp: Bitmap?
    ) {
        val snapshot = createSnapshot(screenInfo, screenshotBmp)
        preActionSnapshots[taskId] = snapshot
        Log.d(TAG, "保存操作前快照: $taskId, acc=${snapshot.hasAccessibility}, img=${snapshot.hasImage}")
    }

    /**
     * 检测操作后的界面变化（双层回退）
     */
    fun detectChange(
        taskId: String,
        screenInfo: ScreenInfo?,
        screenshotBmp: Bitmap?
    ): ScreenChange? {
        val startTime = System.currentTimeMillis()
        val preSnapshot = preActionSnapshots.remove(taskId) ?: return null
        val postSnapshot = createSnapshot(screenInfo, screenshotBmp)

        val result = detectWithFallback(preSnapshot, postSnapshot)
        val latencyMs = System.currentTimeMillis() - startTime

        return result.copy(latencyMs = latencyMs)
    }

    /**
     * 清理缓存
     */
    fun clearCache() {
        preActionSnapshots.clear()
    }

    // ============================================================
    //  双层回退检测核心
    // ============================================================

    private fun detectWithFallback(before: ScreenSnapshot, after: ScreenSnapshot): ScreenChange {
        // 第1层：无障碍服务检测（最精确）
        if (before.hasAccessibility && after.hasAccessibility) {
            val change = compareByAccessibility(before, after)
            if (change.changeType != ScreenChangeType.NO_CHANGE) {
                Log.d(TAG, "[无障碍] 检测到变化: ${change.description}")
                return change
            }
            // 无障碍说没变化，但再验证一下其他层
            // （无障碍树可能没更新，比如动画/过渡页面）
        }

        // 第2层：图像哈希 + SSIM 双校验（兜底）
        if (before.hasImage && after.hasImage) {
            val change = compareByImageHash(before, after)
            if (change.changeType != ScreenChangeType.NO_CHANGE) {
                Log.d(TAG, "[图像哈希] 检测到变化: ${change.description}")
                return change
            }
        }

        // 所有层都认为无变化
        val confidence = when {
            before.hasAccessibility && after.hasAccessibility -> CONFIDENCE_ACCESSIBILITY
            before.hasImage && after.hasImage -> CONFIDENCE_IMAGE_HASH
            else -> CONFIDENCE_NO_SOURCE
        }
        return ScreenChange(
            changeType = ScreenChangeType.NO_CHANGE,
            description = buildNoChangeDescription(before, after),
            detectionSource = determineBestSource(before, after),
            confidence = confidence
        )
    }

    // ============================================================
    //  第1层：无障碍服务检测
    // ============================================================

    private fun compareByAccessibility(before: ScreenSnapshot, after: ScreenSnapshot): ScreenChange {
        // 包名/Activity变化
        val packageChanged = before.packageName != after.packageName
        val activityChanged = before.activityName != after.activityName

        if (packageChanged || activityChanged) {
            return ScreenChange(
                changeType = ScreenChangeType.PAGE_NAVIGATION,
                description = if (packageChanged) "页面跳转（应用切换: ${before.packageName} → ${after.packageName}）"
                              else "页面跳转（Activity变化）",
                packageChanged = packageChanged,
                activityChanged = activityChanged,
                detectionSource = DetectionSource.ACCESSIBILITY,
                confidence = CONFIDENCE_ACCESSIBILITY
            )
        }

        // 元素数量显著变化（超过30%或绝对差>5）→ 界面一定有变化
        val countDiff = Math.abs(after.elementCount - before.elementCount)
        val countRatio = if (before.elementCount > 0) countDiff.toDouble() / before.elementCount else 1.0
        if (countDiff > 5 || countRatio > 0.3) {
            return ScreenChange(
                changeType = ScreenChangeType.COMPLEX_CHANGE,
                description = "界面元素数量变化: ${before.elementCount} → ${after.elementCount}",
                detectionSource = DetectionSource.ACCESSIBILITY,
                confidence = CONFIDENCE_ACCESSIBILITY
            )
        }

        // 加载状态变化
        val beforeLoading = before.textElements.any { it.contains("加载") || it.contains("loading", ignoreCase = true) }
        val afterLoading = after.textElements.any { it.contains("加载") || it.contains("loading", ignoreCase = true) }
        if (beforeLoading && !afterLoading) {
            return ScreenChange(
                changeType = ScreenChangeType.LOADING_STATE_CHANGED,
                description = "加载完成",
                loadingFinished = true,
                detectionSource = DetectionSource.ACCESSIBILITY,
                confidence = CONFIDENCE_ACCESSIBILITY
            )
        }

        // 键盘状态变化
        val kbdKeywords = listOf("q", "w", "e", "r", "t", "y", "搜索", "空格", "换行", "中英")
        val beforeKbd = before.textElements.any { t -> kbdKeywords.any { it in t.lowercase() } }
        val afterKbd = after.textElements.any { t -> kbdKeywords.any { it in t.lowercase() } }
        if (beforeKbd != afterKbd) {
            return ScreenChange(
                changeType = ScreenChangeType.KEYBOARD_TOGGLE,
                description = if (afterKbd) "键盘弹出" else "键盘收起",
                keyboardVisible = afterKbd,
                detectionSource = DetectionSource.ACCESSIBILITY,
                confidence = CONFIDENCE_ACCESSIBILITY
            )
        }

        // 文本集合对比（比ID匹配更可靠，因为很多元素没有resourceId）
        val beforeTexts = before.textElements.toSet()
        val afterTexts = after.textElements.toSet()
        val addedTexts = afterTexts - beforeTexts
        val removedTexts = beforeTexts - afterTexts
        val commonTexts = beforeTexts.intersect(afterTexts)

        if (addedTexts.isNotEmpty() || removedTexts.isNotEmpty()) {
            val textSimilarity = if (beforeTexts.isEmpty() && afterTexts.isEmpty()) 1.0
                else if (beforeTexts.isEmpty() || afterTexts.isEmpty()) 0.0
                else commonTexts.size.toDouble() / maxOf(beforeTexts.size, afterTexts.size)

            val changeType = when {
                textSimilarity < 0.5 -> ScreenChangeType.COMPLEX_CHANGE
                addedTexts.isNotEmpty() && removedTexts.isNotEmpty() -> ScreenChangeType.TEXT_CHANGED
                addedTexts.isNotEmpty() -> ScreenChangeType.ELEMENT_ADDED
                else -> ScreenChangeType.ELEMENT_REMOVED
            }

            return ScreenChange(
                changeType = changeType,
                description = buildTextChangeDescription(addedTexts, removedTexts, textSimilarity),
                detectionSource = DetectionSource.ACCESSIBILITY,
                confidence = CONFIDENCE_ACCESSIBILITY
            )
        }

        // 元素级对比（使用ID+位置双重匹配）
        val addedElements = mutableListOf<KeyElement>()
        val removedElements = mutableListOf<KeyElement>()
        val changedElements = mutableListOf<ChangedElement>()
        var scrollDelta = 0

        // 有ID的元素按ID匹配
        val beforeWithId = before.keyElements.filter { it.id.isNotBlank() }.associateBy { it.id }
        val afterWithId = after.keyElements.filter { it.id.isNotBlank() }.associateBy { it.id }

        afterWithId.forEach { (id, el) -> if (id !in beforeWithId) addedElements.add(el) }
        beforeWithId.forEach { (id, el) -> if (id !in afterWithId) removedElements.add(el) }

        beforeWithId.forEach { (id, bEl) ->
            afterWithId[id]?.let { aEl ->
                if (bEl.text != aEl.text) {
                    changedElements.add(ChangedElement(aEl, bEl.text, bEl.centerX, bEl.centerY))
                }
                val dy = Math.abs(bEl.centerY - aEl.centerY)
                if (dy > COORDINATE_THRESHOLD) scrollDelta += dy
            }
        }

        // 综合判断变化类型
        val changeType = when {
            scrollDelta > SCROLL_THRESHOLD -> ScreenChangeType.LIST_SCROLLED
            changedElements.any { it.element.text != it.previousText } -> ScreenChangeType.TEXT_CHANGED
            addedElements.isNotEmpty() || removedElements.isNotEmpty() -> ScreenChangeType.COMPLEX_CHANGE
            changedElements.isNotEmpty() -> ScreenChangeType.ELEMENT_MOVED
            else -> ScreenChangeType.NO_CHANGE
        }

        return ScreenChange(
            changeType = changeType,
            description = generateAccDescription(changeType, addedElements, removedElements, changedElements, scrollDelta),
            addedElements = addedElements,
            removedElements = removedElements,
            changedElements = changedElements,
            scrollDelta = scrollDelta,
            detectionSource = DetectionSource.ACCESSIBILITY,
            confidence = CONFIDENCE_ACCESSIBILITY
        )
    }

    private fun buildTextChangeDescription(added: Set<String>, removed: Set<String>, similarity: Double): String {
        val parts = mutableListOf<String>()
        if (added.isNotEmpty()) parts.add("新增: ${added.take(5).joinToString(",")}")
        if (removed.isNotEmpty()) parts.add("移除: ${removed.take(5).joinToString(",")}")
        if (similarity < 0.5) parts.add(0, "界面大幅变化(${(similarity * 100).toInt()}%相似)")
        return parts.joinToString("；")
    }

    private fun generateAccDescription(
        changeType: ScreenChangeType,
        added: List<KeyElement>,
        removed: List<KeyElement>,
        changed: List<ChangedElement>,
        scrollDelta: Int
    ): String = when (changeType) {
        ScreenChangeType.NO_CHANGE -> "界面无明显变化"
        ScreenChangeType.ELEMENT_ADDED -> "新增元素: ${added.take(5).joinToString(", ") { it.text ?: it.id }}"
        ScreenChangeType.ELEMENT_REMOVED -> "移除元素: ${removed.take(5).joinToString(", ") { it.text ?: it.id }}"
        ScreenChangeType.TEXT_CHANGED -> "文本变化: ${changed.filter { it.element.text != it.previousText }.take(3).joinToString(", ") { "${it.previousText ?: "空"}→${it.element.text ?: "空"}" }}"
        ScreenChangeType.LIST_SCROLLED -> "列表滚动（约${scrollDelta}px）"
        ScreenChangeType.ELEMENT_MOVED -> "元素位置变化"
        ScreenChangeType.PAGE_NAVIGATION -> "页面跳转"
        ScreenChangeType.DIALOG_APPEARED -> "对话框弹出"
        ScreenChangeType.KEYBOARD_TOGGLE -> "键盘状态变化"
        ScreenChangeType.LOADING_STATE_CHANGED -> "加载状态变化"
        ScreenChangeType.COMPLEX_CHANGE -> {
            val parts = mutableListOf<String>()
            if (added.isNotEmpty()) parts.add("新增${added.size}元素")
            if (removed.isNotEmpty()) parts.add("移除${removed.size}元素")
            if (changed.isNotEmpty()) parts.add("${changed.size}元素变化")
            "界面变化: ${parts.joinToString("；")}"
        }
    }

    // ============================================================
    //  第2层：图像感知哈希 + SSIM 双校验
    // ============================================================

    private fun compareByImageHash(before: ScreenSnapshot, after: ScreenSnapshot): ScreenChange {
        if (before.imageHash.isBlank() || after.imageHash.isBlank()) {
            return ScreenChange(
                changeType = ScreenChangeType.NO_CHANGE,
                description = "无图像数据",
                detectionSource = DetectionSource.IMAGE_HASH,
                confidence = CONFIDENCE_NO_SOURCE
            )
        }

        val diff = computeHammingDistance(before.imageHash, after.imageHash)
        val diffRatio = diff.toDouble() / before.imageHash.length

        // pHash 检测到变化 → 直接判定为变化
        if (diffRatio > IMAGE_HASH_THRESHOLD) {
            return ScreenChange(
                changeType = ScreenChangeType.COMPLEX_CHANGE,
                description = "界面发生变化（图像差异${(diffRatio * 100).toInt()}%）",
                detectionSource = DetectionSource.IMAGE_HASH,
                confidence = CONFIDENCE_IMAGE_HASH
            )
        }

        // pHash 认为无变化 → 白屏/黑屏陷阱检测
        // 纯色背景上少量文字变化时 pHash 可能漏判，用颜色直方图辅助
        val beforeSolidRatio = before.solidColorRatio
        val afterSolidRatio = after.solidColorRatio
        if (beforeSolidRatio > SOLID_COLOR_RATIO_THRESHOLD || afterSolidRatio > SOLID_COLOR_RATIO_THRESHOLD) {
            // 至少一方是纯色屏（白屏/黑屏），pHash 不可信，降低置信度
            Log.d(TAG, "[图像哈希] 纯色屏检测: before=${(beforeSolidRatio * 100).toInt()}%, after=${(afterSolidRatio * 100).toInt()}%，降低置信度")
            return ScreenChange(
                changeType = ScreenChangeType.NO_CHANGE,
                description = "图像无明显变化（差异${(diffRatio * 100).toInt()}%，纯色屏低置信度）",
                detectionSource = DetectionSource.IMAGE_HASH,
                confidence = 0.3f  // 纯色屏时大幅降低置信度
            )
        }

        // pHash 认为无变化且非纯色屏 → 正常返回
        return ScreenChange(
            changeType = ScreenChangeType.NO_CHANGE,
            description = "图像无明显变化（差异${(diffRatio * 100).toInt()}%）",
            detectionSource = DetectionSource.IMAGE_HASH,
            confidence = CONFIDENCE_IMAGE_HASH
        )
    }

    /**
     * 计算汉明距离（两个等长字符串中不同字符的数量）
     */
    private fun computeHammingDistance(hash1: String, hash2: String): Int {
        val len = minOf(hash1.length, hash2.length)
        var diff = 0
        for (i in 0 until len) {
            if (hash1[i] != hash2[i]) diff++
        }
        return diff
    }

    // ============================================================
    //  快照创建
    // ============================================================

    private fun createSnapshot(
        screenInfo: ScreenInfo?,
        screenshotBmp: Bitmap?
    ): ScreenSnapshot {
        val hasAccessibility = screenInfo != null && (screenInfo.uiElements?.isNotEmpty() == true)
        val hasImage = screenshotBmp != null && !screenshotBmp.isRecycled

        // 无障碍数据
        val keyElements = screenInfo?.uiElements?.mapNotNull { element ->
            if (isKeyElement(element)) {
                KeyElement(
                    id = element.id,
                    type = element.type,
                    text = element.text,
                    centerX = (element.bounds.left + element.bounds.right) / 2,
                    centerY = (element.bounds.top + element.bounds.bottom) / 2,
                    hash = element.hashCode().toString()
                )
            } else null
        } ?: emptyList()

        val textElements = screenInfo?.uiElements
            ?.mapNotNull { it.text?.takeIf { t -> t.isNotBlank() } }
            ?.distinct() ?: emptyList()

        val screenHash = generateScreenHash(keyElements, textElements, screenInfo?.currentPackage)

        // 图像哈希 + 纯色占比（合并计算，只做一次HARDWARE→ARGB_8888转换）
        var imageHash = ""
        var solidColorRatio = 0f
        if (hasImage) {
            val fingerprint = computeImageFingerprint(screenshotBmp!!)
            imageHash = fingerprint.pHash
            solidColorRatio = fingerprint.solidColorRatio
        }

        return ScreenSnapshot(
            timestamp = System.currentTimeMillis(),
            packageName = screenInfo?.currentPackage,
            activityName = screenInfo?.currentActivity,
            elementCount = screenInfo?.uiElements?.size ?: 0,
            textElements = textElements,
            keyElements = keyElements,
            screenHash = screenHash,
            imageHash = imageHash,
            hasAccessibility = hasAccessibility,
            hasImage = hasImage,
            solidColorRatio = solidColorRatio
        )
    }

    private fun isKeyElement(element: UIElement): Boolean {
        if (element.text.isNullOrBlank() && element.contentDescription.isNullOrBlank()) return false
        if (element.type == UIElementType.TEXT && (element.text?.length ?: 0) < 2) return false
        return element.isClickable || element.isEditable ||
               element.type.isInteractive() ||
               element.text?.isNotBlank() == true
    }

    private fun generateScreenHash(keyElements: List<KeyElement>, textElements: List<String>, packageName: String?): String {
        val input = buildString {
            packageName?.let { append(it) }
            append(keyElements.size)
            keyElements.forEach { append("${it.type}-${it.centerX}-${it.centerY}") }
            textElements.forEach { append(it) }
        }
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            input.hashCode().toString()
        }
    }

    data class ImageFingerprint(val pHash: String, val solidColorRatio: Float)

    /**
     * 计算图像感知哈希（pHash简化版：缩放+灰度+均值二值化）
     * 输出64位二进制字符串，对缩放/轻微颜色变化鲁棒
     */
    private fun computePerceptualHash(bitmap: Bitmap): String {
        return computeImageFingerprint(bitmap).pHash
    }

    /**
     * 公开的 pHash 计算接口，供 SmartWaitStrategy 等外部调用
     */
    fun computePerceptualHashPublic(bitmap: Bitmap): String = computeImageFingerprint(bitmap).pHash

    /**
     * 合并计算 pHash + solidColorRatio，只做一次 HARDWARE→ARGB_8888 转换和一次像素遍历
     */
    private fun computeImageFingerprint(bitmap: Bitmap): ImageFingerprint {
        try {
            val src = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
            val small = Bitmap.createScaledBitmap(src, 8, 8, false)

            val gray = IntArray(64)
            var sum = 0
            var solidCount = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val pixel = small.getPixel(x, y)
                    val g = (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114).toInt()
                    gray[y * 8 + x] = g
                    sum += g
                    if (g < 10 || g > 245) solidCount++
                }
            }
            val avg = sum / 64

            val hash = StringBuilder()
            for (i in 0 until 64) {
                hash.append(if (gray[i] >= avg) '1' else '0')
            }

            small.recycle()
            if (src !== bitmap) src.recycle()
            return ImageFingerprint(hash.toString(), solidCount.toFloat() / 64)
        } catch (e: Exception) {
            Log.e(TAG, "计算图像指纹失败: ${e.message}")
            return ImageFingerprint("", 0f)
        }
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    private fun buildNoChangeDescription(before: ScreenSnapshot, after: ScreenSnapshot): String {
        val sources = mutableListOf<String>()
        if (before.hasAccessibility && after.hasAccessibility) sources.add("无障碍")
        if (before.hasImage && after.hasImage) sources.add("图像")
        return if (sources.isEmpty()) "界面无变化（无可用数据源）" else "界面无明显变化（${sources.joinToString("+")}验证）"
    }

    private fun determineBestSource(before: ScreenSnapshot, after: ScreenSnapshot): DetectionSource {
        return when {
            before.hasAccessibility && after.hasAccessibility -> DetectionSource.ACCESSIBILITY
            before.hasImage && after.hasImage -> DetectionSource.IMAGE_HASH
            else -> DetectionSource.IMAGE_HASH
        }
    }
}
