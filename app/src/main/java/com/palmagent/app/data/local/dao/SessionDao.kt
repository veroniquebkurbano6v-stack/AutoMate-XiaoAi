package com.palmagent.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.palmagent.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * 会话摘要：会话列表条目（会话信息 + 最后一条消息摘要），用于抽屉列表展示。
 * preview 为空表示该会话尚无消息。
 */
data class SessionWithPreview(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String?,
    val source: String = "LOCAL"
)

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Query("DELETE FROM chat_session WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE chat_session SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)

    /** 仅刷新会话更新时间（消息变动时调用，保证会话列表排序正确） */
    @Query("UPDATE chat_session SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    /** 按最近更新时间倒序观察全部会话摘要（供侧边抽屉实时刷新） */
    @Query(
        """
        SELECT cs.id AS id, cs.name AS name, cs.createdAt AS createdAt, cs.updatedAt AS updatedAt,
               cm.content AS preview, cs.source AS source
        FROM chat_session cs
        LEFT JOIN chat_message cm ON cm.id = (
            SELECT id FROM chat_message WHERE sessionId = cs.id ORDER BY timestamp DESC LIMIT 1
        )
        ORDER BY cs.updatedAt DESC
        """
    )
    fun observeWithPreview(): Flow<List<SessionWithPreview>>

    /** 一次性读取全部会话摘要（供启动恢复/删除后重建决策，避免依赖 Flow 时序） */
    @Query(
        """
        SELECT cs.id AS id, cs.name AS name, cs.createdAt AS createdAt, cs.updatedAt AS updatedAt,
               cm.content AS preview, cs.source AS source
        FROM chat_session cs
        LEFT JOIN chat_message cm ON cm.id = (
            SELECT id FROM chat_message WHERE sessionId = cs.id ORDER BY timestamp DESC LIMIT 1
        )
        ORDER BY cs.updatedAt DESC
        """
    )
    suspend fun getAllWithPreview(): List<SessionWithPreview>

    @Query("SELECT EXISTS(SELECT 1 FROM chat_session WHERE id = :id)")
    suspend fun exists(id: String): Boolean
}
