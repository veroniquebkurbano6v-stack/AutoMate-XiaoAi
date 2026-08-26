package com.palmagent.app.tool.impl

import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import kotlinx.coroutines.delay

/**
 * 滚动查找工具（scroll_until）
 *
 * 在滚动过程中自动甄别目标元素是否出现，找到后按需定位并点击。
 * 识别链路与 LocateTool 不同（甄别与定位分离，防幻觉坐标）：
 *  - 甄别（exists）：调 GuiOwlService.exists 只判断元素是否存在（true/false，无坐标可编）
 *  - 定位（ground）：仅当 exists=true 后才调 GuiOwlService.ground 获取坐标，并按 click_on_found 决定是否点击
 *
 * 循环：截屏 → exists → true则 ground+点击(按click_on_found) / false则滚动 → 直到找到或达 max_scrolls / 页面边界
 */
class ScrollUntilTool : BaseTool() {

    companion object {
        private const val TAG = "ScrollUntilTool"
        private const val MAX_SCROLLS_DEFAULT = 5
        private const val MAX_SCROLLS_MAX = 10
        private const val INTERVAL_MS_DEFAULT = 800L
        private const val INTERVAL_MS_MIN = 500L
        private const val INTERVAL_MS_MAX = 2000L
    }

    override fun getName(): String = "scroll_until"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "target",
            "string",
            "要找的目标元素（必填），必须是视觉可辨识的具体描述：可见文字、图标形状/颜色、相对位置。" +
                "如'心相印金装经典抽纸'、'底部导航栏的购物车图标'、'数量加号图标，圆形白色底，位于商品行右侧'。" +
                "禁止模糊描述（那个/相关的/类似的）",
            true
        ),
        ToolParameter(
            "direction",
            "string",
            "滚动方向：down/up/left/right，默认down",
            false,
            default = "down"
        ),
        ToolParameter(
            "max_scrolls",
            "integer",
            "最大滚动次数，默认5，范围1-10",
            false,
            default = MAX_SCROLLS_DEFAULT,
            minValue = 1,
            maxValue = MAX_SCROLLS_MAX
        ),
        ToolParameter(
            "interval_ms",
            "integer",
            "每次滚动后等待界面稳定的间隔毫秒，默认800，范围500-2000",
            false,
            default = INTERVAL_MS_DEFAULT
        ),
        ToolParameter(
            "click_on_found",
            "boolean",
            "找到目标后是否自动定位并点击，默认true；false时只返回坐标不点击",
            false,
            default = true
        )
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val target = requireString(params, "target").trim()
        if (target.isBlank()) {
            return ToolResult.error("target 不能为空，必须是视觉可辨识的元素描述")
        }

        val direction = (params["direction"] as? String)?.lowercase()
            ?.takeIf { it in setOf("down", "up", "left", "right") } ?: "down"
        val maxScrolls = optionalInt(params, "max_scrolls", MAX_SCROLLS_DEFAULT)
            .coerceIn(1, MAX_SCROLLS_MAX)
        val intervalMs = optionalLong(params, "interval_ms", INTERVAL_MS_DEFAULT)
            .coerceIn(INTERVAL_MS_MIN, INTERVAL_MS_MAX)
        val clickOnFound = parseBooleanParam(params["click_on_found"], default = true)

        val screenSize = getScreenSize()
        val screenWidth = screenSize[0]
        val screenHeight = screenSize[1]

        if (!GuiOwlService.isReady) {
            return ToolResult.error(
                "GUI-Plus服务未就绪: ${GuiOwlService.lastError ?: "请在设置中配置API地址"}",
                errorType = "FATAL",
                failureCategory = "SERVICE_UNAVAILABLE",
                code = "GUI_PLUS_UNAVAILABLE",
                suggestion = "GUI-Plus服务未配置，需用户在设置中配置API"
            )
        }

        LiveLogBuffer.append("🔍 SCROLL_UNTIL 查找'$target'（方向:$direction 最多滚${maxScrolls}次 点击:$clickOnFound）")

        for (scroll in 0..maxScrolls) {
            // ① 截屏
            val screenshot: Bitmap? = takeScreenshot()
            if (screenshot == null) {
                return ToolResult.error("无法获取屏幕截图")
            }

            try {
                // ② 甄别元素是否存在（GuiOwlService.exists，只返回 true/false）
                val existsResult = GuiOwlService.exists(target, screenshot)
                if (!existsResult.success) {
                    Log.w(TAG, "exists 调用失败(第${scroll}屏): ${existsResult.error}")
                    return ToolResult.error("元素甄别失败: ${existsResult.error}")
                }

                if (existsResult.exists) {
                    LiveLogBuffer.append("🎯 第${scroll}屏找到'$target': ${existsResult.reason.take(50)}")
                    // ③ 找到：按 click_on_found 决定是否定位+点击
                    return handleFound(target, screenshot, screenWidth, screenHeight, scroll, clickOnFound)
                }

                // ④ 未找到且已达滚动上限：返回未找到
                if (scroll >= maxScrolls) {
                    return ToolResult.error(
                        "滚动查找'$target' ${maxScrolls}次后仍未找到，可能不在当前列表或描述不准确",
                        errorType = "VALIDATION",
                        failureCategory = "TARGET_NOT_FOUND",
                        code = "TARGET_NOT_FOUND",
                        suggestion = "目标可能不在该入口下，请返回换入口、调整target描述，或改用其他操作"
                    )
                }

                // ⑤ 滚动一屏（复用方向滚动工具，含"签名无变化=已到边界"检测）
                val scrollResult = scrollOnce(direction)
                if (!scrollResult.isSuccess) {
                    return ToolResult.error(
                        "滚动查找'$target'在第${scroll + 1}次滚动时失败: ${scrollResult.error}（可能已到页面边界）",
                        errorType = "VALIDATION",
                        failureCategory = "TARGET_NOT_FOUND",
                        code = "SCROLL_FAILED",
                        suggestion = "已到页面底部/边界，目标不在当前列表"
                    )
                }

                delay(intervalMs)
            } finally {
                try { if (!screenshot.isRecycled) screenshot.recycle() } catch (_: Exception) {}
            }
        }

        return ToolResult.error("滚动查找'$target'未找到")
    }

    /** 找到目标后的处理：exists=true 后再调 ground 定位，按 click_on_found 决定是否点击 */
    private suspend fun handleFound(
        target: String,
        screenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        scroll: Int,
        clickOnFound: Boolean
    ): ToolResult {
        // 定位：exists=true 后再调 GuiOwlService.ground 拿坐标
        val groundResult = GuiOwlService.ground(
            "Click $target", screenshot, screenWidth, screenHeight
        )
        if (!groundResult.success || groundResult.coordinate == null) {
            // 存在但定位失败：返回已找到但无法定位（不视为致命错误）
            return ToolResult.success(
                "已找到'$target'（滚动${scroll}次），但定位失败: ${groundResult.error ?: "未知错误"}",
                mapOf("found" to true, "clicked" to false, "scrolls" to scroll)
            )
        }

        val x = groundResult.coordinate.x
        val y = groundResult.coordinate.y

        // 坐标屏内校验（防幻觉坐标）
        val invalid = validateCoordinates(x, y)
        if (invalid != null) {
            Log.w(TAG, "定位坐标越界($x,$y)，不点击")
            return ToolResult.success(
                "已找到'$target'但定位坐标越界($x,$y)，未点击",
                mapOf("found" to true, "clicked" to false, "scrolls" to scroll)
            )
        }

        if (!clickOnFound) {
            // 只返回坐标不点击
            return ToolResult.success(
                "已找到'$target'（滚动${scroll}次），坐标($x,$y)，未点击",
                mapOf("found" to true, "clicked" to false, "x" to x, "y" to y, "scrolls" to scroll)
            )
        }

        // 点击
        val clicked = performClick(x, y)
        return if (clicked) {
            ToolResult.success(
                "滚动${scroll}次后找到'$target'并已点击 ($x,$y)",
                mapOf("found" to true, "clicked" to true, "x" to x, "y" to y, "scrolls" to scroll)
            )
        } else {
            ToolResult.success(
                "已找到'$target'($x,$y)但点击手势被取消",
                mapOf("found" to true, "clicked" to false, "x" to x, "y" to y, "scrolls" to scroll)
            )
        }
    }

    /** 按方向执行一次滚动，复用 swipe 方向滚动模式（含边界签名检测） */
    private suspend fun scrollOnce(direction: String): ToolResult {
        val safeDirection = direction.lowercase().takeIf { it in setOf("up", "down", "left", "right") } ?: "down"
        return SwipeTool().executeWithWaitAfter(mapOf("direction" to safeDirection))
    }

    /** 宽松解析布尔参数（true/1/yes/Boolean） */
    private fun parseBooleanParam(value: Any?, default: Boolean): Boolean {
        return when (value) {
            null -> default
            is Boolean -> value
            else -> when (value.toString().lowercase()) {
                "true", "1", "yes", "是" -> true
                "false", "0", "no", "否" -> false
                else -> default
            }
        }
    }

    override fun getDescriptionEN(): String =
        "Scroll and search for a target element. Each screen: check existence via the visual model " +
        "(returns true/false only, no fake coordinates), scroll one page if not found, repeat until found " +
        "or max_scrolls reached / page boundary. If found and click_on_found=true (default), auto locate and click; " +
        "if false, return coordinates only. target must be visually identifiable (visible text/icon shape/color/position)."

    override fun getDescriptionCN(): String =
        "滚动查找目标元素：每屏先用视觉模型甄别目标是否存在（只返回有/无，不产生猜测坐标），" +
            "不存在则滚动一屏继续查找，直到找到或达到最大滚动次数/页面边界。" +
            "找到后按 click_on_found 决定是否自动定位并点击（默认点击）；false 时只返回坐标不点击。" +
            "target 必须是视觉可辨识的具体描述（可见文字/图标形状/颜色/位置），禁止模糊描述（那个/相关的/类似的）。" +
            "用于'滚动找商品/找按钮/找入口'场景，替代多次连续 SCROLL_DOWN。"
}
