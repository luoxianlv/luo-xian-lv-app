import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.efs.sdk.plugin.EfsFactory
import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// U-APM 字节码插桩（页面访问 / 启动耗时细分 / 卡顿帧率）：
// 官方 com.efs.sdk.plugin 插件的 EfsExtension 字段是包级私有且没有 setter，Kotlin DSL 无法配置；
// 因此不应用该插件，只复用它提供的 EfsFactory，通过 AGP 公开的 Instrumentation API 自行注册（见文件末尾）。

// 版本号同时供 defaultConfig 与 apmUploadMapping（符号表归档）使用。
val appVersionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 10
val appVersionName = project.findProperty("appVersionName") as String? ?: "1.0.5"

android {
    namespace = "app.luoxianlv"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.luoxianlv"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        // 本地模拟器构建指向宿主机上的 Rust 服务；
        // Release 构建用 -PupdateBaseUrl=https://... 覆盖
        val updateBaseUrl = project.findProperty("updateBaseUrl") as String? ?: "https://luoxianlv-api.admilk.cn"
        require(updateBaseUrl.matches(Regex("https?://[A-Za-z0-9.:-]+"))) { "updateBaseUrl must be an HTTP(S) origin" }
        buildConfigField("String", "UPDATE_BASE_URL", "\"$updateBaseUrl\"")
        val updateSource = project.findProperty("updateSource") as String? ?: "oss"
        require(updateSource in listOf("oss", "github")) { "updateSource must be oss or github" }
        buildConfigField("String", "UPDATE_SOURCE", "\"$updateSource\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        create("release") {
            if (project.hasProperty("releaseStoreFile")) {
                storeFile = file(project.property("releaseStoreFile")!!)
                storePassword = project.property("releaseStorePassword") as String?
                keyAlias = project.property("releaseKeyAlias") as String?
                keyPassword = project.property("releaseKeyPassword") as String?
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (project.hasProperty("releaseStoreFile")) {
                signingConfig = signingConfigs.getByName("release")
            } else if (project.findProperty("useDebugSigning") == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        // 复现调试包：与正式包共存（独立包名），供导出诊断 ZIP 排查手势坐标问题。
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 等价于官方文档里 efs { enable = true; whiteList = ["app.luoxianlv"] } 的效果。
// 帧计算模式与插件自身保持一致（注入方法调用后需要重算帧）。
androidComponents {
    onVariants { variant ->
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
        )
        variant.instrumentation.transformClassesWith(
            EfsFactory::class.java,
            InstrumentationScope.ALL,
        ) { params ->
            params.enable.set(true)
            params.whiteList.set(listOf("app.luoxianlv"))
        }
    }
}

dependencies {
    implementation(libs.material)

    // Compose (M1)：主界面迁移用；material 暂保留给悬浮窗 View
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
    implementation(libs.umeng.common)
    implementation(libs.umeng.asms)
    implementation(libs.umeng.uyumao)
    implementation(libs.umeng.apm)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}

// U-APM 崩溃/卡顿栈还原需要与版本一一对应的 R8 mapping。
// 官方只在控制台提供手动上传（U-APM → 设置 → 符号表），所以这里把 mapping.txt 归档成
// 带版本号的固定文件名（console 直接可传），避免下一次 release 把它覆盖掉；
// 若提供了上传地址与凭据（-PumengSymbolUploadUrl / -PumengSymbolUploadToken）则自动上传。
// 注意：自动上传的请求体/鉴权按常见约定编写，拿到友盟实际上传接口后按文档对齐这几个字段即可。
val umengSymbolUploadUrl = providers.gradleProperty("umengSymbolUploadUrl")
val umengSymbolUploadToken = providers.gradleProperty("umengSymbolUploadToken")

tasks.register("apmUploadMapping") {
    group = "reporting"
    description = "归档 release 的 R8 mapping（版本号命名），并可选上传到 U-APM 符号表"

    val mappingDir = layout.buildDirectory.dir("outputs/mapping/release")
    val versionNameForUpload = appVersionName
    val versionCodeForUpload = appVersionCode

    dependsOn("assembleRelease")

    doLast {
        val mapping = mappingDir.get().file("mapping.txt").asFile
        if (!mapping.isFile) {
            logger.warn("apmUploadMapping: 找不到 ${mapping.path}（release 未开启 minify 时会这样），跳过。")
            return@doLast
        }

        val stamped = mappingDir.get().file("mapping-v$versionNameForUpload+$versionCodeForUpload.txt").asFile
        mapping.copyTo(stamped, overwrite = true)
        logger.lifecycle(
            "apmUploadMapping: 已归档 ${stamped.absolutePath}（versionName=$versionNameForUpload, versionCode=$versionCodeForUpload）",
        )

        val url = umengSymbolUploadUrl.orNull
        val token = umengSymbolUploadToken.orNull
        if (url.isNullOrBlank() || token.isNullOrBlank()) {
            logger.lifecycle(
                "apmUploadMapping: 未配置 umengSymbolUploadUrl / umengSymbolUploadToken，跳过自动上传。" +
                    "请在 U-APM 控制台「设置 → 符号表」为版本 $versionNameForUpload($versionCodeForUpload) 上传上面这个文件。",
            )
            return@doLast
        }

        val boundary = "----apmSymbolBoundary${System.currentTimeMillis()}"
        val conn =
            (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
        conn.outputStream.use { out ->
            fun part(
                name: String,
                value: String,
            ) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            part("versionName", versionNameForUpload)
            part("versionCode", versionCodeForUpload.toString())
            out.write("--$boundary\r\n".toByteArray())
            out.write(
                (
                    "Content-Disposition: form-data; name=\"file\"; filename=\"mapping.txt\"\r\n" +
                        "Content-Type: text/plain\r\n\r\n"
                ).toByteArray(),
            )
            out.write(mapping.readBytes())
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val status = conn.responseCode
        val body =
            (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
        check(status in 200..299) { "apmUploadMapping: 上传失败 HTTP $status $body" }
        logger.lifecycle("apmUploadMapping: 上传成功 HTTP $status $body")
    }
}
