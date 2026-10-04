package app.luoxianlv.wallpaper

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.luoxianlv.shared.AppStorage
import java.io.File
import java.util.UUID
import org.json.JSONObject

/** 复制原始项目到应用专属外部目录；导入失败不改变当前选择。 */
object WallpaperProjectStore {
    private fun prefs(context: Context) =
        context.getSharedPreferences("practice_wallpaper", Context.MODE_PRIVATE)

    fun hasBundled(context: Context) =
        DefaultWallpaper.installed(context) || hasLegacyBundled(context)

    fun hasLegacyBundled(context: Context) =
        context.assets.list("default-wallpaper")?.contains("scene.pkg") == true

    fun selectedId(context: Context): String? = prefs(context).getString("project", null)

    fun soundEnabled(context: Context): Boolean = prefs(context).getBoolean("sound", false)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("sound", enabled).apply()
    }

    fun minute(context: Context): Int? =
        prefs(context).getInt("minute", -1).takeIf { it in 0..1439 }

    fun setMinute(context: Context, minute: Int?) {
        require(minute == null || minute in 0..1439)
        prefs(context).edit().putInt("minute", minute ?: -1).apply()
    }

    fun current(context: Context): File? {
        val id = selectedId(context) ?: DefaultWallpaper.ID
        if (!id.matches(Regex("[a-f0-9-]{36}"))) return null
        return AppStorage.wallpaperRoots(context)
            .map { File(it, id) }
            .firstOrNull { File(it, ".root").isFile }
    }

    fun reset(context: Context) {
        prefs(context).edit().remove("project").apply()
    }

    fun scenePackage(root: File): File? {
        val metadata = WallpaperArchive.resolve(root, "project.json")
        val declared = runCatching {
            JSONObject(metadata!!.readText().removePrefix("\uFEFF")).optString("file")
        }
            .getOrDefault("")
        val candidates =
            (if (declared.endsWith(".pkg", true)) listOf(declared) else emptyList()) +
                listOf("scene.pkg", "scenes/scene.pkg", "gifscene.pkg")
        return candidates.firstNotNullOfOrNull { WallpaperArchive.resolve(root, it) }
            ?: root.listFiles()?.singleOrNull { it.isFile && it.extension.equals("pkg", true) }
    }

    fun import(context: Context, uri: Uri, tree: Boolean, checkpoint: () -> Unit = {}): String {
        val id = UUID.randomUUID().toString()
        val staging = File(AppStorage.wallpapers(context), id).apply { mkdirs() }
        val budget = WallpaperArchive.Budget(staging, checkpoint)
        var archive: File? = null
        try {
            val resolver = context.contentResolver
            if (tree) {
                fun walk(documentId: String, prefix: String, depth: Int) {
                    require(depth <= 32) { "项目目录层级过深" }
                    val children =
                        DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId)
                    resolver
                        .query(
                            children,
                            arrayOf("document_id", "_display_name", "mime_type"),
                            null,
                            null,
                            null,
                        )
                        ?.use { cursor ->
                            while (cursor.moveToNext()) {
                                val child = cursor.getString(0)
                                val name = cursor.getString(1)
                                require(
                                    name.isNotEmpty() &&
                                        '/' !in name &&
                                        '\\' !in name &&
                                        name != ".." &&
                                        name != "."
                                )
                                val path = prefix + name
                                if (
                                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                                ) {
                                    budget.target(path, true)
                                    walk(child, "$path/", depth + 1)
                                } else {
                                    val dest = budget.target(path)
                                    requireNotNull(
                                            resolver.openInputStream(
                                                DocumentsContract.buildDocumentUriUsingTree(
                                                    uri,
                                                    child,
                                                )
                                            )
                                        )
                                        .use { budget.copy(it, dest) }
                                }
                            }
                        } ?: error("无法读取项目文件夹")
                }
                walk(DocumentsContract.getTreeDocumentId(uri), "", 0)
            } else {
                val copiedArchive =
                    File.createTempFile("wallpaper-", ".zip", AppStorage.imports(context))
                archive = copiedArchive
                val archiveBudget = WallpaperArchive.Budget(AppStorage.imports(context), checkpoint)
                requireNotNull(resolver.openInputStream(uri)).use {
                    archiveBudget.copy(it, copiedArchive)
                }
                WallpaperArchive.extract(copiedArchive, staging, checkpoint)
            }
            val projects =
                staging
                    .walkTopDown()
                    .filter {
                        it.isFile &&
                            it.name.equals("project.json", true) &&
                            "__MACOSX" !in it.relativeTo(staging).path.split(File.separatorChar)
                    }
                    .toList()
            require(projects.isNotEmpty()) { "ZIP 中没有 project.json，请压缩完整的壁纸项目文件夹" }
            val depth = projects.minOf {
                it.relativeTo(staging).path.split(File.separatorChar).size
            }
            val roots = projects.filter {
                it.relativeTo(staging).path.split(File.separatorChar).size == depth
            }
            require(roots.size == 1) { "ZIP 包含多个壁纸项目，请分别打包" }
            val projectFile = roots.single()
            require(projectFile.length() <= 1024 * 1024) { "project.json 过大" }
            val project = JSONObject(projectFile.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
            val type = project.optString("type").lowercase(java.util.Locale.ROOT)
            require(type in setOf("scene", "video", "web", "image", "gif")) {
                "不支持此壁纸类型：$type。Windows 程序类壁纸无法在 Android 运行"
            }
            val projectRoot = projectFile.parentFile!!
            if (type == "scene") {
                val pkg = scenePackage(projectRoot)
                require(pkg != null && pkg.length() in 16..256L * 1024 * 1024) {
                    "缺少 scene.pkg，或场景包超过 256 MB"
                }
                pkg.inputStream().use {
                    val header = ByteArray(12)
                    require(
                        it.read(header) == 12 &&
                            header.take(4) == listOf<Byte>(8, 0, 0, 0) &&
                            String(header, 4, 4, Charsets.US_ASCII) == "PKGV"
                    ) {
                        "scene.pkg 格式无效"
                    }
                }
            } else {
                val entry =
                    project.optString("file").ifBlank { if (type == "web") "index.html" else "" }
                require(WallpaperArchive.resolve(projectRoot, entry) != null) { "项目入口文件不存在：$entry" }
            }
            checkpoint()
            // ZIP 可含一层外目录；保留原文件，并记录项目根的相对路径。
            require(!File(staging, ".root").exists()) { "项目包含保留文件名" }
            File(staging, ".root")
                .writeText(projectFile.parentFile!!.relativeTo(staging).path, Charsets.UTF_8)
            check(prefs(context).edit().putString("project", id).commit()) { "无法保存项目选择" }
            app.luoxianlv.diagnostics.AppLog.i(
                "壁纸",
                "导入成功：项目=$id；类型=$type；目录=${staging.absolutePath}",
            )
            return project.optString("title", "壁纸项目")
        } catch (error: Exception) {
            // 失败只删除本次新建的 UUID 目录，不触碰源文件和已有项目。
            app.luoxianlv.diagnostics.AppLog.w("壁纸", "导入失败：项目=$id", error)
            staging.deleteRecursively()
            throw error
        } finally {
            archive?.delete()
        }
    }

    data class Entry(val id: String?, val title: String, val root: File?)

    fun entries(context: Context): List<Entry> {
        val defaultRoot =
            AppStorage.wallpaperRoots(context)
                .map { File(it, DefaultWallpaper.ID) }
                .firstOrNull { File(it, ".root").isFile }
        val defaults =
            listOf(
                Entry(
                    null,
                    if (defaultRoot != null) DefaultWallpaper.TITLE
                    else if (hasLegacyBundled(context)) "窗旁の伊蕾娜" else "默认背景",
                    defaultRoot,
                )
            )
        val imported =
            AppStorage.wallpaperRoots(context)
                .flatMap { it.listFiles().orEmpty().toList() }
                .filter { it.name != DefaultWallpaper.ID && File(it, ".root").isFile }
                .distinctBy { it.name }
                .mapNotNull { folder ->
                    if (
                        !folder.name.matches(Regex("[a-f0-9-]{36}")) ||
                            !File(folder, ".root").isFile
                    )
                        return@mapNotNull null
                    runCatching {
                        val root = File(folder, File(folder, ".root").readText()).canonicalFile
                        require(
                            root == folder.canonicalFile ||
                                root.path.startsWith(folder.canonicalPath + File.separator)
                        )
                        val metadata =
                            WallpaperArchive.resolve(root, "project.json") ?: return@mapNotNull null
                        Entry(
                            folder.name,
                            JSONObject(metadata.readText().removePrefix("\uFEFF"))
                                .optString("title", "壁纸项目"),
                            root,
                        )
                    }
                        .getOrNull()
                }
                .sortedByDescending { it.root?.lastModified() ?: 0L }
        return defaults + imported
    }

    fun select(context: Context, entry: Entry) {
        if (entry.id == null) reset(context)
        else {
            require(entries(context).any { it.id == entry.id }) { "项目不存在" }
            check(prefs(context).edit().putString("project", entry.id).commit())
        }
    }

    fun preview(root: File): File? {
        val declared = runCatching {
            JSONObject(
                    WallpaperArchive.resolve(root, "project.json")!!.readText()
                        .removePrefix("\uFEFF")
                )
                .optString("preview")
        }
            .getOrDefault("")
        return (listOf(declared, "preview.gif", "preview.jpg", "preview.png", "preview.webp"))
            .firstNotNullOfOrNull {
                WallpaperArchive.resolve(root, it)
            }
    }

    fun root(context: Context): File? =
        current(context)?.let { folder ->
            val relative = File(folder, ".root").takeIf { it.isFile }?.readText().orEmpty()
            File(folder, relative).canonicalFile.takeIf {
                it == folder.canonicalFile ||
                    it.path.startsWith(folder.canonicalPath + File.separator)
            }
        }
}
