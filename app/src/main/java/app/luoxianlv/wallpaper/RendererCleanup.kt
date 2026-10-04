package app.luoxianlv.wallpaper

/** 厂商实现某一步失败仍需销毁实例；崩溃的 renderer 只允许移除和 destroy。 */
internal fun cleanupWallpaperRenderer(
    rendererGone: Boolean,
    remove: () -> Unit,
    stop: () -> Unit,
    pause: () -> Unit,
    destroy: () -> Unit,
    onFailure: (String, Throwable) -> Unit,
) {
    fun attempt(step: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            onFailure(step, error)
        } catch (error: LinkageError) {
            onFailure(step, error)
        }
    }
    attempt("remove", remove)
    if (!rendererGone) {
        attempt("stop", stop)
        attempt("pause", pause)
    }
    attempt("destroy", destroy)
}
