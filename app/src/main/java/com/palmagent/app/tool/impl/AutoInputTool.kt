package com.palmagent.app.tool.impl

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.palmagent.app.AgentApplication
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.service.KeyboardVisionDetector
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import com.palmagent.app.utils.recycleSafely
import kotlinx.coroutines.delay

/**
 * 自动化输入工具
 *
 * 双模式执行：无障碍快捷流程（~1s） / 视觉降级流程（13-25s）
 *
 * ── 无障碍可用 → 快捷流程 ─────────────────────────────────┐
 * │ A步: 定位目标元素（isTextInputBox≠null 时）                      │
 * │     findNodesByText → findNodesByDesc → GUI-Plus兜底        │
 * │ B步: 查找输入框节点                                        │
 * │     findFocus(FOCUS_INPUT) → findEditableNode             │
 * │ C步: 输入文本                                              │
 * │     ACTION_SET_TEXT → 剪贴板+ACTION_PASTE（降级）          │
 * │ D步: 自动点击按钮（默认执行）                              │
 * │     优先级：搜索/Search → 发送/Send                       │
 * │     无障碍（严格匹配+Y-proximity） → Grounding             │
 * └──────────────────────────────────────────────────────────┘
 *
 * ┌─ 无障碍不可用 → 视觉流程（6步） ────────────────────────┐
 * │ 0. GUI-Plus定位目标元素并点击（可选）                       │
 * │ 1. 复制文本到剪贴板                                      │
 * │ 2. 键盘检测（视觉验证）                                  │
 * │ 3. GUI-Plus定位输入框（3步回退）→ 点击 → 键盘验证          │
 * │ 4. 长按输入框                                            │
 * │ 5. GUI定位粘贴按钮并点击 → 无障碍 ACTION_PASTE（降级）       │
 * │ 6. 自动点击按钮（默认执行）                              │
 * │    优先级：搜索/Search → 发送/Send                       │
 * │    无障碍（严格匹配+Y-proximity） → Grounding             │
 * └──────────────────────────────────────────────────────────┘
 *
 * D步/第6步的按钮匹配规则：
 * - 严格文本匹配（equals，忽略大小写），'联网搜索'/'重新发送' 等子串不命中
 * - 多匹配时按 Y-proximity 选择：选 centerY 距离输入框 Y 最近的；无 inputY 时回退到选最下方
 * - 全部失败时返回成功+警告（输入已完成，不阻断 auto_input 流程）
 */
class AutoInputTool : BaseTool() {

    companion object {
        private const val TAG = "AutoInput"
        private const val LONG_PRESS_DURATION = 600L
        private const val WAIT_AFTER_CLICK_INPUT = 1000L
        private const val WAIT_AFTER_LONG_PRESS = 1800L   // 长按后等粘贴菜单动画渲染完成再截屏
        private const val MAX_KEYBOARD_RETRY = 3

        private const val LOWER_THIRD_THRESHOLD = 2f / 3f

        /** 第5步粘贴按钮 GUI 定位的最大尝试次数（含首次；未命中时回退点击输入框重新触发菜单） */
        private const val MAX_PASTE_RETRY = 3
        /** 第5步回退点击输入框后，等待上下文菜单弹出的间隔 */
        private const val MENU_POPUP_DELAY = 800L

        /**
         * 输入完成后默认尝试点击的按钮关键词（顺序敏感）：
         * 第一优先：搜索 / Search
         * 第二优先：发送 / Send
         * 严格 equals 匹配（忽略大小写），'联网搜索'/'重新发送' 不命中
         */
        private val BUTTON_KEYWORDS = listOf("搜索", "Search", "发送", "Send")
    }

    private data class InputState(
        var keyboardDetected: Boolean = false,
        var inputX: Int = 0,
        var inputY: Int = 0,
        var needInputLocate: Boolean = true
    )

    override fun getName(): String = "auto_input"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("text", "string", "Text to input (will be copied to clipboard and pasted)", true),
        ToolParameter("is_text_input_box", "string",
            "Optional: true=click the text input box, false=click the search icon. " +
            "If omitted, skip locating and go directly to input.", false)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val text = requireString(params, "text")
        if (text.isBlank()) return ToolResult.error("输入文本不能为空")

        // isTextInputBox：true=文本输入框，false=搜索图标（PromptBuilder 锁死二选一），null=跳过定位
        val isTextInputBox = params["is_text_input_box"]?.toString()?.trim()?.lowercase()
            ?.let { if (it == "true") true else if (it == "false") false else null }

        Log.d(TAG, "开始自动化输入: '$text'" +
                (if (isTextInputBox != null) ", 目标: ${if (isTextInputBox) "文本输入框" else "搜索图标"}" else ""))
        LiveLogBuffer.append("⌨️ 自动输入: '$text'" +
                (if (isTextInputBox != null) " → ${if (isTextInputBox) "文本输入框" else "搜索图标"}" else ""))

        // 检测无障碍服务可用性
        val a11yService = getA11yService()
        val a11yAvailable = a11yService != null && try {
            a11yService.rootInActiveWindow != null
        } catch (_: Exception) { false }

        return if (a11yAvailable && a11yService != null) {
            Log.d(TAG, "无障碍服务可用，使用快捷流程")
            LiveLogBuffer.append("  🚀 无障碍快捷模式")
            executeWithAccessibility(a11yService, text, isTextInputBox)
        } else {
            Log.d(TAG, "无障碍服务不可用，使用视觉流程")
            LiveLogBuffer.append("  👁️ 视觉降级模式")
            executeWithVision(text, isTextInputBox)
        }
    }

    // ======================== 无障碍快捷流程 ========================

    /**
     * 无障碍快捷流程：ACTION_SET_TEXT 直接输入，无需截图/GUI-Plus
     */
    private suspend fun executeWithAccessibility(
        service: GUIAccessibilityService,
        text: String,
        isTextInputBox: Boolean?
    ): ToolResult {
        // [A步] 定位+点击目标元素（true=文本输入框 / false=搜索图标）
        if (isTextInputBox != null) {
            // 定位前先检测键盘：键盘已弹出说明输入框已聚焦，跳过定位直接输入
            val kbScreenshot: Bitmap? = takeScreenshot()
            val keyboardVisible = if (kbScreenshot != null) {
                try {
                    val kbResult = KeyboardVisionDetector.detectKeyboard(kbScreenshot)
                    kbResult.keyboardVisible
                } catch (e: Exception) {
                    Log.w(TAG, "键盘预检异常: ${e.message}")
                    false
                } finally {
                    kbScreenshot.recycleSafely()
                }
            } else {
                false
            }

            if (keyboardVisible) {
                Log.d(TAG, "键盘已弹出，跳过A步定位，直接输入文字")
                LiveLogBuffer.append("  ⏭️ 键盘已弹出，跳过定位直接输入")
            } else {
                // 极简定位：按 isTextInputBox 生成定位描述，GUI-Plus 定位并点击
                val state = InputState()
                val groundResult = step0LocateAndClick(isTextInputBox, state)
                if (groundResult.isFailure) {
                    return ToolResult.error("定位目标元素失败: ${groundResult.exceptionOrNull()?.message}")
                }
                delay(500)
            }
        }

        // [B步] 查找输入框节点
        val inputNode = service.findEditableNode(null)
        if (inputNode == null) {
            Log.w(TAG, "无障碍未找到可编辑节点，降级到视觉流程")
            LiveLogBuffer.append("  ⚠️ 无障碍未找到输入框，降级到视觉流程")
            // 保留 instruction：丢失会导致视觉流程 step4 用通用英文提示词
            // 重新定位输入框，在非搜索页面上会定位到错误位置（如商品列表）
            return executeWithVision(text, isTextInputBox)
        }

        // 捕获输入框 Y 坐标（用于 D 步按钮的 Y-proximity）
        var inputY = 0
        try {
            val inputBounds = Rect()
            inputNode.getBoundsInScreen(inputBounds)
            inputY = inputBounds.centerY()
        } catch (_: Exception) {
            // 忽略，inputY 保持 0
        }

        try {
            // [C步] 输入文本
            val inputResult = a11yInputText(service, inputNode, text)
            if (!inputResult) {
                Log.w(TAG, "无障碍输入失败，降级到视觉流程")
                LiveLogBuffer.append("  ⚠️ 无障碍输入失败，降级到视觉流程")
                // 保留 instruction：同上，避免视觉流程定位输入框失败
                return executeWithVision(text, isTextInputBox)
            }

            delay(500)

            // [D步] 默认自动点击按钮（搜索→发送），失败时返回成功+警告
            val dResult = stepAutoClickButton(service, inputY)
            if (dResult.isFailure) {
                val failedKeywords = dResult.exceptionOrNull()?.message ?: "未找到可点击按钮"
                Log.w(TAG, "D步失败: $failedKeywords")
                LiveLogBuffer.append("  ⚠️ D步: $failedKeywords")
                return ToolResult.success("输入完成: '$text'，但未找到可点击的按钮（搜索/发送）")
            }

            Log.d(TAG, "无障碍输入完成: '$text' + 自动点击按钮")
            LiveLogBuffer.append("🏁 无障碍输入完成: '$text' + 自动点击按钮")
            return ToolResult.success("无障碍输入完成: '$text'，已自动点击按钮")
        } finally {
            try { inputNode.recycle() } catch (_: Exception) {}
        }
    }

    /**
     * 无障碍输入文本
     * 优先级：ACTION_SET_TEXT → 剪贴板+ACTION_PASTE
     */
    private suspend fun a11yInputText(
        service: GUIAccessibilityService,
        node: AccessibilityNodeInfo,
        text: String
    ): Boolean {
        // 1. ACTION_SET_TEXT（最快，不污染剪贴板）
        val setSuccess = service.performClearAndSetText(node, text)
        if (setSuccess) {
            Log.v(TAG, "C步: ACTION_SET_TEXT成功")
            LiveLogBuffer.append("  ✅ C步: ACTION_SET_TEXT输入 '$text'")
            return true
        }

        // 2. 降级到剪贴板+ACTION_PASTE
        Log.w(TAG, "ACTION_SET_TEXT失败，降级到剪贴板粘贴")
        if (!copyToClipboard(text)) return false

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        delay(100)

        // 全选已有内容后粘贴
        val selectArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                   node.text?.length ?: 0)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectArgs)
        delay(50)

        val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasteSuccess) {
            Log.d(TAG, "C步: 剪贴板粘贴成功")
            LiveLogBuffer.append("  ✅ C步: 剪贴板粘贴输入 '$text'")
        } else {
            Log.w(TAG, "剪贴板粘贴也失败")
        }
        return pasteSuccess
    }

    // ======================== 视觉降级流程 ========================

    /**
     * 视觉流程：6步，截图+GUI-Plus，无障碍不可用时的降级方案
     */
    private suspend fun executeWithVision(
        text: String,
        isTextInputBox: Boolean?
    ): ToolResult {
        val screenSize = getScreenSize()
        val screenWidth = screenSize[0]
        val screenHeight = screenSize[1]

        val state = InputState()

        // ====== 第0步：GUI-Plus定位目标元素并点击（可选） ======
        if (isTextInputBox != null) {
            step0LocateAndClick(isTextInputBox, state).let { result ->
                if (result.isFailure) return ToolResult.error(result.exceptionOrNull()?.message ?: "第0步失败")
            }
        }

        // ====== 第1步：复制文本到剪贴板 ======
        step1CopyToClipboard(text).let { result ->
            if (result.isFailure) return ToolResult.error(result.exceptionOrNull()?.message ?: "第1步失败")
        }

        // ====== 第2步：键盘检测（视觉验证，仅第0步未执行时） ======
        step2DetectKeyboard(state)

        // ====== 第3步：GUI-Plus定位输入框 → 点击 → 键盘验证+定位检测（3步回退） ======
        if (state.needInputLocate && !state.keyboardDetected) {
            step3LocateInputField(state, isTextInputBox).let { result ->
                if (result.isFailure) return ToolResult.error(result.exceptionOrNull()?.message ?: "第3步失败")
            }
        }

        // ====== 第4步：长按输入框 ======
        step4LongPress(state, isTextInputBox).let { result ->
            if (result.isFailure) return ToolResult.error(result.exceptionOrNull()?.message ?: "第4步失败")
        }

        // ====== 第5步：GUI定位粘贴按钮（未命中时回退点击输入框重试）→ 无障碍ACTION_PASTE降级 ======
        step5PasteWithRetry(state).let { result ->
            if (result.isFailure) {
                // GUI多次未找到粘贴按钮时，尝试通过无障碍服务 ACTION_PASTE 降级
                val a11yFallback = step5A11yPasteFallback(text)
                if (a11yFallback.isFailure) {
                    return ToolResult.error(result.exceptionOrNull()?.message ?: "第5步失败")
                }
            }
        }

        delay(1000L)

        // ====== 第6步：默认自动点击按钮（搜索→发送），失败时返回成功+警告 ======
        val s6Result = stepAutoClickButton(getA11yService(), state.inputY)
        if (s6Result.isFailure) {
            val failedKeywords = s6Result.exceptionOrNull()?.message ?: "未找到可点击按钮"
            Log.w(TAG, "第6步失败: $failedKeywords")
            LiveLogBuffer.append("  ⚠️ 步骤6: $failedKeywords")
            return ToolResult.success("输入完成: '$text'，但未找到可点击的按钮（搜索/发送）")
        }

        Log.d(TAG, "视觉流程输入完成: '$text' + 自动点击按钮")
        LiveLogBuffer.append("🏁 视觉流程输入完成: '$text' + 自动点击按钮")

        return ToolResult.success("自动化输入完成: '$text'，已自动点击按钮")
    }

    // ======================== 步骤函数 ========================

    /**
     * 第0步：GUI-Plus定位目标元素并点击
     *
     * 统一使用全屏 ground()（与 LocateTool 一致），不做客户端裁剪/缩放：
     * 1) 服务端会按 MAX_PIXELS 统一 resize，客户端提前缩到 80% 收益极小
     * 2) 客户端 scale 未传递给服务端会导致归一化坐标按错误比例映射到屏幕坐标
     *    （如搜索图标模式 (959,786) 的事故），全屏模式下由服务端用 screen_width/height
     *    直接完成精确映射
     */
    private suspend fun step0LocateAndClick(isTextInputBox: Boolean?, state: InputState): Result<Unit> {
        if (!GuiOwlService.isReady) {
            return Result.failure(IllegalStateException("第0步失败: GUI-Plus服务未就绪，无法定位目标元素"))
        }

        val groundScreenshot: Bitmap? = takeScreenshot()
        if (groundScreenshot == null) {
            return Result.failure(IllegalStateException("第0步失败: 无法获取屏幕截图"))
        }

        try {
            val screenSize = getScreenSize()
            val screenWidth = screenSize[0]
            val screenHeight = screenSize[1]

            val modeDesc = "通用"
            Log.d(TAG, "第0步: $modeDesc 模式，使用全屏GUI-Plus定位")
            // 方案1：定位描述具体化（业界做法：类型+位置+可见文本），避免 GUI-Plus 把
            // 占位文本当可点击文字元素定位到错误目标（如"路线"按钮而非输入框）
            val groundInstruction = buildGroundInstruction(isTextInputBox)
            val groundResult = GuiOwlService.ground(
                groundInstruction, groundScreenshot, screenWidth, screenHeight
            )

            if (!groundResult.success || groundResult.coordinate == null) {
                return Result.failure(IllegalStateException("第0步失败: 未找到目标元素 - ${groundResult.error ?: "未返回坐标"}"))
            }

            val clickX = groundResult.coordinate.x
            val clickY = groundResult.coordinate.y
            validateCoordinates(clickX, clickY)?.let { return Result.failure(IllegalStateException("第0步失败: $it")) }

            // 点击目标元素
            performClick(clickX, clickY)
            Log.v(TAG, "第0步: GUI-Plus定位+点击目标 ($clickX, $clickY) [$modeDesc]")
            LiveLogBuffer.append("  📍 步骤0: 定位+点击目标 ($clickX, $clickY) [$modeDesc]")
            delay(WAIT_AFTER_CLICK_INPUT)

            // 键盘检测+定位检测
            if (GuiOwlService.isReady) {
                val kbScreenshot: Bitmap? = takeScreenshot()
                if (kbScreenshot != null) {
                    try {
                        val kbResult = KeyboardVisionDetector.detectKeyboard(kbScreenshot)
                        Log.v(TAG, "第0步键盘检测: visible=${kbResult.keyboardVisible}, " +
                                "type=${kbResult.keyboardType}, conf=${kbResult.confidence}, " +
                                "${kbResult.durationMs}ms")

                        if (kbResult.keyboardVisible) {
                            // 定位检测：Y > 屏幕下1/3 → 可能定位错误，需要重新定位输入框
                            if (clickY > screenHeight * LOWER_THIRD_THRESHOLD) {
                                Log.w(TAG, "第0步定位检测: 点击位置Y=$clickY > 屏幕下1/3(${(screenHeight * LOWER_THIRD_THRESHOLD).toInt()}), 需要重新定位输入框")
                                LiveLogBuffer.append("  ⚠️ 步骤0: 键盘已弹出但点击位置在下1/3，需重新定位输入框 (${kbResult.durationMs}ms)")
                                // 不设置keyboardDetected，让第3步重新定位
                            } else {
                                state.keyboardDetected = true
                                state.inputX = clickX
                                state.inputY = clickY
                                state.needInputLocate = false
                                Log.v(TAG, "第0步完成: 键盘已弹出，输入框已聚焦")
                                LiveLogBuffer.append("  ✅ 步骤0: 键盘已弹出，跳过输入框定位 (${kbResult.durationMs}ms)")
                            }
                        } else {
                            Log.v(TAG, "第0步: 键盘未弹出，需要定位输入框")
                            LiveLogBuffer.append("  ⚠️ 步骤0: 键盘未弹出，需定位输入框 (${kbResult.durationMs}ms)")
                        }
                    } finally {
                        kbScreenshot.recycleSafely()
                    }
                } else {
                    Log.w(TAG, "第0步: 无法截屏进行键盘检测，假定成功")
                    state.keyboardDetected = true
                    state.inputX = clickX
                    state.inputY = clickY
                    state.needInputLocate = false
                }
            } else {
                state.keyboardDetected = true
                state.inputX = clickX
                state.inputY = clickY
                state.needInputLocate = false
            }
        } finally {
            groundScreenshot.recycleSafely()
        }

        return Result.success(Unit)
    }

    /** 极简定位描述：true=请点击文本输入框，false=请点击搜索图标 */
    private fun buildGroundInstruction(isTextInputBox: Boolean?): String {
        return if (isTextInputBox == false) "请点击搜索图标" else "请点击文本输入框"
    }

    /**
     * 第1步：复制文本到剪贴板
     */
    private fun step1CopyToClipboard(text: String): Result<Unit> {
        val clipboardOk = copyToClipboard(text)
        if (!clipboardOk) {
            return Result.failure(IllegalStateException("第1步失败: 复制到剪贴板失败"))
        }
        Log.d(TAG, "第1步完成: 已复制到剪贴板")
        LiveLogBuffer.append("  ✅ 步骤1: 复制到剪贴板")
        return Result.success(Unit)
    }

    /**
     * 第2步：键盘检测（视觉验证，仅第0步未执行时）
     */
    private suspend fun step2DetectKeyboard(state: InputState) {
        if (!state.keyboardDetected && state.needInputLocate) {
            if (GuiOwlService.isReady) {
                val kbScreenshot: Bitmap? = takeScreenshot()
                if (kbScreenshot != null) {
                    try {
                        val kbResult = KeyboardVisionDetector.detectKeyboard(kbScreenshot)
                        Log.d(TAG, "第2步键盘检测: visible=${kbResult.keyboardVisible}, " +
                                "type=${kbResult.keyboardType}, conf=${kbResult.confidence}, " +
                                "${kbResult.durationMs}ms")

                        if (kbResult.keyboardVisible) {
                            state.keyboardDetected = true
                            state.needInputLocate = false
                            Log.d(TAG, "第2步完成: 键盘已弹出，输入框已聚焦，跳过定位点击")
                            LiveLogBuffer.append("  ✅ 步骤2: 键盘已弹出，跳过定位点击 (${kbResult.durationMs}ms)")
                        } else {
                            Log.d(TAG, "第2步完成: 键盘未弹出，需要定位输入框")
                            LiveLogBuffer.append("  ⚠️ 步骤2: 键盘未弹出，需要定位输入框 (${kbResult.durationMs}ms)")
                        }
                    } finally {
                        kbScreenshot.recycleSafely()
                    }
                } else {
                    Log.w(TAG, "第2步: 无法截屏进行键盘检测，跳过验证")
                    LiveLogBuffer.append("  ⚠️ 步骤2: 无法截屏，跳过键盘检测")
                }
            } else {
                Log.w(TAG, "第2步: GUI-Plus不可用，跳过键盘检测")
                LiveLogBuffer.append("  ⚠️ 步骤2: GUI-Plus不可用，跳过键盘检测")
            }
        }
    }

    /**
     * 第3步：GUI-Plus定位输入框 → 点击 → 键盘验证（3次重试）
     *
     * 统一使用全屏 ground()，不再做"全屏→上2/3→下2/3"裁剪策略，原因同 step0：
     * 客户端裁剪+80%缩放既无明显速度收益（服务端会 resize），又引入了客户端
     * scale 字段未传服务端的坐标映射 bug。3 次重试仅用于应对键盘未弹出的瞬时失败。
     *
     * 定位指令：isTextInputBox 布尔语义经 buildGroundInstruction 生成两句描述（输入框/搜索图标）；
     */
    private suspend fun step3LocateInputField(state: InputState, isTextInputBox: Boolean?): Result<Unit> {
        if (!GuiOwlService.isReady) {
            return Result.failure(IllegalStateException("第3步失败: GUI-Plus服务未就绪，无法定位输入框"))
        }

        val screenSize = getScreenSize()
        val screenWidth = screenSize[0]
        val screenHeight = screenSize[1]
        // 定位指令：isTextInputBox 为布尔语义（true=输入框/false=搜索图标），经 buildGroundInstruction 生成描述
        val locateInstruction = if (isTextInputBox != null) {
            buildGroundInstruction(isTextInputBox)
        } else {
            "text input field, search bar, or chat input box"
        }
        for (attempt in 1..MAX_KEYBOARD_RETRY) {
            val locateScreenshot: Bitmap? = takeScreenshot()
            if (locateScreenshot == null) {
                return Result.failure(IllegalStateException("第3步失败: 无法获取屏幕截图"))
            }

            val inputCoord = try {
                val result = GuiOwlService.ground(
                    locateInstruction,
                    locateScreenshot, screenWidth, screenHeight
                )

                if (!result.success || result.coordinate == null) {
                    throw NoSuchElementException("GUI-Plus未找到输入框 - ${result.error ?: "未返回坐标"}")
                }

                Log.d(TAG, "输入框定位(全屏): GUI-Plus坐标(${result.coordinate.x},${result.coordinate.y})")
                result.coordinate
            } catch (e: NoSuchElementException) {
                if (attempt == MAX_KEYBOARD_RETRY) {
                    locateScreenshot.recycleSafely()
                    return Result.failure(IllegalStateException("第3步失败: 未找到输入框 - ${e.message}"))
                }
                Log.w(TAG, "第3步: 未找到输入框，重试 (尝试$attempt/$MAX_KEYBOARD_RETRY)")
                LiveLogBuffer.append("  ⚠️ 步骤3: 未找到输入框，重试 (尝试$attempt/$MAX_KEYBOARD_RETRY)")
                locateScreenshot.recycleSafely()
                delay(500)
                continue
            } finally {
                locateScreenshot.recycleSafely()
            }

            state.inputX = inputCoord.x
            state.inputY = inputCoord.y
            validateCoordinates(state.inputX, state.inputY)?.let {
                return Result.failure(IllegalStateException("第3步失败: $it"))
            }

            performClick(state.inputX, state.inputY)
            Log.d(TAG, "第3步: GUI-Plus定位+点击输入框 (${state.inputX}, ${state.inputY}), 尝试$attempt/$MAX_KEYBOARD_RETRY")
            LiveLogBuffer.append("  📍 步骤3: 定位+点击输入框 (${state.inputX}, ${state.inputY}) [尝试$attempt]")
            delay(WAIT_AFTER_CLICK_INPUT)

            if (GuiOwlService.isReady) {
                val kbScreenshot: Bitmap? = takeScreenshot()
                if (kbScreenshot != null) {
                    try {
                        val kbResult = KeyboardVisionDetector.detectKeyboard(kbScreenshot)
                        Log.d(TAG, "第3步键盘检测: visible=${kbResult.keyboardVisible}, " +
                                "type=${kbResult.keyboardType}, conf=${kbResult.confidence}, " +
                                "${kbResult.durationMs}ms")

                        if (kbResult.keyboardVisible) {
                            state.keyboardDetected = true
                            LiveLogBuffer.append("  ✅ 步骤3: 点击后键盘已弹出 [尝试$attempt, ${kbResult.durationMs}ms]")
                            break
                        } else {
                            Log.w(TAG, "键盘未弹出 (尝试$attempt/$MAX_KEYBOARD_RETRY), conf=${kbResult.confidence}")
                            if (attempt < MAX_KEYBOARD_RETRY) {
                                LiveLogBuffer.append("  ⚠️ 步骤3: 点击后键盘未弹出，重试 (尝试$attempt/$MAX_KEYBOARD_RETRY)")
                            } else {
                                LiveLogBuffer.append("  ❌ 步骤3: ${MAX_KEYBOARD_RETRY}次定位均未弹出键盘，返回失败")
                            }
                        }
                    } finally {
                        kbScreenshot.recycleSafely()
                    }
                } else {
                    Log.w(TAG, "无法截屏进行键盘检测，假定成功")
                    state.keyboardDetected = true
                    break
                }
            } else {
                state.keyboardDetected = true
                break
            }
        }

        if (!state.keyboardDetected) {
            return Result.failure(IllegalStateException("第3步失败: ${MAX_KEYBOARD_RETRY}次定位输入框均未弹出键盘，无法完成输入"))
        }

        return Result.success(Unit)
    }

    /**
     * 第4步：长按输入框
     *
     * 键盘已弹出的分支：键盘检测完成后才走到这里，输入框坐标已由 step0/step3 设置。
     * 键盘未弹出的分支（仅 step0 跳过 step3 场景）：用全屏 ground() 定位后长按。
     */
    private suspend fun step4LongPress(state: InputState, isTextInputBox: Boolean?): Result<Unit> {
        // 长按前必须用 GUI 重新定位输入框：键盘弹出后布局可能变化（输入框被键盘顶起），
        // 且 state 坐标可能来自 step0 点击的目标元素（搜索图标）而非输入框，不能复用旧坐标
        if (!GuiOwlService.isReady) {
            return Result.failure(IllegalStateException("第4步失败: GUI-Plus服务未就绪，无法定位输入框进行长按"))
        }
        val locateScreenshot: Bitmap? = takeScreenshot()
        if (locateScreenshot == null) return Result.failure(IllegalStateException("第4步失败: 无法获取屏幕截图"))

        // 定位指令：键盘已弹出时输入框被顶起且被压缩（占位文字消失、仅剩光标），
        // 用"屏幕上方的文本输入框"描述（不提及键盘，避免模型误定位到键盘候选词栏）；
        // 键盘未弹出时按 isTextInputBox 布尔语义生成常规描述
        val locateInstruction = if (state.keyboardDetected) {
            "点击屏幕上方的文本输入框，用于输入文字"
        } else if (isTextInputBox != null) {
            buildGroundInstruction(isTextInputBox)
        } else {
            "text input field, search bar, or chat input box"
        }
        val inputCoord = try {
            val screenSize = getScreenSize()
            val result = GuiOwlService.ground(
                locateInstruction,
                locateScreenshot, screenSize[0], screenSize[1]
            )
            if (!result.success || result.coordinate == null) {
                throw NoSuchElementException("GUI-Plus未找到输入框 - ${result.error ?: "未返回坐标"}")
            }
            result.coordinate
        } catch (e: NoSuchElementException) {
            return Result.failure(IllegalStateException("第4步失败: ${e.message}"))
        } finally {
            locateScreenshot.recycleSafely()
        }
        state.inputX = inputCoord.x
        state.inputY = inputCoord.y
        validateCoordinates(state.inputX, state.inputY)?.let {
            return Result.failure(IllegalStateException("第4步失败: $it"))
        }

        val longPressOk = performLongPress(state.inputX, state.inputY, LONG_PRESS_DURATION)
        if (!longPressOk) {
            return Result.failure(IllegalStateException("第4步失败: 长按输入框失败"))
        }
        delay(WAIT_AFTER_LONG_PRESS)
        Log.d(TAG, "第4步完成: 长按输入框 (${state.inputX}, ${state.inputY}), 键盘=${if(state.keyboardDetected) "已弹出" else "未弹出"}")
        LiveLogBuffer.append("  ✅ 步骤4: 长按输入框 (键盘${if(state.keyboardDetected) "已弹出" else "未弹出"})")

        return Result.success(Unit)
    }

    /**
     * 第5步：GUI 定位粘贴按钮并点击；未命中时回退点击输入框重新触发菜单
     *
     * 粘贴按钮定位：GUI Grounding 优先（多语言提示词 粘贴/Paste/貼上，适配不同语言 App 长按菜单）。
     * GUI 未识别到（通常是上下文菜单未弹出/长按未生效）时，回退点击输入框
     * （坐标由第4步长按时保存）触发菜单重现，再重新定位；最多 MAX_PASTE_RETRY 次；
     * 仍失败返回失败，由调用点降级到无障碍 ACTION_PASTE。
     */
    private suspend fun step5PasteWithRetry(state: InputState): Result<Unit> {
        for (attempt in 1..MAX_PASTE_RETRY) {
            // GUI 定位（多语言提示词：粘贴/Paste/貼上）
            val groundResult = step5GroundingPaste()
            if (groundResult.isSuccess) {
                Log.d(TAG, "第5步完成: GUI定位粘贴按钮成功（尝试$attempt/$MAX_PASTE_RETRY）")
                return Result.success(Unit)
            }

            if (attempt >= MAX_PASTE_RETRY) {
                Log.w(TAG, "第5步失败: GUI ${MAX_PASTE_RETRY}次未找到粘贴按钮")
                return Result.failure(IllegalStateException("第5步失败: GUI ${MAX_PASTE_RETRY}次未找到粘贴按钮"))
            }

            // 回退：重新长按输入框，触发上下文菜单重新弹出（输入框坐标由第4步保存）
            // 注意：回退必须重新长按（点击无法弹出粘贴菜单；键盘已确认弹出），等菜单动画后再定位
            if (state.inputX == 0 && state.inputY == 0) {
                return Result.failure(IllegalStateException("第5步失败: 输入框坐标未知，无法回退长按"))
            }
            Log.w(TAG, "第5步: GUI未找到粘贴（第${attempt}次），回退重新长按输入框(${state.inputX},${state.inputY})重新触发菜单")
            LiveLogBuffer.append("  ⚠️ 步骤5: GUI未找到粘贴，回退重新长按输入框(第${attempt}次)")
            if (!performLongPress(state.inputX, state.inputY, LONG_PRESS_DURATION)) {
                return Result.failure(IllegalStateException("第5步失败: 回退长按输入框失败"))
            }
            delay(MENU_POPUP_DELAY)
        }
        return Result.failure(IllegalStateException("第5步失败: GUI未找到粘贴按钮"))
    }

    /**
     * 第5步：GUI-Plus 定位粘贴按钮
     */
    private suspend fun step5GroundingPaste(): Result<Unit> {
        if (!GuiOwlService.isReady) {
            return Result.failure(IllegalStateException("第5步失败: GUI-Plus不可用，无法定位粘贴按钮"))
        }

        val pasteScreenshot: Bitmap? = takeScreenshot()
        if (pasteScreenshot == null) return Result.failure(IllegalStateException("第5步失败: 无法获取屏幕截图"))

        try {
            val screenSize = getScreenSize()
            // 多语言提示词：适配中文（粘贴）/繁体（貼上）/英文（Paste）等不同语言 App 弹出的上下文菜单
            // 强调"返回该粘贴选项正中心、不要点其他菜单项/相邻元素"，避免模型偶发点中剪切/复制/全选等邻近项
            val groundResult = GuiOwlService.ground(
                "点击长按输入框后弹出的上下文菜单/浮动工具栏中的\"粘贴\"按钮。粘贴按钮是菜单或工具栏里的一个小选项（文字通常为\"粘贴\"，繁体\"貼上\"，或英文\"Paste\"，也可能是一个带剪贴板/粘贴图标的按钮），与\"剪切/复制/粘贴/全选\"等选项排成一行或一列。请返回该\"粘贴\"选项的正中心坐标：不要点输入框，不要点其他菜单项（剪切/复制/全选等），不要点空白处。",
                pasteScreenshot, screenSize[0], screenSize[1]
            )

            if (!groundResult.success || groundResult.coordinate == null) {
                Log.w(TAG, "第5步Grounding失败: ${groundResult.error}")
                return Result.failure(IllegalStateException("第5步失败: GUI-Plus未找到粘贴按钮"))
            }

            val (x, y) = groundResult.coordinate
            val clicked = performClick(x, y)
            if (!clicked) {
                return Result.failure(IllegalStateException("第5步失败: 点击粘贴按钮失败"))
            }

            Log.d(TAG, "第5步完成: Grounding定位粘贴按钮 ($x, $y)")
            LiveLogBuffer.append("  ✅ 步骤5: Grounding定位粘贴 ($x, $y)")
            return Result.success(Unit)
        } finally {
            pasteScreenshot.recycleSafely()
        }
    }

    /**
     * 第5步降级：GUI 粘贴失败时，通过无障碍服务 ACTION_PASTE 粘贴
     */
    private fun step5A11yPasteFallback(text: String): Result<Unit> {
        val service = GUIAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "第5步降级: 无障碍服务不可用，无法执行ACTION_PASTE")
            return Result.failure(IllegalStateException("无障碍服务不可用"))
        }

        val root = try { service.rootInActiveWindow } catch (_: Exception) { null }
        if (root == null) {
            Log.w(TAG, "第5步降级: 无法获取无障碍根节点")
            return Result.failure(IllegalStateException("无法获取无障碍根节点"))
        }

        // 查找当前聚焦的可编辑节点
        val inputNode = service.findEditableNode(null)
        if (inputNode == null) {
            Log.w(TAG, "第5步降级: 未找到可编辑节点")
            return Result.failure(IllegalStateException("未找到可编辑节点"))
        }

        try {
            // 验证剪贴板内容
            val clipboard = android.content.ClipboardManager::class.java
            val cm = AgentApplication.instance.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clipText = cm?.primaryClip?.getItemAt(0)?.text?.toString()
            if (clipText != text) {
                Log.w(TAG, "第5步降级: 剪贴板内容不匹配(期望='$text', 实际='$clipText')")
                return Result.failure(IllegalStateException("剪贴板内容不匹配"))
            }

            // 执行 ACTION_PASTE
            val pasted = inputNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasted) {
                Log.w(TAG, "第5步降级: ACTION_PASTE 执行失败")
                return Result.failure(IllegalStateException("ACTION_PASTE执行失败"))
            }

            Log.d(TAG, "第5步降级成功: 通过无障碍ACTION_PASTE粘贴文本")
            LiveLogBuffer.append("  ✅ 步骤5降级: 无障碍ACTION_PASTE粘贴成功")
            return Result.success(Unit)
        } finally {
            inputNode.recycle()
        }
    }

    // ======================== D步/第6步：默认自动点按钮 ========================

    /**
     * D步/第6步：默认自动点按钮（无任何参数控制）
     *
     * 关键词优先级：BUTTON_KEYWORDS = [搜索, Search, 发送, Send]
     * 每个关键词的查找顺序：
     *   1. 无障碍服务（严格 equals 匹配 + Y-proximity 选最合适）
     *   2. Grounding 视觉定位（搜索/发送按钮）
     *
     * 多匹配选择规则：
     * - inputY > 0 → 选 centerY 距离 inputY 最近的（Y-proximity）
     * - inputY <= 0 → 选 centerY 最大的（兜底：最下方）
     *
     * 全部失败时返回失败（外层返回 ToolResult.success 提示警告，不阻断 auto_input）。
     */
    private suspend fun stepAutoClickButton(
        service: GUIAccessibilityService?,
        inputY: Int
    ): Result<Unit> {
        for (keyword in BUTTON_KEYWORDS) {
            // 1. 无障碍优先
            if (service != null) {
                val a11yCoord = findClickableButtonByA11y(service, keyword, inputY)
                if (a11yCoord != null) {
                    val (x, y) = a11yCoord
                    val clicked = performClick(x, y)
                    if (clicked) {
                        val proximityInfo = if (inputY > 0) " [Y-proximity, inputY=$inputY]" else " [最下方]"
                        Log.d(TAG, "D步: 无障碍点击'$keyword' ($x, $y)$proximityInfo")
                        LiveLogBuffer.append("  ✅ D步: 无障碍点击'$keyword' ($x, $y)$proximityInfo")
                        return Result.success(Unit)
                    }
                }
            }

            // 2. Grounding 视觉定位
            val groundCoord = findClickableButtonByGrounding(keyword)
            if (groundCoord != null) {
                val (x, y) = groundCoord
                val clicked = performClick(x, y)
                if (clicked) {
                    val proximityInfo = if (inputY > 0) " [Y-proximity, inputY=$inputY]" else " [最下方]"
                    Log.d(TAG, "D步: Grounding点击'$keyword' ($x, $y)$proximityInfo")
                    LiveLogBuffer.append("  ✅ D步: Grounding点击'$keyword' ($x, $y)$proximityInfo")
                    return Result.success(Unit)
                }
            }
        }
        return Result.failure(IllegalStateException("未找到任何可点击按钮（${BUTTON_KEYWORDS.joinToString("/")}）"))
    }

    /**
     * 无障碍方式查找按钮（严格 equals 匹配 + Y-proximity 选最合适）
     * 返回 (centerX, centerY) 或 null
     */
    private fun findClickableButtonByA11y(
        service: GUIAccessibilityService,
        buttonText: String,
        inputY: Int
    ): Pair<Int, Int>? {
        data class Candidate(val node: AccessibilityNodeInfo, val centerY: Int, val rect: Rect)
        val candidates = mutableListOf<Candidate>()

        // 1. 按文本查找（findNodesByText 是子串匹配，必须后置严格过滤）
        try {
            val textNodes = service.findNodesByText(buttonText)
            for (node in textNodes) {
                val nodeText = node.text?.toString()
                if (node.isClickable && nodeText.equals(buttonText, ignoreCase = true)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    candidates.add(Candidate(node, bounds.centerY(), bounds))
                } else {
                    try { node.recycle() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "D步无障碍: findNodesByText异常: ${e.message}")
        }

        // 2. 按 contentDescription 查找（也是子串匹配，必须后置严格过滤）
        try {
            val descNodes = service.findNodesByDesc(buttonText)
            for (node in descNodes) {
                val descText = node.contentDescription?.toString()
                if (node.isClickable && descText.equals(buttonText, ignoreCase = true)) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    candidates.add(Candidate(node, bounds.centerY(), bounds))
                } else {
                    try { node.recycle() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "D步无障碍: findNodesByDesc异常: ${e.message}")
        }

        if (candidates.isEmpty()) return null

        // 选最合适的：Y-proximity 优先，无 inputY 时回退到最下方
        val best = if (inputY > 0) {
            candidates.minBy { Math.abs(it.centerY - inputY) }
        } else {
            candidates.maxBy { it.centerY }
        }

        val clickX = best.rect.centerX()
        val clickY = best.rect.centerY()

        // 回收所有候选节点
        for (c in candidates) {
            try { c.node.recycle() } catch (_: Exception) {}
        }

        return Pair(clickX, clickY)
    }

    /**
     * Grounding 方式查找按钮，失败时返回 null
     * 返回 (centerX, centerY) 或 null
     */
    private suspend fun findClickableButtonByGrounding(
        buttonText: String
    ): Pair<Int, Int>? {
        if (!GuiOwlService.isReady) return null

        val screenshot: Bitmap? = takeScreenshot()
        if (screenshot == null) return null

        try {
            val screenSize = getScreenSize()
            val instruction = if (buttonText.equals("搜索", ignoreCase = true) ||
                buttonText.equals("Search", ignoreCase = true)) {
                "搜索按钮，位于输入框右侧或键盘上方"
            } else {
                "${buttonText}按钮，位于输入框右侧或键盘上方"
            }
            val groundResult = GuiOwlService.ground(
                instruction, screenshot, screenSize[0], screenSize[1]
            )

            if (!groundResult.success || groundResult.coordinate == null) return null
            return Pair(groundResult.coordinate.x, groundResult.coordinate.y)
        } finally {
            screenshot.recycleSafely()
        }
    }

    // ======================== 辅助方法 ========================

    private fun copyToClipboard(text: String): Boolean {
        return try {
            val context = com.palmagent.app.AgentApplication.instance
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("agent_input", text))
            true
        } catch (e: Exception) { Log.e(TAG, "剪贴板复制失败: ${e.message}"); false }
    }

    override fun getDescriptionEN(): String =
        "Automated text input with dual-mode execution. " +
        "After input, automatically clicks search/send button (search first, then send, with strict text matching and Y-proximity to input field). " +
        "When accessibility service is available: fast path using ACTION_SET_TEXT directly on editable nodes " +
        "(findNodesByText/Desc for targeting, findEditableNode for input, ACTION_SET_TEXT for text entry). " +
        "When accessibility is unavailable: visual fallback with GUI-Plus " +
        "(0) GUI-Plus locate+click target → (1) clipboard copy → (2) keyboard detect → " +
        "(3) GUI-Plus locate input (3-step fallback) → (4) long press → (5) GUI-Plus paste → " +
        "(6) auto click search/send button."

    override fun getDescriptionCN(): String =
        "自动化文本输入，双模式执行。输入完成后自动按'搜索→Search→发送→Send'顺序点击按钮（严格 equals 匹配，'联网搜索'/'重新发送' 不命中；多匹配时按 Y-proximity 选最接近输入框的）。" +
        "无障碍可用时：快捷流程，ACTION_SET_TEXT直接输入。" +
        "无障碍不可用时：视觉降级流程，GUI-Plus " +
        "(0) GUI-Plus定位+点击目标 → (1) 复制到剪贴板 → (2) 键盘检测 → " +
        "(3) GUI-Plus定位输入框(3步回退) → (4) 长按 → (5) GUI-Plus定位粘贴 → " +
        "(6) 自动点击搜索/发送按钮。"
}
