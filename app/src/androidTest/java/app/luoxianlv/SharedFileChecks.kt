package app.luoxianlv

import android.app.Instrumentation
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import androidx.core.content.FileProvider
import app.luoxianlv.hot.contract.SharedFiles
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/** 对照旧地址实际读取，验证安装/诊断分享兼容与路径、写入边界。 */
internal fun Instrumentation.checkSharedFiles() {
    val context = targetContext
    val authority = context.packageName + ".updates"
    val resolver = context.contentResolver
    val id = UUID.randomUUID().toString()
    val created = mutableListOf<File>()
    fun rejected(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(
            failure is SecurityException ||
                failure is IllegalArgumentException ||
                failure is UnsupportedOperationException ||
                failure is FileNotFoundException
        ) {
            "应拒绝文件操作，实际结果：$failure"
        }
    }
    try {
        val roots =
            listOf(
                File(context.cacheDir, "updates"),
                File(context.filesDir, "updates"),
                File(context.filesDir, "diagnostics"),
                File(context.getExternalFilesDir(null), "updates"),
                File(context.getExternalFilesDir(null), "diagnostics"),
            )
        roots.forEach { root ->
            root.mkdirs()
            val file = File(root, "中文 空格 #$id.APK").also { created += it }
            val bytes = "分享原始字节-$id".toByteArray(Charsets.UTF_8)
            file.writeBytes(bytes)
            val uri = SharedFiles.getUriForFile(context, authority, file)
            check(uri == FileProvider.getUriForFile(context, authority, file)) { "旧分享地址发生变化" }
            check(resolver.getType(uri) == "application/vnd.android.package-archive")
            resolver.openInputStream(uri)!!.use { check(it.readBytes().contentEquals(bytes)) }
            resolver
                .query(
                    uri,
                    arrayOf(OpenableColumns.SIZE, "unknown", OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )!!
                .use {
                    check(it.moveToFirst() && it.columnCount == 2)
                    check(it.getLong(0) == bytes.size.toLong() && it.getString(1) == file.name)
                }
            listOf("w", "rw", "rwt", "wa").forEach { mode ->
                rejected { resolver.openFileDescriptor(uri, mode)?.close() }
            }
            rejected { resolver.delete(uri, null, null) }
            check(file.readBytes().contentEquals(bytes)) { "被拒绝的操作修改了原文件" }
        }
        val outside = File(context.filesDir, "outside-share-$id.txt").also { created += it }
        outside.writeText("不应共享", Charsets.UTF_8)
        rejected { SharedFiles.getUriForFile(context, authority, outside) }
        rejected { SharedFiles.getUriForFile(context, authority, roots[1]) }
        listOf(
                "/fallback_updates/%2e%2e/${outside.name}",
                "/fallback_updates/%2F${outside.absolutePath}",
                "/fallback_updates/..%2Fupdates-other/$id",
                "/fallback_updates/.",
                "/fallback_updates/",
                "/missing/${outside.name}",
            )
            .forEach { path ->
                rejected {
                    resolver.openInputStream(Uri.parse("content://$authority$path"))?.close()
                }
            }
        val link = File(roots[1], "escape-$id.txt").also { created += it }
        Os.symlink(outside.absolutePath, link.absolutePath)
        rejected { SharedFiles.getUriForFile(context, authority, link) }
        rejected {
            resolver
                .openInputStream(Uri.parse("content://$authority/fallback_updates/${link.name}"))
                ?.close()
        }
        val directory =
            File(roots[1], "directory-$id").also {
                created += it
                it.mkdirs()
            }
        rejected {
            resolver
                .openInputStream(SharedFiles.getUriForFile(context, authority, directory))
                ?.close()
        }
        rejected {
            SharedFiles.resolve(
                context,
                Uri.parse("content://different.updates/fallback_updates/a"),
            )
        }
    } finally {
        // 仅删除本检查创建的文件/空目录，不递归跟随符号链接。
        created.asReversed().forEach { it.delete() }
    }
}
