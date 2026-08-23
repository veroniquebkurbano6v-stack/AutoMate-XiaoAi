package com.palmagent.app.service

import org.junit.Assert.*
import org.junit.Test

/**
 * VoiceConfig 单元测试：严格解析（无默认值兜底）
 *
 * 覆盖：
 * 1. 完整配置正常解析（字段值正确）
 * 2. 根节点非 JSON 对象 → 抛异常
 * 3. JSON 非法 → 抛异常
 * 4. 缺少顶层字段 → 抛异常
 * 5. 缺少嵌套对象（vad/asr/fbank）→ 抛异常
 * 6. 字段类型错误（整数/数字/布尔/字符串）→ 抛异常
 * 7. 缺嵌套字段 → 抛异常
 */
class VoiceConfigTest {

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

    @Test
    fun `完整配置正常解析且字段值正确`() {
        val cfg = VoiceConfig.parseJson(validJson)

        assertEquals(16000, cfg.sampleRate)
        assertEquals(60, cfg.maxRecordSeconds)
        assertEquals(3000L, cfg.silenceTimeoutMs)
        assertEquals(0.02f, cfg.voiceVolumeThreshold)

        assertEquals("vad/silero_vad.onnx", cfg.vadModelAssetPath)
        assertEquals(512, cfg.vadFrameSize)
        assertEquals(0.5f, cfg.vadSpeechThreshold)
        assertEquals(0.3f, cfg.vadSilenceThreshold)
        assertEquals(94, cfg.vadSilenceFrames)
        assertEquals(16, cfg.vadMinSpeechFrames)

        assertEquals("sensevoice/model.int8.onnx", cfg.asrModelAssetPath)
        assertEquals("sensevoice/tokens.txt", cfg.asrTokensAssetPath)
        assertEquals("zh", cfg.asrLanguage)
        assertTrue(cfg.asrUseItn)
        assertEquals(2, cfg.asrNumThreads)

        assertEquals(512, cfg.fbankNfft)
        assertEquals(400, cfg.fbankWinLength)
        assertEquals(160, cfg.fbankHopLength)
        assertEquals(80, cfg.fbankNMels)
        assertEquals(7, cfg.fbankLfrM)
    }

    @Test
    fun `根节点非JSON对象时抛异常`() {
        val e = assertThrows(IllegalStateException::class.java) {
            VoiceConfig.parseJson("[1, 2, 3]")
        }
        assertTrue(e.message!!.contains("根节点"))
    }

    @Test
    fun `JSON非法时抛异常`() {
        assertThrows(IllegalStateException::class.java) {
            VoiceConfig.parseJson("{ not valid json !!")
        }
    }

    @Test
    fun `缺少顶层字段时抛异常`() {
        // 去掉 sample_rate
        val bad = validJson.replace("\"sample_rate\": 16000,", "")
        val e = assertThrows(IllegalStateException::class.java) {
            VoiceConfig.parseJson(bad)
        }
        assertTrue(e.message!!.contains("sample_rate"))
    }

    @Test
    fun `缺少嵌套对象时抛异常`() {
        val bad = validJson.replace("\"vad\": {", "\"vad_typo\": {")
        val e = assertThrows(IllegalStateException::class.java) {
            VoiceConfig.parseJson(bad)
        }
        assertTrue(e.message!!.contains("vad"))
    }

    @Test
    fun `缺嵌套字段时抛异常`() {
        // 去掉 vad.speech_threshold
        val bad = validJson.replace("\"speech_threshold\": 0.5,", "")
        val e = assertThrows(IllegalStateException::class.java) {
            VoiceConfig.parseJson(bad)
        }
        assertTrue(e.message!!.contains("speech_threshold"))
    }

    @Test
    fun `整数类型错误时抛异常`() {
        val bad = validJson.replace("\"sample_rate\": 16000", "\"sample_rate\": \"16k\"")
        val e = assertThrows(IllegalStateException::class.java) {
            VoiceConfig.parseJson(bad)
        }
        assertTrue(e.message!!.contains("sample_rate"))
    }

    @Test
    fun `数字类型错误时抛异常`() {
        val bad = validJson.replace("\"voice_volume_threshold\": 0.02", "\"voice_volume_threshold\": \"高\"")
        val e = assertThrows(IllegalStateException::class.java) {
            VoiceConfig.parseJson(bad)
        }
        assertTrue(e.message!!.contains("voice_volume_threshold"))
    }

    @Test
    fun `布尔类型错误时抛异常`() {
        // Gson 的 asBoolean 对数字 1 会返回 true（不抛），须用字符串触发类型错误
        val bad = validJson.replace("\"use_itn\": true", "\"use_itn\": \"yes\"")
        val e = assertThrows(IllegalStateException::class.java) {
            VoiceConfig.parseJson(bad)
        }
        assertTrue(e.message!!.contains("use_itn"))
    }

    @Test
    fun `字符串类型错误时抛异常`() {
        // Gson 的 asString 对数字 123 会返回 "123"（不抛），须用布尔触发类型错误
        val bad = validJson.replace("\"language\": \"zh\"", "\"language\": true")
        val e = assertThrows(IllegalStateException::class.java) {
            VoiceConfig.parseJson(bad)
        }
        assertTrue(e.message!!.contains("language"))
    }
}
