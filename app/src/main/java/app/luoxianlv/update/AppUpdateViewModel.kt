package app.luoxianlv.update

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.luoxianlv.BuildConfig
import app.luoxianlv.business.BusinessJobs
import app.luoxianlv.data.Kv
import app.luoxianlv.hot.contract.SharedFiles
import app.luoxianlv.hot.contract.SharedUpdate
import app.luoxianlv.storage.AppStorage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class AppUpdateState(
    val release: AppRelease? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val source: String = "",
    val selectedSource: String = BuildConfig.UPDATE_SOURCE,
    val ready: Boolean = false,
    val needsPermission: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val phase: String = "",
    val downloadedBytes: Long = 0,
    val downloadSize: Long = 0,
    val installing: Boolean = false,
    val installedVersionName: String = BuildConfig.VERSION_NAME,
)

/** Activity 级更新状态，由前台检查、关于页和唯一弹窗宿主共享。 */
class AppUpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = Kv.of(app, "app_updates")
    private val _state = MutableStateFlow(AppUpdateState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private val baseUrl = BuildConfig.UPDATE_BASE_URL.trimEnd('/')
    private val cacheDir = AppStorage.updates(app)
    private var readyApk: File? = null
    @Volatile private var downloadGeneration = 0L
    @Volatile private var pauseRequested = false
    private var restoring = true
    private var pendingCheck: Pair<Boolean, Boolean>? = null
    private val installedVersionCode: Int
        get() =
            app.packageManager.getPackageInfo(app.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode
            }

    init {
        cleanupCache()
        _state.update {
            it.copy(
                installedVersionName =
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                        ?: BuildConfig.VERSION_NAME
            )
        }
        restorePending()
    }

    /** 在检查间隔之外发现已认证断点，用户点击继续后才下载。 */
    private fun restorePending() {
        viewModelScope.launch {
            try {
                val recovered = BusinessJobs.io {
                    val pending = SharedUpdate.current()?.pending() ?: return@io null
                    val request = pending.request
                    require(request.versionCode in 1..Int.MAX_VALUE.toLong()) { "恢复任务版本无效" }
                    val sources =
                        request.fullUrls.mapIndexed { index, url ->
                            UpdateSource(
                                if (index == 0) "oss" else "resume-$index",
                                validatedUpdateUrl(url, baseUrl, BuildConfig.INTERNAL_BUILD),
                                request.sha256,
                                request.size,
                            )
                        }
                    require(sources.isNotEmpty()) { "恢复任务没有完整包来源" }
                    AppRelease(
                        request.versionCode.toInt(),
                        pending.versionName,
                        request.sha256,
                        request.size,
                        emptyList(),
                        false,
                        sources,
                        request.signedEnvelope,
                    )
                }
                coroutineContext.ensureActive()
                if (recovered != null)
                    _state.update {
                        it.copy(
                            release = recovered,
                            selectedSource = recovered.sources.first().id,
                            phase = "paused",
                            source = "上次更新已保留，可继续下载",
                        )
                    }
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                _state.update { it.copy(message = "上次更新未通过验证，请重新检查更新") }
            } finally {
                restoring = false
                val queued = pendingCheck
                pendingCheck = null
                if (queued != null && coroutineContext[Job]?.isActive == true)
                    check(queued.first, queued.second)
            }
        }
    }

    private fun apk(
        release: AppRelease,
        source: UpdateSource,
    ) = File(cacheDir, "${release.versionCode}-${source.sha256.ifBlank { release.sha256 }}.apk")

    fun check(manual: Boolean = false, force: Boolean = false) {
        if (restoring) {
            pendingCheck =
                (manual || pendingCheck?.first == true) to (force || pendingCheck?.second == true)
            return
        }
        if (_state.value.checking || _state.value.downloading) return
        if (!manual && _state.value.release != null) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_check", 0)
        if (!manual && !force && now >= last && now - last < 6 * 60 * 60 * 1000L) return
        _state.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val release = BusinessJobs.io {
                    val connection =
                        open(
                            "$baseUrl/api/update/stable?versionCode=$installedVersionCode" +
                                if (SharedUpdate.current()?.supportsIncremental() == true)
                                    "&deltaCapability=hdiff-w26-zstd-v1&installationId=${updateInstallationId()}"
                                else ""
                        )
                    try {
                        check(connection.responseCode == 200) {
                            "更新服务暂时不可用 (${connection.responseCode})"
                        }
                        val text =
                            connection.inputStream.use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (output.size() <= 256 * 1024) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        require(text.size <= 256 * 1024) { "更新信息过大" }
                        parseAppRelease(
                            JSONObject(text.toString(Charsets.UTF_8)),
                            installedVersionCode,
                            baseUrl,
                            BuildConfig.UPDATE_SOURCE,
                            BuildConfig.INTERNAL_BUILD,
                        )
                    } finally {
                        connection.disconnect()
                    }
                }
                prefs.edit().putLong("last_check", now).apply()
                readyApk = null
                _state.update {
                    it.copy(
                        release = release,
                        checking = false,
                        ready = false,
                        selectedSource =
                            release?.sources?.firstOrNull()?.id ?: BuildConfig.UPDATE_SOURCE,
                        message = if (manual && release == null) "已是最新版本" else null,
                    )
                }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                _state.update {
                    it.copy(
                        checking = false,
                        message = if (manual) e.message ?: "检查更新失败，请重试" else null,
                    )
                }
            }
        }
    }

    fun selectSource(id: String) {
        if (!_state.value.downloading) _state.update { it.copy(selectedSource = id, error = null) }
    }

    fun download() {
        val release = _state.value.release ?: return
        if (job?.isActive == true) return
        val selected = _state.value.selectedSource
        val generation = ++downloadGeneration
        pauseRequested = false
        readyApk = null
        _state.update {
            it.copy(
                downloading = true,
                error = null,
                progress = 0f,
                ready = false,
                phase = "downloading",
                downloadedBytes = 0,
                downloadSize = 0,
            )
        }
        cleanupCache()
        job = viewModelScope.launch {
            try {
                BusinessJobs.io {
                    coroutineContext.ensureActive()
                    val sources = release.sources.sortedBy { if (it.id == selected) 0 else 1 }
                    val bridge = SharedUpdate.current()
                    if (release.deliveryJson != null) {
                        check(bridge != null) { "当前安装包不支持此更新说明，请下载完整安装包" }
                        var lastPhase = ""
                        var lastProgressAt = 0L
                        try {
                            val result =
                                bridge.prepare(
                                    SharedUpdate.Request(
                                        release.deliveryJson,
                                        sources.map { it.url },
                                        release.versionCode.toLong(),
                                        release.sha256,
                                        release.size,
                                    )
                                ) { phase, completed, total, downloaded, detail ->
                                    if (generation != downloadGeneration || pauseRequested) {
                                        bridge.cancel()
                                        return@prepare
                                    }
                                    val progressAt = System.nanoTime()
                                    if (
                                        phase == lastPhase &&
                                            completed < total &&
                                            progressAt - lastProgressAt < 100_000_000L
                                    )
                                        return@prepare
                                    lastPhase = phase
                                    lastProgressAt = progressAt
                                    _state.update {
                                        it.copy(
                                            phase = phase,
                                            source = detail,
                                            progress =
                                                if (total > 0)
                                                    (completed.toFloat() / total).coerceIn(0f, 1f)
                                                else 0f,
                                            downloadedBytes = downloaded,
                                            downloadSize =
                                                if (phase == "downloading") total
                                                else it.downloadSize,
                                        )
                                    }
                                }
                            readyApk = result.file
                            _state.update { it.copy(downloadedBytes = result.downloadedBytes) }
                            return@io
                        } finally {
                            coroutineContext.ensureActive()
                        }
                    }
                    var failure: Exception? = null
                    for (source in sources) {
                        coroutineContext.ensureActive()
                        val target = apk(release, source)
                        if (
                            target.exists() &&
                                runCatching { verify(target, release, source) }.isSuccess
                        ) {
                            readyApk = target
                            return@io
                        }
                        if (target.exists()) check(target.delete()) { "无法清理失效的更新包，请重试" }
                        _state.update { it.copy(source = source.label, progress = 0f) }
                        try {
                            downloadFile(source.url, target, release, source)
                            readyApk = target
                            return@io
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            target.delete()
                            failure = e
                        }
                    }
                    throw failure ?: IllegalStateException("没有可用下载源")
                }
                _state.update { it.copy(downloading = false, ready = true, progress = 1f) }
            } catch (e: SharedUpdate.DeferredException) {
                coroutineContext.ensureActive()
                _state.update {
                    it.copy(
                        downloading = false,
                        phase = "paused",
                        source = e.message ?: "更新已暂停",
                        error = null,
                    )
                }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                _state.update { it.copy(downloading = false, error = e.message ?: "下载失败，请重试") }
            } finally {
                if (generation == downloadGeneration && pauseRequested)
                    _state.update {
                        it.copy(
                            downloading = false,
                            phase = "paused",
                            source = "下载已暂停",
                            error = null,
                        )
                    }
            }
        }
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        release: AppRelease,
        source: UpdateSource,
    ) {
        val partial = File(cacheDir, target.name + ".part")
        val savedPriority = android.os.Process.getThreadPriority(android.os.Process.myTid())
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        var connection: HttpURLConnection? = null
        try {
            // 逐次校验重定向；GitHub 会跳至 release-assets.githubusercontent.com。
            var next = url
            for (redirect in 0..5) {
                connection = open(next)
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: error("下载地址无效")
                    connection.disconnect()
                    next = validatedUpdateUrl(location, next, BuildConfig.INTERNAL_BUILD)
                } else {
                    break
                }
            }
            val active = requireNotNull(connection)
            check(active.responseCode == 200) { "下载失败 (${active.responseCode})，请重试或切换下载源" }
            val length =
                source.size.takeIf { it > 0 }
                    ?: release.size.takeIf { it > 0 }
                    ?: active.contentLengthLong
            active.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val bytes = ByteArray(64 * 1024)
                    var total = 0L
                    var reportedAt = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "安装包大小超出限制" }
                        output.write(bytes, 0, count)
                        val progressAt = System.nanoTime()
                        if (
                            length > 0 &&
                                (total == length || progressAt - reportedAt >= 100_000_000L)
                        ) {
                            reportedAt = progressAt
                            _state.update {
                                it.copy(
                                    progress = (total.toFloat() / length).coerceIn(0f, 1f),
                                    downloadedBytes = total,
                                    downloadSize = length,
                                )
                            }
                        }
                    }
                }
            }
            verify(partial, release, source)
            check(partial.renameTo(target)) { "无法保存安装包" }
        } finally {
            try {
                connection?.disconnect()
                partial.delete()
            } finally {
                android.os.Process.setThreadPriority(savedPriority)
            }
        }
    }

    private fun verify(
        file: File,
        release: AppRelease,
        source: UpdateSource,
    ) {
        val expectedSize = source.size.takeIf { it > 0 } ?: release.size
        if (expectedSize > 0) require(file.length() == expectedSize) { "更新包大小不一致，请重试" }
        val expectedSha = source.sha256.ifBlank { release.sha256 }
        require(sha256Hex(file) == expectedSha) { "更新包校验失败，请重试" }
        val info =
            signingQueryFlags().firstNotNullOfOrNull {
                app.packageManager.getPackageArchiveInfo(file.path, it)
            } ?: error("更新文件不是有效 APK")
        require(info.packageName == app.packageName) { "安装包不属于落弦律" }
        val code =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        require(code == release.versionCode.toLong() && code > installedVersionCode) {
            "安装包版本与更新信息不一致"
        }
        val incoming = signerDigests { pm, flags -> pm.getPackageArchiveInfo(file.path, flags) }
        val current = signerDigests { pm, flags -> pm.getPackageInfo(app.packageName, flags) }
        require(incoming != null && current != null && incoming == current) {
            "安装包签名不一致，无法覆盖安装"
        }
    }

    // 部分 ROM 上归档解析拿不到 signingInfo，回退到 GET_SIGNATURES 重查；
    // 证书按 SHA-256 摘要比对，避免证书字节重编码导致相等性误判。
    private fun signingQueryFlags(): List<Int> =
        if (Build.VERSION.SDK_INT >= 28) {
            listOf(PackageManager.GET_SIGNING_CERTIFICATES, PackageManager.GET_SIGNATURES)
        } else {
            listOf(PackageManager.GET_SIGNATURES)
        }

    private fun signerDigests(query: (PackageManager, Int) -> PackageInfo?): Set<String>? {
        for (flags in signingQueryFlags()) {
            val signers =
                query(app.packageManager, flags)?.let { archive ->
                    if (Build.VERSION.SDK_INT >= 28) {
                        archive.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() }
                            ?: archive.signatures
                    } else {
                        archive.signatures
                    }
                }
            if (!signers.isNullOrEmpty()) {
                return signers.mapTo(HashSet()) { sha256Hex(it.toByteArray()) }
            }
        }
        return null
    }

    private fun sha256Hex(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(bytes)
                if (size < 0) break
                hash.update(bytes, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 清理中断下载，仅保留最新两个 APK。 */
    private fun cleanupCache() {
        runCatching {
            cacheDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            cacheDir
                .listFiles()
                ?.filter { it.extension == "apk" }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(2)
                ?.forEach { it.delete() }
        }
    }

    fun install(activity: Activity) {
        val release = _state.value.release ?: return
        if (!_state.value.ready || _state.value.installing) return
        try {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                _state.update { it.copy(needsPermission = true) }
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${app.packageName}"),
                    )
                )
                return
            }
            val source =
                release.sources.firstOrNull { it.id == _state.value.selectedSource }
                    ?: release.sources.first()
            val file = readyApk ?: apk(release, source)
            _state.update { it.copy(installing = true) }
            // 系统安装前重新认证最终文件；哈希及归档读取不占主线程。
            viewModelScope.launch {
                try {
                    BusinessJobs.io { verify(file, release, source) }
                    val uri = SharedFiles.getUriForFile(app, "${app.packageName}.updates", file)
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                    _state.update {
                        it.copy(needsPermission = false, error = null, installing = false)
                    }
                } catch (failure: Exception) {
                    coroutineContext.ensureActive()
                    _state.update {
                        it.copy(ready = false, installing = false, error = "安装文件验证失败，请重新下载")
                    }
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = "无法打开安装程序：${e.message}") }
        }
    }

    fun onResume(activity: Activity) {
        if (_state.value.needsPermission && activity.packageManager.canRequestPackageInstalls()) {
            install(activity)
        } else if (UpdateAutoCheck.isEnabled(app)) {
            // 自动检查被设置关掉就不查；手动「检查新版本」走 check(manual = true)，不受此约束。
            check()
        }
    }

    fun dismiss() {
        if (_state.value.release?.mandatory == true || _state.value.downloading) return
        _state.update {
            it.copy(release = null, error = null, ready = false, needsPermission = false)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** 仅取消本次运行，保留已认证的断点；再次下载按相同目标恢复。 */
    fun pauseDownload() {
        pauseRequested = true
        SharedUpdate.current()?.cancel()
        job?.cancel()
        _state.update { it.copy(phase = "pausing", source = "正在暂停下载", error = null) }
    }

    private fun updateInstallationId(): String {
        val existing = prefs.getString("delta_installation_id", "") ?: ""
        if (existing.isNotBlank()) return existing
        return java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("delta_installation_id", it).apply()
        }
    }

    override fun onCleared() {
        downloadGeneration++
        SharedUpdate.current()?.cancel()
        job?.cancel()
        super.onCleared()
    }

    /** 仅内部测试版: adb 可主动触发更新弹窗（am broadcast -a app.luoxianlv.DEBUG_TRIGGER_UPDATE）。 */
    fun debugTriggerUpdate() {
        if (!BuildConfig.INTERNAL_BUILD) return
        val code = BuildConfig.VERSION_CODE + 1
        _state.update {
            it.copy(
                checking = false,
                release =
                    AppRelease(
                        versionCode = code,
                        versionName = "${BuildConfig.VERSION_NAME}-demo",
                        sha256 = "0".repeat(64),
                        size = 22L * 1024 * 1024,
                        notes =
                            listOf(
                                ReleaseNoteSection(
                                    "演示更新",
                                    listOf("谱面同步更稳定", "优化 MIDI 渲染性能", "修复已知问题"),
                                )
                            ),
                        mandatory = false,
                        sources = listOf(UpdateSource("oss", "$baseUrl/api/update/oss")),
                    ),
                selectedSource = "oss",
            )
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(validatedUpdateUrl(url, baseUrl, BuildConfig.INTERNAL_BUILD)).openConnection()
                as HttpURLConnection)
            .apply {
                ClientVersion.attach(this)
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Luoxianlv/${BuildConfig.VERSION_NAME}")
            }
}
