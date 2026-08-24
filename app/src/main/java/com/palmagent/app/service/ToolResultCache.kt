package com.palmagent.app.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 通用工具结果磁盘缓存（统一缓存：决策台账 + 执行模型 web_search 共用）。
 *
 * 取代原 SearchResultCache，职责扩展为「所有工具结果」：
 * - 通用条目：键 = `tool::<规范化参数>`，存任意工具（list_apps / kb_read / amap_* / web_search…）的完整结果，
 *   预览在 put() 时烧录一次（不可变，唯一预览来源，保证 prompt cache 稳定），全文落盘。
 * - web_search 特殊条目：沿用原「轮次 round + ref(ws-<round>-<n>) + 结构化 items」格式，
 *   是唯一保留结构化字段（title/url/snippet/summary）的工具。
 *
 * 职责边界：只负责 落盘 / 取回 / 去重索引 / 文件保留；
 * 台账的 token 预算、FIFO 淘汰、保护最近 N 条由 DecisionDialogService 服务层承担，本类不处理。
 */
object ToolResultCache {

    private const val TAG = "ToolResultCache"
    private const val DIR_NAME = "tool_cache"
    // web_search 按轮保留最近轮数
    private const val KEEP_ROUNDS = 4
    // 通用条目（fx_*.json）最大文件数，超出后淘汰最旧（按 lastModified）
    private const val MAX_GENERIC_FILES = 60

    /** 通用条目：preview 在 put() 时烧录，之后永不变；web_search 条目则结构化字段非空 */
    data class CachedEntry(
        val key: String,        // "tool::<normalized Args>"
        val ref: String,        // 通用 "fx-<hash8>"; web_search "ws-<round>-<n>"
        val tool: String,
        val preview: String,    // 固化预览（head+tail），唯一预览来源
        val content: String,    // 完整内容/结果（落盘）
        val createdAt: Long,
        // web_search 结构化字段（仅 tool=="web_search" 时非空）
        val title: String = "",
        val url: String = "",
        val snippet: String = "",
        val summary: String? = null
    )

    private val gson = Gson()
    private val listType = object : TypeToken<List<CachedEntry>>() {}.type
    private var cacheDir: File? = null

    // web_search：规范化 query → 轮次（会话内去重索引，同 round 文件）
    private val index = mutableMapOf<String, Int>()

    fun init(context: Context) {
        if (cacheDir != null) return
        cacheDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
    }

    /** 测试专用：指定缓存目录（生产由 init(context) 设置）。 */
    internal fun initForTest(dir: File) {
        cacheDir = dir.apply { mkdirs() }
    }

    /** 测试专用：重置目录与索引，避免污染其他测试。 */
    internal fun resetForTest() {
        cacheDir = null
        index.clear()
    }

    /**
     * 新决策会话开始调用：仅清空通用 fx_ 工具结果，保留 web_search 的 search_ 轮次缓存与去重索引。
     * 决策侧内存台账按 sessionId 隔离，但磁盘缓存是共享的；若不清空，新会话会误复用上一会话
     * （查询/取消重启均不触发任务级 clearAll）写下的 amap/kb_read/list_apps 等通用结果。
     */
    fun clearGenerics() {
        val dir = cacheDir ?: return
        dir.listFiles { f -> f.name.matches(Regex("fx_.*\\.json")) }?.forEach { it.delete() }
        Log.d(TAG, "新决策会话：清空通用工具结果缓存(fx_)")
    }

    // ==================== 通用条目（任意工具） ====================

    /**
     * 规范化 args → 稳定的去重键片段：全角转半角 + 去标点/符号/空白 + lowercase。
     * 用于对任意 Map 参数生成稳定 key，相同 (tool, args) 产出相同 key。
     */
    private fun normalizeText(s: String): String {
        val halfWidth = s.map { ch ->
            when {
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                ch == '\u3000' -> ' '
                else -> ch
            }
        }.joinToString("")
        return halfWidth.replace(Regex("[\\p{P}\\p{S}\\s]+"), "").lowercase().trim()
    }

    /**
     * 通用条目键：`tool::<精确序列化的参数 JSON>`（与删除前的 buildLedgerKey 一致）。
     * 参数用 toSortedMap 保证键序无关；但**不对整个 JSON 做标点/空白归一化**，
     * 否则像 {"keywords":["美团","淘宝"]} 与 {"keywords":["美团淘宝"]} 会碰撞成同一 key、错误复用结果。
     * web_search 的查询关键字归一化只在 putSearch/hitRound 内部对 query 值做，不作用到这里的 key。
     */
    fun buildKey(tool: String, args: Map<String, Any>): String {
        val normalized = args.toSortedMap()
        return "$tool::" + gson.toJson(normalized)
    }

    /** 稳定短哈希（确定性，跨进程一致），用于生成 fx-ref 与文件名 */
    private fun shortHash(s: String): String {
        val h = s.hashCode()
        val hex = h.toLong() and 0xffffffffL.toLong()
        return hex.toString(16).padStart(8, '0')
    }

    /**
     * 写入任意工具结果：烧录 preview（head+tail）、全文落盘，返回条目。
     * 同 key 覆盖旧条目。preview 一旦生成即固化，后续读取绝不再重算。
     */
    fun put(tool: String, args: Map<String, Any>, content: String): CachedEntry {
        val key = buildKey(tool, args)
        val hash = shortHash(key)
        val entry = CachedEntry(
            key = key,
            ref = "fx-$hash",
            tool = tool,
            preview = buildPreview(content),
            content = content,
            createdAt = System.currentTimeMillis()
        )
        writeGenericFile(hash, entry)
        return entry
    }

    /** 按 key 读取通用条目（决策台账/去重用） */
    fun getByKey(key: String): CachedEntry? {
        val dir = cacheDir ?: return null
        val f = File(dir, "fx_${shortHash(key)}.json")
        if (!f.exists()) return null
        return try {
            gson.fromJson(f.readText(), CachedEntry::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "读通用缓存失败 $key: ${e.message}")
            null
        }
    }

    /** 按 ref 取回完整条目（支持 fx-<hash> 与 ws-<round>-<n> 两种 ref） */
    fun get(ref: String): CachedEntry? {
        if (ref.startsWith("fx-")) {
            val dir = cacheDir ?: return null
            val f = File(dir, "fx_${ref.substring(3)}.json")
            if (!f.exists()) return null
            return try {
                gson.fromJson(f.readText(), CachedEntry::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "读通用缓存失败 ref=$ref: ${e.message}")
                null
            }
        }
        // web_search：ws-<round>-<n>
        return getSearchEntry(ref)
    }

    /** 生成预览：head 70% + tail 30%，中间占位；短内容（≤头尾合计）原样返回 */
    fun buildPreview(content: String): String {
        val head = 700
        val tail = 300
        val placeholder = "\n…[完整结果已缓存到磁盘，可用 fetch_result 取回全文]…\n"
        if (content.length <= head + tail) return content
        return buildString {
            append(content.take(head))
            append(placeholder)
            append(content.takeLast(tail))
        }
    }

    // ==================== web_search 特殊条目（沿用原 round/ref/结构化） ====================

    private fun writeGenericFile(hash: String, entry: CachedEntry) {
        val dir = cacheDir ?: return
        val file = File(dir, "fx_$hash.json")
        try {
            file.writeText(gson.toJson(entry))
            // 通用条目保留窗口：超出 MAX_GENERIC_FILES 时删除最旧 fx 文件
            cleanupGeneric()
        } catch (e: Exception) {
            Log.w(TAG, "写通用缓存失败: ${e.message}")
        }
    }

    private fun cleanupGeneric() {
        val dir = cacheDir ?: return
        val files = dir.listFiles { f -> f.name.matches(Regex("fx_.*\\.json")) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size <= MAX_GENERIC_FILES) return
        files.drop(MAX_GENERIC_FILES).forEach { f ->
            f.delete()
            Log.d(TAG, "清理过期通用缓存: ${f.name}")
        }
    }

    /**
     * 缓存一轮搜索结果（完整保存所有条目），返回带 ref 的缓存条目列表（web_search 特殊）。
     * 写入失败返回空列表（调用方回退旧截断逻辑）。
     */
    fun putSearch(round: Int, query: String, items: List<WebSearchService.SearchItem>): List<CachedEntry> {
        val dir = cacheDir ?: return emptyList()
        val file = File(dir, "search_$round.json")
        try {
            val entries = items.mapIndexed { idx, item ->
                CachedEntry(
                    key = "web_search::" + normalizeText(query),
                    ref = "ws-$round-${idx + 1}",
                    tool = "web_search",
                    preview = buildPreview(item.title + "\n" + (item.snippet ?: "")),
                    content = listOf(item.title, item.url, item.snippet, item.summary).filterNotNull().joinToString("\n"),
                    createdAt = System.currentTimeMillis(),
                    title = item.title,
                    url = item.url,
                    snippet = item.snippet,
                    summary = item.summary
                )
            }
            file.writeText(gson.toJson(entries))
            index[normalizeText(query)] = round
            cleanupSearch()
            return entries
        } catch (e: Exception) {
            Log.w(TAG, "写搜索缓存失败: ${e.message}")
            return emptyList()
        }
    }

    /** 同 query 是否命中缓存？返回命中轮次（文件仍存在时），否则 null */
    fun hitRound(query: String): Int? {
        val key = normalizeText(query)
        if (key.isBlank()) return null
        val round = index[key] ?: return null
        val dir = cacheDir ?: return null
        return round.takeIf { File(dir, "search_$round.json").exists() }
    }

    /** 按 ref 取回单条 web_search 缓存条目 */
    private fun getSearchEntry(ref: String): CachedEntry? {
        val dir = cacheDir ?: return null
        val m = Regex("^ws-(\\d+)-(\\d+)$").find(ref) ?: return null
        val round = m.groupValues[1].toIntOrNull() ?: return null
        val idx = (m.groupValues[2].toIntOrNull() ?: return null) - 1
        return readEntries(round)?.getOrNull(idx)
    }

    /** 读取某轮全部缓存条目（供缓存命中复用摘要 / 按 ref 取回原文） */
    fun readEntries(round: Int): List<CachedEntry>? {
        val dir = cacheDir ?: return null
        val file = File(dir, "search_$round.json")
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), listType)
        } catch (e: Exception) {
            Log.w(TAG, "读缓存失败 round=$round: ${e.message}")
            null
        }
    }

    /**
     * 生成摘要视图文本（web_search 供本轮上下文使用，仅本轮看、不入工作区）。
     * 每条：ref + title + snippet(截断)，并提示模型按 ref 取回全文。
     */
    fun buildSummary(query: String, entries: List<CachedEntry>, snippetLimit: Int = 120): String {
        if (entries.isEmpty()) return "【搜索结果摘要】查询: $query | 无结果"
        val sb = StringBuilder()
        sb.append("【搜索结果摘要】查询: $query | 共 ${entries.size} 条")
        sb.append("\n如需某条完整内容，调用 fetch_result 传入 ref；请判断哪些与任务相关，把要点写入工作区。")
        entries.forEach { e ->
            sb.append("\n[${e.ref}] ${e.title}")
            if (e.snippet.isNotBlank()) {
                val cut = if (e.snippet.length > snippetLimit) "${e.snippet.take(snippetLimit)}…" else e.snippet
                sb.append("\n   摘要: $cut")
            }
        }
        return sb.toString()
    }

    /** 保留最近 KEEP_ROUNDS 轮 web_search 文件，删除更早的（同步清理失效索引） */
    private fun cleanupSearch() {
        val dir = cacheDir ?: return
        val files = dir.listFiles { f -> f.name.matches(Regex("search_\\d+\\.json")) }
            ?.sortedByDescending { f -> f.name.removePrefix("search_").removeSuffix(".json").toIntOrNull() ?: 0 }
            ?: return
        val kept = files.take(KEEP_ROUNDS).map { it.name }.toSet()
        files.drop(KEEP_ROUNDS).forEach { f ->
            f.delete()
            Log.d(TAG, "清理过期搜索缓存: ${f.name}")
        }
        index.entries.removeAll { (_, round) -> kept.none { it == "search_$round.json" } }
    }

    /** 任务结束清理：清空文件与索引 */
    fun clearAll() {
        val dir = cacheDir ?: return
        dir.listFiles()?.forEach { it.delete() }
        index.clear()
        Log.d(TAG, "任务结束，清空工具结果缓存")
    }
}