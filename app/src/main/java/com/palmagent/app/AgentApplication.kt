package com.palmagent.app

import android.app.Application
import android.app.Activity
import android.os.Bundle
import com.palmagent.app.service.AIService
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.service.TtsManager
import com.palmagent.app.framework.config.AppConfig
import com.palmagent.app.framework.event.EventBus
import com.palmagent.app.framework.coroutine.AgentCoroutineScope
import com.palmagent.app.framework.coroutine.CoroutineDispatcherProvider
import com.palmagent.app.floating.UserGuideNotifier
import com.palmagent.app.tool.ToolRegistry
import com.palmagent.app.utils.KVUtils
import com.palmagent.app.utils.InstalledAppProvider
import com.palmagent.app.utils.KeyboardDetector
import com.palmagent.app.kb.LocalKbEngine
import android.util.Log
import timber.log.Timber
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

val appCoordinator: AppCoordinator by lazy { AgentApplication.instance.appCoordinatorInstance }

@HiltAndroidApp
class AgentApplication : Application() {

    @Inject
    lateinit var aiService: AIService

    @Inject
    lateinit var appConfig: AppConfig

    @Inject
    lateinit var eventBus: EventBus

    @Inject
    lateinit var coroutineScope: AgentCoroutineScope

    @Inject
    lateinit var dispatcherProvider: CoroutineDispatcherProvider

    @Inject
    lateinit var appCoordinatorInstance: AppCoordinator

    companion object {
        private const val TAG = "AgentApplication"
        lateinit var instance: AgentApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 层1：统一日志门面（Timber）——Debug 构建全量打印，Release 构建只留 INFO+（去噪 + 安全）
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority < Log.INFO) return
                    Log.println(priority, tag ?: "PalmAgent", message)
                    t?.let { Log.println(priority, tag ?: "PalmAgent", it.stackTraceToString()) }
                }
            })
        }
        KVUtils.init(this)
        com.palmagent.app.service.SearchResultCache.init(this)

        // 注册 Activity 生命周期回调，追踪前台 Activity 供键盘检测使用
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                KeyboardDetector.LastActivityHolder.onActivityResumed(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                KeyboardDetector.LastActivityHolder.onActivityPaused(activity)
            }
            override fun onActivityDestroyed(activity: Activity) {
                KeyboardDetector.LastActivityHolder.onActivityDestroyed(activity)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        })

        ToolRegistry.initAllTools()
        UserGuideNotifier.init(this)
        Log.i(TAG, "AgentApplication initialized, tools: ${ToolRegistry.getAllTools().size}")

        // 预热已装 App 列表缓存（供 ListAppsTool 工具查询，避免首次调用扫描 PackageManager 阻塞对话）
        coroutineScope.launch {
            try {
                val count = InstalledAppProvider.getInstalledAppsList(this@AgentApplication).size
                Log.i(TAG, "已安装应用列表预热完成: $count 个")
            } catch (e: Exception) {
                Log.w(TAG, "已安装应用列表预热失败: ${e.message}")
            }
        }

        // 端侧知识库引擎初始化（后台：拷贝 assets -> 加载 ONNX 模型 -> 读 SQLite 向量到内存）
        // 耗时约 1-3s，不阻塞主线程；KbReadTool 在引擎就绪前调用会返回未初始化错误
        if (KVUtils.isLocalKbEnabled()) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    LocalKbEngine.init(this@AgentApplication)
                } catch (e: Exception) {
                    Log.e(TAG, "端侧知识库初始化失败: ${e.message}", e)
                }
            }
        }

        // TTS 语音播报引擎初始化（后台线程异步初始化，不阻塞主线程）
        if (KVUtils.isTtsEnabled()) {
            val ttsManager = TtsManager(this)
            appCoordinatorInstance.setTtsManager(ttsManager)
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    ttsManager.initialize()
                    Log.i(TAG, "TTS 引擎初始化完成")
                } catch (e: Exception) {
                    Log.e(TAG, "TTS 引擎初始化失败: ${e.message}", e)
                }
            }
        }

        appCoordinatorInstance.initCommon()

        if (KVUtils.hasLlmConfig()) {
            Thread({
                appCoordinatorInstance.afterInit()
                // 初始化 GUI-Plus 云端 API（视觉描述/问答/决策均已迁移至此）
                GuiOwlService.init()
            }, "agent-async-init").start()
        }
    }

    /**
     * v3.2 Bug-V 修复：应用退出时取消应用级协程 scope，避免泄漏
     * 注意：onTerminate 在真实设备上仅在模拟进程退出时调用，仿真器/低内存场景不保证触发，
     * 因此 appCoordinator 内部对 launchDecision 异常做了兜底捕获
     */
    override fun onTerminate() {
        super.onTerminate()
        try {
            appCoordinatorInstance.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown 异常: ${e.message}")
        }
    }
}
