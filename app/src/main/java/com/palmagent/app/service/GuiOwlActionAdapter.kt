package com.palmagent.app.service

import com.palmagent.app.model.AgentAction
import com.palmagent.app.model.Coordinate

/**
 * GUI-Plus 动作适配器
 *
 * 将 GuiOwlService.DecideResult（action + coordinate）映射为 AgentAction。
 * action 已在 GuiOwlService.normalizeAction() 归一化到统一动作集：
 * - click          → tap         (coordinate)
 * - long_press     → long_press  (coordinate)
 * - swipe          → swipe       (coordinate + coordinateEnd)
 * - type           → auto_input  (text)
 * - system_button  → back / home (button name)
 * - open           → open_app    (text=应用名)
 * - wait           → wait
 * - answer         → visual_describe (text=回答内容)
 * - terminate      → finish
 */
object GuiOwlActionAdapter {

    fun adapt(result: GuiOwlService.DecideResult): AgentAction {
        val baseConfidence = if (result.success) 1.0f else 0.0f

        return when (result.action.lowercase().trim()) {
            "click" -> AgentAction(
                type = "tap",
                coordinate = result.coordinate,
                description = "点击",
                confidence = baseConfidence
            )

            "long_press" -> AgentAction(
                type = "long_press",
                coordinate = result.coordinate,
                description = "长按",
                confidence = baseConfidence
            )

            "swipe" -> AgentAction(
                type = "swipe",
                coordinate = result.coordinate,
                coordinateEnd = result.coordinateEnd,
                description = "滑动",
                confidence = baseConfidence
            )

            "type" -> AgentAction(
                type = "auto_input",
                text = result.text ?: "",
                description = "输入文本",
                confidence = baseConfidence
            )

            "system_button" -> {
                val button = result.text?.lowercase()?.trim() ?: "back"
                if (button == "home") {
                    AgentAction(
                        type = "home",
                        description = "主页键",
                        confidence = baseConfidence
                    )
                } else {
                    AgentAction(
                        type = "back",
                        description = "返回键",
                        confidence = baseConfidence
                    )
                }
            }

            "open" -> AgentAction(
                type = "open_app",
                text = result.text,
                description = "打开应用",
                confidence = baseConfidence
            )

            "wait" -> AgentAction(
                type = "wait",
                description = "等待",
                confidence = baseConfidence
            )

            "answer" -> AgentAction(
                type = "visual_describe",
                text = result.text,
                description = "视觉描述",
                confidence = baseConfidence
            )

            "terminate" -> AgentAction(
                type = "finish",
                description = "任务完成",
                confidence = baseConfidence
            )

            else -> AgentAction(
                type = "wait",
                description = "未知动作: ${result.action}",
                confidence = 0.0f
            )
        }
    }
}
