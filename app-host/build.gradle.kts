import app.luoxianlv.buildlogic.BundleBaseline

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
            dependsOn(runtime, business)
        }
    variant.sources.assets!!.addGeneratedSourceDirectory(baseline) { it.output }
}
