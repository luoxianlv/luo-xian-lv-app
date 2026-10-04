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
)

/** Activity 级更新状态，由前台检查、关于页和唯一弹窗宿主共享。 */
class AppUpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = Kv.of(app, "app_updates")
    private val _state = MutableStateFlow(AppUpdateState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private val baseUrl = BuildConfig.UPDATE_BASE_URL.trimEnd('/')
    private val cacheDir = AppStorage.updates(app)

    init {
        cleanupCache()
    }

    private fun apk(
        release: AppRelease,
        source: UpdateSource,
    ) = File(cacheDir, "${release.versionCode}-${source.sha256.ifBlank { release.sha256 }}.apk")

    fun check(manual: Boolean = false, force: Boolean = false) {
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
                        open("$baseUrl/api/update/stable?versionCode=${BuildConfig.VERSION_CODE}")
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
                            BuildConfig.VERSION_CODE,
                            baseUrl,
                            BuildConfig.UPDATE_SOURCE,
                            BuildConfig.INTERNAL_BUILD,
                        )
                    } finally {
                        connection.disconnect()
                    }
                }
                prefs.edit().putLong("last_check", now).apply()
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
        _state.update { it.copy(downloading = true, error = null, progress = 0f, ready = false) }
        cleanupCache()
        job = viewModelScope.launch {
            try {
                BusinessJobs.io {
                    val sources = release.sources.sortedBy { if (it.id == selected) 0 else 1 }
                    var failure: Exception? = null
                    for (source in sources) {
                        coroutineContext.ensureActive()
                        val target = apk(release, source)
                        if (
                            target.exists() &&
                                runCatching { verify(target, release, source) }.isSuccess
                        )
                            return@io
                        if (target.exists()) check(target.delete()) { "无法清理失效的更新包，请重试" }
                        _state.update { it.copy(source = source.label, progress = 0f) }
                        try {
                            downloadFile(source.url, target, release, source)
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
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                _state.update { it.copy(downloading = false, error = e.message ?: "下载失败，请重试") }
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
            val progress = DownloadProgressThrottle()
            active.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val bytes = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(bytes)
                        if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "安装包大小超出限制" }
                        output.write(bytes, 0, count)
                        if (length > 0 && progress.shouldPublish(System.nanoTime()))
                            _state.update {
                                it.copy(progress = (total.toFloat() / length).coerceIn(0f, 1f))
                            }
                    }
                }
            }
            verify(partial, release, source)
            check(partial.renameTo(target)) { "无法保存安装包" }
        } finally {
            connection?.disconnect()
            partial.delete()
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
        require(code == release.versionCode.toLong() && code > BuildConfig.VERSION_CODE) {
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
        if (!_state.value.ready) return
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
            val uri =
                SharedFiles.getUriForFile(app, "${app.packageName}.updates", apk(release, source))
            activity.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            _state.update { it.copy(needsPermission = false, error = null) }
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
