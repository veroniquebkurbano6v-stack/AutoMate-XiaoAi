package com.palmagent.app.channel.wechat

import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.palmagent.app.LiveLogBuffer
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class WeChatSender(
    private val client: WeChatApiClient,
    private val fromUserId: () -> String,
    private val toUserId: () -> String
) {

    companion object {
        private const val TAG = "WeChatSender"
    }

    fun sendText(text: String, contextToken: String?, messageId: Long? = null): Boolean {
        if (text.isBlank()) {
            Log.w(TAG, "跳过发送空消息（生成内容为空）")
            return false
        }

        val itemList = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", MessageItemType.TEXT)
                add("text_item", JsonObject().apply {
                    addProperty("text", text)
                })
            })
        }

        val msgBody = JsonObject().apply {
            addProperty("to_user_id", toUserId())
            addProperty("message_type", MessageType.BOT)
            addProperty("message_state", MessageState.FINISH)
            if (!contextToken.isNullOrEmpty()) {
                addProperty("context_token", contextToken)
            }
            add("item_list", itemList)
        }

        Log.d(TAG, "发送文本消息: ${text.take(50)}..., toUser=${toUserId().takeLast(16)}, contextToken=${if (contextToken.isNullOrEmpty()) "NULL" else contextToken!!.takeLast(12)}")
        val ret = client.sendMessage(msgBody)
        if (ret != 0) {
            // 不可恢复错误：参数错误(-2)、权限不足(-4) 不重试
            val isUnrecoverable = ret == -2 || ret == -4
            if (isUnrecoverable) {
                Log.w(TAG, "消息发送失败(ret=$ret)，不可恢复错误，跳过重试")
                LiveLogBuffer.append("WeChat消息发送失败(不可恢复: ret=$ret)")
                return false
            }
            Log.d(TAG, "消息发送失败(ret=$ret)，1秒后重试")
            try { Thread.sleep(1000) } catch (_: InterruptedException) {}
            val retriedRet = client.sendMessage(msgBody)
            if (retriedRet != 0) {
                LiveLogBuffer.append("WeChat消息发送失败(重试后仍失败: ret=$retriedRet)")
            }
            return retriedRet == 0
        }
        return true
    }

    fun sendImage(imageData: ByteArray, contextToken: String?, messageId: Long? = null): Boolean {
        val cdnMedia = uploadToCdn(imageData, UploadMediaType.IMAGE)
        if (cdnMedia == null) {
            Log.e(TAG, "图片CDN上传失败")
            return false
        }

        val itemList = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", MessageItemType.IMAGE)
                add("image", JsonObject().apply {
                    add("media", JsonObject().apply {
                        addProperty("encrypt_query_param", cdnMedia.downloadEncryptedQueryParam)
                        addProperty("aes_key", cdnMedia.aeskeyHex)
                        addProperty("encrypt_type", 0)
                    })
                    addProperty("mid_size", cdnMedia.fileSize)
                    addProperty("thumb_size", cdnMedia.fileSize)
                })
            })
        }

        val msgBody = JsonObject().apply {
            addProperty("to_user_id", toUserId())
            addProperty("message_type", MessageType.BOT)
            addProperty("message_state", MessageState.FINISH)
            if (!contextToken.isNullOrEmpty()) {
                addProperty("context_token", contextToken)
            }
            add("item_list", itemList)
        }

        return client.sendMessage(msgBody) == 0
    }

    fun setTypingStatus(isTyping: Boolean, contextToken: String? = null): Boolean {
        val status = if (isTyping) TypingStatus.TYPING else TypingStatus.CANCEL
        return client.setTypingStatus(toUserId(), contextToken, status)
    }

    private fun randomId(): String {
        return "bot_" + Base64.encodeToString(
            UUID.randomUUID().toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        ).take(32)
    }

    private fun uploadToCdn(data: ByteArray, mediaType: Int): UploadedFileInfo? {
        val rawsize = data.size
        val rawfilemd5 = WeChatCdn.md5Hex(data)
        val filesize = aesEcbPaddedSize(rawsize)
        val filekey = WeChatCdn.randomHex(16)
        val aeskeyHex = WeChatCdn.randomHex(16)

        val uploadParam = client.getUploadUrl(
            filekey = filekey, mediaType = mediaType, toUserId = toUserId(),
            rawsize = rawsize, rawfilemd5 = rawfilemd5, filesize = filesize,
            aeskeyHex = aeskeyHex
        )
        if (uploadParam == null) {
            Log.e(TAG, "获取上传URL失败")
            return null
        }

        val encryptedData = aesEcbEncrypt(data, aeskeyHex)
        val filesizeCiphertext = encryptedData.size
        val bytesBase64 = Base64.encodeToString(encryptedData, Base64.NO_WRAP)

        val body = JsonObject().apply {
            addProperty("from_user_id", fromUserId())
            addProperty("to_user_id", toUserId())
            addProperty("filekey", filekey)
            addProperty("rawsize", rawsize)
            addProperty("rawfilemd5", rawfilemd5)
            addProperty("aeskey", aeskeyHex)
            addProperty("upload_param", uploadParam)
            addProperty("filesize", filesizeCiphertext)
            addProperty("bytes_base64", bytesBase64)
        }
        val rawText = client.uploadMedia(body) ?: return null
        if (rawText.isEmpty()) return null

        return try {
            val json = com.google.gson.Gson().fromJson(rawText, JsonObject::class.java)
            UploadedFileInfo(
                filekey = filekey,
                downloadEncryptedQueryParam = json.get("encrypt_query_param")?.asString ?: "",
                aeskeyHex = aeskeyHex,
                fileSize = filesize,
                fileSizeCiphertext = filesizeCiphertext
            )
        } catch (e: Exception) {
            Log.e(TAG, "uploadMedia解析失败", e)
            null
        }
    }

    private fun aesEcbPaddedSize(rawSize: Int): Int {
        val blockSize = 16
        return ((rawSize + blockSize - 1) / blockSize) * blockSize
    }

    private fun aesEcbEncrypt(data: ByteArray, hexKey: String): ByteArray {
        val keyBytes = hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.doFinal(data)
    }
}