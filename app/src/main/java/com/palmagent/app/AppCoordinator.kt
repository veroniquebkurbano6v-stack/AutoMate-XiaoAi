package com.palmagent.app

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.palmagent.app.agent.AgentConfig
import com.palmagent.app.agent.Plan
import com.palmagent.app.channel.Channel
import com.palmagent.app.channel.ChannelManager
import com.palmagent.app.channel.ChannelSetup
import com.palmagent.app.floating.FloatingProgressManager
import com.palmagent.app.service.ForegroundService
import com.palmagent.app.service.KeepAliveJobService
import com.palmagent.app.service.TtsManager
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用协调器
 *
 * 负责协调各模块的初始化和任务调度。
 * 不继承 ViewModel，命名为 Coordinator 更准确地反映其职责。
 */
@Singleton
class AppCoordinator @Inject constructor(
    val taskOrchestrator: TaskOrchestrator
) {

    companion object {
        private const val TAG = "AppCoordinator"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private val channelSetup = ChannelSetup(taskOrchestrator = taskOrchestrator)

    /**
     * v3.2 Bug-7 修复：应用级 CoroutineScope
     * 用于悬浮窗触发的决策模型对话，避免 HomeActivity 销毁时 lifecycleScope 取消决策对话
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * v3.2 Bug-7 修复：在应用级 scope 中启动决策模型对话
     * HomeActivity 销毁不会取消决策对话，结果通过 taskStateListener 推送
     */
    fun launchDecision(block: suspend () -> Unit) {
        applicationScope.launch { block() }
    }

    /**
     * v3.2 Bug-V 修复：应用退出时取消 applicationScope，避免协程泄漏
     * 应在 AgentApplication.onTerminate() 中调用
     */
    fun shutdown() {
        applicationScope.cancel()
        // TTS 释放资源
        taskOrchestrator.ttsManager?.shutdown()
    }

    /**
     * 设置 TTS 管理器（由 Application 初始化后调用）
     */
    fun setTtsManager(ttsManager: TtsManager) {
        taskOrchestrator.ttsManager = ttsManager
    }

    fun initCommon() {
        Log.i(TAG, "initCommon")
    }

    fun getAgentConfig(): AgentConfig = KVUtils.getAgentConfig()

    fun afterInit() {
        acquireScreenWakeLock()
        ForegroundService.start(AgentApplication.instance)
        KeepAliveJobService.schedule(AgentApplication.instance)
        if (Settings.canDrawOverlays(AgentApplication.instance)) {
            FloatingProgressManager.show(AgentApplication.instance)
        }
        channelSetup.setup()
    }

    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = AgentApplication.instance.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "PalmAgent::ScreenWakeLock"
        ).apply { acquire() }
        Log.i(TAG, "亮屏锁已获取")
    }

    fun startNewTask(channel: Channel, task: String, messageID: String, plan: Plan? = null) =
        taskOrchestrator.startNewTask(channel, task, messageID, plan)

    fun isTaskRunning(): Boolean = taskOrchestrator.isTaskRunning()

    fun cancelCurrentTask() {
        taskOrchestrator.cancelCurrentTask()
    }

    /**
     * 发送任务命令
     * @param text 用户需求文本（复杂模式下为 PlanFormatter 格式化后的 plan 文本）
     * @param plan 决策模型生成的结构化计划（复杂模式传入；简单模式为空），注入执行模型【决策模型任务计划】区域
     */
    fun sendCommand(text: String, plan: Plan? = null): Boolean {
        val msgId = java.util.UUID.randomUUID().toString()
        return if (taskOrchestrator.tryAcquireTask(msgId, Channel.LOCAL)) {
            taskOrchestrator.startNewTask(Channel.LOCAL, text, msgId, plan)
            true
        } else {
            Log.w(TAG, "任务冲突，sendCommand 被拒绝（有任务正在执行）")
            false
        }
    }
}
