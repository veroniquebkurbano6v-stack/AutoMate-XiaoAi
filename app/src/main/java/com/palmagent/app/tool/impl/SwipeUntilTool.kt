package com.palmagent.app.tool.impl

import android.util.Log
import com.palmagent.app.agent.ScreenDescriptor
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import kotlinx.coroutines.delay

/**
 * swipe_until：目标驱动滑动——模型只提需求（目标可见文本），工具内部自动滑动直到目标可见
 *
 * - 参数：target（目标可见文本，如"22:00"）、container（选填——容器名——容器表匹配 y 锚定滑动行）、
 *   max_swipes（滑动上限，默认 5，上限 10）
 * - 循环：先 EXISTS 问"目标可见？"（当前截图）→ 可见则不动返回成功
 *   → 不可见则默认方向滑一次（横向容器左滑/竖向容器上滑；无容器默认横向左滑）→ 重新截图再问
 *   → 默认方向滑 2 次仍不可见则换反向（试错——不依赖模型预知方向）→ max_swipes 用完仍不可见返回失败
 * - 坐标自适配（居中起点+半屏距离——基于当前手机尺寸比例）
 */
class SwipeUntilTool : BaseTool() {

    private val TAG = "SwipeUntilTool"

    override fun getName(): String = "swipe_until"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("target", "string", "必填,目标可见文本（如'22:00'/'明天'——滑动直到该文本可见）", true),
        ToolParameter("container", "string", "选填,容器名（如'时间选择栏'——从【可横向滑动容器】段选取；不填则屏幕中部横向滑动）", false),
        ToolParameter("max_swipes", "int", "选填,最大滑动次数,默认5,上限10", false, 5)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val target = params["target"]?.toString()?.trim().orEmpty()
        if (target.isEmpty()) return ToolResult.error("swipe_until 缺 target（目标可见文本）")
        val containerName = params["container"]?.toString()?.trim()
        val maxSwipes = (params["max_swipes"] as? Number)?.toInt()?.coerceIn(1, 10) ?: 5

        val size = getScreenSize()
        val screenW = size[0]
        val screenH = size[1]
        if (screenW <= 0 || screenH <= 0) return ToolResult.error("无法获取屏幕尺寸，swipe_until 失败")

        // 容器表匹配（选填——y 锚定滑动行；无容器默认屏幕中部）
        val containerInfo = containerName?.let { name ->
            ScreenDescriptor.containerTable.entries.firstOrNull {
                it.key.contains(name) || name.contains(it.key)
            }?.value ?: ScreenDescriptor.containerTable[name]
        }

        // ① 初始 EXISTS 检查：目标已可见则不动
        var screenshot = takeScreenshot()
        if (screenshot == null) return ToolResult.error("无法截屏，swipe_until 失败")
        if (GuiOwlService.exists(target, screenshot).exists) {
            return clickVisibleTarget(target, containerName, containerInfo?.yScreen, screenshot, screenW, screenH)
        }

        // 默认方向：横向容器左滑 / 竖向容器上滑 / 无容器默认横向左滑（当前容器识别仅横向——竖向滚动场景走全屏）
        val horizontal = containerInfo != null || containerName == null // 有容器或未指定→横向；仅明确竖向场景走纵向（当前识别无竖向）
        var forward = true // 默认：横向左滑 / 竖向上滑

        var swiped = 0
        var noProgress = 0  // 当前方向连续"滑动后界面签名无变化"次数——无变化 = 该方向已到底/无效（换方向依据，非滑动次数）
        while (swiped < maxSwipes) {
            // 滑动前签名（对比用）
            val beforeSig = getA11yService()?.getTreeSignature()
            val y = containerInfo?.yScreen ?: (screenH / 2)
            val startX = screenW / 2
            if (horizontal) {
                val halfW = screenW / 2
                val endX = if (forward) (startX - halfW).coerceAtLeast(0) else (startX + halfW).coerceAtMost(screenW - 1)
                val r = SwipeTool().execute(
                    mapOf("direction" to "custom", "start_x" to startX, "start_y" to y, "end_x" to endX, "end_y" to y)
                )
                if (!r.isSuccess) return ToolResult.error("swipe_until 第${swiped + 1}次滑动失败: ${r.error}")
            } else {
                val scrollH = screenH * 4 / 5
                val endY = if (forward) (y - scrollH).coerceAtLeast(0) else (y + scrollH).coerceAtMost(screenH - 1)
                val r = SwipeTool().execute(
                    mapOf("direction" to "custom", "start_x" to startX, "start_y" to y, "end_x" to startX, "end_y" to endY)
                )
                if (!r.isSuccess) return ToolResult.error("swipe_until 第${swiped + 1}次滑动失败: ${r.error}")
            }
            swiped++
            delay(400)

            // 滑动后签名稳定对比：无变化 = 该方向已到底/滑动无效（换方向依据）
            val afterSig = stableSignature()
            if (beforeSig != null && afterSig != null && afterSig == beforeSig) {
                noProgress++
                Log.d(TAG, "swipe_until: 第${swiped}次滑动后界面签名无变化（该方向已到底）——无进展 $noProgress/2")
            } else {
                noProgress = 0
            }

            // ② 滑动后重新截图 + 再问目标可见
            screenshot = takeScreenshot()
            if (screenshot == null) return ToolResult.error("无法截屏，swipe_until 失败")
            if (GuiOwlService.exists(target, screenshot).exists) {
                return clickVisibleTarget(target, containerName, containerInfo?.yScreen, screenshot, screenW, screenH)
            }
            // ③ 换方向：仅当该方向连续 2 次滑动后界面签名无变化（已到底/方向无效）才换反向——有进展保持方向
            if (noProgress >= 2 && swiped < maxSwipes) {
                forward = !forward
                noProgress = 0
                Log.d(TAG, "swipe_until: 当前方向已到底（连续 2 次无变化）——换方向")
            }
        }

        return ToolResult.error(
            "滑动 $maxSwipes 次后目标「$target」仍不可见——目标可能在别的容器或需要切换界面——请参考【可横向滑动容器】段重新规划"
        )
    }

    /**
     * 目标可见即点击：GROUND 定位（容器名+目标拼接消歧）→ y 校验（与容器 y 偏差 ≤ 屏高 15%）→ tap
     * 返回"已点击"；定位失败/坐标异常/点击失败分别降级（不点——模型下一轮自己处理）
     */
    private suspend fun clickVisibleTarget(
        target: String,
        containerName: String?,
        containerY: Int?,   // 容器 y（屏幕像素）——y 校验用
        screenshot: android.graphics.Bitmap,
        screenW: Int,
        screenH: Int
    ): ToolResult {
        val groundDesc = containerName?.takeIf { it.isNotBlank() }
            ?.let { "${it}中的$target" } ?: target
        val ground = GuiOwlService.ground(groundDesc, screenshot, screenW, screenH)
        val coord = ground.coordinate
        if (ground.success && coord != null) {
            val yOk = containerY == null || kotlin.math.abs(coord.y - containerY) <= screenH * 15 / 100
            if (yOk) {
                val tapResult = TapTool().execute(mapOf("x" to coord.x, "y" to coord.y))
                if (tapResult.isSuccess) {
                    return ToolResult.success("已点击目标「$target」（坐标 (${coord.x},${coord.y})）")
                }
                return ToolResult.error("目标「$target」已可见但点击失败: ${tapResult.error}")
            }
            return ToolResult.error(
                "目标「$target」已可见但定位坐标异常（y=${coord.y} 与容器 y=$containerY 偏差过大）——跳过点击"
            )
        }
        return ToolResult.success("目标「$target」已可见（未点击——定位失败：${ground.error ?: "未知"}）")
    }

    /** 轮询取无障碍树稳定签名（连续两次一致视为稳定——覆盖惯性滚动/异步渲染） */
    private suspend fun stableSignature(): Pair<Int, String>? {
        val service = getA11yService() ?: return null
        var prev: Pair<Int, String>? = null
        repeat(3) {
            val cur = service.getTreeSignature()
            if (prev != null && cur == prev) return cur
            prev = cur
            delay(200)
        }
        return prev
    }

    override fun getDescriptionEN(): String =
        "Swipe until a target text is visible (goal-driven — model states the requirement only; tool auto-swipes: default horizontal=left/vertical=up, EXISTS visibility check before and after each swipe, reverses direction after 2 no-progress swipes). Params: target(required), container(optional), max_swipes(optional, default 5)."

    override fun getDescriptionCN(): String =
        "滑动直到目标文本可见（目标驱动——模型只提需求不控方向：默认横向左滑/竖向向上滑——滑动前后 EXISTS 检查目标可见性——2 次无进展换反向——上限后仍不可见返回失败）。参数：target(必填,目标文本如'22:00')、container(选填,容器名)、max_swipes(选填,默认5,上限10)。"
}
