import app.luoxianlv.buildlogic.BundleBaseline
import app.luoxianlv.buildlogic.CopyHotConfig

plugins { id("com.android.application") }

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
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release { isMinifyEnabled = false }
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
