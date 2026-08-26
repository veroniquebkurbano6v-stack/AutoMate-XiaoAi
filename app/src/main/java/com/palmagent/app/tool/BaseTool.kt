package com.palmagent.app.tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.palmagent.app.AgentApplication
import com.palmagent.app.service.GUIAccessibilityService
import java.io.File

abstract class BaseTool {

    companion object {
        // P3-9 修复：加 @Volatile 保证多线程可见性
        @Volatile
        var useChineseDescription: Boolean = true

        private const val MAX_WAIT_AFTER_MS = 10000L

        val WAIT_AFTER_PARAM = ToolParameter(
            "wait_after",
            "integer",
            "Optional: milliseconds to wait after this action completes (e.g. 2000 for page load). Default 0 (no wait).",
            false
        )
    }

    abstract fun getName(): String
    abstract fun getParameters(): List<ToolParameter>
    abstract suspend fun execute(params: Map<String, Any>): ToolResult

    fun getParametersWithWaitAfter(): List<ToolParameter> {
        val params = getParameters().toMutableList()
        if (getName() !in listOf("wait", "finish", "get_screen_info", "take_screenshot")) {
            params.add(WAIT_AFTER_PARAM)
        }
        return params
    }

    suspend fun executeWithWaitAfter(params: Map<String, Any>): ToolResult {
        val result = execute(params)
        if (result.isSuccess) {
            // P3-10 修复：越界值 clamp 到 [0, MAX] 而非静默忽略
            val waitMs = optionalLong(params, "wait_after", 0).coerceIn(0L, MAX_WAIT_AFTER_MS)
            if (waitMs > 0) {
                kotlinx.coroutines.delay(waitMs)
            }
        }
        return result
    }

    abstract fun getDescriptionEN(): String
    abstract fun getDescriptionCN(): String

    fun getDescription(): String =
        if (useChineseDescription) getDescriptionCN() else getDescriptionEN()

    /**
     * 是否暴露给执行模型。
     * false 的工具仅系统内部使用（如决策模型），不进入执行模型的 prompt 描述和检索候选集。
     */
    open fun isExposedToExecutionModel(): Boolean = true

    open fun getDisplayName(): String = getName()

    protected fun requireString(params: Map<String, Any>, key: String): String {
        return params[key]?.toString()
            ?: throw IllegalArgumentException("Missing required parameter: $key")
    }

    protected fun requireInt(params: Map<String, Any>, key: String): Int {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().toIntOrNull()
                ?: throw IllegalArgumentException("参数 $key 无法转为 Int: $value")
        }
    }

    protected fun requireLong(params: Map<String, Any>, key: String): Long {
        val value = params[key] ?: throw IllegalArgumentException("Missing required parameter: $key")
        return when (value) {
            is Number -> value.toLong()
            else -> value.toString().toLongOrNull()
                ?: throw IllegalArgumentException("参数 $key 无法转为 Long: $value")
        }
    }

    protected fun optionalInt(params: Map<String, Any>, key: String, default: Int): Int {
        val value = params[key] ?: return default
        val result = when (value) {
            is Number -> value.toInt()
            // P1-2 修复：LLM 传入非数字字符串时回退到 default，而非抛 NumberFormatException 崩溃
            else -> value.toString().toIntOrNull() ?: return default
        }
        // 防御：LLM 可能传入 0 或负数（如 max_scroll_times=0），确保至少返回 1
        return result.coerceAtLeast(default.coerceAtLeast(1))
    }

    protected fun optionalLong(params: Map<String, Any>, key: String, default: Long): Long {
        val value = params[key] ?: return default
        return when (value) {
            is Number -> value.toLong()
            // P1-2 修复：LLM 传入非数字字符串时回退到 default，而非抛 NumberFormatException 崩溃
            else -> value.toString().toLongOrNull() ?: default
        }
    }

    protected fun optionalString(params: Map<String, Any>, key: String, default: String): String {
        return params[key]?.toString() ?: default
    }

    protected fun getA11yService(): GUIAccessibilityService? = GUIAccessibilityService.instance

    /**
     * 获取屏幕尺寸，无障碍不可用时通过Application Context回退
     */
    protected fun getScreenSize(): IntArray {
        val metrics = DisplayMetrics()
        val context = getA11yService() ?: AgentApplication.instance
        @Suppress("DEPRECATION")
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        return intArrayOf(metrics.widthPixels, metrics.heightPixels)
    }

    protected fun validateCoordinates(x: Int, y: Int): String? {
        val size = getScreenSize()
        if (x < 0 || x >= size[0] || y < 0 || y >= size[1]) {
            return "坐标 ($x, $y) 超出屏幕范围 (${size[0]}x${size[1]})"
        }
        return null
    }

    /**
     * 通过shell命令执行滑动手势（无障碍服务不可用时的回退方案）
     */
    protected fun shellSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "input swipe $startX $startY $endX $endY $duration")
            )
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.w(getName(), "shell滑动失败: ${e.message}")
            false
        }
    }

    /**
     * 执行滑动：优先无障碍服务，回退shell命令
     */
    protected suspend fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long): ToolResult {
        val service = getA11yService()
        if (service != null) {
            val ok = service.performAccessibilitySwipe(startX, startY, endX, endY, duration)
            return if (ok) ToolResult.success("已滑动 ($startX,$startY) → ($endX,$endY)")
            else ToolResult.error("滑动手势被取消")
        }

        // 回退：shell命令
        return if (shellSwipe(startX, startY, endX, endY, duration)) {
            ToolResult.success("已滑动($startX,$startY)→($endX,$endY) [shell]")
        } else {
            ToolResult.error("无障碍服务未运行且shell滑动失败")
        }
    }

    // ======================== 统一的 Shell 命令 ========================

    /**
     * 通过shell命令执行点击（无障碍服务不可用时的回退方案）
     */
    protected fun shellClick(x: Int, y: Int): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "input tap $x $y")).waitFor() == 0
        } catch (e: Exception) {
            Log.w(getName(), "shell点击失败: ${e.message}")
            false
        }
    }

    /**
     * 通过shell命令执行长按（无障碍服务不可用时的回退方案）
     */
    protected fun shellLongPress(x: Int, y: Int, duration: Long = 600L): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "input swipe $x $y $x $y $duration")).waitFor() == 0
        } catch (e: Exception) {
            Log.w(getName(), "shell长按失败: ${e.message}")
            false
        }
    }

    /**
     * 通过shell命令截屏（无障碍服务不可用时的回退方案）
     */
    protected fun shellScreenshot(): Bitmap? {
        return try {
            val file = File.createTempFile("screenshot", ".png")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap ${file.absolutePath}"))
            if (process.waitFor() == 0) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                file.delete()
                bitmap
            } else {
                file.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(getName(), "shell截屏失败: ${e.message}")
            null
        }
    }

    // ======================== 统一的操作方法（优先无障碍，回退shell） ========================

    /**
     * 执行点击：优先无障碍服务，回退shell命令
     */
    protected suspend fun performClick(x: Int, y: Int): Boolean {
        val service = getA11yService()
        return if (service != null) service.performAccessibilityClick(x, y)
        else shellClick(x, y)
    }

    /**
     * 执行长按：优先无障碍服务，回退shell命令
     */
    protected suspend fun performLongPress(x: Int, y: Int, duration: Long = 1200L): Boolean {
        val service = getA11yService()
        return if (service != null) service.performAccessibilityLongPress(x, y, duration)
        else shellLongPress(x, y, duration)
    }

    /**
     * 截屏：优先无障碍服务，回退shell命令
     */
    protected suspend fun takeScreenshot(): Bitmap? {
        val service = getA11yService()
        val bmp = service?.takeScreenshot()
        return bmp ?: shellScreenshot()
    }
}