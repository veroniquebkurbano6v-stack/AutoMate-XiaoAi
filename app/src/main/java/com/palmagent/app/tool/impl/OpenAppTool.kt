package com.palmagent.app.tool.impl

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.palmagent.app.AgentApplication
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import com.palmagent.app.utils.InstalledAppProvider
import kotlinx.coroutines.delay

class OpenAppTool : BaseTool() {
    override fun getName(): String = "open_app"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("app_name", "string", "应用名称（如 微信、支付宝、B站）或包名（如 com.tencent.mm）", true)
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val appInput = requireString(params, "app_name")

        val context = getA11yService() ?: AgentApplication.instance

        // 通过 InstalledAppProvider 查找包名（支持应用名和包名两种输入）
        val appStatus = InstalledAppProvider.queryApps(context, listOf(appInput)).firstOrNull()
        val packageName = appStatus?.packageName

        if (packageName.isNullOrEmpty()) {
            return ToolResult.error(
                "应用 $appInput 未安装",
                errorType = "FATAL",
                failureCategory = "APP_NOT_INSTALLED",
                code = "APP_NOT_INSTALLED",
                suggestion = "应用未安装：换用替代方案或征得用户同意后引导安装"
            )
        }

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent == null) {
                return ToolResult.error(
                    "无法启动 $appInput (包名: $packageName)",
                    errorType = "FATAL",
                    failureCategory = "APP_NOT_INSTALLED",
                    code = "APP_NOT_INSTALLED",
                    suggestion = "应用可能未安装：换用替代方案或征得用户同意后引导安装"
                )
            }
            // P0 修复：必须重置任务栈，否则会停留在之前的 Activity（聊天详情页等）
            // - FLAG_ACTIVITY_NEW_TASK：从非 Activity context 启动必需
            // - FLAG_ACTIVITY_CLEAR_TASK：完全清空目标应用的旧任务栈（API 11+）
            //   避免 open_app 微信时保留在聊天详情页/小程序页等深路径
            // - FLAG_ACTIVITY_RESET_TASK_IF_NEEDED：系统按需重置 launchMode 状态
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            // 方案 1：startActivity 前等待窗口稳定——悬浮窗状态刷新/insets 重算会触发本 App HomeActivity 配置变化
            // （onConfigurationChange），与其撞车会延迟/覆盖外部应用启动（日志实证：首次 open_app 前台确认超时 5s，重试 540ms 成功）
            delay(600)
            // 方案 2：前台确认超时自动重试一次（再 startActivity + 再确认）；重试仍失败才返回失败（不再掩盖启动未生效）
            var confirmedMs: Long? = null
            for (attempt in 1..2) {
                context.startActivity(intent)
                confirmedMs = waitForForeground(packageName, timeoutMs = 5000L)
                if (confirmedMs != null) break
                Log.d("OpenAppTool", "第${attempt}次启动前台确认超时（5s）——自动重试第${attempt + 1}次")
                delay(600)
            }
            if (confirmedMs != null) {
                Log.d("OpenAppTool", "已启动应用: $appInput → $packageName（前台确认 ${confirmedMs}ms）")
                ToolResult.success("已启动应用: $appInput ($packageName)")
            } else {
                Log.d(
                    "OpenAppTool",
                    "已启动应用: $appInput → $packageName（两次前台确认均超时——启动未生效，可能被系统拦截）"
                )
                ToolResult.error(
                    "启动应用未确认: $appInput（两次前台确认均超时，启动可能被系统拦截或窗口竞争）",
                    errorType = "TRANSIENT",
                    suggestion = "启动可能被系统限制，请重试或检查目标应用状态"
                )
            }
        } catch (e: Exception) {
            ToolResult.error(
                "启动应用失败: ${e.message}",
                errorType = "TRANSIENT",
                suggestion = "启动失败，可能是系统繁忙，可重试一次"
            )
        }
    }

    /** startActivity 后等待目标应用到达前台；返回确认耗时 ms，超时返回 null（不失败——下轮界面确认） */
    private suspend fun waitForForeground(packageName: String, timeoutMs: Long): Long? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val fg = runCatching {
                GUIAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
            }.getOrNull()
            if (fg == packageName) {
                return System.currentTimeMillis() - start
            }
            delay(500)
        }
        return null
    }

    /**
     * 通过应用商店引导用户安装应用
     * 使用 market:// Intent，应用商店未安装时降级为网页版
     */
    fun openAppStore(packageName: String): ToolResult {
        val context = getA11yService() ?: AgentApplication.instance
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://details?id=$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult.success("已打开应用商店，包名: $packageName")
        } catch (e: ActivityNotFoundException) {
            // 应用商店未安装，降级为网页版
            try {
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                ToolResult.success("已打开应用商店网页版，包名: $packageName")
            } catch (e2: Exception) {
                ToolResult.error(
                    "无法打开应用商店",
                    errorType = "FATAL",
                    failureCategory = "SERVICE_UNAVAILABLE",
                    code = "APP_STORE_UNAVAILABLE",
                    suggestion = "设备无应用商店，需换用其他替代方案"
                )
            }
        }
    }

    override fun getDescriptionEN(): String =
        "Open an app by its name (e.g. WeChat, Alipay) or package name (e.g. com.tencent.mm)."

    override fun getDescriptionCN(): String =
        "通过应用名打开指定应用（如 微信、支付宝、B站），也可传包名。工具内部自动检索已安装应用匹配包名。"
}
