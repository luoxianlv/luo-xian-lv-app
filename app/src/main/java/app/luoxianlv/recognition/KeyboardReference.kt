package app.luoxianlv.recognition

/** 游戏初始几何仅作候选，最终布局由图像证据修正。 */
internal object KeyboardReference {
    // 以音符间距为单位描述两行的相对位置；实测几何只初始化搜索区，最终平移和缩放由文字与圆框确定。
    const val MODE_SEMI_OFFSET = 1.96f
    val MODE_GAPS = floatArrayOf(0f, 1.20f, 2.15f, 3.08f)
    const val MODE_Y_OFFSET = -0.91f
}
