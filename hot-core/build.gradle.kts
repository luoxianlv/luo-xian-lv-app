plugins { id("com.android.library") }

android {
    namespace = "app.luoxianlv.hot"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "app.luoxianlv.hot.HotCoreInstrumentation"
    }
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { testImplementation(libs.junit) }
