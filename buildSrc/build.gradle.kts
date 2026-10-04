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

sourceSets.named("main") {
    java.srcDir("../modules/hot-core/src/main/java")
    java.include("app/luoxianlv/buildlogic/**", "app/luoxianlv/hot/StrictJson.java",
        "app/luoxianlv/hot/HotSignatures.java", "app/luoxianlv/hot/HotManifest.java",
        "app/luoxianlv/hot/HostConfigRules.java")
}
sourceSets.named("test") {
    resources.srcDir("../modules/hot-core/src/test/resources")
    resources.include("native/**", "protocol-v1/root.public.json")
}
