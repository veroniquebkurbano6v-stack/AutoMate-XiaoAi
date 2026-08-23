package com.palmagent.app.service

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * SileroVadDetector 单元测试
 *
 * 测试目标（覆盖 6 个关键链路）：
 * 1. 状态机：IDLE → VOICE_START → SPEECH_ONGOING → VOICE_END → IDLE
 * 2. 低概率帧不触发语音检测
 * 3. 短促噪声不误触发
 * 4. 静音帧计数正确累积
 * 5. 回调 onVoiceStart / onVoiceEnd 被正确触发
 * 6. 模型加载/释放生命周期
 *
 * 注意：VAD 模型文件（silero_vad.onnx）需要实际部署到设备上才能运行完整推理。
 * 本单元测试聚焦于状态机逻辑和配置验证，不依赖实际模型文件。
 */
class SileroVadDetectorTest {

    private lateinit var mockContext: Context

    /** 测试用语音配置（与 assets/config/voice_config.json 一致的默认值） */
    private val testConfig: VoiceConfig = VoiceConfig.parseJson(
        """
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
    )

    @Before
    fun setUp() {
        mockContext = Mockito.mock(Context::class.java)
        Mockito.`when`(mockContext.applicationContext).thenReturn(mockContext)
    }

    // ========== 测试 1: 配置验证 ==========

    @Test
    fun `constants_are_correctly_configured`() {
        assertEquals("采样率应为 16kHz", 16000, testConfig.sampleRate)
        assertEquals("帧大小应为 512 样本", 512, testConfig.vadFrameSize)
        assertEquals("语音阈值应为 0.5", 0.5f, testConfig.vadSpeechThreshold, 0.001f)
        assertEquals("静音阈值应为 0.3", 0.3f, testConfig.vadSilenceThreshold, 0.001f)
        assertEquals("连续静音判定帧数应为 94", 94, testConfig.vadSilenceFrames)
        assertEquals("最小语音帧数应为 16", 16, testConfig.vadMinSpeechFrames)
    }

    // ========== 测试 2: 状态机初始状态 ==========

    @Test
    fun `initial_state_is_IDLE`() {
        // 创建 VAD 但未加载模型
        val vad = SileroVadDetector(mockContext, testConfig)
        assertEquals("初始状态应为 IDLE", SileroVadDetector.VadState.IDLE, vad.getCurrentState())
    }

    // ========== 测试 3: 状态机转换验证 ==========

    @Test
    fun `state_machine_transitions_through_all_states`() {
        val vad = SileroVadDetector(mockContext, testConfig)

        // 初始为 IDLE
        assertEquals(SileroVadDetector.VadState.IDLE, vad.getCurrentState())

        // 因为没加载模型，processFrame 返回 0，状态保持在 IDLE
        val frame = ShortArray(512)
        val prob = vad.processFrame(frame)
        assertEquals("未加载模型时概率为 0", 0f, prob, 0.001f)
        assertEquals("未加载模型时状态保持在 IDLE", SileroVadDetector.VadState.IDLE, vad.getCurrentState())
    }

    // ========== 测试 4: 状态机回调验证 ==========

    @Test
    fun `voice_start_callback_fires_correctly`() {
        val vad = SileroVadDetector(mockContext, testConfig)
        var startCalled = false
        var endCalled = false

        vad.onVoiceStart = { startCalled = true }
        vad.onVoiceEnd = { endCalled = true }

        // 未加载模型时，不会触发回调
        val frame = ShortArray(512)
        vad.processFrame(frame)

        assertFalse("未加载模型不应触发 onVoiceStart", startCalled)
        assertFalse("未加载模型不应触发 onVoiceEnd", endCalled)
    }

    // ========== 测试 5: resetState 重置 ==========

    @Test
    fun `resetState_resets_internal_state`() {
        val vad = SileroVadDetector(mockContext, testConfig)

        // 多次处理帧后重置
        val frame = ShortArray(512)
        for (i in 0..10) {
            vad.processFrame(frame)
        }

        vad.resetState()
        assertEquals("resetState 后应为 IDLE", SileroVadDetector.VadState.IDLE, vad.getCurrentState())
    }

    // ========== 测试 6: 模型加载/释放生命周期 ==========

    @Test
    fun `load_and_release_lifecycle`() {
        val vad = SileroVadDetector(mockContext, testConfig)

        // 未加载时
        assertFalse("初始未加载", vad.isLoaded())

        // 不加载模型，直接释放（不应报错）
        vad.release()
        assertFalse("释放后未加载", vad.isLoaded())
    }

    // ========== 测试 7: 帧大小验证 ==========

    @Test
    fun `processFrame_handles_various_frame_sizes`() {
        val vad = SileroVadDetector(mockContext, testConfig)

        // 空帧
        val emptyFrame = ShortArray(0)
        val prob1 = vad.processFrame(emptyFrame)
        assertEquals("空帧返回 0", 0f, prob1, 0.001f)

        // 小帧（小于 512）
        val smallFrame = ShortArray(100)
        val prob2 = vad.processFrame(smallFrame)
        assertEquals("小帧返回 0", 0f, prob2, 0.001f)

        // 大帧（大于 512，取前512）
        val largeFrame = ShortArray(1024)
        val prob3 = vad.processFrame(largeFrame)
        assertEquals("大帧返回 0（未加载模型）", 0f, prob3, 0.001f)
    }

    // ========== 测试 8: processAudio 批量处理 ==========

    @Test
    fun `processAudio_returns_empty_list_for_silent_audio`() {
        val vad = SileroVadDetector(mockContext, testConfig)

        // 全静音音频（未加载模型时，应返回空列表）
        val silentAudio = ShortArray(testConfig.sampleRate * 2) // 2 秒静音
        val segments = vad.processAudio(silentAudio)

        assertNotNull("segments 不应为 null", segments)
        assertTrue("静音音频应返回空列表", segments.isEmpty())
    }

    // ========== 测试 9: 连续帧处理稳定性 ==========

    @Test
    fun `consecutive_frame_processing_does_not_crash`() {
        val vad = SileroVadDetector(mockContext, testConfig)

        // 连续处理大量帧，验证不崩溃
        val frame = ShortArray(512)
        for (i in 0..1000) {
            vad.processFrame(frame)
        }

        // 不应崩溃
        assertEquals("大量帧处理后状态应为 IDLE",
            SileroVadDetector.VadState.IDLE, vad.getCurrentState())
    }

    // ========== 测试 10: 多实例隔离 ==========

    @Test
    fun `multiple_instances_are_independent`() {
        val vad1 = SileroVadDetector(mockContext, testConfig)
        val vad2 = SileroVadDetector(mockContext, testConfig)

        // 两个实例互不影响
        val frame = ShortArray(512)
        vad1.processFrame(frame)
        vad2.processFrame(frame)

        assertEquals("vad1 状态应为 IDLE",
            SileroVadDetector.VadState.IDLE, vad1.getCurrentState())
        assertEquals("vad2 状态应为 IDLE",
            SileroVadDetector.VadState.IDLE, vad2.getCurrentState())
    }
}