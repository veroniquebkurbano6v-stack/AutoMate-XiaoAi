package com.palmagent.app.tool.impl

import android.util.Log
import com.palmagent.app.AgentApplication
import com.palmagent.app.service.LocationService
import com.palmagent.app.service.WebMCPService
import com.palmagent.app.tool.BaseTool
import com.palmagent.app.tool.ToolParameter
import com.palmagent.app.tool.ToolResult
import com.palmagent.app.utils.KVUtils

/**
 * 高德地图天气查询工具
 *
 * 查询当前位置的天气信息。
 */
class AmapWeatherTool : BaseTool() {

    companion object {
        private const val TAG = "AmapWeatherTool"
    }

    private val mcpService = WebMCPService()

    override fun getName(): String = "amap_weather"
    override fun isExposedToExecutionModel(): Boolean = false

    override fun getParameters(): List<ToolParameter> = listOf()

    override fun getDescriptionEN(): String =
        "Get weather information for current location using Amap. " +
        "Automatically uses device location if available."

    override fun getDescriptionCN(): String =
        "使用高德地图查询当前位置的天气。自动附带设备位置（需位置权限）。"

    override fun getDisplayName(): String = "天气查询"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        if (!KVUtils.getAmapMcpEnabled()) {
            return ToolResult.error("高德地图 MCP 未启用，请在设置中开启")
        }

        val hasLocation = LocationService.hasLocationPermission(AgentApplication.instance)
        if (!hasLocation) {
            Log.w(TAG, "无位置权限，天气查询可能不准确")
        }

        Log.d(TAG, "高德天气查询: hasLocation=$hasLocation")

        val result = mcpService.amapWeather()

        return if (result.success) {
            val output = buildString {
                appendLine("高德地图天气查询：")
                appendLine(result.content)
                if (!hasLocation) {
                    appendLine()
                    appendLine("提示：未授予位置权限，天气信息可能不是当前位置")
                }
            }
            ToolResult.success(output)
        } else {
            ToolResult.error(result.error ?: "天气查询失败")
        }
    }
}