package com.palmagent.app

import android.util.Log
import com.palmagent.app.agent.AgentCallback
import com.palmagent.app.agent.AgentConfig
import com.palmagent.app.agent.AgentService
import com.palmagent.app.agent.AgentServiceFactory
import com.palmagent.app.agent.Plan
import com.palmagent.app.floating.FloatingProgressManager
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.service.TtsManager
import com.palmagent.app.channel.Channel
import com.palmagent.app.channel.ChannelManager
import com.palmagent.app.tool.ToolResult
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskOrchestrator @Inject constructor(
    private val agentServiceFactory: AgentServiceFactory
) {
    /** TTS 语音播报实例（由 Application 初始化后注入） */
    @Volatile
    var ttsManager: TtsManager? = null

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    interface TaskStateListener {
        fun onTaskStateChanged(running: Boolean)
        // 新增：模型内容推送（isFinal=true 表示任务结束的最终答案）
        fun onTaskContent(content: String, isFinal: Boolean) {}
        // 新增：任务开始通知（携带原始指令文本，供 UI 显示"开始执行：xxx"）
        fun onTaskStart(command: String) {}
        // 新增：执行模型确认任务真正完成——携带执行摘要，由上层（HomeActivity）调决策模型 reportResult 生成完成报告
        fun onExecutionFinished(executionSummary: String) {}
    }

    private val taskLock = Any()
    @Volatile
    private var currentChannel: Channel? = null
    @Volatile
    private var currentMessageID: String? = null

    val activeChannel: Channel?
        get() = currentChannel
    @Volatile
    private var isCancelled = false

    @Volatile
    private var agentService: AgentService? = null

    @Volatile
    var taskStateListener: TaskStateListener? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    fun tryAcquireTask(messageID: String, channel: Channel): Boolean {
        synchronized(taskLock) {
            if (currentChannel != null && !isCancelled) return false
            currentChannel = channel
            currentMessageID = messageID
            isCancelled = false
            return true
        }
    }

    /**
     * 判断是否应通过微信汇报任务结果
     * v3.1: 仅当"任务来自微信渠道"且"微信已连接（已绑定）"时才通过微信汇报
     */
    internal fun shouldReportToWeChat(channel: Channel): Boolean {
        return channel == Channel.WECHAT && ChannelManager.isConnected(Channel.WECHAT)
    }

    fun releaseTask() {
        synchronized(taskLock) {
            currentChannel = null
            currentMessageID = null
            isCancelled = false
        }
    }

    fun isHoldingTask(messageID: String): Boolean {
        synchronized(taskLock) { return currentMessageID == messageID && !isCancelled }
    }

    fun tryCancel(messageID: String): Boolean {
        synchronized(taskLock) {
            return if (currentMessageID == messageID) {
                agentService?.cancel()
                isCancelled = true
                FloatingProgressManager.setIdleState()
                val channel = currentChannel
                if (channel != null) {
                    scope.launch {
                        try {
                            if (shouldReportToWeChat(channel)) {
                                ChannelManager.sendMessage(channel, "任务已被手动取消", messageID)
                                ChannelManager.flushMessages(channel)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "取消通知发送失败: ${e.message}")
                        }
                    }
                }
                LiveLogBuffer.append("⏹ 任务已被手动取消")
                releaseTask()
                taskStateListener?.onTaskStateChanged(false)
                true
            } else false
        }
    }

    fun getChannel(): Channel? = currentChannel

    fun isTaskRunning(): Boolean = agentService?.isRunning == true

    fun cancelCurrentTask() {
        val channel = currentChannel
        val msgId = currentMessageID
        agentService?.cancel()
        FloatingProgressManager.setIdleState()
        if (channel != null && msgId != null) {
            scope.launch {
                try {
                    if (shouldReportToWeChat(channel)) {
                        ChannelManager.sendMessage(channel, "任务已被手动取消", msgId)
                        ChannelManager.flushMessages(channel)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "取消通知发送失败: ${e.message}")
                }
            }
        }
        LiveLogBuffer.append("⏹ 任务已被手动取消")
        releaseTask()
        taskStateListener?.onTaskStateChanged(false)
    }

    fun startNewTask(channel: Channel, task: String, messageID: String, plan: Plan? = null) {
        scope.launch {
            try {
                if (channel != Channel.LOCAL) {
                    FloatingProgressManager.enterMinimizedMode()
                } else {
                    // v3.2 Bug-5 修复：LOCAL 渠道用轻量执行中状态替代跳过，保持悬浮窗可见但有反馈
                    FloatingProgressManager.setExecutingState()
                }
                taskStateListener?.onTaskStart(task)
                val previousService = agentService
                if (previousService?.isRunning == true) previousService.cancel()

                val config = AgentConfig.Builder()
                    .apiKey(KVUtils.getLlmApiKey())
                    .baseUrl(KVUtils.getLlmBaseUrl())
                    .modelName(KVUtils.getLlmModelName())
                    .userPrompt(task)
                    .build()
                val callback = createAgentCallback(channel, messageID)

                val service = agentServiceFactory.create()
                agentService = service
                taskStateListener?.onTaskStateChanged(true)

                GUIAccessibilityService.instance?.markAgentAction()
                // 复杂模式（决策模型已产出 plan）任务启动前自动回到桌面：
                // 确保执行模型的首轮截图/视觉描述从干净桌面开始，而非停留在决策对话或上一个应用界面
                if (plan != null) {
                    val a11y = GUIAccessibilityService.instance
                    if (a11y != null) {
                        val toHome = a11y.performAccessibilityHome()
                        Log.d(TAG, "任务启动前回到桌面: ${if (toHome) "成功" else "失败"}")
                        if (!toHome) LiveLogBuffer.append("⚠ 启动前回到桌面失败（继续执行）")
                    } else {
                        Log.w(TAG, "任务启动前回到桌面跳过：无障碍服务未就绪")
                    }
                }
                service.initialize(config)
                service.executeTask(task, callback, plan)
            } catch (e: Exception) {
                Log.e(TAG, "startNewTask异常: ${e.message}")
                onTaskFinished(channel, "任务启动失败: ${e.message}", messageID)
            }
        }
    }

    private fun createAgentCallback(channel: Channel, messageID: String): AgentCallback {
        val roundBuffer = StringBuilder()
        val reportToWeChat = shouldReportToWeChat(channel)

        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                if (reportToWeChat) {
                    ChannelManager.sendMessage(channel, roundBuffer.toString().trim(), messageID)
                }
                roundBuffer.clear()
            }
        }

        return object : AgentCallback {
            override fun onLoopStart(round: Int) {
                flushRoundBuffer()
                Log.d(TAG, "Loop $round 开始")
                FloatingProgressManager.updateProgress(round, "执行中·第${round}轮")
                LiveLogBuffer.append("🔁 第${round}轮开始 — ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                // TTS：首轮播报任务开始执行
                if (round == 1 && !reportToWeChat) {
                    ttsManager?.speakProgress("执行任务中")
                }
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                    LiveLogBuffer.append("💭 ${content}")
                    // v3.2 Bug-4 修复：LOCAL 渠道下也把内容推送给 UI（不再只依赖 WECHAT flush）
                    if (!reportToWeChat) {
                        taskStateListener?.onTaskContent(content, isFinal = false)
                    }
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                flushRoundBuffer()
                val answer = finalAnswer.trim()
                Log.d(TAG, "任务完成: ${answer.take(100)}")
                FloatingProgressManager.updateProgress(-1, "")
                // v3.2 Bug-9 修复：LiveLogBuffer 写入最终答案内容（不再只写"任务完成"字面量）
                LiveLogBuffer.append("✅ 任务完成：${answer.take(200)}")
                // v3.2 Bug-3 修复：LOCAL 渠道下推送最终答案给 UI + 更新悬浮窗"上一句"
                if (!reportToWeChat && answer.isNotEmpty()) {
                    taskStateListener?.onTaskContent(answer, isFinal = true)
                    FloatingProgressManager.setLastModelMessage(answer)
                }
                // TTS：播报最终结果
                if (!reportToWeChat) {
                    ttsManager?.speakResult(answer)
                }
                if (answer.isNotEmpty()) {
                    onTaskFinished(channel, answer, messageID)
                } else {
                    onTaskFinished(channel, "任务已完成", messageID)
                }
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                flushRoundBuffer()
                Log.e(TAG, "任务错误: ${error.message}")
                FloatingProgressManager.updateProgress(-1, "")
                LiveLogBuffer.append("❌ 任务失败: ${error.message}")
                // v3.2 Bug-3 修复：LOCAL 渠道下推送错误信息给 UI + 更新悬浮窗"上一句"
                val errorMsg = "任务失败: ${error.message}"
                if (!reportToWeChat) {
                    taskStateListener?.onTaskContent(errorMsg, isFinal = true)
                    FloatingProgressManager.setLastModelMessage(errorMsg)
                }
                // TTS：播报错误信息
                if (!reportToWeChat) {
                    ttsManager?.speakError(error.message ?: "未知错误")
                }
                onTaskFinished(channel, "任务执行失败: ${error.message}", messageID)
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, displayName: String, parameters: String) {
                Log.d(TAG, "Tool调用: $toolName($parameters)")
                // 决策模型调用工具的日志写入 LiveLogBuffer（App 内日志界面可见）
                LiveLogBuffer.append("🔧 工具调用: $displayName $parameters")
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, displayName: String, parameters: String, result: ToolResult) {
                Log.d(TAG, "Tool结果: $toolName -> success=${result.isSuccess}")
                val status = if (result.isSuccess) "✓" else "✗"
                val data = if (result.isSuccess) (result.data ?: "").take(100) else (result.error ?: "失败")
                if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                roundBuffer.append("$status $toolName: $data")
                // 工具结果同步写入 LiveLogBuffer（App 内日志界面可见）
                LiveLogBuffer.append("📦 工具结果: $status $toolName → $data")
                // TTS：敏感操作（REQUEST_USER_ACTION）需要语音播报提醒
                if (toolName == "REQUEST_USER_ACTION" && !reportToWeChat) {
                    ttsManager?.speakConfirmation(parameters)
                }
            }

            override fun onExecutionFinished(executionSummary: String) {
                // 执行模型确认任务真正完成 → 转发给上层（HomeActivity 持有决策模型，生成任务完成报告）
                Log.d(TAG, "执行模型确认任务完成，转发执行摘要给上层生成报告")
                taskStateListener?.onExecutionFinished(executionSummary)
            }
        }
    }

    internal fun onTaskFinished(channel: Channel, finalMessage: String, messageID: String) {
        if (!isHoldingTask(messageID)) return
        val msg = finalMessage.trim().ifEmpty { "任务已结束" }
        if (shouldReportToWeChat(channel)) {
            ChannelManager.sendMessage(channel, msg, messageID)
            ChannelManager.flushMessages(channel)
        } else if (channel == Channel.LOCAL) {
            // v3.2 LOCAL 渠道：任务完成时强制展开悬浮窗到 IDLE，显示 lastModelMessage
            FloatingProgressManager.setIdleState()
        } else {
            FloatingProgressManager.setIdleState()
        }
        releaseTask()
        taskStateListener?.onTaskStateChanged(false)
    }
}