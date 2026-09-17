plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.luoxianlv"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.luoxianlv"
        minSdk = 26
        targetSdk = 37
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 5
        versionName = project.findProperty("appVersionName") as String? ?: "1.0.0"

        // 本地模拟器构建指向宿主机上的 Rust 服务；
        // Release 构建用 -PupdateBaseUrl=https://... 覆盖
        val updateBaseUrl = project.findProperty("updateBaseUrl") as String? ?: "https://luoxianlv.com"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (project.hasProperty("releaseStoreFile")) {
                signingConfig = signingConfigs.getByName("release")
            } else if (project.findProperty("useDebugSigning") == "true") {
                signingConfig = signingConfigs.getByName("debug")
            }
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
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
