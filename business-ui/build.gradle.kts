plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "app.luoxianlv.business.ui"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.material3)
    compileOnly(libs.compose.material.icons.extended)
    compileOnly(libs.activity.compose)
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}
