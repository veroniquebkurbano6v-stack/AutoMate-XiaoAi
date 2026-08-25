package com.palmagent.app.floating

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.sin

/**
 * 录音音波可视化 View
 *
 * 用 Canvas 绘制 5 根垂直音波条，高度随时间正弦波动 + 音量驱动
 * 中间条最高、两侧递减，模拟真实音波分布（参考 iPhone 录音界面效果）
 *
 * 使用场景：
 * - 主界面麦克风按钮（录音时替代静态麦克风图标）
 * - 悬浮窗麦克风按钮（录音时替代静态麦克风图标）
 *
 * 动画原理：
 * - 每根条有独立相位偏移，形成"波浪"传播效果
 * - 音量越大，条的整体高度越高（真实反馈说话响度）
 * - 使用 ValueAnimator 驱动 phase 变化，onDraw 时计算实时高度
 */
class MicWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** 音波条数量（奇数，保证中心有一条最高的） */
        private const val BAR_COUNT = 5

        /** 相邻条的相位差（弧度）：形成波浪传播效果 */
        private const val PHASE_STEP = 0.8f

        /** 动画周期（ms）：完成一次完整波动的时间 */
        private const val WAVE_PERIOD = 600L

        /** 基础条高系数（相对于可用高度的比例） */
        private const val BASE_HEIGHT_RATIO = 0.25f

        /** 波动幅度系数（相对于可用高度的比例） */
        private const val WAVE_AMPLITUDE = 0.25f

        /** 音量驱动的额外高度系数 */
        private const val VOLUME_BOOST = 0.30f
    }

    /** 绘制音波条的 Paint */
    private val barPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    /** 每根条的圆角矩形（复用避免 GC） */
    private val barRects = Array(BAR_COUNT) { RectF() }

    /** 相位动画器（驱动所有条的波动） */
    private var phaseAnimator: ValueAnimator? = null

    /** 当前相位（弧度） */
    private var currentPhase = 0f

    /** 实时音量 [0, 1]，由外部 setVolume() 提供 */
    private var volume = 0.3f

    /** 是否正在录音（控制动画启停） */
    private var isRecording = false

    /**
     * 设置音量，驱动音波高度
     * @param volume 归一化音量 [0, 1]
     */
    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
        if (isRecording) invalidate()
    }

    /**
     * 开始音波动画
     */
    fun startWaveform() {
        isRecording = true
        if (phaseAnimator?.isRunning == true) return

        phaseAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = WAVE_PERIOD
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                currentPhase = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * 停止音波动画
     */
    fun stopWaveform() {
        isRecording = false
        phaseAnimator?.cancel()
        phaseAnimator = null
        currentPhase = 0f
        volume = 0.3f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        // 计算每根条的宽度和间距
        val totalBarArea = width / BAR_COUNT
        val barWidth = totalBarArea * 0.45f  // 条宽 = 间距的 45%，留出间隙
        val gap = totalBarArea - barWidth

        // 可用高度（上下留 10% 边距）
        val availableHeight = height * 0.85f
        val topPadding = height * 0.075f

        // 基础高度 + 音量驱动额外高度
        val baseHeight = availableHeight * BASE_HEIGHT_RATIO
        val amplitude = availableHeight * WAVE_AMPLITUDE + availableHeight * volume * VOLUME_BOOST

        // 绘制每根条
        for (i in 0 until BAR_COUNT) {
            // 计算该条的相位（中心条相位 = currentPhase，两侧对称偏移）
            val phaseOffset = if (i <= BAR_COUNT / 2) {
                (BAR_COUNT / 2 - i) * PHASE_STEP
            } else {
                (i - BAR_COUNT / 2) * PHASE_STEP
            }
            val barPhase = currentPhase + phaseOffset

            // 条高 = 基础 + 波动（正弦）+ 音量驱动
            val waveComponent = sin(barPhase.toDouble()).toFloat() * amplitude
            // 中心条最高、两侧递减（高斯分布权重）
            val centerWeight = 1f - kotlin.math.abs(i - BAR_COUNT / 2) * 0.15f
            val barHeight = (baseHeight + waveComponent * centerWeight)
                .coerceIn(height * 0.08f, height * 0.95f)

            // 条的位置（居中对齐）
            val left = i * totalBarArea + gap / 2f
            val top = topPadding + (availableHeight - barHeight) / 2f
            val right = left + barWidth
            val bottom = top + barHeight

            barRects[i].set(left, top, right, bottom)
            canvas.drawRoundRect(barRects[i], barWidth / 2f, barWidth / 2f, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopWaveform()
    }
}
