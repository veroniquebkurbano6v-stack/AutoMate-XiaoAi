package com.palmagent.app.floating

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.palmagent.app.R
import com.palmagent.app.model.Question
import com.palmagent.app.model.QuestionAnswer
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

object FloatingProgressManager {

    private const val TAG = "FloatingProgress"

    enum class State {
        EDGE_HIDDEN,        // 边缘半隐藏（默认状态）
        CHIP,               // 迷你气泡
        IDLE,               // 展开的输入面板
        MINIMIZED,          // 执行中（小圆圈）
        USER_GUIDE,         // 用户引导
        PAUSED,             // 暂停
        USER_ACTION,        // 用户操作提示（替代原TOP_BANNER）
        ASK_USER            // v3.2：执行模型追问用户
    }

    private var isShowing = false
    private var currentState = State.EDGE_HIDDEN
    private var previousState = State.EDGE_HIDDEN

    private var appRef: Application? = null
    private var windowManager: WindowManager? = null
    private var floatRoot: View? = null
    private var currentEditText: EditText? = null

    private var layoutX = 0
    private var layoutY = 0

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging = false
    private var accumulatedDx = 0f
    private var accumulatedDy = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    // P1：自动收起定时器
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoCollapseJob: Job? = null
    private const val AUTO_COLLAPSE_DELAY = 30_000L // 30秒无操作自动收起

    var onFloatClick: () -> Unit = {}

    var onSendCommand: ((String) -> Unit)? = null

    var onProgressUpdate: ((round: Int, taskText: String) -> Unit)? = null

    /** 语音输入按钮点击回调 */
    var onVoiceInputClick: (() -> Unit)? = null

    // USER_ACTION 状态：保存进入前的状态
    private var savedStateBeforeUserAction: State = State.CHIP

    private var guideText: String = ""
    private var onGuideDone: (() -> Unit)? = null

    // v3.2：模型上一句回应（IDLE 状态显示，仅最终答案）
    @Volatile
    private var lastModelMessage: String = ""

    @Volatile
    private var showPending = false
    private var isInputFocused = false

    // 边缘吸附相关
    // 默认位置：右上侧适中（TOP | END），layoutY 在 show() 时初始化为屏幕高度 25%
    private var currentGravity = Gravity.TOP or Gravity.END
    private var isLeftSide = false
    private var autoHideJob: Job? = null
    private const val AUTO_HIDE_DELAY = 3_000L // 3秒无操作自动隐藏到边缘
    // EDGE_HIDDEN 贴边小条的呼吸点动画
    private var breatheAnimator: ObjectAnimator? = null

    /** 悬浮窗麦克风按钮（录音动画用） */
    private var micBtn: ImageView? = null

    /** 悬浮窗麦克风脉冲动画（AnimatorSet 统一管理 XY 轴，停止时可一并 cancel） */
    private var micAnimator: AnimatorSet? = null

    /** 启动麦克风录音脉冲动画 */
    fun startMicAnimation() {
        val btn = micBtn ?: return
        micAnimator?.cancel()
        val animX = ObjectAnimator.ofFloat(btn, View.SCALE_X, 1f, 1.25f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        val animY = ObjectAnimator.ofFloat(btn, View.SCALE_Y, 1f, 1.25f).apply {
            duration = 600
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        // 两轴统一放入 AnimatorSet：停止时一并 cancel，避免 SCALE_Y 动画泄漏导致动画停不掉
        micAnimator = AnimatorSet().apply {
            playTogether(animX, animY)
            start()
        }
    }

    /**
     * 将语音识别文本写入悬浮窗输入框（在光标处插入/续写，不覆盖已有内容）
     * 由外部（HomeActivity 语音回调）在悬浮窗麦克风输入场景下调用。
     *
     * @return true=已写入悬浮窗输入框；false=悬浮窗输入框不可用（未显示/已销毁），调用方应回退处理
     */
    fun setVoiceInputText(text: String): Boolean {
        val edit = currentEditText ?: return false
        mainHandler.post {
            val editable = edit.text
            val selStart = edit.selectionStart.coerceAtLeast(0)
            val selEnd = edit.selectionEnd.coerceAtLeast(selStart)
            editable.replace(selStart, selEnd, text)
            edit.setSelection(selStart + text.length)
        }
        return true
    }

    /** 停止麦克风录音脉冲动画 */
    fun stopMicAnimation() {
        micAnimator?.cancel()
        micAnimator = null
        micBtn?.apply {
            scaleX = 1f
            scaleY = 1f
        }
    }

    fun show(application: Application) {
        if (isShowing || showPending) return
        showPending = true
        appRef = application
        mainHandler.post {
            if (isShowing) { showPending = false; return@post }
            windowManager = application.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            // 初始化默认位置：右上侧适中（屏幕高度的 25%），仅首次显示时设置
            if (layoutY == 0) {
                val screenHeight = application.resources.displayMetrics.heightPixels
                layoutY = (screenHeight * 0.25f).toInt()
            }
            createUnifiedView()
            isShowing = true
            showPending = false
        }
    }

    fun hide() {
        mainHandler.post {
            removeCurrentView()
            isShowing = false
        }
    }

    fun isShowing(): Boolean = isShowing

    fun setIdleState() {
        mainHandler.post { setState(State.IDLE) }
    }

    private var onResumeCallback: (() -> Unit)? = null

    fun showPaused(onResume: () -> Unit) {
        onResumeCallback = onResume
        mainHandler.post { setState(State.PAUSED) }
    }

    fun isPaused(): Boolean = currentState == State.PAUSED

    fun showUserGuide(text: String, onDone: () -> Unit, onRejected: (() -> Unit)? = null) {
        UserActionManager.requestUserAction(
            request = UserActionManager.UserActionRequest(
                title = text,
                steps = emptyList()
            ),
            onResult = { response ->
                when (response.action) {
                    UserActionManager.UserActionResult.DONE -> onDone()
                    UserActionManager.UserActionResult.SKIP,
                    UserActionManager.UserActionResult.CANCEL -> onRejected?.invoke()
                }
            }
        )
    }

    fun hideUserGuide() {
        mainHandler.post {
            onGuideDone = null
            guideText = ""
            setState(State.EDGE_HIDDEN)
        }
    }

    // ======================== USER_ACTION 用户操作面板 ========================

    /**
     * 显示用户操作面板（悬浮窗），替代原 TOP_BANNER
     */
    fun showUserActionPanel(request: UserActionManager.UserActionRequest) {
        mainHandler.post {
            savedStateBeforeUserAction = currentState
            removeCurrentView()
            currentState = State.USER_ACTION
            createUnifiedView()
        }
    }

    /**
     * 刷新用户操作面板（展开/收起切换）
     */
    fun refreshUserActionPanel() {
        mainHandler.post {
            removeCurrentView()
            createUnifiedView()
        }
    }

    /**
     * 隐藏用户操作面板，恢复之前状态
     */
    fun hideUserActionPanel() {
        mainHandler.post {
            if (currentState != State.USER_ACTION) return@post
            val restore = savedStateBeforeUserAction
            removeCurrentView()
            currentState = if (restore == State.USER_ACTION || restore == State.ASK_USER) {
                State.CHIP
            } else {
                restore
            }
            createUnifiedView()
        }
    }

    fun enterMinimizedMode() {
        mainHandler.post {
            if (currentState == State.EDGE_HIDDEN) return@post
            previousState = currentState
            currentState = State.EDGE_HIDDEN
            removeCurrentView()
            createUnifiedView()
        }
    }

    /**
     * v3.2 Bug-5 修复：LOCAL 渠道执行中状态
     * 与 enterMinimizedMode 区别：不隐藏悬浮窗，切到 CHIP 状态保持可见但缩小
     */
    fun setExecutingState() {
        mainHandler.post {
            if (currentState == State.CHIP || currentState == State.EDGE_HIDDEN) return@post
            previousState = currentState
            setState(State.CHIP)
        }
    }

    /**
     * v3.2：更新模型上一句回应，IDLE 状态下立即刷新视图显示
     * 仅在 isFinal=true（最终答案）时调用，避免中间内容频繁刷新
     */
    fun setLastModelMessage(text: String) {
        lastModelMessage = text
        mainHandler.post {
            if (currentState == State.IDLE) {
                // 刷新当前 IDLE 视图以显示/更新"上一句"
                removeCurrentView()
                createUnifiedView()
            }
        }
    }

    // ======================== v3.2：ASK_USER 追问状态 ========================

    /**
     * v3.2：显示 ASK_USER 追问面板（简单模式执行模型追问）
     */
    fun showAskUserBanner(req: AskUserManager.AskRequest) {
        mainHandler.post {
            savedStateBeforeUserAction = currentState
            removeCurrentView()
            currentState = State.ASK_USER
            createUnifiedView()
        }
    }

    /**
     * v3.2：隐藏 ASK_USER 面板，恢复之前状态
     */
    fun hideAskUserBanner() {
        mainHandler.post {
            if (currentState != State.ASK_USER) return@post
            val restore = savedStateBeforeUserAction
            removeCurrentView()
            currentState = if (restore == State.ASK_USER || restore == State.USER_ACTION) {
                State.IDLE
            } else {
                restore
            }
            createUnifiedView()
        }
    }

    fun exitMinimizedMode() {
        mainHandler.post {
            if (currentState != State.EDGE_HIDDEN) return@post
            val restoreState = previousState
            previousState = State.CHIP
            setState(restoreState)
        }
    }

    fun isMinimized(): Boolean = currentState == State.EDGE_HIDDEN

    fun updateProgress(round: Int, taskText: String) {
        mainHandler.post {
            onProgressUpdate?.invoke(round, taskText)
        }
    }

    private fun setState(state: State) {
        currentState = state
        removeCurrentView()
        createUnifiedView()
    }

    /**
     * 构建悬浮窗通用背景：白色 + 浅青边框 + 全圆角
     * 贴合对话页浅色主题设计
     */
    private fun buildFloatBackground(density: Float, radiusDp: Float = 16f): GradientDrawable {
        return GradientDrawable().apply {
            setColor("#FFFFFFFF".toColorInt()) // 白色背景
            cornerRadius = radiusDp * density
            setStroke(
                (1 * density).toInt(),
                "#A5F3FC".toColorInt() // 浅青边框
            )
        }
    }

    /**
     * 构建 EDGE_HIDDEN 贴边小条背景：单侧圆角（贴边侧无圆角）+ 浅青边框 + 白底
     */
    private fun buildEdgeBackground(density: Float): GradientDrawable {
        val r = 12 * density
        return GradientDrawable().apply {
            setColor("#FFFFFFFF".toColorInt()) // 白色背景
            // isLeftSide=true 时贴左边：右侧圆角；isLeftSide=false 时贴右边：左侧圆角
            if (isLeftSide) {
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            } else {
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            }
            setStroke((1 * density).toInt(), "#A5F3FC".toColorInt())
        }
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun createUnifiedView() {
        val density = appRef?.resources?.displayMetrics?.density ?: 3f

        floatRoot = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 暗色半透明 + 青色边框 + 圆角（参考 HTML .float-panel 样式）
            background = buildFloatBackground(density)
            elevation = 8f * density
            val radius = (16 * density).toInt()
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius.toFloat())
                }
            }
            clipToOutline = true
            setPadding(
                (12 * density).toInt(),
                (8 * density).toInt(),
                (12 * density).toInt(),
                (8 * density).toInt()
            )
        }

        when (currentState) {
            State.EDGE_HIDDEN -> buildEdgeHiddenContent(density)
            State.CHIP -> buildChipContent(density)
            State.IDLE -> buildIdleContent(density)
            State.USER_GUIDE -> buildUserGuideContent(density)
            State.MINIMIZED -> buildMinimizedContent(density)
            State.PAUSED -> buildPausedContent(density)
            State.USER_ACTION -> buildUserActionContent(density)
            State.ASK_USER -> buildAskUserContent(density)
        }

        setFloatTouchListener()

        val height = (when (currentState) {
            State.EDGE_HIDDEN -> 64
            State.CHIP -> 48
            State.IDLE -> 105   // 【改动】IDLE 高度：原 150 → 105，缩小悬浮窗
            State.USER_GUIDE -> 180
            State.MINIMIZED -> 48
            State.PAUSED -> 64
            State.USER_ACTION -> 100
            State.ASK_USER -> 380
        } * density).toInt()

        addToWindow(height)
    }

    /**
     * 边缘半隐藏状态 — 默认状态
     * 只露出 24dp 宽的小条，点击展开为 CHIP
     */
    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun buildEdgeHiddenContent(density: Float) {
        val root = floatRoot as LinearLayout
        root.gravity = Gravity.CENTER
        root.setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        // 贴边小条背景：单侧圆角 + 青色边框（参考 HTML .float-edge）
        root.background = buildEdgeBackground(density)

        // 箭头：青色，指向屏幕内侧
        val arrow = TextView(appRef!!).apply {
            text = if (isLeftSide) "▶" else "◀"
            setTextColor("#22D3EE".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        root.addView(arrow)

        // 呼吸点：绿色圆点 + alpha 呼吸动画（参考 HTML .fe-dot @keyframes breathe）
        val dot = View(appRef!!).apply {
            setBackgroundColor("#34D399".toColorInt())
            val dotSize = (5 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                topMargin = (7 * density).toInt()
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        root.addView(dot)

        // 启动呼吸动画：1.6s 循环 1.0 ↔ 0.4
        breatheAnimator?.cancel()
        breatheAnimator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.4f).apply {
            duration = 1600L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    /**
     * P1：CHIP 状态 — 迷你气泡
     * 仅显示图标+名称，点击展开为 IDLE 输入面板
     */
    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun buildChipContent(density: Float) {
        val root = floatRoot as LinearLayout
        root.gravity = Gravity.CENTER
        root.setPadding(
            (10 * density).toInt(),
            (6 * density).toInt(),
            (10 * density).toInt(),
            (6 * density).toInt()
        )

        val chipRow = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val icon = TextView(appRef!!).apply {
            text = "🤖"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(0, 0, (6 * density).toInt(), 0)
        }

        val label = TextView(appRef!!).apply {
            text = "PalmAgent"
            setTextColor("#1A1A1A".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
        }

        chipRow.addView(icon)
        chipRow.addView(label)
        root.addView(chipRow)

        // 3秒无操作自动收缩到边缘
        resetAutoHideTimer()
    }

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun buildIdleContent(density: Float) {
        val root = floatRoot as LinearLayout

        // IDLE 态：浅色背景 + 浅青边框 + 18dp 圆角（贴合对话页浅色主题）
        root.background = buildFloatBackground(density, radiusDp = 18f)
        root.elevation = 6f * density
        val idleRadius = (18 * density).toInt()
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, idleRadius.toFloat())
            }
        }
        root.clipToOutline = true
        root.setPadding(
            (6 * density).toInt(),   // 【改动】水平 padding：原 8dp → 6dp
            (4 * density).toInt(),   // 【改动】垂直 padding：原 6dp → 4dp
            (6 * density).toInt(),
            (4 * density).toInt()
        )

        val topBar = createTopBar(density)
        root.addView(topBar)

        val space1 = View(appRef!!).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (3 * density).toInt()
            )
        }
        root.addView(space1)

        // v3.2：模型上一句回应（仅当有内容时显示，不展示用户输入）
        if (lastModelMessage.isNotBlank()) {
            val lastMsgView = TextView(appRef!!).apply {
                text = "🤖 $lastModelMessage"
                setTextColor("#666666".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(1 * density, 1.0f)
                setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            root.addView(lastMsgView)

            val spaceAfterMsg = View(appRef!!).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (4 * density).toInt()
                )
            }
            root.addView(spaceAfterMsg)
        }

        val inputRow = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val editText = EditText(appRef!!).apply {
            hint = "输入任务，如：打开微信发消息给张三"
            setHintTextColor("#BBBBBB".toColorInt())
            setTextColor("#1A1A1A".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)  // 【改动】输入框字号：原 14f → 13f
            // 浅色胶囊输入框背景（贴合对话页设计）
            background = GradientDrawable().apply {
                setColor("#F3F4F6".toColorInt())
                cornerRadius = 16 * density
                setStroke((1 * density).toInt(), "#E5E7EB".toColorInt())
            }
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(
                (10 * density).toInt(),   // 【改动】水平 padding：原 12dp → 10dp
                (7 * density).toInt(),    // 【改动】垂直 padding：原 10dp → 7dp
                (10 * density).toInt(),
                (7 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = (6 * density).toInt()
            }

            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) enableInputFocus() else disableInputFocus()
            }

            // 点击时直接触发 FLAG 切换，不依赖 onFocusChange（FLAG_NOT_FOCUSABLE 窗口下不可靠）
            setOnClickListener { enableInputFocus() }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val text = this.text.toString().trim()
                    if (text.isNotBlank()) {
                        onSendCommand?.invoke(text)
                        this.text.clear()
                        clearFocus()
                        // 【改动】发送指令后自动收起：原 collapseToChip()（显示气泡）→ collapseToEdge()（直接收起）
                        collapseToEdge()
                    }
                    true
                } else false
            }
        }
        currentEditText = editText
        inputRow.addView(editText)

        // 【改动】发送按钮：原 36dp → 30dp，缩小尺寸
        val sendBtn = ImageView(appRef!!).apply {
            setImageDrawable(ContextCompat.getDrawable(appRef!!, R.drawable.ic_send))
            background = ContextCompat.getDrawable(appRef!!, R.drawable.bg_idle_send)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener {
                val text = editText.text.toString().trim()
                if (text.isNotBlank()) {
                    onSendCommand?.invoke(text)
                    editText.text.clear()
                    editText.clearFocus()
                    // 【改动】发送指令后自动收起：原 collapseToChip() → collapseToEdge()
                    collapseToEdge()
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                (30 * density).toInt(),
                (30 * density).toInt()
            )
        }
        inputRow.addView(sendBtn)

        // 语音输入按钮（麦克风图标）
        val micBtn = ImageView(appRef!!).apply {
            // 使用自定义麦克风图标
            setImageDrawable(ContextCompat.getDrawable(appRef!!, R.drawable.ic_mic))
            scaleType = ImageView.ScaleType.CENTER
            val micColor = if (com.palmagent.app.utils.KVUtils.isVoiceInputEnabled())
                "#22D3EE".toColorInt() else "#CCCCCC".toColorInt()
            setColorFilter(micColor, PorterDuff.Mode.SRC_ATOP)
            setOnClickListener {
                // 触发语音输入（由外部注册的 onVoiceInputClick 回调处理）
                onVoiceInputClick?.invoke()
            }
            layoutParams = LinearLayout.LayoutParams(
                (30 * density).toInt(),
                (30 * density).toInt()
            ).apply {
                marginStart = (4 * density).toInt()
            }
        }
        // 保存引用以便动画控制
        this@FloatingProgressManager.micBtn = micBtn
        inputRow.addView(micBtn)

        root.addView(inputRow)

        // 30秒无操作自动收起到边缘
        resetAutoCollapseTimer()
    }

    @SuppressLint("SetTextI18n")
    private fun buildUserGuideContent(density: Float) {
        val root = floatRoot as LinearLayout

        val topBar = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            setBackgroundColor("#44FF9800".toColorInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val iconView = TextView(appRef!!).apply {
            text = "🖐"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
        }
        topBar.addView(iconView)

        val titleView = TextView(appRef!!).apply {
            text = "需要您手动操作"
            setTextColor("#FFB74D".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        topBar.addView(titleView)
        root.addView(topBar)

        val space = View(appRef!!).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * density).toInt()
            )
        }
        root.addView(space)

        val displayText = guideText.ifBlank { "请按照提示进行手动操作" }
        val guideView = TextView(appRef!!).apply {
            this.text = displayText
            setTextColor(Color.argb(230, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(4 * density, 1.1f)
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(guideView)

        val space2 = View(appRef!!).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * density).toInt()
            )
        }
        root.addView(space2)

        val doneBtn = TextView(appRef!!).apply {
            text = "✓ 已完成，继续执行"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundColor("#FF4CAF50".toColorInt())
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val callback = onGuideDone
                onGuideDone = null
                guideText = ""
                setState(State.EDGE_HIDDEN)
                callback?.invoke()
            }
        }
        root.addView(doneBtn)
    }

    @SuppressLint("SetTextI18n")
    private fun buildMinimizedContent(density: Float) {
        val root = floatRoot as LinearLayout
        root.gravity = Gravity.CENTER
        root.setPadding(
            (4 * density).toInt(),
            (4 * density).toInt(),
            (4 * density).toInt(),
            (4 * density).toInt()
        )

        val progress = android.widget.ProgressBar(appRef!!, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            val pbSize = (18 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(pbSize, pbSize)
        }
        root.addView(progress)
    }

    @SuppressLint("SetTextI18n")
    private fun buildPausedContent(density: Float) {
        val root = floatRoot as LinearLayout
        root.gravity = Gravity.CENTER
        root.setPadding(
            (8 * density).toInt(),
            (6 * density).toInt(),
            (8 * density).toInt(),
            (6 * density).toInt()
        )

        val pauseIcon = TextView(appRef!!).apply {
            text = "⏸"
            setTextColor("#FFB74D".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
        }
        root.addView(pauseIcon)

        val resumeBtn = TextView(appRef!!).apply {
            text = "继续任务"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(150, 76, 175, 80))
            setPadding(
                (10 * density).toInt(),
                (6 * density).toInt(),
                (10 * density).toInt(),
                (6 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                val cb = onResumeCallback
                onResumeCallback = null
                setState(State.EDGE_HIDDEN)
                cb?.invoke()
            }
        }
        root.addView(resumeBtn)
    }

    /**
     * v3.2：ASK_USER 追问面板
     * 布局：❓标题行 + 问题文本 + 可选选项按钮/输入框 + 发送/取消按钮
     */
    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun buildAskUserContent(density: Float) {
        val root = floatRoot as LinearLayout

        // 复用 IDLE 容器样式
        ContextCompat.getDrawable(appRef!!, R.drawable.bg_idle_container)?.let {
            root.background = it
        }
        root.elevation = 6f * density
        val askRadius = (20 * density).toInt()
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, askRadius.toFloat())
            }
        }
        root.clipToOutline = true
        root.setPadding(
            (10 * density).toInt(),
            (8 * density).toInt(),
            (10 * density).toInt(),
            (8 * density).toInt()
        )

        val questions = AskUserManager.currentRequest?.questions
        if (questions.isNullOrEmpty()) {
            // 防御性兜底：正常情况不会走到这里（ActionParser 已校验）
            val errorMsg = TextView(appRef!!).apply {
                text = "问题数据异常"
                setTextColor(Color.RED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }
            root.addView(errorMsg)
            return
        }
        val totalCount = questions.size

        // 标题行：❓ + "模型提问 (N 个问题)" + 关闭按钮
        val titleRow = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val iconView = TextView(appRef!!).apply {
            text = "❓"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
        }
        titleRow.addView(iconView)

        val titleView = TextView(appRef!!).apply {
            text = "模型提问 ($totalCount 个问题)"
            setTextColor("#FFB74D".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        titleRow.addView(titleView)

        val closeBtn = TextView(appRef!!).apply {
            text = "×"
            setTextColor(Color.argb(180, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setOnClickListener { AskUserManager.onUserCancel() }
            layoutParams = LinearLayout.LayoutParams(
                (32 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        titleRow.addView(closeBtn)
        root.addView(titleRow)

        // 间距
        val space1 = View(appRef!!).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * density).toInt()
            )
        }
        root.addView(space1)

        // ScrollView 容纳多问题卡
        val scrollView = ScrollView(appRef!!).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val cardsContainer = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 每个问题的答案收集器（返回 null 表示未作答）
        val answerCollectors = mutableListOf<() -> QuestionAnswer?>()

        // 为每个问题构建独立卡片
        questions.forEachIndexed { idx, q ->
            val card = buildQuestionCard(q, idx, totalCount, density, answerCollectors)
            cardsContainer.addView(card)
        }

        scrollView.addView(cardsContainer)
        root.addView(scrollView)

        // 间距
        val space2 = View(appRef!!).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * density).toInt()
            )
        }
        root.addView(space2)

        // 底部按钮行：[全部提交] + [取消]
        val submitBtn = TextView(appRef!!).apply {
            text = "全部提交"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundColor("#FF4CAF50".toColorInt())
            setPadding(
                (16 * density).toInt(),
                (8 * density).toInt(),
                (16 * density).toInt(),
                (8 * density).toInt()
            )
            setOnClickListener {
                val answers = answerCollectors.mapNotNull { it() }
                if (answers.size < totalCount) {
                    Toast.makeText(appRef, "还有 ${totalCount - answers.size} 个问题未作答", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                AskUserManager.onUserAnswer(answers)
            }
        }

        val cancelBtn = TextView(appRef!!).apply {
            text = "取消"
            setTextColor(Color.argb(180, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(60, 255, 255, 255))
            setPadding(
                (16 * density).toInt(),
                (8 * density).toInt(),
                (16 * density).toInt(),
                (8 * density).toInt()
            )
            setOnClickListener { AskUserManager.onUserCancel() }
        }

        val btnRow = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val submitParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = (8 * density).toInt() }
        btnRow.addView(cancelBtn)
        btnRow.addView(submitBtn, submitParams)
        root.addView(btnRow)
    }

    /**
     * 构建单个问题卡片
     * @param q 问题数据
     * @param idx 问题序号（0-based）
     * @param total 问题总数
     * @param density 屏幕密度
     * @param answerCollectors 答案收集器列表，本卡片的收集器会被追加到此列表
     */
    @SuppressLint("SetTextI18n")
    private fun buildQuestionCard(
        q: Question,
        idx: Int,
        total: Int,
        density: Float,
        answerCollectors: MutableList<() -> QuestionAnswer?>
    ): LinearLayout {
        val card = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(appRef!!, R.drawable.bg_idle_container)
            setPadding(
                (10 * density).toInt(),
                (8 * density).toInt(),
                (10 * density).toInt(),
                (8 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * density).toInt() }
        }

        // 卡片头部：[i/N] + header
        val headerRow = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val indexView = TextView(appRef!!).apply {
            text = "[${idx + 1}/$total]"
            setTextColor("#FFB74D".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
        }
        headerRow.addView(indexView)

        val headerView = TextView(appRef!!).apply {
            text = q.header
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        headerRow.addView(headerView)
        card.addView(headerRow)

        // 问题文本
        val questionView = TextView(appRef!!).apply {
            text = q.question
            setTextColor(Color.argb(230, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(2 * density, 1.1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
        }
        card.addView(questionView)

        // 选项区
        if (q.multiSelect) {
            // 多选：CheckBox 列表
            val checkBoxes = mutableListOf<CheckBox>()
            q.options.forEach { opt ->
                val cb = CheckBox(appRef!!).apply {
                    text = if (opt.recommended) "${opt.label} (推荐)" else opt.label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = if (opt.recommended) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                }
                checkBoxes.add(cb)
                card.addView(cb)
            }

            // 自定义输入选项（如果允许）
            var customCheckBox: CheckBox? = null
            var customEdit: EditText? = null
            if (q.allowFreeInput) {
                customCheckBox = CheckBox(appRef!!).apply {
                    text = "自定义输入"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                card.addView(customCheckBox)
                customEdit = EditText(appRef!!).apply {
                    hint = "输入自定义答案..."
                    setHintTextColor(Color.argb(140, 255, 255, 255))
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    background = ContextCompat.getDrawable(appRef!!, R.drawable.bg_idle_edittext)
                    maxLines = 2
                    visibility = View.GONE
                    setPadding(
                        (8 * density).toInt(),
                        (6 * density).toInt(),
                        (8 * density).toInt(),
                        (6 * density).toInt()
                    )
                    setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) enableInputFocus() else disableInputFocus()
                    }
                    setOnClickListener { enableInputFocus() }
                }
                card.addView(customEdit)
                val editRef = customEdit
                customCheckBox.setOnCheckedChangeListener { _, isChecked ->
                    editRef.visibility = if (isChecked) View.VISIBLE else View.GONE
                    if (isChecked) editRef.requestFocus()
                }
            }

            // 收集器：多选
            val cbList = checkBoxes
            val cCb = customCheckBox
            val cEdit = customEdit
            answerCollectors.add {
                val selected = cbList.filter { it.isChecked }
                    .map { it.text.toString().replace(" (推荐)", "") }
                    .toMutableList()
                if (cCb?.isChecked == true) {
                    val customText = cEdit?.text?.toString()?.trim()
                    if (!customText.isNullOrBlank()) selected.add(customText)
                }
                if (selected.isEmpty()) null
                else QuestionAnswer(question = q.question, answer = selected.toList())
            }
        } else {
            // 单选：RadioGroup
            val radioGroup = RadioGroup(appRef!!).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            q.options.forEachIndexed { optIdx, opt ->
                val rb = RadioButton(appRef!!).apply {
                    text = if (opt.recommended) "${opt.label} (推荐)" else opt.label
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = if (opt.recommended) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                    id = optIdx + 1  // RadioGroup 要求唯一 id
                }
                radioGroup.addView(rb)
            }

            // 自定义输入选项（如果允许）
            var customRadioButton: RadioButton? = null
            var customEdit: EditText? = null
            if (q.allowFreeInput) {
                customRadioButton = RadioButton(appRef!!).apply {
                    text = "自定义输入"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    id = q.options.size + 1
                }
                radioGroup.addView(customRadioButton)
                customEdit = EditText(appRef!!).apply {
                    hint = "输入自定义答案..."
                    setHintTextColor(Color.argb(140, 255, 255, 255))
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    background = ContextCompat.getDrawable(appRef!!, R.drawable.bg_idle_edittext)
                    maxLines = 2
                    visibility = View.GONE
                    setPadding(
                        (8 * density).toInt(),
                        (6 * density).toInt(),
                        (8 * density).toInt(),
                        (6 * density).toInt()
                    )
                    setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) enableInputFocus() else disableInputFocus()
                    }
                    setOnClickListener { enableInputFocus() }
                }
                card.addView(customEdit)
                val editRef = customEdit
                val customRbRef = customRadioButton
                radioGroup.setOnCheckedChangeListener { _, checkedId ->
                    editRef.visibility = if (checkedId == customRbRef?.id) View.VISIBLE else View.GONE
                    if (checkedId == customRbRef?.id) editRef.requestFocus()
                }
            }

            card.addView(radioGroup)

            // 收集器：单选
            val options = q.options
            val cRb = customRadioButton
            val cEdit = customEdit
            answerCollectors.add {
                val checkedId = radioGroup.checkedRadioButtonId
                if (checkedId == -1) {
                    null
                } else if (cRb != null && checkedId == cRb.id) {
                    val customText = cEdit?.text?.toString()?.trim()
                    if (customText.isNullOrBlank()) null
                    else QuestionAnswer(question = q.question, answer = listOf(customText))
                } else {
                    val optIdx = checkedId - 1
                    if (optIdx in options.indices) {
                        QuestionAnswer(question = q.question, answer = listOf(options[optIdx].label))
                    } else null
                }
            }
        }
        return card
    }

    /**
     * 【改动】顶部栏构建：模式切换按钮和收起按钮均改版
     * 模式切换：原 ImageView 图标 → LinearLayout 药丸+圆点+文字
     * 收起按钮：原 ImageView 图标 → TextView 纯文字"收起 ▾"
     */
    private fun createTopBar(density: Float): LinearLayout {
        val topBar = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 【改动】模式切换按钮：原 ImageView(ic_complex/ic_simple) → 药丸+圆点+文字，颜色暗示当前模式
        val modeContainer = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 半透明青色药丸背景，轻盈不突兀
            background = GradientDrawable().apply {
                val isComplex = KVUtils.isComplexModeEnabled()
                // 简单模式：青色调，复杂模式：紫色调，用颜色暗示当前状态
                setColor(if (isComplex) "#F3E8FF".toColorInt() else "#E0F7FA".toColorInt())
                cornerRadius = 12 * density
            }
            setPadding(
                (8 * density).toInt(),
                (3 * density).toInt(),
                (8 * density).toInt(),
                (3 * density).toInt()
            )
            setOnClickListener {
                val newMode = !KVUtils.isComplexModeEnabled()
                KVUtils.setComplexModeEnabled(newMode)
                // 刷新背景色
                (background as? GradientDrawable)?.setColor(
                    if (newMode) "#F3E8FF".toColorInt() else "#E0F7FA".toColorInt()
                )
                // 刷新圆点和文字
                val dot = getChildAt(0) as View
                dot.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (newMode) "#8B5CF6".toColorInt() else "#22D3EE".toColorInt())
                }
                (getChildAt(1) as TextView).text = if (newMode) "复杂" else "简单"
                Toast.makeText(appRef!!,
                    if (newMode) "已切换到复杂模式" else "已切换到简单模式",
                    Toast.LENGTH_SHORT).show()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (8 * density).toInt()
            }
        }

        // 圆点指示器：简单模式青色，复杂模式紫色
        val modeDot = View(appRef!!).apply {
            val isComplex = KVUtils.isComplexModeEnabled()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isComplex) "#8B5CF6".toColorInt() else "#22D3EE".toColorInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                (5 * density).toInt(),
                (5 * density).toInt()
            ).apply {
                marginEnd = (4 * density).toInt()
            }
        }
        modeContainer.addView(modeDot)

        // 模式文字
        val modeText = TextView(appRef!!).apply {
            text = if (KVUtils.isComplexModeEnabled()) "复杂" else "简单"
            setTextColor("#555555".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
        }
        modeContainer.addView(modeText)
        topBar.addView(modeContainer)

        // 【改动】收起按钮：原 ImageView(ic_chevron_down)+灰色胶囊 → TextView 纯文字"收起 ▾"，无背景轻量感
        val collapseBtn = TextView(appRef!!).apply {
            text = "收起 ▾"
            setTextColor("#999999".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            setPadding(
                (4 * density).toInt(),
                (3 * density).toInt(),
                (4 * density).toInt(),
                (3 * density).toInt()
            )
            setOnClickListener { collapseToEdge() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        topBar.addView(collapseBtn)

        return topBar
    }

    private fun setFloatTouchListener() {
        floatRoot?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    accumulatedDx = 0f
                    accumulatedDy = 0f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    accumulatedDx += abs(dx)
                    accumulatedDy += abs(dy)
                    if (accumulatedDx > 8 || accumulatedDy > 8) isDragging = true
                    // 根据吸附方向调整 x 偏移
                    if (isLeftSide) layoutX += dx.toInt() else layoutX -= dx.toInt()
                    // 【改动·Bug修复】拖拽方向：原 layoutY -= dy（BOTTOM 模式逻辑，TOP 模式下方向反）
                    // 改为 layoutY += dy，修复向上拖悬浮窗反而往下走的 Bug
                    layoutY += dy.toInt()
                    updatePosition()
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // 拖拽结束，吸附到最近边缘
                        snapToEdge()
                    } else {
                        // 点击
                        when (currentState) {
                            State.EDGE_HIDDEN -> {
                                // 【改动】边缘隐藏 → 直接展开 IDLE 输入面板（原需 EDGE_HIDDEN→CHIP→IDLE 两次点击）
                                autoHideJob?.cancel()
                                currentState = State.IDLE
                                removeCurrentView()
                                createUnifiedView()
                            }
                            State.CHIP -> {
                                // CHIP → 展开 IDLE 输入面板
                                autoHideJob?.cancel()
                                currentState = State.IDLE
                                removeCurrentView()
                                createUnifiedView()
                            }
                            // 【改动·Bug修复】IDLE 状态点击空白不做操作
                            // 原代码 else 分支会触发 onFloatClick()（跳转日志页），导致点击发送按钮上方空白误跳转
                            State.IDLE -> { }
                            else -> onFloatClick()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun addToWindow(height: Int) {
        val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                     WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                     WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = appRef?.resources?.displayMetrics?.density ?: 3f

        val width = when (currentState) {
            State.EDGE_HIDDEN -> (30 * density).toInt()
            State.CHIP -> (120 * density).toInt()
            State.IDLE -> (200 * density).toInt()  // 【改动】IDLE 宽度：原 260dp → 200dp，缩小悬浮窗
            State.USER_GUIDE -> (250 * density).toInt()
            State.MINIMIZED -> (48 * density).toInt()
            State.PAUSED -> (140 * density).toInt()
            State.USER_ACTION -> (280 * density).toInt()
            State.ASK_USER -> (280 * density).toInt()
        }

        val params = WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = currentGravity
            x = layoutX
            y = layoutY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        try {
            windowManager?.addView(floatRoot, params)
        } catch (e: Exception) {
            Log.e(TAG, "addView失败: ${e.message}")
        }
    }

    private fun updatePosition() {
        val params = floatRoot?.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = layoutX
        params.y = layoutY
        try {
            windowManager?.updateViewLayout(floatRoot, params)
        } catch (_: Exception) {}
    }

    /**
     * 输入框聚焦
     * 悬浮窗默认带 FLAG_NOT_FOCUSABLE，EditText 获得焦点时需临时移除该 FLAG，
     * 否则 InputMethodManager 会拒绝弹出键盘（"view is not served"）
     */
    private fun enableInputFocus() {
        if (isInputFocused) return
        isInputFocused = true
        val root = floatRoot
        if (root == null) {
            isInputFocused = false
            return
        }
        val params = root.layoutParams as? WindowManager.LayoutParams
        if (params == null) {
            isInputFocused = false
            return
        }
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        try {
            windowManager?.updateViewLayout(root, params)
        } catch (e: Exception) {
            Log.e(TAG, "enableInputFocus updateViewLayout失败: ${e.message}")
            isInputFocused = false
            return
        }
        mainHandler.postDelayed({
            val view = currentEditText ?: floatRoot?.findFocus()
            if (view != null) {
                view.requestFocus()
                val imm = appRef?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 200)
    }

    private fun disableInputFocus() {
        if (!isInputFocused) return
        isInputFocused = false
        val params = floatRoot?.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL.inv()
        try {
            windowManager?.updateViewLayout(floatRoot, params)
        } catch (_: Exception) {}
        val imm = appRef?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        floatRoot?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    /**
     * 拖拽结束后吸附到最近的屏幕边缘
     */
    private fun snapToEdge() {
        val screenWidth = appRef?.resources?.displayMetrics?.widthPixels ?: 1080
        // 根据当前 gravity 判断窗口在屏幕的哪一半
        if (isLeftSide) {
            // gravity 是 START，layoutX 是距离左边的距离，越大越靠右
            isLeftSide = layoutX <= screenWidth / 2
        } else {
            // gravity 是 END，layoutX 是距离右边的距离，越大越靠左
            isLeftSide = layoutX > screenWidth / 2
        }
        currentGravity = if (isLeftSide) Gravity.TOP or Gravity.START else Gravity.TOP or Gravity.END
        layoutX = 0

        val params = floatRoot?.layoutParams as? WindowManager.LayoutParams ?: return
        params.gravity = currentGravity
        params.x = layoutX
        try {
            windowManager?.updateViewLayout(floatRoot, params)
        } catch (_: Exception) {}

        // CHIP 状态下重新启动自动隐藏定时器
        if (currentState == State.CHIP) {
            resetAutoHideTimer()
        }
    }

    /**
     * CHIP 状态 3秒无操作自动收缩到边缘
     */
    private fun resetAutoHideTimer() {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(AUTO_HIDE_DELAY)
            if (currentState == State.CHIP) {
                mainHandler.post {
                    currentState = State.EDGE_HIDDEN
                    removeCurrentView()
                    createUnifiedView()
                }
            }
        }
    }

    /**
     * 【改动】收起到边缘隐藏状态
     * 原始：collapseToChip() 收起到 CHIP 状态（显示 PalmAgent 气泡）
     * 改后：collapseToEdge() 直接收起到 EDGE_HIDDEN，不显示气泡
     */
    private fun collapseToEdge() {
        autoCollapseJob?.cancel()
        mainHandler.post {
            if (currentState == State.EDGE_HIDDEN) return@post
            currentState = State.EDGE_HIDDEN
            removeCurrentView()
            createUnifiedView()
        }
    }

    /**
     * 【改动】30秒无操作自动从 IDLE 收起到边缘
     * 原始：自动收起到 CHIP 状态（显示气泡）
     * 改后：自动收起到 EDGE_HIDDEN 状态（直接隐藏）
     */
    private fun resetAutoCollapseTimer() {
        autoCollapseJob?.cancel()
        autoCollapseJob = scope.launch {
            delay(AUTO_COLLAPSE_DELAY)
            if (currentState == State.IDLE) {
                mainHandler.post {
                    currentState = State.EDGE_HIDDEN
                    removeCurrentView()
                    createUnifiedView()
                }
            }
        }
    }

    // ======================== USER_ACTION 面板视图 ========================

    /**
     * USER_ACTION 用户操作面板
     * 布局：🖐标题行 + 步骤列表 + 跳过/完成按钮
     * 参考 ASK_USER 样式，在悬浮窗中展示用户操作提示
     */
    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    private fun buildUserActionContent(density: Float) {
        val root = floatRoot as LinearLayout

        // 复用 IDLE/ASK_USER 容器样式
        ContextCompat.getDrawable(appRef!!, R.drawable.bg_idle_container)?.let {
            root.background = it
        }
        root.elevation = 6f * density
        val uaRadius = (20 * density).toInt()
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, uaRadius.toFloat())
            }
        }
        root.clipToOutline = true
        root.setPadding(
            (10 * density).toInt(),
            (8 * density).toInt(),
            (10 * density).toInt(),
            (8 * density).toInt()
        )

        val request = UserActionManager.getCurrentRequest()
        val title = request?.title ?: "请手动操作"
        val steps = request?.steps ?: emptyList()
        val allowSkip = request?.allowSkip ?: true
        val expanded = UserActionManager.isExpandedState()

        // 标题行：🖐 + 标题 + 展开按钮 + 关闭
        val titleRow = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val iconView = TextView(appRef!!).apply {
            text = "🖐"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (6 * density).toInt() }
        }
        titleRow.addView(iconView)

        val titleView = TextView(appRef!!).apply {
            text = title
            setTextColor("#FFB74D".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = if (expanded) 3 else 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        titleRow.addView(titleView)

        // 展开/收起按钮
        if (steps.isNotEmpty()) {
            val expandBtn = TextView(appRef!!).apply {
                text = if (expanded) "▲" else "▼"
                setTextColor("#FFB74D".toColorInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding((8 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    (32 * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { UserActionManager.toggleExpand() }
            }
            titleRow.addView(expandBtn)
        }

        val closeBtn = TextView(appRef!!).apply {
            text = "×"
            setTextColor(Color.argb(180, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setOnClickListener { UserActionManager.onUserCancel() }
            layoutParams = LinearLayout.LayoutParams(
                (32 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        titleRow.addView(closeBtn)
        root.addView(titleRow)

        // 展开时显示步骤列表
        if (expanded && steps.isNotEmpty()) {
            val space1 = View(appRef!!).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (6 * density).toInt()
                )
            }
            root.addView(space1)

            for (step in steps) {
                val stepView = TextView(appRef!!).apply {
                    text = step
                    setTextColor(Color.argb(230, 255, 255, 255))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setLineSpacing(2 * density, 1.1f)
                    setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                root.addView(stepView)
            }
        }

        // 按钮行
        val space2 = View(appRef!!).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * density).toInt()
            )
        }
        root.addView(space2)

        val btnRow = LinearLayout(appRef!!).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // 跳过按钮
        if (allowSkip) {
            val skipBtn = TextView(appRef!!).apply {
                text = "跳过"
                setTextColor(Color.argb(180, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.argb(60, 255, 255, 255))
                setPadding(
                    (12 * density).toInt(),
                    (6 * density).toInt(),
                    (12 * density).toInt(),
                    (6 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
                setOnClickListener { UserActionManager.onUserSkip() }
            }
            btnRow.addView(skipBtn)
        }

        // 完成按钮
        val doneBtn = TextView(appRef!!).apply {
            text = "✓ 已完成"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setBackgroundColor("#FF4CAF50".toColorInt())
            setPadding(
                (16 * density).toInt(),
                (8 * density).toInt(),
                (16 * density).toInt(),
                (8 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { UserActionManager.onUserDone() }
        }
        btnRow.addView(doneBtn)

        root.addView(btnRow)
    }

    private fun removeCurrentView() {
        autoCollapseJob?.cancel()
        autoHideJob?.cancel()
        // 取消呼吸点动画，避免悬浮窗移除后动画仍在运行导致泄漏
        breatheAnimator?.cancel()
        breatheAnimator = null
        try {
            floatRoot?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatRoot = null
        currentEditText = null
        isInputFocused = false
    }
}
