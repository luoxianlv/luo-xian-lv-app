package app.luoxianlv.diagnostics

import java.io.File

internal object DiagnosticRetention {
    const val MAX_BYTES = 50L * 1024 * 1024

    /** 仅清理诊断文件，不删除下载的更新 APK。 */
    fun trim(logDirectory: File, exportDirectory: File, limit: Long = MAX_BYTES) {
        val files =
            logDirectory.walkTopDown().filter { it.isFile }.toList() +
                exportDirectory
                    .listFiles { file ->
                        file.isFile &&
                            file.name.startsWith("luoxianlv-debug-") &&
                            file.extension == "zip"
                    }
                    .orEmpty()
        var bytes = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (bytes <= limit) break
            val size = file.length()
            if (file.delete()) bytes -= size
        }
    }
}
