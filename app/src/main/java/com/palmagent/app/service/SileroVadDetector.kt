package com.palmagent.app.service

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer

/**
 * Silero VAD (Voice Activity Detection) 语音活动检测器
 *
 * 基于 Silero VAD v4 ONNX 模型（MIT 协议）。
 * 检测音频流中是否包含人声，用于语音输入的"说话/静音"判断。
 *
 * 资源消耗：
 * - 存储：~2MB（ONNX 模型文件）
 * - 运行时内存：~10MB
 * - 按需加载：仅在用户录音时加载，用完释放
 *
 * 执行链路：
 *   AudioRecorder → PCM 16bit 16kHz → SileroVadDetector.isSpeech() → 阈值判断
 *   → 有语音 → SherpaOnnxAsrEngine 转录
 *   → 无语音 → 继续等待/结束录音
 *
 * 状态机：
 *   IDLE → VOICE_DETECTED（概率 > 阈值）→ SPEECH_ONGOING → VOICE_END（概率 < 阈值持续 N 帧）
 *   → 触发 ASR 转录
 */
class SileroVadDetector(context: Context, private val config: VoiceConfig) {

    companion object {
        private const val TAG = "SileroVadDetector"
    }

    enum class VadState {
        /** 未检测到语音 */
        IDLE,
        /** 刚检测到语音（起始帧） */
        VOICE_START,
        /** 语音持续中 */
        SPEECH_ONGOING,
        /** 语音结束 */
        VOICE_END
    }

    private val appContext = context.applicationContext

    /** 采样率（来自配置） */
    val sampleRate: Int = config.sampleRate

    /** 帧大小（来自配置） */
    val frameSize: Int = config.vadFrameSize

    /** 语音检测阈值（概率 > 此值视为有语音） */
    private val speechThreshold: Float = config.vadSpeechThreshold

    /** 语音结束阈值 */
    private val silenceThreshold: Float = config.vadSilenceThreshold

    /** 语音结束判定：连续静音帧数（约 3 秒） */
    private val silenceFramesThreshold: Int = config.vadSilenceFrames

    /** 最小语音长度帧数（约 0.5 秒） */
    private val minSpeechFrames: Int = config.vadMinSpeechFrames

    /** 模型会话（同步锁保护，避免 release 与推理竞态） */
    private val sessionLock = Any()
    private var session: OrtSession? = null

    @Volatile
    private var isLoaded = false

    /** 停止标志：设置后 processFrame 立即返回 0f */
    @Volatile
    private var stopped = false

    /** 内部状态 */
    @Volatile
    private var currentState = VadState.IDLE

    /** 连续静音帧计数 */
    private var silenceFrameCount = 0

    /** 语音持续帧计数 */
    private var speechFrameCount = 0

    /** 模型内部状态 h（hidden state，1×1×128） */
    private val h = FloatArray(128)

    /** 模型内部状态 c（cell state，1×1×128） */
    private val c = FloatArray(128)

    /** 组合状态 state（[2,1,128] = [h; c]），用于单 state 输入模型 */
    private val state = FloatArray(256)

    /** 采样率张量（int64 标量，shape=[] 或 [1]） */
    private var sr: OnnxTensor? = null

    /** 输入名称（从模型加载时读取） */
    private var inputName: String? = null
    private var stateHName: String? = null
    private var stateCName: String? = null
    private var srName: String? = null
    /** 模型是否为单 state 输入（[input, state, sr]，state=[2,1,128]）而非双 state_h/state_c 输入 */
    private var singleStateInput = false

    /** 检测到语音开始时的回调 */
    @Volatile
    var onVoiceStart: (() -> Unit)? = null

    /** 检测到语音结束时的回调 */
    @Volatile
    var onVoiceEnd: (() -> Unit)? = null

    /** 每帧检测结果回调（测试用） */
    @Volatile
    var onFrameResult: ((Float, VadState) -> Unit)? = null

    /**
     * 加载模型（首次调用时加载，之后复用）。
     * 需要在后台线程调用。
     *
     * @return true 加载成功
     */
    fun load(): Boolean {
        if (isLoaded) return true
        return try {
            val modelFile = File(appContext.filesDir, config.vadModelAssetPath)
            if (!modelFile.exists()) {
                modelFile.parentFile?.mkdirs()
                appContext.assets.open(config.vadModelAssetPath).use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "VAD 模型已拷贝: ${modelFile.length()} bytes")
            }

            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
            val sess = env.createSession(modelFile.absolutePath, opts)

            // 记录实际输入/输出名称（调试用）
            val inputNames = sess.inputNames.toList()
            val outputNames = sess.outputNames.toList()
            Log.i(TAG, "VAD 模型输入: $inputNames")
            Log.i(TAG, "VAD 模型输出: $outputNames")

            synchronized(sessionLock) {
                session = sess
                inputName = inputNames.getOrNull(0)
                stateHName = inputNames.getOrNull(1)
                stateCName = inputNames.getOrNull(2)
                // 兼容两种模型形态：
                //  A. 双状态输入 [input, state_h, state_c, sr]（旧版 Silero VAD，4 输入）
                //  B. 单状态输入 [input, state, sr]（新版，state=[2,1,128] 内含 h/c，3 输入）
                singleStateInput = inputNames.size == 3 &&
                    (inputNames[1] == "state" || inputNames[1]?.lowercase()?.contains("state") == true) &&
                    inputNames[2] == "sr"
                // sr 的输入名随形态不同：
                //  单状态 [input,state,sr] → 索引 2；双状态 [input,state_h,state_c,sr] → 索引 3
                srName = if (singleStateInput) inputNames.getOrNull(2) else inputNames.getOrNull(3)
            }

            // 初始化内部状态
            h.fill(0f)
            c.fill(0f)
            state.fill(0f)

            // 创建采样率张量（int64 标量，shape=[1]）
            val srData = LongBuffer.wrap(longArrayOf(sampleRate.toLong()))
            sr = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                srData,
                longArrayOf(1)
            )

            isLoaded = true
            stopped = false
            Log.i(TAG, "VAD 模型加载完成 (singleStateInput=$singleStateInput)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "VAD 模型加载失败: ${e.message}", e)
            false
        }
    }

    /**
     * 停止 VAD 处理（通知 processFrame 立即返回 0f）
     * 用于避免 release() 与 processFrame() 的竞态条件
     */
    fun stop() {
        stopped = true
    }

    /**
     * 处理一帧 PCM 16bit 音频数据，返回语音概率。
     *
     * @param pcmFrame 512 个 16-bit PCM 样本（16kHz mono）
     * @return 语音概率 [0, 1]
     */
    fun processFrame(pcmFrame: ShortArray): Float {
        if (stopped || !isLoaded) return 0f

        // 获取 session 的本地副本（同步锁保护）
        val sess: OrtSession
        synchronized(sessionLock) {
            sess = session ?: return 0f
        }

        try {
            // 将 ShortArray 归一化为 FloatArray [-1, 1]
            val floatInput = FloatArray(frameSize)
            for (i in 0 until frameSize.coerceAtMost(pcmFrame.size)) {
                floatInput[i] = pcmFrame[i].toFloat() / 32768f
            }

            val env = OrtEnvironment.getEnvironment()

            // 输入形状：input 必须是 2 维 [1, 512]（模型声明 [None, None]）
            val inputShape = longArrayOf(1, frameSize.toLong())
            val inputTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatInput), inputShape
            )

            val inputs: Map<String, OnnxTensor>
            var extraTensors = mutableListOf<OnnxTensor>()
            if (singleStateInput) {
                // 单状态输入 [input, state, sr]：state=[2,1,128] = [h(1×128); c(1×128)]
                System.arraycopy(h, 0, state, 0, 128)
                System.arraycopy(c, 0, state, 128, 128)
                val stateShape = longArrayOf(2, 1, 128)
                val stateTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(state), stateShape
                )
                extraTensors.add(stateTensor)
                if (stopped) {
                    inputTensor.close()
                    stateTensor.close()
                    return 0f
                }
                inputs = mapOf(
                    inputName!! to inputTensor,
                    stateHName!! to stateTensor,
                    srName!! to sr!!
                )
            } else {
                // 双状态输入 [input, state_h, state_c, sr]：state_h/state_c 各 [2,1,64] 或 [1,1,128]
                val stateShape = longArrayOf(2, 1, 64)
                val hTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(h), stateShape
                )
                val cTensor = OnnxTensor.createTensor(
                    env, FloatBuffer.wrap(c), stateShape
                )
                extraTensors.add(hTensor)
                extraTensors.add(cTensor)
                if (stopped) {
                    inputTensor.close()
                    hTensor.close()
                    cTensor.close()
                    return 0f
                }
                inputs = mapOf(
                    inputName!! to inputTensor,
                    stateHName!! to hTensor,
                    stateCName!! to cTensor,
                    srName!! to sr!!
                )
            }

            val output = sess.run(inputs)

            // === 读取输出概率（使用 FloatBuffer 避免泛型数组转换问题） ===
            var probability: Float = 0f
            try {
                val probValue = output[0]
                if (probValue is OnnxTensor) {
                    val fb = probValue.floatBuffer
                    probability = if (fb.remaining() > 0) fb.get() else 0f
                } else {
                    // 兜底：尝试通过 value 读取
                    @Suppress("UNCHECKED_CAST")
                    val arr = probValue?.value as? Array<Array<FloatArray>>
                    probability = arr?.get(0)?.get(0)?.get(0) ?: 0f
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取输出概率失败: ${e.message}")
                probability = 0f
            }

            // === 读取新的状态 ===
            try {
                val stateValue = output[1]
                if (stateValue is OnnxTensor) {
                    val fb = stateValue.floatBuffer
                    if (singleStateInput) {
                        // stateN = [h'; c']，拆分回 h 和 c
                        val tmp = FloatArray(256)
                        fb.get(tmp)
                        System.arraycopy(tmp, 0, h, 0, 128)
                        System.arraycopy(tmp, 128, c, 0, 128)
                    } else {
                        // stateN_h + stateN_c：连续读取
                        fb.get(h, 0, h.size.coerceAtMost(fb.remaining()))
                        if (fb.remaining() >= c.size) fb.get(c)
                    }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val newState = stateValue?.value as? Array<Array<FloatArray>>
                    if (newState != null) {
                        for (i in 0 until 2) {
                            for (j in 0 until 64) {
                                h[i * 64 + j] = newState[i][0][j]
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "读取状态失败: ${e.message}")
            }

            // 关闭资源（统一关闭本轮创建的全部张量）
            output.close()
            inputTensor.close()
            extraTensors.forEach { runCatching { it.close() } }

            // 更新状态机
            updateStateMachine(probability)

            return probability
        } catch (e: Exception) {
            Log.e(TAG, "VAD 推理失败: ${e.message}")
            return 0f
        }
    }

    /**
     * 批量处理音频帧，返回检测到的语音片段列表。
     *
     * @param pcmData 完整 PCM 16bit 音频数据
     * @return 语音片段列表（每个片段为 [startSample, endSample]）
     */
    fun processAudio(pcmData: ShortArray): List<IntArray> {
        resetState()
        val segments = mutableListOf<IntArray>()
        var segmentStart = -1

        for (offset in 0 until pcmData.size step frameSize) {
            val end = (offset + frameSize).coerceAtMost(pcmData.size)
            val frame = pcmData.copyOfRange(offset, end)
            val prob = processFrame(frame)

            when {
                prob > speechThreshold && segmentStart == -1 -> {
                    segmentStart = offset
                }
                prob < silenceThreshold && segmentStart != -1 -> {
                    segments.add(intArrayOf(segmentStart, offset))
                    segmentStart = -1
                }
            }
        }

        // 如果音频结束仍有未关闭的语音段
        if (segmentStart != -1) {
            segments.add(intArrayOf(segmentStart, pcmData.size))
        }

        resetState()
        return segments
    }

    /**
     * 重置模型内部状态
     */
    fun resetState() {
        h.fill(0f)
        c.fill(0f)
        state.fill(0f)
        currentState = VadState.IDLE
        silenceFrameCount = 0
        speechFrameCount = 0
    }

    /**
     * 释放模型资源
     */
    fun release() {
        stop()
        synchronized(sessionLock) {
            try { sr?.close() } catch (_: Exception) {}
            sr = null
            try { session?.close() } catch (_: Exception) {}
            session = null
        }
        isLoaded = false
        Log.d(TAG, "VAD 模型已释放")
    }

    /**
     * 是否已加载
     */
    fun isLoaded(): Boolean = isLoaded

    /**
     * 获取当前状态
     */
    fun getCurrentState(): VadState = currentState

    // ============= 内部方法 =============

    /**
     * VAD 状态机更新
     * IDLE → VOICE_START (概率 > 阈值) → SPEECH_ONGOING
     * SPEECH_ONGOING → VOICE_END (概率 < 静音阈值，持续 N 帧)
     * VOICE_END → 回调通知 → IDLE
     */
    private fun updateStateMachine(probability: Float) {
        when (currentState) {
            VadState.IDLE -> {
                if (probability > speechThreshold) {
                    speechFrameCount = 1
                    currentState = VadState.VOICE_START
                    onVoiceStart?.invoke()
                    Log.d(TAG, "VAD: 语音开始 (prob=$probability)")
                }
            }
            VadState.VOICE_START -> {
                speechFrameCount++
                if (probability > speechThreshold) {
                    if (speechFrameCount >= 3) {
                        currentState = VadState.SPEECH_ONGOING
                    }
                } else {
                    // 误触发，回退到 IDLE
                    if (speechFrameCount < minSpeechFrames) {
                        resetState()
                    } else {
                        currentState = VadState.VOICE_END
                    }
                }
            }
            VadState.SPEECH_ONGOING -> {
                speechFrameCount++
                if (probability < silenceThreshold) {
                    silenceFrameCount++
                    if (silenceFrameCount >= silenceFramesThreshold) {
                        currentState = VadState.VOICE_END
                    }
                } else {
                    silenceFrameCount = 0
                }
            }
            VadState.VOICE_END -> {
                if (speechFrameCount >= minSpeechFrames) {
                    onVoiceEnd?.invoke()
                    Log.d(TAG, "VAD: 语音结束 (frames=$speechFrameCount)")
                }
                resetState()
            }
        }

        onFrameResult?.invoke(probability, currentState)
    }
}