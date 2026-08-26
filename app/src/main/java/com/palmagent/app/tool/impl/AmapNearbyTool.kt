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
 * 高德地图周边搜索工具
 *
 * 搜索当前位置周边的地点（需位置权限）。
 */
class AmapNearbyTool : BaseTool() {

    companion object {
        private const val TAG = "AmapNearbyTool"
        private const val DEFAULT_RADIUS = 1000
    }

    private val mcpService = WebMCPService()

    override fun getName(): String = "amap_nearby"
    override fun isExposedToExecutionModel(): Boolean = false

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "keywords",
            "string",
            "搜索关键词，如'医院'、'餐厅'、'药店'",
            true
        ),
        ToolParameter(
            "radius",
            "integer",
            "搜索半径（米），默认1000，最大5000",
            false,
            default = DEFAULT_RADIUS,
            minValue = 100,
            maxValue = 5000
        )
    )

    override fun getDescriptionEN(): String =
        "Search for nearby locations using Amap. Requires location permission. " +
        "Use this to find nearby hospitals, restaurants, pharmacies, etc."

    override fun getDescriptionCN(): String =
        "使用高德地图搜索周边地点。需要位置权限。用于查找附近的医院、餐厅、药店等。"

    override fun getDisplayName(): String = "周边搜索"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        if (!KVUtils.getAmapMcpEnabled()) {
            return ToolResult.error("高德地图 MCP 未启用，请在设置中开启")
        }

        val keywords = requireString(params, "keywords")
        if (keywords.isBlank()) {
            return ToolResult.error("搜索关键词不能为空")
        }

        val radius = optionalInt(params, "radius", DEFAULT_RADIUS).coerceIn(100, 5000)

        // 检查位置权限（周边搜索必须要有位置）
        val hasLocation = LocationService.hasLocationPermission(AgentApplication.instance)
        if (!hasLocation) {
            return ToolResult.error("周边搜索需要位置权限，请在首页权限管理中授予")
        }

        Log.d(TAG, "高德周边搜索: keywords='$keywords', radius=$radius")

        val result = mcpService.amapNearby(keywords, radius)

        return if (result.success) {
            val output = buildString {
                appendLine("高德地图周边搜索结果（半径${radius}米）：")
                appendLine(result.content)
            }
            ToolResult.success(output)
        } else {
            ToolResult.error(result.error ?: "周边搜索失败")
        }
    }
}