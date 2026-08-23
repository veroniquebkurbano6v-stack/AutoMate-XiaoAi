package com.palmagent.app.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.util.Log
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.palmagent.app.R
import com.palmagent.app.appCoordinator
import com.palmagent.app.agent.PlanFormatter
import com.palmagent.app.data.local.dao.SessionWithPreview
import com.palmagent.app.service.AccessibilityServiceHelper
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.service.GuiOwlService
import com.palmagent.app.service.RapidOcrService
import com.palmagent.app.service.DecisionDialogService
import com.palmagent.app.service.DecisionDialogService.DialogResult
import com.palmagent.app.service.VoiceInputManager
import com.palmagent.app.service.VoiceConfig
import com.palmagent.app.floating.FloatingProgressManager
import com.palmagent.app.TaskOrchestrator
import com.palmagent.app.ui.chat.ChatAdapter
import com.palmagent.app.ui.chat.ChatMessage
import com.palmagent.app.ui.chat.SessionAdapter
import com.palmagent.app.ui.guide.GuideActivity
import com.palmagent.app.ui.settings.SettingsActivity
import com.palmagent.app.ui.log.LogViewerActivity
import com.palmagent.app.ui.viewmodel.ChatViewModel
import com.palmagent.app.utils.KVUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeActivity : ComponentActivity() {

    companion object {
        private const val TAG = "HomeActivity"
    }

    private lateinit var btnCancelTask: Button
    private lateinit var cardTaskProgress: LinearLayout
    private lateinit var tvTaskText: TextView
    private lateinit var tvTaskRound: TextView
    private lateinit var progressTask: android.widget.ProgressBar

    // 聊天组件
    private lateinit var recyclerChat: androidx.recyclerview.widget.RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnVoiceInput: ImageButton
    private lateinit var chatAdapter: ChatAdapter
    private var shouldAutoScroll = true

    private val dialogService = DecisionDialogService()
    private val chatHistory = mutableListOf<ChatMessage>()

    // 语音输入（Phase 2）
    private val voiceInputManager: VoiceInputManager by lazy {
        VoiceInputManager(application, VoiceConfig.load(application))
    }
    private var voiceInputState = VoiceInputManager.RecordingState.IDLE
    private var micAnimator: AnimatorSet? = null
    /** 语音输入来源：true=悬浮窗麦克风触发（结果写悬浮窗输入框），false=主界面（结果写主界面输入框） */
    @Volatile
    private var voiceInputFromFloating = false
    private val voiceInputCallback = object : VoiceInputManager.VoiceInputCallback {
        override fun onStateChanged(state: VoiceInputManager.RecordingState) {
            voiceInputState = state
            // 更新按钮动画（录音中脉冲，空闲恢复）
            runOnUiThread {
                when (state) {
                    VoiceInputManager.RecordingState.WAITING_FOR_SPEECH,
                    VoiceInputManager.RecordingState.RECORDING -> {
                        startMicAnimation(true)
                        // 悬浮窗也启动动画
                        FloatingProgressManager.startMicAnimation()
                    }
                    VoiceInputManager.RecordingState.TRANSCRIBING -> {
                        startMicAnimation(false)
                        FloatingProgressManager.stopMicAnimation()
                        Toast.makeText(this@HomeActivity, "正在识别…", Toast.LENGTH_SHORT).show()
                    }
                    VoiceInputManager.RecordingState.ERROR -> {
                        startMicAnimation(false)
                        FloatingProgressManager.stopMicAnimation()
                        Toast.makeText(this@HomeActivity, "语音输入出错", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // IDLE
                        startMicAnimation(false)
                        FloatingProgressManager.stopMicAnimation()
                    }
                }
            }
        }
        override fun onVoiceInputResult(text: String) {
            if (text.isNotBlank()) {
                Log.d(TAG, "语音输入结果: $text (fromFloating=$voiceInputFromFloating)")
                runOnUiThread {
                    if (voiceInputFromFloating) {
                        // 悬浮窗麦克风触发：写入悬浮窗输入框（续写，不覆盖）；
                        // 悬浮窗不可用（未显示/已销毁）时回退写入主界面输入框，避免结果静默丢失
                        if (!FloatingProgressManager.setVoiceInputText(text)) {
                            insertIntoInput(text)
                        }
                    } else {
                        // 主界面触发：续写而非覆盖，在光标位置插入识别文本，保留输入框已有内容
                        insertIntoInput(text)
                    }
                }
            }
        }

        /** 将识别文本插入主界面输入框（光标处插入，保留已有内容，光标移到插入后） */
        private fun insertIntoInput(text: String) {
            val editable = etInput.text
            val selStart = etInput.selectionStart.coerceAtLeast(0)
            val selEnd = etInput.selectionEnd.coerceAtLeast(selStart)
            // 若存在选中文本则替换选中区，否则在光标处插入
            editable.replace(selStart, selEnd, text)
            // 光标移动到插入内容之后，便于继续输入
            etInput.setSelection(selStart + text.length)
            etInput.requestFocus()
            // 不自动发送，用户可自行编辑后发送
        }
        override fun onVoiceInputError(error: String) {
            Log.w(TAG, "语音输入错误: $error")
            runOnUiThread {
                Toast.makeText(this@HomeActivity, "语音识别失败: $error", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onVolumeChanged(volume: Float) {
            // 可选：更新 UI 音量指示器
        }
    }

    // 录音权限请求
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(TAG, "录音权限已授予")
            startVoiceInput()
        } else {
            Toast.makeText(this, "语音输入需要录音权限，请在设置中开启", Toast.LENGTH_LONG).show()
        }
    }

    // 会话持久化：会话列表/当前会话消息由 ChatViewModel 管理，HomeActivity 仅同步写库
    private val chatViewModel: ChatViewModel by viewModels()

    // 侧边抽屉（会话列表）
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var sessionAdapter: SessionAdapter
    private var isSessionInitialized = false

    // v3.2 Bug-7 修复：UI 存活标志，用于悬浮窗决策对话判断 HomeActivity 是否可见
    @Volatile
    private var isUiAlive = false

    private val handler = Handler(Looper.getMainLooper())
    // 【改动】状态检查 Runnable：原调用 updateAllPermissionStatus()（权限已移至设置页），改为仅刷新任务按钮显隐
    private val checkRunnable = object : Runnable {
        override fun run() {
            try {
                updateCancelTaskVisibility()
            } catch (e: Exception) {
                android.util.Log.e("HomeActivity", "状态检查异常", e)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        initViews()
        initDrawer()
        initChat()
        initChatSession()
        setupImeInsets()
        showGuideIfNeeded()
        tryRestoreAccessibility()

        // v3.2 Bug-W 修复：listener 在 onCreate 注册一次，避免 onResume/onPause 重设的 race window
        // 用 isUiAlive 控制是否处理回调，而非置 null
        appCoordinator.taskOrchestrator.taskStateListener = object : TaskOrchestrator.TaskStateListener {
            override fun onTaskStateChanged(running: Boolean) {
                if (!isUiAlive) return
                runOnUiThread { updateCancelTaskVisibility() }
            }
            // v3.2 Bug-3,4 修复：LOCAL 渠道下推送模型内容到主界面聊天 UI
            override fun onTaskContent(content: String, isFinal: Boolean) {
                // UI 不可见时不刷屏（悬浮窗路径已通过其他渠道展示）
                if (!isUiAlive) return
                // 仅显示最终答案，中间内容（isFinal=false）不刷屏
                if (isFinal && content.isNotBlank()) {
                    runOnUiThread {
                        val aiMsg = ChatMessage(content = content, isUser = false)
                        chatAdapter.addMessage(aiMsg) { scrollToBottom() }
                        persistMessage(aiMsg)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isUiAlive = true
        updateSystemWindowStatus()
        updateCancelTaskVisibility()
        // 【改动】从设置页返回后刷新模式按钮状态（原为 updateAllPermissionStatus()，权限已移至设置页）
        try {
            val modeContainer = findViewById<LinearLayout>(R.id.chipModeSwitch)
            val modeDot = findViewById<View>(R.id.modeDot)
            val tvModeText = findViewById<TextView>(R.id.tvModeText)
            updateModeSwitchUI(modeContainer, modeDot, tvModeText)
        } catch (_: Exception) { }
        startStatusCheck()
    }

    override fun onPause() {
        super.onPause()
        isUiAlive = false
        stopStatusCheck()
    }

    override fun onDestroy() {
        super.onDestroy()
        // v3.2 Bug-W 补丁：Activity 销毁时注销 listener，避免单例 TaskOrchestrator 持有已销毁 Activity 引用
        appCoordinator.taskOrchestrator.taskStateListener = null
        // Phase 2: 释放语音输入资源
        voiceInputManager.release()
    }

    private fun showGuideIfNeeded() {
        if (!KVUtils.isGuideShown()) {
            startActivity(Intent(this, GuideActivity::class.java))
        }
    }

    // 【改动】initViews：移除了原权限卡片初始化（cardAccessibility/cardNotification 等已移至 SettingsActivity）
    private fun initViews() {
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnCancelTask = findViewById(R.id.btnCancelTask)
        btnCancelTask.setOnClickListener {
            if (!appCoordinator.isTaskRunning()) {
                Toast.makeText(this, R.string.home_no_task_running, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // v2：加二次确认弹窗，避免误触
            AlertDialog.Builder(this)
                .setTitle("结束当前会话？")
                .setMessage("任务将立即终止，已完成的操作不可撤销。")
                .setPositiveButton("结束") { _, _ ->
                    appCoordinator.cancelCurrentTask()
                    Toast.makeText(this, R.string.home_cancel_task_success, Toast.LENGTH_SHORT).show()
                    updateCancelTaskVisibility()
                }
                .setNegativeButton("继续任务", null)
                .show()
        }

        cardTaskProgress = findViewById(R.id.cardTaskProgress)
        tvTaskText = findViewById(R.id.tvTaskText)
        tvTaskRound = findViewById(R.id.tvTaskRound)
        progressTask = findViewById(R.id.progressTask)
        cardTaskProgress.visibility = View.GONE

        // 【改动】日志按钮：原为权限容器内的 ImageButton，现改为顶部栏 TextView 文字按钮
        findViewById<TextView>(R.id.btnLogIcon).setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
    }

    /**
     * 初始化侧边抽屉（会话列表）：
     * - 汉堡按钮 → 打开抽屉
     * - 会话列表 RecyclerView：点击切换会话，长按删除会话
     * - "＋ 新建" → 新建会话并清空当前聊天界面
     */
    private fun initDrawer() {
        drawerLayout = findViewById(R.id.drawerLayout)

        findViewById<TextView>(R.id.btnSessions).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        val recyclerSessions = findViewById<RecyclerView>(R.id.recyclerSessions)
        sessionAdapter = SessionAdapter(
            onSessionClick = { session -> switchToSession(session.id) },
            onSessionLongClick = { session -> confirmDeleteSession(session) }
        )
        recyclerSessions.layoutManager = LinearLayoutManager(this)
        recyclerSessions.adapter = sessionAdapter

        findViewById<Button>(R.id.btnNewSession).setOnClickListener {
            createNewSession()
        }

        // 观察会话列表变化，实时刷新抽屉
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.sessions.collect { list ->
                    sessionAdapter.submitList(list)
                }
            }
        }
    }

    /**
     * 首次进入：无会话则新建，有会话则恢复最近一个会话的历史记录。
     * 使用一次性快照查询，避免依赖 sessions Flow 的初始空值时序。
     */
    private fun initChatSession() {
        lifecycleScope.launch {
            val sessions = chatViewModel.sessionsSnapshot()
            val targetId = sessions.firstOrNull()?.id ?: chatViewModel.createSession()
            val history = chatViewModel.loadMessages(targetId)
            chatHistory.clear()
            chatHistory.addAll(history)
            chatAdapter.updateMessages(history)
            isSessionInitialized = true
            scrollToBottom()
        }
    }

    /** 切换到指定会话：从库加载其消息并重建聊天界面 */
    private fun switchToSession(sessionId: String) {
        if (chatViewModel.currentSessionId.value == sessionId && isSessionInitialized) return
        drawerLayout.closeDrawer(GravityCompat.START)
        lifecycleScope.launch {
            val history = chatViewModel.loadMessages(sessionId)
            chatHistory.clear()
            chatHistory.addAll(history)
            chatAdapter.updateMessages(history)
            isSessionInitialized = true
            scrollToBottom()
        }
    }

    /** 新建会话：清空聊天界面，空历史写库 */
    private fun createNewSession() {
        drawerLayout.closeDrawer(GravityCompat.START)
        chatViewModel.createSession()
        chatHistory.clear()
        chatAdapter.updateMessages(emptyList())
        isSessionInitialized = true
    }

    /** 删除会话（长按触发，二次确认）；若删除的是当前会话则切到剩余最近会话或新建 */
    private fun confirmDeleteSession(session: SessionWithPreview) {
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("确定删除「${session.name}」？该会话的历史记录将一并删除，不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                val isCurrent = chatViewModel.currentSessionId.value == session.id
                chatViewModel.deleteSession(session.id)
                if (isCurrent) {
                    lifecycleScope.launch {
                        val remaining = chatViewModel.sessionsSnapshot().filter { it.id != session.id }
                        if (remaining.isEmpty()) {
                            createNewSession()
                        } else {
                            switchToSession(remaining.first().id)
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 将一条消息持久化到当前会话（覆盖同 id，幂等） */
    private fun persistMessage(message: ChatMessage) {
        chatViewModel.currentSessionId.value?.let { sessionId ->
            chatViewModel.persistMessage(sessionId, message)
        }
    }

    /**
     * 启动即自动恢复无障碍服务。
     * 需要一次性 ADB 授权 WRITE_SECURE_SETTINGS 后永久有效；未授权时静默跳过（由用户手动开启）。
     */
    private fun tryRestoreAccessibility() {
        try {
            if (!GUIAccessibilityService.isRunning &&
                AccessibilityServiceHelper.canWriteSecureSettings(this)
            ) {
                val restored = AccessibilityServiceHelper.ensureServiceEnabled(this)
                Log.d(TAG, "启动自动恢复无障碍服务: $restored")
            }
        } catch (e: Exception) {
            Log.w(TAG, "启动自动恢复无障碍服务异常: ${e.message}")
        }
    }

    /**
     * 【改动】初始化输入框旁的模式切换按钮
     * 原始：ToggleButton + setOnCheckedChangeListener
     * 改后：LinearLayout 药丸 + 圆点指示器（与悬浮窗同设计）
     * 简单模式：青色圆点 + 浅青药丸背景
     * 复杂模式：紫色圆点 + 浅紫药丸背景
     */
    private fun setupModeSwitch() {
        val modeContainer = findViewById<LinearLayout>(R.id.chipModeSwitch)
        val modeDot = findViewById<View>(R.id.modeDot)
        val tvModeText = findViewById<TextView>(R.id.tvModeText)

        // 根据 KVUtils 当前状态初始化外观
        updateModeSwitchUI(modeContainer, modeDot, tvModeText)

        modeContainer.setOnClickListener {
            val newMode = !KVUtils.isComplexModeEnabled()
            KVUtils.setComplexModeEnabled(newMode)
            updateModeSwitchUI(modeContainer, modeDot, tvModeText)
            Toast.makeText(this,
                if (newMode) "已切换到复杂模式" else "已切换到简单模式",
                Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 【改动】根据当前模式更新药丸按钮的外观（背景色、圆点色、文字）
     * 简单模式：bg_mode_pill_cyan + bg_dot_cyan + "简单"
     * 复杂模式：bg_mode_pill_violet + bg_dot_violet + "复杂"
     */
    private fun updateModeSwitchUI(
        container: LinearLayout,
        dot: View,
        text: TextView
    ) {
        val isComplex = KVUtils.isComplexModeEnabled()
        if (isComplex) {
            container.setBackgroundResource(R.drawable.bg_mode_pill_violet)
            dot.setBackgroundResource(R.drawable.bg_dot_violet)
            text.text = "复杂"
        } else {
            container.setBackgroundResource(R.drawable.bg_mode_pill_cyan)
            dot.setBackgroundResource(R.drawable.bg_dot_cyan)
            text.text = "简单"
        }
    }

    private fun initChat() {
        recyclerChat = findViewById(R.id.recyclerChat)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnVoiceInput = findViewById(R.id.btnVoiceInput)
        btnVoiceInput.setOnClickListener {
            voiceInputFromFloating = false   // 主界面麦克风：结果写主界面输入框
            startVoiceInput()
        }

        chatAdapter = ChatAdapter()
        recyclerChat.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerChat.adapter = chatAdapter

        // 问题卡提交回调：用户选择后不显示用户气泡，AI 内部接收答案继续决策对话
        // 答案拼成文本作为下一轮 userMessage 传回 chat()
        chatAdapter.onQuestionSubmit = { _, answers ->
            val answerText = answers.joinToString("；") { qa ->
                "${qa.question}：${qa.answer.joinToString("、")}"
            }
            // 内部 chat：不显示用户气泡，仅追加到 chatHistory 供决策模型读取
            continueDecisionDialog(answerText)
        }

        // 用户手动向上滑动查看历史消息时，暂停自动滚动
        recyclerChat.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                shouldAutoScroll = lastVisible >= chatAdapter.itemCount - 2
            }
        })

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                etInput.text.clear()
                sendMessage(text)
            }
        }

        // 模式切换按钮初始化
        setupModeSwitch()
    }

    private fun sendMessage(text: String) {
        shouldAutoScroll = true
        val userMsg = ChatMessage(content = text, isUser = true)
        chatAdapter.addMessage(userMsg) { scrollToBottom() }
        persistMessage(userMsg)

        if (KVUtils.isComplexModeEnabled()) {
            // 复杂模式：决策模型 → 执行模型
            runDecisionDialog(text, userMsgToShow = userMsg)
        } else {
            // 简单模式：直接执行模型（跳过决策模型，与悬浮窗行为一致）
            Log.d(TAG, "简单模式：直接执行任务")
            val aiMsg = ChatMessage(content = "好的，开始执行：$text", isUser = false)
            chatAdapter.addMessage(aiMsg) { scrollToBottom() }
            persistMessage(aiMsg)
            if (!appCoordinator.sendCommand(text)) {
                Toast.makeText(this, "有任务正在执行，请稍后再试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 问题卡提交后的内部续问：不显示用户气泡，仅追加到 chatHistory 供决策模型读取
     * 用于 ChatAdapter.onQuestionSubmit 回调，避免用户答案污染聊天界面
     */
    private fun continueDecisionDialog(answerText: String) {
        shouldAutoScroll = true
        val userMsg = ChatMessage(content = answerText, isUser = true)
        // 不调用 chatAdapter.addMessage(userMsg)：用户答案不显示为气泡
        chatHistory.add(userMsg)  // 仅加入历史供决策模型读取
        persistMessage(userMsg)
        runDecisionDialog(answerText, userMsgToShow = null)
    }

    /**
     * 执行决策对话（主界面路径）：思考中 → chat() → 处理结果
     * @param text 决策模型输入文本
     * @param userMsgToShow 需要加入 chatHistory 的用户消息（首次对话时传入，续问时为 null 因已加入）
     */
    private fun runDecisionDialog(
        text: String,
        userMsgToShow: ChatMessage?
    ) {
        val thinkingMsg = ChatMessage(content = "思考中...", isUser = false)
        chatAdapter.addMessage(thinkingMsg) { scrollToBottom() }

        lifecycleScope.launch {
            val result = dialogService.chat(text, chatHistory, chatViewModel.currentSessionId.value ?: "")
            chatAdapter.removeMessage(thinkingMsg) { scrollToBottom() }
            if (userMsgToShow != null) chatHistory.add(userMsgToShow)

            when (result) {
                is DialogResult.NeedMoreInfo -> {
                    if (result.questions != null) {
                        // 结构化问题卡：作为 AI 消息加入聊天，用户在卡内选择提交
                        val aiMsg = ChatMessage(
                            content = result.message.ifBlank { "请回答以下问题" },
                            isUser = false,
                            questions = result.questions
                        )
                        chatHistory.add(aiMsg)
                        persistMessage(aiMsg)
                        chatAdapter.addMessage(aiMsg) { scrollToBottom() }
                    } else {
                        val aiMsg = ChatMessage(content = result.message, isUser = false)
                        chatHistory.add(aiMsg)
                        persistMessage(aiMsg)
                        chatAdapter.addMessage(aiMsg) { scrollToBottom() }
                    }
                }
                is DialogResult.Ready -> {
                    // 简短确认语：不把复杂 Plan 步骤上屏，仅提示已开始执行（意图摘要作第二行）
                    val summaryLine = result.userSummary.takeIf { it.isNotBlank() }
                    val confirm = if (summaryLine != null) {
                        "我明白了，现在开始执行任务，请稍候\n\n$summaryLine"
                    } else {
                        "我明白了，现在开始执行任务，请稍候"
                    }
                    val aiMsg = ChatMessage(content = confirm, isUser = false)
                    chatHistory.add(aiMsg)
                    persistMessage(aiMsg)
                    chatAdapter.addMessage(aiMsg) { scrollToBottom() }

                    // v9: 决策模型的 plan 经 PlanFormatter 格式化为文本后传给执行模型
                    // 不再经过规划模型二次压缩，避免 SOP 导航细节在中间层丢失
                    appCoordinator.sendCommand(PlanFormatter.format(result.plan), result.plan)
                }
                is DialogResult.Error -> {
                    val aiMsg = ChatMessage(content = "出错了：${result.message}", isUser = false)
                    chatAdapter.addMessage(aiMsg) { scrollToBottom() }
                    persistMessage(aiMsg)
                }
            }
        }
    }

    /**
     * 悬浮窗任务发送回调：根据当前模式选择执行路径
     * v3.2 Bug-1,2,7,8 修复：同步写入主界面聊天 UI + 应用级 scope + NeedMoreInfo 不丢弃
     * - 复杂模式：走决策模型生成 plan，再传给执行模型
     * - 简单模式：直接传给执行模型（保持原有行为）
     */
    private fun handleFloatingCommand(text: String) {
        // v3.2 Bug-2,8 修复：悬浮窗路径同步写入主界面聊天 UI
        shouldAutoScroll = true
        val userMsg = ChatMessage(content = text, isUser = true)
        chatAdapter.addMessage(userMsg) { scrollToBottom() }
        persistMessage(userMsg)

        if (KVUtils.isComplexModeEnabled()) {
            Log.d(TAG, "悬浮窗-复杂模式：决策模型生成 plan")
            val thinkingMsg = ChatMessage(content = "思考中...", isUser = false)
            chatAdapter.addMessage(thinkingMsg) { scrollToBottom() }
            // v3.2 Bug-7 修复：用应用级 scope，避免 HomeActivity 销毁时决策对话被取消
            appCoordinator.launchDecision {
                val result = dialogService.chat(text, chatHistory, chatViewModel.currentSessionId.value ?: "")
                if (!isUiAlive) {
                    // v3.2 Bug-7：HomeActivity 不可见时，决策结果降级执行（不更新 UI）
                    // v3.2 Bug-3 修复：必须移除 thinkingMsg，避免 UI 残留"思考中..."
                    Log.d(TAG, "悬浮窗-决策对话完成但 UI 不可见，降级执行")
                    chatAdapter.removeMessage(thinkingMsg) { scrollToBottom() }
                    when (result) {
                        is DialogResult.Ready -> appCoordinator.sendCommand(PlanFormatter.format(result.plan), result.plan)
                        else -> appCoordinator.sendCommand(text)
                    }
                    return@launchDecision
                }
                chatAdapter.removeMessage(thinkingMsg) { scrollToBottom() }
                chatHistory.add(userMsg)
                when (result) {
                    is DialogResult.NeedMoreInfo -> {
                        // v3.2 Bug-1 修复：NeedMoreInfo 不再丢弃，显示为 AI 消息 + 加入 chatHistory
                        if (result.questions != null) {
                            // 结构化问题卡：作为 AI 消息加入聊天，用户在卡内选择提交
                            // 提交回调走 continueDecisionDialog（不显示用户气泡）
                            val aiMsg = ChatMessage(
                                content = result.message.ifBlank { "请回答以下问题" },
                                isUser = false,
                                questions = result.questions
                            )
                            chatHistory.add(aiMsg)
                            persistMessage(aiMsg)
                            chatAdapter.addMessage(aiMsg) { scrollToBottom() }
                        } else {
                            val aiMsg = ChatMessage(content = result.message, isUser = false)
                            chatHistory.add(aiMsg)
                            persistMessage(aiMsg)
                            chatAdapter.addMessage(aiMsg) { scrollToBottom() }
                        }
                    }
                    is DialogResult.Ready -> {
                        val summaryLine = result.userSummary.takeIf { it.isNotBlank() }
                        val confirm = if (summaryLine != null) {
                            "我明白了，现在开始执行任务，请稍候\n\n$summaryLine"
                        } else {
                            "我明白了，现在开始执行任务，请稍候"
                        }
                        val aiMsg = ChatMessage(content = confirm, isUser = false)
                        chatHistory.add(aiMsg)
                        persistMessage(aiMsg)
                        chatAdapter.addMessage(aiMsg) { scrollToBottom() }
                        appCoordinator.sendCommand(PlanFormatter.format(result.plan), result.plan)
                    }
                    is DialogResult.Error -> {
                        Log.w(TAG, "悬浮窗-决策模型失败: ${result.message}，降级为简单模式")
                        val aiMsg = ChatMessage(content = "决策失败，降级执行：$text", isUser = false)
                        chatAdapter.addMessage(aiMsg) { scrollToBottom() }
                        persistMessage(aiMsg)
                        appCoordinator.sendCommand(text)
                    }
                }
            }
        } else {
            Log.d(TAG, "悬浮窗-简单模式：直接执行任务")
            // v3.2 Bug-6 修复：UI 不可见时不再无效更新 chatAdapter，行为与复杂模式对称
            if (isUiAlive) {
                val aiMsg = ChatMessage(content = "好的，开始执行：$text", isUser = false)
                chatAdapter.addMessage(aiMsg) { scrollToBottom() }
                persistMessage(aiMsg)
            }
            if (!appCoordinator.sendCommand(text)) {
                if (isUiAlive) {
                    Toast.makeText(this, "有任务正在执行，请稍后再试", Toast.LENGTH_SHORT).show()
                } else {
                    Log.w(TAG, "悬浮窗-简单模式：sendCommand 被拒绝（有任务正在执行）")
                }
            }
        }
    }

    /**
     * 控制主界面麦克风按钮脉冲动画
     * @param recording true=启动脉冲动画，false=停止
     */
    private fun startMicAnimation(recording: Boolean) {
        if (!::btnVoiceInput.isInitialized) return
        if (recording) {
            if (micAnimator?.isRunning == true) return
            micAnimator?.cancel()
            // 激活态：切换为品牌渐变圆形背景 + 白色图标，视觉与发送按钮统一
            btnVoiceInput.setBackgroundResource(R.drawable.bg_mic_active)
            btnVoiceInput.setImageTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.WHITE
            ))
            // 柔和呼吸：两轴同步 1f→1.12f + 透明度 0.9→1.0，避免生硬的大幅缩放
            val animX = ObjectAnimator.ofFloat(btnVoiceInput, View.SCALE_X, 1f, 1.12f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
            val animY = ObjectAnimator.ofFloat(btnVoiceInput, View.SCALE_Y, 1f, 1.12f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
            val animAlpha = ObjectAnimator.ofFloat(btnVoiceInput, View.ALPHA, 0.9f, 1f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
            micAnimator = AnimatorSet().apply {
                playTogether(animX, animY, animAlpha)
                start()
            }
        } else {
            micAnimator?.cancel()
            micAnimator = null
            // 恢复空闲态：原背景 + 深色图标 + 复位缩放/透明度
            btnVoiceInput.setBackgroundResource(R.drawable.bg_icon_button)
            btnVoiceInput.setImageTintList(android.content.res.ColorStateList.valueOf(
                android.graphics.Color.parseColor("#1A1A2E")
            ))
            btnVoiceInput.scaleX = 1f
            btnVoiceInput.scaleY = 1f
            btnVoiceInput.alpha = 1f
        }
    }

    /**
     * 启动语音输入：检查权限 → 启动 VoiceInputManager
     * Phase 2: 语音输入入口
     */
    private fun startVoiceInput() {
        if (voiceInputState != VoiceInputManager.RecordingState.IDLE) {
            // 正在录音中 → 停止并转录（再次点击 = 结束录音并识别，而非丢弃）
            voiceInputManager.stopAndTranscribe()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val started = voiceInputManager.startVoiceInput(voiceInputCallback)
        if (!started) {
            Toast.makeText(this, "无法启动语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scrollToBottom() {
        if (!shouldAutoScroll) return
        recyclerChat.post {
            val itemCount = chatAdapter.itemCount
            if (itemCount > 0) {
                recyclerChat.scrollToPosition(itemCount - 1)
            }
        }
    }

    private fun updateSystemWindowStatus() {
        val enabled = Settings.canDrawOverlays(this)
        if (enabled && !FloatingProgressManager.isShowing()) {
            FloatingProgressManager.show(application)
        }
        if (enabled) {
            FloatingProgressManager.onFloatClick = {
                val intent = Intent(this, LogViewerActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            FloatingProgressManager.onProgressUpdate = { round, text ->
                runOnUiThread { updateProgressCard(round, text) }
            }
            FloatingProgressManager.onSendCommand = { text ->
                handleFloatingCommand(text)
            }
            // Phase 2: 悬浮窗麦克风按钮 → 启动语音输入（结果写入悬浮窗输入框）
            FloatingProgressManager.onVoiceInputClick = {
                voiceInputFromFloating = true
                startVoiceInput()
            }
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (!GuiOwlService.isReady) {
                            GuiOwlService.init()
                        }
                        if (!RapidOcrService.isReady) {
                            RapidOcrService.init(application)
                        }
                    }
                }
            }
        }
    }

    // 控制"结束任务"按钮的显示/隐藏
    private fun updateCancelTaskVisibility() {
        btnCancelTask.visibility = if (appCoordinator.isTaskRunning()) View.VISIBLE else View.GONE
    }

    private fun startStatusCheck() {
        stopStatusCheck()
        handler.postDelayed(checkRunnable, 1000)
    }

    private fun stopStatusCheck() {
        handler.removeCallbacks(checkRunnable)
    }

    private fun updateProgressCard(round: Int, text: String) {
        if (round < 0) {
            cardTaskProgress.visibility = View.GONE
            updateCancelTaskVisibility()
            return
        }
        cardTaskProgress.visibility = View.VISIBLE
        if (round > 0) {
            tvTaskRound.text = "第${round}轮"
            tvTaskRound.visibility = View.VISIBLE
        } else {
            tvTaskRound.visibility = View.GONE
        }
        tvTaskText.text = text
        updateCancelTaskVisibility()
    }

    private fun setupImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(recyclerChat) { view, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val bottomPadding = if (imeHeight > 0) imeHeight else navBarHeight

            if (view.paddingBottom != bottomPadding) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomPadding)
                if (imeHeight > 0) {
                    scrollToBottom()
                }
            }
            insets
        }
    }
}
