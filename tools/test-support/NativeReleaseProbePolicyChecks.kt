fun main() {
    val allowed = mapOf("nativeReleaseProbe" to "true", "useDebugSigning" to "true", "nativeOptimize" to "true")
    val signingNames = listOf("releaseStoreFile", "releaseStorePassword", "releaseKeyAlias", "releaseKeyPassword")
    var passed = 0
    fun accept(values: Map<String, String?>) {
        requireNativeReleaseProbePolicy(values, false)
        passed++
    }
    fun reject(values: Map<String, String?>, hotConfigPresent: Boolean = false) {
        try {
            requireNativeReleaseProbePolicy(values, hotConfigPresent)
        } catch (expected: IllegalArgumentException) {
            check(!expected.message.orEmpty().contains("fixture-not-a-real-secret")) { "Gate exposed a property value" }
            passed++
            return
        }
        error("Unsafe probe policy unexpectedly accepted")
    }
    accept(allowed)
    accept(allowed + signingNames.associateWith { "" })
    accept(allowed + ("nativeRequireReleaseSigning" to "false"))
    for (name in listOf("nativeReleaseProbe", "useDebugSigning", "nativeOptimize")) {
        reject(allowed - name)
        reject(allowed + (name to "false"))
    }
    for (name in signingNames) {
        reject(allowed + (name to "fixture-not-a-real-secret"))
        reject(allowed + (name to " "))
    }
    reject(allowed, true) // Presence is forbidden even when the configured file path is empty.
    reject(allowed + ("nativeRequireReleaseSigning" to "true"))
    reject(allowed + ("nativeRequireReleaseSigning" to ""))
    println("NativeReleaseProbePolicyChecks: $passed passed")
}
