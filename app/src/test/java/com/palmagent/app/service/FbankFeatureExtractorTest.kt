package com.palmagent.app.service

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * FbankFeatureExtractor 单元测试（纯 JVM，无 Android 依赖）
 *
 * 覆盖：
 * 1. 默认配置下特征维度 = 80 * lfr_m = 560
 * 2. 音频过短返回空数组
 * 3. 正弦波输入产生正确帧数（T = (size - win)/hop + 1）
 * 4. 输出值为有限数（无 NaN/Infinity）
 * 5. lfr_m 配置变化影响特征维度
 */
class FbankFeatureExtractorTest {

    private val validJson = """
        {
          "sample_rate": 16000,
          "max_record_seconds": 60,
          "silence_timeout_ms": 3000,
          "voice_volume_threshold": 0.02,
          "vad": {
            "model_asset_path": "vad/silero_vad.onnx",
            "frame_size": 512,
            "speech_threshold": 0.5,
            "silence_threshold": 0.3,
            "silence_frames": 94,
            "min_speech_frames": 16
          },
          "asr": {
            "model_asset_path": "sensevoice/model.int8.onnx",
            "tokens_asset_path": "sensevoice/tokens.txt",
            "language": "zh",
            "use_itn": true,
            "num_threads": 2
          },
          "fbank": {
            "n_fft": 512,
            "win_length": 400,
            "hop_length": 160,
            "n_mels": 80,
            "lfr_m": 7
          }
        }
    """.trimIndent()

    private fun config(): VoiceConfig = VoiceConfig.parseJson(validJson)

    /** 生成一段指定秒数的正弦波 PCM（16kHz, [-1,1]） */
    private fun sinePcm(seconds: Float, freq: Float = 200f, amp: Float = 0.5f): FloatArray {
        val n = (16000 * seconds).toInt()
        return FloatArray(n) { i -> (amp * sin(2.0 * PI * freq * i / 16000.0)).toFloat() }
    }

    @Test
    fun `默认配置特征维度为560`() {
        val extractor = FbankFeatureExtractor(config())
        assertEquals("80 mel × 7 帧拼接", 560, extractor.featureDim())
    }

    @Test
    fun `音频过短返回空数组`() {
        val extractor = FbankFeatureExtractor(config())
        // 小于窗长 400
        val short = FloatArray(200) { 0f }
        assertTrue(extractor.extract(short).isEmpty())
    }

    @Test
    fun `正弦波产生正确帧数`() {
        val extractor = FbankFeatureExtractor(config())
        val pcm = sinePcm(1.0f)   // 1 秒 = 16000 样本
        val feats = extractor.extract(pcm)
        // T = (16000 - 400) / 160 + 1 = 98.5 → 98（整数除法）
        val expected = (16000 - 400) / 160 + 1
        assertEquals(expected, feats.size)
        assertEquals(560, feats[0].size)
    }

    @Test
    fun `输出值均为有限数`() {
        val extractor = FbankFeatureExtractor(config())
        val pcm = sinePcm(0.5f)
        val feats = extractor.extract(pcm)
        assertTrue(feats.isNotEmpty())
        for (frame in feats) {
            for (v in frame) {
                assertTrue("值应为有限数，实际 $v", v.isFinite())
            }
        }
    }

    @Test
    fun `lfr_m配置影响特征维度`() {
        // 覆盖 fbank.lfr_m = 3 → 特征维度 = 80 * 3 = 240
        val customJson = validJson.replace("\"lfr_m\": 7", "\"lfr_m\": 3")
        val cfg = VoiceConfig.parseJson(customJson)
        val extractor = FbankFeatureExtractor(cfg)
        assertEquals("80 mel × 3 帧拼接", 240, extractor.featureDim())

        val pcm = sinePcm(0.5f)
        val feats = extractor.extract(pcm)
        assertTrue(feats.isNotEmpty())
        assertEquals(240, feats[0].size)
    }

    @Test
    fun `不同mel数影响特征维度`() {
        // 覆盖 fbank.n_mels = 40 → 特征维度 = 40 * 7 = 280
        val customJson = validJson.replace("\"n_mels\": 80", "\"n_mels\": 40")
        val cfg = VoiceConfig.parseJson(customJson)
        val extractor = FbankFeatureExtractor(cfg)
        assertEquals("40 mel × 7 帧拼接", 280, extractor.featureDim())
    }
}
