package app.luoxianlv.hot.business

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.*
import app.luoxianlv.business.ui.AboutContent
import app.luoxianlv.hot.contract.NativePage
import app.luoxianlv.ui.theme.GradientBackdrop
import app.luoxianlv.ui.theme.LuoXianLvTheme
import kotlinx.coroutines.*

/** 实际关于页的动态入口；宿主生命周期驱动组合时钟，不在后台持续动画。 */
@OptIn(ExperimentalComposeApi::class)
class AboutEntry : NativePage, LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val models = ViewModelStore()
    private var page: ComposeView? = null
    private var scope: CoroutineScope? = null
    private var recomposer: Recomposer? = null
    private var frameClock: PausableMonotonicFrameClock? = null
    private var scroll = ScrollState(0)
    private var versionName by mutableStateOf("")
    private var checking by mutableStateOf(false)
    private var dark by mutableStateOf(false)
    private var closed = false
    private var active by mutableStateOf(false)
    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = saved.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = models

    override fun create(
        context: Context,
        state: Bundle,
        hostState: Bundle,
        events: NativePage.Events,
        ready: NativePage.Ready,
    ): View {
        check(page == null && !closed)
        scroll = ScrollState(state.getInt("scrollY", 0).coerceAtLeast(0))
        updateHostState(hostState)
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        val dispatcher = AndroidUiDispatcher.Main
        val clock =
            PausableMonotonicFrameClock(checkNotNull(dispatcher[MonotonicFrameClock])).also {
                it.pause()
            }
        frameClock = clock
        val jobs = CoroutineScope(SupervisorJob() + dispatcher + clock)
        val composer = Recomposer(jobs.coroutineContext)
        scope = jobs
        recomposer = composer
        jobs.launch { composer.runRecomposeAndApplyChanges() }
        return ComposeView(context).also { view ->
            page = view
            view.setViewTreeLifecycleOwner(this)
            view.setViewTreeSavedStateRegistryOwner(this)
            view.setViewTreeViewModelStoreOwner(this)
            view.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            view.setParentCompositionContext(composer)
            view.setContent {
                LuoXianLvTheme(darkTheme = dark, applySystemBars = active) {
                    Box(Modifier.fillMaxSize()) {
                        GradientBackdrop(Modifier.fillMaxSize())
                        AboutContent(
                            versionName,
                            checking,
                            { events.emit("navigation.back", Bundle.EMPTY) },
                            { events.emit("app.checkUpdate", Bundle.EMPTY) },
                            scrollState = scroll,
                        )
                        EditionContent(Modifier.align(Alignment.BottomCenter).padding(20.dp))
                    }
                    LaunchedEffect(Unit) {
                        withFrameNanos {}
                        withFrameNanos {}
                        if (!closed) ready.ready()
                    }
                }
            }
        }
    }

    override fun updateHostState(state: Bundle) {
        check(!closed)
        versionName = state.getString("versionName", "")
        checking = state.getBoolean("checkingUpdate", false)
        dark = state.getBoolean("darkTheme", false)
    }

    override fun lifecycle(state: Int) {
        if (closed) return
        active = state == NativePage.RESUMED
        registry.currentState =
            when (state) {
                NativePage.RESUMED -> Lifecycle.State.RESUMED
                NativePage.STARTED -> Lifecycle.State.STARTED
                else -> Lifecycle.State.CREATED
            }
        if (state >= NativePage.STARTED) frameClock?.resume() else frameClock?.pause()
    }

    override fun save() = Bundle().apply { putInt("scrollY", scroll.value) }

    override fun canReplace() = !scroll.isScrollInProgress

    override fun close() {
        if (closed) return
        closed = true
        page?.disposeComposition()
        page = null
        recomposer?.cancel()
        recomposer = null
        scope?.cancel()
        scope = null
        frameClock = null
        registry.currentState = Lifecycle.State.DESTROYED
        models.clear()
    }
}
