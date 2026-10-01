import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.kotlin.dsl.configure

buildscript {
    dependencies {
        classpath(files(rootProject.buildscript.configurations.getByName("classpath")))
    }
}

check(project.path == ":app-host") { "Release probe belongs only to app-host" }
// PROBE_POLICY_BEGIN：独立检查直接编译本段实际 gate，不启动 Gradle 或读取环境签名值。
fun requireNativeReleaseProbePolicy(values: Map<String, String?>, hotConfigPresent: Boolean) {
    require(values["nativeReleaseProbe"] == "true") { "Release probe must be explicitly enabled" }
    require(values["useDebugSigning"] == "true") { "Release probe requires explicit useDebugSigning=true" }
    require(values["nativeOptimize"] == "true") { "Release probe requires nativeOptimize=true" }
    val signingNames = listOf("releaseStoreFile", "releaseStorePassword", "releaseKeyAlias", "releaseKeyPassword")
    val configured = signingNames.filter { !values[it].isNullOrEmpty() }
    require(configured.isEmpty()) { "Release probe forbids configured release signing properties: " + configured.joinToString(", ") }
    require(!hotConfigPresent) { "Release probe forbids hotUpdateConfig" }
    require(values["nativeRequireReleaseSigning"].let { it == null || it == "false" }) {
        "Release probe cannot require formal release signing"
    }
}
// PROBE_POLICY_END
val probePropertyNames = listOf("nativeReleaseProbe", "useDebugSigning", "nativeOptimize", "nativeRequireReleaseSigning",
    "releaseStoreFile", "releaseStorePassword", "releaseKeyAlias", "releaseKeyPassword")
requireNativeReleaseProbePolicy(probePropertyNames.associateWith { providers.gradleProperty(it).orNull },
    providers.gradleProperty("hotUpdateConfig").isPresent)

extensions.configure<ApplicationAndroidComponentsExtension> {
    finalizeDsl { android ->
        android.testBuildType = "release"
        android.defaultConfig.testApplicationId = "app.luoxianlv.releaseprobe.test"
        android.defaultConfig.testInstrumentationRunner = "app.luoxianlv.host.NativeReleaseInstrumentation"
        android.buildTypes.getByName("release").apply {
            applicationIdSuffix = ".releaseprobe"
            versionNameSuffix = "-releaseprobe"
            isDebuggable = false
            signingConfig = android.signingConfigs.getByName("debug")
        }
        // 原有 androidTest 源码/manifest 引用 Debug 包与未混淆内部类，不能带入此 profile。
        android.sourceSets.getByName("androidTest").setRoot("src/releaseProbeAndroidTest")
        android.sourceSets.getByName("release").manifest.srcFile("src/releaseProbe/AndroidManifest.xml")
    }
}
