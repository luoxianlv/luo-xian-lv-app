plugins { id("com.android.application") }
android {
    namespace = "app.luoxianlv.hot.runtime"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.luoxianlv.hot.runtime"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "runtime-1"
    }
    androidResources.additionalParameters += listOf("--package-id", "0x7d", "--allow-reserved-package-id")
    buildTypes {
        release {
            isMinifyEnabled = providers.gradleProperty("hotRuntimeMinify").orNull == "true"
            isShrinkResources = isMinifyEnabled
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
}
