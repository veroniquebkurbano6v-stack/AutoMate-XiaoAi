package com.palmagent.app.channel.wechat

import android.content.Context
import android.util.Log
import com.palmagent.app.channel.Channel
import com.palmagent.app.channel.ChannelHandler
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class WeChatChannelHandler(
    private val context: Context
) : ChannelHandler {

    companion object {
        private const val TAG = "WeChatHandler"
        private const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
    }

    override val channel = Channel.WECHAT

    @Volatile
    private var isConnected = false
    @Volatile
    private var running = false

    private lateinit var apiClient: WeChatApiClient
    private lateinit var inbound: WeChatInbound
    private var sender: WeChatSender? = null

    // 这些字段会被收消息线程（inbound scope）和发回复线程（决策/编排多个协程）并发读写，
    // 必须 volatile 保证可见性，否则偶发读到 stale 值导致回复丢失
    @Volatile
    private var userId = ""
    @Volatile
    private var botId = ""
    @Volatile
    private var toUserId = ""
    @Volatile
    private var lastContextToken: String? = null

    /**
     * 消息 ID → 该条消息自带的 contextToken。
     * 微信 ilink 的 context_token 有时效性，长任务（决策对话+多轮执行）跨分钟复用同一个
     * token 会被服务端拒绝（表现为"任务正常执行但微信端收不到任何回复"）。
     * 回复时按 replyToMessageId 精确配对该条入站消息自己的 token，比全局共用更稳。
     */
    private val contextTokensByMessageId = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 定长保护：只保留最近 N 条消息的 token，防止长期运行内存膨胀 */
    private val maxTrackedContextTokens = 50

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var messageListener: ((String, String) -> Unit)? = null

    override fun setMessageReceivedListener(listener: ((String, String) -> Unit)?) {
        this.messageListener = listener
    }

    override fun start() {
        if (running) return
        running = true

        // 重建 scope（stop 时已 cancel 旧 scope）
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val token = KVUtils.getWechatBotToken()
        if (token.isBlank()) {
            Log.w(TAG, "WeChat Bot Token 未配置，无法启动")
            running = false
            return
        }

        val storedBaseUrl = KVUtils.getWechatApiBaseUrl().ifBlank { DEFAULT_BASE_URL }
        userId = KVUtils.getWechatUserId()
        botId = KVUtils.getWechatBotId()
        toUserId = KVUtils.getWechatToUserId()

        apiClient = WeChatApiClient(storedBaseUrl, token)

        inbound = WeChatInbound(
            client = apiClient,
            onNewMessage = { msg -> handleIncomingMessage(msg) },
            onExpired = { handleSessionExpired() }
        ).also { it.start() }

        sender = WeChatSender(apiClient, { userId }, { toUserId })

        isConnected = true
        Log.d(TAG, "WeChat Channel 启动成功, botId=$botId, baseUrl=$storedBaseUrl")
    }

    override fun stop() {
        running = false
        try { inbound.stop() } catch (_: Exception) {}
        scope.cancel()
        isConnected = false
        sender = null
        Log.d(TAG, "WeChat Channel 已停止")
    }

    override fun isConnected(): Boolean = isConnected

    override fun sendMessage(text: String, replyToMessageId: String?): Boolean {
        val s = sender ?: return false
        val messageId = replyToMessageId?.toLongOrNull()
        // 优先使用该条入站消息自带的 contextToken（时效内精确配对），没有则退回最近一次的
        val token = replyToMessageId?.let { contextTokensByMessageId[it] } ?: lastContextToken
        return s.sendText(text, token, messageId)
    }

    override fun sendImage(imageBytes: ByteArray, replyToMessageId: String?): Boolean {
        val s = sender ?: return false
        val messageId = replyToMessageId?.toLongOrNull()
        val token = replyToMessageId?.let { contextTokensByMessageId[it] } ?: lastContextToken
        return s.sendImage(imageBytes, token, messageId)
    }

    override fun setTypingStatus(isTyping: Boolean): Boolean {
        return sender?.setTypingStatus(isTyping, lastContextToken) ?: false
    }

    private fun handleIncomingMessage(msg: WeChatMessage) {
        scope.launch {
            try {
                Log.d(TAG, "收到原始消息: from=${msg.fromUserId.takeLast(16)}, type=${msg.messageType}, state=${msg.messageState}, items=${msg.itemList?.size ?: 0}, sessionId=${msg.sessionId?.takeLast(8)}, contextToken=${if (msg.contextToken.isNullOrEmpty()) "NULL" else msg.contextToken!!.takeLast(12)}")

                if (msg.fromUserId.isNotEmpty()) {
                    toUserId = msg.fromUserId
                    KVUtils.setWechatToUserId(toUserId)
                }
                if (msg.toUserId.isNotEmpty()) {
                    userId = msg.toUserId
                }
                if (msg.sessionId != null || msg.contextToken != null) {
                    sender = WeChatSender(apiClient, { userId }, { toUserId })
                }
                if (!msg.contextToken.isNullOrEmpty()) {
                    lastContextToken = msg.contextToken
                    // 按 messageId 记录该条消息自己的 token，回复时精确配对（定长保护）
                    val msgIdKey = msg.messageId?.toString()
                    if (msgIdKey != null) {
                        if (contextTokensByMessageId.size >= maxTrackedContextTokens) {
                            // 移除最早写入的一条
                            contextTokensByMessageId.keys().toList().firstOrNull()?.let {
                                contextTokensByMessageId.remove(it)
                            }
                        }
                        msg.contextToken?.let { contextTokensByMessageId[msgIdKey] = it }
                    }
                }

                val isUserMessage = msg.messageType == MessageType.USER || msg.messageType == MessageType.NONE || msg.messageType == null
                val isNew = msg.messageState == MessageState.NEW || msg.messageState == MessageState.FINISH || msg.messageState == null

                if (!isUserMessage || !isNew) {
                    Log.w(TAG, "消息被过滤: isUser=$isUserMessage, isNew=$isNew, type=${msg.messageType}, state=${msg.messageState}")
                    return@launch
                }

                val textContent = extractTextFromMessage(msg)
                if (textContent.isBlank()) {
                    Log.d(TAG, "收到非文本消息，忽略: itemTypes=${msg.itemList?.map { it.type }?.joinToString()}")
                    return@launch
                }

                Log.d(TAG, "收到WeChat消息: ${textContent.take(50)}...")
                setTypingStatus(true)

                val listener = messageListener
                if (listener != null) {
                    listener.invoke(textContent, msg.messageId.toString())
                } else {
                    Log.w(TAG, "messageListener 未设置，消息被丢弃")
                    sender?.sendText("Agent未就绪，请稍后再试", lastContextToken)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "消息处理异常: ${e.message}")
            }
        }
    }

    private fun handleSessionExpired() {
        Log.w(TAG, "WeChat session 过期，清除 token")
        KVUtils.setWechatBotToken("")
        KVUtils.setWechatApiBaseUrl("")
        isConnected = false
    }

    private fun extractTextFromMessage(msg: WeChatMessage): String {
        val items = msg.itemList ?: return ""
        return items.filter { it.type == MessageItemType.TEXT }
            .mapNotNull { it.textItem?.text }
            .joinToString(" ")
    }
}