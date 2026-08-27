package com.palmagent.app.channel.wechat

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class WeChatApiClient(
    private var baseUrl: String = DEFAULT_BASE_URL,
    private var botToken: String = ""
) {

    companion object {
        private const val TAG = "WeChatApiClient"
        private val JSON_MEDIA = "application/json".toMediaTypeOrNull()
    }

    private val gson = Gson()

    fun setBotToken(token: String) { this.botToken = token }
    fun setBaseUrl(url: String) { this.baseUrl = url }
    fun isAuthenticated(): Boolean = botToken.isNotEmpty()

    // ==================== HTTP 客户端 ====================

    private val longPollClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // ==================== 请求构建 ====================

    private fun randomWechatUin(): String {
        val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val uint32 = ((bytes[0].toLong() and 0xFF) shl 24) or
                ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or
                (bytes[3].toLong() and 0xFF)
        return Base64.encodeToString(uint32.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun buildApiRequest(endpoint: String, body: String, client: OkHttpClient): Pair<Request, OkHttpClient> {
        val path = if (baseUrl.endsWith("/")) "$baseUrl$endpoint" else "$baseUrl/$endpoint"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val builder = Request.Builder()
            .url(path)
            .addHeader("Content-Type", "application/json")
            .addHeader("AuthorizationType", "ilink_bot_token")
            .addHeader("Content-Length", bodyBytes.size.toString())
            .addHeader("X-WECHAT-UIN", randomWechatUin())
            .post(bodyBytes.toRequestBody(JSON_MEDIA))

        if (botToken.isNotEmpty()) {
            builder.addHeader("Authorization", "Bearer $botToken")
        }

        return builder.build() to client
    }

    private fun apiFetch(endpoint: String, body: JsonObject, client: OkHttpClient, label: String): String? {
        val bodyStr = body.toString()
        val (request, httpClient) = buildApiRequest(endpoint, bodyStr, client)
        return try {
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                val rawText = response.body?.string() ?: ""
                if (code !in 200..299) {
                    Log.e(TAG, "$label: HTTP $code, body=${rawText.take(600)}")
                    null
                } else {
                    Log.d(TAG, "$label OK [$code] body=${rawText.take(600)}")
                    rawText
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "$label: timeout")
            null
        } catch (e: Exception) {
            Log.e(TAG, "$label: exception", e)
            null
        }
    }

    // ==================== API 端点 ====================

    fun getUpdates(getUpdatesBuf: String): GetUpdatesResp? {
        val body = JsonObject().apply {
            addProperty("get_updates_buf", getUpdatesBuf)
            add("base_info", JsonObject().apply { addProperty("channel_version", CHANNEL_VERSION) })
        }
        val rawText = apiFetch("ilink/bot/getupdates", body, longPollClient, "getUpdates")
        // 返回 null 让 pollingLoop 触发 RECONNECT_DELAY_MS 退避，避免异常时死循环
        if (rawText == null) return null
        if (rawText.isEmpty()) return null

        return try {
            val json = gson.fromJson(rawText, JsonObject::class.java)
            val msgs = mutableListOf<WeChatMessage>()
            json.getAsJsonArray("msgs")?.let { arr ->
                for (i in 0 until arr.size()) {
                    val entry = arr.get(i).asJsonObject
                    WeChatInbound.parseMessage(entry)?.let { msgs.add(it) }
                }
            }
            GetUpdatesResp(
                ret = json.get("ret")?.asInt ?: 0,
                msgs = msgs.ifEmpty { null },
                getUpdatesBuf = json.get("get_updates_buf")?.asString
            )
        } catch (e: Exception) {
            Log.e(TAG, "getUpdates解析失败", e)
            null
        }
    }

    fun sendMessage(msgBody: JsonObject): Int {
        // 参数校验
        val toUserId = msgBody.get("to_user_id")?.asString ?: ""
        if (toUserId.isBlank()) {
            Log.w(TAG, "sendMessage: to_user_id为空，跳过发送")
            return -2
        }

        val body = JsonObject().apply {
            add("msg", msgBody)
        }
        Log.d(TAG, "sendMessage REQ: ${body.toString().take(600)}")
        val rawText = apiFetch("ilink/bot/sendmessage", body, apiClient, "sendMessage")
            ?: return -1

        Log.d(TAG, "sendMessage RESP: ${rawText.take(600)}")

        return try {
            val resp = gson.fromJson(rawText, JsonObject::class.java)
            val ret = resp.get("ret")?.asInt ?: 0
            if (ret != 0) {
                val errMsg = resp.get("err_msg")?.asString ?: "unknown"
                val mappedError = mapWeChatRetCode(ret)
                Log.e(TAG, "sendMessage失败: ret=$ret ($mappedError), err_msg=$errMsg, to_user_id=$toUserId")
            }
            ret
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage响应解析异常", e)
            -1
        }
    }

    private fun mapWeChatRetCode(ret: Int): String = when (ret) {
        -1 -> "系统错误"
        -2 -> "参数错误或用户不存在"
        -3 -> "发送失败"
        -4 -> "权限不足"
        else -> "未知错误($ret)"
    }

    fun getUploadUrl(
        filekey: String, mediaType: Int, toUserId: String,
        rawsize: Int, rawfilemd5: String, filesize: Int, aeskeyHex: String
    ): String? {
        val body = JsonObject().apply {
            addProperty("filekey", filekey)
            addProperty("media_type", mediaType)
            addProperty("to_user_id", toUserId)
            addProperty("rawsize", rawsize)
            addProperty("rawfilemd5", rawfilemd5)
            addProperty("filesize", filesize)
            addProperty("no_need_thumb", true)
            addProperty("aeskey", aeskeyHex)
            add("base_info", JsonObject().apply { addProperty("channel_version", CHANNEL_VERSION) })
        }
        val rawText = apiFetch("ilink/bot/getuploadurl", body, apiClient, "getUploadUrl") ?: return null
        if (rawText.isEmpty()) return null
        return try {
            val json = gson.fromJson(rawText, JsonObject::class.java)
            val ret = json.get("ret")?.asInt ?: 0
            if (ret != 0) {
                Log.w(TAG, "getUploadUrl: ret=$ret, body=$rawText")
                return null
            }
            val param = json.get("upload_param")?.asString ?: ""
            param.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "getUploadUrl解析失败", e)
            null
        }
    }

    private var typingTicketCache: String? = null

    fun getConfig(userId: String, contextToken: String?): String? {
        val body = JsonObject().apply {
            addProperty("ilink_user_id", userId)
            if (!contextToken.isNullOrEmpty()) {
                addProperty("context_token", contextToken)
            }
            add("base_info", JsonObject().apply { addProperty("channel_version", CHANNEL_VERSION) })
        }
        val rawText = apiFetch("ilink/bot/getconfig", body, apiClient, "getConfig") ?: return null
        if (rawText.isEmpty()) return null
        return try {
            val json = gson.fromJson(rawText, JsonObject::class.java)
            val ticket = json.get("typing_ticket")?.asString
            if (ticket.isNullOrEmpty()) {
                Log.w(TAG, "getConfig: typing_ticket为空")
                null
            } else ticket
        } catch (e: Exception) {
            Log.e(TAG, "getConfig解析失败", e)
            null
        }
    }

    fun setTypingStatus(userId: String, contextToken: String?, status: Int): Boolean {
        // 获取 typing_ticket（缓存，ticket 可能过期，失败时清空重试一次）
        var ticket = typingTicketCache
        if (ticket.isNullOrEmpty()) {
            ticket = getConfig(userId, contextToken)
            if (ticket.isNullOrEmpty()) {
                Log.w(TAG, "setTypingStatus: 无法获取typing_ticket，跳过")
                return false
            }
            typingTicketCache = ticket
        }

        val body = JsonObject().apply {
            addProperty("ilink_user_id", userId)
            addProperty("typing_ticket", ticket)
            addProperty("status", status)
            add("base_info", JsonObject().apply { addProperty("channel_version", CHANNEL_VERSION) })
        }
        val rawText = apiFetch("ilink/bot/sendtyping", body, apiClient, "sendTyping")
        // 404/错误时清空缓存，下次重新获取
        if (rawText == null) {
            Log.w(TAG, "sendTyping失败，清空typing_ticket缓存")
            typingTicketCache = null
        }
        return rawText != null
    }

    fun uploadMedia(body: JsonObject): String? {
        return apiFetch("ilink/bot/uploadmedia", body, apiClient, "uploadMedia")
    }

    // ==================== 扫码登录 ====================

    fun getQrCode(): QrCodeResult? {
        return try {
            val url = "$DEFAULT_BASE_URL/ilink/bot/get_bot_qrcode?bot_type=$DEFAULT_ILINK_BOT_TYPE"
            val request = Request.Builder().url(url).get().build()
            Log.d(TAG, "GET $url")
            apiClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val code = response.code

                if (code != 200) {
                    Log.e(TAG, "getQrCode FAIL [$code] body=${body.take(300)}")
                    return null
                } else {
                    Log.d(TAG, "getQrCode OK [$code] body=${body.take(200)}")
                }

                if (body.isEmpty()) return null
                val json = gson.fromJson(body, JsonObject::class.java)
                val qrcode = json.get("qrcode")?.asString ?: ""
                val imgContent = json.get("qrcode_img_content")?.asString ?: ""
                if (qrcode.isEmpty()) {
                    Log.e(TAG, "getQrCode: qrcode为空")
                    return null
                }
                Log.d(TAG, "got qrcode: ${qrcode.take(50)}...")
                QrCodeResult(qrcode = qrcode, qrcodeImgContent = imgContent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getQrCode异常", e)
            null
        }
    }

    fun pollQrCodeStatus(qrcode: String): AuthResult? {
        return try {
            val url = "$DEFAULT_BASE_URL/ilink/bot/get_qrcode_status?qrcode=$qrcode"
            val request = Request.Builder()
                .url(url)
                .addHeader("iLink-App-ClientVersion", "1")
                .get()
                .build()
            longPollClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val code = response.code

                if (code != 200) {
                    Log.e(TAG, "pollQrCodeStatus FAIL [$code]")
                    return null
                }
                if (body.isEmpty()) return null

            val json = gson.fromJson(body, JsonObject::class.java)
            val status = json.get("status")?.asString ?: ""
            Log.d(TAG, "QR status: $status")

            if (status == "confirmed") {
                AuthResult(
                    botToken = json.get("bot_token")?.asString ?: "",
                    baseUrl = json.get("baseurl")?.asString ?: DEFAULT_BASE_URL,
                    botId = json.get("ilink_bot_id")?.asString ?: "",
                    userId = json.get("ilink_user_id")?.asString ?: ""
                ).also {
                    Log.d(TAG, "扫码登录成功, botId=${it.botId}")
                }
            } else null
            }
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "QR poll timeout, retrying")
            null
        } catch (e: Exception) {
            Log.e(TAG, "pollQrCodeStatus异常", e)
            null
        }
    }
}