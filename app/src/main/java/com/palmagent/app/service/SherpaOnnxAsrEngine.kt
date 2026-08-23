package com.palmagent.app.service

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * sherpa-onnx 官方 SenseVoice ASR 引擎（替代自实现 onnxruntime 直调）
 *
 * 使用 sherpa-onnx 官方推理框架（C++ 解码器）：
 * - 正确处理 SenseVoice 的语言前缀 token（<|zh|>）、情感/事件 token、ITN 逆文本规范化
 * - 解码质量/稳定性由官方实现保证，消除自实现 tokens 查表的识别错误
 *
 * 模型：sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8（与现有 assets 中 model.int8.onnx 相同文件）
 * 接入：OfflineRecognizer + OfflineSenseVoiceModelConfig（language=zh、开启 ITN）
 */
class SherpaOnnxAsrEngine(context: Context, private val config: VoiceConfig) {

    companion object {
        private const val TAG = "SherpaOnnxAsrEngine"

        /** 采样率（来自配置，此处为默认引用值） */
        private val SAMPLE_RATE: Int = 16000

        /** 最大输入音频长度（与录音上限一致） */
        private val MAX_AUDIO_LENGTH: Int = SAMPLE_RATE * 60
    }

    private val appContext = context.applicationContext

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var isLoaded = false

    @Volatile
    private var isTranscribing = false

    /**
     * 加载模型（首次调用时加载，之后复用）。
     * 需要在后台线程调用。
     *
     * @return true 加载成功
     */
    fun load(): Boolean {
        if (isLoaded) return true
        return try {
            // 1. 从 assets 拷贝模型与 tokens 到 filesDir（与旧 SenseVoice 引擎同模式）
            val modelFile = ensureAssetCopied(config.asrModelAssetPath)
            val tokensFile = ensureAssetCopied(config.asrTokensAssetPath)
            if (modelFile == null || tokensFile == null) {
                Log.e(TAG, "模型资源拷贝失败")
                return false
            }
            Log.i(TAG, "模型: ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024}MB)")

            // 2. 配置 sherpa-onnx（用无参构造 + setter，避免非空类型传 null）
            val featConfig = FeatureConfig().apply {
                sampleRate = config.sampleRate
                featureDim = 80
                dither = 0f
            }

            val senseVoiceConfig = OfflineSenseVoiceModelConfig().apply {
                model = modelFile.absolutePath
                language = config.asrLanguage               // 指定中文（业界：auto/zh/en/...）
                useInverseTextNormalization = config.asrUseItn     // 开启 ITN（数字/标点规范化）
            }

            val modelConfig = OfflineModelConfig().apply {
                senseVoice = senseVoiceConfig
                tokens = tokensFile.absolutePath
                numThreads = config.asrNumThreads
                debug = false
                provider = "cpu"
                modelType = "sense_voice"
                modelingUnit = "cjkchar"
                bpeVocab = ""
            }

            val recognizerConfig = OfflineRecognizerConfig().apply {
                this.featConfig = featConfig
                this.modelConfig = modelConfig
                decodingMethod = "greedy_search"
                maxActivePaths = 4
                hotwordsFile = ""
                hotwordsScore = 1.5f
                blankPenalty = 0.0f
            }

            // 3. 创建识别器（模型/tokens 均从 filesDir 绝对路径加载 → assetManager 必须传 null，
            //    否则 sherpa-onnx 会按 assets 解析绝对路径失败并 abort 崩溃）
            recognizer = OfflineRecognizer(
                assetManager = null,
                config = recognizerConfig
            )
            isLoaded = true
            Log.i(TAG, "sherpa-onnx SenseVoice 模型加载完成")
            true
        } catch (e: Exception) {
            Log.e(TAG, "sherpa-onnx 模型加载失败: ${e.message}", e)
            false
        }
    }

    /**
     * 将 PCM 16bit 音频转录为文字。
     *
     * @param pcmData 16-bit PCM 音频数据（16kHz mono）
     * @return 转录结果，失败返回 null
     */
    fun transcribe(pcmData: ShortArray): String? {
        val rec = recognizer ?: run {
            Log.w(TAG, "ASR 未加载")
            return null
        }
        if (isTranscribing) {
            Log.w(TAG, "ASR 正在转录中")
            return null
        }

        isTranscribing = true
        try {
            // 1. 截断过长音频
            val audioData = if (pcmData.size > MAX_AUDIO_LENGTH) {
                Log.w(TAG, "音频过长，截断到 ${MAX_AUDIO_LENGTH} 样本")
                pcmData.copyOfRange(0, MAX_AUDIO_LENGTH)
            } else {
                pcmData
            }

            // 2. 归一化到 [-1, 1]（sherpa-onnx acceptWaveform 接受 float 波形）
            val floatData = FloatArray(audioData.size)
            for (i in audioData.indices) {
                floatData[i] = audioData[i].toFloat() / 32768f
            }
            Log.d(TAG, "送入波形: ${floatData.size} 样本 (${floatData.size / SAMPLE_RATE}.${(floatData.size % SAMPLE_RATE) * 10 / SAMPLE_RATE}s)")

            // 3. 创建流并送入波形
            val stream = rec.createStream()
            stream.acceptWaveform(floatData, SAMPLE_RATE)

            // 4. 解码并取结果
            rec.decode(stream)
            val result = rec.getResult(stream)
            val text = result.text.trim()

            stream.release()
            Log.d(TAG, "ASR 转录结果: '$text'")
            return text.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "ASR 转录失败: ${e.message}", e)
            return null
        } finally {
            isTranscribing = false
        }
    }

    /**
     * 释放模型资源
     */
    fun release() {
        try {
            recognizer?.release()
        } catch (_: Exception) {}
        recognizer = null
        isLoaded = false
        isTranscribing = false
        Log.d(TAG, "sherpa-onnx ASR 模型已释放")
    }

    /**
     * 是否已加载
     */
    fun isLoaded(): Boolean = isLoaded

    /**
     * 是否正在转录
     */
    fun isTranscribing(): Boolean = isTranscribing

    /** 从 assets 拷贝文件到 filesDir（已存在则跳过） */
    private fun ensureAssetCopied(assetPath: String): File? {
        return try {
            val file = File(appContext.filesDir, assetPath)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                appContext.assets.open(assetPath).use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "已拷贝: $assetPath (${file.length() / 1024 / 1024}MB)")
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "拷贝 $assetPath 失败: ${e.message}", e)
            null
        }
    }
}
