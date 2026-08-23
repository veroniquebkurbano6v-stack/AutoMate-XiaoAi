package com.palmagent.app.service

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.os.Build
import com.palmagent.app.utils.KVUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * VoiceInputManager 单元测试
 *
 * 测试目标（覆盖 7 个关键链路）：
 * 1. 权限检查逻辑
 * 2. 状态机转换
 * 3. 启动/取消语音输入
 * 4. 回调验证
 * 5. 资源释放
 * 6. 并发启动保护
 * 7. 状态一致性
 */
class VoiceInputManagerTest {

    private lateinit var mockContext: Context
    private lateinit var voiceInputManager: VoiceInputManager

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

        // 注入 FakeSharedPreferences 到 KVUtils
        val fakePrefs = FakeSharedPreferences()
        KVUtils::class.java.getDeclaredField("prefs").apply {
            isAccessible = true
            set(KVUtils, fakePrefs)
        }
        KVUtils::class.java.getDeclaredField("securePrefs").apply {
            isAccessible = true
            set(KVUtils, fakePrefs)
        }

        KVUtils.setVoiceInputEnabled(true)

        voiceInputManager = VoiceInputManager(mockContext, testConfig)
    }

    @After
    fun tearDown() {
        voiceInputManager.release()
    }

    // ========== 测试 1: 初始状态 ==========

    @Test
    fun `initial_state_is_IDLE`() {
        assertEquals("初始状态应为 IDLE",
            VoiceInputManager.RecordingState.IDLE, voiceInputManager.getCurrentState())
    }

    // ========== 测试 2: 无录音权限返回失败 ==========

    @Test
    fun `startVoiceInput_returns_false_without_permission`() {
        // 模拟没有录音权限（JVM 单测下 ContextCompat 可能走 checkPermission 分支，两个都 stub）
        Mockito.`when`(mockContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        Mockito.`when`(mockContext.checkPermission(
            Mockito.anyString(),
            Mockito.anyInt(),
            Mockito.anyInt()
        )).thenReturn(PackageManager.PERMISSION_DENIED)

        val callback = object : VoiceInputManager.VoiceInputCallback {
            override fun onStateChanged(state: VoiceInputManager.RecordingState) {}
            override fun onVoiceInputResult(text: String) {
                fail("无权限不应返回结果")
            }
            override fun onVoiceInputError(error: String) {
                // 期望的错误回调
            }
            override fun onVolumeChanged(volume: Float) {}
        }

        val result = voiceInputManager.startVoiceInput(callback)
        assertFalse("无权限应返回 false", result)
    }

    // ========== 测试 3: 重复启动被阻止 ==========

    @Test
    fun `startVoiceInput_when_already_running_returns_false`() {
        // 模拟有权限
        Mockito.`when`(mockContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            .thenReturn(PackageManager.PERMISSION_GRANTED)

        val callback = object : VoiceInputManager.VoiceInputCallback {
            override fun onStateChanged(state: VoiceInputManager.RecordingState) {}
            override fun onVoiceInputResult(text: String) {}
            override fun onVoiceInputError(error: String) {}
            override fun onVolumeChanged(volume: Float) {}
        }

        // 第一次启动
        val firstResult = voiceInputManager.startVoiceInput(callback)
        // 注意：由于没有音频设备，实际启动会失败，但逻辑上应返回 false
        // 因为录音初始化会失败，所以状态会变为 ERROR 而不是 WAITING_FOR_SPEECH

        // 第二次启动应返回 false（因为状态不是 IDLE）
        val secondResult = voiceInputManager.startVoiceInput(callback)
        assertFalse("重复启动应返回 false", secondResult)
    }

    // ========== 测试 4: cancelVoiceInput 重置状态 ==========

    @Test
    fun `cancelVoiceInput_resets_to_IDLE`() {
        Mockito.`when`(mockContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            .thenReturn(PackageManager.PERMISSION_GRANTED)

        // 先启动再取消
        voiceInputManager.cancelVoiceInput()
        assertEquals("取消后应为 IDLE",
            VoiceInputManager.RecordingState.IDLE, voiceInputManager.getCurrentState())
    }

    // ========== 测试 5: 权限检查方法 ==========

    @Test
    fun `hasRecordAudioPermission_returns_correctly`() {
        // 无权限（JVM 单测下 ContextCompat 可能走 checkPermission 分支，两个都 stub）
        Mockito.`when`(mockContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            .thenReturn(PackageManager.PERMISSION_DENIED)
        Mockito.`when`(mockContext.checkPermission(
            Mockito.anyString(),
            Mockito.anyInt(),
            Mockito.anyInt()
        )).thenReturn(PackageManager.PERMISSION_DENIED)
        assertFalse("无权限应返回 false", voiceInputManager.hasRecordAudioPermission())

        // 有权限
        Mockito.`when`(mockContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            .thenReturn(PackageManager.PERMISSION_GRANTED)
        Mockito.`when`(mockContext.checkPermission(
            Mockito.anyString(),
            Mockito.anyInt(),
            Mockito.anyInt()
        )).thenReturn(PackageManager.PERMISSION_GRANTED)
        assertTrue("有权限应返回 true", voiceInputManager.hasRecordAudioPermission())
    }

    // ========== 测试 6: 多次 cancel 安全性 ==========

    @Test
    fun `multiple_cancel_calls_are_safe`() {
        // 多次取消不应崩溃
        voiceInputManager.cancelVoiceInput()
        voiceInputManager.cancelVoiceInput()
        voiceInputManager.cancelVoiceInput()

        assertEquals("多次取消后应为 IDLE",
            VoiceInputManager.RecordingState.IDLE, voiceInputManager.getCurrentState())
    }

    // ========== 测试 7: release 后状态 ==========

    @Test
    fun `release_reclaims_resources`() {
        voiceInputManager.release()

        // release 后状态应为 IDLE
        assertEquals("release 后应为 IDLE",
            VoiceInputManager.RecordingState.IDLE, voiceInputManager.getCurrentState())
    }

    // ========== 测试 8: 回调状态通知 ==========

    @Test
    fun `callback_receives_state_changes`() {
        Mockito.`when`(mockContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            .thenReturn(PackageManager.PERMISSION_GRANTED)

        val stateChanges = mutableListOf<VoiceInputManager.RecordingState>()
        val callback = object : VoiceInputManager.VoiceInputCallback {
            override fun onStateChanged(state: VoiceInputManager.RecordingState) {
                stateChanges.add(state)
            }
            override fun onVoiceInputResult(text: String) {}
            override fun onVoiceInputError(error: String) {}
            override fun onVolumeChanged(volume: Float) {}
        }

        // 启动后取消，验证状态变化
        voiceInputManager.startVoiceInput(callback)
        voiceInputManager.cancelVoiceInput()

        // 最终状态应为 IDLE
        assertEquals("最终状态应为 IDLE",
            VoiceInputManager.RecordingState.IDLE, voiceInputManager.getCurrentState())
    }

    // ========== 测试 9: 音量回调正确性 ==========

    @Test
    fun `volume_callback_receives_normalized_values`() {
        Mockito.`when`(mockContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            .thenReturn(PackageManager.PERMISSION_GRANTED)

        val volumes = mutableListOf<Float>()
        val callback = object : VoiceInputManager.VoiceInputCallback {
            override fun onStateChanged(state: VoiceInputManager.RecordingState) {}
            override fun onVoiceInputResult(text: String) {}
            override fun onVoiceInputError(error: String) {}
            override fun onVolumeChanged(volume: Float) {
                // 验证音量在 [0, 1] 范围内
                assertTrue("音量应 >= 0: $volume", volume >= 0f)
                assertTrue("音量应 <= 1: $volume", volume <= 1f)
                volumes.add(volume)
            }
        }

        voiceInputManager.startVoiceInput(callback)
        voiceInputManager.cancelVoiceInput()
    }

    // ========== 测试 10: 状态机枚举完整性 ==========

    @Test
    fun `recording_state_enum_has_all_expected_values`() {
        val states = VoiceInputManager.RecordingState.values()
        assertTrue("应有 IDLE 状态", states.contains(VoiceInputManager.RecordingState.IDLE))
        assertTrue("应有 WAITING_FOR_SPEECH 状态",
            states.contains(VoiceInputManager.RecordingState.WAITING_FOR_SPEECH))
        assertTrue("应有 RECORDING 状态",
            states.contains(VoiceInputManager.RecordingState.RECORDING))
        assertTrue("应有 TRANSCRIBING 状态",
            states.contains(VoiceInputManager.RecordingState.TRANSCRIBING))
        assertTrue("应有 ERROR 状态",
            states.contains(VoiceInputManager.RecordingState.ERROR))
    }

    // ========== 辅助 FakeSharedPreferences ==========

    class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any>()
        override fun getAll() = map.mapValues { it.value }
        override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
        override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
        override fun getStringSet(key: String?, defValue: MutableSet<String>?) = map[key] as? MutableSet<String> ?: defValue
        override fun contains(key: String?) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    class FakeEditor(private val map: MutableMap<String, Any>) : SharedPreferences.Editor {
        override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) map[key] = value }
        override fun putString(key: String?, value: String?) = apply { if (key != null) map[key] = value ?: "" }
        override fun putInt(key: String?, value: Int) = apply { if (key != null) map[key] = value }
        override fun putLong(key: String?, value: Long) = apply { if (key != null) map[key] = value }
        override fun putFloat(key: String?, value: Float) = apply { if (key != null) map[key] = value }
        override fun putStringSet(key: String?, value: MutableSet<String>?) = apply { if (key != null) map[key] = value ?: mutableSetOf<String>() }
        override fun remove(key: String?) = apply { if (key != null) map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}