package com.palmagent.app.data.local

import com.palmagent.app.data.local.entity.MessageEntity
import com.palmagent.app.ui.chat.ChatMessage
import com.palmagent.app.ui.chat.MessageStatus

/**
 * 数据库实体 ↔ UI 模型双向映射器。
 * 保持 ChatAdapter / DecisionDialogService 等现有代码对 ChatMessage 的引用不变。
 */
object MessageMapper {

    fun entityToUi(entity: MessageEntity): ChatMessage {
        return ChatMessage(
            id = entity.id,
            content = entity.content,
            isUser = entity.isUser,
            timestamp = entity.timestamp,
            status = try { MessageStatus.valueOf(entity.status) } catch (_: Exception) { MessageStatus.SENT },
            questions = entity.questions,
            source = entity.source
        )
    }

    fun uiToEntity(ui: ChatMessage, sessionId: String): MessageEntity {
        return MessageEntity(
            id = ui.id,
            sessionId = sessionId,
            content = ui.content,
            isUser = ui.isUser,
            timestamp = ui.timestamp,
            status = ui.status.name,
            questions = ui.questions,
            source = ui.source
        )
    }
}