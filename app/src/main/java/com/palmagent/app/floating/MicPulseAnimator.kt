package com.palmagent.app.floating

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * 麦克风录音水波纹动画管理器（主界面 + 悬浮窗共用）
 *
 * Issue #1：优化麦克风按钮点击/录音动画效果
 *
 * 设计语言：
 * - 水波纹：多层圆环从按钮边缘向外扩散，如水滴落水面
 * - 边界安全：波纹最大扩散不超过按钮周边 4dp 间距，不遮挡相邻按钮
 * - 渐变消失：波纹接近边界时 alpha 渐变到 0（非突兀消失）
 * - 形状一致：按钮空闲/录音态均为圆形
 *
 * 动画参数：
 * - 波纹最大缩放：1.15 倍（40dp 按钮 → 46dp，溢出 3dp，在 4dp 间距内）
 * - 波纹周期：1200ms/次，两层错开 600ms 形成连续水波
 * - 呼吸缩放：1.0↔1.04（柔和，不超出按钮边界）
 * - 音量反馈：实时音量驱动按钮微缩放
 *
 * 动画停止保障：
 * - 所有 Animator 统一收入 AnimatorSet，stop() 一并 cancel
 * - halo View 停止后隐藏，不占用布局空间
 * - 按钮属性复位到初始值，防止泄漏
 */
class MicPulseAnimator(
    /** 麦克风按钮本体 */
    private val micButton: View,
    /** 第一层波纹光环 View */
    private val haloView: View,
    /** 第二层波纹光环 View（错开时序形成连续水波） */
    private val haloView2: View? = null
) {
    companion object {
        private const val TAG = "MicPulseAnimator"

        /** 呼吸动画周期（ms） */
        private const val BREATH_DURATION = 1200L

        /** 单层波纹扩散时长（ms） */
        private const val RIPPLE_DURATION = 1200L

        /** 两层波纹错开间隔（ms） */
        private const val RIPPLE_STAGGER = 600L

        /** 呼吸缩放上限（柔和，不超出按钮边界） */
        private const val BREATH_SCALE_MAX = 1.04f

        /**
         * 波纹最大缩放倍数（相对按钮大小）
         * 40dp 按钮 → 46dp，溢出 3dp/侧，在 4dp 间距内安全
         */
        private const val RIPPLE_SCALE_MAX = 1.15f

        /** 波纹起始透明度 */
        private const val RIPPLE_ALPHA_START = 0.5f

        /** 音量驱动缩放系数（volume * 此值 = 额外缩放） */
        private const val VOLUME_SCALE_FACTOR = 0.06f
    }

    /** 呼吸动画（按钮本体 scale + alpha） */
    private var breathAnimator: AnimatorSet? = null

    /** 第一层波纹扩散动画 */
    private var rippleAnimator1: ValueAnimator? = null

    /** 第二层波纹扩散动画 */
    private var rippleAnimator2: ValueAnimator? = null

    /** 音量驱动的额外缩放量 */
    private var volumeScale = 0f

    /** 呼吸动画当前的基础缩放值 */
    private var breathScale = 1f

    /**
     * 启动录音动画
     *
     * 1. 显示波纹 View
     * 2. 启动呼吸缩放（按钮本体）
     * 3. 启动双层水波纹（错开时序，连续扩散）
     */
    fun start() {
        // 防重复启动
        if (breathAnimator?.isRunning == true) return

        // 初始化波纹 View
        initHalo(haloView)
        initHalo(haloView2)

        // === 1. 呼吸动画：按钮本体柔和缩放 ===
        val animScaleX = ObjectAnimator.ofFloat(micButton, View.SCALE_X, 1f, BREATH_SCALE_MAX).apply {
            duration = BREATH_DURATION
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                breathScale = it.animatedValue as Float
                applyCombinedScale()
            }
        }
        val animScaleY = ObjectAnimator.ofFloat(micButton, View.SCALE_Y, 1f, BREATH_SCALE_MAX).apply {
            duration = BREATH_DURATION
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val animAlpha = ObjectAnimator.ofFloat(micButton, View.ALPHA, 0.9f, 1f).apply {
            duration = BREATH_DURATION
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        breathAnimator = AnimatorSet().apply {
            playTogether(animScaleX, animScaleY, animAlpha)
            start()
        }

        // === 2. 启动第一层水波纹 ===
        startRipple(haloView, delay = 0L) { rippleAnimator1 = it }

        // === 3. 启动第二层水波纹（错开 600ms，形成连续水波） ===
        if (haloView2 != null) {
            micButton.postDelayed({
                if (breathAnimator?.isRunning == true) {
                    startRipple(haloView2, delay = 0L) { rippleAnimator2 = it }
                }
            }, RIPPLE_STAGGER)
        }
    }

    /**
     * 停止录音动画，复位所有属性
     *
     * 取消全部 Animator + 隐藏 halo + 复位按钮 scale/alpha
     * 确保无动画泄漏
     */
    fun stop() {
        breathAnimator?.cancel()
        breathAnimator = null

        rippleAnimator1?.cancel()
        rippleAnimator1 = null

        rippleAnimator2?.cancel()
        rippleAnimator2 = null

        // 复位 halo
        resetHalo(haloView)
        resetHalo(haloView2)

        // 复位按钮
        micButton.scaleX = 1f
        micButton.scaleY = 1f
        micButton.alpha = 1f

        volumeScale = 0f
        breathScale = 1f
    }

    /**
     * 设置实时音量，驱动按钮缩放
     *
     * @param volume 归一化音量 [0, 1]
     */
    fun setVolume(volume: Float) {
        volumeScale = (volume.coerceIn(0f, 1f)) * VOLUME_SCALE_FACTOR
        applyCombinedScale()
    }

    // ============= 内部方法 =============

    /** 初始化 halo View 到起始状态 */
    private fun initHalo(halo: View?) {
        halo ?: return
        halo.visibility = View.VISIBLE
        halo.scaleX = 1f
        halo.scaleY = 1f
        halo.alpha = 0f
    }

    /** 复位 halo View */
    private fun resetHalo(halo: View?) {
        halo ?: return
        halo.visibility = View.GONE
        halo.scaleX = 1f
        halo.scaleY = 1f
        halo.alpha = 0f
    }

    /**
     * 合并呼吸缩放 + 音量缩放
     */
    private fun applyCombinedScale() {
        val combined = breathScale + volumeScale
        micButton.scaleX = combined
        micButton.scaleY = combined
    }

    /**
     * 启动单层水波纹扩散动画（循环）
     *
     * 水波效果：halo 从按钮大小开始 → 扩大到 1.15 倍 + alpha 从 0.5 渐变到 0
     * 渐变消失：使用 AccelerateInterpolator 让 alpha 前半段缓慢衰减、后半段加速消失
     *
     * @param halo 要动画的 halo View
     * @param delay 启动延迟（ms）
     * @param onCreated 回调，保存 Animator 引用以供 stop() 取消
     */
    private fun startRipple(halo: View, delay: Long, onCreated: (ValueAnimator) -> Unit) {
        halo.postDelayed({
            if (breathAnimator?.isRunning != true) return@postDelayed

            // 重置到起始状态
            halo.scaleX = 1f
            halo.scaleY = 1f
            halo.alpha = RIPPLE_ALPHA_START
            halo.visibility = View.VISIBLE

            val ripple = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = RIPPLE_DURATION
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    val progress = anim.animatedValue as Float
                    // 缩放：1.0 → 1.15（水波向外扩散）
                    halo.scaleX = 1f + (RIPPLE_SCALE_MAX - 1f) * progress
                    halo.scaleY = 1f + (RIPPLE_SCALE_MAX - 1f) * progress
                    // alpha 渐变消失：前 30% 缓慢衰减（保持可见），后 70% 加速消失（渐变淡出）
                    // 使用 AccelerateInterpolator 的逆效果：progress 小时 alpha 衰减慢，progress 大时加速消失
                    val alphaProgress = (progress * 1.3f).coerceAtMost(1f)
                    halo.alpha = RIPPLE_ALPHA_START * (1f - alphaProgress)
                }
                // 动画结束后延迟一会儿再次触发（形成连续水波）
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (breathAnimator?.isRunning == true) {
                            halo.postDelayed({
                                if (breathAnimator?.isRunning == true) {
                                    startRipple(halo, 0L, onCreated)
                                }
                            }, 100L)
                        }
                    }
                })
                start()
            }
            onCreated(ripple)
        }, delay)
    }
}
