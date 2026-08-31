package com.palmagent.app.utils

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已安装应用信息查询工具
 *
 * 完整获取设备上所有已安装应用的应用名和包名（含系统应用），保存供查询。
 * 决策模型通过传递应用名，获取已保存的包名信息，确认用户设备是否安装对应应用。
 */
object InstalledAppProvider {

    /**
     * 应用安装状态查询结果
     */
    data class AppStatus(
        val appName: String,        // 查询的应用名（输入）
        val packageName: String,    // 匹配到的包名（空字符串表示未找到）
        val installed: Boolean,     // 是否已安装
        val matchType: String       // 匹配类型：exact(精确包名)/fuzzy(模糊名称)/none(未匹配)
    )

    // 全量已安装应用缓存（完整保存，含系统应用）
    @Volatile
    private var installedAppsCache: List<Pair<String, String>>? = null  // (appName, packageName)

    /**
     * 批量查询应用安装状态
     *
     * @param context 上下文
     * @param appNames 应用名称列表，如 ["微信", "支付宝", "国家医保服务平台"]
     * @return 每个应用的安装状态
     */
    suspend fun queryApps(context: Context, appNames: List<String>): List<AppStatus> =
        withContext(Dispatchers.IO) {
            if (appNames.isEmpty()) return@withContext emptyList()

            val allApps = getInstalledAppsList(context)

            appNames.map { queryName ->
                // 1. 精确包名匹配（如输入 "com.tencent.mm"）
                val exactMatch = allApps.find { it.second == queryName }
                if (exactMatch != null) {
                    return@map AppStatus(queryName, exactMatch.second, true, "exact")
                }

                // 2. 模糊名称匹配（如输入 "微信" 匹配 "微信" 应用名）
                val fuzzyMatch = allApps.find { app ->
                    app.first.contains(queryName, ignoreCase = true) ||
                    queryName.contains(app.first, ignoreCase = true)
                }
                if (fuzzyMatch != null) {
                    return@map AppStatus(queryName, fuzzyMatch.second, true, "fuzzy")
                }

                // 3. 未匹配（应用未安装）
                AppStatus(queryName, "", false, "none")
            }
        }

    /**
     * 获取全量已安装应用列表（完整获取，含系统应用，懒加载缓存）
     *
     * public 暴露：供 ListAppsTool 等工具按关键词过滤查询
     * 内部仍走缓存机制，避免重复扫描
     */
    suspend fun getInstalledAppsList(context: Context): List<Pair<String, String>> {
        installedAppsCache?.let { return it }

        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = try {
            pm.queryIntentActivities(launchIntent, 0)
        } catch (e: Exception) {
            emptyList()
        }.mapNotNull { resolveInfo ->
            try {
                val appInfo = resolveInfo.activityInfo.applicationInfo
                val appName = pm.getApplicationLabel(appInfo).toString()
                val packageName = appInfo.packageName
                Pair(appName, packageName)
            } catch (e: Exception) {
                null
            }
        }.distinctBy { it.second }

        installedAppsCache = apps
        return apps
    }

    /**
     * 清除缓存（应用安装/卸载后或新任务开始时调用）
     */
    fun clearCache() {
        installedAppsCache = null
    }
}
