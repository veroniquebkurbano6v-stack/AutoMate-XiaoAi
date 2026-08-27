package com.palmagent.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.palmagent.app.data.local.dao.MessageDao
import com.palmagent.app.data.local.dao.SessionDao
import com.palmagent.app.data.local.entity.MessageEntity
import com.palmagent.app.data.local.entity.SessionEntity

/**
 * 聊天历史数据库（Room）。
 * - 与知识库 kb.db 完全独立：kb.db 用原生 SQLite 管理向量数据，此处负责会话/消息持久化
 * - version 1 起步，初期使用 fallbackToDestructiveMigration 加速开发（发布前补 Migration）
 */
@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        private const val DB_NAME = "chat_history.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }
    }
}
