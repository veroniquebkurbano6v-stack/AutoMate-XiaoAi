package com.palmagent.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 会话表：一个会话对应一段独立的对话历史（决策模型多轮对话）。
 * - id: 客户端生成的 UUID，作为消息分组键
 * - name: 会话标题（默认"新会话"，用户可重命名）
 * - createdAt / updatedAt: 用于会话列表排序（最近会话靠前）
 */
@Entity(tableName = "chat_session")
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** 会话来源："LOCAL"=本地输入，"WECHAT"=微信通道 */
    val source: String = "LOCAL"
)
