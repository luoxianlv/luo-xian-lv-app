package app.luoxianlv.wallpaper

import android.app.Activity
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import app.luoxianlv.business.ui.resourceApplicationContext
import app.luoxianlv.practice.PracticePlaybackGate

/** 缓存一个应用上下文渲染器，预加载时挂在首页背后，进入演练场时直接移交。 */
object PreparedWallpaper {
    private var cached: PracticeBackdrop? = null
    private var selection: String? = null
    private var owner: Any? = null
    private var rendererLoader: ClassLoader? = null

    private fun key(context: Context): String =
        "${WallpaperProjectStore.selectedId(context)}:${WallpaperProjectStore.minute(context)}:${WallpaperProjectStore.hasBundled(context)}"

    fun claim(value: Any) {
        owner = value
    }

    fun prepare(activity: Activity, resources: Context = activity, owner: Any? = null) {
        if (owner != null && this.owner !== owner) return
        if (PracticePlaybackGate.active || activity.isFinishing || activity.isDestroyed) return
        val next = key(resources)
        if (selection != next || rendererLoader !== resources.classLoader) clear()
        this.owner = owner
        val decor = activity.window.decorView as ViewGroup
        val view =
            cached
                ?: PracticeBackdrop(resources.resourceApplicationContext()).also {
                    selection = next
                    cached = it
                    rendererLoader = resources.classLoader
                    it.onPrepared = { it.suspendRendering() }
                }
        if (view.parent !== decor) {
            (view.parent as? ViewGroup)?.removeView(view)
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

    fun pause(owner: Any) {
        if (this.owner === owner) cached?.suspendRendering()
    }

    fun take(activity: Activity, resources: Context = activity): PracticeBackdrop {
        val view = cached?.takeIf {
            selection == key(resources) &&
                rendererLoader === resources.classLoader &&
                it.renderState != "error"
        }
        if (view == null) {
            clear()
            return PracticeBackdrop(resources)
        }
        (view.parent as? ViewGroup)?.removeView(view)
        cached = null
        selection = null
        owner = null
        rendererLoader = null
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
        owner = null
        rendererLoader = null
    }

    fun clear(owner: Any) {
        if (this.owner === owner) clear()
    }
}
