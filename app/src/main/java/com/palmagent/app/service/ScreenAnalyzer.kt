package com.palmagent.app.service

import android.app.Activity
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import com.palmagent.app.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScreenAnalyzer(private val context: Context) {
    companion object {
        private const val TAG = "ScreenAnalyzer"
    }

    suspend fun analyzeScreen(): ScreenInfo = withContext(Dispatchers.Main) {
        val uiElements = mutableListOf<UIElement>()
        var currentPackage: String? = null
        var currentActivity: String? = null

        try {
            val foregroundInfo = getForegroundAppInfo()
            currentPackage = foregroundInfo.first
            currentActivity = foregroundInfo.second
            Log.d(TAG, "前台应用: $currentPackage / $currentActivity")
        } catch (e: Exception) {
            Log.e(TAG, "获取前台应用失败: ${e.message}")
        }

        try {
            if (context is Activity) {
                extractAccessibilityTree(context.window.decorView, uiElements)
            }
        } catch (e: Exception) {
            Log.e(TAG, "分析UI元素失败: ${e.message}")
        }

        ScreenInfo(
            uiElements = uiElements,
            currentPackage = currentPackage,
            currentActivity = currentActivity
        )
    }

    @Suppress("DEPRECATION")
    private fun extractAccessibilityTree(decorView: View?, elements: MutableList<UIElement>) {
        if (decorView == null) {
            Log.w(TAG, "decorView为空")
            return
        }

        val contentView = decorView.findViewById<View>(android.R.id.content)
        if (contentView != null) {
            Log.d(TAG, "找到contentView: ${contentView.javaClass.simpleName}")
            val provider = contentView.accessibilityNodeProvider
            if (provider != null) {
                try {
                    val rootNode = provider.createAccessibilityNodeInfo(
                        android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID
                    )
                    rootNode?.let { node ->
                        val childCount = node.childCount
                        Log.d(TAG, "contentView无障碍节点树: 子节点数=$childCount")
                        traverseNode(node, elements, 0)
                        node.recycle()
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "contentView无障碍节点失败: ${e.message}")
                }
            } else {
                Log.d(TAG, "contentView无无障碍Provider，尝试遍历子View")

                if (contentView is ViewGroup) {
                    for (i in 0 until contentView.childCount) {
                        val child = contentView.getChildAt(i)
                        findAccessibilityProviderInView(child, elements)
                    }
                }
            }
        } else {
            Log.w(TAG, "未找到contentView，回退到decorView遍历")
            findAccessibilityProviderInView(decorView, elements)
        }
    }

    @Suppress("DEPRECATION")
    private fun findAccessibilityProviderInView(view: View?, elements: MutableList<UIElement>, depth: Int = 0) {
        if (view == null || depth > 10) return

        try {
            val provider = view.accessibilityNodeProvider
            if (provider != null) {
                val rootNode = provider.createAccessibilityNodeInfo(
                    android.view.accessibility.AccessibilityNodeProvider.HOST_VIEW_ID
                )
                rootNode?.let { node ->
                    val count = node.childCount
                    Log.d(TAG, "子View中找到无障碍树: ${view.javaClass.simpleName}, 子节点=$count")
                    if (count > 0) {
                        traverseNode(node, elements, 0)
                        node.recycle()
                        return
                    }
                    node.recycle()
                }
            }
        } catch (_: Exception) {
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findAccessibilityProviderInView(view.getChildAt(i), elements, depth + 1)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun traverseNode(
        node: AccessibilityNodeInfo,
        elements: MutableList<UIElement>,
        depth: Int
    ) {
        if (depth > 10) return

        try {
            val element = createUIElement(node)
            if (element != null) {
                elements.add(element)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                child?.let {
                    traverseNode(it, elements, depth + 1)
                    it.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "遍历节点失败: ${e.message}")
        }
    }

    private fun createUIElement(node: AccessibilityNodeInfo): UIElement? {
        return try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            if (bounds.width() <= 0 || bounds.height() <= 0) {
                return null
            }

            val text = node.text?.toString() ?: node.contentDescription?.toString()
            val type = detectElementType(node)

            UIElement(
                id = node.viewIdResourceName ?: "node_${node.hashCode()}",
                type = type,
                text = text,
                bounds = Bounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                contentDescription = node.contentDescription?.toString(),
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isSelected = node.isSelected,
                isChecked = node.isChecked
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun detectElementType(node: AccessibilityNodeInfo): UIElementType {
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val hasContent = hasText || hasDesc

        return when {
            node.isEditable -> UIElementType.INPUT
            node.isCheckable && node.isClickable -> UIElementType.SWITCH
            node.isClickable && (node.className?.toString()?.lowercase()?.contains("tab") == true ||
                    node.contentDescription?.toString()?.lowercase()?.contains("标签") == true) -> UIElementType.TAB
            node.isClickable && !hasContent -> UIElementType.ICON
            node.isClickable && hasText && !hasDesc -> UIElementType.LINK
            node.isClickable -> UIElementType.BUTTON
            node.isScrollable -> UIElementType.LIST
            hasContent -> UIElementType.TEXT
            else -> UIElementType.UNKNOWN
        }
    }

    fun findElementByPosition(screenInfo: ScreenInfo, x: Int, y: Int): UIElement? {
        return screenInfo.uiElements.find { element ->
            x >= element.bounds.left && x <= element.bounds.right &&
            y >= element.bounds.top && y <= element.bounds.bottom
        }
    }

    fun findElementByText(screenInfo: ScreenInfo, text: String): List<UIElement> {
        return screenInfo.uiElements.filter { element ->
            element.text?.contains(text, ignoreCase = true) == true ||
            element.contentDescription?.contains(text, ignoreCase = true) == true
        }
    }

    fun findElementById(screenInfo: ScreenInfo, id: String): UIElement? {
        return screenInfo.uiElements.find { it.id.contains(id) }
    }

    fun getScreenDescription(screenInfo: ScreenInfo): String {
        return buildString {
            appendLine("当前屏幕分析：")
            appendLine("- 应用：${screenInfo.currentPackage}")
            appendLine("- 界面元素数量：${screenInfo.uiElements.size}")

            if (screenInfo.uiElements.isNotEmpty()) {
                appendLine("\n主要交互元素：")
                screenInfo.uiElements
                    .filter { it.isClickable || it.isEditable }
                    .take(10)
                    .forEachIndexed { index, element ->
                        appendLine("${index + 1}. [${element.type}] \"${element.text}\" - 可${if (element.isEditable) "输入" else "点击"}")
                    }
            }
        }
    }

    suspend fun takeScreenshot(): Bitmap? = withContext(Dispatchers.Main) {
        GUIAccessibilityService.instance?.takeScreenshot()
    }

    private fun getForegroundAppInfo(): Pair<String?, String?> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                if (usageStatsManager != null) {
                    val endTime = System.currentTimeMillis()
                    val beginTime = endTime - 10000
                    val usageStatsList = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, beginTime, endTime
                    )

                    if (usageStatsList.isNotEmpty()) {
                        val recent = usageStatsList.maxByOrNull { it.lastTimeUsed }
                        if (recent != null) {
                            return Pair(recent.packageName, null)
                        }
                    }
                }
            }

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager != null) {
                val tasks = activityManager.appTasks
                if (tasks.isNotEmpty()) {
                    val taskInfo = tasks.firstOrNull()?.taskInfo
                    if (taskInfo != null) {
                        val componentName = taskInfo.topActivity
                        return Pair(componentName?.packageName, componentName?.className)
                    }
                }

                @Suppress("DEPRECATION")
                val runningTasks = activityManager.getRunningTasks(1)
                if (runningTasks.isNotEmpty()) {
                    val topActivity = runningTasks[0].topActivity
                    return Pair(topActivity?.packageName, topActivity?.className)
                }
            }

            Pair(null, null)
        } catch (e: Exception) {
            Log.e(TAG, "获取前台应用异常: ${e.message}")
            Pair(null, null)
        }
    }
}
