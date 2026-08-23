package com.palmagent.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音输入管理器
 *
 * 整合录音、VAD（语音活动检测）、ASR（语音识别）为完整的语音输入流程。
 *
 * 执行链路：
 *   用户点击麦克风按钮
 *     → 检查录音权限
 *     → 启动 AudioRecord 录音线程
 *     → VAD 检测语音活动（状态机：IDLE→VOICE_START→SPEECH_ONGOING→VOICE_END）
 *     → 语音结束后，将 PCM 数据送入 ASR 转录
 *     → 转录结果作为任务指令提交给 TaskOrchestrator
 *     → 释放录音和模型资源
 *
 * 资源管理：
 * - VAD 模型：按需加载（用户点击麦克风时加载，转录完成释放）
 * - ASR 模型：按需加载（语音结束后加载，转录完成释放）
 * - 录音线程：语音结束后自动停止
 * - 内存：PCM 数据循环缓冲区，最长 30 秒
 *
 * 权限要求：
 * - RECORD_AUDIO（运行时权限）
 */
class VoiceInputManager(private val context: Context, private val config: VoiceConfig) {

    companion object {
        private const val TAG = "VoiceInputManager"

        /** 音频通道：单声道 */
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        /** 音频格式：16-bit PCM */
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    /** 采样率（来自配置） */
    private val sampleRate: Int = config.sampleRate

    /** 缓冲区大小：VAD 帧大小（来自配置） */
    private val frameSize: Int = config.vadFrameSize

    /** 缓冲区大小（AudioRecord 建议值） */
    private val bufferSize: Int = frameSize * 2

    /** 最大录音时长（来自配置） */
    private val maxRecordSeconds: Int = config.maxRecordSeconds

    /** 最大 PCM 样本数 */
    private val maxPcmSamples: Int = sampleRate * maxRecordSeconds

    /** 语音结束超时：静音持续 N 毫秒后自动结束录音（来自配置） */
    private val silenceTimeoutMs: Long = config.silenceTimeoutMs

    /** 无声检测：连续静音帧数阈值 */
    private val silentFrameLimit: Int =
        silenceTimeoutMs.toInt() * sampleRate / (frameSize * 1000)

    /** 音量判定阈值（来自配置）：平均音量超过此值视为有声音（讲话中不触发静音超时） */
    private val voiceVolumeThreshold: Float = config.voiceVolumeThreshold

    /** 录音状态 */
    enum class RecordingState {
        /** 空闲 */
        IDLE,
        /** 等待语音（已启动录音，但未检测到语音） */
        WAITING_FOR_SPEECH,
        /** 录音中（检测到语音） */
        RECORDING,
        /** 转录中（语音结束，正在 ASR 转文字） */
        TRANSCRIBING,
        /** 出错 */
        ERROR
    }

    /** 语音输入回调 */
    interface VoiceInputCallback {
        /** 录音状态变化 */
        fun onStateChanged(state: RecordingState)

        /** 语音输入完成，返回转录文字 */
        fun onVoiceInputResult(text: String)

        /** 语音输入出错 */
        fun onVoiceInputError(error: String)

        /** 实时音量变化（用于 UI 反馈） */
        fun onVolumeChanged(volume: Float)
    }

    private val appContext = context.applicationContext

    @Volatile
    private var currentState = RecordingState.IDLE

    private var audioRecord: AudioRecord? = null
    private var vadDetector: SileroVadDetector? = null
    private var asrEngine: SherpaOnnxAsrEngine? = null

    private var callback: VoiceInputCallback? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recordingJob: Job? = null

    /** PCM 数据缓冲区 */
    private val pcmBuffer = ByteArrayOutputStream()

    /** 录音是否被用户取消 */
    private val isCancelled = AtomicBoolean(false)

    /** 用户主动停止（要转录）：区别于取消（丢弃） */
    private val isUserStopped = AtomicBoolean(false)

    /** 是否已静音超时（无语音结束） */
    private val isSilentTimeout = AtomicBoolean(false)

    /**
     * 启动语音输入
     *
     * @param callback 回调接口
     * @return true 启动成功
     */
    fun startVoiceInput(callback: VoiceInputCallback): Boolean {
        if (currentState != RecordingState.IDLE) {
            Log.w(TAG, "语音输入已在进行中")
            return false
        }

        // 检查权限
        if (!checkAudioPermission()) {
            callback.onVoiceInputError("缺少录音权限，请在系统设置中允许录音权限")
            return false
        }

        this.callback = callback
        isCancelled.set(false)
        isUserStopped.set(false)
        isSilentTimeout.set(false)
        isRecordingStopped.set(false)
        pcmBuffer.reset()

        setState(RecordingState.WAITING_FOR_SPEECH)

        // 在后台线程启动录音
        recordingJob = scope.launch {
            try {
                startRecording()
            } catch (e: Exception) {
                Log.e(TAG, "录音失败: ${e.message}", e)
                setState(RecordingState.ERROR)
                callback.onVoiceInputError("录音启动失败: ${e.message}")
            }
        }

        return true
    }

    /**
     * 取消语音输入（真正取消：丢弃录音，不转录）
     *
     * 只设置取消标志 + 停止录音，不直接释放 VAD/ASR 资源。
     * 资源释放由录音循环的 finally 块统一处理，避免竞态条件。
     */
    fun cancelVoiceInput() {
        isCancelled.set(true)
        // 先通知 VAD 停止推理，避免 processFrame 与 session.close 竞态
        vadDetector?.stop()
        stopRecording()
        // 注意：不在此处调用 cleanup()，让录音循环的 finally 块处理
        setState(RecordingState.IDLE)
    }

    /**
     * 用户主动停止并转录（再次点击麦克风）
     *
     * 与 cancelVoiceInput 的区别：不置 isCancelled，录音循环退出后
     * 仍会走 startTranscription() 转录已录到的内容。
     */
    fun stopAndTranscribe() {
        if (currentState == RecordingState.IDLE || isCancelled.get()) return
        isUserStopped.set(true)
        vadDetector?.stop()
        stopRecording()
        // 不置 isCancelled、不 setState(IDLE)：录音循环退出后自然进入转录，
        // 转录完成回调里会置回 IDLE
    }

    /**
     * 获取当前状态
     */
    fun getCurrentState(): RecordingState = currentState

    /**
     * 释放所有资源
     */
    fun release() {
        cancelVoiceInput()
        scope.cancel()
    }

    // ============= 内部方法 =============

    /**
     * 启动录音线程
     */
    private suspend fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize <= 0) {
            callback?.onVoiceInputError("设备不支持录音配置")
            setState(RecordingState.ERROR)
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize.coerceAtLeast(bufferSize * 4)
        )

        val record = audioRecord ?: run {
            callback?.onVoiceInputError("无法创建录音实例")
            setState(RecordingState.ERROR)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            callback?.onVoiceInputError("录音初始化失败")
            setState(RecordingState.ERROR)
            return
        }

        // 按需加载 VAD 模型
        val vad = SileroVadDetector(appContext, config)
        vadDetector = vad
        if (!vad.load()) {
            Log.w(TAG, "VAD 模型加载失败，回退到无 VAD 模式")
            // 不阻断，仍可录音，只是不检测语音边界
        }

        // 语音检测标志：供 VAD onVoiceStart 回调与录音循环共用（须在回调前声明，闭包才能捕获）
        var hasDetectedSpeech = false

        // 设置 VAD 回调
        if (vad.isLoaded()) {
            vad.onVoiceStart = {
                // 检测到语音：置位语音检测标志，避免 3 秒静音超时误截断
                // （hasDetectedSpeech 与无 VAD 模式共用；VAD 模式此前恒为 false 导致录音固定 3 秒被截断）
                hasDetectedSpeech = true
                setState(RecordingState.RECORDING)
            }
            vad.onVoiceEnd = {
                // 语音结束，停止录音并转录
                if (currentState == RecordingState.RECORDING) {
                    stopRecording()
                    scope.launch { startTranscription() }
                }
            }
        }

        record.startRecording()
        Log.d(TAG, "录音已启动")

        val frameBuffer = ShortArray(frameSize)
        var totalSamples = 0
        var silentFrames = 0

        // 循环读取音频数据
        while (!isCancelled.get() && !isUserStopped.get() && totalSamples < maxPcmSamples) {
            val read = record.read(frameBuffer, 0, frameSize)
            if (read <= 0) continue

            totalSamples += read

            // 写入 PCM 缓冲区
            val byteBuffer = ByteBuffer.allocate(read * 2).order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer.asShortBuffer().put(frameBuffer.copyOfRange(0, read))
            pcmBuffer.write(byteBuffer.array())

            // 计算 RMS 音量（用于 UI 反馈）
            if (read > 0) {
                var sum = 0.0
                for (i in 0 until read) {
                    sum += (frameBuffer[i].toDouble() / 32768.0).let { it * it }
                }
                val rms = Math.sqrt(sum / read).toFloat()
                callback?.onVolumeChanged(rms.coerceIn(0f, 1f))
            }

            // VAD 检测
            if (vad.isLoaded()) {
                // 如果 VAD 已停止（被 cancelVoiceInput 触发），退出循环
                if (isCancelled.get() || isUserStopped.get()) break
                vad.processFrame(frameBuffer.copyOfRange(0, read))

                // VAD 模式下静音超时兜底改用「音量」判定：
                // VAD 概率可能 <0.5（麦克风增益/环境噪声导致 VAD 不响应）但音频实际有声音，
                // 若用 hasDetectedSpeech 判定会把"正在讲话"误判为静音 → 录音被 3 秒截断。
                // 有声音 → 重置静音计数（讲话中不触发兜底）；真无声持续 3 秒 → 结束。
                var sum = 0f
                for (i in 0 until read) {
                    sum += Math.abs(frameBuffer[i].toFloat() / 32768f)
                }
                val avgVolume = sum / read
                if (avgVolume > voiceVolumeThreshold) {
                    silentFrames = 0
                } else {
                    silentFrames++
                    if (silentFrames >= silentFrameLimit) {
                        Log.d(TAG, "VAD 模式：3 秒静音超时，结束录音")
                        isSilentTimeout.set(true)
                        break
                    }
                }
            } else {
                // 无 VAD 模式：简单音量检测
                var sum = 0f
                for (i in 0 until read) {
                    sum += Math.abs(frameBuffer[i].toFloat() / 32768f)
                }
                val avgVolume = sum / read

                if (avgVolume > voiceVolumeThreshold) {
                    if (!hasDetectedSpeech) {
                        hasDetectedSpeech = true
                        setState(RecordingState.RECORDING)
                    }
                    silentFrames = 0
                } else if (hasDetectedSpeech) {
                    silentFrames++
                    if (silentFrames >= silentFrameLimit) {
                        Log.d(TAG, "无 VAD 模式：静音超时，结束录音")
                        isSilentTimeout.set(true)
                        break
                    }
                }
            }
        }

        // 停止录音
        stopRecording()

        // 如果未取消，转录（用户主动停止 stopAndTranscribe 时 isCancelled=false 会走这里）
        if (!isCancelled.get() && pcmBuffer.size() > 0) {
            startTranscription()
        } else if (!isCancelled.get()) {
            callback?.onVoiceInputError("未检测到语音")
            setState(RecordingState.IDLE)
        }

        // 统一清理（cancelVoiceInput 不再单独调用 cleanup）
        cleanup()
    }

    /** 录音是否已停止（线程安全，防止 stopRecording 并发重复 stop/release） */
    private val isRecordingStopped = AtomicBoolean(false)

    /**
     * 停止录音
     * 线程安全：cancelVoiceInput（UI 线程）与录音循环（IO 线程）可能并发调用，
     * 用 AtomicBoolean 保证 stop/release 只执行一次，避免 AudioRecord.stop() 二次调用抛异常。
     */
    private fun stopRecording() {
        if (!isRecordingStopped.compareAndSet(false, true)) {
            // 已在其他线程停止过，直接返回
            return
        }
        try {
            audioRecord?.let {
                if (it.state == AudioRecord.STATE_INITIALIZED ||
                    it.state == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    it.stop()
                }
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败: ${e.message}")
        }
        audioRecord = null
    }

    /**
     * 启动 ASR 转录
     */
    private suspend fun startTranscription() {
        if (isCancelled.get()) return
        setState(RecordingState.TRANSCRIBING)

        val pcmBytes = pcmBuffer.toByteArray()
        if (pcmBytes.isEmpty()) {
            callback?.onVoiceInputError("未录制到音频数据")
            setState(RecordingState.IDLE)
            return
        }

        // 将 byte[] 转为 ShortArray
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcmShorts = ShortArray(shortBuffer.remaining())
        shortBuffer.get(pcmShorts)

        // 按需加载 ASR 模型（sherpa-onnx 官方 SenseVoice，替换自实现推理）
        val asr = SherpaOnnxAsrEngine(appContext, config)
        asrEngine = asr

        if (!asr.load()) {
            callback?.onVoiceInputError("语音识别模型加载失败")
            asrEngine = null
            setState(RecordingState.ERROR)
            return
        }

        // 转录（在后台线程执行）
        val result = withContext(Dispatchers.IO) {
            asr.transcribe(pcmShorts)
        }

        // 释放 ASR 模型
        asr.release()
        asrEngine = null

        if (result.isNullOrBlank()) {
            callback?.onVoiceInputError("语音识别失败，请重试")
            setState(RecordingState.IDLE)
        } else {
            callback?.onVoiceInputResult(result)
            setState(RecordingState.IDLE)
        }
    }

    /**
     * 清理资源（由录音循环的 finally 或末尾统一调用）
     */
    private fun cleanup() {
        try {
            vadDetector?.stop()
            vadDetector?.release()
        } catch (_: Exception) {}
        vadDetector = null
        try {
            asrEngine?.release()
        } catch (_: Exception) {}
        asrEngine = null
        pcmBuffer.reset()
    }

    /**
     * 更新状态
     */
    private fun setState(state: RecordingState) {
        currentState = state
        callback?.onStateChanged(state)
    }

    /**
     * 检查录音权限
     */
    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查录音权限
     */
    fun hasRecordAudioPermission(): Boolean = checkAudioPermission()
}