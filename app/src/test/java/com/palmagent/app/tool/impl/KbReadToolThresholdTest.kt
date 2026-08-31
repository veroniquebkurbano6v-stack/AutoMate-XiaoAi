package com.palmagent.app.tool.impl

import org.junit.Assert.assertTrue
import org.junit.Test

class KbReadToolThresholdTest {

    @Test
    fun `scoreBelowThreshold_isNotUsable`() {
        val tool = KbReadTool()
        assertTrue("0.34 相似度应判定不相关", !tool.isScoreUsable(0.34))
        assertTrue("0.45 应被 0.5 阈值拒绝（旧 0.3 会通过）", !tool.isScoreUsable(0.45))
    }

    @Test
    fun `scoreAtOrAboveThreshold_isUsable`() {
        val tool = KbReadTool()
        assertTrue("score == 阈值(0.5) 应可用（>= 含边界）", tool.isScoreUsable(0.5))
        assertTrue("0.72 相似度应判定相关", tool.isScoreUsable(0.72))
    }
}