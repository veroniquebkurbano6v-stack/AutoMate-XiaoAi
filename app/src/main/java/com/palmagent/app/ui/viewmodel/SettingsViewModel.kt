package com.palmagent.app.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palmagent.app.config.Config
import com.palmagent.app.utils.KVUtils
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class SettingsUiState(
    val llmModelName: String = "",
    val llmBaseUrl: String = "",
    val vlmModelName: String = "",
    val vlmApiUrl: String = "",
    val compactModelName: String = "",
    val compactApiUrl: String = "",
    val isWechatBound: Boolean = false,
    val wechatBotId: String = "",
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val testSuccess: Boolean = false
)

data class ApiTestResult(val isSuccess: Boolean, val error: String = "")

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    init {
        refreshConfig()
    }

    fun refreshConfig() {
        _uiState.value = _uiState.value.copy(
            llmModelName = KVUtils.getLlmModelName(),
            llmBaseUrl = KVUtils.getLlmBaseUrl(),
            vlmModelName = KVUtils.getVlmModelName(),
            vlmApiUrl = KVUtils.getVlmApiUrl(),
            isWechatBound = KVUtils.getWechatBotToken().isNotBlank(),
            wechatBotId = KVUtils.getWechatBotId().ifEmpty { "微信Bot" }
        )
    }

    fun saveLlmConfig(apiKey: String, baseUrl: String, modelName: String) {
        KVUtils.setLlmApiKey(apiKey)
        KVUtils.setLlmBaseUrl(baseUrl)
        KVUtils.setLlmModelName(modelName)
        refreshConfig()
    }

    fun saveGuiOwlConfig() {
        refreshConfig()
    }

    fun clearWechatBinding() {
        KVUtils.setWechatBotToken("")
        KVUtils.setWechatApiBaseUrl("")
        KVUtils.setWechatBotId("")
        KVUtils.setWechatUserId("")
        refreshConfig()
    }

    fun saveWechatAuth(botToken: String, baseUrl: String, botId: String?, userId: String?) {
        KVUtils.setWechatBotToken(botToken)
        KVUtils.setWechatApiBaseUrl(baseUrl)
        KVUtils.setWechatBotId(botId ?: "")
        KVUtils.setWechatUserId(userId ?: "")
        refreshConfig()
    }

    fun testLlmConnection(baseUrl: String, apiKey: String, modelName: String, onResult: (ApiTestResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = testApiConnection(baseUrl, apiKey, modelName)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun testGuiOwlConnection(apiUrl: String, apiKey: String, modelName: String, onResult: (ApiTestResult) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = testGuiOwlApiConnection(apiUrl, apiKey, modelName)
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    private fun testApiConnection(baseUrl: String, apiKey: String, modelName: String): ApiTestResult {
        return try {
            val normalizedUrl = normalizeApiUrl(baseUrl, Config.LLM_API_URL)
            val effectiveModel = modelName.ifBlank { Config.LLM_MODEL }

            val testBody = gson.toJson(mapOf(
                "model" to effectiveModel,
                "messages" to listOf(mapOf("role" to "user", "content" to "Hi")),
                "max_tokens" to 5,
                "temperature" to 0.0
            ))

            val request = Request.Builder()
                .url(normalizedUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(testBody.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = httpClient.newCall(request).execute().use { resp ->
                Pair(resp.code, resp.body?.string() ?: "")
            }

            if (code in 200..299) {
                ApiTestResult(true)
            } else {
                val errorMsg = parseErrorMessage(body, code)
                ApiTestResult(false, errorMsg)
            }
        } catch (e: Exception) {
            ApiTestResult(false, "连接失败：${e.message ?: "未知错误"}")
        }
    }

    private fun testGuiOwlApiConnection(apiUrl: String, apiKey: String, modelName: String): ApiTestResult {
        return try {
            val chatUrl = normalizeApiUrl(apiUrl, null)

            val testBody = gson.toJson(mapOf(
                "model" to modelName,
                "messages" to listOf(mapOf("role" to "user", "content" to "Hi")),
                "max_tokens" to 5,
                "temperature" to 0.0
            ))

            val requestBuilder = Request.Builder()
                .url(chatUrl)
                .addHeader("Content-Type", "application/json")

            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val request = requestBuilder
                .post(testBody.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = httpClient.newCall(request).execute().use { resp ->
                Pair(resp.code, resp.body?.string() ?: "")
            }

            if (code in 200..299) {
                ApiTestResult(true)
            } else {
                val errorMsg = parseErrorMessage(body, code)
                ApiTestResult(false, errorMsg)
            }
        } catch (e: Exception) {
            ApiTestResult(false, "连接失败：${e.message ?: "未知错误"}")
        }
    }

    private fun normalizeApiUrl(baseUrl: String, defaultUrl: String?): String {
        val effectiveBaseUrl = if (baseUrl.isBlank()) {
            if (defaultUrl.isNullOrBlank()) "" else {
                val trimmed = defaultUrl.trimEnd('/')
                if (trimmed.endsWith("/chat/completions")) trimmed
                else if (trimmed.endsWith("/v1")) "$trimmed/chat/completions"
                else "$trimmed/v1/chat/completions"
            }
        } else {
            val trimmed = baseUrl.trimEnd('/')
            if (trimmed.endsWith("/chat/completions")) trimmed
            else if (trimmed.endsWith("/v1")) "$trimmed/chat/completions"
            else "$trimmed/v1/chat/completions"
        }
        return effectiveBaseUrl
    }

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val errorMap = gson.fromJson(body, Map::class.java)
            (errorMap["error"] as? Map<*, *>)?.get("message")?.toString()
                ?: "HTTP $code"
        } catch (e: Exception) {
            "HTTP $code: ${body.take(200)}"
        }
    }
}
