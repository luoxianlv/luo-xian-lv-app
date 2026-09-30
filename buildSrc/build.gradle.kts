plugins { java }

repositories { mavenCentral() }

val sdkTestKotlinVersion = Regex("(?m)^kotlin\\s*=\\s*\"([^\"]+)\"")
    .find(file("../gradle/libs.versions.toml").readText(Charsets.UTF_8))?.groupValues?.get(1)
    ?: error("SDK 测试缺少项目 Kotlin 版本")

dependencies {
    implementation(gradleApi())
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$sdkTestKotlinVersion")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
