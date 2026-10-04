plugins { id("com.android.library") }

android {
    namespace = "app.luoxianlv.update"
    compileSdk = 37
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "app.luoxianlv.update.UpdateInstrumentation"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { aidl = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
