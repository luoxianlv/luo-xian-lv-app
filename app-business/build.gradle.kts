import app.luoxianlv.buildlogic.ExportNativeApk
import app.luoxianlv.buildlogic.LinkBusinessResources
import com.android.build.api.artifact.SingleArtifact

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.luoxianlv"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.luoxianlv.business"
        minSdk = 26
        targetSdk = 37
        versionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 18
        versionName = findProperty("appVersionName") as String? ?: "1.1.0"
        val origin = findProperty("updateBaseUrl") as String? ?: "https://www.luoxianlv.cn"
        require(origin.matches(Regex("https?://[A-Za-z0-9.:-]+")))
        val source = findProperty("updateSource") as String? ?: "oss"
        require(source in listOf("oss", "github"))
        buildConfigField("String", "UPDATE_BASE_URL", "\"$origin\"")
        buildConfigField("String", "UPDATE_SOURCE", "\"$source\"")
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    androidResources.additionalParameters += listOf("--package-id", "0x81")
    buildTypes {
        debug {
            buildConfigField("boolean", "INTERNAL_BUILD", "true")
            versionNameSuffix = "-debug"
        }
        release {
            buildConfigField("boolean", "INTERNAL_BUILD", "false")
            isMinifyEnabled = false
        }
    }
    sourceSets.getByName("main") {
        kotlin.directories.add(rootProject.file("app/src/main/java").path)
        assets.directories.add(rootProject.file("app/src/main/assets").path)
    }
    sourceSets
        .getByName("debug")
        .kotlin
        .directories
        .add(rootProject.file("app/src/debug/java").path)
    sourceSets
        .getByName("release")
        .kotlin
        .directories
        .add(rootProject.file("app/src/release/java").path)
    if (providers.gradleProperty("nativeBusinessProbe").orNull == "true") {
        sourceSets.getByName("debug").java.srcDir("src/hotProbe/java")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents.onVariants { variant ->
    val exported =
        tasks.register<ExportNativeApk>(
            "export${variant.name.replaceFirstChar(Char::uppercaseChar)}Business"
        ) {
            apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            apk.set(layout.buildDirectory.file("native-link/${variant.name}/business.apk"))
        }
    val published =
        configurations.create("${variant.name}BusinessApk") {
            isCanBeConsumed = true
            isCanBeResolved = false
        }
    artifacts.add(published.name, exported.flatMap { it.apk })
    val runtimeApk =
        configurations.create("${variant.name}RuntimeApk") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
    val runtimeSymbols =
        configurations.create("${variant.name}RuntimeSymbols") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
    dependencies.add(
        runtimeApk.name,
        dependencies.project(
            mapOf("path" to ":app-runtime", "configuration" to "${variant.name}RuntimeApk")
        ),
    )
    dependencies.add(
        runtimeSymbols.name,
        dependencies.project(
            mapOf("path" to ":app-runtime", "configuration" to "${variant.name}RuntimeSymbols")
        ),
    )
    val linked =
        tasks.register<LinkBusinessResources>(
            "link${variant.name.replaceFirstChar(Char::uppercaseChar)}RuntimeResources"
        ) {
            source.set(rootProject.layout.projectDirectory.dir("app/src/main/res"))
            symbols.set(layout.file(provider { runtimeSymbols.singleFile }))
            output.set(layout.buildDirectory.dir("generated/native-res/${variant.name}"))
            dependsOn(runtimeApk, runtimeSymbols)
        }
    variant.sources.res!!.addGeneratedSourceDirectory(linked) { it.output }
    variant.androidResources.aaptAdditionalParameters.addAll(
        provider { listOf("-I", runtimeApk.singleFile.absolutePath) }
    )
}

dependencies {
    implementation(project(":business-ui"))
}

// 当前变体真实 SDK JAR 通过 compileOnly 消费；资源链接仍使用运行时 APK / 符号。
apply(from = rootProject.file("gradle/native-report.gradle.kts"))
apply(from = rootProject.file("gradle/native-contract.gradle.kts"))
