package com.palmagent.app.floating

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.palmagent.app.AgentApplication
import com.palmagent.app.channel.TaskChannelHolder
import com.palmagent.app.channel.wechat.WeChatDecisionRouter
import com.palmagent.app.floating.UserActionManager.UserActionMode
import com.palmagent.app.floating.UserActionManager.UserActionRequest
import com.palmagent.app.floating.UserActionManager.UserActionResult
import com.palmagent.app.floating.UserActionManager.UserActionResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 用户操作管理器
 *
 * 协调悬浮窗面板、系统通知，统一管理所有需要用户介入的场景。
 * 无超时限制，等待用户操作直到用户主动完成/跳过/取消。
 *
 * 通道适配（v4.0）：根据消息来源自动切换交互逻辑：
 * - LOCAL：走原有本地交互逻辑（悬浮窗弹窗、系统通知）
 * - WECHAT：走微信文本消息交互逻辑（通过微信发送操作指引、接收用户文本回复）
 */
object UserActionManager {

    private const val TAG = "UserActionManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 数据结构定义
    enum class UserActionMode {
        CONFIRM,    // 需要用户确认完成
        ALERT       // 仅提示，无需确认（如权限提示）
    }

    enum class UserActionResult {
        DONE,       // 用户完成操作
        SKIP,       // 用户跳过
        CANCEL      // 用户取消任务
    }

    data class UserActionRequest(
        val title: String,
        val steps: List<String> = emptyList(),
        val mode: UserActionMode = UserActionMode.CONFIRM,
        val allowSkip: Boolean = true
    )

    data class UserActionResponse(
        val action: UserActionResult,
        val elapsedSeconds: Long
    )

    private var currentRequest: UserActionRequest? = null
    private var currentCallback: ((UserActionResponse) -> Unit)? = null
    private var startTime: Long = 0
    private var isExpanded = false

    /**
     * 请求用户进行手动操作，根据消息来源自动切换交互逻辑
     *
     * - LOCAL：显示悬浮窗操作面板 + 系统通知（原有逻辑）
     * - WECHAT：通过微信文本消息发送操作指引，等待用户文本回复
     */
    fun requestUserAction(request: UserActionRequest, onResult: (UserActionResponse) -> Unit) {
        // 微信通道：走微信文本消息交互
        if (TaskChannelHolder.isWeChat()) {
            val router = WeChatDecisionRouter.instance
            if (router != null) {
                startTime = System.currentTimeMillis()
                scope.launch {
                    try {
                        val guideText = buildString {
                            append(request.title)
                            if (request.steps.isNotEmpty()) {
                                append("\n")
                                request.steps.forEachIndexed { i, step ->
                                    append("${i + 1}. $step\n")
                                }
                            }
                        }
                        val completed = router.requestUserActionViaWeChat(guideText)
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000
                        val result = if (completed) UserActionResult.DONE else UserActionResult.CANCEL
                        onResult(UserActionResponse(result, elapsed))
                    } catch (e: Exception) {
                        Log.e(TAG, "微信 requestUserAction 异常: ${e.message}")
                        onResult(UserActionResponse(UserActionResult.CANCEL, 0))
                    }
                }
                return
            }
            Log.w(TAG, "微信通道但 WeChatDecisionRouter 未初始化，回退到本地交互")
        }

        // 本地通道：原有悬浮窗逻辑
        cancel() // 取消之前的请求
        currentRequest = request
        currentCallback = onResult
        startTime = System.currentTimeMillis()
        isExpanded = false

        Log.d(TAG, "请求用户操作: ${request.title}")

        // 1. 显示悬浮窗操作面板
        FloatingProgressManager.showUserActionPanel(request)

        // 2. 发送系统通知
        try {
            UserGuideNotifier.showNotification(request)
        } catch (e: Exception) {
            Log.w(TAG, "发送通知失败: ${e.message}")
        }

        // 3. 触觉反馈
        vibrate()
    }

    /**
     * 用户确认完成操作
     */
    fun onUserDone() {
        handleResult(UserActionResult.DONE)
    }

    /**
     * 用户跳过操作
     */
    fun onUserSkip() {
        handleResult(UserActionResult.SKIP)
    }

    /**
     * 用户取消操作
     */
    fun onUserCancel() {
        handleResult(UserActionResult.CANCEL)
    }

    /**
     * 切换展开/收起状态
     */
    fun toggleExpand() {
        val request = currentRequest ?: return
        isExpanded = !isExpanded
        FloatingProgressManager.refreshUserActionPanel()
    }

    /**
     * 取消当前请求
     * P0修复：必须通知调用方，否则 suspendCancellableCoroutine 永远等不到结果
     */
    fun cancel() {
        // P0关键修复：通知调用方操作已取消
        val callback = currentCallback
        currentCallback = null
        currentRequest = null
        isExpanded = false

        try {
            FloatingProgressManager.hideUserActionPanel()
        } catch (_: Exception) {}
        try {
            UserGuideNotifier.cancelNotification()
        } catch (_: Exception) {}

        callback?.invoke(UserActionResponse(UserActionResult.CANCEL, 0))
    }

    /**
     * 当前是否有活跃的用户操作请求
     */
    fun hasActiveRequest(): Boolean = currentRequest != null

    /**
     * 获取当前请求（供FloatingProgressManager使用）
     */
    fun getCurrentRequest(): UserActionRequest? = currentRequest

    /**
     * 是否处于展开状态
     */
    fun isExpandedState(): Boolean = isExpanded

    private fun handleResult(action: UserActionResult) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        val callback = currentCallback
        currentCallback = null
        currentRequest = null
        isExpanded = false

        try {
            FloatingProgressManager.hideUserActionPanel()
        } catch (_: Exception) {}
        try {
            UserGuideNotifier.cancelNotification()
        } catch (_: Exception) {}

        callback?.invoke(UserActionResponse(action, elapsed))
    }

    /**
     * P1：触觉反馈
     * Banner 出现时短震动提醒
     */
    private fun vibrate() {
        try {
            val app = AgentApplication.instance
            val vibrator = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        } catch (_: Exception) {}
    }
}
