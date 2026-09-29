package app.luoxianlv.business.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.*
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.*
import app.luoxianlv.hot.contract.HostActions
import app.luoxianlv.hot.contract.NativePage
import kotlinx.coroutines.*

val LocalNativePage = staticCompositionLocalOf<ComposePage?> { null }

/** 页面拥有 Jetpack 生命周期和状态，不要求宿主继承 ComponentActivity。 */
@OptIn(ExperimentalComposeApi::class)
abstract class ComposePage :
    NativePage,
    LifecycleOwner,
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory,
    OnBackPressedDispatcherOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    private val models = ViewModelStore()
    private val backDispatcher = OnBackPressedDispatcher {}
    private var view: ComposeView? = null
    private var scope: CoroutineScope? = null
    private var composer: Recomposer? = null
    private var clock: PausableMonotonicFrameClock? = null
    private var currentLifecycle = NativePage.CREATED
    private var closed = false
    protected var isActive by mutableStateOf(false)
        private set

    protected lateinit var pageContext: Context
        private set

    protected lateinit var host: HostActions
        private set

    private val resultHandlers = mutableMapOf<String, (Int, Intent?) -> Unit>()
    protected val activity: Activity
        get() = checkNotNull(pageContext.findActivity())

    override val lifecycle: Lifecycle
        get() = registry

    override val savedStateRegistry: SavedStateRegistry
        get() = saved.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = models

    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = backDispatcher

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = SavedStateViewModelFactory(pageContext.applicationContext as Application, this)

    override val defaultViewModelCreationExtras: CreationExtras
        get() =
            MutableCreationExtras().apply {
                set(
                    ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY,
                    pageContext.applicationContext as Application,
                )
                set(SAVED_STATE_REGISTRY_OWNER_KEY, this@ComposePage)
                set(VIEW_MODEL_STORE_OWNER_KEY, this@ComposePage)
            }

    final override fun attachHost(host: HostActions) {
        this.host = host
    }

    final override fun create(
        context: Context,
        state: Bundle,
        hostState: Bundle,
        events: NativePage.Events,
        ready: NativePage.Ready,
    ): View {
        check(view == null && !closed)
        pageContext = context
        saved.performAttach()
        enableSavedStateHandles()
        saved.performRestore(state.getString("compose-state")?.let(PageSavedState::decode))
        registry.currentState = Lifecycle.State.CREATED
        prepare(state)
        updateHostState(hostState)
        val dispatcher = AndroidUiDispatcher.Main
        val frames =
            PausableMonotonicFrameClock(checkNotNull(dispatcher[MonotonicFrameClock])).also {
                it.pause()
            }
        clock = frames
        val jobs = CoroutineScope(SupervisorJob() + dispatcher + frames)
        val recomposer = Recomposer(jobs.coroutineContext)
        scope = jobs
        composer = recomposer
        jobs.launch { recomposer.runRecomposeAndApplyChanges() }
        return ComposeView(context).also { root ->
            view = root
            root.id = 0x0010f00d
            root.isSaveFromParentEnabled = false
            root.setViewTreeLifecycleOwner(this)
            root.setViewTreeSavedStateRegistryOwner(this)
            root.setViewTreeViewModelStoreOwner(this)
            root.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            root.setParentCompositionContext(recomposer)
            root.setContent {
                CompositionLocalProvider(
                    LocalNativePage provides this,
                    LocalOnBackPressedDispatcherOwner provides this,
                ) {
                    Content()
                    LaunchedEffect(Unit) {
                        withFrameNanos {}
                        withFrameNanos {}
                        if (!closed) ready.ready()
                    }
                }
            }
        }
    }

    protected open fun prepare(state: Bundle) {}

    @Composable protected abstract fun Content()

    protected open fun lifecycleChanged(state: Int) {}

    override fun updateHostState(state: Bundle) {}

    protected open fun savePageState() = Bundle()

    final override fun save(): Bundle =
        savePageState().apply {
            val registryState = Bundle()
            saved.performSave(registryState)
            putString("compose-state", PageSavedState.encode(registryState))
        }

    override fun canReplace() = !::host.isInitialized || !host.hasPendingResults()

    final override fun lifecycle(state: Int) {
        if (closed) return
        isActive = state == NativePage.RESUMED
        registry.currentState =
            when (state) {
                NativePage.RESUMED -> Lifecycle.State.RESUMED
                NativePage.STARTED -> Lifecycle.State.STARTED
                else -> Lifecycle.State.CREATED
            }
        if (state >= NativePage.STARTED) clock?.resume() else clock?.pause()
        if (currentLifecycle != state) {
            currentLifecycle = state
            lifecycleChanged(state)
        }
    }

    final override fun back(): Boolean {
        if (!backDispatcher.hasEnabledCallbacks()) return false
        backDispatcher.onBackPressed()
        return true
    }

    override fun result(key: String, resultCode: Int, data: Intent?): Boolean {
        val handler = resultHandlers[key] ?: return false
        handler(resultCode, data)
        return true
    }

    internal fun registerResult(key: String, callback: (Int, Intent?) -> Unit) {
        check(resultHandlers.putIfAbsent(key, callback) == null) { "系统结果键重复：$key" }
        host.resultReady(key)
    }

    internal fun unregisterResult(key: String) {
        resultHandlers.remove(key)
    }

    internal fun launchResult(key: String, intent: Intent, options: Bundle?) {
        if (intent.action == "androidx.activity.result.contract.action.REQUEST_PERMISSIONS") {
            host.permissions(
                key,
                intent.getStringArrayExtra("androidx.activity.result.contract.extra.PERMISSIONS")
                    ?: emptyArray(),
            )
        } else host.launch(key, intent, options)
    }

    override fun close() {
        if (closed) return
        closed = true
        view?.disposeComposition()
        view = null
        composer?.cancel()
        composer = null
        scope?.cancel()
        scope = null
        clock = null
        resultHandlers.clear()
        registry.currentState = Lifecycle.State.DESTROYED
        models.clear()
    }
}

/** 模块资源上下文通常包裹 Activity，不直接强转为 Activity。 */
fun Context.findActivity(): Activity? {
    var current: Context = this
    repeat(16) {
        if (current is Activity) return current as Activity
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}
