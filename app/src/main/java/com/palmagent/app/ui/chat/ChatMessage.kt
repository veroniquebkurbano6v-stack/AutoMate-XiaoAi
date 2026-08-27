package com.palmagent.app.ui.chat

import com.palmagent.app.model.Question

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    /** AI 消息携带的问题卡数据（非空时渲染为问题卡，content 作为概述） */
    val questions: List<Question>? = null,
    /** 消息来源："LOCAL"=本地输入，"WECHAT"=微信通道 */
    val source: String = "LOCAL"
)

enum class MessageStatus {
    SENDING, SENT, ERROR
}
