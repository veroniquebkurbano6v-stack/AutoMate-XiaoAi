package com.palmagent.app.agent

import com.palmagent.app.model.AgentAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * AgentLogger 统一日志引擎验证测试
 *
 * 验证目标：
 * 1. logRound 方法创建 round_N/ 目录及完整文件结构
 * 2. logTaskInfo 方法写入 task_info.txt
 * 3. 文件内容包含正确的模式标识和决策信息
 *
 * 技术方案：
 * - 通过反射设置 currentTaskDir 绕过 AgentApplication.instance 依赖
 * - 传入 screenshotJpegBytes=null 绕过 Bitmap 依赖
 * - 传入 screenInfo=null 绕过 ScreenInfo JSON 序列化
 * - isReturnDefaultValues=true 使 android.util.Log 返回默认值不抛异常
 */
class AgentLoggerTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "agent_logger_test")

        // 通过反射设置 currentTaskDir，绕过 AgentApplication.instance 依赖
        val currentTaskDirField = AgentLogger::class.java.getDeclaredField("currentTaskDir")
        currentTaskDirField.isAccessible = true
        currentTaskDirField.set(AgentLogger, tempDir)

        // 重置 endTaskCalled
        val endTaskCalledField = AgentLogger::class.java.getDeclaredField("endTaskCalled")
        endTaskCalledField.isAccessible = true
        endTaskCalledField.set(AgentLogger, false)

        // 重置 logFileWriter 为 null（不写 agent_full.log 时间线）
        val logFileWriterField = AgentLogger::class.java.getDeclaredField("logFileWriter")
        logFileWriterField.isAccessible = true
        logFileWriterField.set(AgentLogger, null)

        // 确保 isEnabled = true
        val isEnabledField = AgentLogger::class.java.getDeclaredField("isEnabled")
        isEnabledField.isAccessible = true
        isEnabledField.setBoolean(AgentLogger, true)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `logRound_creates_round_directory_with_complete_file_structure`() {
        val action = AgentAction(
            type = "locate",
            description = "点击微信群聊列表中的目标群",
            confidence = 0.9f,
            text = "东莞职业技术学院 422"
        )

        AgentLogger.logRound(
            round = 1,
            mode = "VL",
            screenInfo = null,
            screenshotJpegBytes = null,
            modelInput = "=== System Prompt ===\nVL Test\n=== User Prompt ===\n测试任务",
            modelOutput = """```json
{"type":"LOCATE","text":"目标群","description":"群聊列表","confidence":0.9}
```""",
            action = action,
            actionSuccess = true,
            actionResultSummary = "点击成功",
            planContext = Plan("帮我在微信中搜索群聊", "打开微信搜索群聊", listOf(PlanStep(1, "打开微信", "进入主页", false), PlanStep(2, "搜索群聊", "显示搜索结果", false)))
        )

        val roundDir = File(tempDir, "round_1")
        assertTrue("round_1/ 目录应被创建: ${tempDir.absolutePath}", roundDir.exists())
        assertTrue("model_input.txt 应存在", File(roundDir, "model_input.txt").exists())
        assertTrue("model_output.txt 应存在", File(roundDir, "model_output.txt").exists())
        assertTrue("decision.txt 应存在", File(roundDir, "decision.txt").exists())
        assertTrue("action_result.txt 应存在", File(roundDir, "action_result.txt").exists())
        assertTrue("plan_context.txt 应存在", File(roundDir, "plan_context.txt").exists())
    }

    @Test
    fun `logRound_decision_file_contains_mode_and_action_type`() {
        val action = AgentAction(
            type = "web_search",
            description = "搜索答案",
            confidence = 0.8f,
            text = "1+1等于几"
        )

        AgentLogger.logRound(
            round = 2,
            mode = "VL",
            screenInfo = null,
            screenshotJpegBytes = null,
            modelInput = "test input",
            modelOutput = "test output",
            action = action,
            actionSuccess = true,
            actionResultSummary = "搜索完成"
        )

        val decisionFile = File(File(tempDir, "round_2"), "decision.txt")
        assertTrue("decision.txt 应存在", decisionFile.exists())
        val content = decisionFile.readText()
        assertTrue("decision.txt 应包含 mode=VL", content.contains("mode=VL"))
        assertTrue("decision.txt 应包含 actionType=web_search", content.contains("actionType=web_search"))
        assertTrue("decision.txt 应包含 description", content.contains("搜索答案"))
    }

    @Test
    fun `logRound_text_mode_saves_enhanced_context_and_ocr`() {
        val action = AgentAction(
            type = "wait",
            description = "等待页面加载",
            confidence = 0.7f
        )

        AgentLogger.logRound(
            round = 3,
            mode = "TEXT",
            screenInfo = null,
            screenshotJpegBytes = null,
            modelInput = "full prompt text",
            modelOutput = "model output",
            action = action,
            actionSuccess = false,
            actionResultSummary = "等待超时",
            screenText = "屏幕文本内容",
            enhancedContext = "增强上下文内容"
        )

        val roundDir = File(tempDir, "round_3")
        assertTrue("screen_text.txt 应存在（文本模式）", File(roundDir, "screen_text.txt").exists())
        assertTrue("enhanced_context.txt 应存在（文本模式）", File(roundDir, "enhanced_context.txt").exists())

        val screenContent = File(roundDir, "screen_text.txt").readText()
        assertEquals("屏幕文本内容", screenContent)

        val ctxContent = File(roundDir, "enhanced_context.txt").readText()
        assertEquals("增强上下文内容", ctxContent)
    }

    @Test
    fun `logRound_vl_mode_does_not_save_ocr_and_enhanced_context`() {
        val action = AgentAction(
            type = "finish",
            description = "任务完成",
            confidence = 1.0f,
            text = "已完成"
        )

        AgentLogger.logRound(
            round = 4,
            mode = "VL",
            screenInfo = null,
            screenshotJpegBytes = null,
            modelInput = "vl input",
            modelOutput = "vl output",
            action = action,
            actionSuccess = true,
            actionResultSummary = "完成",
            screenText = "",
            enhancedContext = ""
        )

        val roundDir = File(tempDir, "round_4")
        assertTrue("VL 模式不应创建 screen_text.txt", !File(roundDir, "screen_text.txt").exists())
        assertTrue("VL 模式不应创建 enhanced_context.txt", !File(roundDir, "enhanced_context.txt").exists())
    }

    @Test
    fun `logTaskInfo_writes_mode_and_userPrompt`() {
        AgentLogger.logTaskInfo(
            mode = "VL",
            userPrompt = "帮我在微信中搜索群聊",
            planContext = Plan("帮我在微信中搜索群聊", "打开微信点击搜索", listOf(PlanStep(1, "打开微信", "进入主页", false), PlanStep(2, "点击搜索", "显示搜索入口", false)))
        )

        val taskInfoFile = File(tempDir, "task_info.txt")
        assertTrue("task_info.txt 应存在", taskInfoFile.exists())
        val content = taskInfoFile.readText()
        assertTrue("应包含 mode=VL", content.contains("mode=VL"))
        assertTrue("应包含 userPrompt", content.contains("帮我在微信中搜索群聊"))
        assertTrue("应包含 planContext", content.contains("planContext="))
    }

    @Test
    fun `logTaskInfo_skips_planContext_when_null`() {
        AgentLogger.logTaskInfo(
            mode = "TEXT",
            userPrompt = "简单任务",
            planContext = null
        )

        val content = File(tempDir, "task_info.txt").readText()
        assertTrue("应包含 mode=TEXT", content.contains("mode=TEXT"))
        assertTrue("不应包含 planContext（简单模式）", !content.contains("planContext"))
    }

    @Test
    fun `logRound_writes_screenshot_bytes_when_provided`() {
        val action = AgentAction(
            type = "wait",
            description = "测试",
            confidence = 0.5f
        )
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        AgentLogger.logRound(
            round = 5,
            mode = "VL",
            screenInfo = null,
            screenshotJpegBytes = fakeJpeg,
            modelInput = "input",
            modelOutput = "output",
            action = action,
            actionSuccess = true,
            actionResultSummary = "ok"
        )

        val screenshotFile = File(File(tempDir, "round_5"), "screenshot.jpg")
        assertTrue("screenshot.jpg 应存在", screenshotFile.exists())
        val bytes = screenshotFile.readBytes()
        assertEquals("screenshot.jpg 内容应与传入的 JPEG bytes 一致", fakeJpeg.size, bytes.size)
        assertEquals(0xFF.toByte(), bytes[0])
    }
}
