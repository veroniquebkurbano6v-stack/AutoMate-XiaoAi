package com.palmagent.app.data.repository

import com.palmagent.app.data.local.dao.MessageDao
import com.palmagent.app.data.local.dao.SessionDao
import com.palmagent.app.data.local.dao.SessionWithPreview
import com.palmagent.app.data.local.entity.MessageEntity
import com.palmagent.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天会话/消息持久化仓库。
 * - 会话列表（含最后消息摘要）以 Flow 提供给侧边抽屉实时刷新
 * - 消息写入/读取按 sessionId 分组，删除会话时级联删除其全部消息（外键 CASCADE）
 */
@Singleton
class ChatRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao
) {

    /** 观察全部会话摘要（按最近更新倒序） */
    fun observeSessionsWithPreview(): Flow<List<SessionWithPreview>> =
        sessionDao.observeWithPreview()

    /** 一次性读取全部会话摘要（供启动恢复等场景，避免依赖 Flow 时序） */
    suspend fun getSessionsSnapshot(): List<SessionWithPreview> =
        sessionDao.getAllWithPreview()

    /** 若会话不存在则创建（幂等）：防止异步 createSession 未完成时消息写入触发外键异常 */
    suspend fun ensureSessionExists(id: String, source: String = "LOCAL") {
        if (!sessionDao.exists(id)) {
            createSession(id, source = source)
        }
    }

    /** 新建会话（默认标题"新会话"），返回完整会话实体 */
    suspend fun createSession(id: String, name: String = "新会话", source: String = "LOCAL"): SessionEntity {
        val now = System.currentTimeMillis()
        val session = SessionEntity(
            id = id,
            name = name,
            createdAt = now,
            updatedAt = now,
            source = source
        )
        sessionDao.insert(session)
        return session
    }

    /** 删除会话（消息由外键级联删除） */
    suspend fun deleteSession(id: String) = sessionDao.deleteById(id)

    /** 重命名会话并刷新更新时间 */
    suspend fun renameSession(id: String, name: String) =
        sessionDao.rename(id, name, System.currentTimeMillis())

    /** 新增单条消息 */
    suspend fun insertMessage(entity: MessageEntity) = messageDao.insert(entity)

    /** 读取某会话全部消息（按时间正序） */
    suspend fun getMessages(sessionId: String): List<MessageEntity> =
        messageDao.getBySession(sessionId)

    /** 更新会话时间戳（会话有消息变动时调用，保证列表排序正确） */
    suspend fun touchSession(id: String) =
        sessionDao.touch(id, System.currentTimeMillis())
}
