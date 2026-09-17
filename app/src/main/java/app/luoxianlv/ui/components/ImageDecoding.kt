package app.luoxianlv.ui.components

import android.graphics.BitmapFactory

/** 解码上限：按最长边降采样，避免几千万像素的原图直接进内存。 */
internal const val MAX_DECODE_EDGE = 1440

/**
 * 计算 [BitmapFactory.Options.inSampleSize]：
 * 保证解码后最长边不超过 [maxEdge]，返回满足条件的最小 2 的幂。
 */
internal fun decodeSampleSize(
    width: Int,
    height: Int,
    maxEdge: Int = MAX_DECODE_EDGE,
): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    // 只要当前降采样结果仍超出上限就继续翻倍
    while (width / sample > maxEdge || height / sample > maxEdge) {
        sample *= 2
    }
    return sample
}
