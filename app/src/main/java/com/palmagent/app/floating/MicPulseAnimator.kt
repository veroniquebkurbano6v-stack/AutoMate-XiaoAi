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
 * - 所有 postDelayed Runnable 存为字段并在 stop()/cleanup() 中 removeCallbacks
 * - 使用 generation 代数计数器守卫，旧 Runnable 不会误触发新动画
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

        /** 波纹重启间隔（连续水波之间的短暂停顿） */
        private const val RIPPLE_RESTART_DELAY = 100L
    }

    // ============== 动画器字段 ==============

    /**
     * 呼吸动画（按钮本体 scale + alpha）
     * ⚠️ 用 ValueAnimator 动画一个中性 breathValue，在唯一 update listener 中
     *    计算 breathScale + volumeScale 后同时写入 scaleX/scaleY。
     *    之前使用 ObjectAnimator.ofFloat(View.SCALE_X/SCALE_Y) 会在 listener 之后
     *    再次写入属性，把 listener 里的组合值覆盖掉，导致音量驱动缩放完全失效。
     */
    private var breathAnimatorSet: AnimatorSet? = null

    /** 第一层波纹扩散动画 */
    private var rippleAnimator1: ValueAnimator? = null

    /** 第二层波纹扩散动画 */
    private var rippleAnimator2: ValueAnimator? = null

    // ============== 状态字段 ==============

    /** 音量驱动的额外缩放量 */
    private var volumeScale = 0f

    /** 呼吸动画当前的基础缩放值（由 breathValue Animator 更新） */
    private var breathScale = 1f

    /**
     * 代数计数器：每次 start() +1，所有 postDelayed Runnable 捕获启动时的 generation，
     * 运行前比较相等才继续执行。替代 breathAnimator.isRunning 守卫，
     * 避免 stop→start 后旧任务看到新 breathAnimator 已运行而误触发。
     */
    private var generation = 0L

    // ============== postDelayed Runnable 引用（用于 stop()/cleanup() 取消） ==============

    /** 第二层波纹的延迟启动任务（start() 中排期） */
    private var staggerRunnable: Runnable? = null

    /** 第一层波纹续排任务（startRipple → onAnimationEnd 中排期） */
    private var rippleRestartRunnable1: Runnable? = null

    /** 第二层波纹续排任务 */
    private var rippleRestartRunnable2: Runnable? = null

    /** 第一层波纹启动任务（startRipple delay 参数产生） */
    private var rippleStartRunnable1: Runnable? = null

    /** 第二层波纹启动任务 */
    private var rippleStartRunnable2: Runnable? = null

    // ============== 对外 API ==============

    /**
     * 启动录音动画
     *
     * 1. 显示波纹 View
     * 2. 启动呼吸缩放（按钮本体）
     * 3. 启动双层水波纹（错开时序，连续扩散）
     */
    fun start() {
        // ⚠️ 严格评审：重复启动必须早返回，不能先 stop() 再 start。
        //    录音状态机（WAITING_FOR_SPEECH → RECORDING）会连续两次调用同一 startMicAnimation()，
        //    若先 stop() 再启动，会中断正在运行的波纹+呼吸动画，产生可见跳变/闪烁。
        if (breathAnimatorSet?.isRunning == true) return

        generation++
        val myGen = generation

        // 初始化波纹 View
        initHalo(haloView)
        initHalo(haloView2)

        // === 1. 呼吸动画：按钮本体柔和缩放 + 透明度 ===
        // 使用 ValueAnimator 动画中性 breathValue（0~1 往复），
        // 在唯一 listener 中计算 breathScale 并同时写入 scaleX/scaleY，
        // 确保 applyCombinedScale() 写入的值不会在同一帧被 ObjectAnimator 属性写入覆盖。
        val breathValueAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BREATH_DURATION
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                // 0→1：缩放 1.0 → BREATH_SCALE_MAX
                breathScale = 1f + v * (BREATH_SCALE_MAX - 1f)
                applyCombinedScale()
            }
        }
        val animAlpha = ObjectAnimator.ofFloat(micButton, View.ALPHA, 0.9f, 1f).apply {
            duration = BREATH_DURATION
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        breathAnimatorSet = AnimatorSet().apply {
            playTogether(breathValueAnim, animAlpha)
            start()
        }

        // === 2. 启动第一层水波纹（立即） ===
        startRippleInternal(
            halo = haloView,
            delay = 0L,
            generation = myGen,
            animatorSetter = { rippleAnimator1 = it },
            restartSetter = { rippleRestartRunnable1 = it },
            startSetter = { rippleStartRunnable1 = it }
        )

        // === 3. 启动第二层水波纹（错开 RIPPLE_STAGGER，形成连续水波） ===
        if (haloView2 != null) {
            val runnable = Runnable {
                // 通过代数守卫判断：若期间已 stop→start 过新周期，则跳过
                if (generation == myGen && breathAnimatorSet?.isRunning == true) {
                    startRippleInternal(
                        halo = haloView2,
                        delay = 0L,
                        generation = myGen,
                        animatorSetter = { rippleAnimator2 = it },
                        restartSetter = { rippleRestartRunnable2 = it },
                        startSetter = { rippleStartRunnable2 = it }
                    )
                }
            }
            staggerRunnable = runnable
            micButton.postDelayed(runnable, RIPPLE_STAGGER)
        }
    }

    /**
     * 停止录音动画，复位所有属性，取消全部排期任务。
     * 确保无动画泄漏、无 postDelayed 残留。
     */
    fun stop() {
        // generation 递增：让所有仍在队列中的旧 Runnable 立即失效
        generation++

        // === 取消所有 Animator ===
        breathAnimatorSet?.cancel()
        breathAnimatorSet = null

        rippleAnimator1?.cancel()
        rippleAnimator1 = null

        rippleAnimator2?.cancel()
        rippleAnimator2 = null

        // === 取消所有 postDelayed 任务（防止停止后仍运行） ===
        staggerRunnable?.let { micButton.removeCallbacks(it) }
        staggerRunnable = null

        rippleStartRunnable1?.let { haloView.removeCallbacks(it) }
        rippleStartRunnable1 = null
        rippleRestartRunnable1?.let { haloView.removeCallbacks(it) }
        rippleRestartRunnable1 = null

        if (haloView2 != null) {
            rippleStartRunnable2?.let { haloView2.removeCallbacks(it) }
            rippleStartRunnable2 = null
            rippleRestartRunnable2?.let { haloView2.removeCallbacks(it) }
            rippleRestartRunnable2 = null
        }

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
     * 资源清理钩子：供 FloatingProgressManager 在 View 被 detach/重建前调用。
     * 先 stop() 取消所有动画 + postDelayed，避免在 detached View 上继续运行
     * 导致 CPU/内存泄漏。stop() 已包含全部清理，这里显式别名便于调用方意图表达。
     */
    fun cleanup() {
        stop()
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
     * 合并呼吸缩放 + 音量缩放，同时写入两轴。
     * ⚠️ 唯一写入 scaleX/scaleY 的地方，保证写入方统一、不会出现非均匀缩放/抖动。
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
     * 渐变消失：前 30% 缓慢衰减、后 70% 加速消失（渐变淡出）
     *
     * @param halo 要动画的 halo View
     * @param delay 启动延迟（ms）
     * @param generation 调用方 start() 时的代数，用于守卫续排任务
     * @param animatorSetter 保存 Animator 引用（供 stop() 取消）
     * @param restartSetter 保存续排 Runnable 引用（供 stop() 取消）
     * @param startSetter 保存启动 Runnable 引用（供 stop() 取消）
     */
    private fun startRippleInternal(
        halo: View,
        delay: Long,
        generation: Long,
        animatorSetter: (ValueAnimator) -> Unit,
        restartSetter: (Runnable) -> Unit,
        startSetter: (Runnable) -> Unit
    ) {
        val startRunnable = Runnable {
            // 代数守卫：stop→start 后 generation 已变化，旧任务直接退出
            if (generation != this@MicPulseAnimator.generation) return@Runnable
            if (breathAnimatorSet?.isRunning != true) return@Runnable

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
                    val scale = 1f + (RIPPLE_SCALE_MAX - 1f) * progress
                    halo.scaleX = scale
                    halo.scaleY = scale
                    // alpha 渐变消失：前 30% 缓慢衰减（保持可见），后 70% 加速消失
                    val alphaProgress = (progress * 1.3f).coerceAtMost(1f)
                    halo.alpha = RIPPLE_ALPHA_START * (1f - alphaProgress)
                }
                // 动画结束后延迟 RIPPLE_RESTART_DELAY 再次触发（形成连续水波）
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (generation != this@MicPulseAnimator.generation) return
                        if (breathAnimatorSet?.isRunning == true) {
                            val restart = Runnable {
                                if (generation == this@MicPulseAnimator.generation
                                    && breathAnimatorSet?.isRunning == true
                                ) {
                                    startRippleInternal(
                                        halo, 0L, generation,
                                        animatorSetter, restartSetter, startSetter
                                    )
                                }
                            }
                            restartSetter(restart)
                            halo.postDelayed(restart, RIPPLE_RESTART_DELAY)
                        }
                    }
                })
                start()
            }
            animatorSetter(ripple)
        }
        startSetter(startRunnable)
        halo.postDelayed(startRunnable, delay)
    }
}
