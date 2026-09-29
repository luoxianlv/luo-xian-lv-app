plugins { id("com.android.library") }

android {
    namespace = "app.luoxianlv.hot"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "app.luoxianlv.hot.HotCoreInstrumentation"
    }
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file(".local/hot-core-test-assets"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":hot-contract"))
    testImplementation(libs.junit)
}
