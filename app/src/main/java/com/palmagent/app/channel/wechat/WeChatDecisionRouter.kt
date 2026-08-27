package com.palmagent.app.channel.wechat

import android.content.Context
import android.util.Log
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.TaskOrchestrator
import com.palmagent.app.channel.Channel
import com.palmagent.app.channel.ChannelManager
import com.palmagent.app.channel.TaskChannelHolder
import com.palmagent.app.data.local.AppDatabase
import com.palmagent.app.data.local.MessageMapper
import com.palmagent.app.data.repository.ChatRepository
import com.palmagent.app.model.Question
import com.palmagent.app.model.QuestionAnswer
import com.palmagent.app.model.QuestionOption
import com.palmagent.app.service.DecisionDialogService
import com.palmagent.app.ui.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * 微信决策路由器
 *
 * 将微信消息路由到决策模型（DecisionDialogService）而非直接执行任务。
 * 完整流程：微信消息 → 决策模型对话 →（信息充足）→ 执行模型 → 结果推回微信
 *
 * 状态机：
 * IDLE → DECIDING →（NeedMoreInfo）→ AWAITING_ANSWER → DECIDING → ...
 *                 →（Ready）→ EXECUTING → IDLE
 *                 →（Error）→ IDLE
 *
 * 所有微信通道产生的对话记录（用户消息、模型回复）均持久化到 App 主界面的
 * 对话记录系统，source = "WECHAT"，与本地对话记录统一管理。
 *
 * 交互工具适配：执行期间若决策模型需要向用户提问（ask_user / request_user_action），
 * 自动通过微信文本消息发送问题、接收用户文本回复，不弹出本地悬浮窗。
 */
class WeChatDecisionRouter(
    private val taskOrchestrator: TaskOrchestrator,
    context: Context
) {

    companion object {
        private const val TAG = "WeChatDecisionRouter"

        /** 全局实例引用，供 AskUserManager 等非 Hilt 组件访问 */
        @Volatile
        var instance: WeChatDecisionRouter? = null

        private const val WECHAT_SESSION_PREFIX = "wechat_"

        /** 执行期间 ask_user / request_user_action 的整体超时（5 分钟） */
        private const val INTERACTION_TIMEOUT_MS = 5 * 60 * 1000L
    }

    private val dialogService = DecisionDialogService()
    private val chatRepository: ChatRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 路由器状态 */
    enum class State {
        IDLE,             // 空闲，可接收新任务
        DECIDING,         // 决策模型运行中
        AWAITING_ANSWER,  // 等待用户回答追问
        EXECUTING         // 任务执行中
    }

    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var currentMessageID: String? = null

    /** 当前微信会话在 App DB 中的 sessionId */
    @Volatile
    private var wechatSessionId: String? = null

    /** 决策模型对话历史（供 DecisionDialogService.chat() 使用） */
    @Volatile
    private var chatHistory: MutableList<ChatMessage> = mutableListOf()

    /** 执行期间 ask_user / request_user_action 的待处理回调 */
    @Volatile
    private var pendingInteractionCallback: ((String) -> Unit)? = null

    init {
        instance = this
        val db = AppDatabase.getInstance(context)
        chatRepository = ChatRepository(db.sessionDao(), db.messageDao())

        // 注册任务完成回调：当微信通道的任务执行完成时，持久化最终回复并重置状态
        taskOrchestrator.weChatTaskFinishedCallback = { finalMessage ->
            onTaskCompleted(finalMessage)
        }
    }

    // ==================== 消息入口 ====================

    /**
     * 处理来自微信的消息
     *
     * 根据当前状态决定是新任务、追问回答、还是执行期间的用户回复：
     * - IDLE：开始新的决策对话
     * - AWAITING_ANSWER：作为追问回答继续决策对话
     * - DECIDING：拒绝（决策中，请等待）
     * - EXECUTING：检查是否为 ask_user 回复，否则拒绝（执行中）
     */
    fun handleMessage(message: String, messageID: String) {
        Log.d(TAG, "收到微信消息: state=$state, msg=${message.take(30)}")
        LiveLogBuffer.append("📨 微信消息到达: ${message.take(60)} [状态=$state]")

        when (state) {
            State.IDLE -> startNewDialog(message, messageID)
            State.AWAITING_ANSWER -> handleAnswer(message, messageID)
            State.DECIDING -> rejectBusy(messageID)
            State.EXECUTING -> handleExecutingMessage(message, messageID)
        }
    }

    // ==================== 决策对话流程 ====================

    /**
     * 开始新的决策对话（IDLE 状态触发）
     */
    private fun startNewDialog(message: String, messageID: String) {
        // 尝试获取任务锁，防止与本地任务并发
        if (!taskOrchestrator.tryAcquireTask(messageID, Channel.WECHAT)) {
            Log.w(TAG, "任务锁被占用，无法处理微信消息")
            sendToWeChat("有任务正在执行，请稍后再试", messageID)
            return
        }

        TaskChannelHolder.activeChannel = Channel.WECHAT
        currentMessageID = messageID
        state = State.DECIDING

        // 获取或创建微信会话
        val sessionId = getOrCreateWeChatSession()
        wechatSessionId = sessionId

        scope.launch {
            try {
                // 持久化用户消息
                val userMsg = ChatMessage(content = message, isUser = true, source = "WECHAT")
                chatHistory.add(userMsg)
                persistMessage(sessionId, userMsg)

                // 调用决策模型
                val result = dialogService.chat(message, chatHistory, sessionId)

                handleDecisionResult(result, message, messageID, sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "决策对话异常: ${e.message}")
                sendToWeChat("处理消息时出错: ${e.message}", messageID)
                taskOrchestrator.releaseTask()
                resetState()
            }
        }
    }

    /**
     * 处理追问回答（AWAITING_ANSWER 状态触发）
     */
    private fun handleAnswer(message: String, messageID: String) {
        val sessionId = wechatSessionId ?: run {
            Log.e(TAG, "AWAITING_ANSWER 状态但无 sessionId，回退为新对话")
            resetState()
            startNewDialog(message, messageID)
            return
        }

        state = State.DECIDING

        scope.launch {
            try {
                // 持久化用户回答
                val userMsg = ChatMessage(content = message, isUser = true, source = "WECHAT")
                chatHistory.add(userMsg)
                persistMessage(sessionId, userMsg)

                // 继续决策对话
                val result = dialogService.chat(message, chatHistory, sessionId)

                handleDecisionResult(result, message, messageID, sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "追问回答处理异常: ${e.message}")
                sendToWeChat("处理回答时出错: ${e.message}", messageID)
                taskOrchestrator.releaseTask()
                resetState()
            }
        }
    }

    /**
     * 处理决策模型返回结果
     */
    private suspend fun handleDecisionResult(
        result: DecisionDialogService.DialogResult,
        originalMessage: String,
        messageID: String,
        sessionId: String
    ) {
        when (result) {
            is DecisionDialogService.DialogResult.NeedMoreInfo -> {
                // 信息不足，需要追问
                val questionsText = formatQuestionsForWeChat(result)
                sendToWeChat(questionsText, messageID)

                val aiMsg = ChatMessage(
                    content = questionsText,
                    isUser = false,
                    source = "WECHAT",
                    questions = result.questions
                )
                chatHistory.add(aiMsg)
                persistMessage(sessionId, aiMsg)

                state = State.AWAITING_ANSWER
                Log.d(TAG, "进入 AWAITING_ANSWER 状态")
            }

            is DecisionDialogService.DialogResult.Ready -> {
                // 信息充足，开始执行
                val summaryLine = result.userSummary.takeIf { it.isNotBlank() }
                val confirmText = if (summaryLine != null) {
                    "好的，现在开始执行任务\n\n$summaryLine"
                } else {
                    "好的，现在开始执行任务"
                }
                sendToWeChat(confirmText, messageID)

                val aiMsg = ChatMessage(content = confirmText, isUser = false, source = "WECHAT")
                chatHistory.add(aiMsg)
                persistMessage(sessionId, aiMsg)

                state = State.EXECUTING
                Log.d(TAG, "进入 EXECUTING 状态，启动任务执行")

                // 通过 TaskOrchestrator 启动任务（任务锁已在 startNewDialog 中获取）
                taskOrchestrator.startNewTask(
                    Channel.WECHAT,
                    originalMessage,
                    messageID,
                    result.plan
                )
            }

            is DecisionDialogService.DialogResult.Error -> {
                val errorText = "出错了：${result.message}"
                sendToWeChat(errorText, messageID)

                val aiMsg = ChatMessage(content = errorText, isUser = false, source = "WECHAT")
                chatHistory.add(aiMsg)
                persistMessage(sessionId, aiMsg)

                taskOrchestrator.releaseTask()
                resetState()
            }
        }
    }

    /**
     * 处理执行期间收到的微信消息
     */
    private fun handleExecutingMessage(message: String, messageID: String) {
        // 优先检查是否有待处理的 ask_user / request_user_action 回调
        val callback = pendingInteractionCallback
        if (callback != null) {
            pendingInteractionCallback = null
            Log.d(TAG, "收到执行期间用户回复: ${message.take(30)}")
            callback.invoke(message)
            return
        }

        // 检查任务是否已结束（TaskOrchestrator 可能在异步线程完成并释放锁）
        if (!taskOrchestrator.isTaskRunning()) {
            Log.d(TAG, "任务已结束，作为新消息处理")
            // 先持久化上一个任务的最终回复（如果还未处理）
            resetState()
            startNewDialog(message, messageID)
            return
        }

        // 任务仍在执行中
        sendToWeChat("任务正在执行中，请稍候", messageID)
    }

    // ==================== 交互工具适配（AskUser / RequestUserAction） ====================

    /**
     * 通过微信向用户提问（执行期间 ActionExecutor.handleAskUser 调用）
     *
     * 将结构化问题格式化为文本发送到微信，等待用户文本回复。
     * 用户回复通过 handleExecutingMessage → pendingInteractionCallback 回调接收。
     */
    suspend fun askUserViaWeChat(questions: List<Question>): List<QuestionAnswer> {
        val messageID = currentMessageID ?: run {
            Log.w(TAG, "askUserViaWeChat: 无 currentMessageID")
            return emptyList()
        }

        // 格式化问题为微信文本
        val questionText = buildString {
            append("需要您回答以下问题：\n\n")
            questions.forEachIndexed { index, q ->
                append("${index + 1}. ${q.question}\n")
                if (q.options.isNotEmpty()) {
                    append("   选项：")
                    append(q.options.joinToString("、") { it.label })
                    append("\n")
                }
                append("   （请回复对应序号或具体内容）\n")
            }
        }

        sendToWeChat(questionText, messageID)
        LiveLogBuffer.append("❓ 微信端批量提问: ${questions.size} 个问题")

        // 持久化问题消息
        wechatSessionId?.let { sid ->
            val aiMsg = ChatMessage(
                content = questionText,
                isUser = false,
                source = "WECHAT",
                questions = questions
            )
            chatHistory.add(aiMsg)
            persistMessage(sid, aiMsg)
        }

        // 等待用户回复（5 分钟超时）
        val reply = withTimeoutOrNull(INTERACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                pendingInteractionCallback = { replyText ->
                    if (cont.isActive) {
                        cont.resumeWith(kotlin.Result.success(replyText))
                    }
                }
                cont.invokeOnCancellation {
                    pendingInteractionCallback = null
                }
            }
        }

        // 持久化用户回答
        if (reply != null) {
            wechatSessionId?.let { sid ->
                val userMsg = ChatMessage(content = reply, isUser = true, source = "WECHAT")
                chatHistory.add(userMsg)
                persistMessage(sid, userMsg)
            }
            LiveLogBuffer.append("💬 微信用户回答: ${reply.take(60)}")
            return parseReplyAsAnswers(reply, questions)
        }

        // 超时或取消
        sendToWeChat("等待回复超时，继续执行任务", messageID)
        return emptyList()
    }

    /**
     * 通过微信请求用户手动操作（执行期间 ActionExecutor.handleUserActionRequest 调用）
     *
     * 发送操作指引到微信，等待用户回复"完成"或"取消"。
     */
    suspend fun requestUserActionViaWeChat(guideText: String): Boolean {
        val messageID = currentMessageID ?: run {
            Log.w(TAG, "requestUserActionViaWeChat: 无 currentMessageID")
            return false
        }

        val fullText = buildString {
            append("需要您手动操作：\n")
            append(guideText)
            append("\n\n完成后请回复【完成】，取消请回复【取消】")
        }
        sendToWeChat(fullText, messageID)
        LiveLogBuffer.append("📋 微信端请求用户操作: ${guideText.take(60)}")

        // 持久化
        wechatSessionId?.let { sid ->
            val aiMsg = ChatMessage(content = fullText, isUser = false, source = "WECHAT")
            chatHistory.add(aiMsg)
            persistMessage(sid, aiMsg)
        }

        val reply = withTimeoutOrNull(INTERACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine<String?> { cont ->
                pendingInteractionCallback = { replyText ->
                    if (cont.isActive) {
                        cont.resumeWith(kotlin.Result.success(replyText))
                    }
                }
                cont.invokeOnCancellation {
                    pendingInteractionCallback = null
                }
            }
        }

        // 持久化用户回复
        if (reply != null) {
            wechatSessionId?.let { sid ->
                val userMsg = ChatMessage(content = reply, isUser = true, source = "WECHAT")
                chatHistory.add(userMsg)
                persistMessage(sid, userMsg)
            }
            val isCancel = reply.contains("取消") || reply.equals("cancel", ignoreCase = true)
            return !isCancel
        }

        return false
    }

    // ==================== 任务完成回调 ====================

    /**
     * 任务执行完成回调（由 TaskOrchestrator.weChatTaskFinishedCallback 触发）
     * 持久化最终回复并重置状态
     */
    private fun onTaskCompleted(finalMessage: String) {
        val sessionId = wechatSessionId
        if (sessionId != null && finalMessage.isNotBlank()) {
            scope.launch {
                val aiMsg = ChatMessage(
                    content = finalMessage,
                    isUser = false,
                    source = "WECHAT"
                )
                chatHistory.add(aiMsg)
                persistMessage(sessionId, aiMsg)
                Log.d(TAG, "任务最终回复已持久化")
            }
        }
        resetState()
        Log.d(TAG, "任务完成，状态重置为 IDLE")
    }

    // ==================== 辅助方法 ====================

    private fun resetState() {
        state = State.IDLE
        currentMessageID = null
        TaskChannelHolder.reset()
    }

    private fun rejectBusy(messageID: String) {
        sendToWeChat("正在处理中，请稍候", messageID)
    }

    private fun sendToWeChat(text: String, messageID: String?) {
        ChannelManager.sendMessage(Channel.WECHAT, text, messageID)
        ChannelManager.flushMessages(Channel.WECHAT)
    }

    /**
     * 获取或创建微信会话
     * 复用现有微信会话 ID，若不存在则创建新的
     */
    private fun getOrCreateWeChatSession(): String {
        val existing = wechatSessionId
        if (existing != null) {
            scope.launch { chatRepository.ensureSessionExists(existing, source = "WECHAT") }
            return existing
        }

        val sessionId = WECHAT_SESSION_PREFIX + UUID.randomUUID().toString()
        scope.launch { chatRepository.createSession(sessionId, source = "WECHAT") }
        return sessionId
    }

    /**
     * 持久化消息到 App DB
     */
    private suspend fun persistMessage(sessionId: String, message: ChatMessage) {
        try {
            chatRepository.ensureSessionExists(sessionId, source = "WECHAT")
            chatRepository.insertMessage(MessageMapper.uiToEntity(message, sessionId))
            chatRepository.touchSession(sessionId)

            // 自动命名：首条用户消息前 12 字作为会话标题
            if (message.isUser) {
                val sessions = chatRepository.getSessionsSnapshot()
                val s = sessions.firstOrNull { it.id == sessionId }
                if (s?.name == "新会话") {
                    val title = message.content.trim().take(12)
                    if (title.isNotEmpty()) {
                        chatRepository.renameSession(sessionId, title)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "消息持久化失败: ${e.message}")
        }
    }

    /**
     * 将决策模型的 NeedMoreInfo 格式化为微信文本
     */
    private fun formatQuestionsForWeChat(result: DecisionDialogService.DialogResult.NeedMoreInfo): String {
        val questions = result.questions
        return if (questions != null && questions.isNotEmpty()) {
            buildString {
                append(result.message.ifBlank { "请回答以下问题" })
                append("\n\n")
                questions.forEachIndexed { index, q ->
                    append("${index + 1}. ${q.question}\n")
                    if (q.options.isNotEmpty()) {
                        append("   选项：")
                        append(q.options.joinToString("、") { it.label })
                        append("\n")
                    }
                }
                append("\n请直接回复您的选择或答案。")
            }
        } else {
            result.message
        }
    }

    /**
     * 将用户微信文本回复解析为 QuestionAnswer 列表
     *
     * 支持的回复格式：
     * - 多行/分号分隔：每行/每段对应一个问题
     * - 单条文本：作为最后一个问题的答案
     * - 数字选项：匹配选项序号
     */
    private fun parseReplyAsAnswers(reply: String, questions: List<Question>): List<QuestionAnswer> {
        val lines = reply.split("\n", "；", ";", "，")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val answers = mutableListOf<QuestionAnswer>()

        questions.forEachIndexed { index, q ->
            val answerText = if (index < lines.size) {
                lines[index]
            } else if (index == questions.lastIndex) {
                // 行数不足时，取整条回复作为最后问题的答案
                reply.trim()
            } else {
                ""
            }

            // 尝试匹配选项：数字序号 → 选项 label
            val matchedOptions = matchOptions(answerText, q.options)
            val answerValues = if (matchedOptions.isNotEmpty()) {
                matchedOptions.map { it.label }
            } else {
                listOf(answerText)
            }

            answers.add(QuestionAnswer(question = q.question, answer = answerValues))
        }

        return answers
    }

    /**
     * 将用户文本回复与问题选项匹配
     * 支持：纯数字（1→第一个选项）、选项 label 直接匹配
     */
    private fun matchOptions(text: String, options: List<QuestionOption>): List<QuestionOption> {
        if (options.isEmpty()) return emptyList()

        val trimmed = text.trim()

        // 纯数字：作为序号匹配
        if (trimmed.matches(Regex("\\d+"))) {
            val num = trimmed.toInt()
            // 单数字直接匹配选项序号
            if (num in 1..options.size) {
                return listOf(options[num - 1])
            }
            // 多数字尝试作为多选（如"12"=选项1和2），仅当所有数字都有效时匹配
            val indices = trimmed.map { it.digitToInt() }
            if (indices.all { it in 1..options.size }) {
                return indices.map { options[it - 1] }
            }
        }

        // 文本匹配选项 label
        return options.filter { opt ->
            trimmed.contains(opt.label) || opt.label.contains(trimmed)
        }
    }
}
