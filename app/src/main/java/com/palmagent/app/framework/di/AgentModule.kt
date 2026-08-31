package com.palmagent.app.framework.di

import android.content.Context
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.agent.AgentService
import com.palmagent.app.agent.DefaultAgentService
import com.palmagent.app.agent.ScreenDescriptor
import com.palmagent.app.agent.SmartWaitStrategy
import com.palmagent.app.agent.TaskProgressTracker
import com.palmagent.app.domain.repository.AIRepository
import com.palmagent.app.domain.repository.TaskRepository
import com.palmagent.app.data.repository.AIRepositoryImpl
import com.palmagent.app.data.repository.TaskRepositoryImpl
import com.palmagent.app.data.local.AppDatabase
import com.palmagent.app.data.local.dao.MessageDao
import com.palmagent.app.data.local.dao.SessionDao
import com.palmagent.app.service.AIService
import com.palmagent.app.service.ScreenAnalyzer
import com.palmagent.app.service.ToolDecisionEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideAIService(): AIService {
        return AIService()
    }

    @Provides
    @Singleton
    fun provideScreenDescriptor(): ScreenDescriptor {
        return ScreenDescriptor()
    }

    @Provides
    @Singleton
    fun provideSmartWaitStrategy(): SmartWaitStrategy {
        return SmartWaitStrategy()
    }

    @Provides
    @Singleton
    fun provideTaskProgressTracker(): TaskProgressTracker {
        return TaskProgressTracker()
    }

    @Provides
    @Singleton
    fun provideToolDecisionEngine(
        aiService: AIService
    ): ToolDecisionEngine {
        // log 回调同时写入 Logcat 与 LiveLogBuffer，保证文本模式下工具循环的 AI 决策日志在 App 内日志界面可见
        return ToolDecisionEngine(aiService) { message ->
            Log.d("ToolDecision", message)
            LiveLogBuffer.append(message)
        }
    }

    @Provides
    @Singleton
    fun provideScreenAnalyzer(@ApplicationContext context: Context): ScreenAnalyzer {
        return ScreenAnalyzer(context)
    }

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()

    @Provides
    fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentBindModule {

    @Binds
    @Singleton
    abstract fun bindAgentService(impl: DefaultAgentService): AgentService

    @Binds
    @Singleton
    abstract fun bindAIRepository(impl: AIRepositoryImpl): AIRepository

    @Binds
    @Singleton
    abstract fun bindTaskRepository(impl: TaskRepositoryImpl): TaskRepository
}
