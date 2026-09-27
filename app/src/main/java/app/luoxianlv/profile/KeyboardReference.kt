package app.luoxianlv.profile

/** Initial game geometry; image evidence refines these proposals. */
internal object KeyboardReference {
    // Mode row geometry relative to the note row, in units of note spacing,
    // measured on real captures. This initializes the search region; labels
    // and circular borders determine the final translation and scale.
    const val MODE_SEMI_OFFSET = 1.96f
    val MODE_GAPS = floatArrayOf(0f, 1.20f, 2.15f, 3.08f)
    const val MODE_Y_OFFSET = -0.91f
}
