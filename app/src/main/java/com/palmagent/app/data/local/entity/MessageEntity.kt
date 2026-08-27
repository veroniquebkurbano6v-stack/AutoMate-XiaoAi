package com.palmagent.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palmagent.app.model.Question

/**
 * 消息表：持久化单条聊天消息（用户/AI/问题卡）。
 * - sessionId: 外键 → chat_session.id，删除会话时级联删除其全部消息
 * - status: SENDING / SENT / ERROR（对应 UI 层 MessageStatus 的 name）
 * - questions: 问题卡数据（仅 AI 消息携带，非空时渲染为问题卡），经 TypeConverter 序列化为 JSON
 */
@Entity(
    tableName = "chat_message",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long,
    val status: String,
    val questions: List<Question>?,
    /** 消息来源："LOCAL"=本地输入，"WECHAT"=微信通道 */
    val source: String = "LOCAL"
)
