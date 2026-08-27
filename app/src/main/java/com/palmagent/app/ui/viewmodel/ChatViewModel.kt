package com.palmagent.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palmagent.app.data.local.MessageMapper
import com.palmagent.app.data.local.dao.SessionWithPreview
import com.palmagent.app.data.repository.ChatRepository
import com.palmagent.app.ui.chat.ChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * 聊天会话 ViewModel：管理会话列表 + 当前会话消息的持久化。
 *
 * 设计说明（对现有 HomeActivity 最小侵入）：
 * - HomeActivity 仍保留内存 chatHistory（供 DecisionDialogService 读取），
 *   本 ViewModel 仅负责"从库加载 / 变更时写库"。
 * - sessions 以 Flow 实时驱动侧边抽屉会话列表。
 * - 新建/切换会话后，调用方通过 loadMessages() 恢复聊天界面。
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _sessions = MutableStateFlow<List<SessionWithPreview>>(emptyList())
    val sessions: StateFlow<List<SessionWithPreview>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    init {
        viewModelScope.launch {
            chatRepository.observeSessionsWithPreview().collect { _sessions.value = it }
        }
    }

    /** 一次性读取全部会话摘要（供启动恢复等场景，避免依赖 Flow 初始空值时序） */
    suspend fun sessionsSnapshot(): List<SessionWithPreview> =
        chatRepository.getSessionsSnapshot()

    /** 创建新会话并设为当前，返回新会话 id（同步返回，异步入库） */
    fun createSession(source: String = "LOCAL"): String {
        val id = UUID.randomUUID().toString()
        _currentSessionId.value = id
        viewModelScope.launch {
            chatRepository.createSession(id, source = source)
        }
        return id
    }

    /** 设置当前会话并一次性加载其全部消息（供 HomeActivity 恢复聊天界面） */
    suspend fun loadMessages(sessionId: String): List<ChatMessage> {
        _currentSessionId.value = sessionId
        val entities = chatRepository.getMessages(sessionId)
        return entities.map { MessageMapper.entityToUi(it) }
    }

    /** 删除会话（含其全部消息，外键级联）。若删的是当前会话，调用方需负责重建 */
    fun deleteSession(id: String) {
        viewModelScope.launch { chatRepository.deleteSession(id) }
    }

    /** 重命名会话 */
    fun renameSession(id: String, name: String) {
        viewModelScope.launch { chatRepository.renameSession(id, name) }
    }

    /**
     * 持久化一条消息到指定会话，并刷新会话时间戳。
     * 自动命名：若会话标题仍是默认"新会话"且本条为用户消息，用消息前 12 字作为标题。
     */
    fun persistMessage(sessionId: String, message: ChatMessage) {
        viewModelScope.launch {
            // 先确保会话存在（幂等）：防止异步 createSession 未完成时外键约束失败
            chatRepository.ensureSessionExists(sessionId, source = message.source)
            chatRepository.insertMessage(MessageMapper.uiToEntity(message, sessionId))
            chatRepository.touchSession(sessionId)
            autoNameSessionIfNeeded(sessionId, message)
        }
    }

    private suspend fun autoNameSessionIfNeeded(sessionId: String, message: ChatMessage) {
        if (!message.isUser) return
        val s = chatRepository.getSessionsSnapshot().firstOrNull { it.id == sessionId } ?: return
        if (s.name != "新会话") return
        val title = message.content.trim().take(12)
        if (title.isNotEmpty()) {
            chatRepository.renameSession(sessionId, title)
        }
    }
}
