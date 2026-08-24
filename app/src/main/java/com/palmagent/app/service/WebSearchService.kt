package com.palmagent.app.service

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.palmagent.app.model.ToolCallResult
import com.palmagent.app.utils.KVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 独立联网搜索 service（不复用 WebMCPService）。
 *
 * 引擎选择：
 * - 配置了 BOCHA_API_KEY → 博查 Bocha AI Search（国内 CDN 快、AI 优化、结构化 JSON）
 * - 未配置 → DuckDuckGo HTML 抓取（完全免费、无 Key、国内可访问但偶发慢）
 *
 * 严格超时：connect 5s + read 10s + write 5s，单次搜索最长 15s 内返回。
 * 失败不抛异常，返回 ToolCallResult(success=false, error=...)。
 *
 * v3.2 Bug-T 修复：改为 object 单例，避免每次实例化新建 OkHttpClient 导致端口/线程耗尽
 */
object WebSearchService {

    private const val TAG = "WebSearchService"
    private const val BOCHA_API_URL = "https://api.bochaai.com/v1/web-search"
    private const val DDG_HTML_URL = "https://duckduckgo.com/html/?q="
    private const val CONNECT_TIMEOUT_S = 5L
    private const val READ_TIMEOUT_S = 10L
    private const val WRITE_TIMEOUT_S = 5L

    data class WebSearchResult(
        val query: String,
        val engine: String,           // "bocha" / "duckduckgo"
        val answer: String?,          // AI 摘要（仅博查有）
        val results: List<SearchItem>,
        val elapsedMs: Long
    )

    data class SearchItem(
        val title: String,
        val url: String,
        val snippet: String,
        val summary: String? = null
    )

    /** 结构化搜索结果（供缓存/摘要视图使用，避免格式化文本丢失结构） */
    data class SearchOutcome(
        val success: Boolean,
        val result: WebSearchResult? = null,
        val error: String? = null,
        val durationMs: Long = 0
    )

    /**
     * 带缓存的搜索结果（主链路使用）：
     * 1. 去重检查：同 query（规范化）在最近 KEEP_ROUNDS 内已搜过 → 命中缓存，不重复调博查
     * 2. 未命中 → 正常搜索 → 完整结果写 ToolResultCache → 生成摘要视图
     *
     * @param round 当前轮次（用于 ref 命名 ws-<round>-<n>）
     */
    data class CachedSearchResult(
        val success: Boolean,
        val summaryText: String,      // 摘要视图（仅本轮注入，不入工作区）
        val hitCache: Boolean,        // 是否命中缓存
        val hitRound: Int?,           // 命中轮次（未命中为 null）
        val refs: List<String>,       // 本轮缓存条目 ref 列表
        val durationMs: Long = 0,     // 实际搜索耗时（缓存命中为 0）
        val error: String? = null
    )

    private val fastClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    /**
     * 统一搜索入口（兼容旧调用方）：自动选择引擎，返回格式化文本。
     * 注意：主链路请使用 [searchWithCache]（带去重+缓存+摘要视图）。
     */
    suspend fun search(query: String, count: Int = 5): ToolCallResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            return@withContext ToolCallResult("web_search", success = false, error = "查询词不能为空")
        }
        val bochaKey = KVUtils.getBochaApiKey()
        val outcome = if (bochaKey.isNotEmpty()) {
            Log.d(TAG, "使用博查引擎搜索: query=$query, count=$count")
            searchViaBocha(query, count, bochaKey)
        } else {
            Log.d(TAG, "未配置博查Key，使用 DuckDuckGo 兜底: query=$query, count=$count")
            searchViaDuckDuckGo(query, count)
        }
        if (!outcome.success) {
            ToolCallResult("web_search", success = false, error = outcome.error, durationMs = outcome.durationMs)
        } else {
            ToolCallResult(
                "web_search", success = true,
                content = formatResults(outcome.result!!),
                durationMs = outcome.durationMs
            )
        }
    }

    /**
     * 主链路搜索入口：去重 → 搜索 → 完整缓存 → 摘要视图。
     * 1. 同 query（规范化）在最近 KEEP_ROUNDS 内已缓存 → 直接复用缓存摘要，不重复调博查
     * 2. 未命中 → 调搜索引擎 → 完整结果写 ToolResultCache（全字段不截断）
     * 3. 返回摘要视图（仅本轮注入，不入工作区），模型可按 ref 调 fetch_result 取回原文
     *
     * @param round 当前轮次（ref 命名 ws-<round>-<n>）
     */
    suspend fun searchWithCache(query: String, count: Int = 5, round: Int): CachedSearchResult =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) {
                return@withContext CachedSearchResult(
                    success = false, summaryText = "", hitCache = false, hitRound = null, refs = emptyList(),
                    error = "查询词不能为空"
                )
            }

            // 1. 去重检查：规范化 query 命中缓存则直接复用
            val hit = ToolResultCache.hitRound(query)
            if (hit != null) {
                Log.d(TAG, "搜索缓存命中 round=$hit: query=${query.take(60)}")
                val entries = readEntries(hit)
                if (entries.isNotEmpty()) {
                    return@withContext CachedSearchResult(
                        success = true,
                        summaryText = buildCachedSummaryHeader(query, hit) + "\n" +
                            ToolResultCache.buildSummary(query, entries),
                        hitCache = true,
                        hitRound = hit,
                        refs = entries.map { it.ref }
                    )
                }
            }

            // 2. 未命中 → 正常搜索（结构化）
            val bochaKey = KVUtils.getBochaApiKey()
            val outcome = if (bochaKey.isNotEmpty()) {
                searchViaBocha(query, count, bochaKey)
            } else {
                searchViaDuckDuckGo(query, count)
            }
            if (!outcome.success) {
                return@withContext CachedSearchResult(
                    success = false, summaryText = "", hitCache = false, hitRound = null, refs = emptyList(),
                    durationMs = outcome.durationMs, error = outcome.error
                )
            }

            // 3. 完整结果写缓存（全字段），再生成摘要视图
            val cached = ToolResultCache.putSearch(round, query, outcome.result!!.results)
            if (cached.isEmpty()) {
                // 缓存写失败：回退旧逻辑（格式化全文），保证不破坏主流程
                return@withContext CachedSearchResult(
                    success = true,
                    summaryText = formatResults(outcome.result),
                    hitCache = false, hitRound = null, refs = emptyList(),
                    durationMs = outcome.durationMs
                )
            }
            CachedSearchResult(
                success = true,
                summaryText = ToolResultCache.buildSummary(query, cached),
                hitCache = false,
                hitRound = null,
                refs = cached.map { it.ref },
                durationMs = outcome.durationMs
            )
        }

    /** 读取某轮缓存条目（供命中后复用摘要） */
    private fun readEntries(round: Int): List<ToolResultCache.CachedEntry> =
        ToolResultCache.readEntries(round) ?: emptyList()

    private fun buildCachedSummaryHeader(query: String, round: Int): String =
        "【搜索结果摘要】查询: $query | 缓存命中 round $round（如需最新结果可用新关键词重搜）"

    // ==================== 博查 Bocha AI Search ====================

    private suspend fun searchViaBocha(query: String, count: Int, apiKey: String): SearchOutcome {
        val startMs = System.currentTimeMillis()
        val requestBody = buildString {
            append("{")
            append("\"query\":\"").append(escapeJson(query)).append("\",")
            append("\"count\":").append(count).append(",")
            append("\"freshness\":\"oneWeek\"")
            append("}")
        }

        val req = Request.Builder()
            .url(BOCHA_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        return try {
            fastClient.newCall(req).execute().use { resp ->
                val elapsedMs = System.currentTimeMillis() - startMs
                val body = resp.body?.string()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "博查搜索HTTP失败: code=${resp.code}, body=$body, elapsed=${elapsedMs}ms")
                    return@use SearchOutcome(
                        success = false,
                        error = "博查HTTP ${resp.code}: ${body?.take(200) ?: "无响应体"}",
                        durationMs = elapsedMs
                    )
                }
                if (body.isNullOrBlank()) {
                    return@use SearchOutcome(
                        success = false,
                        error = "博查返回空响应体",
                        durationMs = elapsedMs
                    )
                }
                val result = parseBochaResponse(query, body, elapsedMs)
                if (result == null) {
                    SearchOutcome(success = false, error = "博查响应解析失败", durationMs = elapsedMs)
                } else {
                    SearchOutcome(success = true, result = result, durationMs = elapsedMs)
                }
            }
        } catch (e: SocketTimeoutException) {
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.w(TAG, "博查搜索超时: ${e.message}, elapsed=${elapsedMs}ms")
            SearchOutcome(success = false, error = "博查搜索超时（${elapsedMs}ms）", durationMs = elapsedMs)
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.e(TAG, "博查搜索异常: ${e.message}", e)
            SearchOutcome(success = false, error = "博查搜索异常: ${e.message}", durationMs = elapsedMs)
        }
    }

    private fun parseBochaResponse(query: String, body: String, elapsedMs: Long): WebSearchResult? {
        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val webPages = json.getAsJsonObject("webPages") ?: return null
            val valueArr = webPages.getAsJsonArray("value") ?: return null
            val items = valueArr.mapNotNull { item ->
                val obj = item.asJsonObject
                SearchItem(
                    title = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    snippet = obj.get("snippet")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    summary = obj.get("summary")?.takeIf { !it.isJsonNull }?.asString
                )
            }.filter { it.title.isNotEmpty() || it.url.isNotEmpty() }

            val answer = json.get("summary")?.takeIf { !it.isJsonNull }?.asString
            WebSearchResult(query = query, engine = "bocha", answer = answer, results = items, elapsedMs = elapsedMs)
        } catch (e: Exception) {
            Log.e(TAG, "解析博查响应异常: ${e.message}", e)
            null
        }
    }

    // ==================== DuckDuckGo HTML 抓取（兜底） ====================

    private suspend fun searchViaDuckDuckGo(query: String, count: Int): SearchOutcome {
        val startMs = System.currentTimeMillis()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("$DDG_HTML_URL$encodedQuery")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .get()
            .build()

        return try {
            fastClient.newCall(req).execute().use { resp ->
                val elapsedMs = System.currentTimeMillis() - startMs
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrBlank()) {
                    Log.w(TAG, "DuckDuckGo搜索HTTP失败: code=${resp.code}, elapsed=${elapsedMs}ms")
                    return@use SearchOutcome(
                        success = false,
                        error = "DuckDuckGo HTTP ${resp.code}",
                        durationMs = elapsedMs
                    )
                }
                val items = parseDuckDuckGoHtml(body, count)
                val result = WebSearchResult(query = query, engine = "duckduckgo", answer = null, results = items, elapsedMs = elapsedMs)
                if (items.isEmpty()) {
                    SearchOutcome(success = false, error = "DuckDuckGo 未返回有效结果", durationMs = elapsedMs)
                } else {
                    SearchOutcome(success = true, result = result, durationMs = elapsedMs)
                }
            }
        } catch (e: SocketTimeoutException) {
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.w(TAG, "DuckDuckGo搜索超时: ${e.message}, elapsed=${elapsedMs}ms")
            SearchOutcome(success = false, error = "DuckDuckGo搜索超时（${elapsedMs}ms）", durationMs = elapsedMs)
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startMs
            Log.e(TAG, "DuckDuckGo搜索异常: ${e.message}", e)
            SearchOutcome(success = false, error = "DuckDuckGo搜索异常: ${e.message}", durationMs = elapsedMs)
        }
    }

    private fun parseDuckDuckGoHtml(html: String, count: Int): List<SearchItem> {
        val items = mutableListOf<SearchItem>()
        // 简单正则提取 result__a (标题链接) 和 result__snippet (摘要)
        val linkRegex = Regex("""<a[^>]+class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""<a[^>]+class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).toList()
        val maxCount = minOf(count, links.size)
        for (i in 0 until maxCount) {
            val url = links[i].groupValues[1]
            val titleRaw = links[i].groupValues[2]
            val title = stripHtml(titleRaw).trim()
            val snippet = if (i < snippets.size) stripHtml(snippets[i].groupValues[1]).trim() else ""
            if (title.isNotEmpty() || url.isNotEmpty()) {
                items.add(SearchItem(title = title, url = url, snippet = snippet, summary = null))
            }
        }
        return items
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    // ==================== 格式化输出（回传模型用） ====================

    private fun formatResults(result: WebSearchResult): String {
        val sb = StringBuilder()
        sb.append("[联网搜索] 查询: ${result.query} | 引擎: ${result.engine} | 耗时: ${result.elapsedMs}ms | 结果数: ${result.results.size}")
        sb.append("\n")
        if (!result.answer.isNullOrBlank()) {
            sb.append("AI摘要: ${result.answer}\n")
        }
        if (result.results.isEmpty()) {
            sb.append("（无搜索结果）")
            return sb.toString()
        }
        result.results.forEachIndexed { idx, item ->
            sb.append("${idx + 1}. ${item.title}\n")
            if (item.url.isNotEmpty()) sb.append("   URL: ${item.url}\n")
            if (item.snippet.isNotEmpty()) sb.append("   摘要: ${item.snippet}\n")
            if (!item.summary.isNullOrBlank()) sb.append("   AI摘要: ${item.summary}\n")
        }
        sb.append("\n（搜索已完成，请基于以上结果给出下一步操作）")
        return sb.toString().trimEnd()
    }
}
