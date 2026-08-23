package com.palmagent.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.model.*
import com.palmagent.app.service.GuiOwlService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

class GUIAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "GUIAccessibilityService"
        private var instanceRef: WeakReference<GUIAccessibilityService>? = null

        val instance: GUIAccessibilityService?
            get() = instanceRef?.get()

        val isRunning: Boolean get() = instance != null

        @Suppress("DEPRECATION")
        fun recycleNodes(nodes: List<AccessibilityNodeInfo>) {
            for (node in nodes) {
                try { node.recycle() } catch (_: Exception) {}
            }
        }

        // Android AccessibilityService.takeScreenshot 错误码（官方常量值）
        private const val ERR_INTERNAL_ERROR = 1
        private const val ERR_INVALID_DISPLAY = 2
        private const val ERR_NO_ACCESSIBILITY_ACCESS = 3
        private const val ERR_INTERVAL_TIME_SHORT = 4

        private const val SCREENSHOT_MAX_RETRIES = 3
        private const val SCREENSHOT_TIMEOUT_MS = 3000L
    }

    @Volatile
    private var lastEventTime = 0L
    private var eventCount = 0

    @Volatile
    private var userTouchDetected = false
    private var lastAgentActionTime = 0L
    @Volatile
    private var agentActing = false

    fun markAgentAction() {
        lastAgentActionTime = System.currentTimeMillis()
        userTouchDetected = false
    }

    fun setAgentActing(acting: Boolean) {
        agentActing = acting
        if (acting) {
            lastAgentActionTime = System.currentTimeMillis()
            userTouchDetected = false
        }
    }

    fun consumeUserTouch(): Boolean {
        val detected = userTouchDetected
        userTouchDetected = false
        return detected
    }

    fun isUserTouchDetected(): Boolean = userTouchDetected

    /**
     * 返回最近一次无障碍事件的时间戳（毫秒）。
     * SmartWaitStrategy 据此判断"事件流是否已静默"——事件流静默意味着页面渲染/动画
     * 已收敛（不再产生 TYPE_WINDOW_CONTENT_CHANGED 等事件），比"元素数量不变"
     * 更可靠的界面稳定信号。0 表示尚未收到任何事件。
     */
    fun getLastAccessibilityEventTime(): Long = lastEventTime

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        eventCount = 0
        Log.d(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // TalkBack 兼容：当 AI 正在操作时，跳过非关键事件处理，减少与 TalkBack 的资源竞争
        // 但保留 TYPE_WINDOW_STATE_CHANGED 等关键事件，确保 AI 能感知页面跳转
        if (agentActing) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // 窗口变化是 AI 执行的关键信号，即使 agentActing 也要记录
                    lastEventTime = System.currentTimeMillis()
                }
                // 其他事件在 AI 操作时全部跳过，减少冲突
                else -> return
            }
        }

        eventCount++
        lastEventTime = System.currentTimeMillis()

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (!agentActing && System.currentTimeMillis() - lastAgentActionTime > 5000) {
                    userTouchDetected = true
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (eventCount % 10 == 0) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "已处理${eventCount}个事件，最近事件: ${event.packageName}/${event.className}")
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断，尝试恢复")
        AccessibilityServiceHelper.ensureServiceEnabled(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        instanceRef?.clear()
        Log.w(TAG, "无障碍服务已销毁，尝试恢复")
        AccessibilityServiceHelper.ensureServiceEnabled(this)
    }

    fun isServiceHealthy(): Boolean {
        if (!isRunning) return false

        val root = try { rootInActiveWindow } catch (_: Exception) { null }

        if (root == null) {
            Log.w(TAG, "isServiceHealthy: rootInActiveWindow为空")
            return false
        }

        try {
            // 检查包名
            val pkg = root.packageName?.toString()
            if (pkg.isNullOrBlank()) {
                Log.w(TAG, "isServiceHealthy: packageName为空")
                root.recycle()
                return false
            }

            // 检查子节点数量（特殊情况：某些界面只有一个根节点但有内容）
            val childCount = root.childCount
            if (childCount == 0) {
                val hasContent = !root.text.isNullOrBlank() || !root.contentDescription.isNullOrBlank()
                if (!hasContent) {
                    Log.w(TAG, "isServiceHealthy: 根节点无子节点且无内容")
                    root.recycle()
                    return false
                }
            }

            root.recycle()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "isServiceHealthy: 检查异常: ${e.message}")
            try { root.recycle() } catch (_: Exception) {}
            return false
        }
    }

    @Suppress("DEPRECATION")
    fun getCurrentScreenInfo(): ScreenInfo {
        val uiElements = mutableListOf<UIElement>()
        var currentPackage: String? = null
        var currentActivity: String? = null

        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "获取根节点失败: ${e.message}")
            null
        }

        if (rootNode != null) {
            try {
                currentPackage = rootNode.packageName?.toString()
                val rootBounds = Rect()
                rootNode.getBoundsInScreen(rootBounds)
                val rootChildCount = rootNode.childCount
                val rootClassName = rootNode.className?.toString()
                val rootText = rootNode.text?.toString()?.take(50)
                traverseNode(rootNode, uiElements, 0)
                rootNode.recycle()
                if (uiElements.isEmpty()) {
                    Log.w(TAG, "屏幕分析元素为空: 包名=$currentPackage, 根节点子数=$rootChildCount, " +
                            "根bounds=${rootBounds.toShortString()}, " +
                            "根text=$rootText, 根className=$rootClassName")
                } else {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(TAG, "屏幕分析完成: 包名=$currentPackage, 元素数=${uiElements.size}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "遍历节点树失败: ${e.message}")
                try { rootNode.recycle() } catch (_: Exception) {}
            }
        } else {
            Log.w(TAG, "rootInActiveWindow为空，可能无障碍服务权限不足或目标窗口不可访问")
        }

        return ScreenInfo(
            uiElements = uiElements,
            currentPackage = currentPackage,
            currentActivity = currentActivity
        )
    }

    @Suppress("DEPRECATION")
    private fun traverseNode(
        node: AccessibilityNodeInfo,
        elements: MutableList<UIElement>,
        depth: Int,
        parentHashCode: Int = 0
    ) {
        if (depth > 15) return

        try {
            val element = createUIElement(node, parentHashCode)
            if (element != null) {
                elements.add(element)
            }

            val nodeHashCode = node.hashCode()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                child?.let {
                    traverseNode(it, elements, depth + 1, nodeHashCode)
                    it.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "遍历节点失败: ${e.message}")
        }
    }

    private fun createUIElement(node: AccessibilityNodeInfo, parentHashCode: Int): UIElement? {
        return try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.width() <= 0 || bounds.height() <= 0 || bounds.left < 0 || bounds.top < 0) {
                return null
            }

            val text = node.text?.toString()
            val type = detectElementType(node)

            // 提取 CollectionItemInfo 分组信息
            val groupInfo = extractGroupInfo(node, parentHashCode)

            UIElement(
                id = node.viewIdResourceName ?: "node_${node.hashCode()}",
                type = type,
                text = text,
                bounds = Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                contentDescription = node.contentDescription?.toString(),
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                groupInfo = groupInfo,
                isSelected = node.isSelected
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 从 AccessibilityNodeInfo 提取 CollectionItemInfo 分组信息
     * "7之1" 的来源：CollectionItemInfo(rowIndex, columnIndex) + 父容器 CollectionInfo(rowCount, columnCount)
     */
    private fun extractGroupInfo(node: AccessibilityNodeInfo, parentHashCode: Int): GroupInfo? {
        return try {
            val itemInfo = node.collectionItemInfo ?: return null
            val parentCollection = node.parent?.collectionInfo
            val rowCount = parentCollection?.rowCount ?: -1
            val colCount = parentCollection?.columnCount?.let { if (it <= 0) 1 else it } ?: 1
            val totalItems = if (rowCount > 0) rowCount * colCount else -1
            val itemIndex = itemInfo.rowIndex * colCount + itemInfo.columnIndex + 1 // 从1开始
            val selectionMode = parentCollection?.selectionMode ?: GroupInfo.SELECTION_NONE

            GroupInfo(
                groupId = "$parentHashCode",
                totalItems = totalItems,
                itemIndex = itemIndex,
                selectionMode = selectionMode
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun detectElementType(node: AccessibilityNodeInfo): UIElementType {
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val hasContent = hasText || hasDesc

        return when {
            // 可交互类型（优先级从高到低）
            node.isEditable -> UIElementType.INPUT
            node.isCheckable && node.isClickable -> UIElementType.SWITCH
            node.isClickable && isTabNode(node) -> UIElementType.TAB
            node.isClickable && !hasContent -> UIElementType.ICON
            node.isClickable && hasText && !hasDesc -> UIElementType.LINK
            node.isClickable -> UIElementType.BUTTON
            // 非交互类型
            node.isScrollable -> UIElementType.LIST
            hasContent -> UIElementType.TEXT
            else -> UIElementType.UNKNOWN
        }
    }

    /**
     * 判断节点是否为Tab标签
     * 条件：className含"Tab" 或 contentDescription含"标签"
     */
    private fun isTabNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        return className.contains("tab") || desc.contains("标签")
    }

    suspend fun performAccessibilityClick(x: Int, y: Int): Boolean {
        return performAccessibilityGesture(x, y, x, y, 50)
    }

    suspend fun performAccessibilityLongPress(x: Int, y: Int, durationMs: Long = 600): Boolean {
        return performAccessibilityGesture(x, y, x, y, durationMs)
    }

    suspend fun performAccessibilitySwipe(
        fromX: Int, fromY: Int, toX: Int, toY: Int, duration: Long = 300
    ): Boolean {
        return performAccessibilityGesture(fromX, fromY, toX, toY, duration)
    }

    private suspend fun performAccessibilityGesture(
        fromX: Int, fromY: Int, toX: Int, toY: Int, duration: Long
    ): Boolean {
        // 坐标 clamp 到非负，防止 Path bounds must not be negative
        val safeFromX = fromX.coerceAtLeast(0)
        val safeFromY = fromY.coerceAtLeast(0)
        val safeToX = toX.coerceAtLeast(0)
        val safeToY = toY.coerceAtLeast(0)

        return suspendCancellableCoroutine { continuation ->
            val path = Path()
            path.moveTo(safeFromX.toFloat(), safeFromY.toFloat())
            
            // 多点贝塞尔曲线：模拟真实手指的加速→匀速→减速过程
            // 两点直线无法触发某些 App 的惯性滚动（fling）
            if (safeFromX != safeToX || safeFromY != safeToY) {
                val dx = safeToX - safeFromX
                val dy = safeToY - safeFromY
                
                // 点1: 加速段（20%距离）
                path.lineTo(safeFromX + dx * 0.2f, safeFromY + dy * 0.1f)
                // 点2: 匀速段（50%距离）
                path.lineTo(safeFromX + dx * 0.5f, safeFromY + dy * 0.5f)
                // 点3: 减速段（80%距离）
                path.lineTo(safeFromX + dx * 0.8f, safeFromY + dy * 0.9f)
                // 点4: 终点
                path.lineTo(safeToX.toFloat(), safeToY.toFloat())
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, duration)

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (!continuation.isCancelled) {
                        continuation.resume(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (!continuation.isCancelled) {
                        continuation.resume(false)
                    }
                }
            }

            val dispatched = dispatchGesture(
                GestureDescription.Builder().addStroke(stroke).build(),
                callback,
                null
            )

            if (!dispatched) {
                if (!continuation.isCancelled) {
                    continuation.resume(false)
                }
            }

            continuation.invokeOnCancellation {}
        }
    }

    suspend fun performAccessibilityBack(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val result = performGlobalAction(GLOBAL_ACTION_BACK)
            continuation.resume(result)
        }
    }

    suspend fun performAccessibilityHome(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val result = performGlobalAction(GLOBAL_ACTION_HOME)
            continuation.resume(result)
        }
    }

    // 用于在 callback 中暂存最近一次错误码名称（供重试循环读取）
    @Volatile
    private var lastErrorNameRef: String = "UNKNOWN"

    suspend fun takeScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Log.w(TAG, "截屏需要 Android 14+")
            return null
        }
        lastErrorNameRef = "UNKNOWN"
        repeat(SCREENSHOT_MAX_RETRIES) { attempt ->
            val result = tryTakeScreenshotOnce(SCREENSHOT_TIMEOUT_MS)
            if (result != null) {
                if (attempt > 0) {
                    Log.w(TAG, "截屏第${attempt + 1}次尝试成功（前 $attempt 次失败: $lastErrorNameRef）")
                    LiveLogBuffer.append("📸 截屏重试第${attempt + 1}次成功")
                }
                return result
            }
            // 失败：按指数退避等待后重试
            val backoffMs = 100L * (1 shl attempt)  // 100, 200, 400ms
            if (attempt < SCREENSHOT_MAX_RETRIES - 1) {
                Log.w(TAG, "截屏第${attempt + 1}次失败($lastErrorNameRef)，${backoffMs}ms 后重试")
                delay(backoffMs)
            }
        }
        Log.e(TAG, "截屏重试 $SCREENSHOT_MAX_RETRIES 次仍失败（最后错误: $lastErrorNameRef）")
        LiveLogBuffer.append("⚠️ 截屏连续 ${SCREENSHOT_MAX_RETRIES} 次失败（$lastErrorNameRef）")
        return null
    }

    /**
     * 单次截屏尝试，含超时保护
     * @param timeoutMs 单次调用超时时间
     * @return Bitmap 或 null（失败/超时）
     */
    private suspend fun tryTakeScreenshotOnce(timeoutMs: Long): Bitmap? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine<Bitmap?> { continuation ->
            val handler = android.os.Handler(mainLooper)
            val executor = java.util.concurrent.Executor { handler.post(it) }
            val callback = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(
                    screenshotResult.hardwareBuffer,
                    screenshotResult.colorSpace
                )
                screenshotResult.hardwareBuffer.close()
                // P1-6 修复：超时取消后 bitmap 既不 resume 也不 recycle 会泄漏，补充 else 分支
                if (continuation.isActive) {
                    continuation.resume(bitmap)
                } else {
                    bitmap?.recycle()
                }
            }
                override fun onFailure(errorCode: Int) {
                    lastErrorNameRef = when (errorCode) {
                        ERR_INTERNAL_ERROR -> "INTERNAL_ERROR"
                        ERR_INVALID_DISPLAY -> "INVALID_DISPLAY"
                        ERR_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
                        ERR_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
                        else -> "UNKNOWN($errorCode)"
                    }
                    Log.w(TAG, "截屏单次失败: $lastErrorNameRef")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            try {
                this@GUIAccessibilityService.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY, executor, callback
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "截屏权限缺失: ${e.message}")
                lastErrorNameRef = "SECURITY_EXCEPTION"
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    @Suppress("DEPRECATION")
    fun findEditableNode(targetId: String?): AccessibilityNodeInfo? {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) {
                root.recycle()
                return focused
            }
            focused?.recycle()

            if (!targetId.isNullOrBlank()) {
                val byId = root.findAccessibilityNodeInfosByViewId(targetId)
                for (node in byId) {
                    if (node.isEditable) {
                        root.recycle()
                        return node
                    }
                    node.recycle()
                }
            }

            val result = findEditableNodeRecursive(root)
            root.recycle()
            return result
        } catch (e: Exception) {
            try { root.recycle() } catch (_: Exception) {}
            Log.e(TAG, "查找输入节点失败: ${e.message}")
            return null
        }
    }

    @Suppress("DEPRECATION")
    private fun findEditableNodeRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isEditable) return child
            val found = findEditableNodeRecursive(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    fun performSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG, "设置文本失败: ${e.message}")
            false
        }
    }

    // P2-4 修复：改为 suspend，用 delay 替代 Thread.sleep 避免阻塞线程
    suspend fun performClearAndSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        try {
            if (!node.isEditable) return performSetText(node, text)

            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            delay(100)

            val selectAllArgs = android.os.Bundle()
            selectAllArgs.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0
            )
            selectAllArgs.putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                node.text?.length ?: 0
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
            delay(50)

            return performSetText(node, text)
        } catch (e: Exception) {
            Log.e(TAG, "清空并设置文本失败: ${e.message}")
            return false
        }
    }

    // ======================== Node Operations (from ApkClaw) ========================

    @Suppress("DEPRECATION")
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return emptyList()
        // P1-7 修复：recycle root 防止泄漏（对比 findNodesByDesc 已正确 recycle）
        val result = root.findAccessibilityNodeInfosByText(text) ?: emptyList()
        root.recycle()
        return result
    }

    @Suppress("DEPRECATION")
    fun findNodesByDesc(desc: String): List<AccessibilityNodeInfo> {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByDescRecursive(root, desc, results)
        root.recycle()
        return results
    }

    private fun findNodesByDescRecursive(node: AccessibilityNodeInfo, desc: String, results: MutableList<AccessibilityNodeInfo>) {
        if (node.contentDescription?.contains(desc, ignoreCase = true) == true) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByDescRecursive(child, desc, results)
            child.recycle()
        }
    }

    /** 按 hintText（占位文本）查找节点：搜索框占位符通常在 hint 而非 text（方案3） */
    fun findNodesByHint(hint: String): List<AccessibilityNodeInfo> {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByHintRecursive(root, hint, results)
        root.recycle()
        return results
    }

    private fun findNodesByHintRecursive(node: AccessibilityNodeInfo, hint: String, results: MutableList<AccessibilityNodeInfo>) {
        if (node.hintText?.contains(hint, ignoreCase = true) == true) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByHintRecursive(child, hint, results)
            child.recycle()
        }
    }

    @Suppress("DEPRECATION")
    fun findNodesById(viewId: String): List<AccessibilityNodeInfo> {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return emptyList()
        // P1-7 修复：recycle root 防止泄漏
        val result = root.findAccessibilityNodeInfosByViewId(viewId) ?: emptyList()
        root.recycle()
        return result
    }

    @Suppress("DEPRECATION")
    suspend fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // P2-1 修复：遍历父节点链时 recycle 中间节点，防止泄漏
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return result
            }
            val next = parent.parent
            parent.recycle()
            parent = next
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return dispatchGestureSync(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), 100)
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    // ======================== Scroll Operations ========================

    /**
     * 获取当前无障碍树签名（元素数量 + 可见元素边界），用于检测页面是否变化
     */
    @Suppress("DEPRECATION")
    fun getTreeSignature(): Pair<Int, String> {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return 0 to ""
        val boundsList = mutableListOf<String>()
        val count = countVisibleElements(root, boundsList)
        root.recycle()
        return count to boundsList.joinToString(",")
    }

    @Suppress("DEPRECATION")
    private fun countVisibleElements(node: AccessibilityNodeInfo, boundsList: MutableList<String>): Int {
        if (!node.isVisibleToUser) return 0
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            boundsList.add("${bounds.left},${bounds.top}")
        }
        var count = 1
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            count += countVisibleElements(child, boundsList)
            child.recycle()
        }
        return count
    }

    // ======================== Screen Tree (from ApkClaw) ========================

    @Suppress("DEPRECATION")
    fun getScreenTree(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return ""
        val sb = StringBuilder()
        buildNodeTree(root, sb, 0)
        root.recycle()
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    fun getScreenTreeFull(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return ""
        val sb = StringBuilder()
        buildNodeTreeFull(root, sb, 0)
        root.recycle()
        return sb.toString()
    }

    @Suppress("DEPRECATION")
    private fun buildNodeTree(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (!node.isVisibleToUser) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                buildNodeTree(child, sb, depth)
                child.recycle()
            }
            return
        }

        val hasText = node.text?.isNotEmpty() == true
        val hasDesc = node.contentDescription?.isNotEmpty() == true
        val isInteractive = node.isClickable || node.isScrollable || node.isEditable ||
                node.isCheckable || node.isLongClickable
        val isSlider = isSliderNode(node)
        val className = node.className?.toString()
        val isProgress = className?.contains("ProgressBar") == true
        val isMeaningful = hasText || hasDesc || isInteractive || isSlider || isProgress

        if (isMeaningful) {
            val indent = "  ".repeat(depth)
            sb.append(indent)

            if (className != null) {
                val shortName = className.substringAfterLast('.')
                sb.append("[$shortName]")
            }

            if (hasText) {
                val text = node.text.toString()
                if (text.length > 100) {
                    sb.append(" text=\"${text.substring(0, 100)}...\"")
                } else {
                    sb.append(" text=\"$text\"")
                }
            }
            if (hasDesc) {
                sb.append(" desc=\"${node.contentDescription}\"")
            }
            if (node.isClickable) sb.append(" [clickable]")
            if (node.isLongClickable) sb.append(" [long-clickable]")
            if (node.isScrollable) sb.append(" [scrollable]")
            if (node.isEditable) sb.append(" [editable]")
            if (node.isCheckable) sb.append(if (node.isChecked) " [checked]" else " [unchecked]")
            if (!node.isEnabled) sb.append(" [disabled]")
            if (node.isFocused) sb.append(" [focused]")
            if (isProgress) sb.append(" [loading]")

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            sb.append(" bounds=${bounds.toShortString()}")
            sb.append("\n")
        }

        val childDepth = if (isMeaningful) depth + 1 else depth
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            buildNodeTree(child, sb, childDepth)
            child.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNodeTreeFull(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        sb.append(indent)

        val className = node.className?.toString()
        if (className != null) {
            sb.append("[${className.substringAfterLast('.')}]")
        }

        if (node.text?.isNotEmpty() == true) {
            val text = node.text.toString()
            if (text.length > 200) {
                sb.append(" text=\"${text.substring(0, 200)}...\"")
            } else {
                sb.append(" text=\"$text\"")
            }
        }

        if (node.contentDescription?.isNotEmpty() == true) {
            sb.append(" desc=\"${node.contentDescription}\"")
        }

        node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let {
            sb.append(" id=\"$it\"")
        }

        if (node.isClickable) sb.append(" [clickable]")
        if (node.isLongClickable) sb.append(" [long-clickable]")
        if (node.isScrollable) sb.append(" [scrollable]")
        if (node.isEditable) sb.append(" [editable]")
        if (node.isCheckable) sb.append(if (node.isChecked) " [checked]" else " [unchecked]")
        if (!node.isEnabled) sb.append(" [disabled]")
        if (node.isFocused) sb.append(" [focused]")
        if (node.isSelected) sb.append(" [selected]")
        if (!node.isVisibleToUser) sb.append(" [invisible]")

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        sb.append(" bounds=${bounds.toShortString()}")
        sb.append("\n")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            buildNodeTreeFull(child, sb, depth + 1)
            child.recycle()
        }
    }

    private fun isSliderNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: return false
        return className.contains("SeekBar") ||
                className.contains("Slider") ||
                className.contains("RatingBar") ||
                node.rangeInfo != null
    }

    @Suppress("DEPRECATION")
    fun getNodeDetail(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        sb.append("class=${node.className}")
        if (node.text?.isNotEmpty() == true) sb.append(", text=\"${node.text}\"")
        if (node.contentDescription?.isNotEmpty() == true) sb.append(", desc=\"${node.contentDescription}\"")
        sb.append(", clickable=${node.isClickable}")
        sb.append(", enabled=${node.isEnabled}")
        sb.append(", visible=${node.isVisibleToUser}")
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        sb.append(", bounds=${bounds.toShortString()}")
        return sb.toString()
    }

    // ======================== Global Actions Extended ========================

    fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动应用失败: $packageName", e)
            false
        }
    }

    fun sendKeyEvent(keyCode: Int): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("input", "keyevent", keyCode.toString()))
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "发送按键事件失败: $keyCode", e)
            false
        }
    }

    suspend fun unlockScreen(): Boolean {
        try {
            val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager ?: return false
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or
                            android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "PalmAgent:unlock"
                )
                wl.acquire(3000)
                wl.release()
                delay(500)
            }

            val dm = resources.displayMetrics
            val centerX = dm.widthPixels / 2
            val bottomY = (dm.heightPixels * 0.8).toInt()
            val topY = (dm.heightPixels * 0.2).toInt()
            return dispatchGestureSync(centerX, bottomY, centerX, topY, 300)
        } catch (e: Exception) {
            Log.e(TAG, "解锁屏幕失败", e)
            return false
        }
    }

    // ======================== 剪贴板输入（无障碍节点不可用时的回退方案） ========================

    suspend fun inputViaClipboard(text: String, x: Int, y: Int): Boolean {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("agent_input", text))
            Log.d(TAG, "已复制文本到剪贴板: ${text.take(30)}...")
        } catch (e: Exception) {
            Log.e(TAG, "剪贴板复制失败: ${e.message}")
            return false
        }

        delay(200)

        val longPressOk = dispatchGestureSync(x, y, x, y, 400)
        if (!longPressOk) {
            Log.w(TAG, "长按手势派发失败")
        }

        delay(400)

        // 三层 fallback：Grounding → OCR → 点击长按位置上方
        val pasteCoord = findPasteButtonByGrounding() ?: findPasteButtonByOcr()
        val pasteOk = if (pasteCoord != null) {
            Log.d(TAG, "定位粘贴按钮: (${pasteCoord.first}, ${pasteCoord.second})")
            dispatchGestureSync(pasteCoord.first, pasteCoord.second, pasteCoord.first, pasteCoord.second, 50)
        } else {
            // 回退：点击长按位置上方100px（粘贴菜单通常在上方弹出）
            val fallbackY = (y - 100).coerceAtLeast(0)
            Log.d(TAG, "OCR未找到粘贴按钮，回退点击: ($x, $fallbackY)")
            dispatchGestureSync(x, fallbackY, x, fallbackY, 50)
        }

        delay(300)

        Log.d(TAG, "剪贴板粘贴: longPress=$longPressOk, tapPaste=$pasteOk")
        return longPressOk && pasteOk
    }

    /**
     * 通过 Grounding 定位"粘贴"按钮坐标
     * 长按后弹出的粘贴菜单在输入框附近
     */
    private suspend fun findPasteButtonByGrounding(): Pair<Int, Int>? {
        if (!GuiOwlService.isReady) return null
        val screenshot = takeScreenshot() ?: return null
        try {
            val dm = resources.displayMetrics
            val groundResult = GuiOwlService.ground(
                "粘贴按钮，位于长按输入框后弹出的上下文菜单中",
                screenshot, dm.widthPixels, dm.heightPixels
            )
            if (!groundResult.success || groundResult.coordinate == null) return null
            return Pair(groundResult.coordinate.x, groundResult.coordinate.y)
        } finally {
            if (!screenshot.isRecycled) { try { screenshot.recycle() } catch (_: Exception) {} }
        }
    }

    /**
     * 通过OCR定位"粘贴"按钮坐标
     * 长按后弹出的粘贴菜单在输入框附近，OCR扫描整个屏幕匹配关键词
     */
    private suspend fun findPasteButtonByOcr(): Pair<Int, Int>? {
        if (!RapidOcrService.isReady) return null
        val screenshot = takeScreenshot() ?: return null
        try {
            val ocrResults = RapidOcrService.extractTextWithBboxes(screenshot)
            val pasteKeywords = listOf("粘贴", "贴上", "Paste", "PASTE", "paste")
            for (keyword in pasteKeywords) {
                // 精确匹配（忽略大小写）
                val exact = ocrResults.find { it.text.equals(keyword, ignoreCase = true) }
                if (exact != null) return Pair(exact.centerX, exact.centerY)
            }
            // 包含匹配（选最短文本）
            val contains = ocrResults
                .filter { result -> pasteKeywords.any { kw -> result.text.equals(kw, ignoreCase = true) || result.text.contains(kw, ignoreCase = true) } }
                .minByOrNull { it.text.length }
            if (contains != null) return Pair(contains.centerX, contains.centerY)
            return null
        } finally {
            if (!screenshot.isRecycled) { try { screenshot.recycle() } catch (_: Exception) {} }
        }
    }

    // ======================== Synchronous Gesture Dispatch ========================

    private suspend fun dispatchGestureSync(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long): Boolean {
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        if (startX != endX || startY != endY) {
            path.lineTo(endX.toFloat(), endY.toFloat())
        }

        return suspendCancellableCoroutine { continuation ->
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }, null)

            // P2-3 修复：协程取消后 resume 会抛 IllegalStateException，加 isActive 守护
            if (!dispatched && continuation.isActive) {
                continuation.resume(false)
            }
        }
    }
}
