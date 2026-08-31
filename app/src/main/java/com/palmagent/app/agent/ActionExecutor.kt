package com.palmagent.app.agent

import android.graphics.Bitmap
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.model.AgentAction
import com.palmagent.app.model.QuestionAnswer
import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.service.ScreenChangeDetector
import com.palmagent.app.model.ScreenChangeType
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolRegistry
import com.palmagent.app.tool.ToolResult
import com.palmagent.app.tool.impl.ErrorClassifier
import com.palmagent.app.tool.impl.StepError
import com.palmagent.app.tool.impl.ToolExecutionException

import com.palmagent.app.utils.recycleSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 动作执行器
 *
 * 从 DefaultAgentService 中拆分，负责：
 * - GUI-Plus Grounding 定位
 * - 动作执行（按工具名分发到 ToolRegistry）
 * - 操作前后变化检测
 * - 剪贴板自动粘贴
 * - 用户中断处理
 */
class ActionExecutor @Inject constructor(
    private val screenDescriptor: ScreenDescriptor,
    val progressTracker: TaskProgressTracker,
    private val smartWait: SmartWaitStrategy
) {

    companion object {
        private const val TAG = "ActionExecutor"
        private const val POST_ACTION_DELAY_MS = 400L
        // 批量重复执行安全边界：次数上限、间隔范围与默认值
        private const val MAX_REPEAT_EXEC = 10
        private const val DEFAULT_REPEAT_INTERVAL_MS = 800L
        private const val MIN_REPEAT_INTERVAL_MS = 500L
        private const val MAX_REPEAT_INTERVAL_MS = 2000L
        // 支持批量重复的动作类型（点击/长按/滑动），其余类型忽略 repeat 强制单次
        private val repeatableTypes = setOf(
            "tap", "long_press", "swipe"
        )
    }

    /** 取消检查回调，由 DefaultAgentService 注入 */
    var isCancelled: () -> Boolean = { false }

    /** Scratchpad FORGET 回调，由 DefaultAgentService 注入 */
    var onScratchpadForget: ((String) -> Unit)? = null

    data class CaptureResult(
        val screenInfo: ScreenInfo?,
        val screenshotBmp: Bitmap?,
        val isTreeEmpty: Boolean,
        val accessibilityCheck: ScreenDescriptor.AccessibilityCheckResult? = null
    )

    /**
     * 截屏并获取无障碍信息
     */
    suspend fun captureScreen(): CaptureResult = coroutineScope {
        val screenAnalyzer = com.palmagent.app.service.ScreenAnalyzer(com.palmagent.app.AgentApplication.instance)
        var screenshotBmp = withContext(Dispatchers.IO) {
            screenAnalyzer.takeScreenshot()
        }
        // 主循环 shell 回退：无障碍截屏失败时，尝试 screencap 命令兜底
        if (screenshotBmp == null) {
            Log.w(TAG, "无障碍截屏失败，尝试 shell screencap 回退")
            screenshotBmp = withContext(Dispatchers.IO) { shellScreenshotFallback() }
            if (screenshotBmp != null) {
                Log.w(TAG, "shell screencap 回退成功")
                LiveLogBuffer.append("📸 shell 截屏回退成功")
            } else {
                Log.w(TAG, "⚠️ 截屏失败，本轮将无视觉信息（VLM/GUI-Plus 不可用）")
                LiveLogBuffer.append("⚠️ 截屏失败，本轮无视觉信息")
            }
        }
        // VL视觉模式：跳过无障碍树遍历，仅轻量获取包名（用于日志和actionHistory）
        if (com.palmagent.app.utils.KVUtils.isVisionModeEnabled()) {
            val root = withContext(Dispatchers.Main) {
                try { GUIAccessibilityService.instance?.rootInActiveWindow } catch (_: Exception) { null }
            }
            val pkg = root?.packageName?.toString()
            root?.recycle()
            Log.d(TAG, "VL模式：跳过无障碍树采集，仅获取包名=$pkg")
            val lightScreenInfo = pkg?.let {
                ScreenInfo(uiElements = emptyList(), currentPackage = it, currentActivity = null)
            }
            return@coroutineScope CaptureResult(lightScreenInfo, screenshotBmp, true, null)
        }
        // 文本模式：完整无障碍树遍历 + dataQuality计算
        val infoDeferred = async<ScreenInfo?>(Dispatchers.Main) {
            GUIAccessibilityService.instance?.getCurrentScreenInfo()
        }
        val screenInfo = infoDeferred.await()
        val checkResult = screenDescriptor.checkAccessibilityAvailability(screenInfo)
        Log.d(TAG, "无障碍检查: available=${checkResult.isAvailable}, " +
                "reason=${checkResult.reason}, " +
                "serviceHealthy=${checkResult.serviceHealthy}, " +
                "dataQuality=${(checkResult.dataQuality * 100).toInt()}%, " +
                "elementCount=${checkResult.elementCount}, " +
                "packageName=${checkResult.packageName}")
        CaptureResult(screenInfo, screenshotBmp, !checkResult.isAvailable, checkResult)
    }

    /**
     * shell screencap 兜底截屏（无障碍 API 失败时的最后防线）
     * 注意：screencap 需要 root 或 shell 权限，普通应用可能失败
     */
    private suspend fun shellScreenshotFallback(): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File.createTempFile("screenshot_fallback", ".png")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap ${file.absolutePath}"))
            if (process.waitFor() == 0) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                file.delete()
                bitmap
            } else {
                file.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "shell screencap 回退失败: ${e.message}")
            null
        }
    }

    /**
     * 执行动作（含变化检测）
     * v2 优化：pre/post 变化检测只用无障碍树，省 2-4s/轮
     */
    suspend fun executeWithChangeDetection(
        action: AgentAction,
        screenshotBmp: Bitmap?,
        screenInfo: ScreenInfo?,
        round: Int,
        userPrompt: String
    ): ToolResult {
        // v3.2 Bug-5 修复：ASK_USER 期间屏幕未变化，跳过操作后变化检测（节省 800ms + 一次截图）
        if (action.type == "ask_user") {
            return try {
                GUIAccessibilityService.instance?.setAgentActing(true)
                executeFinalAction(action, screenshotBmp, round)
            } finally {
                GUIAccessibilityService.instance?.setAgentActing(false)
                GUIAccessibilityService.instance?.markAgentAction()
            }
        }
        val result = try {
            GUIAccessibilityService.instance?.setAgentActing(true)

            val taskId = userPrompt.take(20).hashCode().toString() + "-" + round
            // VL视觉模式：跳过无障碍可用性检查，变化检测走图像哈希
            val hasA11y = if (com.palmagent.app.utils.KVUtils.isVisionModeEnabled()) {
                false
            } else {
                screenDescriptor.checkAccessibilityAvailability(screenInfo).isAvailable
            }
            // v2：pre-action 不做额外文字提取（无障碍可用直接用；不可用时留给 ScreenChangeDetector 走图像哈希）
            ScreenChangeDetector.savePreActionSnapshot(taskId, screenInfo, screenshotBmp)

            executeFinalAction(action, screenshotBmp, round)
        } finally {
            GUIAccessibilityService.instance?.setAgentActing(false)
            GUIAccessibilityService.instance?.markAgentAction()
        }

        // 操作后变化检测
        val postTaskId = userPrompt.take(20).hashCode().toString() + "-" + round
        try {
            delay(800)
            val postCapture = captureScreen()
            val postScreenInfo = postCapture.screenInfo
            val postScreenshot = postCapture.screenshotBmp

            // v2：post-action 不做额外文字提取（同上）
            val screenChange = ScreenChangeDetector.detectChange(
                postTaskId, postScreenInfo, postScreenshot
            )
            if (screenChange != null) {
                Log.d(TAG, "[界面变化] ${screenChange.description}")
                LiveLogBuffer.append("📊 界面变化: ${screenChange.description}")

                if (screenChange.changeType != ScreenChangeType.NO_CHANGE) {
                    screenDescriptor.updateLastScreenDescription("[操作结果反馈] ${screenChange.description}")
                }
            }

            postScreenshot.recycleSafely()
        } catch (e: Exception) {
            Log.w(TAG, "操作后变化检测失败: ${e.message}")
            ScreenChangeDetector.detectChange(postTaskId, screenInfo, screenshotBmp)
        }

        return result
    }

    /**
     * 操作后延迟 + 智能等待
     */
    suspend fun postActionDelayAndWait(actionType: String) {
        if (actionType == "finish") return

        // v3.2 Bug-7 修复：ASK_USER 期间屏幕未变化，跳过 delay 和 waitForPageStable
        // 用户回答后应立即继续任务，不应等待 1000ms + 页面稳定检测
        if (actionType == "ask_user") {
            GUIAccessibilityService.instance?.markAgentAction()
            return
        }

        if (actionType != "wait") {
            // 非 WAIT：先延迟（让动画/过渡完成），再稳定等待
            val delayMs = when (actionType) {
                "home" -> 500L
                "back" -> 500L
                // swipe 常用于滚动，滑动后需稍长等待界面稳定/惯性动画完成
                "swipe" -> 600L
                else -> POST_ACTION_DELAY_MS
            }
            delay(delayMs)
        }
        // WAIT 本身就是等待，无需额外 delay；但仍需 markAgentAction + 稳定等待
        // 否则 WAIT 后立即截屏会撞上 Surface 重组窗口（errorCode=3）
        GUIAccessibilityService.instance?.markAgentAction()
        smartWait.waitForPageStable()
    }

    /**
     * 执行最终动作
     */
    private suspend fun executeFinalAction(action: AgentAction, screenshotBmp: Bitmap?, round: Int = 0): ToolResult {
        when (action.type) {
            "request_user_action" -> return handleUserActionRequest(action)
            "visual_describe" -> return ToolResult.success("VISUAL_DESCRIBE已在决策引擎中处理")
            "ask_user" -> return handleAskUser(action)
            "forget" -> {
                val target = action.text ?: ""
                if (target.isBlank()) {
                    return ToolResult.error("FORGET: 需指定条目 ID 或关键词")
                }
                onScratchpadForget?.invoke(target)
                ToolResult.success("已删除工作记忆: $target")
            }
            else -> {}
        }

        var finalAction = action

        if (shouldUseGuiOwlGrounding(action) && screenshotBmp != null && !screenshotBmp.isRecycled) {
            val screenSize = getScreenSize()
            val instruction = buildGroundingInstruction(action)
            if (instruction.isNotBlank()) {
                val groundingResult = GuiOwlService.ground(instruction, screenshotBmp, screenSize.width, screenSize.height)
                AgentLogger.logGuiOwlGrounding(
                    instruction, groundingResult, round
                )
                if (groundingResult.success && groundingResult.coordinate != null) {
                    finalAction = action.copy(
                        coordinate = groundingResult.coordinate,
                        description = "${action.description} [GUI-Plus定位:(${groundingResult.coordinate.x},${groundingResult.coordinate.y})]"
                    )
                    Log.d(TAG, "GUI-Plus Grounding成功: (${groundingResult.coordinate.x},${groundingResult.coordinate.y})")
                    LiveLogBuffer.append("🎯 GUI-Plus定位成功: (${groundingResult.coordinate.x},${groundingResult.coordinate.y}) ${groundingResult.durationMs}ms")
                } else {
                    Log.d(TAG, "GUI-Plus Grounding未启用或失败，使用原始坐标")
                }
            }
        }

        val toolName = finalAction.type

        val params = if (isSwipeLike(finalAction.type)) {
            buildSwipeParams(finalAction)
        } else {
            buildActionParams(finalAction).mapValues { it.value ?: "" as Any }
        }

        // 真机调试观测点：打印"协议参数名 → 执行参数名"映射结果，便于核对模型输出字段是否被正确转译
        Log.d(TAG, "tool_params[${toolName}] modelText=${finalAction.text?.take(40)} mapped=$params")

        val tool = ToolRegistry.getTool(toolName)
            ?: run {
                val allTools = ToolRegistry.getAllTools().map { it.getName() }.toMutableList().apply {
                    addAll(listOf("fetch_result", "forget", "request_user_action", "ask_user"))
                }
                return ToolResult.error("未知工具 '$toolName'，可用工具: ${allTools.joinToString(", ")}")
            }

        // 批量重复执行：仅对可重复类型生效（TAP/CLICK/LONG_PRESS/SCROLL_*），其余类型强制单次
        val repeatCount = if (finalAction.type in repeatableTypes) {
            finalAction.repeat.coerceIn(1, MAX_REPEAT_EXEC)
        } else 1
        if (repeatCount <= 1) {
            return executeToolWithFallback(tool, params)
        }

        // 循环执行同一动作 N 次：每次间隔 interval_ms（默认 800ms，clamp 500-2000ms）
        val intervalMs = finalAction.intervalMs?.coerceIn(MIN_REPEAT_INTERVAL_MS, MAX_REPEAT_INTERVAL_MS)
            ?: DEFAULT_REPEAT_INTERVAL_MS
        LiveLogBuffer.append("🔁 批量执行 ${finalAction.type} × $repeatCount（间隔 ${intervalMs}ms）")
        var lastResult: ToolResult = ToolResult.success("")
        for (i in 1..repeatCount) {
            if (i > 1) {
                delay(intervalMs)
                GUIAccessibilityService.instance?.markAgentAction()
            }
            lastResult = executeToolWithFallback(tool, params)
            if (!lastResult.isSuccess) {
                Log.w(TAG, "批量执行第 $i/$repeatCount 次失败: ${lastResult.error}")
                LiveLogBuffer.append("⚠️ 批量执行第 $i/$repeatCount 次失败: ${lastResult.error}，提前终止")
                return ToolResult.error("批量执行到第 $i/$repeatCount 次失败: ${lastResult.error}")
            }
            Log.d(TAG, "批量执行第 $i/$repeatCount 次成功")
        }
        return lastResult
    }

    /**
     * 工具执行兜底（方案 A）：工具正常返回则透传；
     * 工具抛异常时用 ErrorClassifier 分类为结构化错误信封，避免原始异常冒泡。
     */
    private suspend fun executeToolWithFallback(tool: BaseTool, params: Map<String, Any>): ToolResult {
        return try {
            tool.executeWithWaitAfter(params)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val stepError = ErrorClassifier.classify(e)
            val errorType = when (stepError) {
                is StepError.Transient -> "TRANSIENT"
                is StepError.Fatal -> "FATAL"
                is StepError.Validation -> "VALIDATION"
            }
            val retriable = stepError is StepError.Transient
            val errorMsg = if (e is ToolExecutionException) {
                e.errorMessage
            } else {
                e.message ?: "工具执行异常"
            }
            Log.w(TAG, "工具执行异常(${tool.getName()}): $errorMsg")
            LiveLogBuffer.append("⚠️ 工具执行异常: ${errorMsg.take(60)}")
            ToolResult.error(
                error = errorMsg,
                errorType = errorType,
                code = "TOOL_EXECUTION_EXCEPTION",
                retriable = retriable
            )
        }
    }

    private fun shouldUseGuiOwlGrounding(action: AgentAction): Boolean {
        if (!GuiOwlService.isReady) return false
        if (action.type != "tap" && action.type != "long_press") return false

        val screenSize = getScreenSize()
        val hasValidCoordinate = action.coordinate != null &&
            action.coordinate.x > 0 && action.coordinate.y > 0 &&
            action.coordinate.x < screenSize.width && action.coordinate.y < screenSize.height

        if (hasValidCoordinate) return false

        val hasTargetWithBounds = action.targetElement != null &&
            (action.targetElement.bounds.right - action.targetElement.bounds.left > 0 ||
             action.targetElement.bounds.bottom - action.targetElement.bounds.top > 0)

        if (hasTargetWithBounds) return false

        return true
    }

    private fun buildGroundingInstruction(action: AgentAction): String {
        val target = action.targetId ?: action.description ?: ""
        if (target.isBlank()) return ""
        return "Click the $target"
    }

    private fun isSwipeLike(type: String): Boolean = type == "swipe"

    private fun buildSwipeParams(action: AgentAction): Map<String, Any> {
        val screenSize = getScreenSize()
        val screenW = screenSize.width
        val screenH = screenSize.height

        // 起点默认：有 coordinate 用之，否则屏幕中部（滚动惯例）
        val startX = action.coordinate?.x ?: (screenW / 2)
        val startY = action.coordinate?.y ?: (screenH / 2)

        val params = mutableMapOf<String, Any>()
        action.durationMs?.let { params["duration_ms"] = it }

        // 显式 direction（模型/ActionParser 提供）→ 优先方向滚动模式（direction 传给 SwipeTool 计算起终点）
        val direction = action.direction?.lowercase()
            ?.takeIf { it in setOf("up", "down", "left", "right", "custom") }
        if (direction != null && direction != "custom") {
            params["direction"] = direction
            params["start_x"] = startX
            params["start_y"] = startY
            action.distance?.let { params["distance"] = it }
            return params
        }

        // custom 模式（direction=custom 或旧坐标格式）：必须显式提供 coordinate_end 终点，
        // 不提供则不推算终点，交由 SwipeTool 校验报错（严格：swipe 必须显式给 direction 或 end_x/end_y）
        if (direction == "custom" || action.coordinateEnd != null) {
            params["direction"] = "custom"
            params["start_x"] = startX
            params["start_y"] = startY
            action.coordinateEnd?.let { end ->
                params["end_x"] = end.x
                params["end_y"] = end.y
            }
            return params
        }

        // 未提供 direction 且未提供终点：不推算，交由 SwipeTool 校验报错
        return params
    }

    private fun buildActionParams(action: AgentAction): Map<String, Any?> {
        val params = mutableMapOf<String, Any?>()
        val screenSize = getScreenSize()
        val screenW = screenSize.width
        val screenH = screenSize.height

        val hasValidCoord = action.coordinate != null &&
            action.coordinate.x in 0 until screenW &&
            action.coordinate.y in 0 until screenH

        if (hasValidCoord) {
            params["x"] = action.coordinate!!.x
            params["y"] = action.coordinate!!.y
        } else if (action.targetElement != null) {
            val bounds = action.targetElement.bounds
            params["x"] = (bounds.left + bounds.right) / 2
            params["y"] = (bounds.top + bounds.bottom) / 2
        } else if (action.coordinate != null) {
            val edgeSafe = maxOf(screenW / 100, 24) // 安全区钳制（比例1%+下限24px——全面屏手势区避让——业界做法）
            params["x"] = action.coordinate.x.coerceIn(edgeSafe, screenW - 1 - edgeSafe)
            params["y"] = action.coordinate.y.coerceIn(edgeSafe, screenH - 1 - edgeSafe)
        }
        action.text?.let { params["text"] = it }
        action.targetId?.let { params["target_id"] = it }
        action.targetDesc?.let { params["target_desc"] = it }
        action.actionDesc?.let { params["action_desc"] = it }

        if (action.type == "auto_input") {
            action.instruction?.let { params["instruction"] = it }
        }

        // ============ v7：新增 3 个工具的参数映射 ============
        // OPEN_APP: text → app_name（主）, description → app_name（兜底）
        if (action.type == "open_app") {
            val appName = action.text?.takeIf { it.isNotBlank() } ?: action.description?.takeIf { it.isNotBlank() }
            appName?.let { params["app_name"] = it }
        }

        // LOCATE: description → tool 入参 description（AI 元素描述）, text → text
        if (action.type == "locate") {
            action.text?.let { params["text"] = it }
            val desc = action.description?.takeIf { it.isNotBlank() } ?: action.targetDesc?.takeIf { it.isNotBlank() }
            desc?.let { params["description"] = it }
        }

        // REQUEST_USER_ACTION: text → title（必填）, description → steps（选填）
        if (action.type == "request_user_action") {
            val title = action.text?.takeIf { it.isNotBlank() } ?: action.description?.takeIf { it.isNotBlank() }
            title?.let { params["title"] = it }
            val steps = action.description?.takeIf { it.isNotBlank() } ?: action.text?.takeIf { it.isNotBlank() }
            steps?.let { params["steps"] = it }
        }

        // FINISH: description → summary, text → next_action
        if (action.type == "finish") {
            action.description?.takeIf { it.isNotBlank() }?.let { params["summary"] = it }
            action.text?.takeIf { it.isNotBlank() }?.let { params["next_action"] = it }
        }

        // WAIT: duration_ms → tool duration_ms（clamp 100-10000ms 防止卡死）
        if (action.type == "wait") {
            val durationMs = action.durationMs ?: 1000L
            params["duration_ms"] = durationMs.coerceIn(100L, 10_000L)
        }

        // SELECT_SPEC: specs → specs（需选取的规格列表）, confirmText → confirm_text（确认按钮文本）
        if (action.type == "select_spec") {
            action.specs?.takeIf { it.isNotEmpty() }?.let { params["specs"] = it }
            action.confirmText?.takeIf { it.isNotBlank() }?.let { params["confirm_text"] = it }
        }

        // WEB_SEARCH: query → query（主取 query 协议字段；text 兜底兼容旧描述格式）, mode → mode（web/ai，默认web）
        if (action.type == "web_search") {
            val searchQuery = action.query?.takeIf { it.isNotBlank() }
                ?: action.text?.takeIf { it.isNotBlank() }
            searchQuery?.let { params["query"] = it }
            action.mode?.takeIf { it in setOf("web", "ai") }?.let { params["mode"] = it }
        }

        // SCROLL_UNTIL: target → target（主取 targetId：ActionParser 将 JSON target 落入 AgentAction.targetId；
        // text 兜底兼容旧格式）, direction → direction（同 swip direction 模式）
        if (action.type == "scroll_until") {
            val target = action.targetId?.takeIf { it.isNotBlank() }
                ?: action.text?.takeIf { it.isNotBlank() }
                ?: action.targetDesc?.takeIf { it.isNotBlank() }
            target?.let { params["target"] = it }
            action.direction?.let { params["direction"] = it }
            action.maxScrolls?.let { params["max_scrolls"] = it }
            action.intervalMs?.let { params["interval_ms"] = it }
            action.clickOnFound?.let { params["click_on_found"] = it }
        }

        // SWIPE_UNTIL: target → target（复用 targetId——与 scroll_until 同字段）、container → container（选填）、max_swipes → max_swipes
        if (action.type == "swipe_until") {
            action.targetId?.takeIf { it.isNotBlank() }?.let { params["target"] = it }
            action.container?.takeIf { it.isNotBlank() }?.let { params["container"] = it }
            action.maxSwipes?.let { params["max_swipes"] = it }
        }
        // =================================================

        return params
    }


    private suspend fun handleUserActionRequest(action: AgentAction): ToolResult {
        val guideText = action.description.ifBlank { "模型需要您进行手动操作" }
        AgentLogger.log(AgentLogger.LogType.DECISION, "请求用户手动操作: $guideText")

        return try {
            val completed = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                com.palmagent.app.floating.FloatingProgressManager.showUserGuide(
                    text = guideText,
                    onDone = {
                        if (cont.isActive) cont.resumeWith(Result.success(true))
                    },
                    onRejected = {
                        if (cont.isActive) cont.resumeWith(Result.success(false))
                    }
                )
                cont.invokeOnCancellation {
                    com.palmagent.app.floating.FloatingProgressManager.hideUserGuide()
                }
            }
            if (!completed) {
                ToolResult.error("用户拒绝了操作")
            } else {
                ToolResult.success(collectPostUserActionSnapshot(guideText))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            com.palmagent.app.floating.FloatingProgressManager.hideUserGuide()
            throw e
        } catch (e: Exception) {
            com.palmagent.app.floating.FloatingProgressManager.hideUserGuide()
            ToolResult.error("等待用户操作时出错: ${e.message}")
        }
    }

    /**
     * 执行模型 ASK_USER 批量追问工具（仅简单模式）
     * Layer 3：复杂模式兜底 + 同步等待用户批量回答（5 分钟整体超时）
     * 截图暂停/复用由 DefaultAgentService 主循环负责
     */
    private suspend fun handleAskUser(action: AgentAction): ToolResult {
        // Layer 3：复杂模式兜底（PromptBuilder/ActionParser 已过滤，此处再次确认）
        if (com.palmagent.app.utils.KVUtils.isComplexModeEnabled()) {
            return ToolResult.error("复杂模式不支持 ask_user")
        }

        val questions = action.questions?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.error("ASK_USER 缺少 questions 字段")

        Log.d(TAG, "ASK_USER 批量提问: ${questions.size} 个问题")
        LiveLogBuffer.append("❓ 模型批量提问: ${questions.size} 个问题")

        return try {
            // 5 分钟整体超时，避免无限等待
            val answers = kotlinx.coroutines.withTimeoutOrNull(5 * 60 * 1000L) {
                kotlinx.coroutines.suspendCancellableCoroutine<List<QuestionAnswer>?> { cont ->
                    com.palmagent.app.floating.AskUserManager.requestAnswer(
                        req = com.palmagent.app.floating.AskUserManager.AskRequest(
                            questions = questions
                        ),
                        onResult = { response ->
                            if (cont.isActive) {
                                if (response.cancelled) {
                                    cont.resumeWith(Result.success(null))
                                } else {
                                    cont.resumeWith(Result.success(response.answers))
                                }
                            }
                        }
                    )
                    cont.invokeOnCancellation {
                        com.palmagent.app.floating.AskUserManager.cancel()
                    }
                }
            }

            when {
                answers.isNullOrEmpty() -> {
                    Log.w(TAG, "ASK_USER 用户取消或超时")
                    ToolResult.error("用户取消了追问或超时未回答")
                }
                else -> {
                    // 拼接多问多答摘要，单问答案上限 200 字符
                    val summary = answers.joinToString(" | ") { qa ->
                        val ansStr = qa.answer.joinToString(",")
                        "${qa.question.take(30)}=${ansStr.take(200)}"
                    }
                    Log.d(TAG, "ASK_USER 用户回答: ${summary.take(200)}")
                    LiveLogBuffer.append("💬 用户回答: ${summary.take(200)}")
                    ToolResult.success("用户回答：$summary")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            com.palmagent.app.floating.AskUserManager.cancel()
            throw e
        } catch (e: Exception) {
            com.palmagent.app.floating.AskUserManager.cancel()
            ToolResult.error("等待用户回答时出错: ${e.message}")
        }
    }

    private suspend fun collectPostUserActionSnapshot(guideText: String): String {
        try {
            delay(600)
            val screenInfo = withContext(Dispatchers.Main) {
                GUIAccessibilityService.instance?.getCurrentScreenInfo()
            }
            val pkg = screenInfo?.currentPackage ?: "未知"
            val elementCount = screenInfo?.uiElements?.size ?: 0
            AgentLogger.log(AgentLogger.LogType.SYSTEM, "用户操作完成，当前界面: $pkg, $elementCount 个元素")
            return buildString {
                appendLine("用户已完成操作: $guideText")
                appendLine("操作后界面: $pkg, UI元素: $elementCount")
            }
        } catch (e: Exception) {
            AgentLogger.log(AgentLogger.LogType.ERROR, "用户操作后快照采集失败: ${e.message}")
            return "用户已完成操作: $guideText"
        }
    }

    private fun getScreenSize(): GuiOwlService.ScreenSize {
        val metrics = android.util.DisplayMetrics()
        val wm = com.palmagent.app.AgentApplication.instance
            .getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        return GuiOwlService.ScreenSize(metrics.widthPixels, metrics.heightPixels)
    }
}
