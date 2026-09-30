import app.luoxianlv.buildlogic.ExportNativeApk
import com.android.build.api.artifact.SingleArtifact

plugins { id("com.android.application") }

android {
    namespace = "app.luoxianlv.runtime"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.luoxianlv.runtime"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "runtime-1"
    }
    // 公用控件是资源基础；0x7f 引用在 Android 主题解析中保持绝对地址。
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    if (providers.gradleProperty("nativeRuntimeProbe").orNull == "true") {
        sourceSets.getByName("debug").java.srcDir("src/hotProbe/java")
    }
}

androidComponents.onVariants { variant ->
    val exported =
        tasks.register<ExportNativeApk>(
            "export${variant.name.replaceFirstChar(Char::uppercaseChar)}Runtime"
        ) {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            apk.set(layout.buildDirectory.file("native-link/${variant.name}/runtime.apk"))
        }
    val apkLink =
        configurations.create("${variant.name}RuntimeApk") {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
    val symbols =
        configurations.create("${variant.name}RuntimeSymbols") {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
    artifacts.add(apkLink.name, exported.flatMap { it.apk })
    artifacts.add(symbols.name, variant.artifacts.get(SingleArtifact.RUNTIME_SYMBOL_LIST))
}

dependencies {
    implementation(libs.material)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.fastkv)
    implementation(libs.kotlin.stdlib)
    releaseImplementation(libs.umeng.common)
    releaseImplementation(libs.umeng.asms)
    releaseImplementation(libs.umeng.uyumao)
    releaseImplementation(libs.umeng.apm)
}

apply(from = rootProject.file("gradle/native-report.gradle.kts"))
