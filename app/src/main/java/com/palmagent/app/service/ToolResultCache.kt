package com.palmagent.app.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.MessageDigest

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

    /** 稳定短哈希：SHA-256 截断前 8 字节（64 位），确定性、跨进程一致、抗碰撞，用于生成 fx-ref 与文件名 */
    private fun shortHash(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(s.toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
    }

    /**
     * 通用条目的会话作用域文件哈希：把会话分量并入哈希输入，使不同会话对同一 (tool,args)
     * 生成互不相同的文件/ref，避免一个会话覆盖另一个会话的全文、串扰到对方决策上下文。
     * 文件仍写在根目录（执行侧 fetch_result 无会话参数也能按 ref 定位）。
     */
    private fun fxHash(key: String, session: String): String =
        shortHash(if (session.isBlank()) key else "$session::$key")

    /**
     * 写入任意工具结果：烧录 preview（head+tail）、全文落盘（根目录，文件名/ref 含会话分量）。
     * 同 key 覆盖旧条目。决策侧去重由内存台账按会话隔离负责，磁盘只负责按 ref 保存/取回全文。
     */
    fun put(tool: String, args: Map<String, Any>, content: String, session: String = ""): CachedEntry {
        val key = buildKey(tool, args)
        val hash = fxHash(key, session)
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

    /** 按 key 读取通用条目（决策侧取回全文用；session 决定会话作用域文件）。读取时校验存储 key 防碰撞。 */
    fun getByKey(key: String, session: String = ""): CachedEntry? {
        val dir = cacheDir ?: return null
        val f = File(dir, "fx_${fxHash(key, session)}.json")
        if (!f.exists()) return null
        return try {
            val entry = gson.fromJson(f.readText(), CachedEntry::class.java)
            // 防哈希碰撞/错文件串扰：文件内容 key 必须与请求 key 一致
            if (entry.key == key) entry else null
        } catch (e: Exception) {
            Log.w(TAG, "读通用缓存失败 $key: ${e.message}")
            null
        }
    }

    /** 按 ref 取回完整条目（支持 fx-<hash> 与 ws-<round>-<n>；均在根命名空间）。读取时校验存储 ref 防碰撞。 */
    fun get(ref: String): CachedEntry? {
        if (ref.startsWith("fx-")) {
            val dir = cacheDir ?: return null
            val f = File(dir, "fx_${ref.substring(3)}.json")
            if (!f.exists()) return null
            return try {
                val entry = gson.fromJson(f.readText(), CachedEntry::class.java)
                // 防哈希碰撞/错文件串扰：文件内容 ref 必须与请求 ref 一致
                if (entry.ref == ref) entry else null
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
            cleanupGeneric(dir)
        } catch (e: Exception) {
            Log.w(TAG, "写通用缓存失败: ${e.message}")
        }
    }

    private fun cleanupGeneric(dir: File) {
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

    /** 任务开始清理：仅清 web_search 轮次（search_*.json）与去重索引，保留通用 fx_（决策取回正文仍在生效） */
    fun clearAll() {
        val dir = cacheDir ?: return
        dir.listFiles { f -> f.name.matches(Regex("search_\\d+\\.json")) }?.forEach { it.delete() }
        index.clear()
        Log.d(TAG, "任务开始，清理 web_search 轮次缓存")
    }
}