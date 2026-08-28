package com.palmagent.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 无障碍服务辅助工具
 *
 * 通过 WRITE_SECURE_SETTINGS 权限实现编程方式恢复无障碍服务。
 * 需要一次性 ADB 授权：adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS
 * 授权后永久有效（卸载重装才失效）。
 */
object AccessibilityServiceHelper {
    private const val TAG = "A11yHelper"

    /**
     * 检查是否拥有 WRITE_SECURE_SETTINGS 权限
     */
    fun canWriteSecureSettings(context: Context): Boolean {
        return context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查无障碍服务是否已开启（标准 API：AccessibilityManager 的启用服务列表）
     *
     * 用于启动引导：无障碍被系统（MIUI 后台清理/Android 14 崩溃禁用）关闭时，
     * 引导用户重新开启 + 添加白名单（自启动/电池优化/省电策略）。
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager ?: return false
        val expected = android.content.ComponentName(context, GUIAccessibilityService::class.java)
        return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                it.resolveInfo.serviceInfo.packageName == expected.packageName &&
                    it.resolveInfo.serviceInfo.name == expected.className
            }
    }

    /**
     * 弹出无障碍与后台保活引导对话框（设置页菜单 + 启动自动检测共用）
     *
     * 说明：MIUI 后台清理/Android 14 崩溃禁用会把无障碍服务关闭，需引导用户
     * 重新开启 + 添加白名单（自启动/电池优化/省电策略）。
     */
    fun showAccessibilityGuideDialog(activity: android.app.Activity) {
        val miui = android.os.Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
        val message = buildString {
            append("无障碍权限在应用退后台时被系统自动关闭，通常原因：\n")
            append("1. 系统省电策略/后台清理回收了无障碍服务\n")
            append("2. 服务异常导致系统主动禁用（Android 14 起崩溃 2 次即自动禁用）\n")
            if (miui) {
                append("\nMIUI 请完成以下白名单设置（一次性解决）：\n")
                append("   • 自启动管理 = 允许\n")
                append("   • 电池优化 = 无限制\n")
                append("   • 省电策略 = 无限制\n")
            }
            append("\n设置完成后回到本应用重新开启「视觉执行/自动化」相关功能。")
        }
        android.app.AlertDialog.Builder(activity)
            .setTitle(if (miui) "无障碍与后台保活（MIUI）" else "无障碍与后台保活")
            .setMessage(message)
            .setPositiveButton("无障碍服务设置") { _, _ ->
                activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("电池优化") { _, _ ->
                val powerManager = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!powerManager.isIgnoringBatteryOptimizations(activity.packageName)) {
                    @Suppress("DEPRECATION")
                    val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = android.net.Uri.parse("package:${activity.packageName}")
                    activity.startActivity(intent)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 通过 WRITE_SECURE_SETTINGS 编程启用无障碍服务
     *
     * 原理：直接写入 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES，
     * 让系统重新绑定无障碍服务，无需用户手动到设置页开启。
     *
     * @return true 表示成功写入或服务已启用
     */
    fun ensureServiceEnabled(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) {
            Log.w(TAG, "无 WRITE_SECURE_SETTINGS 权限，无法编程恢复")
            return false
        }

        return try {
            val serviceName = "${context.packageName}/${context.packageName}.service.GUIAccessibilityService"

            // 读取当前已启用的无障碍服务列表
            val currentServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""

            // 检查是否已包含本服务
            if (currentServices.contains(serviceName)) {
                // 已在列表中但服务未运行，先移除再添加以触发系统重新绑定
                val cleaned = currentServices.split(":")
                    .filter { it.isNotBlank() && it != serviceName }
                    .joinToString(":")
                val newServices = if (cleaned.isBlank()) serviceName else "$cleaned:$serviceName"

                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServices
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
                Log.i(TAG, "已通过 WRITE_SECURE_SETTINGS 重新绑定无障碍服务")
            } else {
                // 不在列表中，直接添加
                val newServices = if (currentServices.isBlank()) {
                    serviceName
                } else {
                    "$currentServices:$serviceName"
                }

                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    newServices
                )
                Settings.Secure.putInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
                Log.i(TAG, "已通过 WRITE_SECURE_SETTINGS 启用无障碍服务")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "编程启用无障碍服务失败: ${e.message}")
            false
        }
    }
}
