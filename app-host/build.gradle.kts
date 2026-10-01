import app.luoxianlv.buildlogic.BundleBaseline
import app.luoxianlv.buildlogic.CopyHotConfig

plugins { id("com.android.application") }

// 与旧 :app 及现有 ORG_GRADLE_PROJECT_* 环境变量使用同一组属性。
val releaseSigningProperties =
    listOf("releaseStoreFile", "releaseStorePassword", "releaseKeyAlias", "releaseKeyPassword")
val releaseSigningValues = releaseSigningProperties.associateWith {
    providers.gradleProperty(it).orNull?.takeIf(String::isNotBlank)
}
val hasReleaseSigning = releaseSigningValues.values.all { it != null }
require(hasReleaseSigning || releaseSigningValues.values.all { it == null }) {
    "Incomplete release signing properties: " +
        releaseSigningProperties.filter { releaseSigningValues[it] == null }.joinToString(", ")
}
fun nativeSigningFlag(name: String): Boolean {
    val value = providers.gradleProperty(name).orNull ?: return false
    require(value == "true" || value == "false") { "$name must be true or false" }
    return value == "true"
}
val useNativeDebugSigning = nativeSigningFlag("useDebugSigning")
require(!nativeSigningFlag("nativeRequireReleaseSigning") || hasReleaseSigning) {
    "nativeRequireReleaseSigning requires all four release signing properties"
}

android {
    namespace = "app.luoxianlv.host"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.luoxianlv"
        minSdk = 26
        targetSdk = 37
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 16
        versionName = findProperty("appVersionName") as String? ?: "1.0.9"
        testInstrumentationRunner = "app.luoxianlv.host.NativeAppInstrumentation"
    }
    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            // 保留旧 :app 的相对路径含义，避免迁移时指向另一份 keystore。
            storeFile = rootProject.project(":app").file(releaseSigningValues.getValue("releaseStoreFile")!!)
            storePassword = releaseSigningValues.getValue("releaseStorePassword")
            keyAlias = releaseSigningValues.getValue("releaseKeyAlias")
            keyPassword = releaseSigningValues.getValue("releaseKeyPassword")
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else if (useNativeDebugSigning) {
                // 仅本地安装验收；正式核验脚本明确拒绝 Android Debug 证书。
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    androidResources.noCompress += "apk"
    androidResources.additionalParameters += listOf("--package-id", "0x80")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { implementation(project(":hot-core")) }

androidComponents.onVariants { variant ->
    providers.gradleProperty("hotUpdateConfig").orNull?.let { configPath ->
        val config =
            tasks.register<CopyHotConfig>(
                "copy${variant.name.replaceFirstChar(Char::uppercaseChar)}HotConfig"
            ) {
                this.config.set(rootProject.layout.projectDirectory.file(configPath))
                applicationId.set(variant.applicationId)
                debuggable.set(variant.debuggable)
                output.set(layout.buildDirectory.dir("generated/hotConfig/${variant.name}"))
            }
        variant.sources.assets!!.addGeneratedSourceDirectory(config) { it.output }
    }
    val runtime =
        configurations.create("${variant.name}BaselineRuntime") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
    val business =
        configurations.create("${variant.name}BaselineBusiness") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
    dependencies.add(
        runtime.name,
        dependencies.project(
            mapOf("path" to ":app-runtime", "configuration" to "${variant.name}RuntimeApk")
        ),
    )
    dependencies.add(
        business.name,
        dependencies.project(
            mapOf("path" to ":app-business", "configuration" to "${variant.name}BusinessApk")
        ),
    )
    val baseline =
        tasks.register<BundleBaseline>(
            "bundle${variant.name.replaceFirstChar(Char::uppercaseChar)}Baseline"
        ) {
            runtimeApk.set(layout.file(provider { runtime.singleFile }))
            businessApk.set(layout.file(provider { business.singleFile }))
            output.set(layout.buildDirectory.dir("generated/baseline/${variant.name}"))
            if (
                providers.gradleProperty("nativeBusinessProbe").orNull == "true" ||
                    providers.gradleProperty("nativeRuntimeProbe").orNull == "true"
            ) {
                doFirst { error("在线验收用的新组件不能内置到宿主恢复基线") }
            }
            dependsOn(runtime, business)
        }
    variant.sources.assets!!.addGeneratedSourceDirectory(baseline) { it.output }
}

apply(from = rootProject.file("gradle/native-report.gradle.kts"))
apply(from = rootProject.file("gradle/native-contract.gradle.kts"))
