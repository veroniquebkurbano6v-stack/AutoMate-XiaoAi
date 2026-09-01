package com.palmagent.app.agent

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.palmagent.app.AgentApplication
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.model.AgentAction
import com.palmagent.app.model.ScreenInfo
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.service.ToolDecisionEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AgentLogger {

    private const val TAG = "AgentLogger"

    data class LogEntry(
        val timestamp: String,
        val type: LogType,
        val message: String,
        val data: String = "",
        val round: Int = 0
    )

    enum class LogType {
        SYSTEM,
        ACCESSIBILITY,
        SCREENSHOT,
        MODEL_INPUT,
        MODEL_OUTPUT,
        DECISION,
        ACTION,
        ERROR,
        TOOL_CALL,
        THINKING,
        GUI_PLUS_GROUNDING
    }

    /** 日志类型对应的 UI 显示前缀 */
    private val LogType.prefix: String
        get() = when (this) {
            LogType.SYSTEM -> "[系统]"
            LogType.ACCESSIBILITY -> "[无障碍]"
            LogType.SCREENSHOT -> "[截图]"
            LogType.MODEL_INPUT -> "[→AI输入]"
            LogType.MODEL_OUTPUT -> "[←AI输出]"
            LogType.DECISION -> "[决策]"
            LogType.ACTION -> "[动作]"
            LogType.ERROR -> "[‼错误]"
            LogType.TOOL_CALL -> "[工具]"
            LogType.THINKING -> "[💭思考]"
            LogType.GUI_PLUS_GROUNDING -> "[🎯GUI-Plus]"
        }

    private var currentTaskDir: File? = null
    private var logFileWriter: PrintWriter? = null
    private var endTaskCalled = false

    // ===== 任务级统计（endTask 时汇总为 summary.txt）=====
    private var currentTaskId: String = ""
    private var taskStartTimeMs: Long = 0L
    private var roundCount: Int = 0
    private var requestUserActionCount: Int = 0
    private var selfHealCount: Int = 0
    /** model -> [promptTokens, completionTokens]（决策/执行 LLM，来自 API usage 字段） */
    private val tokenUsageMap = LinkedHashMap<String, LongArray>()

    @Volatile
    var isEnabled: Boolean = true

    // P1-1 修复：SimpleDateFormat 非线程安全，用 ThreadLocal 包装避免并发崩溃
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    }
    private val fileDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    }

    fun beginTask(taskId: String) {
        try {
            val baseDir = AgentApplication.instance.getExternalFilesDir(null)?.let {
                File(it, "log")
            } ?: File(AgentApplication.instance.filesDir, "log")
            baseDir.mkdirs()

            val timestamp = fileDateFormat.get().format(Date())
            currentTaskDir = File(baseDir, "task_${taskId.take(20)}_$timestamp")
            currentTaskDir!!.mkdirs()

            val logFile = File(currentTaskDir, "agent_full.log")
            logFileWriter = PrintWriter(FileWriter(logFile, true), true)

            endTaskCalled = false
            currentTaskId = taskId
            taskStartTimeMs = System.currentTimeMillis()
            roundCount = 0
            requestUserActionCount = 0
            selfHealCount = 0
            tokenUsageMap.clear()

            writeHeader()
            log(LogType.SYSTEM, "任务开始", "taskId=$taskId")
            Log.d(TAG, "日志会话开始: ${currentTaskDir!!.absolutePath}")
            LiveLogBuffer.append("📝 日志会话已创建: ${currentTaskDir!!.name}")
        } catch (e: Exception) {
            Log.e(TAG, "初始化日志失败: ${e.message}")
        }
    }

    fun endTask(reason: String = "任务完成") {
        if (endTaskCalled) return
        endTaskCalled = true
        log(LogType.SYSTEM, "任务结束", reason)
        writeSummary(reason)
        logFileWriter?.flush()
        logFileWriter?.close()
        logFileWriter = null
        Log.d(TAG, "日志会话结束: $reason")
        LiveLogBuffer.append("📝 日志已保存: ${currentTaskDir?.name}")
        // v2：备份到公共目录（Documents/ 或 Download/，在自带文件管理器可见）
        backupToPublicDirectory()
    }

    /**
     * v2 优化：endTask 时同步拷贝到 /sdcard/Documents/PalmAgentLogs/ 和 /sdcard/Download/PalmAgentLogs/
     * 让用户在自带文件管理器中能看到日志文件（/sdcard/Android/data/... 路径在多数文件管理器不可见）
     */
    private fun backupToPublicDirectory() {
        val srcDir = currentTaskDir ?: return
        val taskFolderName = srcDir.name
        val backupTargets = listOf(
            "/sdcard/Documents/PalmAgentLogs/$taskFolderName" to "Documents/PalmAgentLogs",
            "/sdcard/Download/PalmAgentLogs/$taskFolderName" to "Download/PalmAgentLogs"
        )
        for ((targetPath, displayName) in backupTargets) {
            try {
                val target = File(targetPath)
                target.parentFile?.mkdirs()
                srcDir.copyRecursively(target, overwrite = true)
                Log.d(TAG, "日志已备份到公共目录 [$displayName]: ${target.absolutePath}")
                LiveLogBuffer.append("📁 日志已备份到 $displayName")
                return  // 备份成功一次即可
            } catch (e: Exception) {
                Log.w(TAG, "备份到 $displayName 失败: ${e.message}，尝试下一个目录")
            }
        }
    }

    /**
     * 记录任务基础信息（模式、用户任务、planContext）
     */
    fun logTaskInfo(mode: String, userPrompt: String, planContext: Plan?) {
        val taskDir = currentTaskDir ?: return
        val info = buildString {
            appendLine("mode=$mode")
            appendLine("userPrompt=$userPrompt")
            if (planContext != null) {
                appendLine("planContext=${PlanFormatter.formatForLog(planContext)}")
            }
        }
        try { File(taskDir, "task_info.txt").writeText(info) } catch (_: Exception) {}
    }

    /**
     * 获取或创建轮次目录 round_N/
     */
    private fun getOrCreateRoundDir(round: Int): File? {
        val taskDir = currentTaskDir ?: return null
        val roundDir = File(taskDir, "round_$round")
        if (!roundDir.exists()) roundDir.mkdirs()
        return roundDir
    }

    /**
     * 将截图压缩为 JPEG byte[]（从 ScreenDataLogger 移入）
     */
    fun compressScreenshot(bmp: Bitmap?): ByteArray? {
        if (bmp == null || bmp.isRecycled) return null
        return try {
            val scaledBmp = if (bmp.width > 720) {
                val ratio = 720f / bmp.width
                val newHeight = (bmp.height * ratio).toInt()
                Bitmap.createScaledBitmap(bmp, 720, newHeight, true)
            } else null
            val target = scaledBmp ?: bmp
            val bytes = ByteArrayOutputStream().use { baos ->
                target.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                baos.toByteArray()
            }
            if (scaledBmp != null) scaledBmp.recycle()
            bytes
        } catch (_: Exception) { null }
    }

    /**
     * 保存无障碍 UI 树信息为 JSON（从 ScreenDataLogger 移入）
     */
    private fun saveScreenInfo(screenInfo: ScreenInfo?, dir: File) {
        if (screenInfo == null) return
        try {
            val json = JSONObject().apply {
                put("currentPackage", screenInfo.currentPackage ?: "")
                put("currentActivity", screenInfo.currentActivity ?: "")
                put("elementCount", screenInfo.uiElements.size)
                val elements = JSONArray()
                screenInfo.uiElements.take(80).forEach { el ->
                    elements.put(JSONObject().apply {
                        put("id", el.id)
                        put("type", el.type.name)
                        put("text", el.text ?: "")
                        put("contentDescription", el.contentDescription ?: "")
                        put("isClickable", el.isClickable)
                        put("bounds", JSONObject().apply {
                            put("left", el.bounds.left)
                            put("top", el.bounds.top)
                            put("right", el.bounds.right)
                            put("bottom", el.bounds.bottom)
                        })
                    })
                }
                put("uiElements", elements)
            }
            File(dir, "screen_accessibility.json").writeText(json.toString(2))
        } catch (_: Exception) {}
    }

    /**
     * 统一轮次日志：保存一轮的完整数据到 round_N/ 目录
     * 所有模式（VL/文本）统一调用此方法，确保日志完整性
     */
    fun logRound(
        round: Int,
        mode: String,               // "VL" | "TEXT"
        screenInfo: ScreenInfo?,
        screenshotJpegBytes: ByteArray?,
        modelInput: String,          // 完整模型输入（system + user prompt）
        modelOutput: String,         // 模型原始输出
        action: AgentAction,
        actionSuccess: Boolean,
        actionResultSummary: String,
        screenText: String = "",
        enhancedContext: String = "",
        planContext: Plan? = null
    ) {
        val roundDir = getOrCreateRoundDir(round) ?: return
        roundCount++

        // 1. 截图
        screenshotJpegBytes?.let {
            try { File(roundDir, "screenshot.jpg").writeBytes(it) } catch (_: Exception) {}
        }

        // 2. 无障碍信息
        saveScreenInfo(screenInfo, roundDir)

        // 3. 屏幕文本（无障碍树提取）
        if (screenText.isNotBlank()) {
            try { File(roundDir, "screen_text.txt").writeText(screenText) } catch (_: Exception) {}
        }

        // 4. 模型输入
        try { File(roundDir, "model_input.txt").writeText(modelInput) } catch (_: Exception) {}

        // 5. 模型输出
        try { File(roundDir, "model_output.txt").writeText(modelOutput) } catch (_: Exception) {}

        // 6. 决策结果
        val decisionData = buildString {
            appendLine("timestamp=${dateFormat.get().format(Date())}")
            appendLine("mode=$mode")
            appendLine("actionType=${action.type}")
            appendLine("description=${action.description}")
            appendLine("confidence=${action.confidence}")
            action.coordinate?.let { appendLine("coordinate=(${it.x},${it.y})") }
            action.text?.let { appendLine("text=$it") }
            action.progress?.let { appendLine("progress=$it") }
        }
        try { File(roundDir, "decision.txt").writeText(decisionData) } catch (_: Exception) {}

        // 7. 执行结果
        val actionResult = buildString {
            appendLine("timestamp=${dateFormat.get().format(Date())}")
            appendLine("success=$actionSuccess")
            appendLine("result=$actionResultSummary")
        }
        try { File(roundDir, "action_result.txt").writeText(actionResult) } catch (_: Exception) {}

        // 8. planContext（如有）
        if (planContext != null) {
            try { File(roundDir, "plan_context.txt").writeText(PlanFormatter.format(planContext)) } catch (_: Exception) {}
        }

        // 9. enhanced context（文本模式如有）
        if (enhancedContext.isNotBlank()) {
            try { File(roundDir, "enhanced_context.txt").writeText(enhancedContext) } catch (_: Exception) {}
        }

        // 10. 推送模型输出摘要到 UI（开发阶段调试用）
        if (modelOutput.isNotBlank()) {
            LiveLogBuffer.append("[←AI输出] R$round: ${modelOutput.take(200)}")
        }

        // 11. 写入 agent_full.log 时间线
        val status = if (actionSuccess) "✓" else "✗"
        log(LogType.SYSTEM, "第${round}轮 [$mode] 完成",
            "$status ${action.type} - ${action.description} → $actionResultSummary", round)
    }

    fun log(type: LogType, message: String, data: String = "", round: Int = 0) {
        if (!isEnabled) return
        val timestamp = dateFormat.get().format(Date())
        val entry = LogEntry(timestamp, type, message, data, round)
        writeEntry(entry)

        // 同步摘要到 LiveLogBuffer（UI 可见）
        // 格式：[类型前缀] message，data 非空时附加前 200 字符
        val prefix = type.prefix
        val uiMessage = if (data.isNotBlank()) {
            "$prefix $message | ${data.take(200)}"
        } else {
            "$prefix $message"
        }
        LiveLogBuffer.append(uiMessage)
    }

    /**
     * 记录工具循环中的 LLM 输入到独立文件
     * 文件名: model_input_tool_M.txt（M 是工具循环序号，从 1 开始）
     * 触发场景: LOCATE / VISUAL_DESCRIBE 等工具调用后重新请求 AI 决策
     */
    fun logToolLoopModelInput(fullPrompt: String, round: Int, attempt: Int) {
        log(LogType.MODEL_INPUT, "工具循环第${attempt}次重新请求AI (第${round}轮)",
            "contextLength=${fullPrompt.length}\n${fullPrompt}", round)
        try {
            getOrCreateRoundDir(round)?.let { dir ->
                File(dir, "model_input_tool_${attempt}.txt").writeText(fullPrompt)
            }
        } catch (_: Exception) {}
    }

    fun logError(error: String, exception: Exception? = null, round: Int = 0) {
        val data = buildString {
            appendLine("error=$error")
            if (exception != null) {
                val sw = StringWriter()
                exception.printStackTrace(PrintWriter(sw))
                appendLine("stackTrace=${sw.toString().take(1000)}")
            }
        }
        log(LogType.ERROR, "错误: ${error.take(200)}", data, round)
    }

    fun logGuiOwlGrounding(
        instruction: String,
        result: GuiOwlService.GroundingResult,
        round: Int
    ) {
        val data = buildString {
            appendLine("instruction=$instruction")
            appendLine("success=${result.success}")
            result.coordinate?.let { appendLine("coordinate=(${it.x},${it.y})") }
            appendLine("durationMs=${result.durationMs}ms")
            if (result.thinking.isNotBlank()) appendLine("thinking=${result.thinking.take(500)}")
            if (result.answer.isNotBlank()) appendLine("answer=${result.answer}")
            if (result.error != null) appendLine("error=${result.error}")
        }
        val status = if (result.success) "✓" else "✗"
        log(LogType.GUI_PLUS_GROUNDING, "$status GUI-Plus[GROUND]: $instruction → ${result.answer.ifBlank { result.error ?: "" }} (${result.durationMs}ms)", data, round)

        try {
            getOrCreateRoundDir(round)?.let { dir ->
                val content = buildString {
                    appendLine("=== GUI-Plus Grounding 第${round}轮 ===")
                    appendLine("指令: $instruction")
                    appendLine("成功: ${result.success}")
                    appendLine("耗时: ${result.durationMs}ms")
                    appendLine()
                    if (result.thinking.isNotBlank()) {
                        appendLine("【推理过程】")
                        appendLine(result.thinking)
                        appendLine()
                    }
                    appendLine("【模型原始响应】")
                    appendLine(result.rawResponse)
                    appendLine()
                    appendLine("【解析结果】")
                    if (result.coordinate != null) {
                        appendLine("坐标: (${result.coordinate.x}, ${result.coordinate.y})")
                    }
                    if (result.answer.isNotBlank()) {
                        appendLine("答案: ${result.answer}")
                    }
                    if (result.error != null) {
                        appendLine("错误: ${result.error}")
                    }
                }
                File(dir, "gui_plus_grounding.txt").writeText(content)
            }
        } catch (_: Exception) {}
    }

    private fun writeHeader() {
        logFileWriter?.let { writer ->
            writer.println("=".repeat(80))
            writer.println("PalmAgent 代理日志")
            writer.println("开始时间: ${dateFormat.get().format(Date())}")
            writer.println("设备: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            writer.println("=".repeat(80))
            writer.println()
            writer.flush()
        }
    }

    private fun writeEntry(entry: LogEntry) {
        logFileWriter?.let { writer ->
            val prefix = when (entry.type) {
                LogType.SYSTEM -> "[系统]"
                LogType.ACCESSIBILITY -> "[无障碍]"
                LogType.SCREENSHOT -> "[截图]"
                LogType.MODEL_INPUT -> "[→AI输入]"
                LogType.MODEL_OUTPUT -> "[←AI输出]"
                LogType.DECISION -> "[决策]"
                LogType.ACTION -> "[动作]"
                LogType.ERROR -> "[‼错误]"
                LogType.TOOL_CALL -> "[工具]"
                LogType.THINKING -> "[💭思考]"
                LogType.GUI_PLUS_GROUNDING -> "[🎯GUI-Plus]"
            }
            val roundStr = if (entry.round > 0) " R${entry.round}" else ""
            writer.println("${entry.timestamp} $prefix$roundStr ${entry.message}")
            if (entry.data.isNotBlank()) {
                writer.println("  ${entry.data.replace("\n", "\n  ")}")
            }
            writer.println()
            writer.flush()
        }
    }

    fun getCurrentLogDir(): String? = currentTaskDir?.absolutePath

    fun getCurrentLogFilePath(): String? {
        return currentTaskDir?.let { File(it, "agent_full.log").absolutePath }
    }

    /** 敏感操作：request_user_action 触发计数（任务摘要用） */
    fun recordUserActionRequest() {
        requestUserActionCount++
    }

    /** 失败自愈事件计数（FailureCompactor 触发压缩时调用） */
    fun recordSelfHeal(round: Int, note: String) {
        selfHealCount++
        log(LogType.SYSTEM, "失败自愈 #$selfHealCount：$note", "round=$round")
    }

    /** 记录 LLM 调用的 token 用量（决策/执行模型，来自 API usage 字段），追加 token_usage.log 并汇总 */
    fun recordTokenUsage(model: String, promptTokens: Int, completionTokens: Int) {
        if (promptTokens <= 0 && completionTokens <= 0) return
        val arr = tokenUsageMap.getOrPut(model) { LongArray(2) }
        arr[0] += promptTokens
        arr[1] += completionTokens
        try {
            val dir = currentTaskDir ?: return
            val f = File(dir, "token_usage.log")
            f.appendText("${dateFormat.get().format(Date())} model=$model prompt_tokens=$promptTokens completion_tokens=$completionTokens\n")
        } catch (_: Exception) {}
    }

    /** endTask 时生成任务级摘要：评委一眼可核对轮数/耗时/结果/敏感操作/自愈/成本 */
    private fun writeSummary(reason: String) {
        val dir = currentTaskDir ?: return
        val durationSec = if (taskStartTimeMs > 0) (System.currentTimeMillis() - taskStartTimeMs) / 1000 else 0
        val sb = StringBuilder().apply {
            appendLine("=== 任务摘要（summary）===")
            appendLine("taskId=$currentTaskId")
            appendLine("开始时间: ${if (taskStartTimeMs > 0) dateFormat.get().format(Date(taskStartTimeMs)) else "未知"}")
            appendLine("结束时间: ${dateFormat.get().format(Date())}")
            appendLine("总耗时: ${durationSec} 秒")
            appendLine("轮数: $roundCount")
            appendLine("结果: $reason")
            appendLine("敏感操作: request_user_action 触发 $requestUserActionCount 次")
            appendLine("失败自愈: 失败压缩触发 $selfHealCount 次")
            appendLine("Token 用量（决策/执行 LLM，API usage 精确值）:")
            if (tokenUsageMap.isEmpty()) {
                appendLine("  （无记录——需 LLM 响应含 usage 字段）")
            } else {
                var totalPrompt = 0L
                var totalCompletion = 0L
                tokenUsageMap.forEach { (m, v) ->
                    appendLine("  $m: prompt=${v[0]}, completion=${v[1]}")
                    totalPrompt += v[0]
                    totalCompletion += v[1]
                }
                appendLine("  合计: prompt=$totalPrompt, completion=$totalCompletion, 总计=${totalPrompt + totalCompletion}")
            }
            appendLine("说明: VLM/GUI-Plus 图像 token 未计入（如需可后续接入），逐条明细见 token_usage.log")
        }
        try { File(dir, "summary.txt").writeText(sb.toString()) } catch (_: Exception) {}
    }
}
