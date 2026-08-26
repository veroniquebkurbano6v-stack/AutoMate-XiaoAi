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
 * 高德地图 MCP 工具
 *
 * 提供地点搜索、周边搜索、路线规划、天气查询等功能。
 * 自动附带设备位置信息（需位置权限）。
 */
class AmapSearchTool : BaseTool() {

    companion object {
        private const val TAG = "AmapSearchTool"
    }

    private val mcpService = WebMCPService()

    override fun getName(): String = "amap_search"
    override fun isExposedToExecutionModel(): Boolean = false

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "keywords",
            "string",
            "搜索关键词，如'医院'、'星巴克'、'皮肤科'",
            true
        ),
        ToolParameter(
            "city",
            "string",
            "城市名称（可选），如'北京'、'上海'",
            false
        )
    )

    override fun getDescriptionEN(): String =
        "Search for locations using Amap (Gaode Maps). Automatically uses device location if available. " +
        "Use this to find hospitals, restaurants, shops, etc."

    override fun getDescriptionCN(): String =
        "使用高德地图搜索地点。自动附带设备位置（需位置权限）。用于查找医院、餐厅、商店等。"

    override fun getDisplayName(): String = "高德搜索"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        // 检查是否启用
        if (!KVUtils.getAmapMcpEnabled()) {
            return ToolResult.error("高德地图 MCP 未启用，请在设置中开启")
        }

        val keywords = requireString(params, "keywords")
        if (keywords.isBlank()) {
            return ToolResult.error("搜索关键词不能为空")
        }

        val city = optionalString(params, "city", "")

        // 检查位置权限
        val hasLocation = LocationService.hasLocationPermission(AgentApplication.instance)
        if (!hasLocation) {
            Log.w(TAG, "无位置权限，搜索结果可能不准确")
        }

        Log.d(TAG, "高德搜索: keywords='$keywords', city='$city', hasLocation=$hasLocation")

        val result = mcpService.amapSearch(keywords, city)

        return if (result.success) {
            val output = buildString {
                appendLine("高德地图搜索结果：")
                appendLine(result.content)
                if (!hasLocation) {
                    appendLine()
                    appendLine("提示：未授予位置权限，搜索结果可能不是附近的地点")
                }
            }
            ToolResult.success(output)
        } else {
            ToolResult.error(result.error ?: "高德搜索失败")
        }
    }
}