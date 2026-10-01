plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "app.luoxianlv.hot.business"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.luoxianlv.hot.business"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "business-1"
    }
    buildFeatures { compose = true }
    flavorDimensions += "edition"
    productFlavors {
        create("v1") { dimension = "edition" }
        create("v2") { dimension = "edition" }
    }
    androidResources.additionalParameters += listOf("--package-id", "0x7e", "--allow-reserved-package-id")
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":business-ui"))
    compileOnly(project(":hot-contract"))
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.material3)
    compileOnly(libs.activity.compose)
    compileOnly(libs.lifecycle.runtime.compose)
    compileOnly(libs.lifecycle.viewmodel.compose)
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}
