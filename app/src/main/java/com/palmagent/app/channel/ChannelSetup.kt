package com.palmagent.app.channel

import com.palmagent.app.AgentApplication
import com.palmagent.app.R
import com.palmagent.app.TaskOrchestrator
import com.palmagent.app.channel.wechat.WeChatChannelHandler
import com.palmagent.app.channel.wechat.WeChatDecisionRouter
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.utils.KVUtils

class ChannelSetup(
    private val taskOrchestrator: TaskOrchestrator
) {

    private var weChatDecisionRouter: WeChatDecisionRouter? = null

    fun setup() {
        val wechatHandler = WeChatChannelHandler(AgentApplication.instance)
        ChannelManager.registerHandler(Channel.WECHAT, wechatHandler)

        ChannelManager.init(
            wechatBotToken = KVUtils.getWechatBotToken().ifEmpty { null },
            wechatApiBaseUrl = KVUtils.getWechatApiBaseUrl().ifEmpty { null }
        )

        // 创建微信决策路由器：微信消息先走决策模型，再决定是否执行任务
        weChatDecisionRouter = WeChatDecisionRouter(taskOrchestrator, AgentApplication.instance)

        ChannelManager.setOnMessageReceivedListener(object : ChannelManager.OnMessageReceivedListener {
            override fun onMessageReceived(channel: Channel, message: String, messageID: String) {
                val app = AgentApplication.instance
                if (!GUIAccessibilityService.isRunning) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_no_accessibility), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }

                if (channel == Channel.WECHAT) {
                    // 微信消息走决策路由器：决策模型对话 →（信息充足）→ 执行模型
                    weChatDecisionRouter?.handleMessage(message, messageID)
                    return
                }

                // 其他通道：原有逻辑（直接执行任务）
                if (!taskOrchestrator.tryAcquireTask(messageID, channel)) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_task_in_progress), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                taskOrchestrator.startNewTask(channel, message, messageID)
            }
        })

        ChannelManager.start(Channel.WECHAT)
    }
}
