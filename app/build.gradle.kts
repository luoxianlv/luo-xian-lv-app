plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.luoxianlv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.luoxianlv"
        minSdk = 26
        targetSdk = 35
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

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("com.google.android.material:material:1.10.0")

    // Compose (M1)：主界面迁移用；material:1.10 暂保留给悬浮窗 View
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
