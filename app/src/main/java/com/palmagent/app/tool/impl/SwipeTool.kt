package com.palmagent.app.tool.impl

import android.util.Log
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import kotlinx.coroutines.delay

/**
 * 滑动/滚动工具（swipe）
 *
 * 合并原 swipe 与 scroll_down/up/left/right 四个方向滚动工具为单一工具，通过 direction 参数控制：
 * - direction=up/down/left/right：方向滚动模式。纯手势滑动，不依赖无障碍原生滚动 API；
 *   默认从屏幕中部起滑（规避命中顶部分类 Tab 行/底部导航栏导致"滑动切标签"），
 *   滑动后通过无障碍树签名对比校验是否真正生效（页面无变化 = 已到边界 → SCROLL_NO_EFFECT）。
 * - direction=custom（默认，兼容旧调用）：自定义轨迹滑动，直接使用显式起终点坐标
 *   （全面屏返回手势 start_x=0, end_x=屏幕宽度的15% / 自定义轨迹）。
 */
class SwipeTool : BaseTool() {

    companion object {
        private const val TAG = "SwipeTool"
        /** 方向模式未指定 distance 时的默认滑动距离占主轴长度的比例（方案一：一屏的80%） */
        private const val DEFAULT_DISTANCE_RATIO = 0.8f
        /** 默认起点：主轴居中（50%） */
        private const val CENTER_RATIO = 0.5f
        /** 方向模式的最小滑动距离（像素），防御 distance 过小导致"假滑动" */
        private const val MIN_SWIPE_DISTANCE = 10
        /** 滑动后等待页面稳定的时长（与 ActionExecutor 轮询间隔一致） */
        private const val STABLE_WAIT_MS = 300L
        private val DIRECTIONS = setOf("up", "down", "left", "right", "custom")
    }

    override fun getName(): String = "swipe"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "direction",
            "string",
            "滑动方向：up/down/left/right/custom，默认custom（custom=使用显式坐标精确滑动）",
            false,
            default = "custom",
            enumValues = listOf("up", "down", "left", "right", "custom")
        ),
        ToolParameter(
            "start_x",
            "integer",
            "起点X坐标（可选，不传默认屏幕水平居中）",
            false
        ),
        ToolParameter(
            "start_y",
            "integer",
            "起点Y坐标（可选，不传默认屏幕垂直居中）",
            false
        ),
        ToolParameter(
            "end_x",
            "integer",
            "终点X坐标（direction=custom时必填）",
            false
        ),
        ToolParameter(
            "end_y",
            "integer",
            "终点Y坐标（direction=custom时必填）",
            false
        ),
        ToolParameter(
            "distance",
            "integer",
            "方向模式的滑动距离（像素，不传默认一屏的80%）",
            false
        ),
        ToolParameter("duration_ms", "integer", "滑动持续时间(ms)，不传则按滑动距离自动推算(200-500ms)", false)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val size = getScreenSize()
        val screenW = size[0]
        val screenH = size[1]
        if (screenW <= 0 || screenH <= 0) {
            return ToolResult.error("无法获取屏幕尺寸，滑动失败")
        }

        // 计算起终点（纯函数，可单测）；校验失败返回空
        val path = planSwipe(params, screenW, screenH) ?: return ToolResult.error(
            if (isCustom(params)) {
                "direction=custom 时必须提供合法的 end_x/end_y（以及可选 start_x/start_y）终点坐标"
            } else {
                "滑动参数非法：请检查 start_x/start_y/distance 是否在屏幕范围内"
            }
        )
        return if (isCustom(params)) {
            performSwipe(path.startX, path.startY, path.endX, path.endY, durationMs(params, path))
        } else {
            executeDirectionalWithCheck(params, path, screenW, screenH)
        }
    }

    /** 方向模式：滑动成功后校验无障碍树签名，页面无变化判定为已到边界 */
    private suspend fun executeDirectionalWithCheck(
        params: Map<String, Any>,
        path: SwipePath,
        screenW: Int,
        screenH: Int
    ): ToolResult {
        val dirName = params["direction"]?.toString()?.lowercase()?.let { dirLabel(it) } ?: ""
        val service = getA11yService()
        val beforeSig = service?.getTreeSignature()

        val result = performSwipe(path.startX, path.startY, path.endX, path.endY, durationMs(params, path))
        if (!result.isSuccess) {
            return result
        }

        // 等页面稳定后校验：签名变化 = 真的滚动了；无变化 = 已到边界/未生效
        delay(STABLE_WAIT_MS)
        val afterSig = service?.getTreeSignature()
        if (afterSig != null && beforeSig != null && afterSig == beforeSig) {
            Log.w(TAG, "向${dirName}滑动后页面签名无变化，滚动可能未生效（已到页面边界？）")
            return ToolResult.error(
                "向${dirName}滑动后页面无变化，可能已到页面边界或滑动未生效",
                errorType = "VALIDATION",
                failureCategory = "SCROLL_NO_EFFECT",
                code = "SCROLL_NO_EFFECT",
                suggestion = "已到页面边界或内容不可滚动，请尝试返回、换入口或改用其他操作，不要重复相同滑动"
            )
        }

        return ToolResult.success(
            "向${dirName}滑动完成 (${path.startX},${path.startY}) → (${path.endX},${path.endY})，屏幕尺寸: ${screenW}x${screenH}"
        )
    }

    /**
     * 根据 direction/distance/start/end 参数计算滑动起终点（纯函数，无 Android 依赖，便于单测）。
     *
     * @return null 表示参数非法（缺终点/越界/屏幕尺寸异常）
     */
    internal fun planSwipe(params: Map<String, Any>, screenW: Int, screenH: Int): SwipePath? {
        if (screenW <= 0 || screenH <= 0) return null
        val direction = (params["direction"] as? String)?.lowercase()
            ?.takeIf { it in DIRECTIONS } ?: "custom"

        return when (direction) {
            "custom" -> {
                val endX = optionalInt(params, "end_x", -1)
                val endY = optionalInt(params, "end_y", -1)
                // 缺终点（默认 -1）→ 非法；起点缺省默认屏幕中心
                if (endX < 0 || endY < 0) return null
                val startX = optionalInt(params, "start_x", -1)
                    .let { if (it >= 0) it else screenW / 2 }
                val startY = optionalInt(params, "start_y", -1)
                    .let { if (it >= 0) it else (screenH * CENTER_RATIO).toInt() }
                if (startX !in 0 until screenW || startY !in 0 until screenH) return null
                if (endX !in 0 until screenW || endY !in 0 until screenH) return null
                SwipePath(startX, startY, endX, endY)
            }
            else -> {
                val isVertical = direction == "up" || direction == "down"
                val mainAxisSize = if (isVertical) screenH else screenW
                val crossAxisSize = if (isVertical) screenW else screenH

                // 滑动距离：显式 distance 按 [MIN, 主轴] clamp；缺省取主轴 80%（可选值必须 ≤ default 才不被 BaseTool.optionalInt 提升）
                val distance = if (params.containsKey("distance")) {
                    optionalInt(params, "distance", MIN_SWIPE_DISTANCE)
                        .coerceIn(MIN_SWIPE_DISTANCE, mainAxisSize)
                } else {
                    (mainAxisSize * DEFAULT_DISTANCE_RATIO).toInt()
                }

                // 起点：默认取屏幕中部（CENTER_RATIO=0.5），不会落在顶部分类 Tab 行或底部导航栏，
                // 从机制上规避"原生滚动作用在 Tab 行导致切换标签"的问题
                val mainStart: Int
                val crossStart: Int
                if (isVertical) {
                    mainStart = optionalInt(params, "start_y", -1)
                        .let { if (it >= 0) it.coerceIn(0, mainAxisSize - 1) else (mainAxisSize * CENTER_RATIO).toInt() }
                    crossStart = optionalInt(params, "start_x", -1)
                        .let { if (it >= 0) it.coerceIn(0, crossAxisSize - 1) else (crossAxisSize * CENTER_RATIO).toInt() }
                } else {
                    mainStart = optionalInt(params, "start_x", -1)
                        .let { if (it >= 0) it.coerceIn(0, mainAxisSize - 1) else (mainAxisSize * CENTER_RATIO).toInt() }
                    crossStart = optionalInt(params, "start_y", -1)
                        .let { if (it >= 0) it.coerceIn(0, crossAxisSize - 1) else (crossAxisSize * CENTER_RATIO).toInt() }
                }

                // Android 触摸滑动：手指方向与内容滚动方向相反
                // down/right = 看更下方/右方内容 = 手指向上/向左滑 → sign=-1
                val sign = if (direction == "down" || direction == "right") -1 else 1
                val mainEnd = (mainStart + distance * sign).coerceIn(0, mainAxisSize - 1)

                return if (isVertical) {
                    SwipePath(crossStart, mainStart, crossStart, mainEnd)
                } else {
                    SwipePath(mainStart, crossStart, mainEnd, crossStart)
                }
            }
        }
    }

    /** duration：显式 duration_ms > 0 用之，否则按距离动态推算（模拟真实手指） */
    private fun durationMs(params: Map<String, Any>, path: SwipePath): Long {
        val user = optionalLong(params, "duration_ms", -1)
        return if (user > 0) user else calculateDuration(path)
    }

    private fun calculateDuration(path: SwipePath): Long {
        val dx = (path.endX - path.startX).toDouble()
        val dy = (path.endY - path.startY).toDouble()
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        return when {
            distance < 200 -> 200L   // 短距离快速
            distance < 500 -> 300L   // 中距离标准
            distance < 1000 -> 400L  // 长距离慢速
            else -> 500L
        }
    }

    private fun dirLabel(direction: String): String = when (direction) {
        "up" -> "上"; "down" -> "下"; "left" -> "左"; else -> "右"
    }

    private fun isCustom(params: Map<String, Any>): Boolean {
        val dir = (params["direction"] as? String)?.lowercase()?.takeIf { it in DIRECTIONS } ?: "custom"
        return dir == "custom"
    }

    override fun getDescriptionEN(): String =
        "Swipe or scroll the screen. direction: up/down/left/right for directional scrolling " +
            "(auto-calculated from screen center), or custom with explicit start_x/start_y/end_x/end_y " +
            "for precise gestures (e.g. full-screen back). Optional: distance (px), duration_ms (default auto)."

    override fun getDescriptionCN(): String =
        "滑动/滚动屏幕，参数 direction(up/down/left/right/custom，默认custom)、start_x/start_y(起点，可选)、" +
            "end_x/end_y(终点，direction=custom时必填)、distance(方向模式滑动距离像素，默认一屏的80%)、duration_ms(可选，不传按距离自动推算)。" +
            "用于：浏览列表(向上/下滑动)、切换标签页(左/右滑动)、全面屏手势返回(start_x=0,end_x=屏幕宽度的15%)。" +
            "方向模式默认从屏幕中部起滑并校验滑动是否生效。"
}

/** 滑动路径（起终点坐标），由 planSwipe 纯函数计算 */
internal data class SwipePath(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int
)