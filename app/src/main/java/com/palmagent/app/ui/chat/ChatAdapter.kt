package com.palmagent.app.ui.chat

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.palmagent.app.R
import com.palmagent.app.model.Question
import com.palmagent.app.model.QuestionAnswer

class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val TYPE_USER = 1
        private const val TYPE_AI = 2
        private const val TYPE_AI_QUESTION = 3

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
                return oldItem == newItem
            }
        }
    }

    // 单一数据源：所有增删都基于此列表，避免连续 submitList 时 DiffUtil 竞争导致消息丢失
    private val messages = mutableListOf<ChatMessage>()

    /** 问题卡提交回调：参数为用户答案列表，回调应内部处理（不显示用户气泡） */
    var onQuestionSubmit: ((message: ChatMessage, answers: List<QuestionAnswer>) -> Unit)? = null

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        return when {
            msg.isUser -> TYPE_USER
            msg.questions != null -> TYPE_AI_QUESTION
            else -> TYPE_AI
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER -> UserViewHolder(
                inflater.inflate(R.layout.item_message_user, parent, false)
            )
            TYPE_AI_QUESTION -> QuestionViewHolder(
                inflater.inflate(R.layout.item_message_ai_question, parent, false)
            )
            else -> AIViewHolder(
                inflater.inflate(R.layout.item_message_ai, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is UserViewHolder -> holder.bind(message)
            is AIViewHolder -> holder.bind(message)
            is QuestionViewHolder -> holder.bind(message)
        }
    }

    fun addMessage(message: ChatMessage, onCommitted: (() -> Unit)? = null) {
        messages.add(message)
        submitList(messages.toList()) { onCommitted?.invoke() }
    }

    /** 添加用户消息（自动生成唯一 ID） */
    fun addUserMessage(content: String) {
        val message = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            content = content,
            isUser = true
        )
        addMessage(message)
    }

    fun removeMessage(message: ChatMessage, onCommitted: (() -> Unit)? = null) {
        messages.remove(message)
        submitList(messages.toList()) { onCommitted?.invoke() }
    }

    /** 替换全部消息并滚动到底部（commit callback 用于在 diff 完成后回调滚动） */
    fun updateMessages(newMessages: List<ChatMessage>, onCommitted: (() -> Unit)? = null) {
        messages.clear()
        messages.addAll(newMessages)
        submitList(messages.toList()) { onCommitted?.invoke() }
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textMessage: TextView = itemView.findViewById(R.id.textMessage)
        private val tvWechatBadge: TextView = itemView.findViewById(R.id.tvWechatBadge)

        fun bind(message: ChatMessage) {
            textMessage.text = message.content
            // 微信来源用户消息显示绿色"微信"标识
            tvWechatBadge.visibility = if (message.source == "WECHAT") View.VISIBLE else View.GONE
        }
    }

    class AIViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textMessage: TextView = itemView.findViewById(R.id.textMessage)
        private val tvWechatBadge: TextView = itemView.findViewById(R.id.tvWechatBadge)

        fun bind(message: ChatMessage) {
            textMessage.text = message.content
            // 微信来源AI消息显示绿色"微信"标识
            tvWechatBadge.visibility = if (message.source == "WECHAT") View.VISIBLE else View.GONE
        }
    }

    /**
     * 问题卡 ViewHolder（仿 Trae Work）：单问题展示 + 右上角左右切换 + 答案跨问题保留
     * - 每次只展示一个问题（标题 + 选项 + 自定义输入）
     * - 切换问题时保留已选答案，切回时恢复
     * - 最后一题点击"提交"收集所有答案回调；非最后一题"提交"按钮文案为"下一题"
     */
    class QuestionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textOverview: TextView = itemView.findViewById(R.id.textOverview)
        private val textQuestionIndex: TextView = itemView.findViewById(R.id.textQuestionIndex)
        private val btnPrev: ImageButton = itemView.findViewById(R.id.btnPrev)
        private val btnNext: ImageButton = itemView.findViewById(R.id.btnNext)
        private val containerQuestion: FrameLayout = itemView.findViewById(R.id.containerQuestion)
        private val btnSubmit: android.widget.Button = itemView.findViewById(R.id.btnSubmit)
        private val tvWechatBadge: TextView = itemView.findViewById(R.id.tvWechatBadge)

        // 当前问题索引
        private var currentIdx = 0
        // 每个问题的答案收集器（惰性：切到该问题时才创建）
        private val answerCollectors = mutableListOf<() -> QuestionAnswer?>()
        // 已收集的答案（切换时保留）
        private val collectedAnswers = mutableMapOf<Int, QuestionAnswer?>()

        fun bind(message: ChatMessage) {
            val context = itemView.context

            // 微信来源问题卡显示绿色"微信"标识
            tvWechatBadge.visibility = if (message.source == "WECHAT") View.VISIBLE else View.GONE

            // 概述
            if (message.content.isNotBlank()) {
                textOverview.text = message.content
                textOverview.visibility = View.VISIBLE
            } else {
                textOverview.visibility = View.GONE
            }

            val questions = message.questions ?: emptyList()
            if (questions.isEmpty()) {
                textQuestionIndex.text = "无问题"
                btnPrev.visibility = View.GONE
                btnNext.visibility = View.GONE
                btnSubmit.visibility = View.GONE
                return
            }

            // 初始化收集器（首次 bind 或复用复位）
            currentIdx = 0
            answerCollectors.clear()
            collectedAnswers.clear()
            repeat(questions.size) { answerCollectors.add { null } }

            renderQuestion(questions, message)

            btnPrev.setOnClickListener {
                if (currentIdx > 0) {
                    currentIdx--
                    renderQuestion(questions, message)
                }
            }
            btnNext.setOnClickListener {
                if (currentIdx < questions.size - 1) {
                    currentIdx++
                    renderQuestion(questions, message)
                }
            }
            btnSubmit.setOnClickListener {
                // 收集当前问题答案
                collectedAnswers[currentIdx] = answerCollectors[currentIdx]()
                if (currentIdx < questions.size - 1) {
                    // 非最后一题：跳到下一题
                    currentIdx++
                    renderQuestion(questions, message)
                } else {
                    // 最后一题：校验全部答案
                    val answers = (0 until questions.size).mapNotNull { collectedAnswers[it] }
                    if (answers.size < questions.size) {
                        val missing = questions.size - answers.size
                        Toast.makeText(context, "还有 $missing 个问题未作答", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    btnSubmit.isEnabled = false
                    btnSubmit.text = "已提交"
                    (itemView.parent as? RecyclerView)?.let { rv ->
                        val pos = rv.getChildAdapterPosition(itemView)
                        if (pos != RecyclerView.NO_POSITION) {
                            (rv.adapter as? ChatAdapter)?.onQuestionSubmit?.invoke(message, answers)
                        }
                    }
                }
            }
        }

        /** 渲染当前问题（标题 + 选项 + 自定义输入），并更新导航条状态 */
        private fun renderQuestion(questions: List<Question>, message: ChatMessage) {
            val context = itemView.context
            val density = context.resources.displayMetrics.density
            val q = questions[currentIdx]

            // 导航条
            textQuestionIndex.text = "${currentIdx + 1} / ${questions.size}"
            btnPrev.isEnabled = currentIdx > 0
            btnPrev.alpha = if (btnPrev.isEnabled) 1.0f else 0.3f
            btnNext.isEnabled = currentIdx < questions.size - 1
            btnNext.alpha = if (btnNext.isEnabled) 1.0f else 0.3f
            // 提交按钮文案：最后一题为"提交"，否则为"下一题"
            btnSubmit.text = if (currentIdx == questions.size - 1) "提交" else "下一题"

            // 渲染问题内容
            containerQuestion.removeAllViews()
            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 问题标题
            val titleText = TextView(context).apply {
                text = q.question
                setTextColor(0xFF1A1A1A.toInt())
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, (4 * density).toInt(), 0, (6 * density).toInt())
            }
            root.addView(titleText)

            // 选项区（单选 RadioGroup / 多选 CheckBox）
            if (q.multiSelect) {
                val checkBoxes = mutableListOf<CheckBox>()
                q.options.forEach { opt ->
                    val cb = CheckBox(context).apply {
                        text = if (opt.description != null) "${opt.label}（${opt.description}）" else opt.label
                        textSize = 13f
                    }
                    checkBoxes.add(cb)
                    root.addView(cb)
                }
                val customEt = if (q.allowFreeInput) {
                    val et = EditText(context).apply {
                        hint = "其他（手动输入）"
                        textSize = 13f
                        visibility = View.GONE
                    }
                    val cb = CheckBox(context).apply {
                        text = "其他"
                        textSize = 13f
                        setOnCheckedChangeListener { _, isChecked ->
                            et.visibility = if (isChecked) View.VISIBLE else View.GONE
                        }
                    }
                    checkBoxes.add(cb)
                    root.addView(cb)
                    root.addView(et)
                    et
                } else null

                // 恢复已选答案（切换问题后 UI 重建，根据 collectedAnswers 恢复）
                collectedAnswers[currentIdx]?.let { qa ->
                    checkBoxes.forEachIndexed { i, cb ->
                        val label = if (i == checkBoxes.size - 1 && customEt != null) "其他"
                                    else q.options[i].label
                        if (qa.answer.contains(label)) cb.isChecked = true
                    }
                }

                answerCollectors[currentIdx] = {
                    val selected = checkBoxes.mapIndexedNotNull { i, cb ->
                        if (!cb.isChecked) null
                        else if (i == checkBoxes.size - 1 && customEt != null)
                            customEt.text.toString().trim().ifEmpty { "其他" }
                        else q.options[i].label
                    }
                    if (selected.isEmpty()) null
                    else QuestionAnswer(question = q.question, answer = selected)
                }
            } else {
                val radioGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
                val radioButtons = mutableListOf<RadioButton>()
                q.options.forEach { opt ->
                    val rb = RadioButton(context).apply {
                        id = View.generateViewId()
                        text = if (opt.description != null) "${opt.label}（${opt.description}）" else opt.label
                        textSize = 13f
                    }
                    radioButtons.add(rb)
                    radioGroup.addView(rb)
                }
                root.addView(radioGroup)
                val customEt = if (q.allowFreeInput) {
                    val et = EditText(context).apply {
                        hint = "其他（手动输入）"
                        textSize = 13f
                        visibility = View.GONE
                    }
                    val rb = RadioButton(context).apply {
                        id = View.generateViewId()
                        text = "其他"
                        textSize = 13f
                    }
                    radioButtons.add(rb)
                    radioGroup.addView(rb)
                    root.addView(et)
                    radioGroup.setOnCheckedChangeListener { _, checkedId ->
                        et.visibility = if (checkedId == rb.id) View.VISIBLE else View.GONE
                    }
                    et
                } else null

                // 恢复已选答案（切换问题后 UI 重建，根据 collectedAnswers 恢复）
                // 注意：若原选"其他"且填了自定义文本，此处仅恢复勾选状态，不回填文本（避免答案歧义）
                collectedAnswers[currentIdx]?.let { qa ->
                    val answerStr = qa.answer.firstOrNull()
                    radioButtons.forEachIndexed { i, rb ->
                        val label = if (i == radioButtons.size - 1 && customEt != null) "其他"
                                    else q.options[i].label
                        if (label == answerStr) {
                            rb.isChecked = true
                            if (i == radioButtons.size - 1 && customEt != null) {
                                customEt.visibility = View.VISIBLE
                            }
                        }
                    }
                }

                answerCollectors[currentIdx] = {
                    val checkedId = radioGroup.checkedRadioButtonId
                    if (checkedId == -1) null
                    else {
                        val checkedIdx = radioButtons.indexOfFirst { it.id == checkedId }
                        val answer = if (checkedIdx == radioButtons.size - 1 && customEt != null)
                            customEt.text.toString().trim().ifEmpty { "其他" }
                        else q.options[checkedIdx].label
                        QuestionAnswer(question = q.question, answer = listOf(answer))
                    }
                }
            }

            containerQuestion.addView(root)
        }
    }
}
