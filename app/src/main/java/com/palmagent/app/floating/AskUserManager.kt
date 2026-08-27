package com.palmagent.app.floating

import android.util.Log
import com.palmagent.app.channel.TaskChannelHolder
import com.palmagent.app.channel.wechat.WeChatDecisionRouter
import com.palmagent.app.model.Question
import com.palmagent.app.model.QuestionAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 批量提问管理器
 *
 * 协调悬浮窗 ASK_USER 状态，管理执行模型向用户的批量追问流程。
 * 一次请求可展示 1-4 个问题，每问含选项按钮 + 自定义输入，用户一次性提交所有回答。
 *
 * 通道适配（v4.0）：根据消息来源自动切换交互逻辑：
 * - LOCAL：走原有本地交互逻辑（悬浮窗弹窗、问题卡组件）
 * - WECHAT：走微信文本消息交互逻辑（通过微信发送问题、接收用户文本回复）
 *
 * P0 教训：新请求必须 cancel 前一个，避免回调丢失。
 */
object AskUserManager {

    private const val TAG = "AskUserManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class AskRequest(
        val questions: List<Question>  // 批量问题数组（1-4 个）
    )

    data class AskResponse(
        val answers: List<QuestionAnswer>,  // 与 AskRequest.questions 一一对应
        val cancelled: Boolean
    )

    @Volatile
    private var currentCallback: ((AskResponse) -> Unit)? = null

    /**
     * 当前追问请求（供 FloatingProgressManager 读取问题和选项）
     */
    @Volatile
    var currentRequest: AskRequest? = null

    /**
     * 发起批量追问请求，根据消息来源自动切换交互逻辑
     *
     * - LOCAL：显示悬浮窗 ASK_USER 面板（原有逻辑）
     * - WECHAT：通过微信文本消息发送问题，等待用户文本回复
     */
    fun requestAnswer(req: AskRequest, onResult: (AskResponse) -> Unit) {
        // 微信通道：走微信文本消息交互
        if (TaskChannelHolder.isWeChat()) {
            val router = WeChatDecisionRouter.instance
            if (router != null) {
                scope.launch {
                    try {
                        val answers = router.askUserViaWeChat(req.questions)
                        onResult(AskResponse(answers = answers, cancelled = answers.isEmpty()))
                    } catch (e: Exception) {
                        Log.e(TAG, "微信 askUser 异常: ${e.message}")
                        onResult(AskResponse(answers = emptyList(), cancelled = true))
                    }
                }
                return
            }
            Log.w(TAG, "微信通道但 WeChatDecisionRouter 未初始化，回退到本地交互")
        }

        // 本地通道：原有悬浮窗逻辑
        synchronized(this) {
            cancel() // P0: 必须通知前一个调用方
            currentRequest = req
            currentCallback = onResult
        }
        FloatingProgressManager.showAskUserBanner(req)
    }

    /**
     * 用户批量提交回答（来自悬浮窗多问题卡）
     */
    fun onUserAnswer(answers: List<QuestionAnswer>) {
        handleResult(AskResponse(answers, cancelled = false))
    }

    /**
     * 用户主动取消（点击取消按钮）
     */
    fun onUserCancel() {
        handleResult(AskResponse(emptyList(), cancelled = true))
    }

    /**
     * 外部取消（任务被取消/超时/新请求抢占）
     */
    fun cancel() {
        handleResult(AskResponse(emptyList(), cancelled = true))
    }

    private fun handleResult(resp: AskResponse) {
        synchronized(this) {
            val cb = currentCallback
            currentCallback = null
            currentRequest = null
            if (cb == null) return@synchronized
            FloatingProgressManager.hideAskUserBanner()
            try {
                cb.invoke(resp)
            } catch (e: Exception) {
                Log.w(TAG, "回调异常: ${e.message}")
            }
        }
    }
}
