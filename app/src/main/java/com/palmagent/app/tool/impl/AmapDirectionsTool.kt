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
 * 高德地图路线规划工具
 *
 * 从当前位置规划到目的地的路线（需位置权限）。
 */
class AmapDirectionsTool : BaseTool() {

    companion object {
        private const val TAG = "AmapDirectionsTool"
    }

    private val mcpService = WebMCPService()

    override fun getName(): String = "amap_directions"
    override fun isExposedToExecutionModel(): Boolean = false

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "destination",
            "string",
            "目的地，如'北京站'、'协和医院'或坐标'116.481028,39.989643'",
            true
        ),
        ToolParameter(
            "mode",
            "string",
            "出行方式：drive（驾车）、walk（步行）、bus（公交）、bike（骑行），默认drive",
            false,
            default = "drive"
        )
    )

    override fun getDescriptionEN(): String =
        "Get directions from current location to destination using Amap. Requires location permission. " +
        "Supports driving, walking, public transit, and cycling modes."

    override fun getDescriptionCN(): String =
        "使用高德地图规划路线。从当前位置到目的地。需要位置权限。支持驾车、步行、公交、骑行。"

    override fun getDisplayName(): String = "路线规划"

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        if (!KVUtils.getAmapMcpEnabled()) {
            return ToolResult.error("高德地图 MCP 未启用，请在设置中开启")
        }

        val destination = requireString(params, "destination")
        if (destination.isBlank()) {
            return ToolResult.error("目的地不能为空")
        }

        val mode = optionalString(params, "mode", "drive")
        val validModes = listOf("drive", "car", "walk", "bus", "bike")
        val normalizedMode = when (mode.lowercase()) {
            "car" -> "drive"
            in validModes -> mode.lowercase()
            else -> "drive"
        }

        // 检查位置权限（路线规划必须要有起点位置）
        val hasLocation = LocationService.hasLocationPermission(AgentApplication.instance)
        if (!hasLocation) {
            return ToolResult.error("路线规划需要位置权限，请在首页权限管理中授予")
        }

        Log.d(TAG, "高德路线规划: destination='$destination', mode=$normalizedMode")

        val result = mcpService.amapDirections(destination, normalizedMode)

        return if (result.success) {
            val modeDesc = when (normalizedMode) {
                "drive" -> "驾车"
                "walk" -> "步行"
                "bus" -> "公交"
                "bike" -> "骑行"
                else -> normalizedMode
            }
            val output = buildString {
                appendLine("高德地图路线规划（$modeDesc）：")
                appendLine(result.content)
            }
            ToolResult.success(output)
        } else {
            ToolResult.error(result.error ?: "路线规划失败")
        }
    }
}