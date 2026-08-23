package com.palmagent.app.service

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 语音配置（唯一配置源：assets/config/voice_config.json）
 *
 * 严格解析：任何字段缺失、类型错误或文件缺失都会抛出异常——
 * 不做默认值兜底，保证配置即事实（业界：参数 JSON 注入、运行时可调）。
 *
 * 用法：VoiceConfig.load(context) 在应用启动时加载一次，各引擎通过构造参数接收。
 */
class VoiceConfig private constructor(
    val sampleRate: Int,
    val maxRecordSeconds: Int,
    val silenceTimeoutMs: Long,
    val voiceVolumeThreshold: Float,
    val vadModelAssetPath: String,
    val vadFrameSize: Int,
    val vadSpeechThreshold: Float,
    val vadSilenceThreshold: Float,
    val vadSilenceFrames: Int,
    val vadMinSpeechFrames: Int,
    val asrModelAssetPath: String,
    val asrTokensAssetPath: String,
    val asrLanguage: String,
    val asrUseItn: Boolean,
    val asrNumThreads: Int,
    val fbankNfft: Int,
    val fbankWinLength: Int,
    val fbankHopLength: Int,
    val fbankNMels: Int,
    val fbankLfrM: Int
) {

    companion object {
        private const val ASSET_PATH = "config/voice_config.json"

        @Volatile
        private var cached: VoiceConfig? = null

        /**
         * 加载语音配置（进程内缓存，幂等）
         *
         * @throws IllegalStateException 文件缺失、JSON 非法或字段缺失/类型错误时抛出
         */
        fun load(context: Context): VoiceConfig {
            cached?.let { return it }
            val cfg = parse(context)
            cached = cfg
            return cfg
        }

        /** 测试/重载用：清除缓存 */
        fun clearCache() {
            cached = null
        }

        private fun parse(context: Context): VoiceConfig {
            val jsonText = try {
                context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                throw IllegalStateException("语音配置文件缺失: assets/$ASSET_PATH", e)
            }
            return parseJson(jsonText)
        }

        /**
         * 从 JSON 文本解析配置（纯解析，可单测直调）。
         * 严格解析：文件缺失、JSON 非法或字段缺失/类型错误一律抛 IllegalStateException，无默认值兜底。
         */
        internal fun parseJson(jsonText: String): VoiceConfig {
            val root = try {
                JsonParser.parseString(jsonText)
            } catch (e: Exception) {
                throw IllegalStateException("语音配置文件 JSON 非法", e)
            }
            if (!root.isJsonObject) {
                throw IllegalStateException("语音配置文件根节点必须是 JSON 对象")
            }
            val obj = root.asJsonObject

            val vad = requireObject(obj, "vad")
            val asr = requireObject(obj, "asr")
            val fbank = requireObject(obj, "fbank")

            return VoiceConfig(
                sampleRate = requireInt(obj, "sample_rate"),
                maxRecordSeconds = requireInt(obj, "max_record_seconds"),
                silenceTimeoutMs = requireLong(obj, "silence_timeout_ms"),
                voiceVolumeThreshold = requireFloat(obj, "voice_volume_threshold"),
                vadModelAssetPath = requireString(vad, "model_asset_path"),
                vadFrameSize = requireInt(vad, "frame_size"),
                vadSpeechThreshold = requireFloat(vad, "speech_threshold"),
                vadSilenceThreshold = requireFloat(vad, "silence_threshold"),
                vadSilenceFrames = requireInt(vad, "silence_frames"),
                vadMinSpeechFrames = requireInt(vad, "min_speech_frames"),
                asrModelAssetPath = requireString(asr, "model_asset_path"),
                asrTokensAssetPath = requireString(asr, "tokens_asset_path"),
                asrLanguage = requireString(asr, "language"),
                asrUseItn = requireBoolean(asr, "use_itn"),
                asrNumThreads = requireInt(asr, "num_threads"),
                fbankNfft = requireInt(fbank, "n_fft"),
                fbankWinLength = requireInt(fbank, "win_length"),
                fbankHopLength = requireInt(fbank, "hop_length"),
                fbankNMels = requireInt(fbank, "n_mels"),
                fbankLfrM = requireInt(fbank, "lfr_m")
            )
        }

        private fun requireObject(parent: JsonObject, name: String): JsonObject {
            val el = parent.get(name) ?: throw IllegalStateException("配置缺少字段: $name")
            if (!el.isJsonObject) throw IllegalStateException("配置字段必须是对象: $name")
            return el.asJsonObject
        }

        private fun requireInt(parent: JsonObject, name: String): Int {
            val el = parent.get(name) ?: throw IllegalStateException("配置缺少字段: $name")
            return try {
                el.asInt
            } catch (e: Exception) {
                throw IllegalStateException("配置字段类型错误（应为整数）: $name = $el", e)
            }
        }

        private fun requireLong(parent: JsonObject, name: String): Long {
            val el = parent.get(name) ?: throw IllegalStateException("配置缺少字段: $name")
            return try {
                el.asLong
            } catch (e: Exception) {
                throw IllegalStateException("配置字段类型错误（应为整数）: $name = $el", e)
            }
        }

        private fun requireFloat(parent: JsonObject, name: String): Float {
            val el = parent.get(name) ?: throw IllegalStateException("配置缺少字段: $name")
            return try {
                el.asFloat
            } catch (e: Exception) {
                throw IllegalStateException("配置字段类型错误（应为数字）: $name = $el", e)
            }
        }

        private fun requireBoolean(parent: JsonObject, name: String): Boolean {
            val el = parent.get(name) ?: throw IllegalStateException("配置缺少字段: $name")
            // 严格类型检查：Gson 的 asBoolean 对字符串会宽松解析（"yes"→false），必须先验类型
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isBoolean) {
                throw IllegalStateException("配置字段类型错误（应为布尔）: $name = $el")
            }
            return el.asBoolean
        }

        private fun requireString(parent: JsonObject, name: String): String {
            val el = parent.get(name) ?: throw IllegalStateException("配置缺少字段: $name")
            // 严格类型检查：Gson 的 asString 对数字/布尔会宽松转字符串，必须先验类型
            if (!el.isJsonPrimitive || !el.asJsonPrimitive.isString) {
                throw IllegalStateException("配置字段类型错误（应为字符串）: $name = $el")
            }
            return el.asString
        }
    }
}
