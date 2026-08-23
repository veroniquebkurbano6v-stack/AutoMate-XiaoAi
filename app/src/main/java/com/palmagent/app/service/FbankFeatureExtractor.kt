package com.palmagent.app.service

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

/**
 * Fbank 特征提取器（SenseVoice 前端，对齐 FunASR 配置）
 *
 * 将 16kHz PCM 音频转为 SenseVoice 模型需要的 560 维特征帧：
 *   80 维 log-mel filterbank × 7 帧拼接（lfr_m=7, lfr_n=6，当前帧 + 前 6 帧）
 *
 * 全部参数来自 VoiceConfig（assets/config/voice_config.json），无硬编码默认值。
 *
 * 实现要点：
 * - 迭代式 Cooley-Tukey FFT（2 的幂）
 * - Hamming 窗
 * - Mel 滤波器组系数预计算（三角滤波器）
 * - log 取自然对数（ln），与 FunASR 一致
 */
class FbankFeatureExtractor(private val config: VoiceConfig) {

    private val sampleRate: Int = config.sampleRate
    private val nFft: Int = config.fbankNfft
    private val winLength: Int = config.fbankWinLength
    private val hopLength: Int = config.fbankHopLength
    private val nMels: Int = config.fbankNMels
    private val lfrM: Int = config.fbankLfrM
    private val frameFeatDim: Int = nMels * lfrM   // 80 * 7 = 560

    private val hammingWindow: FloatArray = FloatArray(winLength) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (winLength - 1))).toFloat()
    }

    /** Mel 滤波器组：nMels 个三角滤波器，每个长度 nFft/2+1 */
    private val melFilters: Array<FloatArray> = buildMelFilters()

    /** 预计算 FFT 旋转因子（避免每帧重复计算） */
    private val fftCos: FloatArray = FloatArray(nFft / 2)
    private val fftSin: FloatArray = FloatArray(nFft / 2)

    init {
        for (k in 0 until nFft / 2) {
            val angle = 2.0 * PI * k / nFft
            fftCos[k] = cos(angle).toFloat()
            fftSin[k] = sin(angle).toFloat()
        }
    }

    /**
     * 提取 Fbank 特征
     *
     * @param pcm Float PCM 数据（16kHz, [-1,1]）
     * @return [T][560] 特征帧（T = 帧数），不足一帧返回空数组
     */
    fun extract(pcm: FloatArray): Array<FloatArray> {
        if (pcm.size < winLength) return emptyArray()
        val nFrames = (pcm.size - winLength) / hopLength + 1
        if (nFrames <= 0) return emptyArray()

        // 先算每帧 80 维 log-mel
        val frameMels = Array(nFrames) { FloatArray(nMels) }
        for (t in 0 until nFrames) {
            val start = t * hopLength
            frameMels[t] = melSpectrum(pcm, start)
        }

        // 7 帧拼接（当前帧 + 前 6 帧；不足 6 帧时左侧补 0）
        val feats = Array(nFrames) { FloatArray(frameFeatDim) }
        for (t in 0 until nFrames) {
            for (m in 0 until lfrM) {
                val src = t - (lfrM - 1 - m)   // m=0 是最左侧（t-6），m=6 是当前帧（t）
                val srcRow = if (src >= 0 && src < nFrames) frameMels[src] else null
                val dstOff = m * nMels
                if (srcRow != null) {
                    System.arraycopy(srcRow, 0, feats[t], dstOff, nMels)
                } else {
                    // 左侧越界补 0
                    java.util.Arrays.fill(feats[t], dstOff, dstOff + nMels, 0f)
                }
            }
        }
        return feats
    }

    /** 计算单帧 80 维 log-mel 特征 */
    private fun melSpectrum(pcm: FloatArray, start: Int): FloatArray {
        // 1. 加窗
        val windowed = FloatArray(nFft)
        for (i in 0 until winLength) {
            windowed[i] = pcm[start + i] * hammingWindow[i]
        }
        // 尾部（nFft - winLength）补 0

        // 2. FFT（实数输入 → 复数频谱）
        val re = windowed.copyOf()
        val im = FloatArray(nFft)
        fft(re, im)

        // 3. 功率谱（单边，0..nFft/2）
        val power = FloatArray(nFft / 2 + 1)
        for (i in 0 until nFft / 2 + 1) {
            power[i] = re[i] * re[i] + im[i] * im[i]
        }

        // 4. Mel 滤波器组加权 + log
        val mel = FloatArray(nMels)
        for (m in 0 until nMels) {
            val filter = melFilters[m]
            var sum = 0f
            for (i in 0 until nFft / 2 + 1) {
                sum += power[i] * filter[i]
            }
            // 与 FunASR 一致：ln(x)，最小值保护
            mel[m] = ln(sum.coerceAtLeast(1e-10f)).toFloat()
        }
        return mel
    }

    /** 迭代式 Cooley-Tukey FFT（就地，位反转 + 蝶形） */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        // 蝶形
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = nFft / len
            for (i in 0 until n step len) {
                for (k in 0 until half) {
                    val idx = k * step
                    val wRe = fftCos[idx]
                    val wIm = -fftSin[idx]
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + half]
                    val bIm = im[i + k + half]
                    val tRe = wRe * bRe - wIm * bIm
                    val tIm = wRe * bIm + wIm * bRe
                    re[i + k] = aRe + tRe
                    im[i + k] = aIm + tIm
                    re[i + k + half] = aRe - tRe
                    im[i + k + half] = aIm - tIm
                }
            }
            len = len shl 1
        }
    }

    /** 构建 Mel 三角滤波器组（0..fs/2 频率范围，nMels 个滤波器） */
    private fun buildMelFilters(): Array<FloatArray> {
        val fftBins = nFft / 2 + 1
        val filters = Array(nMels) { FloatArray(fftBins) }

        fun hzToMel(hz: Double): Double = 2595.0 * kotlin.math.log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val lowMel = hzToMel(0.0)
        val highMel = hzToMel(sampleRate / 2.0)
        val melPoints = DoubleArray(nMels + 2)
        for (i in melPoints.indices) {
            melPoints[i] = lowMel + i * (highMel - lowMel) / (nMels + 1)
        }
        val hzPoints = DoubleArray(nMels + 2) { melToHz(melPoints[it]) }
        val binPoints = DoubleArray(nMels + 2) { hzPoints[it] * (nFft + 1) / sampleRate }

        for (m in 0 until nMels) {
            val left = binPoints[m]
            val center = binPoints[m + 1]
            val right = binPoints[m + 2]
            for (i in 0 until fftBins) {
                val x = i.toDouble()
                filters[m][i] = when {
                    x < left || x > right -> 0f
                    x < center -> ((x - left) / (center - left)).toFloat()
                    else -> ((right - x) / (right - center)).toFloat()
                }
            }
        }
        return filters
    }

    /** 特征维度（560） */
    fun featureDim(): Int = frameFeatDim
}
