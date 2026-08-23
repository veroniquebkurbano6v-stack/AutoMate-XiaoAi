package com.palmagent.app.service

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.palmagent.app.utils.KVUtils
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Android TTS 语音播报管理器
 *
 * 职责：
 * - 使用 Android 内置 TextToSpeech API 实现语音播报
 * - 不依赖任何额外模型文件，零存储开销
 * - 使用 STREAM_MUSIC 流，与 TalkBack（STREAM_ACCESSIBILITY / STREAM_NOTIFICATION）
 *   互不冲突，可同时发声
 * - 支持播报队列：避免播报内容交叉覆盖
 * - 支持配置开关：可在设置中开启/关闭语音反馈
 *
 * 执行链路：
 *   onProgress / onComplete / onError 等事件
 *     → KVUtils.isTtsEnabled() 检查开关
 *     → TtsManager.speak(text) 入队
 *     → TextToSpeech.speak() 逐条播报
 *
 * TalkBack 兼容性：
 * - 使用 STREAM_MUSIC 而非 STREAM_ACCESSIBILITY，保证 TalkBack 读屏与 TTS 播报可同时发声
 * - 播报内容简短扼要，避免干扰 TalkBack 的正常读屏
 */
class TtsManager(context: Context) {

    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID = "palmagent_tts_utterance"

        /** 单条播报最大长度，过长自动截断 */
        private const val MAX_TEXT_LENGTH = 200

        /** 播报队列最大长度，防止无限堆积 */
        private const val MAX_QUEUE_SIZE = 20
    }

    private val appContext = context.applicationContext

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var isInitialized = false

    private val speechQueue = ConcurrentLinkedQueue<String>()

    @Volatile
    private var isSpeaking = false

    @Volatile
    private var isShutdown = false

    /** 初始化锁 */
    private val initLock = Any()

    /** 播报回调（用于测试或日志） */
    @Volatile
    var onUtteranceDone: ((String) -> Unit)? = null

    /**
     * 异步初始化 TTS 引擎。
     * 在 Application.onCreate() 或首次调用 speak() 时调用。
     * 初始化完成后自动播报队列中的积压内容。
     */
    fun initialize() {
        if (isInitialized || isShutdown) return
        synchronized(initLock) {
            if (isInitialized || isShutdown) return
            Log.d(TAG, "正在初始化 TTS 引擎...")
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts
                    if (engine != null) {
                        // 设置中文语言
                        val langResult = engine.setLanguage(Locale.CHINESE)
                        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
                            langResult == TextToSpeech.LANG_NOT_SUPPORTED
                        ) {
                            Log.w(TAG, "TTS 中文语言不可用，回退到系统默认语言")
                            engine.setLanguage(Locale.getDefault())
                        }

                        // 设置语速（老年人适合稍慢，1.0=正常）
                        engine.setSpeechRate(KVUtils.getTtsSpeechRate())

                        // 设置音调
                        engine.setPitch(1.0f)

                        // 设置音频属性：使用 STREAM_MUSIC 避免与 TalkBack 冲突
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            engine.setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                        }

                        // 设置播报完成监听
                        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                // 播报开始
                            }

                            override fun onDone(utteranceId: String?) {
                                isSpeaking = false
                                onUtteranceDone?.invoke(utteranceId ?: "")
                                // 播报完成后，播报队列中的下一条
                                processQueue()
                            }

                            override fun onError(utteranceId: String?) {
                                Log.w(TAG, "TTS 播报出错: utteranceId=$utteranceId")
                                isSpeaking = false
                                processQueue()
                            }

                            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                                super.onStop(utteranceId, interrupted)
                                isSpeaking = false
                            }
                        })

                        isInitialized = true
                        Log.d(TAG, "TTS 引擎初始化完成")
                        // 处理积压队列
                        processQueue()
                    }
                } else {
                    Log.e(TAG, "TTS 引擎初始化失败: status=$status")
                }
            }
        }
    }

    /**
     * 播报一段文本。
     * 如果 TTS 未初始化，先初始化再播报。
     * 如果正在播报，加入队列等待。
     *
     * @param text 要播报的文本
     * @param force 是否强制打断当前播报
     */
    fun speak(text: String, force: Boolean = false) {
        if (isShutdown) return
        if (!KVUtils.isTtsEnabled()) return

        val trimmed = text.trim().take(MAX_TEXT_LENGTH)
        if (trimmed.isBlank()) return

        if (force) {
            // 强制打断当前播报，直接播报新内容
            speechQueue.clear()
            isSpeaking = false
            tts?.stop()
            doSpeak(trimmed)
        } else {
            // 加入队列
            if (speechQueue.size >= MAX_QUEUE_SIZE) {
                Log.w(TAG, "播报队列已满，丢弃: $trimmed")
                return
            }
            speechQueue.offer(trimmed)
            // 如果没有正在播报，立即处理队列
            if (!isSpeaking) {
                processQueue()
            }
        }
    }

    /**
     * 播报任务进度
     * 格式：简短描述，适合语音播报
     */
    fun speakProgress(step: String) {
        speak("正在${step}")
    }

    /**
     * 播报敏感操作确认
     */
    fun speakConfirmation(title: String) {
        speak("需要确认：$title，请查看屏幕", force = true)
    }

    /**
     * 播报最终结果
     */
    fun speakResult(result: String) {
        val clean = result
            .replace(Regex("[\\[\\]{}()【】（）]"), "")
            .take(MAX_TEXT_LENGTH)
        speak(clean, force = true)
    }

    /**
     * 播报错误信息
     */
    fun speakError(error: String) {
        speak("操作失败：${error.take(100)}", force = true)
    }

    /**
     * 停止当前播报，清空队列
     */
    fun stop() {
        speechQueue.clear()
        isSpeaking = false
        tts?.stop()
    }

    /**
     * 是否正在播报
     */
    fun isSpeaking(): Boolean = isSpeaking

    /**
     * 释放 TTS 引擎资源
     */
    fun shutdown() {
        isShutdown = true
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * 更新语速（设置中调整后调用）
     */
    fun updateSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }

    // ============= 内部方法 =============

    /**
     * 实际的 TTS 播报调用
     */
    private fun doSpeak(text: String) {
        val engine = tts ?: return
        if (!isInitialized) {
            // 未初始化，重新入队
            speechQueue.offer(text)
            initialize()
            return
        }
        try {
            isSpeaking = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            } else {
                @Suppress("DEPRECATION")
                engine.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak 失败: ${e.message}")
            isSpeaking = false
        }
    }

    /**
     * 处理队列中的下一条播报
     */
    private fun processQueue() {
        if (isSpeaking || isShutdown) return
        val next = speechQueue.poll() ?: return
        doSpeak(next)
    }
}