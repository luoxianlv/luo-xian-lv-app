package app.luoxianlv.wallpaper.render

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import app.luoxianlv.ui.practice.PracticePlaybackGate
import app.luoxianlv.wallpaper.data.WallpaperProjectStore

/** One application-context renderer, attached behind the home content until handed to the stage. */
object PreparedWallpaper {
    private var cached: PracticeBackdrop? = null
    private var selection: String? = null

    private fun key(activity: Activity): String =
        "${WallpaperProjectStore.root(activity)?.absolutePath}:${WallpaperProjectStore.minute(activity)}:${WallpaperProjectStore.hasBundled(activity)}"

    fun prepare(activity: Activity) {
        if (PracticePlaybackGate.active || activity.isFinishing || activity.isDestroyed) return
        val next = key(activity)
        if (selection != next) clear()
        val decor = activity.window.decorView as ViewGroup
        val view =
            cached
                ?: PracticeBackdrop(activity.applicationContext).also {
                    selection = next
                    cached = it
                    it.onPrepared = { it.suspendRendering() }
                }
        if (view.parent == null) {
            val metrics = activity.resources.displayMetrics
            decor.addView(
                view,
                0,
                FrameLayout.LayoutParams(
                    maxOf(metrics.widthPixels, metrics.heightPixels),
                    minOf(metrics.widthPixels, metrics.heightPixels),
                ),
            )
        }
        if (!view.prepared) view.resumeRendering()
    }

    fun pause() {
        cached?.suspendRendering()
    }

    fun take(activity: Activity): PracticeBackdrop {
        val view = cached?.takeIf { selection == key(activity) && it.renderState != "error" }
        if (view == null) {
            clear()
            return PracticeBackdrop(activity)
        }
        (view.parent as? ViewGroup)?.removeView(view)
        cached = null
        selection = null
        view.onPrepared = null
        view.resumeRendering()
        return view
    }

    fun clear() {
        cached?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.close()
        }
        cached = null
        selection = null
    }
}
