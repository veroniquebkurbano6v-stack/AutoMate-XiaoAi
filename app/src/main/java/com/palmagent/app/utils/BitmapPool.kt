package com.palmagent.app.utils

import android.graphics.Bitmap
import android.util.Log

/**
 * Bitmap 复用池，减少频繁分配/回收带来的 GC 压力
 *
 * 适用场景：VLM 缩放图等高频创建的临时 Bitmap。
 * 通过复用已分配的内存区域，避免反复 allocate/free native 内存。
 */
object BitmapPool {

    private const val TAG = "BitmapPool"
    private const val MAX_POOL_SIZE = 3

    private val pool = mutableListOf<Bitmap>()
    private val lock = Any()

    /**
     * 获取一个指定尺寸和配置的 Bitmap。
     * 优先从池中复用，池中无匹配项时创建新 Bitmap。
     */
    fun acquire(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap {
        synchronized(lock) {
            val reusableIndex = pool.indexOfFirst {
                !it.isRecycled && it.width == width && it.height == height && it.config == config
            }
            if (reusableIndex >= 0) {
                val reusable = pool.removeAt(reusableIndex)
                Log.d(TAG, "复用 Bitmap: ${width}x${height} $config (池剩余${pool.size})")
                return reusable
            }
        }
        Log.d(TAG, "新建 Bitmap: ${width}x${height} $config")
        return Bitmap.createBitmap(width, height, config)
    }

    /**
     * 归还 Bitmap 到池中以便复用。
     * 池满时直接 recycle。
     */
    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(lock) {
            if (pool.size < MAX_POOL_SIZE) {
                pool.add(bitmap)
                Log.d(TAG, "归还 Bitmap: ${bitmap.width}x${bitmap.height} (池${pool.size}/$MAX_POOL_SIZE)")
            } else {
                bitmap.recycle()
                Log.d(TAG, "池满，回收 Bitmap (池${pool.size}/$MAX_POOL_SIZE)")
            }
        }
    }

    /**
     * 清空池并回收所有 Bitmap
     */
    fun clear() {
        synchronized(lock) {
            pool.forEach { if (!it.isRecycled) it.recycle() }
            pool.clear()
            Log.d(TAG, "池已清空")
        }
    }
}
