package com.palmagent.app.tool.impl

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.palmagent.app.AgentApplication
import com.palmagent.app.LiveLogBuffer
import com.palmagent.app.floating.UserActionManager
import com.palmagent.app.floating.UserActionManager.UserActionMode
import com.palmagent.app.floating.UserActionManager.UserActionRequest
import com.palmagent.app.floating.UserActionManager.UserActionResult
import com.palmagent.app.floating.UserActionManager.UserActionResponse
import com.palmagent.app.service.GUIAccessibilityService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 用户手动操作工具
 *
 * 请求用户进行手动操作（登录、验证码、支付等），暂停Agent任务直到用户完成。
 * 显示顶部Banner提示 + 系统通知兜底，不遮挡主要操作区域。
 *
 * 流程：
 * 1. 检查悬浮窗权限，无权限时降级为纯通知模式
 * 2. 显示顶部Banner（状态栏下方，60dp）
 * 3. 发送系统通知（带震动）
 * 4. 暂停Agent，等待用户操作
 * 5. 用户确认/跳过/超时后，采集屏幕快照返回给模型
 */
class UserActionTool : BaseTool() {

    companion object {
        private const val TAG = "UserActionTool"
    }

    override fun getName(): String = "request_user_action"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "title",
            "string",
            "操作标题，简洁描述需要用户做什么（如：请完成登录、请输入支付密码）",
            true
        ),
        ToolParameter(
            "steps",
            "string",
            "步骤列表，每行一个步骤（如：1. 输入手机号\\n2. 点击获取验证码）",
            false
        ),
        ToolParameter(
            "mode",
            "string",
            "提示模式：confirm（需确认）/ alert（仅提示），默认confirm",
            false,
            enumValues = listOf("confirm", "alert")
        ),
        ToolParameter(
            "allow_skip",
            "boolean",
            "是否允许跳过，默认true",
            false,
            default = true
        )
    )

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val title = requireString(params, "title")
        val steps = optionalString(params, "steps", "")
        val mode = optionalString(params, "mode", "confirm")
        val allowSkip = optionalString(params, "allow_skip", "true").toBoolean()

        Log.d(TAG, "请求用户手动操作: $title")
        LiveLogBuffer.append("🖐 请求用户操作: $title")

        val request = UserActionRequest(
            title = title,
            steps = if (steps.isBlank()) emptyList() else steps.split("\n").filter { it.isNotBlank() },
            mode = if (mode == "alert") UserActionMode.ALERT else UserActionMode.CONFIRM,
            allowSkip = allowSkip
        )

        // P1：权限降级 — 无悬浮窗权限时仅使用系统通知
        if (!hasOverlayPermission()) {
            Log.w(TAG, "无悬浮窗权限，降级为纯通知模式")
            LiveLogBuffer.append("  ⚠ 无悬浮窗权限，使用通知模式")
            return executeWithNotificationOnly(request)
        }

        return try {
            val response = awaitUserAction(request)
            when (response.action) {
                UserActionResult.DONE -> {
                    val snapshot = collectSnapshot()
                    Log.d(TAG, "用户完成操作: $title")
                    LiveLogBuffer.append("  ✅ 用户完成操作: $title")
                    ToolResult.success(snapshot)
                }
                UserActionResult.SKIP -> {
                    Log.d(TAG, "用户跳过操作: $title")
                    LiveLogBuffer.append("  ⏭️ 用户跳过: $title")
                    ToolResult.success("用户跳过了此步骤")
                }
                UserActionResult.CANCEL -> {
                    Log.d(TAG, "用户取消操作: $title")
                    LiveLogBuffer.append("  ❌ 用户取消: $title")
                    ToolResult.error(
                        "用户取消了操作",
                        errorType = "FATAL",
                        failureCategory = "USER_CANCELLED",
                        code = "USER_CANCELLED",
                        suggestion = "用户主动取消，应终止任务"
                    )
                }
            }
        } catch (e: CancellationException) {
            UserActionManager.cancel()
            throw e
        } catch (e: Exception) {
            UserActionManager.cancel()
            Log.e(TAG, "等待用户操作异常: ${e.message}")
            ToolResult.error("等待用户操作时出错: ${e.message}")
        }
    }

    private suspend fun awaitUserAction(request: UserActionRequest): UserActionResponse {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            UserActionManager.requestUserAction(request) { response ->
                if (cont.isActive) {
                    cont.resumeWith(Result.success(response))
                }
            }
            cont.invokeOnCancellation {
                UserActionManager.cancel()
            }
        }
    }

    /**
     * P0修复：collectSnapshot 可靠化
     * 使用轮询检测屏幕稳定（连续两次采集包名相同），替代硬编码 600ms 延迟
     */
    private suspend fun collectSnapshot(): String {
        return try {
            // 等待屏幕稳定：最多轮询 2 秒，连续两次包名相同视为稳定
            val stable = waitForScreenStable(timeoutMs = 2000)
            if (!stable) delay(500) // 降级：固定等待

            val screenInfo = withContext(Dispatchers.Main) {
                GUIAccessibilityService.instance?.getCurrentScreenInfo()
            }
            val pkg = screenInfo?.currentPackage ?: "未知"
            val elementCount = screenInfo?.uiElements?.size ?: 0
            "用户已完成操作，当前界面: $pkg, UI元素: $elementCount"
        } catch (e: Exception) {
            "用户已完成操作"
        }
    }

    /**
     * 等待屏幕稳定：轮询检测连续两次包名相同
     * @return true 表示屏幕已稳定，false 表示超时未稳定
     */
    private suspend fun waitForScreenStable(timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        var lastPackage: String? = null

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(300)
            val currentPackage = withContext(Dispatchers.Main) {
                GUIAccessibilityService.instance?.getCurrentScreenInfo()?.currentPackage
            }

            if (currentPackage != null && currentPackage == lastPackage) {
                return true // 连续两次包名相同，屏幕已稳定
            }
            lastPackage = currentPackage
        }
        return false
    }

    /**
     * P1：检查悬浮窗权限
     */
    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(AgentApplication.instance)
        } else {
            true
        }
    }

    /**
     * P1：纯通知模式降级
     * 无悬浮窗权限时，仅发送系统通知，用户通过通知回到应用后确认
     */
    private suspend fun executeWithNotificationOnly(request: UserActionRequest): ToolResult {
        return try {
            // 仅发送通知，不显示Banner
            com.palmagent.app.floating.UserGuideNotifier.showNotification(request)

            // 等待用户操作（复用 UserActionManager，但跳过 Banner）
            val response = awaitUserAction(request)
            when (response.action) {
                UserActionResult.DONE -> {
                    val snapshot = collectSnapshot()
                    ToolResult.success(snapshot)
                }
                UserActionResult.SKIP -> ToolResult.success("用户跳过了此步骤")
                UserActionResult.CANCEL -> ToolResult.error(
                    "用户取消了操作",
                    errorType = "FATAL",
                    failureCategory = "USER_CANCELLED",
                    code = "USER_CANCELLED",
                    suggestion = "用户主动取消，应终止任务"
                )
            }
        } catch (e: CancellationException) {
            UserActionManager.cancel()
            throw e
        } catch (e: Exception) {
            UserActionManager.cancel()
            ToolResult.error("等待用户操作时出错: ${e.message}")
        }
    }

    override fun getDescriptionEN(): String =
        "Request user to perform manual action (login, verification code, payment, etc.). " +
        "Pauses agent task and shows floating panel + notification until user confirms. " +
        "Use when encountering security operations that cannot be automated (passwords, biometrics, payments)."

    override fun getDescriptionCN(): String =
        "请求用户进行手动操作（登录、验证码、支付等），暂停任务直到用户完成。" +
        "遇到无法自动化的安全操作时使用（密码输入、生物识别、支付确认）。" +
        "显示悬浮窗提示+系统通知，不遮挡操作区域。"

    override fun getDisplayName(): String = "用户操作"
}
