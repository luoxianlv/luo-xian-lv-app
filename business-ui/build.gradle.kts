plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "app.luoxianlv.business.ui"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "app.luoxianlv.business.ui.PageStateInstrumentation"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    compileOnly(project(":hot-contract"))
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.material3)
    compileOnly(libs.compose.material.icons.extended)
    compileOnly(libs.activity.compose)
    compileOnly(libs.lifecycle.runtime.compose)
    compileOnly(libs.lifecycle.viewmodel.compose)
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    androidTestImplementation(project(":hot-contract"))
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.material3)
    androidTestImplementation(libs.activity.compose)
    androidTestImplementation(libs.lifecycle.runtime.compose)
    androidTestImplementation(libs.lifecycle.viewmodel.compose)
}
